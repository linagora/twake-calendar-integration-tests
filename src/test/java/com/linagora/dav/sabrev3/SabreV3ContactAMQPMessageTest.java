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

package com.linagora.dav.sabrev3;

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtensionV3;
import com.linagora.dav.contracts.ContactAMQPMessageContract;

public class SabreV3ContactAMQPMessageTest extends ContactAMQPMessageContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV3 dockerExtension = new DockerTwakeCalendarExtensionV3();

    @Override
    public DockerTwakeCalendarExtensionV3 dockerExtension() {
        return dockerExtension;
    }
}
