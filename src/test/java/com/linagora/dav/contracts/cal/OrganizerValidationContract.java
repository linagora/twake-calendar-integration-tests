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

import static com.linagora.dav.TestUtil.body;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

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
    void delegateCanWriteToOwnerCalendarWithOwnerAsOrganizer() {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser delegate = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        calDavClient.grantDelegation(owner, owner.id(), delegate, DelegationRight.READ_WRITE);

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

    @Test
    void delegateCanWriteToOwnerCalendarWithDelegateAsOrganizer() {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser delegate = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        calDavClient.grantDelegation(owner, owner.id(), delegate, DelegationRight.READ_WRITE);

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

    @Test
    void delegateCannotUseOwnerAsOrganizerInTheirOwnCalendar() {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // GIVEN Bob delegates his calendar to Alice with write rights
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

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

    @Test
    void delegateCannotWriteToOwnerCalendarWithThirdPartyAsOrganizer() {
        OpenPaasUser owner = dockerExtension().newTestUser();
        OpenPaasUser delegate = dockerExtension().newTestUser();
        OpenPaasUser thirdParty = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        calDavClient.grantDelegation(owner, owner.id(), delegate, DelegationRight.READ_WRITE);

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
}
