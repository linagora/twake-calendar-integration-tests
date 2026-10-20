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

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.with;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.collect.ImmutableSet;
import com.linagora.dav.AddressBookURL;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.CardDavClient.PublicRight;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.XMLUtil;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

public abstract class CardDavSharingContract {

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
    protected void cannotCreateSubscriptionInAnotherUsersHome() {
        // Given Alice can read her source book but cannot write Bob's home.
        String payload = """
            {
                "id": "injected-subscription",
                "dav:name": "Alice subscription",
                "openpaas:source": {
                    "_links": {
                        "self": {
                            "href": "/addressbooks/{alice}/contacts.json"
                        }
                    }
                }
            }
            """.replace("{alice}", alice.id());

        // When Alice tries to create the subscription in Bob's home, deny it.
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .body(payload)
        .when()
            .post("/addressbooks/" + bob.id() + ".json")
        .then()
            .statusCode(SC_FORBIDDEN);

        // Then Bob confirms that no subscription was created in his home.
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .get("/addressbooks/" + bob.id() + "/injected-subscription.json")
        .then()
            .statusCode(SC_NOT_FOUND);
    }

    @Test
    public void subscribeShouldThrowErrorWhenAddressBookPublicRightIsHidden() {
        String addressBook = "collected";
        cardDavClient.setHiddenPublicRight(bob, bob.id(), addressBook);

        assertThatThrownBy(() -> cardDavClient.subscribe(alice, bob.id(), addressBook, "new book"))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    public void subscribeShouldSucceedWhenAddressBookIsPubliclyReadable() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        // GIVEN Bob has a address book named "collected"
        // AND the public right of this address book is not hidden
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        // WHEN Alice subscribes to Bob's "collected" address book
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

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
            .isEqualTo("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
                        }
                    },
                    "dav:name": "{addressBook}",
                    "carddav:description": "",
                    "dav:acl": ["dav:read"],
                    "dav:share-access": null,
                    "openpaas:subscription-type": "public",
                    "type": null,
                    "state": null,
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
                """.replace("{addressBook}", copiedAddressBook)
                .replace("{aliceId}", alice.id())
                .replace("{bobId}", bob.id()));
    }

    @ParameterizedTest
    @EnumSource(PublicRight.class)
    protected void userShouldBeAbleToResubscribeToPublicAddressBook(PublicRight publicRight) {
        String sourceAddressBookId = "collected";
        String resubscribedName = "Resubscribed address book";

        // GIVEN Alice has a public address book containing a contact
        cardDavClient.upsertContact(alice, sourceAddressBookId, "test-contact-uid", """
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            END:VCARD
            """.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        cardDavClient.setPublicRight(alice, alice.id(), sourceAddressBookId, publicRight);

        // AND Bob subscribes to Alice's address book
        cardDavClient.subscribe(bob, alice.id(), sourceAddressBookId, "Alice's address book");
        AddressBookURL subscribedAddressBook = cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId)
            .orElseThrow();

        // WHEN Bob deletes his subscription and subscribes again
        cardDavClient.deleteAddressBook(bob, subscribedAddressBook.addressBookId());
        assertThat(cardDavClient.findSubscribedAddressBook(bob, alice.id(), sourceAddressBookId))
            .isEmpty();
        cardDavClient.subscribe(bob, alice.id(), sourceAddressBookId, resubscribedName);

        // THEN Bob has a new public subscription with the requested name and the source's public rights
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
            softly.assertThat(resubscribedAddressBook).as("New public subscription").isPresent();
            softly.assertThatCode(() -> assertThatJson(addressBooksResponse)
                    .when(Option.IGNORING_EXTRA_FIELDS)
                    .inPath("_embedded.dav:addressbook[2]")
                    .isEqualTo("""
                        {
                            "openpaas:source": "/addressbooks/{aliceId}/{sourceAddressBookId}.json",
                            "openpaas:subscription-type": "public",
                            "dav:name": "{resubscribedName}",
                            "dav:acl": {publicAcl}
                        }
                        """.replace("{aliceId}", alice.id())
                        .replace("{sourceAddressBookId}", sourceAddressBookId)
                        .replace("{resubscribedName}", resubscribedName)
                        .replace("{publicAcl}", publicRight == PublicRight.READ ? "[\"dav:read\"]" : "[\"dav:read\", \"dav:write\"]")))
                .as("Public subscription metadata")
                .doesNotThrowAnyException();

            // AND Bob can read Alice's existing contact through the new subscription
            resubscribedAddressBook.ifPresent(addressBook -> softly.assertThat(cardDavClient.getContacts(bob, bob.id(), addressBook.addressBookId()))
                .as("Contacts read through the new public subscription")
                .contains("John Doe"));
        });
    }

    @Test
    public void subscribeShouldSucceedWhenAddressBookIsPubliclyWritable() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        // GIVEN Bob has a address book named "collected"
        // AND the public right of this address book is not hidden
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);

        // WHEN Alice subscribes to Bob's "collected" address book
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

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
            .isEqualTo("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
                        }
                    },
                    "dav:name": "{addressBook}",
                    "carddav:description": "",
                    "dav:acl": [
                        "dav:read",
                        "dav:write"
                    ],
                    "dav:share-access": null,
                    "openpaas:subscription-type": "public",
                    "type": null,
                    "state": null,
                    "numberOfContacts": null,
                    "acl": [
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
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{aliceId}",
                            "protected": true
                        }
                    ],
                    "dav:group": null,
                    "openpaas:source": "/addressbooks/{bobId}/collected.json"
                }
                """.replace("{addressBook}", copiedAddressBook)
                .replace("{aliceId}", alice.id())
                .replace("{bobId}", bob.id()));
    }

    @Test
    void userCannotSeeHiddenAddressBook() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);
        cardDavClient.setHiddenPublicRight(bob, bob.id(), addressBook);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, bob.id(), addressBook))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void userCanSeePublicAddressBook() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        String response = cardDavClient.getContacts(alice, bob.id(), addressBook);

        assertThat(response).contains("John Doe");
    }

    @Test
    void createContactDirectlyOnReadOnlyAddressBookShouldThrowError() {
        String addressBook = "collected";

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void createContactDirectlyOnHiddenAddressBookShouldThrowError() {
        String addressBook = "collected";

        cardDavClient.setHiddenPublicRight(bob, bob.id(), addressBook);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void createContactDirectlyOnOriginalAddressBookShouldSucceedWhenPublicRightIsReadWrite() {
        String addressBook = "collected";

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).contains("John Doe");
    }

    @Test
    void updateContactDirectlyOnOriginalAddressBookShouldSucceedWhenPublicRightIsReadWrite() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);

        byte[] vcardPayload2 = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, bob.id(), addressBook, vcardUid, vcardPayload2);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response)
            .doesNotContain("John Doe")
            .contains("John Cole");
    }

    @Test
    void deleteContactDirectlyOnOriginalAddressBookShouldSucceedWhenPublicRightIsReadWrite() {
        String addressBook = "collected";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);

        cardDavClient.deleteContact(alice, bob.id(), addressBook, vcardUid);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).doesNotContain("John Doe");
    }

    @Test
    void copiedAddressBookShouldNoLongerExistWhenOriginalAddressBookIsHidden() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        cardDavClient.setHiddenPublicRight(bob, bob.id(), addressBook);

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

        assertThat(response).doesNotContain("\"openpaas:source\":\"\\/addressbooks\\/" + bob.id() + "\\/collected.json\"");
    }

    @Test
    void copiedAddressBookShouldNoLongerExistWhenOriginalAddressBookIsDeleted() {
        cardDavClient.createAddressBook(bob, "encyclopedia");

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(bob)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        cardDavClient.setPublicRight(bob, bob.id(), addressBookURL.addressBookId(), PublicRight.READ);

        cardDavClient.subscribe(alice, bob.id(), addressBookURL.addressBookId(), "new book");

        cardDavClient.deleteAddressBook(bob, addressBookURL.addressBookId());

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

        assertThat(response).doesNotContain("\"openpaas:source\":\"\\/addressbooks\\/" + bob.id() + "\\/" + addressBookURL.addressBookId() + ".json\"");
    }

    @Test
    public void copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String response = cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId());

        assertThat(response).contains("John Doe");
    }

    @Test
    void shouldNotReadDataOnceDelegationIsRemoved() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();
        cardDavClient.setHiddenPublicRight(bob, bob.id(), addressBook);

        assertThatThrownBy(() -> cardDavClient.getContacts(alice, alice.id(), addressBookURL.addressBookId()))
            .hasMessageContaining("404 when fetching contacts");
    }

    @Test
    public void thridPartyShouldNotReadDelegation() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        assertThatThrownBy(() -> cardDavClient.getContacts(cedric, alice.id(), addressBookURL.addressBookId()))
            .hasMessageContaining("403 when fetching contacts");
    }

    @Test
    public void thridPartyShouldNotWriteDelegation() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);
        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        assertThatThrownBy(() -> cardDavClient.upsertContact(cedric, addressBookURL.base(), addressBookURL.addressBookId(), vcardUid, vcardPayload))
            .hasMessageContaining("403 when creating contact");
    }

    @Test
    public void canCreateNewContactInCopiedAddressBook() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

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
    void cannotCreateNewContactInCopiedAddressBookWhenNotAuthorized() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> cardDavClient.upsertContact(alice, addressBookURL.addressBookId(), vcardUid, vcardPayload))
            .hasMessageContaining("403 when creating contact");
    }

    @Test
    public void cannotDeleteContactInCopiedAddressBookWhenNotAuthorized() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(alice, addressBookURL.addressBookId(), vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        cardDavClient.deleteContact(alice, alice.id(), addressBookURL.addressBookId(), vcardUid);
    }

    @Test
    public void createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

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
        String copiedAddressBook = "new book";

        // Given Bob has a address book with read/write public rights
        // and Alice has subscribed to it
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

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
        String copiedAddressBook = "new book";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

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
        String copiedAddressBook = "new book";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, bob.id(), addressBook, vcardUid, vcardPayload);

        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        cardDavClient.deleteContact(alice, alice.id(), addressBookURL.addressBookId(), vcardUid);

        String response = cardDavClient.getContacts(bob, bob.id(), addressBook);

        assertThat(response).doesNotContain("John Doe");
    }

    @Test
    public void publicSubscriptionsCanGetContactViaNativeCardDAV() throws Exception {
        String addressBook = "collected";
        String copiedAddressBook = "new book";
        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);

        // GIVEN: Bob sets his address book as read-only
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        // AND: Bob has a contact in his address book
        cardDavClient.upsertContact(bob, addressBook, vcardUid, vcardPayload);

        // WHEN: Alice subscribes to Bob's address book
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        AddressBookURL addressBookURL = cardDavClient.findUserAddressBooks(alice)
            .collectList().block().stream()
            .filter(url -> !ImmutableSet.of("collected", "contacts").contains(url.addressBookId()))
            .findAny().get();

        // THEN: Alice can GET the contact using native CardDAV
        String responseBody = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .accept("text/vcard")
        .when()
            .get("/addressbooks/" + alice.id() + "/" + addressBookURL.addressBookId() + "/" + vcardUid + ".vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).contains("John Doe");
    }

    @Test
    void propfindShouldListSubscriptionsInUserAddressBooks() throws Exception {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        // GIVEN Bob has a public addressbook
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        // WHEN Alice subscribes to Bob's addressbook
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        // THEN Alice can list her addressbooks including the subscription using native PROPFIND
        String responseBody = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
        .when()
            .request("PROPFIND", "/addressbooks/" + alice.id())
        .then()
            .extract().asString();

        List<String> addressBookHrefs = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        // Alice should have at least 3 addressbooks: collected, contacts, and the subscription
        assertThat(addressBookHrefs)
            .hasSizeGreaterThanOrEqualTo(3)
            .contains("/addressbooks/" + alice.id() + "/collected/", "/addressbooks/" + alice.id() + "/contacts/");

        // The subscription should be listed among Alice's addressbooks
        assertThat(addressBookHrefs.stream()
            .filter(href -> !href.equals("/addressbooks/" + alice.id() + "/collected/")
                && !href.equals("/addressbooks/" + alice.id() + "/contacts/")
                && !href.equals("/addressbooks/" + alice.id() + "/")))
            .hasSize(1);
    }
}
