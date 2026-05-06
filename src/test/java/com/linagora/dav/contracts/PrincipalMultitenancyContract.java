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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.XMLUtil;

import io.netty.handler.codec.http.HttpMethod;

public abstract class PrincipalMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaasUser bob;
    private OpenPaasUser alice;
    private OpenPaasUser john;

    @BeforeEach
    void setUp() {
        dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        bob = dockerExtension().newTestUser();
        alice = dockerExtension().newTestUser();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
    }

    @Disabled("https://github.com/linagora/twake-calendar-integration-tests/issues/209")
    @Test
    void principalPropertySearchShouldRespectDomainIsolation() throws Exception {
        // Given Bob is authenticated in the default domain, Alice is in the same domain, and John is in another domain
        record PrincipalSearch(String property, String match) { }

        Function<PrincipalSearch, DavResponse> principalPropertySearch = search -> execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Depth", "0")
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/principals/users")
            .send(body("""
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
                </d:principal-property-search>""".formatted(search.property(), search.match()))));

        // When Bob searches users principals by Alice's same-domain email
        DavResponse aliceEmailSearchResponse = principalPropertySearch.apply(new PrincipalSearch("<s:email-address/>", alice.email()));

        // Then Alice's principal URI is visible
        assertThat(aliceEmailSearchResponse.status()).isEqualTo(207);
        List<String> aliceEmailSearchHrefs = extractPrincipalHrefs(aliceEmailSearchResponse);
        assertThat(aliceEmailSearchHrefs).anySatisfy(href -> assertThat(href).contains(alice.id()));

        // When Bob searches users principals by John's cross-domain email
        DavResponse johnEmailSearchResponse = principalPropertySearch.apply(new PrincipalSearch("<s:email-address/>", john.email()));

        // Then John's principal URI is not leaked
        assertThat(johnEmailSearchResponse.status()).isEqualTo(207);
        List<String> johnEmailSearchHrefs = extractPrincipalHrefs(johnEmailSearchResponse);
        assertThat(johnEmailSearchHrefs)
            .as("Bob must not discover John's cross-domain principal by email")
            .isEmpty();

        // When Bob searches users principals by John's cross-domain display name
        DavResponse johnDisplayNameSearchResponse = principalPropertySearch.apply(new PrincipalSearch("<d:displayname/>", john.firstname()));

        // Then John's principal URI is not leaked
        assertThat(johnDisplayNameSearchResponse.status()).isEqualTo(207);
        List<String> johnDisplayNameSearchHrefs = extractPrincipalHrefs(johnDisplayNameSearchResponse);
        assertThat(johnDisplayNameSearchHrefs)
            .as("Bob must not discover John's cross-domain principal by display name")
            .isEmpty();
    }

    @Disabled("https://github.com/linagora/twake-calendar-integration-tests/issues/209")
    @Test
    void jwtAuthenticatedBobCannotPropfindCrossDomainPrincipal() {
        // Given Bob is authenticated with a JWT token
        String jwt = generateJwtFor(bob);
        Function<OpenPaasUser, DavResponse> propfindPrincipal = user -> execute(dockerExtension().davHttpClient()
                .headers(headers -> headers.add("Authorization", "Bearer " + jwt)
                    .add("Depth", "0")
                    .add("Content-Type", "application/xml"))
                .request(HttpMethod.valueOf("PROPFIND"))
                .uri("/principals/users/" + user.id())
                .send(body("""
                    <d:propfind xmlns:d="DAV:">
                      <d:prop>
                        <d:current-user-principal/>
                      </d:prop>
                    </d:propfind>""")));

        // When Bob does PROPFIND on his own principal
        DavResponse bobPrincipalResponse = propfindPrincipal.apply(bob);

        // Then JWT authentication succeeds
        assertThat(bobPrincipalResponse.status()).isEqualTo(207);

        // When Bob does PROPFIND on John's cross-domain principal
        DavResponse johnPrincipalResponse = propfindPrincipal.apply(john);

        // Then cross-domain principal access is rejected
        assertThat(johnPrincipalResponse.status()).isIn(403, 404);
    }

    @Disabled("https://github.com/linagora/twake-calendar-integration-tests/issues/209")
    @Test
    void propfindOnCrossDomainPrincipalShouldReturn403() {
        int status = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Depth", "0")
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/principals/users/" + john.id())
            .send(body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>"""))).status();

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-integration-tests/issues/209")
    @Test
    void calendarHomeSetDiscoveryShouldReturn403ForCrossDomainPrincipal() {
        int status = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Depth", "0")
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/principals/users/" + john.id())
            .send(body("""
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop>
                    <c:calendar-home-set/>
                  </d:prop>
                </d:propfind>"""))).status();

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-integration-tests/issues/209")
    @Test
    void addressBookHomeSetDiscoveryShouldReturn403ForCrossDomainPrincipal() {
        int status = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Depth", "0")
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/principals/users/" + john.id())
            .send(body("""
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:prop>
                    <card:addressbook-home-set/>
                  </d:prop>
                </d:propfind>"""))).status();

        assertThat(status).isEqualTo(403);
    }

    private String generateJwtFor(OpenPaasUser user) {
        String quotedJwt = given().log().all()
            .baseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE, "http")
                .toString())
            .auth().preemptive().basic(user.email(), user.password())
            .when()
            .post("/api/jwt/generate").prettyPeek()
        .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        return quotedJwt.substring(1, quotedJwt.length() - 1);
    }

    private List<String> extractPrincipalHrefs(DavResponse response) throws Exception {
        return XMLUtil.extractMultipleValueByXPath(response.body(), "//d:multistatus/d:response/d:href", Map.of("d", "DAV:"));
    }

}
