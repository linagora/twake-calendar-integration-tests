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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.dav;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmlunit.assertj3.XmlAssert;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

class CardDavTest {
    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    @Test
    void unauthenticatedCallsShouldBeRejected() {
        HttpClientResponse response = getHttpClient()
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/.well-known/carddav")
            .response()
            .block();

        assertThat(response.status())
            .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
    }

    @Test
    void authenticatedCallsShouldBeAccepted() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks")
            .response()
            .block();

        assertThat(response.status())
            .isEqualTo(HttpResponseStatus.OK);
    }

    @Test
    void autoDiscoveryShouldRedirect() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/.well-known/carddav")
            .response()
            .block();

        assertThat(response.status())
            .isEqualTo(HttpResponseStatus.MOVED_PERMANENTLY);
    }

    @Test
    void propfindShouldListUserAddressBooks() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        String responseBody = getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id())
            .responseSingle((r, byteBufMono) -> {
                return byteBufMono.asString(StandardCharsets.UTF_8);
            })
            .block();

        XmlAssert.assertThat(responseBody)
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "  <d:response>\n" +
                "    <d:href>/addressbooks/" + testUser.id() + "/</d:href>\n" +
                "    <d:propstat>\n" +
                "      <d:prop>\n" +
                "        <d:resourcetype>\n" +
                "          <d:collection/>\n" +
                "        </d:resourcetype>\n" +
                "      </d:prop>\n" +
                "      <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "    </d:propstat>\n" +
                "  </d:response>\n" +
                "  <d:response>\n" +
                "    <d:href>/addressbooks/" + testUser.id() + "/collected/</d:href>\n" +
                "    <d:propstat>\n" +
                "      <d:prop>\n" +
                "        <d:resourcetype>\n" +
                "          <d:collection/>\n" +
                "          <card:addressbook/>\n" +
                "        </d:resourcetype>\n" +
                "      </d:prop>\n" +
                "      <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "    </d:propstat>\n" +
                "  </d:response>\n" +
                "  <d:response>\n" +
                "    <d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href>\n" +
                "    <d:propstat>\n" +
                "      <d:prop>\n" +
                "        <d:resourcetype>\n" +
                "          <d:collection/>\n" +
                "          <card:addressbook/>\n" +
                "        </d:resourcetype>\n" +
                "      </d:prop>\n" +
                "      <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "    </d:propstat>\n" +
                "  </d:response>\n" +
                "</d:multistatus>\n")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void propfindShouldListUserEmptyAddressBooks() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        String responseBody = getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .responseSingle((response, byteBufMono) -> byteBufMono.asString(StandardCharsets.UTF_8))
            .block();

        XmlAssert.assertThat(responseBody)
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "</d:multistatus>\n")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    private static HttpClient getHttpClient() {
        return HttpClient.create()
            .baseUrl("http://" + TestContainersUtils.getContainerPrivateIpAddress(dockerOpenPaasExtension.getDockerOpenPaasSetupSingleton().getSabreDavContainer()) + ":80");
    }
}
