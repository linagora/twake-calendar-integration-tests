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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.VCardContact;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

public abstract class DomainAddressBookContract {

    public abstract DockerTwakeCalendarExtension extension();

    private CardDavClient cardDavClient;
    private OpenPaasUser openPaasUser;
    private String technicalToken;
    private String domainId;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(extension().davHttpClient());
        openPaasUser = extension().newTestUser();
        domainId = extension().domainId();
        technicalToken = extension().twakeCalendarProvisioningService().generateToken();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig()
                .encoderConfig(EncoderConfig.encoderConfig()
                    .defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(extension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http")
                .toString())
            .build();

        cardDavClient.deleteAddressBook(domainId, "dab", technicalToken);
        cardDavClient.deleteAddressBook(domainId, "domain-members", technicalToken);
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
        OpenPaasUser adminUser = extension().newTestUser();
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

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    void domainAdministratorCanAddContactToDomainAddressBook(VCardContact.Format format) {
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";

        Assertions.assertThatThrownBy(() -> cardDavClient.deleteContact(bob, domainId, "dab", vcardUid))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void normalUserCannotSetPublicRightOfDomainAddressBook() {
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        assertThatThrownBy(() -> cardDavClient.setPublicRight(bob, domainId, "dab", CardDavClient.PublicRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void normalUserCannotDelegateDomainAddressBook() {
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        assertThatThrownBy(() -> cardDavClient.grantDelegation(bob, domainId, "dab", alice, CardDavClient.DelegationRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @ParameterizedTest
    @EnumSource(VCardContact.Format.class)
    protected void domainAdministratorCanSetPublicRightOfDomainAddressBook(VCardContact.Format format) {
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

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
        OpenPaasUser alice = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();
        OpenPaasUser cedric = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

        // Given domain address book exists
        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String tcalendarAdminApiBase = extension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
            .toString();

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
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

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
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

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

    private String getAddressBookMetadata(String domainId) {
        return given()
            .headers("TwakeCalendarToken", technicalToken)
            .queryParam("contactsCount", true)
            .queryParam("inviteStatus", 2)
            .queryParam("personal", true)
            .queryParam("shared", true)
            .queryParam("subscribed", true)
            .when()
            .get("/addressbooks/" + domainId + ".json")
            .then()
            .extract()
            .body()
            .asString();
    }
}
