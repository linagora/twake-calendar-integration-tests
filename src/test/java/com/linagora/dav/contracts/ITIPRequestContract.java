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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.OpenPaasUser;

public abstract class ITIPRequestContract {
    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollDelay(Duration.ofSeconds(2))
        .pollInterval(Duration.ofSeconds(1))
        .await();

    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

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
        calDavClient.createNewCalendar(bob, bobCustomCalendarId, "Bob Custom Calendar");
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

        List<JsonNode> defaultCalendarEvents =
            awaitAtMost.until(() -> bobEventsForUri.apply(bobDefaultCalendarUri), nodes -> !nodes.isEmpty());

        assertThat(defaultCalendarEvents)
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + bob.email());
                assertThat(json).contains("Meeting from Cedric");
            });

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
}