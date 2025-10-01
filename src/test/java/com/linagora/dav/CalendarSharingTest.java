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

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class CalendarSharingTest {

    @RegisterExtension
    static DockerOpenPaasExtension extension = new DockerOpenPaasExtension();

    private CalDavClient calDavClient;

    private OpenPaasUser bob;
    private OpenPaasUser alice;
    private Channel channel;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        calDavClient = new CalDavClient(extension.davHttpClient());
        extension.getDockerOpenPaasSetupSingleton()
            .getOpenPaaSProvisioningService()
            .enableSharedCalendarModule()
            .block();

        bob = extension.newTestUser();
        alice = extension.newTestUser();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(extension.getDockerOpenPaasSetupSingleton().getHost(DockerOpenPaasSetup.DockerService.RABBITMQ));
        factory.setPort(extension.getDockerOpenPaasSetupSingleton().getPort(DockerOpenPaasSetup.DockerService.RABBITMQ));
        factory.setUsername("guest");
        factory.setPassword("guest");

        connection = factory.newConnection();
        channel = connection.createChannel();
    }

    @AfterEach
    void afterEach() throws Exception {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Disabled("Sabre DAV wrongly allows subscribing to private calendars. " +
        "Expected: forbidden. https://github.com/linagora/esn-sabre/issues/52")
    @Test
    void cannotSubscribeToPrivateCalendar() {
        // Given: Bob sets his calendar as private
        calDavClient.updateCalendarAcl(bob, "");

        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob subscribed")
            .color("#FF0000")
            .readOnly(true)
            .build();

        // WHEN / THEN
        assertThatThrownBy(() -> calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Unexpected status code");
    }

    @Test
    void canSubscribeToReadOnlyCalendar() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        // WHEN: Alice subscribes to Bob's calendar
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // THEN: Alice should see the subscription in her calendars
        List<JsonNode> subscribedList = calDavClient.findUserSubscribedCalendars(alice).collectList().block();

        assertThat(subscribedList)
            .anySatisfy(node -> {
                assertThat(node.path("dav:name").asText()).isEqualTo("Bob readonly shared");
                assertThat(node.path("apple:color").asText()).isEqualTo("#00FF00");
                assertThat(node.path("calendarserver:source").path("_links").path("self").path("href").asText())
                    .contains(bob.id());
            });
    }

    @Test
    void canSubscribeToReadWriteCalendar() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // WHEN: Alice subscribes to Bob's calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // THEN: Alice should see the subscription in her calendars
        List<JsonNode> subscribedList = calDavClient.findUserSubscribedCalendars(alice).collectList().block();

        assertThat(subscribedList)
            .anySatisfy(node -> {
                assertThat(node.path("dav:name").asText()).isEqualTo("Bob writable shared");
                assertThat(node.path("apple:color").asText()).isEqualTo("#0000FF");
                assertThat(node.path("calendarserver:source").path("_links").path("self").path("href").asText())
                    .contains(bob.id());
            });
    }

    @Test
    void copyExistingEventsFromReadonlyCalendar() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String description = "Important meeting with Alice";
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Bob's readonly event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, description);

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // WHEN: Alice subscribes to Bob's readonly calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // THEN: Alice's copy of Bob's calendar should also contain the event (with description)
        String subscribedCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarRequest.id() + ".json";

        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(
            alice,
            subscribedCalendarURI,
            Instant.parse("2025-09-01T00:00:00Z"),
            Instant.parse("2025-11-01T00:00:00Z")).collectList().block();

        assertThat(aliceEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains(description);
            });
    }

    @Test
    void copyNewEventsFromReadonlyCalendar() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's readonly calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // WHEN: Bob adds a new event in his own calendar
        String eventUid = "event-" + UUID.randomUUID();
        String description = "Follow-up sync with Alice";
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251001T090000Z
            DTEND:20251001T100000Z
            SUMMARY:Bob's new event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, description);

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // THEN: the event should also be visible by Alice in her copy of Bob's calendar
        String subscribedCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarRequest.id() + ".json";

        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(
            alice,
            subscribedCalendarURI,
            Instant.parse("2025-09-01T00:00:00Z"),
            Instant.parse("2025-11-01T00:00:00Z")).collectList().block();
        assertThat(aliceEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains(description);
            });
    }

    @Test
    void copyUpdatedEventsFromReadonlyCalendar() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Bob has an event (event1) in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String originalDescription = "Initial planning meeting";
        String updatedDescription = "Updated planning meeting with Alice";

        String originalEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251002T090000Z
            DTEND:20251002T100000Z
            SUMMARY:Bob's event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, originalDescription);

        calDavClient.upsertCalendarEvent(bob, eventUid, originalEvent);

        // AND: Alice subscribes to Bob's readonly calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // WHEN: Bob modifies event1
        String updatedEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251002T100000Z
            DTEND:20251002T110000Z
            SUMMARY:Bob's updated event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, updatedDescription);

        calDavClient.upsertCalendarEvent(bob, eventUid, updatedEvent);

        // THEN: Alice should see the updated version in her copy, not the original
        String subscribedCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarRequest.id() + ".json";

        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(
            alice,
            subscribedCalendarURI,
            Instant.parse("2025-09-01T00:00:00Z"),
            Instant.parse("2025-11-01T00:00:00Z")).collectList().block();

        assertThat(aliceEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains(updatedDescription);
                assertThat(json).doesNotContain(originalDescription);
            });
    }

    @Test
    void copyDeletesFromReadonlyCalendar() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String description = "Event to be deleted";
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Bob's deletable event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, description);

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND: Alice subscribes to Bob's readonly calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        String subscribedCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarRequest.id() + ".json";

        // Supplier để tránh duplicate code
        Supplier<List<JsonNode>> aliceEvents = () -> calDavClient.reportCalendarEvents(
            alice,
            subscribedCalendarURI,
            Instant.parse("2025-09-01T00:00:00Z"),
            Instant.parse("2025-11-01T00:00:00Z")
        ).collectList().block();

        // CONFIRM: Before deletion, Alice sees the event
        assertThat(aliceEvents.get())
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains(description);
            });

        // WHEN: Bob deletes the event
        calDavClient.deleteCalendarEvent(bob, eventUid);

        // THEN: After deletion, Alice should not see the event anymore
        assertThat(aliceEvents.get())
            .noneSatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
            });
    }

    @Test
    void subscriptionIsRevokedWhenSourceBecomesPrivate() {
        // GIVEN: Bob sets his calendar as publicly-readable
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob public shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // CONFIRM: Alice initially sees the subscription
        List<JsonNode> aliceSubscribedBefore = calDavClient.findUserSubscribedCalendars(alice)
            .collectList().block();
        assertThat(aliceSubscribedBefore)
            .anySatisfy(node -> assertThat(node.get("dav:name").asText()).isEqualTo("Bob public shared"));

        // WHEN: Bob changes his calendar to private
        calDavClient.updateCalendarAcl(bob, "");

        // THEN: Alice should no longer see the subscription
        List<JsonNode> aliceSubscribedAfter = calDavClient.findUserSubscribedCalendars(alice)
            .collectList().block();
        assertThat(aliceSubscribedAfter)
            .noneSatisfy(node -> assertThat(node.get("dav:name").asText()).isEqualTo("Bob public shared"));
    }

    @Test
    void synchronizedCalendarsAreNotListedPublicly() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // WHEN: Cedric tries to list Alice's calendars
        OpenPaasUser cedric = extension.newTestUser();
        List<JsonNode> cedricViewOnAliceCalendars = calDavClient.findUserSubscribedCalendars(cedric, alice.id())
            .collectList()
            .block();

        // THEN: Cedric should see nothing
        assertThat(cedricViewOnAliceCalendars).isEmpty();
    }

    @Test
    void unsubscribeCalendarRemovesOnlyFromAlice() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's calendar
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // WHEN: Alice unsubscribes (deletes her copy)
        String aliceSubscribedCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarRequest.id() + ".json";
        calDavClient.deleteSubscribedCalendar(alice, aliceSubscribedCalendarURI);

        // THEN: Alice should no longer see the calendar
        List<JsonNode> aliceCalendars = calDavClient.findUserSubscribedCalendars(alice)
            .collectList()
            .block();
        assertThat(aliceCalendars)
            .noneSatisfy(node -> assertThat(node.get("dav:name").asText()).isEqualTo("Bob readonly shared"));

        // AND: Bob should still see his original calendar in his own collection
        List<CalendarURL> bobCalendars = calDavClient.findUserCalendars(bob).collectList().block();
        assertThat(bobCalendars)
            .anySatisfy(url -> assertThat(url.asUri().toString())
                .contains("/calendars/" + bob.id() + "/" + bob.id()));
    }

    @Test
    void removalOfSourceCollectionAlsoDeletesSubscribedCopy() {
        // GIVEN: Bob creates a new calendar (non-default)
        String bobCalendarId = UUID.randomUUID().toString();
        String bobCalendarName = "Bob custom calendar";
        calDavClient.createNewCalendar(bob, bobCalendarId, bobCalendarName);

        CalendarURL bobCustomCalendar = new CalendarURL(bob.id(), bobCalendarId);

        // AND: Bob sets it as readonly
        calDavClient.updateCalendarAcl(bob, bobCustomCalendar, "{DAV:}read");

        // AND: Alice subscribes to Bob's custom calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .sourceCalendarId(bobCalendarId) // non-default calendar
            .name("Subscribed copy of Bob custom calendar")
            .color("#FF0000")
            .readOnly(true)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // THEN (before deletion): Alice should see the subscribed copy
        List<JsonNode> aliceSubscribedBefore = calDavClient.findUserSubscribedCalendars(alice).collectList().block();
        assertThat(aliceSubscribedBefore)
            .anySatisfy(node -> assertThat(node.get("dav:name").asText())
                .isEqualTo("Subscribed copy of Bob custom calendar"));

        // WHEN: Bob deletes his custom calendar
        calDavClient.deleteCalendar(bob, bobCustomCalendar);

        // THEN (after deletion): Alice should no longer see the subscribed copy
        List<JsonNode> aliceSubscribedAfter = calDavClient.findUserSubscribedCalendars(alice).collectList().block();
        assertThat(aliceSubscribedAfter)
            .noneSatisfy(node -> assertThat(node.get("dav:name").asText())
                .isEqualTo("Subscribed copy of Bob custom calendar"));
    }

    @Disabled("Sabre DAV emits AMQP messages for Alice's subscribed copy. " +
        "Expected: only Bob's original calendar should emit events. " +
        "See https://github.com/linagora/esn-sabre/issues/53")
    @Test
    void noAmqpMessagesEmittedForSubscribedCopy() throws Exception {
        String queueName = "sharing-test" + alice.id();
        channel.queueDeclare(queueName, false, true, true, null);
        channel.queueBind(queueName, "calendar:event:created", "");
        channel.queueBind(queueName, "calendar:event:updated", "");
        channel.queueBind(queueName, "calendar:event:deleted", "");

        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's readonly calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // AND: Listen to Alice's AMQP queue
        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(channel, queueName);

        // WHEN: Bob adds an event in his own calendar
        String eventUid = "event-" + UUID.randomUUID();
        String description = "Follow-up sync with Alice";
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251001T090000Z
            DTEND:20251001T100000Z
            SUMMARY:Bob's new event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, description);

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // THEN: Ensure no message is emitted for Alice's copy (but Bob's is fine)
        String aliceCalendarPath = "/calendars/" + alice.id();
        Thread.sleep(2000);

        assertThat(messages)
            .noneSatisfy(json ->
                assertThat(json.path("eventPath").asText()).startsWith(aliceCalendarPath));
    }

    @Test
    void noAmqpAlarmMessagesEmittedForSubscribedCopy() throws Exception {
        String queueName = "sharing-test-alarms-" + alice.id();
        channel.queueDeclare(queueName, false, false, true, null);
        channel.queueBind(queueName, "calendar:event:alarm:created", "");
        channel.queueBind(queueName, "calendar:event:alarm:updated", "");
        channel.queueBind(queueName, "calendar:event:alarm:deleted", "");

        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's readonly calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob readonly shared")
            .color("#00FF00")
            .readOnly(true)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // AND: Listen to Alice's AMQP queue
        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(channel, queueName);

        // WHEN: Bob adds an event in his own calendar WITH an alarm
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Bob's alarmed event
            DESCRIPTION:Meeting with alarm
            BEGIN:VALARM
            TRIGGER:-PT10M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:test alarm
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // THEN: Ensure no alarm message is emitted for Alice's copy
        String aliceCalendarPath = "/calendars/" + alice.id();
        Thread.sleep(2000);

        assertThat(messages)
            .noneSatisfy(json ->
                assertThat(json.path("eventPath").asText()).startsWith(aliceCalendarPath));
    }
}
