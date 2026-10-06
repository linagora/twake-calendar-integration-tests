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
import java.util.HashMap;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

        // WHEN Alice scrolls her contacts 2 by 2
        List<JsonPath> pages = paginate(alice, Map.of("sort", "fn", "limit", 2));

        // THEN every contact is returned exactly once, in full name order (ties broken consistently)
        List<String> seen = pages.stream().flatMap(page -> uids(page).stream()).toList();
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(pages).hasSize(4);
            softly.assertThat(pages).allSatisfy(page -> {
                assertThat(uids(page)).hasSize(2);
            });
            softly.assertThat(seen).hasSize(8).doesNotHaveDuplicates();
            softly.assertThat(seen.subList(0, 2)).containsExactly("adam", "mia");
            softly.assertThat(seen.subList(2, 5)).containsExactlyInAnyOrder("same-1", "same-2", "same-3");
            softly.assertThat(seen.subList(5, 8)).containsExactly("tom", "zoe", "zyk");
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
            .queryParam("sort", "phone")
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

    /**
     * Lists all the contacts of a user page after page, passing back the next cursor as after until the last page.
     */
    private List<JsonPath> paginate(OpenPaasUser user, Map<String, ?> params) {
        List<JsonPath> pages = new ArrayList<>();
        JsonPath page = listContacts(user, params);
        pages.add(page);
        while (page.getString(NEXT) != null) {
            assertThat(pages).as("pagination should end").hasSizeLessThan(100);
            Map<String, Object> nextParams = new HashMap<>(params);
            nextParams.put("after", page.getString(NEXT));
            page = listContacts(user, nextParams);
            pages.add(page);
        }
        return pages;
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
        addContact(user, addressBook, uid, firstName, lastName, null);
    }

    private void addContact(OpenPaasUser user, String addressBook, String uid, String firstName, String lastName, String email) {
        VCardContact.Builder builder = VCardContact.builder().firstName(firstName).lastName(lastName);
        if (email != null) {
            builder.email(email);
        }
        cardDavClient.upsertContact(user, addressBook, uid, builder.build().toVCardPayload(uid));
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

    @Test
    protected void shouldDefaultToAscendingFullNameOrder() {
        // GIVEN names whose insertion order differs from their full-name order
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        addContact(alice, "collected", "anna", "Anna", "First");

        // WHEN listing with defaults and with explicit ascending order
        JsonPath defaultResponse = listContacts(alice, Map.of());
        JsonPath ascendingResponse = listContacts(alice, Map.of("sort", "fn", "order", "asc"));

        // THEN both requests return contacts from A to Z
        assertThat(uids(defaultResponse)).containsExactly("anna", "zoe");
        assertThat(uids(ascendingResponse)).containsExactly("anna", "zoe");
    }

    @Test
    protected void shouldSortOwnDelegatedAndSubscribedContactsDescending() {
        // GIVEN contacts from all three sources, including an accented and a lowercase name
        addContact(alice, "contacts", "anna", "Anna", "First");
        addContact(bob, "contacts", "elodie", "Élodie", "Martin");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        addContact(bob, "collected", "bob", "bob", "Marley");
        cardDavClient.setPublicRight(bob, bob.id(), "collected", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "collected", "Bob collected");

        // WHEN listing descending THEN all sources are sorted together
        JsonPath response = listContacts(alice, Map.of("sort", "fn", "order", "desc"));
        assertThat(uids(response)).containsExactly("elodie", "bob", "anna");
        assertThat(response.getString(NEXT)).isNull();

        // WHEN delegated and subscribed books are excluded THEN only Alice's contact remains
        JsonPath ownContactsResponse = listContacts(alice, Map.of("order", "desc", "delegate", false, "share", false));
        assertThat(uids(ownContactsResponse)).containsExactly("anna");
    }

    @ParameterizedTest
    @MethodSource("expectedContactPages")
    protected void shouldPaginateInRequestedOrderWithoutDuplicatesNorMisses(String order, List<List<String>> expectedPages) {
        // GIVEN six contacts in two address books, created in a different order from their names
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        addContact(alice, workBook, "bob", "bob", "Marley");
        addContact(alice, "contacts", "anna", "Anna", "First");
        addContact(alice, workBook, "elodie", "Élodie", "Martin");
        addContact(alice, "contacts", "dora", "Dora", "Middle");
        addContact(alice, workBook, "carl", "Carl", "Middle");

        // WHEN scrolling two contacts at a time in the requested order
        List<JsonPath> pages = paginate(alice, Map.of("sort", "fn", "order", order, "limit", 2));
        List<List<String>> actualPages = pages.stream().map(this::uids).toList();

        // THEN every page contains exactly the expected contacts in order, with no omissions or duplicates
        assertThat(actualPages).containsExactlyElementsOf(expectedPages);
        assertThat(pages.getLast().getString(NEXT)).isNull();
    }

    static List<Arguments> expectedContactPages() {
        return List.of(
            Arguments.of("asc", List.of(
                List.of("anna", "bob"),
                List.of("carl", "dora"),
                List.of("elodie", "zoe"))),
            Arguments.of("desc", List.of(
                List.of("zoe", "elodie"),
                List.of("dora", "carl"),
                List.of("bob", "anna"))));
    }

    @Test
    protected void shouldPaginateEquivalentNamesInBothOrders() {
        // GIVEN three names that compare equal when ignoring accents and case, in two address books
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "same-1", "Same", "Name");
        addContact(alice, workBook, "same-2", "Same", "Name");
        addContact(alice, "contacts", "same-3", "sáme", "name");

        // WHEN every contact is on its own page, the cursor must break name ties using _id
        List<JsonPath> ascendingPages = paginate(alice, Map.of("order", "asc", "limit", 1));
        List<JsonPath> descendingPages = paginate(alice, Map.of("order", "desc", "limit", 1));
        List<String> ascendingUids = ascendingPages.stream().flatMap(page -> uids(page).stream()).toList();
        List<String> descendingUids = descendingPages.stream().flatMap(page -> uids(page).stream()).toList();

        // THEN all three contacts appear once, and descending reverses the _id order of equal names
        assertThat(ascendingPages).hasSize(3).allSatisfy(page -> assertThat(uids(page)).hasSize(1));
        assertThat(descendingPages).hasSize(3).allSatisfy(page -> assertThat(uids(page)).hasSize(1));
        assertThat(ascendingUids).containsExactlyInAnyOrder("same-1", "same-2", "same-3");
        assertThat(descendingUids).containsExactlyElementsOf(ascendingUids.reversed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "1"})
    protected void invalidOrderShouldBeRejected(String order) {
        // WHEN order is unsupported THEN reject it instead of silently using ascending
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("order", order)
            .get(contactsPath(alice.id()))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    protected void arrayOrderShouldBeRejected() {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("order[]", "asc")
            .get(contactsPath(alice.id()))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({"asc, desc", "desc, asc"})
    protected void cursorFromOppositeOrderShouldBeRejected(String cursorOrder, String requestedOrder) {
        // GIVEN a cursor from a non-final page
        addContact(alice, "contacts", "anna", "Anna", "First");
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        String cursor = listContacts(alice, Map.of("order", cursorOrder, "limit", 1)).getString(NEXT);

        // WHEN changing order while keeping that cursor THEN require a fresh listing
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParams(Map.of("order", requestedOrder, "after", cursor))
            .get(contactsPath(alice.id()))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    protected void shouldPaginateContactsByEmailAscending() {
        // GIVEN emails whose order differs from full names, including a contact with no email
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "zoe", "Zoe", "Last", "alpha@example.org");
        addContact(alice, workBook, "bob", "Bob", "Marley", "CHARLIE@example.org");
        addContact(alice, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(alice, workBook, "no-email", "Élodie", "Martin");
        addContact(alice, "contacts", "dora", "Dora", "Middle", "delta@example.org");
        addContact(alice, workBook, "carl", "Carl", "Middle", "bravo@example.org");

        // WHEN listing contacts by email ascending, two contacts at a time
        List<JsonPath> pages = paginate(alice, Map.of("sort", "email", "order", "asc", "limit", 2));

        // THEN each page follows email order, with the missing email first
        assertThat(pages).hasSize(3);
        assertThat(uids(pages.get(0))).containsExactly("no-email", "zoe");
        assertThat(uids(pages.get(1))).containsExactly("carl", "bob");
        assertThat(uids(pages.get(2))).containsExactly("dora", "anna");
        assertThat(pages.getLast().getString(NEXT)).isNull();
    }

    @Test
    protected void shouldPaginateContactsByEmailDescending() {
        // GIVEN emails whose order differs from full names, including a contact with no email
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "zoe", "Zoe", "Last", "alpha@example.org");
        addContact(alice, workBook, "bob", "Bob", "Marley", "CHARLIE@example.org");
        addContact(alice, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(alice, workBook, "no-email", "Élodie", "Martin");
        addContact(alice, "contacts", "dora", "Dora", "Middle", "delta@example.org");
        addContact(alice, workBook, "carl", "Carl", "Middle", "bravo@example.org");

        // WHEN listing contacts by email descending, two contacts at a time
        List<JsonPath> pages = paginate(alice, Map.of("sort", "email", "order", "desc", "limit", 2));

        // THEN each page follows reversed email order, with the missing email last
        assertThat(pages).hasSize(3);
        assertThat(uids(pages.get(0))).containsExactly("anna", "dora");
        assertThat(uids(pages.get(1))).containsExactly("bob", "carl");
        assertThat(uids(pages.get(2))).containsExactly("zoe", "no-email");
        assertThat(pages.getLast().getString(NEXT)).isNull();
    }

    @Test
    protected void shouldPaginateEqualAndMissingEmailsInBothOrders() {
        // GIVEN equal emails ignoring case, and two contacts without emails, in different books
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "same-1", "Anna", "First", "same@example.org");
        addContact(alice, workBook, "same-2", "Bob", "Second", "SAME@example.org");
        addContact(alice, "contacts", "same-3", "Carl", "Third", "same@example.org");
        addContact(alice, "contacts", "empty-1", "Dora", "Fourth");
        addContact(alice, workBook, "empty-2", "Zoe", "Last");

        // WHEN each contact is on its own page THEN _id breaks ties in both directions
        List<JsonPath> ascendingPages = paginate(alice, Map.of("sort", "email", "order", "asc", "limit", 1));
        List<JsonPath> descendingPages = paginate(alice, Map.of("sort", "email", "order", "desc", "limit", 1));
        List<String> ascendingUids = ascendingPages.stream().flatMap(page -> uids(page).stream()).toList();
        List<String> descendingUids = descendingPages.stream().flatMap(page -> uids(page).stream()).toList();

        assertThat(ascendingPages).hasSize(5).allSatisfy(page -> assertThat(uids(page)).hasSize(1));
        assertThat(descendingPages).hasSize(5).allSatisfy(page -> assertThat(uids(page)).hasSize(1));
        assertThat(ascendingUids.subList(0, 2)).containsExactlyInAnyOrder("empty-1", "empty-2");
        assertThat(ascendingUids.subList(2, 5)).containsExactlyInAnyOrder("same-1", "same-2", "same-3");
        assertThat(descendingUids).containsExactlyElementsOf(ascendingUids.reversed());
    }

    @Test
    protected void shouldSortOwnDelegatedAndSubscribedContactsByEmail() {
        // GIVEN emails across all three sources whose order differs from contact names
        addContact(alice, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(bob, "contacts", "elodie", "Élodie", "Martin", "alpha@example.org");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        addContact(bob, "collected", "bob", "Bob", "Marley", "middle@example.org");
        cardDavClient.setPublicRight(bob, bob.id(), "collected", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "collected", "Bob collected");

        // WHEN sorting by email THEN sort all sources together
        JsonPath ascending = listContacts(alice, Map.of("sort", "email", "order", "asc"));
        JsonPath descending = listContacts(alice, Map.of("sort", "email", "order", "desc"));
        assertThat(uids(ascending)).containsExactly("elodie", "bob", "anna");
        assertThat(uids(descending)).containsExactly("anna", "bob", "elodie");
    }

    @Test
    protected void shouldUpdateEmailSortWhenEmailChangesOrIsRemoved() {
        // GIVEN two contacts ordered by email
        addContact(alice, "contacts", "anna", "Anna", "First", "alpha@example.org");
        addContact(alice, "contacts", "bob", "Bob", "Second", "middle@example.org");
        assertThat(uids(listContacts(alice, Map.of("sort", "email")))).containsExactly("anna", "bob");

        // WHEN Anna changes her email THEN her position is updated
        addContact(alice, "contacts", "anna", "Anna", "First", "zoe@example.org");
        assertThat(uids(listContacts(alice, Map.of("sort", "email")))).containsExactly("bob", "anna");

        // WHEN Anna removes her email THEN her empty email sorts first
        addContact(alice, "contacts", "anna", "Anna", "First");
        assertThat(uids(listContacts(alice, Map.of("sort", "email")))).containsExactly("anna", "bob");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("emailPreferences")
    protected void shouldSortUsingPreferredEmail(EmailPreferenceScenario scenario) {
        // GIVEN several emails and another contact between alpha and zoe
        String multipleEmails = """
            BEGIN:VCARD
            VERSION:{version}
            UID:multiple
            FN:Multiple Emails
            {emails}END:VCARD
            """.replace("{version}", scenario.vcardVersion()).replace("{emails}", scenario.emailProperties());
        cardDavClient.upsertContact(alice, "contacts", "multiple", multipleEmails.getBytes(StandardCharsets.UTF_8));
        addContact(alice, "contacts", "single", "Single", "Email", "middle@example.org");

        // WHEN sorting in either direction THEN use the preferred email, or the first without a preference
        assertThat(uids(listContacts(alice, Map.of("sort", "email", "order", "asc"))))
            .containsExactlyElementsOf(scenario.expectedOrder());
        assertThat(uids(listContacts(alice, Map.of("sort", "email", "order", "desc"))))
            .containsExactlyElementsOf(scenario.expectedOrder().reversed());
    }

    protected record EmailPreferenceScenario(String description, String vcardVersion, String emailProperties, List<String> expectedOrder) {
        @Override
        public String toString() {
            return description;
        }
    }

    static List<EmailPreferenceScenario> emailPreferences() {
        return List.of(
            new EmailPreferenceScenario("vCard 3 TYPE=PREF", "3.0", """
                EMAIL:zoe@example.org
                EMAIL;TYPE=INTERNET,PREF:alpha@example.org
                """,
                List.of("multiple", "single")),
            new EmailPreferenceScenario("vCard 4 lowest PREF", "4.0", """
                EMAIL:zoe@example.org
                EMAIL;PREF=50:bravo@example.org
                EMAIL;PREF=2:alpha@example.org
                """,
                List.of("multiple", "single")),
            new EmailPreferenceScenario("vCard 4 PREF=100 before unpreferred", "4.0", """
                EMAIL:zoe@example.org
                EMAIL;PREF=100:alpha@example.org
                """,
                List.of("multiple", "single")),
            new EmailPreferenceScenario("same PREF keeps first", "4.0", """
                EMAIL;PREF=2:zoe@example.org
                EMAIL;PREF=2:alpha@example.org
                """,
                List.of("single", "multiple")),
            new EmailPreferenceScenario("no PREF keeps first", "3.0", """
                EMAIL:zoe@example.org
                EMAIL:alpha@example.org
                """,
                List.of("single", "multiple")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    protected void shouldPaginateAcrossContactsWithAndWithoutEmail(int pageSize) {
        // GIVEN three contacts with emails and three without, spread over two books
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "alpha-1", "Anna", "First", "alpha@example.org");
        addContact(alice, workBook, "alpha-2", "Bob", "Second", "ALPHA@example.org");
        addContact(alice, "contacts", "bravo", "Carl", "Third", "bravo@example.org");
        addContact(alice, "contacts", "empty-1", "Dora", "Fourth");
        addContact(alice, workBook, "empty-2", "Ella", "Fifth");
        addContact(alice, workBook, "empty-3", "Fred", "Sixth");
        List<String> expectedAscending = uids(listContacts(alice, Map.of("sort", "email", "order", "asc")));
        assertThat(expectedAscending.subList(0, 3)).containsExactlyInAnyOrder("empty-1", "empty-2", "empty-3");
        assertThat(expectedAscending.subList(3, 5)).containsExactlyInAnyOrder("alpha-1", "alpha-2");
        assertThat(expectedAscending.get(5)).isEqualTo("bravo");

        // WHEN pages cross the boundary between missing and present emails in either direction
        List<JsonPath> ascendingPages = paginate(alice, Map.of("sort", "email", "order", "asc", "limit", pageSize));
        List<JsonPath> descendingPages = paginate(alice, Map.of("sort", "email", "order", "desc", "limit", pageSize));

        // THEN pagination preserves all contacts and reverses the tie order, including empty emails
        assertThat(ascendingPages.stream().flatMap(page -> uids(page).stream()).toList())
            .containsExactlyElementsOf(expectedAscending);
        assertThat(descendingPages.stream().flatMap(page -> uids(page).stream()).toList())
            .containsExactlyElementsOf(expectedAscending.reversed());
        assertThat(ascendingPages).hasSize((6 + pageSize - 1) / pageSize);
        assertThat(descendingPages).hasSize((6 + pageSize - 1) / pageSize);
        assertThat(ascendingPages.getLast().getString(NEXT)).isNull();
        assertThat(descendingPages.getLast().getString(NEXT)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc"})
    protected void shouldPaginateContactsWhenEveryEmailIsMissing(String order) {
        // GIVEN contacts without any email
        addContact(alice, "contacts", "anna", "Anna", "First");
        addContact(alice, "contacts", "bob", "Bob", "Second");
        addContact(alice, "contacts", "carl", "Carl", "Third");

        // WHEN sorting contacts by email in the requested direction
        List<JsonPath> pages = paginate(alice, Map.of("sort", "email", "order", order, "limit", 2));

        // THEN every contact is returned once, with no next cursor on the last page
        assertThat(pages).hasSize(2);
        assertThat(uids(pages.getFirst())).hasSize(2);
        assertThat(uids(pages.getLast())).hasSize(1);
        assertThat(pages.stream().flatMap(page -> uids(page).stream()).toList())
            .containsExactlyInAnyOrder("anna", "bob", "carl");
        assertThat(pages.getLast().getString(NEXT)).isNull();
    }
}
