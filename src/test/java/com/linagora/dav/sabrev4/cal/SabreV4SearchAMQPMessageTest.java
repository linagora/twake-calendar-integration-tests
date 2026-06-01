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

package com.linagora.dav.sabrev4.cal;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.contracts.cal.SearchAMQPMessageContract;

public class SabreV4SearchAMQPMessageTest extends SearchAMQPMessageContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/235 Fixed in 4.7.0")
    @Test
    @Override
    protected void shouldReceiveOnlyOneMessageFromEventCancelExchange()  {

    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/235 Fixed in 4.7.0")
    @Override
    @Test
    protected void shouldReceiveOnlyOneMessageFromEventRequestExchange()  {

    }

    @Disabled("Fixed in 4.7.0")
    @Override
    @Test
    protected void itipRequestShouldResultInEventInDefaultCalendar() {

    }

    @Disabled("Only true for Sabre 4.7 where ITIP CANCEL also emits calendar:event:deleted")
    @Override
    @Test
    protected void itipCancelShouldPublishDeletedMessage() {

    }

    @Disabled("Only true for Sabre 4.7 where ITIP REQUEST emits calendar:event:updated")
    @Override
    @Test
    protected void itipRequestShouldPublishUpdatedMessage() {

    }
}
