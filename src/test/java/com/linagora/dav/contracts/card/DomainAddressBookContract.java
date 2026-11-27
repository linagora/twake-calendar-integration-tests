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
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_CONFLICT;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_MULTI_STATUS;
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import javax.xml.transform.OutputKeys;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Node;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.builder.Input;
import org.xmlunit.builder.Transform;
import org.xmlunit.xpath.JAXPXPathEngine;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.VCardContact;
import com.linagora.dav.XMLUtil;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import reactor.core.publisher.Mono;

public abstract class DomainAddressBookContract {

    public abstract DockerTwakeCalendarExtension extension();

    private CardDavClient cardDavClient;
    private OpenPaasUser openPaasUser;
    private String technicalToken;
    private String domainId;
    private String domainName;
    private static final String DOMAIN_ADDRESS_BOOK = "dab";
    private static final String DOMAIN_MEMBERS_ADDRESS_BOOK = "domain-members";

    private static final String RESTRICTED_DOMAIN_MEMBERS_ACL = """
        <d:acl xmlns:d="DAV:"/>
        """;
    private static final String DOMAIN_MEMBERS_READ_ACL = """
        <d:acl xmlns:d="DAV:">
          <d:ace>
            <d:principal>
              <d:href>/principals/domains/DOMAIN_ID</d:href>
            </d:principal>
            <d:grant>
              <d:privilege>
                <d:read/>
              </d:privilege>
            </d:grant>
          </d:ace>
        </d:acl>
        """;
    private static final String DOMAIN_MEMBER_VCARD = """
        BEGIN:VCARD
        VERSION:3.0
        UID:directory-contact
        FN:Directory Contact
        EMAIL:directory.contact@example.org
        END:VCARD
        """.replace("\n", "\r\n");

    private static final String DOMAIN_MEMBERS_DISPLAY_NAME_UPDATE = """
        <d:propertyupdate xmlns:d="DAV:">
          <d:set>
            <d:prop>
              <d:displayname>Current administrator role</d:displayname>
            </d:prop>
          </d:set>
        </d:propertyupdate>
        """;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(extension().davHttpClient());
        if (isolateDomainPerTest()) {
            domainName = "domain-" + UUID.randomUUID() + ".test";
            Document domain = extension().twakeCalendarProvisioningService()
                .createDomainIfNotExists(domainName);
            domainId = domain.getObjectId("_id").toString();
            openPaasUser = newDomainUser();
            technicalToken = extension().twakeCalendarProvisioningService().generateToken(domainId);
        } else {
            openPaasUser = extension().newTestUser();
            domainId = extension().domainId();
            domainName = StringUtils.substringAfterLast(openPaasUser.email(), "@");
            technicalToken = extension().twakeCalendarProvisioningService().generateToken();
        }
        RestAssured.reset();
        RestAssured.requestSpecification = extension().davRequestSpecification()
            .contentType("application/xml")
            .accept("application/xml");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    protected boolean isolateDomainPerTest() {
        return true;
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void regularUserCanReadDomainMemberAddressBookContactsWhenInDomain(VCardContact.Format format) {
        // GIVEN a domain addressbook with two contacts
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        String firstUid = "contact-" + UUID.randomUUID();
        String secondUid = "contact-" + UUID.randomUUID();

        VCardContact firstContact = VCardContact.builder()
            .firstName("First")
            .lastName("Contact")
            .email("first.contact@" + domainId)
            .build();
        VCardContact secondContact = VCardContact.builder()
            .firstName("Second")
            .lastName("Contact")
            .email("second.contact@" + domainId)
            .build();

        cardDavClient.upsertDomainMemberContact(domainId, firstUid, firstContact.toBytes(format, firstUid), technicalToken);
        cardDavClient.upsertDomainMemberContact(domainId, secondUid, secondContact.toBytes(format, secondUid), technicalToken);

        // WHEN a user of that domain fetches domain-members addressbook
        String response = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");

        // THEN they can see both contacts
        assertThat(response)
            .contains("First Contact")
            .contains("Second Contact")
            .contains("first.contact@" + domainId)
            .contains("second.contact@" + domainId);
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void regularUserCanReadDomainMemberAddressBookContactsInXMLFormat(VCardContact.Format format) {
        // GIVEN a domain addressbook with two contacts
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        String firstUid = "contact-" + UUID.randomUUID();
        String secondUid = "contact-" + UUID.randomUUID();

        VCardContact firstContact = VCardContact.builder()
            .firstName("First")
            .lastName("Contact")
            .email("first.contact@" + domainId)
            .build();
        VCardContact secondContact = VCardContact.builder()
            .firstName("Second")
            .lastName("Contact")
            .email("second.contact@" + domainId)
            .build();

        cardDavClient.upsertDomainMemberContact(domainId, firstUid, firstContact.toBytes(format, firstUid), technicalToken);
        cardDavClient.upsertDomainMemberContact(domainId, secondUid, secondContact.toBytes(format, secondUid), technicalToken);

        // WHEN a user of that domain fetches domain-members addressbook
        String response = cardDavClient.getContactsXML(openPaasUser, domainId, "domain-members");

        // THEN they can see both contacts
        assertThat(response)
            .contains("First Contact")
            .contains("Second Contact")
            .contains("first.contact@" + domainId)
            .contains("second.contact@" + domainId);
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void adminUserCannotAddContactIntoDomainMemberAddressBook(VCardContact.Format format) {
        OpenPaasUser adminUser = newDomainUser();
        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(adminUser.email(), "@");

        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + adminUser.email())
            .then()
            .statusCode(204);

        // GIVEN an existing domain addressbook
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        // AND a contact vCard payload
        String vcardUid = "contact-" + UUID.randomUUID();
        VCardContact contact = VCardContact.builder()
            .firstName("New")
            .lastName("Contact1")
            .email("contact@" + domainId)
            .build();

        // WHEN an administrator tries to add a contact into the domain-members addressbook
        // THEN the server rejects the request (403)
        assertThatThrownBy(() ->
            cardDavClient.upsertContact(adminUser, domainId, "domain-members", vcardUid, contact.toBytes(format, vcardUid)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unexpected status code: 403");

        String response = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");
        assertThat(response)
            .doesNotContain("New Contact1");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void technicalTokenCanManageDomainMemberContacts(VCardContact.Format format) {
        // GIVEN an empty domain addressbook created by the Twake Calendar service
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        String vcardUid = "member-" + UUID.randomUUID();
        VCardContact contact = VCardContact.builder()
            .firstName("Technical")
            .lastName("Contact")
            .email("tech.contact@" + domainId)
            .build();

        // WHEN using the technical token to create a new domain member contact
        cardDavClient.upsertDomainMemberContact(domainId, vcardUid, contact.toBytes(format, vcardUid), technicalToken);

        // THEN the contact is visible in the addressbook
        String responseAfterCreate = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");
        assertThat(responseAfterCreate)
            .contains("Technical Contact")
            .contains("tech.contact@" + domainId);

        // WHEN updating the same contact using the technical token
        VCardContact updatedContact = VCardContact.builder()
            .firstName("Updated")
            .lastName("Contact")
            .email("tech.updated@" + domainId)
            .build();
        cardDavClient.upsertDomainMemberContact(domainId, vcardUid, updatedContact.toBytes(format, vcardUid), technicalToken);

        // THEN the updated value is visible
        String responseAfterUpdate = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");
        assertThat(responseAfterUpdate)
            .contains("Updated Contact")
            .contains("tech.updated@" + domainId)
            .doesNotContain("Technical Contact");

        // WHEN deleting the contact
        cardDavClient.deleteContactDomainMembers(domainId, vcardUid, technicalToken);

        // THEN it should be gone
        String responseAfterDelete = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");
        assertThat(responseAfterDelete)
            .doesNotContain("Updated Contact")
            .doesNotContain("tech.updated@" + domainId);
    }

    @Test
    protected void domainMembersAddressBookCanReportTechnicalTokenChangesBySyncToken() {
        // GIVEN a domain-members address book provisioned by the technical token
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        String vcardUid = "member-sync-" + UUID.randomUUID();
        VCardContact contact = VCardContact.builder()
            .firstName("Domain")
            .lastName("Member")
            .email("domain.member@" + domainId)
            .build();

        // WHEN a domain member contact is created by the technical token
        cardDavClient.upsertDomainMemberContact(domainId, vcardUid, contact.toBytes(VCardContact.Format.JSON, vcardUid), technicalToken);
        // THEN the next sync report exposes the created contact and advances the sync token
        String createdSyncReportBody = given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + domainId + "/" + DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(createdSyncReportBody)
            .contains("<d:href>/addressbooks/" + domainId + "/" + DOMAIN_MEMBERS_ADDRESS_BOOK + "/" + vcardUid + ".vcf</d:href>")
            .contains("<d:status>HTTP/1.1 200 OK</d:status>")
            .contains("<d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>");

        VCardContact updatedContact = VCardContact.builder()
            .firstName("Updated")
            .lastName("Member")
            .email("updated.member@" + domainId)
            .build();

        // WHEN the same domain member contact is updated by the technical token
        cardDavClient.upsertDomainMemberContact(domainId, vcardUid, updatedContact.toBytes(VCardContact.Format.JSON, vcardUid), technicalToken);
        // THEN the next sync report exposes the updated contact and advances the sync token again
        String updatedSyncReportBody = given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + domainId + "/" + DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(updatedSyncReportBody)
            .contains("<d:href>/addressbooks/" + domainId + "/" + DOMAIN_MEMBERS_ADDRESS_BOOK + "/" + vcardUid + ".vcf</d:href>")
            .contains("<d:status>HTTP/1.1 200 OK</d:status>")
            .contains("<d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>");

        // WHEN the domain member contact is deleted by the technical token
        cardDavClient.deleteContactDomainMembers(domainId, vcardUid, technicalToken);
        // THEN the next sync report exposes the deleted contact as 404 and advances the sync token
        String deletedSyncReportBody = given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + domainId + "/" + DOMAIN_MEMBERS_ADDRESS_BOOK)
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(deletedSyncReportBody)
            .contains("<d:href>/addressbooks/" + domainId + "/" + DOMAIN_MEMBERS_ADDRESS_BOOK + "/" + vcardUid + ".vcf</d:href>")
            .contains("<d:status>HTTP/1.1 404 Not Found</d:status>")
            .contains("<d:sync-token>http://sabre.io/ns/sync/4</d:sync-token>");
    }

    @Test
    protected void domainAddressBookCanReportDomainContactChangesBySyncToken() {
        // GIVEN a domain admin and a domain address book provisioned by the technical token
        OpenPaasUser adminUser = newDomainUser();
        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(adminUser.email(), "@");

        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + adminUser.email())
            .then()
            .statusCode(204);

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "dab-sync-" + UUID.randomUUID();
        VCardContact contact = VCardContact.builder()
            .firstName("Domain")
            .lastName("Contact")
            .email("domain.contact@" + domainId)
            .build();

        // WHEN a domain contact is created in the domain address book by the technical token
        cardDavClient.upsertDomainContact(domainId, vcardUid, contact.toBytes(VCardContact.Format.JSON, vcardUid), technicalToken);
        // THEN the next sync report exposes the created contact and advances the sync token
        String createdSyncReportBody = given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + domainId + "/" + DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(createdSyncReportBody)
            .contains("<d:href>/addressbooks/" + domainId + "/" + DOMAIN_ADDRESS_BOOK + "/" + vcardUid + ".vcf</d:href>")
            .contains("<d:status>HTTP/1.1 200 OK</d:status>")
            .contains("<d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>");

        VCardContact updatedContact = VCardContact.builder()
            .firstName("Updated")
            .lastName("Contact")
            .email("updated.contact@" + domainId)
            .build();

        // WHEN the same domain contact is updated by the technical token
        cardDavClient.upsertDomainContact(domainId, vcardUid, updatedContact.toBytes(VCardContact.Format.JSON, vcardUid), technicalToken);
        // THEN the next sync report exposes the updated contact and advances the sync token again
        String updatedSyncReportBody = given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + domainId + "/" + DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(updatedSyncReportBody)
            .contains("<d:href>/addressbooks/" + domainId + "/" + DOMAIN_ADDRESS_BOOK + "/" + vcardUid + ".vcf</d:href>")
            .contains("<d:status>HTTP/1.1 200 OK</d:status>")
            .contains("<d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>");

        // WHEN the domain contact is deleted by a domain admin
        cardDavClient.deleteContact(adminUser, domainId, DOMAIN_ADDRESS_BOOK, vcardUid);
        // THEN the next sync report exposes the deleted contact as 404 and advances the sync token
        String deletedSyncReportBody = given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + domainId + "/" + DOMAIN_ADDRESS_BOOK)
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(deletedSyncReportBody)
            .contains("<d:href>/addressbooks/" + domainId + "/" + DOMAIN_ADDRESS_BOOK + "/" + vcardUid + ".vcf</d:href>")
            .contains("<d:status>HTTP/1.1 404 Not Found</d:status>")
            .contains("<d:sync-token>http://sabre.io/ns/sync/4</d:sync-token>");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void domainAdministratorCanAddContactToDomainAddressBook(VCardContact.Format format) {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        // Given bob is admin of the domain
        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        // When bob adds a contact to the domain address book
        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();
        cardDavClient.upsertContact(bob, domainId, "dab", vcardUid, contact.toBytes(format, vcardUid));

        String response = cardDavClient.getContacts(alice, domainId, "dab");

        // Then alice can see the contact
        assertThat(response).contains("John Doe");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void domainAdministratorCanUpdateContactInDomainAddressBook(VCardContact.Format format) {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();
        cardDavClient.upsertDomainContact(domainId, vcardUid, contact.toBytes(format, vcardUid), technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        // Given bob is admin of the domain
        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        // When bob update contact in the domain address book
        VCardContact updatedContact = VCardContact.builder()
            .firstName("John")
            .lastName("Cole")
            .build();
        cardDavClient.upsertContact(bob, domainId, "dab", vcardUid, updatedContact.toBytes(format, vcardUid));

        String response = cardDavClient.getContacts(alice, domainId, "dab");

        // Then alice can see the updated contact
        assertThat(response).contains("John Cole");
    }

    @Test
    void domainAdministratorCanDeleteContactInDomainAddressBook() {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();
        cardDavClient.upsertDomainContact(domainId, vcardUid, contact.toVCardJsonPayload(vcardUid), technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        // Given bob is admin of the domain
        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        // When bob delete contact in the domain address book
        cardDavClient.deleteContact(bob, domainId, "dab", vcardUid);

        String response = cardDavClient.getContacts(alice, domainId, "dab");

        // Then alice does not see the deleted contact
        assertThat(response).doesNotContain("John Doe");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void normalUserCannotAddContactToDomainAddressBook(VCardContact.Format format) {
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();

        Assertions.assertThatThrownBy(() -> cardDavClient.upsertContact(bob, domainId, "dab", vcardUid, contact.toBytes(format, vcardUid)))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void normalUserCannotDeleteContactToDomainAddressBook() {
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";

        Assertions.assertThatThrownBy(() -> cardDavClient.deleteContact(bob, domainId, "dab", vcardUid))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void normalUserCannotSetPublicRightOfDomainAddressBook() {
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        assertThatThrownBy(() -> cardDavClient.setPublicRight(bob, domainId, "dab", CardDavClient.PublicRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void normalUserCannotDelegateDomainAddressBook() {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        assertThatThrownBy(() -> cardDavClient.grantDelegation(bob, domainId, "dab", alice, CardDavClient.DelegationRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    protected void domainAdministratorCanSetPublicRightOfDomainAddressBook(VCardContact.Format format) {
        OpenPaasUser bob = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        // When bob is admin of the domain
        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        // And bob set public right on domain address book (set to read-write in this case)
        cardDavClient.setDomainAddressBookPublicRightReadWrite(bob, domainId, "dab");

        // Then any user can add the contact in the domain address book
        OpenPaasUser alice = newDomainUser();

        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();

        cardDavClient.upsertContact(alice, domainId, "dab", vcardUid, contact.toBytes(format, vcardUid));

        String response = cardDavClient.getContacts(alice, domainId, "dab");

        assertThat(response).contains("John Doe");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    protected void domainAdministratorCanDelegateDomainAddressBook(VCardContact.Format format) {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        // When bob is admin of the domain
        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        // And bob delegate domain address book (set to read-write in this case)
        cardDavClient.grantDomainBookDelegation(bob, domainId, "dab", alice, CardDavClient.DelegationRight.READ_WRITE);

        // Then alice can add the contact in the domain address book
        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();

        cardDavClient.upsertContact(alice, domainId, "dab", vcardUid, contact.toBytes(format, vcardUid));

        String response = cardDavClient.getContacts(alice, domainId, "dab");

        assertThat(response).contains("John Doe");

        // And alice can delete the contact in the domain address book
        cardDavClient.deleteContact(alice, domainId, "dab", vcardUid);
        String responseAfterDelete = cardDavClient.getContacts(alice, domainId, "dab");
        assertThat(responseAfterDelete).doesNotContain("John Doe");
    }

    @Test
    protected void domainAdministratorCanDelegateDomainAddressBookWithAdminRight() {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();
        OpenPaasUser cedric = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        // And bob is admin of the domain
        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        // When bob delegate domain address book (set to admin in this case) to alice
        cardDavClient.grantDomainBookDelegation(bob, domainId, "dab", alice, CardDavClient.DelegationRight.ADMIN);

        // Then alice can delegate domain address book to cedric with read-write right
        cardDavClient.grantDomainBookDelegation(alice, domainId, "dab", cedric, CardDavClient.DelegationRight.READ_WRITE);

        // And cedric can add the contact in the domain address book
        String vcardUid = "test-contact-uid";
        VCardContact contact = VCardContact.builder()
            .firstName("John")
            .lastName("Doe")
            .build();

        cardDavClient.upsertContact(cedric, domainId, "dab", vcardUid, contact.toBytes(VCardContact.Format.JSON, vcardUid));

        String response = cardDavClient.getContacts(cedric, domainId, "dab");

        assertThat(response).contains("John Doe");
    }

    @Test
    void domainAdministratorCannotSetPublicRightOfDomainMembersBook() {
        OpenPaasUser bob = newDomainUser();

        // Given domain members address book exists
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        // When bob is admin of the domain
        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        String expected = getAddressBookMetadata(domainId);

        // Then bob cannot set public right on domain members address book (set to read-write in this case)
        assertThatThrownBy(() -> cardDavClient.setDomainAddressBookPublicRightReadWrite(bob, domainId, "domain-members"))
            .hasMessageContaining("Unexpected status code:");

        String actual = getAddressBookMetadata(domainId);

        // And the public right does not change
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void domainAdministratorCannotDelegateDomainMembersBook() {
        OpenPaasUser alice = newDomainUser();
        OpenPaasUser bob = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        // When bob is admin of the domain
        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        String expected = getAddressBookMetadata(domainId);

        // Then bob cannot delegate domain members address book (set to read-write in this case)
        assertThatThrownBy(() -> cardDavClient.grantDomainBookDelegation(bob, domainId, "domain-members", alice, CardDavClient.DelegationRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 405");

        String actual = getAddressBookMetadata(domainId);

        // And the delegation is not set
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    protected void shouldReturn403WhenNonAdminUserSetPublicRightOfDomainAddressBook() {
        OpenPaasUser bob = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String expected = getAddressBookMetadata(domainId);

        // When bob set public right on domain address book
        // Then sabre rejects the request (403)
        assertThatThrownBy(() -> cardDavClient.setDomainAddressBookPublicRightReadWrite(bob, domainId, "dab"))
            .hasMessageContaining("Unexpected status code: 403");

        String actual = getAddressBookMetadata(domainId);
        // And the public right does not change
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    protected void shouldReturn400WhenDomainAdminSetEmptyPublicRightOfDomainAddressBook() {
        OpenPaasUser bob = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        // When bob is admin of the domain
        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        String expected = getAddressBookMetadata(domainId);

        // And bob set empty public right on domain address book
        // Then sabre rejects the request (400)
        String payload = """
            {
                "dav:group-addressbook": {
                    "privileges": []
                }
            }
            """;
        assertThatThrownBy(() -> cardDavClient.setDomainAddressBookPublicRight(bob, domainId, "dab", payload))
            .hasMessageContaining("Unexpected status code: 400");

        String actual = getAddressBookMetadata(domainId);
        // And the public right does not change
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    protected void shouldReturn400WhenDomainAdminSetInvalidPublicRightOfDomainAddressBook() {
        OpenPaasUser bob = newDomainUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

        String domainName = StringUtils.substringAfterLast(bob.email(), "@");

        // When bob is admin of the domain
        given()
            .baseUri(tcalendarAdminApiBase)
            .put("/domains/" + domainName + "/admins/" + bob.email())
            .then()
            .statusCode(204);

        String expected = getAddressBookMetadata(domainId);

        // And bob set empty public right on domain address book
        // Then sabre rejects the request (400)
        String payload = """
            {
                "dav:group-addressbook": {
                    "privileges": [
                        "{DAV:}invalid"
                    ]
                }
            }
            """;
        assertThatThrownBy(() -> cardDavClient.setDomainAddressBookPublicRight(bob, domainId, "dab", payload))
            .hasMessageContaining("Unexpected status code: 400");

        String actual = getAddressBookMetadata(domainId);
        // And the public right does not change
        assertThat(actual).isEqualTo(expected);
    }

    private OpenPaasUser newDomainUser() {
        return extension().twakeCalendarProvisioningService()
            .createUser("user_" + UUID.randomUUID(), domainName)
            .block();
    }

    private String getAddressBookMetadata(String domainId) {
        return given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .headers("TwakeCalendarToken", technicalToken)
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
            .when()
            .get("/addressbooks/" + domainId)
            .then()
            .extract().asString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COPY", "MOVE"})
    protected void domainAdminCannotTransferPersonalContactIntoDomainMembersBook(String method) {
        // Given a domain admin owns a card, but domain-members writes belong to technical users.
        given(extension().webAdminRequestSpecification())
        .when()
            .put("/domains/" + domainName + "/admins/" + openPaasUser.email())
        .then()
            .statusCode(SC_NO_CONTENT);
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(openPaasUser, "contacts", uid, VCardContact.builder()
            .firstName("Personal").lastName("Contact").build().toVCardPayload(uid));
        String source = "/addressbooks/" + openPaasUser.id() + "/contacts/" + uid + ".vcf";
        String destination = "/addressbooks/" + domainId + "/domain-members/" + uid + ".vcf";

        // When the admin tries COPY or MOVE instead of the guarded PUT endpoint.
        given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
            .header("Destination", destination)
        .when()
            .request(method, source)
        .then()
            .statusCode(SC_FORBIDDEN);

        // Then the personal card survives and no domain member was injected.
        given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
        .when()
            .get(source)
        .then()
            .statusCode(SC_OK);
        given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
        .when()
            .get(destination)
        .then()
            .statusCode(SC_NOT_FOUND);
    }

    protected static Stream<Arguments> domainMembersReadRequests() {
        return Stream.of(false, true).flatMap(domainAdministrator -> Stream.of(
            Arguments.of(domainAdministrator, "GET", "/directory-contact.vcf", "", "text/vcard", SC_OK),
            Arguments.of(domainAdministrator, "GET", "?export", "", "text/vcard", SC_OK),
            Arguments.of(domainAdministrator, "GET", ".json", "", "application/json", SC_OK),
            Arguments.of(domainAdministrator, "GET", ".json?email=directory.contact%40example.org", "", "application/json", SC_OK),
            Arguments.of(domainAdministrator, "PROPFIND", "", """
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:propfind>
                """, "application/xml", SC_MULTI_STATUS),
            Arguments.of(domainAdministrator, "REPORT", "", """
                <c:addressbook-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <c:address-data/>
                  </d:prop>
                </c:addressbook-query>
                """, "application/xml", SC_MULTI_STATUS),
            Arguments.of(domainAdministrator, "REPORT", "", """
                <c:addressbook-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <c:address-data/>
                  </d:prop>
                  <d:href>BOOK_PATH/directory-contact.vcf</d:href>
                </c:addressbook-multiget>
                """, "application/xml", SC_MULTI_STATUS),
            Arguments.of(domainAdministrator, "REPORT", "", """
                <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
                  <d:sync-token/>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                    <c:address-data/>
                  </d:prop>
                </d:sync-collection>
                """, "application/xml", SC_MULTI_STATUS)));
    }

    @ParameterizedTest
    @MethodSource("domainMembersReadRequests")
    protected void restrictedPolicyShouldDenyMembersButPreserveDomainAdministratorReads(boolean domainAdministrator, String method,
                                                                                 String suffix, String body,
                                                                                 String accept, int initialStatus) {
        OpenPaasUser administrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        // Given both members and domain administrators can read the existing directory.
        OpenPaasUser user = domainAdministrator ? administrator : openPaasUser;
        given()
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Depth", "1")
            .accept(accept)
            .body(body.replace("BOOK_PATH", bookPath))
            .request(method, bookPath + suffix)
        .then()
            .statusCode(initialStatus);

        // When the domain's technical token restricts access.
        given()
            .header("TwakeCalendarToken", technicalToken)
            .body(RESTRICTED_DOMAIN_MEMBERS_ACL)
            .request("ACL", bookPath)
        .then()
            .statusCode(SC_OK);

        // Then members are denied, while domain administrators retain access to the preserved card.
        String denied = given()
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Depth", "1")
            .accept(accept)
            .body(body.replace("BOOK_PATH", bookPath))
            .request(method, bookPath + suffix)
        .then()
            .statusCode(domainAdministrator ? initialStatus : SC_FORBIDDEN).extract().asString();
        if (!domainAdministrator) {
            assertThat(denied).doesNotContain("directory.contact@example.org", "BEGIN:VCARD");
        }
        String stored = given()
            .header("TwakeCalendarToken", technicalToken)
            .accept("text/vcard")
            .get(bookPath + "/directory-contact.vcf")
        .then()
            .statusCode(SC_OK).extract().asString();
        assertThat(stored).contains("directory.contact@example.org");
    }

    @Test
    protected void technicalTokenCanSynchronizeAndRestoreAccessWithoutRepopulatingCards() {
        OpenPaasUser administrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        // Given an idempotent restriction, with the existing card still present.
        for (int attempt = 0; attempt < 2; attempt++) {
            given().header("TwakeCalendarToken", technicalToken)
                .body(RESTRICTED_DOMAIN_MEMBERS_ACL)
                .request("ACL", bookPath)
            .then().statusCode(SC_OK);
        }

        // LDAP synchronization exports the existing cards before computing its changes.
        String beforeSync = given().header("TwakeCalendarToken", technicalToken).accept("text/vcard")
            .get(bookPath + "?export")
        .then().statusCode(SC_OK).extract().asString();
        assertThat(beforeSync).contains("directory.contact@example.org");

        // When synchronization updates, adds and deletes cards while end users are denied access.
        given().header("TwakeCalendarToken", technicalToken).contentType("text/vcard")
            .body(DOMAIN_MEMBER_VCARD.replace("FN:Directory Contact", "FN:Updated Directory Contact"))
            .put(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_NO_CONTENT);
        for (String uid : List.of("added", "deleted")) {
            given().header("TwakeCalendarToken", technicalToken).contentType("text/vcard")
                .body(DOMAIN_MEMBER_VCARD.replace("UID:directory-contact", "UID:" + uid))
                .put(bookPath + "/" + uid + ".vcf")
            .then().statusCode(SC_CREATED);
        }
        given().header("TwakeCalendarToken", technicalToken).delete(bookPath + "/deleted.vcf")
        .then().statusCode(SC_NO_CONTENT);
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/added.vcf")
        .then().statusCode(SC_FORBIDDEN);

        String afterSync = given().header("TwakeCalendarToken", technicalToken).accept("text/vcard")
            .get(bookPath + "?export")
        .then().statusCode(SC_OK).extract().asString();
        assertThat(afterSync).contains("FN:Updated Directory Contact", "UID:added").doesNotContain("UID:deleted");

        // Then restoring access twice exposes the same cards without a reimport.
        for (int attempt = 0; attempt < 2; attempt++) {
            given().header("TwakeCalendarToken", technicalToken)
                .body(DOMAIN_MEMBERS_READ_ACL.replace("DOMAIN_ID", domainId))
                .request("ACL", bookPath)
            .then().statusCode(SC_OK);
        }
        for (OpenPaasUser user : List.of(openPaasUser, administrator)) {
            String card = given().header("Authorization", user.impersonatedBasicAuth()).accept("text/vcard")
                .get(bookPath + "/directory-contact.vcf")
            .then().statusCode(SC_OK).extract().asString();
            assertThat(card).contains("Updated Directory Contact");
            given().header("Authorization", user.impersonatedBasicAuth()).get(bookPath + "/added.vcf")
            .then().statusCode(SC_OK);
        }
        given().header("TwakeCalendarToken", technicalToken).get(bookPath + "/deleted.vcf")
        .then().statusCode(SC_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    protected void endUsersCannotChangeAclEvenAsDomainAdministrators(boolean domainAdministrator) {
        OpenPaasUser administrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        // Given a restricted directory, only the technical token may reopen it.
        given().header("TwakeCalendarToken", technicalToken)
            .body(RESTRICTED_DOMAIN_MEMBERS_ACL)
            .request("ACL", bookPath)
        .then().statusCode(SC_OK);
        OpenPaasUser user = domainAdministrator ? administrator : openPaasUser;
        for (String policy : List.of(RESTRICTED_DOMAIN_MEMBERS_ACL, DOMAIN_MEMBERS_READ_ACL)) {
            given().header("Authorization", user.impersonatedBasicAuth())
                .body(policy.replace("DOMAIN_ID", domainId))
                .request("ACL", bookPath)
            .then().statusCode(SC_FORBIDDEN);
        }
    }

    @Test
    protected void foreignTechnicalTokenCannotChangeOrReadTheDirectory() {
        String bookPath = createDomainMembersDirectory();
        // Given a valid technical token issued for a different domain.
        String foreignId = extension().twakeCalendarProvisioningService()
            .createDomainIfNotExists("foreign-" + UUID.randomUUID() + ".test").getObjectId("_id").toString();
        String foreignToken = extension().twakeCalendarProvisioningService().generateToken(foreignId);

        // Then it cannot change either access policy or read a card.
        for (String policy : List.of(RESTRICTED_DOMAIN_MEMBERS_ACL, DOMAIN_MEMBERS_READ_ACL)) {
            given().header("TwakeCalendarToken", foreignToken)
                .body(policy.replace("DOMAIN_ID", domainId))
                .request("ACL", bookPath)
            .then().statusCode(SC_FORBIDDEN);
        }
        given().header("TwakeCalendarToken", foreignToken).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_FORBIDDEN);
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
    }

    protected static Stream<Arguments> invalidDomainMembersAcls() {
        return Stream.of(
            Arguments.of("", SC_BAD_REQUEST, ""),
            Arguments.of("""
                <d:acl xmlns:d="DAV:">
                """, SC_BAD_REQUEST, ""),
            Arguments.of("""
                <d:not-acl xmlns:d="DAV:"/>
                """, SC_BAD_REQUEST, ""),
            Arguments.of("""
                <d:acl xmlns:d="DAV:">
                  <d:ace/>
                </d:acl>
                """, SC_BAD_REQUEST, ""),
            Arguments.of("""
                <d:acl xmlns:d="DAV:">
                  <ace/>
                </d:acl>
                """, SC_BAD_REQUEST, ""),
            Arguments.of(DOMAIN_MEMBERS_READ_ACL.replace("</d:acl>", "  <ace/>\n</d:acl>"), SC_BAD_REQUEST, ""),
            Arguments.of(DOMAIN_MEMBERS_READ_ACL.replace("<d:read/>", "<d:unknown/>"), SC_FORBIDDEN, "not-supported-privilege"),
            Arguments.of(DOMAIN_MEMBERS_READ_ACL.replace("/principals/domains/DOMAIN_ID", "/principals/users/000000000000000000000000"), SC_FORBIDDEN, "recognized-principal"),
            Arguments.of(DOMAIN_MEMBERS_READ_ACL.replace("</d:ace>", "  <d:protected/>\n  </d:ace>"), SC_CONFLICT, "no-ace-conflict"));
    }

    @ParameterizedTest
    @MethodSource("invalidDomainMembersAcls")
    protected void invalidAclShouldLeaveExistingPermissionsUnchanged(String body, int status, String condition) throws Exception {
        newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        // Given the full generated ACL, including grants beyond this member's read access.
        String propfind = """
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:acl/>
              </d:prop>
            </d:propfind>
            """;
        String before = given().header("TwakeCalendarToken", technicalToken).header("Depth", "0").body(propfind)
            .request("PROPFIND", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();

        // When an invalid ACL is submitted through the standard WebDAV method.
        String response = given().header("TwakeCalendarToken", technicalToken)
            .body(body.replace("DOMAIN_ID", domainId))
            .request("ACL", bookPath)
        .then().statusCode(status).extract().asPrettyString();
        if (!condition.isEmpty()) {
            assertThat(XMLUtil.extractByXPath(response, "count(/d:error/d:" + condition + ")", Map.of("d", "DAV:")))
                .isEqualTo("1");
        }
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
        String after = given().header("TwakeCalendarToken", technicalToken).header("Depth", "0").body(propfind)
            .request("PROPFIND", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        XmlAssert.assertThat(editableAclFromPropfind(after))
            .and(editableAclFromPropfind(before))
            .ignoreWhitespace()
            .areIdentical();
    }

    @Test
    protected void standardAclRequiresAuthenticationAndRemainsRefusedOnPersonalBooks() {
        String bookPath = createDomainMembersDirectory();
        given().body(RESTRICTED_DOMAIN_MEMBERS_ACL)
            .request("ACL", bookPath)
        .then().statusCode(SC_UNAUTHORIZED);
        given().header("TwakeCalendarToken", technicalToken)
            .body(RESTRICTED_DOMAIN_MEMBERS_ACL)
            .request("ACL", "/addressbooks/" + openPaasUser.id() + "/contacts")
        .then().statusCode(SC_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    protected void aggregatedDirectoryShouldHonorMemberAclAndPreserveAdministratorAccess(boolean domainAdministrator) {
        OpenPaasUser administrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        OpenPaasUser user = domainAdministrator ? administrator : openPaasUser;
        String path = "/contacts/" + user.id() + ".json?domainMember=true";
        // Given the aggregate includes the directory before access is restricted.
        String before = given().header("Authorization", user.impersonatedBasicAuth()).accept(ContentType.JSON).get(path)
        .then().statusCode(SC_OK).extract().asString();
        assertThat(before).contains("directory.contact@example.org");
        given().header("TwakeCalendarToken", technicalToken)
            .body(RESTRICTED_DOMAIN_MEMBERS_ACL)
            .request("ACL", bookPath)
        .then().statusCode(SC_OK);
        String after = given().header("Authorization", user.impersonatedBasicAuth()).accept(ContentType.JSON).get(path)
        .then().statusCode(SC_OK).extract().asString();
        assertThat(after.contains("directory.contact@example.org")).isEqualTo(domainAdministrator);

        // Restoring domain read access makes the directory visible to aggregation again.
        given().header("TwakeCalendarToken", technicalToken).body(DOMAIN_MEMBERS_READ_ACL.replace("DOMAIN_ID", domainId))
            .request("ACL", bookPath)
        .then().statusCode(SC_OK);
        String restored = given().header("Authorization", user.impersonatedBasicAuth()).accept(ContentType.JSON).get(path)
        .then().statusCode(SC_OK).extract().asString();
        assertThat(restored).contains("directory.contact@example.org");
    }

    @Test
    protected void standardAclCanGrantOnePrincipalAndBeReadBackWithPropfind() throws Exception {
        OpenPaasUser administrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        // Given an ACL granting one member, while administrators retain protected role grants.
        OpenPaasUser anotherMember = newDomainUser();
        String acl = DOMAIN_MEMBERS_READ_ACL.replace("/principals/domains/DOMAIN_ID", "/principals/users/" + openPaasUser.id());
        given().header("TwakeCalendarToken", technicalToken).body(acl).request("ACL", bookPath)
        .then().statusCode(SC_OK);
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
        given().header("Authorization", administrator.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
        given().header("Authorization", anotherMember.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_FORBIDDEN);

        // Then DAV:acl distinguishes the editable grant from protected administrator rights.
        String result = given().header("TwakeCalendarToken", technicalToken).header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:acl/>
                  </d:prop>
                </d:propfind>
                """)
            .request("PROPFIND", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        assertThat(result).contains("principals/users/" + openPaasUser.id(), "principals/users/" + administrator.id());
        assertThat(editableAclFromPropfind(result)).contains("principals/users/" + openPaasUser.id())
            .doesNotContain("principals/users/" + administrator.id());
    }

    @Test
    protected void savedAclCanBeRestoredWithoutReplacingAdministratorRights() throws Exception {
        OpenPaasUser administrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectory();
        // Given the editable member ACL, excluding protected administrator privileges.
        String propfind = """
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:acl/>
              </d:prop>
            </d:propfind>
            """;
        String before = given().header("TwakeCalendarToken", technicalToken).header("Depth", "0").body(propfind)
            .request("PROPFIND", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        String savedAcl = editableAclFromPropfind(before);
        assertThat(savedAcl).doesNotContain("write", "technicalUser", "principals/users/" + administrator.id());

        // When access is revoked and the saved editable ACEs are reapplied.
        given().header("TwakeCalendarToken", technicalToken).body(RESTRICTED_DOMAIN_MEMBERS_ACL).request("ACL", bookPath)
        .then().statusCode(SC_OK);
        given().header("Authorization", administrator.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_FORBIDDEN);
        given().header("TwakeCalendarToken", technicalToken).body(savedAcl).request("ACL", bookPath)
        .then().statusCode(SC_OK);

        // Then the editable ACL matches and the administrator retains write-properties access.
        String after = given().header("TwakeCalendarToken", technicalToken).header("Depth", "0").body(propfind)
            .request("PROPFIND", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        XmlAssert.assertThat(editableAclFromPropfind(after))
            .and(savedAcl)
            .ignoreWhitespace()
            .areIdentical();
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
        String update = given().header("Authorization", administrator.impersonatedBasicAuth())
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>Restored directory</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """)
            .request("PROPPATCH", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        assertThat(XMLUtil.extractByXPath(update, "//d:propstat[d:prop/d:displayname]/d:status", Map.of("d", "DAV:")))
            .isEqualTo("HTTP/1.1 200 OK");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    protected void restrictedDirectoryShouldBlockMemberSyncButPreserveDomainAdministratorSync(boolean domainAdministrator) throws Exception {
        // Given an end user has synchronized the directory before access is revoked.
        OpenPaasUser administrator = newDomainAdministrator();
        OpenPaasUser user = domainAdministrator ? administrator : openPaasUser;
        String bookPath = createDomainMembersDirectory();
        String properties = given().header("Authorization", user.impersonatedBasicAuth()).header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:sync-token/>
                  </d:prop>
                </d:propfind>
                """)
            .request("PROPFIND", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        String syncToken = XMLUtil.extractByXPath(properties, "//d:propstat/d:prop/d:sync-token", Map.of("d", "DAV:"));
        assertThat(syncToken).isNotBlank();
        String report = """
            <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
              <d:sync-token>SYNC_TOKEN</d:sync-token>
              <d:sync-level>1</d:sync-level>
              <d:prop>
                <d:getetag/>
                <c:address-data/>
              </d:prop>
            </d:sync-collection>
            """.replace("SYNC_TOKEN", syncToken);

        // When the technical token changes a contact while the directory has an empty ACL.
        given().header("TwakeCalendarToken", technicalToken).body(RESTRICTED_DOMAIN_MEMBERS_ACL).request("ACL", bookPath)
        .then().statusCode(SC_OK);
        given().header("TwakeCalendarToken", technicalToken).contentType("text/vcard")
            .body(DOMAIN_MEMBER_VCARD.replace("FN:Directory Contact", "FN:Updated While Restricted"))
            .put(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_NO_CONTENT);

        // Then an old sync token does not bypass member revocation; administrators retain access.
        String userReport = given().header("Authorization", user.impersonatedBasicAuth()).header("Depth", "1")
            .body(report).request("REPORT", bookPath)
        .then().statusCode(domainAdministrator ? SC_MULTI_STATUS : SC_FORBIDDEN).extract().asString();
        if (domainAdministrator) {
            assertThat(userReport).contains(bookPath + "/directory-contact.vcf", "Updated While Restricted");
        } else {
            assertThat(userReport).doesNotContain("directory.contact@example.org", "Updated While Restricted",
                bookPath + "/directory-contact.vcf");
        }
        String technicalReport = given().header("TwakeCalendarToken", technicalToken).header("Depth", "1")
            .body(report).request("REPORT", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        assertThat(technicalReport).contains(bookPath + "/directory-contact.vcf", "Updated While Restricted");
        assertThat(XMLUtil.extractByXPath(technicalReport, "/d:multistatus/d:sync-token", Map.of("d", "DAV:")))
            .isNotBlank().isNotEqualTo(syncToken);

        // Restoring member access also allows the end user to resume from the previous token.
        given().header("TwakeCalendarToken", technicalToken).body(DOMAIN_MEMBERS_READ_ACL.replace("DOMAIN_ID", domainId))
            .request("ACL", bookPath)
        .then().statusCode(SC_OK);
        String resumed = given().header("Authorization", user.impersonatedBasicAuth()).header("Depth", "1")
            .body(report).request("REPORT", bookPath)
        .then().statusCode(SC_MULTI_STATUS).extract().asPrettyString();
        assertThat(resumed).contains(bookPath + "/directory-contact.vcf", "Updated While Restricted");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    protected void legacyDirectoryDelegationCannotBypassRevocation(boolean domainMember) {
        // Given an accepted legacy delegation; current HTTP APIs deliberately forbid creating it.
        String bookPath = createDomainMembersDirectory();
        String mirrorUri = "legacy-" + UUID.randomUUID();
        String mongoUri = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.MONGO, "mongodb").toString();
        try (MongoClient mongo = MongoClients.create(mongoUri)) {
            Document addressBook = Mono.from(mongo.getDatabase("sabre").getCollection("addressbooks")
                .find(Filters.and(Filters.eq("principaluri", "principals/domains/" + domainId),
                    Filters.eq("uri", DOMAIN_MEMBERS_ADDRESS_BOOK))).first()).block();
            assertThat(addressBook).isNotNull();
            Document delegation = new Document("addressbookid", addressBook.getObjectId("_id"))
                .append("principaluri", "principals/users/" + openPaasUser.id())
                .append("uri", mirrorUri)
                .append("displayname", "Legacy directory")
                .append("description", "Legacy domain-members delegation")
                .append("share_access", CardDavClient.DelegationRight.READ.getValue())
                .append("share_href", "mailto:" + openPaasUser.email())
                .append("share_invitestatus", 2); // Sabre's INVITE_ACCEPTED.
            assertThat(Mono.from(mongo.getDatabase("sabre").getCollection("sharedaddressbooks")
                .insertOne(delegation)).block().wasAcknowledged()).isTrue();
        }
        String aggregatePath = "/contacts/" + openPaasUser.id() + ".json?domainMember=" + domainMember;
        String before = given().header("Authorization", openPaasUser.impersonatedBasicAuth())
            .accept(ContentType.JSON).get(aggregatePath)
        .then().statusCode(SC_OK).extract().asString();
        assertThat(before).contains("directory.contact@example.org");
        given().header("Authorization", openPaasUser.impersonatedBasicAuth()).get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);

        // When the technical token replaces all user grants with an empty ACL.
        given().header("TwakeCalendarToken", technicalToken).body(RESTRICTED_DOMAIN_MEMBERS_ACL).request("ACL", bookPath)
        .then().statusCode(SC_OK);

        // Then neither direct reads nor aggregation through the old delegation exposes the card.
        String denied = given().header("Authorization", openPaasUser.impersonatedBasicAuth())
            .get(bookPath + "/directory-contact.vcf")
        .then().statusCode(SC_FORBIDDEN).extract().asString();
        assertThat(denied).doesNotContain("directory.contact@example.org");
        given().header("Authorization", openPaasUser.impersonatedBasicAuth())
            .get("/addressbooks/" + openPaasUser.id() + "/" + mirrorUri + "/directory-contact.vcf")
        .then().statusCode(SC_NOT_FOUND);
        String after = given().header("Authorization", openPaasUser.impersonatedBasicAuth())
            .accept(ContentType.JSON).get(aggregatePath)
        .then().statusCode(SC_OK).extract().asString();
        assertThat(after).doesNotContain("directory.contact@example.org", bookPath + "/directory-contact.vcf");

        // The accepted delegation remains in storage, so absence of data comes from ACL enforcement.
        try (MongoClient mongo = MongoClients.create(mongoUri)) {
            assertThat(Mono.from(mongo.getDatabase("sabre").getCollection("sharedaddressbooks")
                .countDocuments(Filters.and(Filters.eq("uri", mirrorUri), Filters.eq("share_invitestatus", 2))))
                .block()).isEqualTo(1L);
        }
    }

    @Test
    protected void restoredDomainAclShouldAllowNewMembersToRead() {
        // Given the directory default ACL has been saved and restored.
        String bookPath = createDomainMembersDirectoryWithRestoredAcl();

        // When a new user joins the domain after restoration.
        OpenPaasUser newMember = newDomainUser();

        // Then both new and existing members can read the directory contact.
        given()
            .header("Authorization", newMember.impersonatedBasicAuth())
        .when()
            .get(bookPath + "/directory-contact.vcf")
        .then()
            .statusCode(SC_OK);

        given()
            .header("Authorization", openPaasUser.impersonatedBasicAuth())
        .when()
            .get(bookPath + "/directory-contact.vcf")
        .then()
            .statusCode(SC_OK);
    }

    @Test
    protected void restoredDomainAclShouldKeepReadAccessForFormerAdministrators() {
        // Given the default ACL is restored while the user is a domain administrator.
        OpenPaasUser previousAdministrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectoryWithRestoredAcl();

        // When the user loses the administrator role but remains a domain member.
        given(extension().webAdminRequestSpecification())
        .when()
            .delete("/domains/" + domainName + "/admins/" + previousAdministrator.email())
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then the former administrator retains ordinary member read access.
        given()
            .header("Authorization", previousAdministrator.impersonatedBasicAuth())
        .when()
            .get(bookPath + "/directory-contact.vcf")
        .then()
            .statusCode(SC_OK);
    }

    @Test
    protected void restoredDomainAclShouldRevokeWriteAccessFromFormerAdministrators() {
        // Given the default ACL is restored while the user is a domain administrator.
        OpenPaasUser previousAdministrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectoryWithRestoredAcl();

        // When the user loses the administrator role but remains a domain member.
        given(extension().webAdminRequestSpecification())
        .when()
            .delete("/domains/" + domainName + "/admins/" + previousAdministrator.email())
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then the former administrator can no longer change the directory properties.
        given()
            .header("Authorization", previousAdministrator.impersonatedBasicAuth())
            .body(DOMAIN_MEMBERS_DISPLAY_NAME_UPDATE)
        .when()
            .request("PROPPATCH", bookPath)
        .then()
            .statusCode(SC_FORBIDDEN);
    }

    @Test
    protected void restoredDomainAclShouldGrantWriteAccessToNewAdministrators() throws Exception {
        // Given the directory default ACL has been saved and restored.
        String bookPath = createDomainMembersDirectoryWithRestoredAcl();

        // When a user becomes a domain administrator after restoration.
        OpenPaasUser newAdministrator = newDomainAdministrator();

        // Then the new administrator can read the contact and update the directory properties.
        given()
            .header("Authorization", newAdministrator.impersonatedBasicAuth())
        .when()
            .get(bookPath + "/directory-contact.vcf")
        .then()
            .statusCode(SC_OK);

        String update = given()
            .header("Authorization", newAdministrator.impersonatedBasicAuth())
            .body(DOMAIN_MEMBERS_DISPLAY_NAME_UPDATE)
        .when()
            .request("PROPPATCH", bookPath)
        .then()
            .statusCode(SC_MULTI_STATUS)
            .extract().asPrettyString();

        assertThat(XMLUtil.extractByXPath(update, "//d:propstat[d:prop/d:displayname]/d:status", Map.of("d", "DAV:")))
            .isEqualTo("HTTP/1.1 200 OK");
    }

    @Test
    protected void restoredDomainAclShouldKeepMemberGrantsAfterAdministratorChange() {
        // Given the restored member ACL grants access through the domain principal.
        OpenPaasUser previousAdministrator = newDomainAdministrator();
        String bookPath = createDomainMembersDirectoryWithRestoredAcl();
        String savedAcl = editableAclFromPropfind(readDomainAcl(bookPath));

        assertThat(savedAcl)
            .contains("/principals/domains/" + domainId)
            .doesNotContain("principals/users/");

        // When a new administrator replaces the previous administrator.
        newDomainAdministrator();
        given(extension().webAdminRequestSpecification())
        .when()
            .delete("/domains/" + domainName + "/admins/" + previousAdministrator.email())
        .then()
            .statusCode(SC_NO_CONTENT);

        // Then the editable member grants remain unchanged.
        XmlAssert.assertThat(editableAclFromPropfind(readDomainAcl(bookPath)))
            .and(savedAcl)
            .ignoreWhitespace()
            .areIdentical();
    }

    private String readDomainAcl(String bookPath) {
        String propfind = """
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:acl/>
              </d:prop>
            </d:propfind>
            """;
        return given()
            .header("TwakeCalendarToken", technicalToken)
            .header("Depth", "0")
            .body(propfind)
        .when()
            .request("PROPFIND", bookPath)
        .then()
            .statusCode(SC_MULTI_STATUS)
            .extract().asPrettyString();
    }

    @ParameterizedTest
    @MethodSource("domainMembersReadRequests")
    protected void foreignDomainMemberOrAdministratorCannotReadDirectory(boolean domainAdministrator, String method,
                                                                         String suffix, String body,
                                                                         String accept, int initialStatus) {
        // Given the caller can read its own domain, and another domain has a distinct directory contact.
        OpenPaasUser caller = domainAdministrator ? newDomainAdministrator() : openPaasUser;
        String ownBookPath = createDomainMembersDirectory();
        String otherDomainName = "foreign-directory-" + UUID.randomUUID() + ".test";
        String otherDomainId = extension().twakeCalendarProvisioningService().createDomainIfNotExists(otherDomainName)
            .getObjectId("_id").toString();
        OpenPaasUser otherMember = extension().twakeCalendarProvisioningService().createUser("member", otherDomainName).block();
        String otherToken = extension().twakeCalendarProvisioningService().generateToken(otherDomainId);
        String otherBookPath = "/addressbooks/" + otherDomainId + "/domain-members";
        cardDavClient.createDomainMembersAddressBook(otherDomainId, otherToken);
        cardDavClient.upsertDomainMemberContact(otherDomainId, "directory-contact",
            DOMAIN_MEMBER_VCARD.replace("directory.contact@example.org", "foreign.contact@example.org")
                .getBytes(StandardCharsets.UTF_8), otherToken);
        if (domainAdministrator) {
            given().header("TwakeCalendarToken", technicalToken).body(RESTRICTED_DOMAIN_MEMBERS_ACL)
                .request("ACL", ownBookPath)
            .then().statusCode(SC_OK);
        }
        given().header("Authorization", caller.impersonatedBasicAuth()).header("Depth", "1").accept(accept)
            .body(body.replace("BOOK_PATH", ownBookPath)).request(method, ownBookPath + suffix)
        .then().statusCode(initialStatus);

        // Then the same read is denied in the other domain, before and after its ACL is emptied.
        for (String policy : List.of("default", "restricted")) {
            if (policy.equals("restricted")) {
                given().header("TwakeCalendarToken", otherToken).body(RESTRICTED_DOMAIN_MEMBERS_ACL)
                    .request("ACL", otherBookPath)
                .then().statusCode(SC_OK);
            }
            String denied = given().header("Authorization", caller.impersonatedBasicAuth()).header("Depth", "1")
                .accept(accept).body(body.replace("BOOK_PATH", otherBookPath)).request(method, otherBookPath + suffix)
            .then().statusCode(SC_FORBIDDEN).extract().asString();
            assertThat(denied).doesNotContain("foreign.contact@example.org", "BEGIN:VCARD");
            given().header("Authorization", caller.impersonatedBasicAuth()).accept(ContentType.JSON)
                .get("/contacts/" + otherMember.id() + ".json?domainMember=true")
            .then().statusCode(SC_FORBIDDEN);
            String ownContacts = given().header("Authorization", caller.impersonatedBasicAuth()).accept(ContentType.JSON)
                .get("/contacts/" + caller.id() + ".json?domainMember=true")
            .then().statusCode(SC_OK).extract().asString();
            assertThat(ownContacts).contains("directory.contact@example.org").doesNotContain("foreign.contact@example.org");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "PROPPATCH", "ACL"})
    protected void domainAdministratorCannotModifyAnotherDomainsDirectory(String method) {
        // Given two domains; this user administers only the first one.
        OpenPaasUser administrator = newDomainAdministrator();
        String ownBookPath = createDomainMembersDirectory();
        given().header("Authorization", administrator.impersonatedBasicAuth()).get(ownBookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK);
        String otherDomainName = "foreign-directory-" + UUID.randomUUID() + ".test";
        String otherDomainId = extension().twakeCalendarProvisioningService().createDomainIfNotExists(otherDomainName)
            .getObjectId("_id").toString();
        String otherToken = extension().twakeCalendarProvisioningService().generateToken(otherDomainId);
        String otherBookPath = "/addressbooks/" + otherDomainId + "/domain-members";
        cardDavClient.createDomainMembersAddressBook(otherDomainId, otherToken);
        cardDavClient.upsertDomainMemberContact(otherDomainId, "directory-contact",
            DOMAIN_MEMBER_VCARD.getBytes(StandardCharsets.UTF_8), otherToken);
        String payload = switch (method) {
            case "PUT" -> DOMAIN_MEMBER_VCARD.replace("FN:Directory Contact", "FN:Cross-domain overwrite");
            case "ACL" -> DOMAIN_MEMBERS_READ_ACL.replace("DOMAIN_ID", otherDomainId);
            case "PROPPATCH" -> """
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>Cross-domain rename</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """;
            default -> "";
        };
        String target = otherBookPath + (List.of("PUT", "DELETE").contains(method) ? "/directory-contact.vcf" : "");

        // Then administrator role grants never authorize writes or ACL changes in another domain.
        for (String policy : List.of("default", "restricted")) {
            if (policy.equals("restricted")) {
                given().header("TwakeCalendarToken", otherToken).body(RESTRICTED_DOMAIN_MEMBERS_ACL)
                    .request("ACL", otherBookPath)
                .then().statusCode(SC_OK);
            }
            given().header("Authorization", administrator.impersonatedBasicAuth())
                .contentType(method.equals("PUT") ? "text/vcard" : "application/xml")
                .body(payload).request(method, target)
            .then().statusCode(SC_FORBIDDEN);
        }
        String preserved = given().header("TwakeCalendarToken", otherToken).accept("text/vcard")
            .get(otherBookPath + "/directory-contact.vcf")
        .then().statusCode(SC_OK).extract().asString();
        assertThat(preserved).contains("FN:Directory Contact").doesNotContain("Cross-domain overwrite");
    }

    private static String editableAclFromPropfind(String response) {
        JAXPXPathEngine xpath = new JAXPXPathEngine();
        xpath.setNamespaceContext(Map.of("d", "DAV:"));
        Node acl = xpath.selectNodes("/d:multistatus/d:response[1]/d:propstat/d:prop/d:acl",
            Input.fromString(response).build()).iterator().next();
        // Protected and inherited grants are reported by PROPFIND but cannot be resubmitted through ACL.
        for (Node ace : xpath.selectNodes("d:ace[d:protected or d:inherited]", acl)) {
            acl.removeChild(ace);
        }
        return Transform.source(Input.fromNode(acl).build())
            .withOutputProperty(OutputKeys.INDENT, "yes")
            .withOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            .build().toString();
    }

    private String createDomainMembersDirectory() {
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);
        cardDavClient.upsertDomainMemberContact(domainId, "directory-contact", DOMAIN_MEMBER_VCARD.getBytes(StandardCharsets.UTF_8), technicalToken);
        return "/addressbooks/" + domainId + "/domain-members";
    }

    private String createDomainMembersDirectoryWithRestoredAcl() {
        String bookPath = createDomainMembersDirectory();
        String savedAcl = editableAclFromPropfind(readDomainAcl(bookPath));

        given()
            .header("TwakeCalendarToken", technicalToken)
            .body("<d:acl xmlns:d=\"DAV:\"/>")
        .when()
            .request("ACL", bookPath)
        .then()
            .statusCode(SC_OK);

        given()
            .header("TwakeCalendarToken", technicalToken)
            .body(savedAcl)
        .when()
            .request("ACL", bookPath)
        .then()
            .statusCode(SC_OK);

        return bookPath;
    }

    private OpenPaasUser newDomainAdministrator() {
        OpenPaasUser administrator = newDomainUser();
        given(extension().webAdminRequestSpecification())
            .put("/domains/" + domainName + "/admins/" + administrator.email())
        .then()
            .statusCode(SC_NO_CONTENT);
        return administrator;
    }
}
