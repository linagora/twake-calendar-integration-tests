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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static com.linagora.dav.contracts.CalDavContract.ICS_1;
import static com.linagora.dav.contracts.CalDavContract.ICS_2;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Streams;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TwakeCalendarEvent;

import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

public abstract class CalJsonContract {
    public record EventData(String uid, String dtstart, String dtend, Optional<String> recurrenceId) {
    }

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();
    }

    @Test
    void shouldListCalendar() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
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
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
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
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/json, text/plain, */*"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20250201T000000","end":"20250215T000000"}}""")));

        assertThatJson(response.body())
            .whenIgnoringPaths("_embedded.dav:item[0].data[1][0][3]")  // ignore prodid
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
    void reportShouldListEventsWhenRruleIsDaily() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=DAILY")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20300415T000000","end":"20300417T000000"}}""")));


        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "2030-04-15T03:00:00Z", "2030-04-15T04:00:00Z", Optional.of("2030-04-15T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "2030-04-16T03:00:00Z", "2030-04-16T04:00:00Z", Optional.of("2030-04-16T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsDailyWithCountCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=DAILY;COUNT=2")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250511T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-12T03:00:00Z", "3025-04-12T04:00:00Z", Optional.of("3025-04-12T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsDailyWithUntilCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=DAILY;UNTIL=30250413T000000Z")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
            {"match":{"start":"30250410T000000","end":"30250420T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-12T03:00:00Z", "3025-04-12T04:00:00Z", Optional.of("3025-04-12T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsDailyWithInterval() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=DAILY;COUNT=2;INTERVAL=2")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250511T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-13T03:00:00Z", "3025-04-13T04:00:00Z", Optional.of("3025-04-13T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsWeeklyWithCountCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=WEEKLY;COUNT=2")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250511T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-18T03:00:00Z", "3025-04-18T04:00:00Z", Optional.of("3025-04-18T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsWeeklyWithUntilCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=WEEKLY;UNTIL=30250420T000000Z")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250511T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-18T03:00:00Z", "3025-04-18T04:00:00Z", Optional.of("3025-04-18T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsWeeklyWithInterval() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=WEEKLY;COUNT=2;INTERVAL=2")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250511T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-25T03:00:00Z", "3025-04-25T04:00:00Z", Optional.of("3025-04-25T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsWeeklyWithByDayCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=WEEKLY;BYDAY=FR;COUNT=3")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250511T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-15T03:00:00Z", "3025-04-15T04:00:00Z", Optional.of("3025-04-15T03:00:00Z")));
        assertThat(result.get(2)).isEqualTo(new EventData(eventUid, "3025-04-22T03:00:00Z", "3025-04-22T04:00:00Z", Optional.of("3025-04-22T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsMonthlyWithByMonthDayCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=MONTHLY;BYMONTHDAY=15")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20300413T000000","end":"20300520T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "2030-04-15T03:00:00Z", "2030-04-15T04:00:00Z", Optional.of("2030-04-15T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "2030-05-15T03:00:00Z", "2030-05-15T04:00:00Z", Optional.of("2030-05-15T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsMonthlyWithByMonthDayAndCountCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=MONTHLY;BYMONTHDAY=15;COUNT=3")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250611T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-15T03:00:00Z", "3025-04-15T04:00:00Z", Optional.of("3025-04-15T03:00:00Z")));
        assertThat(result.get(2)).isEqualTo(new EventData(eventUid, "3025-05-15T03:00:00Z", "3025-05-15T04:00:00Z", Optional.of("3025-05-15T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsMonthlyWithByMonthDayAndUntilCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("30250411T100000")
            .dtend("30250411T110000")
            .rrule("FREQ=MONTHLY;BYMONTHDAY=15;UNTIL=30250520T000000Z")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"30250311T000000","end":"30250611T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "3025-04-11T03:00:00Z", "3025-04-11T04:00:00Z", Optional.of("3025-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "3025-04-15T03:00:00Z", "3025-04-15T04:00:00Z", Optional.of("3025-04-15T03:00:00Z")));
        assertThat(result.get(2)).isEqualTo(new EventData(eventUid, "3025-05-15T03:00:00Z", "3025-05-15T04:00:00Z", Optional.of("3025-05-15T03:00:00Z")));
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/50")
    @Test
    void reportShouldListEventsWhenRruleIsYearlyWithByMonthCondition() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=YEARLY;BYMONTH=5;BYMONTHDAY=15")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20300410T000000","end":"20310520T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "2030-04-11T03:00:00Z", "2030-04-11T04:00:00Z", Optional.of("2030-04-11T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "2030-05-15T03:00:00Z", "2030-05-15T04:00:00Z", Optional.of("2030-05-15T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsFirstMondayOfEachMonth() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20300413T000000","end":"20300620T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "2030-05-06T03:00:00Z", "2030-05-06T04:00:00Z", Optional.of("2030-05-06T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "2030-06-03T03:00:00Z", "2030-06-03T04:00:00Z", Optional.of("2030-06-03T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsDailyAndExdateIsPresent() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=DAILY")
            .exDate("20300416T100000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20300415T000000","end":"20300417T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "2030-04-15T03:00:00Z", "2030-04-15T04:00:00Z", Optional.of("2030-04-15T03:00:00Z")));
    }

    @Test
    void reportShouldListEventsWhenRruleIsDailyAndRecurrenceIdIsPresent() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=DAILY")
            .recurrenceOverride("20300416T100000", "20300416T150000", "20300416T160000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20300415T000000","end":"20300417T000000"}}""")));

        List<EventData> result = getEventData(response.body());

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(new EventData(eventUid, "2030-04-15T03:00:00Z", "2030-04-15T04:00:00Z", Optional.of("2030-04-15T03:00:00Z")));
        assertThat(result.get(1)).isEqualTo(new EventData(eventUid, "2030-04-16T08:00:00Z", "2030-04-16T09:00:00Z", Optional.of("2030-04-16T03:00:00Z")));
    }

    @Test
    void putShouldCreateEvents() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20250224T000000","end":"20250315T000000"}}""")));

        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .whenIgnoringPaths("_embedded.dav:item[0].etag", "_embedded.dav:item[0].data[1][0][3]")   // ignore etag, prodid
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
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH 2\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(204);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + ".json")
            .send(body("""
                {"match":{"start":"20250224T000000","end":"20250315T000000"}}""")));

        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .whenIgnoringPaths("_embedded.dav:item[0].etag", "_embedded.dav:item[0].data[1][0][3]")   // ignore etag, prodid
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
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", "bad")
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH 2\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(412);
    }

    @Test
    void putShouldUpdateWhenGoodTag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);


        String etag = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .get("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .extract()
            .header("Etag");

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", etag)
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH 2\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(204);
    }

    @Test
    void getShouldShowEventDetail() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        String body = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .header("Accept", "application/calendar+json")
        .when()
            .get("/calendars/" + testUser.id() + "/" + testUser.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .whenIgnoringPaths("[1][1][3]")   // ignore prodid
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
        OpenPaasUser alice = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("[\"vcalendar\",[],[[\"vevent\",[[\"uid\",{},\"text\",\"1111632f-9917-4efc-b78f-243be5403074\"],[\"transp\",{},\"text\",\"OPAQUE\"],[\"dtstart\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-01\"],[\"dtend\",{\"tzid\":\"Europe/Paris\"},\"date\",\"2025-03-02\"],[\"class\",{},\"text\",\"PUBLIC\"],[\"x-openpaas-videoconference\",{},\"unknown\",null],[\"summary\",{},\"text\",\"USTH\"],[\"organizer\",{\"cn\":\"Benoît TELLIER\"},\"cal-address\",\"mailto:btellier@linagora.com\"],[\"attendee\",{\"partstat\":\"ACCEPTED\",\"rsvp\":\"FALSE\",\"role\":\"CHAIR\",\"cutype\":\"INDIVIDUAL\"},\"cal-address\",\"mailto:btellier@linagora.com\"]],[]],[\"vtimezone\",[[\"tzid\",{},\"text\",\"Europe/Paris\"]],[[\"daylight\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+01:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+02:00\"],[\"tzname\",{},\"text\",\"CEST\"],[\"dtstart\",{},\"date-time\",\"1970-03-29T02:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":3,\"byday\":\"-1SU\"}]],[]],[\"standard\",[[\"tzoffsetfrom\",{},\"utc-offset\",\"+02:00\"],[\"tzoffsetto\",{},\"utc-offset\",\"+01:00\"],[\"tzname\",{},\"text\",\"CET\"],[\"dtstart\",{},\"date-time\",\"1970-10-25T03:00:00\"],[\"rrule\",{},\"recur\",{\"freq\":\"YEARLY\",\"bymonth\":10,\"byday\":\"-1SU\"}]],[]]]]]]")
        .when()
            .put("/calendars/" + alice.id() + "/" + alice.id() + "/1111632f-9917-4efc-b78f-243be5403074.json")
        .then()
            .statusCode(201);

        String body = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
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
        OpenPaasUser alice = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
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
        OpenPaasUser alice = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
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
        OpenPaasUser alice = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
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
            .headers("Authorization", alice.impersonatedBasicAuth())
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

    @Test
    void publicReads() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        execute(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json")
            .send(body("""
                {"public_right":"{DAV:}read"}""")));

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics")
            .send(body(ICS_1)));

        String response2 = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics").prettyPeek()
        .then()
            .extract()
            .body()
            .asString();

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/efghi.ics")
            .send(body(ICS_2)));

        assertThat(response2).isEqualTo(ICS_1);
        assertThat(status).isEqualTo(403);
    }

    @Test
    void publicWrites() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        execute(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json")
            .send(body("""
                {"public_right":"{DAV:}write"}""")));

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics")
            .send(body(ICS_1)));

        String response2 = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics").prettyPeek()
        .then()
            .extract()
            .body()
            .asString();

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/efghi.ics")
            .send(body(ICS_2)));

        assertThat(response2).isEqualTo(ICS_1);
        assertThat(status).isEqualTo(201);
    }

    @Test
    void removingRights() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
        .when()
            .post("/calendars/" + alice.id() + ".json");

        execute(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json")
            .send(body("""
                {"public_right":"{DAV:}write"}""")));

        execute(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json")
            .send(body("""
                {"public_right":""}""")));

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics")
            .send(body(ICS_1)));

        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics")
        .then()
            .statusCode(403);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/efghi.ics")
            .send(body(ICS_2)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("Somehow failing")
    @Test
    void delegationRead() throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
            .when()
            .post("/calendars/" + alice.id() + ".json");

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics")
            .send(body(ICS_1)));

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "text/html; charset=UTF-8")
            .body("{\"share\":{\"set\":[{\"dav:href\":\"mailto:" + bob.email() + "\",\"dav:read\":true}],\"remove\":[]}}").log().all()
        .when()
            .post("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json").prettyPeek()
        .then()
            .statusCode(200);

        Thread.sleep(5000);

        String response2 = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics").prettyPeek()
        .then()
            .extract()
            .body()
            .asString();

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/efghi.ics")
            .send(body(ICS_2)));

        assertThat(response2).isEqualTo(ICS_1);
        assertThat(status).isEqualTo(403);
    }

    @Disabled("Somehow failing")
    @Test
    void delegationWrite() throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"b5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
            .when()
            .post("/calendars/" + alice.id() + ".json");
        // provision bob principal...
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body("{\"id\":\"c5dd8eee-7ae3-45f5-834a-356025b1e877\",\"dav:name\":\"automated\",\"apple:color\":\"#E91E63\",\"caldav:description\":\"\"}")
            .when()
            .post("/calendars/" + bob.id() + ".json");

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics")
            .send(body(ICS_1)));

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "text/html; charset=UTF-8")
            .body("{\"share\":{\"set\":[{\"dav:href\":\"mailto:" + bob.email() + "\",\"dav:read-write\":true}],\"remove\":[]}}").log().all()
        .when()
            .post("/calendars/" + alice.id()  + "/b5dd8eee-7ae3-45f5-834a-356025b1e877.json").prettyPeek()
        .then()
            .statusCode(200);

        Thread.sleep(5000);

        String response2 = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/abcd.ics").prettyPeek()
        .then()
            .extract()
            .body()
            .asString();

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + alice.id() + "/b5dd8eee-7ae3-45f5-834a-356025b1e877/efghi.ics")
            .send(body(ICS_2)));

        assertThat(response2).isEqualTo(ICS_1);
        assertThat(status).isEqualTo(201);
    }

    // todo delegation

    private List<EventData> getEventData(String json) throws JsonProcessingException {
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode vevents = root.at("/_embedded/dav:item/0/data/2");
        return Streams.stream(vevents.elements()).map(vevent -> {
            String uid = getEventDataField(vevent.get(1), "uid").get();
            String dtstart = getEventDataField(vevent.get(1), "dtstart").get();
            String dtend = getEventDataField(vevent.get(1), "dtend").get();
            Optional<String> recurrenceId = getEventDataField(vevent.get(1), "recurrence-id");
            return new EventData(uid, dtstart, dtend, recurrenceId);
        }).toList();
    }

    private Optional<String> getEventDataField(JsonNode vevent, String fieldName) {
        return Streams.stream(vevent.elements())
            .filter(jsonNode -> jsonNode.get(0).asText().equals(fieldName))
            .map(jsonNode -> jsonNode.get(3).asText()).findAny();
    }
}
