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

import static com.linagora.dav.TestUtil.body;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Streams;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class CalDavClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum DelegationRight {
        READ("\"dav:read\": true"),
        READ_WRITE("\"dav:read-write\":true"),
        ADMIN("\"dav:administration\": true");

        private final String value;

        DelegationRight(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public record CounterRequest(String calendarData,
                                 String sender,
                                 String recipient,
                                 String eventUid,
                                 int sequence) {
        private static final ObjectMapper mapper = new ObjectMapper();

        public String toJson() {
            ObjectNode root = mapper.createObjectNode();

            root.put("ical", calendarData);
            root.put("sender", sender);
            root.put("recipient", recipient);
            root.put("uid", eventUid);
            root.put("sequence", sequence);
            root.put("method", "COUNTER");

            try {
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final String CONTENT_TYPE_JSON = "application/json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public CalDavClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void upsertCalendarEvent(OpenPaasUser user, String eventUid, String initialCalendarData) {
        URI calendarURI = URI.create("/calendars/" + user.id() + "/" + user.id() + "/" + eventUid + ".ics");
        upsertCalendarEvent(user, calendarURI, initialCalendarData);
    }

    public void upsertCalendarEvent(OpenPaasUser user, CalendarURL calendarURL, String eventUid, String initialCalendarData) {
        URI calendarURI = URI.create(calendarURL.asUri() + "/" + eventUid + ".ics");
        upsertCalendarEvent(user, calendarURI, initialCalendarData);
    }

    public void upsertCalendarEvent(OpenPaasUser userRequest, URI calendarURI, String initialCalendarData) {
        httpClient.headers(headers -> userRequest.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri(calendarURI.toString())
            .send(TestUtil.body(initialCalendarData))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201 || response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when create/update calendar object
                        %s
                        """.formatted(response.status().code(), responseBody))));
            }).block();
    }

    public void upsertCalendarEvent(String entityId, String eventId, String calendarData, String token) {
        httpClient.headers(headers -> headers.add("TwakeCalendarToken", token))
            .put()
            .uri("/calendars/" + entityId + "/" + entityId + "/" + eventId + ".ics")
            .send(TestUtil.body(calendarData))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201 || response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when create/update calendar object
                        %s
                        """.formatted(response.status().code(), responseBody))));
            }).block();
    }

    public void upsertJsonCalendarEvent(OpenPaasUser openPaasUser, String eventId, String calendarData) {
        httpClient.headers(headers -> openPaasUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/calendar+json"))
            .put()
            .uri("/calendars/" + openPaasUser.id() + "/" + openPaasUser.id() + "/" + eventId + ".ics")
            .send(TestUtil.body(calendarData))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201 || response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when create/update calendar object
                        %s
                        """.formatted(response.status().code(), responseBody))));
            }).block();
    }

    public String getCalendarEvent(String entityId, String eventId, String token) {
        return httpClient.headers(headers -> headers.add("TwakeCalendarToken", token))
            .get()
            .uri("/calendars/" + entityId + "/" + entityId + "/" + eventId + ".ics")
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return responseContent.asByteArray().map(bytes -> new String(bytes, StandardCharsets.UTF_8));
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when create/update calendar object
                        %s
                        """.formatted(response.status().code(), responseBody))));
            }).block();
    }

    public String getCalendarEvent(OpenPaasUser userRequest, URI calendarURI) {
        return httpClient.headers(headers -> userRequest.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .get()
            .uri(calendarURI.toASCIIString())
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return responseContent.asByteArray().map(bytes -> new String(bytes, StandardCharsets.UTF_8));
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when create/update calendar object
                        %s
                        """.formatted(response.status().code(), responseBody))));
            }).block();
    }

    public void deleteCalendarEvent(OpenPaasUser user, String eventUid) {
        URI calendarURI = URI.create("/calendars/" + user.id() + "/" + user.id() + "/" + eventUid + ".ics");
        deleteCalendarEvent(user, calendarURI);
    }

    public void deleteCalendarEvent(OpenPaasUser user, CalendarURL calendarURL, String eventUid) {
        URI calendarURI = URI.create(calendarURL.asUri() + "/" + eventUid + ".ics");
        deleteCalendarEvent(user, calendarURI);
    }

    public void deleteCalendarEvent(OpenPaasUser user, URI calendarURI) {
        httpClient.headers(headers -> user.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri(calendarURI.toString())
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when delete calendar object
                        %s
                        """.formatted(response.status().code(), responseBody))));
            }).block();
    }

    public Optional<String> findFirstEventId(OpenPaasUser openPaaSUser) {
        return findUserCalendars(openPaaSUser)
            .flatMap(calendarURL -> findUserCalendarEventIds(openPaaSUser, calendarURL))
            .collectList()
            .blockOptional()
            .flatMap(e -> e.stream().findFirst());
    }

    public Flux<String> findUserCalendarEventIds(OpenPaasUser openPaaSUser, CalendarURL calendarURL) {
        return httpClient.headers(headers -> openPaaSUser.impersonatedBasicAuth(headers).add(HttpHeaderNames.CONTENT_TYPE, "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri(calendarURL.asUri().toString())
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 207) {
                    return responseContent.asByteArray();
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(errorBody -> Mono.error(new RuntimeException("""
                            Unexpected status code: %d when finding user calendar event ids in calendar '%s'
                            %s
                            """.formatted(response.status().code(), calendarURL.asUri(), errorBody))));
                }
            }).flatMapIterable(bytes -> {
                try {
                    return XMLUtil.extractEventIdsFromXml(bytes);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse XML response of finding user calendar event ids in calendar " + calendarURL.asUri(), e);
                }
            });
    }

    public Flux<CalendarURL> findUserCalendars(OpenPaasUser openPaaSUser) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + openPaaSUser.id() + ".json"
            + "?personal=true&sharedDelegationStatus=accepted&sharedPublicSubscription=true&withRights=true";
        return httpClient.headers(headers -> openPaaSUser.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return responseContent.asString(StandardCharsets.UTF_8).map(this::extractCalendarURLsFromResponse);
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(errorBody -> Mono.error(new RuntimeException("""
                            Unexpected status code: %d when finding user calendars for user '%s'
                            %s
                            """.formatted(response.status().code(), openPaaSUser.id(), errorBody))));
                }
            }).flatMapMany(Flux::fromIterable);
    }

    public Optional<String> findFirstEventId(String resourceId, OpenPaasUser openPaaSUser) {
        return findUserCalendarEventIds(resourceId, openPaaSUser)
            .collectList()
            .blockOptional()
            .flatMap(e -> e.stream().findFirst());
    }

    public Flux<String> findUserCalendarEventIds(String resourceId, OpenPaasUser openPaaSUser) {
        String calendarURI = "/calendars/" + resourceId + "/" + resourceId;
        return httpClient.headers(headers -> openPaaSUser.impersonatedBasicAuth(headers).add(HttpHeaderNames.CONTENT_TYPE, "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri(calendarURI)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 207) {
                    return responseContent.asByteArray();
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                        .flatMap(errorBody -> Mono.error(new RuntimeException("""
                            Unexpected status code: %d when finding user calendar event ids in calendar '%s'
                            %s
                            """.formatted(response.status().code(), calendarURI, errorBody))));
                }
            }).flatMapIterable(bytes -> {
                try {
                    return XMLUtil.extractEventIdsFromXml(bytes);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse XML response of finding user calendar event ids in calendar " + calendarURI, e);
                }
            });
    }

    public void postCounter(OpenPaasUser openPaaSUser, String attendeeEventUid, CounterRequest counterRequest) {
        URI uri = URI.create("/calendars/" + openPaaSUser.id() + "/" + openPaaSUser.id() + "/" + attendeeEventUid + ".ics");
        httpClient.headers(headers -> openPaaSUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/calendar+json")
                .add("Accept", "application/json, text/plain, */*")
                .add("x-http-method-override", "ITIP"))
            .request(HttpMethod.POST)
            .uri(uri.toString())
            .send(Mono.just(Unpooled.wrappedBuffer(counterRequest.toJson().getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when posting calendar object '%s'
                        %s
                        """.formatted(response.status().code(), uri.toString(), responseBody))));
            }).block();
    }

    public void grantDelegation(OpenPaasUser user, String calendarId, OpenPaasUser delegatedUser, DelegationRight right) {
        String uri = "/calendars/" + user.id() + "/" + calendarId + ".json";

        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:{email}",
                    {right}
                  }
                ],
                "remove": []
              }
            }
            """.replace("{email}", delegatedUser.email())
            .replace("{right}", right.getValue());

        sendDelegationRequest(user, uri, payload);
    }

    public void revokeDelegation(OpenPaasUser user, String calendarId, OpenPaasUser delegatedUser) {
        String uri = "/calendars/" + user.id() + "/" + calendarId + ".json";

        String payload = """
            {
                "share": {
                    "set": [],
                    "remove": [
                        {
                            "dav:href": "mailto:{email}"
                        }
                    ]
                }
            }
            """.replace("{email}", delegatedUser.email());

        sendDelegationRequest(user, uri, payload);
    }

    public DavResponse findEventsByTime(OpenPaasUser user, String start, String end) {
        return findEventsByTime(user, CalendarURL.from(user.id()), start, end);
    }

    public DavResponse findEventsByTime(OpenPaasUser user, String baseId, String calendarId, String start, String end) {
        return findEventsByTime(user, new CalendarURL(baseId, calendarId), start, end);
    }

    public DavResponse findEventsByTime(OpenPaasUser user, CalendarURL calendarURL, String start, String end) {
        String json = """
            {
                "match": {
                    "start": "{start}",
                    "end": "{end}"
                }
            }
            """.replace("{start}", start)
            .replace("{end}", end);

        return getCalendarEvents(user, calendarURL, json);
    }

    public DavResponse findEventsBySyncToken(OpenPaasUser user, CalendarURL calendarURL, String syncToken) {
        String json = """
            {
                "sync-token": "{syncToken}"
            }
            """.replace("{syncToken}", syncToken);

        return getCalendarEvents(user, calendarURL, json);
    }

    private DavResponse getCalendarEvents(OpenPaasUser user, CalendarURL calendarURL, String json) {
        return httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add("Depth", 0)
                .add("Accept", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(calendarURL.asUri() + ".json")
            .send(body(json))
            .responseSingle((response, content) -> content.asString()
                .map(stringContent -> new DavResponse(response.status().code(), stringContent)))
            .block();
    }

    private void sendDelegationRequest(OpenPaasUser user, String uri, String payload) {
        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when sharing calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    private List<CalendarURL> extractCalendarURLsFromResponse(String json) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            ArrayNode calendars = (ArrayNode) node.path("_embedded").path("dav:calendar");
            return Streams.stream(calendars.elements())
                .map(calendarNode -> calendarNode.path("_links").path("self").path("href").asText())
                .filter(href -> !href.isEmpty())
                .map(this::parseCalendarHref)
                .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse calendar list JSON", e);
        }
    }

    private CalendarURL parseCalendarHref(String href) {
        String[] parts = href.split("/");
        if (parts.length != 4) {
            throw new RuntimeException("Found an invalid calendar href in JSON response: " + href);
        }
        String userId = parts[2];
        String calendarIdWithExt = parts[3];
        String calendarId = calendarIdWithExt.replace(".json", "");
        return new CalendarURL(userId, calendarId);
    }

    public void subscribeToSharedCalendar(OpenPaasUser user, SubscribedCalendarRequest subscribedCalendarRequest) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + user.id() + ".json";

        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*"))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(subscribedCalendarRequest.serialize().getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 201) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when subscribing to shared calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    public void deleteSubscribedCalendar(OpenPaasUser user, String calendarURI) {
        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json"))
            .request(HttpMethod.DELETE)
            .uri(calendarURI)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when deleting subscribed calendar '%s'
                        %s
                        """.formatted(response.status().code(), calendarURI, errorBody))));
            }).block();
    }

    public void updateCalendarAcl(OpenPaasUser user, String publicRight) {
        updateCalendarAcl(user, CalendarURL.from(user.id()), publicRight);
    }

    public void updateCalendarAcl(OpenPaasUser user, CalendarURL calendarURL, String publicRight) {
        updateCalendarAcl(user, URI.create(calendarURL.asUri() + ".json"), publicRight);
    }

    /**
     * <p>Examples of {@code public_right} values:
     * <ul>
     *     <li><b>Hide calendar</b>:
     *     <pre>{@code
     *     {"public_right": ""}
     *     }</pre>
     *     </li>
     *
     *     <li><b>See all details</b>:
     *     <pre>{@code
     *     {"public_right": "{DAV:}read"}
     *     }</pre>
     *     </li>
     *
     *     <li><b>Edit (full access)</b>:
     *     <pre>{@code
     *     {"public_right": "{DAV:}write"}
     *     }</pre>
     *     </li>
     * </ul>
     */
    public void updateCalendarAcl(OpenPaasUser user, URI calendarURL, String publicRight) {
        String uri = calendarURL.toString();
        String payload = """
            {
              "public_right":"%s"
            }
            """.formatted(publicRight);

        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*")
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json"))
            .request(HttpMethod.valueOf("ACL"))
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when updating ACL for calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    public Flux<JsonNode> reportCalendarEvents(OpenPaasUser user, String calendarURI, Instant start, Instant end) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
        String payload = """
            {
              "match": {
                "start": "%s",
                "end": "%s"
              }
            }
            """.formatted(formatter.format(start), formatter.format(end));

        return httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*")
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri(calendarURI)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return responseContent.asString(StandardCharsets.UTF_8);
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(""))
                    .flatMap(errorBody -> Mono.error(new RuntimeException(
                        "Unexpected status code: %d when reporting events for calendar '%s'%n%s"
                            .formatted(response.status().code(), calendarURI, errorBody))));
            })
            .flatMapMany(body -> {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(body);
                    ArrayNode items = (ArrayNode) root.path("_embedded").path("dav:item");
                    return Flux.fromIterable(items);
                } catch (Exception e) {
                    return Flux.error(new RuntimeException("Failed to parse REPORT response for calendar " + calendarURI, e));
                }
            });
    }

    public Flux<JsonNode> findUserSubscribedCalendars(OpenPaasUser requester) {
        return findUserCalendarsWithOptions(requester, requester.id(), true, false, true);
    }

    public Flux<JsonNode> findUserSubscribedCalendars(OpenPaasUser requester, String targetUserId) {
        return findUserCalendarsWithOptions(requester, targetUserId, true, false, true);
    }

    public Flux<JsonNode> findUserCalendarsWithOptions(OpenPaasUser requester, String targetUserId,
                                                       boolean sharedPublicSubscription,
                                                       boolean withDelegation,
                                                       boolean withRights) {
        StringBuilder uriBuilder = new StringBuilder(CalendarURL.CALENDAR_URL_PATH_PREFIX)
            .append("/")
            .append(targetUserId)
            .append(".json")
            .append("?");

        if (sharedPublicSubscription) {
            uriBuilder.append("sharedPublicSubscription=true&");
        }
        if (withRights) {
            uriBuilder.append("withRights=true&");
        }
        if (withDelegation) {
            uriBuilder.append("sharedDelegationStatus=accepted&");
        }

        // Remove trailing '&' or '?' if present
        String uri = uriBuilder.toString().replaceAll("[&?]$", "");

        return httpClient.headers(headers -> requester.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
            .request(HttpMethod.GET)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return responseContent.asString(StandardCharsets.UTF_8);
                } else {
                    return responseContent.asString(StandardCharsets.UTF_8)
                        .switchIfEmpty(Mono.just(""))
                        .flatMap(errorBody -> Mono.error(new RuntimeException(
                            "Unexpected status code: %d when finding calendars for user '%s'%n%s"
                                .formatted(response.status().code(), targetUserId, errorBody))));
                }
            })
            .flatMapMany(json -> {
                try {
                    JsonNode root = MAPPER.readTree(json);
                    ArrayNode calendars = (ArrayNode) root.path("_embedded").path("dav:calendar");
                    return Flux.fromIterable(calendars);
                } catch (Exception e) {
                    return Flux.error(new RuntimeException("Failed to parse calendars JSON", e));
                }
            });
    }

    public void createNewCalendar(OpenPaasUser user, String id, String name, int order) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + user.id() + ".json";

        String payload = """
            {
              "id": "%s",
              "dav:name": "%s",
              "apple:color": "#FF0000",
              "caldav:description": "Calendar for %s",
              "apple:order": %d
            }
            """.formatted(id, name, name, order);

        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON)
                .add(HttpHeaderNames.ACCEPT, CONTENT_TYPE_JSON))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_CREATED) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when creating calendar '%s' for user '%s'
                        %s
                        """.formatted(response.status().code(), id, user.id(), responseBody))));
            })
            .block();
    }

    public void deleteCalendar(OpenPaasUser user, CalendarURL calendarURL) {
        String uri = calendarURL.asUri() + ".json";

        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_JSON))
            .request(HttpMethod.DELETE)
            .uri(uri)
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == HttpStatus.SC_NO_CONTENT) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(responseBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when deleting calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, responseBody))));
            })
            .block();
    }

    /**
     * Examples of rights:
     *  - Administration (manage + admin): "dav:administration"
     *  - Read/Write: "dav:read-write"
     *  - Read Only: "dav:read"
     */
    public void delegateCalendar(OpenPaasUser owner, String calendarId, OpenPaasUser delegate, String rightKey) {
        String uri = CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + owner.id() + "/" + calendarId + ".json";

        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:%s",
                    "%s": true
                  }
                ],
                "remove": []
              }
            }
            """.formatted(delegate.email(), rightKey);

        httpClient.headers(headers -> owner.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8")
                .add(HttpHeaderNames.ACCEPT, "application/json"))
            .request(HttpMethod.POST)
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 200) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(""))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when delegating calendar '%s' to %s
                        %s
                        """.formatted(response.status().code(), uri, delegate.email(), errorBody))));
            })
            .block();
    }

    public void updateCalendarSetting(OpenPaasUser user, CalendarURL calendarURL, String name, String color) {
        String uri = calendarURL.asUri() + ".json";
        String payload = """
            {
              "id": "{id}",
              "dav:name": "{name}",
              "apple:color": "{color}"
            }
            """.replace("{id}", calendarURL.calendarId())
            .replace("{name}", name)
            .replace("{color}", color);

        httpClient.headers(headers -> user.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/json, text/plain, */*")
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json"))
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri(uri)
            .send(Mono.just(Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when updating setting for calendar '%s'
                        %s
                        """.formatted(response.status().code(), uri, errorBody))));
            }).block();
    }

    public Mono<Void> sendITIPRequest(OpenPaasUser requester, URI recipientCalendarUri, String jsonBody) {
        return httpClient.headers(headers -> requester.impersonatedBasicAuth(headers)
                .add(HttpHeaderNames.ACCEPT, "application/json")
                .add(HttpHeaderNames.CONTENT_TYPE, "application/json"))
            .request(HttpMethod.valueOf("ITIP"))
            .uri(recipientCalendarUri.toString())
            .send(Mono.just(Unpooled.wrappedBuffer(jsonBody.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(body -> Mono.error(new RuntimeException("""
                        Unexpected status code: %d when sending ITIP to '%s'
                        %s
                        """.formatted(response.status().code(), recipientCalendarUri, body))));
            });
    }
}
