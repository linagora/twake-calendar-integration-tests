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

import java.util.Hashtable;
import java.util.Map;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;

/**
 * The LDAP directory of the test stack (ldap_populate.ldif), written as its administrator.
 */
public class LdapDirectory {
    public static final String USERS = "ou=users,dc=open-paas.org,dc=lng";

    private static final String ADMIN_DN = "cn=admin,dc=open-paas.org,dc=lng";
    private static final String ADMIN_PASSWORD = "admin";

    public static LdapDirectory of(DockerTwakeCalendarSetup setup) {
        return new LdapDirectory("ldap://" + setup.getHost(DockerService.LDAP) + ":" + setup.getPort(DockerService.LDAP));
    }

    private final String url;

    private LdapDirectory(String url) {
        this.url = url;
    }

    /**
     * Adds the person "uid={uid},{parent}", as in ldap_populate.ldif.
     */
    public void addPerson(String parent, String uid, String mail) throws NamingException {
        Attributes attributes = new BasicAttributes(true);
        attributes.put(new BasicAttribute("objectClass", "inetOrgPerson"));
        attributes.put("uid", uid);
        attributes.put("cn", uid);
        attributes.put("sn", uid);
        attributes.put("mail", mail);
        createEntry("uid=" + uid + "," + parent, attributes);
    }

    /**
     * Adds the organizational unit "ou={ou},{parent}" and returns its DN.
     */
    public String addOrganizationalUnit(String parent, String ou) throws NamingException {
        Attributes attributes = new BasicAttributes(true);
        attributes.put(new BasicAttribute("objectClass", "organizationalUnit"));
        attributes.put("ou", ou);
        String dn = "ou=" + ou + "," + parent;
        createEntry(dn, attributes);
        return dn;
    }

    private void createEntry(String dn, Attributes attributes) throws NamingException {
        DirContext context = new InitialDirContext(new Hashtable<>(Map.of(
            Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory",
            Context.PROVIDER_URL, url,
            Context.SECURITY_AUTHENTICATION, "simple",
            Context.SECURITY_PRINCIPAL, ADMIN_DN,
            Context.SECURITY_CREDENTIALS, ADMIN_PASSWORD)));
        try {
            context.createSubcontext(dn, attributes).close();
        } finally {
            context.close();
        }
    }
}
