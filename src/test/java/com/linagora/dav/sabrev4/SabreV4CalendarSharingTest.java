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


import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.contracts.CalendarSharingContract;
import com.linagora.dav.DockerTwakeCalendarExtensionV4;

public class SabreV4CalendarSharingTest extends CalendarSharingContract {

    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 extension() {
        return dockerExtension;
    }

    @Disabled("ISSUE-52")
    @Override
    public void cannotSubscribeToPrivateCalendar() {

    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void publicSubscriptionsCanContainVEvent() {
        super.publicSubscriptionsCanContainVEvent();
    }


    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void publicSubscriptionsAreReadableInDav() throws Exception {
        super.publicSubscriptionsAreReadableInDav();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void readRightsCanBeRevoked() {
        super.readRightsCanBeRevoked();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void nativeUpsertShouldBeRejectedOnReadPublicCalender() {
        super.nativeUpsertShouldBeRejectedOnReadPublicCalender();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void nativeUpsertShouldBeAcceptedOnWritePublicCalender() {
        super.nativeUpsertShouldBeAcceptedOnWritePublicCalender();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void nativeUpsertShouldBeReplicatedForWritePublicCalender() {
        super.nativeUpsertShouldBeReplicatedForWritePublicCalender();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void nativeDeleteShouldBeRejectedOnReadPublicCalender() {
        super.nativeDeleteShouldBeRejectedOnReadPublicCalender();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void nativeDeleteShouldBeAcceptedOnWritePublicCalender() {
        super.nativeDeleteShouldBeAcceptedOnWritePublicCalender();
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/61")
    @Override
    public void nativeDeleteShouldBeReplicatedForWritePublicCalender() {
        super.nativeDeleteShouldBeReplicatedForWritePublicCalender();
    }
}
