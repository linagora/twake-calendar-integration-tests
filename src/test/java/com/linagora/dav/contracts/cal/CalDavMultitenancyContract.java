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

import static com.linagora.dav.TestUtil.TWAKE_CALENDAR_TOKEN_HEADER;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

public abstract class CalDavMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser john;
    private String defaultDomainToken;
    private String secondDomainToken;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/xml")
            .accept("application/xml");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        Document secondDomainDoc = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        String secondDomainId = secondDomainDoc.getObjectId("_id").toString();
        defaultDomainToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        secondDomainToken = dockerExtension().twakeCalendarProvisioningService().generateToken(secondDomainId);
        bob = dockerExtension().newTestUser();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
    }

    @Test
    void propfindOnCalendarsRootShouldNotExposeCrossDomainCalendarHome() {
        String responseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/calendars/")
        .then()
            .extract().asString();

        assertThat(responseBody).doesNotContain(john.id());
    }

    @Test
    void propfindShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/calendars/" + john.id())
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void propfindCalendarShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/calendars/" + john.id() + "/" + john.id())
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void mkcolShouldReturnErrorStatusForCrossDomainUser() {
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                     <D:displayname>New Calendar</D:displayname>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/calendars/" + john.id() + "/newCalendar")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void mkcalendarShouldReturnErrorStatusForCrossDomainUser() {
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .body("""
                <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:displayname>New Calendar</D:displayname>
                   </D:prop>
                 </D:set>
                </C:mkcalendar>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCALENDAR", "/calendars/" + john.id() + "/newCalendar")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void putEventShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(CalDavContract.ICS_1.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void deleteEventShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .delete("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void getEventShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void proppatchShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>Hacked Calendar</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/calendars/" + john.id() + "/" + john.id())
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void headEventShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .head("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void exportShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "?export")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void reportShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <C:free-busy-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:time-range start="20300401T000000Z" end="20300430T000000Z"/>
                </C:free-busy-query>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + john.id() + "/" + john.id())
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void copyEventBetweenCalendarsShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/calendars/" + john.id() + "/secondCalendar")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Destination", "/calendars/" + john.id() + "/secondCalendar/abcd.ics")
        .when()
            .request("COPY", "/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void moveEventBetweenCalendarsShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/calendars/" + john.id() + "/secondCalendar")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Destination", "/calendars/" + john.id() + "/secondCalendar/abcd.ics")
        .when()
            .request("MOVE", "/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void updateAclShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(bob, CalendarURL.from(john.id()), "{DAV:}read"))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void getJsonCalendarListShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
        .when()
            .get("/calendars/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void putJsonEventShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        String eventUid = UUID.randomUUID().toString();
        String jsonCalendarData = """
            [
                "vcalendar",
                [],
                [
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
                                "dtstamp",
                                {},
                                "date-time",
                                "3025-04-11T02:20:32Z"
                            ]
                        ],
                        []
                    ]
                ]
            ]
            """.replace("{eventUid}", eventUid);

        assertThatThrownBy(() -> calDavClient.upsertJsonCalendarEvent(bob, john.id(), john.id(), eventUid, jsonCalendarData))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void getJsonEventShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(john, eventUid, CalDavContract.ICS_1);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void exportJsonCalendarShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + ".json?export")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void postJsonNewCalendarShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .body("""
                {"id":"newCal","dav:name":"New Calendar","apple:order":2}
                """)
        .when()
            .post("/calendars/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void updateJsonCalendarMetadataShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.updateCalendarSetting(bob, CalendarURL.from(john.id()), "Hacked Name", "#FF0000"))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void deleteJsonCalendarShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.deleteCalendar(bob, CalendarURL.from(john.id())))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void reportJsonCalendarShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
            .contentType("application/json")
            .body("""
                {"match":{"start":"20300401T000000","end":"20300430T000000"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + john.id() + "/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void updateJsonAclShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(bob, CalendarURL.from(john.id()), "{DAV:}read"))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void subscribeShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        SubscribedCalendarRequest request = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob's shared calendar")
            .color("#00FF00")
            .readOnly(true)
            .build();

        assertThatThrownBy(() -> calDavClient.subscribeToSharedCalendar(john, request))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void delegateShouldReturnErrorStatusForCrossDomainUser() {
        assertThatThrownBy(() -> calDavClient.grantDelegation(bob, bob.id(), john, DelegationRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void freeBusyShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/json")
            .accept("application/json")
            .body("""
                {
                  "start": "20300411T020000",
                  "end": "20300411T050000",
                  "users": [
                    "{johnId}"
                  ]
                }
                """
                .replace("{johnId}", john.id()).getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/calendars/freebusy")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void itipRequestShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T080000Z
            DTSTART:20300405T090000Z
            DTEND:20300405T100000Z
            SUMMARY:Cross-domain meeting
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=John;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), john.email());

        String itipBody = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender(bob.email())
            .recipient(john.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        assertThatThrownBy(() -> calDavClient.sendITIPRequest(bob, URI.create("/calendars/" + john.id()), itipBody).block())
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void itipCounterShouldReturnErrorStatusForCrossDomainUser() {
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        String eventUid = "event-" + UUID.randomUUID();
        String counterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T081000Z
            DTSTART:20300405T100000Z
            DTEND:20300405T110000Z
            SUMMARY:Cross-domain meeting - counter proposal
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=John;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), john.email());

        String counterBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcs)
            .sender(john.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        assertThatThrownBy(() -> calDavClient.sendITIPRequest(john, URI.create("/calendars/" + bob.id()), counterBody).block())
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    protected void shouldNotExposeUserCalendarsWhenUsingForeignTechnicalToken() {
        // GIVEN John owns a Domain B calendar
        // WHEN a Domain A technical token lists John's calendars
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .accept("application/json")
        .when()
            .get("/calendars/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN Domain B calendars are not exposed and remain reachable with a Domain B technical token
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("application/json")
        .when()
            .get("/calendars/" + john.id() + ".json")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotCreateIcsEventInUserCalendarWhenUsingForeignTechnicalToken() {
        // GIVEN John owns a Domain B calendar
        String eventUid = "event-" + UUID.randomUUID();

        // WHEN a Domain A technical token writes an ICS event into John's calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .contentType("text/calendar; charset=utf-8")
            .body(icsEvent(eventUid, "Forbidden ICS event").getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no event is created in Domain B
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(404);
    }

    @Test
    protected void shouldNotExposeIcsEventInUserCalendarWhenUsingForeignTechnicalToken() {
        // GIVEN an ICS event exists in John's Domain B calendar
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(john.id(), eventUid, icsEvent(eventUid, "Visible ICS event"), secondDomainToken);

        // WHEN a Domain A technical token reads that ICS event
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B event is not exposed and remains readable by a Domain B technical token
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotDeleteIcsEventInUserCalendarWhenUsingForeignTechnicalToken() {
        // GIVEN an ICS event exists in John's Domain B calendar
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(john.id(), eventUid, icsEvent(eventUid, "Protected ICS event"), secondDomainToken);

        // WHEN a Domain A technical token deletes that ICS event
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
        .when()
            .delete("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B event remains available
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotCreateJsonEventInUserCalendarWhenUsingForeignTechnicalToken() {
        // GIVEN John owns a Domain B calendar
        String eventUid = "event-" + UUID.randomUUID();

        // WHEN a Domain A technical token writes a JSON event into John's calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .contentType("application/calendar+json")
            .body("""
                ["vcalendar",[],[["vevent",[
                    ["uid",{},"text","%s"],
                    ["dtstart",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T10:00:00"],
                    ["dtend",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T11:00:00"],
                    ["summary",{},"text","Forbidden JSON event"],
                    ["dtstamp",{},"date-time","3025-04-11T02:00:00Z"]
                ],[]]]]
                """.formatted(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no event is created in Domain B
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(404);
    }

    @Test
    protected void shouldNotExposeJsonEventInUserCalendarWhenUsingForeignTechnicalToken() {
        // GIVEN a JSON event exists in John's Domain B calendar
        String eventUid = "event-" + UUID.randomUUID();
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .contentType("application/calendar+json")
            .body("""
                ["vcalendar",[],[["vevent",[
                    ["uid",{},"text","%s"],
                    ["dtstart",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T10:00:00"],
                    ["dtend",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T11:00:00"],
                    ["summary",{},"text","Visible JSON event"],
                    ["dtstamp",{},"date-time","3025-04-11T02:00:00Z"]
                ],[]]]]
                """.formatted(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(201);

        // WHEN a Domain A technical token reads that event as JSON
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B event is not exposed and remains readable by a Domain B technical token
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotExposeUserCalendarReportWhenUsingForeignTechnicalToken() {
        // GIVEN an event exists in John's Domain B calendar
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(john.id(), eventUid, icsEvent(eventUid, "Reported event"), secondDomainToken);

        // WHEN a Domain A technical token searches John's calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .contentType("application/json")
            .accept("application/json")
            .body("""
                {"match":{"start":"30250401T000000","end":"30250430T000000"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + john.id() + "/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the report does not expose Domain B events and a Domain B technical token can still search the calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .contentType("application/json")
            .accept("application/json")
            .body("""
                {"match":{"start":"30250401T000000","end":"30250430T000000"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + john.id() + "/" + john.id() + ".json")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotGrantUserCalendarDelegationWhenUsingForeignTechnicalToken() {
        // GIVEN John owns a Domain B calendar
        OpenPaasUser secondDomainDelegate = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();

        // WHEN a Domain A technical token grants delegation on John's calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .contentType("application/json;charset=UTF-8")
            .accept("application/json, text/plain, */*")
            .body("""
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
                """.formatted(secondDomainDelegate.email()).getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/calendars/" + john.id() + "/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no delegated calendar appears
        assertThat(calDavClient.findUserCalendars(secondDomainDelegate).collectList().block())
            .noneMatch(calendarURL -> calendarURL.base().equals(john.id()));
    }

    private static String icsEvent(String eventUid, String summary) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30250411T020000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, summary);
    }
}
