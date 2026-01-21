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

import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.contracts.CalDavDelegationContract;

public class SabreV4CalDavDelegationTest extends CalDavDelegationContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/195")
    @Override
    public void shouldPropagateToOrganizerWhenResourceAdminUpdatePartStat() {
        super.shouldPropagateToOrganizerWhenResourceAdminUpdatePartStat();
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/55")
    @Override
    public void updateCalendarAclShouldThrowErrorWhenDelegatedUserOnlyHasReadWriteRight() {
        super.updateCalendarAclShouldThrowErrorWhenDelegatedUserOnlyHasReadWriteRight();
    }
}
