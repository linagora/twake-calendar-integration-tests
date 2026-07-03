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
import org.xmlunit.assertj3.XmlAssert;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaaSTeamCalendar;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.response.Response;

public abstract class TeamCalendarContract {
    private static final Map<String, String> DAV_NAMESPACES = Map.of(
        "d", "DAV:",
        "s", "http://sabredav.org/ns",
        "cal", "urn:ietf:params:xml:ns:caldav");

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerService.SABRE_DAV, "http")
                .toString())
            .build();
    }

    @Test
    void currentUserPrincipalShouldLinkTheTeamCalendarPrincipal() {
        // Given a team calendar exists in the default domain
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("sales-" + UUID.randomUUID(), "Sales Team")
            .block();

        // When the team calendar is authenticated through admin impersonation
        Response response = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/")
        .then()
            .extract()
            .response();

        // Then the current user principal points to the team calendar namespace
        assertThat(response.statusCode())
            .as("Team calendar admin impersonation should expose current-user-principal")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:current-user-principal/d:href")
            .isEqualTo(teamCalendar.principalHref());
    }

    @Test
    void teamCalendarPrincipalShouldExposeDisplayName() {
        // Given a team calendar exists in the default domain
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("support-" + UUID.randomUUID(), "Support Team")
            .block();

        // When its DAV principal properties are requested
        Response response = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/team-calendars/" + teamCalendar.id())
        .then()
            .extract()
            .response();

        // Then the principal exposes the team calendar display name
        assertThat(response.statusCode())
            .as("Team calendar principal PROPFIND should return display name")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname")
            .isEqualTo(teamCalendar.displayName());
    }

    @Test
    void teamCalendarPrincipalShouldResolveCalendarHome() {
        // Given a team calendar exists in the default domain
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("engineering-" + UUID.randomUUID(), "Engineering Team")
            .block();

        // When its CalDAV calendar home is discovered
        Response discoveryResponse = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop>
                    <c:calendar-home-set/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/team-calendars/" + teamCalendar.id())
        .then()
            .extract()
            .response();

        // Then the calendar home-set points to the team calendar home
        assertThat(discoveryResponse.statusCode())
            .as("Team calendar principal PROPFIND should resolve calendar-home-set")
            .isEqualTo(207);
        XmlAssert.assertThat(discoveryResponse.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//cal:calendar-home-set/d:href")
            .isEqualTo("/calendars/" + teamCalendar.id() + "/");
    }

    @Test
    void teamCalendarDefaultCalendarShouldBeAvailableOnFirstAccess() {
        // Given a team calendar exists
        OpenPaaSTeamCalendar teamCalendar = dockerExtension().twakeCalendarProvisioningService()
            .createTeamCalendar("product-" + UUID.randomUUID(), "Product Team")
            .block();

        // When its default calendar is accessed for the first time
        Response response = given()
            .header("Authorization", OpenPaasUser.impersonatedBasicAuth(teamCalendar.email()))
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/calendars/" + teamCalendar.id() + "/" + teamCalendar.id())
        .then()
            .extract()
            .response();

        // Then the first request succeeds without requiring lazy provisioning to be retried
        assertThat(response.statusCode())
            .as("First access to team calendar default calendar should trigger lazy provisioning successfully")
            .isEqualTo(207);
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .nodesByXPath("//d:multistatus/d:response/d:href")
            .extractingText()
            .contains("/calendars/" + teamCalendar.id() + "/" + teamCalendar.id() + "/");
        XmlAssert.assertThat(response.body().asString())
            .withNamespaceContext(DAV_NAMESPACES)
            .valueByXPath("//d:response[d:href='/calendars/%s/%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                .formatted(teamCalendar.id(), teamCalendar.id()))
            .isEqualTo(teamCalendar.displayName());
    }

}
