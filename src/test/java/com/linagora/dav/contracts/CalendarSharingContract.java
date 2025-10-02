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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;
import com.rabbitmq.client.Channel;

public abstract class CalendarSharingContract {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    public abstract DockerTwakeCalendarExtension extension();

    private CalDavClient calDavClient;

    private OpenPaasUser bob;
    private OpenPaasUser alice;
    private OpenPaasUser cedric;
    private Channel channel;

    @BeforeEach
    void setUp() throws Exception {
        calDavClient = new CalDavClient(extension().davHttpClient());
        extension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .enableSharedCalendarModule()
            .block();

        bob = extension().newTestUser();
        alice = extension().newTestUser();
        cedric = extension().newTestUser();
        channel = extension().getChannel();
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
    void canDelegateReadWriteManageCalendar() {
        // GIVEN: Bob has a calendar and delegates it to Alice with administration rights (manage)
        String calendarId = bob.id(); // default calendar
        calDavClient.delegateCalendar(bob, calendarId, alice, "dav:administration");

        // WHEN: Alice lists her delegated
        List<JsonNode> aliceDelegatedCalendars = calDavClient.findUserCalendarsWithOptions(alice, alice.id(), true, true, true)
            .collectList()
            .block();

        // THEN: Alice should see Bob's delegated calendar
        assertThat(aliceDelegatedCalendars)
            .anySatisfy(node -> {
                // Delegated source points to Bob's calendar
                assertThat(node.path("calendarserver:delegatedsource").asText())
                    .contains(bob.id());

                // Invite contains Alice
                JsonNode invites = node.path("invite");
                JsonNode aliceInvite = StreamSupport.stream(invites.spliterator(), false)
                    .filter(invite -> invite.path("href").asText().contains(alice.email()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No invite entry found for Alice (" + alice.email() + ")"));

                assertThat(aliceInvite.path("href").asText()).contains(alice.email());
                assertThat(aliceInvite.path("access").asInt()).isEqualTo(5);  // full rights
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
    void cannotReadPrivateEventWhenPubliclyReadable() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

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
            CLASS:PRIVATE
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
                assertThat(json).contains("[\"summary\",{},\"text\",\"Busy\"]");
                assertThat(json).doesNotContain(description);
            });
    }

    @Test
    void cannotReadPrivateEventWhenPubliclyWritable() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

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
            CLASS:PRIVATE
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
                assertThat(json).contains("[\"summary\",{},\"text\",\"Busy\"]");
                assertThat(json).doesNotContain(description);
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
        OpenPaasUser cedric = extension().newTestUser();
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

    @Test
    void amqpMessagesEmittedForSubscribedCopy() throws Exception {
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
            .anySatisfy(json ->
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

    @Test
    void rejectUpdateOnReadOnlyCalendar() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Bob has an event in his calendar
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
            SUMMARY:Bob's readonly event
            DESCRIPTION:Initial event by Bob
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

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

        // WHEN: Alice tries to update Bob's event in her subscribed copy
        String subscribedCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";
        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(alice, subscribedCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"), Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(aliceEvents).hasSize(1);

        String eventHref = aliceEvents.getFirst().path("_links").path("self").path("href").asText();

        // WHEN: Alice tries to update the event
        // THEN: The update should be rejected
        String updatedEventData = calendarData.replace("SUMMARY:Bob's readonly event",
            "SUMMARY:Alice tries to update Bob's event");

        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, URI.create(eventHref), updatedEventData))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unexpected status code: 403")
            .hasMessageContaining("User did not have the required privileges");
    }

    @Test
    void propagateEventUpdatesFromSubscribedCopyToSource() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // AND: Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String originalEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251010T090000Z
            DTEND:20251010T100000Z
            SUMMARY:Shared event
            DESCRIPTION:Event created by Bob
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        calDavClient.upsertCalendarEvent(bob, eventUid, originalEvent);

        // AND: Alice subscribes to Bob's writable calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // AND: Alice sees Bob's event in her subscribed copy
        String aliceCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";
        Supplier<List<JsonNode>> aliceEventsSupplier = () -> calDavClient.reportCalendarEvents(
                alice,
                aliceCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        List<JsonNode> aliceEvents = aliceEventsSupplier.get();
        assertThat(aliceEvents).hasSize(1);
        String eventHref = aliceEvents.getFirst().path("_links").path("self").path("href").asText();

        // WHEN: Alice updates the event
        String updatedEvent = originalEvent
            .replace("DESCRIPTION:Event created by Bob", "DESCRIPTION:Alice updated the event");

        calDavClient.upsertCalendarEvent(alice, URI.create(eventHref), updatedEvent);

        // THEN: Bob should see the updated event in his own calendar
        String bobCalendarURI = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        List<JsonNode> bobEvents = calDavClient.reportCalendarEvents(
                bob,
                bobCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(bobEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).doesNotContain("Event created by Bob");
                assertThat(json).contains("Alice updated the event");
            });

        // Alice should see the updated event
        List<JsonNode> aliceEventsAfter = aliceEventsSupplier.get();
        assertThat(aliceEventsAfter)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).doesNotContain("Event created by Bob");
                assertThat(json).contains("Alice updated the event");
            });
    }

    @Test
    void propagateEventDeletionFromSubscribedCopyToSource() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // AND: Bob has an event with Alice and Cedric in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String eventData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251012T090000Z
            DTEND:20251012T100000Z
            SUMMARY:Shared event to be deleted
            DESCRIPTION:Event with Alice and Cedric
            ATTENDEE;CN=Alice:mailto:%s
            ATTENDEE;CN=Cedric:mailto:cedric@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, eventData);

        // AND: Alice subscribes to Bob's writable calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        String aliceCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";

        Supplier<List<JsonNode>> aliceEventsSupplier = () -> calDavClient.reportCalendarEvents(
                alice, aliceCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        // AND: Alice sees the event in her subscribed copy
        List<JsonNode> aliceEvents = aliceEventsSupplier.get();
        assertThat(aliceEvents).hasSize(1);
        String eventHref = aliceEvents.getFirst().path("_links").path("self").path("href").asText();

        // WHEN: Alice deletes the event from her copy
        calDavClient.deleteCalendarEvent(alice, URI.create(eventHref));

        // THEN: Bob should not see the event anymore
        String bobCalendarURI = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        List<JsonNode> bobEvents = calDavClient.reportCalendarEvents(
                bob, bobCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(bobEvents)
            .noneSatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
            });

        // AND: Alice should not see the event anymore in her copy
        List<JsonNode> aliceEventsAfter = aliceEventsSupplier.get();
        assertThat(aliceEventsAfter)
            .noneSatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
            });
    }

    @Test
    void triggersITIPRequestWhenAttendeeAdded() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // AND: Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String originalEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251015T090000Z
            DTEND:20251015T100000Z
            SUMMARY:Team sync
            DESCRIPTION:Initial event by Bob
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), bob.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, originalEvent);

        // AND: Alice subscribes to Bob's writable calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // WHEN: Alice updates the event by adding Cedric as attendee
        String aliceCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";
        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(
                alice,
                aliceCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(aliceEvents).hasSize(1);
        String eventHref = aliceEvents.getFirst().path("_links").path("self").path("href").asText();

        String updatedEvent = originalEvent.replace("END:VEVENT",
            "ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:" + cedric.email() + "\r\nEND:VEVENT");
        calDavClient.upsertCalendarEvent(alice, URI.create(eventHref), updatedEvent);

        // THEN: Bob should see the updated event in his own calendar
        String bobCalendarURI = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        List<JsonNode> bobEvents = calDavClient.reportCalendarEvents(
                bob,
                bobCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(bobEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains(cedric.email());
            });

        // AND: Cedric should have an iTIP request in his inbox
        String cedricInboxUri = "/calendars/" + cedric.id() + "/inbox/";
        List<JsonNode> cedricInboxItems = calDavClient.reportCalendarEvents(cedric, cedricInboxUri, Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(cedricInboxItems)
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains("\"REQUEST\"");
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + cedric.email());
                assertThat(json).contains("\"partstat\":\"NEEDS-ACTION\"");
            });
    }

    @Test
    void triggersITIPRequestWhenEventUpdated() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // AND: Bob has an event in his calendar with Cedric & Alice as attendees
        String eventUid = "event-" + UUID.randomUUID();
        String originalEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251020T090000Z
            DTEND:20251020T100000Z
            SUMMARY:Planning meeting
            DESCRIPTION:Initial event by Bob
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:%s
            ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:%s
            ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), bob.email(), alice.email(), cedric.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, originalEvent);

        // AND: Alice subscribes to Bob's writable calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // WHEN: Alice updates the event (changes description)
        String aliceCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";
        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(
                alice,
                aliceCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(aliceEvents).hasSize(1);
        String eventHref = aliceEvents.getFirst().path("_links").path("self").path("href").asText();

        String updatedEvent = originalEvent.replace("DESCRIPTION:Initial event by Bob",
            "DESCRIPTION:Updated by Alice");

        calDavClient.upsertCalendarEvent(alice, URI.create(eventHref), updatedEvent);

        // THEN: Bob should see the updated event in his own calendar
        String bobCalendarURI = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        List<JsonNode> bobEvents = calDavClient.reportCalendarEvents(
                bob,
                bobCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(bobEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("Updated by Alice");
            });

        // AND: Both Alice and Cedric should receive an iTIP UPDATE request
        List<OpenPaasUser> attendees = List.of(alice, cedric);

        for (OpenPaasUser attendee : attendees) {
            String inboxUri = "/calendars/" + attendee.id() + "/inbox/";
            List<JsonNode> inboxItems = calDavClient.reportCalendarEvents(
                    attendee,
                    inboxUri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList().block();

            assertThat(inboxItems)
                .anySatisfy(item -> {
                    String json = item.toString();
                    assertThat(json).contains(eventUid);
                    assertThat(json).contains("mailto:" + attendee.email());
                    assertThat(json).contains("Updated by Alice");
                    assertThatJson(item)
                        .inPath("data[1]")
                        .isArray()
                        .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                            .isEqualTo("""
                                ["method", {}, "text", "REQUEST"]"""));
                });
        }
    }

    @Test
    void triggersITIPCancelWhenEventDeleted() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // AND: Bob has an event in his calendar with Cedric & Alice as attendees
        String eventUid = "event-" + UUID.randomUUID();
        String originalEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251025T090000Z
            DTEND:20251025T100000Z
            SUMMARY:Planning meeting
            DESCRIPTION:Initial event by Bob
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:%s
            ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:%s
            ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), bob.email(), alice.email(), cedric.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, originalEvent);

        // AND: Alice subscribes to Bob's writable calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // CONFIRM: Alice sees the event
        String aliceCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";
        List<JsonNode> aliceEventsBefore = calDavClient.reportCalendarEvents(
                alice,
                aliceCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();
        assertThat(aliceEventsBefore).hasSize(1);

        String eventHref = aliceEventsBefore.getFirst().path("_links").path("self").path("href").asText();

        // WHEN: Alice deletes the event from her subscribed copy
        calDavClient.deleteCalendarEvent(alice, URI.create(eventHref));

        // THEN: Bob should no longer see the event in his own calendar
        String bobCalendarURI = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        List<JsonNode> bobEventsAfter = calDavClient.reportCalendarEvents(
                bob,
                bobCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();
        assertThat(bobEventsAfter).isEmpty();

        // AND: Both Alice and Cedric should receive an iTIP CANCEL request
        List<OpenPaasUser> attendees = List.of(alice, cedric);

        for (OpenPaasUser attendee : attendees) {
            String inboxUri = "/calendars/" + attendee.id() + "/inbox/";
            List<JsonNode> inboxItems = calDavClient.reportCalendarEvents(
                    attendee,
                    inboxUri,
                    Instant.parse("2025-09-01T00:00:00Z"),
                    Instant.parse("2025-11-01T00:00:00Z"))
                .collectList().block();

            assertThat(inboxItems)
                .anySatisfy(item -> {
                    String json = item.toString();
                    assertThat(json).contains(eventUid);
                    assertThat(json).contains("mailto:" + attendee.email());

                    assertThatJson(item)
                        .inPath("data[1]")
                        .isArray()
                        .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                            .isEqualTo("""
                                ["method", {}, "text", "CANCEL"]"""));
                });
        }
    }

    @Test
    void triggersITIPCancelWhenAttendeeRemoved() {
        // GIVEN: Bob sets his calendar as read-write
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        // AND: Bob has an event with Alice and Cedric as attendees
        String eventUid = "event-" + UUID.randomUUID();
        String originalEvent = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Planning sync
            DESCRIPTION:Initial event by Bob
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=ACCEPTED:mailto:%s
            ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:%s
            ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), bob.email(), alice.email(), cedric.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, originalEvent);

        // AND: Alice subscribes to Bob's writable calendar
        String subscribedCalendarId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribedCalendarRequest = SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(bob.id())
            .name("Bob writable shared")
            .color("#0000FF")
            .readOnly(false)
            .build();

        calDavClient.subscribeToSharedCalendar(alice, subscribedCalendarRequest);

        // CONFIRM: Alice sees the event
        String aliceCalendarURI = "/calendars/" + alice.id() + "/" + subscribedCalendarId + ".json";
        List<JsonNode> aliceEvents = calDavClient.reportCalendarEvents(
                alice,
                aliceCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(aliceEvents).hasSize(1);
        String eventHref = aliceEvents.getFirst().path("_links").path("self").path("href").asText();

        // WHEN: Alice updates the event by removing Cedric from attendees
        String updatedEvent = originalEvent.replace("ATTENDEE;CN=Cedric;PARTSTAT=NEEDS-ACTION:mailto:" + cedric.email() + "\n",
            "" /* remove Cedric */);
        calDavClient.upsertCalendarEvent(alice, URI.create(eventHref), updatedEvent);

        // THEN: Bob should see the updated event without Cedric
        String bobCalendarURI = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        List<JsonNode> bobEvents = calDavClient.reportCalendarEvents(
                bob,
                bobCalendarURI,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(bobEvents)
            .anySatisfy(eventNode -> {
                String json = eventNode.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).doesNotContain(cedric.email());
            });

        // AND: Cedric should receive an iTIP CANCEL request in his inbox
        String cedricInboxUri = "/calendars/" + cedric.id() + "/inbox/";
        List<JsonNode> cedricInboxItems = calDavClient.reportCalendarEvents(
                cedric,
                cedricInboxUri,
                Instant.parse("2025-09-01T00:00:00Z"),
                Instant.parse("2025-11-01T00:00:00Z"))
            .collectList().block();

        assertThat(cedricInboxItems)
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + cedric.email());

                // check it is a CANCEL method
                assertThatJson(item.path("data").get(1))
                    .isArray()
                    .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                        .isEqualTo("""
                            ["method", {}, "text", "CANCEL"]"""));
            });
    }
}
