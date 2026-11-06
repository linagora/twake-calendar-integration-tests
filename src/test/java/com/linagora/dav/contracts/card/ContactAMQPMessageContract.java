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
import static com.linagora.dav.TestUtil.TWAKE_CALENDAR_TOKEN_HEADER;
import static com.linagora.dav.TestUtil.awaitEvery500Millis;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/users/" + user.id(), cardMessage(
            "addressbooks/" + user.id() + "/collected/" + uid + ".vcf",
            "principals/users/" + user.id(),
            vcard(uid, "John Doe"),
            syncToken(user, user.id(), "collected"),
            etag(user, user.id(), "collected", uid)));
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
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/users/" + user.id(), cardMessage(
            "addressbooks/" + user.id() + "/collected/" + uid + ".vcf",
            "principals/users/" + user.id(),
            vcard(uid, "John Doe Updated"),
            tokenAfterUpdate,
            etag(user, user.id(), "collected", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void deletedMessageShouldCarryAHigherSyncTokenThanTheLastUpdateAndTheEtagOfTheDeletedCard() throws IOException {
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

        // Then a single message is published, with the card data of the deleted card, its etag, and a sync token higher than the last update one, which is the current one
        long tokenAfterDeletion = syncToken(user, user.id(), "collected");
        assertThat(tokenAfterDeletion).isGreaterThan(tokenAfterUpdate);
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/users/" + user.id(),
            cardMessage("addressbooks/" + user.id() + "/collected/" + uid + ".vcf", "principals/users/" + user.id(),
                vcard(uid, "John Doe Updated"), tokenAfterDeletion, etagBeforeDeletion));
    }

    @Test
    @Disabled("wait to new image")
    void creatingAContactOfASubscribedAddressBookShouldPublishMessagesForTheSourceAndTheSubscriber() throws IOException {
        // Given Alice subscribes to the public address book of Bob, and a queue listening to the created contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Bob creates a contact in that address book
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner, both with the sync token of the source address book and the card etag
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + bob.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void updatingAContactOfASubscribedAddressBookShouldPublishMessagesForTheSourceAndTheSubscriber() throws IOException {
        // Given Alice subscribes to the public address book of Bob, which holds a contact, and a queue listening to the updated contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        long tokenAfterCreation = syncToken(bob, bob.id(), "contacts");
        String etagAfterCreation = etag(bob, bob.id(), "contacts", uid);
        String queue = newQueueBoundTo(UPDATED_EXCHANGE);

        // When Bob updates that contact
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe Updated"));

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner, both with the new card data, the new etag and a higher sync token
        long tokenAfterUpdate = syncToken(bob, bob.id(), "contacts");
        String etagAfterUpdate = etag(bob, bob.id(), "contacts", uid);
        assertThat(tokenAfterUpdate).isGreaterThan(tokenAfterCreation);
        assertThat(etagAfterUpdate).isNotEqualTo(etagAfterCreation);
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + bob.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe Updated"), tokenAfterUpdate, etagAfterUpdate));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactOfASubscribedAddressBookShouldPublishAFullMessageForTheSourceAndTheSubscriber() throws IOException {
        // Given Alice subscribes to the public address book of Bob, which holds a contact, and a queue listening to the deleted contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        long tokenBeforeDeletion = syncToken(bob, bob.id(), "contacts");
        String etag = etag(bob, bob.id(), "contacts", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When Bob deletes that contact
        cardDavClient.deleteContact(bob, "contacts", uid);

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner, both with the card data and a higher sync token
        long tokenAfterDeletion = syncToken(bob, bob.id(), "contacts");
        assertThat(tokenAfterDeletion).isGreaterThan(tokenBeforeDeletion);
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + bob.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe"), tokenAfterDeletion, etag));
    }

    @Test
    @Disabled("wait to new image")
    void writingThroughAWritableSubscriptionShouldPublishMessagesForTheOtherSubscribers() throws IOException {
        // Given Alice and Carol subscribe to the address book Bob published with write access, and a queue listening to the contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser carol = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        cardDavClient.subscribe(carol, bob.id(), "contacts", "Bob contacts");
        String aliceBook = otherAddressBookOf(alice);
        String carolBook = otherAddressBookOf(carol);
        String queue = newQueueBoundTo(CREATED_EXCHANGE, UPDATED_EXCHANGE, DELETED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates, updates, then deletes a contact through her subscription
        cardDavClient.upsertContact(alice, alice.id(), aliceBook, uid, vcard(uid, "John Doe"));
        long tokenAfterCreation = syncToken(bob, bob.id(), "contacts");
        String etagAfterCreation = etag(bob, bob.id(), "contacts", uid);
        cardDavClient.upsertContact(alice, alice.id(), aliceBook, uid, vcard(uid, "John Doe Updated"));
        long tokenAfterUpdate = syncToken(bob, bob.id(), "contacts");
        String etagAfterUpdate = etag(bob, bob.id(), "contacts", uid);
        cardDavClient.deleteContact(alice, alice.id(), aliceBook, uid);

        // Then Carol gets a message on her path for each of the three writes, with Carol as owner and Alice as connected user
        assertExactMessages(awaitMessagesOnPath(queue, "addressbooks/" + carol.id() + "/" + carolBook + "/" + uid + ".vcf", 3), "principals/users/" + alice.id(),
            mirrorMessage(bob, carol, carolBook, uid, vcard(uid, "John Doe"), tokenAfterCreation, etagAfterCreation),
            mirrorMessage(bob, carol, carolBook, uid, vcard(uid, "John Doe Updated"), tokenAfterUpdate, etagAfterUpdate),
            mirrorMessage(bob, carol, carolBook, uid, vcard(uid, "John Doe Updated"), syncToken(bob, bob.id(), "contacts"), etagAfterUpdate));
    }

    @Test
    @Disabled("wait to new image")
    void creatingAContactThroughAWritableSubscriptionShouldPublishMessagesForTheSourceAndTheSubscription() throws IOException {
        // Given Alice subscribes to the address book Bob published with write access, and a queue listening to the created contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates a contact through her subscription
        cardDavClient.upsertContact(alice, alice.id(), subscribedBook, uid, vcard(uid, "John Doe"));

        // Then one message has the source path with Bob as owner, and one the path of the subscription with Alice as owner
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void updatingAContactThroughAWritableSubscriptionShouldPublishMessagesForTheSourceAndTheSubscription() throws IOException {
        // Given Alice subscribes to the address book Bob published with write access, which holds a contact, and a queue listening to the updated contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String queue = newQueueBoundTo(UPDATED_EXCHANGE);

        // When Alice updates that contact through her subscription
        cardDavClient.upsertContact(alice, alice.id(), subscribedBook, uid, vcard(uid, "John Doe Updated"));

        // Then one message has the source path with Bob as owner, and one the path of the subscription with Alice as owner
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe Updated"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactThroughAWritableSubscriptionShouldPublishMessagesForTheSourceAndTheSubscription() throws IOException {
        // Given Alice subscribes to the address book Bob published with write access, which holds a contact, and a queue listening to the deleted contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String etag = etag(bob, bob.id(), "contacts", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When Alice deletes that contact through her subscription
        cardDavClient.deleteContact(alice, alice.id(), subscribedBook, uid);

        // Then one message has the source path with Bob as owner, and one the path of the subscription with Alice as owner
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag));
    }

    @Test
    @Disabled("wait to new image")
    void creatingAContactThroughADelegatedAddressBookShouldPublishMessagesForTheSourceAndTheDelegatedAddressBook() throws IOException {
        // Given Bob delegates his address book to Alice with write access, and a queue listening to the created contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates a contact through her delegated address book
        cardDavClient.upsertContact(alice, alice.id(), delegatedBook, uid, vcard(uid, "John Doe"));

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, delegatedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void updatingAContactThroughADelegatedAddressBookShouldPublishMessagesForTheSourceAndTheDelegatedAddressBook() throws IOException {
        // Given Bob delegates his address book, which holds a contact, to Alice with write access, and a queue listening to the updated contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String queue = newQueueBoundTo(UPDATED_EXCHANGE);

        // When Alice updates that contact through her delegated address book
        cardDavClient.upsertContact(alice, alice.id(), delegatedBook, uid, vcard(uid, "John Doe Updated"));

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, delegatedBook, uid, vcard(uid, "John Doe Updated"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void writingThroughADelegatedAddressBookShouldPublishMessagesForTheOtherDelegates() throws IOException {
        // Given Bob delegates his address book to Alice and Carol with write access, and a queue listening to the contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaasUser carol = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        cardDavClient.grantDelegation(bob, "contacts", carol, DelegationRight.READ_WRITE);
        String aliceBook = otherAddressBookOf(alice);
        String carolBook = otherAddressBookOf(carol);
        String queue = newQueueBoundTo(CREATED_EXCHANGE, UPDATED_EXCHANGE, DELETED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates, updates, then deletes a contact through her delegated address book
        cardDavClient.upsertContact(alice, alice.id(), aliceBook, uid, vcard(uid, "John Doe"));
        long tokenAfterCreation = syncToken(bob, bob.id(), "contacts");
        String etagAfterCreation = etag(bob, bob.id(), "contacts", uid);
        cardDavClient.upsertContact(alice, alice.id(), aliceBook, uid, vcard(uid, "John Doe Updated"));
        long tokenAfterUpdate = syncToken(bob, bob.id(), "contacts");
        String etagAfterUpdate = etag(bob, bob.id(), "contacts", uid);
        cardDavClient.deleteContact(alice, alice.id(), aliceBook, uid);

        // Then Carol gets a message on her path for each of the three writes, with Carol as owner and Alice as connected user
        assertExactMessages(awaitMessagesOnPath(queue, "addressbooks/" + carol.id() + "/" + carolBook + "/" + uid + ".vcf", 3), "principals/users/" + alice.id(),
            mirrorMessage(bob, carol, carolBook, uid, vcard(uid, "John Doe"), tokenAfterCreation, etagAfterCreation),
            mirrorMessage(bob, carol, carolBook, uid, vcard(uid, "John Doe Updated"), tokenAfterUpdate, etagAfterUpdate),
            mirrorMessage(bob, carol, carolBook, uid, vcard(uid, "John Doe Updated"), syncToken(bob, bob.id(), "contacts"), etagAfterUpdate));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactThroughADelegatedAddressBookShouldPublishMessagesForTheSourceAndTheDelegatedAddressBook() throws IOException {
        // Given Bob delegates his address book, which holds a contact, to Alice with write access, and a queue listening to the deleted contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String etag = etag(bob, bob.id(), "contacts", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When Alice deletes that contact through her delegated address book
        cardDavClient.deleteContact(alice, alice.id(), delegatedBook, uid);

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, delegatedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag));
    }

    @Test
    @Disabled("wait to new image")
    void creatingAContactOfAWritableSubscribedAddressBookThroughTheSourcePathShouldPublishMessagesForTheSourceAndTheSubscription() throws IOException {
        // Given Alice subscribes to the address book Bob published with write access, and a queue listening to the created contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates a contact through the path of Bob
        cardDavClient.upsertContact(alice, bob.id(), "contacts", uid, vcard(uid, "John Doe"));

        // Then one message has the source path with Bob as owner, and one the path of the subscription with Alice as owner, and Alice is the connected user of both
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void updatingAContactOfAWritableSubscribedAddressBookThroughTheSourcePathShouldPublishMessagesForTheSourceAndTheSubscription() throws IOException {
        // Given Alice subscribes to the address book Bob published with write access, which holds a contact, and a queue listening to the updated contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String queue = newQueueBoundTo(UPDATED_EXCHANGE);

        // When Alice updates that contact through the path of Bob
        cardDavClient.upsertContact(alice, bob.id(), "contacts", uid, vcard(uid, "John Doe Updated"));

        // Then one message has the source path with Bob as owner, and one the path of the subscription with Alice as owner, and Alice is the connected user of both
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe Updated"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactOfAWritableSubscribedAddressBookThroughTheSourcePathShouldPublishFullMessagesForTheSourceAndTheSubscription() throws IOException {
        // Given Alice subscribes to the address book Bob published with write access, which holds a contact, and a queue listening to the deleted contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.setPublicRight(bob, bob.id(), "contacts", PublicRight.READ_WRITE);
        cardDavClient.subscribe(alice, bob.id(), "contacts", "Bob contacts");
        String subscribedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String etag = etag(bob, bob.id(), "contacts", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When Alice deletes that contact through the path of Bob
        cardDavClient.deleteContact(alice, bob.id(), "contacts", uid);

        // Then one message has the source path with Bob as owner, and one the path of the subscription with Alice as owner, and Alice is the connected user of both
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, subscribedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag));
    }

    @Test
    @Disabled("wait to new image")
    void creatingAContactOfADelegatedAddressBookThroughTheSourcePathShouldPublishMessagesForTheSourceAndTheDelegatedAddressBook() throws IOException {
        // Given Bob delegates his address book to Alice with write access, and a queue listening to the created contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String queue = newQueueBoundTo(CREATED_EXCHANGE);
        String uid = UUID.randomUUID().toString();

        // When Alice creates a contact through the path of Bob
        cardDavClient.upsertContact(alice, bob.id(), "contacts", uid, vcard(uid, "John Doe"));

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner, and Alice is the connected user of both
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, delegatedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void updatingAContactOfADelegatedAddressBookThroughTheSourcePathShouldPublishMessagesForTheSourceAndTheDelegatedAddressBook() throws IOException {
        // Given Bob delegates his address book, which holds a contact, to Alice with write access, and a queue listening to the updated contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String queue = newQueueBoundTo(UPDATED_EXCHANGE);

        // When Alice updates that contact through the path of Bob
        cardDavClient.upsertContact(alice, bob.id(), "contacts", uid, vcard(uid, "John Doe Updated"));

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner, and Alice is the connected user of both
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, delegatedBook, uid, vcard(uid, "John Doe Updated"),
                syncToken(bob, bob.id(), "contacts"), etag(bob, bob.id(), "contacts", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactOfADelegatedAddressBookThroughTheSourcePathShouldPublishFullMessagesForTheSourceAndTheDelegatedAddressBook() throws IOException {
        // Given Bob delegates his address book, which holds a contact, to Alice with write access, and a queue listening to the deleted contacts
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaasUser alice = dockerExtension().newTestUser();
        cardDavClient.grantDelegation(bob, "contacts", alice, DelegationRight.READ_WRITE);
        String delegatedBook = otherAddressBookOf(alice);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(bob, "contacts", uid, vcard(uid, "John Doe"));
        String etag = etag(bob, bob.id(), "contacts", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When Alice deletes that contact through the path of Bob
        cardDavClient.deleteContact(alice, bob.id(), "contacts", uid);

        // Then one message has the source path with Bob as owner, and one the path of Alice with Alice as owner, both with the card data
        assertExactMessages(awaitMessagesOfCard(queue, uid, 2), "principals/users/" + alice.id(),
            sourceAndMirrorMessages(bob, alice, delegatedBook, uid, vcard(uid, "John Doe"),
                syncToken(bob, bob.id(), "contacts"), etag));
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
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/technicalUser", cardMessage(
            "addressbooks/" + domainId + "/dab/" + uid + ".vcf",
            "principals/domains/" + domainId,
            vcard(uid, "John Doe"),
            syncToken(member, domainId, "dab"),
            etag(member, domainId, "dab", uid)));
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
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/technicalUser", cardMessage(
            "addressbooks/" + domainId + "/domain-members/" + uid + ".vcf",
            "principals/domains/" + domainId,
            vcard(uid, "John Doe"),
            syncToken(member, domainId, "domain-members"),
            etag(member, domainId, "domain-members", uid)));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactOfADomainAddressBookShouldPublishAMessageOwnedByTheDomain() throws IOException {
        // Given a domain address book holding a contact, and a queue listening to the deleted contacts
        String domainName = "domain-" + UUID.randomUUID() + ".test";
        String domainId = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(domainName).getObjectId("_id").toString();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken(domainId);
        OpenPaasUser member = dockerExtension().twakeCalendarProvisioningService().createUser("user_" + UUID.randomUUID(), domainName).block();
        cardDavClient.createDomainAddressBook(domainId, technicalToken);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertDomainContact(domainId, uid, vcard(uid, "John Doe"), technicalToken);
        String etag = etag(member, domainId, "dab", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When that contact is deleted
        given()
            .header(TWAKE_CALENDAR_TOKEN_HEADER, technicalToken)
        .when()
            .delete("/addressbooks/" + domainId + "/dab/" + uid + ".vcf")
        .then()
            .statusCode(204);

        // Then a single message is published, owned by the domain, with the sync token following the deletion and the etag of the deleted card
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/technicalUser",
            cardMessage("addressbooks/" + domainId + "/dab/" + uid + ".vcf", "principals/domains/" + domainId,
                vcard(uid, "John Doe"), syncToken(member, domainId, "dab"), etag));
    }

    @Test
    @Disabled("wait to new image")
    void deletingAContactOfADomainMembersAddressBookShouldPublishAMessageOwnedByTheDomain() throws IOException {
        // Given a domain members address book holding a contact, and a queue listening to the deleted contacts
        String domainName = "domain-" + UUID.randomUUID() + ".test";
        String domainId = dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(domainName).getObjectId("_id").toString();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken(domainId);
        OpenPaasUser member = dockerExtension().twakeCalendarProvisioningService().createUser("user_" + UUID.randomUUID(), domainName).block();
        cardDavClient.createDomainMembersAddressBook(domainId, technicalToken);
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertDomainMemberContact(domainId, uid, vcard(uid, "John Doe"), technicalToken);
        String etag = etag(member, domainId, "domain-members", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When that contact is deleted
        cardDavClient.deleteContactDomainMembers(domainId, uid, technicalToken);

        // Then a single message is published, owned by the domain, with the sync token following the deletion and the etag of the deleted card
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/technicalUser",
            cardMessage("addressbooks/" + domainId + "/domain-members/" + uid + ".vcf", "principals/domains/" + domainId,
                vcard(uid, "John Doe"), syncToken(member, domainId, "domain-members"), etag));
    }

    @Test
    @Disabled("wait to new image")
    void movingAContactToAnotherAddressBookShouldPublishADeletedMessageForTheSource() throws IOException {
        // Given a contact in the collected address book, and a queue listening to the deleted contacts
        OpenPaasUser user = dockerExtension().newTestUser();
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(user, "collected", uid, vcard(uid, "John Doe"));
        String etag = etag(user, user.id(), "collected", uid);
        String queue = newQueueBoundTo(DELETED_EXCHANGE);

        // When the user moves that contact to the contacts address book
        given()
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Destination", "/addressbooks/" + user.id() + "/contacts/" + uid + ".vcf")
        .when()
            .request("MOVE", "/addressbooks/" + user.id() + "/collected/" + uid + ".vcf")
        .then()
            .statusCode(201);

        // Then a single message is published, with the source path, the sync token of the source address book and the etag of the moved card
        assertExactMessages(awaitMessagesOfCard(queue, uid, 1), "principals/users/" + user.id(),
            cardMessage("addressbooks/" + user.id() + "/collected/" + uid + ".vcf", "principals/users/" + user.id(),
                vcard(uid, "John Doe"), syncToken(user, user.id(), "collected"), etag));
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

    private ObjectNode[] sourceAndMirrorMessages(OpenPaasUser bob, OpenPaasUser alice, String mirrorBook, String uid, byte[] vcard, long syncToken, String etag) {
        return new ObjectNode[] {
            cardMessage("addressbooks/" + bob.id() + "/contacts/" + uid + ".vcf", "principals/users/" + bob.id(), vcard, syncToken, etag),
            mirrorMessage(bob, alice, mirrorBook, uid, vcard, syncToken, etag)
        };
    }

    private ObjectNode mirrorMessage(OpenPaasUser bob, OpenPaasUser mirrorOwner, String mirrorBook, String uid, byte[] vcard, long syncToken, String etag) {
        return cardMessage("addressbooks/" + mirrorOwner.id() + "/" + mirrorBook + "/" + uid + ".vcf", "principals/users/" + mirrorOwner.id(), vcard, syncToken, etag)
            .put("sourcePath", "addressbooks/" + bob.id() + "/contacts/" + uid + ".vcf");
    }

    private void assertExactMessages(List<String> actual, String connectedUser, ObjectNode... expected) {
        ArrayNode expectedMessages = OBJECT_MAPPER.createArrayNode();
        for (ObjectNode message : expected) {
            expectedMessages.add(message.deepCopy().put("connectedUser", connectedUser));
        }
        assertThatJson("[" + String.join(",", actual) + "]")
            .when(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo(expectedMessages.toString());
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
     * Tests of the same JVM share the exchanges, hence the filtering on the card.
     */
    private List<String> awaitMessagesOfCard(String queue, String uid, int expectedCount) {
        return awaitMessages(queue, expectedCount, path -> path.endsWith("/" + uid + ".vcf"));
    }

    private List<String> awaitMessagesOnPath(String queue, String path, int expectedCount) {
        return awaitMessages(queue, expectedCount, path::equals);
    }

    private List<String> awaitMessages(String queue, int expectedCount, Predicate<String> pathFilter) {
        List<String> messages = new ArrayList<>();
        awaitAtMost.untilAsserted(() -> {
            GetResponse delivery;
            while ((delivery = dockerExtension().getChannel().basicGet(queue, true)) != null) {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                if (pathFilter.test(OBJECT_MAPPER.readTree(body).get("path").asText())) {
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
