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
import static org.xmlunit.diff.ComparisonResult.SIMILAR;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.DifferenceEvaluator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

class CardDavTest {

    public static final boolean DEBUG = true;
    public static final String STRING = "BEGIN:VCARD\n" +
        "VERSION:3.0\n" +
        "FN:John Doe\n" +
        "N:Doe;John;;;\n" +
        "EMAIL:john.doe@example.com\n" +
        "UID:123456789\n" +
        "END:VCARD\n";
    public static final DifferenceEvaluator IGNORE_GETLASTMODIFIED = (comparison, outcome) -> {
        if (outcome.equals(ComparisonResult.DIFFERENT) &&
            comparison.getControlDetails().getXPath() != null &&
            comparison.getControlDetails().getXPath().contains("getlastmodified")) {
            return SIMILAR;
        }
        if (outcome.equals(ComparisonResult.DIFFERENT) &&
            comparison.getControlDetails().getXPath() == null &&
            comparison.getControlDetails().getValue() == null &&
            comparison.getControlDetails().getTarget() == null &&
            comparison.getControlDetails().getParentXPath().equals("/multistatus[1]/response[2]/propstat[1]/prop[1]")) {
            return SIMILAR;
        }
        return outcome;
    };

    record Response(int status, String body) {}

    Response execute(HttpClient.ResponseReceiver<?> client) {
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


    int executeNoContent(HttpClient.ResponseReceiver<?> client) {
        return client.response()
            .block()
            .status()
            .code();
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
    void mkcolShouldCreateNewAddressBook() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/awesome/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/collected/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void deleteAddressBookShouldWork() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        int status = executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/awesome"));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void propfindShouldNotReturnDeletedAddressBooks() {

        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/awesome"));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/collected/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void mkcolShouldSucceed() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status = executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        assertThat(status).isEqualTo(201);
    }

    @Test
    void nestingAddressBookShouldNotBeSupported() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        int status = executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome/v2")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void creatingAddressBookShouldNotBeAllowedOutOfUserRoot() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status = executeNoContent(getHttpClient()
            .headers(header -> testUser.basicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                "          <d:set>\n" +
                "                <d:prop>\n" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>\n" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>\n" +
                "                </d:prop>\n" +
                "          </d:set>\n" +
                "         </d:mkcol>"))));

        assertThat(status).isEqualTo(405);
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

    @Test
    void putShouldWork() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status = executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(201);
    }

    @Test
    void getShouldSucceed() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(response).isEqualTo(new Response(200, STRING));
    }

    @Test
    void getShouldReturnNotFoundWhenMissing() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(response.status).isEqualTo(404);
    }

    @Test
    void putShouldReplace() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe-Riga\n" +
                "EMAIL:john.doe@example.com\n" +
                "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
                "UID:123456789\n" +
                "END:VCARD\n"))));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(response).isEqualTo(new Response(200, "BEGIN:VCARD\n" +
            "VERSION:3.0\n" +
            "FN:John Doe-Riga\n" +
            "EMAIL:john.doe@example.com\n" +
            "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
            "UID:123456789\n" +
            "END:VCARD\n"));
    }

    @Test
    void putShouldReturn204WhenExist() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void deleteShouldWork() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void deleteShouldReturnNotFoundWhenMissing() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status = executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(404);
    }

    @Test
    void deleteShouldNotBeReturnedByPropfind() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts"));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void putShouldBeListedByPropfind() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts"));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href><d:propstat><d:prop><d:getlastmodified>Fri, 14 Feb 2025 14:57:02 GMT</d:getlastmodified><d:getcontentlength>101</d:getcontentlength><d:resourcetype/><d:getetag>&quot;b6cfbc684d6173513ed73f413e6b6cb4&quot;</d:getetag><d:getcontenttype>text/vcard; charset=utf-8</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    void putShouldNotBeListedByPropfindWhenWrongDepth() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/collected/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    void propfindShouldAllowRetrievingTheDisplayName() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "        <d:propfind xmlns:d=\"DAV:\">" +
                "          <d:prop>" +
                "            <d:displayname/>" +
                "          </d:prop>" +
                "        </d:propfind>")));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/collected/</d:href><d:propstat><d:prop><d:displayname></d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:displayname></d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    void proppatchShouldUpdateDisplayName() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Address Book Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        assertThat(response.status).isEqualTo(207);
    }

    @Test
    void proppatchShouldReturnOldValue() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Address Book Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts</d:href><d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    void propfindShouldRetrieveUpdatedDisplayName() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Address Book Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "        <d:propfind xmlns:d=\"DAV:\">" +
                "          <d:prop>" +
                "            <d:displayname/>" +
                "          </d:prop>" +
                "        </d:propfind>")));

        XmlAssert.assertThat(response.body)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/collected/</d:href><d:propstat><d:prop><d:displayname></d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:displayname>New Address Book Name</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    private static Mono<ByteBuf> body(String body) {
        return Mono.just(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void canRecreatePreviouslyDeletedVCards() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        Response response = execute(getHttpClient()
            .headers(testUser::basicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        executeNoContent(getHttpClient()
            .headers(testUser::basicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(response).isEqualTo(new Response(200, STRING));
    }

    private static HttpClient getHttpClient() {
        return HttpClient.create()
            .baseUrl("http://" + TestContainersUtils.getContainerPrivateIpAddress(dockerOpenPaasExtension.getDockerOpenPaasSetupSingleton().getSabreDavContainer()) + ":80");
    }

    // TODO REPORT VCARD
    // synctoken
    // vcard with a given UID
    // vcard with a given email

    // Retrieve user address book

    // edge cases: duplicated uid
}
