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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.netty.handler.codec.http.HttpMethod;
import reactor.netty.http.client.HttpClient;

public class TwakeCalendarOpenPaaSAPITest extends OpenPaaSAPITest {
    @RegisterExtension
    static DockerTwakeCalendarExtension extension = new DockerTwakeCalendarExtension();

    OpenPaasUser createUser() {
        return extension.newTestUser();
    }

    @Override
    HttpClient davHttpClient() {
        return extension.davHttpClient();
    }

    @Override
    String domainId() {
        return extension.domainId();
    }

    @Override
    URI backendURI() {
        return extension.getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE, "http");
    }

    @Override
    URI elasticSearchURI() {
        return extension.getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.OPENSEARCH, "http");
    }

    @Test
    void retrieveUserDetailTheOpenPaaSWay() {
        OpenPaasUser user = createUser();

        String body = given()
            .headers("Authorization", basicAuth(user.email()))
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
                  "id": "6881fa229bba2d28bc992669",
                  "_id": "6881fa229bba2d28bc992669",
                  "configurations": {
                    "modules": [
                      {
                        "name": "core",
                        "configurations": [
                          {
                            "name": "davserver",
                            "value": {
                              "frontend": {
                                "url": "https://dav.linagora.com"
                              },
                              "backend": {
                                "url": "https://dav.linagora.com"
                              }
                            }
                          },
                          {
                            "name": "allowDomainAdminToManageUserEmails",
                            "value": null
                          },
                          {
                            "name": "homePage",
                            "value": null
                          },
                          {
                            "name": "language",
                            "value": "en"
                          },
                          {
                            "name": "datetime",
                            "value": {
                              "timeZone": "Europe/Paris",
                              "use24hourFormat": true
                            }
                          },
                          {
                            "name": "businessHours",
                            "value": [
                              {
                                "start": "8:0",
                                "end": "19:0",
                                "daysOfWeek": [
                                  1,
                                  2,
                                  3,
                                  4,
                                  5,
                                  6
                                ]
                              }
                            ]
                          }
                        ]
                      },
                      {
                        "name": "linagora.esn.calendar",
                        "configurations": [
                          {
                            "name": "features",
                            "value": {
                              "isSharingCalendarEnabled": true
                            }
                          },
                          {
                            "name": "workingDays",
                            "value": null
                          },
                          {
                            "name": "hideDeclinedEvents",
                            "value": null
                          }
                        ]
                      },
                      {
                        "name": "linagora.esn.videoconference",
                        "configurations": [
                          {
                            "name": "jitsiInstanceUrl",
                            "value": "https://jitsi.linagora.com"
                          },
                          {
                            "name": "openPaasVideoconferenceAppUrl",
                            "value": "https://jitsi.linagora.com"
                          }
                        ]
                      },
                      {
                        "name": "linagora.esn.contacts",
                        "configurations": [
                          {
                            "name": "features",
                            "value": {
                              "isVirtualFollowingAddressbookEnabled": false,
                              "isVirtualUserAddressbookEnabled": false,
                              "isSharingAddressbookEnabled": true,
                              "isDomainMembersAddressbookEnabled": true
                            }
                          }
                        ]
                      }
                    ]
                  },
                  "accounts": [
                    {
                      "hosted": true,
                      "preferredEmailIndex": 0,
                      "timestamps": {
                        "creation": "1970-01-01T00:00:00.000Z"
                      },
                      "type": "email",
                      "emails": [
                        "{email}"
                      ]
                    }
                  ],
                  "login": {
                    "success": "1970-01-01T00:00:00.000Z",
                    "failures": []
                  },
                  "isPlatformAdmin": false,
                  "preferredEmail": "{email}",
                  "state": [],
                  "domains": [
                    {
                      "domain_id": "6881fa1e50b6ab20eaf0a171",
                      "joined_at": "1970-01-01T00:00:00.000Z"
                    }
                  ],
                  "objectType": "user",
                  "main_phone": "",
                  "followings": 0,
                  "following": false,
                  "followers": 0,
                  "emails": [
                    "{email}"
                  ],
                  "firstname": "{firstname}",
                  "lastname": "{lastname}"
                }
                """.replace("{email}", user.email())
                .replace("{firstname}", user.firstname())
                .replace("{lastname}", user.lastname()));
    }

    @Test
    void calendarConfiguration() {
        OpenPaasUser user = createUser();

        String body = given()
            .headers("Authorization", basicAuth(user.email()))
            .queryParam("scope", "user")
            .body("[{\"name\":\"linagora.esn.calendar\",\"keys\":[\"workingDays\",\"hideDeclinedEvents\"]}]")
            .when()
            .post("api/configurations")
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("[{\"name\":\"linagora.esn.calendar\",\"configurations\":[{\"name\":\"workingDays\",\"value\":null},{\"name\":\"hideDeclinedEvents\",\"value\":null}]}]");
    }

    @Test
    void settingsConfiguration() {
        OpenPaasUser user = createUser();

        String body = given()
            .headers("Authorization", basicAuth(user.email()))
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
                    "value": null
                  },
                  {
                    "name": "businessHours",
                    "value": [
                      {
                        "start": "8:0",
                        "end": "19:0",
                        "daysOfWeek": [
                          1,
                          2,
                          3,
                          4,
                          5,
                          6
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
                    "value": "en"
                  }
                ]
              }
            ]
            """);
    }

    @Test
    void jwtTokenCanBeUsedToCallSabre() {
        OpenPaasUser user = createUser();

        String string = given()
            .headers("Authorization", basicAuth(user.email()))
            .when()
            .post("/api/jwt/generate")
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        DavResponse response = execute(extension.davHttpClient()
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
    void avatar() {
        OpenPaasUser testUser = createUser();
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .when()
            .redirects().follow(false)
            .get("/api/users/" + testUser.id() + "/profile/avatar")
            .then()
            .statusCode(302)
            .header("Location", "https://twcalendar.linagora.com/api/avatars?email=" + testUser.email());
    }

    @Test
    void davServerLocation() {
        OpenPaasUser user = createUser();

        String body = given()
            .headers("Authorization", basicAuth(user.email()))
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

        assertThatJson(body).isEqualTo("[{\"name\":\"core\",\"configurations\":[{\"name\":\"davserver\",\"value\":{\"frontend\":{\"url\":\"https://dav.linagora.com\"},\"backend\":{\"url\":\"https://dav.linagora.com\"}}}]}]");
    }

    @Test
    void logo() {
        given()
            .headers("Authorization", "Basic YWRtaW5Ab3Blbi1wYWFzLm9yZzpzZWNyZXQ=")
            .when()
            .redirects().follow(false)
            .get("api/themes/" + domainId() + "/logo").prettyPeek()
            .then()
            .statusCode(301)
            .header("Location", "https://e-calendrier.avocat.fr/assets/images/white-logo.svg");
    }

    // No data in opensearch
    @Disabled
    @Test
    void peopleSearch(){
    }

    private static String basicAuth(String email) {
        String userPassword = email + ":secret";
        byte[] base64UserPassword = Base64
            .getEncoder()
            .encode(userPassword.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8);
    }
}
