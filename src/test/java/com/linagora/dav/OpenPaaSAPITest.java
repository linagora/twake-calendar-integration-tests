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

import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_OK;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

class OpenPaaSAPITest {
    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    @BeforeEach
    void setUp() {
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri("http://" + TestContainersUtils.getContainerPrivateIpAddress(DockerOpenPaasSetupSingleton.singleton.getOpenPaasContainer()) + ":8080")
            .build();
    }

    @Test
    void retrieveUserDetail() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/users/" + testUser.id())
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("domains[0].joined_at")
            .isEqualTo(String.format("""
                {
                    "_id": "%s",
                    "firstname": "%s",
                    "lastname": "%s",
                    "preferredEmail": "%s",
                    "emails": [
                        "%s"
                    ],
                    "domains": [
                        {
                            "joined_at": "2025-02-18T16:57:02.104Z",
                            "domain_id": "%s"
                        }
                    ],
                    "states": [],
                    "avatars": [],
                    "id": "%s",
                    "displayName": "%s %s",
                    "objectType": "user",
                    "following": false,
                    "followers": 0,
                    "followings": 0
                }""", testUser.id(), testUser.firstname(), testUser.lastname(),
                testUser.email(), testUser.email(), dockerOpenPaasExtension.domainId(),
                testUser.id(), testUser.firstname(), testUser.lastname()));
    }

    @Test
    void retrieveUserDetailByEmailAddress() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("email", testUser.email())
        .when()
            .get("api/users").prettyPeek()
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("[0].domains[0].joined_at")
            .isEqualTo(String.format("""
                    [
                         {
                             "_id": "%s",
                             "firstname": "%s",
                             "lastname": "%s",
                             "preferredEmail": "%s",
                             "emails": [
                                 "%s"
                             ],
                             "domains": [
                                 {
                                     "joined_at": "2025-02-18T17:21:03.405Z",
                                     "domain_id": "%s"
                                 }
                             ],
                             "states": [ ],
                             "avatars": [ ],
                             "id": "%s",
                             "displayName": "%s %s",
                             "objectType": "user"
                         }
                     ]""", testUser.id(), testUser.firstname(), testUser.lastname(),
                testUser.email(), testUser.email(), dockerOpenPaasExtension.domainId(),
                testUser.id(), testUser.firstname(), testUser.lastname()));
    }

    @Test
    void retrieveDomainDetail() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/domains/" + dockerOpenPaasExtension.domainId())
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("timestamps.creation",
                "administrators[0].timestamps.creation",
                "administrators[0].user_id")
            .isEqualTo(String.format("""
                {
                     "timestamps": {
                         "creation": "2025-02-18T17:14:58.689Z"
                     },
                     "hostnames": [
                         "localhost",
                         "127.0.0.1",
                         "open-paas.org"
                     ],
                     "schemaVersion": 1,
                     "_id": "%s",
                     "name": "open-paas.org",
                     "company_name": "openpaas",
                     "administrators": [
                         {
                             "timestamps": {
                                 "creation": "2025-02-18T17:14:58.689Z"
                             },
                             "user_id": "67b4c01202748a005112cbcd"
                         }
                     ],
                     "injections": [],
                     "__v": 0
                 }""", dockerOpenPaasExtension.domainId()));
    }
}
