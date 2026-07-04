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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;

public abstract class TeamCalendarMultitenancyContract {
    private static final String SECOND_DOMAIN = "second-domain.org";
    private static final Map<String, String> DAV_NAMESPACES = Map.of("d", "DAV:");

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http")
                .toString())
            .build();
    }

    @Test
    void propfindOnCrossDomainTeamCalendarPrincipalShouldReturn403() {
        // Given Bob is in the default domain and a team calendar exists in a second domain
        OpenPaasUser bob = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar crossDomainTeamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("legal-" + UUID.randomUUID(), "Legal Team", SECOND_DOMAIN)
            .block();

        // When Bob requests the cross-domain team calendar principal
        Response response = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/team-calendars/" + crossDomainTeamCalendar.id())
        .then()
            .extract()
            .response();

        // Then cross-domain access is rejected
        assertThat(response.statusCode())
            .as("Cross-domain team calendar principal PROPFIND should be rejected")
            .isEqualTo(403);
    }

    @Test
    void technicalTokenShouldNotManageCrossDomainTeamCalendarSharing() {
        // Given Alice and a technical token are in the default domain, while the team calendar is not
        OpenPaasUser alice = dockerExtension().newTestUser();
        OpenPaaSTeamCalendar crossDomainTeamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("security-" + UUID.randomUUID(), "Security Team", SECOND_DOMAIN)
            .block();
        String defaultDomainTechnicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // When/Then the default-domain technical token cannot share the cross-domain team calendar
        assertThatThrownBy(() -> calDavClient.grantDelegation(crossDomainTeamCalendar.id(), alice, DelegationRight.READ, defaultDomainTechnicalToken))
            .as("Default-domain technical token should not share a cross-domain team calendar")
            .hasMessageContaining("Unexpected status code: 403 when sharing calendar");
    }

    @Test
    void technicalTokenShouldNotShareTeamCalendarWithCrossDomainUser() {
        // Given a team calendar and a technical token are in the default domain, while Alice is not
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("security-" + UUID.randomUUID(), "Security Team")
            .block();
        OpenPaasUser crossDomainAlice = dockerExtension().twakeCalendarProvisioningService()
            .createUser("alice-" + UUID.randomUUID(), SECOND_DOMAIN)
            .block();
        String defaultDomainTechnicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // When/Then the default-domain technical token cannot share the team calendar with the cross-domain user
        assertThatThrownBy(() -> calDavClient.grantDelegation(teamCalendar.id(), crossDomainAlice, DelegationRight.READ, defaultDomainTechnicalToken))
            .as("Default-domain technical token should not share a team calendar with a cross-domain user")
            .hasMessageContaining("Unexpected status code: 403 when sharing calendar");
    }

    private record PrincipalSearch(String property, String match) {
    }
}
