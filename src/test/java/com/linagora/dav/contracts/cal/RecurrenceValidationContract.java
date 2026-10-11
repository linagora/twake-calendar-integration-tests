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
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.hamcrest.Matchers.containsString;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;

public abstract class RecurrenceValidationContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaasUser user;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        user = dockerExtension().newTestUser();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/calendar+json")
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Prefer", "handling=strict");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void shouldStoreValidStrictObject() {
        // Given RRULE UNTIL, EXDATE, and RECURRENCE-ID all match DTSTART as UTC date-time values
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre stores the valid strict request
        .then()
            .statusCode(SC_CREATED);
    }

    @Test
    void shouldRejectOverrideWithoutMaster() {
        // Given a VEVENT with RECURRENCE-ID but no master VEVENT sharing its UID
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the orphan override
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("RECURRENCE-ID override for UID " + uid + " has no matching recurring master VEVENT (same UID with RRULE or RDATE)"));
    }

    @Test
    void shouldRejectUntilWhenMismatchedValueType() {
        // Given RRULE UNTIL is DATE (2026-05-17) while DTSTART is DATE-TIME UTC
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the UNTIL/DTSTART type mismatch
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("UNTIL value type (DATE) must match DTSTART value type (DATE-TIME)"));
    }

    @Test
    void shouldRejectRRuleWithCountAndUntil() {
        // Given RRULE defines both COUNT=3 and UNTIL=2026-05-17T09:00:00Z
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the RFC 5545 rule conflict
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("COUNT and UNTIL MUST NOT both be present in the same RRULE"));
    }

    @Test
    void shouldRejectOverrideIdWhenMismatchedValueType() {
        // Given RECURRENCE-ID is DATE (2026-05-16) while master DTSTART is DATE-TIME UTC
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the override id value type mismatch
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("RECURRENCE-ID value type (DATE) must match DTSTART value type (DATE-TIME)"));
    }

    @Test
    void shouldRejectOverrideIdWhenMismatchedTimezone() {
        // Given RECURRENCE-ID is UTC date-time while master DTSTART is Europe/Paris local date-time
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the override id timezone form mismatch
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("RECURRENCE-ID date-time form (UTC) must match DTSTART date-time form (TZID:Europe/Paris)"));
    }

    @Test
    void shouldRejectDuplicateOverrideId() {
        // Given two override VEVENTs use the same RECURRENCE-ID date-time for one UID
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When the object is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the duplicate override id
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("Duplicate RECURRENCE-ID 2026-05-16T09:00:00Z for UID " + uid));
    }

    @Test
    void shouldRejectExDateWhenMismatchedValueType() {
        // Given EXDATE is DATE (2026-05-16) while DTSTART is DATE-TIME UTC
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the EXDATE/DTSTART type mismatch
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("EXDATE value type (DATE) must match DTSTART value type (DATE-TIME)"));
    }

    @Test
    void shouldRejectExDateWhenMismatchedTimezone() {
        // Given EXDATE is UTC date-time while DTSTART is Europe/Paris local date-time
        String uid = UUID.randomUUID().toString();

        String payload = """
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
            """.replace("{uid}", uid);

        // When it is PUT with strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the EXDATE timezone form mismatch
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("EXDATE date-time form (UTC) must match DTSTART date-time form (TZID:Europe/Paris)"));
    }
}
