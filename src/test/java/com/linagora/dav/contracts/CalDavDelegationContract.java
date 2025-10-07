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
import static com.linagora.dav.TestUtil.execute;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.JsonCalendarEventData;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TwakeCalendarEvent;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

public abstract class CalDavDelegationContract {

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
    void listCalendarsShouldShowDelegatedCalendar() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

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

        // THEN a copy of bob calendar is created in Alice calendar list
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
                        }
                    },
                    "calendarserver:delegatedsource": "/calendars/{userId2}/{userId2}.json",
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read-acl",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read-acl",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }  
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userEmail}", alice.email()));
    }

    @Test
    void listCalendarsShouldNotShowDelegatedCalendarWhenDelegationHasBeenRevoked() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Alice has a copy of bob calendar is created in Alice calendar list
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        // WHEN Bob revokes rights for Alice
        calDavClient.revokeDelegation(bob, bob.id(), alice);

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


        // THEN a copy of bob calendar is removed from Alice calendar list
        assertThat(response).doesNotContain("\"calendarserver:delegatedsource\":\"\\/calendars\\/" + bob.id() + "\\/" + bob.id() + ".json\"");
    }

    @Test
    void listCalendarsShouldShowDelegatedCalendarWithReadWriteRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // WHEN Bob delegates read write to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

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

        // THEN Alice copy of bob calendar is updated accordingly
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
                        }
                    },
                    "calendarserver:delegatedsource": "/calendars/{userId2}/{userId2}.json",
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 3,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userEmail}", alice.email()));
    }

    @Test
    void listCalendarsShouldShowSharingRightOfDelegation() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        // THEN the sharing right is shown on Bob source calendar
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/{userId2}/{userId2}.json"
                        }
                    },
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userEmail}", alice.email()));
    }

    @Test
    void listCalendarsShouldNotShowSharingRightOfDelegationWhenCopiedCalendarHasBeenDeleted() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Alice has a copy of bob calendar is created in Alice calendar list
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        // WHEN Alice deletes this calendar copy
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.calendarId().equals(alice.id())).findAny().get();
        calDavClient.deleteCalendar(alice, calendarURL);

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();


        // THEN the sharing right is removed on Bob source calendar
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0].invite")
            .isEqualTo(String.format("""
                [
                    {
                        "href": "principals/users/{userId2}",
                        "principal": "principals/users/{userId2}",
                        "properties": [],
                        "access": 1,
                        "comment": null,
                        "inviteStatus": 2
                    }
                ]
                """.replace("{userId2}", bob.id())));
    }

    @Test
    void copiedCalendarShouldContainsExistingEvent() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);
        CalendarURL calendarURL = calDavClient.findUserCalendars(testUser2).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        DavResponse response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
    }

    @Test
    void cannotReadPrivateEventWhenCalendarIsReadable() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);
        CalendarURL calendarURL = calDavClient.findUserCalendars(testUser2).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        DavResponse response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Busy");
    }

    @Test
    void canExportWhenCalendarIsReadable() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "?export"));

        AssertionsForClassTypes.assertThat(response2.status()).isEqualTo(200);
    }

    @Test
    void canExportWhenCalendarIsWritable() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ_WRITE);

        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "?export"));

        AssertionsForClassTypes.assertThat(response2.status()).isEqualTo(200);
    }

    @Test
    void cannotReadPrivateEventWhenPCalendarIsWritable() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ_WRITE);
        CalendarURL calendarURL = calDavClient.findUserCalendars(testUser2).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        DavResponse response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Busy");
    }

    @Test
    void copiedCalendarShouldContainsNewEvent() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        CalendarURL calendarURL = calDavClient.findUserCalendars(testUser2).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        DavResponse response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
    }

    @Test
    void copiedCalendarShouldContainsUpdatedEvent() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String updatedCalendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Updated Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        CalendarURL calendarURL = calDavClient.findUserCalendars(testUser2).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        DavResponse response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Updated Sprint planning #01");
    }

    @Test
    void copiedCalendarShouldNotContainsDeletedEvent() throws JsonProcessingException {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        calDavClient.deleteCalendarEvent(testUser, eventUid);

        CalendarURL calendarURL = calDavClient.findUserCalendars(testUser2).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        DavResponse response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(0);
    }

    @Test
    void amqpMessagesShouldBeEmittedForCopiedCalendar() throws Exception {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:created", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:updated", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:deleted", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), QUEUE_NAME);
        Thread.sleep(3000);

        assertThat(messages)
            .anySatisfy(json ->
                assertThat(json.path("eventPath").asText()).startsWith("/calendars/" + testUser2.id()));
    }

    @Test
    void noAmqpAlarmMessagesEmittedForCopiedCalendar() throws Exception {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:created", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:updated", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:deleted", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .alarmTrigger("-PT15M")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), QUEUE_NAME);
        Thread.sleep(3000);

        assertThat(messages)
            .noneSatisfy(json ->
                assertThat(json.path("eventPath").asText()).startsWith("/calendars/" + testUser2.id()));
    }

    @Test
    void putCalendarEventShouldThrowErrorWhenCopiedCalendarOnlyHasReadRight() {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        // WHEN Alice tries to create an event in Bob calendar copy
        // THEN a 403 error is thrown
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData))
            .hasMessageContaining("Unexpected status code: 403 when create/update calendar object");
    }

    @Test
    void putCalendarEventShouldCreateNewEventInOriginalCalendarWhenCopiedCalendarHasReadWriteRight() throws JsonProcessingException {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(alice.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        // WHEN Alice creates an event in Bob calendar copy
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData);

        DavResponse response = calDavClient.findEventsByTime(bob,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        // THEN the event is created in Bob original calendar
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
    }

    @Test
    void putCalendarEventShouldUpdateEventInOriginalCalendarWhenCopiedCalendarHasReadWriteRight() throws JsonProcessingException {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob has an event in his calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice updates the event in Bob calendar copy
        String updatedCalendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Updated Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, updatedCalendarData);

        DavResponse response = calDavClient.findEventsByTime(bob,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        // THEN the event is updated in Bob original calendar
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Updated Sprint planning #01");
    }

    @Test
    void shouldDeleteCalendarEventInOriginalCalendarWhenCopiedCalendarHasReadWriteRight() throws JsonProcessingException {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob has an event in his calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice deletes the event in Bob calendar copy
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        DavResponse response = calDavClient.findEventsByTime(bob,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        // THEN the event is deleted in Bob original calendar
        assertThat(result).hasSize(0);
    }

    @Test
    void putCalendarEventShouldSendITIPRequestWhenCopiedCalendarHasReadWriteRight() throws JsonProcessingException {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .attendee(cedric.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        // WHEN Alice creates an event in Bob calendar copy with Cedric as attendee
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData);

        // THEN an ITIP request is sent to Cedric
        DavResponse response = calDavClient.findEventsByTime(cedric,
            cedric.id(),
            "inbox",
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).method().get()).isEqualTo("REQUEST");
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
    }

    @Test
    void deleteCalendarEventShouldSendITIPCancelWhenCopiedCalendarHasReadWriteRight() throws JsonProcessingException {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob has an event in his calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .attendee(cedric.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice deletes the event in Bob calendar copy
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        // THEN an ITIP cancel is sent to Cedric
        DavResponse response = calDavClient.findEventsByTime(cedric,
            cedric.id(),
            "inbox",
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(eventData -> {
            assertThat(eventData.uid()).isEqualTo(eventUid);
            assertThat(eventData.method().get()).isEqualTo("CANCEL");
            assertThat(eventData.summary().get()).isEqualTo("Sprint planning #01");
        });
    }

    @Test
    void updateCalendarAclShouldThrowErrorWhenDelegatedUserOnlyHasReadRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN bob delegated his calendar to Alice in readonly mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice attempts to manage rights on her local copy
        // THEN is gets rejected
        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(alice, calendarURL, "{DAV:}read"))
            .hasMessageContaining("Unexpected status code: 403 when updating ACL for calendar");
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/55")
    @Test
    void updateCalendarAclShouldThrowErrorWhenDelegatedUserOnlyHasReadWriteRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN bob delegated his calendar to Alice in read-write mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice attempts to manage rights on her local copy
        // THEN is gets rejected
        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(alice, calendarURL, "{DAV:}read"))
            .hasMessageContaining("Unexpected status code: 403 when updating ACL for calendar");
    }

    @Test
    void grantDelegationShouldThrowErrorWhenDelegatedUserOnlyHasReadRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in read mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice attempts to delegate her local copy to Cedric
        // THEN is gets rejected
        assertThatThrownBy(() -> calDavClient.grantDelegation(alice, calendarURL.calendarId(), cedric, DelegationRight.READ))
            .hasMessageContaining("Unexpected status code: 403 when sharing calendar");
    }

    @Test
    void grantDelegationShouldThrowErrorWhenDelegatedUserOnlyHasReadWriteRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in read-write mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice attempts to delegate her local copy to Cedric
        // THEN is gets rejected
        assertThatThrownBy(() -> calDavClient.grantDelegation(alice, calendarURL.calendarId(), cedric, DelegationRight.READ))
            .hasMessageContaining("Unexpected status code: 403 when sharing calendar");
    }

    @Test
    void updateCalendarAclShouldRunSuccessfullyWhenDelegatedUserHasAdminRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in admin mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice attempts to manage rights on her local copy
        calDavClient.updateCalendarAcl(alice, calendarURL, "{DAV:}read");

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + bob.id() + ".json")
            .then()
            .extract()
            .body()
            .asString();

        // THEN the right is updated in Bob original calendar
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/{userId2}/{userId2}.json"
                        }
                    },
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userEmail}", alice.email()));
    }

    @Test
    void grantDelegationShouldRunSuccessfullyWhenDelegatedUserHasAdminRight() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in admin mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice delegates her local copy to Cedric
        calDavClient.grantDelegation(alice, calendarURL.calendarId(), cedric, DelegationRight.READ);

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + bob.id() + ".json")
            .then()
            .extract()
            .body()
            .asString();

        // THEN the right is updated in Bob original calendar
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/{userId2}/{userId2}.json"
                        }
                    },
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail3}",
                            "principal": "principals/users/{userId3}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userId3}", cedric.id())
                .replace("{userEmail}", alice.email())
                .replace("{userEmail3}", cedric.email()));
    }

    @Test
    void rightMetadataOfCopiedCalendarShouldBeUpdatedWhenOriginalCalendarIsUpdated() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in read mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        // WHEN Bob updates public rights on his calendar
        // AND Bob delegates his calendar to Cedric in read mode
        calDavClient.updateCalendarAcl(bob,"{DAV:}read");
        calDavClient.grantDelegation(bob, bob.id(), cedric, DelegationRight.READ);

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

        // THEN the right is updated in Alice local copy of Bob original calendar
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
                        }
                    },
                    "calendarserver:delegatedsource": "/calendars/{userId2}/{userId2}.json",
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail3}",
                            "principal": "principals/users/{userId3}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userId3}", cedric.id())
                .replace("{userEmail}", alice.email())
                .replace("{userEmail3}", cedric.email()));
    }

    @Test
    void delegatedUserCanUpdateSettingOfCopiedCalendar() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        calDavClient.updateCalendarSetting(alice, calendarURL, "new name", "#009688");

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

        assertThat(response)
            .containsOnlyOnce("\"dav:name\":\"new name\"")
            .containsOnlyOnce("\"apple:color\":\"#009688\"");
    }

    @Test
    void updateNameAndColorOfCopiedCalendarShouldNotAffectOriginalCalendar() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        calDavClient.updateCalendarSetting(alice, calendarURL, "new name", "#009688");

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", 2)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + bob.id() + ".json")
            .then()
            .extract()
            .body()
            .asString();

        assertThat(response)
            .doesNotContain("\"dav:name\":\"new name\"")
            .doesNotContain("\"apple:color\":\"#009688\"");
    }

    @Test
    void updateNameAndColorOfOriginalCalendarShouldNotAffectCopiedCalendar() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        calDavClient.updateCalendarSetting(bob, CalendarURL.from(bob.id()), "new name", "#009688");

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

        assertThat(response)
            .doesNotContain("\"dav:name\":\"new name\"")
            .doesNotContain("\"apple:color\":\"#009688\"");
    }
}
