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

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void deleteShouldFailWhenRightsAreRemoved() {
        super.deleteShouldFailWhenRightsAreRemoved();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void createShouldFailWhenRightsAreRemoved() {
        super.createShouldFailWhenRightsAreRemoved();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void getShouldFailWhenRightsAreRemoved() {
        super.getShouldFailWhenRightsAreRemoved();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void getShouldFailWhenRightsAreRemovedWhenHasPublicRight() {
        super.getShouldFailWhenRightsAreRemovedWhenHasPublicRight();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void davShouldListDelegatedAddressBooks() throws Exception {
        super.davShouldListDelegatedAddressBooks();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void shouldNotShowMeaningfullDisplayName() throws Exception {
        super.shouldNotShowMeaningfullDisplayName();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void getShouldFailWhenNotDelegationTarget() {
        super.getShouldFailWhenNotDelegationTarget();
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/60")
    @Override
    public void createShouldFailWhenNotDelegationTarget() {
        super.createShouldFailWhenNotDelegationTarget();
    }

    @Test
    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveContactFromOwnAddressBookToDelegatedAddressBookShouldSucceedWithWriteRight() {
        super.moveContactFromOwnAddressBookToDelegatedAddressBookShouldSucceedWithWriteRight();
    }

    @Test
    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveContactFromDelegatedAddressBookToOwnAddressBookShouldSucceedWithWriteRight() {
        super.moveContactFromDelegatedAddressBookToOwnAddressBookShouldSucceedWithWriteRight();
    }

    @Test
    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveContactFromOwnAddressBookToDelegatedAddressBookShouldFailWithReadOnlyRight() {
        super.moveContactFromOwnAddressBookToDelegatedAddressBookShouldFailWithReadOnlyRight();
    }

    @Test
    @Disabled("Issue https://github.com/linagora/esn-sabre/issues/273")
    @Override
    public void moveContactFromDelegatedAddressBookToOwnAddressBookShouldFailWithReadOnlyRight() {
        super.moveContactFromDelegatedAddressBookToOwnAddressBookShouldFailWithReadOnlyRight();
    }
}
