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

import java.io.IOException;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DockerTwakeCalendarExtensionV4;
import com.linagora.dav.contracts.EmailAMQPMessageContract;

public class SabreV4EmailAMQPMessageTest extends EmailAMQPMessageContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4 dockerExtension = new DockerTwakeCalendarExtensionV4();

    @Override
    public DockerTwakeCalendarExtensionV4 dockerExtension() {
        return dockerExtension;
    }

    @Disabled("https://github.com/linagora/esn-sabre/issues/229 Sabe 4.1.5 bug! Fxed in 4.7.0...")
    @Test
    @Override
    public void shouldReceiveNotificationEmailMessageOnEventCreationWith1DVALARM() {
        super.shouldReceiveNotificationEmailMessageOnEventCreationWith1DVALARM();
    }

    @Disabled("Only supported in Sabre 4.7+")
    @Override
    public void shouldNotSendNotificationEmailWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithInternalAttendee() throws IOException, InterruptedException {
        super.shouldNotSendNotificationEmailWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithInternalAttendee();
    }

    @Disabled("Only supported in Sabre 4.7+")
    @Override
    public void shouldNotSendNotificationEmailWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithExternalAttendee() {
        super.shouldNotSendNotificationEmailWhenOrganizerPartStatIsNeedsActionAndPubliclyCreatedWithExternalAttendee();
    }

    @Disabled("Only supported in Sabre 4.7+")
    @Override
    public void shouldSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedWithInternalAttendee(String partStat) {
        super.shouldSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedWithInternalAttendee(partStat);
    }

    @Disabled("Only supported in Sabre 4.7+")
    @Override
    public void shouldSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedWithExternalAttendee(String partStat) {
        super.shouldSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToAcceptedWithExternalAttendee(partStat);
    }

    @Disabled("Only supported in Sabre 4.7+")
    @Override
    public void shouldNotSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToDeclinedWithInternalAttendee() throws InterruptedException, IOException {
        super.shouldNotSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToDeclinedWithInternalAttendee();
    }

    @Disabled("Only supported in Sabre 4.7+")
    @Override
    public void shouldNotSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToDeclinedWithExternalAttendee() throws InterruptedException, IOException {
        super.shouldNotSendNotificationEmailWhenOrganizerPartStatUpdatedFromNeedsActionToDeclinedWithExternalAttendee();
    }
}
