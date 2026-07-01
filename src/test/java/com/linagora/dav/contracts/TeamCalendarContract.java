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

package com.linagora.dav.contracts;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xmlunit.assertj3.XmlAssert;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;

public abstract class TeamCalendarContract {
    private static final Map<String, String> DAV_NAMESPACES = Map.of(
        "d", "DAV:",
        "s", "http://sabredav.org/ns",
        "cal", "urn:ietf:params:xml:ns:caldav");

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerService.SABRE_DAV, "http")
                .toString())
            .build();
    }

    @Test
    void currentUserPrincipalShouldLinkTheTeamCalendarPrincipal() {
        // Given a team calendar exists in the default domain
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("sales-" + UUID.randomUUID(), "Sales Team")
            .block();

        // When the team calendar is authenticated through admin impersonation
        Response response = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/")
        .then()
            .extract()
            .response();

        // Then the current user principal points to the team calendar namespace
        assertThat(response.statusCode())
            .as("Team calendar admin impersonation should expose current-user-principal")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:current-user-principal/d:href")
            .isEqualTo(teamCalendar.principalHref());
    }

    @Test
    void teamCalendarPrincipalShouldExposeDisplayName() {
        // Given a team calendar exists in the default domain
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("support-" + UUID.randomUUID(), "Support Team")
            .block();

        // When its DAV principal properties are requested
        Response response = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/team-calendars/" + teamCalendar.id())
        .then()
            .extract()
            .response();

        // Then the principal exposes the team calendar display name
        assertThat(response.statusCode())
            .as("Team calendar principal PROPFIND should return display name")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname")
            .isEqualTo(teamCalendar.displayName());
    }

    @Test
    void teamCalendarPrincipalShouldResolveCalendarHome() {
        // Given a team calendar exists in the default domain
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("engineering-" + UUID.randomUUID(), "Engineering Team")
            .block();

        // When its CalDAV calendar home is discovered
        Response discoveryResponse = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop>
                    <c:calendar-home-set/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/team-calendars/" + teamCalendar.id())
        .then()
            .extract()
            .response();

        // Then the calendar home-set points to the team calendar home
        assertThat(discoveryResponse.statusCode())
            .as("Team calendar principal PROPFIND should resolve calendar-home-set")
            .isEqualTo(207);
        XmlAssert.assertThat(discoveryResponse.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//cal:calendar-home-set/d:href")
            .isEqualTo("/calendars/" + teamCalendar.id() + "/");
    }

    @Test
    void teamCalendarDefaultCalendarShouldBeAvailableOnFirstAccess() {
        // Given a team calendar exists
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("product-" + UUID.randomUUID(), "Product Team")
            .block();

        // When its default calendar is accessed for the first time
        Response response = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/calendars/" + teamCalendar.id() + "/" + teamCalendar.id())
        .then()
            .extract()
            .response();

        // Then the first request succeeds without requiring lazy provisioning to be retried
        assertThat(response.statusCode())
            .as("First access to team calendar default calendar should trigger lazy provisioning successfully")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .nodesByXPath("//d:multistatus/d:response/d:href")
            .extractingText()
            .contains("/calendars/" + teamCalendar.id() + "/" + teamCalendar.id() + "/");
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:response[d:href='/calendars/%s/%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                .formatted(teamCalendar.id(), teamCalendar.id()))
            .isEqualTo(teamCalendar.displayName());
    }

    @Test
    void technicalTokenShouldManageTeamCalendarSharing() {
        // Given a team calendar and Alice exist in the technical token domain
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // When the technical token grants Alice read-write access to the team calendar
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ_WRITE, technicalToken);

        // Then Alice sees the delegated team calendar
        assertThat(calDavClient.findDelegatedCalendar(alice))
            .as("Alice should see the team calendar after technical token grants delegation")
            .hasSize(1);

        // When the technical token revokes Alice's delegation
        calDavClient.revokeDelegation(teamCalendar.id(), alice, technicalToken);

        // Then Alice no longer sees that team calendar delegation
        assertThat(calDavClient.findDelegatedCalendar(alice))
            .as("Alice should no longer see the team calendar after technical token revokes delegation")
            .isEmpty();
    }

    @Test
    void teamCalendarDelegateeShouldSeeDelegatedCalendarThroughPropfind() {
        // Given a team calendar is delegated to Alice by the technical token
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ_WRITE, technicalToken);
        List<CalendarURL> delegatedCalendars = calDavClient.findDelegatedCalendar(alice);
        assertThat(delegatedCalendars)
            .as("Alice should have one delegated team calendar")
            .hasSize(1);

        // When Alice lists her calendar home through PROPFIND
        Response response = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Depth", "1")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/calendars/" + alice.id())
        .then()
            .extract()
            .response();

        // Then Alice sees the delegated team calendar as a calendar home child
        assertThat(response.statusCode())
            .as("Delegatee PROPFIND on calendar home should succeed")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .nodesByXPath("//d:multistatus/d:response/d:href")
            .extractingText()
            .contains(delegatedCalendars.getFirst().asUri() + "/");
    }

    @Test
    void teamCalendarDelegateeShouldSeeDelegatedCalendarPropertiesThroughPropfind() {
        // Given a team calendar is delegated to Alice by the technical token
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ_WRITE, technicalToken);
        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(alice).getFirst();

        // When Alice requests the delegated team calendar properties through PROPFIND
        Response response = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", delegatedCalendar.asUri().toString())
        .then()
            .extract()
            .response();

        // Then Alice sees team calendar properties through the delegated calendar URL
        assertThat(response.statusCode())
            .as("Delegatee PROPFIND on delegated team calendar should succeed")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:response[d:href='%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                .formatted(delegatedCalendar.asUri()))
            .isEqualTo(teamCalendar.displayName());
        assertThat(response.body().asString())
            .as("Delegated team calendar should be advertised as a CalDAV calendar collection")
            .contains("<d:collection/>", "<cal:calendar/>");
    }

    @Test
    void readOnlyTeamCalendarDelegateeShouldOnlySeeReadPrivilegesThroughPropfind() {
        // Given a team calendar is delegated to Alice as read-only by the technical token
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ, technicalToken);
        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(alice).getFirst();

        // When Alice requests privileges on the delegated team calendar through PROPFIND
        Response response = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-privilege-set/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", delegatedCalendar.asUri().toString())
        .then()
            .extract()
            .response();

        // Then Alice only sees read privileges
        assertThat(response.statusCode())
            .as("Delegatee PROPFIND on read-only team calendar privileges should succeed")
            .isEqualTo(207);
        assertThat(response.body().asString())
            .as("Read-only delegated team calendar should not advertise write privileges")
            .contains(delegatedCalendar.asUri() + "/", "<d:read/>")
            .doesNotContain("<d:write/>", "<d:write-content/>", "<d:write-properties/>", "<d:all/>");
    }

    @Test
    void readWriteTeamCalendarDelegateeShouldCreateEvent() {
        // Given Alice has read-write delegation on a team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ_WRITE, technicalToken);
        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(alice).getFirst();
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates an event in the delegated team calendar
        calDavClient.upsertCalendarEvent(alice, delegatedCalendar, eventUid, calendarData(eventUid, "Alice creates team event"));

        // Then Alice can read it from the delegated team calendar
        DavResponse response = calDavClient.findEventsByTime(alice, delegatedCalendar, "20300110T000000", "20300110T235959");
        assertThat(response.status())
            .as("Alice should report events from the delegated team calendar")
            .isEqualTo(200);
        assertThat(response.body())
            .as("Alice should see the event she created in the team calendar")
            .contains(eventUid);
    }

    @Test
    void readOnlyTeamCalendarDelegateeShouldNotCreateEvent() {
        // Given Alice has read-only delegation on a team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ, technicalToken);
        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(alice).getFirst();
        String eventUid = "team-event-" + UUID.randomUUID();

        // When/Then Alice cannot create an event in the delegated team calendar
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, delegatedCalendar, eventUid, calendarData(eventUid, "Alice tries to create team event")))
            .as("Read-only team calendar delegatee should not create events")
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void teamCalendarMemberShouldSeeEventCreatedByAnotherMember() {
        // Given Alice and Bob are members of the same team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ_WRITE, technicalToken);
        calDavClient.grantDelegation(teamCalendar.id(), bob, DelegationRight.READ, technicalToken);
        CalendarURL aliceDelegatedCalendar = calDavClient.findDelegatedCalendar(alice).getFirst();
        CalendarURL bobDelegatedCalendar = calDavClient.findDelegatedCalendar(bob).getFirst();
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates an event in the delegated team calendar
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid, calendarData(eventUid, "Alice creates event for Bob"));

        // Then Bob can read the event from his delegated team calendar
        DavResponse response = calDavClient.findEventsByTime(bob, bobDelegatedCalendar, "20300110T000000", "20300110T235959");
        assertThat(response.status())
            .as("Bob should report team calendar events as a member")
            .isEqualTo(200);
        assertThat(response.body())
            .as("Bob should see Alice's event because he is also a team calendar member")
            .contains(eventUid);
    }

    @Test
    void nonTeamCalendarMemberShouldNotSeeEventCreatedByMember() {
        // Given Alice is a team calendar member while Bob is not
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();

        OpenPaasUser mockUser = new OpenPaasUser(teamCalendar.id(), "", "", teamCalendar.email(), "");
        calDavClient.updateCalendarAcl(mockUser, "" );
        calDavClient.grantDelegation(teamCalendar.id(), alice, DelegationRight.READ_WRITE, technicalToken);
        CalendarURL aliceDelegatedCalendar = calDavClient.findDelegatedCalendar(alice).getFirst();
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates an event in the delegated team calendar
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid, calendarData(eventUid, "Alice creates private team event"));

        // Then Bob cannot read it from the team calendar because he is not a member
        DavResponse response = calDavClient.findEventsByTime(bob, teamCalendarCanonicalUrl, "20300110T000000", "20300110T235959");
        assertThat(response.status())
            .as("Non-member Bob should not report events from the team calendar")
            .isIn(403, 404);
        assertThat(response.body())
            .as("Non-member Bob should not see Alice's team calendar event")
            .doesNotContain(eventUid);
    }

    private String calendarData(String eventUid, String summary) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300101T080000Z
            DTSTART:20300110T090000Z
            DTEND:20300110T100000Z
            SUMMARY:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, summary);
    }

}
