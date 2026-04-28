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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

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

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CardDavClient cardDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser john;
    private String secondDomainId;
    private String technicalToken;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        Document secondDomainDoc = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        secondDomainId = secondDomainDoc.getObjectId("_id").toString();
        technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        bob = dockerExtension().newTestUser();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void propfindOnAddressBooksRootShouldNotExposeCrossDomainAddressBookHome() {
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/"));

        assertThat(response.body()).doesNotContain(john.id());
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void propfindShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + john.id()));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void mkcolShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

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

        assertThat(status).isEqualTo(403);
    }

    @Test
    void deleteAddressBookShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        assertThatThrownBy(() -> cardDavClient.deleteAddressBook(bob, john.id(), "contacts"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void putContactShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        assertThatThrownBy(() -> cardDavClient.upsertContact(bob, john.id(), "contacts", "abcdef", VCARD))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void deleteContactShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, "contacts", "abcdef", VCARD);

        assertThatThrownBy(() -> cardDavClient.deleteContact(bob, john.id(), "contacts", "abcdef"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void getContactShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, "contacts", "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void proppatchShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

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

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void headContactShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, "contacts", "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("HEAD"))
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void exportShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + john.id() + "/contacts?export=true"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void reportShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

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

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void copyContactBetweenAddressBooksShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, "contacts", "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + john.id() + "/collected/abcdef.vcf"))
            .request(HttpMethod.valueOf("COPY"))
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void moveContactBetweenAddressBooksShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, "contacts", "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Destination", "/addressbooks/" + john.id() + "/collected/abcdef.vcf"))
            .request(HttpMethod.valueOf("MOVE"))
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void getJsonAddressBooksShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/json"))
            .get()
            .uri("/addressbooks/" + john.id() + ".json"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void putJsonContactShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

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

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void getJsonContactShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.upsertContact(john, "contacts", "abcdef", VCARD);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/vcard+json"))
            .get()
            .uri("/addressbooks/" + john.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void postJsonAddressBookShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .post()
            .uri("/addressbooks/" + john.id() + ".json")
            .send(body("""
                {"id":"newbook","dav:name":"New Book","dav:acl":["dav:read","dav:write"],"type":"addressbook"}
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void updateJsonAddressBookMetadataShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + john.id() + "/contacts.json")
            .send(body("""
                {"dav:name":"Hacked Name","carddav:description":"Hacked"}
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void reportJsonShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + john.id() + "/contacts.json")
            .send(body("""
                {"match":{"field":"fn","value":"Test"}}
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void subscribeShouldReturn403ForCrossDomainUser() {
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);

        assertThatThrownBy(() -> cardDavClient.subscribe(john, bob.id(), "contacts", "Bob's contacts"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void delegateShouldReturn403ForCrossDomainUser() {
        assertThatThrownBy(() -> cardDavClient.grantDelegation(bob, "contacts", john, DelegationRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Test
    void readDabShouldReturn403ForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, technicalToken);

        assertThatThrownBy(() -> cardDavClient.getContacts(bob, secondDomainId, "dab"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Test
    void readDabAsOpenPaasAdminShouldReturn403ForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, technicalToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        assertThatThrownBy(() -> cardDavClient.getContacts(bob, secondDomainId, "dab"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Test
    void addContactToPublicReadWriteDabShouldReturn403ForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, technicalToken);
        makeDomainAdmin(john, SECOND_DOMAIN);
        cardDavClient.setDomainAddressBookPublicRightReadWrite(john, secondDomainId, "dab");

        String vcardUid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> cardDavClient.upsertContact(bob, secondDomainId, "dab", vcardUid, VCARD))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Test
    void addContactToPublicReadWriteDabAsOpenPaasAdminShouldReturn403ForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, technicalToken);
        makeDomainAdmin(john, SECOND_DOMAIN);
        cardDavClient.setDomainAddressBookPublicRightReadWrite(john, secondDomainId, "dab");

        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        String vcardUid = UUID.randomUUID().toString();
        assertThatThrownBy(() -> cardDavClient.upsertContact(bob, secondDomainId, "dab", vcardUid, VCARD))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Test
    void setPublicRightOfDabAsOpenPaasAdminShouldReturn403ForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(secondDomainId, technicalToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        assertThatThrownBy(() -> cardDavClient.setPublicRight(bob, secondDomainId, "dab", PublicRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void delegateDabAsOpenPaasAdminShouldReturn403ForCrossDomainUser() {
        cardDavClient.createDomainAddressBook(dockerExtension().domainId(), technicalToken);
        makeDomainAdmin(bob, DEFAULT_DOMAIN);

        assertThatThrownBy(() -> cardDavClient.grantDomainBookDelegation(bob, dockerExtension().domainId(), "dab", john, DelegationRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
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
}
