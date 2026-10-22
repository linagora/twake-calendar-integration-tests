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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;

/**
 * iCalendar rules validated on every write, without the
 * "Prefer: handling=strict" header, as Twake Calendar does not send it.
 */
public abstract class CalendarObjectValidationContract {
    private static final String DTEND_BEFORE_DTSTART_MESSAGE = "DTEND must not be before DTSTART";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaasUser user;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        user = dockerExtension().newTestUser();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/calendar+json")
            .header("Authorization", user.impersonatedBasicAuth());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void shouldRejectAllDayIcsEventWhenDtEndBeforeDtStart() {
        // Given an all-day iCalendar event whose DTEND (2026-10-19) precedes DTSTART (2026-10-22)
        String uid = UUID.randomUUID().toString();

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//QA//EN
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261019T120000Z
            SUMMARY:Backwards
            DTSTART;VALUE=DATE:20261022
            DTEND;VALUE=DATE:20261019
            END:VEVENT
            END:VCALENDAR
            """.replace("{uid}", uid).replace("\n", "\r\n");

        // When it is PUT without strict handling
        given()
            .contentType("text/calendar; charset=utf-8")
            .body(ics.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the inverted range
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString(DTEND_BEFORE_DTSTART_MESSAGE));
    }

    @Test
    void shouldRejectAllDayEventWhenDtEndBeforeDtStart() {
        // Given an all-day jCal event whose DTEND (2026-10-19) precedes DTSTART (2026-10-22)
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
                    ["dtstamp", {}, "date-time", "2026-10-19T12:00:00Z"],
                    ["dtstart", {}, "date", "2026-10-22"],
                    ["dtend", {}, "date", "2026-10-19"],
                    ["summary", {}, "text", "Backwards all-day event"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid);

        // When it is PUT without strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the inverted range
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString(DTEND_BEFORE_DTSTART_MESSAGE));
    }

    @Test
    void shouldRejectTimedEventWhenDtEndBeforeDtStart() {
        // Given a timed Asia/Ho_Chi_Minh event whose DTEND (08:00) precedes DTSTART (10:00)
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
                    ["dtstamp", {}, "date-time", "2026-10-21T03:30:48Z"],
                    ["dtstart", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2026-10-22T10:00:00"],
                    ["dtend", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2026-10-22T08:00:00"],
                    ["summary", {}, "text", "Backwards timed event"]
                  ],
                  []
                ],
                ["vtimezone",
                  [
                    ["tzid", {}, "text", "Asia/Ho_Chi_Minh"]
                  ],
                  [
                    ["standard",
                      [
                        ["tzoffsetfrom", {}, "utc-offset", "+07:00"],
                        ["tzoffsetto", {}, "utc-offset", "+07:00"],
                        ["tzname", {}, "text", "ICT"],
                        ["dtstart", {}, "date-time", "1970-01-01T00:00:00"]
                      ],
                      []
                    ]
                  ]
                ]
              ]
            ]
            """.replace("{uid}", uid);

        // When it is PUT without strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the inverted range
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString(DTEND_BEFORE_DTSTART_MESSAGE));
    }

    @Test
    void shouldRejectOverrideWhenDtEndBeforeDtStart() {
        // Given a valid recurring master and an override whose DTEND (10:00Z) precedes DTSTART (11:00Z)
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
                    ["dtend", {}, "date-time", "2026-05-16T10:00:00Z"],
                    ["summary", {}, "text", "Backwards override"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid);

        // When it is PUT without strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre rejects the inverted range of the override
        .then()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString(DTEND_BEFORE_DTSTART_MESSAGE));
    }

    @Test
    void shouldStoreEventWhenDtEndEqualsDtStart() {
        // Given an instantaneous event whose DTEND equals DTSTART
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
                    ["dtstamp", {}, "date-time", "2026-10-21T00:00:00Z"],
                    ["dtstart", {}, "date-time", "2026-10-22T10:00:00Z"],
                    ["dtend", {}, "date-time", "2026-10-22T10:00:00Z"],
                    ["summary", {}, "text", "Instantaneous event"]
                  ],
                  []
                ]
              ]
            ]
            """.replace("{uid}", uid);

        // When it is PUT without strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre stores it
        .then()
            .statusCode(SC_CREATED);
    }

    @Test
    void shouldStoreEventWhenDtEndInOtherTimezoneIsAfterDtStart() {
        // Given DTSTART 10:00 Asia/Ho_Chi_Minh (03:00Z) and DTEND 04:00Z: earlier wall-clock time, later instant
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
                    ["dtstamp", {}, "date-time", "2026-10-21T00:00:00Z"],
                    ["dtstart", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "2026-10-22T10:00:00"],
                    ["dtend", {}, "date-time", "2026-10-22T04:00:00Z"],
                    ["summary", {}, "text", "Cross timezone event"]
                  ],
                  []
                ],
                ["vtimezone",
                  [
                    ["tzid", {}, "text", "Asia/Ho_Chi_Minh"]
                  ],
                  [
                    ["standard",
                      [
                        ["tzoffsetfrom", {}, "utc-offset", "+07:00"],
                        ["tzoffsetto", {}, "utc-offset", "+07:00"],
                        ["tzname", {}, "text", "ICT"],
                        ["dtstart", {}, "date-time", "1970-01-01T00:00:00"]
                      ],
                      []
                    ]
                  ]
                ]
              ]
            ]
            """.replace("{uid}", uid);

        // When it is PUT without strict handling
        given()
            .body(payload)
        .when()
            .put("/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics")
        // Then Sabre compares instants, not wall-clock values, and stores it
        .then()
            .statusCode(SC_CREATED);
    }
}
