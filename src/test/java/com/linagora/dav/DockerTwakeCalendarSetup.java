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

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.junit.platform.commons.util.Preconditions;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

public class DockerTwakeCalendarSetup {
    public enum DockerService {
        CALENDAR_SIDE("twake-calendar-side-service", 8080),
        CALENDAR_SIDE_ADMIN("twake-calendar-side-service", 8000),
        RABBITMQ("rabbitmq", 5672),
        RABBITMQ_ADMIN("rabbitmq", 15672),
        SABRE_DAV("sabre_dav", 80),
        MONGO("mongo", 27017),
        OPENSEARCH("opensearch", 9200),
        REDIS("redis", 6379),
        LDAP("ldap", 389);

        private final String serviceName;
        private final Integer port;

        DockerService(String serviceName, Integer port) {
            this.serviceName = serviceName;
            this.port = port;
        }

        public String serviceName() {
            return serviceName;
        }

        public Integer port() {
            return port;
        }
    }

    public static final String SABRE_V3 = "sabre-v3-it";
    public static final String SABRE_V4 = "sabre-v4-it";

    private static final Path definitionFilePath = Path.of("src/test/resources/rabbitmq-definitions.json");

    private final ComposeContainer environment;
    private TwakeCalendarProvisioningService twakeCalendarProvisioningService;

    public DockerTwakeCalendarSetup(String sabreVersion) {
        try {
            environment = new ComposeContainer(
                new File(DockerTwakeCalendarSetup.class.getResource("/docker-twake-calendar-setup.yml").toURI()))
                .withExposedService(DockerService.CALENDAR_SIDE.serviceName(), DockerService.CALENDAR_SIDE.port())
                .withExposedService(DockerService.CALENDAR_SIDE_ADMIN.serviceName(), DockerService.CALENDAR_SIDE_ADMIN.port())
                .withExposedService(DockerService.RABBITMQ.serviceName(), DockerService.RABBITMQ.port())
                .withExposedService(DockerService.RABBITMQ_ADMIN.serviceName(), DockerService.RABBITMQ_ADMIN.port())
                .withExposedService(DockerService.SABRE_DAV.serviceName(), DockerService.SABRE_DAV.port())
                .withExposedService(DockerService.MONGO.serviceName(), DockerService.MONGO.port())
                .withExposedService(DockerService.OPENSEARCH.serviceName(), DockerService.OPENSEARCH.port())
                .withExposedService(DockerService.REDIS.serviceName(), DockerService.REDIS.port())
                .withExposedService(DockerService.LDAP.serviceName(), DockerService.LDAP.port())
                .waitingFor(DockerService.CALENDAR_SIDE.serviceName(), Wait.forLogMessage(".*StartUpChecks all succeeded.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(10)))
                .withEnv("SABRE_DAV_IMAGE", sabreVersion)
                .withLogConsumer(DockerService.SABRE_DAV.serviceName(), log -> System.out.print("sabre_dav " + log.getUtf8String()))
                .withLogConsumer(DockerService.CALENDAR_SIDE.serviceName(), log -> System.out.print("twake-calendar-side-service " + log.getUtf8String()));
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to initialize Twake Calendar Setup from docker compose.", e);
        }
    }

    public void start() {
        environment.start();
        twakeCalendarProvisioningService = new TwakeCalendarProvisioningService(
            getServiceUri(DockerService.MONGO, "mongodb").toString(),
            getServiceUri(DockerService.CALENDAR_SIDE_ADMIN, "http").toString());

        twakeCalendarProvisioningService.createUserInUsersRepository("admin@open-paas.org").block();

        waitForRabbitMQToBeReady();
    }

    public void stop() {
        environment.stop();
    }

    public TwakeCalendarProvisioningService getTwakeCalendarProvisioningService() {
        Preconditions.notNull(twakeCalendarProvisioningService, "Twake Calendar Provisioning Service not initialized");
        return twakeCalendarProvisioningService;
    }

    private boolean importRabbitMQDefinitions() {
        try {
            rabbitmqAdminHttpclient().post()
                .uri("/api/definitions")
                .send(ByteBufFlux.fromPath(definitionFilePath))
                .responseSingle((response, responseContent) -> {
                    if (response.status().code() == 204) {
                        System.out.println("Successfully imported RabbitMQ definitions (HTTP 204)");
                        return Mono.empty();
                    } else {
                        return responseContent.asString(StandardCharsets.UTF_8)
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .flatMap(responseBody -> Mono.error(new RuntimeException("""
                                Unexpected status code: %d when import RabbitMQ definitions
                                %s
                                """.formatted(response.status().code(), responseBody))));
                    }
                }).block();
            return true;
        } catch (Exception e) {
            System.out.println(e.getMessage());
            return false;
        }
    }

    private void waitForRabbitMQToBeReady() {
        Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(this::importRabbitMQDefinitions);
    }

    private HttpClient rabbitmqAdminHttpclient() {
        return HttpClient.create()
            .baseUrl(getServiceUri(DockerService.RABBITMQ_ADMIN, "http").toString())
            .headers(headers -> {
                headers.add("Authorization", "Basic Z3Vlc3Q6Z3Vlc3Q="); // "guest:guest"
                headers.add("Content-Type", "application/json");
            });
    }

    public ContainerState getContainer(DockerService service) {
        return environment.getContainerByServiceName(service.serviceName()).orElseThrow();
    }

    public String getHost(DockerService service) {
        return environment.getServiceHost(service.serviceName(), service.port());
    }

    public Integer getPort(DockerService service) {
        return environment.getServicePort(service.serviceName(), service.port());
    }

    public URI getServiceUri(DockerService service, String scheme) {
        try {
            return new URIBuilder()
                .setScheme(scheme)
                .setHost(getHost(service))
                .setPort(getPort(service))
                .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to build URI for service " + service.serviceName(), e);
        }
    }
}