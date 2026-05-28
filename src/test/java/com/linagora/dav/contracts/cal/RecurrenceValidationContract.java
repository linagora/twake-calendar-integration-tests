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
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

public abstract class RecurrenceValidationContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    @Test
    void shouldStoreValidStrictObject() {
        // Given RRULE UNTIL, EXDATE, and RECURRENCE-ID all match DTSTART as UTC date-time values
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-15T09:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-15T10:00:00Z"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "until": "2026-05-17T09:00:00Z"}],
                    ["exdate", {}, "date-time", "2026-05-16T09:00:00Z"],
                    ["summary", {}, "text", "Valid recurring master"]
                  ],
                  []
                ],
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["recurrence-id", {}, "date-time", "2026-05-17T09:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-17T11:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-17T12:00:00Z"],
                    ["summary", {}, "text", "Valid override"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre stores the valid strict request
        assertThat(response.status()).isEqualTo(SC_CREATED);
    }

    @Test
    void shouldRejectOverrideWithoutMaster() {
        // Given a VEVENT with RECURRENCE-ID but no master VEVENT sharing its UID
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["recurrence-id", {}, "date-time", "2026-05-16T09:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-16T11:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-16T12:00:00Z"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}],
                    ["summary", {}, "text", "Orphan recurring occurrence"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the orphan override
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("RECURRENCE-ID override for UID " + uid + " has no matching recurring master VEVENT (same UID with RRULE or RDATE)");
    }

    @Test
    void shouldRejectUntilWhenMismatchedValueType() {
        // Given RRULE UNTIL is DATE (2026-05-17) while DTSTART is DATE-TIME UTC
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-15T09:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-15T10:00:00Z"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "until": "2026-05-17"}],
                    ["summary", {}, "text", "Recurring master with invalid UNTIL"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the UNTIL/DTSTART type mismatch
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("UNTIL value type (DATE) must match DTSTART value type (DATE-TIME)");
    }

    @Test
    void shouldRejectRRuleWithCountAndUntil() {
        // Given RRULE defines both COUNT=3 and UNTIL=2026-05-17T09:00:00Z
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-15T09:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-15T10:00:00Z"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "count": 3, "until": "2026-05-17T09:00:00Z"}],
                    ["summary", {}, "text", "Recurring master with invalid RRULE"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the RFC 5545 rule conflict
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("COUNT and UNTIL MUST NOT both be present in the same RRULE");
    }

    @Test
    void shouldRejectOverrideIdWhenMismatchedValueType() {
        // Given RECURRENCE-ID is DATE (2026-05-16) while master DTSTART is DATE-TIME UTC
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
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
                    ["uid", {}, "text", "{uid}"],
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
            """.replace("{uid}", uid));

        // Then Sabre rejects the override id value type mismatch
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("RECURRENCE-ID value type (DATE) must match DTSTART value type (DATE-TIME)");
    }

    @Test
    void shouldRejectOverrideIdWhenMismatchedTimezone() {
        // Given RECURRENCE-ID is UTC date-time while master DTSTART is Europe/Paris local date-time
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vtimezone",
                  [
                    ["tzid", {}, "text", "Europe/Paris"]
                  ],
                  [
                    ["daylight",
                      [
                        ["tzoffsetfrom", {}, "utc-offset", "+01:00"],
                        ["tzoffsetto", {}, "utc-offset", "+02:00"],
                        ["tzname", {}, "text", "CEST"],
                        ["dtstart", {}, "date-time", "1970-03-29T02:00:00"],
                        ["rrule", {}, "recur", {"freq": "YEARLY", "bymonth": "3", "byday": "-1SU"}]
                      ],
                      []
                    ],
                    ["standard",
                      [
                        ["tzoffsetfrom", {}, "utc-offset", "+02:00"],
                        ["tzoffsetto", {}, "utc-offset", "+01:00"],
                        ["tzname", {}, "text", "CET"],
                        ["dtstart", {}, "date-time", "1970-10-25T03:00:00"],
                        ["rrule", {}, "recur", {"freq": "YEARLY", "bymonth": "10", "byday": "-1SU"}]
                      ],
                      []
                    ]
                  ]
                ],
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {"tzid": "Europe/Paris"}, "date-time", "2026-05-15T09:00:00"],
                    ["dtend", {"tzid": "Europe/Paris"}, "date-time", "2026-05-15T10:00:00"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}],
                    ["summary", {}, "text", "Recurring master in Paris time"]
                  ],
                  []
                ],
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["recurrence-id", {}, "date-time", "2026-05-16T09:00:00Z"],
                    ["dtstart", {"tzid": "Europe/Paris"}, "date-time", "2026-05-16T11:00:00"],
                    ["dtend", {"tzid": "Europe/Paris"}, "date-time", "2026-05-16T12:00:00"],
                    ["summary", {}, "text", "Invalid UTC override"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the override id timezone form mismatch
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("RECURRENCE-ID date-time form (UTC) must match DTSTART date-time form (TZID:Europe/Paris)");
    }

    @Test
    void shouldRejectDuplicateOverrideId() {
        // Given two override VEVENTs use the same RECURRENCE-ID date-time for one UID
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When the object is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
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
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["recurrence-id", {}, "date-time", "2026-05-16T09:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-16T11:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-16T12:00:00Z"],
                    ["summary", {}, "text", "First override"]
                  ],
                  []
                ],
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["recurrence-id", {}, "date-time", "2026-05-16T09:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-16T13:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-16T14:00:00Z"],
                    ["summary", {}, "text", "Duplicate override"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the duplicate override id
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("Duplicate RECURRENCE-ID 2026-05-16T09:00:00Z for UID " + uid);
    }

    @Test
    void shouldRejectExDateWhenMismatchedValueType() {
        // Given EXDATE is DATE (2026-05-16) while DTSTART is DATE-TIME UTC
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-05-15T09:00:00Z"],
                    ["dtend", {}, "date-time", "2026-05-15T10:00:00Z"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}],
                    ["exdate", {}, "date", "2026-05-16"],
                    ["summary", {}, "text", "Recurring master with invalid EXDATE"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the EXDATE/DTSTART type mismatch
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("EXDATE value type (DATE) must match DTSTART value type (DATE-TIME)");
    }

    @Test
    void shouldRejectExDateWhenMismatchedTimezone() {
        // Given EXDATE is UTC date-time while DTSTART is Europe/Paris local date-time
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();

        // When it is PUT with strict handling
        DavResponse response = putStrictCalendarObject(user, uid, """
            ["vcalendar",
              [
                ["version", {}, "text", "2.0"],
                ["prodid", {}, "text", "-//Linagora//Twake Calendar//EN"]
              ],
              [
                ["vtimezone",
                  [
                    ["tzid", {}, "text", "Europe/Paris"]
                  ],
                  [
                    ["daylight",
                      [
                        ["tzoffsetfrom", {}, "utc-offset", "+01:00"],
                        ["tzoffsetto", {}, "utc-offset", "+02:00"],
                        ["tzname", {}, "text", "CEST"],
                        ["dtstart", {}, "date-time", "1970-03-29T02:00:00"],
                        ["rrule", {}, "recur", {"freq": "YEARLY", "bymonth": "3", "byday": "-1SU"}]
                      ],
                      []
                    ],
                    ["standard",
                      [
                        ["tzoffsetfrom", {}, "utc-offset", "+02:00"],
                        ["tzoffsetto", {}, "utc-offset", "+01:00"],
                        ["tzname", {}, "text", "CET"],
                        ["dtstart", {}, "date-time", "1970-10-25T03:00:00"],
                        ["rrule", {}, "recur", {"freq": "YEARLY", "bymonth": "10", "byday": "-1SU"}]
                      ],
                      []
                    ]
                  ]
                ],
                ["vevent",
                  [
                    ["uid", {}, "text", "{uid}"],
                    ["dtstamp", {}, "date-time", "2026-05-15T00:00:00Z"],
                    ["dtstart", {"tzid": "Europe/Paris"}, "date-time", "2026-05-15T09:00:00"],
                    ["dtend", {"tzid": "Europe/Paris"}, "date-time", "2026-05-15T10:00:00"],
                    ["rrule", {}, "recur", {"freq": "DAILY", "count": 3}],
                    ["exdate", {}, "date-time", "2026-05-16T09:00:00Z"],
                    ["summary", {}, "text", "Recurring master with invalid EXDATE"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid));

        // Then Sabre rejects the EXDATE timezone form mismatch
        assertThat(response.status()).isEqualTo(SC_BAD_REQUEST);
        assertThat(response.body())
            .contains("EXDATE date-time form (UTC) must match DTSTART date-time form (TZID:Europe/Paris)");
    }

    private DavResponse putStrictCalendarObject(OpenPaasUser user, String uid, String payload) {
        return dockerExtension().davHttpClient()
            .headers(headers -> {
                user.impersonatedBasicAuth(headers)
                    .add("Content-Type", "application/calendar+json")
                    .add("Prefer", "handling=strict");
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
