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
import static com.linagora.dav.TwakeCalendarProvisioningService.DEFAULT_DOMAIN;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

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
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

public abstract class CardDavMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";
    private static final byte[] VCARD = """
        BEGIN:VCARD
        VERSION:3.0
        FN:Test Contact
        N:Contact;Test;;;
        UID:abcdef
        END:VCARD
        """.getBytes(StandardCharsets.UTF_8);
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
        RestAssured.reset();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/json;charset=UTF-8")
            .accept("application/json, text/plain, */*");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
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
        String responseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
        .when()
            .request("PROPFIND", "/addressbooks/")
        .then()
            .extract().asString();

        assertThat(responseBody).doesNotContain(john.id());
    }

    @Test
    void propfindShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + john.id())
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void propfindAddressBookShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + john.id() + "/contacts")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void mkcolShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
            .body("""
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
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + john.id() + "/newBook")
        .then()
            .statusCode(anyOf(is(403), is(404)));
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

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void proppatchShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/xml")
            .accept("application/xml")
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>Hacked Address Book</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/addressbooks/" + john.id() + "/contacts")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void headContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
        .when()
            .head("/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void exportShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + john.id() + "/contacts?export=true")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void reportShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/xml")
            .header("Depth", "1")
            .accept("application/xml")
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <C:addressbook-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:prop>
                    <D:getetag/>
                    <C:address-data/>
                  </D:prop>
                </C:addressbook-query>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/addressbooks/" + john.id() + "/contacts")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void copyContactBetweenAddressBooksShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + john.id() + "/collected/abcdef.vcf")
        .when()
            .request("COPY", "/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void moveContactBetweenAddressBooksShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + john.id() + "/collected/abcdef.vcf")
        .when()
            .request("MOVE", "/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void getJsonAddressBooksShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
        .when()
            .get("/addressbooks/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    protected void aggregatedContactsShouldReturnErrorStatusForCrossDomainUser() {
        // GIVEN John has a contact in another tenant
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        // WHEN Bob requests John's aggregated contacts
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
        .when()
            .get("/contacts/" + john.id() + ".json?sort=email")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    protected void aggregatedContactsShouldRejectForeignTechnicalToken() {
        // GIVEN John has a contact in the second tenant
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        // WHEN the first tenant's technical token requests John's aggregated contacts
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .accept("application/json")
        .when()
            .get("/contacts/" + john.id() + ".json?sort=email")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN access is denied, while the second tenant's token can read the contact
        String ownTenantResponseBody = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
            .accept(ContentType.JSON)
        .when()
            .get("/contacts/" + john.id() + ".json?sort=email")
        .then()
            .statusCode(200)
            .extract().asString();
        assertThatJson(ownTenantResponseBody).inPath("_embedded.dav:item[0]._links.self.href")
            .isEqualTo("/addressbooks/" + john.id() + "/contacts/abcdef.vcf");
    }

    @Test
    protected void aggregatedContactsShouldOnlyIncludeOwnTenantData() {
        // GIVEN Bob and John belong to different tenants and each has a contact
        cardDavClient.upsertContact(bob, CONTACTS_BOOK, "own", VCARD);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "foreign", VCARD);

        // WHEN Bob lists his aggregated contacts sorted by email
        String responseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept(ContentType.JSON)
        .when()
            .get("/contacts/" + bob.id() + ".json?sort=email")
        .then()
            .statusCode(200)
            .extract().asString();

        // THEN only Bob's contact is returned
        assertThatJson(responseBody).inPath("_embedded.dav:item").isArray().hasSize(1);
        assertThatJson(responseBody).inPath("_embedded.dav:item[0]._links.self.href")
            .isEqualTo("/addressbooks/" + bob.id() + "/contacts/own.vcf");
    }

    @Test
    void putJsonContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/vcard+json")
            .body("""
                ["vcard", [
                    ["version", {}, "text", "4.0"],
                    ["fn", {}, "text", "Test Contact"]
                ]]
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void getJsonContactShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, CONTACTS_BOOK, "abcdef", VCARD);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/vcard+json")
        .when()
            .get("/addressbooks/" + john.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void postJsonAddressBookShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
            .contentType("application/json")
            .body("""
                {"id":"newbook","dav:name":"New Book","dav:acl":["dav:read","dav:write"],"type":"addressbook"}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/addressbooks/" + john.id() + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void updateJsonAddressBookMetadataShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
            .contentType("application/json")
            .body("""
                {"dav:name":"Hacked Name","carddav:description":"Hacked"}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/addressbooks/" + john.id() + "/contacts.json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void reportJsonShouldReturnErrorStatusForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), CONTACTS_BOOK, PublicRight.READ_WRITE);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json")
            .contentType("application/json")
            .body("""
                {"match":{"field":"fn","value":"Test"}}
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/addressbooks/" + john.id() + "/contacts.json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
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
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .accept("application/json")
            .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/addressbooks/" + targetDomainId + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no address book is created in Domain B and a Domain B technical token can still create it
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, targetDomainToken)
            .accept("application/json")
            .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/addressbooks/" + targetDomainId + ".json")
        .then()
            .statusCode(201);
    }

    @Test
    protected void shouldNotExposeDomainAddressBooksWhenUsingForeignTechnicalToken() {
        // GIVEN a domain address book exists in Domain B
        cardDavClient.createDomainAddressBook(secondDomainId, secondDomainToken);

        // WHEN a Domain A technical token lists Domain B address books
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .accept("application/json")
        .when()
            .get("/addressbooks/" + secondDomainId + ".json")
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

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
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
        .when()
            .get("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN Domain B contacts are not exposed and remain readable by a Domain B technical token
        String ownTenantResponseBody = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
        .when()
            .get("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn")
        .then()
            .extract().asString();

        assertThat(ownTenantResponseBody).contains("Visible Contact");
    }

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
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .contentType("application/xml")
            .header("Depth", "1")
            .accept("application/xml")
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <C:addressbook-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav">
                  <D:prop>
                    <D:getetag/>
                    <C:address-data/>
                  </D:prop>
                </C:addressbook-query>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("REPORT", "/addressbooks/" + secondDomainId + "/" + addressBookId)
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the report does not expose Domain B contacts and they remain readable by a Domain B technical token
        String ownTenantResponseBody = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
        .when()
            .get("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn")
        .then()
            .extract().asString();

        assertThat(ownTenantResponseBody).contains("Reported Contact");
    }

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
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
            .contentType("application/vcard+json")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:Forbidden Contact
                N:Forbidden Contact;;;;
                UID:%s
                END:VCARD
                """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + secondDomainId + "/" + addressBookId + "/" + vcardUid + ".vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN no contact is created in Domain B
        String ownTenantResponseBody = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
        .when()
            .get("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn")
        .then()
            .extract().asString();

        assertThat(ownTenantResponseBody)
            .doesNotContain(vcardUid)
            .doesNotContain("Forbidden Contact");
    }

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
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, defaultDomainToken)
        .when()
            .delete("/addressbooks/" + secondDomainId + "/" + addressBookId + "/" + vcardUid + ".vcf")
        .then()
            .statusCode(anyOf(is(403), is(404)));

        // THEN the Domain B contact remains available
        String ownTenantResponseBody = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, secondDomainToken)
        .when()
            .get("/addressbooks/" + secondDomainId + "/" + addressBookId + ".json?limit=100&offset=0&sort=fn")
        .then()
            .extract().asString();

        assertThat(ownTenantResponseBody).contains("Contact To Keep");
    }

}
