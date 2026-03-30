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

import org.apache.commons.lang3.BooleanUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;

import net.fortuna.ical4j.model.Calendar;

public abstract class ResourceAMQPMessageContract {

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    private CalDavClient calDavClient;
    
    public abstract DockerTwakeCalendarExtension dockerExtension();

    // TODO ISSUE-182
    protected String expectedResourceAcceptPartStat() {
        return BooleanUtils.toBoolean(System.getProperty("amqp.scheduling.enabled", "false")) ? "ACCEPTED" : "TENTATIVE";
    }

    protected String expectedResourceDeclinePartStat() {
        return BooleanUtils.toBoolean(System.getProperty("amqp.scheduling.enabled", "false")) ? "DECLINED" : "TENTATIVE";
    }

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
    }

    @Test
    void shouldReceiveMessageFromEventResourceCreatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "resource:calendar:event:created", "");

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
        BlockingQueue<JsonNode> messages = listenToQueue();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();
        String expectedEventIcs = """
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
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id());

        String expected = """
            {
              "resourceId" : "{resourceId}",
              "eventId" : "{resourceEventId}.ics",
              "eventPath" : "/calendars/{resourceId}/{resourceId}/{resourceEventId}.ics",
              "ics" : "${json-unit.any-string}",
              "etag" : "${json-unit.any-string}"
            }
            """
            .replace("{resourceId}", resource.id())
            .replace("{eventUid}", eventUid)
            .replace("{resourceEventId}", resourceEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> resource.id().equals(message.path("resourceId").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("ics").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    CalendarUtil.removeParticipantScheduleStatus(actualCalendar);
                    CalendarUtil.removeParticipantScheduleStatus(expectedCalendar);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveMessageFromEventResourceAcceptExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "resource:calendar:event:accepted", "");

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
        BlockingQueue<JsonNode> messages = listenToQueue();
        calDavClient.upsertCalendarEvent(resource.id(), resourceEventId, updatedCalendarData, token);

        String expectedEventIcs = """
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
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id())
            .replace("{partStat}", expectedResourceAcceptPartStat());

        String expected = """
            {
              "resourceId" : "{resourceId}",
              "eventId" : "{eventUid}",
              "eventPath" : "/calendars/{resourceId}/{resourceId}/{resourceEventId}.ics",
              "ics" : "${json-unit.any-string}",
              "etag" : "${json-unit.any-string}"
            }
            """
            .replace("{resourceId}", resource.id())
            .replace("{eventUid}", eventUid)
            .replace("{resourceEventId}", resourceEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> resource.id().equals(message.path("resourceId").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("ics").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    CalendarUtil.removeParticipantScheduleStatus(actualCalendar);
                    CalendarUtil.removeParticipantScheduleStatus(expectedCalendar);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    @Test
    void shouldReceiveMessageFromEventResourceDeclineExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "resource:calendar:event:declined", "");

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
        BlockingQueue<JsonNode> messages = listenToQueue();
        calDavClient.upsertCalendarEvent(resource.id(), resourceEventId, updatedCalendarData, token);

        String expectedEventIcs = """
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
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id())
            .replace("{partStat}", expectedResourceDeclinePartStat());


        String expected = """
            {
              "resourceId" : "{resourceId}",
              "eventId" : "{eventUid}",
              "eventPath" : "/calendars/{resourceId}/{resourceId}/{resourceEventId}.ics",
              "ics" : "${json-unit.any-string}",
              "etag" : "${json-unit.any-string}"
            }
            """
            .replace("{resourceId}", resource.id())
            .replace("{eventUid}", eventUid)
            .replace("{resourceEventId}", resourceEventId);

        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .filteredOn(message -> resource.id().equals(message.path("resourceId").asText()))
                .anySatisfy(message -> {
                    assertThatJson(message.toString())
                        .isEqualTo(expected);

                    Calendar actualCalendar = CalendarUtil.parseIcsAndSanitize(message.path("ics").asText());
                    Calendar expectedCalendar = CalendarUtil.parseIcsAndSanitize(expectedEventIcs);
                    CalendarUtil.removeParticipantScheduleStatus(actualCalendar);
                    CalendarUtil.removeParticipantScheduleStatus(expectedCalendar);
                    assertThat(actualCalendar).isEqualTo(expectedCalendar);
                }));
    }

    private BlockingQueue<JsonNode> listenToQueue() {
        return AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), QUEUE_NAME);
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
