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
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import reactor.netty.http.client.HttpClient;

public class CalendarSharingTest {

    @RegisterExtension
    static DockerOpenPaasExtension extension = new DockerOpenPaasExtension();

    private CalDavClient calDavClient;

    private OpenPaasUser bob;
    private OpenPaasUser alice;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(HttpClient.create()
            .baseUrl("http://localhost:8001"));

        extension.getDockerOpenPaasSetupSingleton()
            .getOpenPaaSProvisioningService()
            .enableSharedCalendarModule()
            .block();

        bob = extension.newTestUser();
        alice = extension.newTestUser();
    }

    @Disabled("Sabre DAV wrongly allows subscribing to private calendars. " +
        "Expected: forbidden")
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

        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);

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
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob writable shared", false);
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
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);
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
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);
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
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);
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
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);
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
    void synchronizedCalendarsAreNotListedPublicly() {
        // GIVEN: Bob sets his calendar as read-only
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        // AND: Alice subscribes to Bob's calendar
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);
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
        SubscribedCalendarRequest subscribedCalendarRequest = subscribeRequest(bob.id(), "Bob readonly shared", true);
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

    private SubscribedCalendarRequest subscribeRequest(String userSourceId, String name, boolean readOnly) {
        return SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(userSourceId)
            .name(name)
            .color(readOnly ? "#00FF00" : "#0000FF")
            .readOnly(readOnly)
            .build();
    }
}
