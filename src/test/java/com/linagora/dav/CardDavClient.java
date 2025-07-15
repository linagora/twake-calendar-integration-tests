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

import org.apache.commons.lang3.StringUtils;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

public class CardDavClient {

    private static final String CONTENT_TYPE_VCARD = "application/vcard";
    private static final String ACCEPT_VCARD_JSON = "text/plain";
    private static final String ADDRESS_BOOK_PATH = "/addressbooks/%s/%s/%s.vcf";

    private final HttpClient client;

    public CardDavClient(HttpClient client) {
        this.client = client;
    }

    public void upsertContact(OpenPaasUser openPaasUser, String addressBook, String vcardUid, byte[] vcardPayload) {
        client.headers(headers -> openPaasUser.basicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_VCARD)
                .add(HttpHeaderNames.ACCEPT, ACCEPT_VCARD_JSON))
            .put()
            .uri(String.format(ADDRESS_BOOK_PATH, openPaasUser.id(), addressBook, vcardUid))
            .send(Mono.just(Unpooled.wrappedBuffer(vcardPayload)))
            .responseSingle((response, byteBufMono) ->
                handleContactUpsertResponse(response, byteBufMono, openPaasUser, addressBook, vcardUid))
            .block();
    }

    public void deleteContact(OpenPaasUser openPaasUser, String addressBookId, String vcardUid) {
        String uri = String.format("/addressbooks/%s/%s/%s.vcf", openPaasUser.id(), addressBookId, vcardUid);
        client.headers(headers -> openPaasUser.basicAuth(headers)
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
}
