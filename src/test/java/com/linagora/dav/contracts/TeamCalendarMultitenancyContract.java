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

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
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


    private record PrincipalSearch(String property, String match) {
    }
}
