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

package com.linagora.dav;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ContainerState;

import reactor.netty.http.client.HttpClient;

public class LegacyOpenPaaSAPITest extends OpenPaaSAPITest {
    @RegisterExtension
    static DockerOpenPaasExtension extension = new DockerOpenPaasExtension();

    OpenPaasUser createUser() {
        return extension.newTestUser();
    }

    @Override
    HttpClient davHttpClient() {
        return extension.davHttpClient();
    }

    @Override
    String domainId() {
        return extension.domainId();
    }

    @Override
    ContainerState container() {
        return DockerOpenPaasSetupSingleton.singleton.getOpenPaasContainer();
    }
}
