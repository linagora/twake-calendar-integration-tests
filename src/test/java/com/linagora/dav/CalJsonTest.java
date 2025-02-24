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

package com.linagora.dav;

import static com.linagora.dav.CalDavTest.ICS_1;
import static com.linagora.dav.DockerOpenPaasExtension.body;
import static com.linagora.dav.DockerOpenPaasExtension.execute;
import static com.linagora.dav.DockerOpenPaasExtension.executeNoContent;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class CalJsonTest {
    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    @BeforeEach
    void setUp() {
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri("http://" + TestContainersUtils.getContainerPrivateIpAddress(DockerOpenPaasSetupSingleton.singleton.getSabreDavContainer()) + ":80")
            .build();
    }

    @Test
    void shouldListCalendar() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        String response = given()
            .headers("Authorization", testUser.basicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + testUser.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:calendar": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/%s.json"
                                    }
                                },
                                "dav:name": "#default",
                                "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                                "invite": [
                                    {
                                        "href": "principals/users/%s",
                                        "principal": "principals/users/%s",
                                        "properties": [],
                                        "access": 1,
                                        "comment": null,
                                        "inviteStatus": 2
                                    }
                                ],
                                "acl": [
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-read",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                        "principal": "{DAV:}authenticated",
                                        "protected": true
                                    }
                                ]
                            }
                        ]
                    }
                }
             """, testUser.id(), testUser.id(), testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id(), testUser.id()));
    }

    @Test
    void shouldListEvents() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        String response = given()
            .headers("Authorization", testUser.basicAuth())
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + testUser.id() + "/events.json")
        .then()
            .extract()
            .body()
            .asString();


        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                            "_links": {
                                "self": {
                                    "href": "/calendars/%s/%s.json"
                                }
                            },
                            "dav:name": "#default",
                            "calendarserver:ctag": "http://sabre.io/ns/sync/2",
                            "invite": [
                                {
                                    "href": "principals/users/%s",
                                    "principal": "principals/users/%s",
                                    "properties": [],
                                    "access": 1,
                                    "comment": null,
                                    "inviteStatus": 2
                                }
                            ],
                            "acl": [
                                {
                                    "privilege": "{DAV:}share",
                                    "principal": "principals/users/%s",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}share",
                                    "principal": "principals/users/%s/calendar-proxy-write",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}write",
                                    "principal": "principals/users/%s",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}write",
                                    "principal": "principals/users/%s/calendar-proxy-write",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}write-properties",
                                    "principal": "principals/users/%s",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}write-properties",
                                    "principal": "principals/users/%s/calendar-proxy-write",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}read",
                                    "principal": "principals/users/%s",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}read",
                                    "principal": "principals/users/%s/calendar-proxy-read",
                                    "protected": true
                                },
                                {
                                    "privilege": "{DAV:}read",
                                    "principal": "principals/users/%s/calendar-proxy-write",
                                    "protected": true
                                },
                                {
                                    "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                    "principal": "{DAV:}authenticated",
                                    "protected": true
                                }
                            ]
                        }
             """, testUser.id(), testUser.id(), testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id()));
    }

    @Test
    void reportShouldListEvents() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/json, text/plain, */*"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20250201T000000","end":"20250215T000000"}}""")));

        assertThatJson(response.body())
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:item": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/%s/abcd.ics"
                                    }
                                },
                                "etag": "\\"8c97fb06c60212c47d46a9c8c0f625ef\\"",
                                "data": [
                                    "vcalendar",
                                    [
                                        [
                                            "prodid",
                                            {},
                                            "text",
                                            "-//Sabre//Sabre VObject 4.1.3//EN"
                                        ],
                                        [
                                            "version",
                                            {},
                                            "text",
                                            "2.0"
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
                                            "vevent",
                                            [
                                                [
                                                    "uid",
                                                    {},
                                                    "text",
                                                    "47d90176-b477-4fe1-91b3-a36ec0cfc67b"
                                                ],
                                                [
                                                    "transp",
                                                    {},
                                                    "text",
                                                    "OPAQUE"
                                                ],
                                                [
                                                    "dtstart",
                                                    {},
                                                    "date-time",
                                                    "2025-02-14T10:00:00Z"
                                                ],
                                                [
                                                    "dtend",
                                                    {},
                                                    "date-time",
                                                    "2025-02-14T10:45:00Z"
                                                ],
                                                [
                                                    "class",
                                                    {},
                                                    "text",
                                                    "PUBLIC"
                                                ],
                                                [
                                                    "x-openpaas-videoconference",
                                                    {},
                                                    "unknown",
                                                    ""
                                                ],
                                                [
                                                    "summary",
                                                    {},
                                                    "text",
                                                    "OW2con'25"
                                                ],
                                                [
                                                    "description",
                                                    {},
                                                    "text",
                                                    "Avoir un draft de prêt"
                                                ],
                                                [
                                                    "location",
                                                    {},
                                                    "text",
                                                    "https://jitsi.linagora.com/ow2"
                                                ],
                                                [
                                                    "organizer",
                                                    {
                                                        "cn": "Julie VERRIER"
                                                    },
                                                    "cal-address",
                                                    "mailto:jverrier@linagora.com"
                                                ],
                                                [
                                                    "attendee",
                                                    {
                                                        "partstat": "NEEDS-ACTION",
                                                        "rsvp": "TRUE",
                                                        "role": "REQ-PARTICIPANT",
                                                        "cutype": "INDIVIDUAL",
                                                        "cn": "Alexandre PUJOL"
                                                    },
                                                    "cal-address",
                                                    "mailto:apujol@linagora.com"
                                                ],
                                                [
                                                    "attendee",
                                                    {
                                                        "partstat": "NEEDS-ACTION",
                                                        "rsvp": "TRUE",
                                                        "role": "REQ-PARTICIPANT",
                                                        "cutype": "INDIVIDUAL",
                                                        "cn": "Benoît TELLIER"
                                                    },
                                                    "cal-address",
                                                    "mailto:btellier@linagora.com"
                                                ],
                                                [
                                                    "attendee",
                                                    {
                                                        "partstat": "NEEDS-ACTION",
                                                        "rsvp": "TRUE",
                                                        "role": "REQ-PARTICIPANT",
                                                        "cutype": "INDIVIDUAL",
                                                        "cn": "Xavier GUIMARD"
                                                    },
                                                    "cal-address",
                                                    "mailto:xguimard@linagora.com"
                                                ],
                                                [
                                                    "attendee",
                                                    {
                                                        "partstat": "NEEDS-ACTION",
                                                        "rsvp": "TRUE",
                                                        "role": "REQ-PARTICIPANT",
                                                        "cutype": "INDIVIDUAL",
                                                        "cn": "Frédéric HERMELIN"
                                                    },
                                                    "cal-address",
                                                    "mailto:fhermelin@linagora.com"
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
                                                    "mailto:jverrier@linagora.com"
                                                ],
                                                [
                                                    "dtstamp",
                                                    {},
                                                    "date-time",
                                                    "2025-02-05T17:05:16Z"
                                                ],
                                                [
                                                    "sequence",
                                                    {},
                                                    "integer",
                                                    0
                                                ]
                                            ],
                                            []
                                        ]
                                    ]
                                ],
                                "status": 200
                            }
                        ]
                    }
                }
                    """, testUser.id(), testUser.id(),
                testUser.id(), testUser.id()));
    }

    @Test
    void putShouldCreateEvents() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", testUser.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20250224T000000","end":"20250315T000000"}}""")));

        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .whenIgnoringPaths("_embedded.dav:item[0].etag")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:item": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/%s/1111632f-9917-4efc-b78f-243be5403074"
                                    }
                                },
                                "etag": "\\"eee24d9d9177f99bf06df068bd4937d3\\"",
                                "data": [
                                    "vcalendar",
                                    [
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
                                        ],
                                        [
                                            "version",
                                            {},
                                            "text",
                                            "2.0"
                                        ]
                                    ],
                                    [
                                        [
                                            "vevent",
                                            [
                                                [
                                                    "uid",
                                                    {},
                                                    "text",
                                                    "1111632f-9917-4efc-b78f-243be5403074"
                                                ],
                                                [
                                                    "transp",
                                                    {},
                                                    "text",
                                                    "OPAQUE"
                                                ],
                                                [
                                                    "dtstart",
                                                    {},
                                                    "date",
                                                    "2025-03-01"
                                                ],
                                                [
                                                    "dtend",
                                                    {},
                                                    "date",
                                                    "2025-03-02"
                                                ],
                                                [
                                                    "class",
                                                    {},
                                                    "text",
                                                    "PUBLIC"
                                                ],
                                                [
                                                    "x-openpaas-videoconference",
                                                    {},
                                                    "unknown",
                                                    ""
                                                ],
                                                [
                                                    "summary",
                                                    {},
                                                    "text",
                                                    "USTH"
                                                ],
                                                [
                                                    "organizer",
                                                    {
                                                        "cn": "Benoît TELLIER"
                                                    },
                                                    "cal-address",
                                                    "mailto:btellier@linagora.com"
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
                                                    "mailto:btellier@linagora.com"
                                                ]
                                            ],
                                            []
                                        ]
                                    ]
                                ],
                                "status": 200
                            }
                        ]
                    }
                }""", testUser.id(), testUser.id(),
                testUser.id(), testUser.id()));
    }

    @Test
    void putShouldUpdateEvents() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", testUser.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        given()
            .headers("Authorization", testUser.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH 2\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(204);

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20250224T000000","end":"20250315T000000"}}""")));

        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .whenIgnoringPaths("_embedded.dav:item[0].etag")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:item": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/%s/1111632f-9917-4efc-b78f-243be5403074"
                                    }
                                },
                                "etag": "\\"eee24d9d9177f99bf06df068bd4937d3\\"",
                                "data": [
                                    "vcalendar",
                                    [
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
                                        ],
                                        [
                                            "version",
                                            {},
                                            "text",
                                            "2.0"
                                        ]
                                    ],
                                    [
                                        [
                                            "vevent",
                                            [
                                                [
                                                    "uid",
                                                    {},
                                                    "text",
                                                    "1111632f-9917-4efc-b78f-243be5403074"
                                                ],
                                                [
                                                    "transp",
                                                    {},
                                                    "text",
                                                    "OPAQUE"
                                                ],
                                                [
                                                    "dtstart",
                                                    {},
                                                    "date",
                                                    "2025-03-01"
                                                ],
                                                [
                                                    "dtend",
                                                    {},
                                                    "date",
                                                    "2025-03-02"
                                                ],
                                                [
                                                    "class",
                                                    {},
                                                    "text",
                                                    "PUBLIC"
                                                ],
                                                [
                                                    "x-openpaas-videoconference",
                                                    {},
                                                    "unknown",
                                                    ""
                                                ],
                                                [
                                                    "summary",
                                                    {},
                                                    "text",
                                                    "USTH 2"
                                                ],
                                                [
                                                    "organizer",
                                                    {
                                                        "cn": "Benoît TELLIER"
                                                    },
                                                    "cal-address",
                                                    "mailto:btellier@linagora.com"
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
                                                    "mailto:btellier@linagora.com"
                                                ]
                                            ],
                                            []
                                        ]
                                    ]
                                ],
                                "status": 200
                            }
                        ]
                    }
                }""", testUser.id(), testUser.id(),
                testUser.id(), testUser.id()));
    }

    @Test
    void putShouldFailUpdateWhenBadTag() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", testUser.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        given()
            .headers("Authorization", testUser.basicAuth())
            .header("If-Match", "bad")
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH 2\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(412);
    }

    @Test
    void putShouldUpdateWhenGoodTag() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", testUser.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);


        String etag = given()
            .headers("Authorization", testUser.basicAuth())
        .when()
            .get("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .extract()
            .header("Etag");

        given()
            .headers("Authorization", testUser.basicAuth())
            .header("If-Match", etag)
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH 2\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(204);
    }

    @Test
    void getShouldShowEventDetail() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", testUser.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        String body = given()
            .headers("Authorization", testUser.basicAuth())
            .header("Accept", "application/calendar+json")
        .when()
            .get("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .isEqualTo(String.format("""
                [
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
                                    "Europe/Paris"
                                ]
                            ],
                            [
                                [
                                    "daylight",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+01:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+02:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "CEST"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-03-29T02:00:00"
                                        ],
                                        [
                                            "rrule",
                                            {},
                                            "recur",
                                            {
                                                "freq": "YEARLY",
                                                "bymonth": "3",
                                                "byday": "-1SU"
                                            }
                                        ]
                                    ],
                                    []
                                ],
                                [
                                    "standard",
                                    [
                                        [
                                            "tzoffsetfrom",
                                            {},
                                            "utc-offset",
                                            "+02:00"
                                        ],
                                        [
                                            "tzoffsetto",
                                            {},
                                            "utc-offset",
                                            "+01:00"
                                        ],
                                        [
                                            "tzname",
                                            {},
                                            "text",
                                            "CET"
                                        ],
                                        [
                                            "dtstart",
                                            {},
                                            "date-time",
                                            "1970-10-25T03:00:00"
                                        ],
                                        [
                                            "rrule",
                                            {},
                                            "recur",
                                            {
                                                "freq": "YEARLY",
                                                "bymonth": "10",
                                                "byday": "-1SU"
                                            }
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
                                    "1111632f-9917-4efc-b78f-243be5403074"
                                ],
                                [
                                    "transp",
                                    {},
                                    "text",
                                    "OPAQUE"
                                ],
                                [
                                    "dtstart",
                                    {},
                                    "date",
                                    "2025-03-01"
                                ],
                                [
                                    "dtend",
                                    {},
                                    "date",
                                    "2025-03-02"
                                ],
                                [
                                    "class",
                                    {},
                                    "text",
                                    "PUBLIC"
                                ],
                                [
                                    "x-openpaas-videoconference",
                                    {},
                                    "unknown",
                                    ""
                                ],
                                [
                                    "summary",
                                    {},
                                    "text",
                                    "USTH"
                                ],
                                [
                                    "organizer",
                                    {
                                        "cn": "Benoît TELLIER"
                                    },
                                    "cal-address",
                                    "mailto:btellier@linagora.com"
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
                                    "mailto:btellier@linagora.com"
                                ]
                            ],
                            []
                        ]
                    ]
                ]
                """));
    }

    @Test
    void freeBusy() {
        OpenPaasUser alice = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", alice.basicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + alice.id() + "/" + alice.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        String body = given()
            .headers("Authorization", alice.basicAuth())
            .body("{\"start\":\"20250228T230000\",\"end\":\"20250301T230000\",\"users\":[\"" + alice.id() + "\"],\"uids\":[\"1111632f-9917-4efc-b78f-243be5403074\"]}")
        .when()
            .post("/calendars/freebusy")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .isEqualTo(String.format("""
                {"start":"20250228T230000","end":"20250301T230000","users":[{"id":"%s","calendars":[{"id":"%s","busy":[]}]}]}
                """, alice.id(), alice.id()));
    }

    @Test
    void shouldCreateCalendar() {
        OpenPaasUser alice = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", alice.basicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        String response = given()
            .headers("Authorization", alice.basicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:calendar": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/%s.json"
                                    }
                                },
                                "dav:name": "#default",
                                "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                                "invite": [
                                    {
                                        "href": "principals/users/%s",
                                        "principal": "principals/users/%s",
                                        "properties": [],
                                        "access": 1,
                                        "comment": null,
                                        "inviteStatus": 2
                                    }
                                ],
                                "acl": [
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-read",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                        "principal": "{DAV:}authenticated",
                                        "protected": true
                                    }
                                ]
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/b5dd8eee-7ae3-45f5-834a-356025b1e877.json"
                                    }
                                },
                                "dav:name": "automated",
                                "caldav:description": "",
                                "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                                "apple:color": "#E91E63",
                                "invite": [
                                    {
                                        "href": "principals/users/%s",
                                        "principal": "principals/users/%s",
                                        "properties": [],
                                        "access": 1,
                                        "comment": null,
                                        "inviteStatus": 2
                                    }
                                ],
                                "acl": [
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-read",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                        "principal": "{DAV:}authenticated",
                                        "protected": true
                                    }
                                ]
                            }
                        ]
                    }
                }
                """, alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id()));
    }

    @Test
    void acl() {
        OpenPaasUser alice = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", alice.basicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> alice.basicAuth(headers)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json")
            .send(body("""
                {"public_right":""}""")));

        assertThatJson(response.body())
            .isEqualTo(String.format("""
                [
                    {
                        "privilege": "{DAV:}share",
                        "principal": "principals/users/%s",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}share",
                        "principal": "principals/users/%s/calendar-proxy-write",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}write",
                        "principal": "principals/users/%s",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}write",
                        "principal": "principals/users/%s/calendar-proxy-write",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}write-properties",
                        "principal": "principals/users/%s",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}write-properties",
                        "principal": "principals/users/%s/calendar-proxy-write",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}read",
                        "principal": "principals/users/%s",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}read",
                        "principal": "principals/users/%s/calendar-proxy-read",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}read",
                        "principal": "principals/users/%s/calendar-proxy-write",
                        "protected": true
                    },
                    {
                        "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                        "principal": "{DAV:}authenticated",
                        "protected": true
                    }
                ]
                """, alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id()));
    }

    @Test
    void proppatch() {
        OpenPaasUser alice = dockerOpenPaasExtension.newTestUser();

        given()
            .headers("Authorization", alice.basicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> alice.basicAuth(headers)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json")
            .send(body(String.format("""
                {
                    "id": "b5dd8eee-7ae3-45f5-834a-356025b1e877",
                    "acl": [
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/%s",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/%s/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/%s",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/%s/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/%s",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/%s/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/%s",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/%s/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/%s/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ],
                    "invite": [
                        {
                            "href": "principals/users/%s",
                            "principal": "principals/users/%s",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "dav:name": "automated 2",
                    "apple:color": "#E91E63",
                    "caldav:description": ""
                }""", alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id()))));

        String response = given()
            .headers("Authorization", alice.basicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:calendar": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/%s.json"
                                    }
                                },
                                "dav:name": "#default",
                                "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                                "invite": [
                                    {
                                        "href": "principals/users/%s",
                                        "principal": "principals/users/%s",
                                        "properties": [],
                                        "access": 1,
                                        "comment": null,
                                        "inviteStatus": 2
                                    }
                                ],
                                "acl": [
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-read",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                        "principal": "{DAV:}authenticated",
                                        "protected": true
                                    }
                                ]
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/calendars/%s/b5dd8eee-7ae3-45f5-834a-356025b1e877.json"
                                    }
                                },
                                "dav:name": "automated 2",
                                "caldav:description": "",
                                "calendarserver:ctag": "http://sabre.io/ns/sync/2",
                                "apple:color": "#E91E63",
                                "invite": [
                                    {
                                        "href": "principals/users/%s",
                                        "principal": "principals/users/%s",
                                        "properties": [],
                                        "access": 1,
                                        "comment": null,
                                        "inviteStatus": 2
                                    }
                                ],
                                "acl": [
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}share",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-read",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s/calendar-proxy-write",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                        "principal": "{DAV:}authenticated",
                                        "protected": true
                                    }
                                ]
                            }
                        ]
                    }
                }
                """, alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id(), alice.id(), alice.id(), alice.id(), alice.id(),
                alice.id()));
    }

    // todo public right
    // todo delegation
}
