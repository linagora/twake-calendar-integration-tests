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
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestContainersUtils;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

public abstract class ResourceAMQPMessageContract {

    public static final String QUEUE_NAME = "tcalendar:event:test";

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    private CalDavClient calDavClient;
    private Connection connection;
    private Channel channel;
    
    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(TestContainersUtils.getContainerPrivateIpAddress(
            dockerExtension().getDockerTwakeCalendarSetupSingleton().getRabbitMqContainer()));
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        calDavClient = new CalDavClient(dockerExtension().davHttpClient());

        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.queueDeclare(QUEUE_NAME, false, true, true, null);
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
    void shouldReceiveMessageFromEventResourceCreatedExchange() throws IOException, ParseException {
        channel.queueBind(QUEUE_NAME, "resource:calendar:event:created", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", testUser)
            .block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "resourceId" : "{resourceId}",
              "eventId" : "{resourceEventId}.ics",
              "eventPath" : "/calendars/{resourceId}/{resourceId}/{resourceEventId}.ics",
              "ics" : "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nSUMMARY:Sprint planning #01\\r\\nLOCATION:Twake Meeting Room\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next w\\r\\n eek.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;\\r\\n CN=projector:mailto:{resourceId}@open-paas.org\\r\\nDTSTAMP:20250827T045634Z\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
              "etag" : "\\"61b33f55b55cd01b5174ea36cd2e149c\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id())
            .replace("{eventUid}", eventUid)
            .replace("{resourceEventId}", resourceEventId);

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("ics"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("ics"));
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        actualJson.remove("etag");
        expectedJson.remove("etag");
        actualJson.remove("ics");
        expectedJson.remove("ics");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveMessageFromEventResourceAcceptExchange() throws IOException, ParseException {
        channel.queueBind(QUEUE_NAME, "resource:calendar:event:accepted", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", testUser)
            .block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();

        String token = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // To ensure calendar directory is activated
        try {
            calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        } catch (Exception ignored) {
        }

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id(),
            "ACCEPTED");
        calDavClient.upsertCalendarEvent(resource.id(), resourceEventId, updatedCalendarData, token);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "resourceId" : "{resourceId}",
              "eventId" : "{eventUid}",
              "eventPath" : "/calendars/{resourceId}/{resourceId}/{resourceEventId}.ics",
              "ics" : "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nSUMMARY:Sprint planning #01\\r\\nLOCATION:Twake Meeting Room\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next w\\r\\n eek.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;\\r\\n CN=projector:mailto:{resourceId}@open-paas.org\\r\\nDTSTAMP:20250827T045634Z\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
              "etag" : "\\"61b33f55b55cd01b5174ea36cd2e149c\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id())
            .replace("{eventUid}", eventUid)
            .replace("{resourceEventId}", resourceEventId);

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("ics"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("ics"));
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        actualJson.remove("etag");
        expectedJson.remove("etag");
        actualJson.remove("ics");
        expectedJson.remove("ics");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void shouldReceiveMessageFromEventResourceDeclineExchange() throws IOException, ParseException {
        channel.queueBind(QUEUE_NAME, "resource:calendar:event:declined", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", testUser)
            .block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();

        String token = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // To ensure calendar directory is activated
        try {
            calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        } catch (Exception ignored) {
        }

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id(),
            "DECLINED");
        calDavClient.upsertCalendarEvent(resource.id(), resourceEventId, updatedCalendarData, token);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "resourceId" : "{resourceId}",
              "eventId" : "{eventUid}",
              "eventPath" : "/calendars/{resourceId}/{resourceId}/{resourceEventId}.ics",
              "ics" : "BEGIN:VCALENDAR\\r\\nVERSION:2.0\\r\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\r\\nCALSCALE:GREGORIAN\\r\\nBEGIN:VTIMEZONE\\r\\nTZID:Asia/Ho_Chi_Minh\\r\\nBEGIN:STANDARD\\r\\nTZOFFSETFROM:+0700\\r\\nTZOFFSETTO:+0700\\r\\nTZNAME:ICT\\r\\nDTSTART:19700101T000000\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\nBEGIN:VEVENT\\r\\nUID:{eventUid}\\r\\nSEQUENCE:1\\r\\nDTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000\\r\\nDTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000\\r\\nSUMMARY:Sprint planning #01\\r\\nLOCATION:Twake Meeting Room\\r\\nDESCRIPTION:This is a meeting to discuss the sprint planning for the next w\\r\\n eek.\\r\\nORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}\\r\\nATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}\\r\\nATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;\\r\\n CN=projector:mailto:{resourceId}@open-paas.org\\r\\nDTSTAMP:20250827T045634Z\\r\\nEND:VEVENT\\r\\nEND:VCALENDAR\\r\\n",
              "etag" : "\\"61b33f55b55cd01b5174ea36cd2e149c\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id())
            .replace("{eventUid}", eventUid)
            .replace("{resourceEventId}", resourceEventId);

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        Calendar actualCalendar = CalendarUtil.parseIcs(actualJson.getAsString("ics"));
        Calendar expectedCalendar = CalendarUtil.parseIcs(expectedJson.getAsString("ics"));
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        actualJson.remove("etag");
        expectedJson.remove("etag");
        actualJson.remove("ics");
        expectedJson.remove("ics");

        assertThat(actualJson.toJSONString()).isEqualTo(expectedJson.toJSONString());
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
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
                                        String dtend,
                                        String resourceId) {
        return generateCalendarData(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, resourceId, "TENTATIVE");
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String resourceId,
                                        String partStat) {

        return """
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
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            LOCATION:{location}
            DESCRIPTION:{description}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
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
            .replace("{partStat}", partStat)
            .replace("{resourceId}", resourceId);
    }
}
