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

package com.linagora.dav.sabrev4;

import static com.linagora.dav.DockerTwakeCalendarSetup.SABRE_V4;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.contracts.IMIPCallBackContract;

public class SabreV4IMIPCallBackTest extends IMIPCallBackContract {

    static class DockerExtension extends DockerTwakeCalendarExtension implements BeforeAllCallback, AfterAllCallback {
        static final DockerTwakeCalendarSetup dockerTwakeCalendarSetup = new DockerTwakeCalendarSetup(SABRE_V4, true);

        @Override
        protected DockerTwakeCalendarSetup setup() {
            return dockerTwakeCalendarSetup;
        }

        @Override
        public void beforeAll(ExtensionContext extensionContext) {
            dockerTwakeCalendarSetup.start();
        }

        @Override
        public void afterAll(ExtensionContext extensionContext) {
            dockerTwakeCalendarSetup.stop();
        }
    }

    @RegisterExtension
    static DockerTwakeCalendarExtension dockerExtension = new DockerExtension();

    @Override
    public DockerTwakeCalendarExtension extension() {
        return dockerExtension;
    }
}
