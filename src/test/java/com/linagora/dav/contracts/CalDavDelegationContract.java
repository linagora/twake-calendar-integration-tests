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
import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static com.linagora.dav.contracts.ITIPRequestContract.awaitAtMost;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.AssertionsForClassTypes;
import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.JsonCalendarEventData;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestUtil;
import com.linagora.dav.TwakeCalendarEvent;

import com.linagora.dav.XMLUtil;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public abstract class CalDavDelegationContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser alice;
    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();
        bob = dockerExtension().newTestUser();
        alice = dockerExtension().newTestUser();
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

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void listCalendarsShouldShowDelegatedCalendarInDav(DelegationRight right) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        // AND: Alice see the subscription in her calendars
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(alice::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + alice.id()));
        assertThat(response.status()).isEqualTo(207);
        List<String> actual = XMLUtil.extractMultipleValueByXPath(response.body(), "//d:multistatus/d:response/d:href", Map.of("d", "DAV:"));
        AssertionsForInterfaceTypes.assertThat(actual).contains(calendarURL.asUri() + "/");
        // AND: the subscription do not contain event
        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(alice::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri(calendarURL.asUri().toString()));
        assertThat(response2.status()).isEqualTo(207);
        assertThat(response2.body()).contains("<cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set>");
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void listCalendarsShouldShowDelegatedCalendarEventsInDav(DelegationRight right) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);
        // AND: Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String description = "Important meeting with Alice";
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Bob's readonly event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, description);
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        // THEN: Alice can report the event in her subscription
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(calendarURL.asUri().toString())
            .send(body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">{eventUid}</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.replace("{eventUid}", eventUid))));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        Calendar expectedCalendar = CalendarUtil.parseIcs(calendarData);
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void bobCannotReadAliceSourceCalendarDirectlyWhenDelegated(DelegationRight right) {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Alice has an event in her calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Alice's event
            DESCRIPTION:Event in Alice source calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        // WHEN Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // THEN Bob can read Alice's SOURCE calendar directly via CalDAV REPORT (not his copy)
        String aliceSourceCalendarUri = "/calendars/" + alice.id() + "/" + alice.id();
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(aliceSourceCalendarUri)
            .send(body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))));

        assertThat(response.status()).isEqualTo(207);
        assertThat(response.body()).contains("<d:status>HTTP/1.1 403 Forbidden</d:status>");
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void bobCannotUpsertInAliceSourceCalendarDirectlyWhenDelegated(DelegationRight right) {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob tries to create an event directly in Alice's SOURCE calendar (not his copy)
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Bob's event in Alice calendar
            DESCRIPTION:Event created by Bob directly in Alice source calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI aliceSourceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics");

        // THEN Bob gets a 403 Forbidden
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(bob, aliceSourceCalendarEventUri, calendarData))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void bobCannotDeleteInAliceSourceCalendarDirectlyWhenDelegated(DelegationRight right) {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Alice has an event in her calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Alice's event
            DESCRIPTION:Event in Alice source calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        // AND Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob tries to delete the event directly in Alice's SOURCE calendar (not his copy)
        URI aliceSourceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics");

        // THEN Bob gets a 403 Forbidden
        assertThatThrownBy(() -> calDavClient.deleteCalendarEvent(bob, aliceSourceCalendarEventUri))
            .hasMessageContaining("Unexpected status code: 403");
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/256")
    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE", "CONFIDENTIAL"})
    void privateOrConfidentialEventShouldBeAnonymizedInDavReport(String eventClass) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Alice's secret meeting
            DESCRIPTION:Confidential information that Bob should not see
            LOCATION:Secret Room
            CLASS:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, eventClass);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        // WHEN Alice delegates her calendar to Bob with READ access
        calDavClient.grantDelegation(alice, alice.id(), bob, DelegationRight.READ);

        // THEN Bob can access the delegated calendar
        CalendarURL calendarURL = calDavClient.findUserCalendars(bob).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Bob queries the event via CalDAV REPORT
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(calendarURL.asUri().toString())
            .send(body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))));

        assertThat(response.status()).isEqualTo(207);

        // THEN Bob sees an anonymized version of the event
        String calendarDataResponse = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));

        Calendar actualCalendar = CalendarUtil.parseIcs(calendarDataResponse);
        VEvent event = (VEvent) actualCalendar.getComponent(Component.VEVENT).get();

        // The event should be anonymized: SUMMARY = "Busy", no DESCRIPTION, no LOCATION
        assertThat(event.getProperty(Property.SUMMARY).get().getValue()).isEqualTo("Busy");
        assertThat(event.getProperty(Property.DESCRIPTION)).isEmpty();
        assertThat(event.getProperty(Property.LOCATION)).isEmpty();
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

    @ParameterizedTest
    @ValueSource(strings = {"READ_WRITE", "ADMIN"})
    void aliceCanCreateEventsInReadOnlyDelegationWithDAVWhenAtLeastWriteRight(String param) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.valueOf(param));

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();
        // And: Alice cannot place an event in it
        String eventUid2 = "event-" + UUID.randomUUID();
        String calendarData2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Alice created event
            DESCRIPTION:Whatever
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid2);
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid2, calendarData2);

        // AND the event is created in Bob original calendar
        DavResponse response = calDavClient.findEventsByTime(bob,
            "20251029T090000",
            "20251031T100000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid2);
        assertThat(result.get(0).summary().get()).isEqualTo("Alice created event");
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_WRITE", "ADMIN"})
    void upsertedEventViaDelegatedCalendarShouldAppearInOwnerSourceCalendarInDav(String param) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.valueOf(param));

        // AND Alice gets her delegated calendar copy
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice creates an event via her delegated calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Alice created event
            DESCRIPTION:Created via delegated calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData);

        // THEN the event appears in Bob's SOURCE calendar via CalDAV REPORT
        String bobSourceCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(bobSourceCalendarUri)
            .send(body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))));

        assertThat(response.status()).isEqualTo(207);

        String calendarDataResponse = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));

        Calendar actualCalendar = CalendarUtil.parseIcs(calendarDataResponse);
        VEvent event = (VEvent) actualCalendar.getComponent(Component.VEVENT).get();

        assertThat(event.getProperty(Property.UID).get().getValue()).isEqualTo(eventUid);
        assertThat(event.getProperty(Property.SUMMARY).get().getValue()).isEqualTo("Alice created event");
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_WRITE", "ADMIN"})
    void aliceCanDeleteEventsInDelegationWithDAVWhenAtLeastWriteRight(String param) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar with an event
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Bob's event to delete
            DESCRIPTION:This event will be deleted by Alice
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.valueOf(param));

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice deletes the event via CalDAV using her delegated calendar
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        // THEN the event is deleted in Bob's original calendar
        DavResponse response = calDavClient.findEventsByTime(bob,
            "20251029T090000",
            "20251031T100000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.body());
        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_WRITE", "ADMIN"})
    void deletedEventViaDelegatedCalendarShouldBeRemovedFromOwnerSourceCalendarInDav(String param) throws Exception {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Bob's event to delete
            DESCRIPTION:This event will be deleted by Alice
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.valueOf(param));

        // AND Alice gets her delegated calendar copy
        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice deletes the event via her delegated calendar
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        // THEN the event is removed from Bob's SOURCE calendar (verified via CalDAV REPORT)
        String bobSourceCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(bobSourceCalendarUri)
            .send(body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))));

        assertThat(response.status()).isEqualTo(207);
        // No calendar-data should be returned for the deleted event
        String calendarDataResponse = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));
        assertThat(calendarDataResponse).isNullOrEmpty();
    }

    @Test
    void deleteCalendarEventShouldThrowErrorWhenCopiedCalendarOnlyHasReadRight() {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar with an event
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Bob's protected event")
            .location("Twake Meeting Room")
            .description("This event should not be deletable by Alice with READ only.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice with READ only
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL calendarURL = calDavClient.findUserCalendars(alice).collectList().block()
            .stream().filter(url -> !url.base().equals(url.calendarId())).findAny().get();

        // WHEN Alice tries to delete the event in Bob calendar copy
        // THEN a 403 error is thrown
        assertThatThrownBy(() -> calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid))
            .hasMessageContaining("Unexpected status code: 403 when delete calendar object");
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

    @Test
    void resourceAdminCanUpdateParticipationStatus() {
        // GIVEN: Create a resource 'whiteboard' with Bob as administrator
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: Grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T100000
            SUMMARY:Whiteboard session
            LOCATION:Meeting Room
            DESCRIPTION:Test event with resource attendee
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=whiteboard:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());

        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // GIVEN: Fetch Bob's delegated resource calendar URL
        CalendarURL resourceCalendarURL = calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .next().blockOptional()
            .orElseThrow(() -> new AssertionError("Bob has no delegated resource calendar"));

        // WHEN: Bob locates the event in the resource calendar
        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();

        // THEN: Bob can retrieve the event via GET
        DavResponse getResponse = execute(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri(resourceCalendarURL.asUri().toASCIIString() + "/" + resourceEventId + ".ics"));

        assertThat(getResponse.status()).isEqualTo(200);
        assertThat(getResponse.body()).contains(eventUid);

        // WHEN: Bob updates the resource participation status to ACCEPTED
        String updatedEventIcs = getResponse.body()
            .replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");

        int updateStatusCode = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri(resourceCalendarURL.asUri().toASCIIString() + "/" + resourceEventId + ".ics")
            .send(body(updatedEventIcs)));

        assertThat(updateStatusCode).isEqualTo(204);

        // THEN: Verify that the participation status is updated
        DavResponse verifyResponse = execute(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri(resourceCalendarURL.asUri().toASCIIString() + "/" + resourceEventId + ".ics"));

        assertThat(verifyResponse.status()).isEqualTo(200);
        assertThat(verifyResponse.body())
            .contains("mailto:" + resource.id() + "@open-paas.org")
            .contains("PARTSTAT=ACCEPTED");
    }

    @Test
    public void shouldPropagateToOrganizerWhenResourceAdminUpdatePartStat() {
        // GIVEN: Create a resource 'whiteboard' with Bob as administrator
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: Grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T100000
            SUMMARY:Whiteboard session
            LOCATION:Meeting Room
            DESCRIPTION:Test event with resource attendee
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=whiteboard:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());

        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // GIVEN: Fetch Bob's delegated resource calendar URL
        CalendarURL resourceCalendarURL = calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .next().blockOptional()
            .orElseThrow(() -> new AssertionError("Bob has no delegated resource calendar"));

        // WHEN: Bob updates the resource participation status to ACCEPTED
        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();
        DavResponse getResponse = execute(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri(resourceCalendarURL.asUri().toASCIIString() + "/" + resourceEventId + ".ics"));

        String updatedEventIcs = getResponse.body()
            .replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");
        executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri(resourceCalendarURL.asUri().toASCIIString() + "/" + resourceEventId + ".ics")
            .send(body(updatedEventIcs)));

        // THEN: Verify that Alice sees the updated participation status of the resource
        awaitAtMost.untilAsserted(() -> {
            DavResponse aliceViewResponse = execute(dockerExtension().davHttpClient()
                .headers(alice::impersonatedBasicAuth)
                .get()
                .uri("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics"));

            assertThat(aliceViewResponse.status()).isEqualTo(200);
            assertThat(aliceViewResponse.body())
                .contains("mailto:" + resource.id() + "@open-paas.org")
                .contains("PARTSTAT=ACCEPTED");
        });
    }

    @Test
    void resourceAdminCanSendItipCounterViaResourceCalendarUri() {
        // GIVEN: a resource "whiteboard" with Bob as administrator
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T100000
            SUMMARY:Design meeting
            LOCATION:Meeting Room
            DESCRIPTION:Initial meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=whiteboard;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());

        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // WHEN: Bob (resource admin) sends an ITIP COUNTER on behalf of the resource
        String counterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T030000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T110000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T120000
            SUMMARY:Design meeting - Proposed new time
            ORGANIZER;CN=whiteboard:mailto:%s@open-paas.org
            ATTENDEE;CN=Alice;ROLE=REQ-PARTICIPANT:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, resource.id(), alice.email());

        String counterJsonBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcal)
            .sender(resource.id() + "@open-paas.org")
            .recipient(alice.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        assertThatCode(() ->
            calDavClient.sendITIPRequest(bob, URI.create("/calendars/" + resource.id()), counterJsonBody).block())
            .doesNotThrowAnyException();

        // THEN: Alice should receive the COUNTER request in her inbox
        String aliceInboxUri = "/calendars/" + alice.id() + "/inbox/";
        List<JsonNode> aliceInboxItems = calDavClient.reportCalendarEvents(
                alice,
                aliceInboxUri,
                Instant.parse("2024-09-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"))
            .collectList()
            .block();

        assertThat(aliceInboxItems)
            .as("Alice should receive a COUNTER proposal from the resource in her inbox")
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("\"COUNTER\"");
                assertThat(json).contains("mailto:" + alice.email());
                assertThat(json).contains("mailto:" + resource.id() + "@open-paas.org");
            });
    }

    @Test
    void resourceAdminCanSendItipCounterViaDelegatedCalendarUri() {
        // GIVEN: Create resource with Bob as admin and grant him read-write rights on the resource calendar
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T100000
            SUMMARY:Design meeting
            LOCATION:Meeting Room
            DESCRIPTION:Initial meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=whiteboard;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());
        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // GIVEN: Bob's delegated resource calendar URL and event href
        CalendarURL resourceCalendarUrl = calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .next().blockOptional()
            .orElseThrow(() -> new AssertionError("Bob has no delegated resource calendar"));

        List<String> propfindHrefs = TestUtil.awaitCalendarEntries(
            dockerExtension().davHttpClient(),
            bob, resourceCalendarUrl.asUri().toString(), 1);

        // WHEN: Bob (resource admin) sends ITIP COUNTER via calendar URI
        String counterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T030000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T110000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T120000
            SUMMARY:Design meeting - Proposed new time
            ORGANIZER;CN=whiteboard:mailto:%s@open-paas.org
            ATTENDEE;CN=Alice;ROLE=REQ-PARTICIPANT:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, resource.id(), alice.email());

        String counterJsonBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcal)
            .sender(resource.id() + "@open-paas.org")
            .recipient(alice.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        Map.Entry<Integer, String> postResponse = dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .headers(header -> header.add("Accept", "application/json, text/plain, */*")
                .add("Content-Type", "application/calendar+json"))
            .request(HttpMethod.valueOf("ITIP"))
            .uri(propfindHrefs.getFirst())
            .send(Mono.fromCallable(() -> Unpooled.wrappedBuffer(counterJsonBody.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, content) ->
                content.asString().defaultIfEmpty("").map(body -> Map.entry(response.status().code(), body)))
            .block();

        assertThat(postResponse.getKey())
            .as("POST COUNTER request by resource admin should succeed")
            .isIn(200, 202, 204);

        // THEN: Alice should receive the COUNTER request in her inbox
        String aliceInboxUri = "/calendars/" + alice.id() + "/inbox/";
        List<JsonNode> aliceInboxItems = calDavClient.reportCalendarEvents(
                alice,
                aliceInboxUri,
                Instant.parse("2024-09-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"))
            .collectList()
            .block();

        assertThat(aliceInboxItems)
            .as("Alice should receive a COUNTER proposal from the resource in her inbox")
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("\"COUNTER\"");
                assertThat(json).contains("mailto:" + alice.email());
                assertThat(json).contains("mailto:" + resource.id() + "@open-paas.org");
            });
    }

    @Test
    protected void resourceAdminCanListEventsViaDelegatedCalendar() {
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("resourceA", "Shared resource", bob)
            .block();

        // GIVEN: Grant Bob read-write rights on the resource calendar
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .headers(headers -> headers.add("Accept", "application/calendar+json"))
            .get()
            .uri("/calendars/" + bob.id() + "/events.json"));

        assertThat(response.status())
            .as("Expected status 200 but got %s with body: %s", response.status(), response.body())
            .isEqualTo(200);
    }

    private void delegateResourceToAdmin(OpenPaaSResource resource, OpenPaasUser admin, String technicalToken) {
        Map.Entry<Integer, String> delegationResponse = dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add("TwakeCalendarToken", technicalToken)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri("/calendars/" + resource.id() + "/" + resource.id() + ".json")
            .send(Mono.defer(() -> {
                String payload = """
                    {
                      "share": {
                        "set": [
                          {
                            "dav:href": "mailto:%s",
                            "dav:read-write": true
                          }
                        ],
                        "remove": []
                      }
                    }
                    """.formatted(admin.email());
                return Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8)));
            }))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .flatMap(body -> {
                    int status = response.status().code();
                    if (status != 200 && status != 201 && status != 204) {
                        return Mono.error(new RuntimeException("HTTP " + status + ": " + body));
                    }
                    return Mono.just(Map.entry(status, body));
                }))
            .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(1))
                .filter(error -> StringUtils.containsAnyIgnoreCase(error.getMessage(), "Could not find node at path")))
            .block();

        assertThat(delegationResponse.getKey())
            .as("Bob should be granted write access to the resource calendar")
            .isIn(200, 201, 204);
    }
}
