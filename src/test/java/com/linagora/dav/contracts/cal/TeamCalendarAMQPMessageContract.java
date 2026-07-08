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

package com.linagora.dav.contracts.cal;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;

import net.javacrumbs.jsonunit.core.Option;

public abstract class TeamCalendarAMQPMessageContract {

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
    }

    @Test
    void teamCalendarEventCreationShouldPublishCreatedMessage() throws IOException {
        // Given a write-enabled Team Calendar member and a fresh queue bound to created messages
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("indexing", "Indexing Team");
        OpenPaasUser member = dockerExtension().newTestUser();
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, member, DelegationRight.READ_WRITE);
        String eventUid = UUID.randomUUID().toString();
        String summary = "Team calendar created event";
        String teamCalendarPath = CalendarURL.from(teamCalendar.id()).asUri().toString();
        BlockingQueue<JsonNode> messages = listenToFreshQueue("team-calendar-created-", "calendar:event:created");

        // When the member creates an event through the delegated Team Calendar URL
        calDavClient.upsertCalendarEvent(member, delegatedCalendar, eventUid, calendarData(eventUid, member.email(), summary));

        // Then the indexing message uses the canonical Team Calendar event path and payload
        String expected = """
            {
              "eventPath": "${json-unit.any-string}",
              "rawEvent": "${json-unit.any-string}",
              "event": "${json-unit.ignore}"
            }
            """;
        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .as("Team Calendar created indexing message should be published")
                .anySatisfy(message -> assertSoftly(softly -> {
                    softly.assertThatCode(() -> assertThatJson(message.toString())
                            .when(Option.IGNORING_EXTRA_FIELDS)
                            .isEqualTo(expected))
                        .doesNotThrowAnyException();
                    softly.assertThat(message.path("eventPath").asText())
                        .startsWith(teamCalendarPath + "/");
                    softly.assertThat(message.path("rawEvent").asText())
                        .contains("UID:" + eventUid, "ORGANIZER:mailto:" + member.email(), "SUMMARY:" + summary);
                    softly.assertThat(message.path("event").toString())
                        .contains(eventUid, "mailto:" + member.email(), summary);
                })));
    }

    @Test
    void teamCalendarEventUpdateShouldPublishUpdatedMessage() throws IOException {
        // Given a Team Calendar event already exists and a fresh queue tracks only update messages
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("indexing", "Indexing Team");
        OpenPaasUser member = dockerExtension().newTestUser();
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, member, DelegationRight.READ_WRITE);
        String eventUid = UUID.randomUUID().toString();
        String initialSummary = "Team calendar event before update";
        calDavClient.upsertCalendarEvent(member, delegatedCalendar, eventUid,
            calendarData(eventUid, member.email(), initialSummary));
        BlockingQueue<JsonNode> messages = listenToFreshQueue("team-calendar-updated-", "calendar:event:updated");
        String updatedSummary = "Team calendar event after update";
        String teamCalendarPath = CalendarURL.from(teamCalendar.id()).asUri().toString();

        // When the same member updates the event content through the delegated Team Calendar URL
        calDavClient.upsertCalendarEvent(member, delegatedCalendar, eventUid, calendarData(eventUid, member.email(), updatedSummary));

        // Then the update message keeps the canonical Team Calendar path and carries both old and new event content
        String expected = """
            {
              "eventPath": "${json-unit.any-string}",
              "rawEvent": "${json-unit.any-string}",
              "event": "${json-unit.ignore}",
              "old_event": "${json-unit.ignore}"
            }
            """;
        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .as("Team Calendar updated indexing message should be published")
                .anySatisfy(message -> assertSoftly(softly -> {
                    softly.assertThatCode(() -> assertThatJson(message.toString())
                            .when(Option.IGNORING_EXTRA_FIELDS)
                            .isEqualTo(expected))
                        .doesNotThrowAnyException();
                    softly.assertThat(message.path("eventPath").asText())
                        .startsWith(teamCalendarPath + "/");
                    softly.assertThat(message.path("rawEvent").asText())
                        .contains("UID:" + eventUid, "ORGANIZER:mailto:" + member.email(), "SUMMARY:" + updatedSummary);
                    softly.assertThat(message.path("event").toString())
                        .contains(eventUid, "mailto:" + member.email(), updatedSummary);
                    softly.assertThat(message.path("old_event").toString())
                        .contains(initialSummary);
                })));
    }

    @Test
    void teamCalendarEventDeletionShouldPublishDeletedMessage() throws IOException {
        // Given a Team Calendar event exists and a fresh queue tracks only delete messages
        OpenPaaSTeamCalendar teamCalendar = newTeamCalendar("indexing", "Indexing Team");
        OpenPaasUser member = dockerExtension().newTestUser();
        CalendarURL delegatedCalendar = delegateTeamCalendarTo(teamCalendar, member, DelegationRight.READ_WRITE);
        String eventUid = UUID.randomUUID().toString();
        String summary = "Team calendar event to delete";
        calDavClient.upsertCalendarEvent(member, delegatedCalendar, eventUid, calendarData(eventUid, member.email(), summary));
        String teamCalendarPath = CalendarURL.from(teamCalendar.id()).asUri().toString();
        BlockingQueue<JsonNode> messages = listenToFreshQueue("team-calendar-deleted-", "calendar:event:deleted");

        // When the member deletes the event through the delegated Team Calendar URL
        calDavClient.deleteCalendarEvent(member, delegatedCalendar, eventUid);

        // Then the delete message still points search/indexing to the canonical Team Calendar event path
        awaitAtMost.untilAsserted(() ->
            assertThat(messages)
                .as("Team Calendar deleted indexing message should be published")
                .anySatisfy(message -> assertThat(message.path("eventPath").asText())
                    .startsWith(teamCalendarPath + "/")));
    }

    private OpenPaaSTeamCalendar newTeamCalendar(String namePrefix, String displayName) {
        return dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar(namePrefix + "-" + UUID.randomUUID(), displayName)
            .block();
    }

    private CalendarURL delegateTeamCalendarTo(OpenPaaSTeamCalendar teamCalendar, OpenPaasUser user, DelegationRight right) {
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        calDavClient.grantDelegation(teamCalendar.id(), user, right, technicalToken);
        List<CalendarURL> delegatedCalendars = calDavClient.findDelegatedCalendar(user);
        assertThat(delegatedCalendars)
            .as("User should have one delegated team calendar")
            .hasSize(1);
        return delegatedCalendars.getFirst();
    }

    private BlockingQueue<JsonNode> listenToFreshQueue(String queuePrefix, String exchange) throws IOException {
        String queueName = queuePrefix + UUID.randomUUID();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, exchange, "");
        return AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);
    }

    private String calendarData(String eventUid, String organizerEmail, String summary) {
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
            LOCATION:Twake Meeting Room
            DESCRIPTION:Team calendar AMQP indexing contract
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizerEmail, summary);
    }

}
