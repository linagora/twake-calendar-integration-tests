/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the Affero Gnu Public License for                  *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.dav;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.util.Map;

import org.junit.jupiter.api.Test;

class DockerTwakeCalendarSetupTest {
    @Test
    void settingsShouldRemainIsolatedBetweenSetups() {
        // Given a setup with privacy and a string containing special characters
        String specialValue = "https://example.test/$calendar?name=\"Bob\"&path=\\private";
        String privateConfig = DockerTwakeCalendarSetup.createSabreConfig(Map.of(
            "PRINCIPAL_PRIVACY", true,
            "TRUSTED_URL", specialValue));

        // When another setup uses the default settings
        String defaultConfig = DockerTwakeCalendarSetup.createSabreConfig(Map.of());

        // Then overrides retain their types and do not leak into the next setup
        assertThatJson(privateConfig).inPath("environment.PRINCIPAL_PRIVACY").isBoolean().isTrue();
        assertThatJson(privateConfig).inPath("environment.TRUSTED_URL").isString().isEqualTo(specialValue);
        assertThatJson(defaultConfig).inPath("environment.PRINCIPAL_PRIVACY").isBoolean().isFalse();
        assertThatJson(defaultConfig).inPath("environment.TRUSTED_URL").isAbsent();
    }
}
