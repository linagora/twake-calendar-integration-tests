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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.executeNoContent;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
    private OpenPaasUser john;
    private String token;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);

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
        token = dockerExtension().twakeCalendarProvisioningService().generateToken();
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

    @Disabled("Currently returns 404")
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
}
