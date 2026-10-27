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

package com.linagora.dav.contracts.card;

import static com.linagora.dav.contracts.card.CardDavContract.STRING;
import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.with;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.CardDavClient.DelegationRight;
import com.linagora.dav.CardDavClient.PublicRight;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.VCardContact;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import net.javacrumbs.jsonunit.core.Option;

public abstract class CardJsonContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
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
            .get("/addressbooks/" + testUser.id())
        .then()
            .extract().asString();

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

    @ParameterizedTest(name = "personal={0}, shared={1}, subscribed={2}, public={3}")
    @CsvSource(delimiter = '|', textBlock = """
        # personal | shared | subscribed | public
          true     | false  | false      | false
          false    | true   | false      | false
          false    | false  | true       | false
          true     | true   | true       | false
          false    | true   | false      | true
          false    | false  | true       | true
          true     | true   | true       | true
        """)
    void shouldNotExposeAnotherUsersAddressBooks(boolean personal, boolean shared, boolean subscribed, boolean publicBooks) {
        // GIVEN Alice, Bob and Cedric belong to the same domain.
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();
        CardDavClient cardDavClient = new CardDavClient(dockerExtension().davHttpClient());

        // AND Bob owns three private books: contacts, collected and a custom book.
        cardDavClient.createAddressBook(bob, "Bob private book");
        cardDavClient.upsertContact(bob, "contacts", "bob-private-contact",
            VCardContact.builder()
                .firstName("Bob")
                .lastName("Private")
                .build()
                .toVCardPayload("bob-private-contact"));

        // AND Cedric delegates his private collected book to Bob only.
        cardDavClient.grantDelegation(cedric, "collected", bob, DelegationRight.READ);

        // AND Bob subscribes to Cedric's public contacts book; the subscription belongs to Bob.
        cardDavClient.setPublicRight(cedric, cedric.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(bob, cedric.id(), "contacts", "Bob subscription");

        // Check the owner can see all five books, including the private contact count.
        String ownerResponse = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
            .queryParam("contactsCount", true)
        .when()
            .get("/addressbooks/" + bob.id())
        .then()
            .statusCode(SC_OK)
            .extract().asString();

        assertThatJson(ownerResponse)
            .inPath("_embedded.dav:addressbook")
            .isArray()
            .hasSize(5);

        assertThatJson(ownerResponse)
            .inPath("_embedded.dav:addressbook")
            .isArray()
            .anySatisfy(book -> assertThatJson(book)
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                        "_links": {
                            "self": {
                                "href": "/addressbooks/{bobId}/contacts.json"
                            }
                        },
                        "numberOfContacts": 1
                    }
                    """.replace("{bobId}", bob.id())));

        // WHEN Alice requests Bob's home with client-controlled listing filters.
        Response response = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .queryParam("personal", personal)
            .queryParam("shared", shared)
            .queryParam("subscribed", subscribed)
            .queryParam("public", publicBooks)
            .queryParam("contactsCount", true)
        .when()
            .get("/addressbooks/" + bob.id())
        .then()
            .statusCode(anyOf(is(SC_OK), is(SC_FORBIDDEN)))
            .extract().response();

        // THEN deny the request or omit every book Alice cannot read, including subscription metadata.
        if (response.statusCode() == SC_OK) {
            assertThatJson(response.asString())
                .inPath("_embedded.dav:addressbook")
                .isArray()
                .isEmpty();
        } else {
            assertThat(response.asString())
                .doesNotContain(
                    "Bob private book",
                    "Bob subscription",
                    "dav:addressbook",
                    "numberOfContacts",
                    "openpaas:source",
                    "/addressbooks/" + bob.id() + "/contacts.json",
                    "/addressbooks/" + cedric.id() + "/contacts.json",
                    "/addressbooks/" + cedric.id() + "/collected.json");
        }
    }

    @Test
    void shouldStillDiscoverAnotherUsersPublicAddressBooks() {
        // GIVEN Bob has private books and one public book.
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        CardDavClient cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        cardDavClient.createAddressBook(bob, "Bob private book");
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);

        // WHEN Alice discovers Bob's public books for subscription.
        String response = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .queryParam("public", true)
            .queryParam("contactsCount", true)
        .when()
            .get("/addressbooks/" + bob.id())
        .then()
            .statusCode(SC_OK)
            .extract().asString();

        // THEN only the public book is returned.
        assertThatJson(response)
            .inPath("_embedded.dav:addressbook")
            .isArray()
            .hasSize(1);

        assertThatJson(response)
            .inPath("_embedded.dav:addressbook[0]._links.self.href")
            .isEqualTo("/addressbooks/" + bob.id() + "/contacts.json");
    }

    @Test
    void shouldForbidReadingAnotherUsersPrivateContacts() {
        // GIVEN Bob has a contact in a private book.
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        CardDavClient cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        String contactUid = "bob-private-contact";
        String firstName = "PrivateContactSentinel";
        String lastName = "ConfidentialSurname";
        String email = "private-contact-sentinel@example.test";
        String phone = "+84911223344";

        cardDavClient.upsertContact(bob, "contacts", contactUid,
            VCardContact.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .tel(phone)
                .build()
                .toVCardPayload(contactUid));

        // AND the owner can read every private value, so the absence checks below are meaningful.
        String ownerResponse = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .queryParam("limit", 2)
            .queryParam("offset", 0)
        .when()
            .get("/addressbooks/" + bob.id() + "/contacts")
        .then()
            .statusCode(SC_OK)
            .extract().asString();

        assertThat(ownerResponse)
            .contains(contactUid, firstName, lastName, email, phone);

        // WHEN Alice tries to read the same private book.
        String forbiddenResponse = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .queryParam("limit", 2)
            .queryParam("offset", 0)
        .when()
            .get("/addressbooks/" + bob.id() + "/contacts")
        .then()
            .statusCode(SC_FORBIDDEN)
            .extract().asString();

        // THEN both the status and the body deny access: no contact values or contact payload are returned.
        assertThat(forbiddenResponse)
            .doesNotContain(
                contactUid,
                firstName,
                lastName,
                email,
                phone,
                "dav:item",
                "BEGIN:VCARD",
                "\"vcard\"");
    }

    @Test
    void shouldAllowAddressBookDiscoveryJson() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String response = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "0")
        .when()
            .request("PROPFIND", "/principals/users/" + testUser.id())
        .then()
            .statusCode(200)
            .extract().asString();

        assertThatJson(response).isEqualTo(String.format("""
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
            .get("/addressbooks/" + testUser.id() + "/contacts")
            .then()
            .extract().asString();

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
    void shouldReturnEachContactOnceAcrossSortedPages() {
        OpenPaasUser user = dockerExtension().newTestUser();
        String addressBook = "/addressbooks/" + user.id() + "/contacts";
        int contactCount = 60;
        int pageSize = 10;
        Set<String> expectedHrefs = new HashSet<>();

        // Given contacts with the same stored fn initial across page boundaries.
        for (int i = 0; i < contactCount; i++) {
            String cardUri = "page-" + i + ".vcf";
            expectedHrefs.add(addressBook + "/" + cardUri);
            String vcard = """
                BEGIN:VCARD
                VERSION:3.0
                FN:Alice {index}
                UID:page-{index}
                END:VCARD
                """.replace("{index}", String.valueOf(i));
            given()
                .header("Authorization", user.impersonatedBasicAuth())
                .contentType("text/vcard; charset=utf-8")
                .body(vcard.getBytes(StandardCharsets.UTF_8))
            .when()
                .put(addressBook + "/" + cardUri)
            .then()
                .statusCode(201);
        }

        // When listing every page by fn, with _id as a control.
        for (String sort : List.of("_id", "fn")) {
            List<String> hrefs = new ArrayList<>();
            for (int offset = 0; offset < contactCount; offset += pageSize) {
                List<String> page = given()
                    .headers("Authorization", user.impersonatedBasicAuth())
                    .queryParam("limit", pageSize)
                    .queryParam("offset", offset)
                    .queryParam("sort", sort)
                .when()
                    .get(addressBook)
                .then()
                    .statusCode(200)
                    .extract()
                    .jsonPath()
                    .getList("_embedded.'dav:item'._links.self.href", String.class);
                assertThat(page).as("sort=%s, offset=%s", sort, offset).hasSize(pageSize);
                hrefs.addAll(page);
            }

            // Then no contact is missed or repeated across page boundaries.
            assertThat(hrefs)
                .as("sort=%s", sort)
                .containsExactlyInAnyOrderElementsOf(expectedHrefs);
        }
    }

    @Test
    void shouldAListAddressBookContentWhenData() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .extract().asString();

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

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
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
                }""")
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .extract().asString();

            assertThatJson(response)
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

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .then()
            .extract().asString();

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
            .get("/addressbooks/" + testUser.id() + "/contacts")
            .then()
            .extract().asString();

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
    void putShouldStripInlinePhotoFromJCard() {
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
                        "photo",
                        {},
                        "uri",
                        "data:image/jpeg;base64,%s"
                    ]
                ],
                []
            ]""".formatted(CardDavContract.BASE64_IMAGE);

        with()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Content-Type", "application/vcard+json")
            .body(payload)
            .put("addressbooks/" + testUser.id() + "/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf");

        String storedCard = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(storedCard)
            .doesNotContain("PHOTO")
            .contains("FN:First name Last name");
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
            .get("/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .extract().asString();

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
            .post("addressbooks/" + testUser.id())
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
            .get("/addressbooks/" + testUser.id())
        .then()
            .extract().asString();

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
            .post("addressbooks/" + testUser.id())
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("application/vcard+json")
            .body("""
                         {
                           "dav:name": "custom 3",
                           "carddav:description": "",
                           "state": ""
                         }""".getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/addressbooks/" + testUser.id() + "/86dcbff9-c748-4338-8051-6071b4389586")
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
            .get("/addressbooks/" + testUser.id())
        .then()
            .extract().asString();

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
            .post("addressbooks/" + testUser.id())
        .then()
            .statusCode(201);

        given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .delete("addressbooks/" + testUser.id() + "/86dcbff9-c748-4338-8051-6071b4389586")
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
            .get("/addressbooks/" + testUser.id())
        .then()
            .extract().asString();

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

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/vcard")
            .queryParam("export", true)
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .extract().asString();

        assertThat(response)
            .isEqualToNormalizingNewlines("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe
                N:Doe;John;;;
                EMAIL:john.doe@example.com
                UID:123456789
                END:VCARD
                """);
    }

    @Test
    void searchWhenRelated() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/json, text/plain, */*")
            .queryParam("limit", 30)
            .queryParam("page", 1)
            .queryParam("search", "zapo")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .extract().asString();

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

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .headers("Accept", "application/json, text/plain, */*")
            .queryParam("limit", 30)
            .queryParam("page", 1)
            .queryParam("search", "unrelated")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .extract().asString();

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
    public void move() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + testUser.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MOVE", "/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(201), is(204)));

        String response1 = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/contacts")
            .then()
            .extract().asString();
        String response2 = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/collected")
            .then()
            .extract().asString();
        assertThat(response1).doesNotContain("abcdef.vcf");
        assertThat(response2).contains("abcdef.vcf");
    }

    @Test
    void copy() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + testUser.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("COPY", "/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String response1 = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/contacts")
            .then()
            .extract().asString();
        String response2 = given()
            .headers("Authorization", testUser.impersonatedBasicAuth())
            .queryParam("limit", 20)
            .queryParam("offset", 0)
            .queryParam("sort", "fn")
            .queryParam("userId", testUser.id())
            .when()
            .get("/addressbooks/" + testUser.id() + "/collected")
            .then()
            .extract().asString();
        assertThat(response1).contains("abcdef.vcf");
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
            .post("addressbooks/" + alice.id() + "/contacts")
            .then()
            .statusCode(204);

        // And alice has a contact
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob cannot update alice contacts
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
        .then()
            .statusCode(403);
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
            .post("addressbooks/" + alice.id() + "/contacts")
            .then()
            .statusCode(204);

        // And alice has a contact
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        // THEN bob can read the contact
        given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .headers("Accept", "application/vcard+json")
        .when()
            .get("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);

        // AND bob can update alice contacts
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
        .then()
            .statusCode(201);
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
            .post("addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(204);
    }

    @Test
    void settingShareeRead() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // Alice has a contact
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

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
            .post("addressbooks/" + alice.id() + "/contacts")
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
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
        .then()
            .statusCode(403);
    }

    @Test
    void settingShareeWrite() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // Alice has a contact
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

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
            .post("addressbooks/" + alice.id() + "/contacts")
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
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
        .then()
            .statusCode(201);

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
            .post("addressbooks/" + alice.id() + "/contacts")
            .then()
            .statusCode(403);
    }

    @Test
    void settingShareeSharing() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // Alice has a contact
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

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
            .post("addressbooks/" + alice.id() + "/contacts")
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
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/ghijklmno.vcf")
        .then()
            .statusCode(201);

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
            .post("addressbooks/" + alice.id() + "/contacts")
            .then()
            .statusCode(204);
    }

    @Test
    @Disabled("wait to new image")
    void shouldListShareeCalendar() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // Alice has a contact
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + alice.id() + "/collected/abcdef.vcf")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Alexandre ZAPOLSKY
                N:ZAPOLSKY;Alexandre;;;
                EMAIL:zapo@lina.com
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + alice.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

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
            .post("addressbooks/" + alice.id() + "/contacts")
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
            .get("/addressbooks/" + bob.id())
        .then()
            .extract().asString();

        assertThatJson(response)
            .whenIgnoringPaths("_embedded.dav:addressbook[2]._links.self.href")
            .isEqualTo("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/BOB_ID.json"
                        }
                    },
                    "_embedded": {
                        "dav:addressbook": [
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/BOB_ID/collected.json"
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
                                        "principal": "principals/users/BOB_ID",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/BOB_ID/contacts.json"
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
                                        "principal": "principals/users/BOB_ID",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null
                            },
                            {
                                "_links": {
                                    "self": {
                                        "href": "/addressbooks/BOB_ID/a72cf3f8-0dc2-4416-bf66-43e5aeaeff24.json"
                                    }
                                },
                                "dav:name": "",
                                "carddav:description": "",
                                "dav:acl": [
                                    "dav:read",
                                    "dav:write"
                                ],
                                "dav:share-access": 2,
                                "openpaas:subscription-type": "delegation",
                                "type": "",
                                "state": "",
                                "numberOfContacts": 1,
                                "acl": [
                                    {
                                        "privilege": "{DAV:}read",
                                        "principal": "principals/users/BOB_ID",
                                        "protected": true
                                    },
                                    {
                                        "privilege": "{DAV:}write-properties",
                                        "principal": "principals/users/BOB_ID",
                                        "protected": true
                                    }
                                ],
                                "dav:group": null,
                                "openpaas:source": "/addressbooks/ALICE_ID/contacts.json"
                            }
                        ]
                    }
                }
            """.replace("ALICE_ID", alice.id())
                .replace("BOB_ID", bob.id()));
    }
}
