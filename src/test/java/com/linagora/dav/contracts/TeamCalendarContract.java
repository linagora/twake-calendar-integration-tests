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

import static com.linagora.dav.CalendarAssert.assertThatCalendar;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xmlunit.assertj3.XmlAssert;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestUtil;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;

public abstract class TeamCalendarContract {
    private static final Map<String, String> DAV_NAMESPACES = Map.of(
        "d", "DAV:",
        "cal", "urn:ietf:params:xml:ns:caldav");
    private static final String DISPLAY_NAME_PROPFIND_BODY = """
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:displayname/>
          </d:prop>
        </d:propfind>""";
    private static final String PUBLIC_READ_RIGHT = "{DAV:}read";

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        RestAssured.reset();
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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("sales", "Sales Team");

        // When the team calendar is authenticated through admin impersonation
        Response response = propfind(teamCalendar, "/", 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""");

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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("support", "Support Team");

        // When its DAV principal properties are requested
        Response response = propfind(teamCalendar,
            "/principals/team-calendars/" + teamCalendar.id(), 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""");

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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("engineering", "Engineering Team");

        // When its CalDAV calendar home is discovered
        Response discoveryResponse = propfind(teamCalendar,
            "/principals/team-calendars/" + teamCalendar.id(), 0, """
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop>
                    <c:calendar-home-set/>
                  </d:prop>
                </d:propfind>""");

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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("product", "Product Team");

        // When its default calendar is accessed for the first time
        Response response = propfind(teamCalendar,
            "/calendars/" + teamCalendar.id() + "/" + teamCalendar.id(), 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""");

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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);

        // When Alice lists her calendar home through PROPFIND
        Response response = propfind(alice, "/calendars/" + alice.id(), 1, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""");

        // Then Alice sees the delegated team calendar as a calendar home child
        assertThat(response.statusCode())
            .as("Delegatee PROPFIND on calendar home should succeed")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .nodesByXPath("//d:multistatus/d:response/d:href")
            .extractingText()
            .contains(delegatedCalendar.asUri() + "/");
    }

    @Test
    void teamCalendarDelegateeShouldSeeDelegatedCalendarPropertiesThroughPropfind() {
        // Given a team calendar is delegated to Alice by the technical token
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);

        // When Alice requests the delegated team calendar properties through PROPFIND
        Response response = propfind(alice, delegatedCalendar.asUri().toString(), 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                  </d:prop>
                </d:propfind>""");

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
    void teamCalendarDisplayNameUpdateShouldUpdateUncustomizedMemberDelegatedCalendarName() {
        // Given Alice and Bob have uncustomized delegated copies of a team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Initial Team Name");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);
        CalendarURL bobDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ);

        // When WebAdmin renames the team calendar
        String renamedDisplayName = "Renamed Team Calendar";
        updateTeamCalendarDisplayName(teamCalendar, renamedDisplayName);

        // Then the canonical and delegated calendar names follow the new source display name
        TestUtil.awaitAtMost.untilAsserted(() -> {
            CalendarURL canonicalCalendar = CalendarURL.from(teamCalendar.id());
            assertDisplayName(propfind(teamCalendar, canonicalCalendar.asUri().toString(), 0, DISPLAY_NAME_PROPFIND_BODY),
                canonicalCalendar, renamedDisplayName);
            assertDisplayName(propfind(alice, aliceDelegatedCalendar.asUri().toString(), 0, DISPLAY_NAME_PROPFIND_BODY),
                aliceDelegatedCalendar, renamedDisplayName);
            assertDisplayName(propfind(bob, bobDelegatedCalendar.asUri().toString(), 0, DISPLAY_NAME_PROPFIND_BODY),
                bobDelegatedCalendar, renamedDisplayName);
        });
    }

    @Test
    void teamCalendarDisplayNameUpdateShouldNotOverwriteCustomizedMemberDelegatedCalendarName() {
        // Given Alice and Bob have delegated copies of a team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Initial Team Name");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);
        CalendarURL bobDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ);

        // And Bob customizes his local delegated calendar name
        calDavClient.updateCalendarSetting(bob, bobDelegatedCalendar, "Initial Team Name BOB", "#009688");

        // When WebAdmin renames the team calendar
        String renamedDisplayName = "Renamed Team Calendar";
        updateTeamCalendarDisplayName(teamCalendar, renamedDisplayName);

        // Then Alice follows the source display name while Bob keeps his customized name
        TestUtil.awaitAtMost.untilAsserted(() -> {
            CalendarURL canonicalCalendar = CalendarURL.from(teamCalendar.id());
            assertDisplayName(propfind(teamCalendar, canonicalCalendar.asUri().toString(), 0, DISPLAY_NAME_PROPFIND_BODY),
                canonicalCalendar, renamedDisplayName);
            assertDisplayName(propfind(alice, aliceDelegatedCalendar.asUri().toString(), 0, DISPLAY_NAME_PROPFIND_BODY),
                aliceDelegatedCalendar, renamedDisplayName);
            assertDisplayName(propfind(bob, bobDelegatedCalendar.asUri().toString(), 0, DISPLAY_NAME_PROPFIND_BODY),
                bobDelegatedCalendar, "Initial Team Name BOB");
        });
    }

    @Test
    void readOnlyTeamCalendarDelegateeShouldOnlySeeReadPrivilegesThroughPropfind() {
        // Given a team calendar is delegated to Alice as read-only by the technical token
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);

        // When Alice requests privileges on the delegated team calendar through PROPFIND
        Response response = propfind(alice, delegatedCalendar.asUri().toString(), 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-privilege-set/>
                  </d:prop>
                </d:propfind>""");

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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates an event in the delegated team calendar
        calDavClient.upsertCalendarEvent(alice, delegatedCalendar, eventUid,
            calendarData(eventUid, "Alice creates team event"));

        // Then Alice can read it from the delegated team calendar
        DavResponse response = findEventsByTime(alice, delegatedCalendar);
        assertThat(response.status())
            .as("Alice should report events from the delegated team calendar")
            .isEqualTo(200);
        assertThat(response.body())
            .as("Alice should see the event she created in the team calendar")
            .contains(eventUid);
    }

    @ParameterizedTest(name = "{0} can create a team calendar event")
    @CsvSource({
        "member, READ_WRITE",
        "manager, ADMIN"
    })
    void writeEnabledTeamCalendarMemberShouldCreateEventWithSelfOrganizer(String role, DelegationRight right) {
        // Given Bob has write access on a team calendar
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, right);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Bob creates an event in the team calendar with himself as organizer
        Response putResponse = putCalendarEvent(bob, delegatedCalendar, eventUid,
            calendarData(eventUid, role + " creates team event", bob.email()));

        // Then the request is accepted
        assertThat(putResponse.statusCode())
            .as("Write-enabled %s should create a team calendar event with themselves as organizer".formatted(role))
            .isIn(201, 204);

        // Then the event is stored in the team calendar
        Response reportResponse = reportEventsByTime(bob, delegatedCalendar);
        assertThat(reportResponse.body().asString())
            .as("Team calendar should contain the event created by the write-enabled %s".formatted(role))
            .contains(eventUid);
    }

    @Test
    void writeEnabledMemberShouldMovePersonalEventToTeamCalendar() {
        // Given Bob is a write-enabled member of a team calendar and owns an event in his personal calendar
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ_WRITE);
        String eventUid = "personal-event-" + UUID.randomUUID();
        CalendarURL personalCalendar = CalendarURL.from(bob.id());
        URI personalEventUri = personalCalendar.eventHref(eventUid);
        URI teamEventUri = delegatedCalendar.eventHref(eventUid);
        String eventIcs = calendarData(eventUid, "Bob moves personal event to team calendar", bob.email());
        calDavClient.upsertCalendarEvent(bob, personalEventUri, eventIcs);

        // When Bob moves the event to his delegated team calendar
        Response moveResponse = moveEvent(bob, personalEventUri, teamEventUri);

        // Then the event is removed from the personal calendar and stored in the team calendar
        assertThat(moveResponse.statusCode())
            .as("Write-enabled member should move a personal event to the team calendar")
            .isIn(201, 204);
        assertThat(getEventStatus(bob, personalEventUri)).isEqualTo(404);
        String movedEventIcs = calDavClient.getCalendarEvent(bob, teamEventUri);
        assertThatCalendar(movedEventIcs)
            .ignoringProperties("X-OPENPAAS-TEAM-CALENDAR-ID")
            .isEqualTo(eventIcs);
        assertThat(reportEventsByTime(bob, delegatedCalendar).body().asString()).contains(eventUid);
    }

    @Test
    void nonMemberShouldNotMovePersonalEventToPrivateTeamCalendar() {
        // Given Alice can inspect a private team calendar while Bob is not a member and owns a personal event
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.updateTeamCalendarAcl(teamCalendar, "");
        String eventUid = "personal-event-" + UUID.randomUUID();
        URI personalEventUri = CalendarURL.from(bob.id()).eventHref(eventUid);
        URI teamEventUri = teamCalendarCanonicalUrl.eventHref(eventUid);
        calDavClient.upsertCalendarEvent(bob, personalEventUri,
            calendarData(eventUid, "Bob cannot move personal event to private team calendar", bob.email()));

        // When Bob moves his event directly to the private team calendar
        Response moveResponse = moveEvent(bob, personalEventUri, teamEventUri);

        // Then the move is rejected and neither source nor destination is changed
        assertThat(moveResponse.statusCode()).isIn(403, 404);
        assertThat(getEventStatus(bob, personalEventUri)).isEqualTo(200);
        assertThat(reportEventsByTime(alice, aliceDelegatedCalendar).body().asString()).doesNotContain(eventUid);
    }

    @Test
    void readOnlyMemberShouldNotMovePersonalEventToTeamCalendar() {
        // Given Bob has read-only delegation on a team calendar and owns a personal event
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ);
        String eventUid = "personal-event-" + UUID.randomUUID();
        URI personalEventUri = CalendarURL.from(bob.id()).eventHref(eventUid);
        URI teamEventUri = delegatedCalendar.eventHref(eventUid);
        calDavClient.upsertCalendarEvent(bob, personalEventUri,
            calendarData(eventUid, "Bob cannot move personal event as read-only member", bob.email()));

        // When Bob moves the event to his read-only delegated team calendar
        Response moveResponse = moveEvent(bob, personalEventUri, teamEventUri);

        // Then the move is rejected and neither source nor destination is changed
        assertThat(moveResponse.statusCode()).isEqualTo(403);
        assertThat(getEventStatus(bob, personalEventUri)).isEqualTo(200);
        assertThat(getEventStatus(bob, teamEventUri)).isEqualTo(404);
        assertThat(reportEventsByTime(bob, delegatedCalendar).body().asString()).doesNotContain(eventUid);
    }

    @Test
    void nonMemberAttendeeShouldNotMoveInvitedEventToPrivateTeamCalendar() {
        // Given Alice owns an event inviting Bob, while only Alice can write to the private team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.updateTeamCalendarAcl(teamCalendar, "");
        String eventUid = "invited-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarDataWithAttendee(eventUid,
            "Alice invites Bob", alice.email(), bob.email()));

        // And Bob receives the attendee copy in his personal calendar
        URI bobEventUri = TestUtil.awaitAtMost.until(
                () -> calDavClient.findFirstUserCalendarObjectUriByEventUid(bob, CalendarURL.from(bob.id()), eventUid),
                Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected attendee copy for UID " + eventUid));
        URI teamEventUri = teamCalendarCanonicalUrl.eventHref(eventUid);

        // When Bob moves his attendee copy directly to the private team calendar
        Response moveResponse = moveEvent(bob, bobEventUri, teamEventUri);

        // Then the move is rejected and the attendee copy remains in Bob's personal calendar
        assertThat(moveResponse.statusCode()).isIn(403, 404);
        assertThat(getEventStatus(bob, bobEventUri)).isEqualTo(200);
        assertThat(reportEventsByTime(alice, aliceDelegatedCalendar).body().asString()).doesNotContain(eventUid);
    }

    @Test
    void teamCalendarMemberShouldReportEventsOnCanonicalUrl() {
        // Given Bob is a team calendar member and can write through his delegated mirror
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.ADMIN);
        String eventUid = "team-event-" + UUID.randomUUID();

        Response putResponse = putCalendarEvent(bob, delegatedCalendar, eventUid,
            calendarData(eventUid, "member creates team event", bob.email()));
        assertThat(putResponse.statusCode())
            .as("Team calendar member should create an event through the delegated mirror")
            .isIn(201, 204);

        // When the client reports against the canonical team calendar URL instead of the delegated mirror
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        Response reportResponse = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(bob.email()))
            .header("Depth", 0)
            .header("Accept", "application/json")
            .header("Content-Type", "text/plain;charset=UTF-8")
            .body("""
                {
                    "match": {
                        "start": "20300110T000000",
                        "end": "20300110T235959"
                    }
                }
                """)
        .when()
            .request("REPORT", teamCalendarCanonicalUrl.asUri() + ".json")
        .then()
            .extract()
            .response();

        // Then Sabre accepts the canonical URL because Bob has read rights through delegation.
        assertThat(reportResponse.statusCode())
            .as("Team calendar member should report events through the canonical URL")
            .isEqualTo(200);
        assertThat(reportResponse.body().asString())
            .contains(eventUid);
    }

    @Test
    void readOnlyTeamCalendarDelegateeShouldNotCreateEvent() {
        // Given Alice has read-only delegation on a team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When/Then Alice cannot create an event in the delegated team calendar
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, delegatedCalendar, eventUid,
            calendarData(eventUid, "Alice tries to create team event")))
            .as("Read-only team calendar delegatee should not create events")
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void viewerTeamCalendarMemberShouldNotCreateEvent() {
        // Given Bob has viewer access on a team calendar
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Bob creates an event in the team calendar with himself as organizer
        Response putResponse = putCalendarEvent(bob, delegatedCalendar, eventUid,
            calendarData(eventUid, "Viewer tries to create team event", bob.email()));

        // Then the request is rejected
        assertThat(putResponse.statusCode())
            .as("Viewer should not create a team calendar event")
            .isEqualTo(403);

        // Then the event is not stored in the team calendar
        Response reportResponse = reportEventsByTime(bob, delegatedCalendar);
        assertThat(reportResponse.body().asString())
            .as("Team calendar should not contain the event rejected for a viewer")
            .doesNotContain(eventUid);
    }

    @Test
    void nonTeamCalendarMemberShouldNotCreateEvent() {
        // Given Bob is not a member of a private team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.updateTeamCalendarAcl(teamCalendar, "");
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Bob creates an event in the team calendar with himself as organizer
        Response putResponse = putCalendarEvent(bob, teamCalendarCanonicalUrl, eventUid,
            calendarData(eventUid, "Non-member tries to create team event", bob.email()));

        // Then the request is rejected
        assertThat(putResponse.statusCode())
            .as("Non-member should not create a private team calendar event")
            .isIn(403, 404);

        // Then the event is not stored in the team calendar
        Response reportResponse = reportEventsByTime(alice, aliceDelegatedCalendar);
        assertThat(reportResponse.body().asString())
            .as("Team calendar should not contain the event rejected for a non-member")
            .doesNotContain(eventUid);
    }

    @Test
    void writeEnabledTeamCalendarMemberShouldNotCreateEventWithNonMemberOrganizer() {
        // Given Bob has write access on a team calendar
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ_WRITE);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Bob creates an event with a non-member organizer
        Response putResponse = putCalendarEvent(bob, delegatedCalendar, eventUid,
            calendarData(eventUid, "Bob tries to create team event with non-member organizer", "external-or-non-member@domain.tld"));

        // Then the request is rejected
        assertThat(putResponse.statusCode())
            .as("Write-enabled member should not create a team calendar event with a non-member organizer")
            .isEqualTo(403);
        assertThat(putResponse.body().asString())
            .as("Rejected non-member organizer response should mention ORGANIZER")
            .contains("ORGANIZER");

        // Then the event is not stored in the team calendar
        Response reportResponse = reportEventsByTime(bob, delegatedCalendar);
        assertThat(reportResponse.body().asString())
            .as("Team calendar should not contain the event rejected for a non-member organizer")
            .doesNotContain(eventUid);
    }

    @Test
    void viewerTeamCalendarMemberShouldNotUpdateEvent() {
        // Given Bob creates an event in a team calendar and Charlie only has viewer access
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser charlie = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL bobDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ_WRITE);
        CalendarURL charlieDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, charlie, DelegationRight.READ);
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bob, bobDelegatedCalendar, eventUid,
            calendarData(eventUid, "Bob creates event readable by viewer", bob.email()));

        // When Charlie tries to update the event
        Response updateResponse = putCalendarEvent(charlie, charlieDelegatedCalendar, eventUid,
            calendarData(eventUid, "Viewer tries to update team event", bob.email()));

        // Then the update is rejected
        assertThat(updateResponse.statusCode())
            .as("Viewer should not update team calendar events")
            .isEqualTo(403);

        // Then the original event remains unchanged
        DavResponse bobReadResponse = findEventsByTime(bob, bobDelegatedCalendar);
        assertThat(bobReadResponse.body())
            .as("Team calendar event should keep the original summary after viewer rejected update")
            .contains(eventUid, "Bob creates event readable by viewer")
            .doesNotContain("Viewer tries to update team event");
    }

    @Test
    void teamCalendarMemberShouldSeeEventCreatedByAnotherMember() {
        // Given Alice and Bob are members of the same team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL bobDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates an event in the delegated team calendar
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid,
            calendarData(eventUid, "Alice creates event for Bob"));

        // Then Bob can read the event from his delegated team calendar
        DavResponse response = findEventsByTime(bob, bobDelegatedCalendar);
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
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        String eventUid = "team-event-" + UUID.randomUUID();

        // And the team calendar is private
        calDavClient.updateTeamCalendarAcl(teamCalendar, "");

        // When Alice creates an event in the delegated team calendar
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid,
            calendarData(eventUid, "Alice creates private team event"));

        // Then Bob cannot read it from the team calendar because he is not a member
        DavResponse response = findEventsByTime(bob, teamCalendarCanonicalUrl);
        assertThat(response.status())
            .as("Non-member Bob should not report events from the team calendar")
            .isIn(403, 404);
        assertThat(response.body())
            .as("Non-member Bob should not see Alice's team calendar event")
            .doesNotContain(eventUid);
    }

    @Test
    void publicReadTeamCalendarShouldBeVisibleToNonMemberThroughPropfind() {
        // Given a team calendar is publicly readable while Bob is not a member
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);

        // When Bob requests the public team calendar properties through PROPFIND
        Response response = propfind(bob, teamCalendarCanonicalUrl.asUri().toString(), 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <d:current-user-privilege-set/>
                  </d:prop>
                </d:propfind>""");

        // Then Bob sees the team calendar as a readable public calendar collection
        assertThat(response.statusCode())
            .as("Non-member Bob should PROPFIND a publicly readable team calendar")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:response[d:href='%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                .formatted(teamCalendarCanonicalUrl.asUri()))
            .isEqualTo(teamCalendar.displayName());
        assertThat(response.body().asString())
            .as("Publicly readable team calendar should advertise read access without write privileges")
            .contains(teamCalendarCanonicalUrl.asUri() + "/", "<d:collection/>", "<cal:calendar/>", "<d:read/>")
            .doesNotContain("<d:write/>", "<d:write-content/>", "<d:write-properties/>", "<d:all/>");
    }

    @Test
    void publicReadTeamCalendarShouldExposeMemberEventToNonMember() {
        // Given Alice creates an event in a publicly readable team calendar while Bob is not a member
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid,
            calendarData(eventUid, "Alice creates public team event"));

        // When Bob reports events from the public team calendar
        DavResponse response = findEventsByTime(bob, teamCalendarCanonicalUrl);

        // Then Bob can read the event because the team calendar is public
        assertThat(response.status())
            .as("Non-member Bob should report events from a publicly readable team calendar")
            .isEqualTo(200);
        assertThat(response.body())
            .as("Non-member Bob should see Alice's event because the team calendar is public")
            .contains(eventUid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "CONFIDENTIAL"})
    void publicReadTeamCalendarShouldExposeClassifiedMemberEventDetailsToMember(String eventClass) {
        // Given Alice and Bob are members of a team calendar
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL bobDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, bob, DelegationRight.READ);
        // And the team calendar is public-read
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates a classified event in the team calendar
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Example Corp.//CalDAV Client//EN
                BEGIN:VEVENT
                UID:{eventUid}
                DTSTAMP:20300101T080000Z
                DTSTART:20300110T090000Z
                DTEND:20300110T100000Z
                ORGANIZER:mailto:{organizerEmail}
                SUMMARY:Sensitive {eventClass} team event
                DESCRIPTION:Sensitive {eventClass} team details
                LOCATION:Sensitive {eventClass} room
                CLASS:{eventClass}
                END:VEVENT
                END:VCALENDAR
                """.replace("{eventUid}", eventUid)
                .replace("{organizerEmail}", alice.email())
                .replace("{eventClass}", eventClass));

        // Then Bob can read the classified event details from the JSON time-range REPORT on his delegated team calendar
        String body = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(bob.email()))
            .header("Depth", 0)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .body("""
                {
                    "match": {
                        "start": "20300110T000000",
                        "end": "20300110T235959"
                    }
                }
                """)
        .when()
            .request("REPORT", bobDelegatedCalendar.asUri() + ".json")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .node("_embedded.dav:item").isArray().hasSizeGreaterThanOrEqualTo(1);
        assertThat(body)
            .as("Team calendar member Bob should see classified event details")
            .contains(eventUid,
                "Sensitive {eventClass} team event".replace("{eventClass}", eventClass),
                "Sensitive {eventClass} team details".replace("{eventClass}", eventClass),
                "Sensitive {eventClass} room".replace("{eventClass}", eventClass));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "CONFIDENTIAL"})
    void publicReadTeamCalendarShouldNotExposeClassifiedMemberEventDetailsToNonMember(String eventClass) {
        // Given Alice creates a classified event in a publicly readable team calendar while Charlie is not a member
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser charlie = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Example Corp.//CalDAV Client//EN
                BEGIN:VEVENT
                UID:{eventUid}
                DTSTAMP:20300101T080000Z
                DTSTART:20300110T090000Z
                DTEND:20300110T100000Z
                ORGANIZER:mailto:{organizerEmail}
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{attendeeEmail}
                SUMMARY:Sensitive {eventClass} team event
                DESCRIPTION:Sensitive {eventClass} team details
                LOCATION:Sensitive {eventClass} room
                CLASS:{eventClass}
                END:VEVENT
                END:VCALENDAR
                """.replace("{eventUid}", eventUid)
                .replace("{organizerEmail}", alice.email())
                .replace("{attendeeEmail}", attendee.email())
                .replace("{eventClass}", eventClass));

        // When Charlie requests a JSON time-range REPORT from the public team calendar
        String body = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(charlie.email()))
            .header("Depth", 0)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .body("""
                {
                    "match": {
                        "start": "20300110T000000",
                        "end": "20300110T235959"
                    }
                }
                """)
        .when()
            .request("REPORT", teamCalendarCanonicalUrl.asUri() + ".json")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        // Then Charlie can read the public calendar but cannot see classified event details in the JSON response
        assertThatJson(body)
            .node("_embedded.dav:item").isArray().hasSizeGreaterThanOrEqualTo(1);
        assertThat(body)
            .as("Non-member Charlie should not see classified event sensitive details")
            .doesNotContain(
                "Sensitive {eventClass} team event".replace("{eventClass}", eventClass),
                "Sensitive {eventClass} team details".replace("{eventClass}", eventClass),
                "Sensitive {eventClass} room".replace("{eventClass}", eventClass),
                attendee.email());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "CONFIDENTIAL"})
    void publicReadTeamCalendarShouldNotExposeClassifiedMemberEventDetailsInCalendarDataToNonMember(String eventClass) {
        // Given Alice is a member of a team calendar while Charlie is not a member
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser charlie = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL aliceDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ_WRITE);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        // And the team calendar is public-read
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        String eventUid = "team-event-" + UUID.randomUUID();

        // When Alice creates a classified event in the team calendar
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, eventUid,
            """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Example Corp.//CalDAV Client//EN
                BEGIN:VEVENT
                UID:{eventUid}
                DTSTAMP:20300101T080000Z
                DTSTART:20300110T090000Z
                DTEND:20300110T100000Z
                ORGANIZER:mailto:{organizerEmail}
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{attendeeEmail}
                SUMMARY:Sensitive {eventClass} team event
                DESCRIPTION:Sensitive {eventClass} team details
                LOCATION:Sensitive {eventClass} room
                CLASS:{eventClass}
                END:VEVENT
                END:VCALENDAR
                """.replace("{eventUid}", eventUid)
                .replace("{organizerEmail}", alice.email())
                .replace("{attendeeEmail}", attendee.email())
                .replace("{eventClass}", eventClass));

        // When Charlie requests XML CalDAV calendar-data from the public team calendar
        String body = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(charlie.email()))
            .header("Depth", 1)
            .header("Content-Type", "application/xml")
            .header("Accept", "application/xml")
            .body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">{eventUid}</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.replace("{eventUid}", eventUid))
        .when()
            .request("REPORT", teamCalendarCanonicalUrl.asUri().toString())
        .then()
            .statusCode(207)
            .extract()
            .body()
            .asString();

        // Then Charlie can read the public calendar but cannot see classified event details in the XML calendar-data response
        assertThat(body)
            .as("Non-member Charlie should not see classified event sensitive details in XML calendar-data")
            .contains(eventUid)
            .doesNotContain(
                "Sensitive {eventClass} team event".replace("{eventClass}", eventClass),
                "Sensitive {eventClass} team details".replace("{eventClass}", eventClass),
                "Sensitive {eventClass} room".replace("{eventClass}", eventClass),
                attendee.email());
    }

    @Test
    void publicReadTeamCalendarShouldBeSubscribableByNonMember() {
        // Given a team calendar is publicly readable while Alice is not a member
        OpenPaasUser teamMember = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        CalendarURL teamMemberDelegatedCalendar = delegateTeamCalendarTo(teamCalendar, teamMember, DelegationRight.READ_WRITE);
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(teamMember, teamMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, "Team member creates public team event"));

        // And Alice prepares a read-only subscription to the public team calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(teamCalendar.id())
            .name("Operations Team mirror")
            .color("#00FF00")
            .readOnly(true)
            .build();

        // When Alice subscribes to the public team calendar
        CalendarURL subscribedCalendar = calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // Then Alice sees the subscribed team calendar in her calendar home
        Response propfindResponse = propfind(alice, "/calendars/" + alice.id(), 1, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""");

        assertThat(propfindResponse.statusCode())
            .as("Alice should list her calendar home after subscribing to a public team calendar")
            .isEqualTo(207);
        XmlAssert.assertThat(propfindResponse.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .nodesByXPath("//d:multistatus/d:response/d:href")
            .extractingText()
            .contains(subscribedCalendar.asUri() + "/");

        // And Alice can read team calendar events through the subscribed mirror
        DavResponse eventsResponse = findEventsByTime(alice, subscribedCalendar);
        assertThat(eventsResponse.status())
            .as("Alice should report events from the subscribed public team calendar")
            .isEqualTo(200);
        assertThat(eventsResponse.body())
            .as("Alice should see team calendar events through the subscribed mirror")
            .contains(eventUid);
    }

    @Test
    void nonTeamCalendarMemberShouldNotSeePrivateTeamCalendarThroughCalendarHomePropfind() {
        // Given a private team calendar is delegated to Alice
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);

        // When Bob, who is not a member, lists his calendar home through PROPFIND
        Response response = propfind(bob, "/calendars/" + bob.id(), 1, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""");

        // Then Bob does not discover the private team calendar
        assertThat(response.statusCode())
            .as("Non-member Bob should list his own calendar home successfully")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .nodesByXPath("//d:multistatus/d:response/d:href")
            .extractingText()
            .noneSatisfy(href -> assertThat(href)
                .as("Bob's calendar home should not expose a team calendar he is not a member of")
                .contains("/calendars/" + teamCalendar.id() + "/" + teamCalendar.id() + "/"));
    }

    @Test
    void nonTeamCalendarMemberShouldNotPropfindPrivateTeamCalendar() {
        // Given a private team calendar is delegated to Alice
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("operations", "Operations Team");
        delegateTeamCalendarTo(teamCalendar, alice, DelegationRight.READ);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());

        // When Bob, who is not a member, requests the private team calendar directly through PROPFIND
        Response response = propfind(bob, teamCalendarCanonicalUrl.asUri().toString(), 0, """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""");

        // Then Bob cannot access the private team calendar
        assertThat(response.statusCode())
            .as("Non-member Bob should not directly PROPFIND a private team calendar")
            .isIn(403, 404);
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

    private String calendarData(String eventUid, String summary, String organizerEmail) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300101T080000Z
            DTSTART:20300110T090000Z
            DTEND:20300110T100000Z
            ORGANIZER:mailto:%s
            SUMMARY:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizerEmail, summary);
    }

    private String calendarDataWithAttendee(String eventUid, String summary, String organizerEmail, String attendeeEmail) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:20300101T080000Z
            DTSTART:20300110T090000Z
            DTEND:20300110T100000Z
            ORGANIZER:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{attendeeEmail}
            SUMMARY:{summary}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary);
    }

    private OpenPaaSTeamCalendar newTeamCalendar(String namePrefix, String displayName) {
        return dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar(namePrefix + "-" + UUID.randomUUID(), displayName)
            .block();
    }

    private CalendarURL delegateTeamCalendarTo(OpenPaaSTeamCalendar teamCalendar, OpenPaasUser user, DelegationRight right) {
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), user, right, technicalToken);
        return calDavClient.findDelegatedCalendar(user, teamCalendar.id());
    }

    private void updateTeamCalendarDisplayName(OpenPaaSTeamCalendar teamCalendar, String displayName) {
        String payload = """
            {
              "displayName": "{displayName}"
            }
            """.replace("{displayName}", displayName);

        given(dockerExtension().webAdminRequestSpecification())
            .body(payload)
        .when()
            .patch("/domains/{domain}/team-calendars/{teamCalendarId}", teamCalendar.domainName(), teamCalendar.id())
        .then()
            .statusCode(200);
    }

    private void assertDisplayName(Response response, CalendarURL calendarURL, String expectedDisplayName) {
        assertThat(response.statusCode()).isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:response[d:href='%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                .formatted(calendarURL.asUri()))
            .isEqualTo(expectedDisplayName);
    }

    private Response propfind(OpenPaaSTeamCalendar teamCalendar, String path, int depth, String requestBody) {
        return propfind(teamCalendar.email(), path, depth, requestBody);
    }

    private Response propfind(OpenPaasUser user, String path, int depth, String requestBody) {
        return propfind(user.email(), path, depth, requestBody);
    }

    private Response propfind(String userEmail, String path, int depth, String requestBody) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(userEmail))
            .header("Depth", depth)
            .header("Content-Type", "application/xml")
            .body(requestBody)
        .when()
            .request("PROPFIND", path)
        .then()
            .extract()
            .response();
    }

    private Response putCalendarEvent(OpenPaasUser user, CalendarURL calendarURL, String eventUid, String requestBody) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
            .header("Content-Type", "text/calendar ; charset=utf-8")
            .body(requestBody)
        .when()
            .put(calendarURL.eventHref(eventUid).toString())
        .then()
            .extract()
            .response();
    }

    private Response moveEvent(OpenPaasUser user, URI sourceEventUri, URI destinationEventUri) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
            .header("Destination", destinationEventUri.toASCIIString())
        .when()
            .request("MOVE", sourceEventUri.toASCIIString())
        .then()
            .extract()
            .response();
    }

    private int getEventStatus(OpenPaasUser user, URI eventUri) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
        .when()
            .get(eventUri.toASCIIString())
        .then()
            .extract()
            .statusCode();
    }

    private DavResponse findEventsByTime(OpenPaasUser user, CalendarURL calendarURL) {
        return calDavClient.findEventsByTime(user, calendarURL, "20300110T000000", "20300110T235959");
    }

    private Response reportEventsByTime(OpenPaasUser user, CalendarURL calendarURL) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
            .header("Depth", 0)
            .header("Accept", "application/json")
            .body("""
                {
                    "match": {
                        "start": "20300110T000000",
                        "end": "20300110T235959"
                    }
                }
                """)
        .when()
            .request("REPORT", calendarURL.asUri() + ".json")
        .then()
            .statusCode(200)
            .extract()
            .response();
    }

}
