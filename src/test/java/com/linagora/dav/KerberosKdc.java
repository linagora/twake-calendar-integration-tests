/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.dav;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;

import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * An embedded Kerberos KDC (Apache Kerby) issuing the tickets the tests send to esn-sabre.
 *
 * esn-sabre never talks to it: nginx validates the tickets offline, with the keytab of
 * {@link #SABRE_SERVICE_PRINCIPAL} ({@link #sabreKeytabBase64()}).
 */
public class KerberosKdc implements AutoCloseable {
    public static final String REALM = "TWAKE.TEST";
    // Any host name works: the client names the service itself, whatever URL it connects to.
    public static final String SABRE_SERVICE_PRINCIPAL = "HTTP/esn-sabre.twake.test";
    // Another service whose key is also in the keytab of Sabre, like a keytab shared by the services of a host.
    public static final String NEIGHBOUR_SERVICE_PRINCIPAL = "HTTP/neighbour.twake.test";

    private static final Oid SPNEGO = oid("1.3.6.1.5.5.2");
    private static final Oid KRB5_PRINCIPAL_NAME = oid("1.2.840.113554.1.2.2.1");

    public static KerberosKdc start() throws Exception {
        return new KerberosKdc(Files.createTempDirectory("twake-calendar-kerberos-"));
    }

    private final Path workDirectory;
    private final SimpleKdcServer kdc;

    private KerberosKdc(Path workDirectory) throws Exception {
        this.workDirectory = workDirectory;
        this.kdc = new SimpleKdcServer();
        kdc.setWorkDir(workDirectory.toFile());
        kdc.setKdcRealm(REALM);
        kdc.setKdcHost("127.0.0.1");
        kdc.setAllowUdp(false);
        kdc.setKdcTcpPort(availablePort());
        // Also points java.security.krb5.conf to the KDC, for the clients of this JVM.
        kdc.init();
        kdc.start();
        createPrincipal(SABRE_SERVICE_PRINCIPAL);
        // exportPrincipal appends to an existing keytab
        kdc.createPrincipal(NEIGHBOUR_SERVICE_PRINCIPAL + "@" + REALM);
        kdc.exportPrincipal(NEIGHBOUR_SERVICE_PRINCIPAL + "@" + REALM, keytab(SABRE_SERVICE_PRINCIPAL).toFile());
    }

    /**
     * Creates a principal of {@link #REALM}, "alice" or "HTTP/host", and exports its keytab.
     */
    public void createPrincipal(String name) throws Exception {
        String principal = name + "@" + REALM;
        kdc.createPrincipal(principal);
        kdc.exportPrincipal(principal, keytab(name).toFile());
    }

    /**
     * The keytab of Sabre: the keys of {@link #SABRE_SERVICE_PRINCIPAL} and {@link #NEIGHBOUR_SERVICE_PRINCIPAL}.
     */
    public String sabreKeytabBase64() throws IOException {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(keytab(SABRE_SERVICE_PRINCIPAL)));
    }

    /**
     * What "kinit" then "curl --negotiate" send: logs the client in with its keytab, gets a
     * ticket for the service and returns the "Authorization" header carrying it.
     *
     * A token holds a timestamped authenticator: get a new one for every request.
     */
    public String negotiateHeader(String client, String service) throws Exception {
        LoginContext login = new LoginContext("", null, null, keytabLogin(client + "@" + REALM, keytab(client)));
        login.login();
        try {
            byte[] token = Subject.callAs(login.getSubject(), () -> {
                GSSManager manager = GSSManager.getInstance();
                GSSName serviceName = manager.createName(service + "@" + REALM, KRB5_PRINCIPAL_NAME);
                GSSContext context = manager.createContext(serviceName, SPNEGO, null, GSSContext.DEFAULT_LIFETIME);
                try {
                    return context.initSecContext(new byte[0], 0, 0);
                } finally {
                    context.dispose();
                }
            });
            return "Negotiate " + Base64.getEncoder().encodeToString(token);
        } finally {
            login.logout();
        }
    }

    @Override
    public void close() throws Exception {
        kdc.stop();
    }

    private Path keytab(String name) {
        return workDirectory.resolve(name.replace('/', '_') + ".keytab");
    }

    private static Configuration keytabLogin(String principal, Path keytab) {
        Map<String, String> options = Map.of(
            "principal", principal,
            "useKeyTab", "true",
            "keyTab", keytab.toAbsolutePath().toString(),
            "doNotPrompt", "true",
            "isInitiator", "true",
            "useTicketCache", "false",
            "refreshKrb5Config", "true");
        return new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[] {
                    new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options)
                };
            }
        };
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Oid oid(String value) {
        try {
            return new Oid(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
