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

import java.net.URI;

import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.contracts.OpenPaaSAPIContract;

import reactor.netty.http.client.HttpClient;

public class LegacyOpenPaaSAPITest extends OpenPaaSAPIContract {
    @RegisterExtension
    static DockerOpenPaasExtension extension = new DockerOpenPaasExtension();

    public OpenPaasUser createUser() {
        return extension.newTestUser();
    }

    @Override
    public HttpClient davHttpClient() {
        return extension.davHttpClient();
    }

    @Override
    public String domainId() {
        return extension.domainId();
    }

    @Override
    public URI backendURI() {
        return extension.getDockerOpenPaasSetupSingleton().getServiceUri(DockerOpenPaasSetup.DockerService.OPENPAAS, "http");
    }

    @Override
    public URI elasticSearchURI() {
        return extension.getDockerOpenPaasSetupSingleton().getServiceUri(DockerOpenPaasSetup.DockerService.ELASTICSEARCH, "http");
    }
}
