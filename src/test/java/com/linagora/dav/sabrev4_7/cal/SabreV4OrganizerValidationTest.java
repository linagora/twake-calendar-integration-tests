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

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarExtensionV4_7;
import com.linagora.dav.contracts.cal.OrganizerValidationContract;

public class SabreV4OrganizerValidationTest extends OrganizerValidationContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4_7 dockerExtension = DockerTwakeCalendarExtensionV4_7.withOrganizerValidation();

    @Override
    public DockerTwakeCalendarExtension dockerExtension() {
        return dockerExtension;
    }
}
