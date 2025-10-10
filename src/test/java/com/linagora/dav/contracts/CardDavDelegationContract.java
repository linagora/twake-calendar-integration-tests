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

import static com.linagora.dav.CardDavClient.*;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        cardDavClient.grantDelegation(bob, "collected", alice, DelegationRight.ADMIN);

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
            .extract()
            .body()
            .asString();

        // THEN the sharing right for Alice is removed on Bob's source address book
        assertThat(response).doesNotContain(alice.id());
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

        assertThat(response).doesNotContain("John Doe");
        assertThat(response).contains("John Cole");
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 Fails with 500 upon contact creation: " +
        "PHP message: PHP Fatal error:  Uncaught TypeError: Argument 1 passed to Sabre\\CalDAV\\Plugin::getSupportedPrivilegeSet() must implement interface Sabre\\DAV\\INode, null given in /var/www/vendor/sabre/dav/lib/CalDAV/Plugin.php:970")
    @Test
    void canCreateNewContactDirectlyInCopiedAddressBook() {
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 Fails with 500 upon contact creation: " +
        "PHP message: PHP Fatal error:  Uncaught TypeError: Argument 1 passed to Sabre\\CalDAV\\Plugin::getSupportedPrivilegeSet() must implement interface Sabre\\DAV\\INode, null given in /var/www/vendor/sabre/dav/lib/CalDAV/Plugin.php:970")
    @Test
    void createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 upsertContact fails with a 500 error" +
        "[error] 13#13: *23 FastCGI sent in stderr: \"PHP message: PHP Fatal error:  Uncaught TypeError: Argument 1 passed to Sabre\\CalDAV\\Plugin::getSupportedPrivilegeSet() must implement interface Sabre\\DAV\\INode, null given in /var/www/vendor/sabre/dav/lib/CalDAV/Plugin.php:970")
    @Test
    void updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook() {
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

        assertThat(response).doesNotContain("John Doe");
        assertThat(response).contains("John Cole");
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 Fails with 403 upon delete")
    @Test
    void deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook() {
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 Data is not copied")
    @Test
    void copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook() {
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 Data is not copied")
    @Test
    void createNewContactInOriginalAddressBookShouldResultInNewContactInCopiedAddressBook() {
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

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 PUT is accepted but not applied")
    @Test
    void updateContactInOriginalAddressBookShouldResultInUpdatedContactInCopiedAddressBook() {
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

        assertThat(response).doesNotContain("John Doe");
        assertThat(response).contains("John Cole");
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/60 contact is not copied in the first place so it cannot be deleted and test succeeds")
    @Test
    void deleteContactInOriginalAddressBookShouldResultInDeletedContactInCopiedAddressBook() {
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
            .extract()
            .body()
            .asString();

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
            .extract()
            .body()
            .asString();

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
}
