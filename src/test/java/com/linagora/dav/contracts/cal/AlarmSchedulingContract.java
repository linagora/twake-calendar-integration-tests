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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.CalendarUtil.CalendarExtractor;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.PartStat;

public abstract class AlarmSchedulingContract {
    private static final String ALARM_TRIGGER_15M = "-PT15M";
    private static final String ALARM_TRIGGER_10M = "-PT10M";
    private static final String ALARM_TRIGGER_10M_EXPLICIT = "-P0DT0H10M0S";
    private static final String ALARM_TRIGGER_5M = "-PT5M";
    private static final String ALARM_TRIGGER_5M_EXPLICIT = "-P0DT0H05M0S";
    private static final String ALARM_TRIGGER_20M = "-PT20M";
    private static final String ALARM_TRIGGER_20M_EXPLICIT = "-P0DT0H20M0S";

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
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
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
            ACTION:DISPLAY
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

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
    void emailVALARMShouldKeepOnlyCurrentRecipientInAttendeeCopies() {
        // Given Bob creates an event with Alice and Cedric and targets all three users in one email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Listed email alarm target
            LOCATION:Meeting Room A
            DESCRIPTION:Check email alarm is synchronized to listed attendees only
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{cedricEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // Then copied events keep only their owner while Bob's original event keeps every recipient
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(cedric.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email(), cedric.email(), bob.email())));
        });
    }

    @Test
    void emailVALARMWithoutUIDShouldReceiveGeneratedUIDAndPropagateIt() {
        // Given Bob creates an event with Alice and an email VALARM without UID
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Generated alarm UID
            LOCATION:Meeting Room A
            DESCRIPTION:Check server generates missing VALARM UID
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // Then the generated UID is stored on Bob's source and propagated to Alice's copy
        awaitAtMost.untilAsserted(() -> {
            List<String> bobEmailAlarmUids = readEmailAlarmUids(calDavClient.getCalendarEvent(bob, bobCalendarEventUri));
            List<String> aliceEmailAlarmUids = readEmailAlarmUids(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri));

            assertThat(bobEmailAlarmUids)
                .singleElement()
                .satisfies(uid -> assertThat(uid).matches("alarm-[0-9a-fA-F-]{36}"));
            assertThat(aliceEmailAlarmUids)
                .containsExactlyElementsOf(bobEmailAlarmUids);
        });
    }

    @Test
    void emailVALARMShouldNotLeakOrganizerAliasToAttendeeCopy() {
        // Given Bob creates an event with Alice and targets Alice plus one organizer alias in an email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerAlias = "bob-alias@example.org";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer alias alarm target
            LOCATION:Meeting Room A
            DESCRIPTION:Check organizer alias is not copied to attendee calendar
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{organizerAlias}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{organizerAlias}", organizerAlias)
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // Then Alice copy keeps only Alice, while Bob source keeps Alice and the explicit alias
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email(), organizerAlias)));
        });
    }

    @Test
    void emailVALARMShouldPropagateToNewlyAddedAttendee() {
        // Given Bob creates an event with Alice and targets both of them in one email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Add attendee to email alarm
            LOCATION:Meeting Room A
            DESCRIPTION:Check a newly invited attendee receives the existing email alarm
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email(), bob.email())));
        });

        // When Bob updates the event to invite Cedric and includes him in the email VALARM
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:" + bob.email(),
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:" + cedric.email()
                    + "\nATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:" + bob.email())
            .replace("ATTENDEE:mailto:" + bob.email(),
                "ATTENDEE:mailto:" + cedric.email()
                    + "\nATTENDEE:mailto:" + bob.email());
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Cedric receives the alarm, Alice keeps hers, and Bob retains all recipients
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(cedric.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email(), cedric.email(), bob.email())));
        });
    }

    @Test
    void emailVALARMShouldNotPropagateToNewlyAddedUnlistedAttendee() {
        // Given Bob creates an event with Alice and targets Bob and Alice in one email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Add attendee without email alarm
            LOCATION:Meeting Room A
            DESCRIPTION:Check a newly invited unlisted attendee does not receive the email alarm
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email(), bob.email())));
        });

        // When Bob invites Cedric without adding him to the email VALARM attendees
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:" + bob.email(),
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:" + cedric.email()
                    + "\nATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:" + bob.email());
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Cedric receives no email VALARM while Bob and Alice keep theirs
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .isEmpty();
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email(), bob.email())));
        });
    }

    @Test
    void emailVALARMShouldNotPropagateToUnlistedAttendee() {
        // Given Bob creates an event with Alice but does not target Alice in the email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Unlisted email alarm target
            LOCATION:Meeting Room A
            DESCRIPTION:Check email alarm is not synchronized to unlisted attendees
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);

        // Then Alice does not receive the email VALARM because she is not listed in the VALARM attendees
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEmpty());
    }

    @Test
    void emailVALARMsShouldPropagateByRecipientSpecificTrigger() {
        // Given Bob creates an event with one email VALARM for attendees and another one for himself
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Attendee specific email alarms
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendees can receive different email VALARM triggers
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{cedricEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT)
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // Then each copied event keeps its recipient-specific alarm and Bob's original keeps both alarms
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(cedric.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email(), cedric.email())),
                    new EmailAlarm(ALARM_TRIGGER_10M, Set.of(bob.email())));
        });
    }

    @Test
    void emailVALARMsWithDifferentTriggersShouldAllPropagateToSameAttendee() {
        // Given Bob creates an event with two email VALARMs targeting Alice at different times
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Multiple email alarms for one attendee
            LOCATION:Meeting Room A
            DESCRIPTION:Check all email VALARMs targeting the same attendee are synchronized
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT)
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);

        // When Bob upserts the event
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);
        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // Then both calendars keep both independent alarms targeting Alice
        Set<String> aliceAlarmRecipient = Set.of(alice.email());
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactlyInAnyOrder(
                    new EmailAlarm(ALARM_TRIGGER_10M, aliceAlarmRecipient),
                    new EmailAlarm(ALARM_TRIGGER_5M, aliceAlarmRecipient));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactlyInAnyOrder(
                    new EmailAlarm(ALARM_TRIGGER_10M, aliceAlarmRecipient),
                    new EmailAlarm(ALARM_TRIGGER_5M, aliceAlarmRecipient));
        });
    }

    @Test
    void organizerUpdateShouldPropagateEmailVALARMTriggerToExistingAttendee() {
        // Given Bob creates an event with Alice and targets Alice in an organizer-managed email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer alarm update
            LOCATION:Meeting Room A
            DESCRIPTION:Check organizer-managed email alarm updates are synchronized
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            UID:organizer-reminder@example.org
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email()))));

        // When Bob updates the organizer-managed email VALARM trigger
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("TRIGGER:" + ALARM_TRIGGER_5M_EXPLICIT, "TRIGGER:" + ALARM_TRIGGER_20M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Alice receives the updated organizer-managed alarm projection
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_20M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_20M, Set.of(alice.email(), bob.email())));
        });
    }

    @Test
    void organizerEmailVALARMShouldBeKeptAfterEventUpdateAndAttendeeAcceptance() {
        // Given Bob invites Alice and defines an organizer-managed email VALARM for Bob and Alice
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer managed alarm regular update
            LOCATION:Meeting Room A
            DESCRIPTION:Initial description
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            UID:alarm-11111111-1111-4111-8111-111111111111
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email()))));

        // When Bob updates non-alarm event fields
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("DTSTART:30250101T090000Z", "DTSTART:30250101T093000Z")
            .replace("DTEND:30250101T100000Z", "DTEND:30250101T103000Z")
            .replace("DESCRIPTION:Initial description", "DESCRIPTION:Updated description");
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Alice receives the event update while keeping her projected email VALARM
        awaitAtMost.untilAsserted(() -> {
            String aliceEvent = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
            assertThat(aliceEvent)
                .contains("DTSTART:30250101T093000Z")
                .contains("DESCRIPTION:Updated description");
            assertThat(readEmailAlarms(aliceEvent))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email())));
        });

        // When Alice accepts the event
        String aliceAcceptedCalendarEventIcs = CalendarUtil.withAttendeePartStat(
            calDavClient.getCalendarEvent(alice, aliceCalendarEventUri), alice.email(), PartStat.ACCEPTED);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceAcceptedCalendarEventIcs);

        // Then Alice acceptance is propagated to Bob
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.getAttendeePartStat(
            calDavClient.getCalendarEvent(bob, bobCalendarEventUri), alice.email()))
            .isEqualTo(PartStat.ACCEPTED));

        // And the email VALARM remains projected for Alice and unchanged in Bob's source event
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                    .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email())));
                assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                    .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(alice.email(), bob.email())));
            });
    }

    @Test
    void organizerPersonalEmailVALARMShouldBePreservedAfterAttendeeAccept() {
        // Given Bob invites Alice and defines an email VALARM targeting only himself
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer personal alarm accept
            LOCATION:Meeting Room A
            DESCRIPTION:Check organizer personal alarm survives attendee accept
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            UID:alarm-22222222-2222-4222-8222-222222222222
            ACTION:EMAIL
            DESCRIPTION:Bob personal reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEmpty();
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(bob.email())));
        });

        // When Alice accepts the event
        String aliceAcceptedCalendarEventIcs = CalendarUtil.withAttendeePartStat(
            calDavClient.getCalendarEvent(alice, aliceCalendarEventUri), alice.email(), PartStat.ACCEPTED);
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceAcceptedCalendarEventIcs);

        // Then Alice acceptance is propagated to Bob
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.getAttendeePartStat(
            calDavClient.getCalendarEvent(bob, bobCalendarEventUri), alice.email()))
            .isEqualTo(PartStat.ACCEPTED));

        // And Bob's personal email VALARM remains on the organizer source event only
        calmlyAwait
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                    .isEmpty();
                assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                    .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(bob.email())));
            });
    }

    @Test
    void attendeePersonalEmailVALARMShouldBePreservedAfterRegularOrganizerUpdate() {
        // Given Bob creates an event with Alice and no email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String aliceAlias = "alice-alias@example.org";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Attendee personal alarm regular update
            LOCATION:Meeting Room A
            DESCRIPTION:Initial description
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email());
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // And Alice creates a separate personal email VALARM for her alias
        String aliceUpdatedCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)
            .replace("END:VEVENT", """
                BEGIN:VALARM
                UID:alarm-33333333-3333-4333-8333-333333333333
                ACTION:EMAIL
                DESCRIPTION:Alice personal reminder
                SUMMARY:Personal alarm notification
                ATTENDEE:mailto:{aliceAlias}
                TRIGGER:{alarmTrigger10m}
                END:VALARM
                END:VEVENT
                """
                .replace("{aliceAlias}", aliceAlias)
                .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT));
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);
        awaitAtMost.untilAsserted(() -> assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceAlias))));

        // When Bob updates non-alarm event fields
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("DTSTART:30250101T090000Z", "DTSTART:30250101T093000Z")
            .replace("DTEND:30250101T100000Z", "DTEND:30250101T103000Z")
            .replace("DESCRIPTION:Initial description", "DESCRIPTION:Updated description");
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Alice receives the event update and keeps her personal email VALARM local to her copy
        awaitAtMost.untilAsserted(() -> {
            String aliceEvent = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
            assertThat(aliceEvent)
                .contains("DTSTART:30250101T093000Z")
                .contains("DESCRIPTION:Updated description");
            assertThat(readEmailAlarms(aliceEvent))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceAlias)));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .isEmpty();
        });
    }

    @Test
    void organizerEmailVALARMShouldBeAddedAlongsideAttendeePersonalGeneratedVALARM() {
        // Given Bob creates an event with Alice and no email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String aliceAlias = "alice-alias@example.org";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer alarm after attendee personal alarm
            LOCATION:Meeting Room A
            DESCRIPTION:Check organizer alarm does not overwrite attendee personal alarm
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email());
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // And Alice creates a personal email VALARM without UID for her alias, which is not an event attendee
        String aliceUpdatedCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)
            .replace("END:VEVENT", """
                BEGIN:VALARM
                ACTION:EMAIL
                DESCRIPTION:Alice personal reminder
                SUMMARY:Personal alarm notification
                ATTENDEE:mailto:{aliceAlias}
                TRIGGER:{alarmTrigger10m}
                END:VALARM
                END:VEVENT
                """
                .replace("{aliceAlias}", aliceAlias)
                .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT));
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);
        awaitAtMost.untilAsserted(() -> {
            String aliceEvent = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
            assertThat(readEmailAlarms(aliceEvent))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceAlias)));
        });

        // When Bob adds an organizer-managed email VALARM without UID for Alice
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("END:VEVENT", """
                BEGIN:VALARM
                ACTION:EMAIL
                DESCRIPTION:Organizer reminder
                SUMMARY:Alarm notification
                ATTENDEE:mailto:{aliceEmail}
                ATTENDEE:mailto:{bobEmail}
                TRIGGER:{alarmTrigger5m}
                END:VALARM
                END:VEVENT
                """
                .replace("{aliceEmail}", alice.email())
                .replace("{bobEmail}", bob.email())
                .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT));
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Alice keeps her generated personal alarm and receives Bob's generated alarm as a separate VALARM
        awaitAtMost.untilAsserted(() -> {
            String aliceEvent = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri);
            assertThat(readEmailAlarms(aliceEvent))
                .containsExactlyInAnyOrder(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())),
                    new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceAlias)));
        });
    }

    @Test
    void organizerUpdateShouldRemoveEmailVALARMWhenAttendeeIsRemovedFromAlarmRecipients() {
        // Given Bob creates an event with Alice and Cedric and targets both attendees in one email VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer alarm recipient removal
            LOCATION:Meeting Room A
            DESCRIPTION:Check removing an alarm recipient removes its projection
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Cedric:mailto:{cedricEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            UID:organizer-reminder@example.org
            ACTION:EMAIL
            DESCRIPTION:This is an event reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{cedricEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{cedricEmail}", cedric.email())
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        String cedricCalendarEventId = awaitFirstEventId(cedric);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(cedric.email())));
        });

        // When Bob removes Alice from the email VALARM recipients while keeping Alice as event attendee
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("ATTENDEE:mailto:" + alice.email() + "\n", "");
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Alice loses the organizer-managed email alarm projection and Cedric keeps his
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEmpty();
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(cedric.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(cedric.email(), bob.email())));
        });
    }

    @Test
    void attendeePersonalEmailVALARMShouldNotPropagateToOrganizerAndOtherAttendees() {
        // Given Bob creates an event with Alice and Cedric as attendees and no VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String aliceAlias = "alice-alias@example.org";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Personal email alarm isolation
            LOCATION:Meeting Room A
            DESCRIPTION:Check attendee personal email alarm remains local
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

        // When Alice creates a personal email VALARM targeting her alias
        String aliceUpdatedCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)
            .replace("END:VEVENT", """
                BEGIN:VALARM
                UID:alice-personal-reminder@example.org
                ACTION:EMAIL
                DESCRIPTION:Alice personal reminder
                SUMMARY:Personal alarm notification
                ATTENDEE:mailto:{aliceAlias}
                TRIGGER:{alarmTrigger10m}
                END:VALARM
                END:VEVENT
                """
                .replace("{aliceAlias}", aliceAlias)
                .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT));
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        // Then only Alice's calendar contains that personal email alarm
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceAlias)));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .isEmpty();
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(cedric, cedricCalendarEventUri)))
                .isEmpty();
        });
    }

    @Test
    void organizerUpdateShouldPreserveAttendeePersonalEmailVALARMWhenOrganizerAlarmChanges() {
        // Given Bob creates an organizer-managed email VALARM for Alice
        String organizerEventUid = "event-" + UUID.randomUUID();
        String aliceAlias = "alice-alias@example.org";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{organizerEventUid}
            SEQUENCE:1
            DTSTART:30250101T090000Z
            DTEND:30250101T100000Z
            SUMMARY:Organizer and personal alarm merge
            LOCATION:Meeting Room A
            DESCRIPTION:Check organizer-managed alarm update preserves attendee personal alarm
            ORGANIZER;CN=Bob:mailto:{bobEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Alice:mailto:{aliceEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{bobEmail}
            BEGIN:VALARM
            UID:organizer-reminder@example.org
            ACTION:EMAIL
            DESCRIPTION:Organizer reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{bobEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{organizerEventUid}", organizerEventUid)
            .replace("{bobEmail}", bob.email())
            .replace("{aliceEmail}", alice.email())
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, organizerEventUid, organizerEventIcs);

        String aliceCalendarEventId = awaitFirstEventId(alice);
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);
        awaitAtMost.untilAsserted(() -> assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(alice.email()))));

        // And Alice creates a separate personal email VALARM for her alias
        String aliceUpdatedCalendarEventIcs = calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)
            .replace("END:VEVENT", """
                BEGIN:VALARM
                UID:alice-personal-reminder@example.org
                ACTION:EMAIL
                DESCRIPTION:Alice personal reminder
                SUMMARY:Personal alarm notification
                ATTENDEE:mailto:{aliceAlias}
                TRIGGER:{alarmTrigger10m}
                END:VALARM
                END:VEVENT
                """
                .replace("{aliceAlias}", aliceAlias)
                .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT));
        calDavClient.upsertCalendarEvent(alice, aliceCalendarEventUri, aliceUpdatedCalendarEventIcs);

        // When Bob updates the organizer-managed alarm trigger
        String updatedOrganizerEventIcs = organizerEventIcs
            .replace("SEQUENCE:1", "SEQUENCE:2")
            .replace("TRIGGER:" + ALARM_TRIGGER_5M_EXPLICIT, "TRIGGER:" + ALARM_TRIGGER_20M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bob, bobCalendarEventUri, updatedOrganizerEventIcs);

        // Then Alice keeps her personal alarm and receives the updated organizer-managed alarm
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .containsExactlyInAnyOrder(
                    new EmailAlarm(ALARM_TRIGGER_20M, Set.of(alice.email())),
                    new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceAlias)));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bob, bobCalendarEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_20M, Set.of(alice.email(), bob.email())));
        });
    }

    @Test
    void attendeeAddingVALARMShouldNotPropagateToOrganizerAndOtherAttendees() {
        // Given Bob creates an event with Alice and Cedric as attendees and no VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

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
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

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
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
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
            ACTION:DISPLAY
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

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
    void attendeePartStatUpdateShouldReapplyOrganizerManagedVALARMOverAttendeeMutation() {
        // Given Bob creates an event with Alice and Cedric as attendees and a VALARM
        String organizerEventUid = "event-" + UUID.randomUUID();
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
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
            ATTENDEE:mailto:{aliceEmail}
            ATTENDEE:mailto:{cedricEmail}
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI cedricCalendarEventUri = CalendarURL.from(cedric.id()).eventHref(cedricCalendarEventId);

        awaitAtMost.untilAsserted(() -> {
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(CalendarUtil.getAttendeePartStat(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri), cedric.email()))
                .isEqualTo(PartStat.NEEDS_ACTION);
        });

        // And Alice mutates the organizer-managed VALARM in her own event copy
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

        // And Alice's mutation is not preserved as a personal alarm
        assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(alice, aliceCalendarEventUri)))
            .isEqualTo(ALARM_TRIGGER_15M);
    }

    @Test
    void organizerUpdateShouldNotResetAttendeeLocalVALARMOnRecurringMasterAndOverride() {
        // Given Bob creates a recurring event with one override and different organizer alarms
        String organizerEventUid = "event-" + UUID.randomUUID();
        String overrideRecurrenceId = "30250102T090000Z";
        String organizerEventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.5.7//EN
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
        URI aliceCalendarEventUri = CalendarURL.from(alice.id()).eventHref(aliceCalendarEventId);
        URI bobCalendarEventUri = CalendarURL.from(bob.id()).eventHref(organizerEventUid);

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

    private record EmailAlarm(String trigger, Set<String> attendees) {
    }

    private List<EmailAlarm> readEmailAlarms(String icsContent) {
        return CalendarUtil.parseIcs(icsContent).getComponents(Component.VEVENT).stream()
            .filter(ComponentContainer.class::isInstance)
            .flatMap(vevent -> ((ComponentContainer<?>) vevent).getComponentList().getAll().stream())
            .filter(component -> Component.VALARM.equals(component.getName()))
            .filter(alarm -> alarm.getProperty(Property.ACTION)
                .map(Property::getValue)
                .filter("EMAIL"::equalsIgnoreCase)
                .isPresent())
            .map(alarm -> new EmailAlarm(readRequiredProperty(alarm, Property.TRIGGER),
                alarm.getProperties(Property.ATTENDEE).stream()
                    .map(Property::getValue)
                    .map(value -> value.replaceFirst("(?i)^mailto:", ""))
                    .collect(Collectors.toSet())))
            .toList();
    }

    private List<String> readEmailAlarmUids(String icsContent) {
        return CalendarUtil.parseIcs(icsContent).getComponents(Component.VEVENT).stream()
            .filter(ComponentContainer.class::isInstance)
            .flatMap(vevent -> ((ComponentContainer<?>) vevent).getComponentList().getAll().stream())
            .filter(component -> Component.VALARM.equals(component.getName()))
            .filter(alarm -> alarm.getProperty(Property.ACTION)
                .map(Property::getValue)
                .filter("EMAIL"::equalsIgnoreCase)
                .isPresent())
            .map(alarm -> readRequiredProperty(alarm, Property.UID))
            .toList();
    }

    private String readRequiredProperty(Component component, String propertyName) {
        return component.getProperty(propertyName)
            .map(Property::getValue)
            .orElseThrow(() -> new AssertionError("Expected " + propertyName + " to be present"));
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
