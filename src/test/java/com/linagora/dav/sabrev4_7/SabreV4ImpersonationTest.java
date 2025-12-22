package com.linagora.dav.sabrev4_7;

import static com.linagora.dav.DockerTwakeCalendarSetup.SABRE_IMPERSONATION_ENABLED;
import static com.linagora.dav.DockerTwakeCalendarSetup.SABRE_V4_7;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestUtil;

import io.netty.handler.codec.http.HttpMethod;

class SabreV4ImpersonationTest {

    private static final String SABRE_IMPERSONATION_ENABLED_ENV_KEY = "SABRE_IMPERSONATION_ENABLED";

    @Nested
    class WhenImpersonationDisabled {
        static class SabreV4ImpersonationDisabledExtension extends DockerTwakeCalendarExtension implements BeforeAllCallback, AfterAllCallback {

            private static final DockerTwakeCalendarSetup twakeCalendarSetup = new DockerTwakeCalendarSetup(SABRE_V4_7)
                .withEnv(SABRE_IMPERSONATION_ENABLED_ENV_KEY, String.valueOf(!SABRE_IMPERSONATION_ENABLED));

            @Override
            protected DockerTwakeCalendarSetup setup() {
                return twakeCalendarSetup;
            }

            @Override
            public void afterAll(ExtensionContext extensionContext) {
                twakeCalendarSetup.stop();

            }

            @Override
            public void beforeAll(ExtensionContext extensionContext) {
                twakeCalendarSetup.start();
            }
        }

        @RegisterExtension
        static SabreV4ImpersonationDisabledExtension extension = new SabreV4ImpersonationDisabledExtension();

        @Test
        void shouldRejectImpersonationEvenWithValidCredential() {
            OpenPaasUser testUser = extension.newTestUser();

            DavResponse response = TestUtil.execute(extension.davHttpClient()
                .headers(testUser::impersonatedBasicAuth)
                .request(HttpMethod.GET)
                .uri("/principals/users/" + testUser.id()));

            assertThat(response.status())
                .as("Impersonation must be rejected when SABRE_IMPERSONATION_ENABLED=false")
                .isEqualTo(401);
            assertThat(response.body())
                .contains("Username or password was incorrect");
        }
    }

    @Nested
    class WhenImpersonationEnabled {
        static class SabreV4ImpersonationEnabledExtension extends DockerTwakeCalendarExtension implements BeforeAllCallback, AfterAllCallback {

            private static final DockerTwakeCalendarSetup twakeCalendarSetup = new DockerTwakeCalendarSetup(SABRE_V4_7)
                .withEnv(SABRE_IMPERSONATION_ENABLED_ENV_KEY, String.valueOf(SABRE_IMPERSONATION_ENABLED));

            @Override
            protected DockerTwakeCalendarSetup setup() {
                return twakeCalendarSetup;
            }

            @Override
            public void afterAll(ExtensionContext extensionContext) {
                twakeCalendarSetup.stop();
            }

            @Override
            public void beforeAll(ExtensionContext extensionContext) {
                twakeCalendarSetup.start();
            }
        }

        @RegisterExtension
        static SabreV4ImpersonationEnabledExtension extension = new SabreV4ImpersonationEnabledExtension();

        @Test
        void shouldRejectImpersonationWithInvalidCredential() {
            OpenPaasUser testUser = extension.newTestUser();

            String userPassword = "admin&" + testUser.email() + ":" + "fakePassword";
            byte[] base64UserPassword = Base64
                .getEncoder()
                .encode(userPassword.getBytes(StandardCharsets.UTF_8));

            DavResponse response = TestUtil.execute(extension.davHttpClient()
                .headers(headers -> headers.add("Authorization", "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8)))
                .request(HttpMethod.GET)
                .uri("/principals/users/" + testUser.id()));

            assertThat(response.status())
                .isEqualTo(401);
            assertThat(response.body())
                .contains("Username or password was incorrect");
        }

        @Test
        void shouldAllowImpersonationWithValidCredential() {
            OpenPaasUser testUser = extension.newTestUser();

            DavResponse response = TestUtil.execute(extension.davHttpClient()
                .headers(testUser::impersonatedBasicAuth)
                .request(HttpMethod.GET)
                .uri("/principals/users/" + testUser.id()));

            assertThat(response.status())
                .isIn(200, 207);
            assertThat(response.body())
                .contains("principals/users");
        }
    }

}
