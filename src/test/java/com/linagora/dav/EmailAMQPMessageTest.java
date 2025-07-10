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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

class EmailAMQPMessageTest {

    public static final String QUEUE_NAME = "tcalendar:event:notificationEmail:send";
    public static final boolean NOT_COUNTER = false;
    public static final boolean COUNTER = true;

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    private DavClient davClient;
    private Connection connection;
    private Channel channel;

    @BeforeEach
    void setUp() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(TestContainersUtils.getContainerPrivateIpAddress(
            dockerOpenPaasExtension.getDockerOpenPaasSetupSingleton().getRabbitMqContainer()));
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        davClient = new DavClient(dockerOpenPaasExtension.davHttpClient());

        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.queueDeclare(QUEUE_NAME, false, true, true, null);
        channel.queueBind(QUEUE_NAME, "calendar:event:notificationEmail:send", "");
    }

    @AfterEach
    void cleanUp() throws IOException, TimeoutException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventCreation() throws ParseException {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();
        OpenPaasUser testUser2 = dockerOpenPaasExtension.newTestUser();

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
        davClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> davClient.findFirstEventId(testUser2), Optional::isPresent).get();

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
        actualJson.remove("event");
        expectedJson.remove("event");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventUpdate() throws ParseException {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();
        OpenPaasUser testUser2 = dockerOpenPaasExtension.newTestUser();

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
        davClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost.until(() -> davClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #02",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next 2 weeks.",
            "30250411T150000",
            "30250411T160000");
        davClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

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
        actualJson.remove("event");
        expectedJson.remove("event");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventCancel() throws ParseException {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();
        OpenPaasUser testUser2 = dockerOpenPaasExtension.newTestUser();

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
        davClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> davClient.findFirstEventId(testUser2), Optional::isPresent).get();

        davClient.deleteCalendarEvent(testUser, eventUid);

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
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        actualJson.remove("event");
        expectedJson.remove("event");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventReply() throws ParseException {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();
        OpenPaasUser testUser2 = dockerOpenPaasExtension.newTestUser();

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
        davClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost.until(() -> davClient.findFirstEventId(testUser2), Optional::isPresent).get();

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
        davClient.upsertCalendarEvent(testUser2, attendeeEventId, updatedCalendarData);

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
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        actualJson.remove("event");
        expectedJson.remove("event");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveNotificationEmailMessageOnEventCounter() throws ParseException {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();
        OpenPaasUser testUser2 = dockerOpenPaasExtension.newTestUser();

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
        davClient.upsertCalendarEvent(testUser, eventUid, initialCalendarData);

        String attendeeEventId = awaitAtMost.until(() -> davClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCounterCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T150000",
            "30250411T160000");
        DavClient.CounterRequest counterRequest = new DavClient.CounterRequest(
            updatedCalendarData,
            testUser2.email(),
            testUser.email(),
            eventUid,
            1);
        davClient.postCounter(testUser2, attendeeEventId, counterRequest);

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
        actualJson.remove("event");
        expectedJson.remove("event");
        actualJson.remove("oldEvent");
        expectedJson.remove("oldEvent");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
        assertThat(actualOldCalendar).isEqualTo(expectedOldCalendar);
    }

    private byte[] getMessageFromQueue() {
        return awaitAtMost.atMost(Duration.ofSeconds(20))
            .until(() -> channel.basicGet(QUEUE_NAME, true), Objects::nonNull)
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
