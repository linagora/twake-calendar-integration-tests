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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

public abstract class CalDavResourceMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaaSResource resource;
    private OpenPaaSResource secondDomainResource;
    private OpenPaasUser john;
    private OpenPaasUser secondDomainDelegate;
    private String token;
    private String secondDomainToken;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        Document secondDomainDoc = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        String secondDomainId = secondDomainDoc.getObjectId("_id").toString();

        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        OpenPaasUser admin = dockerExtension().newTestUser();
        resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("meeting-room", "A meeting room", admin)
            .block();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
        secondDomainDelegate = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
        secondDomainResource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("second-domain-meeting-room", "A second domain meeting room", john, SECOND_DOMAIN)
            .block();
        token = dockerExtension().twakeCalendarProvisioningService().generateToken();
        secondDomainToken = dockerExtension().twakeCalendarProvisioningService().generateToken(secondDomainId);
    }

    @Test
    void propfindResourceCalendarHomeShouldReturn403ForCrossDomainUser() {
        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
        .when()
            .request("PROPFIND", "/calendars/" + resource.id())
        .then()
            .statusCode(403);
    }

    @Test
    void propfindResourceCalendarShouldReturn403ForCrossDomainUser() {
        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
        .when()
            .request("PROPFIND", "/calendars/" + resource.id() + "/" + resource.id())
        .then()
            .statusCode(403);
    }

    @Test
    void getEventFromResourceCalendarShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(resource.id(), eventUid, CalDavContract.ICS_1, token);

        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(403);
    }

    @Test
    void putEventToResourceCalendarShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();

        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(CalDavContract.ICS_1.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(403);
    }

    @Test
    void deleteEventFromResourceCalendarShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(resource.id(), eventUid, CalDavContract.ICS_1, token);

        given()
            .header("Authorization", john.impersonatedBasicAuth())
        .when()
            .delete("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(403);
    }

    @Test
    void reportResourceCalendarShouldReturn403ForCrossDomainUser() {
        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .body("""
                {"match":{"start":"20300401T000000","end":"20300430T000000"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + resource.id() + "/" + resource.id() + ".json")
        .then()
            .statusCode(403);
    }

    @Test
    void getJsonCalendarListShouldReturn403ForCrossDomainUser() {
        given()
            .header("Authorization", john.impersonatedBasicAuth())
        .when()
            .get("/calendars/" + resource.id() + ".json")
        .then()
            .statusCode(403);
    }

    @Test
    void propfindCalendarRootShouldNotEnumerateCrossDomainResourceCalendarHomes() {
        // Given a resource exists in a second domain

        // When a default-domain user lists calendar homes from the calendar root
        OpenPaasUser defaultDomainUser = dockerExtension().newTestUser();
        String responseBody = given()
            .header("Authorization", defaultDomainUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .contentType("application/xml")
            .accept("application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/calendars/")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then the cross-domain resource calendar home must not be disclosed
        assertThat(responseBody)
            .as("Calendar root listing must not expose resource calendar homes from another domain")
            .doesNotContain(secondDomainResource.id());
    }

    @Test
    void putJsonEventShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();

        given()
            .headers("Authorization", john.impersonatedBasicAuth())
            .body("""
                ["vcalendar",[],[["vevent",[
                    ["uid",{},"text","%s"],
                    ["dtstart",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T10:00:00"],
                    ["dtend",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T11:00:00"],
                    ["summary",{},"text","Hacked meeting"]
                ],[]]]]
                """.formatted(eventUid))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(403);
    }

    @Test
    void getJsonEventShouldReturn403Or404ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(resource.id(), eventUid, CalDavContract.ICS_1, token);

        given()
            .headers("Authorization", john.impersonatedBasicAuth())
            .header("Accept", "application/calendar+json")
        .when()
            .get("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void mkcolInResourceSpaceShouldReturn403ForCrossDomainUser() {
        given()
            .header("Authorization", john.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
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
            .request("MKCOL", "/calendars/" + resource.id() + "/newCalendar")
        .then()
            .statusCode(403);
    }

    @Test
    protected void shouldNotExposeResourceCalendarsWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token lists Domain B resource calendars
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
        .when()
            .get("/calendars/" + secondDomainResource.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    protected void shouldNotCreateIcsEventWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token writes an ICS event into the Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
            .contentType("text/calendar; charset=utf-8")
            .body("""
                BEGIN:VCALENDAR
                VERSION:2.0
                PRODID:-//Sabre//Sabre VObject 4.1.3//EN
                CALSCALE:GREGORIAN
                BEGIN:VEVENT
                UID:%s
                DTSTAMP:30250411T020000Z
                DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
                DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
                SUMMARY:Forbidden ICS event
                END:VEVENT
                END:VCALENDAR
                """.formatted(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no event is created in Domain B
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(404);
    }

    @Test
    protected void shouldNotExposeIcsEventWhenUsingForeignTechnicalToken() {
        // GIVEN an ICS event exists in a Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(secondDomainResource.id(), eventUid, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30250411T020000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Visible ICS event
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid), secondDomainToken);

        // WHEN a Domain A technical token reads that ICS event
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
            .accept("*/*")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B event is not exposed and remains readable by a Domain B technical token
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotDeleteIcsEventWhenUsingForeignTechnicalToken() {
        // GIVEN an ICS event exists in a Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(secondDomainResource.id(), eventUid, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30250411T020000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Protected ICS event
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid), secondDomainToken);

        // WHEN a Domain A technical token deletes that ICS event
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
        .when()
            .delete("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B event remains available
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("*/*")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotCreateJsonEventWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token writes a JSON event into the Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
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
            .put("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no event is created in Domain B
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(404);
    }

    @Test
    protected void shouldNotExposeJsonEventWhenUsingForeignTechnicalToken() {
        // GIVEN a JSON event exists in a Domain B resource calendar
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
            .put("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(201);

        // WHEN a Domain A technical token reads that event as JSON
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B event is not exposed and remains readable by a Domain B technical token
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotExposeResourceCalendarReportWhenUsingForeignTechnicalToken() {
        // GIVEN an event exists in a Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(secondDomainResource.id(), eventUid, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30250411T020000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Reported event
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid), secondDomainToken);

        // WHEN a Domain A technical token searches the Domain B resource calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
            .body("""
                {"match":{"start":"30250401T000000","end":"30250430T000000"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the report does not expose Domain B events and a Domain B technical token can still search the calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .body("""
                {"match":{"start":"30250401T000000","end":"30250430T000000"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + ".json")
        .then()
            .statusCode(200);
    }

    @Test
    protected void shouldNotGrantResourceCalendarDelegationWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token grants delegation on the Domain B resource calendar
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, token)
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
            .post("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no delegated calendar appears
        assertThat(calDavClient.findUserCalendars(secondDomainDelegate).collectList().block())
            .noneMatch(calendarURL -> calendarURL.base().equals(secondDomainResource.id()));
    }

}
