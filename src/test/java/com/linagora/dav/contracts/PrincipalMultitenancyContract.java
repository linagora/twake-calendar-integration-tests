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

package com.linagora.dav.contracts;

import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.XMLUtil;

import io.restassured.RestAssured;

public abstract class PrincipalMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaasUser bob;
    private OpenPaasUser alice;
    private OpenPaasUser john;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        bob = dockerExtension().newTestUser();
        alice = dockerExtension().newTestUser();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/xml");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void principalPropertySearchShouldRespectDomainIsolation() throws Exception {
        // Given Bob is authenticated in the default domain, Alice is in the same domain, and John is in another domain
        record PrincipalSearch(String property, String match) { }

        Function<PrincipalSearch, String> principalPropertySearchBody = search -> """
                <d:principal-property-search xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:property-search>
                    <d:prop>
                      %s
                    </d:prop>
                    <d:match>%s</d:match>
                  </d:property-search>
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:principal-property-search>""".formatted(search.property(), search.match());

        // When Bob searches users principals by Alice's same-domain email
        String aliceEmailSearchResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body(principalPropertySearchBody.apply(new PrincipalSearch("<s:email-address/>", alice.email())))
        .when()
            .request("REPORT", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then Alice's principal URI is visible
        List<String> aliceEmailSearchHrefs = extractPrincipalHrefs(aliceEmailSearchResponseBody);
        assertThat(aliceEmailSearchHrefs).anySatisfy(href -> assertThat(href).contains(alice.id()));

        // When Bob searches users principals by John's cross-domain email
        String johnEmailSearchResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body(principalPropertySearchBody.apply(new PrincipalSearch("<s:email-address/>", john.email())))
        .when()
            .request("REPORT", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then John's principal URI is not leaked
        List<String> johnEmailSearchHrefs = extractPrincipalHrefs(johnEmailSearchResponseBody);
        assertThat(johnEmailSearchHrefs)
            .as("Bob must not discover John's cross-domain principal by email")
            .isEmpty();

        // When Bob searches users principals by John's cross-domain display name
        String johnDisplayNameSearchResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body(principalPropertySearchBody.apply(new PrincipalSearch("<d:displayname/>", john.firstname())))
        .when()
            .request("REPORT", "/principals/users")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then John's principal URI is not leaked
        List<String> johnDisplayNameSearchHrefs = extractPrincipalHrefs(johnDisplayNameSearchResponseBody);
        assertThat(johnDisplayNameSearchHrefs)
            .as("Bob must not discover John's cross-domain principal by display name")
            .isEmpty();
    }

    @Test
    void resourcePrincipalSearchShouldNotEnumerateCrossDomainResources() throws Exception {
        // Given Bob is authenticated in the default domain and a resource exists in another domain
        OpenPaaSResource secondDomainResource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("second-domain-room-" + UUID.randomUUID(), "Second domain room", john, SECOND_DOMAIN)
            .block();

        // When Bob searches resource principals by the exact cross-domain resource email address
        String responseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:principal-property-search xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:property-search>
                    <d:prop>
                      <s:email-address/>
                    </d:prop>
                    <d:match>%s</d:match>
                  </d:property-search>
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:principal-property-search>""".formatted(secondDomainResource.id() + "@" + SECOND_DOMAIN))
        .when()
            .request("REPORT", "/principals/resources")
        .then()
            .statusCode(207)
            .extract().asString();

        // Then the cross-domain resource principal must not be disclosed
        assertThat(extractPrincipalHrefs(responseBody))
            .as("Bob must not discover a cross-domain resource principal by email")
            .noneSatisfy(href -> assertThat(href).contains("/principals/resources/" + secondDomainResource.id()));
        assertThat(responseBody)
            .doesNotContain(secondDomainResource.id())
            .doesNotContain(SECOND_DOMAIN);
    }

    @Test
    void propfindCrossDomainResourcePrincipalShouldNotExposeResource() throws Exception {
        // Given Bob is authenticated in the default domain and a resource principal exists in another domain
        String resourceName = "second-domain-room-" + UUID.randomUUID();
        OpenPaaSResource secondDomainResource = dockerExtension().twakeCalendarProvisioningService()
            .createResource(resourceName, "Second domain room", john, SECOND_DOMAIN)
            .block();
        String resourceEmail = secondDomainResource.id() + "@" + SECOND_DOMAIN;

        // When Bob directly PROPFINDs the cross-domain resource principal URI
        String responseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns">
                  <d:prop>
                    <d:displayname/>
                    <s:email-address/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/resources/" + secondDomainResource.id())
        .then()
            .extract().asString();

        // Then cross-domain resource principal data is not disclosed
        assertThat(responseBody)
            .doesNotContain(resourceName)
            .doesNotContain(resourceEmail);
    }

    @Test
    void jwtAuthenticatedBobCannotPropfindCrossDomainPrincipal() {
        // Given Bob is authenticated with a JWT token
        String jwt = generateJwtFor(bob);
        String principalPropfindBody = """
                    <d:propfind xmlns:d="DAV:">
                      <d:prop>
                        <d:current-user-principal/>
                      </d:prop>
                    </d:propfind>""";

        // When Bob does PROPFIND on his own principal
        given()
            .header("Authorization", "Bearer " + jwt)
            .header("Depth", "0")
            .body(principalPropfindBody)
        .when()
            .request("PROPFIND", "/principals/users/" + bob.id())
        // Then JWT authentication succeeds
        .then()
            .statusCode(207);

        // When Bob does PROPFIND on John's cross-domain principal
        given()
            .header("Authorization", "Bearer " + jwt)
            .header("Depth", "0")
            .body(principalPropfindBody)
        .when()
            .request("PROPFIND", "/principals/users/" + john.id())
        // Then cross-domain principal access is rejected
        .then()
            .statusCode(anyOf(is(403), is(404)));
    }

    @Test
    void propfindOnCrossDomainPrincipalShouldReturn403() {
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/users/" + john.id())
        .then()
            .statusCode(403);
    }

    @Test
    void calendarHomeSetDiscoveryShouldReturn403ForCrossDomainPrincipal() {
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop>
                    <c:calendar-home-set/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/users/" + john.id())
        .then()
            .statusCode(403);
    }

    @Test
    void addressBookHomeSetDiscoveryShouldReturn403ForCrossDomainPrincipal() {
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:addressbook-home-set/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/users/" + john.id())
        .then()
            .statusCode(403);
    }

    private String generateJwtFor(OpenPaasUser user) {
        String quotedJwt = given()
            .baseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE, "http")
                .toString())
            .auth().preemptive().basic(user.email(), user.password())
            .when()
            .post("/api/jwt/generate")
        .then()
            .statusCode(SC_OK)
            .extract().asString();

        return quotedJwt.substring(1, quotedJwt.length() - 1);
    }

    private List<String> extractPrincipalHrefs(String responseBody) throws Exception {
        return XMLUtil.extractMultipleValueByXPath(responseBody, "//d:multistatus/d:response/d:href", Map.of("d", "DAV:"));
    }

}
