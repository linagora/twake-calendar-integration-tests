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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.xmlunit.diff.ComparisonResult.SIMILAR;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.DifferenceEvaluator;

import io.netty.handler.codec.http.HttpMethod;
import reactor.netty.http.client.HttpClientResponse;

class CardDavTest {
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
    public static final DifferenceEvaluator IGNORE_ETAG = (comparison, outcome) -> {
        if (outcome.equals(ComparisonResult.DIFFERENT) &&
            comparison.getControlDetails().getXPath() != null &&
            comparison.getControlDetails().getXPath().contains("getetag")) {
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

    @RegisterExtension
    static DockerTwakeCalendarExtension dockerExtension = new DockerTwakeCalendarExtension();

    @Test
    void unauthenticatedCallsShouldBeRejected() {
        DavResponse response = execute(dockerExtension.davHttpClient()
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks"));

        assertThat(response.status())
            .isEqualTo(401);
    }

    @Test
    void authenticatedCallsShouldBeAccepted() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks"));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
    void propfindShouldListUserAddressBooks() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).containsExactlyInAnyOrder("/addressbooks/" + testUser.id() + "/",
            "/addressbooks/" + testUser.id() + "/collected/",
            "/addressbooks/" + testUser.id() + "/contacts/");
    }

    @Test
    void mkcolShouldCreateNewAddressBook() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).contains("/addressbooks/" + testUser.id() + "/awesome/");
    }

    @Test
    void deleteAddressBookShouldWork() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/awesome"));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void propfindShouldNotReturnDeletedAddressBooks() throws Exception {

        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/awesome"));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).doesNotContain("/addressbooks/" + testUser.id() + "/awesome/");
    }

    @Test
    void mkcolShouldSucceed() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        assertThat(status).isEqualTo(201);
    }

    @Test
    void nestingAddressBookShouldNotBeSupported() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/" + testUser.id() + "/awesome/v2")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void creatingAddressBookShouldNotBeAllowedOutOfUserRoot() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(header -> testUser.impersonatedBasicAuth(header)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/addressbooks/awesome")
            .send(body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "         <d:mkcol xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "          <d:set>" +
                "                <d:prop>" +
                "                  <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>" +
                "                  <d:displayname>AWESOME DISPLAY NAME</d:displayname>" +
                "                </d:prop>" +
                "          </d:set>" +
                "         </d:mkcol>"))));

        assertThat(status).isEqualTo(405);
    }

    @Test
    void propfindShouldListUserEmptyAddressBooks() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts"));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\"><d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void putShouldWork() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(201);
    }

    @Test
    void getShouldSucceed() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(response).isEqualTo(new DavResponse(200, STRING));
    }

    @Test
    void getShouldReturnNotFoundWhenMissing() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void putShouldReplace() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe-Riga\n" +
                "EMAIL:john.doe@example.com\n" +
                "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
                "UID:123456789\n" +
                "END:VCARD\n"))));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(response).isEqualTo(new DavResponse(200, "BEGIN:VCARD\n" +
            "VERSION:3.0\n" +
            "FN:John Doe-Riga\n" +
            "EMAIL:john.doe@example.com\n" +
            "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
            "UID:123456789\n" +
            "END:VCARD\n"));
    }

    @Test
    void putShouldReturn204WhenExist() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void deleteShouldWork() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void deleteShouldReturnNotFoundWhenMissing() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(404);
    }

    @Test
    void deleteShouldNotBeReturnedByPropfind() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts"));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><card:addressbook/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void putShouldBeListedByPropfind() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts"));

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).contains("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf");
    }

    @Test
    void putShouldNotBeListedByPropfindWhenWrongDepth() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id()));

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).doesNotContain("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf");
    }

    @Test
    void propfindShouldAllowRetrievingTheDisplayName() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "        <d:propfind xmlns:d=\"DAV:\">" +
                "          <d:prop>" +
                "            <d:displayname/>" +
                "          </d:prop>" +
                "        </d:propfind>")));

        XmlAssert.assertThat(response.body())
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
    void shouldAllowDuplicatedUid() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/other.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(201);
    }

    @Test
    void proppatchShouldUpdateDisplayName() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Address Book Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
    void proppatchShouldReturnOldValue() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Address Book Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts</d:href><d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    void propfindShouldRetrieveUpdatedDisplayName() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Address Book Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "        <d:propfind xmlns:d=\"DAV:\">" +
                "          <d:prop>" +
                "            <d:displayname/>" +
                "          </d:prop>" +
                "        </d:propfind>")));

        XmlAssert.assertThat(response.body())
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

    @Test
    void canRecreatePreviouslyDeletedVCards() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(201);
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/34")
    @Test
    void headShouldReturnFound() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .head()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(200);
    }

    @Test
    void headShouldReturnNotFound() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .head()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(404);
    }

    @Test
    void shouldSupportExport() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/addressbooks/" + testUser.id() + "/contacts?export"));

        assertThat(response).isEqualTo(new DavResponse(200, "BEGIN:VCARD\r\n" +
            "VERSION:3.0\r\n" +
            "FN:John Doe\r\n" +
            "N:Doe;John;;;\r\n" +
            "EMAIL:john.doe@example.com\r\n" +
            "UID:123456789\r\n" +
            "END:VCARD\r\n"));
    }

    @Test
    void canReportEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));
        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "    <d:prop>" +
                "        <d:getetag />" +
                "    </d:prop>" +
                "</card:addressbook-query>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href><d:propstat><d:prop><d:getetag>&quot;b6cfbc684d6173513ed73f413e6b6cb4&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void putShouldReturnEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)).response()
            .block();

        assertThat(response.responseHeaders().contains("ETag")).isTrue();
    }

    @Test
    void putShouldFailCreateWhenBadEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("If-Match", "bad"))
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        assertThat(status).isEqualTo(412);
    }

    @Test
    void putShouldFailUpdateWhenBadEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)).response()
            .block();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("If-Match", "bad"))
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe-Riga\n" +
                "EMAIL:john.doe@example.com\n" +
                "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
                "UID:123456789\n" +
                "END:VCARD\n"))));

        assertThat(status).isEqualTo(412);
    }

    @Test
    void putShouldSucceedWhenGoodETag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response1 = dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)).response()
            .block();
        String etag = response1.responseHeaders().get("ETag");

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("If-Match", etag))
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe-Riga\n" +
                "EMAIL:john.doe@example.com\n" +
                "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
                "UID:123456789\n" +
                "END:VCARD\n"))));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void putShouldChangeEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response1 = dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)).response()
            .block();
        String etag1 = response1.responseHeaders().get("ETag");

        HttpClientResponse response2 = dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe-Riga\n" +
                "EMAIL:john.doe@example.com\n" +
                "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
                "UID:123456789\n" +
                "END:VCARD\n")))
            .response()
            .block();
        String etag2 = response2.responseHeaders().get("ETag");

        assertThat(etag1).isNotEqualTo(etag2);
    }

    @Test
    void deleteShouldShouldFailWhenBadEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

       executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("If-Match", "bad"))
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(412);
    }

    @Test
    void deleteShouldShoulSucceedWhenGoodEtag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        String etag = dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)).response().block().responseHeaders().get("ETag");

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("If-Match", etag))
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        assertThat(status).isEqualTo(204);
    }

    @Test
    void canReportSyncTokenInitialSync() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "0"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<d:propfind xmlns:d=\"DAV:\" xmlns:cs=\"http://calendarserver.org/ns/\">" +
                "  <d:prop>\n" +
                "     <d:sync-token />" +
                "  </d:prop>" +
                "</d:propfind>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/</d:href><d:propstat><d:prop><d:sync-token>http://sabre.io/ns/sync/1</d:sync-token></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenNoUpdate() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                "<d:sync-collection xmlns:d=\"DAV:\">\n" +
                "  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>\n" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenCreate() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                "<d:sync-collection xmlns:d=\"DAV:\">\n" +
                "  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>\n" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:response>\n" +
                "  <d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href>\n" +
                "  <d:propstat>\n" +
                "   <d:prop>\n" +
                "    <d:getetag>&quot;b6cfbc684d6173513ed73f413e6b6cb4&quot;</d:getetag>\n" +
                "   </d:prop>\n" +
                "   <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "  </d:propstat>\n" +
                " </d:response>\n" +
                " <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenDelete() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf"));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                "<d:sync-collection xmlns:d=\"DAV:\">\n" +
                "  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>\n" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:response>\n" +
                "  <d:status>HTTP/1.1 404 Not Found</d:status>\n" +
                "  <d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href>\n" +
                "  <d:propstat>\n" +
                "   <d:prop/>\n" +
                "   <d:status>HTTP/1.1 418 I'm a teapot</d:status>\n" +
                "  </d:propstat>\n" +
                " </d:response>\n" +
                " <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenUpdate() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(("BEGIN:VCARD\n" +
                "VERSION:3.0\n" +
                "FN:John Doe-Riga\n" +
                "EMAIL:john.doe@example.com\n" +
                "TEL;TYPE=WORK,VOICE:+1-555-123-4567\n" +
                "UID:123456789\n" +
                "END:VCARD\n"))));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
                "<d:sync-collection xmlns:d=\"DAV:\">\n" +
                "  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>\n" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:response>\n" +
                "  <d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href>\n" +
                "  <d:propstat>\n" +
                "   <d:prop>\n" +
                "    <d:getetag>&quot;b2da460cc3c54a9d9b1808fd14c1e8b9&quot;</d:getetag>\n" +
                "   </d:prop>\n" +
                "   <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "  </d:propstat>\n" +
                " </d:response>\n" +
                " <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldAllowUidLookup() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body(" <card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "    <d:prop>" +
                "      <card:address-data>" +
                "        <card:prop name=\"UID\"/>" +
                "      </card:address-data>" +
                "    </d:prop>" +
                "    <card:filter>" +
                "      <card:prop-filter name=\"UID\">" +
                "        <card:text-match collation=\"i;unicode-casemap\">123456789</card:text-match>" +
                "      </card:prop-filter>" +
                "    </card:filter>" +
                "  </card:addressbook-query>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href><d:propstat><d:prop><card:address-data>BEGIN:VCARD&#13;\n" +
                "VERSION:3.0&#13;\n" +
                "FN:John Doe&#13;\n" +
                "UID:123456789&#13;\n" +
                "END:VCARD&#13;\n" +
                "</card:address-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldNotIncludeUnrelatedDataWhenUidLookup() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body(" <card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "    <d:prop>" +
                "      <card:address-data>" +
                "        <card:prop name=\"UID\"/>" +
                "      </card:address-data>" +
                "    </d:prop>" +
                "    <card:filter>" +
                "      <card:prop-filter name=\"UID\">" +
                "        <card:text-match collation=\"i;unicode-casemap\">notFound</card:text-match>" +
                "      </card:prop-filter>" +
                "    </card:filter>" +
                "  </card:addressbook-query>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldAllowEmailLookup() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body(" <card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "    <d:prop>" +
                "      <card:address-data>" +
                "        <card:prop name=\"UID\"/>" +
                "      </card:address-data>" +
                "    </d:prop>" +
                "    <card:filter>" +
                "      <card:prop-filter name=\"EMAIL\">" +
                "        <card:text-match collation=\"i;unicode-casemap\">john.doe@example.com</card:text-match>" +
                "      </card:prop-filter>" +
                "    </card:filter>" +
                "  </card:addressbook-query>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href><d:propstat><d:prop><card:address-data>BEGIN:VCARD&#13;\n" +
                "VERSION:3.0&#13;\n" +
                "FN:John Doe&#13;\n" +
                "UID:123456789&#13;\n" +
                "END:VCARD&#13;\n" +
                "</card:address-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldAllowAutoComplete() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body(" <card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "    <d:prop>" +
                "      <card:address-data>" +
                "        <card:prop name=\"UID\"/>" +
                "        <card:prop name=\"EMAIL\"/>" +
                "      </card:address-data>" +
                "    </d:prop>" +
                "    <card:filter>" +
                "      <card:prop-filter name=\"EMAIL\">" +
                "        <card:text-match collation=\"i;unicode-casemap\" match-type=\"contains\">joh</card:text-match>" +
                "      </card:prop-filter>" +
                "    </card:filter>" +
                "  </card:addressbook-query>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf</d:href><d:propstat><d:prop><card:address-data>BEGIN:VCARD&#13;\n" +
                "VERSION:3.0&#13;\n" +
                "FN:John Doe&#13;\n" +
                "EMAIL:john.doe@example.com&#13;\n" +
                "UID:123456789&#13;\n" +
                "END:VCARD&#13;\n" +
                "</card:address-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldNotIncludeUnrelatedDataWhenEmailLookup() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .put()
            .uri("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
            .send(body(STRING)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/addressbooks/" + testUser.id() + "/contacts")
            .send(body(" <card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "    <d:prop>" +
                "      <card:address-data>" +
                "        <card:prop name=\"UID\"/>" +
                "      </card:address-data>" +
                "    </d:prop>" +
                "    <card:filter>" +
                "      <card:prop-filter name=\"EMAIL\">" +
                "        <card:text-match collation=\"i;unicode-casemap\">unrelated@notfound.com</card:text-match>" +
                "      </card:prop-filter>" +
                "    </card:filter>" +
                "  </card:addressbook-query>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }
}
