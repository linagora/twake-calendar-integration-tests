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
import com.linagora.dav.contracts.CardDavSharingContract;

public class SabreV4CardDavSharingTest extends CardDavSharingContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Disabled("ISSUE-111")
    @Override
    public void subscribeShouldThrowErrorWhenAddressBookPublicRightIsHidden() {
        super.subscribeShouldThrowErrorWhenAddressBookPublicRightIsHidden();
    }

    @Disabled("ISSUE-111")
    @Override
    public void thridPartyShouldNotReadDelegation() {
        super.thridPartyShouldNotReadDelegation();
    }

    @Disabled("ISSUE-111")
    @Override
    public void thridPartyShouldNotWriteDelegation() {
        super.thridPartyShouldNotWriteDelegation();
    }

    @Disabled("ISSUE-111")
    @Override
    public void subscribeShouldSucceedWhenAddressBookIsPubliclyReadable() {
        super.subscribeShouldSucceedWhenAddressBookIsPubliclyReadable();
    }

    @Disabled("ISSUE-111")
    @Override
    public void subscribeShouldSucceedWhenAddressBookIsPubliclyWritable() {
        super.subscribeShouldSucceedWhenAddressBookIsPubliclyWritable();
    }

    @Disabled("ISSUE-111")
    @Override
    public void copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook() {
        super.copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook();
    }

    @Disabled("ISSUE-111")
    @Override
    public void canCreateNewContactInCopiedAddressBook() {
        super.canCreateNewContactInCopiedAddressBook();
    }

    @Disabled("ISSUE-111")
    @Override
    public void cannotDeleteContactInCopiedAddressBookWhenNotAuthorized() {
        super.cannotDeleteContactInCopiedAddressBookWhenNotAuthorized();
    }

    @Disabled("ISSUE-111")
    @Override
    public void createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
        super.createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook();
    }

    @Disabled("ISSUE-111")
    @Override
    public void updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook() {
        super.updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook();
    }

    @Disabled("ISSUE-111")
    @Override
    public void deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook() {
        super.deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook();
    }

    @Disabled("ISSUE-111")
    @Override
    public void publicSubscriptionsCanGetContactViaNativeCardDAV() throws Exception {
        super.publicSubscriptionsCanGetContactViaNativeCardDAV();
    }
}
