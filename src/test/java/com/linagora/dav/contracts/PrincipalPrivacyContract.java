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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TwakeCalendarProvisioningService;
import com.linagora.dav.XMLUtil;

import io.restassured.RestAssured;

public abstract class PrincipalPrivacyContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaasUser bob;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        bob = dockerExtension().newTestUser();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/xml")
            .header("Authorization", bob.impersonatedBasicAuth());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void propfindUsersPrincipalShouldNotExposeOtherUsers() throws Exception {
        // Given two users exist in the same domain.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob lists user principals through PROPFIND Depth 1.
        String responseBody = given()
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Bob must see his own principal but not Alice's principal.
        List<String> hrefs = extractPrincipalHrefs(responseBody);
        assertThat(hrefs)
            .anySatisfy(href -> assertThat(href).contains("/principals/users/" + bob.id()))
            .noneSatisfy(href -> assertThat(href).contains("/principals/users/" + alice.id()));
    }

    @Test
    void propfindAddressBooksShouldNotExposeOtherUsers() throws Exception {
        // Given Bob and Alice both have address book roots.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob lists the address book root through PROPFIND Depth 1.
        String responseBody = given()
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/addressbooks")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Alice's address book collection must not be exposed to Bob.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/addressbooks/" + alice.id()));
    }

    @Test
    void cardDavDomainAddressBooksShouldRemainVisibleAndReadable() throws Exception {
        // Given Bob exists in the domain and domain address books are provisioned.
        String domainId = dockerExtension().domainId();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        CardDavClient cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        cardDavClient.createDomainAddressBook(domainId, technicalToken);
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);
        String domainContactUid = "dab-" + bob.id();
        String domainMemberContactUid = "domain-members-" + bob.id();
        cardDavClient.upsertDomainContact(domainId, domainContactUid, """
            BEGIN:VCARD
            VERSION:3.0
            FN:Domain Address Book Contact
            N:Address Book Contact;Domain;;;
            UID:%s
            END:VCARD
            """.formatted(domainContactUid).getBytes(StandardCharsets.UTF_8), technicalToken);
        cardDavClient.upsertDomainMemberContact(domainId, domainMemberContactUid, """
            BEGIN:VCARD
            VERSION:3.0
            FN:Domain Member Contact
            N:Member Contact;Domain;;;
            UID:%s
            END:VCARD
            """.formatted(domainMemberContactUid).getBytes(StandardCharsets.UTF_8), technicalToken);

        // When Bob lists the address book root through PROPFIND Depth 1.
        String addressBooksResponseBody = given()
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/addressbooks")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then principal privacy must not hide the domain address book collection.
        assertThat(extractPrincipalHrefs(addressBooksResponseBody))
            .contains("/addressbooks/" + domainId + "/");

        // When Bob lists the domain address book root through PROPFIND Depth 1.
        String domainAddressBooksResponseBody = given()
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/addressbooks/" + domainId)
        .then()
            .statusCode(207)
            .extract().asString();

        // Then principal privacy must not hide the domain or domain-members address books.
        assertThat(extractPrincipalHrefs(domainAddressBooksResponseBody))
            .contains("/addressbooks/" + domainId + "/",
                "/addressbooks/" + domainId + "/dab/",
                "/addressbooks/" + domainId + "/domain-members/");

        // When Bob reads contacts from both domain address books through CardDAV REPORT.
        String domainAddressBookContacts = cardDavClient.getContactsXML(bob, domainId, "dab");
        String domainMemberAddressBookContacts = cardDavClient.getContactsXML(bob, domainId, "domain-members");

        // Then both domain address books remain readable.
        assertThat(domainAddressBookContacts)
            .contains(domainContactUid)
            .contains("Domain Address Book Contact");
        assertThat(domainMemberAddressBookContacts)
            .contains(domainMemberContactUid)
            .contains("Domain Member Contact");
    }

    @Test
    void getUsersPrincipalShouldNotExposeOtherUsers() {
        // Given two users exist in the same domain.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob opens the user principals browser listing through GET.
        String responseBody = given()
        .when()
            .get("/principals/users")
        .then()
            .statusCode(200)
            .extract().asString();

        // Then the generated HTML must contain Bob's principal but not Alice's principal.
        assertThat(responseBody)
            .contains("/principals/users/" + bob.id())
            .doesNotContain("/principals/users/" + alice.id());
    }

    @Test
    void getAddressBooksShouldNotExposeOtherUsers() {
        // Given Bob and Alice both have address book roots.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob opens the address book browser listing through GET.
        String responseBody = given()
        .when()
            .get("/addressbooks")
        .then()
            .statusCode(200)
            .extract().asString();

        // Then the generated HTML must not expose Alice's address book collection.
        assertThat(responseBody)
            .doesNotContain("/addressbooks/" + alice.id());
    }

    @Test
    void reportUsersPrincipalPropertySearchShouldNotExposeOtherUsers() throws Exception {
        // Given Bob and Alice exist in the same domain.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob searches user principals through REPORT principal-property-search by Alice's email address.
        String responseBody = given()
            .header("Depth", "0")
            .body("""
                <d:principal-property-search xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:property-search>
                    <d:prop>
                      <s:email-address/>
                    </d:prop>
                    <d:match>%s</d:match>
                  </d:property-search>
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:principal-property-search>""".formatted(alice.email()))
        .when()
            .request("REPORT", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Alice's principal must not be returned to Bob.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/principals/users/" + alice.id()));
    }

    @Test
    void reportUsersPrincipalExpandPropertyShouldNotExposeOtherUsers() throws Exception {
        // Given two users exist in the same domain.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob expands properties on user principals through REPORT expand-property with Depth 1.
        String responseBody = given()
            .header("Depth", "1")
            .body("""
                <d:expand-property xmlns:d="DAV:">
                  <d:property name="displayname" namespace="DAV:"/>
                  <d:property name="email-address" namespace="http://sabredav.org/ns"/>
                </d:expand-property>""")
        .when()
            .request("REPORT", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Alice's principal must not be included in the expanded result set.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/principals/users/" + alice.id()));
    }

    @Test
    void reportUsersPrincipalMatchShouldNotExposeOtherUsers() throws Exception {
        // Given Bob and Alice exist in the same domain.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob runs REPORT principal-match on user principals.
        // This guards the REPORT path that enumerates candidates through getPropertiesForPath(..., depth=1).
        String responseBody = given()
            .header("Depth", "0")
            .body("""
                <d:principal-match xmlns:d="DAV:">
                  <d:principal-property>
                    <d:principal-URL/>
                  </d:principal-property>
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:principal-match>""")
        .when()
            .request("REPORT", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Alice's principal must not be returned as a principal-match candidate.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/principals/users/" + alice.id()));
    }

    @Test
    void reportAddressBooksExpandPropertyShouldNotExposeOtherUsers() throws Exception {
        // Given Bob and Alice both have address book roots.
        OpenPaasUser alice = dockerExtension().newTestUser();

        // When Bob expands properties on the address book root through REPORT expand-property with Depth 1.
        String responseBody = given()
            .header("Depth", "1")
            .body("""
                <d:expand-property xmlns:d="DAV:">
                  <d:property name="displayname" namespace="DAV:"/>
                </d:expand-property>""")
        .when()
            .request("REPORT", "/addressbooks")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Alice's address book collection must not be included in the expanded result set.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/addressbooks/" + alice.id()));
    }

    @Test
    void propfindResourcesPrincipalShouldNotExposeDomainResources() throws Exception {
        // Given a resource exists in the domain.
        OpenPaasUser resourceOwner = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("Room " + resourceOwner.id(), "Meeting room", resourceOwner)
            .block();

        // When any user lists resource principals through PROPFIND Depth 1.
        String responseBody = given()
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/resources")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then the domain resource principal must not be exposed.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/principals/resources/" + resource.id()));
    }

    @Test
    void getResourcesPrincipalShouldNotExposeDomainResources() {
        // Given a resource exists in the domain.
        OpenPaasUser resourceOwner = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("Room " + resourceOwner.id(), "Meeting room", resourceOwner)
            .block();

        // When any user opens the resource principals browser listing through GET.
        String responseBody = given()
        .when()
            .get("/principals/resources")
        .then()
            .statusCode(200)
            .extract().asString();

        // Then the generated HTML must not expose the domain resource principal.
        assertThat(responseBody)
            .doesNotContain("/principals/resources/" + resource.id());
    }

    @Test
    void reportResourcesPrincipalPropertySearchShouldNotExposeDomainResources() throws Exception {
        // Given a resource exists in the domain.
        OpenPaasUser resourceOwner = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("Room " + resourceOwner.id(), "Meeting room", resourceOwner)
            .block();

        // When any user searches resource principals through REPORT principal-property-search by that resource email address.
        String responseBody = given()
            .header("Depth", "0")
            .body("""
                <d:principal-property-search xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:property-search>
                    <d:prop>
                      <s:email-address/>
                    </d:prop>
                    <d:match>%s</d:match>
                  </d:property-search>
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:principal-property-search>""".formatted(resource.id() + "@" + TwakeCalendarProvisioningService.DEFAULT_DOMAIN))
        .when()
            .request("REPORT", "/principals/resources")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then the resource principal must not be returned by the search.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/principals/resources/" + resource.id()));
    }

    @Test
    void reportResourcesPrincipalExpandPropertyShouldNotExposeDomainResources() throws Exception {
        // Given a resource exists in the domain.
        OpenPaasUser resourceOwner = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("Room " + resourceOwner.id(), "Meeting room", resourceOwner)
            .block();

        // When any user expands properties on resource principals through REPORT expand-property with Depth 1.
        String responseBody = given()
            .header("Depth", "1")
            .body("""
                <d:expand-property xmlns:d="DAV:">
                  <d:property name="displayname" namespace="DAV:"/>
                  <d:property name="email-address" namespace="http://sabredav.org/ns"/>
                </d:expand-property>""")
        .when()
            .request("REPORT", "/principals/resources")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then the resource principal must not be included in the expanded result set.
        assertThat(extractPrincipalHrefs(responseBody))
            .noneSatisfy(href -> assertThat(href).contains("/principals/resources/" + resource.id()));
    }

    private List<String> extractPrincipalHrefs(String responseBody) throws Exception {
        return XMLUtil.extractMultipleValueByXPath(responseBody, "//d:multistatus/d:response/d:href", Map.of("d", "DAV:"));
    }
}
