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

import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;
import com.linagora.dav.AddressBookURL;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

public abstract class CardDavDelegationContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CardDavClient cardDavClient;
    private OpenPaasUser alice;
    private OpenPaasUser bob;
    private OpenPaasUser cedric;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());

        alice = dockerExtension().newTestUser();
        bob = dockerExtension().newTestUser();
        cedric = dockerExtension().newTestUser();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();
    }

    @Test
    void listAddressBooksShouldShowDelegatedAddressBook() {
        // GIVEN Bob has a address book named "collected"
        // WHEN Bob delegates that address book to Alice
        cardDavClient.grantDelegation(bob, "collected", alice, CardDavClient.DelegationRight.ADMIN);

        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
            .when()
            .get("/addressbooks/" + alice.id() + ".json")
            .then()
            .extract()
            .body()
            .asString();

        // THEN a copy of Bob's address book is visible in Alice's address book list
        assertThatJson(response)
            .inPath("_embedded.dav:addressbook[2]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
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
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        }
                    ],
                    "dav:group": null,
                    "openpaas:source": "/addressbooks/{bobId}/collected.json"
                }
                """.replace("{aliceId}", alice.id()))
                .replace("{bobId}", bob.id()));
    }

    @Test
    void listAddressBooksShouldNotShowDelegatedAddressBookWhenDelegationHasBeenRevoked() {
        // GIVEN Alice has a copy of Bob's address book
        cardDavClient.grantDelegation(bob, "collected", alice, CardDavClient.DelegationRight.READ);

        // WHEN Bob revokes rights for Alice
        cardDavClient.revokeDelegation(bob, "collected", alice);

        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
            .when()
            .get("/addressbooks/" + alice.id() + ".json")
            .then()
            .extract()
            .body()
            .asString();

        // THEN Bob's address book is removed from Alice's address book list
        assertThat(response).doesNotContain("\"openpaas:source\":\"\\/addressbooks\\/" + bob.id() + "\\/collected.json\"");
    }

    @Test
    void listAddressBooksShouldShowSharingRightOfDelegation() {
        // GIVEN Bob has an address book
        // WHEN Bob delegates that address book to Alice
        cardDavClient.grantDelegation(bob, "collected", alice, CardDavClient.DelegationRight.ADMIN);

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

        // THEN the sharing right is shown on Bob's source address book
        assertThatJson(response)
            .inPath("_embedded.dav:addressbook[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/{bobId}/collected.json"
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
                            "principal": "principals/users/{bobId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-content",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}bind",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}unbind",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        }
                    ],
                    "dav:group": null
                }
                """.replace("{aliceId}", alice.id()))
                .replace("{bobId}", bob.id()));
    }

    @Test
    void listAddressBooksShouldNotShowSharingRightOfDelegationWhenCopiedAddressBookHasBeenDeleted() {
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser bob = dockerExtension().newTestUser();

        // GIVEN Bob has an address book
        // AND Bob delegates that address book to Alice
        cardDavClient.grantDelegation(bob, "collected", alice, CardDavClient.DelegationRight.ADMIN);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // WHEN Alice deletes the copy of Bob's address book
        cardDavClient.deleteAddressBook(alice, addressBookURL.addressBookId());

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

        // THEN the sharing right for Alice is removed on Bob's source address book
        assertThat(response).doesNotContain(alice.id());
    }

    @Test
    void userCannotSeeAddressBookOfAnotherUser() {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, bob.id(), addressBook))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void delegatedUserCanSeeDelegatedAddressBook() {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, CardDavClient.DelegationRight.READ);

        String response = cardDavClient.getContacts(alice, bob.id(), addressBook);

        assertThat(response).contains("John Doe");
    }

    @Test
    void userCannotSeeAddressBookOfAnotherUserWhenDelegationHasBeenRevoked() {
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, CardDavClient.DelegationRight.READ);

        cardDavClient.revokeDelegation(bob, addressBook, alice);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, bob.id(), addressBook))
            .hasMessageContaining("Unexpected status code: 403");
    }
}
