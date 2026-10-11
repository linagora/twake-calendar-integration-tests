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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import reactor.netty.http.client.HttpClient;

public abstract class DockerTwakeCalendarExtension implements AfterEachCallback, ParameterResolver {

    public static final String QUEUE_NAME = "tcalendar:event:test";

    private Channel channel;
    private Connection connection;

    abstract DockerTwakeCalendarSetup setup();

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } finally {
            channel = null;
            try {
                if (connection != null && connection.isOpen()) {
                    connection.close();
                }
            } finally {
                connection = null;
            }
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerTwakeCalendarSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return setup();
    }

    public DockerTwakeCalendarSetup getDockerTwakeCalendarSetupSingleton() {
        return setup();
    }

    public OpenPaasUser newTestUser() {
        return setup()
            .getTwakeCalendarProvisioningService()
            .createUser()
            .block();
    }

    public OpenPaasUser newTestUser(String localPart) {
        return setup()
            .getTwakeCalendarProvisioningService()
            .createUser(localPart)
            .block();
    }

    public TwakeCalendarProvisioningService twakeCalendarProvisioningService() {
        return setup().getTwakeCalendarProvisioningService();
    }

    public String domainId() {
        return ((ObjectId) setup()
            .getTwakeCalendarProvisioningService()
            .openPaasDomain()
            .get("_id")).toString();
    }

    public HttpClient davHttpClient() {
        return HttpClient.create()
            .baseUrl(getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString());
    }

    public RequestSpecification davRequestSpecification() {
        return new RequestSpecBuilder()
            .setBaseUri(getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http")
                .toString())
            .setUrlEncodingEnabled(false)
            .setConfig(RestAssuredConfig.newConfig()
                .encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8))
                .redirect(RedirectConfig.redirectConfig().followRedirects(false)))
            .build();
    }

    public RequestSpecification webAdminRequestSpecification() {
        return new RequestSpecBuilder()
            .setBaseUri(getDockerTwakeCalendarSetupSingleton()
                .getServiceUri(DockerTwakeCalendarSetup.DockerService.CALENDAR_SIDE_ADMIN, "http")
                .toString())
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .build();
    }

    public synchronized Channel getChannel() {
        if (channel == null) {
            // Most DAV tests do not consume AMQP messages; open a connection only when needed.
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(setup().getHost(DockerTwakeCalendarSetup.DockerService.RABBITMQ));
            factory.setPort(setup().getPort(DockerTwakeCalendarSetup.DockerService.RABBITMQ));
            factory.setUsername("guest");
            factory.setPassword("guest");

            try {
                connection = factory.newConnection();
                channel = connection.createChannel();
                channel.queueDeclare(QUEUE_NAME, false, true, true, null);
            } catch (IOException | TimeoutException e) {
                throw new IllegalStateException("Failed to initialize the test AMQP channel", e);
            }
        }
        return channel;
    }
}