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

package com.linagora.dav.sabrev4_7;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linagora.dav.DockerTwakeCalendarExtensionV4_7;
import com.linagora.dav.contracts.CalJsonContract;

public class SabreV4CalJsonTest extends CalJsonContract {
    @RegisterExtension
    static DockerTwakeCalendarExtensionV4_7 dockerExtension = new DockerTwakeCalendarExtensionV4_7();

    @Override
    public DockerTwakeCalendarExtensionV4_7 dockerExtension() {
        return dockerExtension;
    }


    @Disabled("CF super.reportShouldIncludeSyncToken();")
    @Test
    @Override
    public void reportShouldIncludeSyncToken() throws Exception {
        super.reportShouldIncludeSyncToken();
    }

    @Test
    @Disabled("CF https://github.com/linagora/esn-sabre/pull/231")
    @Override
    public void reportShouldExpandSingleRecurringEventWithTimeRange() throws JsonProcessingException {
        super.reportShouldExpandSingleRecurringEventWithTimeRange();
    }

    @Test
    @Disabled("CF https://github.com/linagora/esn-sabre/pull/231")
    @Override
    public void reportOnSingleEventShouldReturn400WhenMissingStartParameter() {
        super.reportOnSingleEventShouldReturn400WhenMissingStartParameter();
    }

    @Test
    @Disabled("CF https://github.com/linagora/esn-sabre/pull/231")
    @Override
    public void reportOnSingleEventShouldReturn400WhenMissingEndParameter() {
        super.reportOnSingleEventShouldReturn400WhenMissingEndParameter();
    }

    @Test
    @Disabled("CF https://github.com/linagora/esn-sabre/pull/231")
    @Override
    public void reportOnSingleEventShouldReturn400WhenMissingBothParameters() {
        super.reportOnSingleEventShouldReturn400WhenMissingBothParameters();
    }

    @Test
    @Disabled("CF https://github.com/linagora/esn-sabre/pull/231")
    @Override
    public void reportOnSingleEventShouldReturnEmptyWhenOutOfRange() throws JsonProcessingException {
        super.reportOnSingleEventShouldReturnEmptyWhenOutOfRange();
    }

    @Test
    @Disabled("CF https://github.com/linagora/esn-sabre/pull/231")
    @Override
    public void reportOnSingleEventShouldIncludeETag() throws JsonProcessingException {
        super.reportOnSingleEventShouldIncludeETag();
    }
}
