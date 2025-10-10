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

import static com.linagora.dav.DockerTwakeCalendarSetup.SABRE_V3;

public class DockerTwakeCalendarExtensionV3 extends DockerTwakeCalendarExtension {

    private static final DockerTwakeCalendarSetup dockerTwakeCalendarSetup = new DockerTwakeCalendarSetup(SABRE_V3);

    static {
        dockerTwakeCalendarSetup.start();
    }

    public DockerTwakeCalendarExtensionV3() {

    }

    @Override
    DockerTwakeCalendarSetup setup() {
        return dockerTwakeCalendarSetup;
    }
}