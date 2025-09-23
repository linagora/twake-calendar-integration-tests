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
import java.time.Duration;

import org.apache.http.client.utils.URIBuilder;
import org.junit.platform.commons.util.Preconditions;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public class DockerOpenPaasSetup {
    public enum DockerService {
        OPENPAAS("openpaas", 8080),
        RABBITMQ("rabbitmq", 5672),
        RABBITMQ_ADMIN("rabbitmq", 15672),
        SABRE_DAV("sabre_dav", 80),
        MONGO("mongo", 27017),
        ELASTICSEARCH("elasticsearch", 9200),
        REDIS("redis", 6379);

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

    private final ComposeContainer environment;
    private OpenPaaSProvisioningService openPaaSProvisioningService;

    {
        try {
            environment = new ComposeContainer(
                new File(DockerOpenPaasSetup.class.getResource("/docker-openpaas-setup.yml").toURI()))
                .withExposedService(DockerService.OPENPAAS.serviceName(), DockerService.OPENPAAS.port())
                .withExposedService(DockerService.RABBITMQ.serviceName(), DockerService.RABBITMQ.port())
                .withExposedService(DockerService.RABBITMQ_ADMIN.serviceName(), DockerService.RABBITMQ_ADMIN.port())
                .withExposedService(DockerService.SABRE_DAV.serviceName(), DockerService.SABRE_DAV.port())
                .withExposedService(DockerService.MONGO.serviceName(), DockerService.MONGO.port())
                .withExposedService(DockerService.ELASTICSEARCH.serviceName(), DockerService.ELASTICSEARCH.port())
                .withExposedService(DockerService.REDIS.serviceName(), DockerService.REDIS.port())
                .waitingFor(DockerService.OPENPAAS.serviceName(), Wait.forLogMessage(".*Users currently connected.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)))
                .withLogConsumer(DockerService.SABRE_DAV.serviceName(), log -> System.out.print("sabre_dav " + log.getUtf8String()))
                .withLogConsumer(DockerService.OPENPAAS.serviceName(), log -> System.out.print("openpaas " + log.getUtf8String()));
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to initialize OpenPaas Setup from docker compose.", e);
        }
    }

    public void start() {
        environment.start();
        openPaaSProvisioningService = new OpenPaaSProvisioningService(
            getServiceUri(DockerService.MONGO, "mongodb").toString());
    }

    public void stop() {
        environment.stop();
    }

    public OpenPaaSProvisioningService getOpenPaaSProvisioningService() {
        Preconditions.notNull(openPaaSProvisioningService, "OpenPaas Provisioning Service not initialized");
        return openPaaSProvisioningService;
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
            throw new RuntimeException(e);
        }
    }
}