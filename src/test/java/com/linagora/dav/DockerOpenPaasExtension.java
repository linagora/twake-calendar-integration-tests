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

import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import reactor.netty.http.client.HttpClient;

public class DockerOpenPaasExtension implements BeforeAllCallback, AfterAllCallback,ParameterResolver {

    public static final boolean DEBUG = true;

    private DockerOpenPaasSetup dockerOpenPaasSetupSingleton = new DockerOpenPaasSetup();

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        dockerOpenPaasSetupSingleton.start();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        dockerOpenPaasSetupSingleton.stop();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerOpenPaasSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return dockerOpenPaasSetupSingleton;
    }

    public DockerOpenPaasSetup getDockerOpenPaasSetupSingleton() {
        return dockerOpenPaasSetupSingleton;
    }

    public OpenPaasUser newTestUser() {
        return dockerOpenPaasSetupSingleton
            .getOpenPaaSProvisioningService()
            .createUser()
            .block();
    }

    public String domainId() {
        return ((ObjectId) dockerOpenPaasSetupSingleton
            .getOpenPaaSProvisioningService()
            .openPaasDomain()
            .get("_id")).toString();
    }

    public HttpClient davHttpClient() {
        return HttpClient.create()
            .baseUrl("http://" + TestContainersUtils.getContainerPrivateIpAddress(getDockerOpenPaasSetupSingleton().getSabreDavContainer()) + ":80");
    }
}