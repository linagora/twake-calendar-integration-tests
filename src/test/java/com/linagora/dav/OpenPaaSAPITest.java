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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.with;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerState;

import com.linagora.dav.contracts.CalDavContract;

import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;
import reactor.netty.http.client.HttpClient;

abstract class OpenPaaSAPITest {

    private HttpClient davHttpClient;
    private String domainId;

    abstract OpenPaasUser createUser();

    abstract HttpClient davHttpClient();

    abstract String domainId();

    abstract URI backendURI();

    abstract URI elasticSearchURI();

    @BeforeEach
    void setUp() {
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(backendURI().toString())
            .build();

        davHttpClient = davHttpClient();
        domainId = domainId();
    }

    @Test
    void retrieveUserDetail() {
        OpenPaasUser testUser = createUser();

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
            .whenIgnoringPaths("domains[0].joined_at",
                "avatars","displayName","id","states",     //twake missing
                "main_phone","state")       //twake extra
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
                testUser.email(), testUser.email(), domainId,
                testUser.id(), testUser.firstname(), testUser.lastname()));
    }

    @Test
    void retrieveUserDetailTheOpenPaaSWay() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/user")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("domains[0].joined_at", "_id", "id", "domains[0].domain_id", "accounts[0].timestamps")
            .isEqualTo("""
                    {
                        "_id": "67b6084cb5096b0053bec21c",
                        "firstname": "admin",
                        "lastname": "admin",
                        "preferredEmail": "admin@open-paas.org",
                        "emails": [
                            "admin@open-paas.org"
                        ],
                        "domains": [
                            {
                                "joined_at": "2025-02-19T16:35:24.714Z",
                                "domain_id": "67b6084cb5096b0053bec21d"
                            }
                        ],
                        "states": [],
                        "avatars": [],
                        "accounts": [
                            {
                                "timestamps": {
                                    "creation": "2025-02-19T16:35:24.570Z"
                                },
                                "hosted": false,
                                "emails": [
                                    "admin@open-paas.org"
                                ],
                                "preferredEmailIndex": 0,
                                "type": "email"
                            }
                        ],
                        "login": {
                            "failures": []
                        },
                        "id": "67b6084cb5096b0053bec21c",
                        "displayName": "admin admin",
                        "objectType": "user",
                        "isPlatformAdmin": true,
                        "configurations": {
                            "modules": [
                                {
                                    "configurations": [
                                        {
                                            "name": "webadminApiFrontend",
                                            "value": "http://localhost:8000"
                                        }
                                    ],
                                    "name": "linagora.esn.james"
                                },
                                {
                                    "configurations": [
                                        {
                                            "name": "davserver",
                                            "value": {
                                                "backend": {
                                                    "url": "http://esn_sabre:80"
                                                }
                                            }
                                        },
                                        {
                                            "name": "features",
                                            "value": {
                                                "application-menu:jobqueue": false,
                                                "application-menu:invitation": false,
                                                "control-center:password": true,
                                                "control-center:invitation": false,
                                                "header:user-notification": true
                                            }
                                        },
                                        {
                                            "name": "homePage",
                                            "value": "unifiedinbox"
                                        },
                                        {
                                            "name": "datetime",
                                            "value": {
                                                "timeZone": "UTC"
                                            }
                                        },
                                        {
                                            "name": "language",
                                            "value": "en"
                                        },
                                        {
                                            "name": "maxSizeUpload",
                                            "value": {
                                                "maxSizeUpload": 104857600
                                            }
                                        }
                                    ],
                                    "name": "core"
                                },
                                {
                                    "configurations": [
                                        {
                                            "name": "api",
                                            "value": "http://localhost:1080/jmap"
                                        },
                                        {
                                            "name": "uploadUrl",
                                            "value": "http://localhost:1080/upload"
                                        },
                                        {
                                            "name": "downloadUrl",
                                            "value": "http://localhost:1080/download/{blobId}/{name}"
                                        },
                                        {
                                            "name": "isJmapSendingEnabled",
                                            "value": true
                                        },
                                        {
                                            "name": "composer.attachments",
                                            "value": true
                                        },
                                        {
                                            "name": "maxSizeUpload",
                                            "value": 20971520
                                        },
                                        {
                                            "name": "numberItemsPerPageOnBulkReadOperations",
                                            "value": 30
                                        },
                                        {
                                            "name": "numberItemsPerPageOnBulkDeleteOperations",
                                            "value": 30
                                        },
                                        {
                                            "name": "numberItemsPerPageOnBulkUpdateOperations",
                                            "value": 30
                                        },
                                        {
                                            "name": "drafts",
                                            "value": true
                                        },
                                        {
                                            "name": "view",
                                            "value": "messages"
                                        },
                                        {
                                            "name": "swipeRightAction",
                                            "value": "markAsRead"
                                        },
                                        {
                                            "name": "forwarding",
                                            "value": true
                                        },
                                        {
                                            "name": "isLocalCopyEnabled",
                                            "value": true
                                        }
                                    ],
                                    "name": "linagora.esn.unifiedinbox"
                                },
                                {
                                    "name": "linagora.esn.jobqueue",
                                    "configurations": [
                                        {
                                            "name": "features",
                                            "value": {
                                                "isUserInterfaceEnabled": false
                                            }
                                        }
                                    ]
                                }
                            ]
                        }
                    }
                    """);
    }

    @Test
    void retrieveUserDetailByEmailAddress() {
        OpenPaasUser testUser = createUser();

        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("email", testUser.email())
        .when()
            .get("api/users")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("[0].domains[0].joined_at",
                "[0].avatars","[0].displayName","[0].id","[0].states",    //twake missing
                "[0].followers","[0].following","[0].followings","[0].main_phone","[0].state")   //twake extra
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
                testUser.email(), testUser.email(), domainId,
                testUser.id(), testUser.firstname(), testUser.lastname()));
    }

    @Test
    void retrieveDomainDetail() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/domains/" + domainId)
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("administrators", //twake missing
                "company_name",    //twake different
                "hostnames",       //twake different
                "timestamps")       //twake different
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
                 }""", domainId));
    }

    @Test
    void jwtTokenGeneration() {
        String string = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .when()
            .post("/api/jwt/generate")
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThat(string.startsWith("\"eyJ")).isTrue();
    }

    @Test
    void jwtTokenCanBeUsedToCallAPI() {
        String string = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .when()
            .post("/api/jwt/generate")
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();


        given()
            .headers("Authorization", "Bearer " + string.substring(1, string.length() - 1))
        .when()
            .get("api/domains/" + domainId)
        .then()
            .statusCode(SC_OK);
    }

    @Test
    void jwtTokenCanBeUsedToCallSabre() {
        String string = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .when()
            .post("/api/jwt/generate")
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        DavResponse response = execute(davHttpClient
            .headers(headers -> headers.add("Authorization", "Bearer " + string.substring(1, string.length() - 1))
                .add("Depth", 0)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/")
            .send(body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                     <d:current-user-principal />
                  </d:prop>
                </d:propfind>""")));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
    void theme() {
        String theme = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/themes/" + domainId)
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(theme).isEqualTo("{\"logos\":{},\"colors\":{}}");
    }

    @Test
    void logo() {
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .redirects().follow(false)
            .get("api/themes/" + domainId + "/logo").prettyPeek()
        .then()
            .statusCode(302)
            .header("Location", "http://localhost:8080/images/white-logo.svg");
    }

    @Test
    void davServerLocation() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("scope", "user")
            .body("[{\"name\":\"core\",\"keys\":[\"davserver\"]}]")
        .when()
            .redirects().follow(false)
            .post("api/configurations")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("[{\"name\":\"core\",\"configurations\":[{\"name\":\"davserver\",\"value\":{\"backend\":{\"url\":\"http://esn_sabre:80\"}}}]}]");
    }

    @Test
    void avatar() {
        OpenPaasUser testUser = createUser();
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .redirects().follow(false)
            .get("/api/users/" + testUser.id() + "/profile/avatar")
        .then()
            .statusCode(302)
            .header("Location", "/api/avatars?objectType=email&email=" + testUser.email());
    }

    @Test
    void avatarBis() {
        OpenPaasUser testUser = createUser();
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("email", testUser.email())
        .when()
            .get("/api/avatars")
        .then()
            .statusCode(SC_OK);
    }

    @Test
    void calendarConfiguration() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("scope", "user")
            .body("[{\"name\":\"linagora.esn.calendar\",\"keys\":[\"workingDays\",\"hideDeclinedEvents\"]}]")
        .when()
            .post("api/configurations")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("[{\"name\":\"linagora.esn.calendar\",\"configurations\":[{\"name\":\"workingDays\"},{\"name\":\"hideDeclinedEvents\"}]}]");
    }

    @Disabled("Somehow indexing is broken")
    @Test
    void calendarSearch() throws Exception {
        String adminId = getAdminId();

        int status = davHttpClient
            .headers(headers -> headers.add("Authorization", OpenPaasUser.impersonatedBasicAuth("admin@open-paas.org"))
                .add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + adminId + "/" + adminId + "/abcd.ics")
            .send(body(CalDavContract.ICS_1))
            .response()
            .block()
            .status()
            .code();
        assertThat(status).isEqualTo(201);

        Thread.sleep(1000);
        with()
            .baseUri(elasticSearchURI().toString())
            .post("_flush");
        Thread.sleep(1000);

        String response = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .body("{\"calendars\":[{\"userId\":\"" + adminId + "\",\"calendarId\":\"" + adminId + "\"}],\"query\":\"irrelevant\"}")
            .queryParam("limit", 30)
            .queryParam("offset", 0)
        .when()
            .post("calendar/api/events/search")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response).isEqualTo("{\"_links\":{\"self\":{\"href\":\"/calendar/api/events/search?limit=30&offset=0\"}},\"_total_hits\":0,\"_embedded\":{\"events\":[]}}");
    }

    @Test
    void peopleSearch() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .body("{\"q\":\"john\",\"objectTypes\":[\"user\",\"resource\"],\"limit\":10}")
        .when()
            .post("api/people/search")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("$[*].id")
            .isEqualTo("[{\"id\":\"67b655e7cd6be9005113d526\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user0@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John0 Doe0\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user0@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d527\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user1@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John1 Doe1\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user1@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d530\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user10@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John10 Doe10\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user10@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d531\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user11@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John11 Doe11\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user11@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d532\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user12@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John12 Doe12\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user12@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d533\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user13@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John13 Doe13\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user13@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d534\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user14@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John14 Doe14\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user14@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d535\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user15@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John15 Doe15\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user15@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d536\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user16@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John16 Doe16\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user16@open-paas.org\",\"type\":\"default\"}]},{\"id\":\"67b655e7cd6be9005113d537\",\"objectType\":\"user\",\"emailAddresses\":[{\"value\":\"user17@open-paas.org\",\"type\":\"default\"}],\"phoneNumbers\":[],\"names\":[{\"displayName\":\"John17 Doe17\",\"type\":\"default\"}],\"photos\":[{\"url\":\"http://localhost:8080/api/avatars?objectType=user&email=user17@open-paas.org\",\"type\":\"default\"}]}]");
    }

    @Disabled("Somehow indexing is broken")
    @Test
    void peopleSearchInContacts() throws Exception {
        String adminId = getAdminId();

        int status = davHttpClient
            .headers(headers -> headers.add("Authorization", OpenPaasUser.impersonatedBasicAuth("admin@open-paas.org")))
            .put()
            .uri("/addressbooks/" + adminId + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Oliver Werk\n" +
                "N:Oliver;Derk;;;\n" +
                "EMAIL:oliver.derk@example.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n"))
            .response()
            .block()
            .status()
            .code();
        assertThat(status).isEqualTo(201);

        Thread.sleep(1000);
        with()
            .baseUri(elasticSearchURI().toString())
            .post("_flush");
        Thread.sleep(1000);

        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .body("{\"q\":\"oliver\",\"objectTypes\":[\"user\",\"resource\", \"contact\"],\"limit\":10}")
        .when()
            .post("api/people/search")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("");
    }

    @Disabled("This needs the secret link configuration, not included by default...")
    @Test
    void secretLink() {
        String adminId = getAdminId();

        String secretLink = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("shouldResetLink", "true")
        .when()
            .get("calendar/api/calendars/" + adminId + "/" + adminId + "/secret-link").prettyPeek()
        .then()
            .extract()
            .body()
            .jsonPath()
            .getString("secretLink");

        String body = given()
            .baseUri(secretLink)
            .get().prettyPeek()
            .then()
            .extract()
            .body()
            .asString();

        assertThat(body.startsWith("BEGIN:VCALENDAR")).isTrue();
    }

    @Test
    void settingsConfiguration() {
        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("scope", "user")
            .body("[{\"name\":\"core\",\"keys\":[\"homePage\",\"businessHours\",\"datetime\",\"language\"]}]")
        .when()
            .post("api/configurations")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            [
                {
                    "name": "core",
                    "configurations": [
                        {
                            "name": "homePage",
                            "value": "unifiedinbox"
                        },
                        {
                            "name": "businessHours"
                        },
                        {
                            "name": "datetime",
                            "value": {
                                "timeZone": "UTC"
                            }
                        },
                        {
                            "name": "language",
                            "value": "en"
                        }
                    ]
                }
            ]""");
    }

    @Disabled("Breaks isolation")
    @Test
    void settingsUpdate() {
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("scope", "user")
            .body("[{\"name\":\"core\",\"configurations\":[{\"name\":\"homePage\",\"value\":\"calendar.main\"},{\"name\":\"businessHours\",\"value\":[{\"start\":\"9:0\",\"end\":\"19:0\",\"daysOfWeek\":[1,2,3,4,5]}]},{\"name\":\"datetime\",\"value\":{\"timeZone\":\"Europe/Paris\",\"use24hourFormat\":true}},{\"name\":\"language\",\"value\":\"fr\"}]}]")
        .when()
            .put("api/configurations")
        .then()
            .statusCode(204);

        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("scope", "user")
            .body("[{\"name\":\"core\",\"keys\":[\"homePage\",\"businessHours\",\"datetime\",\"language\"]}]")
        .when()
            .post("api/configurations")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            [
                 {
                     "name": "core",
                     "configurations": [
                         {
                             "name": "homePage",
                             "value": "calendar.main"
                         },
                         {
                             "name": "businessHours",
                             "value": [
                                 {
                                     "start": "9:0",
                                     "end": "19:0",
                                     "daysOfWeek": [
                                         1,
                                         2,
                                         3,
                                         4,
                                         5
                                     ]
                                 }
                             ]
                         },
                         {
                             "name": "datetime",
                             "value": {
                                 "timeZone": "Europe/Paris",
                                 "use24hourFormat": true
                             }
                         },
                         {
                             "name": "language",
                             "value": "fr"
                         }
                     ]
                 }
             ]""");
    }

    @Disabled("Breaks isolation")
    @Test
    void profileUpdate() {
        // Same input than /api/user result
        // Extra properties: job_title service building_location office_location main_phone description
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .queryParam("scope", "user")
            .body("""
                    {
                     "job_title": "Testing post 3",
                     "service": "Testing service 3",
                     "building_location": "8 rue du docteur Penard 3",
                     "office_location": "Lyon 3",
                     "main_phone": "0677260458 3",
                     "description": "A deeply committed tester 3",
                        "_id": "67b6084cb5096b0053bec21c",
                        "firstname": "admin 2",
                        "lastname": "admin 2",
                        "preferredEmail": "admin@open-paas.org",
                        "emails": [
                            "admin@open-paas.org"
                        ],
                        "domains": [
                            {
                                "joined_at": "2025-02-19T16:35:24.714Z",
                                "domain_id": "67b6084cb5096b0053bec21d"
                            }
                        ],
                        "states": [],
                        "avatars": [],
                        "accounts": [
                            {
                                "timestamps": {
                                    "creation": "2025-02-19T16:35:24.570Z"
                                },
                                "hosted": false,
                                "emails": [
                                    "admin@open-paas.org"
                                ],
                                "preferredEmailIndex": 0,
                                "type": "email"
                            }
                        ],
                        "login": {
                            "failures": []
                        },
                        "id": "67b6084cb5096b0053bec21c",
                        "displayName": "admin 2 admin 2",
                        "objectType": "user",
                        "isPlatformAdmin": true,
                        "configurations": {
                            "modules": [
                                {
                                    "configurations": [
                                        {
                                            "name": "webadminApiFrontend",
                                            "value": "http://localhost:8000"
                                        }
                                    ],
                                    "name": "linagora.esn.james"
                                },
                                {
                                    "configurations": [
                                        {
                                            "name": "davserver",
                                            "value": {
                                                "backend": {
                                                    "url": "http://esn_sabre:80"
                                                }
                                            }
                                        },
                                        {
                                            "name": "features",
                                            "value": {
                                                "application-menu:jobqueue": false,
                                                "application-menu:invitation": false,
                                                "control-center:password": true,
                                                "control-center:invitation": false,
                                                "header:user-notification": true
                                            }
                                        },
                                        {
                                            "name": "homePage",
                                            "value": "unifiedinbox"
                                        },
                                        {
                                            "name": "datetime",
                                            "value": {
                                                "timeZone": "UTC"
                                            }
                                        },
                                        {
                                            "name": "language",
                                            "value": "en"
                                        },
                                        {
                                            "name": "maxSizeUpload",
                                            "value": {
                                                "maxSizeUpload": 104857600
                                            }
                                        }
                                    ],
                                    "name": "core"
                                },
                                {
                                    "configurations": [
                                        {
                                            "name": "api",
                                            "value": "http://localhost:1080/jmap"
                                        },
                                        {
                                            "name": "uploadUrl",
                                            "value": "http://localhost:1080/upload"
                                        },
                                        {
                                            "name": "downloadUrl",
                                            "value": "http://localhost:1080/download/{blobId}/{name}"
                                        },
                                        {
                                            "name": "isJmapSendingEnabled",
                                            "value": true
                                        },
                                        {
                                            "name": "composer.attachments",
                                            "value": true
                                        },
                                        {
                                            "name": "maxSizeUpload",
                                            "value": 20971520
                                        },
                                        {
                                            "name": "numberItemsPerPageOnBulkReadOperations",
                                            "value": 30
                                        },
                                        {
                                            "name": "numberItemsPerPageOnBulkDeleteOperations",
                                            "value": 30
                                        },
                                        {
                                            "name": "numberItemsPerPageOnBulkUpdateOperations",
                                            "value": 30
                                        },
                                        {
                                            "name": "drafts",
                                            "value": true
                                        },
                                        {
                                            "name": "view",
                                            "value": "messages"
                                        },
                                        {
                                            "name": "swipeRightAction",
                                            "value": "markAsRead"
                                        },
                                        {
                                            "name": "forwarding",
                                            "value": true
                                        },
                                        {
                                            "name": "isLocalCopyEnabled",
                                            "value": true
                                        }
                                    ],
                                    "name": "linagora.esn.unifiedinbox"
                                },
                                {
                                    "name": "linagora.esn.jobqueue",
                                    "configurations": [
                                        {
                                            "name": "features",
                                            "value": {
                                                "isUserInterfaceEnabled": false
                                            }
                                        }
                                    ]
                                }
                            ]
                        }
                    }
                    """)
        .when()
            .put("api/user/profile")
         .then()
            .statusCode(200);

        String body = given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/user")
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .when(Option.IGNORING_EXTRA_FIELDS)
            .whenIgnoringPaths("domains[0].joined_at", "_id", "id", "domains[0].domain_id", "accounts[0].timestamps")
            .isEqualTo("""
                    {
                     "job_title": "Testing post 3",
                        "service": "Testing service 3",
                        "building_location": "8 rue du docteur Penard 3",
                        "office_location": "Lyon 3",
                        "main_phone": "0677260458 3",
                        "description": "A deeply committed tester 3",
                        "firstname": "admin 2",
                        "lastname": "admin 2",
                        "displayName": "admin 2 admin 2"
                    }
                    """);
    }

    private static String getAdminId() {
        return given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
        .when()
            .get("api/user")
        .then()
            .extract()
            .body()
            .jsonPath()
            .getString("_id");
    }
}
