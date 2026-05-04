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

import static com.linagora.dav.TestUtil.TWAKE_CALENDAR_TOKEN_HEADER;
import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static com.linagora.dav.TwakeCalendarProvisioningService.DEFAULT_DOMAIN;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.CardDavClient.DelegationRight;
import com.linagora.dav.CardDavClient.PublicRight;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;

import io.netty.handler.codec.http.HttpMethod;

public abstract class CardDavMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";
    private static final byte[] VCARD = ("BEGIN:VCARD\n" +
        "VERSION:3.0\n" +
        "FN:Test Contact\n" +
        "N:Contact;Test;;;\n" +
        "UID:abcdef\n" +
        "END:VCARD\n").getBytes(StandardCharsets.UTF_8);
    public static final String DOMAIN_ADDRESS_BOOK = "dab";
    public static final String DOMAIN_MEMBERS_BOOK = "domain-members";
    public static final String CONTACTS_BOOK = "contacts";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CardDavClient cardDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser john;
    private String secondDomainId;
    private String defaultDomainToken;
    private String secondDomainToken;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        Document secondDomainDoc = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        secondDomainId = secondDomainDoc.getObjectId("_id").toString();
        defaultDomainToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        secondDomainToken = dockerExtension().twakeCalendarProvisioningService().generateToken(secondDomainId);
        bob = dockerExtension().newTestUser();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
    }

    @Test
    void propfindOnAddressBooksRootShouldNotExposeCrossDomainAddressBookHome() {
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/"));

        assertThat(response.body()).doesNotContain(john.id());
    }

    @Test
    void propfindShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + john.id()));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void propfindAddressBookShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + john.id() + "/contacts"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void mkcolShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + john.id() + "/newBook")
            .send(body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:addressbook/>
                     </D:resourcetype>
                     <D:displayname>New Address Book</D:displayname>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void deleteAddressBookShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        assertThatThrownBy(() -> cardDavClient.deleteAddressBook(bob, john.id(), CONTACTS_BOOK))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void putContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        assertThatThrownBy(() -> cardDavClient.upsertContact(bob, john.id(), CONTACTS_BOOK, "abcdef", VCARD))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void deleteContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        assertThatThrownBy(() -> cardDavClient.deleteContact(bob, john.id(), CONTACTS_BOOK, "abcdef"))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void getContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void proppatchShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + john.id() + "/contacts")
            .send(body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>Hacked Address Book</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void headContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("HEAD"))
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void exportShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + john.id() + "/contacts?export=true"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void reportShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + john.id() + "/contacts")
            .send(body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <C:addressbook-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:prop>
                    <D:getetag/>
                    <C:address-data/>
                  </D:prop>
                </C:addressbook-query>
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void copyContactBetweenAddressBooksShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + john.id() + "/collected/abcdef.vcf"))
            .request(HttpMethod.valueOf("COPY"))
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void moveContactBetweenAddressBooksShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + john.id() + "/collected/abcdef.vcf"))
            .request(HttpMethod.valueOf("MOVE"))
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void getJsonAddressBooksShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/json"))
            .get()
            .uri("/addressbooks/" + john.id() + ".json"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void putJsonContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/vcard+json"))
            .put()
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
            .send(body("""
                ["vcard", [
                    ["version", {}, "text", "4.0"],
                    ["fn", {}, "text", "Test Contact"]
                ]]
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void getJsonContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/vcard+json"))
            .get()
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void postJsonAddressBookShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .post()
            .uri("/addressbooks/" + john.id() + ".json")
            .send(body("""
                {"id":"newbook","dav:name":"New Book","dav:acl":["dav:read","dav:write"],"type":"addressbook"}
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void updateJsonAddressBookMetadataShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + john.id() + "/contacts.json")
            .send(body("""
                {"dav:name":"Hacked Name","carddav:description":"Hacked"}
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void reportJsonShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + john.id() + "/contacts.json")
            .send(body("""
                {"match":{"field":"fn","value":"Test"}}
                """)));

        assertThat(status).isIn(403, 404);
    }

    @Test
    void subscribeShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(bob, bob.id(), CONTACTS_BOOK, PublicRight.READ);

        assertThatThrownBy(() -> cardDavClient.subscribe(john, bob.id(), CONTACTS_BOOK, "Bob's contacts"))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void delegateShouldReturnErrorStatusForCrossDomainUser() {
        assertThatThrownBy(() -> cardDavClient.grantDelegation(bob, CONTACTS_BOOK, john, DelegationRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void readDabShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);

        assertThatThrownBy(() -> cardDavClient.getContacts(bob, secondDomainId, DOMAIN_ADDRESS_BOOK))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void readDabAsDomainAdminShouldReturnErrorStatusForCrossDomainUser() {
        // Given domain address book of second domain
        // And Bob is domain admin of first domain
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        // When Bob tries to read domain address book of second domain
        // Then it should return 403
        assertThatThrownBy(() -> cardDavClient.getContacts(bob, secondDomainId, DOMAIN_ADDRESS_BOOK))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void addContactToPublicReadWriteDabShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        makeDomainAdmin(john, SECOND_DOMAIN);
        cardDavClient.setDomainAddressBookPublicRightReadWrite(john, secondDomainId, DOMAIN_ADDRESS_BOOK);

        String vcardUid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> cardDavClient.upsertContact(bob, secondDomainId, DOMAIN_ADDRESS_BOOK, vcardUid, VCARD))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void addContactToPublicReadWriteDabAsDomainAdminShouldReturnErrorStatusForCrossDomainUser() {
        // Given domain address book of second domain with public read write right
        // And Bob is domain admin of first domain
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        makeDomainAdmin(john, SECOND_DOMAIN);
        cardDavClient.setDomainAddressBookPublicRightReadWrite(john, secondDomainId, DOMAIN_ADDRESS_BOOK);

        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        // When Bob tries to add contact to domain address book of second domain
        // Then it should return 403
        String vcardUid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> cardDavClient.upsertContact(bob, secondDomainId, DOMAIN_ADDRESS_BOOK, vcardUid, VCARD))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void setPublicRightOfDabAsDomainAdminShouldReturnErrorStatusForCrossDomainUser() {
        // Given domain address book of second domain
        // And Bob is domain admin of first domain
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        // When Bob tries to set public right of domain address book of second domain
        // Then it should return 403
        assertThatThrownBy(() -> cardDavClient.setPublicRight(bob, secondDomainId, DOMAIN_ADDRESS_BOOK, PublicRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void delegateDabAsDomainAdminShouldReturnErrorStatusForCrossDomainUser() {
        // Given domain address book of second domain
        // And Bob is domain admin of first domain
        cardDavClient.createDomainAddressBook(dockerExtension().domainId(), defaultDomainToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        // When Bob tries to delegate domain address book of first domain to John who is a user of second domain
        // Then it should return 403
        assertThatThrownBy(() -> cardDavClient.grantDomainBookDelegation(bob, dockerExtension().domainId(), DOMAIN_ADDRESS_BOOK, john, DelegationRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void readDomainMemberBookShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.createDomainMembersAddressBook(secondDomainId, secondDomainToken);

        assertThatThrownBy(() -> cardDavClient.getContacts(bob, secondDomainId, DOMAIN_MEMBERS_BOOK))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    @Test
    void readDomainMemberBookAsDomainAdminShouldReturnErrorStatusForCrossDomainUser() {
        // Given domain member address book of second domain
        // And Bob is domain admin of first domain
        cardDavClient.createDomainMembersAddressBook(secondDomainId, secondDomainToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        // When Bob tries to read domain member address book of second domain
        // Then it should return 403
        assertThatThrownBy(() -> cardDavClient.getContacts(bob, secondDomainId, DOMAIN_MEMBERS_BOOK))
            .isInstanceOf(RuntimeException.class)
            .message().containsAnyOf("403", "404");
    }

    private void makeDomainAdmin(OpenPaasUser user, String domainName) {
        String adminApiBase = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        given()
            .baseUri(adminApiBase)
            .put("/domains/" + domainName + "/admins/" + user.email())
            .then()
            .statusCode(204);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @ParameterizedTest
    @ValueSource(strings = {DOMAIN_ADDRESS_BOOK, DOMAIN_MEMBERS_BOOK})
    protected void shouldNotCreateDomainAddressBookWhenUsingForeignTechnicalToken(String addressBookId) {
        // GIVEN Domain B and a payload for one of its domain address books
        Document targetDomain = dockerExtension().twakeCalendarProvisioningService()
            .createDomainIfNotExists("technical-token-target-" + UUID.randomUUID() + ".test");
        String targetDomainId = targetDomain.getObjectId("_id").toString();
        String targetDomainToken = dockerExtension().twakeCalendarProvisioningService().generateToken(targetDomainId);
        String payload = DOMAIN_MEMBERS_BOOK.equals(addressBookId) ? """
            {
                "id": "domain-members",
                "dav:name": "Domain Members",
                "carddav:description": "Address book contains all domain members",
                "dav:acl": [ "{DAV:}read" ],
                "type": "group"
            }
            """ : """
            {
                "id": "dab",
                "dav:name": "Domain address book",
                "carddav:description": "Domain address book",
                "dav:acl": [ "{DAV:}read" ],
                "type": "group",
                "state": "enabled"
            }
            """;

        // WHEN a Domain A technical token tries to create the Domain B address book
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json"))
            .post()
            .uri("/addressbooks/" + targetDomainId + ".json")
            .send(body(payload)));

        // THEN no address book is created in Domain B and a Domain B technical token can still create it
        assertThat(status).isIn(403, 404);
        assertThat(executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, targetDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json"))
            .post()
            .uri("/addressbooks/" + targetDomainId + ".json")
            .send(body(payload))))
            .isEqualTo(201);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @Test
    protected void shouldNotExposeDomainAddressBooksWhenUsingForeignTechnicalToken() {
        // GIVEN a domain address book exists in Domain B
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);

        // WHEN a Domain A technical token lists Domain B address books
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
                .add("Accept", "application/json"))
            .get()
            .uri("/addressbooks/" + secondDomainId + ".json"));

        // THEN Domain B address books are not exposed
        assertThat(status).isIn(403, 404);
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @ParameterizedTest
    @ValueSource(strings = {DOMAIN_ADDRESS_BOOK, DOMAIN_MEMBERS_BOOK})
    protected void shouldNotExposeDomainAddressBookContactsInJsonWhenUsingForeignTechnicalToken(String addressBookId) {
        // GIVEN a contact exists in one of the Domain B address books
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.createDomainMembersAddressBook(secondDomainId, secondDomainToken);
        } else {
            cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        }
        String vcardUid = "contact-" + UUID.randomUUID();
        byte[] payload = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Visible Contact
            N:Visible Contact;;;;
            UID:%s
            END:VCARD
            """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8);
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.upsertDomainMemberContact(secondDomainId, vcardUid, payload, secondDomainToken);
        } else {
            cardDavClient.upsertDomainContact(secondDomainId, vcardUid, payload, secondDomainToken);
        }

        // WHEN a Domain A technical token reads Domain B contacts as JSON
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json, text/plain, */*"))
            .get()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn"));

        // THEN Domain B contacts are not exposed and remain readable by a Domain B technical token
        assertThat(status).isIn(403, 404);
        assertThat(execute(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json, text/plain, */*"))
            .get()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn"))
            .body()).contains("Visible Contact");
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @ParameterizedTest
    @ValueSource(strings = {DOMAIN_ADDRESS_BOOK, DOMAIN_MEMBERS_BOOK})
    protected void shouldNotExposeDomainAddressBookContactsReportWhenUsingForeignTechnicalToken(String addressBookId) {
        // GIVEN a contact exists in one of the Domain B address books
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.createDomainMembersAddressBook(secondDomainId, secondDomainToken);
        } else {
            cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        }
        String vcardUid = "contact-" + UUID.randomUUID();
        byte[] payload = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Reported Contact
            N:Reported Contact;;;;
            UID:%s
            END:VCARD
            """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8);
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.upsertDomainMemberContact(secondDomainId, vcardUid, payload, secondDomainToken);
        } else {
            cardDavClient.upsertDomainContact(secondDomainId, vcardUid, payload, secondDomainToken);
        }

        // WHEN a Domain A technical token runs a CardDAV REPORT on Domain B contacts
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId)
            .send(body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <C:addressbook-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:prop>
                    <D:getetag/>
                    <C:address-data/>
                  </D:prop>
                </C:addressbook-query>
                """)));

        // THEN the report does not expose Domain B contacts and they remain readable by a Domain B technical token
        assertThat(status).isIn(403, 404);
        assertThat(execute(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json, text/plain, */*"))
            .get()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn"))
            .body()).contains("Reported Contact");
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @ParameterizedTest
    @ValueSource(strings = {DOMAIN_ADDRESS_BOOK, DOMAIN_MEMBERS_BOOK})
    protected void shouldNotCreateContactInDomainAddressBookWhenUsingForeignTechnicalToken(String addressBookId) {
        // GIVEN one of the Domain B address books exists
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.createDomainMembersAddressBook(secondDomainId, secondDomainToken);
        } else {
            cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        }
        String vcardUid = "contact-" + UUID.randomUUID();

        // WHEN a Domain A technical token writes a contact into the Domain B address book
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
                .add("Content-Type", "application/vcard+json")
                .add("Accept", "application/json, text/plain, */*"))
            .put()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + "/" + vcardUid + ".vcf")
            .send(body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Forbidden Contact
                N:Forbidden Contact;;;;
                UID:%s
                END:VCARD
                """.formatted(vcardUid))));

        // THEN no contact is created in Domain B
        assertThat(status).isIn(403, 404);
        assertThat(execute(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json, text/plain, */*"))
            .get()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn"))
            .body())
            .doesNotContain(vcardUid)
            .doesNotContain("Forbidden Contact");
    }

    @Disabled("Wait to https://github.com/linagora/esn-sabre/pull/357")
    @ParameterizedTest
    @ValueSource(strings = {DOMAIN_ADDRESS_BOOK, DOMAIN_MEMBERS_BOOK})
    protected void shouldNotDeleteContactFromDomainAddressBookWhenUsingForeignTechnicalToken(String addressBookId) {
        // GIVEN a contact exists in one of the Domain B address books
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.createDomainMembersAddressBook(secondDomainId, secondDomainToken);
        } else {
            cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);
        }
        String vcardUid = "contact-" + UUID.randomUUID();
        byte[] payload = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Contact To Keep
            N:Contact To Keep;;;;
            UID:%s
            END:VCARD
            """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8);
        if (DOMAIN_MEMBERS_BOOK.equals(addressBookId)) {
            cardDavClient.upsertDomainMemberContact(secondDomainId, vcardUid, payload, secondDomainToken);
        } else {
            cardDavClient.upsertDomainContact(secondDomainId, vcardUid, payload, secondDomainToken);
        }

        // WHEN a Domain A technical token deletes the Domain B contact
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
                .add("Accept", "application/json, text/plain, */*"))
            .delete()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + "/" + vcardUid + ".vcf"));

        // THEN the Domain B contact remains available
        assertThat(status).isIn(403, 404);
        assertThat(execute(dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("Accept", "application/json, text/plain, */*"))
            .get()
            .uri("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn"))
            .body()).contains("Contact To Keep");
    }

}
