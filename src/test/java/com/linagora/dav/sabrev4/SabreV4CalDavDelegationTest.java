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

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/256")
    @Override
    public void privateOrConfidentialEventShouldBeAnonymizedInDavReport(String eventClass) throws Exception {
        super.privateOrConfidentialEventShouldBeAnonymizedInDavReport(eventClass);
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/256")
    @Override
    public void privateOrConfidentialEventShouldBeAnonymizedInDavGet(String eventClass) {
        super.privateOrConfidentialEventShouldBeAnonymizedInDavGet(eventClass);
    }

    @Disabled("Fixed in Sabre 4_7 (com.linagora.dav.sabrev4_7.SabreV4CalDavDelegationTest)")
    @Override
    public void amqpShouldPublishDelegationUpdatedForSourceCalendarOnGrant() throws Exception {
        super.amqpShouldPublishDelegationUpdatedForSourceCalendarOnGrant();
    }

    @Disabled("Fixed in Sabre 4_7 (com.linagora.dav.sabrev4_7.SabreV4CalDavDelegationTest)")
    @Override
    public void amqpShouldPublishDelegationUpdatedForSourceCalendarOnUpdateRight() throws Exception {
        super.amqpShouldPublishDelegationUpdatedForSourceCalendarOnUpdateRight();
    }

    @Disabled("Fixed in Sabre 4_7 (com.linagora.dav.sabrev4_7.SabreV4CalDavDelegationTest)")
    @Override
    public void amqpShouldPublishDelegationUpdatedForSourceCalendarOnRevoke() throws Exception {
        super.amqpShouldPublishDelegationUpdatedForSourceCalendarOnRevoke();
    }

    @Disabled("Fixed in Sabre 4_7 (com.linagora.dav.sabrev4_7.SabreV4CalDavDelegationTest)")
    @Override
    public void amqpShouldDedupDelegationUpdatedForSameSourceCalendarInSingleRequest() throws Exception {
        super.amqpShouldDedupDelegationUpdatedForSameSourceCalendarInSingleRequest();
    }

    @Disabled("Fixed in Sabre 4_7 (com.linagora.dav.sabrev4_7.SabreV4CalDavDelegationTest)")
    @Override
    public void amqpShouldDedupDelegationUpdatedForSameSourceCalendarOnBulkRevoke() throws Exception {
        super.amqpShouldDedupDelegationUpdatedForSameSourceCalendarOnBulkRevoke();
    }

    @Disabled("Fixed in Sabre 4_7 (com.linagora.dav.sabrev4_7.SabreV4CalDavDelegationTest)")
    @Override
    public void amqpShouldPublishDelegationUpdatedWhenRevokeOneOfTwoSharees() throws Exception {
        super.amqpShouldPublishDelegationUpdatedWhenRevokeOneOfTwoSharees();
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveEventFromOwnCalendarToDelegatedCalendarShouldSucceedWithWriteRight() throws Exception {
        super.moveEventFromOwnCalendarToDelegatedCalendarShouldSucceedWithWriteRight();
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveEventFromDelegatedCalendarToOwnCalendarShouldSucceedWithWriteRight() throws Exception {
        super.moveEventFromDelegatedCalendarToOwnCalendarShouldSucceedWithWriteRight();
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveEventFromOwnCalendarToDelegatedCalendarShouldFailWithReadOnlyRight() {
        super.moveEventFromOwnCalendarToDelegatedCalendarShouldFailWithReadOnlyRight();
    }

    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveEventFromDelegatedCalendarToOwnCalendarShouldFailWithReadOnlyRight() {
        super.moveEventFromDelegatedCalendarToOwnCalendarShouldFailWithReadOnlyRight();
    }
}
