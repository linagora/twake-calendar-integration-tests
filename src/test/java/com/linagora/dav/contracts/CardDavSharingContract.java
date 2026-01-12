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

import static com.linagora.dav.TestUtil.execute;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;
import com.linagora.dav.AddressBookURL;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.CardDavClient.PublicRight;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.XMLUtil;

import io.netty.handler.codec.http.HttpMethod;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

public abstract class CardDavSharingContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CardDavClient cardDavClient;
    private OpenPaasUser alice;
    private OpenPaasUser bob;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());

        alice = dockerExtension().newTestUser();
        bob = dockerExtension().newTestUser();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();
    }

    @Test
    public void subscribeShouldThrowErrorWhenAddressBookPublicRightIsHidden() {
        String addressBook = "collected";
        cardDavClient.setHiddenPublicRight(bob, bob.id(), addressBook);

        assertThatThrownBy(() -> cardDavClient.subscribe(alice, bob.id(), addressBook, "new book"))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void subscribeShouldSucceedWhenAddressBookIsPubliclyReadable() {
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
            .extract()
            .body()
            .asString();

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

    @Test
    void subscribeShouldSucceedWhenAddressBookIsPubliclyWritable() {
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
            .extract()
            .body()
            .asString();

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

        assertThat(response).doesNotContain("John Doe");
        assertThat(response).contains("John Cole");
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
            .extract()
            .body()
            .asString();

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
            .extract()
            .body()
            .asString();

        assertThat(response).doesNotContain("\"openpaas:source\":\"\\/addressbooks\\/" + bob.id() + "\\/" + addressBookURL.addressBookId() + ".json\"");
    }

    @Test
    void copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook() {
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
    void canCreateNewContactInCopiedAddressBook() {
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
    void cannotDeleteContactInCopiedAddressBookWhenNotAuthorized() {
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
    void createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
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
    void updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook() {
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

        assertThat(response).doesNotContain("John Doe");
        assertThat(response).contains("John Cole");
    }

    @Test
    void deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook() {
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
    void propfindShouldListSubscriptionsInUserAddressBooks() throws Exception {
        String addressBook = "collected";
        String copiedAddressBook = "new book";

        // GIVEN Bob has a public addressbook
        cardDavClient.setPublicRight(bob, bob.id(), addressBook, PublicRight.READ);

        // WHEN Alice subscribes to Bob's addressbook
        cardDavClient.subscribe(alice, bob.id(), addressBook, copiedAddressBook);

        // THEN Alice can list her addressbooks including the subscription using native PROPFIND
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(alice::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + alice.id()));

        System.out.println(response.body());

        List<String> addressBookHrefs = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        // Alice should have at least 3 addressbooks: collected, contacts, and the subscription
        assertThat(addressBookHrefs.size()).isGreaterThanOrEqualTo(3);
        assertThat(addressBookHrefs).contains("/addressbooks/" + alice.id() + "/collected/");
        assertThat(addressBookHrefs).contains("/addressbooks/" + alice.id() + "/contacts/");

        // The subscription should be listed among Alice's addressbooks
        assertThat(addressBookHrefs.stream()
            .filter(href -> !href.equals("/addressbooks/" + alice.id() + "/collected/")
                && !href.equals("/addressbooks/" + alice.id() + "/contacts/")
                && !href.equals("/addressbooks/" + alice.id() + "/")))
            .hasSize(1);
    }
}
