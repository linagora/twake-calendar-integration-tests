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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.contracts.CardDavDelegationContract;

public class SabreV4CardDavDelegationTest extends CardDavDelegationContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void canCreateNewContactDirectlyInCopiedAddressBook() {
        super.canCreateNewContactDirectlyInCopiedAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook() {
        super.createNewContactInCopiedAddressBookShouldResultInNewContactInOriginalAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook() {
        super.updateContactInCopiedAddressBookShouldResultInUpdatedContactInOriginalAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook() {
        super.deleteContactInCopiedAddressBookShouldResultInDeletedContactInOriginalAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook() {
        super.copiedAddressBookShouldContainsExistingContactsInOriginalAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void createNewContactInOriginalAddressBookShouldResultInNewContactInCopiedAddressBook() {
        super.createNewContactInOriginalAddressBookShouldResultInNewContactInCopiedAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void updateContactInOriginalAddressBookShouldResultInUpdatedContactInCopiedAddressBook() {
        super.updateContactInOriginalAddressBookShouldResultInUpdatedContactInCopiedAddressBook();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void deleteContactInOriginalAddressBookShouldResultInDeletedContactInCopiedAddressBook() {
        super.deleteContactInOriginalAddressBookShouldResultInDeletedContactInCopiedAddressBook();
    }
}
