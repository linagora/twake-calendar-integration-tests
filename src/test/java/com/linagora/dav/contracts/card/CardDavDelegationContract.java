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

import static com.linagora.dav.CardDavClient.*;
import static com.linagora.dav.TestUtil.awaitAtMost;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_OK;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AddressBookURL;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import com.linagora.dav.XMLUtil;
import io.restassured.RestAssured;
import net.javacrumbs.jsonunit.core.Option;
import io.restassured.http.ContentType;

public abstract class CardDavDelegationContract {
    private static final int ACCESS_NOACCESS = 4;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CardDavClient cardDavClient;
    private OpenPaasUser alice;
    private OpenPaasUser bob;
    private OpenPaasUser cedric;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());

        alice = dockerExtension().newTestUser();
        bob = dockerExtension().newTestUser();
        cedric = dockerExtension().newTestUser();

        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    @Disabled("Wait for a new Sabre image. Upstream PR: https://github.com/linagora/esn-sabre/pull/541")
    protected void adminDelegationShouldNotifySourceAndNewCopy() throws IOException {
        // Roles: Alice owns the source address book; Bob is its ADMIN delegate; Cedric is the new sharee.
        // Given Alice grants Bob ADMIN access, allowing him to manage delegations through his copy.
        cardDavClient.grantDelegation(alice, "collected", bob, DelegationRight.ADMIN);
        AddressBookURL bobCopy = cardDavClient.findSubscribedAddressBook(bob, alice.id(), "collected").orElseThrow();

        // Listen after setup so only messages from Bob's next action are captured.
        BlockingQueue<JsonNode> sourceMessagesQueue = listenToAddressBookExchange("sabre:addressbook:updated");
        BlockingQueue<JsonNode> createdMessagesQueue = listenToAddressBookExchange("sabre:addressbook:subscription:created");

        // When Bob grants Cedric READ access to Alice's address book through Bob's delegated copy.
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .body("""
                {
                    "dav:share-resource": {
                        "dav:sharee": [
                            {
                                "dav:href": "mailto:{email}",
                                "dav:share-access": {access}
                            }
                        ]
                    }
                }
                """.replace("{email}", cedric.email())
                .replace("{access}", String.valueOf(DelegationRight.READ.getValue())))
        .when()
            .post(bobCopy.asUri() + ".json")
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then Cedric has his own delegated copy of Alice's address book.
        AddressBookURL cedricCopy = cardDavClient.findSubscribedAddressBook(cedric, alice.id(), "collected").orElseThrow();
        awaitAtMost.untilAsserted(() -> {
            // Alice's source gets an AMQP update because its delegation list changed.
            // connectedUser identifies Bob as the user who made the change.
            assertThat(sourceMessagesQueue).hasSize(1);
            assertThatJson(sourceMessagesQueue.peek().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {
                    "path": "addressbooks/{owner}/collected",
                    "connectedUser": "principals/users/{actor}"
                }
                """.replace("{owner}", alice.id()).replace("{actor}", bob.id()));

            // Cedric's new copy gets an AMQP created message, also recording Bob as the actor.
            assertThat(createdMessagesQueue).hasSize(1);
            assertThatJson(createdMessagesQueue.peek().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {
                    "path": "addressbooks/{copy}",
                    "connectedUser": "principals/users/{actor}"
                }
                """.replace("{copy}", cedricCopy.serialize()).replace("{actor}", bob.id()));
        });
    }

    @Test
    @Disabled("Wait for a new Sabre image. Upstream PR: https://github.com/linagora/esn-sabre/pull/541")
    protected void delegationRightsChangeShouldNotifySourceAndCopy() throws IOException {
        // Roles: Alice owns the source address book and changes access; Bob is the existing READ delegate.
        // Given Alice grants Bob READ access, creating a delegated copy in Bob's address book list.
        cardDavClient.grantDelegation(alice, "collected", bob, DelegationRight.READ);
        AddressBookURL bobCopy = cardDavClient.findSubscribedAddressBook(bob, alice.id(), "collected").orElseThrow();

        // Listen after setup so only messages from the access change are captured.
        BlockingQueue<JsonNode> sourceMessagesQueue = listenToAddressBookExchange("sabre:addressbook:updated");
        BlockingQueue<JsonNode> updatedMessagesQueue = listenToAddressBookExchange("sabre:addressbook:subscription:updated");

        // When Alice upgrades Bob's access from READ to READ_WRITE on her source address book.
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .body("""
                {
                    "dav:share-resource": {
                        "dav:sharee": [
                            {
                                "dav:href": "mailto:{email}",
                                "dav:share-access": {access}
                            }
                        ]
                    }
                }
                """.replace("{email}", bob.email())
                .replace("{access}", String.valueOf(DelegationRight.READ_WRITE.getValue())))
        .when()
            .post("/addressbooks/" + alice.id() + "/collected.json")
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then AMQP updates identify both changed resources, with Alice as connectedUser (the actor).
        awaitAtMost.untilAsserted(() -> {
            // Alice's source is updated because Bob's delegation rights changed.
            assertThat(sourceMessagesQueue).hasSize(1);
            assertThatJson(sourceMessagesQueue.peek().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {
                    "path": "addressbooks/{owner}/collected",
                    "connectedUser": "principals/users/{actor}"
                }
                """.replace("{owner}", alice.id()).replace("{actor}", alice.id()));

            // Bob's existing copy is updated to reflect his new access rights.
            assertThat(updatedMessagesQueue).hasSize(1);
            assertThatJson(updatedMessagesQueue.peek().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {
                    "path": "addressbooks/{copy}",
                    "connectedUser": "principals/users/{actor}"
                }
                """.replace("{copy}", bobCopy.serialize()).replace("{actor}", alice.id()));
        });
    }

    @Test
    @Disabled("Wait for a new Sabre image. Upstream PR: https://github.com/linagora/esn-sabre/pull/541")
    protected void delegationRevocationShouldNotifySourceAndDeletedCopy() throws IOException {
        // Roles: Alice owns the source address book and revokes access; Bob is the delegate losing access.
        // Given Alice grants Bob READ access, creating a visible delegated copy for Bob.
        cardDavClient.grantDelegation(alice, "collected", bob, DelegationRight.READ);
        AddressBookURL bobCopy = cardDavClient.findSubscribedAddressBook(bob, alice.id(), "collected").orElseThrow();

        // Listen after setup so only messages from the revocation are captured.
        BlockingQueue<JsonNode> sourceMessagesQueue = listenToAddressBookExchange("sabre:addressbook:updated");
        BlockingQueue<JsonNode> deletedMessagesQueue = listenToAddressBookExchange("sabre:addressbook:subscription:deleted");

        // When Alice revokes Bob's delegation by setting his access to ACCESS_NOACCESS.
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .body("""
                {
                    "dav:share-resource": {
                        "dav:sharee": [
                            {
                                "dav:href": "mailto:{email}",
                                "dav:share-access": {access}
                            }
                        ]
                    }
                }
                """.replace("{email}", bob.email())
                .replace("{access}", String.valueOf(ACCESS_NOACCESS)))
        .when()
            .post("/addressbooks/" + alice.id() + "/collected.json")
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then AMQP messages identify Alice's updated source and Bob's deleted copy, with Alice as the actor.
        awaitAtMost.untilAsserted(() -> {
            // Alice's source remains, but its delegation list no longer includes Bob.
            assertThat(sourceMessagesQueue).hasSize(1);
            assertThatJson(sourceMessagesQueue.peek().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {
                    "path": "addressbooks/{owner}/collected",
                    "connectedUser": "principals/users/{actor}"
                }
                """.replace("{owner}", alice.id()).replace("{actor}", alice.id()));

            // Bob's delegated copy is removed because he no longer has access.
            assertThat(deletedMessagesQueue).hasSize(1);
            assertThatJson(deletedMessagesQueue.peek().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo("""
                {
                    "path": "addressbooks/{copy}",
                    "connectedUser": "principals/users/{actor}"
                }
                """.replace("{copy}", bobCopy.serialize()).replace("{actor}", alice.id()));
        });
        // The deleted copy belongs to Bob, while the source address book still belongs to Alice.
        assertThatJson(deletedMessagesQueue.peek().toString()).inPath("owner").isEqualTo("principals/users/" + bob.id());
        assertThat(cardDavClient.findSubscribedAddressBook(bob, alice.id(), "collected")).isEmpty();
    }

    private BlockingQueue<JsonNode> listenToAddressBookExchange(String exchange) throws IOException {
        var channel = dockerExtension().getChannel();
        String queue = channel.queueDeclare("", false, true, true, null).getQueue();
        channel.queueBind(queue, exchange, "");
        return AmqpTestHelper.listenToQueue(channel, queue);
    }

    @Test
    void listAddressBooksShouldShowDelegatedAddressBook() {
        // GIVEN Bob has a address book named "collected"
        // WHEN Bob delegates that address book to Alice
        cardDavClient.grantDelegation(bob, "collected", alice, DelegationRight.READ);

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
            .extract().asString();

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
                    "dav:share-access": 2,
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
        cardDavClient.grantDelegation(bob, "collected", alice, DelegationRight.READ);

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
            .extract().asString();

        // THEN Bob's address book is removed from Alice's address book list
        assertThat(response).doesNotContain("\"openpaas:source\":\"\\/addressbooks\\/" + bob.id() + "\\/collected.json\"");
    }

    @Test
    void listAddressBooksShouldShowSharingRightOfDelegation() {
        // GIVEN Bob has an address book
        // WHEN Bob delegates that address book to Alice
        cardDavClient.grantDelegation(bob, "collected", alice, DelegationRight.ADMIN);

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
            .extract().asString();

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
    void listAddressBooksShouldShowSharingRightOfDelegationWhenCopiedAddressBookHasBeenDeleted() {
        // GIVEN Bob has an address book
        // AND Bob delegates that address book to Alice
        cardDavClient.grantDelegation(bob, "collected", alice, DelegationRight.ADMIN);

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
            .extract().asString();

        // THEN deleting the copy keeps Alice's rights on Bob's source address book, as for calendars
        assertThatJson(response).inPath("_embedded.dav:addressbook[0].acl").isArray()
            .anySatisfy(acl -> assertThatJson(acl)
                .inPath("principal")
                .isEqualTo("principals/users/" + alice.id()));
        assertThat(cardDavClient.findSubscribedAddressBook(alice, bob.id(), "collected")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    protected void userShouldKeepDelegationAfterResubscribingToHiddenAddressBook(DelegationRight right) {
        String sourceAddressBookId = "collected";
        String resubscribedName = "Resubscribed address book";

        // GIVEN Alice shares a hidden address book containing a contact with Bob
        cardDavClient.setHiddenPublicRight(alice, alice.id(), sourceAddressBookId);
        cardDavClient.upsertContact(alice, sourceAddressBookId, "test-contact-uid", """
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            END:VCARD
            """.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        cardDavClient.grantDelegation(alice, sourceAddressBookId, bob, right);
        AddressBookURL delegatedAddressBook = cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId).orElseThrow();

        // WHEN Bob deletes the delegated copy and subscribes again
        cardDavClient.deleteAddressBook(bob, delegatedAddressBook.addressBookId());
        assertThat(cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId)).isEmpty();
        cardDavClient.subscribe(bob, alice.id(), sourceAddressBookId, resubscribedName);

        // THEN one delegated copy is restored, with the original access level and the new name
        String addressBooksResponse = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get("/addressbooks/" + bob.id() + ".json")
        .then()
            .statusCode(SC_OK)
            .extract().asString();
        Optional<AddressBookURL> resubscribedAddressBook = cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId);
        assertSoftly(softly -> {
            softly.assertThat(resubscribedAddressBook).as("Restored delegation").isPresent();
            softly.assertThatCode(() -> assertThatJson(addressBooksResponse)
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .inPath("_embedded.dav:addressbook[2]")
                    .isEqualTo("""
                        {
                            "openpaas:source": "/addressbooks/{aliceId}/{sourceAddressBookId}.json",
                            "openpaas:subscription-type": "delegation",
                            "dav:share-access": {shareAccess},
                            "dav:name": "{resubscribedName}"
                        }
                        """.replace("{aliceId}", alice.id())
                        .replace("{sourceAddressBookId}", sourceAddressBookId)
                        .replace("{shareAccess}", String.valueOf(right.getValue()))
                        .replace("{resubscribedName}", resubscribedName)))
                .as("Restored delegation metadata")
                .doesNotThrowAnyException();
            resubscribedAddressBook.ifPresent(addressBook -> softly.assertThat(cardDavClient.getContacts(bob, bob.id(), addressBook.addressBookId()))
                .as("Contacts read through the restored delegation")
                .contains("John Doe"));
        });

        // AND Bob can write through the restored copy only when his delegation allows it
        boolean canWrite = right != DelegationRight.READ;
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Bob's contact
                END:VCARD
                """.replace("\n", "\r\n"))
        .when()
            .put(resubscribedAddressBook.orElseThrow().asUri() + "/bob-contact.vcf")
        .then()
            .statusCode(canWrite ? SC_CREATED : SC_FORBIDDEN);
        assertThat(cardDavClient.getContacts(alice, alice.id(), sourceAddressBookId).contains("Bob's contact"))
            .as("Contact creation in Alice's source book matches Bob's delegation")
            .isEqualTo(canWrite);
    }

    @Test
    protected void userShouldKeepWriteDelegationAfterResubscribingToPublicReadOnlyAddressBook() {
        String sourceAddressBookId = "collected";

        // GIVEN Alice's address book is publicly readable
        cardDavClient.setPublicRight(alice, alice.id(), sourceAddressBookId, PublicRight.READ);

        // AND Alice grants Bob read/write delegation
        cardDavClient.grantDelegation(alice, sourceAddressBookId, bob, DelegationRight.READ_WRITE);
        AddressBookURL delegatedAddressBook = cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId)
            .orElseThrow();

        // WHEN Bob deletes the delegated copy and subscribes again
        cardDavClient.deleteAddressBook(bob, delegatedAddressBook.addressBookId());
        cardDavClient.subscribe(bob, alice.id(), sourceAddressBookId, "Resubscribed address book");
        AddressBookURL resubscribedAddressBook = cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId)
            .orElseThrow();

        // THEN Bob retains his delegated write access and can create a contact through the new copy
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Bob's contact
                END:VCARD
                """.replace("\n", "\r\n"))
        .when()
            .put(resubscribedAddressBook.asUri() + "/bob-contact.vcf")
        .then()
            .statusCode(SC_CREATED);

        // AND the contact appears in Alice's source address book
        assertThat(cardDavClient.getContacts(alice, alice.id(), sourceAddressBookId))
            .contains("Bob's contact");
    }

    @Test
    protected void resubscribedUserShouldOnlyHavePublicRightAfterDelegationIsRevoked() {
        // GIVEN Bob unsubscribes from Alice's delegated address book, then Alice revokes his delegation
        cardDavClient.setPublicRight(alice, alice.id(), "collected", PublicRight.READ);
        cardDavClient.grantDelegation(alice, "collected", bob, DelegationRight.READ_WRITE);
        AddressBookURL delegated = cardDavClient.findSubscribedAddressBook(bob, alice.id(), "collected").orElseThrow();
        cardDavClient.deleteAddressBook(bob, delegated.addressBookId());
        cardDavClient.revokeDelegation(alice, "collected", bob);

        // WHEN Bob subscribes again using the remaining public read access
        cardDavClient.subscribe(bob, alice.id(), "collected", "Public subscription");
        AddressBookURL resubscribed = cardDavClient.findSubscribedAddressBook(bob, alice.id(), "collected").orElseThrow();

        // THEN his revoked write access is not restored
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/vcard")
            .body("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob's contact\r\nEND:VCARD\r\n")
        .when()
            .put(resubscribed.asUri() + "/bob-contact.vcf")
        .then()
            .statusCode(SC_FORBIDDEN);
    }

    @Test
    protected void userShouldNotResubscribeToHiddenAddressBookAfterDelegationIsRevoked() {
        String sourceAddressBookId = "collected";

        // GIVEN Alice grants Bob read/write delegation to a hidden address book
        cardDavClient.setHiddenPublicRight(alice, alice.id(), sourceAddressBookId);
        cardDavClient.grantDelegation(alice, sourceAddressBookId, bob, DelegationRight.READ_WRITE);
        AddressBookURL delegatedAddressBook = cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId)
            .orElseThrow();

        // AND Bob unsubscribes, then Alice revokes his delegation
        cardDavClient.deleteAddressBook(bob, delegatedAddressBook.addressBookId());
        cardDavClient.revokeDelegation(alice, sourceAddressBookId, bob);

        // WHEN Bob tries to subscribe again
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .body("""
                {
                    "id": "resubscribed-address-book",
                    "dav:name": "Resubscribed address book",
                    "type": "subscription",
                    "openpaas:source": {
                        "_links": {
                            "self": {
                                "href": "/addressbooks/{aliceId}/{sourceAddressBookId}.json"
                            }
                        }
                    }
                }
                """.replace("{aliceId}", alice.id())
                .replace("{sourceAddressBookId}", sourceAddressBookId))
        .when()
            .post("/addressbooks/" + bob.id() + ".json")
        .then()
            // THEN access is forbidden because Bob no longer has delegation or public read access
            .statusCode(SC_FORBIDDEN);

        // AND no subscription to Alice's address book is created
        assertThat(cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId))
            .isEmpty();
    }

    @Test
    void nonDelegatedUserCannotSeeAddressBookOfAnotherUser() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, bob.id(), addressBook))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void delegatedUserCanSeeOriginalAddressBook() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);

        String response = cardDavClient.getContacts(alice, bob.id(), addressBook);

        assertThat(response).contains("John Doe");
    }

    @Test
    void userCannotSeeOriginalAddressBookOfAnotherUserWhenDelegationHasBeenRevoked() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);

        cardDavClient.revokeDelegation(bob, addressBook, alice);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, bob.id(), addressBook))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void createContactDirectlyOnOriginalAddressBookShouldThrowErrorWhenDelegatedUserOnlyHasReadRight() {
        String addressBook = "collected";

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void createContactDirectlyOnOriginalAddressBookShouldSucceedWhenDelegatedUserHasReadWriteRight() {
        String addressBook = "collected";

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).contains("John Doe");
    }

    @Test
    void updateContactDirectlyOnOriginalAddressBookShouldSucceedWhenDelegatedUserHasReadWriteRight() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        byte[] vcardPayload2 = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload2);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response)
            .doesNotContain("John Doe")
            .contains("John Cole");
    }

    @Test
    void deleteContactDirectlyOnOriginalAddressBookShouldSucceedWhenDelegatedUserHasReadWriteRight() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        cardDavClient.deleteContact(alice, bob.id(), addressBook, vcardUid);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).doesNotContain("John Doe");
    }

    @Test
    public void canCreateNewContactDirectlyInCopiedAddressBook() {
        String addressBook = "collected";

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, addressBookURL.addressBookId(), vcardUid, vcardPayload);

        String response = cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId());

        assertThat(response).contains("John Doe");
    }

    @Test
    public void createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
        String addressBook = "collected";

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, addressBookURL.addressBookId(), vcardUid, vcardPayload);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).contains("John Doe");
    }

    @Test
    public void createNewJsonContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
        String addressBook = "collected";

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // When Alice creates a new contact (json format) in the copied address book
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
                        "fn",
                        {},
                        "text",
                        "John Doe"
                    ]
                ],
                []
            ]""";
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .headers("Content-Type", "application/vcard+json")
            .when()
            .body(payload)
            .put("addressbooks/" + alice.id() + "/" + addressBookURL.addressBookId() + "/d1ed30c8-15e7-4e81-a7dd-f5c60beab420.vcf")
            .then()
            .statusCode(201);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        // Then the contact should be present in Bob's original address book
        assertThat(response).contains("John Doe");
    }

    @Test
    public void updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        byte[] vcardPayload2 = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, addressBookURL.addressBookId(), vcardUid, vcardPayload2);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response)
            .doesNotContain("John Doe")
            .contains("John Cole");
    }

    @Test
    public void deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        cardDavClient.deleteContact(alice, alice.id(), addressBookURL.addressBookId(), vcardUid);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).doesNotContain("John Doe");
    }

    @Test
    public void deleteShouldFailWhenRightsAreRemoved() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        assertThatThrownBy(() -> cardDavClient.deleteContact(alice, alice.id(), addressBookURL.addressBookId(), vcardUid))
            .hasMessageContaining("403 when deleting contact");
    }

    @Test
    public void createShouldFailWhenRightsAreRemoved() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        byte[] vcardPayload2 = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cardDavClient.upsertContact(alice, addressBookURL.addressBookId(), vcardUid, vcardPayload2))
            .hasMessageContaining("403 when creating contact");
    }

    @Test
    public void getShouldFailWhenRightsAreRemoved() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        cardDavClient.revokeDelegation(bob, addressBook, alice);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId()))
            .hasMessageContaining("404 when fetching contacts");
    }

    @Test
    public void getShouldFailWhenRightsAreRemovedWhenHasPublicRight() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        cardDavClient.revokeDelegation(bob, addressBook, alice);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId()))
            .hasMessageContaining("404 when fetching contacts");
    }

    @Test
    public void getShouldFailWhenNotDelegationTarget() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        assertThatThrownBy(() -> cardDavClient.getContacts(cedric, alice.id(), addressBookURL.addressBookId()))
            .hasMessageContaining("403 when fetching contacts");
    }

    @Test
    public void createShouldFailWhenNotDelegationTarget() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        byte[] vcardPayload2 = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cardDavClient.upsertContact(cedric, addressBookURL.addressBookId(), vcardUid, vcardPayload2))
            .hasMessageContaining("404 when creating contact");
    }

    @Test
    public void davShouldListDelegatedAddressBooks() throws Exception {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();


        String responseBody = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
        .when()
            .request("PROPFIND", "/addressbooks/" + alice.id())
        .then()
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).contains("/addressbooks/" + addressBookURL.serialize() + "/");
    }

    @Test
    public void shouldNotShowMeaningfullDisplayName() throws Exception {
        String addressBook = "contacts";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();


        String responseBody = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", "/addressbooks/" + alice.id())
        .then()
            .extract().asString();

        assertThat(responseBody)
            .contains("<d:response><d:href>/addressbooks/ALICE_ID/BOOK_ID/</d:href><d:propstat><d:prop><d:displayname></d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                .replace("ALICE_ID", alice.id())
                .replace("BOOK_ID", addressBookURL.addressBookId()));
    }

    @Test
    public void copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook() {
        String addressBook = "collected";

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String response = cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId());

        assertThat(response).contains("John Doe");
    }

    @Test
    public void createNewContactInOriginalAddressBookShouldResultInNewContactInCopiedAddressBook() {
        String addressBook = "collected";

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        String response = cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId());

        assertThat(response).contains("John Doe");
    }

    @Test
    public void updateContactInOriginalAddressBookShouldResultInUpdatedContactInCopiedAddressBook() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        byte[] vcardPayload2 = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload2);

        String response = cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId());

        assertThat(response)
            .doesNotContain("John Doe")
            .contains("John Cole");
    }

    @Test
    public void deleteContactInOriginalAddressBookShouldResultInDeletedContactInCopiedAddressBook() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.READ_WRITE);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        cardDavClient.deleteContact(bob, addressBook, vcardUid);

        String response = cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId());

        assertThat(response).doesNotContain("John Doe");
    }

    @ParameterizedTest
    @EnumSource(value = DelegationRight.class, names = {"READ", "READ_WRITE"})
    void setPublicRightDirectlyInOriginalAddressBookShouldThrowErrorWhenDelegatedUserOnlyHasReadRight(DelegationRight right) {
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, right);

        assertThatThrownBy(() -> cardDavClient.setPublicRight(alice, bob.id(), addressBook, PublicRight.READ))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void setPublicRightDirectlyInOriginalAddressBookShouldSucceedWhenDelegatedUserHasAdminRight() {
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.ADMIN);

        cardDavClient.setPublicRight(alice, bob.id(), addressBook, PublicRight.READ);

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
            .extract().asString();

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
                                "privilege": "{DAV:}read",
                                "principal": "{DAV:}authenticated"
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

    @ParameterizedTest
    @EnumSource(value = DelegationRight.class, names = {"READ", "READ_WRITE"})
    void grantDelegationDirectlyInOriginalAddressBookShouldThrowErrorWhenDelegatedUserOnlyHasReadRight(DelegationRight right) {
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, right);

        assertThatThrownBy(() -> cardDavClient.grantDelegation(alice, bob.id(), addressBook, cedric, DelegationRight.READ))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    protected void ownerShouldNotBeAbleToDelegateAddressBookToThemself() {
        String addressBook = "collected";

        // GIVEN Bob owns an address book
        String bobAddressBooksBeforeSelfDelegation = getAddressBooksJson(bob);

        // WHEN Bob attempts to delegate that address book to himself
        String payload = """
            {
                "dav:share-resource": {
                    "dav:sharee": [
                        {
                            "dav:href": "mailto:%s",
                            "dav:share-access": %d
                        },
                        {
                            "dav:href": "principals/users/%s",
                            "dav:share-access": 1
                        }
                    ]
                }
            }
            """.formatted(bob.email(), DelegationRight.READ.getValue(), bob.id());

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/json;charset=UTF-8")
            .accept("application/json, text/plain, */*")
            .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
            .post(AddressBookURL.URL_PATH_PREFIX + "/" + bob.id() + "/" + addressBook + ".json")
        .then()
            .statusCode(SC_BAD_REQUEST);

        // THEN the self delegation is rejected without mutating the address book lists
        assertThat(getAddressBooksJson(bob))
            .as("Self delegation by owner must not mutate Bob's address book list")
            .isEqualTo(bobAddressBooksBeforeSelfDelegation);
    }

    @Test
    protected void delegatedAdminShouldNotBeAbleToDelegateAddressBookToThemself() {
        String addressBook = "collected";

        // GIVEN Bob owns an address book
        // AND Alice has admin delegation on Bob's address book
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.ADMIN);
        String bobAddressBooksBeforeSelfDelegation = getAddressBooksJson(bob);
        String aliceAddressBooksBeforeSelfDelegation = getAddressBooksJson(alice);

        // WHEN Alice attempts to delegate Bob's address book to herself
        String payload = """
            {
                "dav:share-resource": {
                    "dav:sharee": [
                        {
                            "dav:href": "mailto:%s",
                            "dav:share-access": %d
                        },
                        {
                            "dav:href": "principals/users/%s",
                            "dav:share-access": 1
                        }
                    ]
                }
            }
            """.formatted(alice.email(), DelegationRight.READ.getValue(), alice.id());

        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/json;charset=UTF-8")
            .accept("application/json, text/plain, */*")
            .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
            .post(AddressBookURL.URL_PATH_PREFIX + "/" + bob.id() + "/" + addressBook + ".json")
        .then()
            .statusCode(SC_BAD_REQUEST);

        // THEN the self delegation by delegated admin is rejected without mutating the address book lists
        assertThat(getAddressBooksJson(bob))
            .as("Self delegation by delegated admin must not mutate Bob's source address book list")
            .isEqualTo(bobAddressBooksBeforeSelfDelegation);
        assertThat(getAddressBooksJson(alice))
            .as("Self delegation by delegated admin must not mutate Alice's delegated address book list")
            .isEqualTo(aliceAddressBooksBeforeSelfDelegation);
    }

    @Test
    void grantDelegationDirectlyInOriginalAddressBookShouldSucceedWhenDelegatedUserHasAdminRight() {
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.ADMIN);

        cardDavClient.grantDelegation(alice, bob.id(), addressBook, cedric, DelegationRight.READ);

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
            .extract().asString();

        assertThatJson(response)
            .when(Option.IGNORING_ARRAY_ORDER)
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
                        },
                        {
                                "privilege": "{DAV:}read",
                                "principal": "principals/users/{cedricId}",
                                "protected": true
                        }
                    ],
                    "dav:group": null
                }
                """.replace("{aliceId}", alice.id()))
                .replace("{bobId}", bob.id())
                .replace("{cedricId}", cedric.id()));
    }

    @Test
    void grantDelegationInCopiedAddressBookShouldSucceedWhenDelegatedUserHasAdminRight() {
        // GIVEN Bob owns an address book
        // AND Alice has admin right on Bob's address book (delegation)
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.ADMIN);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // WHEN Alice delegates the copied address book (clone) to Cedric
        cardDavClient.grantDelegation(alice, alice.id(), addressBookURL.addressBookId(), cedric, DelegationRight.ADMIN);

        // THEN the sharing right is shown on Bob's source address book
        assertThatJson(cardDavClient.getAddressBooks(bob))
            .when(Option.IGNORING_ARRAY_ORDER)
            .inPath("_embedded.dav:addressbook[0].acl")
            .isEqualTo(String.format("""
                [
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
                    },
                    {
                        "privilege": "{DAV:}share",
                        "principal": "principals/users/{cedricId}",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}write-content",
                        "principal": "principals/users/{cedricId}",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}bind",
                        "principal": "principals/users/{cedricId}",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}unbind",
                        "principal": "principals/users/{cedricId}",
                        "protected": true
                    },
                    {
                        "privilege": "{DAV:}read",
                        "principal": "principals/users/{cedricId}",
                        "protected": true
                    }
                ]
                """.replace("{aliceId}", alice.id()))
                .replace("{bobId}", bob.id())
                .replace("{cedricId}", cedric.id()));

        // AND a copy of Bob's address book is visible in Cedric's address book list
        assertThatJson(cardDavClient.getAddressBooks(cedric))
            .inPath("_embedded.dav:addressbook[2]")
            .isEqualTo("""
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
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{cedricId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-content",
                            "principal": "principals/users/{cedricId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}bind",
                            "principal": "principals/users/{cedricId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}unbind",
                            "principal": "principals/users/{cedricId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{cedricId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{cedricId}",
                            "protected": true
                        }
                    ],
                    "dav:group": null,
                    "openpaas:source": "/addressbooks/{bobId}/collected.json"
                }
                """.replace("{cedricId}", cedric.id())
                .replace("{bobId}", bob.id()));
    }

    @ParameterizedTest
    @EnumSource(value = DelegationRight.class, names = {"READ", "READ_WRITE"})
    void grantDelegationInCopiedAddressBookShouldThrowErrorWhenDelegatedUserDoesNotHaveAdminRight(DelegationRight delegationRight) {
        // GIVEN Bob owns an address book
        // AND Alice has admin right on Bob's address book (delegation)
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, delegationRight);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // WHEN Alice delegates the copied address book (clone) to Cedric
        // THEN an error is thrown
        assertThatThrownBy(() -> cardDavClient.grantDelegation(alice, alice.id(), addressBookURL.addressBookId(), cedric, DelegationRight.READ))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void setPublicRightInCopiedAddressBookShouldSucceedWhenDelegatedUserHasAdminRight() {
        // GIVEN Bob owns an address book
        // AND Alice has admin right on Bob's address book (delegation)
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.ADMIN);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // WHEN Alice publishes the copied address book (clone)
        cardDavClient.setPublicRight(alice, alice.id(), addressBookURL.addressBookId(), PublicRight.READ);

        // THEN Bob's original address book is published
        assertThat(cardDavClient.getAddressBooks(bob))
            .contains("{\"privilege\":\"{DAV:}read\",\"principal\":\"{DAV:}authenticated\"}");
    }

    @ParameterizedTest
    @EnumSource(value = DelegationRight.class, names = {"READ", "READ_WRITE"})
    void setPublicRightInCopiedAddressBookShouldThrowErrorWhenDelegatedUserDoesNotHaveAdminRight(DelegationRight right) {
        // GIVEN Bob owns an address book
        // AND Alice has no admin right on Bob's address book (delegation)
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, right);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // WHEN Alice publishes the copied address book (clone) THEN she is rejected
        assertThatThrownBy(() -> cardDavClient.setPublicRight(alice, alice.id(), addressBookURL.addressBookId(), PublicRight.READ))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void propfindOnCopiedAddressBookShouldReturnAllSharees() {
        // GIVEN Bob owns an address book
        // AND Bob delegates his address book to Alice and Cedric
        String addressBook = "collected";
        cardDavClient.grantDelegation(bob, addressBook, alice, DelegationRight.ADMIN);
        cardDavClient.grantDelegation(bob, addressBook, cedric, DelegationRight.READ);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(cedric)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // WHEN Cedric reads the sharees of the copied address book (clone)
        String responseBody = given()
            .header("Authorization", cedric.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                {
                    "properties": ["{DAV:}invite"]
                }""")
        .when()
            .request("PROPFIND", "/addressbooks/" + cedric.id() + "/" + addressBookURL.addressBookId())
        .then()
            .extract().asString();

        // THEN he sees every sharee of Bob's original address book, Bob included, in no particular order
        assertThatJson(responseBody)
            .when(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                    "{DAV:}invite": [
                        {
                            "href": "mailto:{aliceEmail}",
                            "principal": "principals/users/{aliceId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{cedricEmail}",
                            "principal": "principals/users/{cedricId}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "principals/users/{bobId}",
                            "principal": "principals/users/{bobId}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ]
                }
                """.replace("{aliceEmail}", alice.email())
                .replace("{aliceId}", alice.id())
                .replace("{cedricEmail}", cedric.email())
                .replace("{cedricId}", cedric.id())
                .replace("{bobId}", bob.id()));
    }

    private String getAddressBooksJson(OpenPaasUser user) {
        return given()
            .headers("Authorization", user.impersonatedBasicAuth())
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
        .when()
            .get(AddressBookURL.URL_PATH_PREFIX + "/" + user.id() + ".json")
        .then()
            .extract().asString();
    }
}
