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

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.CalendarUtil.CalendarExtractor;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.PartStat;

public abstract class AlarmSchedulingContract {
    private static final String ALARM_TRIGGER_15M = "-PT15M";
    private static final String ALARM_TRIGGER_5M = "-PT5M";

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
    void attendeeUpdatingVALARMShouldNotPropagateToOrganizerAndOtherAttendees() {
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


    @Test
    void attendeeAddingVALARMShouldNotPropagateToOrganizerAndOtherAttendees() {
        // Given Bob creates an event with Alice and Cedric as attendees and no VALARM
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
            SUMMARY:Alarm add isolation check
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee local alarm addition isolation
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
            .replace("{cedricEmail}", cedric.email());
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI cedricCalendarEventUri = URI.create("/calendars/" + cedric.id() + "/" + cedric.id() + "/" + cedricCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        awaitAtMost.untilAsserted(() -> {
            assertThat(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
                .doesNotContain("BEGIN:VALARM");
            assertThat(calDavClient.getCalendarEvent(bob, bobCalendarEventUri))
                .doesNotContain("BEGIN:VALARM");
            assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                .doesNotContain("BEGIN:VALARM");
        });

        // When Alice adds a VALARM in her own event copy via HTTP PUT
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = aliceCalendarEventIcs
            .replace("END:VEVENT", """
                BEGIN:VALARM
                TRIGGER:{alarmTrigger5m}
                ACTION:DISPLAY
                DESCRIPTION:Alice local reminder
                END:VALARM
                END:VEVENT
                """.replace("{alarmTrigger5m}", ALARM_TRIGGER_5M));
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        // Then Alice calendar reflects the added alarm
        awaitAtMost.untilAsserted(() -> assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .isEqualTo(ALARM_TRIGGER_5M));

        // And Bob and Cedric calendars keep no VALARM
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(calDavClient.getCalendarEvent(bob, bobCalendarEventUri))
                    .doesNotContain("BEGIN:VALARM");
                assertThat(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri))
                    .doesNotContain("BEGIN:VALARM");
            });
    }


    @Test
    void attendeeRemovingVALARMShouldNotPropagateToOrganizerAndOtherAttendees() {
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
            SUMMARY:Alarm remove isolation check
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee local alarm removal isolation
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            TRIGGER:{alarmTrigger15m}
            ACTION:DISPLAY
            DESCRIPTION:Organizer reminder
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

        // When Alice removes the VALARM from her own event copy via HTTP PUT
        String aliceCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
        String aliceUpdatedCalendarEventIcs = removeFirstAlarm(aliceCalendarEventIcs);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        // Then Alice calendar reflects the removed alarm
        awaitAtMost.untilAsserted(() -> assertThat(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri))
            .doesNotContain("BEGIN:VALARM"));

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
    void organizerUpdateShouldNotResetAttendeeLocalVALARMOnRecurringMasterAndOverride() {
        // Given Bob creates a recurring event with one override and different organizer alarms
        String organizerEventUid = "event-" + UUID.randomUUID();
        String overrideRecurrenceId = "30250102T090000Z";
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
            RRULE:FREQ=DAILY;COUNT=3
            SUMMARY:Recurring alarm master
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:DISPLAY
            DESCRIPTION:Master organizer alarm
            END:VALARM
            END:VEVENT
            BEGIN:VEVENT
            UID:{organizerEventUid}
            RECURRENCE-ID:{overrideRecurrenceId}
            SEQUENCE:1
            DTSTART:30250102T130000Z
            DTEND:30250102T140000Z
            SUMMARY:Recurring alarm override
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            TRIGGER:-PT30M
            ACTION:DISPLAY
            DESCRIPTION:Override organizer alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{overrideRecurrenceId}", overrideRecurrenceId)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email());
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + aliceCalendarEventId + ".ics");
        URI bobCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + organizerEventUid + ".ics");

        // And Alice customizes both the master alarm and the override alarm differently
        String aliceUpdatedCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)
            .replace("TRIGGER:-PT15M", "TRIGGER:-PT5M")
            .replace("TRIGGER:-PT30M", "TRIGGER:-PT10M");
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        awaitAtMost.untilAsserted(() -> {
            String aliceEvent = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
            CalendarExtractor aliceEventExtractor = CalendarUtil.toExtractor(aliceEvent);
            assertThat(aliceEventExtractor.extractEventPropertyValue(Optional.empty(), Property.TRIGGER))
                .isEqualTo("-PT5M");
            assertThat(aliceEventExtractor.extractEventPropertyValue(Optional.of(overrideRecurrenceId), Property.TRIGGER))
                .isEqualTo("-PT10M");
        });

        // When Bob updates both the master and override summaries
        String bobCalendarEventIcs = calDavClient.getCalendarEvent(bob, bobCalendarEventUri);
        String bobUpdatedCalendarEventIcs = bobCalendarEventIcs
            .replace("SUMMARY:Recurring alarm master", "SUMMARY:Recurring alarm master updated")
            .replace("SUMMARY:Recurring alarm override", "SUMMARY:Recurring alarm override updated");
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, bobUpdatedCalendarEventIcs);

        // Then summaries are synchronized, but each local alarm remains attached to its VEVENT
        awaitAtMost.untilAsserted(() -> {
            String aliceEvent = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
            CalendarExtractor aliceEventExtractor = CalendarUtil.toExtractor(aliceEvent);
            assertThat(aliceEventExtractor.extractEventPropertyValue(Optional.empty(), Property.SUMMARY))
                .isEqualTo("Recurring alarm master updated");
            assertThat(aliceEventExtractor.extractEventPropertyValue(Optional.of(overrideRecurrenceId), Property.SUMMARY))
                .isEqualTo("Recurring alarm override updated");
            assertThat(aliceEventExtractor.extractEventPropertyValue(Optional.empty(), Property.TRIGGER))
                .isEqualTo("-PT5M");
            assertThat(aliceEventExtractor.extractEventPropertyValue(Optional.of(overrideRecurrenceId), Property.TRIGGER))
                .isEqualTo("-PT10M");
        });
    }


    private String readFirstAlarmTrigger(String icsContent) {
        return icsContent.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("TRIGGER:"))
            .map(line -> line.substring("TRIGGER:".length()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected VALARM trigger to be present"));
    }


    private String removeFirstAlarm(String icsContent) {
        return icsContent.replaceFirst("(?s)BEGIN:VALARM\\R.*?\\REND:VALARM\\R?", "");
    }


    private String awaitFirstEventId(OpenPaasUser user) {
        return awaitAtMost.until(() -> calDavClient.findFirstEventId(user),
                Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected event id to be present"));
    }


    private String readEventSummary(String icsContent) {
        return CalendarUtil.toExtractor(icsContent)
            .extractPropertyValue(Property.SUMMARY);
    }
}
