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

import static com.linagora.dav.DockerOpenPaasExtension.body;
import static com.linagora.dav.DockerOpenPaasExtension.execute;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmlunit.assertj3.XmlAssert;

import io.netty.handler.codec.http.HttpMethod;

class PrincipalTest {
    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    @Test
    void currentUserPrincipalShouldLinkTheUserPrincipal() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
                .add("Depth", 0)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/")
            .send(body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                     <d:current-user-principal />
                  </d:prop>
                </d:propfind>""")));

        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/principals/users/" + testUser.id() + "/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void shouldShowDisplayName() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
                .add("Depth", 0)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/")
            .send(body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                     <d:displayname/>
                     <d:current-user-principal />
                  </d:prop>
                </d:propfind>""")));

        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/principals/users/" + testUser.id() + "/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void shouldAllowAddressBookDiscovery() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
                .add("Depth", 0)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/principals/users/" + testUser.id())
            .send(body("""
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                               <d:prop>
                                  <card:addressbook-home-set />
                               </d:prop>
                             </d:propfind>""")));

        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/principals/users/" + testUser.id() + "/</d:href><d:propstat><d:prop><card:addressbook-home-set><d:href>/addressbooks/" + testUser.id() + "/</d:href></card:addressbook-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }
}
