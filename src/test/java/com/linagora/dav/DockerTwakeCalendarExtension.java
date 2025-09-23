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

import static com.linagora.dav.DockerTwakeCalendarSetup.SABRE_V4;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import reactor.netty.http.client.HttpClient;

public class DockerTwakeCalendarExtension implements BeforeEachCallback, AfterEachCallback, BeforeAllCallback, AfterAllCallback,ParameterResolver {

    public static final String QUEUE_NAME = "tcalendar:event:test";
    public static final boolean DEBUG = true;

    private final DockerTwakeCalendarSetup dockerTwakeCalendarSetup;
    private Channel channel;
    private Connection connection;

    public DockerTwakeCalendarExtension() {
        this(SABRE_V4);
    }

    public DockerTwakeCalendarExtension(String sabreVersion) {
        dockerTwakeCalendarSetup = new DockerTwakeCalendarSetup(sabreVersion);
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(getDockerTwakeCalendarSetupSingleton().getHost(DockerTwakeCalendarSetup.DockerService.RABBITMQ));
        factory.setPort(getDockerTwakeCalendarSetupSingleton().getPort(DockerTwakeCalendarSetup.DockerService.RABBITMQ));
        factory.setUsername("guest");
        factory.setPassword("guest");

        connection = factory.newConnection();
        channel = connection.createChannel();
        channel.queueDeclare(QUEUE_NAME, false, true, true, null);
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        dockerTwakeCalendarSetup.start();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        dockerTwakeCalendarSetup.stop();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerTwakeCalendarSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return dockerTwakeCalendarSetup;
    }

    public DockerTwakeCalendarSetup getDockerTwakeCalendarSetupSingleton() {
        return dockerTwakeCalendarSetup;
    }

    public OpenPaasUser newTestUser() {
        return dockerTwakeCalendarSetup
            .getTwakeCalendarProvisioningService()
            .createUser()
            .block();
    }

    public OpenPaasUser newTestUser(String localPart) {
        return dockerTwakeCalendarSetup
            .getTwakeCalendarProvisioningService()
            .createUser(localPart)
            .block();
    }

    public TwakeCalendarProvisioningService twakeCalendarProvisioningService() {
        return dockerTwakeCalendarSetup.getTwakeCalendarProvisioningService();
    }

    public String domainId() {
        return ((ObjectId) dockerTwakeCalendarSetup
            .getTwakeCalendarProvisioningService()
            .openPaasDomain()
            .get("_id")).toString();
    }

    public HttpClient davHttpClient() {
        return HttpClient.create()
            .baseUrl(getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString());
    }

    public Channel getChannel() {
        return channel;
    }
}