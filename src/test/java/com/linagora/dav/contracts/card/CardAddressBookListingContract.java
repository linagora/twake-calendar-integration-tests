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
import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
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

public abstract class CardAddressBookListingContract {
    private static final String ITEM_HREFS = "_embedded.'dav:item'._links.self.href";
    private static final String NEXT = "next";
    private static final String SYNC_TOKEN = "'dav:syncToken'";

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
    void shouldSortContactsOfTheAddressBookByFullName() {
        // GIVEN Alice has contacts in her "contacts" book
        addContact(alice, "contacts", "charlie", "Charlie", "Brown");
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "elodie", "Élodie", "Martin");
        addContact(alice, "contacts", "bob", "bob", "Marley");

        // WHEN Alice lists the contacts of this address book
        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 50));

        // THEN contacts are sorted by their full name, ignoring case and accents
        assertThat(uids(response)).containsExactly("anna", "bob", "charlie", "elodie");
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void shouldOnlyListContactsOfTheRequestedAddressBook() {
        // GIVEN Alice has contacts in several address books
        String workBook = createAddressBook(alice, "Work");
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, workBook, "bob", "Bob", "Marley");
        addContact(alice, "collected", "carl", "Carl", "Collected");

        // WHEN Alice lists the contacts of her work book
        JsonPath response = listContacts(alice, alice.id(), workBook, Map.of("sort", "fn", "limit", 50));

        // THEN only the contacts of this book are listed
        assertThat(uids(response)).containsExactly("bob");
    }

    @Test
    void responseShouldLinkToItselfAndToContactsAndHaveSyncToken() {
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");

        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 50));

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getString("_links.self.href")).isEqualTo(contactsPath(alice.id(), "contacts"));
            softly.assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
                "/addressbooks/" + alice.id() + "/contacts/anna.vcf",
                "/addressbooks/" + alice.id() + "/contacts/bob.vcf");
            softly.assertThat(response.getString("_embedded.'dav:item'[0].etag")).isNotBlank();
            softly.assertThat(response.getString(SYNC_TOKEN)).isNotBlank();
        });
    }

    @Test
    void shouldPaginateWithCursorWithoutDuplicatesNorMisses() {
        // GIVEN Alice has 8 contacts, some of them sharing the same name
        addContact(alice, "contacts", "same-1", "Same", "Name");
        addContact(alice, "contacts", "same-2", "Same", "Name");
        addContact(alice, "contacts", "same-3", "Same", "Name");
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        addContact(alice, "contacts", "adam", "Adam", "First");
        addContact(alice, "contacts", "mia", "Mia", "Middle");
        addContact(alice, "contacts", "tom", "Tom", "Other");
        addContact(alice, "contacts", "zyk", "Zyk", "Middle");

        // WHEN Alice scrolls the contacts of the book 2 by 2
        List<JsonPath> pages = paginate(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 2));

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

        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 2));

        assertThat(uids(response)).containsExactly("anna", "bob");
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void shouldReturnPagesOfFiftyContactsWhenNoLimit() {
        for (int i = 0; i < 51; i++) {
            addContact(alice, "contacts", String.format("contact-%02d", i), "Contact", String.format("%02d", i));
        }

        JsonPath firstPage = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn"));
        assertThat(uids(firstPage)).hasSize(50).startsWith("contact-00").endsWith("contact-49");
        String next = firstPage.getString(NEXT);
        assertThat(next).isNotNull();

        JsonPath lastPage = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "after", next));
        assertThat(uids(lastPage)).containsExactly("contact-50");
        assertThat(lastPage.getString(NEXT)).isNull();
    }

    @Test
    void emptyAddressBookShouldHaveNoContact() {
        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn"));

        assertThat(uids(response)).isEmpty();
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void shouldListContactsOfDelegatedAddressBook() {
        // GIVEN Bob delegates his "contacts" book to Alice
        addContact(bob, "contacts", "bruno", "Bruno", "Friend");
        addContact(bob, "contacts", "anna", "Anna", "Zed");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        String delegatedBook = otherAddressBookOf(alice);

        // WHEN Alice lists the contacts of the delegated book in her home
        JsonPath response = listContacts(alice, alice.id(), delegatedBook, Map.of("sort", "fn", "limit", 50));

        // THEN she gets Bob's contacts, linked through her own address book home
        assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
            "/addressbooks/" + alice.id() + "/" + delegatedBook + "/anna.vcf",
            "/addressbooks/" + alice.id() + "/" + delegatedBook + "/bruno.vcf");
    }

    @Test
    void shouldListContactsOfSubscribedAddressBook() {
        // GIVEN Alice subscribes to Bob's public "contacts" book
        addContact(bob, "contacts", "bruno", "Bruno", "Friend");
        addContact(bob, "contacts", "anna", "Anna", "Zed");
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscription = otherAddressBookOf(alice);

        // WHEN Alice lists the contacts of the subscription
        JsonPath response = listContacts(alice, alice.id(), subscription, Map.of("sort", "fn", "limit", 50));

        // THEN she gets Bob's contacts, linked through her subscription
        assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
            "/addressbooks/" + alice.id() + "/" + subscription + "/anna.vcf",
            "/addressbooks/" + alice.id() + "/" + subscription + "/bruno.vcf");
    }

    @Test
    void domainMemberShouldListContactsOfDomainAddressBook() {
        // GIVEN a domain whose domain address book, readable by its members, has a contact
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken());
        String domainContactUid = addDomainContact(domain, "Domain", "Contact");

        // WHEN Alice, a member of this domain, lists its contacts
        JsonPath response = listContacts(domainAlice, domain.id(), "dab", Map.of("sort", "fn", "limit", 50));

        // THEN the domain contact is listed
        assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
            "/addressbooks/" + domain.id() + "/dab/" + domainContactUid + ".vcf");
    }

    @Test
    void domainMemberShouldNotListContactsOfDomainAddressBookTheyCannotRead() {
        // GIVEN a domain whose domain address book grants no right to its members
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken(), "[]", "enabled");
        addDomainContact(domain, "Domain", "Contact");

        given()
            .headers("Authorization", domainAlice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .get(contactsPath(domain.id(), "dab"))
            .then()
            .statusCode(SC_FORBIDDEN);
    }

    @Test
    void domainMemberShouldListContactsOfDomainMembersAddressBook() {
        // GIVEN a domain whose domain members address book has members
        TestDomain domain = newDomain();
        OpenPaasUser domainAlice = newDomainUser(domain.name());
        cardDavClient.createDomainMembersAddressBook(domain.id(), domain.technicalToken());
        addDomainMember(domain, "carl", "carl", "Brown");
        addDomainMember(domain, "anna", "Anna", "Zed");
        addDomainMember(domain, "elodie", "Élodie", "Martin");

        // WHEN Alice, a member of this domain, lists its contacts
        JsonPath response = listContacts(domainAlice, domain.id(), "domain-members", Map.of("sort", "fn", "limit", 50));

        // THEN the members are sorted by their full name, ignoring case and accents, and linked to the domain address book
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.getString("_links.self.href")).isEqualTo(contactsPath(domain.id(), "domain-members"));
            softly.assertThat(response.getList(ITEM_HREFS, String.class)).containsExactly(
                "/addressbooks/" + domain.id() + "/domain-members/anna.vcf",
                "/addressbooks/" + domain.id() + "/domain-members/carl.vcf",
                "/addressbooks/" + domain.id() + "/domain-members/elodie.vcf");
            softly.assertThat(response.getString(NEXT)).isNull();
        });
    }

    @Test
    void userOfAnotherDomainShouldNotListDomainMembers() {
        // GIVEN a domain whose domain members address book has a member
        TestDomain domain = newDomain();
        cardDavClient.createDomainMembersAddressBook(domain.id(), domain.technicalToken());
        addDomainMember(domain, "member", "Domain", "Member");

        // WHEN Alice, who does not belong to this domain, lists its contacts
        int statusCode = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .get(contactsPath(domain.id(), "domain-members"))
            .then()
            .extract()
            .statusCode();

        // THEN she is not allowed to
        assertThat(statusCode).isIn(SC_FORBIDDEN, SC_NOT_FOUND);
    }

    @Test
    void userOfAnotherDomainShouldNotListPublicAddressBook() {
        // GIVEN John, from another domain, has a contact in a book he made public
        TestDomain otherDomain = newDomain();
        OpenPaasUser john = newDomainUser(otherDomain.name());
        cardDavClient.setPublicRight(john, john.id(), "contacts", PublicRight.READ_WRITE);
        addContact(john, "contacts", "anna", "Anna", "Zed");

        // WHEN Alice lists the contacts of this book
        int statusCode = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .get(contactsPath(john.id(), "contacts"))
            .then()
            .extract()
            .statusCode();

        // THEN the public right does not cross the domain boundary
        assertThat(statusCode).isIn(SC_FORBIDDEN, SC_NOT_FOUND);
    }

    @Test
    void userOfAnotherDomainShouldNotListDomainAddressBook() {
        // GIVEN a domain whose domain address book, readable by its members, has a contact
        TestDomain domain = newDomain();
        cardDavClient.createDomainAddressBook(domain.id(), domain.technicalToken());
        addDomainContact(domain, "Domain", "Contact");

        // WHEN Alice, who does not belong to this domain, lists its contacts
        int statusCode = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .get(contactsPath(domain.id(), "dab"))
            .then()
            .extract()
            .statusCode();

        // THEN she is not allowed to
        assertThat(statusCode).isIn(SC_FORBIDDEN, SC_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dab", "domain-members"})
    void foreignTechnicalTokenShouldNotListDomainAddressBooks(String addressBookId) {
        // GIVEN a contact in one of the address books of domain B
        TestDomain domainB = newDomain();
        String uid;
        if (addressBookId.equals("dab")) {
            cardDavClient.createDomainAddressBook(domainB.id(), domainB.technicalToken());
            uid = addDomainContact(domainB, "Visible", "Contact");
        } else {
            cardDavClient.createDomainMembersAddressBook(domainB.id(), domainB.technicalToken());
            uid = "member-" + UUID.randomUUID();
            addDomainMember(domainB, uid, "Visible", "Contact");
        }
        TestDomain domainA = newDomain();

        // WHEN a technical token of domain A lists the contacts of this book
        int statusCode = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, domainA.technicalToken())
            .queryParam("sort", "fn")
            .get(contactsPath(domainB.id(), addressBookId))
            .then()
            .extract()
            .statusCode();

        // THEN domain B contacts are not exposed, and remain listed for a technical token of domain B
        assertThat(statusCode).isIn(SC_FORBIDDEN, SC_NOT_FOUND);
        JsonPath response = given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, domainB.technicalToken())
            .queryParam("sort", "fn")
            .get(contactsPath(domainB.id(), addressBookId))
            .then()
            .statusCode(SC_OK)
            .extract()
            .jsonPath();
        assertThat(uids(response)).containsExactly(uid);
    }

    @Test
    void shouldNotAllowListingContactsOfAddressBookOfAnotherUser() {
        addContact(bob, "contacts", "bruno", "Bruno", "Friend");

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .get(contactsPath(bob.id(), "contacts"))
            .then()
            .statusCode(SC_FORBIDDEN);
    }

    @Test
    void unknownAddressBookShouldHaveNoContactList() {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .get(contactsPath(alice.id(), "unknown"))
            .then()
            .statusCode(SC_NOT_FOUND);
    }

    @Test
    void addressBookOfUnknownUserShouldHaveNoContactList() {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .get(contactsPath(new ObjectId().toHexString(), "contacts"))
            .then()
            .statusCode(SC_NOT_FOUND);
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
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    void unsupportedSortWithCursorShouldBeRejected() {
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");
        String next = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 1)).getString(NEXT);

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "unsupported")
            .queryParam("after", next)
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"offset", "search", "modifiedBefore"})
    void cursorShouldNotBeCombinedWithOffsetNorFilters(String param) {
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");
        String next = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 1)).getString(NEXT);

        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .queryParam("limit", 1)
            .queryParam("after", next)
            .queryParam(param, "1")
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({"search, a", "modifiedBefore, 9999999999"})
    void filtersWithoutOffsetShouldNotUseCursorPagination(String param, String value) {
        // GIVEN Alice has 2 contacts matching the filter
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");

        // WHEN Alice lists them one by one by full name with the filter but without offset
        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 1, param, value));

        // THEN the listing is not paginated with a cursor
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void offsetShouldKeepOffsetPagination() {
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");
        addContact(alice, "contacts", "carl", "Carl", "Brown");

        // WHEN Alice lists her contacts with an offset
        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 2, "offset", 0));

        // THEN the page links to the next offset rather than giving a cursor
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(uids(response)).containsExactly("anna", "bob");
            softly.assertThat(response.getString("_links.next.href")).contains("offset=2");
            softly.assertThat(response.getString(NEXT)).isNull();
        });
    }

    @Test
    void cursorPaginationShouldNotHaveOffsetNextLink() {
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "bob", "Bob", "Marley");
        addContact(alice, "contacts", "carl", "Carl", "Brown");

        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "limit", 2));

        assertThat(response.getString("_links.next")).isNull();
        assertThat(response.getString(NEXT)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "", "1001"})
    void invalidLimitShouldBeRejected(String limit) {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .queryParam("limit", limit)
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    void shouldSortContactsOfTheAddressBookDescending() {
        // GIVEN Alice has contacts in her "contacts" book, including an accented and a lowercase name
        addContact(alice, "contacts", "charlie", "Charlie", "Brown");
        addContact(alice, "contacts", "anna", "Anna", "Zed");
        addContact(alice, "contacts", "elodie", "Élodie", "Martin");
        addContact(alice, "contacts", "bob", "bob", "Marley");

        // WHEN Alice lists them in descending order
        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "order", "desc"));

        // THEN contacts are sorted from Z to A, ignoring case and accents
        assertThat(uids(response)).containsExactly("elodie", "charlie", "bob", "anna");
        assertThat(response.getString(NEXT)).isNull();
    }

    @Test
    void shouldDefaultToAscendingOrder() {
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        addContact(alice, "contacts", "anna", "Anna", "First");

        JsonPath defaultResponse = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn"));
        JsonPath ascendingResponse = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "order", "asc"));

        assertThat(uids(defaultResponse)).containsExactly("anna", "zoe");
        assertThat(uids(ascendingResponse)).containsExactly("anna", "zoe");
    }

    @Test
    void shouldSortContactsOfDelegatedAddressBookDescending() {
        // GIVEN Bob delegates his "contacts" book to Alice
        addContact(bob, "contacts", "anna", "Anna", "Zed");
        addContact(bob, "contacts", "elodie", "Élodie", "Martin");
        addContact(bob, "contacts", "bob", "bob", "Marley");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        String delegatedBook = otherAddressBookOf(alice);

        JsonPath response = listContacts(alice, alice.id(), delegatedBook, Map.of("sort", "fn", "order", "desc"));

        assertThat(uids(response)).containsExactly("elodie", "bob", "anna");
    }

    @Test
    void shouldSortContactsOfSubscribedAddressBookDescending() {
        // GIVEN Alice subscribes to Bob's public "contacts" book
        addContact(bob, "contacts", "anna", "Anna", "Zed");
        addContact(bob, "contacts", "elodie", "Élodie", "Martin");
        addContact(bob, "contacts", "bob", "bob", "Marley");
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscription = otherAddressBookOf(alice);

        JsonPath response = listContacts(alice, alice.id(), subscription, Map.of("sort", "fn", "order", "desc"));

        assertThat(uids(response)).containsExactly("elodie", "bob", "anna");
    }

    @ParameterizedTest
    @MethodSource("expectedContactPages")
    void shouldPaginateInRequestedOrderWithoutDuplicatesNorMisses(String order, List<List<String>> expectedPages) {
        // GIVEN six contacts created in a different order from their names
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        addContact(alice, "contacts", "bob", "bob", "Marley");
        addContact(alice, "contacts", "anna", "Anna", "First");
        addContact(alice, "contacts", "elodie", "Élodie", "Martin");
        addContact(alice, "contacts", "dora", "Dora", "Middle");
        addContact(alice, "contacts", "carl", "Carl", "Middle");

        // WHEN scrolling two contacts at a time in the requested order
        List<JsonPath> pages = paginate(alice, alice.id(), "contacts", Map.of("sort", "fn", "order", order, "limit", 2));

        // THEN every page contains exactly the expected contacts in order, with no omissions or duplicates
        assertThat(pages.stream().map(this::uids).toList()).containsExactlyElementsOf(expectedPages);
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
    void shouldPaginateEquivalentNamesInBothOrders() {
        // GIVEN three names that compare equal when ignoring accents and case
        addContact(alice, "contacts", "same-1", "Same", "Name");
        addContact(alice, "contacts", "same-2", "Same", "Name");
        addContact(alice, "contacts", "same-3", "sáme", "name");

        // WHEN every contact is on its own page, the cursor must break name ties using _id
        List<String> ascendingUids = paginate(alice, alice.id(), "contacts", Map.of("sort", "fn", "order", "asc", "limit", 1))
            .stream().flatMap(page -> uids(page).stream()).toList();
        List<String> descendingUids = paginate(alice, alice.id(), "contacts", Map.of("sort", "fn", "order", "desc", "limit", 1))
            .stream().flatMap(page -> uids(page).stream()).toList();

        // THEN all three contacts appear once, and descending reverses the _id order of equal names
        assertThat(ascendingUids).containsExactlyInAnyOrder("same-1", "same-2", "same-3");
        assertThat(descendingUids).containsExactlyElementsOf(ascendingUids.reversed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "1"})
    void invalidOrderShouldBeRejected(String order) {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .queryParam("order", order)
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @Test
    void arrayOrderShouldBeRejected() {
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sort", "fn")
            .queryParam("order[]", "asc")
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({"asc, desc", "desc, asc"})
    void cursorFromOppositeOrderShouldBeRejected(String cursorOrder, String requestedOrder) {
        // GIVEN a cursor from a non-final page
        addContact(alice, "contacts", "anna", "Anna", "First");
        addContact(alice, "contacts", "zoe", "Zoe", "Last");
        String cursor = listContacts(alice, alice.id(), "contacts", Map.of("sort", "fn", "order", cursorOrder, "limit", 1)).getString(NEXT);

        // WHEN changing order while keeping that cursor THEN require a fresh listing
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParams(Map.of("sort", "fn", "order", requestedOrder, "after", cursor))
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc"})
    protected void shouldPaginateContactsByEmail(String order) {
        // GIVEN emails sort differently from names, including a missing email and mixed case
        addContact(alice, "contacts", "zoe", "Anna", "First", "zoe@example.org");
        addContact(alice, "contacts", "alpha", "Zoe", "Last", "alpha@example.org");
        addContact(alice, "contacts", "bravo", "Bob", "Middle", "BRAVO@example.org");
        addContact(alice, "contacts", "no-email", "Missing", "Email");

        // WHEN Alice lists the contacts two at a time by email
        List<JsonPath> pages = paginate(alice, alice.id(), "contacts", Map.of("sort", "email", "order", order, "limit", 2));

        // THEN both pages follow the requested email order, without missing or duplicate contacts
        List<String> expected = List.of("no-email", "alpha", "bravo", "zoe");
        assertThat(pages).hasSize(2);
        assertThat(pages.stream().flatMap(page -> uids(page).stream()).toList())
            .containsExactlyElementsOf(order.equals("asc") ? expected : expected.reversed());
        assertThat(pages).allSatisfy(page -> assertThat(page.getString(SYNC_TOKEN)).isNotNull());
        assertThat(pages.getLast().getString(NEXT)).isNull();
    }

    @Test
    protected void shouldSortByPreferredEmail() {
        // GIVEN the preferred email of one contact is not its first email
        String preferredUid = "preferred";
        String payload = new String(VCardContact.builder().firstName("Preferred").lastName("Email").email("aaa@example.org")
            .build().toVCardPayload(preferredUid), StandardCharsets.UTF_8)
            .replace("EMAIL;TYPE=WORK:aaa@example.org", "EMAIL:aaa@example.org\nEMAIL;TYPE=PREF:zoe@example.org");
        cardDavClient.upsertContact(alice, "contacts", preferredUid, payload.getBytes(StandardCharsets.UTF_8));
        addContact(alice, "contacts", "middle", "Middle", "Email", "middle@example.org");

        // WHEN sorting by email THEN the preferred address determines the contact position
        JsonPath response = listContacts(alice, alice.id(), "contacts", Map.of("sort", "email"));
        assertThat(uids(response)).containsExactly("middle", preferredUid);
    }

    @Test
    protected void shouldPaginateEquivalentEmailsInBothOrders() {
        // GIVEN emails that compare equal under the case-insensitive collation
        addContact(alice, "contacts", "same-1", "First", "Contact", "same@example.org");
        addContact(alice, "contacts", "same-2", "Second", "Contact", "SAME@example.org");
        addContact(alice, "contacts", "same-3", "Third", "Contact", "same@example.org");

        // WHEN each contact is on its own page in both directions
        List<String> ascending = paginate(alice, alice.id(), "contacts", Map.of("sort", "email", "order", "asc", "limit", 1))
            .stream().flatMap(page -> uids(page).stream()).toList();
        List<String> descending = paginate(alice, alice.id(), "contacts", Map.of("sort", "email", "order", "desc", "limit", 1))
            .stream().flatMap(page -> uids(page).stream()).toList();

        // THEN _id breaks ties consistently, with no duplicates or omissions
        assertThat(ascending).containsExactlyInAnyOrder("same-1", "same-2", "same-3");
        assertThat(descending).containsExactlyElementsOf(ascending.reversed());
    }

    @ParameterizedTest
    @CsvSource({"fn, email", "email, fn"})
    protected void cursorFromDifferentSortShouldBeRejected(String cursorSort, String requestedSort) {
        // GIVEN a cursor issued for one sort field
        addContact(alice, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(alice, "contacts", "zoe", "Zoe", "Last", "anna@example.org");
        String cursor = listContacts(alice, alice.id(), "contacts", Map.of("sort", cursorSort, "limit", 1)).getString(NEXT);

        // WHEN changing sort without restarting pagination THEN reject the incompatible cursor
        given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParams(Map.of("sort", requestedSort, "after", cursor))
            .get(contactsPath(alice.id(), "contacts"))
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc"})
    protected void shouldSortDelegatedAddressBookByEmail(String order) {
        // GIVEN Bob delegates a book whose name and email orders differ
        addContact(bob, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(bob, "contacts", "zoe", "Zoe", "Last", "anna@example.org");
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ);
        String delegatedBook = otherAddressBookOf(alice);

        // WHEN Alice paginates the delegated book by email
        List<JsonPath> pages = paginate(alice, alice.id(), delegatedBook, Map.of("sort", "email", "order", order, "limit", 1));

        // THEN the source emails determine the order while links stay in the delegated book
        List<String> expected = List.of("zoe", "anna");
        assertThat(pages.stream().flatMap(page -> uids(page).stream()).toList())
            .containsExactlyElementsOf(order.equals("asc") ? expected : expected.reversed());
        assertThat(pages).allSatisfy(page -> assertThat(page.getList(ITEM_HREFS, String.class))
            .allSatisfy(href -> assertThat(href).contains("/" + alice.id() + "/" + delegatedBook + "/")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc"})
    protected void shouldSortSubscribedAddressBookByEmail(String order) {
        // GIVEN Alice subscribes to a public book whose name and email orders differ
        addContact(bob, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(bob, "contacts", "zoe", "Zoe", "Last", "anna@example.org");
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscription = otherAddressBookOf(alice);

        // WHEN Alice paginates the subscription by email
        List<JsonPath> pages = paginate(alice, alice.id(), subscription, Map.of("sort", "email", "order", order, "limit", 1));

        // THEN source emails determine the order while links stay in the subscription
        List<String> expected = List.of("zoe", "anna");
        assertThat(pages.stream().flatMap(page -> uids(page).stream()).toList())
            .containsExactlyElementsOf(order.equals("asc") ? expected : expected.reversed());
        assertThat(pages).allSatisfy(page -> assertThat(page.getList(ITEM_HREFS, String.class))
            .allSatisfy(href -> assertThat(href).contains("/" + alice.id() + "/" + subscription + "/")));
    }

    @Test
    protected void shouldSortByEmailWithOffset() {
        // GIVEN contact names and emails sort in different orders
        addContact(alice, "contacts", "anna", "Anna", "First", "zoe@example.org");
        addContact(alice, "contacts", "zoe", "Zoe", "Last", "anna@example.org");
        addContact(alice, "contacts", "middle", "Middle", "Contact", "MIDDLE@example.org");

        // WHEN listing by email with offset pagination
        JsonPath first = listContacts(alice, alice.id(), "contacts", Map.of("sort", "email", "offset", 0, "limit", 2));
        JsonPath last = listContacts(alice, alice.id(), "contacts", Map.of("sort", "email", "offset", 2, "limit", 2));

        // THEN emails sort ascending and pagination continues using an offset link
        assertThat(uids(first)).containsExactly("zoe", "middle");
        assertThat(first.getString("_links.next.href")).contains("offset=2");
        assertThat(first.getString(NEXT)).isNull();
        assertThat(uids(last)).containsExactly("anna");
    }

    private JsonPath listContacts(OpenPaasUser user, String baseId, String addressBookId, Map<String, ?> params) {
        return given()
            .headers("Authorization", user.impersonatedBasicAuth())
            .queryParams(params)
            .get(contactsPath(baseId, addressBookId))
            .then()
            .statusCode(SC_OK)
            .extract()
            .jsonPath();
    }

    /**
     * Lists all the contacts of an address book page after page, passing back the next cursor as after until the
     * last page.
     */
    private List<JsonPath> paginate(OpenPaasUser user, String baseId, String addressBookId, Map<String, ?> params) {
        List<JsonPath> pages = new ArrayList<>();
        JsonPath page = listContacts(user, baseId, addressBookId, params);
        pages.add(page);
        while (page.getString(NEXT) != null) {
            assertThat(pages).as("pagination should end").hasSizeLessThan(100);
            Map<String, Object> nextParams = new HashMap<>(params);
            nextParams.put("after", page.getString(NEXT));
            page = listContacts(user, baseId, addressBookId, nextParams);
            pages.add(page);
        }
        return pages;
    }

    private static String contactsPath(String baseId, String addressBookId) {
        return "/addressbooks/" + baseId + "/" + addressBookId + ".json";
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
        return otherAddressBookOf(user);
    }

    /**
     * Id of the only address book of the home of the user other than the default ones: one they created, a
     * delegated one or a subscription.
     */
    private String otherAddressBookOf(OpenPaasUser user) {
        return cardDavClient.findUserAddressBooks(user)
            .filter(url -> url.base().equals(user.id()))
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

    private void addDomainMember(TestDomain domain, String uid, String firstName, String lastName) {
        cardDavClient.upsertDomainMemberContact(domain.id(), uid,
            VCardContact.builder().firstName(firstName).lastName(lastName).build().toVCardPayload(uid), domain.technicalToken());
    }

    private OpenPaasUser newDomainUser(String domainName) {
        return dockerExtension().twakeCalendarProvisioningService()
            .createUser("user_" + UUID.randomUUID(), domainName)
            .block();
    }
}
