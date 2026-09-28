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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DockerTwakeCalendarSetupTest {

    @Test
    void sabreConfigsAreIndependentPerComposeSetup() throws Exception {
        // Given/When two setups override the same runtime setting differently
        File privateConfig = DockerTwakeCalendarSetup.createSabreConfig(Map.of(
            "PRINCIPAL_PRIVACY", true,
            "SABRE_ENFORCE_RFC_6638", false));
        File publicConfig = DockerTwakeCalendarSetup.createSabreConfig(Map.of("PRINCIPAL_PRIVACY", false));

        // Then each container can keep its own config for its whole lifetime
        assertThat(privateConfig).isNotEqualTo(publicConfig);
        assertThat(privateConfig.toPath().getParent())
            .isEqualTo(Path.of(getClass().getResource("/docker-twake-calendar-setup.yml").toURI()).getParent());
        assertThatJson(Files.readString(privateConfig.toPath())).inPath("$.environment.PRINCIPAL_PRIVACY").isEqualTo(true);
        assertThatJson(Files.readString(privateConfig.toPath())).inPath("$.environment.SABRE_ENFORCE_RFC_6638").isEqualTo(false);
        assertThatJson(Files.readString(publicConfig.toPath())).inPath("$.environment.PRINCIPAL_PRIVACY").isEqualTo(false);
    }
}
