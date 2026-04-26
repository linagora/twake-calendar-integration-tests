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

package com.linagora.dav.contracts.cal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public abstract class ResourceCalendarVisibilityContract {

    public abstract DockerTwakeCalendarExtension extension();

    private CalDavClient calDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser alice;

    @BeforeEach
    void setUp() throws Exception {
        calDavClient = new CalDavClient(extension().davHttpClient());
        extension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .enableSharedCalendarModule()
            .block();
        bob = extension().newTestUser();
        alice = extension().newTestUser();
    }

    @Test
    void subscribedResourceCalendarShouldDisappearAfterDeletion() {
        // GIVEN: a resource with alice as admin
        OpenPaaSResource resource = extension().twakeCalendarProvisioningService()
            .createResource("meeting-room", "Meeting Room A", alice)
            .block();

        // GIVEN: the resource calendar is made publicly readable
        String technicalToken = extension().twakeCalendarProvisioningService().generateToken();
        setResourceCalendarPublicRight(resource, technicalToken, "{DAV:}read");

        // WHEN: Bob subscribes to the resource calendar
        String subscriptionId = UUID.randomUUID().toString();
        calDavClient.subscribeToSharedCalendar(bob, SubscribedCalendarRequest.builder()
            .id(subscriptionId)
            .sourceUserId(resource.id())
            .name("Meeting Room A")
            .color("#FF0000")
            .readOnly(true)
            .build());

        assertThat(calDavClient.findUserSubscribedCalendars(bob).collectList().block())
            .anyMatch(n -> n.path("dav:name").asText().equals("Meeting Room A"));

        // WHEN: Bob deletes the subscription
        calDavClient.deleteCalendar(bob, new CalendarURL(bob.id(), subscriptionId));

        // THEN: Bob no longer sees the resource calendar
        assertThat(calDavClient.findUserSubscribedCalendars(bob).collectList().block())
            .noneMatch(n -> n.path("dav:name").asText().equals("Meeting Room A"));
    }

    @Test
    void resourceAdminCalendarShouldDisappearWhenDeletedByAdmin() {
        // GIVEN: a resource with Bob as admin
        OpenPaaSResource resource = extension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Whiteboard Room", bob)
            .block();

        // GIVEN: Bob is delegated access to the resource calendar
        String technicalToken = extension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        CalendarURL resourceCalendarURL = calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .next().block();
        assertThat(resourceCalendarURL).isNotNull();

        // WHEN: Bob deletes the delegated resource calendar
        calDavClient.deleteCalendar(bob, resourceCalendarURL);

        // THEN: Bob no longer sees the resource calendar
        assertThat(calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .collectList().block()).isEmpty();
    }

    @Test
    void resourceCalendarShouldDisappearWhenAdminStatusIsRevoked() {
        // GIVEN: a resource with Bob as admin
        OpenPaaSResource resource = extension().twakeCalendarProvisioningService()
            .createResource("conference-room", "Conference Room", bob)
            .block();

        // GIVEN: Bob is delegated access to the resource calendar
        String technicalToken = extension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        assertThat(calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .collectList().block()).isNotEmpty();

        // WHEN: Bob's admin delegation is revoked
        revokeResourceAdmin(resource, bob, technicalToken);

        // THEN: Bob no longer sees the resource calendar
        assertThat(calDavClient.findUserCalendars(bob)
            .filter(url -> !url.base().equals(url.calendarId()))
            .collectList().block()).isEmpty();
    }

    private void setResourceCalendarPublicRight(OpenPaaSResource resource, String technicalToken, String publicRight) {
        String payload = "{\"public_right\":\"%s\"}".formatted(publicRight);
        extension().davHttpClient()
            .headers(headers -> headers
                .add("TwakeCalendarToken", technicalToken)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri("/calendars/" + resource.id() + "/" + resource.id() + ".json")
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .flatMap(body -> {
                    int status = response.status().code();
                    if (status == 200 || status == 204) {
                        return Mono.just(body);
                    }
                    return Mono.error(new RuntimeException("ACL request failed with HTTP " + status + ": " + body));
                }))
            .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(1))
                .filter(error -> StringUtils.containsAnyIgnoreCase(error.getMessage(), "Could not find node at path")))
            .block();
    }

    private void delegateResourceToAdmin(OpenPaaSResource resource, OpenPaasUser admin, String technicalToken) {
        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:%s",
                    "dav:read-write": true
                  }
                ],
                "remove": []
              }
            }
            """.formatted(admin.email());

        extension().davHttpClient()
            .headers(headers -> headers
                .add("TwakeCalendarToken", technicalToken)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri("/calendars/" + resource.id() + "/" + resource.id() + ".json")
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .flatMap(body -> {
                    int status = response.status().code();
                    if (status == 200 || status == 201 || status == 204) {
                        return Mono.just(body);
                    }
                    return Mono.error(new RuntimeException("HTTP " + status + ": " + body));
                }))
            .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(1))
                .filter(error -> StringUtils.containsAnyIgnoreCase(error.getMessage(), "Could not find node at path")))
            .block();
    }

    private void revokeResourceAdmin(OpenPaaSResource resource, OpenPaasUser admin, String technicalToken) {
        String payload = """
            {
              "share": {
                "set": [],
                "remove": [
                  {
                    "dav:href": "mailto:%s"
                  }
                ]
              }
            }
            """.formatted(admin.email());

        extension().davHttpClient()
            .headers(headers -> headers
                .add("TwakeCalendarToken", technicalToken)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri("/calendars/" + resource.id() + "/" + resource.id() + ".json")
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, content) -> content.asString()
                .defaultIfEmpty("")
                .flatMap(body -> {
                    int status = response.status().code();
                    if (status == 200 || status == 201 || status == 204) {
                        return Mono.just(body);
                    }
                    return Mono.error(new RuntimeException("HTTP " + status + ": " + body));
                }))
            .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(1))
                .filter(error -> StringUtils.containsAnyIgnoreCase(error.getMessage(), "Could not find node at path")))
            .block();
    }
}
