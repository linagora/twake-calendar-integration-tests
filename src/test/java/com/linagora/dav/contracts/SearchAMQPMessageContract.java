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

package com.linagora.dav.contracts;

import static com.linagora.dav.DockerTwakeCalendarExtension.QUEUE_NAME;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

public abstract class SearchAMQPMessageContract {

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
    }

    @Test
    void shouldReceiveMessageFromEventCreatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:created", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
               "eventPath": "/calendars/{organizerId}/{organizerId}/{eventUid}.ics",
               "event": [
                 "vcalendar",
                 [
                   [
                     "version",
                     {},
                     "text",
                     "2.0"
                   ],
                   [
                     "prodid",
                     {},
                     "text",
                     "-//Sabre//Sabre VObject 4.1.3//EN"
                   ]
                 ],
                 [
                   [
                     "vtimezone",
                     [
                       [
                         "tzid",
                         {},
                         "text",
                         "Asia/Ho_Chi_Minh"
                       ]
                     ],
                     [
                       [
                         "standard",
                         [
                           [
                             "tzoffsetfrom",
                             {},
                             "utc-offset",
                             "+07:00"
                           ],
                           [
                             "tzoffsetto",
                             {},
                             "utc-offset",
                             "+07:00"
                           ],
                           [
                             "tzname",
                             {},
                             "text",
                             "ICT"
                           ],
                           [
                             "dtstart",
                             {},
                             "date-time",
                             "1970-01-01T00:00:00"
                           ]
                         ],
                         []
                       ]
                     ]
                   ],
                   [
                     "vevent",
                     [
                       [
                         "uid",
                         {},
                         "text",
                         "{eventUid}"
                       ],
                       [
                         "sequence",
                         {},
                         "integer",
                         1
                       ],
                       [
                         "dtstart",
                         {
                           "tzid": "Asia/Ho_Chi_Minh"
                         },
                         "date-time",
                         "3025-04-11T10:00:00"
                       ],
                       [
                         "dtend",
                         {
                           "tzid": "Asia/Ho_Chi_Minh"
                         },
                         "date-time",
                         "3025-04-11T11:00:00"
                       ],
                       [
                         "summary",
                         {},
                         "text",
                         "Sprint planning #01"
                       ],
                       [
                         "location",
                         {},
                         "text",
                         "Twake Meeting Room"
                       ],
                       [
                         "description",
                         {},
                         "text",
                         "This is a meeting to discuss the sprint planning for the next week."
                       ],
                       [
                         "organizer",
                         {
                           "cn": "Van Tung TRAN"
                         },
                         "cal-address",
                         "mailto:{organizerEmail}"
                       ],
                       [
                         "attendee",
                         {
                           "partstat": "NEEDS-ACTION",
                           "cn": "Benoît TELLIER",
                           "schedule-status": "1.1"
                         },
                         "cal-address",
                         "mailto:{attendeeEmail}"
                       ],
                       [
                         "dtstamp",
                         {},
                         "date-time",
                         "3025-04-11T02:20:32Z"
                       ]
                     ],
                     []
                   ]
                 ]
               ],
               "import": false,
               "etag": "\\"310e05638467e7d20c74d637ef7329fa\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{eventUid}", eventUid);

        assertThatJson(actual).whenIgnoringPaths("event[1][1][3]", "event[2][1][1][9][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventUpdatedExchange() throws ParseException, IOException {
        dockerExtension().getChannel().queueDeclare(QUEUE_NAME, false, true, true, null);
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:updated", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #02",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next 2 weeks.",
            "30250411T150000",
            "30250411T160000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "eventPath": "/calendars/{organizerId}/{organizerId}/{eventUid}.ics",
              "event": [
                "vcalendar",
                [
                  [
                    "version",
                    {},
                    "text",
                    "2.0"
                  ],
                  [
                    "prodid",
                    {},
                    "text",
                    "-//Sabre//Sabre VObject 4.1.3//EN"
                  ]
                ],
                [
                  [
                    "vtimezone",
                    [
                      [
                        "tzid",
                        {},
                        "text",
                        "Asia/Ho_Chi_Minh"
                      ]
                    ],
                    [
                      [
                        "standard",
                        [
                          [
                            "tzoffsetfrom",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzoffsetto",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzname",
                            {},
                            "text",
                            "ICT"
                          ],
                          [
                            "dtstart",
                            {},
                            "date-time",
                            "1970-01-01T00:00:00"
                          ]
                        ],
                        []
                      ]
                    ]
                  ],
                  [
                    "vevent",
                    [
                      [
                        "uid",
                        {},
                        "text",
                        "{eventUid}"
                      ],
                      [
                        "sequence",
                        {},
                        "integer",
                        1
                      ],
                      [
                        "dtstart",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T15:00:00"
                      ],
                      [
                        "dtend",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T16:00:00"
                      ],
                      [
                        "summary",
                        {},
                        "text",
                        "Sprint planning #02"
                      ],
                      [
                        "location",
                        {},
                        "text",
                        "Twake Meeting Room 2"
                      ],
                      [
                        "description",
                        {},
                        "text",
                        "This is a meeting to discuss the sprint planning for the next 2 weeks."
                      ],
                      [
                        "organizer",
                        {
                          "cn": "Van Tung TRAN"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "NEEDS-ACTION",
                          "cn": "Benoît TELLIER",
                          "schedule-status": "1.1"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    []
                  ]
                ]
              ],
              "import": false,
              "old_event": [
                "vcalendar",
                [
                  [
                    "version",
                    {},
                    "text",
                    "2.0"
                  ],
                  [
                    "prodid",
                    {},
                    "text",
                    "-//Sabre//Sabre VObject 4.1.3//EN"
                  ]
                ],
                [
                  [
                    "vtimezone",
                    [
                      [
                        "tzid",
                        {},
                        "text",
                        "Asia/Ho_Chi_Minh"
                      ]
                    ],
                    [
                      [
                        "standard",
                        [
                          [
                            "tzoffsetfrom",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzoffsetto",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzname",
                            {},
                            "text",
                            "ICT"
                          ],
                          [
                            "dtstart",
                            {},
                            "date-time",
                            "1970-01-01T00:00:00"
                          ]
                        ],
                        []
                      ]
                    ]
                  ],
                  [
                    "vevent",
                    [
                      [
                        "uid",
                        {},
                        "text",
                        "{eventUid}"
                      ],
                      [
                        "sequence",
                        {},
                        "integer",
                        1
                      ],
                      [
                        "dtstart",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T10:00:00"
                      ],
                      [
                        "dtend",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T11:00:00"
                      ],
                      [
                        "summary",
                        {},
                        "text",
                        "Sprint planning #01"
                      ],
                      [
                        "location",
                        {},
                        "text",
                        "Twake Meeting Room"
                      ],
                      [
                        "description",
                        {},
                        "text",
                        "This is a meeting to discuss the sprint planning for the next week."
                      ],
                      [
                        "organizer",
                        {
                          "cn": "Van Tung TRAN"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "NEEDS-ACTION",
                          "cn": "Benoît TELLIER",
                          "schedule-status": "1.1"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    []
                  ]
                ]
              ],
              "etag": "\\"d02e67b014b69f636f8e3180b1ae894c\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{eventUid}", eventUid);

        JSONObject actualJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(actual);
        JSONObject expectedJson = (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(expected);
        actualJson.remove("etag");
        expectedJson.remove("etag");

        assertThatJson(actual)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][9][3]", "old_event[1][1][3]", "old_event[2][1][1][9][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventDeletedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:deleted", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.deleteCalendarEvent(testUser, eventUid);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "eventPath": "/calendars/{organizerId}/{organizerId}/{eventUid}.ics",
              "event": [
                "vcalendar",
                [
                  [
                    "version",
                    {},
                    "text",
                    "2.0"
                  ],
                  [
                    "prodid",
                    {},
                    "text",
                    "-//Sabre//Sabre VObject 4.1.3//EN"
                  ]
                ],
                [
                  [
                    "vtimezone",
                    [
                      [
                        "tzid",
                        {},
                        "text",
                        "Asia/Ho_Chi_Minh"
                      ]
                    ],
                    [
                      [
                        "standard",
                        [
                          [
                            "tzoffsetfrom",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzoffsetto",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzname",
                            {},
                            "text",
                            "ICT"
                          ],
                          [
                            "dtstart",
                            {},
                            "date-time",
                            "1970-01-01T00:00:00"
                          ]
                        ],
                        []
                      ]
                    ]
                  ],
                  [
                    "vevent",
                    [
                      [
                        "uid",
                        {},
                        "text",
                        "{eventUid}"
                      ],
                      [
                        "sequence",
                        {},
                        "integer",
                        1
                      ],
                      [
                        "dtstart",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T10:00:00"
                      ],
                      [
                        "dtend",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T11:00:00"
                      ],
                      [
                        "summary",
                        {},
                        "text",
                        "Sprint planning #01"
                      ],
                      [
                        "location",
                        {},
                        "text",
                        "Twake Meeting Room"
                      ],
                      [
                        "description",
                        {},
                        "text",
                        "This is a meeting to discuss the sprint planning for the next week."
                      ],
                      [
                        "organizer",
                        {
                          "cn": "Van Tung TRAN"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "NEEDS-ACTION",
                          "cn": "Benoît TELLIER",
                          "schedule-status": "1.1"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    []
                  ]
                ]
              ],
              "import": false
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{eventUid}", eventUid);

        assertThatJson(actual).whenIgnoringPaths("event[1][1][3]", "event[2][1][1][9][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventCancelExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:cancel", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        calDavClient.deleteCalendarEvent(testUser, eventUid);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics",
              "event": [
                "vcalendar",
                [
                  [
                    "version",
                    {},
                    "text",
                    "2.0"
                  ],
                  [
                    "prodid",
                    {},
                    "text",
                    "-//Sabre//Sabre VObject 4.1.3//EN"
                  ],
                  [
                    "calscale",
                    {},
                    "text",
                    "GREGORIAN"
                  ]
                ],
                [
                  [
                    "vtimezone",
                    [
                      [
                        "tzid",
                        {},
                        "text",
                        "Asia/Ho_Chi_Minh"
                      ]
                    ],
                    [
                      [
                        "standard",
                        [
                          [
                            "tzoffsetfrom",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzoffsetto",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzname",
                            {},
                            "text",
                            "ICT"
                          ],
                          [
                            "dtstart",
                            {},
                            "date-time",
                            "1970-01-01T00:00:00"
                          ]
                        ],
                        []
                      ]
                    ]
                  ],
                  [
                    "vevent",
                    [
                      [
                        "uid",
                        {},
                        "text",
                        "{eventUid}"
                      ],
                      [
                        "dtstart",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T10:00:00"
                      ],
                      [
                        "dtend",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T11:00:00"
                      ],
                      [
                        "summary",
                        {},
                        "text",
                        "Sprint planning #01"
                      ],
                      [
                        "location",
                        {},
                        "text",
                        "Twake Meeting Room"
                      ],
                      [
                        "description",
                        {},
                        "text",
                        "This is a meeting to discuss the sprint planning for the next week."
                      ],
                      [
                        "organizer",
                        {
                          "cn": "Van Tung TRAN"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "NEEDS-ACTION",
                          "cn": "Benoît TELLIER"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ],
                      [
                        "status",
                        {},
                        "text",
                        "CANCELLED"
                      ],
                      [
                        "sequence",
                        {},
                        "integer",
                        2
                      ]
                    ],
                    []
                  ]
                ]
              ],
              "etag": "\\"205c39920848af791872393bfd0e68ad\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual).whenIgnoringPaths("event[1][1][3]", "event[2][1][1][8][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventRequestExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:request", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "eventPath": "/calendars/{attendeeId}/{attendeeId}/{attendeeEventId}.ics",
              "event": [
                "vcalendar",
                [
                  [
                    "version",
                    {},
                    "text",
                    "2.0"
                  ],
                  [
                    "prodid",
                    {},
                    "text",
                    "-//Sabre//Sabre VObject 4.1.3//EN"
                  ],
                  [
                    "calscale",
                    {},
                    "text",
                    "GREGORIAN"
                  ]
                ],
                [
                  [
                    "vtimezone",
                    [
                      [
                        "tzid",
                        {},
                        "text",
                        "Asia/Ho_Chi_Minh"
                      ]
                    ],
                    [
                      [
                        "standard",
                        [
                          [
                            "tzoffsetfrom",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzoffsetto",
                            {},
                            "utc-offset",
                            "+07:00"
                          ],
                          [
                            "tzname",
                            {},
                            "text",
                            "ICT"
                          ],
                          [
                            "dtstart",
                            {},
                            "date-time",
                            "1970-01-01T00:00:00"
                          ]
                        ],
                        []
                      ]
                    ]
                  ],
                  [
                    "vevent",
                    [
                      [
                        "uid",
                        {},
                        "text",
                        "{eventUid}"
                      ],
                      [
                        "sequence",
                        {},
                        "integer",
                        1
                      ],
                      [
                        "dtstart",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T10:00:00"
                      ],
                      [
                        "dtend",
                        {
                          "tzid": "Asia/Ho_Chi_Minh"
                        },
                        "date-time",
                        "3025-04-11T11:00:00"
                      ],
                      [
                        "summary",
                        {},
                        "text",
                        "Sprint planning #01"
                      ],
                      [
                        "location",
                        {},
                        "text",
                        "Twake Meeting Room"
                      ],
                      [
                        "description",
                        {},
                        "text",
                        "This is a meeting to discuss the sprint planning for the next week."
                      ],
                      [
                        "organizer",
                        {
                          "cn": "Van Tung TRAN"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "NEEDS-ACTION",
                          "cn": "Benoît TELLIER"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    []
                  ]
                ]
              ],
              "etag": "\\"dde2448d7dd60033f9fc7e9ad1169f56\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual).whenIgnoringPaths("event[1][1][3]", "event[2][1][1][9][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    private byte[] getMessageFromQueue() {
        return awaitAtMost.atMost(Duration.ofSeconds(20))
            .until(() -> dockerExtension().getChannel().basicGet(QUEUE_NAME, true), Objects::nonNull)
            .getBody();
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend) {

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            LOCATION:{location}
            DESCRIPTION:{description}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary)
            .replace("{location}", location)
            .replace("{description}", description)
            .replace("{dtstart}", dtstart)
            .replace("{dtend}", dtend);
    }
}
