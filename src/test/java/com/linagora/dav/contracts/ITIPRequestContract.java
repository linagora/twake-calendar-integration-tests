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

import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.contracts.CalendarSharingContract.MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.JsonCalendarEventData;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.XMLUtil;

import io.netty.handler.codec.http.HttpMethod;
import net.fortuna.ical4j.model.Calendar;

public abstract class ITIPRequestContract {
    private static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollDelay(Duration.ofSeconds(2))
        .pollInterval(Duration.ofSeconds(1))
        .await();

    public static final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    public abstract DockerTwakeCalendarExtension extension();

    private CalDavClient calDavClient;

    private OpenPaasUser alice;
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

        alice = extension().newTestUser();
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
    @Test
    void itipShouldPropagateAttendanceUpdatesToOtherAttendants() {
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
            ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email(), alice.email());

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
            ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, cedric.email(), bob.email(), alice.email());

        String replyBody = ITIPJsonBodyRequest.builder()
            .ical(replyIcs)
            .sender(bob.email())
            .recipient(cedric.email())
            .uid(eventUid)
            .method("REPLY")
            .buildJson();

        String cedricCalendarUri = "/calendars/" + cedric.id();
        calDavClient.sendITIPRequest(cedric, URI.create(cedricCalendarUri), replyBody).block();

        // THEN Alice’s calendar should reflect Bob’s partstat = ACCEPTED
        String aliceDefaultCalendarUri = "/calendars/" + alice.id() + "/" + alice.id();
        Function<String, List<JsonNode>> aliceEventsForUri = uri ->
            calDavClient.reportCalendarEvents(alice, uri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList()
                .block();
        awaitAtMost.untilAsserted(() -> assertThat(aliceEventsForUri.apply(aliceDefaultCalendarUri)).anySatisfy(item -> {
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
                            "partstat": "ACCEPTED"
                          },
                          "cal-address",
                          "mailto:%s"
                        ]""".formatted(bob.email())));
        }));
    }

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

        // WHEN Cedric sends an ITIP CANCEL for the exception only
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

    @Test
    void itipCancelShouldRemoveInstancesFromRecurringEvent() throws JsonProcessingException {
        // GIVEN Bob has an event with organizer Cedric
        String eventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{eventUid}
            RRULE:FREQ=DAILY;COUNT=4
            DTSTAMP;TZID=Asia/Ho_Chi_Minh:20251002T100000
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251003T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251003T100000
            SUMMARY:Sprint planning
            ORGANIZER;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:{bobEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20251004T090000
            DTSTAMP;TZID=Asia/Ho_Chi_Minh:20251002T100000
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251004T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251004T110000
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

        assertThat(result).hasSize(4);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning");
        assertThat(result.get(0).recurrenceId().get()).isEqualTo("2025-10-03T02:00:00Z");

        assertThat(result.get(1).uid()).isEqualTo(eventUid);
        assertThat(result.get(1).summary().get()).isEqualTo("Exception");
        assertThat(result.get(1).recurrenceId().get()).isEqualTo("2025-10-04T02:00:00Z");

        assertThat(result.get(2).uid()).isEqualTo(eventUid);
        assertThat(result.get(2).summary().get()).isEqualTo("Sprint planning");
        assertThat(result.get(2).recurrenceId().get()).isEqualTo("2025-10-05T02:00:00Z");

        assertThat(result.get(3).uid()).isEqualTo(eventUid);
        assertThat(result.get(3).summary().get()).isEqualTo("Sprint planning");
        assertThat(result.get(3).recurrenceId().get()).isEqualTo("2025-10-06T02:00:00Z");

        // WHEN Cedric sends an ITIP CANCEL for two instances (one in base event and one is exception)
        String cancelIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:CANCEL
            BEGIN:VEVENT
            UID:{eventUid}
            RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20251003T090000
            DTSTAMP;TZID=Asia/Ho_Chi_Minh:20251003T120000
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251003T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251003T100000
            SUMMARY:Sprint planning
            ORGANIZER;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:{bobEmail}
            STATUS:CANCELLED
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20251004T090000
            DTSTAMP;TZID=Asia/Ho_Chi_Minh:20251003T120000
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251004T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251004T110000
            SUMMARY:Exception
            ORGANIZER;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:{bobEmail}
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{cedricEmail}", cedric.email())
            .replace("{bobEmail}", bob.email());

        String cancelBody = ITIPJsonBodyRequest.builder()
            .ical(cancelIcs)
            .sender(cedric.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("CANCEL")
            .buildJson();
        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), cancelBody).block();

        // THEN Bob’s default calendar should reflect cancellation of the specific instances and keep the others
        awaitAtMost.untilAsserted(() -> {
            DavResponse response2 = calDavClient.findEventsByTime(bob,
                CalendarURL.from(bob.id()),
                "20250110T000000",
                "20251210T000000");
            List<JsonCalendarEventData> result2 = JsonCalendarEventData.from(response2.body());

            assertThat(result2).hasSize(2);
            assertThat(result2.get(0).uid()).isEqualTo(eventUid);
            assertThat(result2.get(0).summary().get()).isEqualTo("Sprint planning");
            assertThat(result2.get(0).recurrenceId().get()).isEqualTo("2025-10-05T02:00:00Z");

            assertThat(result2.get(1).uid()).isEqualTo(eventUid);
            assertThat(result2.get(1).summary().get()).isEqualTo("Sprint planning");
            assertThat(result2.get(1).recurrenceId().get()).isEqualTo("2025-10-06T02:00:00Z");
        });
    }

    @Test
    void shouldReceiveSingleItipRequestWhenInvitedToSpecificOccurrence() {
        // GIVEN Bob (organizer) creates a recurring event and invites Cedric to the second occurrence
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        // WHEN the iTIP messages to Cedric’s inbox
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // THEN Cedric’s inbox should contain exactly one iTIP REQUEST for occurrence #2
        String cedricInboxCollection = "/calendars/" + cedric.id() + "/inbox";
        List<String> hrefsFromPropfind = awaitCalendarEntries(cedric, cedricInboxCollection, 1);
        String calendarInboxEventIcs = calDavClient.getCalendarEvent(cedric, URI.create(hrefsFromPropfind.getFirst()));

        Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(calendarInboxEventIcs);

        String expectedInboxIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email())
            .trim();

        Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedInboxIcs);
        assertThat(actualCalendar)
            .isEqualTo(expectedCalendar);
    }

    @Test
    void shouldStoreSingleOccurrenceInDefaultCalendarWhenInvitedToSpecificOccurrence() {
        // GIVEN Bob invites Cedric only for the second occurrence
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // WHEN Cedric checks his default calendar
        String cedricDefaultCalendar = "/calendars/" + cedric.id() + "/" + cedric.id();
        List<String> hrefsFromPropfind = awaitCalendarEntries(cedric, cedricDefaultCalendar, 1);

        String actualEventIcs = calDavClient.getCalendarEvent(cedric, URI.create(hrefsFromPropfind.getFirst()));
        Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(actualEventIcs);

        // THEN Cedric’s calendar should contain only the invited occurrence, without recurrence rules
        String expectedCalendarIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email())
            .trim();

        Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedCalendarIcs);

        // Assert equality (logical content, ignoring order or minor formatting)
        assertThat(actualCalendar)
            .as("Cedric's calendar should contain only the invited occurrence (#2), no recurrence rule")
            .isEqualTo(expectedCalendar);
    }

    @Test
    void shouldNotDuplicateEventInOrganizerCalendarAfterSingleOccurrenceReplyOfRecurrenceEvent() {
        // GIVEN Bob invites Cedric to the second occurrence
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // WHEN Cedric accepts the invitation (sends REPLY)
        String cedricDefaultCalendar = "/calendars/" + cedric.id() + "/" + cedric.id();
        List<String> hrefs = awaitCalendarEntries(cedric, cedricDefaultCalendar, 1);
        String eventHref = hrefs.getFirst();
        String calendarEvent = calDavClient.getCalendarEvent(cedric, URI.create(eventHref));

        calDavClient.upsertCalendarEvent(cedric, URI.create(eventHref),
            calendarEvent.replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED"));

        // THEN Bob receives one REPLY and his calendar reflects Cedric’s acceptance without duplication
        String bobInbox = "/calendars/" + bob.id() + "/inbox";
        List<String> hrefsFromPropfind = awaitCalendarEntries(bob, bobInbox, 1);
        String actualInboxIcs = calDavClient.getCalendarEvent(bob, URI.create(hrefsFromPropfind.getFirst()));
        Calendar actualReplyCalendar = CalendarUtil.parseIcsAndSanitize(actualInboxIcs);

        String expectedReplyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REPLY
            BEGIN:VEVENT
            UID:{UID}
            SEQUENCE:0
            DTSTAMP:20260320T081000Z
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            SUMMARY:Daily meeting
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;PARTSTAT=ACCEPTED;CN=Cedric:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email())
            .trim();

        Calendar expectedReplyCalendar = CalendarUtil.parseIcsAndSanitize(expectedReplyIcs);
        assertThat(actualReplyCalendar)
            .as("Bob's inbox should contain a single REPLY iTIP for the invited occurrence")
            .isEqualTo(expectedReplyCalendar);

        // Bob's default calendar should not duplicate events
        String bobDefaultCalendar = "/calendars/" + bob.id() + "/" + bob.id();
        List<String> bobCalendarHrefs = awaitCalendarEntries(bob, bobDefaultCalendar, 1);
        assertThat(bobCalendarHrefs)
            .as("Bob's calendar should not have duplicate or extra copies of the event")
            .hasSize(1);

        // Verify Bob’s event instance updated correctly (Cedric ACCEPTED)
        String bobCalendarHref = bobCalendarHrefs.getFirst();
        String bobCalendarEventIcs = calDavClient.getCalendarEvent(bob, URI.create(bobCalendarHref));

        Calendar actualBobCalendar = CalendarUtil.parseIcsAndSanitize(bobCalendarEventIcs);
        assertThat(actualBobCalendar.toString())
            .contains("ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED");
    }

    @Test
    void shouldBundleMultipleInvitedOccurrencesIntoSingleRecurringEventInAttendeeCalendar() {
        // GIVEN Bob creates a recurring daily event
        String eventUid = "event-" + UUID.randomUUID();
        String bobEventUri = createRecurringEvent(bob, eventUid);

        // WHEN Bob invites Cedric to occurrence #2
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        String cedricInbox = "/calendars/" + cedric.id() + "/inbox";
        awaitCalendarEntries(cedric, cedricInbox, 1);

        // AND Bob later invites Cedric to occurrence #3 (add Cedric only to occurrence #3, do not modify summary/location/description)
        String inviteForThirdOccurrence = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260324T090000
            DTEND;TZID=Europe/Paris:20260324T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260324T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), inviteForThirdOccurrence);

        // Cedric receives another REQUEST (for occurrence #3)
        List<String> hrefs = awaitCalendarEntries(cedric, cedricInbox, 2);

        String finalIcs = calDavClient.getCalendarEvent(cedric, URI.create(hrefs.getLast()));

        // Assert: finalIcs contains the UID, both invited RECURRENCE-ID (#2, #3), and no duplicate events or VCALENDAR
        assertThat(finalIcs)
            .as("Cedric’s calendar should merge both invited occurrences (#2 and #3) into a single recurring event")
            .contains("UID:" + eventUid)
            .contains("METHOD:REQUEST")
            .contains("RECURRENCE-ID;TZID=Europe/Paris:20260323T090000")
            .contains("RECURRENCE-ID;TZID=Europe/Paris:20260324T090000");

        // Additional asserts: verify Cedric's default calendar content
        List<String> defaultCalendarHrefs = awaitCalendarEntries(cedric, "/calendars/" + cedric.id() + "/" + cedric.id(), 1);
        String cedricDefaultCalendarIcs = calDavClient.getCalendarEvent(cedric, URI.create(defaultCalendarHrefs.getFirst()));
        assertThat(cedricDefaultCalendarIcs)
            .as("Cedric's default calendar should have a merged event with both invited occurrences, no RRULE, and no duplication")
            .contains("UID:" + eventUid)
            .contains("RECURRENCE-ID;TZID=Europe/Paris:20260323T090000")
            .contains("RECURRENCE-ID;TZID=Europe/Paris:20260324T090000")
            .doesNotContain("RRULE");
    }

    @Disabled("SabreDav currently sends unwanted iTIP REQUEST even when organizer modifies a different occurrence that the attendee was not invited to." +
        "See https://github.com/linagora/esn-sabre/issues/152")
    @Test
    void shouldNotSendUpdateToUninvitedAttendeesWhenOrganizerModifiesOtherInstances() throws Exception {
        // GIVEN Bob invites Cedric only for occurrence #2
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // Ensure Cedric received the invite for occurrence #2
        String cedricInbox = "/calendars/" + cedric.id() + "/inbox";
        awaitCalendarEntries(cedric, cedricInbox, 1);

        // WHEN Bob modifies occurrence #3 (unrelated to Cedric)
        String updateThirdOccurrence = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting (updated third occurrence)
            LOCATION:Paris office
            DESCRIPTION:Updated recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260324T090000
            DTEND;TZID=Europe/Paris:20260324T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260324T090000
            SUMMARY:Updated instance (Day 3)
            LOCATION:Paris HQ
            DESCRIPTION:Bob modified only day 3
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email());

        String bobEventUri = "/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), updateThirdOccurrence);

        // WHEN: Cedric checks inbox again after Bob’s modification
        // THEN Cedric should not receive any new iTIP messages
        Thread.sleep(1000);
        Supplier<DavResponse> cedricInboxPropfind = () -> execute(extension().davHttpClient()
            .headers(cedric::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri(cedricInbox));
        List<String> hrefs = XMLUtil.extractCalendarHrefsFromPropfind(cedricInboxPropfind.get().body());
        assertThat(hrefs)
            .as("No new ITIP should be received by uninvited attendee when organizer edits unrelated instance")
            .hasSize(1);
    }

    @Test
    void shouldSendItipRequestWhenOrganizerUpdatesInvitedOccurrence() {
        // GIVEN Bob invites Cedric for occurrence #2
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // Cedric should initially receive one ITIP REQUEST for occurrence #2
        String cedricInbox = "/calendars/" + cedric.id() + "/inbox";
        awaitCalendarEntries(cedric, cedricInbox, 1);

        // WHEN Bob updates the time and summary for that occurrence
        String bobEventUri = "/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics";
        String updatedOccurrenceIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260323T100000
            DTEND;TZID=Europe/Paris:20260323T110000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Updated meeting with Cedric
            LOCATION:Paris office
            DESCRIPTION:Updated time and summary
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), updatedOccurrenceIcs);

        // THEN Cedric receives an updated iTIP REQUEST for the modified occurrence
        List<String> inboxAfterUpdate = awaitCalendarEntries(cedric, cedricInbox, 2);
        assertThat(inboxAfterUpdate)
            .as("Cedric should receive an ITIP update for the modified occurrence")
            .hasSize(2);

        URI latestInboxUri = URI.create(inboxAfterUpdate.getLast());
        String actualInboxIcs = calDavClient.getCalendarEvent(cedric, latestInboxUri);
        Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(actualInboxIcs);
        String expectedInboxIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260323T100000
            DTEND;TZID=Europe/Paris:20260323T110000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Updated meeting with Cedric
            LOCATION:Paris office
            DESCRIPTION:Updated time and summary
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email())
            .trim();

        Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedInboxIcs);
        assertThat(actualCalendar)
            .as("Cedric should receive a proper REQUEST for the modified invited occurrence")
            .isEqualTo(expectedCalendar);

        // AND Cedric's calendar should reflect the updated time & summary
        String cedricDefaultCalendar = "/calendars/" + cedric.id() + "/" + cedric.id();
        List<String> defaultCalendarHrefs = awaitCalendarEntries(cedric, cedricDefaultCalendar, 1);
        assertThat(defaultCalendarHrefs)
            .as("Cedric’s calendar should have exactly one updated event instance")
            .hasSize(1);

        String cedricCalendarIcs = calDavClient.getCalendarEvent(cedric, URI.create(defaultCalendarHrefs.getFirst()));

        assertThat(cedricCalendarIcs)
            .contains("SUMMARY:Updated meeting with Cedric")
            .contains("DTSTART;TZID=Europe/Paris:20260323T100000")
            .contains("DTEND;TZID=Europe/Paris:20260323T110000");
    }

    @Test
    void shouldSendItipRequestWhenOrganizerUpdatesOnlyLocationOfInvitedOccurrence() {
        // GIVEN Bob invites Cedric for occurrence #2
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // Ensure Cedric received the initial REQUEST for occurrence #2
        String cedricInbox = "/calendars/" + cedric.id() + "/inbox";
        awaitCalendarEntries(cedric, cedricInbox, 1);

        // WHEN Bob updates only the location for that occurrence
        String updateLocationIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:New Paris Office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email());

        String bobEventUri = "/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), updateLocationIcs);

        // THEN Cedric receives an iTIP REQUEST reflecting the new location
        List<String> hrefsFromPropfind = awaitCalendarEntries(cedric, cedricInbox, 2);
        URI latestInboxUri = URI.create(hrefsFromPropfind.getLast());
        String updatedIcs = calDavClient.getCalendarEvent(cedric, latestInboxUri);

        assertThat(updatedIcs)
            .as("Cedric should receive REQUEST iTIP for the updated location")
            .contains("METHOD:REQUEST")
            .contains("LOCATION:New Paris Office")
            .contains("RECURRENCE-ID;TZID=Europe/Paris:20260323T090000")
            .doesNotContain("METHOD:CANCEL");
    }

    @Test
    void shouldSendCancelItipToAllAttendeesWhenOrganizerDeletesRecurringEvent() {
        // GIVEN Bob invites Cedric to an occurrence in a recurring event
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);

        // Cedric should receive the initial REQUEST for the recurrence
        String cedricInbox = "/calendars/" + cedric.id() + "/inbox";
        awaitCalendarEntries(cedric, cedricInbox, 1);

        // WHEN Bob deletes the entire recurring event
        String bobEventUri = "/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics";
        calDavClient.deleteCalendarEvent(bob, URI.create(bobEventUri));

        // THEN Cedric receives an iTIP CANCEL (without RECURRENCE-ID)
        List<String> inboxHrefs = awaitCalendarEntries(cedric, cedricInbox, 2); // initial REQUEST + CANCEL
        URI latestInboxUri = URI.create(inboxHrefs.getLast());
        String cancelIcs = calDavClient.getCalendarEvent(cedric, latestInboxUri);

        assertThat(cancelIcs)
            .as("Cedric should receive a CANCEL iTIP for the entire recurrence (no RECURRENCE-ID)")
            .contains("METHOD:CANCEL")
            .contains("UID:" + eventUid)
            .doesNotContain("RECURRENCE-ID");

        // AND Cedric’s calendar marks related events as STATUS:CANCELLED
        List<String> cancelledHrefs = awaitCalendarEntries(cedric, "/calendars/" + cedric.id() + "/" + cedric.id(), 1);
        String ics = calDavClient.getCalendarEvent(cedric, URI.create(cancelledHrefs.getFirst()));
        assertThat(ics)
            .as("Recurrence instances should be marked as STATUS:CANCELLED after organizer cancellation")
            .contains("STATUS:CANCELLED");
    }

    @Test
    void shouldSendCancelItipToInvitedAttendeeWhenOrganizerDeletesSingleOccurrence() {
        // GIVEN Bob invites Cedric to occurrence #2
        String eventUid = "event-" + UUID.randomUUID();
        createRecurringEvent(bob, eventUid);
        inviteAttendeeForSecondOccurrence(bob, cedric, eventUid);
        awaitCalendarEntries(cedric, "/calendars/" + cedric.id() + "/inbox", 1);

        // WHEN Bob deletes that occurrence from his calendar
        String bobEventUri = "/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics";
        String updateWithoutCedricOccurrence = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event (Cedric removed)
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email());

        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), updateWithoutCedricOccurrence);

        // THEN Cedric receives a CANCEL iTIP specifically for the deleted occurrence
        List<String> hrefs = awaitCalendarEntries(cedric, "/calendars/" + cedric.id() + "/inbox", 2);
        assertThat(hrefs)
            .as("Cedric should receive an ITIP CANCEL for the deleted occurrence")
            .hasSize(2);

        URI cancelUri = URI.create(hrefs.getLast());
        String cancelIcs = calDavClient.getCalendarEvent(cedric, cancelUri);

        assertThat(cancelIcs)
            .as("Cedric should receive a CANCEL iTIP only for the deleted instance (#2)")
            .contains("METHOD:CANCEL")
            .contains("UID:" + eventUid);
    }

    @Test
    void shouldSendRequestItipToAttendeesWhenOrganizerDeletesOneOfMultipleInvitedOccurrencesInRecurrenceEvent() {
        // GIVEN Bob invites Cedric for occurrence #2 and #3
        String eventUid = "event-" + UUID.randomUUID();
        String bobEventUri = createRecurringEvent(bob, eventUid);
        String inviteCedricForTwoOccurrencesIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting with Cedric (Day 2)
            LOCATION:Paris office
            DESCRIPTION:Occurrence #2
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:20260324T090000
            DTEND;TZID=Europe/Paris:20260324T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260324T090000
            SUMMARY:Daily meeting with Cedric (Day 3)
            LOCATION:Paris office
            DESCRIPTION:Occurrence #3
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), inviteCedricForTwoOccurrencesIcs);

        String cedricInbox = "/calendars/" + cedric.id() + "/inbox";
        awaitCalendarEntries(cedric, cedricInbox, 1);

        // WHEN Bob deletes only occurrence #2
        String updateIcsWithExdate = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260321T090000Z
            SEQUENCE:2
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Removed occurrence #2
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:20260324T090000
            DTEND;TZID=Europe/Paris:20260324T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260324T090000
            SUMMARY:Daily meeting with Cedric (Day 3)
            LOCATION:Paris office
            DESCRIPTION:Occurrence #3
            ORGANIZER;CN=Bob:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{BOB_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{CEDRIC_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{BOB_EMAIL}", bob.email())
            .replace("{CEDRIC_EMAIL}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, URI.create(bobEventUri), updateIcsWithExdate);

        // THEN Cedric receives a REQUEST update reflecting only the remaining occurrence (#3)
        List<String> hrefs = awaitCalendarEntries(cedric, cedricInbox, 2); // 1 initial REQUEST + 1 update after deletion
        String cancelIcs = calDavClient.getCalendarEvent(cedric, URI.create(hrefs.getLast()));

        assertThat(cancelIcs)
            .as("Cedric should receive a REQUEST iTIP update (remaining occurrence only, no CANCEL)")
            .contains("METHOD:REQUEST")
            .contains("RECURRENCE-ID;TZID=Europe/Paris:20260324T090000")
            .doesNotContain("RECURRENCE-ID;TZID=Europe/Paris:20260323T090000")
            .doesNotContain("RRULE");
    }

    private String createRecurringEvent(OpenPaasUser organizer, String eventUid) {
        String masterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{ORG_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{ORG_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{ORG_EMAIL}", organizer.email());

        String eventUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(organizer, URI.create(eventUri), masterIcs);
        return eventUri;
    }

    private void inviteAttendeeForSecondOccurrence(OpenPaasUser organizer, OpenPaasUser attendee, String eventUid) {
        String eventUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        String inviteIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260322T090000
            DTEND;TZID=Europe/Paris:20260322T100000
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{ORG_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{ORG_EMAIL}
            END:VEVENT
            BEGIN:VEVENT
            UID:{UID}
            DTSTAMP:20260320T080000Z
            DTSTART;TZID=Europe/Paris:20260323T090000
            DTEND;TZID=Europe/Paris:20260323T100000
            RECURRENCE-ID;TZID=Europe/Paris:20260323T090000
            SUMMARY:Daily meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Bob:mailto:{ORG_EMAIL}
            ATTENDEE;CN=Bob;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:{ORG_EMAIL}
            ATTENDEE;CN=Cedric;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:{ATTENDEE_EMAIL}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{UID}", eventUid)
            .replace("{ORG_EMAIL}", organizer.email())
            .replace("{ATTENDEE_EMAIL}", attendee.email());

        calDavClient.upsertCalendarEvent(organizer, URI.create(eventUri), inviteIcs);
    }

    private List<String> awaitCalendarEntries(OpenPaasUser attendee, String collectionPath, int expectedCount) {
        List<String> hrefsFromPropfind = new ArrayList<>();
        awaitAtMost.untilAsserted(() -> {
            List<String> hrefs = XMLUtil.extractCalendarHrefsFromPropfind(
                execute(extension().davHttpClient()
                    .headers(attendee::impersonatedBasicAuth)
                    .request(HttpMethod.valueOf("PROPFIND"))
                    .uri(collectionPath))
                    .body());
            assertThat(hrefs)
                .as("Expected number of iTIP messages in " + collectionPath + " for " + attendee.email())
                .hasSize(expectedCount);
            hrefsFromPropfind.addAll(hrefs);
        });
        return hrefsFromPropfind;
    }

    @Test
    void itipRequestWithDuplicatedStatusShouldBeAccepted() throws JsonProcessingException {
        // GIVEN a Yahoo-like ITIP REQUEST containing duplicated STATUS lines
        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Yahoo Inc.//Yahoo Calendar//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251024T032242Z
            DTSTART;TZID=Etc/GMT:20251025T110000
            DTEND;TZID=Etc/GMT:20251025T120000
            SUMMARY:Test from yahoo calendar
            DESCRIPTION:from yahoo
            CLASS:PUBLIC
            PRIORITY:0
            SEQUENCE:0
            STATUS:CONFIRMED
            STATUS:TENTATIVE
            ORGANIZER;CN=Shi Karen;SENT-BY="mailto:k@yahoo.com.vn":mailto:k@yahoo.com.vn
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email());

        String body = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender("k@yahoo.com.vn")
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), body).block();

        // THEN the event should appear in Bob’s inbox and default calendar
        String bobInboxUri = "/calendars/" + bob.id() + "/inbox";
        List<String> inboxHrefs = awaitCalendarEntries(bob, bobInboxUri, 1);
        assertThat(inboxHrefs)
            .as("Expected one ITIP REQUEST in Bob's inbox")
            .hasSize(1);

        String inboxIcs = calDavClient.getCalendarEvent(bob, URI.create(inboxHrefs.getFirst()));
        assertThat(inboxIcs)
            .as("Inbox ICS should contain valid Yahoo event despite duplicate STATUS")
            .contains("UID:" + eventUid)
            .contains("SUMMARY:Test from yahoo calendar");

        String bobDefaultCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();
        List<String> calendarHrefs = awaitCalendarEntries(bob, bobDefaultCalendarUri, 1);
        assertThat(calendarHrefs)
            .as("Expected the event to be stored in default calendar")
            .hasSize(1);

        String calendarIcs = calDavClient.getCalendarEvent(bob, URI.create(calendarHrefs.getFirst()));
        assertThat(calendarIcs)
            .as("Calendar ICS should contain UID and summary even with duplicated STATUS")
            .contains("UID:" + eventUid)
            .contains("SUMMARY:Test from yahoo calendar");

        // AND verify that REPORT API can still list the event normally
        DavResponse reportResponse = calDavClient.findEventsByTime(bob,
            CalendarURL.from(bob.id()),
            "20250101T000000",
            "20251231T000000");
        List<JsonCalendarEventData> reportEvents = JsonCalendarEventData.from(reportResponse.body());

        assertThat(reportEvents)
            .as("REPORT API should return the event even when ICS has duplicated STATUS")
            .anySatisfy(event -> {
                assertThat(event.uid()).isEqualTo(eventUid);
                assertThat(event.summary().orElse("")).contains("Test from yahoo calendar");
            });
    }

    @Test
    void itipCancelWithDuplicatedStatusShouldBeAccepted() {
        // GIVEN an existing Yahoo-like event previously sent by organizer
        String eventUid = "event-" + UUID.randomUUID();

        // Step 1: send initial REQUEST to create the event
        String requestIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Yahoo Inc.//Yahoo Calendar//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251024T032242Z
            DTSTART;TZID=Etc/GMT:20251025T110000
            DTEND;TZID=Etc/GMT:20251025T120000
            SUMMARY:Yahoo meeting to be cancelled
            DESCRIPTION:from yahoo
            CLASS:PUBLIC
            PRIORITY:0
            SEQUENCE:0
            STATUS:CONFIRMED
            STATUS:TENTATIVE
            ORGANIZER;CN=Shi Karen;SENT-BY="mailto:k@yahoo.com.vn":mailto:k@yahoo.com.vn
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email());

        String requestBody = ITIPJsonBodyRequest.builder()
            .ical(requestIcs)
            .sender("k@yahoo.com.vn")
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), requestBody).block();

        // Verify the event exists initially
        List<String> initialHrefs = awaitCalendarEntries(bob, "/calendars/" + bob.id() + "/" + bob.id(), 1);
        String initialCalendarIcs = calDavClient.getCalendarEvent(bob, URI.create(initialHrefs.getFirst()));
        assertThat(initialCalendarIcs)
            .as("Initial event should be stored in Bob's calendar")
            .contains("SUMMARY:Yahoo meeting to be cancelled")
            .contains("UID:" + eventUid);

        // WHEN Yahoo sends CANCEL with duplicated STATUS
        String cancelIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Yahoo Inc.//Yahoo Calendar//EN
            CALSCALE:GREGORIAN
            METHOD:CANCEL
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251024T050000Z
            DTSTART;TZID=Etc/GMT:20251025T110000
            DTEND;TZID=Etc/GMT:20251025T120000
            SUMMARY:Yahoo meeting to be cancelled
            DESCRIPTION:cancelled from yahoo
            CLASS:PUBLIC
            PRIORITY:0
            SEQUENCE:1
            STATUS:CANCELLED
            STATUS:TENTATIVE
            ORGANIZER;CN=Shi Karen;SENT-BY="mailto:k@yahoo.com.vn":mailto:k@yahoo.com.vn
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email());

        String cancelBody = ITIPJsonBodyRequest.builder()
            .ical(cancelIcs)
            .sender("k@yahoo.com.vn")
            .recipient(bob.email())
            .uid(eventUid)
            .method("CANCEL")
            .buildJson();

        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), cancelBody).block();

        // THEN Bob’s inbox should receive the CANCEL iTIP
        String bobInboxUri = "/calendars/" + bob.id() + "/inbox";
        List<String> inboxHrefs = awaitCalendarEntries(bob, bobInboxUri, 2); // REQUEST + CANCEL
        String cancelInboxIcs = calDavClient.getCalendarEvent(bob, URI.create(inboxHrefs.getLast()));

        assertThat(cancelInboxIcs)
            .as("Inbox should contain Yahoo CANCEL even with duplicated STATUS")
            .contains("METHOD:CANCEL")
            .contains("UID:" + eventUid)
            .contains("SUMMARY:Yahoo meeting to be cancelled");

        // AND the cancelled event should be removed from Bob's default calendar
        String bobDefaultCalendarURI =  "/calendars/" + bob.id() + "/" + bob.id();
        List<String> calendarUri = awaitCalendarEntries(bob, bobDefaultCalendarURI, 1);
        String calendarEvent = calDavClient.getCalendarEvent(bob, URI.create(calendarUri.getLast()));

        assertThat(calendarEvent)
            .contains("STATUS:CANCELLED");
    }

    @Test
    void itipUpdateWithDuplicatedStatusShouldBeAccepted() throws JsonProcessingException {
        // GIVEN: Yahoo-like organizer sends initial event
        String eventUid = "event-" + UUID.randomUUID();

        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Yahoo Inc.//Yahoo Calendar//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251024T032242Z
            DTSTART;TZID=Etc/GMT:20251025T110000
            DTEND;TZID=Etc/GMT:20251025T120000
            SUMMARY:Yahoo meeting update test
            DESCRIPTION:Initial description
            CLASS:PUBLIC
            PRIORITY:0
            SEQUENCE:0
            STATUS:CONFIRMED
            STATUS:TENTATIVE
            ORGANIZER;CN=Shi Karen;SENT-BY="mailto:k@yahoo.com.vn":mailto:k@yahoo.com.vn
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email());

        String requestBody = ITIPJsonBodyRequest.builder()
            .ical(initialIcs)
            .sender("k@yahoo.com.vn")
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        String bobCalendarUri = "/calendars/" + bob.id();
        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), requestBody).block();

        // Verify event exists before update
        List<String> beforeUpdate = awaitCalendarEntries(bob, "/calendars/" + bob.id() + "/" + bob.id(), 1);
        String calendarBefore = calDavClient.getCalendarEvent(bob, URI.create(beforeUpdate.getFirst()));
        assertThat(calendarBefore)
            .contains("SUMMARY:Yahoo meeting update test")
            .contains("DESCRIPTION:Initial description");

        // WHEN: Yahoo sends updated iTIP (duplicate STATUS + new description)
        String updateIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Yahoo Inc.//Yahoo Calendar//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251024T050000Z
            DTSTART;TZID=Etc/GMT:20251025T110000
            DTEND;TZID=Etc/GMT:20251025T120000
            SUMMARY:Yahoo meeting update test
            DESCRIPTION:Updated description from yahoo
            CLASS:PUBLIC
            PRIORITY:0
            SEQUENCE:1
            STATUS:CONFIRMED
            STATUS:CONFIRMED
            ORGANIZER;CN=Shi Karen;SENT-BY="mailto:k@yahoo.com.vn":mailto:k@yahoo.com.vn
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email());

        String updateBody = ITIPJsonBodyRequest.builder()
            .ical(updateIcs)
            .sender("k@yahoo.com.vn")
            .recipient(bob.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        calDavClient.sendITIPRequest(bob, URI.create(bobCalendarUri), updateBody).block();

        // THEN: Inbox should have both original and updated REQUESTs
        String bobInboxUri = "/calendars/" + bob.id() + "/inbox";
        List<String> inboxHrefs = awaitCalendarEntries(bob, bobInboxUri, 2);
        String updatedInboxIcs = calDavClient.getCalendarEvent(bob, URI.create(inboxHrefs.getLast()));

        assertThat(updatedInboxIcs)
            .as("Yahoo duplicated STATUS should still allow update REQUEST")
            .contains("METHOD:REQUEST")
            .contains("UID:" + eventUid)
            .contains("Updated description from yahoo");

        // AND: Default calendar should contain updated description
        List<String> afterUpdate = awaitCalendarEntries(bob, "/calendars/" + bob.id() + "/" + bob.id(), 1);
        String updatedCalendarIcs = calDavClient.getCalendarEvent(bob, URI.create(afterUpdate.getFirst()));

        assertThat(updatedCalendarIcs)
            .as("Default calendar should have updated description after Yahoo duplicated STATUS update")
            .contains("DESCRIPTION:Updated description from yahoo")
            .contains("SUMMARY:Yahoo meeting update test");

        // AND verify via PROPFIND that updated event exists and matches expected content
        String bobDefaultCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();
        List<String> propfindHrefs = awaitCalendarEntries(bob, bobDefaultCalendarUri, 1);
        String propfindIcs = calDavClient.getCalendarEvent(bob, URI.create(propfindHrefs.getFirst()));

        assertThat(propfindIcs)
            .as("PROPFIND should return the updated Yahoo event after duplicated STATUS update")
            .contains("UID:" + eventUid)
            .contains("SUMMARY:Yahoo meeting update test")
            .contains("DESCRIPTION:Updated description from yahoo");
    }

}