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

import static com.linagora.dav.CalendarAssert.assertThatCalendar;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.CalendarUtil.CalendarExtractor;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Transp;

public abstract class SchedulingContract {
    private static final String ALARM_TRIGGER_15M = "-PT15M";
    private static final String ALARM_TRIGGER_5M = "-PT5M";
    private static final String TRANSP_OPAQUE = Transp.VALUE_OPAQUE;
    private static final String TRANSP_TRANSPARENT = Transp.VALUE_TRANSPARENT;

    private static final String[] IGNORED_CALENDAR_PROPERTIES = {
        Property.SEQUENCE,
        Property.CALSCALE,
        Property.PRODID,
        Property.DTSTAMP
    };

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    public abstract DockerTwakeCalendarExtension extension();

    private CalDavClient calDavClient;

    private OpenPaasUser bob;
    private OpenPaasUser alice;
    private OpenPaasUser cedric;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(extension().davHttpClient());
        bob = extension().newTestUser();
        alice = extension().newTestUser();
        cedric = extension().newTestUser();
    }

    @Test
    void eventCreationShouldPropagateEventToAttendeeCalendar() {
        // Given Bob creates an event with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        String expectedCedricCalendarEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // Then Cedric calendar contains an equivalent propagated event
        String actualCedricCalendarEventIcs = calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri);
        assertThatCalendar(actualCedricCalendarEventIcs)
            .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
            .isEqualTo(expectedCedricCalendarEventIcs);
    }

    @Test
    void eventUpdateByOrganizerShouldPropagateToAttendeeCalendar() {
        // Given Bob created an initial event with Cedric
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialOrganizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Initial Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialOrganizerEventIcs);

        // And Cedric already sees the initial summary
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("SUMMARY:Initial Meeting"));

        // When Bob updates the event
        String updatedOrganizerEventIcs = initialOrganizerEventIcs
            .replace("SUMMARY:Initial Meeting", "SUMMARY:Updated Meeting");

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedOrganizerEventIcs);

        // Then Cedric calendar is updated with the propagated changes
        String expectedCedricCalendarEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Updated Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricCalendarEventIcs));
    }

    @Test
    void eventDeletionByOrganizerShouldMarkAttendeeEventCancelled() {
        // Given Bob creates an event with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting to be deleted
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("SUMMARY:Meeting to be deleted"));

        // And Bob deletes that event
        calDavClient.deleteCalendarEvent(bob, organizerEventUid);

        // Then Cedric calendar marks the event as cancelled
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("STATUS:CANCELLED"));
    }

    @Test
    void attendeeAcceptanceShouldPropagateToOrganizerCalendar() {
        // Given Bob creates an event with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String cedricEmail = cedric.email();
        String initialOrganizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting for participation
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedricEmail);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialOrganizerEventIcs);

        Supplier<PartStat> cedricPartStatOnOrganizerCalendar = () -> {
            String bobCalendar = calDavClient.getCalendarEvent(bob,
                URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics"));
            return CalendarUtil.getAttendeePartStat(bobCalendar, cedricEmail);
        };

        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnOrganizerCalendar.get())
            .isEqualTo(PartStat.NEEDS_ACTION));

        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");

        // And Cedric accepts the event
        String cedricCalendarEventIcs = calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri);
        String cedricAcceptedCalendarEventIcs = CalendarUtil.withAttendeePartStat(cedricCalendarEventIcs, cedricEmail, PartStat.ACCEPTED);
        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricAcceptedCalendarEventIcs);

        // Then Bob calendar reflects Cedric acceptance
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnOrganizerCalendar.get())
            .isEqualTo(PartStat.ACCEPTED));
    }

    @Test
    void attendeeAcceptanceShouldPropagateToOtherAttendeesCalendar() {
        // Given Bob creates an event with Cedric and Alice as attendees
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric and Alice
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        Supplier<PartStat> cedricPartStatOnAliceCalendar = () -> {
            String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice,
                URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics"));
            return CalendarUtil.getAttendeePartStat(aliceCalendarEventIcs, cedric.email());
        };
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnAliceCalendar.get())
            .isEqualTo(PartStat.NEEDS_ACTION));

        // And Cedric accepts the event
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        String cedricCalendarEventIcs = calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri);
        String cedricAcceptedCalendarEventIcs = CalendarUtil.withAttendeePartStat(cedricCalendarEventIcs, cedric.email(), PartStat.ACCEPTED);
        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricAcceptedCalendarEventIcs);

        // Then Alice calendar reflects Cedric acceptance
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnAliceCalendar.get())
            .isEqualTo(PartStat.ACCEPTED));
    }

    @Test
    void attendeeUpdatingVALARMShouldOnlyAffectAttendeeCalendar() {
        // Given Bob creates an event with Alice and Cedric as attendees and a VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Alarm sync isolation check
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee local alarm update isolation
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            TRIGGER:{alarmTrigger15m}
            ACTION:EMAIL
            ATTENDEE:mailto:{bobEmail}
            SUMMARY:test alarm
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{alarmTrigger15m}", ALARM_TRIGGER_15M);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        awaitAtMost.untilAsserted(() -> {
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
        });

        // When Alice updates VALARM trigger in her own event copy via HTTP PUT
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("TRIGGER:" + ALARM_TRIGGER_15M, "TRIGGER:" + ALARM_TRIGGER_5M);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        // Then Alice calendar reflects updated alarm
        awaitAtMost.untilAsserted(() -> assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .isEqualTo(ALARM_TRIGGER_5M));

        // And Bob and Cedric calendars keep original alarm trigger
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                    .isEqualTo(ALARM_TRIGGER_15M);
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                    .isEqualTo(ALARM_TRIGGER_15M);
            });
    }

    @Disabled("esn-sabre issue https://github.com/linagora/esn-sabre/issues/319")
    @Test
    void organizerUpdateShouldNotResetAttendeeLocalVALARM() {
        // Given Bob creates an event with Alice and Cedric as attendees and a VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialSummary = "Alarm reset check";
        String updatedSummary = "Alarm reset check - updated by Bob";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:{summary}
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee alarm persistence after organizer update
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            TRIGGER:{alarmTrigger15m}
            ACTION:EMAIL
            ATTENDEE:mailto:{bobEmail}
            SUMMARY:test alarm
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{summary}", initialSummary)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{alarmTrigger15m}", ALARM_TRIGGER_15M);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        awaitAtMost.untilAsserted(() -> {
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(readEventSummary(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEqualTo(initialSummary);
        });

        // When Alice updates VALARM trigger in her own event copy
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("TRIGGER:" + ALARM_TRIGGER_15M, "TRIGGER:" + ALARM_TRIGGER_5M);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        awaitAtMost.untilAsserted(() -> assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .isEqualTo(ALARM_TRIGGER_5M));

        // And Bob updates event summary
        String bobCalendarEventIcs = calDavClient.getCalendarEvent(bob, bobCalendarEventUri);
        String bobUpdatedCalendarEventIcs = bobCalendarEventIcs
            .replace("SUMMARY:" + initialSummary, "SUMMARY:" + updatedSummary);
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, bobUpdatedCalendarEventIcs);

        // Then summary is synchronized, but Alice local alarm is not reset by Bob update
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEventSummary(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEqualTo(updatedSummary);
            assertThat(readEventSummary(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .isEqualTo(updatedSummary);
        });

        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                    .isEqualTo(ALARM_TRIGGER_5M);
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                    .isEqualTo(ALARM_TRIGGER_15M);
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                    .isEqualTo(ALARM_TRIGGER_15M);
            });
    }

    @Disabled("esn-sabre issue https://github.com/linagora/esn-sabre/issues/319")
    @Test
    void attendeePartStatUpdateShouldNotResetOtherAttendeeLocalVALARM() {
        // Given Bob creates an event with Alice and Cedric as attendees and a VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:PartStat and alarm isolation
            LOCATION:Meeting Room A
            DESCRIPTION:Check alarm is preserved when attendee updates partstat
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            TRIGGER:{alarmTrigger15m}
            ACTION:EMAIL
            ATTENDEE:mailto:{bobEmail}
            SUMMARY:test alarm
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{alarmTrigger15m}", ALARM_TRIGGER_15M);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");

        awaitAtMost.untilAsserted(() -> {
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(CalendarUtil.getAttendeePartStat(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri), cedric.email()))
                .isEqualTo(PartStat.NEEDS_ACTION);
        });

        // And Alice customizes her local VALARM
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("TRIGGER:" + ALARM_TRIGGER_15M, "TRIGGER:" + ALARM_TRIGGER_5M);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        awaitAtMost.untilAsserted(() -> assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .isEqualTo(ALARM_TRIGGER_5M));

        // When Cedric accepts the event (updates his own PARTSTAT)
        String cedricCalendarEventIcs = calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri);
        String cedricAcceptedCalendarEventIcs = CalendarUtil.withAttendeePartStat(cedricCalendarEventIcs, cedric.email(), PartStat.ACCEPTED);
        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricAcceptedCalendarEventIcs);

        // Then Cedric PARTSTAT is synchronized to Alice
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.getAttendeePartStat(
            calDavClient.getCalendarEvent(alice, aliceCalendarEventUri), cedric.email()))
            .isEqualTo(PartStat.ACCEPTED));

        // And Alice local VALARM is not reset by that attendee update
        assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .isEqualTo(ALARM_TRIGGER_5M);
    }

    @Test
    void attendeeUpdatingTRANSPShouldOnlyAffectAttendeeCalendar() {
        // Given Bob creates an event with Alice and Cedric as attendees and TRANSP set to OPAQUE
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:TRANSP sync isolation check
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee local TRANSP update isolation
            TRANSP:{transpOpaque}
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{transpOpaque}", TRANSP_OPAQUE);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        awaitAtMost.untilAsserted(() -> {
            assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
                .extractPropertyValue(Property.TRANSP))
                .isEqualTo(TRANSP_OPAQUE);
            assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(bob, bobCalendarEventUri))
                .extractPropertyValue(Property.TRANSP))
                .isEqualTo(TRANSP_OPAQUE);
            assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .extractPropertyValue(Property.TRANSP))
                .isEqualTo(TRANSP_OPAQUE);
        });

        // When Alice updates TRANSP in her own event copy via HTTP PUT
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("TRANSP:" + TRANSP_OPAQUE, "TRANSP:" + TRANSP_TRANSPARENT);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        // Then Alice calendar reflects updated TRANSP
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
            .extractPropertyValue(Property.TRANSP))
            .isEqualTo(TRANSP_TRANSPARENT));

        // And Bob and Cedric calendars keep original TRANSP value
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(bob, bobCalendarEventUri))
                    .extractPropertyValue(Property.TRANSP))
                    .isEqualTo(TRANSP_OPAQUE);
                assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                    .extractPropertyValue(Property.TRANSP))
                    .isEqualTo(TRANSP_OPAQUE);
            });
    }

    @Disabled("esn-sabre issue https://github.com/linagora/esn-sabre/issues/320")
    @Test
    void organizerUpdateShouldNotResetAttendeeLocalTRANSP() {
        // Given Bob creates an event with Alice and TRANSP set to OPAQUE
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialSummary = "TRANSP reset check";
        String updatedSummary = "TRANSP reset check - updated by Bob";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:{summary}
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee TRANSP persistence after organizer update
            TRANSP:{transpOpaque}
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{summary}", initialSummary)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{transpOpaque}", TRANSP_OPAQUE);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");
        Supplier<CalendarExtractor> aliceCalendarEvent = () -> CalendarUtil.toExtractor(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri));

        awaitAtMost.untilAsserted(() -> {
            CalendarExtractor aliceCalendarExtractor = aliceCalendarEvent.get();
            assertThat(aliceCalendarExtractor.extractPropertyValue(Property.TRANSP))
                .isEqualTo(TRANSP_OPAQUE);
            assertThat(aliceCalendarExtractor.extractPropertyValue(Property.SUMMARY))
                .isEqualTo(initialSummary);
        });

        // When Alice updates TRANSP in her own event copy
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("TRANSP:" + TRANSP_OPAQUE, "TRANSP:" + TRANSP_TRANSPARENT);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        awaitAtMost.untilAsserted(() -> assertThat(aliceCalendarEvent.get().extractPropertyValue(Property.TRANSP))
            .isEqualTo(TRANSP_TRANSPARENT));

        // And Bob updates event summary
        String bobCalendarEventIcs = calDavClient.getCalendarEvent(bob, bobCalendarEventUri);
        String bobUpdatedCalendarEventIcs = bobCalendarEventIcs
            .replace("SUMMARY:" + initialSummary, "SUMMARY:" + updatedSummary);
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, bobUpdatedCalendarEventIcs);

        // Then summary is synchronized, but Alice local TRANSP is not reset by Bob update
        awaitAtMost.untilAsserted(() -> assertThat(aliceCalendarEvent.get().extractPropertyValue(Property.SUMMARY))
            .isEqualTo(updatedSummary));

        assertThat(aliceCalendarEvent.get().extractPropertyValue(Property.TRANSP))
            .isEqualTo(TRANSP_TRANSPARENT);
    }

    @Disabled("esn-sabre issue https://github.com/linagora/esn-sabre/issues/320")
    @Test
    void attendeePartStatUpdateShouldNotResetOtherAttendeeLocalTRANSP() {
        // Given Bob creates an event with Alice and Cedric as attendees and TRANSP set to OPAQUE
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:PartStat and TRANSP isolation
            LOCATION:Meeting Room A
            DESCRIPTION:Check TRANSP is preserved when attendee updates partstat
            TRANSP:{transpOpaque}
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{transpOpaque}", TRANSP_OPAQUE);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        Supplier<CalendarExtractor> aliceCalendarEvent = () -> CalendarUtil.toExtractor(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri));

        awaitAtMost.untilAsserted(() -> {
            CalendarExtractor aliceCalendarExtractor = aliceCalendarEvent.get();
            assertThat(aliceCalendarExtractor.extractPropertyValue(Property.TRANSP))
                .isEqualTo(TRANSP_OPAQUE);
            assertThat(aliceCalendarExtractor.extractAttendeePartStat(cedric.email()))
                .isEqualTo(PartStat.NEEDS_ACTION);
        });

        // And Alice customizes her local TRANSP
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("TRANSP:" + TRANSP_OPAQUE, "TRANSP:" + TRANSP_TRANSPARENT);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        awaitAtMost.untilAsserted(() -> assertThat(aliceCalendarEvent.get()
            .extractPropertyValue(Property.TRANSP))
            .isEqualTo(TRANSP_TRANSPARENT));

        // When Cedric accepts the event (updates his own PARTSTAT)
        String cedricCalendarEventIcs = calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri);
        String cedricAcceptedCalendarEventIcs = CalendarUtil.withAttendeePartStat(cedricCalendarEventIcs, cedric.email(), PartStat.ACCEPTED);
        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricAcceptedCalendarEventIcs);

        // Then Cedric PARTSTAT is synchronized to Alice
        awaitAtMost.untilAsserted(() -> assertThat(aliceCalendarEvent.get().extractAttendeePartStat(cedric.email()))
            .isEqualTo(PartStat.ACCEPTED));

        // And Alice local TRANSP is not reset by that attendee update
        assertThat(aliceCalendarEvent.get().extractPropertyValue(Property.TRANSP))
            .isEqualTo(TRANSP_TRANSPARENT);
    }

    @Test
    void attendeeDeletionShouldRejectAttendanceInOrganizerCalendar() {
        // Given Bob creates an event with Cedric and Alice as attendees
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric and Alice
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");
        Supplier<PartStat> cedricPartStatOnOrganizerCalendar = () -> {
            String bobCalendarEventIcs = calDavClient.getCalendarEvent(bob, bobCalendarEventUri);
            return CalendarUtil.getAttendeePartStat(bobCalendarEventIcs, cedric.email());
        };
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnOrganizerCalendar.get())
            .isEqualTo(PartStat.NEEDS_ACTION));

        // And Cedric deletes the event from his calendar
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        calDavClient.deleteCalendarEvent(cedric, cedricCalendarEventUri);

        // Then Bob calendar reflects Cedric declined attendance
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnOrganizerCalendar.get())
            .isEqualTo(PartStat.DECLINED));
    }

    @Test
    void attendeeDeletionShouldRejectAttendanceInOtherAttendeeCalendar() {
        // Given Bob creates an event with Cedric and Alice as attendees
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric and Alice
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        Supplier<PartStat> cedricPartStatOnOtherAttendeeCalendar = () -> {
            String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice,
                URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics"));
            return CalendarUtil.getAttendeePartStat(aliceCalendarEventIcs, cedric.email());
        };
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnOtherAttendeeCalendar.get())
            .isEqualTo(PartStat.NEEDS_ACTION));

        // And Cedric deletes the event from his calendar
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        calDavClient.deleteCalendarEvent(cedric, cedricCalendarEventUri);

        // Then Alice calendar reflects Cedric declined attendance
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatOnOtherAttendeeCalendar.get())
            .isEqualTo(PartStat.DECLINED));
    }

    @Test
    void removingAttendeeFromEventShouldMarkAttendeeEventCancelled() {
        // Given Bob creates an event with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventWithCedricIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting for removal test
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventWithCedricIcs);

        // And Cedric already sees this event
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("SUMMARY:Meeting for removal test"));

        // When Bob removes Cedric from attendee list 
        String organizerEventWithoutCedricIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting for removal test
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventWithoutCedricIcs);

        // Then Cedric calendar marks the event as cancelled
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("STATUS:CANCELLED"));
    }

    @Test
    void addingAttendeeToExistingEventShouldPropagateToNewAttendee() {
        // Given Bob creates an event with Cedric only
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);
        awaitFirstEventId(cedric);

        // When Bob updates the event to also invite Alice
        String updatedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric and Alice
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedIcs);

        // Then Alice receives the event in her calendar
        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");

        String expectedAliceIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            SUMMARY:Meeting with Cedric and Alice
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedAliceIcs));
    }

    @Test
    void recurringInviteSingleOccurrenceShouldPropagateToAttendee() {
        // Given Bob creates a recurring series and invites Alice only on occurrence 2
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email());

        // When Bob upserts the recurring event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);

        // Then Alice calendar contains only the invited occurrence
        String expectedAliceEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email());

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedAliceEventIcs));
    }

    @Test
    void recurringAcceptSingleOccurrenceShouldPropagateToOrganizer() {
        // Given Bob creates a recurring series and invites Cedric only on occurrence 2
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        // And Cedric only sees the invited single occurrence
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        String expectedCedricIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricIcs));

        // When Cedric accepts only that invited occurrence
        String cedricAcceptedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricAcceptedEventIcs);

        // Then Bob calendar reflects Cedric acceptance on the invited occurrence
        String expectedOrganizerIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(bob, bobCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .ignoringParticipantScheduleStatus()
                .isEqualTo(expectedOrganizerIcs));
    }

    @Test
    void recurringDeleteInvitedSingleOccurrenceShouldCancelOnAttendee() {
        // Given Bob creates a recurring series and invites Cedric only on occurrence 2
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialInviteIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Single Instance to Delete
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance to Delete
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialInviteIcs);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        String expectedCedricIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance to Delete
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricIcs));

        // And Bob deletes only that invited occurrence by removing its override from payload
        String updatedOrganizerSeriesAfterOccRemovalIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T100000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Single Instance to Delete
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email());
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedOrganizerSeriesAfterOccRemovalIcs);

        // Then Cedric calendar marks that invited occurrence as cancelled
        String expectedCedricCancelledInviteOccIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Single Instance to Delete
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            STATUS:CANCELLED
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .ignoringParticipantScheduleStatus()
                .isEqualTo(expectedCedricCancelledInviteOccIcs));
    }

    @Test
    void recurringInviteWholeSeriesShouldPropagate() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        // Then Cedric calendar contains the same recurring master event
        String expectedCedricEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");

        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricEventIcs));
    }

    @Test
    void recurringDeleteWholeSeriesShouldCancelOnAttendee() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting to Delete
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("SUMMARY:Recurring Meeting to Delete"));

        // And Bob deletes the whole recurring series
        calDavClient.deleteCalendarEvent(bob, organizerEventUid);

        // Then Cedric calendar marks the recurring event as cancelled
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
            .contains("STATUS:CANCELLED"));
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/300 " +
        "Expected EXDATE in attendee calendar for occurrence-2 removal, but server response omits EXDATE")
    void recurringRemoveAttendeeFromOccurrence2ShouldPropagate() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);

        // And Bob removes Cedric from occurrence 2 (2035-10-06)
        String updatedWithNoCedricOcc2Ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedWithNoCedricOcc2Ics);

        // Then Cedric calendar is expected to keep the master event with EXDATE for occurrence 2
        String expectedCedricEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE:20351006T090000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricEventIcs));
    }

    @Test
    void recurringDeleteOccurrence2ShouldPropagate() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);

        // And Bob deletes occurrence 2 by adding EXDATE on the master event
        String updateWithOcc2DeletedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE:20351006T090000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updateWithOcc2DeletedIcs);

        // Then Cedric calendar contains the same EXDATE on the recurring master
        String expectedCedricEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE:20351006T090000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricEventIcs));
    }

    @Test
    void recurringUpdateWholeSeriesShouldPropagate() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Initial Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the initial recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);

        // And Bob updates the whole recurring series
        String updatedEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T100000Z
            DTEND:20351005T110000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Updated Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedEventIcs);

        // Then Cedric calendar reflects the updated recurring master event
        String expectedCedricEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T100000Z
            DTEND:20351005T110000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Updated Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricEventIcs));
    }

    @Test
    void recurringUpdateSingleOccurrenceShouldPropagate() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);

        // And Bob updates occurrence 2 (2035-10-06) using an override VEVENT
        String updateIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T140000Z
            DTEND:20351006T150000Z
            SUMMARY:Rescheduled Occurrence
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updateIcs);

        // Then Cedric calendar reflects the updated occurrence override
        String expectedCedricEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T140000Z
            DTEND:20351006T150000Z
            SUMMARY:Rescheduled Occurrence
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricEventIcs));
    }

    @Test
    void recurringDeleteSingleOccurrenceInSeriesShouldPropagate() {
        // Given Bob creates a recurring series with Cedric as attendee
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        // When Bob upserts the recurring series
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialEventIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);

        // And Bob deletes occurrence 2 by adding EXDATE on the master event
        String deletedOcc2Ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE:20351006T090000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, deletedOcc2Ics);

        // Then Cedric calendar reflects EXDATE on the recurring master
        String expectedCedricRecurringSeriesEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=5
            EXDATE:20351006T090000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedCedricRecurringSeriesEventIcs));
    }

    @Test
    void recurringAcceptSingleOccurrenceWithinSeriesShouldPropagateToOrganizer() {
        // Given Bob creates a recurring series with Cedric invited to all occurrences
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");

        // When Cedric accepts only occurrence 2 (adds an override VEVENT with PARTSTAT=ACCEPTED)
        String cedricPartialAcceptIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricPartialAcceptIcs);

        // Then Bob calendar reflects Cedric's acceptance on occurrence 2 only
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");
        awaitAtMost.untilAsserted(() -> {
            Calendar actualBobCalendar = CalendarUtil.parseIcsAndSanitize(
                calDavClient.getCalendarEvent(bob, bobCalendarEventUri),
                IGNORED_CALENDAR_PROPERTIES);
            CalendarUtil.removeParticipantScheduleStatus(actualBobCalendar);
            Map<String, PartStat> cedricPartStats = CalendarUtil.getRecurringAttendeePartStats(actualBobCalendar, cedric.email());
            assertThat(cedricPartStats.get(CalendarUtil.MASTER_RECURRENCE_KEY)).isEqualTo(PartStat.NEEDS_ACTION);
            assertThat(cedricPartStats.get("20351006T090000Z")).isEqualTo(PartStat.ACCEPTED);
        });
    }

    @Test
    void recurringDeclineSingleOccurrenceViaExdateShouldPropagateToOrganizer() {
        // Given Bob creates a recurring series with Cedric invited to all occurrences
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");

        // When Cedric declines occurrence 2 by adding an EXDATE on his copy
        String cedricExdateIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE:20351006T090000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(cedric, cedricCalendarEventUri, cedricExdateIcs);

        // Then Bob calendar reflects Cedric's decline on occurrence 2
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");
        awaitAtMost.untilAsserted(() -> {
            Calendar bobCalendar = CalendarUtil.parseIcsAndSanitize(
                calDavClient.getCalendarEvent(bob, bobCalendarEventUri),
                IGNORED_CALENDAR_PROPERTIES);
            Map<String, PartStat> cedricPartStats = CalendarUtil.getRecurringAttendeePartStats(bobCalendar, cedric.email());
            assertThat(cedricPartStats.get(CalendarUtil.MASTER_RECURRENCE_KEY)).isEqualTo(PartStat.NEEDS_ACTION);
            assertThat(cedricPartStats.get("20351006T090000Z")).isEqualTo(PartStat.DECLINED);
        });
    }

    @Test
    void recurringDeclineWholeSeriesByAttendeeShouldPropagateToOrganizer() {
        // Given Bob creates a recurring series with Cedric invited to all occurrences
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);

        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        Supplier<Map<String, PartStat>> cedricPartStatsOnOrganizerCalendar = () -> {
            Calendar bobCalendar = CalendarUtil.parseIcsAndSanitize(
                calDavClient.getCalendarEvent(bob, bobCalendarEventUri),
                IGNORED_CALENDAR_PROPERTIES);
            return CalendarUtil.getRecurringAttendeePartStats(bobCalendar, cedric.email());
        };

        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatsOnOrganizerCalendar.get()
            .get(CalendarUtil.MASTER_RECURRENCE_KEY))
            .isEqualTo(PartStat.NEEDS_ACTION));

        // When Cedric deletes the whole series from his calendar
        calDavClient.deleteCalendarEvent(cedric, cedricCalendarEventUri);

        // Then Bob calendar reflects Cedric's decline on the whole series
        awaitAtMost.untilAsserted(() -> assertThat(cedricPartStatsOnOrganizerCalendar.get())
            .containsOnly(entry(CalendarUtil.MASTER_RECURRENCE_KEY, PartStat.DECLINED)));
    }

    @Test
    void recurringAddAttendeeOnSingleOccurrenceShouldPropagateToOtherAttendeeCalendar() {
        // Given Bob creates a recurring series with Alice invited on the whole series
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");

        // When Bob updates only occurrence 2 to additionally invite Cedric
        String updatedWithCedricOnOccurrence2Ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTAMP:20351003T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedWithCedricOnOccurrence2Ics);

        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(bob, bobCalendarEventUri))
            .contains("RECURRENCE-ID:20351006T090000Z")
            .contains("mailto:" + cedric.email()));

        // Then the occurrence-level Cedric invite is propagated into Alice calendar
        String expectedAliceIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:20351006T090000Z
            DTSTART:20351006T090000Z
            DTEND:20351006T100000Z
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedAliceIcs));
    }

    @Test
    void recurringAddAttendeeToWholeSeriesAfterCreationShouldPropagate() {
        // Given Bob creates a recurring series with Cedric only (no Alice)
        String organizerEventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T080000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, initialIcs);
        awaitFirstEventId(cedric);

        // When Bob updates the series to also invite Alice
        String updatedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTAMP:20351003T090000Z
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());

        calDavClient.upsertCalendarEvent(bob, organizerEventUid, updatedIcs);

        // Then Alice receives the whole recurring series
        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");

        String expectedAliceIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:{organizerEventUid}
            DTSTART:20351005T090000Z
            DTEND:20351005T100000Z
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring Meeting
            ORGANIZER:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:{aliceEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{aliceEmail}", alice.email());
        awaitAtMost.untilAsserted(() ->
            assertThatCalendar(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
                .ignoringProperties(IGNORED_CALENDAR_PROPERTIES)
                .isEqualTo(expectedAliceIcs));
    }

    private String awaitFirstEventId(OpenPaasUser user) {
        return awaitAtMost.until(() -> calDavClient.findFirstEventId(user),
            Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected event id to be present"));
    }

    private String readFirstAlarmTrigger(String icsContent) {
        return icsContent.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("TRIGGER:"))
            .map(line -> line.substring("TRIGGER:".length()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected VALARM trigger to be present"));
    }

    private String readEventSummary(String icsContent) {
        return CalendarUtil.toExtractor(icsContent)
            .extractPropertyValue(Property.SUMMARY);
    }
}
