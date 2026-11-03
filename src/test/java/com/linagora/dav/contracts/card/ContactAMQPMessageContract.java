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

import static com.linagora.dav.DockerTwakeCalendarExtension.QUEUE_NAME;
import static com.linagora.dav.TestUtil.awaitEvery500Millis;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.dav.AddressBookURL;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.CardDavClient.DelegationRight;
import com.linagora.dav.CardDavClient.PublicRight;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;

import io.restassured.RestAssured;
import net.javacrumbs.jsonunit.core.Option;

public abstract class ContactAMQPMessageContract {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SYNC_TOKEN = Pattern.compile("http://sabre\\.io/ns/sync/(\\d+)");
    private static final String CREATED_EXCHANGE = "sabre:contact:created";
    private static final String UPDATED_EXCHANGE = "sabre:contact:updated";
    private static final String DELETED_EXCHANGE = "sabre:contact:deleted";

    private final ConditionFactory awaitAtMost = awaitEvery500Millis().atMost(30, TimeUnit.SECONDS);
    private final Map<String, List<String>> receivedMessages = new HashMap<>();

    private CardDavClient cardDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        RestAssured.reset();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification();
    }

    @Test
    void shouldReceiveMessageFromContactCreatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "sabre:contact:created", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8));

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "path" : "addressbooks/{userId}/collected/{vcardUid}.vcf",
              "owner" : "principals/users/{userId}",
              "carddata" : "BEGIN:VCARD\\nVERSION:3.0\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\nUID:{vcardUid}\\nFN:John Doe\\nEMAIL;TYPE=Work:john.doe@example.com\\nEND:VCARD\\n"
            }
            """.replace("{userId}", testUser.id())
            .replace("{vcardUid}", vcardUid);

        assertThatJson(actual).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromContactUpdatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "sabre:contact:updated", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8));

        String updatedVcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe Updated
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, updatedVcard.getBytes(StandardCharsets.UTF_8));

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "path" : "addressbooks/{userId}/collected/{vcardUid}.vcf",
              "owner" : "principals/users/{userId}",
              "carddata" : "BEGIN:VCARD\\nVERSION:3.0\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\nUID:{vcardUid}\\nFN:John Doe Updated\\nEMAIL;TYPE=Work:john.doe@example.com\\nEND:VCARD\\n"
            }
            """.replace("{userId}", testUser.id())
            .replace("{vcardUid}", vcardUid);

        assertThatJson(actual).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromContactDeletedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "sabre:contact:deleted", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8));

        cardDavClient.deleteContact(testUser, addressBook, vcardUid);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "path" : "addressbooks/{userId}/collected/{vcardUid}.vcf",
              "owner" : "principals/users/{userId}",
              "carddata" : "BEGIN:VCARD\\nVERSION:3.0\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\nUID:{vcardUid}\\nFN:John Doe\\nEMAIL;TYPE=Work:john.doe@example.com\\nEND:VCARD\\n"
            }
            """.replace("{userId}", testUser.id())
            .replace("{vcardUid}", vcardUid);

        assertThatJson(actual).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
    }

    @Test
    void createdMessageShouldCarryTheSyncTokenFollowingTheWriteAndTheEtag() throws IOException {
        // Given a user, and a queue listening to the created contacts
        OpenPaasUser user = dockerExtension().newTestUser();
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When the user creates a contact
        cardDavClient.upsertContact(user, "collected", uid, vcard(uid, "John Doe"));

        // Then the message carries the current sync token of the address book and the stored etag of the card
        assertThat(awaitMessagesOfCard(queue, uid, 1)).singleElement()
            .satisfies(message -> assertCardMessage(message, cardMessage(
                "addressbooks/" + user.id() + "/collected/" + uid + ".vcf",
                "principals/users/" + user.id(),
                vcard(uid, "John Doe"),
                syncToken(user, user.id(), "collected"),
                etag(user, user.id(), "collected", uid))));
    }

    @Test
    void updatedMessageShouldCarryAHigherSyncTokenThanTheCreationAndTheEtag() throws IOException {
        // Given a contact, and a queue listening to the updated contacts
        OpenPaasUser user = dockerExtension().newTestUser();
        String queue = newQueueBoundTo(UPDATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(user, "collected", uid, vcard(uid, "John Doe"));
        long tokenAfterCreation = syncToken(user, user.id(), "collected");

        // When the user updates that contact
        cardDavClient.upsertContact(user, "collected", uid, vcard(uid, "John Doe Updated"));

        // Then the message carries a sync token higher than the creation one, which is the current one, and the new etag
        long tokenAfterUpdate = syncToken(user, user.id(), "collected");
        assertThat(tokenAfterUpdate).isGreaterThan(tokenAfterCreation);
        assertThat(awaitMessagesOfCard(queue, uid, 1)).singleElement()
            .satisfies(message -> assertCardMessage(message, cardMessage(
                "addressbooks/" + user.id() + "/collected/" + uid + ".vcf",
                "principals/users/" + user.id(),
                vcard(uid, "John Doe Updated"),
                tokenAfterUpdate,
                etag(user, user.id(), "collected", uid))));
    }

    @Test
    void deletedMessagesShouldCarryAHigherSyncTokenThanTheLastUpdateAndTheEtagOfTheDeletedCard() throws IOException {
        // Given an updated contact, and a queue listening to the deleted contacts
        OpenPaasUser user = dockerExtension().newTestUser();
        String queue = newQueueBoundTo(DELETED_EXCHANGE);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(user, "collected", uid, vcard(uid, "John Doe"));
        cardDavClient.upsertContact(user, "collected", uid, vcard(uid, "John Doe Updated"));
        long tokenAfterUpdate = syncToken(user, user.id(), "collected");
        String etagBeforeDeletion = etag(user, user.id(), "collected", uid);

        // When the user deletes that contact
        cardDavClient.deleteContact(user, "collected", uid);

        // Then both deleted messages carry a sync token higher than the last update one, which is the current one, and the etag of the deleted card
        long tokenAfterDeletion = syncToken(user, user.id(), "collected");
        assertThat(tokenAfterDeletion).isGreaterThan(tokenAfterUpdate);
        String path = "addressbooks/" + user.id() + "/collected/" + uid + ".vcf";
        List<String> messages = awaitMessagesOfCard(queue, uid, 2);
        assertCardMessage(messages.get(0), cardMessage(path, "principals/users/" + user.id(),
            vcard(uid, "John Doe Updated"), tokenAfterDeletion, etagBeforeDeletion));
        assertCardMessage(messages.get(1), revisionMessage(path, tokenAfterDeletion, etagBeforeDeletion));
    }

    @Test
    void messagesForASubscribedAddressBookShouldCarryTheSyncTokenOfTheSourceAndTheEtag() throws IOException {
        // Given Alice subscribes to the public address book of Bob, and a queue listening to the created and deleted contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE, DELETED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Bob creates a contact in that address book
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        long tokenAfterCreation = syncToken(bob, bob.id(), "contacts");
        String etag = etag(bob, bob.id(), "contacts", uid);

        // Then the message for Alice carries the sync token of the source address book and the card etag
        String subscribedPath = "addressbooks/" + alice.id() + "/" + subscribedBook + "/" + uid + ".vcf";
        String sourcePath = "addressbooks/" + bob.id() + "/contacts/" + uid + ".vcf";
        assertCardMessage(awaitMessagesOfCard(queue, uid, 2).get(1), cardMessage(subscribedPath, "principals/users/" + bob.id(),
            vcard(uid, "John Doe"), tokenAfterCreation, etag).put("sourcePath", sourcePath));

        // When Bob deletes that contact
        cardDavClient.deleteContact(bob, "contacts", uid);

        // Then the last of the three deleted messages, the one for Alice, carries the sync token of the source address book following the deletion, and the etag of the deleted card
        assertCardMessage(awaitMessagesOfCard(queue, uid, 5).get(4),
            revisionMessage(subscribedPath, syncToken(bob, bob.id(), "contacts"), etag).put("sourcePath", sourcePath));
    }

    @Test
    void writeThroughADelegatedAddressBookShouldCarryTheSyncTokenOfTheSourceAndTheEtag() throws IOException {
        // Given Bob delegates his address book to Alice with write access, and a queue listening to the created contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates a contact through the delegated address book
        cardDavClient.upsertContact(alice, alice.id(), delegatedBook, uid, vcard(uid, "John Doe"));

        // Then the message, whose path and owner are those of the delegated address book of Alice, carries the current sync token of the address book of Bob and the card etag
        assertThat(awaitMessagesOfCard(queue, uid, 1)).singleElement()
            .satisfies(message -> assertCardMessage(message, cardMessage(
                "addressbooks/" + alice.id() + "/" + delegatedBook + "/" + uid + ".vcf",
                "principals/users/" + alice.id(),
                vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"),
                etag(bob, bob.id(), "contacts", uid))));
    }

    @Test
    void domainAddressBookMessagesShouldCarryTheSyncTokenFollowingTheWriteAndTheEtag() throws IOException {
        // Given a domain address book, and a queue listening to the created contacts
        String domainName = "domain-" + UUID.randomUUID() + ".test";
        String domainId = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(domainName).getObjectId("_id").toString();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken(domainId);
        OpenPaasUser member = dockerExtension().twakeCalendarProvisioningService().createUser("user_" + UUID.randomUUID(), domainName).block();
        cardDavClient.createDomainAddressBook(domainId, technicalToken);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When a contact is created in that address book
        cardDavClient.upsertDomainContact(domainId, uid, vcard(uid, "John Doe"), technicalToken);

        // Then the message carries the current sync token of the address book and the stored etag of the card
        assertThat(awaitMessagesOfCard(queue, uid, 1)).singleElement()
            .satisfies(message -> assertCardMessage(message, cardMessage(
                "addressbooks/" + domainId + "/dab/" + uid + ".vcf",
                "principals/domains/" + domainId,
                vcard(uid, "John Doe"),
                syncToken(member, domainId, "dab"),
                etag(member, domainId, "dab", uid))));
    }

    @Test
    void domainMembersAddressBookMessagesShouldCarryTheSyncTokenFollowingTheWriteAndTheEtag() throws IOException {
        // Given a domain members address book, and a queue listening to the created contacts
        String domainName = "domain-" + UUID.randomUUID() + ".test";
        String domainId = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(domainName).getObjectId("_id").toString();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken(domainId);
        OpenPaasUser member = dockerExtension().twakeCalendarProvisioningService().createUser("user_" + UUID.randomUUID(), domainName).block();
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When a contact is created in that address book
        cardDavClient.upsertDomainMemberContact(domainId, uid, vcard(uid, "John Doe"), technicalToken);

        // Then the message carries the current sync token of the address book and the stored etag of the card
        assertThat(awaitMessagesOfCard(queue, uid, 1)).singleElement()
            .satisfies(message -> assertCardMessage(message, cardMessage(
                "addressbooks/" + domainId + "/domain-members/" + uid + ".vcf",
                "principals/domains/" + domainId,
                vcard(uid, "John Doe"),
                syncToken(member, domainId, "domain-members"),
                etag(member, domainId, "domain-members", uid))));
    }

    private String otherAddressBookOf(OpenPaasUser user) {
        return cardDavClient.findUserAddressBooks(user)
            .filter(url -> url.base().equals(user.id()))
            .map(AddressBookURL::addressBookId)
            .filter(id -> !id.equals("contacts") && !id.equals("collected"))
            .blockFirst();
    }

    private byte[] vcard(String uid, String fullName) {
        return """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:%s
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(uid, fullName).getBytes(StandardCharsets.UTF_8);
    }

    private ObjectNode cardMessage(String path, String owner, byte[] vcard, long syncToken, String etag) {
        return revisionMessage(path, syncToken, etag)
            .put("owner", owner)
            .put("carddata", new String(vcard, StandardCharsets.UTF_8));
    }

    private ObjectNode revisionMessage(String path, long syncToken, String etag) {
        return OBJECT_MAPPER.createObjectNode()
            .put("path", path)
            .put("syncToken", syncToken)
            .put("etag", etag);
    }

    private void assertCardMessage(String actual, ObjectNode expected) {
        assertThatJson(actual).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected.toString());
    }

    private long syncToken(OpenPaasUser user, String baseId, String addressBook) {
        String response = given()
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:sync-token/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", "/addressbooks/" + baseId + "/" + addressBook)
        .then()
            .statusCode(207)
            .extract().asString();

        Matcher matcher = SYNC_TOKEN.matcher(response);
        assertThat(matcher.find()).as("sync token in %s", response).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private String etag(OpenPaasUser user, String baseId, String addressBook, String uid) {
        return given()
            .header("Authorization", user.impersonatedBasicAuth())
        .when()
            .get("/addressbooks/" + baseId + "/" + addressBook + "/" + uid + ".vcf")
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private String newQueueBoundTo(String... exchanges) throws IOException {
        Channel channel = dockerExtension().getChannel();
        String queue = channel.queueDeclare().getQueue();
        for (String exchange : exchanges) {
            channel.queueBind(queue, exchange, "");
        }
        return queue;
    }

    /**
     * All the messages about that card received so far, including the ones read by a previous call: tests of the same
     * JVM share the exchanges, hence the filtering.
     */
    private List<String> awaitMessagesOfCard(String queue, String uid, int expectedCount) {
        List<String> messages = receivedMessages.computeIfAbsent(queue, ignored -> new ArrayList<>());
        awaitAtMost.untilAsserted(() -> {
            GetResponse delivery;
            while ((delivery = dockerExtension().getChannel().basicGet(queue, true)) != null) {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                if (body.contains(uid + ".vcf")) {
                    messages.add(body);
                }
            }
            assertThat(messages).hasSize(expectedCount);
        });
        return messages;
    }

    private byte[] getMessageFromQueue() {
        return awaitAtMost.atMost(Duration.ofSeconds(20))
            .until(() -> dockerExtension().getChannel().basicGet(QUEUE_NAME, true), Objects::nonNull)
            .getBody();
    }
}
