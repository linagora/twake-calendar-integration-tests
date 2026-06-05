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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.contracts.cal.CalJsonContract;

public class SabreV4CalJsonTest extends CalJsonContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Disabled
    @Override
    public void reportShouldSanitizePrivateEvents() {
        super.reportShouldSanitizePrivateEvents();
    }

    @Disabled("Supported only on Sabre 4.7")
    @Override
    public void reportShouldPreservePrivateRecurringEventTimingWhenSanitizing() throws JsonProcessingException {
        super.reportShouldPreservePrivateRecurringEventTimingWhenSanitizing();
    }

    @Disabled("Supported only on Sabre 4.7")
    @Override
    public void reportShouldPreservePrivateRecurringEventRDATETimingWhenSanitizing() throws JsonProcessingException {
        super.reportShouldPreservePrivateRecurringEventRDATETimingWhenSanitizing();
    }

    @Disabled("Supported only on Sabre 4.7")
    @Override
    public void reportShouldPreservePrivateRecurringEventOverrideTimingWhenSanitizing() throws JsonProcessingException {
        super.reportShouldPreservePrivateRecurringEventOverrideTimingWhenSanitizing();
    }
}
