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

package com.linagora.dav;

import static com.linagora.dav.DockerTwakeCalendarSetup.SABRE_V4_7;

public class DockerTwakeCalendarExtensionV4_7 extends DockerTwakeCalendarExtension {

    private static final boolean PRINCIPAL_PRIVACY_DISABLED = false;
    private static final boolean PRINCIPAL_PRIVACY_ENABLED = true;

    private static DockerTwakeCalendarSetup defaultDockerTwakeCalendarSetup;

    private final DockerTwakeCalendarSetup dockerTwakeCalendarSetup;

    public DockerTwakeCalendarExtensionV4_7() {
        this(PRINCIPAL_PRIVACY_DISABLED);
    }

    public DockerTwakeCalendarExtensionV4_7(boolean principalPrivacy) {
        if (principalPrivacy) {
            dockerTwakeCalendarSetup = new DockerTwakeCalendarSetup(SABRE_V4_7, PRINCIPAL_PRIVACY_ENABLED);
            dockerTwakeCalendarSetup.start();
        } else {
            dockerTwakeCalendarSetup = defaultDockerTwakeCalendarSetup();
        }
    }

    public static DockerTwakeCalendarExtensionV4_7 withPrincipalPrivacy() {
        return new DockerTwakeCalendarExtensionV4_7(PRINCIPAL_PRIVACY_ENABLED);
    }

    @Override
    DockerTwakeCalendarSetup setup() {
        return dockerTwakeCalendarSetup;
    }

    private static synchronized DockerTwakeCalendarSetup defaultDockerTwakeCalendarSetup() {
        if (defaultDockerTwakeCalendarSetup == null) {
            defaultDockerTwakeCalendarSetup = new DockerTwakeCalendarSetup(SABRE_V4_7);
            defaultDockerTwakeCalendarSetup.start();
        }
        return defaultDockerTwakeCalendarSetup;
    }
}