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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;

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
    }

    @Test
    void regularUserCanReadDomainMemberAddressBookContactsWhenInDomain() {
        // GIVEN a domain addressbook with two contacts
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        String firstUid = "contact-" + UUID.randomUUID();
        String secondUid = "contact-" + UUID.randomUUID();

        byte[] firstVCard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:First Contact
            EMAIL:first.contact@%s
            END:VCARD
            """.formatted(domainId).getBytes(StandardCharsets.UTF_8);

        byte[] secondVCard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Second Contact
            EMAIL:second.contact@%s
            END:VCARD
            """.formatted(domainId).getBytes(StandardCharsets.UTF_8);

        cardDavClient.upsertDomainMemberContact(domainId, firstUid, firstVCard, technicalToken);
        cardDavClient.upsertDomainMemberContact(domainId, secondUid, secondVCard, technicalToken);

        // WHEN a user of that domain fetches domain-members addressbook
        String response = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");

        // THEN they can see both contacts
        assertThat(response)
            .contains("First Contact")
            .contains("Second Contact")
            .contains("first.contact@" + domainId)
            .contains("second.contact@" + domainId);
    }

    @Test
    void adminUserCannotAddContactIntoDomainMemberAddressBook() {
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
        byte[] vcardPayload = """
            BEGIN:VCARD
            VERSION:3.0
            FN:New Contact1
            EMAIL:contact@%s
            END:VCARD
            """.formatted(domainId).getBytes(StandardCharsets.UTF_8);

        // WHEN an administrator tries to add a contact into the domain-members addressbook
        // THEN the server rejects the request (403)
        assertThatThrownBy(() ->
            cardDavClient.upsertContact(adminUser, domainId, "domain-members", vcardUid, vcardPayload))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Unexpected status code: 403");

        String response = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");
        assertThat(response)
            .doesNotContain("New Contact1");
    }

    @Test
    void technicalTokenCanManageDomainMemberContacts() {
        // GIVEN an empty domain addressbook created by the Twake Calendar service
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);

        String vcardUid = "member-" + UUID.randomUUID();
        byte[] vcardPayload = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Technical Contact
            EMAIL:tech.contact@%s
            END:VCARD
            """.formatted(domainId).getBytes(StandardCharsets.UTF_8);

        // WHEN using the technical token to create a new domain member contact
        cardDavClient.upsertDomainMemberContact(domainId, vcardUid, vcardPayload, technicalToken);

        // THEN the contact is visible in the addressbook
        String responseAfterCreate = cardDavClient.getContacts(openPaasUser, domainId, "domain-members");
        assertThat(responseAfterCreate)
            .contains("Technical Contact")
            .contains("tech.contact@" + domainId);

        // WHEN updating the same contact using the technical token
        byte[] updatedVcardPayload = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Updated Contact
            EMAIL:tech.updated@%s
            END:VCARD
            """.formatted(domainId).getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertDomainMemberContact(domainId, vcardUid, updatedVcardPayload, technicalToken);

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
    void domainAdministratorCanAddContactToDomainAddressBook() {
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
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, domainId, "dab", vcardUid, vcardPayload);

        String response = cardDavClient.getContacts(alice, domainId, "dab");

        // Then alice can see the contact
        assertThat(response).contains("John Doe");
    }

    @Test
    void domainAdministratorCanUpdateContactInDomainAddressBook() {
        String domainId = extension().domainId();
        OpenPaasUser alice = extension().newTestUser();
        OpenPaasUser bob = extension().newTestUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertDomainContact(domainId, vcardUid, vcardPayload, technicalToken);

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
        byte[] updatedVcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Cole\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertContact(bob, domainId, "dab", vcardUid, updatedVcardPayload);

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
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);
        cardDavClient.upsertDomainContact(domainId, vcardUid, vcardPayload, technicalToken);

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

    @Test
    void normalUserCannotAddContactToDomainAddressBook() {
        String domainId = extension().domainId();
        OpenPaasUser bob = extension().newTestUser();

        cardDavClient.createDomainAddressBook(domainId, technicalToken);

        String vcardUid = "test-contact-uid";
        byte[] vcardPayload = "BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD".getBytes(StandardCharsets.UTF_8);

        Assertions.assertThatThrownBy(() -> cardDavClient.upsertContact(bob, domainId, "dab", vcardUid, vcardPayload))
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

    @Test
    void domainAdministratorCannotSetPublicRightOfDomainAddressBook() {
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

        String expected = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // Then bob cannot set public right on domain address book (set to read-write in this case)
        assertThatThrownBy(() -> cardDavClient.setDomainBookPublicRight(bob, domainId, "dab"))
            .hasMessageContaining("Unexpected status code: 501");

        String actual = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // And the public right does not change
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void domainAdministratorCannotDelegateDomainAddressBook() {
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

        String expected = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // Then bob cannot delegate domain address book (set to read-write in this case)
        assertThatThrownBy(() -> cardDavClient.grantDomainBookDelegation(bob, domainId, "dab", alice, CardDavClient.DelegationRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 405");

        String actual = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // And the delegation is not set
        assertThat(actual).isEqualTo(expected);
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

        String expected = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // Then bob cannot set public right on domain members address book (set to read-write in this case)
        assertThatThrownBy(() -> cardDavClient.setDomainBookPublicRight(bob, domainId, "domain-members"))
            .hasMessageContaining("Unexpected status code: 501");

        String actual = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        String expected = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // Then bob cannot delegate domain members address book (set to read-write in this case)
        assertThatThrownBy(() -> cardDavClient.grantDomainBookDelegation(bob, domainId, "domain-members", alice, CardDavClient.DelegationRight.READ_WRITE))
            .hasMessageContaining("Unexpected status code: 405");

        String actual = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
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

        // And the delegation is not set
        assertThat(actual).isEqualTo(expected);
    }
}
