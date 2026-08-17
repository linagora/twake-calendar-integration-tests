/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.dav.contracts.card;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.dav.AddressBookURL;
import com.linagora.dav.CardDavClient;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.VCardContact;

import net.javacrumbs.jsonunit.core.Option;

public abstract class CardReportContract {
    private static final String CONTACTS = "contacts";
    private static final String SYNC_TOKEN_PREFIX = "http://sabre.io/ns/sync/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CardDavClient cardDavClient;
    private OpenPaasUser alice;
    private AddressBookURL addressBookURL;

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
        alice = dockerExtension().newTestUser();
        addressBookURL = new AddressBookURL(alice.id(), CONTACTS);
    }

    @Test
    void reportShouldReturnOnlyCreatedContactsAfterGivenSync() {
        // GIVEN a contact and its resulting sync token
        createContact("First");
        String previousSyncToken = currentSyncToken();

        // WHEN another contact is created and changes are reported from the previous token
        String secondUid = createContact("Second");
        String currentSyncToken = currentSyncToken();
        DavResponse response = cardDavClient.findContactsBySyncToken(alice, addressBookURL, previousSyncToken);

        // THEN the token advances and only the second contact is returned
        assertThat(currentSyncToken).isNotEqualTo(previousSyncToken);
        assertThat(response.status()).isEqualTo(207);
        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                  "_embedded": {"dav:item": [{"_links": {"self": {"href": "%s/%s.vcf"}}, "status": 200}]},
                  "sync-token": "%s"
                }
                """.formatted(addressBookURL.asUri(), secondUid, currentSyncToken));
    }

    @Test
    void reportShouldReturnAllContactsOnInitialSync() {
        // GIVEN an empty address book and its initial sync token
        String initialSyncToken = currentSyncToken();

        // WHEN two contacts are created and changes are reported from the initial token
        String firstUid = createContact("First");
        String secondUid = createContact("Second");
        String currentSyncToken = currentSyncToken();
        DavResponse response = cardDavClient.findContactsBySyncToken(alice, addressBookURL, initialSyncToken);

        // THEN both contacts are returned as existing resources
        assertThat(currentSyncToken).isNotEqualTo(initialSyncToken);
        assertThat(response.status()).isEqualTo(207);
        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_FIELDS, Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                  "_embedded": {"dav:item": [
                    {"_links": {"self": {"href": "%s/%s.vcf"}}, "status": 200},
                    {"_links": {"self": {"href": "%s/%s.vcf"}}, "status": 200}
                  ]},
                  "sync-token": "%s"
                }
                """.formatted(addressBookURL.asUri(), firstUid, addressBookURL.asUri(), secondUid, currentSyncToken));
    }

    @Test
    void reportShouldReturnOnlyDeletedContactsAfterGivenSync() {
        // GIVEN two contacts and their current sync token
        String firstUid = createContact("First");
        createContact("Second");
        String previousSyncToken = currentSyncToken();

        // WHEN the first contact is deleted and changes are reported from the previous token
        cardDavClient.deleteContact(alice, CONTACTS, firstUid);
        String currentSyncToken = currentSyncToken();
        DavResponse response = cardDavClient.findContactsBySyncToken(alice, addressBookURL, previousSyncToken);

        // THEN the token advances and only the deleted contact is returned with status 404
        assertThat(currentSyncToken).isNotEqualTo(previousSyncToken);
        assertThat(response.status()).isEqualTo(207);
        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                  "_embedded": {"dav:item": [{"_links": {"self": {"href": "%s/%s.vcf"}}, "status": 404}]},
                  "sync-token": "%s"
                }
                """.formatted(addressBookURL.asUri(), firstUid, currentSyncToken));
    }

    @Test
    void reportShouldReturnOnlyUpdatedContactAfterGivenSync() {
        // GIVEN two contacts and their current sync token
        String firstUid = createContact("First");
        createContact("Second");
        String previousSyncToken = currentSyncToken();

        // WHEN the first contact is updated and changes are reported from the previous token
        cardDavClient.upsertContact(alice, CONTACTS, firstUid, VCardContact.builder()
            .firstName("Updated")
            .lastName("Contact")
            .build()
            .toVCardPayload(firstUid));
        String currentSyncToken = currentSyncToken();
        DavResponse response = cardDavClient.findContactsBySyncToken(alice, addressBookURL, previousSyncToken);

        // THEN the token advances and only the updated contact is returned with status 200
        assertThat(currentSyncToken).isNotEqualTo(previousSyncToken);
        assertThat(response.status()).isEqualTo(207);
        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                  "_embedded": {"dav:item": [{"_links": {"self": {"href": "%s/%s.vcf"}}, "status": 200}]},
                  "sync-token": "%s"
                }
                """.formatted(addressBookURL.asUri(), firstUid, currentSyncToken));
    }

    @Test
    void reportShouldReturnEmptyListWhenNoChangesAfterGivenSync() {
        // GIVEN two contacts and no later address book change
        createContact("First");
        createContact("Second");
        String currentSyncToken = currentSyncToken();

        // WHEN reporting from the current sync token
        DavResponse response = cardDavClient.findContactsBySyncToken(alice, addressBookURL, currentSyncToken);

        // THEN no delta is returned and the token remains unchanged
        assertThat(response.status()).isEqualTo(207);
        assertThatJson(response.body())
            .when(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                {
                  "_embedded": {"dav:item": []},
                  "sync-token": "%s"
                }
                """.formatted(currentSyncToken));
    }

    private String createContact(String firstName) {
        String uid = UUID.randomUUID().toString();
        cardDavClient.upsertContact(alice, CONTACTS, uid, VCardContact.builder()
            .firstName(firstName)
            .lastName("Contact")
            .build()
            .toVCardPayload(uid));
        return uid;
    }

    private String currentSyncToken() {
        try {
            String response = cardDavClient.getContacts(alice, alice.id(), CONTACTS);
            String syncToken = OBJECT_MAPPER.readTree(response).path("dav:syncToken").asText();
            return SYNC_TOKEN_PREFIX + syncToken;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read address book sync token", e);
        }
    }

}
