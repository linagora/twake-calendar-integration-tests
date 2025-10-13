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

import net.javacrumbs.jsonunit.core.Option;

public abstract class AlarmAMQPMessageContract {

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
    void shouldReceiveMessageFromEventAlarmCreatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:created", "");

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
            "30250411T110000",
            testUser.email());
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
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{organizerEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "import": false,
              "etag": "\\"ec763b1a6b9366b6e1b77b6c96ee5ad4\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{eventUid}", eventUid);

        assertThatJson(actual).when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][10][3]", "etag") // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventAlarmRequestExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:request", "");

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
            "30250411T110000",
            testUser.email());
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
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{attendeeEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "etag": "\\"21f6a9b87e59d8e717b81c4ce26dbd08\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][10][3]", "etag")   // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventAlarmUpdatedExchangeWhenUpdateEvent() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:updated", "");

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
            "30250411T110000",
            testUser.email());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T150000",
            "30250411T160000",
            testUser.email());
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
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{organizerEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
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
                          "cn": "Benoît TELLIER",
                          "schedule-status": "${json-unit.ignore}"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{organizerEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "etag": "\\"bf2b9ec186809915740081fb3261d413\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][10][3]", "old_event[1][1][3]", "old_event[2][1][1][10][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventAlarmRequestExchangeWhenUpdateEvent() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:request", "");

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
            "30250411T110000",
            testUser.email());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            testUser.email());
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

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
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{attendeeEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "etag": "\\"9e90bfe6eb0d435029f233eadafd029d\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][10][3]", "etag")   // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventAlarmUpdatedExchangeWhenAccept() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:updated", "");

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
            "30250411T110000",
            testUser.email());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            testUser2.email(),
            "ACCEPTED");
        calDavClient.upsertCalendarEvent(testUser2, attendeeEventId, updatedCalendarData);

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
                          "partstat": "ACCEPTED",
                          "cn": "Benoît TELLIER"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{attendeeEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
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
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{attendeeEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "etag": "\\"5bac12ebeb3b621374468f691ec67c26\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][10][3]", "old_event[1][1][3]", "old_event[2][1][1][10][3]", "etag")  // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventAlarmDeletedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:deleted", "");

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
            "30250411T110000",
            testUser.email());
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
                          "cn": "Benoît TELLIER",
                          "schedule-status": "${json-unit.ignore}"
                        },
                        "cal-address",
                        "mailto:{attendeeEmail}"
                      ],
                      [
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
                      ],
                      [
                        "dtstamp",
                        {},
                        "date-time",
                        "3025-04-11T02:20:32Z"
                      ]
                    ],
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{organizerEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "import": false
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{eventUid}", eventUid);

        assertThatJson(actual).when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][10][3]", "etag")   // ignore prodid, dtstamp and etag
            .isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromEventAlarmCancelExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:cancel", "");

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
            "30250411T110000",
            testUser.email());
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
                        "attendee",
                        {
                          "partstat": "ACCEPTED",
                          "rsvp": "FALSE",
                          "role": "CHAIR",
                          "cutype": "INDIVIDUAL"
                        },
                        "cal-address",
                        "mailto:{organizerEmail}"
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
                    [
                      [
                        "valarm",
                        [
                          [
                            "trigger",
                            {},
                            "duration",
                            "-PT10M"
                          ],
                          [
                            "action",
                            {},
                            "text",
                            "EMAIL"
                          ],
                          [
                            "attendee",
                            {},
                            "cal-address",
                            "mailto:{attendeeEmail}"
                          ],
                          [
                            "summary",
                            {},
                            "text",
                            "test alarm"
                          ],
                          [
                            "description",
                            {},
                            "text",
                            "This is an automatic alarm sent by OpenPaas"
                          ]
                        ],
                        []
                      ]
                    ]
                  ]
                ]
              ],
              "etag": "\\"be8cbbbd2134984703bea3331280b56c\\""
            }
            """.replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{organizerId}", testUser.id())
            .replace("{attendeeId}", testUser2.id())
            .replace("{eventUid}", eventUid)
            .replace("{attendeeEventId}", attendeeEventId);

        assertThatJson(actual)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("event[1][1][3]", "event[2][1][1][9][3]", "etag")    // ignore prodid, dtstamp and etag
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
                                        String dtend,
                                        String alarmAttendeeEmail) {
        return generateCalendarData(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, alarmAttendeeEmail, "NEEDS-ACTION");
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String alarmAttendeeEmail,
                                        String partStat) {

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
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
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            BEGIN:VALARM
            TRIGGER:-PT10M
            ACTION:EMAIL
            ATTENDEE:mailto:{alarmAttendeeEmail}
            SUMMARY:test alarm
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary)
            .replace("{location}", location)
            .replace("{description}", description)
            .replace("{dtstart}", dtstart)
            .replace("{dtend}", dtend)
            .replace("{alarmAttendeeEmail}", alarmAttendeeEmail)
            .replace("{partStat}", partStat);
    }
}
