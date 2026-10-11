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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.restassured.specification.RequestSpecification;
import reactor.core.publisher.Mono;

public class TestUtil {
    private static final ConditionFactory calmlyAwait = Awaitility.with()
        .pollDelay(Duration.ofSeconds(2))
        .pollInterval(Duration.ofSeconds(1))
        .await();

    public static final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    public static final String TWAKE_CALENDAR_TOKEN_HEADER = "TwakeCalendarToken";

    public static ConditionFactory awaitEvery500Millis() {
        return Awaitility.with()
            .pollDelay(Duration.ofMillis(500))
            .pollInterval(Duration.ofMillis(500))
            .await();
    }

    public static <T> T awaitPresent(ConditionFactory await, Callable<Optional<T>> condition) {
        return await.until(condition, Optional::isPresent).orElseThrow();
    }

    public static Mono<ByteBuf> body(String body) {
        return Mono.just(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)));
    }

    public static List<String> awaitCalendarEntries(RequestSpecification davSpecification, OpenPaasUser attendee, String collectionPath, int expectedCount) {
        List<String> hrefsFromPropfind = new ArrayList<>();
        awaitAtMost.untilAsserted(() -> {
            String responseBody = given(davSpecification)
                .header("Authorization", attendee.impersonatedBasicAuth())
                .contentType("application/xml")
                .accept("application/xml")
            .when()
                .request("PROPFIND", collectionPath)
            .then()
                .statusCode(207)
                .extract().asString();

            List<String> hrefs = XMLUtil.extractCalendarHrefsFromPropfind(responseBody);
            assertThat(hrefs)
                .as("Expected number of messages in " + collectionPath + " for " + attendee.email())
                .hasSize(expectedCount);
            hrefsFromPropfind.addAll(hrefs);
        });
        return hrefsFromPropfind;
    }
}
