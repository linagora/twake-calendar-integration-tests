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

package com.linagora.dav;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Streams;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

public class CardDavClient {

    public enum DelegationRight {
        READ(2),
        READ_WRITE(3),
        ADMIN(5);

        private final int value;

        DelegationRight(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public enum PublicRight {
        READ("{DAV:}read"),
        READ_WRITE("{DAV:}write");

        private final String value;

        PublicRight(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private static final String CONTENT_TYPE_VCARD = "application/vcard";
    private static final String ACCEPT_VCARD_JSON = "text/plain";
    private static final String ADDRESS_BOOK_PATH = "/addressbooks/%s/%s/%s.vcf";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient client;

    public CardDavClient(HttpClient client) {
        this.client = client;
    }

    public void upsertContact(OpenPaasUser openPaasUser, String addressBook, String vcardUid, byte[] vcardPayload) {
        upsertContact(openPaasUser, openPaasUser.id(), addressBook, vcardUid, vcardPayload);
    }

    public void upsertContact(OpenPaasUser openPaasUser, String baseId, String addressBook, String vcardUid, byte[] vcardPayload) {
        client.headers(headers -> openPaasUser.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_VCARD)
                .add(HttpHeaderNames.ACCEPT, ACCEPT_VCARD_JSON))
            .put()
            .uri(String.format(ADDRESS_BOOK_PATH, baseId, addressBook, vcardUid))
            .send(Mono.just(Unpooled.wrappedBuffer(vcardPayload)))
            .responseSingle((response, byteBufMono) ->
                handleContactUpsertResponse(response, byteBufMono, openPaasUser, addressBook, vcardUid))
            .block();
    }

    public void deleteContact(OpenPaasUser openPaasUser, String addressBookId, String vcardUid) {
        deleteContact(openPaasUser, openPaasUser.id(), addressBookId, vcardUid);
    }

    public void deleteContact(OpenPaasUser openPaasUser, String baseId, String addressBookId, String vcardUid) {
        String uri = String.format("/addressbooks/%s/%s/%s.vcf", baseId, addressBookId, vcardUid);
        client.headers(headers -> openPaasUser.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/vcard+json"))
            .delete()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException(
                        "Unexpected status code: %d when deleting contact %s in address book %s for user %s\n%s"
                            .formatted(response.status().code(), vcardUid, addressBookId, openPaasUser.id(), errorBody))));
            }).block();
    }

    public String getContacts(OpenPaasUser openPaasUser, String baseId, String addressBookId) {
        String uri = String.format("/addressbooks/%s/%s.json?limit=100&offset=0&sort=fn",
            baseId, addressBookId);
        return client.headers(headers -> openPaasUser.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .get()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == 200) {
                    return buf.asString(StandardCharsets.UTF_8);
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException(
                        "Unexpected status code: %d when fetching contacts in address book %s for user %s\n%s"
                            .formatted(response.status().code(), addressBookId, openPaasUser.id(), errorBody))));
            }).block();
    }

    public Flux<AddressBookURL> findUserAddressBooks(OpenPaasUser openPaaSUser) {
        String uri = AddressBookURL.URL_PATH_PREFIX + "/" + openPaaSUser.id() + ".json"
            + "?personal=true&contactsCount=true&inviteStatus=2&shared=true&&subscribed=true";
        return client.headers(headers -> openPaaSUser.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/vcard+json"))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return responseContent.asString(StandardCharsets.UTF_8).map(this::extractURLsFromResponse);
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(errorBody -> Mono.error(new RuntimeException("""
                            Unexpected status code: %d when finding user address books for user '%s'
                            %s
                            """.formatted(response.status().code(), openPaaSUser.id(), errorBody))));
                }
            }).flatMapMany(Flux::fromIterable);
    }

    public void createAddressBook(OpenPaasUser user, String addressBook) {
        String payload = """
            {
                "id": "{id}",
                "dav:name": "{addressBook}",
                "dav:acl": [
                    "dav:read",
                    "dav:write"
                ],
                "type": "user"
            }
            """.replace("{id}", UUID.randomUUID().toString())
            .replace("{addressBook}", addressBook);

        client.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri("/addressbooks/" + user.id() + ".json")
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when creating address book '%s'
                        %s
                        """.formatted(response.status().code(), addressBook, errorBody))));
            }).block();
    }

    public void deleteAddressBook(OpenPaasUser openPaasUser, String addressBookId) {
        String uri = String.format("/addressbooks/%s/%s.json", openPaasUser.id(), addressBookId);
        client.headers(headers -> openPaasUser.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/vcard+json"))
            .delete()
            .uri(uri)
            .responseSingle((response, buf) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return buf.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException(
                        "Unexpected status code: %d when deleting address book %s for user %s\n%s"
                            .formatted(response.status().code(), addressBookId, openPaasUser.id(), errorBody))));
            }).block();
    }

    public void grantDelegation(OpenPaasUser user, String addressBookId, OpenPaasUser delegatedUser, DelegationRight right) {
        grantDelegation(user, user.id(), addressBookId, delegatedUser, right);
    }

    public void grantDelegation(OpenPaasUser user, String baseId, String addressBookId, OpenPaasUser delegatedUser, DelegationRight right) {
        String uri = "/addressbooks/" + baseId + "/" + addressBookId + ".json";

        String payload = """
            {
                "dav:share-resource": {
                    "dav:sharee": [
                        {
                            "dav:href": "mailto:{email}",
                            "dav:share-access": {right}
                        },
                        {
                            "dav:href": "principals/users/{userId}",
                            "dav:share-access": 1
                        }
                    ]
                }
            }
            """.replace("{userId}", user.id())
            .replace("{email}", delegatedUser.email())
            .replace("{right}", String.valueOf(right.getValue()));

        sendDelegationRequest(user, uri, payload);
    }

    public void revokeDelegation(OpenPaasUser user, String addressBookId, OpenPaasUser delegatedUser) {
        String uri = "/addressbooks/" + user.id() + "/" + addressBookId + ".json";

        String payload = """
            {
                "dav:share-resource": {
                    "dav:sharee": [
                        {
                            "dav:href": "mailto:{email}",
                            "dav:share-access": 4
                        },
                        {
                            "dav:href": "principals/users/{userId}",
                            "dav:share-access": 1
                        }
                    ]
                }
            }
            """.replace("{userId}", user.id())
            .replace("{email}", delegatedUser.email());

        sendDelegationRequest(user, uri, payload);
    }

    public void setPublicRight(OpenPaasUser user, String baseId, String addressBookId, PublicRight right) {
        String uri = "/addressbooks/" + baseId + "/" + addressBookId + ".json";

        String payload = """
            {
                "dav:publish-addressbook": {
                    "privilege": "{publicRight}"
                }
            }
            """.replace("{publicRight}", right.getValue());

        sendPublicRightRequest(user, uri, payload);
    }

    public void setHiddenPublicRight(OpenPaasUser user, String baseId, String addressBookId) {
        String uri = "/addressbooks/" + baseId + "/" + addressBookId + ".json";

        String payload = """
            {
                "dav:unpublish-addressbook": true
            }
            """;

        sendPublicRightRequest(user, uri, payload);
    }

    public void subscribe(OpenPaasUser user, String baseId, String originalAddressBook, String copiedAddressBookName) {
        String payload = """
            {
                "id": "{id}",
                "dav:name": "{copiedAddressBook}",
                "carddav:description": "",
                "dav:acl": [
                    "dav:read",
                    "dav:write"
                ],
                "type": "subscription",
                "openpaas:source": {
                    "_links": {
                        "self": {
                            "href": "{uri}"
                        }
                    }
                }
            }
            """.replace("{id}", UUID.randomUUID().toString())
            .replace("{copiedAddressBook}", copiedAddressBookName)
            .replace("{uri}", "/addressbooks/" + baseId + "/" + originalAddressBook + ".json");

        client.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri("/addressbooks/" + user.id() + ".json")
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when sending sharing request for address book '%s'
                        %s
                        """.formatted(response.status().code(), originalAddressBook, errorBody))));
            }).block();
    }

    private Mono<Void> handleContactUpsertResponse(HttpClientResponse response, ByteBufMono responseContent, OpenPaasUser openPaasUser, String addressBook, String vcardUid) {
        return switch (response.status().code()) {
            case 201, 204 -> Mono.empty();
            default -> responseBodyAsString(responseContent)
                .flatMap(responseBody ->
                    Mono.error(new RuntimeException("""
                                Unexpected status code: %d when creating contact for homeBaseId %s and addressBook %s and vcardUid: %s
                                %s
                                """.formatted(response.status().code(), openPaasUser.id(), addressBook, vcardUid, responseBody))));
        };
    }

    private Mono<String> responseBodyAsString(ByteBufMono byteBufMono) {
        return byteBufMono.asString(StandardCharsets.UTF_8)
            .switchIfEmpty(Mono.just(StringUtils.EMPTY));
    }

    private void sendDelegationRequest(OpenPaasUser user, String uri, String payload) {
        client.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when delegating address book '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    private void sendPublicRightRequest(OpenPaasUser user, String uri, String payload) {
        client.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when set public right for address book '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    private List<AddressBookURL> extractURLsFromResponse(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            ArrayNode calendars = (ArrayNode) node.path("_embedded").path("dav:addressbook");
            return Streams.stream(calendars.elements())
                .map(calendarNode -> calendarNode.path("_links").path("self").path("href").asText())
                .filter(href -> !href.isEmpty())
                .map(this::parseHref)
                .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse calendar list JSON", e);
        }
    }

    private AddressBookURL parseHref(String href) {
        String[] parts = href.split("/");
        if (parts.length != 4) {
            throw new RuntimeException("Found an invalid calendar href in JSON response: " + href);
        }
        String userId = parts[2];
        String bookIdWithExt = parts[3];
        String bookId = bookIdWithExt.replace(".json", "");
        return new AddressBookURL(userId, bookId);
    }
}
