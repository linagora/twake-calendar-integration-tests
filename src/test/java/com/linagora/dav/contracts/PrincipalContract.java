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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xmlunit.assertj3.XmlAssert;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

import io.restassured.RestAssured;

public abstract class PrincipalContract {
    public abstract DockerTwakeCalendarExtension dockerExtension();

    private OpenPaasUser testUser;

    @BeforeEach
    void setUp() {
        RestAssured.reset();
        testUser = dockerExtension().newTestUser();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType("application/xml")
            .header("Authorization", testUser.impersonatedBasicAuth());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    void currentUserPrincipalShouldLinkTheUserPrincipal() {
        String responseBody = given()
            .header("Depth", 0)
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                     <d:current-user-principal />
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/principals/users/" + testUser.id() + "/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void shouldShowDisplayName() {
        String responseBody = given()
            .header("Depth", 0)
            .body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                     <d:displayname/>
                     <d:current-user-principal />
                  </d:prop>
                </d:propfind>""")
        .when()
            .request("PROPFIND", "/")
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/principals/users/" + testUser.id() + "/</d:href></d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void shouldAllowAddressBookDiscovery() {
        String responseBody = given()
            .header("Depth", 0)
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                               <d:prop>
                                  <card:addressbook-home-set />
                               </d:prop>
                             </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/users/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/principals/users/" + testUser.id() + "/</d:href><d:propstat><d:prop><card:addressbook-home-set><d:href>/addressbooks/" + testUser.id() + "/</d:href></card:addressbook-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void shouldAllowCalendarDiscovery() {
        String responseBody = given()
            .header("Depth", 0)
            .body("""
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                                            <d:prop>
                                               <c:calendar-home-set />
                                            </d:prop>
                                          </d:propfind>""")
        .when()
            .request("PROPFIND", "/principals/users/" + testUser.id())
        .then()
            .statusCode(207)
            .extract().asString();

        XmlAssert.assertThat(responseBody)
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/principals/users/" + testUser.id() + "/</d:href><d:propstat><d:prop><cal:calendar-home-set><d:href>/calendars/" + testUser.id() + "/</d:href></cal:calendar-home-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }
}
