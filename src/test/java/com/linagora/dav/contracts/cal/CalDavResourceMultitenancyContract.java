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
import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.executeNoContent;
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
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;

import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
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
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        Document secondDomainDoc = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        String secondDomainId = secondDomainDoc.getObjectId("_id").toString();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();

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
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + resource.id()));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void propfindResourceCalendarShouldReturn403ForCrossDomainUser() {
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + resource.id() + "/" + resource.id()));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void getEventFromResourceCalendarShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(resource.id(), eventUid, CalDavContract.ICS_1, token);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(john::impersonatedBasicAuth)
            .get()
            .uri("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".ics"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void putEventToResourceCalendarShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".ics")
            .send(body(CalDavContract.ICS_1)));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void deleteEventFromResourceCalendarShouldReturn403ForCrossDomainUser() {
        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(resource.id(), eventUid, CalDavContract.ICS_1, token);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(john::impersonatedBasicAuth)
            .delete()
            .uri("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".ics"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void reportResourceCalendarShouldReturn403ForCrossDomainUser() {
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/json")
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + resource.id() + "/" + resource.id() + ".json")
            .send(body("""
                {"match":{"start":"20300401T000000","end":"20300430T000000"}}
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void getJsonCalendarListShouldReturn403ForCrossDomainUser() {
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Accept", "application/json"))
            .get()
            .uri("/calendars/" + resource.id() + ".json"));

        assertThat(status).isEqualTo(403);
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
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/calendars/" + resource.id() + "/newCalendar")
            .send(body("""
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
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @Test
    protected void shouldNotExposeResourceCalendarsWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token lists Domain B resource calendars
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, token)
                .add("Accept", "application/json"))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + ".json"));

        // THEN Domain B resource calendars are not exposed
        assertThat(status).isIn(403, 404);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @Test
    protected void shouldNotCreateIcsEventWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token writes an ICS event into the Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, token)
                .add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")
            .send(body("""
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
                """.formatted(eventUid))));

        // THEN no event is created in Domain B
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers.add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")))
            .isEqualTo(404);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
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
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers.add(TWAKE_CALENDAR_TOKEN_HEADER, token))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics"));

        // THEN the Domain B event is not exposed and remains readable by a Domain B technical token
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers.add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")))
            .isEqualTo(200);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
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
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers.add(TWAKE_CALENDAR_TOKEN_HEADER, token))
            .delete()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics"));

        // THEN the Domain B event remains available
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers.add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".ics")))
            .isEqualTo(200);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @Test
    protected void shouldNotCreateJsonEventWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token writes a JSON event into the Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, token)
                .add("Content-Type", "application/calendar+json"))
            .put()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
            .send(body("""
                ["vcalendar",[],[["vevent",[
                    ["uid",{},"text","%s"],
                    ["dtstart",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T10:00:00"],
                    ["dtend",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T11:00:00"],
                    ["summary",{},"text","Forbidden JSON event"],
                    ["dtstamp",{},"date-time","3025-04-11T02:00:00Z"]
                ],[]]]]
                """.formatted(eventUid))));

        // THEN no event is created in Domain B
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Accept", "application/calendar+json"))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")))
            .isEqualTo(404);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @Test
    protected void shouldNotExposeJsonEventWhenUsingForeignTechnicalToken() {
        // GIVEN a JSON event exists in a Domain B resource calendar
        String eventUid = "event-" + UUID.randomUUID();
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Content-Type", "application/calendar+json"))
            .put()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")
            .send(body("""
                ["vcalendar",[],[["vevent",[
                    ["uid",{},"text","%s"],
                    ["dtstart",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T10:00:00"],
                    ["dtend",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T11:00:00"],
                    ["summary",{},"text","Visible JSON event"],
                    ["dtstamp",{},"date-time","3025-04-11T02:00:00Z"]
                ],[]]]]
                """.formatted(eventUid)))))
            .isEqualTo(201);

        // WHEN a Domain A technical token reads that event as JSON
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, token)
                .add("Accept", "application/calendar+json"))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json"));

        // THEN the Domain B event is not exposed and remains readable by a Domain B technical token
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Accept", "application/calendar+json"))
            .get()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + "/" + eventUid + ".json")))
            .isEqualTo(200);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
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
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, token)
                .add("Content-Type", "application/json")
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + ".json")
            .send(body("""
                {"match":{"start":"30250401T000000","end":"30250430T000000"}}
                """)));

        // THEN the report does not expose Domain B events and a Domain B technical token can still search the calendar
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Content-Type", "application/json")
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + ".json")
            .send(body("""
                {"match":{"start":"30250401T000000","end":"30250430T000000"}}
                """))))
            .isEqualTo(200);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @Test
    protected void shouldNotGrantResourceCalendarDelegationWhenUsingForeignTechnicalToken() {
        // WHEN a Domain A technical token grants delegation on the Domain B resource calendar
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, token)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json, text/plain, */*"))
            .post()
            .uri("/calendars/" + secondDomainResource.id() + "/" + secondDomainResource.id() + ".json")
            .send(body("""
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
                """.formatted(secondDomainDelegate.email()))));

        // THEN no delegated calendar appears
        assertThat(status).isIn(403, 404);
        assertThat(calDavClient.findUserCalendars(secondDomainDelegate).collectList().block())
            .noneMatch(calendarURL -> calendarURL.base().equals(secondDomainResource.id()));
    }

}
