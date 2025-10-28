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

import static com.linagora.dav.contracts.ITIPRequestContract.awaitAtMost;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class TestUtil {
    public static final boolean DEBUG = true;
    public static Mono<ByteBuf> body(String body) {
        return Mono.just(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)));
    }

    public static DavResponse execute(HttpClient.ResponseReceiver<?> client) {
        DavResponse block = client.responseSingle((response, content) -> content.asString()
                .map(stringContent -> new DavResponse(response.status().code(), stringContent)))
            .block();

        if (DEBUG) {
            System.out.println("============");
            System.out.println("Code: " + block.status());
            System.out.println(block.body());
            System.out.println("============");
        }

        return block;
    }

    public static int executeNoContent(HttpClient.ResponseReceiver<?> client) {
        return client.response()
            .block()
            .status()
            .code();
    }

    public static List<String> awaitCalendarEntries(HttpClient client, OpenPaasUser attendee, String collectionPath, int expectedCount) {
        List<String> hrefsFromPropfind = new ArrayList<>();
        awaitAtMost.untilAsserted(() -> {
            List<String> hrefs = XMLUtil.extractCalendarHrefsFromPropfind(
                execute(client
                    .headers(attendee::impersonatedBasicAuth)
                    .request(HttpMethod.valueOf("PROPFIND"))
                    .uri(collectionPath))
                    .body());
            assertThat(hrefs)
                .as("Expected number of messages in " + collectionPath + " for " + attendee.email())
                .hasSize(expectedCount);
            hrefsFromPropfind.addAll(hrefs);
        });
        return hrefsFromPropfind;
    }
}
