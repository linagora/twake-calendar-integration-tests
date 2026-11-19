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

package com.linagora.dav.sabrev4_7;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.xmlunit.assertj3.XmlAssert;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtensionV4_7;
import com.linagora.dav.KerberosKdc;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.response.Response;

/**
 * Kerberos (SPNEGO) authentication, https://github.com/linagora/esn-sabre/issues/483.
 *
 * Tickets come from an embedded KDC, realm TWAKE.TEST, mapped to open-paas.org: the
 * principal "{local part}@TWAKE.TEST" authenticates the user "{local part}@open-paas.org".
 */

public class SabreV4KerberosTest {
    private static final String PROPFIND_CURRENT_USER_PRINCIPAL = """
        <d:propfind xmlns:d="DAV:">
          <d:prop>
             <d:current-user-principal />
          </d:prop>
        </d:propfind>""";

    private static KerberosKdc kdc;
    private static DockerTwakeCalendarExtensionV4_7 extension;

    private OpenPaasUser user;
    private String principal;
    // Another user, without ticket: the owner of the calendars the user tries to access
    private OpenPaasUser alice;
    private CalDavClient calDavClient;

    @BeforeAll
    static void startKdcAndSabre() throws Exception {
        kdc = KerberosKdc.start();
        extension = DockerTwakeCalendarExtensionV4_7.withSabreEnvironment(Map.of(
            "KERBEROS_ENABLED", "true",
            "KERBEROS_SERVICE_PRINCIPAL", KerberosKdc.SABRE_SERVICE_PRINCIPAL + "@" + KerberosKdc.REALM,
            "KERBEROS_REALM_DOMAINS", KerberosKdc.REALM + ":open-paas.org",
            "KERBEROS_KEYTAB_BASE64", kdc.sabreKeytabBase64()));
    }

    @AfterAll
    static void stopKdcAndSabre() throws Exception {
        if (extension != null) {
            extension.getDockerTwakeCalendarSetupSingleton().stop();
        }
        if (kdc != null) {
            kdc.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        RestAssured.reset();
        RestAssured.requestSpecification = extension.davRequestSpecification()
            .contentType("application/xml");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        user = extension.newTestUser();
        principal = StringUtils.substringBefore(user.email(), "@");
        kdc.createPrincipal(principal);

        alice = extension.newTestUser();
        calDavClient = new CalDavClient(extension.davHttpClient());
    }

    @Test
    void ticketShouldAuthenticateTheUserOfTheMappedDomain() throws Exception {
        // Given a ticket of "{local part}@TWAKE.TEST", the realm mapped to open-paas.org
        String ticket = kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL);

        // When it is sent to Sabre
        Response response = propfind(ticket);

        // Then Sabre authenticates the user "{local part}@open-paas.org"
        String responseBody = response.then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:current-user-principal>
                          <d:href>/principals/users/{userId}/</d:href>
                        </d:current-user-principal>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{userId}", user.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void requestWithoutCredentialsShouldOfferNegotiateBeforeBasic() {
        // When a request carries no credentials
        Response response = given()
            .header("Depth", "0")
            .body(PROPFIND_CURRENT_USER_PRINCIPAL)
        .when()
            .request("PROPFIND", "/");

        // Then Sabre offers Negotiate first: clients only send a ticket once offered
        // Negotiate, and use the first scheme they support
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().getValues("WWW-Authenticate"))
            .containsExactly("Negotiate", "Basic realm=\"ESN\", charset=\"UTF-8\"");
    }

    @Test
    void basicAuthenticationShouldStillWork() {
        // Given the Basic credentials of the user
        String credentials = user.impersonatedBasicAuth();

        // When they are sent to Sabre, which has Kerberos enabled
        Response response = propfind(credentials);

        // Then Sabre authenticates the user as before
        response.then()
            .statusCode(207);
    }

    @Test
    void calendarShouldBeUsableWithTickets() throws Exception {
        // Given an event stored with a ticket: methods with a body go through the
        // internal location validating tickets too
        String uid = UUID.randomUUID().toString();
        String path = "/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics";
        String event = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//Kerberos test//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261117T090000Z
            DTSTART:20261120T090000Z
            DTEND:20261120T100000Z
            SUMMARY:Kerberos
            END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("\n", "\r\n");

        given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
            .contentType("text/calendar")
            .body(event)
        .when()
            .put(path)
        .then()
            .statusCode(201);

        // When it is read with another ticket
        Response response = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
        .when()
            .get(path);

        // Then the stored event is returned
        String stored = response.then()
            .statusCode(200)
            .extract().asString();

        assertThat(stored).isEqualTo(event);
    }

    @Test
    void invalidTokenShouldBeRejected() {
        // Given a Negotiate token that is no ticket
        String token = "Negotiate Z2FyYmFnZQ==";

        // When it is sent to Sabre
        Response response = propfind(token);

        // Then nginx rejects it
        response.then()
            .statusCode(403);
    }

    @Test
    void ticketForAnotherServiceShouldBeRejected() throws Exception {
        // Given a ticket for another service, whose key is not in the keytab of Sabre
        kdc.createPrincipal("HTTP/other.twake.test");
        String ticket = kdc.negotiateHeader(principal, "HTTP/other.twake.test");

        // When it is sent to Sabre
        Response response = propfind(ticket);

        // Then nginx, unable to decrypt it, rejects it
        response.then()
            .statusCode(403);
    }

    @Test
    void ticketForAnotherServiceOfTheKeytabShouldBeRejected() throws Exception {
        // Given a ticket for another service, whose key is also in the keytab of Sabre
        String ticket = kdc.negotiateHeader(principal, KerberosKdc.NEIGHBOUR_SERVICE_PRINCIPAL);

        // When it is sent to Sabre
        Response response = propfind(ticket);

        // Then nginx rejects it: it only accepts tickets for KERBEROS_SERVICE_PRINCIPAL,
        // not for every service of the keytab
        response.then()
            .statusCode(403);
    }

    @Test
    void principalWithoutUserShouldBeForbidden() throws Exception {
        // Given a valid ticket of a principal matching no user
        String unknown = "unknown-" + UUID.randomUUID();
        kdc.createPrincipal(unknown);
        String ticket = kdc.negotiateHeader(unknown, KerberosKdc.SABRE_SERVICE_PRINCIPAL);

        // When it is sent to Sabre
        Response response = propfind(ticket);

        // Then Sabre forbids it: 403 and not 401, which would make the client send it again forever
        String responseBody = response.then()
            .statusCode(403)
            .extract().asString();

        // SABRE_ENV=dev: the error also carries the stack trace, only its reason is stable
        assertSabreError(responseBody, "Sabre\\DAV\\Exception\\Forbidden", "checkKerberos: no user found");
    }

    @Test
    void servicePrincipalShouldBeForbidden() throws Exception {
        // Given a ticket of a service account, which authenticates but is not a user
        String service = "HTTP/" + principal + ".twake.test";
        kdc.createPrincipal(service);
        String ticket = kdc.negotiateHeader(service, KerberosKdc.SABRE_SERVICE_PRINCIPAL);

        // When it is sent to Sabre
        Response response = propfind(ticket);

        // Then Sabre forbids it
        String responseBody = response.then()
            .statusCode(403)
            .extract().asString();

        assertSabreError(responseBody, "Sabre\\DAV\\Exception\\Forbidden", "checkKerberos: unsupported principal");
    }

    @Test
    void forgedPrincipalHeaderShouldBeIgnored() {
        // Given headers named after the KRB5_PRINCIPAL FastCGI parameter, carrying a principal,
        // spelled both ways: with a dash, as clients send headers, and with an underscore, as
        // the parameter is named
        String forged = principal + "@" + KerberosKdc.REALM;

        // When they are sent without credentials
        Response response = given()
            .header("KRB5-PRINCIPAL", forged)
            .header("KRB5_PRINCIPAL", forged)
            .header("Depth", "0")
            .body(PROPFIND_CURRENT_USER_PRINCIPAL)
        .when()
            .request("PROPFIND", "/");

        // Then Sabre ignores them: nginx sets the parameter, which no header can
        response.then()
            .statusCode(401);
    }

    @Test
    void ticketShouldNotGiveAccessToThePrivateCalendarOfAnotherUser() throws Exception {
        // Given an event in the private calendar of Alice
        String uid = UUID.randomUUID().toString();
        String event = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//Kerberos test//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261117T090000Z
            DTSTART:20261120T090000Z
            DTEND:20261120T100000Z
            SUMMARY:Alice only
            END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("\n", "\r\n");
        calDavClient.updateCalendarAcl(alice, "");
        calDavClient.upsertCalendarEvent(alice, uid, event);
        String path = CalendarURL.from(alice.id()).eventHref(uid).toString();

        // When the user reads, modifies and deletes it with tickets
        Response read = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
        .when()
            .get(path);
        Response modification = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
            .contentType("text/calendar")
            .body(event.replace("SUMMARY:Alice only", "SUMMARY:Modified with a ticket"))
        .when()
            .put(path);
        Response deletion = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
        .when()
            .delete(path);

        // Then Sabre, not nginx: the tickets are valid, forbids each of them, and the
        // event of Alice is left untouched
        for (Response response : new Response[] {read, modification, deletion}) {
            assertThat(response.statusCode()).isEqualTo(403);
            XmlAssert.assertThat(response.asString())
                .withNamespaceContext(Map.of("d", "DAV:", "s", "http://sabredav.org/ns"))
                .valueByXPath("/d:error/s:exception").isEqualTo("Sabre\\DAVACL\\Exception\\NeedPrivileges");
        }
        assertThat(calDavClient.getCalendarEvent(alice, CalendarURL.from(alice.id()).eventHref(uid)))
            .isEqualTo(event);
    }

    @Test
    void ticketShouldOnlyReadACalendarSharedInReadOnly() throws Exception {
        // Given an event in the private calendar of Alice, shared in read only with the user
        String uid = UUID.randomUUID().toString();
        String event = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//Kerberos test//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261117T090000Z
            DTSTART:20261120T090000Z
            DTEND:20261120T100000Z
            SUMMARY:Shared in read only
            END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("\n", "\r\n");
        calDavClient.updateCalendarAcl(alice, "");
        calDavClient.upsertCalendarEvent(alice, uid, event);
        calDavClient.grantDelegation(alice, alice.id(), user, DelegationRight.READ);
        String delegatedPath = calDavClient.findDelegatedCalendar(user, alice.id()).eventHref(uid).toString();

        // When the user reads it, then modifies it, through the shared calendar with tickets
        Response read = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
        .when()
            .get(delegatedPath);
        Response modification = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
            .contentType("text/calendar")
            .body(event.replace("SUMMARY:Shared in read only", "SUMMARY:Modified with a ticket"))
        .when()
            .put(delegatedPath);

        // Then the user reads the event but cannot modify it
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.asString()).isEqualTo(event);
        assertThat(modification.statusCode()).isEqualTo(403);
        XmlAssert.assertThat(modification.asString())
            .withNamespaceContext(Map.of("d", "DAV:", "s", "http://sabredav.org/ns"))
            .valueByXPath("/d:error/s:exception").isEqualTo("Sabre\\DAVACL\\Exception\\NeedPrivileges");
        assertThat(calDavClient.getCalendarEvent(alice, CalendarURL.from(alice.id()).eventHref(uid)))
            .isEqualTo(event);
    }

    @Test
    void ticketShouldWriteACalendarSharedInReadWrite() throws Exception {
        // Given the private calendar of Alice, shared in read write with the user
        calDavClient.updateCalendarAcl(alice, "");
        calDavClient.grantDelegation(alice, alice.id(), user, DelegationRight.READ_WRITE);
        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(user, alice.id());
        String uid = UUID.randomUUID().toString();
        String event = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//Kerberos test//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261117T090000Z
            DTSTART:20261120T090000Z
            DTEND:20261120T100000Z
            SUMMARY:Created with a ticket
            END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("\n", "\r\n");

        // When the user creates an event through the shared calendar with a ticket
        Response creation = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
            .contentType("text/calendar")
            .body(event)
        .when()
            .put(delegatedCalendar.eventHref(uid).toString());

        // Then the event lands in the calendar of Alice
        assertThat(creation.statusCode()).isEqualTo(201);
        assertThat(calDavClient.getCalendarEvent(alice, CalendarURL.from(alice.id()).eventHref(uid)))
            .isEqualTo(event);
    }

    @Test
    void ticketShouldNotReadACalendarOnceItsShareIsRevoked() throws Exception {
        // Given an event in the private calendar of Alice, shared with the user then revoked
        String uid = UUID.randomUUID().toString();
        String event = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Twake//Kerberos test//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261117T090000Z
            DTSTART:20261120T090000Z
            DTEND:20261120T100000Z
            SUMMARY:No longer shared
            END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("\n", "\r\n");
        calDavClient.updateCalendarAcl(alice, "");
        calDavClient.upsertCalendarEvent(alice, uid, event);
        calDavClient.grantDelegation(alice, alice.id(), user, DelegationRight.READ_WRITE);
        calDavClient.revokeDelegation(alice, alice.id(), user);

        // When the user reads it with a ticket
        Response read = given()
            .header("Authorization", kdc.negotiateHeader(principal, KerberosKdc.SABRE_SERVICE_PRINCIPAL))
        .when()
            .get(CalendarURL.from(alice.id()).eventHref(uid).toString());

        // Then Sabre forbids it
        assertThat(read.statusCode()).isEqualTo(403);
        XmlAssert.assertThat(read.asString())
            .withNamespaceContext(Map.of("d", "DAV:", "s", "http://sabredav.org/ns"))
            .valueByXPath("/d:error/s:exception").isEqualTo("Sabre\\DAVACL\\Exception\\NeedPrivileges");
    }

    private static void assertSabreError(String responseBody, String exception, String message) {
        Map<String, String> namespaces = Map.of("d", "DAV:", "s", "http://sabredav.org/ns");
        XmlAssert.assertThat(responseBody).withNamespaceContext(namespaces)
            .valueByXPath("/d:error/s:exception").isEqualTo(exception);
        XmlAssert.assertThat(responseBody).withNamespaceContext(namespaces)
            .valueByXPath("/d:error/s:message").isEqualTo(message);
    }

    private Response propfind(String authorization) {
        return given()
            .header("Authorization", authorization)
            .header("Depth", "0")
            .body(PROPFIND_CURRENT_USER_PRINCIPAL)
        .when()
            .request("PROPFIND", "/");
    }
}
