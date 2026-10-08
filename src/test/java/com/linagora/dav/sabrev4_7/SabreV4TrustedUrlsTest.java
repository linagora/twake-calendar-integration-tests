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

package com.linagora.dav.sabrev4_7;

import static com.linagora.dav.TestUtil.awaitAtMost;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtensionV4_7;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.OpenPaasUser;

@Execution(ExecutionMode.SAME_THREAD)
class SabreV4TrustedUrlsTest {
    private static final String TRUSTED_VIDEO_BASE_URL = "https://meet.linagora.com";
    private static final String TRUSTED_ATTACH_BASE_URL = "https://{fdqn}-drive.linagora.com";

    private enum DavResource {
        EVENT("text/calendar") {
            @Override
            String payload(String uid, String properties) {
                return """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    BEGIN:VEVENT
                    UID:{uid}
                    DTSTAMP:20260928T000000Z
                    DTSTART:20261001T090000Z
                    DTEND:20261001T100000Z
                    SUMMARY:Trusted links test
                    {properties}
                    END:VEVENT
                    END:VCALENDAR
                    """.replace("{uid}", uid).replace("{properties}", properties.stripTrailing())
                    .replace("\n", "\r\n");
            }

            @Override
            String path(OpenPaasUser user, String uid) {
                return "/calendars/" + user.id() + "/" + user.id() + "/" + uid + ".ics";
            }
        },
        CONTACT("text/vcard") {
            @Override
            String payload(String uid, String properties) {
                return """
                    BEGIN:VCARD
                    VERSION:3.0
                    FN:Trusted links test
                    UID:{uid}
                    {properties}
                    END:VCARD
                    """.replace("{uid}", uid).replace("{properties}", properties.stripTrailing())
                    .replace("\n", "\r\n");
            }

            @Override
            String path(OpenPaasUser user, String uid) {
                return "/addressbooks/" + user.id() + "/contacts/" + uid + ".vcf";
            }
        };

        final String contentType;

        DavResource(String contentType) {
            this.contentType = contentType;
        }

        abstract String payload(String uid, String properties);

        abstract String path(OpenPaasUser user, String uid);
    }

    // Each row contains one supported property in the resource that accepts it.
    static Stream<Arguments> trustedLinks() {
        return Stream.of(
            Arguments.of(DavResource.EVENT, "X-OPENPAAS-VIDEOCONFERENCE:https://meet.linagora.com/room"),
            Arguments.of(DavResource.EVENT, "X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:https://meet.linagora.com/room"),
            Arguments.of(DavResource.EVENT, "CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join:https://meet.linagora.com/room"),
            Arguments.of(DavResource.EVENT, "ATTACH:https://tung-drive.linagora.com/file.pdf"),
            Arguments.of(DavResource.EVENT, "ATTACH;FMTTYPE=application/pdf:https://manh-drive.linagora.com/file.pdf"),
            Arguments.of(DavResource.EVENT, "ATTACH;VALUE=URI;FMTTYPE=application/pdf:https://tung-drive.linagora.com/file.pdf"),
            Arguments.of(DavResource.CONTACT, "PHOTO;VALUE=URI:https://manh-drive.linagora.com/avatar.jpg"),
            Arguments.of(DavResource.CONTACT, "PHOTO;VALUE=URI;TYPE=JPEG:https://tung-drive.linagora.com/avatar.jpg"));
    }

    static Stream<Arguments> untrustedLinks() {
        return Stream.of(
            Arguments.of(DavResource.EVENT, "X-OPENPAAS-VIDEOCONFERENCE:https://meet.linagora.com.evil.test/room"),
            Arguments.of(DavResource.EVENT, "X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI:https://meet.linagora.com.evil.test/room"),
            Arguments.of(DavResource.EVENT, "X-OPENPAAS-VIDEOCONFERENCE;VALUE=UNKNOWN:https://meet.linagora.com.evil.test/room"),
            Arguments.of(DavResource.EVENT, "CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join:https://meet.linagora.com.evil.test/room"),
            Arguments.of(DavResource.EVENT, "ATTACH:https://tung-drive.linagora.com.evil.test/file.pdf"),
            Arguments.of(DavResource.EVENT, "ATTACH;FMTTYPE=application/pdf:https://manh-drive.linagora.com.evil.test/file.pdf"),
            Arguments.of(DavResource.EVENT, "ATTACH;VALUE=URI;FMTTYPE=application/pdf:https://tung-drive.linagora.com.evil.test/file.pdf"),
            Arguments.of(DavResource.CONTACT, "PHOTO;VALUE=URI:https://manh-drive.linagora.com.evil.test/avatar.jpg"),
            Arguments.of(DavResource.CONTACT, "PHOTO;VALUE=URI;TYPE=JPEG:https://tung-drive.linagora.com.evil.test/avatar.jpg"));
    }

    static Stream<Arguments> trustedAndUntrustedLinks() {
        return Stream.of(
            Arguments.of(DavResource.EVENT,
                "X-OPENPAAS-VIDEOCONFERENCE:https://meet.linagora.com/room",
                "X-OPENPAAS-VIDEOCONFERENCE:https://meet.linagora.com.evil.test/room"),
            Arguments.of(DavResource.EVENT,
                "CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join:https://meet.linagora.com/room",
                "CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join:https://meet.linagora.com.evil.test/room"),
            Arguments.of(DavResource.EVENT,
                "ATTACH;VALUE=URI;FMTTYPE=application/pdf:https://tung-drive.linagora.com/file.pdf",
                "ATTACH;VALUE=URI;FMTTYPE=application/pdf:https://tung-drive.linagora.com.evil.test/file.pdf"),
            Arguments.of(DavResource.CONTACT,
                "PHOTO;VALUE=URI;TYPE=JPEG:https://tung-drive.linagora.com/avatar.jpg",
                "PHOTO;VALUE=URI;TYPE=JPEG:https://tung-drive.linagora.com.evil.test/avatar.jpg"));
    }

    static Stream<Arguments> videoPropertiesAndExpectedDerivedProperties() {
        return Stream.of(
            Arguments.of("X-OPENPAAS-VIDEOCONFERENCE", "CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join video call"),
            Arguments.of("X-OPENPAAS-VIDEOCONFERENCE;VALUE=URI", "CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join video call"),
            Arguments.of("CONFERENCE;VALUE=URI;FEATURE=AUDIO,VIDEO;LABEL=Join", "X-OPENPAAS-VIDEOCONFERENCE"));
    }

    static Stream<Arguments> itipLinks() {
        return trustedAndUntrustedLinks()
            .map(Arguments::get)
            .filter(values -> values[0] == DavResource.EVENT)
            .map(values -> Arguments.of(values[1], values[2]));
    }

    private static DockerTwakeCalendarExtensionV4_7 configured_extension;

    private DockerTwakeCalendarExtensionV4_7 extension;
    private OpenPaasUser user;

    @BeforeEach
    void createUser() {
        if (extension == null) {
            extension = configuredExtension();
        }
        user = extension.newTestUser();
    }

    private static DockerTwakeCalendarExtensionV4_7 configuredExtension() {
        if (configured_extension == null) {
            configured_extension = DockerTwakeCalendarExtensionV4_7.withSabreSettings(Map.of(
                "TRUSTED_VIDEO_URL_BASE", TRUSTED_VIDEO_BASE_URL,
                "TRUSTED_ATTACH_URL_BASE", TRUSTED_ATTACH_BASE_URL));
        }
        return configured_extension;
    }

    @AfterAll
    static void stopConfiguredSetup() {
        if (configured_extension != null) {
            configured_extension.getDockerTwakeCalendarSetupSingleton().stop();
            configured_extension = null;
        }
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("trustedLinks")
    void configuredBasesKeepTrustedLinks(DavResource resource, String trustedLink) {
        // Given a trusted URL in the selected property form
        // When the resource is stored through DAV
        String stored = putAndGet(resource, trustedLink);

        // Then the complete property, including its parameters, remains
        assertThat(stored.lines())
            .contains(trustedLink);
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("untrustedLinks")
    void configuredBasesRemoveUntrustedLinks(DavResource resource, String untrustedLink) {
        // Given a lookalike host outside the trusted base
        // When the resource is stored through DAV
        String stored = putAndGet(resource, untrustedLink);

        // Then the property is removed, including any derived video property
        assertLinkPropertiesAbsent(stored, untrustedLink);
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("trustedAndUntrustedLinks")
    void configuredBasesRemoveLinksUpdatedFromTrustedToUntrusted(DavResource resource, String trustedLink, String untrustedLink) {
        // Given an existing resource containing a trusted link
        String uid = UUID.randomUUID().toString();
        String created = putAndGet(resource, uid, trustedLink, 201);
        assertThat(created.lines())
            .contains(trustedLink);

        // When the same resource is updated with an untrusted link
        String updated = putAndGet(resource, uid, untrustedLink, 204);

        // Then neither the old link nor an empty or derived link property remains
        assertThat(updated.lines())
            .contains("UID:" + uid);
        assertLinkPropertiesAbsent(updated, untrustedLink);
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("trustedAndUntrustedLinks")
    void configuredBasesKeepLinksUpdatedFromUntrustedToTrusted(DavResource resource, String trustedLink, String untrustedLink) {
        // Given an existing resource whose untrusted link was filtered out
        String uid = UUID.randomUUID().toString();
        String created = putAndGet(resource, uid, untrustedLink, 201);
        assertLinkPropertiesAbsent(created, untrustedLink);

        // When the same resource is updated with a trusted link
        String updated = putAndGet(resource, uid, trustedLink, 204);

        // Then the existing resource contains the complete trusted property
        assertThat(updated.lines())
            .contains("UID:" + uid, trustedLink);
        assertThat(updated)
            .doesNotContain(StringUtils.substringAfter(untrustedLink, ":"));
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("trustedAndUntrustedLinks")
    void configuredBasesKeepTrustedAndRemoveUntrustedLinksInSameResource(DavResource resource, String trustedLink, String untrustedLink) {
        // Given trusted and untrusted links in the same resource
        String properties = trustedLink + "\n" + untrustedLink;

        // When the resource is stored through DAV
        String stored = putAndGet(resource, properties);

        // Then the complete trusted property remains and the untrusted URL is absent
        assertThat(stored.lines())
            .contains(trustedLink);
        assertThat(stored)
            .doesNotContain(StringUtils.substringAfter(untrustedLink, ":"));
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("trustedAndUntrustedLinks")
    void configuredBasesKeepTrustedAndRemoveUntrustedLinksWhenUpdatingExistingResource(DavResource resource, String trustedLink, String untrustedLink) {
        // Given an existing resource without links
        String uid = UUID.randomUUID().toString();
        String created = putAndGet(resource, uid, "", 201);
        assertThat(created.lines())
            .contains("UID:" + uid);

        // When the same resource is updated with both trusted and untrusted links
        String properties = trustedLink + "\n" + untrustedLink;
        String updated = putAndGet(resource, uid, properties, 204);

        // Then the complete trusted property remains and the untrusted URL is absent
        assertThat(updated.lines())
            .contains("UID:" + uid, trustedLink);
        assertThat(updated)
            .doesNotContain(StringUtils.substringAfter(untrustedLink, ":"));
    }

    @ParameterizedTest(name = "{0} derives {1}")
    @MethodSource("videoPropertiesAndExpectedDerivedProperties")
    void configuredBasesDeriveVideoPropertyFromTrustedLink(String property, String derivedProperty) {
        // Given an event containing only one of the two video properties
        String url = TRUSTED_VIDEO_BASE_URL + "/room";

        // When the event is stored through CalDAV
        String stored = putAndGet(DavResource.EVENT, property + ":" + url);

        // Then the original property remains and the other property carries the same URL
        assertThat(stored.lines())
            .contains(property + ":" + url, derivedProperty + ":" + url);
    }

    @ParameterizedTest(name = "iTIP REQUEST creates event with filtered {1}")
    @MethodSource("itipLinks")
    void itipRequestShouldKeepTrustedAndRemoveUntrustedLinks(String trustedLink, String untrustedLink) {
        // Given an email invitation containing trusted and untrusted links of the same kind
        OpenPaasUser organizer = extension.newTestUser();
        String uid = UUID.randomUUID().toString();

        // When the recipient receives the iTIP REQUEST without a CalDAV PUT
        sendItipRequest(organizer, uid, 0, trustedLink + "\n" + untrustedLink);

        // Then the recipient's calendar keeps the trusted link but excludes the untrusted URL
        String stored = awaitItipEvent(uid, "Trusted links iTIP 0");
        assertThat(stored.lines()).contains(trustedLink);
        assertThat(stored).doesNotContain(StringUtils.substringAfter(untrustedLink, ":"));
    }

    @ParameterizedTest(name = "iTIP REQUEST updates event with filtered {1}")
    @MethodSource("itipLinks")
    void itipUpdateShouldKeepNewTrustedAndRemoveUntrustedLinks(String trustedLink, String untrustedLink) {
        // Given an invitation already delivered to the recipient's calendar
        OpenPaasUser organizer = extension.newTestUser();
        String uid = UUID.randomUUID().toString();
        String oldTrustedUrl = StringUtils.substringAfter(trustedLink, ":") + "?old=1";
        sendItipRequest(organizer, uid, 0, trustedLink + "?old=1");
        assertThat(awaitItipEvent(uid, "Trusted links iTIP 0")).contains(oldTrustedUrl);

        // When a newer iTIP REQUEST replaces its link with trusted and untrusted URLs
        sendItipRequest(organizer, uid, 1, trustedLink + "\n" + untrustedLink);

        // Then the updated event contains only the new trusted URL
        String stored = awaitItipEvent(uid, "Trusted links iTIP 1");
        assertThat(stored.lines()).contains(trustedLink);
        assertThat(stored).doesNotContain(oldTrustedUrl, StringUtils.substringAfter(untrustedLink, ":"));
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UnconfiguredTrustedUrlBases {
        private DockerTwakeCalendarExtensionV4_7 unconfigured_extension;

        @BeforeAll
        void startSetup() {
            stopConfiguredSetup();
            unconfigured_extension = DockerTwakeCalendarExtensionV4_7.withSabreSettings(Map.of());
            // The outer @BeforeEach also runs for nested tests.
            extension = unconfigured_extension;
        }

        @AfterAll
        void stopSetup() {
            if (unconfigured_extension != null) {
                unconfigured_extension.getDockerTwakeCalendarSetupSingleton().stop();
                extension = null;
            }
        }

        @ParameterizedTest(name = "{1} in {0}")
        @MethodSource("com.linagora.dav.sabrev4_7.SabreV4TrustedUrlsTest#untrustedLinks")
        void unconfiguredBasesPreserveExternalLinks(DavResource resource, String untrustedLink) {
            // Given absent trusted bases and a URL that configured filtering would reject
            // When the resource is stored through DAV
            String stored = putAndGet(resource, untrustedLink);

            // Then the complete property remains
            assertThat(stored.lines())
                .contains(untrustedLink);
        }

        @ParameterizedTest(name = "iTIP REQUEST preserves {1} without trusted bases")
        @MethodSource("com.linagora.dav.sabrev4_7.SabreV4TrustedUrlsTest#itipLinks")
        void unconfiguredBasesPreserveExternalLinksFromItip(String trustedLink, String untrustedLink) {
            // Given trusted and external links in an iTIP invitation with no trusted bases configured
            OpenPaasUser organizer = extension.newTestUser();
            String uid = UUID.randomUUID().toString();

            // When the recipient receives the invitation without a CalDAV PUT
            sendItipRequest(organizer, uid, 0, trustedLink + "\n" + untrustedLink);

            // Then the recipient's calendar retains both links
            String stored = awaitItipEvent(uid, "Trusted links iTIP 0");
            assertThat(stored.lines()).contains(trustedLink, untrustedLink);
        }

        @ParameterizedTest(name = "{0} derives {1}")
        @MethodSource("com.linagora.dav.sabrev4_7.SabreV4TrustedUrlsTest#videoPropertiesAndExpectedDerivedProperties")
        void unconfiguredBasesDeriveVideoPropertyFromExternalLink(String property, String derivedProperty) {
            // Given an external video URL with trusted-base filtering disabled
            String url = "https://other.example/room";

            // When the event is stored through CalDAV
            String stored = putAndGet(DavResource.EVENT, property + ":" + url);

            // Then both video properties carry the external URL
            assertThat(stored.lines())
                .as("Event should retain %s and derive %s with the external URL", property, derivedProperty)
                .contains(property + ":" + url, derivedProperty + ":" + url);
        }
    }

    private static void assertLinkPropertiesAbsent(String stored, String inputProperty) {
        String propertyName = StringUtils.substringBefore(StringUtils.substringBefore(inputProperty, ":"), ";");
        String[] forbiddenProperties = switch (propertyName) {
            case "X-OPENPAAS-VIDEOCONFERENCE", "CONFERENCE" -> new String[] {"X-OPENPAAS-VIDEOCONFERENCE", "CONFERENCE"};
            default -> new String[] {propertyName};
        };
        assertThat(stored.lines()
            .map(line -> StringUtils.substringBefore(StringUtils.substringBefore(line, ":"), ";")))
            .as("Filtering %s should remove the property and any derived video property", inputProperty)
            .doesNotContain(forbiddenProperties);
        assertThat(stored)
            .as("The rejected URL from %s should not remain anywhere in the resource", inputProperty)
            .doesNotContain(StringUtils.substringAfter(inputProperty, ":"));
    }

    private void sendItipRequest(OpenPaasUser organizer, String uid, int sequence, String properties) {
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:{uid}
            DTSTAMP:20261001T00000{sequence}Z
            DTSTART:20271001T090000Z
            DTEND:20271001T100000Z
            SEQUENCE:{sequence}
            SUMMARY:Trusted links iTIP {sequence}
            ORGANIZER:mailto:{organizer}
            ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:{recipient}
            {properties}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{uid}", uid)
            .replace("{sequence}", Integer.toString(sequence))
            .replace("{organizer}", organizer.email())
            .replace("{recipient}", user.email())
            .replace("{properties}", properties)
            .replace("\n", "\r\n");
        String body = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender(organizer.email())
            .recipient(user.email())
            .uid(uid)
            .method("REQUEST")
            .sequence(Integer.toString(sequence))
            .buildJson();
        new CalDavClient(extension.davHttpClient())
            .sendITIPRequest(user, URI.create("/calendars/" + user.id()), body)
            .block();
    }

    private String awaitItipEvent(String uid, String expectedSummary) {
        CalDavClient client = new CalDavClient(extension.davHttpClient());
        return awaitAtMost.until(
            () -> client.findUserCalendarObjectDataByEventUid(user, CalendarURL.from(user.id()), uid)
                .filter(ics -> ics.contains("SUMMARY:" + expectedSummary)),
            Optional::isPresent).orElseThrow()
            .replaceAll("\\r?\\n[ \\t]", "");
    }

    private String putAndGet(DavResource resource, String properties) {
        return putAndGet(resource, UUID.randomUUID().toString(), properties, 201);
    }

    private String putAndGet(DavResource resource, String uid, String properties, int expectedStatus) {
        String path = resource.path(user, uid);
        String baseUri = extension.getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerService.SABRE_DAV, "http").toString();
        int status = given()
            .baseUri(baseUri)
            .header("Authorization", user.impersonatedBasicAuth())
            .contentType(resource.contentType)
            .body(resource.payload(uid, properties))
        .when()
            .put(path)
            .statusCode();
        assertThat(status)
            .as("PUT %s should return %s", path, expectedStatus)
            .isEqualTo(expectedStatus);

        return given()
            .baseUri(baseUri)
            .header("Authorization", user.impersonatedBasicAuth())
        .when()
            .get(path)
        .then()
            .statusCode(200)
            .extract()
            .asString()
            .replaceAll("\\r?\\n[ \\t]", "");
    }
}
