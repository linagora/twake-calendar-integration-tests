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

package com.linagora.dav.sabrev4_7.cal;

import static io.restassured.RestAssured.given;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtensionV4_7;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.contracts.cal.CalendarSharingContract;

import io.restassured.http.ContentType;

public class SabreV4CalendarSharingTest extends CalendarSharingContract {

    @RegisterExtension
    static DockerTwakeCalendarExtensionV4_7 dockerExtension = new DockerTwakeCalendarExtensionV4_7();

    @Override
    public DockerTwakeCalendarExtensionV4_7 extension() {
        return dockerExtension;
    }

    @Test
    void shouldCreateOwnDefaultCalendarWhenDelegatedBeforeFirstListing() {
        // Given a delegated #default calendar before the recipient's first calendar listing (issue #1196).
        OpenPaasUser owner = extension().newTestUser();
        OpenPaasUser recipient = extension().twakeCalendarProvisioningService().createUserWithoutDefaultCalendar().block();
        CalDavClient client = new CalDavClient(extension().davHttpClient());
        client.grantDelegation(owner, owner.id(), recipient, CalDavClient.DelegationRight.READ);

        // When the recipient lists calendars for the first time.
        List<String> hrefs = given()
            .header("Authorization", recipient.impersonatedBasicAuth())
            .accept(ContentType.JSON)
            .queryParam("personal", true)
            .queryParam("sharedDelegationStatus", "accepted")
        .when()
            .get("/calendars/" + recipient.id())
        .then()
            .statusCode(SC_OK)
            .extract().jsonPath().getList("_embedded.'dav:calendar'._links.self.href");

        // Then the personal main calendar exists alongside the delegated calendar.
        String defaultHref = "/calendars/" + recipient.id() + "/" + recipient.id() + ".json";
        assertThat(hrefs).contains(defaultHref).hasSize(2).doesNotHaveDuplicates();
    }
}
