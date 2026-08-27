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

import static com.linagora.dav.TestUtil.awaitAtMost;
import static com.linagora.dav.TestUtil.body;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import io.netty.handler.codec.http.HttpMethod;
import net.fortuna.ical4j.model.parameter.PartStat;

public abstract class OrganizerValidationContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
    }

    @Test
    void putEventWithoutOrganizerShouldBeAccepted() {
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:No organizer\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid));

        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @Test
    void putEventWithAttendeeButNoOrganizerShouldBeRejected() {
        OpenPaasUser user = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Attendee without organizer\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, attendee.email()));

        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        assertThat(response.body()).contains("ATTENDEE");
    }

    @Test
    void putEventWithOrganizerMatchingCalendarOwnerShouldBeAccepted() {
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Meeting\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:other@example.com\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, user.email()));

        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @Test
    void putEventWithOrganizerNotResolvableToPrincipalShouldBeRejected() {
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Meeting\r
            ORGANIZER:mailto:unknown-nobody@example-nonexistent.invalid\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:other@example.com\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid));

        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        assertThat(response.body()).contains("ORGANIZER");
    }

    @Test
    void putEventWithOrganizerMatchingAnotherValidUserShouldBeRejected() {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser otherUser = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putIcs(owner, owner.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Meeting\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, otherUser.email(), owner.email()));

        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        assertThat(response.body()).contains("ORGANIZER");
    }

    @Test
    void putRecurringEventWithMismatchedOrganizersShouldBeRejected() {
        OpenPaasUser user = dockerExtension().newTestUser();
        OpenPaasUser otherUser = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            RRULE:FREQ=DAILY;COUNT=2\r
            SUMMARY:Recurring master\r
            ORGANIZER:mailto:%s\r
            END:VEVENT\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            RECURRENCE-ID:20250102T090000Z\r
            DTSTART:20250102T100000Z\r
            DTEND:20250102T110000Z\r
            SUMMARY:Override with different organizer\r
            ORGANIZER:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, user.email(), uid, otherUser.email()));

        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        assertThat(response.body()).contains("ORGANIZER");
    }

    @Test
    void importEventWithAttendeeButNoOrganizerShouldBeAccepted() {
        OpenPaasUser user = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = importIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Attendee without organizer\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, attendee.email()));

        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @Test
    void importEventWithOrganizerNotResolvableToPrincipalShouldBeAccepted() {
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = importIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Meeting\r
            ORGANIZER:mailto:unknown-nobody@example-nonexistent.invalid\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:other@example.com\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid));

        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @Test
    void importEventWithOrganizerMatchingAnotherValidUserShouldBeAccepted() {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser otherUser = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = importIcs(owner, owner.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Meeting\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, otherUser.email(), owner.email()));

        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @Test
    void importRecurringEventWithMismatchedOrganizersShouldBeAccepted() {
        OpenPaasUser user = dockerExtension().newTestUser();
        OpenPaasUser otherUser = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = importIcs(user, user.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            RRULE:FREQ=DAILY;COUNT=2\r
            SUMMARY:Recurring master\r
            ORGANIZER:mailto:%s\r
            END:VEVENT\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            RECURRENCE-ID:20250102T090000Z\r
            DTSTART:20250102T100000Z\r
            DTEND:20250102T110000Z\r
            SUMMARY:Override with different organizer\r
            ORGANIZER:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, user.email(), uid, otherUser.email()));

        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void delegateCanWriteToOwnerCalendarWithOwnerAsOrganizer(DelegationRight right) {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser delegate = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        calDavClient.grantDelegation(owner, owner.id(), delegate, right);

        DavResponse response = putIcs(delegate, owner.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Delegate writes with owner as organizer\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, owner.email(), delegate.email()));

        assertThat(response.status()).isIn(SC_CREATED, SC_NO_CONTENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void delegateCanWriteToOwnerCalendarWithDelegateAsOrganizer(DelegationRight right) {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser delegate = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        calDavClient.grantDelegation(owner, owner.id(), delegate, right);

        DavResponse response = putIcs(delegate, owner.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Delegate writes with themselves as organizer\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, delegate.email(), owner.email()));

        assertThat(response.status()).isIn(SC_CREATED, SC_NO_CONTENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void delegateCannotUseOwnerAsOrganizerInTheirOwnCalendar(DelegationRight right) {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // GIVEN Bob delegates his calendar to Alice with write rights
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // WHEN Alice creates an event in her own calendar with Bob as organizer
        DavResponse response = putIcs(alice, alice.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Delegate writes in own calendar with owner as organizer\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, bob.email(), alice.email()));

        // THEN Bob is rejected because he is neither the effective calendar owner nor the requester
        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        assertThat(response.body()).contains("ORGANIZER");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void delegateCannotWriteToOwnerCalendarWithThirdPartyAsOrganizer(DelegationRight right) {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser delegate = dockerExtension().newTestUser();
        OpenPaasUser thirdParty = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        calDavClient.grantDelegation(owner, owner.id(), delegate, right);

        DavResponse response = putIcs(delegate, owner.id(), uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Delegate writes with third party as organizer\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, thirdParty.email(), owner.email()));

        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        assertThat(response.body()).contains("ORGANIZER");
    }

    @Test
    void attendeeCanUpdatePartStatOnEventCopyCreatedByInvitation() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // GIVEN the organizer invites the attendee, scheduling delivers a copy into the attendee calendar
        calDavClient.upsertCalendarEvent(organizer, uid, """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Test//Test//EN\r
            BEGIN:VEVENT\r
            UID:%s\r
            DTSTAMP:20250101T000000Z\r
            DTSTART:20250101T090000Z\r
            DTEND:20250101T100000Z\r
            SUMMARY:Meeting\r
            ORGANIZER:mailto:%s\r
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:%s\r
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL:mailto:%s\r
            END:VEVENT\r
            END:VCALENDAR\r
            """.formatted(uid, organizer.email(), organizer.email(), attendee.email()));

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(attendee), Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected event to be propagated to attendee calendar"));

        // WHEN the attendee updates their PARTSTAT on their copy, where the ORGANIZER is someone else
        String attendeeEventIcs = calDavClient.getCalendarEvent(attendee,
            URI.create("/calendars/" + attendee.id() + "/" + attendee.id() + "/" + attendeeEventId + ".ics"));
        String acceptedIcs = CalendarUtil.withAttendeePartStat(attendeeEventIcs, attendee.email(), PartStat.ACCEPTED);
        DavResponse response = putIcs(attendee, attendee.id(), attendeeEventId, acceptedIcs);

        // THEN the update is accepted
        assertThat(response.status()).isEqualTo(SC_NO_CONTENT);
    }

    @Test
    void copyEventToCalendarOfOtherUserShouldBeForbidden() {
        OpenPaasUser user = dockerExtension().newTestUser();
        OpenPaasUser otherUser = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // GIVEN the user owns an event in his default calendar
        assertThat(putIcs(user, user.id(), uid, eventWithOrganizer(uid, user.email())).status())
            .isEqualTo(SC_CREATED);
        String destinationUri = CalendarURL.from(otherUser.id()).eventHref(uid).toASCIIString();

        // WHEN the user copies the event to the calendar of another user
        DavResponse response = transferIcs("COPY", user, eventUri(user, uid), destinationUri);

        // THEN the copy is forbidden because the target calendar is not owned by the user
        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        // AND nothing landed in the calendar of the other user
        assertThat(eventStatus(otherUser, destinationUri)).isEqualTo(SC_NOT_FOUND);
    }

    @Test
    void moveEventToCalendarOfOtherUserShouldBeForbidden() {
        OpenPaasUser user = dockerExtension().newTestUser();
        OpenPaasUser otherUser = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // GIVEN the user owns an event in his default calendar
        assertThat(putIcs(user, user.id(), uid, eventWithOrganizer(uid, user.email())).status())
            .isEqualTo(SC_CREATED);
        String destinationUri = CalendarURL.from(otherUser.id()).eventHref(uid).toASCIIString();

        // WHEN the user moves the event to the calendar of another user
        DavResponse response = transferIcs("MOVE", user, eventUri(user, uid), destinationUri);

        // THEN the move is forbidden because the target calendar is not owned by the user
        assertThat(response.status()).isEqualTo(SC_FORBIDDEN);
        // AND nothing landed in the calendar of the other user, while the source is untouched
        assertThat(eventStatus(otherUser, destinationUri)).isEqualTo(SC_NOT_FOUND);
        assertThat(eventStatus(user, eventUri(user, uid))).isEqualTo(SC_OK);
    }

    private String eventWithOrganizer(String uid, String organizerEmail) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//Test//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T000000Z
            DTSTART:20250101T090000Z
            DTEND:20250101T100000Z
            SUMMARY:Meeting
            ORGANIZER:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid, organizerEmail);
    }

    private String eventUri(OpenPaasUser user, String uid) {
        return "/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics";
    }

    private DavResponse transferIcs(String method, OpenPaasUser requester, String sourceUri, String destinationUri) {
        return dockerExtension().davHttpClient()
            .headers(headers -> requester.impersonatedBasicAuth(headers)
                .add("Destination", destinationUri))
            .request(HttpMethod.valueOf(method))
            .uri(sourceUri)
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .map(stringContent -> new DavResponse(response.status().code(), stringContent)))
            .block();
    }

    private int eventStatus(OpenPaasUser requester, String uri) {
        return dockerExtension().davHttpClient()
            .headers(requester::impersonatedBasicAuth)
            .get()
            .uri(uri)
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .map(stringContent -> response.status().code()))
            .block();
    }

    private DavResponse putIcs(OpenPaasUser requester, String calendarOwnerId, String uid, String icsContent) {
        return dockerExtension().davHttpClient()
            .headers(headers -> requester.impersonatedBasicAuth(headers)
                .add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + calendarOwnerId + "/" + calendarOwnerId + "/" + uid + ".ics")
            .send(body(icsContent))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .map(stringContent -> new DavResponse(response.status().code(), stringContent)))
            .block();
    }

    private DavResponse importIcs(OpenPaasUser requester, String calendarOwnerId, String uid, String icsContent) {
        return dockerExtension().davHttpClient()
            .headers(headers -> requester.impersonatedBasicAuth(headers)
                .add("Content-Type", "text/plain"))
            .put()
            .uri("/calendars/" + calendarOwnerId + "/" + calendarOwnerId + "/" + uid + ".ics?import")
            .send(body(icsContent))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .map(stringContent -> new DavResponse(response.status().code(), stringContent)))
            .block();
    }
}
