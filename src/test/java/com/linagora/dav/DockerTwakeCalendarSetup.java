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
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import org.apache.http.client.utils.URIBuilder;
import org.junit.platform.commons.util.Preconditions;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.Resources;

public class DockerTwakeCalendarSetup {
    public enum DockerService {
        CALENDAR_SIDE("twake-calendar-side-service", 8080),
        CALENDAR_SIDE_ADMIN("twake-calendar-side-service", 8000),
        RABBITMQ("rabbitmq", 5672),
        RABBITMQ_ADMIN("rabbitmq", 15672),
        SABRE_DAV("sabre_dav", 80),
        MONGO("mongo", 27017),
        OPENSEARCH("opensearch", 9200),
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

    public static final String SABRE_V4_7 = "sabre-v4-7-it";

    private final ComposeContainer environment;
    private TwakeCalendarProvisioningService twakeCalendarProvisioningService;

    public DockerTwakeCalendarSetup(String sabreVersion) {
        this(sabreVersion, false);
    }

    public DockerTwakeCalendarSetup(String sabreVersion, boolean principalPrivacy) {
        this(sabreVersion, Map.of("PRINCIPAL_PRIVACY", principalPrivacy));
    }

    public DockerTwakeCalendarSetup(String sabreVersion, Map<String, ?> sabreSettings) {
        File sabreConfig = createSabreConfig(sabreSettings);
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
                .withExposedService(DockerService.LDAP.serviceName(), DockerService.LDAP.port())
                .waitingFor(DockerService.CALENDAR_SIDE.serviceName(), Wait.forLogMessage(".*StartUpChecks all succeeded.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(10)))
                .withEnv("SABRE_DAV_IMAGE", sabreVersion)
                .withEnv("SABRE_CONFIG_FILE", sabreConfig.getAbsolutePath())
                .withLogConsumer(DockerService.SABRE_DAV.serviceName(), log -> System.out.print("[esn-sabre] " + log.getUtf8String()))
                .withLogConsumer(DockerService.CALENDAR_SIDE.serviceName(), log -> System.out.print("[twake-calendar-side-service] " + log.getUtf8String()));
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to initialize Twake Calendar Setup from docker compose.", e);
        }
    }

    static File createSabreConfig(Map<String, ?> settings) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode config = (ObjectNode) mapper.readTree(Resources.getResource("sabre-config.json"));
            ObjectNode runtimeSettings = (ObjectNode) config.get("environment");
            runtimeSettings.setAll((ObjectNode) mapper.valueToTree(settings));

            // Each ComposeContainer gets an immutable host file, even across Maven forks.
            File configFile = Files.createTempFile("sabre-config-", ".json").toFile();
            configFile.deleteOnExit();
            mapper.writeValue(configFile, config);
            return configFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Sabre test config.", e);
        }
    }

    public void start() {
        environment.start();
        twakeCalendarProvisioningService = new TwakeCalendarProvisioningService(
            getServiceUri(DockerService.MONGO, "mongodb").toString(),
            getServiceUri(DockerService.CALENDAR_SIDE_ADMIN, "http").toString(),
            getServiceUri(DockerService.SABRE_DAV, "http").toString());
    }

    public void stop() {
        environment.stop();
    }

    public TwakeCalendarProvisioningService getTwakeCalendarProvisioningService() {
        Preconditions.notNull(twakeCalendarProvisioningService, "Twake Calendar Provisioning Service not initialized");
        return twakeCalendarProvisioningService;
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
