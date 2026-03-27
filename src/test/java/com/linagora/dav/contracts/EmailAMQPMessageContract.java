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

import static com.linagora.dav.DockerTwakeCalendarExtension.QUEUE_NAME;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import net.fortuna.ical4j.model.Calendar;
import net.minidev.json.parser.ParseException;

public abstract class EmailAMQPMessageContract {

    public static final boolean NOT_COUNTER = false;
    public static final boolean COUNTER = true;
    private static final String X_PUBLICLY_CREATED_HEADER = "X-PUBLICLY-CREATED:true";

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() throws IOException {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:notificationEmail:send", "");
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventCreation() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        BlockingQueue<JsonNode> messages = listenToQueue();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        String expected = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics",
              "isNewEvent": true
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    public void shouldReceiveNotificationEmailMessageOnEventCreationWith1DVALARM() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        BlockingQueue<JsonNode> messages = listenToQueue();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            BEGIN:VALARM
            TRIGGER:-PT1D
            ACTION:EMAIL
            ATTENDEE:mailto:mailto:mailto:{organizerEmail}
            SUMMARY:{summary}
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{summary}", "Sprint planning #01")
            .replace("{dtstart}", "30250411T100000")
            .replace("{dtend}", "30250411T110000")
            .replace("{partStat}", "NEEDS-ACTION");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThat(message.path("event").asText()).contains("TRIGGER:-PT1D")));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventUpdate() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();
        BlockingQueue<JsonNode> messages = listenToQueue();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #02",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next 2 weeks.",
            "30250411T150000",
            "30250411T160000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T150000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T160000
            SUMMARY:Sprint planning #02
            LOCATION:Twake Meeting Room 2
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next 2 weeks.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        String expected = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics",
              "changes": {
                "summary": {
                  "previous": "Sprint planning #01",
                  "current": "Sprint planning #02"
                },
                "location": {
                  "previous": "Twake Meeting Room",
                  "current": "Twake Meeting Room 2"
                },
                "description": {
                  "previous": "This is a meeting to discuss the sprint planning for the next week.",
                  "current": "This is a meeting to discuss the sprint planning for the next 2 weeks."
                },
                "dtstart": {
                  "previous": {
                    "isAllDay": false,
                    "date": "3025-04-11 10:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "current": {
                    "isAllDay": false,
                    "date": "3025-04-11 15:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Asia/Ho_Chi_Minh"
                  }
                },
                "dtend": {
                  "previous": {
                    "isAllDay": false,
                    "date": "3025-04-11 11:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "current": {
                    "isAllDay": false,
                    "date": "3025-04-11 16:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Asia/Ho_Chi_Minh"
                  }
                }
              }
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnLocationUpdate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);
        BlockingQueue<JsonNode> messages = listenToQueue();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThatJson(message.toString())
                    .inPath("changes")
                    .isEqualTo("""
                        {
                          "location": {
                            "previous": "Twake Meeting Room",
                            "current": "Twake Meeting Room 2"
                          }
                        }
                        """)));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnDescriptionUpdate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);
        BlockingQueue<JsonNode> messages = listenToQueue();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next two weeks.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThatJson(message.toString())
                    .inPath("changes")
                    .isEqualTo("""
                        {
                          "description": {
                            "previous": "This is a meeting to discuss the sprint planning for the next week.",
                            "current": "This is a meeting to discuss the sprint planning for the next two weeks."
                          }
                        }
                        """)));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnSummaryUpdate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);
        BlockingQueue<JsonNode> messages = listenToQueue();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #02",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThatJson(message.toString())
                    .inPath("changes")
                    .isEqualTo("""
                        {
                          "summary": {
                            "previous": "Sprint planning #01",
                            "current": "Sprint planning #02"
                          }
                        }
                        """)));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventCancel() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();
        BlockingQueue<JsonNode> messages = listenToQueue();

        calDavClient.deleteCalendarEvent(testUser, eventUid);
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            METHOD:CANCEL
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:20250710T030002Z
            SEQUENCE:2
            SUMMARY:Sprint planning #01
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        String expected = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "CANCEL",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics"
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventReply() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();
        BlockingQueue<JsonNode> messages = listenToQueue();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            "ACCEPTED",
            NOT_COUNTER);
        calDavClient.upsertCalendarEvent(testUser2, attendeeEventId, updatedCalendarData);
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            METHOD:REPLY
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:20250710T041802Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        String expected = """
            {
              "senderEmail": "{attendeeEmail}",
              "recipientEmail": "{organizerEmail}",
              "method": "REPLY",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{attendeeId}",
              "eventPath": "/calendars/{organizerId}/{organizerId}/{eventUid}.ics"
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventCounter() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String initialCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();
        BlockingQueue<JsonNode> messages = listenToQueue();

        String updatedCalendarData = generateCounterCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T150000",
            "30250411T160000");
        CalDavClient.CounterRequest counterRequest = new CalDavClient.CounterRequest(
            updatedCalendarData,
            testUser2.email(),
            testUser.email(),
            eventUid,
            1);
        calDavClient.sendITIP(testUser2, attendeeEventId, counterRequest);
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            METHOD:COUNTER
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T150000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T160000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next w
             eek.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);
        String expectedOldEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next w
             eek.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER;SCHEDULE-STATUS=1.1:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        String expected = """
            {
              "senderEmail": "{attendeeEmail}",
              "recipientEmail": "{organizerEmail}",
              "method": "COUNTER",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{attendeeId}",
              "eventPath": "/calendars/{organizerId}/{organizerId}/{eventUid}.ics",
              "oldEvent": "${json-unit.any-string}"
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);

                    Calendar actualOldCalendar = CalendarUtil.parseIcsAndSanitize(message.path("oldEvent").asText());
                    Calendar expectedOldCalendar = CalendarUtil.parseIcsAndSanitize(expectedOldEventIcs);
                    assertThat(actualOldCalendar).isEqualTo(expectedOldCalendar);
                }));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnRecurringEventWithExdate() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        BlockingQueue<JsonNode> messages = listenToQueue();

        String eventUid = UUID.randomUUID().toString();

        // Create a recurring event with EXDATE
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250401T090000Z
            SEQUENCE:0
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T100000
            RRULE:FREQ=WEEKLY;BYDAY=FR
            EXDATE;TZID=Asia/Ho_Chi_Minh:30250411T090000
            SUMMARY:Weekly meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email());

        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost
            .until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent)
            .get();

        // --- Expected ICS extracted for readability ---
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250401T090000Z
            SEQUENCE:0
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T100000
            RRULE:FREQ=WEEKLY;BYDAY=FR
            EXDATE;TZID=Asia/Ho_Chi_Minh:30250411T090000
            SUMMARY:Weekly meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        // --- Expected JSON ---
        String expectedJsonString = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics",
              "isNewEvent": true
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{attendeeEventId}", attendeeEventId);
        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expectedJsonString);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveCancelMessageWhenRecurringEventOccurrenceIsExcluded() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();

        // Create a recurring event
        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250401T090000Z
            SEQUENCE:0
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T100000
            RRULE:FREQ=WEEKLY;BYDAY=FR
            SUMMARY:Weekly meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email());

        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost
            .until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent)
            .get();
        BlockingQueue<JsonNode> messages = listenToQueue();

        // Update the event by adding an EXDATE (exclude one occurrence)
        String updatedCalendarData = initialCalendarData.replace("SUMMARY:Weekly meeting",
            "SUMMARY:Weekly meeting\nEXDATE;TZID=Asia/Ho_Chi_Minh:30250411T090000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        // --- Expected CANCEL ICS ---
        String expectedCancelIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:CANCEL
            BEGIN:VEVENT
            UID:{eventUid}
            SEQUENCE:0
            SUMMARY:Weekly meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER;SCHEDULE-STATUS=1.1:mailto:{attendeeEmail}
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T090000
            RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:30250411T090000
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        String expectedJsonString = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "CANCEL",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics"
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expectedJsonString);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedCancelIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnRecurringEventOccurrenceUpdate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();

        // Create recurring event (weekly on Tuesday 09:00–10:00)
        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250920T090000Z
            SEQUENCE:0
            DTSTART;TZID=Europe/Paris:30250923T090000
            DTEND;TZID=Europe/Paris:30250923T100000
            RRULE:FREQ=WEEKLY;BYDAY=TU
            SUMMARY:Weekly meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email());

        calDavClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost
            .until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent)
            .get();

        // Override one occurrence (move from 23/09 09:00–10:00 → 24/09 14:00–15:00)
        String updatedOccurrenceData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250920T090000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:30250923T090000
            DTEND;TZID=Europe/Paris:30250923T100000
            RRULE:FREQ=WEEKLY;BYDAY=TU
            SUMMARY:Weekly meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250920T090000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:30250924T140000
            DTEND;TZID=Europe/Paris:30250924T150000
            RECURRENCE-ID;TZID=Europe/Paris:30250923T090000
            SUMMARY:Weekly meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email());

        BlockingQueue<JsonNode> messages = listenToQueue();
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedOccurrenceData);

        // --- Expected ICS for the updated occurrence ---
        String expectedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250920T090000Z
            SEQUENCE:1
            DTSTART;TZID=Europe/Paris:30250924T140000
            DTEND;TZID=Europe/Paris:30250924T150000
            RECURRENCE-ID;TZID=Europe/Paris:30250923T090000
            SUMMARY:Weekly meeting
            LOCATION:Paris office
            DESCRIPTION:Recurring test event
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{eventUid}", eventUid);

        // --- Expected JSON with changes ---
        String expectedJsonString = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics",
              "changes": {
                "dtstart": {
                  "previous": {
                    "isAllDay": false,
                    "date": "3025-09-23 09:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Europe/Paris"
                  },
                  "current": {
                    "isAllDay": false,
                    "date": "3025-09-24 14:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Europe/Paris"
                  }
                },
                "dtend": {
                  "previous": {
                    "isAllDay": false,
                    "date": "3025-09-23 10:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Europe/Paris"
                  },
                  "current": {
                    "isAllDay": false,
                    "date": "3025-09-24 15:00:00.000000",
                    "timezone_type": 3,
                    "timezone": "Europe/Paris"
                  }
                }
              }
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{attendeeEventId}", attendeeEventId);

        awaitAtMost.untilAsserted(() -> {
            assertThat(messages)
                .filteredOn(message -> testUser2.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expectedJsonString);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("event").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                });
        });
    }

    @Test
    protected void shouldNotSendNotificationEmailWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithInternalAttendee() throws IOException, InterruptedException {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, calendarData);

        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
    }

    @Test
    protected void shouldNotSendNotificationEmailWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithExternalAttendee() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        String externalAttendeeEmail = "external-attendee-" + UUID.randomUUID() + "@external-domain.com";

        String eventUid = UUID.randomUUID().toString();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting with external
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting with an external attendee.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=External Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail)
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, calendarData);

        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "TENTATIVE"})
    protected void shouldSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedWithInternalAttendee(String partStat) {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();

        // GIVEN: Organizer creates event with PARTSTAT=NEEDS-ACTION and X-PUBLICLY-CREATED:true
        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

        calmlyAwait
            .during(1, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
        BlockingQueue<JsonNode> messages = listenToQueue();

        // WHEN: Organizer updates PARTSTAT to ACCEPTED or TENTATIVE
        String updatedCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:2
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{partStat}", partStat)
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, updatedCalendarData);
        String expectedInternalAttendeeNotification = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "${json-unit.any-string}",
              "eventPath": "${json-unit.any-string}"
            }
            """.replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email());

        // THEN: A notification email should be sent to the internal attendee
        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> attendee.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThatJson(message.toString())
                    .isEqualTo(expectedInternalAttendeeNotification)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "TENTATIVE"})
    protected void shouldSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedWithExternalAttendee(String partStat) {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        String externalAttendeeEmail = "external-attendee-" + UUID.randomUUID() + "@external-domain.com";

        String eventUid = UUID.randomUUID().toString();

        // GIVEN: Organizer creates event with PARTSTAT=NEEDS-ACTION and X-PUBLICLY-CREATED:true
        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting with external
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting with an external attendee.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=External Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail)
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

        calmlyAwait
            .during(1, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
        BlockingQueue<JsonNode> messages = listenToQueue();

        // WHEN: Organizer updates PARTSTAT to ACCEPTED or TENTATIVE
        String updatedCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:2
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting with external
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting with an external attendee.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=External Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail)
            .replace("{partStat}", partStat)
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, updatedCalendarData);
        String expectedExternalAttendeeNotification = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "${json-unit.any-string}",
              "eventPath": "${json-unit.regex}^/calendars/.*$"
            }
            """.replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail);

        // THEN: A notification email should be sent to the external attendee
        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> externalAttendeeEmail.equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThatJson(message.toString())
                    .isEqualTo(expectedExternalAttendeeNotification)));
    }

    @Test
    protected void shouldSendNotificationEmailWhenAcceptedAfterSetRecurringPubliclyCreated() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();
        String eventUid = UUID.randomUUID().toString();
        String recurrenceRule = "RRULE:FREQ=DAILY;COUNT=3";

        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART:30250411T100000Z
            DTEND:30250411T110000Z
            SUMMARY:Publicly created recurring meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created recurring meeting.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

        calmlyAwait
            .during(1, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());

        String recurringCalendarData = initialCalendarData
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("DTEND:30250411T110000Z", "DTEND:30250411T110000Z\n" + recurrenceRule);

        calDavClient.upsertCalendarEvent(organizer, eventUid, recurringCalendarData);

        calmlyAwait
            .during(1, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
        BlockingQueue<JsonNode> messages = listenToQueue();

        String acceptedCalendarData = recurringCalendarData
            .replace("SEQUENCE:2", "SEQUENCE:3")
            .replace("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:" + organizer.email(),
                "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:" + organizer.email());

        calDavClient.upsertCalendarEvent(organizer, eventUid, acceptedCalendarData);
        String expectedRecurringAcceptedNotification = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "${json-unit.any-string}",
              "notify": true,
              "calendarURI": "${json-unit.any-string}",
              "eventPath": "${json-unit.any-string}"
            }
            """.replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email());

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> attendee.email().equals(message.path("recipientEmail").asText()))
                .anySatisfy(message -> assertThatJson(message.toString())
                    .isEqualTo(expectedRecurringAcceptedNotification)));
    }

    @Test
    protected void shouldNotSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToDeclinedWithInternalAttendee() throws InterruptedException, IOException {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();

        // GIVEN: Organizer creates event with PARTSTAT=NEEDS-ACTION and X-PUBLICLY-CREATED:true
        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

        // WHEN: Organizer updates PARTSTAT to DECLINED
        String updatedCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:2
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=DECLINED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, updatedCalendarData);

        // THEN: No notification email is sent to the attendee
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
    }

    @Test
    protected void shouldNotSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToDeclinedWithExternalAttendee() throws InterruptedException, IOException {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        String externalAttendeeEmail = "external-attendee-" + UUID.randomUUID() + "@external-domain.com";

        String eventUid = UUID.randomUUID().toString();

        // GIVEN: Organizer creates event with PARTSTAT=NEEDS-ACTION and X-PUBLICLY-CREATED:true
        String initialCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting with external
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting with an external attendee.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=External Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail)
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

        // WHEN: Organizer updates PARTSTAT to DECLINED
        String updatedCalendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:2
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Publicly created meeting with external
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a publicly created meeting with an external attendee.
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=DECLINED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=External Attendee:mailto:{attendeeEmail}
            {xPubliclyCreated}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail)
            .replace("{xPubliclyCreated}", X_PUBLICLY_CREATED_HEADER);

        calDavClient.upsertCalendarEvent(organizer, eventUid, updatedCalendarData);

        // THEN: No notification email is sent to the attendee
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true)).isNull());
    }

    private BlockingQueue<JsonNode> listenToQueue() {
        return AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), QUEUE_NAME);
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend) {
        return generateCalendarData(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, "NEEDS-ACTION", NOT_COUNTER);
    }

    private String generateCounterCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                               String summary,
                                               String location,
                                               String description,
                                               String dtstart,
                                               String dtend) {
        return generateCalendarData(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, "NEEDS-ACTION", COUNTER);
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String partStat,
                                        boolean isCounter) {
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            LOCATION:{location}
            DESCRIPTION:{description}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary)
            .replace("{location}", location)
            .replace("{description}", description)
            .replace("{dtstart}", dtstart)
            .replace("{dtend}", dtend)
            .replace("{partStat}", partStat);

        if (isCounter) {
            calendarData = calendarData.replace("BEGIN:VCALENDAR", "BEGIN:VCALENDAR\nMETHOD:COUNTER");
        }
        return calendarData;
    }
}