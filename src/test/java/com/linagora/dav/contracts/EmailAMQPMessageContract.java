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
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.JsonUtil;
import com.linagora.dav.OpenPaasUser;

import net.fortuna.ical4j.model.Calendar;
import net.javacrumbs.jsonunit.assertj.JsonAssertions;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

public abstract class EmailAMQPMessageContract {

    public static final boolean NOT_COUNTER = false;
    public static final boolean COUNTER = true;

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

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nMETHOD:REQUEST\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nDTSTAMP:30250411T022032Z\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nSUMMARY:Sprint planning #01\\r\\nLOCATION:Twake Meeting Room\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next w\\r\\n eek.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
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

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("event"));
        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);
        JsonUtil.sanitize(actualJson);
        JsonUtil.sanitize(expectedJson);

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    public void shouldReceiveNotificationEmailMessageOnEventCreationWith1DVALARM() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

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

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        assertThat(actual).contains("TRIGGER:-PT1D");
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

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
              "event": "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nMETHOD:REQUEST\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nDTSTAMP:30250411T022032Z\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T150000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T160000\\r\\nSUMMARY:Sprint planning #02\\r\\nLOCATION:Twake Meeting Room 2\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next 2\\r\\n  weeks.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
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

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("event"));
        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);
        JsonUtil.sanitize(actualJson);
        JsonUtil.sanitize(expectedJson);

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
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

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        JsonAssertions.assertThatJson(actual)
            .inPath("changes")
            .isEqualTo("""
                {
                  "location": {
                    "previous": "Twake Meeting Room",
                    "current": "Twake Meeting Room 2"
                  }
                }
                """);
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

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        JsonAssertions.assertThatJson(actual)
            .inPath("changes")
            .isEqualTo("""
                {
                  "description": {
                    "previous": "This is a meeting to discuss the sprint planning for the next week.",
                    "current": "This is a meeting to discuss the sprint planning for the next two weeks."
                  }
                }
                """);
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

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        JsonAssertions.assertThatJson(actual)
            .inPath("changes")
            .isEqualTo("""
                {
                  "summary": {
                    "previous": "Sprint planning #01",
                    "current": "Sprint planning #02"
                  }
                }
                """);
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

        calDavClient.deleteCalendarEvent(testUser, eventUid);

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "CANCEL",
              "event": "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nMETHOD:CANCEL\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nDTSTAMP:20250710T030002Z\\r\\nSEQUENCE:2\\r\\nSUMMARY:Sprint planning #01\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
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

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("event"));
        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);
        JsonUtil.sanitize(actualJson);
        JsonUtil.sanitize(expectedJson);

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
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

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "senderEmail": "{attendeeEmail}",
              "recipientEmail": "{organizerEmail}",
              "method": "REPLY",
              "event": "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nMETHOD:REPLY\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nDTSTAMP:20250710T041802Z\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nSUMMARY:Sprint planning #01\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=ACCEPTED;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
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

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("event"));
        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);
        JsonUtil.sanitize(actualJson);
        JsonUtil.sanitize(expectedJson);

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
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
        calDavClient.postCounter(testUser2, attendeeEventId, counterRequest);

        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "senderEmail": "{attendeeEmail}",
              "recipientEmail": "{organizerEmail}",
              "method": "COUNTER",
              "event": "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nMETHOD:COUNTER\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nDTSTAMP:30250411T022032Z\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T150000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T160000\\r\\nSUMMARY:Sprint planning #01\\r\\nLOCATION:Twake Meeting Room\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next w\\r\\n eek.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
              "notify": true,
              "calendarURI": "{attendeeId}",
              "eventPath": "/calendars/{organizerId}/{organizerId}/{eventUid}.ics",
              "oldEvent": "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nDTSTAMP:30250411T022032Z\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nSUMMARY:Sprint planning #01\\r\\nLOCATION:Twake Meeting Room\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next w\\r\\n eek.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER;SCHEDULE-STATUS=1.1:mailto:{attendeeEmail}\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n"
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("event"));
        Calendar actualOldCalendar = CalendarUtil.parseIcs(actualJson.getAsString("event"));
        Calendar expectedOldCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("event"));
        JsonUtil.sanitize(actualJson);
        JsonUtil.sanitize(expectedJson);

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
        assertThat(actualOldCalendar).isEqualTo(expectedOldCalendar);
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnRecurringEventWithExdate() throws ParseException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

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

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

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

        // --- Expected JSON without event field ---
        String expectedJsonString = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "REQUEST",
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

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expectedJsonString);

        // Compare JSON (excluding the event field)
        actualJson.remove("event");
        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());

        // Compare ICS separately
        Calendar actualCalendar = CalendarUtil.parseIcs(((JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual)).getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedEventIcs);
        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
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
        getMessageFromQueue(); // ignore creation message

        // Update the event by adding an EXDATE (exclude one occurrence)
        String updatedCalendarData = initialCalendarData.replace("SUMMARY:Weekly meeting",
            "SUMMARY:Weekly meeting\nEXDATE;TZID=Asia/Ho_Chi_Minh:30250411T090000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        // Consume queue (first is creation, second is cancellation)
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

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

        // --- Expected JSON without event ---
        String expectedJsonString = """
            {
              "senderEmail": "{organizerEmail}",
              "recipientEmail": "{attendeeEmail}",
              "method": "CANCEL",
              "notify": true,
              "calendarURI": "{organizerId}",
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics"
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{attendeeEventId}", attendeeEventId);

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expectedJsonString);

        // Compare JSON without ICS
        actualJson.remove("event");
        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());

        // --- Compare ICS separately ---
        Calendar actualCalendar = CalendarUtil.parseIcs(((JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual)).getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedCancelIcs);

        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnRecurringEventOccurrenceUpdate() throws ParseException {
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
        getMessageFromQueue();

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

        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedOccurrenceData);

        // Consume queue
        getMessageFromQueue();
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

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

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expectedJsonString);

        // Compare JSON without ICS
        actualJson.remove("event");
        JsonUtil.sanitize(actualJson);
        JsonUtil.sanitize(expectedJson);
        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());

        // Compare ICS separately
        Calendar actualCalendar = CalendarUtil.parseIcs(((JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual)).getAsString("event"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedEventIcs);
        CalendarUtil.sanitize(actualCalendar);
        CalendarUtil.sanitize(expectedCalendar);
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    protected void shouldNotReceiveNotificationEmailMessageWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithInternalAttendee() throws IOException, InterruptedException {
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
            X-PUBLICLY-CREATED:true
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email());

        calDavClient.upsertCalendarEvent(organizer, eventUid, calendarData);

        Thread.sleep(2000);

        assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true))
            .isNull();
    }

    @Test
    protected void shouldNotReceiveNotificationEmailMessageWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithExternalAttendee() throws IOException, InterruptedException {
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
            X-PUBLICLY-CREATED:true
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail);

        calDavClient.upsertCalendarEvent(organizer, eventUid, calendarData);

        Thread.sleep(2000);

        assertThat(dockerExtension().getChannel().basicGet(QUEUE_NAME, true))
            .isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "TENTATIVE", "DECLINED"})
    void shouldReceiveNotificationEmailMessageWhenOrganizerPartStatUpdatedFromNeedsActionWithInternalAttendee(String partStat) {
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
            X-PUBLICLY-CREATED:true
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email());

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

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
            X-PUBLICLY-CREATED:true
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", attendee.email())
            .replace("{partStat}", partStat);

        calDavClient.upsertCalendarEvent(organizer, eventUid, updatedCalendarData);

        // THEN: A notification email message should be sent to the attendee
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        assertThat(actual).contains("\"recipientEmail\":\"{attendeeEmail}\""
            .replace("{attendeeEmail}", attendee.email()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "TENTATIVE", "DECLINED"})
    void shouldReceiveNotificationEmailMessageWhenOrganizerPartStatUpdatedFromNeedsActionWithExternalAttendee(String partStat) {
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
            X-PUBLICLY-CREATED:true
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail);

        calDavClient.upsertCalendarEvent(organizer, eventUid, initialCalendarData);

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
            X-PUBLICLY-CREATED:true
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizer.email())
            .replace("{attendeeEmail}", externalAttendeeEmail)
            .replace("{partStat}", partStat);

        calDavClient.upsertCalendarEvent(organizer, eventUid, updatedCalendarData);

        // THEN: A notification email message should be sent to the external attendee
        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        assertThat(actual).contains("\"recipientEmail\":\"{attendeeEmail}\""
            .replace("{attendeeEmail}", externalAttendeeEmail));
    }

    private byte[] getMessageFromQueue() {
        return awaitAtMost.atMost(Duration.ofSeconds(20))
            .until(() -> dockerExtension().getChannel().basicGet(QUEUE_NAME, true), Objects::nonNull)
            .getBody();
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
