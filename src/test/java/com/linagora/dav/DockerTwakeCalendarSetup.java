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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
    public static final String SABRE_V3 = "sabre-v3-it";
    public static final String SABRE_V4 = "sabre-v4-it";

    private static final Path definitionFilePath = Path.of("src/test/resources/rabbitmq-definitions.json");

    private final ComposeContainer environment;
    private TwakeCalendarProvisioningService twakeCalendarProvisioningService;

    public DockerTwakeCalendarSetup(String sabreVersion) {
        try {
            environment = new ComposeContainer(
                new File(DockerTwakeCalendarSetup.class.getResource("/docker-twake-calendar-setup.yml").toURI()))
                .waitingFor("twake-calendar-side-service", Wait.forLogMessage(".*StartUpChecks all succeeded.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(10)))
                .withEnv("SABRE_DAV_IMAGE", sabreVersion)
                .withLogConsumer("sabre_dav", log -> System.out.print("sabre_dav " + log.getUtf8String()))
                .withLogConsumer("twake-calendar-side-service", log -> System.out.print("twake-calendar-side-service " + log.getUtf8String()));
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to initialize Twake Calendar Setup from docker compose.", e);
        }
    }

    public void start() {
        environment.start();
        twakeCalendarProvisioningService = new TwakeCalendarProvisioningService(
            "mongodb://%s:27017".formatted(TestContainersUtils.getContainerPrivateIpAddress(getMongoDBContainer())),
            TestContainersUtils.getContainerPrivateIpAddress(getCalendarSideServiceContainer()));

        twakeCalendarProvisioningService.createUserInUsersRepository("admin@open-paas.org").block();

        waitForRabbitMQToBeReady();
    }

    public void stop() {
        environment.stop();
    }

    public ContainerState getCalendarSideServiceContainer() {
        return environment.getContainerByServiceName("twake-calendar-side-service").orElseThrow();
    }

    public ContainerState getRabbitMqContainer() {
        return environment.getContainerByServiceName("rabbitmq").orElseThrow();
    }

    public ContainerState getSabreDavContainer() {
        return environment.getContainerByServiceName("sabre_dav").orElseThrow();
    }

    public ContainerState getMongoDBContainer() {
        return environment.getContainerByServiceName("mongo").orElseThrow();
    }

    public ContainerState getElasticsearchContainer() {
        return environment.getContainerByServiceName("opensearch").orElseThrow();
    }

    public ContainerState getRedisContainer() {
        return environment.getContainerByServiceName("redis").orElseThrow();
    }

    public ContainerState getLdapContainer() {
        return environment.getContainerByServiceName("ldap").orElseThrow();
    }

    public List<ContainerState> getAllContainers() {
        return List.of(getCalendarSideServiceContainer(),
                getRabbitMqContainer(),
                getSabreDavContainer(),
                getMongoDBContainer(),
                getElasticsearchContainer(),
                getRedisContainer(),
                getLdapContainer()
        );
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
            .baseUrl(rabbitMqManagementUri().toString())
            .headers(headers -> {
                headers.add("Authorization", "Basic Z3Vlc3Q6Z3Vlc3Q="); // "guest:guest"
                headers.add("Content-Type", "application/json");
            });
    }

    private URI rabbitMqManagementUri() {
        try {
            return new URIBuilder()
                .setScheme("http")
                .setHost(TestContainersUtils.getContainerPrivateIpAddress(getRabbitMqContainer()))
                .setPort(15672)
                .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}