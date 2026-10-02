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
import static org.apache.http.HttpStatus.SC_NOT_FOUND;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.SoftAssertions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.dav.AddressBookURL;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.CardDavClient.DelegationRight;
import com.linagora.dav.CardDavClient.PublicRight;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.VCardContact;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

public abstract class CardAggregatedListingContract {
    private static final String ITEM_HREFS = "_embedded.'dav:item'._links.self.href";
    private static final String NEXT = "next";

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
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void shouldSortContactsOfAllOwnAddressBooksByFullName() {
        // GIVEN Alice has contacts spread over her "contacts" book and a book she created
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "charlie", "Charlie", "Brown");
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "elodie", "Élodie", "Martin");
        addContact(alice, workBook, "bob", "bob", "Marley");
        addContact(alice, workBook, "alice", "Alice", "Wonder");

        // WHEN Alice lists her contacts across address books
        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50));

        // THEN contacts are sorted by their full name, ignoring case and accents, whatever their address book
        assertThat(uids(response)).containsExactly("alice", "anna", "bob", "charlie", "elodie");
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void itemsShouldLinkToCorrectContact() {
        // GIVEN Alice has contacts in her own address books
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, workBook, "bob", "Bob", "Marley");
        // AND Bob delegates his "contacts" book to Alice
        addContact(bob, "contacts", "carl", "Carl", "Delegated");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        // AND Alice subscribes to Bob's public "collected" book
        addContact(bob, "collected", "dora", "Dora", "Subscribed");
        cardDavClient.setPublicRight(bob, bob.id(), "collected", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "collected", "Bob collected");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50));

        // THEN each item's self href in the response link to the corresponding contact
        List<String> hrefs = response.getList(ITEM_HREFS, String.class);
        assertThat(hrefs).hasSize(4);
        assertThat(hrefs.get(0)).isEqualTo("/addressbooks/" + alice.id() + "/contacts/anna.vcf");
        assertThat(hrefs.get(1)).isEqualTo("/addressbooks/" + alice.id() + "/" + workBook + "/bob.vcf");
        assertThat(hrefs.get(2)).startsWith("/addressbooks/" + alice.id() + "/").endsWith("/carl.vcf")
            .doesNotContain("/contacts/");
        assertThat(hrefs.get(3)).startsWith("/addressbooks/" + alice.id() + "/").endsWith("/dora.vcf")
            .doesNotContain("/collected/");

        // AND each link can be read by Alice and serves the listed contact
        Map<String, String> fullNames = Map.of(
            hrefs.get(0), "Anna Zed",
            hrefs.get(1), "Bob Marley",
            hrefs.get(2), "Carl Delegated",
            hrefs.get(3), "Dora Subscribed");
        SoftAssertions.assertSoftly(softly -> fullNames.forEach((href, fullName) -> {
            String vcard = given()
                .headers("Authorization", alice.impersonatedBasicAuth())
                .accept("text/vcard")
                .get(href)
                .then()
                .statusCode(SC_OK)
                .extract()
                .asString();
            softly.assertThat(vcard).as(href).contains("FN:" + fullName);
        }));
    }

    @Test
    void shouldPaginateWithCursorWithoutDuplicatesNorMisses() {
        // GIVEN Alice has 8 contacts over 2 address books, some of them sharing the same name
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "same-1", "Same", "Name");
        addContact(alice, workBook, "same-2", "Same", "Name");
        addContact(alice, "contacts", "same-3", "Same", "Name");
        addContact(alice, workBook, "zoe", "Zoe", "Last");
        addContact(alice, "contacts", "adam", "Adam", "First");
        addContact(alice, workBook, "mia", "Mia", "Middle");
        addContact(alice, "contacts", "tom", "Tom", "Other");
        addContact(alice, workBook, "zyk", "Zyk", "Middle");

        // WHEN Alice scrolls her contacts 2 by 2, passing back the next cursor as after
        List<String> seen = new ArrayList<>();
        int pages = 0;
        JsonPath page = listContacts(alice, Map.of("sort", "fn", "limit", 2));
        while (true) {
            pages++;
            List<String> uids = uids(page);
            assertThat(uids).hasSize(2);
            assertThat(page.getMap("_links")).containsOnlyKeys("self");
            seen.addAll(uids);
            String next = page.getString(NEXT);
            if (next == null) {
                break;
            }
            assertThat(pages).isLessThanOrEqualTo(8);
            page = listContacts(alice, Map.of("sort", "fn", "limit", 2, "after", next));
        }

        // THEN every contact is returned exactly once, in full name order (ties broken consistently)
        int finalPages = pages;
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(seen).hasSize(8).doesNotHaveDuplicates();
            softly.assertThat(seen.subList(0, 2)).containsExactly("adam", "mia");
            softly.assertThat(seen.subList(2, 5)).containsExactlyInAnyOrder("same-1", "same-2", "same-3");
            softly.assertThat(seen.subList(5, 8)).containsExactly("tom", "zoe", "zyk");
            softly.assertThat(finalPages).isEqualTo(4);
        });
    }

    @Test
    void lastFullPageShouldNotHaveNextLink() {
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 2));

        assertThat(uids(response)).containsExactly("anna", "bob");
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void shouldReturnPagesOfFiftyContactsWhenNoLimit() {
        for (int i = 0; i < 51; i++) {
            addContact(alice, i % 2 == 0 ? "contacts" : "collected", String.format("contact-%02d", i), "Contact", String.format("%02d", i));
        }

        JsonPath firstPage = listContacts(alice, Map.of("sort", "fn"));
        assertThat(uids(firstPage)).hasSize(50).startsWith("contact-00").endsWith("contact-49");
        String next = firstPage.getString(NEXT);
        assertThat(next).isNotNull();

        JsonPath lastPage = listContacts(alice, Map.of("sort", "fn", "after", next));
        assertThat(uids(lastPage)).containsExactly("contact-50");
        assertThat(lastPage.getString(NEXT)).isNull();
    }

    @Test
    void shouldIncludeDelegatedAddressBooks() {
        // GIVEN Bob delegates his "contacts" book to Alice
        addContact(bob, "contacts", "bob-friend", "Bruno", "Friend");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        addContact(alice, "contacts", "anna", "Anna", "Zed");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50));

        // THEN Alice sees Bob's contacts merged with hers, linked through her own address book home
        assertThat(uids(response)).containsExactly("anna", "bob-friend");
        String delegatedHref = response.getList(ITEM_HREFS, String.class).get(1);
        assertThat(delegatedHref).startsWith("/addressbooks/" + alice.id() + "/").endsWith("/bob-friend.vcf");
    }

    @Test
    void shouldExcludeDelegatedAddressBooksWhenDelegateIsFalse() {
        addContact(bob, "contacts", "bob-friend", "Bruno", "Friend");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        addContact(alice, "contacts", "anna", "Anna", "Zed");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50, "delegate", false));

        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void shouldIncludeSubscribedAddressBooks() {
        // GIVEN Alice subscribes to Bob's public "contacts" book
        addContact(bob, "contacts", "bob-friend", "Bruno", "Friend");
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        addContact(alice, "contacts", "anna", "Anna", "Zed");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50));

        assertThat(uids(response)).containsExactly("anna", "bob-friend");
    }

    @Test
    void shouldExcludeSubscribedAddressBooksWhenShareIsFalse() {
        addContact(bob, "contacts", "bob-friend", "Bruno", "Friend");
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        addContact(alice, "contacts", "anna", "Anna", "Zed");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50, "share", false));

        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void shouldNotListContactsOfOtherUsers() {
        addContact(bob, "contacts", "bob-friend", "Bruno", "Friend");
        addContact(alice, "contacts", "anna", "Anna", "Zed");

        JsonPath response = listContacts(alice, Map.of("sort", "fn", "limit", 50));

        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void shouldNotAllowListingContactsOfAnotherUser() {
        addContact(bob, "contacts", "bob-friend", "Bruno", "Friend");

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .get(contactsPath(bob.id()))
            .then()
            .statusCode(403);
    }

    @Test
    void domainMembersAddressBookShouldBeExcludedByDefaultAndIncludedWhenDomainMemberIsTrue() {
        // GIVEN a dedicated domain whose domain-members address book has a contact
        String domainName = "domain-" + UUID.randomUUID() + ".test";
        Document domain = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(domainName);
        String domainId = domain.getObjectId("_id").toString();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken(domainId);
        OpenPaasUser domainAlice = newDomainUser(domainName);

        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);
        String memberUid = "member-" + UUID.randomUUID();
        cardDavClient.upsertDomainMemberContact(domainId, memberUid,
            VCardContact.builder().firstName("Domain").lastName("Member").build().toVCardPayload(memberUid), technicalToken);
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN listing without domainMember THEN the domain members address book is not aggregated
        assertThat(uids(listContacts(domainAlice, Map.of("sort", "fn", "limit", 50))))
            .containsExactly("anna");

        // WHEN listing with domainMember=true THEN it is, linked to the domain address book
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainMember", true));
        assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
            "/addressbooks/" + domainAlice.id() + "/contacts/anna.vcf",
            "/addressbooks/" + domainId + "/domain-members/" + memberUid + ".vcf");
    }

    @Test
    void domainMemberShouldNotIncludeDomainMembersAddressBookOfAnotherDomain() {
        // GIVEN another domain has a domain members address book with a contact
        TestDomain otherDomain = newDomain();
        cardDavClient.createDomainMembersAddressBook(otherDomain.id(), otherDomain.technicalToken());
        String memberUid = "member-" + UUID.randomUUID();
        cardDavClient.upsertDomainMemberContact(otherDomain.id(), memberUid,
            VCardContact.builder().firstName("Other").lastName("Member").build().toVCardPayload(memberUid), otherDomain.technicalToken());
        // AND Alice belongs to a domain without domain members address book
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        // AND Alice has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts with domainMember=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainMember", true));

        // THEN the domain members address book of the other domain is not aggregated
        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void domainAddressBookShouldBeExcludedByDefaultAndIncludedWhenDomainContactsIsTrue() {
        // GIVEN a dedicated domain whose domain address book, readable by its members, has a contact
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken());
        String domainContactUid = addDomainContact(domain, "Domain", "Contact");
        // AND Alice, a member of this domain, has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts without domainContacts
        JsonPath responseWithoutDomainContacts = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50));

        // THEN the domain address book is not aggregated
        assertThat(uids(responseWithoutDomainContacts)).containsExactly("anna");

        // WHEN Alice lists her contacts with domainContacts=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainContacts", true));

        // THEN the domain contact is aggregated, sorted by full name and linked to the domain address book
        assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
            "/addressbooks/" + domainAlice.id() + "/contacts/anna.vcf",
            "/addressbooks/" + domain.id() + "/dab/" + domainContactUid + ".vcf");
    }

    @Test
    void domainContactsAndDomainMemberCanBeCombined() {
        // GIVEN a dedicated domain whose domain address book has a contact
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken());
        String domainContactUid = addDomainContact(domain, "Domain", "Contact");
        // AND whose domain members address book has a contact
        cardDavClient.createDomainMembersAddressBook(domain.id(), domain.technicalToken());
        String memberUid = "member-" + UUID.randomUUID();
        cardDavClient.upsertDomainMemberContact(domain.id(), memberUid,
            VCardContact.builder().firstName("Domain").lastName("Member").build().toVCardPayload(memberUid), domain.technicalToken());
        // AND Alice, a member of this domain, has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts with both domainContacts=true and domainMember=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainContacts", true, "domainMember", true));

        // THEN her own contact, the domain contact and the domain member are merged, sorted by full name
        assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
            "/addressbooks/" + domainAlice.id() + "/contacts/anna.vcf",
            "/addressbooks/" + domain.id() + "/dab/" + domainContactUid + ".vcf",
            "/addressbooks/" + domain.id() + "/domain-members/" + memberUid + ".vcf");
    }

    @Test
    void disabledDomainAddressBookShouldNotBeIncluded() {
        // GIVEN a dedicated domain whose domain address book is disabled and has a contact
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken(), "[ \"{DAV:}read\" ]", "disabled");
        addDomainContact(domain, "Domain", "Contact");
        // AND Alice, a member of this domain, has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts with domainContacts=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainContacts", true));

        // THEN the disabled domain address book is not aggregated
        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void domainAddressBookShouldNotBeIncludedWhenMembersCannotReadIt() {
        // GIVEN a dedicated domain whose domain address book grants no right to its members and has a contact
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken(), "[]", "enabled");
        addDomainContact(domain, "Domain", "Contact");
        // AND Alice, a member of this domain, has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts with domainContacts=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainContacts", true));

        // THEN the domain address book she cannot read is not aggregated
        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void domainContactsShouldListOnlyOwnContactsWhenDomainHasNoDomainAddressBook() {
        // GIVEN a dedicated domain without domain address book
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        // AND Alice, a member of this domain, has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts with domainContacts=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainContacts", true));

        // THEN only her own contact is listed
        assertThat(uids(response)).containsExactly("anna");
    }

    @Test
    void domainContactsShouldNotIncludeDomainAddressBookOfAnotherDomain() {
        // GIVEN another domain has a domain address book with a contact
        TestDomain otherDomain = newDomain();
        cardDavClient.createDomainAddressBook(otherDomain.id(), otherDomain.technicalToken());
        addDomainContact(otherDomain, "Other", "Domain");
        // AND Alice belongs to a domain without domain address book
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        // AND Alice has a contact of her own
        addContact(domainAlice, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists her contacts with domainContacts=true
        JsonPath response = listContacts(domainAlice, Map.of("sort", "fn", "limit", 50, "domainContacts", true));

        // THEN the domain address book of the other domain is not aggregated
        assertThat(uids(response)).containsExactly("anna");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-base64!",
        // {}
        "e30",
        // {"fn_sort":"A","_id":"not-an-id"}
        "eyJmbl9zb3J0IjoiQSIsIl9pZCI6Im5vdC1hbi1pZCJ9",
        // {"sort":"fn","v":"A","id":"6abcced236be748e3d36302b"}
        "eyJzb3J0IjoiZm4iLCJ2IjoiQSIsImlkIjoiNmFiY2NlZDIzNmJlNzQ4ZTNkMzYzMDJiIn0"})
    void invalidCursorShouldBeRejected(String after) {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .queryParam("limit", 2)
            .queryParam("after", after)
            .get(contactsPath(alice.id()))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    void unsupportedSortShouldBeRejected() {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "email")
            .get(contactsPath(alice.id()))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "", "1001"})
    void invalidLimitShouldBeRejected(String limit) {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .queryParam("limit", limit)
            .get(contactsPath(alice.id()))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    void unknownUserShouldHaveNoContactList() {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .get(contactsPath(new ObjectId().toHexString()))
            .then()
            .statusCode(SC_NOT_FOUND);
    }

    private JsonPath listContacts(OpenPaasUser user, Map<String, ?> params) {
        return given()
            .headers("Authorization", user.impersonatedBasicAuth())
            .queryParams(params)
            .get(contactsPath(user.id()))
            .then()
            .statusCode(SC_OK)
            .extract()
            .jsonPath();
    }

    private static String contactsPath(String userId) {
        return "/contacts/" + userId + ".json";
    }

    private List<String> uids(JsonPath response) {
        return response.getList(ITEM_HREFS, String.class).stream()
            .map(href -> StringUtils.removeEnd(StringUtils.substringAfterLast(href, "/"), ".vcf"))
            .toList();
    }

    private void addContact(OpenPaasUser user, String addressBook, String uid, String firstName, String lastName) {
        VCardContact contact = VCardContact.builder()
            .firstName(firstName)
            .lastName(lastName)
            .build();
        cardDavClient.upsertContact(user, addressBook, uid, contact.toVCardPayload(uid));
    }

    private String createAddressBook(OpenPaasUser user, String name) {
        cardDavClient.createAddressBook(user, name);
        return cardDavClient.findUserAddressBooks(user)
            .map(AddressBookURL::addressBookId)
            .filter(id -> !id.equals("contacts") && !id.equals("collected"))
            .blockFirst();
    }

    private record TestDomain(String name, String id, String technicalToken) {
    }

    private TestDomain newDomain() {
        String domainName = "domain-" + UUID.randomUUID() + ".test";
        Document domain = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(domainName);
        String domainId = domain.getObjectId("_id").toString();
        return new TestDomain(domainName, domainId, dockerExtension().twakeCalendarProvisioningService().generateToken(domainId));
    }

    private String addDomainContact(TestDomain domain, String firstName, String lastName) {
        String uid = "dab-" + UUID.randomUUID();
        cardDavClient.upsertDomainContact(domain.id(), uid,
            VCardContact.builder().firstName(firstName).lastName(lastName).build().toVCardPayload(uid), domain.technicalToken());
        return uid;
    }

    private OpenPaasUser newDomainUser(String domainName) {
        return dockerExtension().twakeCalendarProvisioningService()
            .createUser("user_" + UUID.randomUUID(), domainName)
            .block();
    }
}
