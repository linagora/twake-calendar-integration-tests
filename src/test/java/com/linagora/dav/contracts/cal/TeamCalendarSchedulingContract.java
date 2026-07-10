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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestUtil;
import com.linagora.dav.TwakeCalendarProvisioningService.TeamCalendarRole;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.PartStat;

public abstract class TeamCalendarSchedulingContract {
    private static final String ALARM_TRIGGER_15M = "-PT15M";
    private static final String ALARM_TRIGGER_5M = "-PT5M";
    private static final String TRANSP_OPAQUE = "OPAQUE";
    private static final String TRANSP_TRANSPARENT = "TRANSPARENT";
    private static final String ALARM_TRIGGER_5M_EXPLICIT = "-P0DT0H05M0S";
    private static final String ALARM_TRIGGER_10M = "-PT10M";
    private static final String ALARM_TRIGGER_10M_EXPLICIT = "-P0DT0H10M0S";

    private final ConditionFactory awaitAtMost = TestUtil.awaitAtMost;

    private CalDavClient calDavClient;
    private OpenPaasUser bobMember;
    private OpenPaasUser aliceMember;
    private OpenPaasUser nonMember;
    private OpenPaaSTeamCalendar teamCalendar;
    private CalendarURL bobMemberDelegatedCalendar;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerService.SABRE_DAV, "http")
                .toString())
            .build();
        bobMember = dockerExtension().newTestUser();
        aliceMember = dockerExtension().newTestUser();
        nonMember = dockerExtension().newTestUser();
        teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("operations-" + UUID.randomUUID(), "Operations Team")
            .block();

        dockerExtension().twakeCalendarProvisioningService()
            .addTeamCalendarMember(teamCalendar, bobMember, TeamCalendarRole.MEMBER)
            .block();
        List<CalendarURL> bobDelegatedCalendars = calDavClient.findDelegatedCalendar(bobMember);
        assertThat(bobDelegatedCalendars)
            .as("Bob should have one delegated team calendar")
            .hasSize(1);
        bobMemberDelegatedCalendar = bobDelegatedCalendars.getFirst();

        dockerExtension().twakeCalendarProvisioningService()
            .addTeamCalendarMember(teamCalendar, aliceMember, TeamCalendarRole.MEMBER)
            .block();
        List<CalendarURL> aliceDelegatedCalendars = calDavClient.findDelegatedCalendar(aliceMember);
        assertThat(aliceDelegatedCalendars)
            .as("Alice should have one delegated team calendar")
            .hasSize(1);
    }

    @Test
    void teamCalendarEventCreationShouldPropagateEventToAttendeeCalendar() {
        // Given bobMember is a write-enabled Team Calendar member
        String eventUid = "team-event-" + UUID.randomUUID();

        // When bobMember creates an event in the Team Calendar with nonMember and aliceMember as attendees
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Team calendar invitation"));

        // Then both non-member and member attendees receive an attendee copy in their default calendar
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);

        CalendarUtil.CalendarExtractor nonMemberEvent = CalendarUtil.toExtractor(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri));
        assertSoftly(softly -> {
            softly.assertThat(nonMemberEvent.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
            softly.assertThat(nonMemberEvent.extractPropertyValue(Property.ORGANIZER)).isEqualTo("mailto:" + bobMember.email());
            softly.assertThat(nonMemberEvent.extractPropertyValue(Property.SUMMARY)).isEqualTo("Team calendar invitation");
        });
        CalendarUtil.CalendarExtractor aliceMemberEvent = CalendarUtil.toExtractor(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri));
        assertSoftly(softly -> {
            softly.assertThat(aliceMemberEvent.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
            softly.assertThat(aliceMemberEvent.extractPropertyValue(Property.ORGANIZER)).isEqualTo("mailto:" + bobMember.email());
            softly.assertThat(aliceMemberEvent.extractPropertyValue(Property.SUMMARY)).isEqualTo("Team calendar invitation");
        });
    }

    @Test
    void teamCalendarEventUpdateShouldPropagateToAttendeeCalendar() {
        // Given bobMember created a Team Calendar event with nonMember and aliceMember as attendees
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Initial Team Calendar meeting"));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);

        // When bobMember updates the event in the Team Calendar
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Updated Team Calendar meeting"));

        // Then both non-member and member attendee copies receive the update
        awaitAtMost.untilAsserted(() -> {
            CalendarUtil.CalendarExtractor nonMemberEvent = CalendarUtil.toExtractor(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri));
            assertSoftly(softly -> {
                softly.assertThat(nonMemberEvent.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
                softly.assertThat(nonMemberEvent.extractPropertyValue(Property.ORGANIZER)).isEqualTo("mailto:" + bobMember.email());
                softly.assertThat(nonMemberEvent.extractPropertyValue(Property.SUMMARY)).isEqualTo("Updated Team Calendar meeting");
            });
        });
        awaitAtMost.untilAsserted(() -> {
            CalendarUtil.CalendarExtractor aliceMemberEvent = CalendarUtil.toExtractor(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri));
            assertSoftly(softly -> {
                softly.assertThat(aliceMemberEvent.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
                softly.assertThat(aliceMemberEvent.extractPropertyValue(Property.ORGANIZER)).isEqualTo("mailto:" + bobMember.email());
                softly.assertThat(aliceMemberEvent.extractPropertyValue(Property.SUMMARY)).isEqualTo("Updated Team Calendar meeting");
            });
        });
    }

    @Test
    void teamCalendarEventUpdateThroughDelegatedMirrorShouldUpdateCanonicalTeamCalendarEvent() {
        // Given bobMember created a Team Calendar event through the delegated mirror
        String eventUid = "team-event-" + UUID.randomUUID();
        String updatedSummary = "Updated through delegated mirror";
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Initial Team Calendar meeting"));

        // When bobMember updates the event through the delegated mirror
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), updatedSummary));

        // Then both the delegated mirror and canonical Team Calendar event expose the updated data
        awaitAtMost.untilAsserted(() -> {
            CalendarUtil.CalendarExtractor delegatedMirrorEvent = CalendarUtil.toExtractor(
                calDavClient.getCalendarEvent(bobMember, bobMemberDelegatedCalendar.eventHref(eventUid)));
            CalendarUtil.CalendarExtractor canonicalTeamCalendarEvent = CalendarUtil.toExtractor(
                calDavClient.getCalendarEvent(bobMember, teamCalendarCanonicalUrl.eventHref(eventUid)));
            assertSoftly(softly -> {
                softly.assertThat(delegatedMirrorEvent.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
                softly.assertThat(delegatedMirrorEvent.extractPropertyValue(Property.SUMMARY)).isEqualTo(updatedSummary);
                softly.assertThat(canonicalTeamCalendarEvent.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
                softly.assertThat(canonicalTeamCalendarEvent.extractPropertyValue(Property.SUMMARY)).isEqualTo(updatedSummary);
            });
        });
    }

    @Test
    void teamCalendarEventDeletionShouldMarkAttendeeEventCancelled() {
        // Given bobMember created a Team Calendar event with nonMember and aliceMember as attendees
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Team Calendar meeting to cancel"));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);

        // When bobMember deletes the event in the Team Calendar
        calDavClient.deleteCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid);

        // Then both non-member and member attendee copies are marked as cancelled
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri))
                .extractPropertyValue(Property.STATUS))
            .isEqualTo("CANCELLED"));
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri))
                .extractPropertyValue(Property.STATUS))
            .isEqualTo("CANCELLED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "DECLINED"})
    void attendeePartStatUpdateShouldPropagateFromTeamCalendarEvent(String partStatValue) {
        // Given bobMember creates a Team Calendar event with nonMember and aliceMember as attendees
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Team calendar invitation"));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);
        PartStat partStat = partStatValue.equals("ACCEPTED") ? PartStat.ACCEPTED : PartStat.DECLINED;

        // When nonMember updates her participation status from her attendee copy
        String nonMemberEventIcs = calDavClient.getCalendarEvent(nonMember, nonMemberEventUri);
        given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(nonMember.email()))
            .header("Content-Type", "text/calendar ; charset=utf-8")
            .body(CalendarUtil.withAttendeePartStat(nonMemberEventIcs, nonMember.email(), partStat))
        .when()
            .put(nonMemberEventUri.toString())
        .then()
            .statusCode(anyOf(is(201), is(204)));

        // Then the Team Calendar event reflects nonMember participation status
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.getAttendeePartStat(
                calDavClient.getCalendarEvent(bobMember, bobMemberDelegatedCalendar.eventHref(eventUid)), nonMember.email()))
            .as("Team Calendar event should reflect nonMember PARTSTAT %s".formatted(partStatValue))
            .isEqualTo(partStat));

        // And aliceMember attendee copy also reflects nonMember participation status
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.getAttendeePartStat(
                calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri), nonMember.email()))
            .isEqualTo(partStat));
    }

    @Test
    void attendeeReplyShouldIgnoreForgedTeamCalendarIdWhenCalendarDoesNotContainEventUid() throws IOException {
        // Given bobMember creates a Team Calendar event
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()), "Team calendar invitation"));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);

        // And an unrelated Team Calendar exists but does not contain this event UID
        OpenPaaSTeamCalendar forgedTargetTeamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("unrelated-" + UUID.randomUUID(), "Unrelated Team")
            .block();
        BlockingQueue<JsonNode> localDeliveryMessages = listenToFreshQueue("forged-team-calendar-reply-", "calendar:itip:localDelivery");

        // When nonMember accepts from her attendee copy while forging the Team Calendar ID
        String acceptedAttendeeCopyWithForgedTeamCalendarId = withTeamCalendarId(
            CalendarUtil.withAttendeePartStat(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri), nonMember.email(), PartStat.ACCEPTED),
            forgedTargetTeamCalendar.id());
        calDavClient.upsertCalendarEvent(nonMember, nonMemberEventUri, acceptedAttendeeCopyWithForgedTeamCalendarId);

        // Then Sabre still emits a REPLY, but it does not propagate the forged Team Calendar ID
        awaitAtMost.untilAsserted(() -> assertThat(localDeliveryMessages)
            .as("A REPLY local-delivery message should be published for the organizer")
            .anySatisfy(message -> {
                assertThat(message.path("method").asText()).isEqualTo("REPLY");
                assertThat(message.path("uid").asText()).isEqualTo(eventUid);
                assertThat(message.path("message").asText()).doesNotContain("X-OPENPAAS-TEAM-CALENDAR-ID:" + forgedTargetTeamCalendar.id());
            }));
    }

    @Test
    void teamCalendarEventCreationWithAlarmShouldPropagateAlarmToAttendeeCalendar() {
        // Given bobMember is a write-enabled Team Calendar member
        String eventUid = "team-event-" + UUID.randomUUID();

        // When bobMember creates an event with a display VALARM in the Team Calendar with nonMember and aliceMember as attendees
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarDataWithAlarm(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()),
                "Team calendar invitation with alarm", ALARM_TRIGGER_15M));

        // Then both non-member and member attendee copies carry the alarm
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);

        awaitAtMost.untilAsserted(() -> {
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
        });
    }

    @Test
    void attendeeAlarmUpdateShouldNotPropagateToTeamCalendarEventAndOtherAttendees() {
        // Given bobMember created a Team Calendar event with a display VALARM and nonMember and aliceMember as attendees
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarDataWithAlarm(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()),
                "Team calendar alarm isolation check", ALARM_TRIGGER_15M));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);
        URI teamCalendarEventUri = bobMemberDelegatedCalendar.eventHref(eventUid);
        awaitAtMost.untilAsserted(() -> {
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(bobMember, teamCalendarEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
            assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri)))
                .isEqualTo(ALARM_TRIGGER_15M);
        });

        // When nonMember updates the VALARM trigger in his own attendee copy
        String nonMemberUpdatedEventIcs = calDavClient.getCalendarEvent(nonMember, nonMemberEventUri)
            .replace("TRIGGER:" + ALARM_TRIGGER_15M, "TRIGGER:" + ALARM_TRIGGER_5M);
        calDavClient.upsertCalendarEvent(nonMember, nonMemberEventUri, nonMemberUpdatedEventIcs);

        // Then nonMember copy reflects the updated alarm
        awaitAtMost.untilAsserted(() -> assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri)))
            .isEqualTo(ALARM_TRIGGER_5M));

        // And the Team Calendar event and aliceMember copy keep the original alarm trigger
        awaitAtMost
            .during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(bobMember, teamCalendarEventUri)))
                    .isEqualTo(ALARM_TRIGGER_15M);
                assertThat(readFirstAlarmTrigger(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri)))
                    .isEqualTo(ALARM_TRIGGER_15M);
            });
    }

    @Test
    void attendeePersonalSettingShouldNotPropagateToTeamCalendarEvent() {
        // Given bobMember creates a Team Calendar event with a default TRANSP value
        String eventUid = "team-event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()),
                "Team calendar personal setting isolation", "TRANSP:" + TRANSP_OPAQUE + "\n"));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());

        // When nonMember updates a personal setting from her attendee copy
        String nonMemberEventIcs = calDavClient.getCalendarEvent(nonMember, nonMemberEventUri);
        calDavClient.upsertCalendarEvent(nonMember, nonMemberEventUri,
            nonMemberEventIcs.replace("TRANSP:" + TRANSP_OPAQUE, "TRANSP:" + TRANSP_TRANSPARENT));

        // Then only nonMember attendee copy reflects the local personal setting
        awaitAtMost.untilAsserted(() -> assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri))
                .extractPropertyValue(Property.TRANSP))
            .isEqualTo(TRANSP_TRANSPARENT));

        // And the Team Calendar event and other attendee copies keep the shared value
        awaitAtMost.during(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(bobMember, teamCalendarCanonicalUrl.eventHref(eventUid)))
                        .extractPropertyValue(Property.TRANSP))
                    .isEqualTo(TRANSP_OPAQUE);
                assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(bobMember, bobMemberDelegatedCalendar.eventHref(eventUid)))
                        .extractPropertyValue(Property.TRANSP))
                    .isEqualTo(TRANSP_OPAQUE);
                assertThat(CalendarUtil.toExtractor(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri))
                        .extractPropertyValue(Property.TRANSP))
                    .isEqualTo(TRANSP_OPAQUE);
            });
    }

    @Test
    void emailAlarmShouldReplicateOnlyForMatchingAttendeeWhileTeamCalendarKeepsAllAlarms() {
        // Given bobMember creates a Team Calendar event with attendee-specific email alarms
        String eventUid = "team-event-" + UUID.randomUUID();
        String alarmProperties = """
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:Non-member reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{nonMemberEmail}
            TRIGGER:{alarmTrigger5m}
            END:VALARM
            BEGIN:VALARM
            ACTION:EMAIL
            DESCRIPTION:Alice reminder
            SUMMARY:Alarm notification
            ATTENDEE:mailto:{aliceMemberEmail}
            TRIGGER:{alarmTrigger10m}
            END:VALARM
            """
            .replace("{nonMemberEmail}", nonMember.email())
            .replace("{aliceMemberEmail}", aliceMember.email())
            .replace("{alarmTrigger5m}", ALARM_TRIGGER_5M_EXPLICIT)
            .replace("{alarmTrigger10m}", ALARM_TRIGGER_10M_EXPLICIT);
        calDavClient.upsertCalendarEvent(bobMember, bobMemberDelegatedCalendar, eventUid,
            calendarData(eventUid, bobMember.email(), List.of(nonMember.email(), aliceMember.email()),
                "Team calendar alarm replication", alarmProperties));
        URI nonMemberEventUri = awaitCalendarObjectUriByEventUid(nonMember, CalendarURL.from(nonMember.id()), eventUid);
        URI aliceMemberEventUri = awaitCalendarObjectUriByEventUid(aliceMember, CalendarURL.from(aliceMember.id()), eventUid);
        CalendarURL teamCalendarCanonicalUrl = CalendarURL.from(teamCalendar.id());

        // Then attendee copies keep only their own alarms while Team Calendar keeps every alarm
        awaitAtMost.untilAsserted(() -> {
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(nonMember, nonMemberEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_5M, Set.of(nonMember.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(aliceMember, aliceMemberEventUri)))
                .containsExactly(new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceMember.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bobMember, teamCalendarCanonicalUrl.eventHref(eventUid))))
                .containsExactly(
                    new EmailAlarm(ALARM_TRIGGER_5M, Set.of(nonMember.email())),
                    new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceMember.email())));
            assertThat(readEmailAlarms(calDavClient.getCalendarEvent(bobMember, bobMemberDelegatedCalendar.eventHref(eventUid))))
                .containsExactly(
                    new EmailAlarm(ALARM_TRIGGER_5M, Set.of(nonMember.email())),
                    new EmailAlarm(ALARM_TRIGGER_10M, Set.of(aliceMember.email())));
        });
    }

    private URI awaitCalendarObjectUriByEventUid(OpenPaasUser user, CalendarURL calendarURL, String eventUid) {
        return awaitAtMost.until(() -> calDavClient.findFirstUserCalendarObjectUriByEventUid(user, calendarURL, eventUid),
                Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected calendar object URI to be present for UID " + eventUid));
    }

    private String calendarData(String eventUid, String organizerEmail, List<String> attendeeEmails, String summary) {
        return calendarData(eventUid, organizerEmail, attendeeEmails, summary, "");
    }

    private String calendarData(String eventUid, String organizerEmail, List<String> attendeeEmails, String summary, String extraEventProperties) {
        String attendeeProperties = attendeeEmails.stream()
            .map(attendeeEmail -> "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN="
                + attendeeEmail + ":mailto:" + attendeeEmail)
            .reduce("", (accumulator, attendee) -> accumulator + attendee + "\n");

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:20300101T080000Z
            DTSTART:20300110T090000Z
            DTEND:20300110T100000Z
            ORGANIZER;CN=Organizer:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=Organizer:mailto:{organizerEmail}
            {attendeeProperties}SUMMARY:{summary}
            {extraEventProperties}\
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeProperties}", attendeeProperties)
            .replace("{summary}", summary)
            .replace("{extraEventProperties}", extraEventProperties);
    }

    private String calendarDataWithAlarm(String eventUid, String organizerEmail, List<String> attendeeEmails, String summary, String alarmTrigger) {
        return calendarData(eventUid, organizerEmail, attendeeEmails, summary)
            .replace("END:VEVENT", """
                BEGIN:VALARM
                TRIGGER:{alarmTrigger}
                ACTION:DISPLAY
                DESCRIPTION:This is an automatic alarm sent by OpenPaas
                END:VALARM
                END:VEVENT"""
                .replace("{alarmTrigger}", alarmTrigger));
    }

    private String readFirstAlarmTrigger(String icsContent) {
        return icsContent.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("TRIGGER:"))
            .map(line -> line.substring("TRIGGER:".length()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected VALARM trigger to be present"));
    }

    private String withTeamCalendarId(String icsContent, String teamCalendarId) {
        String property = "X-OPENPAAS-TEAM-CALENDAR-ID:" + teamCalendarId;
        if (icsContent.contains("X-OPENPAAS-TEAM-CALENDAR-ID:")) {
            return icsContent.replaceAll("(?m)^X-OPENPAAS-TEAM-CALENDAR-ID:.*$", property);
        }

        return icsContent.replaceFirst("(?m)^ORGANIZER", property + "\nORGANIZER");
    }

    private BlockingQueue<JsonNode> listenToFreshQueue(String queuePrefix, String exchange) throws IOException {
        String queueName = queuePrefix + UUID.randomUUID();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, exchange, "");
        return AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);
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

    private String readRequiredProperty(Component component, String propertyName) {
        return component.getProperty(propertyName)
            .map(Property::getValue)
            .orElseThrow(() -> new AssertionError("Expected " + propertyName + " to be present"));
    }
}
