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

package com.linagora.dav.sabrev4.cal;

import static com.linagora.dav.TestUtil.TWAKE_CALENDAR_TOKEN_HEADER;

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.contracts.cal.ResourceAMQPMessageContract;

public class SabreV4ResourceAMQPMessageTest extends ResourceAMQPMessageContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Override
    protected void afterResourceSetup(OpenPaaSResource resource) {
        // Sabre 4.1.5 lazily provisions the technical-token principal on the first DAV request.
        dockerExtension().davHttpClient()
            .headers(headers -> headers
                .add(TWAKE_CALENDAR_TOKEN_HEADER, dockerExtension().twakeCalendarProvisioningService().generateToken())
                .add("Accept", "application/json"))
            .get()
            .uri("/calendars/" + resource.id() + ".json")
            .responseSingle((response, responseContent) -> responseContent.asString().then())
            .block();
    }
}
