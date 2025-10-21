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

import static com.linagora.dav.contracts.CalendarSharingContract.MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.JsonCalendarEventData;
import com.linagora.dav.OpenPaasUser;

public abstract class ITIPRequestContract {
    private static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollDelay(Duration.ofSeconds(2))
        .pollInterval(Duration.ofSeconds(1))
        .await();

    public static final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    public abstract DockerTwakeCalendarExtension extension();

    private CalDavClient calDavClient;

    private OpenPaasUser bob;
    private OpenPaasUser cedric;

    private String bobCustomCalendarId;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(extension().davHttpClient());
        extension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .enableSharedCalendarModule()
            .block();

        bob = extension().newTestUser();
        cedric = extension().newTestUser();

        bobCustomCalendarId = UUID.randomUUID().toString();

        calDavClient.createNewCalendar(bob, bobCustomCalendarId, "Bob Custom Calendar", 2);
    }

    @Test
    void itipRequestShouldResultInEventInDefaultCalendar() {
        // GIVEN Cedric and Bob
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting from Cedric
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        // WHEN sends an ITIP REQUEST to Bob
        String bobCalendarUri = "/calendars/" + bob.id();
        String body = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), body).block();

        Function<String, List<JsonNode>> bobEventsForUri = (uri) ->
            calDavClient.reportCalendarEvents(bob, uri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList()
                .block();

        // THEN. Bob’s default calendar should have the event
        String bobDefaultCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();

        awaitAtMost.untilAsserted(() -> assertThat(bobEventsForUri.apply(bobDefaultCalendarUri))
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + bob.email());
                assertThat(json).contains("Meeting from Cedric");
            }));

        // THEN. Bob’s custom calendar should remain empty
        String bobCustomCalendarUri = "/calendars/" + bob.id() + "/" + bobCustomCalendarId;
        List<JsonNode> customCalendarEvents = bobEventsForUri.apply(bobCustomCalendarUri);
        assertThat(customCalendarEvents).isEmpty();
    }

    @Test
    void itipUpdateShouldModifyEventInDefaultCalendar() {
        // GIVEN Cedric event with Bob
        String eventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Initial Meeting
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        calDavClient.upsertCalendarEvent(bob, URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics"), initialIcs);

        Function<String, List<JsonNode>> bobEventsForUri = uri -> calDavClient.reportCalendarEvents(
                bob, uri,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList()
            .block();

        String bobDefaultCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();

        // Ensure initial event is present
        List<JsonNode> beforeUpdate = bobEventsForUri.apply(bobDefaultCalendarUri);
        assertThat(beforeUpdate).anySatisfy(item -> {
            String json = item.toString();
            assertThat(json).contains(eventUid);
            assertThat(json).contains("Initial Meeting");
        });

        // WHEN sends an update (change time and summary)
        String updatedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251006T090000Z
            DTEND:20251006T100000Z
            SUMMARY:Updated Meeting
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String updatedBody = ITIPJsonBodyRequest.builder()
            .ical(updatedIcs)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), updatedBody).block();

        // THEN Bob’s default calendar should be updated accordingly
        awaitAtMost.untilAsserted(() -> assertThat(bobEventsForUri.apply(bobDefaultCalendarUri))
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("Updated Meeting");
                assertThat(json).doesNotContain("Initial Meeting");
            }));
    }

    @Test
    void itipCancelShouldRemoveEventFromDefaultCalendar() {
        // GIVEN Cedric an event with Bob
        String eventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting To Be Cancelled
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String bobCalendarEventUri = "/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(bob, URI.create(bobCalendarEventUri), initialIcs);

        Function<String, List<JsonNode>> bobEventsForUri = (uri) ->
            calDavClient.reportCalendarEvents(bob, uri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList()
                .block();

        String bobDefaultCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();

        // Ensure event is present initially
        List<JsonNode> beforeCancel = bobEventsForUri.apply(bobDefaultCalendarUri);
        assertThat(beforeCancel).anySatisfy(item -> {
            String json = item.toString();
            assertThat(json).contains(eventUid);
            assertThat(json).contains("Meeting To Be Cancelled");
        });

        // WHEN Cedric sends an ITIP CANCEL
        String cancelIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:CANCEL
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080100Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting To Be Cancelled
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String cancelBody = ITIPJsonBodyRequest.builder()
            .ical(cancelIcs)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("CANCEL")
            .buildJson();
        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), cancelBody).block();

        // THEN Bob’s default calendar should reflect cancellation
        awaitAtMost.untilAsserted(() -> assertThat(bobEventsForUri.apply(bobDefaultCalendarUri))
            .filteredOn(item -> item.toString().contains(eventUid))
            .allSatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains("CANCELLED");
            }));
    }

    @Test
    void itipReplyShouldUpdateOrganizerCalendarWithAttendeePartStat() {
        // GIVEN Cedric created an event with Bob
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting with Bob
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String organizerEventUri = "/calendars/" + cedric.id() + "/" + cedric.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(cedric, URI.create(organizerEventUri), ics);

        Function<String, List<JsonNode>> cedricEventsForUri = uri ->
            calDavClient.reportCalendarEvents(cedric, uri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList()
                .block();

        // Ensure Cedric has the event
        String cedricDefaultCalendarUri = "/calendars/" + cedric.id() + "/" + cedric.id();
        List<JsonNode> beforeReply = cedricEventsForUri.apply(cedricDefaultCalendarUri);
        assertThat(beforeReply).anySatisfy(item -> {
            String json = item.toString();
            assertThat(json).contains(eventUid);
            assertThatJson(item)
                .inPath("data[2][0][1]")
                .isArray()
                .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                    .isEqualTo("""
                          [
                          "attendee",
                          {
                            "cn": "Bob",
                            "partstat": "NEEDS-ACTION",
                            "schedule-status": "${json-unit.ignore}"
                          },
                          "cal-address",
                          "mailto:%s"
                        ]""".formatted(bob.email())));
        });

        // WHEN sends a REPLY (ACCEPTED)
        String replyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REPLY
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080500Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting with Bob
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String replyBody = ITIPJsonBodyRequest.builder()
            .ical(replyIcs)
            .sender(bob.email())
            .recipient(cedric.email())
            .uid(eventUid)
            .method("REPLY")
            .buildJson();

        String cedricCalendarUri = "/calendars/" + cedric.id();
        calDavClient.sendITIPRequest(cedric, URI.create(cedricCalendarUri), replyBody).block();

        // THEN Cedric’s calendar should reflect Bob’s partstat = ACCEPTED
        awaitAtMost.untilAsserted(() -> assertThat(cedricEventsForUri.apply(cedricDefaultCalendarUri)).anySatisfy(item -> {
            String json = item.toString();
            assertThat(json).contains(eventUid);
            assertThatJson(item)
                .inPath("data[2][0][1]")
                .isArray()
                .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                    .isEqualTo("""
                          [
                          "attendee",
                          {
                            "cn": "Bob",
                            "partstat": "ACCEPTED",
                            "schedule-status": "${json-unit.ignore}"
                          },
                          "cal-address",
                          "mailto:%s"
                        ]""".formatted(bob.email())));
        }));
    }

    @Disabled("Sabre allows any user to send ITIP to another user's calendar, should be forbidden. See https://github.com/linagora/esn-sabre/issues/49")
    @Test
    void itipShouldNotBeAllowedWhenSenderHasNoWriteAccess() throws Exception {
        // GIVEN Cedric and Bob, and Cedric has no write access to Bob’s calendars
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Unauthorized Meeting
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String body = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        // WHEN Cedric sends an ITIP REQUEST to Bob’s calendar
        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(cedric, URI.create(bobCalendarUri), body).block();
        Thread.sleep(1000);

        // THEN (expected in the future): should fail with permission denied or 403
        // BUT current Sabre behavior: it still creates the event
        // So we just assert this unexpected behavior to document it
        List<JsonNode> events = calDavClient.reportCalendarEvents(bob, bobCalendarUri + "/" + bob.id(),
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList()
            .block();

        assertThat(events)
            .as("Currently Sabre still allows unauthorized ITIP creation (see issue #49)")
            .isNotEmpty();
    }

    @Disabled("Sabre currently ignores the specified calendar path. See https://github.com/linagora/esn-sabre/issues/56")
    @Test
    void itipShouldRespectSpecifiedCalendarPath() throws Exception {
        // GIVEN Bob has multiple calendars (default + custom)
        String calendarA = bob.id(); // default calendar
        String calendarB = bobCustomCalendarId;

        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Meeting on Specific Calendar
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        // WHEN Cedric sends an ITIP REQUEST explicitly targeting Bob’s calendarB
        String body = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        String bobCalendarBUri = "/calendars/" + bob.id() + "/" + calendarB;
        calDavClient.sendITIPRequest(cedric, URI.create(bobCalendarBUri), body).block();

        // THEN the event should appear in the specific target calendar (calendarB)
        Function<String, List<JsonNode>> eventsForUri = uri ->
            calDavClient.reportCalendarEvents(bob, uri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList()
                .block();

        Thread.sleep(1000);
        String bobDefaultCalendarUri = "/calendars/" + bob.id() + "/" + calendarA;
        List<JsonNode> eventsInDefault = eventsForUri.apply(bobDefaultCalendarUri);
        List<JsonNode> eventsInB = eventsForUri.apply("/calendars/" + bob.id() + "/" + calendarB);

        assertThat(eventsInB)
            .as("Expected the ITIP event to appear in the explicitly targeted calendarB")
            .anySatisfy(item -> assertThat(item.toString()).contains(eventUid));

        assertThat(eventsInDefault)
            .as("Default calendar should not receive this event once issue #49 is fixed")
            .noneSatisfy(item -> assertThat(item.toString()).contains(eventUid));
    }

    @Test
    void itipCancelShouldRemoveOnlyExceptionFromRecurringEvent() throws JsonProcessingException {
        // GIVEN Bob has an event with organizer Cedric
        String eventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{eventUid}
            RRULE:FREQ=DAILY;COUNT=3
            DTSTAMP:20251002T100000Z
            DTSTART:20251003T090000Z
            DTEND:20251003T100000Z
            SUMMARY:Sprint planning
            ORGANIZER;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:{bobEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            RECURRENCE-ID:20251004T090000Z
            DTSTAMP:20251002T100000Z
            DTSTART:20251004T100000Z
            DTEND:20251004T110000Z
            SUMMARY:Exception
            ORGANIZER;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{cedricEmail}", cedric.email())
            .replace("{bobEmail}", bob.email());

        calDavClient.upsertCalendarEvent(bob, CalendarURL.from(bob.id()), eventUid, initialIcs);

        // Ensure event is present initially
        DavResponse response = calDavClient.findEventsByTime(bob,
            CalendarURL.from(bob.id()),
            "20250110T000000",
            "20251210T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(3);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning");
        assertThat(result.get(0).recurrenceId().get()).isEqualTo("2025-10-03T09:00:00Z");

        assertThat(result.get(1).uid()).isEqualTo(eventUid);
        assertThat(result.get(1).summary().get()).isEqualTo("Exception");
        assertThat(result.get(1).recurrenceId().get()).isEqualTo("2025-10-04T09:00:00Z");

        assertThat(result.get(2).uid()).isEqualTo(eventUid);
        assertThat(result.get(2).summary().get()).isEqualTo("Sprint planning");
        assertThat(result.get(2).recurrenceId().get()).isEqualTo("2025-10-05T09:00:00Z");

        // WHEN Cedric sends an ITIP CANCEL
        String cancelIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:CANCEL
            BEGIN:VEVENT
            UID:%s
            RECURRENCE-ID:20251004T090000Z
            DTSTAMP:20251003T120000Z
            DTSTART:20251004T100000Z
            DTEND:20251004T110000Z
            SUMMARY:Exception
            ORGANIZER;CN=Cedric:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email());

        String cancelBody = ITIPJsonBodyRequest.builder()
            .ical(cancelIcs)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("CANCEL")
            .buildJson();
        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), cancelBody).block();

        // THEN Bob’s default calendar should reflect cancellation of the specific instance and keep the others
        awaitAtMost.untilAsserted(() -> {
            DavResponse response2 = calDavClient.findEventsByTime(bob,
                CalendarURL.from(bob.id()),
                "20250110T000000",
                "20251210T000000");
            List<JsonCalendarEventData> result2 = JsonCalendarEventData.from(response2.body());

            assertThat(result2).hasSize(2);
            assertThat(result2.get(0).uid()).isEqualTo(eventUid);
            assertThat(result2.get(0).summary().get()).isEqualTo("Sprint planning");
            assertThat(result2.get(0).recurrenceId().get()).isEqualTo("2025-10-03T09:00:00Z");

            assertThat(result2.get(1).uid()).isEqualTo(eventUid);
            assertThat(result2.get(1).summary().get()).isEqualTo("Sprint planning");
            assertThat(result2.get(1).recurrenceId().get()).isEqualTo("2025-10-05T09:00:00Z");
        });
    }
}