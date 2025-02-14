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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmlunit.assertj3.XmlAssert;

import io.netty.handler.codec.http.HttpMethod;
import reactor.netty.http.client.HttpClient;

class CardDavTest {

    public static final boolean DEBUG = true;

    record Response(int status, String body) {}

    Response execute(HttpClient.RequestSender client) {
        Response block = client.responseSingle((response, content) -> content.asString()
                .map(stringContent -> new Response(response.status().code(), stringContent)))
            .block();

        if (DEBUG) {
            System.out.println("============");
            System.out.println("Code: " + block.status);
            System.out.println(block.body);
            System.out.println("============");
        }

        return block;
    }

    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    @Test
    void unauthenticatedCallsShouldBeRejected() {
        Response response = execute(getHttpClient()
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks"));

        assertThat(response.status())
            .isEqualTo(401);
    }

    @Test
    void authenticatedCallsShouldBeAccepted() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks"));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
    void propfindShouldListUserAddressBooks() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/collected/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void propfindShouldListUserEmptyAddressBooks() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts"));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\"><d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    private static HttpClient getHttpClient() {
        return HttpClient.create()
            .baseUrl("http://" + TestContainersUtils.getContainerPrivateIpAddress(dockerOpenPaasExtension.getDockerOpenPaasSetupSingleton().getSabreDavContainer()) + ":80");
    }
}
