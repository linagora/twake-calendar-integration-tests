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
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.UUID;

import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

public abstract class RecurrenceValidationContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    @Test
    void shouldRejectJCalRecurrenceIdWithDifferentValueTypeThanDtStart() {
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "%s"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-15T09:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-15T10:00:00Z"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}],
                    ["summary", {}, "text", "Recurring master"]
                  ],
                  []
                ],
                ["vevent",
                  [
                    ["uid", {}, "text", "%s"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["recurrence-id", {}, "date", "2026-05-16"],
                    ["dtstart", {}, "date-time", "2026-05-16T11:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-16T12:00:00Z"],
                    ["summary", {}, "text", "Invalid override"]
                  ],
                  []
                ]
              ]
            ]
            """.formatted(uid, uid), "application/calendar+json");

        assertThat(response.status()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("Validation error in iCalendar")
            .contains("RFC 5545")
            .contains("RECURRENCE-ID value type (DATE) must match DTSTART value type (DATE-TIME)");
    }

    private DavResponse putStrictCalendarObject(OpenPaasUser user, String uid, String payload, String contentType) {
        return putCalendarObject(user, uid, payload, contentType, "handling=strict");
    }

    private DavResponse putCalendarObject(OpenPaasUser user, String uid, String payload, String contentType, String prefer) {
        return dockerExtension().davHttpClient()
            .headers(headers -> {
                user.impersonatedBasicAuth(headers).add("Content-Type", contentType);
                if (prefer != null) {
                    headers.add("Prefer", prefer);
                }
            })
            .put()
            .uri("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
            .send(body(payload))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .map(stringContent -> new DavResponse(response.status().code(), stringContent)))
            .block();
    }
}
