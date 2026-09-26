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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Raw CalDAV (GET, PROPFIND, REPORT, export) access to team calendars, with a focus on
 * classified (PRIVATE / CONFIDENTIAL) events: they must be fully visible to team members
 * and only to them. Public readers and public subscribers must only get a reduced version.
 */
public abstract class TeamCalendarRawCalDavContract {
    private static final String PUBLIC_READ_RIGHT = "{DAV:}read";
    private static final String NO_PUBLIC_RIGHT = "";
    private static final List<String> EVENT_CLASSES = List.of("PUBLIC", "PRIVATE", "CONFIDENTIAL");
    private static final List<String> CLASSIFIED_EVENT_CLASSES = List.of("PRIVATE", "CONFIDENTIAL");

    enum RawCalDavRead {
        GET(200) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.get(calendarURL.eventHref(eventUid).toString());
            }
        },
        EXPORT(200) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.get(calendarURL.asUri() + "?export");
            }
        },
        PROPFIND_CALENDAR_DATA(207) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.header("Depth", 1)
                    .header("Content-Type", "application/xml")
                    .body("""
                        <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                          <d:prop>
                            <d:getetag/>
                            <c:calendar-data/>
                          </d:prop>
                        </d:propfind>""")
                    .request("PROPFIND", calendarURL.asUri().toString());
            }
        },
        CALENDAR_QUERY(207) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.header("Depth", 1)
                    .header("Content-Type", "application/xml")
                    .body("""
                        <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                            <d:prop>
                                <d:getetag/>
                                <c:calendar-data/>
                            </d:prop>
                            <c:filter>
                                <c:comp-filter name="VCALENDAR">
                                    <c:comp-filter name="VEVENT">
                                        <c:time-range start="20300110T000000Z" end="20300111T000000Z"/>
                                    </c:comp-filter>
                                </c:comp-filter>
                            </c:filter>
                        </c:calendar-query>""")
                    .request("REPORT", calendarURL.asUri().toString());
            }
        },
        CALENDAR_MULTIGET(207) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.header("Depth", 1)
                    .header("Content-Type", "application/xml")
                    .body("""
                        <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                            <d:prop>
                                <d:getetag/>
                                <c:calendar-data/>
                            </d:prop>
                            <d:href>{eventHref}</d:href>
                        </c:calendar-multiget>""".replace("{eventHref}", calendarURL.eventHref(eventUid).toString()))
                    .request("REPORT", calendarURL.asUri().toString());
            }
        },
        SYNC_COLLECTION(207) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.header("Content-Type", "application/xml")
                    .body("""
                        <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                            <d:sync-token/>
                            <d:sync-level>1</d:sync-level>
                            <d:prop>
                                <d:getetag/>
                                <c:calendar-data/>
                            </d:prop>
                        </d:sync-collection>""")
                    .request("REPORT", calendarURL.asUri().toString());
            }
        },
        JSON_TIME_RANGE_REPORT(200) {
            @Override
            Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid) {
                return request.header("Depth", 0)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body("""
                        {
                            "match": {
                                "start": "20300110T000000",
                                "end": "20300110T235959"
                            }
                        }""")
                    .request("REPORT", calendarURL.asUri() + ".json");
            }
        };

        private final int expectedStatus;

        RawCalDavRead(int expectedStatus) {
            this.expectedStatus = expectedStatus;
        }

        abstract Response execute(RequestSpecification request, CalendarURL calendarURL, String eventUid);

        Response execute(OpenPaasUser user, CalendarURL calendarURL, String eventUid) {
            return execute(given().header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email())),
                calendarURL, eventUid)
                .then()
                .extract()
                .response();
        }
    }

    record TeamEvent(String uid, String eventClass, String organizerEmail, String attendeeEmail) {
        static TeamEvent of(String eventClass, OpenPaasUser organizer, OpenPaasUser attendee) {
            return new TeamEvent("team-event-" + UUID.randomUUID(), eventClass, organizer.email(), attendee.email());
        }

        String summary() {
            return "Sensitive " + eventClass + " team event";
        }

        String description() {
            return "Sensitive " + eventClass + " team details";
        }

        String location() {
            return "Sensitive " + eventClass + " room";
        }

        String[] sensitiveDetails() {
            return new String[] {summary(), description(), location(), attendeeEmail};
        }

        String asIcs() {
            return """
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Example Corp.//CalDAV Client//EN
                BEGIN:VEVENT
                UID:{uid}
                DTSTAMP:20300101T080000Z
                DTSTART:20300110T090000Z
                DTEND:20300110T100000Z
                ORGANIZER:mailto:{organizerEmail}
                ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{attendeeEmail}
                SUMMARY:{summary}
                DESCRIPTION:{description}
                LOCATION:{location}
                CLASS:{eventClass}
                END:VEVENT
                END:VCALENDAR
                """.replace("{uid}", uid)
                .replace("{organizerEmail}", organizerEmail)
                .replace("{attendeeEmail}", attendeeEmail)
                .replace("{summary}", summary())
                .replace("{description}", description())
                .replace("{location}", location())
                .replace("{eventClass}", eventClass);
        }
    }

    private CalDavClient calDavClient;
    private OpenPaaSTeamCalendar teamCalendar;
    private OpenPaasUser alice;
    private OpenPaasUser bob;
    private OpenPaasUser charlie;
    private OpenPaasUser attendee;
    private CalendarURL aliceDelegatedCalendar;
    private CalendarURL bobDelegatedCalendar;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    static Stream<Arguments> readsOfEveryEventClass() {
        return combine(EVENT_CLASSES);
    }

    static Stream<Arguments> readsOfClassifiedEvents() {
        return combine(CLASSIFIED_EVENT_CLASSES);
    }

    private static Stream<Arguments> combine(List<String> eventClasses) {
        return Arrays.stream(RawCalDavRead.values())
            .flatMap(read -> eventClasses.stream().map(eventClass -> Arguments.of(read, eventClass)));
    }

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

        // Alice (read-write) and Bob (read-only) are team calendar members, Charlie is not
        alice = dockerExtension().newTestUser();
        bob = dockerExtension().newTestUser();
        charlie = dockerExtension().newTestUser();
        attendee = dockerExtension().newTestUser();
        teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();
        aliceDelegatedCalendar = delegateTeamCalendarTo(alice, DelegationRight.READ_WRITE);
        bobDelegatedCalendar = delegateTeamCalendarTo(bob, DelegationRight.READ);
    }

    @ParameterizedTest(name = "{0} of a {1} event")
    @MethodSource("readsOfEveryEventClass")
    void teamMemberShouldReadFullEventThroughDelegatedCalendar(RawCalDavRead read, String eventClass) {
        // Given Alice creates an event in a public-read team calendar
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        TeamEvent event = createTeamEvent(eventClass);

        // When Bob, a team member, reads it through his delegated team calendar
        Response response = read.execute(bob, bobDelegatedCalendar, event.uid());

        // Then Bob sees all the event details
        assertFullyVisible(response, read, event);
    }

    @ParameterizedTest(name = "{0} of a {1} event")
    @MethodSource("readsOfEveryEventClass")
    void teamMemberShouldReadFullEventThroughCanonicalCalendar(RawCalDavRead read, String eventClass) {
        // Given Alice creates an event in a public-read team calendar
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        TeamEvent event = createTeamEvent(eventClass);

        // When Bob, a team member, reads it through the canonical team calendar URL
        Response response = read.execute(bob, CalendarURL.from(teamCalendar.id()), event.uid());

        // Then Bob sees all the event details
        assertFullyVisible(response, read, event);
    }

    @ParameterizedTest(name = "{0} of a {1} event")
    @MethodSource("readsOfEveryEventClass")
    void teamMemberShouldReadFullEventOfPrivateTeamCalendar(RawCalDavRead read, String eventClass) {
        // Given Alice creates an event in a team calendar without public rights
        calDavClient.updateTeamCalendarAcl(teamCalendar, NO_PUBLIC_RIGHT);
        TeamEvent event = createTeamEvent(eventClass);

        // When Bob, a team member, reads it through his delegated team calendar
        Response response = read.execute(bob, bobDelegatedCalendar, event.uid());

        // Then Bob sees all the event details
        assertFullyVisible(response, read, event);
    }

    @ParameterizedTest(name = "{0} of a {1} event")
    @MethodSource("readsOfClassifiedEvents")
    void publicReaderShouldNotReadClassifiedEventDetails(RawCalDavRead read, String eventClass) {
        // Given Alice creates a classified event in a public-read team calendar
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        TeamEvent event = createTeamEvent(eventClass);

        // When Charlie, who is not a member, reads it through the canonical team calendar URL
        Response response = read.execute(charlie, CalendarURL.from(teamCalendar.id()), event.uid());

        // Then Charlie only sees a reduced version of the event
        assertReduced(response, read, event);
    }

    @ParameterizedTest(name = "{0} of a {1} event")
    @MethodSource("readsOfClassifiedEvents")
    void publicSubscriberShouldNotReadClassifiedEventDetails(RawCalDavRead read, String eventClass) {
        // Given Alice creates a classified event in a public-read team calendar
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        TeamEvent event = createTeamEvent(eventClass);

        // And Charlie, who is not a member, subscribes to the team calendar
        CalendarURL subscription = subscribeToTeamCalendar(charlie);

        // When Charlie reads the event through his subscription
        Response response = read.execute(charlie, subscription, event.uid());

        // Then Charlie only sees a reduced version of the event
        assertReduced(response, read, event);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RawCalDavRead.class)
    void nonMemberShouldNotReadEventOfPrivateTeamCalendar(RawCalDavRead read) {
        // Given Alice creates a classified event in a team calendar without public rights
        calDavClient.updateTeamCalendarAcl(teamCalendar, NO_PUBLIC_RIGHT);
        TeamEvent event = createTeamEvent("PRIVATE");

        // When Charlie, who is not a member, reads it through the canonical team calendar URL
        Response response = read.execute(charlie, CalendarURL.from(teamCalendar.id()), event.uid());

        // Then Charlie is denied
        assertThat(response.statusCode())
            .as("Non-member should not %s a private team calendar", read)
            .isIn(403, 404);
        assertThat(response.body().asString())
            .as("Non-member should not see any event detail with %s on a private team calendar", read)
            .doesNotContain(event.sensitiveDetails());
    }

    @Test
    void publicReaderFreeBusyQueryShouldNotExposePrivateEventDetails() {
        // Given Alice creates a private event in a public-read team calendar
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        TeamEvent event = createTeamEvent("PRIVATE");

        // When Charlie, who is not a member, requests free/busy on the team calendar
        Response response = freeBusyQuery(charlie, CalendarURL.from(teamCalendar.id()));

        // Then Charlie only sees busy time
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().asString())
            .contains("FREEBUSY:20300110T090000Z/20300110T100000Z")
            .doesNotContain(event.uid())
            .doesNotContain(event.sensitiveDetails());
    }

    @Test
    void readWriteTeamMemberShouldUpdateEventThroughRawPut() {
        // Given Alice created an event in the team calendar
        TeamEvent event = createTeamEvent("PRIVATE");
        TeamEvent updatedEvent = new TeamEvent(event.uid(), "PUBLIC", alice.email(), attendee.email());

        // When Alice updates it with a raw CalDAV PUT
        Response putResponse = put(alice, aliceDelegatedCalendar, updatedEvent);

        // Then the update is visible to Bob, another team member
        assertThat(putResponse.statusCode()).isIn(201, 204);
        assertFullyVisible(RawCalDavRead.GET.execute(bob, bobDelegatedCalendar, event.uid()),
            RawCalDavRead.GET, updatedEvent);
    }

    @Test
    void readWriteTeamMemberShouldDeleteEventThroughRawDelete() {
        // Given Alice created an event in the team calendar
        TeamEvent event = createTeamEvent("PRIVATE");

        // When Alice deletes it with a raw CalDAV DELETE
        Response deleteResponse = delete(alice, aliceDelegatedCalendar, event);

        // Then the event is gone for Bob, another team member
        assertThat(deleteResponse.statusCode()).isEqualTo(204);
        assertThat(RawCalDavRead.GET.execute(bob, bobDelegatedCalendar, event.uid()).statusCode())
            .isEqualTo(404);
    }

    @Test
    void readOnlyTeamMemberShouldNotDeleteEventThroughRawDelete() {
        // Given Alice created an event in the team calendar
        TeamEvent event = createTeamEvent("PRIVATE");

        // When Bob, a read-only team member, deletes it with a raw CalDAV DELETE
        Response deleteResponse = delete(bob, bobDelegatedCalendar, event);

        // Then the deletion is rejected and the event is still there
        assertThat(deleteResponse.statusCode()).isEqualTo(403);
        assertThat(RawCalDavRead.GET.execute(alice, aliceDelegatedCalendar, event.uid()).statusCode())
            .isEqualTo(200);
    }

    @Test
    void publicSubscriberShouldNotDeleteEventThroughRawDelete() {
        // Given Alice created an event in a public-read team calendar Charlie subscribed to
        calDavClient.updateTeamCalendarAcl(teamCalendar, PUBLIC_READ_RIGHT);
        TeamEvent event = createTeamEvent("PRIVATE");
        CalendarURL subscription = subscribeToTeamCalendar(charlie);

        // When Charlie deletes it through his subscription
        Response deleteResponse = delete(charlie, subscription, event);

        // Then the deletion is rejected and the event is still there
        assertThat(deleteResponse.statusCode()).isIn(403, 404, 405);
        assertThat(RawCalDavRead.GET.execute(alice, aliceDelegatedCalendar, event.uid()).statusCode())
            .isEqualTo(200);
    }

    private void assertFullyVisible(Response response, RawCalDavRead read, TeamEvent event) {
        assertThat(response.statusCode())
            .as("%s should succeed for a team member", read)
            .isEqualTo(read.expectedStatus);
        assertThat(response.body().asString())
            .as("Team member should see all the details of a %s event with %s", event.eventClass(), read)
            .contains(event.uid())
            .contains(event.sensitiveDetails());
    }

    private void assertReduced(Response response, RawCalDavRead read, TeamEvent event) {
        assertThat(response.statusCode())
            .as("%s should succeed for a public reader", read)
            .isEqualTo(read.expectedStatus);
        assertThat(response.body().asString())
            .as("Public reader should only see a reduced version of a %s event with %s", event.eventClass(), read)
            .contains(event.uid())
            .doesNotContain(event.sensitiveDetails());
    }

    private TeamEvent createTeamEvent(String eventClass) {
        TeamEvent event = TeamEvent.of(eventClass, alice, attendee);
        calDavClient.upsertCalendarEvent(alice, aliceDelegatedCalendar, event.uid(), event.asIcs());
        return event;
    }

    private CalendarURL delegateTeamCalendarTo(OpenPaasUser user, DelegationRight right) {
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), user, right, technicalToken);
        return calDavClient.findDelegatedCalendar(user, teamCalendar.id());
    }

    private CalendarURL subscribeToTeamCalendar(OpenPaasUser subscriber) {
        return calDavClient.subscribeToSharedCalendar(subscriber, SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(teamCalendar.id())
            .name("Operations Team mirror")
            .color("#00FF00")
            .readOnly(true)
            .build());
    }

    private Response put(OpenPaasUser user, CalendarURL calendarURL, TeamEvent event) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
            .header("Content-Type", "text/calendar ; charset=utf-8")
            .body(event.asIcs())
        .when()
            .put(calendarURL.eventHref(event.uid()).toString())
        .then()
            .extract()
            .response();
    }

    private Response delete(OpenPaasUser user, CalendarURL calendarURL, TeamEvent event) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
        .when()
            .delete(calendarURL.eventHref(event.uid()).toString())
        .then()
            .extract()
            .response();
    }

    private Response freeBusyQuery(OpenPaasUser user, CalendarURL calendarURL) {
        return given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(user.email()))
            .header("Depth", 1)
            .header("Content-Type", "application/xml")
            .body("""
                <c:free-busy-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <c:time-range start="20300110T000000Z" end="20300111T000000Z"/>
                </c:free-busy-query>""")
        .when()
            .request("REPORT", calendarURL.asUri().toString())
        .then()
            .extract()
            .response();
    }
}
