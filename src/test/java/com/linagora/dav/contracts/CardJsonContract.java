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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static com.linagora.dav.contracts.CardDavContract.STRING;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.with;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;

import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

public abstract class CardJsonContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();
    }

    @Test
    void shouldListAddressBooks() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get("/addressbooks/" + testUser.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:addressbook": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/collected.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/contacts.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            }
                        ]
                    }
                }""", testUser.id(), testUser.id(), testUser.id(), testUser.id(), testUser.id()));
    }

    @Test
    void shouldAllowAddressBookDiscoveryJson() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/principals/users/" + testUser.id()));

        assertThat(response.status()).isEqualTo(200);
        assertThatJson(response.body()).isEqualTo(String.format("""
            {
                "alternate-URI-set": [
                    "mailto:%s"
                ],
                "principal-URL": "principals\\/users\\/%s\\/",
                "group-member-set": [],
                "group-membership": [
                    "principals\\/domains\\/%s\\/"
                ]
            }""", testUser.email(), testUser.id(), dockerExtension().domainId()));
    }

    @Test
    void shouldAListAddressBookContent() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
            .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json")
            .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response).isEqualTo(String.format("""
            {
                "_links": {
                    "self": {
                        "href": "/addressbooks/%s/contacts.json"
                    }
                },
                "dav:syncToken": 1,
                "_embedded": {
                    "dav:item": []
                }
            }""", testUser.id()));
    }

    @Test
    void shouldAListAddressBookContentWhenData() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("_embedded.dav:item[0].etag")
            .isEqualTo(String.format("""
            {
                 "_links": {
                     "self": {
                         "href": "/addressbooks/%s/contacts.json"
                     }
                 },
                 "dav:syncToken": 2,
                 "_embedded": {
                     "dav:item": [
                         {
                             "_links": {
                                 "self": {
                                     "href": "/addressbooks/%s/contacts/abcdef.vcf"
                                 }
                             },
                             "etag": "\\"${json-unit.any-string}\\"",
                             "data": [
                                 "vcard",
                                 [
                                     [
                                         "version",
                                         {},
                                         "text",
                                         "3.0"
                                     ],
                                     [
                                         "fn",
                                         {},
                                         "text",
                                         "John Doe"
                                     ],
                                     [
                                         "n",
                                         {},
                                         "text",
                                         [
                                             "Doe",
                                             "John",
                                             "",
                                             "",
                                             ""
                                         ]
                                     ],
                                     [
                                         "email",
                                         {},
                                         "text",
                                         "john.doe@example.com"
                                     ],
                                     [
                                         "uid",
                                         {},
                                         "text",
                                         "123456789"
                                     ]
                                 ]
                             ]
                         }
                     ]
                 }
             }""", testUser.id(), testUser.id()));
    }

    @Test
    void propfindOnAddressBooks() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("""
                {
                    "properties": [
                        "{DAV:}displayname",
                        "{urn:ietf:params:xml:ns:carddav}addressbook-description",
                        "{DAV:}acl",
                        "{DAV:}invite",
                        "{DAV:}share-access",
                        "{DAV:}group",
                        "{http://open-paas.org/contacts}subscription-type",
                        "{http://open-paas.org/contacts}source",
                        "{http://open-paas.org/contacts}type",
                        "{http://open-paas.org/contacts}state",
                        "{http://open-paas.org/contacts}numberOfContacts",
                        "acl"
                    ]
                }""")));

            assertThatJson(response.body())
                .isEqualTo(String.format("""
                    {
                          "{DAV:}displayname": "",
                          "{urn:ietf:params:xml:ns:carddav}addressbook-description": "",
                          "{DAV:}acl": [
                              "dav:read",
                              "dav:write"
                          ],
                          "{http:\\/\\/open-paas.org\\/contacts}type": "",
                          "{http:\\/\\/open-paas.org\\/contacts}state": "",
                          "acl": [
                              {
                                  "privilege": "{DAV:}all",
                                  "principal": "principals\\/users\\/%s",
                                  "protected": true
                              }
                          ],
                          "{DAV:}invite": [
                              {
                                  "href": "principals\\/users\\/%s",
                                  "principal": "principals\\/users\\/%s",
                                  "properties": [],
                                  "access": 1,
                                  "comment": null,
                                  "inviteStatus": 2
                              }
                          ],
                          "{DAV:}share-access": 1,
                          "{http:\\/\\/open-paas.org\\/contacts}numberOfContacts": 1
                      }""", testUser.id(), testUser.id(), testUser.id()));
    }

    @Test
    void getContactDetail() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json/abcdef.vcf")
            .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("[1][1][3]") // Path to prodid value
            .isEqualTo("""
                [
                    "vcard",
                    [
                        [
                            "version",
                            {},
                            "text",
                            "4.0"
                        ],
                        [
                            "prodid",
                            {},
                            "text",
                            "-//Sabre//Sabre VObject 4.1.3//EN"
                        ],
                        [
                            "fn",
                            {},
                            "text",
                            "John Doe"
                        ],
                        [
                            "n",
                            {},
                            "text",
                            [
                                "Doe",
                                "John",
                                "",
                                "",
                                ""
                            ]
                        ],
                        [
                            "email",
                            {},
                            "text",
                            "john.doe@example.com"
                        ],
                        [
                            "uid",
                            {},
                            "text",
                            "123456789"
                        ]
                    ]
                ]""");
    }

    @Test
    void shouldPutContact() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String payload = """
            [
                "vcard",
                [
                    [
                        "version",
                        {},
                        "text",
                        "4.0"
                    ],
                    [
                        "uid",
                        {},
                        "text",
                        "d1ed30c8-15e7-4e81-a7dd-f5c60beab420"
                    ],
                    [
                        "fn",
                        {},
                        "text",
                        "First name Last name"
                    ],
                    [
                        "n",
                        {},
                        "text",
                        [
                            "Last name",
                            "First name"
                        ]
                    ],
                    [
                        "categories",
                        {},
                        "text",
                        "fired"
                    ],
                    [
                        "org",
                        {},
                        "text",
                        [
                            "LINAGORA"
                        ]
                    ],
                    [
                        "role",
                        {},
                        "text",
                        "Branleur"
                    ],
                    [
                        "email",
                        {
                            "type": [
                                "Work"
                            ]
                        },
                        "text",
                        "mailto:branleur@linagora.com"
                    ]
                ],
                []
            ]""";
        with()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Content-Type", "application/vcard+json")
            .body(payload)
            .put("addressbooks/" + testUser.id() + "/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf");

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json")
            .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("_embedded.dav:item[0].etag")
            .isEqualTo(String.format("""
                {
                      "_links": {
                          "self": {
                              "href": "/addressbooks/%s/contacts.json"
                          }
                      },
                      "dav:syncToken": 2,
                      "_embedded": {
                          "dav:item": [
                              {
                                  "_links": {
                                      "self": {
                                          "href": "/addressbooks/%s/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf"
                                      }
                                  },
                                  "etag": "\\"da878e05109f56c2bb47f454075de217\\"",
                                  "data": [
                                      "vcard",
                                      [
                                          [
                                              "version",
                                              {},
                                              "text",
                                              "4.0"
                                          ],
                                          [
                                              "uid",
                                              {},
                                              "text",
                                              "d1ed30c8-15e7-4e81-a7dd-f5c60beab420"
                                          ],
                                          [
                                              "fn",
                                              {},
                                              "text",
                                              "First name Last name"
                                          ],
                                          [
                                              "n",
                                              {},
                                              "text",
                                              [
                                                  "Last name",
                                                  "First name",
                                                  "",
                                                  "",
                                                  ""
                                              ]
                                          ],
                                          [
                                              "categories",
                                              {},
                                              "text",
                                              "fired"
                                          ],
                                          [
                                              "org",
                                              {},
                                              "text",
                                              [
                                                  "LINAGORA"
                                              ]
                                          ],
                                          [
                                              "role",
                                              {},
                                              "text",
                                              "Branleur"
                                          ],
                                          [
                                              "email",
                                              {
                                                  "type": "Work"
                                              },
                                              "text",
                                              "mailto:branleur@linagora.com"
                                          ]
                                      ]
                                  ]
                              }
                          ]
                      }
                  }""", testUser.id(), testUser.id()));
    }

    @Test
    void putShouldUpdateContacts() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String payload = """
            [
                "vcard",
                [
                    [
                        "version",
                        {},
                        "text",
                        "4.0"
                    ],
                    [
                        "uid",
                        {},
                        "text",
                        "d1ed30c8-15e7-4e81-a7dd-f5c60beab420"
                    ],
                    [
                        "fn",
                        {},
                        "text",
                        "First name Last name"
                    ],
                    [
                        "n",
                        {},
                        "text",
                        [
                            "Last name",
                            "First name"
                        ]
                    ],
                    [
                        "categories",
                        {},
                        "text",
                        "fired"
                    ],
                    [
                        "org",
                        {},
                        "text",
                        [
                            "LINAGORA"
                        ]
                    ],
                    [
                        "role",
                        {},
                        "text",
                        "Branleur"
                    ],
                    [
                        "email",
                        {
                            "type": [
                                "Work"
                            ]
                        },
                        "text",
                        "mailto:branleur@linagora.com"
                    ]
                ],
                []
            ]""";
        with()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Content-Type", "application/vcard+json")
            .body(payload)
            .put("addressbooks/" + testUser.id() + "/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf");

        with()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Content-Type", "application/vcard+json")
            .body(payload)
            .put("addressbooks/" + testUser.id() + "/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf");

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("_embedded.dav:item[0].etag")
            .isEqualTo(String.format("""
                {
                       "_links": {
                           "self": {
                               "href": "/addressbooks/%s/contacts.json"
                           }
                       },
                       "dav:syncToken": 3,
                       "_embedded": {
                           "dav:item": [
                               {
                                   "_links": {
                                       "self": {
                                           "href": "/addressbooks/%s/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf"
                                       }
                                   },
                                   "etag": "\\"da878e05109f56c2bb47f454075de217\\"",
                                   "data": [
                                       "vcard",
                                       [
                                           [
                                               "version",
                                               {},
                                               "text",
                                               "4.0"
                                           ],
                                           [
                                               "uid",
                                               {},
                                               "text",
                                               "d1ed30c8-15e7-4e81-a7dd-f5c60beab420"
                                           ],
                                           [
                                               "fn",
                                               {},
                                               "text",
                                               "First name Last name"
                                           ],
                                           [
                                               "n",
                                               {},
                                               "text",
                                               [
                                                   "Last name",
                                                   "First name",
                                                   "",
                                                   "",
                                                   ""
                                               ]
                                           ],
                                           [
                                               "categories",
                                               {},
                                               "text",
                                               "fired"
                                           ],
                                           [
                                               "org",
                                               {},
                                               "text",
                                               [
                                                   "LINAGORA"
                                               ]
                                           ],
                                           [
                                               "role",
                                               {},
                                               "text",
                                               "Branleur"
                                           ],
                                           [
                                               "email",
                                               {
                                                   "type": "Work"
                                               },
                                               "text",
                                               "mailto:branleur@linagora.com"
                                           ]
                                       ]
                                   ]
                               }
                           ]
                       }
                   }""", testUser.id(), testUser.id()));
    }

    @Test
    void putShouldFailIfWrongState() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String payload = """
            [
                "vcard",
                [
                    [
                        "version",
                        {},
                        "text",
                        "4.0"
                    ],
                    [
                        "uid",
                        {},
                        "text",
                        "d1ed30c8-15e7-4e81-a7dd-f5c60beab420"
                    ],
                    [
                        "fn",
                        {},
                        "text",
                        "First name Last name"
                    ],
                    [
                        "n",
                        {},
                        "text",
                        [
                            "Last name",
                            "First name"
                        ]
                    ],
                    [
                        "categories",
                        {},
                        "text",
                        "fired"
                    ],
                    [
                        "org",
                        {},
                        "text",
                        [
                            "LINAGORA"
                        ]
                    ],
                    [
                        "role",
                        {},
                        "text",
                        "Branleur"
                    ],
                    [
                        "email",
                        {
                            "type": [
                                "Work"
                            ]
                        },
                        "text",
                        "mailto:branleur@linagora.com"
                    ]
                ],
                []
            ]""";
        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Content-Type", "application/vcard+json")
            .headers("If-Match", "Bad")
            .body(payload)
        .when()
            .put("addressbooks/" + testUser.id() + "/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf")
        .then()
            .statusCode(412);
    }

    @Test
    void createAddressBook() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .body("""
                {
                    "id": "86dcbff9-c748-4338-8051-6071b4389586",
                    "dav:name": "evb",
                    "dav:acl": [
                        "dav:read",
                        "dav:write"
                    ],
                    "type": "user"
                }""")
            .when()
            .post("addressbooks/" + testUser.id() + ".json")
            .then()
            .statusCode(201);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get("/addressbooks/" + testUser.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:addressbook": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/86dcbff9-c748-4338-8051-6071b4389586.json"
                                    }
                                },
                                "dav:name": "evb",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "user",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/collected.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/contacts.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            }
                        ]
                    }
                }
                """, testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id(),
                testUser.id()));
    }

    @Test
    void updateAddressBook() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .body("""
                {
                    "id": "86dcbff9-c748-4338-8051-6071b4389586",
                    "dav:name": "evb",
                    "dav:acl": [
                        "dav:read",
                        "dav:write"
                    ],
                    "type": "user"
                }""")
        .when()
            .post("addressbooks/" + testUser.id() + ".json")
        .then()
            .statusCode(201);

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/vcard+json"))
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/86dcbff9-c748-4338-8051-6071b4389586.json")
            .send(body("""
                         {
                           "dav:name": "custom 3",
                           "carddav:description": "",
                           "state": ""
                         }""")));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get("/addressbooks/" + testUser.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:addressbook": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/86dcbff9-c748-4338-8051-6071b4389586.json"
                                    }
                                },
                                "dav:name": "custom 3",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "user",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/collected.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/contacts.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            }
                        ]
                    }
                }
                """, testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id(),
                testUser.id()));
    }

    @Test
    void deleteAddressBook() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .body("""
                {
                    "id": "86dcbff9-c748-4338-8051-6071b4389586",
                    "dav:name": "evb",
                    "dav:acl": [
                        "dav:read",
                        "dav:write"
                    ],
                    "type": "user"
                }""")
        .when()
            .post("addressbooks/" + testUser.id() + ".json")
        .then()
            .statusCode(201);

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .delete("addressbooks/" + testUser.id() + "/86dcbff9-c748-4338-8051-6071b4389586.json")
        .then()
            .statusCode(204);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get("/addressbooks/" + testUser.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:addressbook": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/collected.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/contacts.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            }
                        ]
                    }
                }
                """, testUser.id(), testUser.id(), testUser.id(),
                testUser.id(), testUser.id(), testUser.id(),
                testUser.id()));
    }

    @Test
    void exportAddressBook() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard")
            .queryParam("export", true)
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json")
        .then()
            .extract()
            .body()
            .asString();

        assertThat(response)
            .isEqualToNormalizingNewlines("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe\n" +
                "N:Doe;John;;;\n" +
                "EMAIL:john.doe@example.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n");
    }

    @Test
    void searchWhenRelated() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/json, text/plain, */*")
            .queryParam("limit", 30)
            .queryParam("page", 1)
            .queryParam("search", "zapo")
        .when()
            .get("/addressbooks/" + testUser.id() + ".json/contacts")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("_embedded.dav:item[0].etag")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s/contacts.json"
                        }
                    },
                    "dav:syncToken": 2,
                    "_embedded": {
                        "dav:item": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/contacts/abcdef.vcf"
                                    }
                                },
                                "etag": "\\"0ea00e213a637ce3c8de512a8b7ad76c\\"",
                                "data": [
                                    "vcard",
                                    [
                                        [
                                            "version",
                                            {},
                                            "text",
                                            "3.0"
                                        ],
                                        [
                                            "fn",
                                            {},
                                            "text",
                                            "Alexandre ZAPOLSKY"
                                        ],
                                        [
                                            "n",
                                            {},
                                            "text",
                                            [
                                                "ZAPOLSKY",
                                                "Alexandre",
                                                "",
                                                "",
                                                ""
                                            ]
                                        ],
                                        [
                                            "email",
                                            {},
                                            "text",
                                            "zapo@lina.com"
                                        ],
                                        [
                                            "uid",
                                            {},
                                            "text",
                                            "123456789"
                                        ]
                                    ]
                                ]
                            }
                        ]
                    }
                }
                """, testUser.id(), testUser.id()));
    }

    @Test
    void searchWhenUnrelatedShouldReturnNoResults() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/json, text/plain, */*")
            .queryParam("limit", 30)
            .queryParam("page", 1)
            .queryParam("search", "unrelated")
        .when()
            .get("/addressbooks/" + testUser.id() + ".json/contacts")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s/contacts.json"
                        }
                    },
                    "dav:syncToken": 2,
                    "_embedded": {
                        "dav:item": []
                    }
                }
                """, testUser.id()));
    }

    @Test
    void move() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + testUser.id() + "/collected/abcdef.vcf"))
            .request(HttpMethod.valueOf("MOVE"))
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        String response1 = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/contacts.json")
            .then()
            .extract()
            .body()
            .asString();
        String response2 = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/collected.json")
            .then()
            .extract()
            .body()
            .asString();
        assertThat(response1).doesNotContain("abcdef.vcf");
        assertThat(response2).contains("abcdef.vcf");
    }

    @Test
    void settingAddressBookWorldVisible() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // Given alice set her calendar visible publicly
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body("""
                {"dav:publish-addressbook":{"privilege":"{DAV:}read"}}""")
        .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
            .then()
            .statusCode(204);

        // And alice has a contact
        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf"))
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob cannot update alice contacts
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
            .send(body(STRING)));
        assertThat(status).isEqualTo(403);
    }

    @Test
    void settingAddressBookWorldWritable() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // Given alice set her calendar visible publicly
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body("""
                {"dav:publish-addressbook":{"privilege":"{DAV:}write"}}""")
        .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
            .then()
            .statusCode(204);

        // And alice has a contact
        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf"))
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob can update alice contacts
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
            .send(body(STRING)));
        assertThat(status).isEqualTo(201);
    }

    @Test
    void settingSharee() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
                {"dav:share-resource":{"dav:sharee":[{"dav:href":"principals/users/%s","dav:share-access":1}]}}""", testUser.id()))
        .when()
            .post("addressbooks/" + testUser.id() + "/contacts.json")
        .then()
            .statusCode(204);
    }

    @Test
    void settingShareeRead() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // Alice has a contact
        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf"))
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
    {
       "dav:share-resource": {
        "dav:sharee": [
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 2
            },
            {
                "dav:href": "principals/users/%s",
                "dav:share-access": 1
            }
        ]
      }
    }""", bob.email(), alice.id()))
        .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
        .then()
            .statusCode(204);

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob cannot update alice contacts
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
            .send(body(STRING)));
        assertThat(status).isEqualTo(403);
    }

    @Test
    void settingShareeWrite() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // Alice has a contact
        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf"))
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
    {
       "dav:share-resource": {
        "dav:sharee": [
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 3
            },
            {
                "dav:href": "principals/users/%s",
                "dav:share-access": 1
            }
        ]
      }
    }""", bob.email(), alice.id()))
        .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
        .then()
            .statusCode(204);

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob can update alice contacts
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
            .send(body(STRING)));
        assertThat(status).isEqualTo(201);

        // And bob cannot share
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
    {
       "dav:share-resource": {
        "dav:sharee": [
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 5
            },
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 5
            },
            {
                "dav:href": "principals/users/%s",
                "dav:share-access": 1
            }
        ]
      }
    }""", cedric.email(), bob.email(), alice.id()))
            .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
            .then()
            .statusCode(403);
    }

    @Test
    void settingShareeSharing() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // Alice has a contact
        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf"))
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
    {
       "dav:share-resource": {
        "dav:sharee": [
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 5
            },
            {
                "dav:href": "principals/users/%s",
                "dav:share-access": 1
            }
        ]
      }
    }""", bob.email(), alice.id()))
        .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
        .then()
            .statusCode(204);

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob can update alice contacts
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
            .send(body(STRING)));
        assertThat(status).isEqualTo(201);

        // And bob cannot share
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
    {
       "dav:share-resource": {
        "dav:sharee": [
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 5
            },
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 5
            },
            {
                "dav:href": "principals/users/%s",
                "dav:share-access": 1
            }
        ]
      }
    }""", cedric.email(), bob.email(), alice.id()))
            .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
            .then()
            .statusCode(204);
    }

    @Test
    void shouldListShareeCalendar() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // Alice has a contact
        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> alice.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf"))
            .put()
            .uri("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
            .send(body("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:Alexandre ZAPOLSKY\n" +
                "N:ZAPOLSKY;Alexandre;;;\n" +
                "EMAIL:zapo@lina.com\n" +
                "UID:123456789\n" +
                "END:VCARD\n")));

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
            .headers("Content-Type", "application/vcard+json")
            .body(String.format("""
    {
       "dav:share-resource": {
        "dav:sharee": [
            {
                "dav:href": "mailto:%s",
                "dav:share-access": 5
            },
            {
                "dav:href": "principals/users/%s",
                "dav:share-access": 1
            }
        ]
      }
    }""", bob.email(), alice.id()))
        .when()
            .post("addressbooks/" + alice.id() + "/contacts.json")
        .then()
            .statusCode(204);

        // THEN bob can read the contact
        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get("/addressbooks/" + bob.id() + ".json")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .whenIgnoringPaths("_embedded.dav:addressbook[2]._links.self.href")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:addressbook": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/collected.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/contacts.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 1,
                                "openpaas:subscription-type": null,
                                "type": "",
                                "state": "",
                                "numberOfContacts": 0,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}all",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/%s/a72cf3f8-0dc2-4416-bf66-43e5aeaeff24.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 5,
                                "openpaas:subscription-type": "delegation",
                                "type": "",
                                "state": "",
                                "numberOfContacts": null,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/%s",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null,
                                "openpaas:source": "/addressbooks/%s/contacts.json"
                            }
                        ]
                    }
                }
            """, bob.id(), bob.id(), bob.id(), bob.id(), bob.id(), bob.id(), bob.id(), bob.id(), alice.id()));
    }
}
