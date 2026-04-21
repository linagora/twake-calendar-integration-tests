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

package com.linagora.dav.sabrev4.card;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.VCardContact;
import com.linagora.dav.contracts.card.DomainAddressBookContract;

public class SabreV4DomainAddressBookTest extends DomainAddressBookContract {

    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 extension() {
        return dockerExtension;
    }

    @Override
    @Disabled("Fixed in Sabre 4_7")
    protected void domainAdministratorCanSetPublicRightOfDomainAddressBook(VCardContact.Format format) {
    }

    @Override
    @Disabled("Fixed in Sabre 4_7")
    protected void domainAdministratorCanDelegateDomainAddressBook(VCardContact.Format format) {
    }

    @Override
    @Disabled("Fixed in Sabre 4_7")
    protected void domainAdministratorCanDelegateDomainAddressBookWithAdminRight() {
    }

    @Override
    @Disabled("Fixed in Sabre 4_7")
    protected void shouldReturn403WhenNonAdminUserSetPublicRightOfDomainAddressBook() {
    }

    @Override
    @Disabled("Fixed in Sabre 4_7")
    protected void shouldReturn400WhenDomainAdminSetEmptyPublicRightOfDomainAddressBook() {
    }

    @Override
    @Disabled("Fixed in Sabre 4_7")
    protected void shouldReturn400WhenDomainAdminSetInvalidPublicRightOfDomainAddressBook() {
    }
}
