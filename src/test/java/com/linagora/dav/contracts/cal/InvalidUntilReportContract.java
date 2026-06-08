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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

/**
 * Reproduces https://github.com/linagora/esn-sabre/issues/374
 *
 * An ICS carrying an invalid RRULE UNTIL value (e.g. {@code UNTIL=INVALID DATE}) makes Sabre blow
 * up with a 500:
 *
 * <pre>
 * Sabre\VObject\InvalidDataException
 * The supplied iCalendar datetime value is incorrect: INVALID DATE
 * </pre>
 *
 * The expected behaviour is to be lenient on read and not crash the whole time-range REPORT because
 * of a single malformed occurrence: the REPORT should succeed (the broken occurrence may simply be
 * filtered out of the result).
 */
public abstract class InvalidUntilReportContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser alice;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        alice = dockerExtension().newTestUser();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/374")
    void timeRangeReportShouldNotFailWhenEventHasInvalidUntil() {
        // GIVEN an event whose RRULE carries an invalid UNTIL value gets ingested
        String eventUid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Linagora//Twake Calendar//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260601T080000Z
            DTSTART:20260608T170000Z
            DTEND:20260608T173000Z
            RRULE:FREQ=WEEKLY;INTERVAL=1;UNTIL=INVALID DATE;BYDAY=MO
            SUMMARY:Recurring event with broken UNTIL
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(alice, eventUid, ics);

        // WHEN a time-range REPORT has to expand that recurrence
        DavResponse response = calDavClient.findEventsByTime(alice, "20260601T000000", "20260701T000000");

        // THEN the REPORT answers gracefully instead of failing with a 500
        // (it is fine for the malformed occurrence to be filtered out of the result)
        assertThat(response.status())
            .as("Time-range REPORT response body: %s", response.body())
            .isEqualTo(200);
    }
}
