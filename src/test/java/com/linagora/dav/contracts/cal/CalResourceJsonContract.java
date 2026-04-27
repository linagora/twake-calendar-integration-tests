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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

public abstract class CalResourceJsonContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaaSResource resource;
    private String token;

    @BeforeEach
    void setUp() {
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
        token = dockerExtension().twakeCalendarProvisioningService().generateToken();
    }

    @Test
    void putShouldCreateEventOnResourceCalendar() {
        String eventUid = UUID.randomUUID().toString();

        given()
            .header("TwakeCalendarToken", token)
            .body(jsonCalendarBody(eventUid))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(201);
    }

    @Test
    void putShouldUpdateEventOnResourceCalendar() {
        String eventUid = UUID.randomUUID().toString();

        given()
            .header("TwakeCalendarToken", token)
            .body(jsonCalendarBody(eventUid))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(201);

        given()
            .header("TwakeCalendarToken", token)
            .body(jsonCalendarBody(eventUid))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(204);
    }

    @Test
    void getShouldReturnEventDetailForResourceCalendar() {
        String eventUid = UUID.randomUUID().toString();

        given()
            .header("TwakeCalendarToken", token)
            .body(jsonCalendarBody(eventUid))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(201);

        String body = given()
            .header("TwakeCalendarToken", token)
            .header("Accept", "application/calendar+json")
        .when()
            .get("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
            .whenIgnoringPaths("[1][1][3]")
            .isEqualTo("""
                [
                    "vcalendar",
                    [
                        ["version", {}, "text", "2.0"],
                        ["prodid", {}, "text", "-//Sabre//Sabre VObject 4.1.3//EN"]
                    ],
                    [
                        [
                            "vevent",
                            [
                                ["uid", {}, "text", "%s"],
                                ["dtstart", {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "3025-04-11T10:00:00"],
                                ["dtend",   {"tzid": "Asia/Ho_Chi_Minh"}, "date-time", "3025-04-11T11:00:00"],
                                ["summary", {}, "text", "Resource meeting"]
                            ],
                            []
                        ]
                    ]
                ]
                """.formatted(eventUid));
    }

    @Test
    void reportShouldListEventsForResourceCalendar() {
        String eventUid = UUID.randomUUID().toString();

        given()
            .header("TwakeCalendarToken", token)
            .body(jsonCalendarBody(eventUid))
        .when()
            .put("/calendars/" + resource.id() + "/" + resource.id() + "/" + eventUid + ".json")
        .then()
            .statusCode(201);

        String body = given()
            .header("TwakeCalendarToken", token)
            .body("{\"match\":{\"start\":\"30250401T000000\",\"end\":\"30250430T000000\"}}")
        .when()
            .request("REPORT", "/calendars/" + resource.id() + "/" + resource.id() + ".json")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .node("_embedded.dav:item").isArray().hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void listCalendarShouldReturnResourceCalendar() {
        String body = given()
            .header("TwakeCalendarToken", token)
        .when()
            .get("/calendars/" + resource.id() + ".json")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .node("_links.self.href").isEqualTo("/calendars/" + resource.id() + ".json");

        assertThat(body).contains("dav:calendar");
    }

    private static String jsonCalendarBody(String eventUid) {
        return """
            ["vcalendar",[],[["vevent",[
                ["uid",{},"text","%s"],
                ["dtstart",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T10:00:00"],
                ["dtend",{"tzid":"Asia/Ho_Chi_Minh"},"date-time","3025-04-11T11:00:00"],
                ["summary",{},"text","Resource meeting"],
                ["dtstamp",{},"date-time","3025-04-11T02:00:00Z"]
            ],[]]]]
            """.formatted(eventUid);
    }
}
