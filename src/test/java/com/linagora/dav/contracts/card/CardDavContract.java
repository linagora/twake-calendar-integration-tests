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

package com.linagora.dav.contracts.card;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.xmlunit.diff.ComparisonResult.SIMILAR;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Node;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.DifferenceEvaluator;
import org.xmlunit.diff.ElementSelectors;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.XMLUtil;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

public abstract class CardDavContract {
    public static final String STRING = """
        BEGIN:VCARD
        VERSION:3.0
        FN:John Doe
        N:Doe;John;;;
        EMAIL:john.doe@example.com
        UID:123456789
        END:VCARD
        """;
    public static final String BASE64_IMAGE = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB";
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

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/xml")
            .accept("application/xml");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unauthenticatedEndpoints")
    protected void unauthenticatedRequestsShouldReturn401(AuthenticatedEndpoint input) {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        String realUserId = testUser.id();
        String resolvedPath = input.pathTemplate().replace("{userId}", realUserId);
        Optional<String> resolvedPayload = input.payloadTemplate().map(payload -> payload.replace("{userId}", realUserId));

        RequestSpecification request = given()
            .headers(input.headers());
        resolvedPayload.ifPresent(payload -> request.body(payload.getBytes(StandardCharsets.UTF_8)));

        request.when()
            .request(input.method(), resolvedPath)
        .then()
            .statusCode(401);
    }

    protected record AuthenticatedEndpoint(String method, String pathTemplate, Optional<String> payloadTemplate, Map<String, String> headers) {
        @Override
        public String toString() {
            return method + " " + pathTemplate;
        }
    }

    private static Stream<AuthenticatedEndpoint> unauthenticatedEndpoints() {
        String userId = "{userId}";

        return Stream.of(
            new AuthenticatedEndpoint("DELETE", "/addressbooks/" + userId + "/fake-book-id", Optional.empty(),
                Map.of()),

            new AuthenticatedEndpoint("DELETE", "/addressbooks/" + userId + "/contacts/fake-card-id.vcf", Optional.empty(),
                Map.of()),

            new AuthenticatedEndpoint("GET", "/addressbooks/" + userId + "/contacts/fake-card-id.vcf", Optional.empty(),
                Map.of("Accept", "text/vcard")),

            new AuthenticatedEndpoint("GET", "/addressbooks/" + userId + "/contacts?export", Optional.empty(),
                Map.of("Accept", "text/vcard")),

            new AuthenticatedEndpoint("HEAD", "/addressbooks/" + userId + "/contacts/fake-card-id.vcf", Optional.empty(),
                Map.of()),

            new AuthenticatedEndpoint("MKCOL", "/addressbooks/" + userId + "/fake-book-id", Optional.empty(),
                Map.of()),

            new AuthenticatedEndpoint("POST", "/addressbooks/" + userId, Optional.of("""
                    {"id":"%s","dav:name":"Unauthorized Addressbook","dav:acl":["dav:read","dav:write"],"type":"user"}
                    """.formatted(userId)),
                Map.of("Accept", "application/json", "Content-Type", "application/json")),

            new AuthenticatedEndpoint("POST", "/addressbooks/" + userId + "/collected", Optional.of("""
                    {"dav:share-resource":{"dav:sharee":[{"dav:href":"mailto:someone@example.com","dav:share-access":2}]}}
                    """),
                Map.of("Accept", "application/json", "Content-Type", "application/json")),

            new AuthenticatedEndpoint("PROPFIND", "/addressbooks", Optional.empty(), Map.of()),

            new AuthenticatedEndpoint("PROPFIND", "/addressbooks/" + userId, Optional.empty(),
                Map.of()),

            new AuthenticatedEndpoint("PROPFIND", "/addressbooks/" + userId + "/contacts", Optional.empty(),
                Map.of()),

            new AuthenticatedEndpoint("PROPPATCH", "/addressbooks/" + userId + "/" + userId, Optional.of("""
                    {"dav:name":"Hacked book"}
                    """),
                Map.of("Accept", "application/json", "Content-Type", "application/json")),

            new AuthenticatedEndpoint("PROPPATCH", "/addressbooks/" + userId + "/contacts",
                Optional.of("<d:propertyupdate xmlns:d=\"DAV:\"><d:set><d:prop><d:displayname>Hacked Name</d:displayname></d:prop></d:set></d:propertyupdate>"),
                Map.of("Content-Type", "application/xml")),

            new AuthenticatedEndpoint("PUT", "/addressbooks/" + userId + "/contacts/fake-card-id.vcf",
                Optional.of("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Fake Card\r\nUID:fake-card-id\r\nEND:VCARD"),
                Map.of("Content-Type", "text/vcard")),

            new AuthenticatedEndpoint("REPORT", "/addressbooks/" + userId + "/contacts",
                Optional.of("<card:addressbook-query xmlns:d=\"DAV:\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\"><d:prop><d:getetag /></d:prop></card:addressbook-query>"),
                Map.of("Content-Type", "application/xml", "Depth", "1"))
        );
    }

    @Test
    void authenticatedCallsShouldBeAccepted() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks")
        .then()
            .statusCode(207);
    }

    @Test
    void propfindShouldListUserAddressBooks() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).containsExactlyInAnyOrder("/addressbooks/" + testUser.id() + "/",
            "/addressbooks/" + testUser.id() + "/collected/",
            "/addressbooks/" + testUser.id() + "/contacts/");
    }

    @Test
    void mkcolShouldCreateNewAddressBook() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).contains("/addressbooks/" + testUser.id() + "/awesome/");
    }

    @Test
    void deleteAddressBookShouldWork() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(204);
    }

    @Test
    void propfindShouldNotReturnDeletedAddressBooks() throws Exception {

        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(204);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).doesNotContain("/addressbooks/" + testUser.id() + "/awesome/");
    }

    @Test
    void mkcolShouldSucceed() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(201);
    }

    @Test
    void nestingAddressBookShouldNotBeSupported() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/awesome")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/awesome/v2")
        .then()
            .statusCode(403);
    }

    @Test
    void creatingAddressBookShouldNotBeAllowedOutOfUserRoot() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype>
                        <d:collection/>
                        <card:addressbook/>
                      </d:resourcetype>
                      <d:displayname>AWESOME DISPLAY NAME</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("MKCOL", "/addressbooks/awesome")
        .then()
            .statusCode(403);
    }

    @Test
    void propfindShouldListUserEmptyAddressBooks() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:resourcetype>
                          <d:collection/>
                          <card:addressbook/>
                        </d:resourcetype>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void putShouldWork() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);
    }

    @Test
    void putShouldStripInlinePhoto() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe
                EMAIL:john.doe@example.com
                PHOTO;ENCODING=b;TYPE=JPEG:{BASE64_IMAGE}
                UID:123456789
                END:VCARD
                """
                .replace("{BASE64_IMAGE}", BASE64_IMAGE).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody)
            .doesNotContain("PHOTO")
            .contains("FN:John Doe");
    }

    @Test
    void putShouldStripInlinePhotoSpanningSeveralLines() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe
                EMAIL:john.doe@example.com
                PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQ
                 EBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ
                 EBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).isEqualToNormalizingNewlines("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            EMAIL:john.doe@example.com
            UID:123456789
            END:VCARD
            """);
    }

    @Test
    void putShouldStripEveryInlinePhotoAndKeepTheOneReferencedByUri() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe
                EMAIL:john.doe@example.com
                PHOTO;ENCODING=b;TYPE=JPEG:{BASE64_IMAGE}
                PHOTO;VALUE=URI:https://example.com/avatar.jpg
                PHOTO;VALUE=URI:data:image/jpeg;base64,{BASE64_IMAGE}
                UID:123456789
                END:VCARD
                """
                .replace("{BASE64_IMAGE}", BASE64_IMAGE).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).isEqualToNormalizingNewlines("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            EMAIL:john.doe@example.com
            PHOTO;VALUE=URI:https://example.com/avatar.jpg
            UID:123456789
            END:VCARD
            """);
    }

    @Test
    void putShouldStripDataUriPhoto() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:4.0
                FN:John Doe
                EMAIL:john.doe@example.com
                PHOTO:data:image/jpeg;base64,{BASE64_IMAGE}
                UID:123456789
                END:VCARD
                """
                .replace("{BASE64_IMAGE}", BASE64_IMAGE).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody)
            .doesNotContain("PHOTO")
            .contains("FN:John Doe");
    }

    @Test
    void putShouldKeepPhotoReferencedByUri() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            EMAIL:john.doe@example.com
            PHOTO;VALUE=URI:https://example.com/avatar.jpg
            UID:123456789
            END:VCARD
            """;

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(vcard.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).isEqualTo(vcard);
    }

    @Test
    void putShouldStripInlinePhotoWhenUpdatingAContact() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe-Riga
                EMAIL:john.doe@example.com
                PHOTO;ENCODING=b;TYPE=JPEG:{BASE64_IMAGE}
                UID:123456789
                END:VCARD
                """
                .replace("{BASE64_IMAGE}", BASE64_IMAGE).getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody)
            .doesNotContain("PHOTO")
            .contains("FN:John Doe");
    }

    @Test
    void getShouldSucceed() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).isEqualTo(STRING);
    }

    @Test
    void getShouldReturnNotFoundWhenMissing() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(404);
    }

    @Test
    void putShouldReplace() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe-Riga
                EMAIL:john.doe@example.com
                TEL;TYPE=WORK,VOICE:+1-555-123-4567
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).isEqualTo("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe-Riga
            EMAIL:john.doe@example.com
            TEL;TYPE=WORK,VOICE:+1-555-123-4567
            UID:123456789
            END:VCARD
            """);
    }

    @Test
    void putShouldReturn204WhenExist() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);
    }

    @Test
    void deleteShouldWork() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);
    }

    @Test
    void deleteShouldReturnNotFoundWhenMissing() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(404);
    }

    @Test
    void deleteShouldNotBeReturnedByPropfind() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:resourcetype>
                          <d:collection/>
                          <card:addressbook/>
                        </d:resourcetype>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void putShouldBeListedByPropfind() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).contains("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf");
    }

    @Test
    void putShouldNotBeListedByPropfindWhenWrongDepth() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        AssertionsForInterfaceTypes.assertThat(actual).doesNotContain("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf");
    }

    @Test
    void propfindShouldAllowRetrievingTheDisplayName() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/collected/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    void shouldAllowDuplicatedUid() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/other.vcf")
        .then()
            .statusCode(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contacts", "collected"})
    void proppatchShouldRefuseDisplayNameOnProtectedAddressBook(String addressBook) {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>New Address Book Name</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """)
        .when()
            .request("PROPPATCH", "/addressbooks/" + testUser.id() + "/" + addressBook)
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/{addressBook}</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 403 Forbidden</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id())
                .replace("{addressBook}", addressBook))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @ParameterizedTest
    @ValueSource(strings = {"contacts", "collected"})
    void propfindShouldNotRetrieveRefusedDisplayNameOnProtectedAddressBook(String addressBook) {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>New Address Book Name</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/addressbooks/" + testUser.id() + "/" + addressBook)
        .then()
            .statusCode(207);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/collected/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_GETLASTMODIFIED)
            .areSimilar();
    }

    @Test
    protected void unauthenticatedProppatchShouldNotUpdateDisplayName() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        String originalDisplayName = "Original Address Book Name";
        String hackedDisplayName = "Hacked Address Book Name";

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>%s</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """.formatted(originalDisplayName).getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207);

        String beforeDisplayName = getContactsDisplayName(testUser);

        given()
            .body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>%s</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """.formatted(hackedDisplayName).getBytes(StandardCharsets.UTF_8))
        .when()
            .request("PROPPATCH", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(401);

        String afterDisplayName = getContactsDisplayName(testUser);

        assertThat(afterDisplayName)
            .as("Unauthenticated PROPPATCH must not update contacts display name")
            .isEqualTo(beforeDisplayName);
    }

    private String getContactsDisplayName(OpenPaasUser testUser) throws Exception {
        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="UTF-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        return XMLUtil.extractByXPath(
            responseBody,
            "//d:response[d:href='/addressbooks/" + testUser.id() + "/contacts/']/d:propstat/d:prop/d:displayname",
            Map.of("d", "DAV:"));
    }

    @Test
    void canRecreatePreviouslyDeletedVCards() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);
    }

    @Test
    public void headShouldReturnFound() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .head("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(200);
    }

    @Test
    void headShouldReturnNotFound() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .head("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(404);
    }

    @Test
    void shouldSupportExport() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/addressbooks/" + testUser.id() + "/contacts?export")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(responseBody).isEqualToNormalizingNewlines("""
            BEGIN:VCARD
            VERSION:3.0
            FN:John Doe
            N:Doe;John;;;
            EMAIL:john.doe@example.com
            UID:123456789
            END:VCARD
            """);
    }

    @Test
    void canReportEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);
        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </card:addressbook-query>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:getetag>"b6cfbc684d6173513ed73f413e6b6cb4"</d:getetag>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void putShouldReturnEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201)
            .header("ETag", notNullValue());
    }

    @Test
    void putShouldFailCreateWhenBadEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", "bad")
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(412);
    }

    @Test
    void putShouldFailUpdateWhenBadEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", "bad")
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe-Riga
                EMAIL:john.doe@example.com
                TEL;TYPE=WORK,VOICE:+1-555-123-4567
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(412);
    }

    @Test
    void putShouldSucceedWhenGoodETag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String etag = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201)
            .extract().header("ETag");

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", etag)
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe-Riga
                EMAIL:john.doe@example.com
                TEL;TYPE=WORK,VOICE:+1-555-123-4567
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);
    }

    @Test
    void putShouldChangeEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String etag1 = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201)
            .extract().header("ETag");

        String etag2 = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe-Riga
                EMAIL:john.doe@example.com
                TEL;TYPE=WORK,VOICE:+1-555-123-4567
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204)
            .extract().header("ETag");

        assertThat(etag1).isNotEqualTo(etag2);
    }

    @Test
    void deleteShouldShouldFailWhenBadEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", "bad")
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(412);
    }

    @Test
    void deleteShouldShoulSucceedWhenGoodEtag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String etag = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201)
            .extract().header("ETag");

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("If-Match", etag)
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);
    }

    @Test
    void canReportSyncTokenInitialSync() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
                  <d:prop>
                    <d:sync-token/>
                  </d:prop>
                </d:propfind>
                """)
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "abc", "-1"})
    void syncCollectionWithATokenTheServerNeverIssuedShouldBeRefused(String token) {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        // WHEN the client syncs with a token above the current one, or not a number
        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/%s</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """.formatted(token))
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        // THEN the token is refused rather than answered "nothing changed" or "everything changed"
        .then()
            .statusCode(403)
            .extract().asString();

        assertThat(responseBody).contains("valid-sync-token");
    }

    @Test
    void reportSyncTokenWhenNoUpdate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>
                </d:multistatus>
                """)
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenCreate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:getetag>"b6cfbc684d6173513ed73f413e6b6cb4"</d:getetag>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenDelete() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
        .when()
            .delete("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:status>HTTP/1.1 404 Not Found</d:status>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop/>
                      <d:status>HTTP/1.1 418 I'm a teapot</d:status>
                    </d:propstat>
                  </d:response>
                  <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreChildNodesOrder()
            .ignoreWhitespace()
            .withNodeMatcher(new DefaultNodeMatcher(ElementSelectors.byNameAndAllAttributes))
            .withNodeFilter(node -> !(node.getNodeType() == Node.ELEMENT_NODE && "propstat".equals(node.getLocalName())))
            .areSimilar();
    }

    @Test
    void reportSyncTokenWhenUpdate() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body("""
                BEGIN:VCARD
                VERSION:3.0
                FN:John Doe-Riga
                EMAIL:john.doe@example.com
                TEL;TYPE=WORK,VOICE:+1-555-123-4567
                UID:123456789
                END:VCARD
                """.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(204);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <d:sync-collection xmlns:d="DAV:">
                  <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                </d:sync-collection>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop>
                        <d:getetag>"b2da460cc3c54a9d9b1808fd14c1e8b9"</d:getetag>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                  <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldAllowUidLookup() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:address-data>
                      <card:prop name="UID"/>
                    </card:address-data>
                  </d:prop>
                  <card:filter>
                    <card:prop-filter name="UID">
                      <card:text-match collation="i;unicode-casemap">123456789</card:text-match>
                    </card:prop-filter>
                  </card:filter>
                </card:addressbook-query>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop>
                        <card:address-data>BEGIN:VCARD&#13;
                VERSION:3.0&#13;
                FN:John Doe&#13;
                UID:123456789&#13;
                END:VCARD&#13;
                </card:address-data>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldNotIncludeUnrelatedDataWhenUidLookup() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:address-data>
                      <card:prop name="UID"/>
                    </card:address-data>
                  </d:prop>
                  <card:filter>
                    <card:prop-filter name="UID">
                      <card:text-match collation="i;unicode-casemap">notFound</card:text-match>
                    </card:prop-filter>
                  </card:filter>
                </card:addressbook-query>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav"/>
                """)
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldAllowEmailLookup() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:address-data>
                      <card:prop name="UID"/>
                    </card:address-data>
                  </d:prop>
                  <card:filter>
                    <card:prop-filter name="EMAIL">
                      <card:text-match collation="i;unicode-casemap">john.doe@example.com</card:text-match>
                    </card:prop-filter>
                  </card:filter>
                </card:addressbook-query>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop>
                        <card:address-data>BEGIN:VCARD&#13;
                VERSION:3.0&#13;
                FN:John Doe&#13;
                UID:123456789&#13;
                END:VCARD&#13;
                </card:address-data>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldAllowAutoComplete() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:address-data>
                      <card:prop name="UID"/>
                      <card:prop name="EMAIL"/>
                    </card:address-data>
                  </d:prop>
                  <card:filter>
                    <card:prop-filter name="EMAIL">
                      <card:text-match collation="i;unicode-casemap" match-type="contains">joh</card:text-match>
                    </card:prop-filter>
                  </card:filter>
                </card:addressbook-query>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/addressbooks/{testUserId}/contacts/abcdef.vcf</d:href>
                    <d:propstat>
                      <d:prop>
                        <card:address-data>BEGIN:VCARD&#13;
                VERSION:3.0&#13;
                FN:John Doe&#13;
                EMAIL:john.doe@example.com&#13;
                UID:123456789&#13;
                END:VCARD&#13;
                </card:address-data>
                      </d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """
                .replace("{testUserId}", testUser.id()))
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void reportShouldNotIncludeUnrelatedDataWhenEmailLookup() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put("/addressbooks/" + testUser.id() + "/contacts/abcdef.vcf")
        .then()
            .statusCode(201);

        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:address-data>
                      <card:prop name="UID"/>
                    </card:address-data>
                  </d:prop>
                  <card:filter>
                    <card:prop-filter name="EMAIL">
                      <card:text-match collation="i;unicode-casemap">unrelated@notfound.com</card:text-match>
                    </card:prop-filter>
                  </card:filter>
                </card:addressbook-query>
                """)
        .when()
            .request("REPORT", "/addressbooks/" + testUser.id() + "/contacts")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:card="urn:ietf:params:xml:ns:carddav"/>
                """)
            .ignoreWhitespace()
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(IGNORE_ETAG)
            .areSimilar();
    }

    @Test
    void xmlMkcolWithADisplayNameHoldingElementsShouldBeRejected() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        // WHEN the user creates an address book whose display name holds child elements
        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                      <d:displayname><x:y xmlns:x="urn:x"/></d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>""")
        .when()
            .request("MKCOL", "/addressbooks/" + testUser.id() + "/complexbook")
        // THEN it is refused
        .then()
            .statusCode(400);

        // AND no address book was created
        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "0")
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id() + "/complexbook")
        .then()
            .statusCode(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<d:displayname><x:y xmlns:x=\"urn:x\"/></d:displayname>",
        "<card:addressbook-description><x:y xmlns:x=\"urn:x\"/></card:addressbook-description>"})
    void xmlProppatchWithANameHoldingElementsShouldBeRejected(String property) throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        createXmlAddressBook(testUser, "plainbook");

        // WHEN the user sets a display name or description holding child elements on an address book
        String responseBody = given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .body("""
                <d:propertyupdate xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>%s</d:prop>
                  </d:set>
                </d:propertyupdate>""".formatted(property))
        .when()
            .request("PROPPATCH", "/addressbooks/" + testUser.id() + "/plainbook")
        .then()
            .statusCode(207)
            .extract().asString();

        // THEN the property is refused with a 400 propstat
        assertThat(XMLUtil.extractMultipleValueByXPath(responseBody, "//d:propstat/d:status", Map.of("d", "DAV:")))
            .containsExactly("HTTP/1.1 400 Bad Request");

        // AND the home of the user still lists
        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <d:displayname/>
                    <card:addressbook-description/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/addressbooks/" + testUser.id())
        .then()
            .statusCode(207);
    }

    private void createXmlAddressBook(OpenPaasUser user, String addressBookId) {
        given()
            .header("Authorization", user.impersonatedBasicAuth())
            .body("""
                <d:mkcol xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:set>
                    <d:prop>
                      <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                      <d:displayname>Plain book</d:displayname>
                    </d:prop>
                  </d:set>
                </d:mkcol>""")
        .when()
            .request("MKCOL", "/addressbooks/" + user.id() + "/" + addressBookId)
        .then()
            .statusCode(201);
    }

    @Test
    void addressbookMultigetShouldAnswer404ForAnUnknownHref() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        String addressBook = "/addressbooks/" + testUser.id() + "/contacts";
        putCard(testUser, addressBook + "/abcdef.vcf");

        // WHEN the client multigets an existing card and one that does not exist
        String responseBody = addressbookMultiget(testUser, addressBook, addressBook + "/abcdef.vcf", addressBook + "/missing.vcf");

        // THEN the existing card is returned and the unknown href gets a 404 response
        assertThat(multigetStatuses(responseBody, addressBook + "/abcdef.vcf")).containsExactly("HTTP/1.1 200 OK");
        assertThat(multigetStatuses(responseBody, addressBook + "/missing.vcf")).containsExactly("HTTP/1.1 404 Not Found");
    }

    @Test
    void addressbookMultigetShouldAnswer404ForAnHrefWhoseAddressBookDoesNotExist() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        String addressBook = "/addressbooks/" + testUser.id() + "/contacts";
        putCard(testUser, addressBook + "/abcdef.vcf");
        String deletedBookHref = "/addressbooks/" + testUser.id() + "/no-such-book/abcdef.vcf";

        // WHEN the client multigets an existing card and one of an address book that does not exist
        String responseBody = addressbookMultiget(testUser, addressBook, addressBook + "/abcdef.vcf", deletedBookHref);

        // THEN the report does not fail as a whole: the existing card is returned, the other href gets a 404
        assertThat(multigetStatuses(responseBody, addressBook + "/abcdef.vcf")).containsExactly("HTTP/1.1 200 OK");
        assertThat(multigetStatuses(responseBody, deletedBookHref)).containsExactly("HTTP/1.1 404 Not Found");
    }

    private void putCard(OpenPaasUser user, String href) {
        given()
            .header("Authorization", user.impersonatedBasicAuth())
            .contentType("text/vcard; charset=utf-8")
            .body(STRING.getBytes(StandardCharsets.UTF_8))
        .when()
            .put(href)
        .then()
            .statusCode(201);
    }

    private String addressbookMultiget(OpenPaasUser user, String addressBook, String... hrefs) {
        String hrefElements = Stream.of(hrefs)
            .map(href -> "<d:href>" + href + "</d:href>")
            .collect(Collectors.joining("\n"));

        return given()
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <card:addressbook-multiget xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <d:getetag/>
                  </d:prop>
                  %s
                </card:addressbook-multiget>""".formatted(hrefElements))
        .when()
            .request("REPORT", addressBook)
        .then()
            .statusCode(207)
            .extract().asString();
    }

    private List<String> multigetStatuses(String responseBody, String href) throws Exception {
        return XMLUtil.extractMultipleValueByXPath(responseBody,
            "//d:response[d:href='" + href + "']/d:status | //d:response[d:href='" + href + "']/d:propstat/d:status",
            Map.of("d", "DAV:"));
    }
}
