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
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import reactor.netty.http.client.HttpClient;

public class DockerTwakeCalendarExtension implements ParameterResolver {

    public static final boolean DEBUG = true;

    // Ensuring DockerTwakeCalendarSetupSingleton is loaded to classpath
    private static DockerTwakeCalendarSetup dockerTwakeCalendarSetup = DockerTwakeCalendarSetupSingleton.singleton;

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerTwakeCalendarSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return DockerTwakeCalendarSetupSingleton.singleton;
    }

    public DockerTwakeCalendarSetup getDockerTwakeCalendarSetupSingleton() {
        return DockerTwakeCalendarSetupSingleton.singleton;
    }

    public OpenPaasUser newTestUser() {
        return DockerTwakeCalendarSetupSingleton.singleton
            .getTwakeCalendarProvisioningService()
            .createUser()
            .block();
    }

    public OpenPaasUser newTestUser(String localPart) {
        return DockerTwakeCalendarSetupSingleton.singleton
                .getTwakeCalendarProvisioningService()
                .createUser(localPart)
                .block();
    }

    public TwakeCalendarProvisioningService twakeCalendarProvisioningService() {
        return DockerTwakeCalendarSetupSingleton.singleton.getTwakeCalendarProvisioningService();
    }

    public String domainId() {
        return ((ObjectId) DockerTwakeCalendarSetupSingleton.singleton
            .getTwakeCalendarProvisioningService()
            .openPaasDomain()
            .get("_id")).toString();
    }

    public HttpClient davHttpClient() {
        return HttpClient.create()
            .baseUrl("http://" + TestContainersUtils.getContainerPrivateIpAddress(getDockerTwakeCalendarSetupSingleton().getSabreDavContainer()) + ":80");
    }
}