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

import static com.linagora.dav.CalendarAssert.assertThatCalendar;
import static com.linagora.dav.DockerTwakeCalendarExtension.QUEUE_NAME;
import static com.linagora.dav.TestUtil.awaitAtMost;
import static com.linagora.dav.TestUtil.awaitCalendarEntries;
import static com.linagora.dav.TestUtil.awaitEvery500Millis;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.xmlunit.assertj3.XmlAssert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.AmqpTestHelper;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.JsonCalendarData;
import com.linagora.dav.JsonCalendarEventData;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TwakeCalendarEvent;
import com.linagora.dav.XMLUtil;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.javacrumbs.jsonunit.core.Option;

public abstract class CalDavDelegationContract {
    private static final Map<String, String> DAV_NAMESPACES = Map.of("d", "DAV:");

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser alice;
    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());

        RestAssured.reset();
        RestAssured.requestSpecification = dockerExtension().davRequestSpecification()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON);
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        bob = dockerExtension().newTestUser();
        alice = dockerExtension().newTestUser();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dav:read", "dav:freebusy"})
    protected void cannotChangeAnotherUsersCalendarInviteStatus(String right) {
        // Given Alice shares her calendar with Bob with read-only or free/busy access.
        OpenPaasUser cedric = dockerExtension().newTestUser();
        calDavClient.delegateCalendar(alice, alice.id(), bob, right);
        JsonNode calendars = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .queryParam("withFreeBusy", true)
            .queryParam("sharedDelegationStatus", "accepted")
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .statusCode(SC_OK)
            .extract().as(JsonNode.class).path("_embedded").path("dav:calendar");
        assertThatJson(calendars).isArray().hasSize(1);
        String delegatedPath = calendars.get(0).path("_links").path("self").path("href").asText();
        String reply = """
            {"invite-reply": {"invitestatus": "noresponse"}}
            """;

        // When Cedric tries to hide Bob's delegated calendar, deny the mutation.
        given()
            .header("Authorization", cedric.impersonatedBasicAuth())
            .body(reply)
        .when()
            .post(delegatedPath)
        .then()
            .statusCode(SC_FORBIDDEN);

        // Then Bob can still change his own invitation status without write access to Alice's calendar.
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .body(reply)
        .when()
            .post(delegatedPath)
        .then()
            .statusCode(SC_OK);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void listCalendarsShouldShowDelegatedCalendar(DelegationRight right) {

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // THEN a copy of bob calendar is created in Alice calendar list
        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + ".json")
        .then()
            .extract().asString();
        String delegatedSource = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        assertThatJson(response)
            .inPath("_embedded.dav:calendar")
            .isArray()
            .filteredOn(calendar -> calendar.toString().contains("\"calendarserver:delegatedsource\":\"" + delegatedSource + "\""))
            .singleElement()
            .satisfies(calendar -> assertThatJson(calendar)
                .when(Option.IGNORING_EXTRA_FIELDS, Option.IGNORING_EXTRA_ARRAY_ITEMS, Option.IGNORING_ARRAY_ORDER)
                .isEqualTo("""
                    {
                        "calendarserver:delegatedsource": "{delegatedSource}",
                        "invite": [
                            {
                                "principal": "principals/users/{ownerId}",
                                "access": 1
                            },
                            {
                                "principal": "principals/users/{delegateId}",
                                "access": {shareAccess}
                            }
                        ],
                        "acl": [
                            {
                                "privilege": "{DAV:}read",
                                "principal": "principals/users/{delegateId}"
                            },
                            {
                                "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                                "principal": "{DAV:}authenticated"
                            }
                        ]
                    }
                    """
                    .replace("{delegatedSource}", delegatedSource)
                    .replace("{ownerId}", bob.id())
                    .replace("{delegateId}", alice.id())
                    .replace("{shareAccess}", String.valueOf(right.getShareAccess()))));
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void listCalendarsShouldShowDelegatedCalendarInDav(DelegationRight right) throws Exception {

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        // AND: Alice see the subscription in her calendars
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", alice.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/calendars/" + alice.id())
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> actual = XMLUtil.extractMultipleValueByXPath(responseBody, "//d:multistatus/d:response/d:href", Map.of("d", "DAV:"));
        AssertionsForInterfaceTypes.assertThat(actual).contains(calendarURL.asUri() + "/");
        // AND: the subscription do not contain event
        String calendarCapabilitiesResponseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", alice.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", calendarURL.asUri().toString())
        .then()
            .statusCode(207)
            .extract().asString();

        assertThat(calendarCapabilitiesResponseBody).contains("<cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set>");
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void listCalendarsShouldShowDelegatedCalendarEventsInDav(DelegationRight right) throws Exception {

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);
        // AND: Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String description = "Important meeting with Alice";
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Bob's readonly event
            DESCRIPTION:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, description);
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        // THEN: Alice can report the event in her subscription
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", alice.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">{eventUid}</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.replace("{eventUid}", eventUid))
        .when()
            .request("REPORT", calendarURL.asUri().toString())
        .then()
            .statusCode(207)
            .extract().asString();

        String actual = XMLUtil.extractByXPath(
            responseBody,
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));

        assertThatCalendar(actual).isEqualTo(calendarData);
    }

    // Sharees read the events of a shared calendar through the owner's path
    // (calendarserver:delegatedsource), e.g. REPORT /calendars/{ownerId}/{calendarId}.json.
    // Sharees with read, read-write or administration access are granted {DAV:}read on the
    // owner's calendar node and its events. Writing there stays forbidden, and private calendars
    // stay unreadable for anyone else.

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void shareeCanJsonReportOwnerSourceCalendarByTimeRange(DelegationRight right) throws Exception {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob reads Alice's calendar through its delegated source, like the frontend does
        Response response = calDavClient.findEventsByTime(bob, CalendarURL.from(alice.id()),
            "20300310T000000", "20300510T000000");

        // THEN Bob gets the event
        response.then().statusCode(200);
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary()).contains("Sprint planning #01");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void shareeCanReadOwnerSourceCalendarWithXmlReport(DelegationRight right) throws Exception {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob queries Alice's SOURCE calendar (not his copy) with a CalDAV REPORT
        String responseBody = xmlCalendarQueryByUid(bob, CalendarURL.from(alice.id()).asUri().toString(), eventUid)
            .then().statusCode(207)
            .extract().asString();

        // THEN Bob gets the event
        String calendarData = XMLUtil.extractByXPath(responseBody, "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));
        assertThat(calendarData)
            .contains("UID:" + eventUid)
            .contains("SUMMARY:Sprint planning #01");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void shareeCanGetEventOfOwnerSourceCalendar(DelegationRight right) {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob gets the event from Alice's SOURCE calendar
        String responseBody = getEvent(bob, alice, eventUid)
            .then().statusCode(200)
            .extract().asString();

        // THEN Bob gets the event
        assertThat(responseBody)
            .contains("UID:" + eventUid)
            .contains("SUMMARY:Sprint planning #01");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void shareeCanSyncTokenReportOwnerSourceCalendar(DelegationRight right) throws Exception {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob synchronizes Alice's SOURCE calendar
        Response response = calDavClient.findEventsBySyncToken(bob, CalendarURL.from(alice.id()), INITIAL_SYNC_TOKEN);

        // THEN Bob gets the event
        response.then().statusCode(207);
        assertThat(JsonCalendarData.from(response.asString()).items())
            .extracting(JsonCalendarData.DavItem::href)
            .contains(aliceSourceEventPath(eventUid));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "PRIVATE, READ",
        "PRIVATE, READ_WRITE",
        "PRIVATE, ADMIN",
        "CONFIDENTIAL, READ",
        "CONFIDENTIAL, READ_WRITE",
        "CONFIDENTIAL, ADMIN"
    })
    void privateOrConfidentialEventShouldBeAnonymizedWhenShareeJsonReportsOwnerSourceCalendar(String eventClass, DelegationRight right) throws Exception {
        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent(eventClass);
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob reads Alice's SOURCE calendar with a JSON REPORT
        Response response = calDavClient.findEventsByTime(bob, CalendarURL.from(alice.id()),
            "20300310T000000", "20300510T000000");

        // THEN Bob sees an anonymized version of the event
        response.then().statusCode(200);
        String responseBody = response.asString();
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(responseBody);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary()).contains("Busy");
        assertThatDoesNotLeakPrivateEventDetails(responseBody);
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "PRIVATE, READ",
        "PRIVATE, READ_WRITE",
        "PRIVATE, ADMIN",
        "CONFIDENTIAL, READ",
        "CONFIDENTIAL, READ_WRITE",
        "CONFIDENTIAL, ADMIN"
    })
    void privateOrConfidentialEventShouldBeAnonymizedWhenShareeXmlReportsOwnerSourceCalendar(String eventClass, DelegationRight right) throws Exception {
        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent(eventClass);
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob queries Alice's SOURCE calendar with a CalDAV REPORT
        String responseBody = xmlCalendarQueryByUid(bob, CalendarURL.from(alice.id()).asUri().toString(), eventUid)
            .then().statusCode(207)
            .extract().asString();

        // THEN Bob sees an anonymized version of the event
        String calendarData = XMLUtil.extractByXPath(responseBody, "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));
        CalendarUtil.CalendarExtractor actualCalendar = CalendarUtil.toExtractor(calendarData);
        assertThat(actualCalendar.extractPropertyValue(Property.SUMMARY)).isEqualTo("Busy");
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.DESCRIPTION)).isEmpty();
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.LOCATION)).isEmpty();
        assertThatDoesNotLeakPrivateEventDetails(responseBody);
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "PRIVATE, READ",
        "PRIVATE, READ_WRITE",
        "PRIVATE, ADMIN",
        "CONFIDENTIAL, READ",
        "CONFIDENTIAL, READ_WRITE",
        "CONFIDENTIAL, ADMIN"
    })
    void privateOrConfidentialEventShouldBeAnonymizedWhenShareeGetsEventOfOwnerSourceCalendar(String eventClass, DelegationRight right) {
        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent(eventClass);
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob gets the event from Alice's SOURCE calendar
        String responseBody = getEvent(bob, alice, eventUid)
            .then().statusCode(200)
            .extract().asString();

        // THEN Bob sees an anonymized version of the event
        CalendarUtil.CalendarExtractor actualCalendar = CalendarUtil.toExtractor(responseBody);
        assertThat(actualCalendar.extractPropertyValue(Property.SUMMARY)).isEqualTo("Busy");
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.DESCRIPTION)).isEmpty();
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.LOCATION)).isEmpty();
        assertThatDoesNotLeakPrivateEventDetails(responseBody);
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "PRIVATE, READ",
        "PRIVATE, READ_WRITE",
        "PRIVATE, ADMIN",
        "CONFIDENTIAL, READ",
        "CONFIDENTIAL, READ_WRITE",
        "CONFIDENTIAL, ADMIN"
    })
    void privateOrConfidentialEventShouldBeAnonymizedWhenShareeSyncTokenReportsOwnerSourceCalendar(String eventClass, DelegationRight right) throws Exception {
        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent(eventClass);
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob synchronizes Alice's SOURCE calendar
        Response response = calDavClient.findEventsBySyncToken(bob, CalendarURL.from(alice.id()), INITIAL_SYNC_TOKEN);

        // THEN Bob does not see the details of the event
        response.then().statusCode(207);
        String responseBody = response.asString();
        assertThat(JsonCalendarData.from(responseBody).items())
            .filteredOn(item -> item.href().equals(aliceSourceEventPath(eventUid)))
            .flatExtracting(JsonCalendarData.DavItem::events)
            .allSatisfy(event -> assertThat(event.summary()).contains("Busy"));
        assertThatDoesNotLeakPrivateEventDetails(responseBody);
    }

    @Test
    void userWithoutShareCannotReadOwnerPrivateSourceCalendar() {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar with Bob only
        calDavClient.grantDelegation(alice, alice.id(), bob, DelegationRight.READ_WRITE);
        OpenPaasUser charlie = dockerExtension().newTestUser();

        // THEN Charlie can read Alice's calendar neither with JSON REPORT, XML REPORT nor GET
        assertThatCannotReadAliceSourceCalendar(charlie, eventUid);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void formerShareeCannotReadOwnerPrivateSourceCalendarAfterRevocation(DelegationRight right) {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar with Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Alice revokes the share
        calDavClient.revokeDelegation(alice, alice.id(), bob);

        // THEN Bob can no longer read Alice's calendar
        assertThatCannotReadAliceSourceCalendar(bob, eventUid);
    }

    @Test
    void freeBusyShareeCannotReadOwnerPrivateSourceCalendar() {
        // GIVEN Alice has an event in her private calendar
        String eventUid = givenAlicePrivateSourceCalendarWithEvent("PUBLIC");
        // AND Alice shares her calendar free-busy only with Bob
        calDavClient.delegateCalendar(alice, alice.id(), bob, "dav:freebusy");

        // THEN Bob cannot read the events of Alice's calendar
        assertThatCannotReadAliceSourceCalendar(bob, eventUid);
    }

    private static final String INITIAL_SYNC_TOKEN = "http://sabre.io/ns/sync/1";

    private String givenAlicePrivateSourceCalendarWithEvent(String eventClass) {
        calDavClient.updateCalendarAcl(alice, "");

        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T080000Z
            DTSTART;X-SECRET=secret-start-parameter:20300411T100000Z
            DTEND:20300411T110000Z
            SUMMARY:Sprint planning #01
            DESCRIPTION:Confidential information that Bob should not see
            LOCATION:Secret Room
            X-PRIVATE-NOTE:Private custom property
            CLASS:%s
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Secret alarm text
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, eventClass);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);
        return eventUid;
    }

    private String aliceSourceEventPath(String eventUid) {
        return CalendarURL.from(alice.id()).asUri() + "/" + eventUid + ".ics";
    }

    private void assertThatDoesNotLeakPrivateEventDetails(String body) {
        assertThat(body)
            .doesNotContain("Sprint planning #01")
            .doesNotContain("Confidential information that Bob should not see")
            .doesNotContain("Secret Room")
            .doesNotContain("Secret alarm text")
            .doesNotContain("secret-start-parameter")
            .doesNotContain("Private custom property");
    }

    private void assertThatCannotReadAliceSourceCalendar(OpenPaasUser user, String eventUid) {
        calDavClient.findEventsByTime(user, CalendarURL.from(alice.id()),
                "20300310T000000", "20300510T000000")
            .then().statusCode(SC_FORBIDDEN);
        xmlCalendarQueryByUid(user, CalendarURL.from(alice.id()).asUri().toString(), eventUid)
            .then().statusCode(SC_FORBIDDEN);
        getEvent(user, alice, eventUid)
            .then().statusCode(SC_FORBIDDEN);
    }

    private Response getEvent(OpenPaasUser user, OpenPaasUser owner, String eventUid) {
        return given()
            .header("Authorization", user.impersonatedBasicAuth())
            .accept("text/calendar")
        .when()
            .get(CalendarURL.from(owner.id()).asUri() + "/" + eventUid + ".ics");
    }

    private Response xmlCalendarQueryByUid(OpenPaasUser user, String calendarUri, String eventUid) {
        return given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", user.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))
        .when()
            .request("REPORT", calendarUri);
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void bobCannotUpsertInAliceSourceCalendarDirectlyWhenDelegated(DelegationRight right) {

        // GIVEN Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob tries to create an event directly in Alice's SOURCE calendar (not his copy)
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Bob's event in Alice calendar
            DESCRIPTION:Event created by Bob directly in Alice source calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        URI aliceSourceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics");

        // THEN Bob gets a 403 Forbidden
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(bob, aliceSourceCalendarEventUri, calendarData))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @ParameterizedTest
    @EnumSource(DelegationRight.class)
    void bobCannotDeleteInAliceSourceCalendarDirectlyWhenDelegated(DelegationRight right) {

        // GIVEN Alice has an event in her calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Alice's event
            DESCRIPTION:Event in Alice source calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        // AND Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // WHEN Bob tries to delete the event directly in Alice's SOURCE calendar (not his copy)
        URI aliceSourceCalendarEventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics");

        // THEN Bob gets a 403 Forbidden
        assertThatThrownBy(() -> calDavClient.deleteCalendarEvent(bob, aliceSourceCalendarEventUri))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegateCannotModifyOwnerPrivateEventViaDelegatedCalendar(DelegationRight right) {
        // GIVEN Bob has a private event in his calendar
        String eventUid = givenBobPrivateEvent();
        // AND Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);
        URI delegatedEventUri = calDavClient.findDelegatedCalendar(alice, bob.id()).eventHref(eventUid);

        // WHEN Alice modifies the private event via her delegated calendar
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(tamperedPrivateIcsEvent(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put(delegatedEventUri.toString())
        .then()
            .statusCode(SC_FORBIDDEN);

        // THEN Bob's event is left untouched
        assertThatBobPrivateEventIsUnchanged(eventUid);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegateCannotModifyOwnerPrivateEventViaDelegatedCalendarWithJson(DelegationRight right) {
        // GIVEN Bob has a private event in his calendar
        String eventUid = givenBobPrivateEvent();
        // AND Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);
        URI delegatedEventUri = calDavClient.findDelegatedCalendar(alice, bob.id()).eventHref(eventUid);

        // WHEN Alice modifies the private event via her delegated calendar using the JSON API
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/calendar+json")
            .body(tamperedPrivateJsonEvent(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put(delegatedEventUri.toString())
        .then()
            .statusCode(SC_FORBIDDEN);

        // THEN Bob's event is left untouched
        assertThatBobPrivateEventIsUnchanged(eventUid);
    }

    @Test
    void delegateCannotDeleteOwnerPrivateEventViaDelegatedCalendar() {
        // GIVEN Bob has a private event in a calendar delegated to Alice with write access
        String eventUid = givenBobPrivateEvent();
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);
        URI delegatedEventUri = calDavClient.findDelegatedCalendar(alice, bob.id()).eventHref(eventUid);

        // WHEN Alice deletes the private event via her delegated calendar
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
        .when()
            .delete(delegatedEventUri.toString())
        .then()
            .statusCode(SC_FORBIDDEN);

        // THEN the deletion is rejected and Bob's event still exists
        assertThatBobPrivateEventIsUnchanged(eventUid);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegateCannotModifyOwnerPrivateEventInOwnerSourceCalendar(DelegationRight right) {
        // GIVEN Bob has a private event in his calendar
        String eventUid = givenBobPrivateEvent();
        // AND Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // WHEN Alice modifies the private event directly in Bob's SOURCE calendar
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(tamperedPrivateIcsEvent(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put(CalendarURL.from(bob.id()).eventHref(eventUid).toString())
        .then()
            .statusCode(SC_FORBIDDEN);

        // THEN Bob's event is left untouched
        assertThatBobPrivateEventIsUnchanged(eventUid);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegateCannotModifyOwnerPrivateEventInOwnerSourceCalendarWithJson(DelegationRight right) {
        // GIVEN Bob has a private event in his calendar
        String eventUid = givenBobPrivateEvent();
        // AND Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // WHEN Alice modifies the private event directly in Bob's SOURCE calendar using the JSON API
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/calendar+json")
            .body(tamperedPrivateJsonEvent(eventUid).getBytes(StandardCharsets.UTF_8))
        .when()
            .put(CalendarURL.from(bob.id()).eventHref(eventUid).toString())
        .then()
            .statusCode(SC_FORBIDDEN);

        // THEN Bob's event is left untouched
        assertThatBobPrivateEventIsUnchanged(eventUid);
    }

    private String givenBobPrivateEvent() {
        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(bob, eventUid, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T080000Z
            DTSTART:20300411T100000Z
            DTEND:20300411T110000Z
            SUMMARY:Bob private meeting
            DESCRIPTION:Bob private description
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid));
        return eventUid;
    }

    private static String tamperedPrivateIcsEvent(String eventUid) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T090000Z
            DTSTART:20300412T100000Z
            DTEND:20300412T110000Z
            SUMMARY:Tampered by delegate
            DESCRIPTION:Tampered description
            CLASS:PRIVATE
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
    }

    private static String tamperedPrivateJsonEvent(String eventUid) {
        return """
            ["vcalendar",[],[["vevent",[
                ["uid",{},"text","%s"],
                ["dtstamp",{},"date-time","2030-04-01T09:00:00Z"],
                ["dtstart",{},"date-time","2030-04-12T10:00:00Z"],
                ["dtend",{},"date-time","2030-04-12T11:00:00Z"],
                ["summary",{},"text","Tampered by delegate"],
                ["description",{},"text","Tampered description"],
                ["class",{},"text","PRIVATE"]
            ],[]]]]
            """.formatted(eventUid);
    }

    private void assertThatBobPrivateEventIsUnchanged(String eventUid) {
        assertThat(calDavClient.getCalendarEvent(bob, CalendarURL.from(bob.id()).eventHref(eventUid)))
            .contains("SUMMARY:Bob private meeting")
            .contains("DESCRIPTION:Bob private description")
            .doesNotContain("Tampered");
    }

    @Test
    void listCalendarsShouldNotShowDelegatedCalendarWhenDelegationHasBeenRevoked() {

        // GIVEN Alice has a copy of bob calendar is created in Alice calendar list
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        // WHEN Bob revokes rights for Alice
        calDavClient.revokeDelegation(bob, bob.id(), alice);

        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + alice.id() + ".json")
        .then()
            .extract().asString();


        // THEN a copy of bob calendar is removed from Alice calendar list
        assertThat(response).doesNotContain("\"calendarserver:delegatedsource\":\"\\/calendars\\/" + bob.id() + "\\/" + bob.id() + ".json\"");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void listCalendarsShouldShowSharingRightOfDelegation(DelegationRight right) {

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // THEN the sharing right is shown on Bob source calendar
        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .extract().asString();
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .satisfies(calendar -> assertThatJson(calendar)
                .when(Option.IGNORING_EXTRA_FIELDS, Option.IGNORING_EXTRA_ARRAY_ITEMS, Option.IGNORING_ARRAY_ORDER)
                .isEqualTo("""
                    {
                        "_links": {
                            "self": {
                                "href": "/calendars/{ownerId}/{ownerId}.json"
                            }
                        },
                        "invite": [
                            {
                                "principal": "principals/users/{ownerId}",
                                "access": 1
                            },
                            {
                                "principal": "principals/users/{delegateId}",
                                "access": {shareAccess}
                            }
                        ],
                        "acl": [
                            {
                                "privilege": "{DAV:}share",
                                "principal": "principals/users/{ownerId}"
                            },
                            {
                                "privilege": "{DAV:}write",
                                "principal": "principals/users/{ownerId}"
                            },
                            {
                                "privilege": "{DAV:}read",
                                "principal": "principals/users/{ownerId}"
                            }
                        ]
                    }
                    """
                    .replace("{ownerId}", bob.id())
                    .replace("{delegateId}", alice.id())
                    .replace("{shareAccess}", String.valueOf(right.getShareAccess()))));
    }

    @Test
    void copiedCalendarShouldContainsExistingEvent() throws JsonProcessingException {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(testUser2, testUser.id());
        Response response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegatedUserCannotReadPrivateEventDetails(DelegationRight right) throws JsonProcessingException {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, right);
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(testUser2, testUser.id());
        Response response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Busy");
    }

    @Test
    void canExportWhenCalendarIsReadable() {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/calendars/" + testUser.id() + "/" + testUser.id() + "?export")
        .then()
            .statusCode(200);
    }

    @Test
    void canExportWhenCalendarIsWritable() {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .clazz("PRIVATE")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ_WRITE);

        given()
            .header("Authorization", testUser.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get("/calendars/" + testUser.id() + "/" + testUser.id() + "?export")
        .then()
            .statusCode(200);
    }

    @Test
    void delegateExportShouldHideOwnerPrivateEventDetails() {
        // GIVEN Bob shares a calendar containing a private event with Alice
        String eventUid = givenBobPrivateEvent();
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);
        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice exports Bob's calendar through her delegated copy
        String responseBody = given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .accept("*/*")
        .when()
            .get(delegatedCalendar.asUri() + "?export")
        .then()
            .statusCode(200)
            .extract().asString();

        // THEN the export contains only the sanitized event details
        assertThat(responseBody)
            .contains("UID:" + eventUid, "SUMMARY:Busy")
            .doesNotContain("Bob private meeting", "Bob private description");
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "private, READ",
        "PRIVATE, READ_WRITE",
        "PRIVATE, ADMIN",
        "CONFIDENTIAL, READ",
        "CONFIDENTIAL, READ_WRITE",
        "CONFIDENTIAL, ADMIN"
    })
    public void privateOrConfidentialEventShouldBeAnonymizedInDavReport(String eventClass, DelegationRight right) throws Exception {

        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART;X-SECRET=secret-start-parameter:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Alice's secret meeting
            DESCRIPTION:Confidential information that Bob should not see
            LOCATION:Secret Room
            X-PRIVATE-NOTE:Private custom property
            CLASS:%s
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Secret alarm text
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, eventClass);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        // WHEN Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // THEN Bob can access the delegated calendar
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(bob, alice.id());

        // WHEN Bob queries the event via CalDAV REPORT
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))
        .when()
            .request("REPORT", calendarURL.asUri().toString())
        .then()
            .statusCode(207)
            .extract().asString();


        // THEN Bob sees an anonymized version of the event
        String calendarDataResponse = XMLUtil.extractByXPath(
            responseBody,
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));

        CalendarUtil.CalendarExtractor actualCalendar = CalendarUtil.toExtractor(calendarDataResponse);

        // The event should be anonymized: SUMMARY = "Busy", no DESCRIPTION, no LOCATION
        assertThat(actualCalendar.extractPropertyValue(Property.SUMMARY)).isEqualTo("Busy");
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.DESCRIPTION)).isEmpty();
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.LOCATION)).isEmpty();
        assertThat(responseBody)
            .contains("DTSTAMP:20250929T080000Z")
            .doesNotContain("BEGIN:VALARM")
            .doesNotContain("Secret alarm text")
            .doesNotContain("secret-start-parameter")
            .doesNotContain("Private custom property");
    }

    @ParameterizedTest(name = "{0} with {1}")
    @CsvSource({
        "PRIVATE, READ",
        "PRIVATE, READ_WRITE",
        "PRIVATE, ADMIN",
        "CONFIDENTIAL, READ",
        "CONFIDENTIAL, READ_WRITE",
        "CONFIDENTIAL, ADMIN"
    })
    public void privateOrConfidentialEventShouldBeAnonymizedInDavGet(String eventClass, DelegationRight right) {

        // GIVEN Alice has a PRIVATE or CONFIDENTIAL event in her calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20250930T090000Z
            DTEND:20250930T100000Z
            SUMMARY:Alice's secret meeting
            DESCRIPTION:Confidential information that Bob should not see
            LOCATION:Secret Room
            CLASS:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, eventClass);
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        // WHEN Alice delegates her calendar to Bob
        calDavClient.grantDelegation(alice, alice.id(), bob, right);

        // THEN Bob can access the delegated calendar
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(bob, alice.id());

        // WHEN Bob queries the event via CalDAV GET
        String responseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("text/calendar")
        .when()
            .get(calendarURL.asUri().toASCIIString() + "/" + eventUid + ".ics")
        .then()
            .statusCode(200)
            .extract().asString();


        // THEN Bob sees an anonymized version of the event
        CalendarUtil.CalendarExtractor actualCalendar = CalendarUtil.toExtractor(responseBody);

        // The event should be anonymized: SUMMARY = "Busy", no DESCRIPTION, no LOCATION
        assertThat(actualCalendar.extractPropertyValue(Property.SUMMARY)).isEqualTo("Busy");
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.DESCRIPTION)).isEmpty();
        assertThat(actualCalendar.extractOptionalEventProperty(Optional.empty(), Property.LOCATION)).isEmpty();
    }

    @Test
    void copiedCalendarShouldContainsNewEvent() throws JsonProcessingException {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(testUser2, testUser.id());
        Response response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
    }

    @Test
    void copiedCalendarShouldContainsUpdatedEvent() throws JsonProcessingException {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String updatedCalendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Updated Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(testUser2, testUser.id());
        Response response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Updated Sprint planning #01");
    }

    @Test
    void copiedCalendarShouldNotContainsDeletedEvent() throws JsonProcessingException {
        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        calDavClient.deleteCalendarEvent(testUser, eventUid);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(testUser2, testUser.id());
        Response response = calDavClient.findEventsByTime(testUser2,
            calendarURL,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        assertThat(result).hasSize(0);
    }

    @Test
    void amqpMessagesShouldBeEmittedForCopiedCalendar() throws Exception {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:created", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:updated", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:deleted", "");

        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), QUEUE_NAME);
        awaitAtMost.untilAsserted(() -> assertThat(messages)
            .anySatisfy(json ->
                assertThat(json.path("eventPath").asText()).startsWith("/calendars/" + testUser2.id())));
    }

    @Test
    void noAmqpAlarmMessagesEmittedForCopiedCalendar() throws Exception {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:created", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:updated", "");
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "calendar:event:alarm:deleted", "");

        OpenPaasUser testUser = bob;
        OpenPaasUser testUser2 = alice;

        calDavClient.grantDelegation(testUser, testUser.id(), testUser2, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .alarmTrigger("-PT15M")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), QUEUE_NAME);
        awaitEvery500Millis().during(Duration.ofSeconds(3)).untilAsserted(() ->
            assertThat(messages)
                .noneSatisfy(json ->
                    assertThat(json.path("eventPath").asText()).startsWith("/calendars/" + testUser2.id())));
    }

    @Test
    protected void amqpShouldPublishDelegationUpdatedForSourceCalendarOnGrant() throws Exception {
        String queueName = "delegation-source-grant-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json -> assertThatJson(json.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "calendarPath": "%s",
                      "calendarProps": {
                        "delegation_updated": true
                      }
                    }
                    """.formatted(sourcePath))));
    }

    @Test
    protected void amqpShouldPublishDelegationUpdatedForSourceCalendarOnUpdateRight() throws Exception {
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        String queueName = "delegation-source-update-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json -> assertThatJson(json.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "calendarPath": "%s",
                      "calendarProps": {
                        "delegation_updated": true
                      }
                    }
                    """.formatted(sourcePath))));
    }

    @Test
    protected void amqpShouldPublishDelegationUpdatedForSourceCalendarOnRevoke() throws Exception {
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        String queueName = "delegation-source-revoke-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        calDavClient.revokeDelegation(bob, bob.id(), alice);

        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json -> assertThatJson(json.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "calendarPath": "%s",
                      "calendarProps": {
                        "delegation_updated": true
                      }
                    }
                    """.formatted(sourcePath))));
    }

    @Test
    protected void amqpShouldDedupDelegationUpdatedForSameSourceCalendarInSingleRequest() throws Exception {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        String queueName = "delegation-source-dedup-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");
        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        calDavClient.grantDelegations(bob, bob.id(), Map.of(
            alice, DelegationRight.READ,
            cedric, DelegationRight.READ));

        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        String expected = """
            {
              "calendarPath": "%s",
              "calendarProps": {
                "delegation_updated": true
              }
            }
            """.formatted(sourcePath);
        Predicate<JsonNode> isDelegationUpdated = json ->
            sourcePath.equals(json.path("calendarPath").asText())
                && json.path("calendarProps").path("delegation_updated").asBoolean(false);

        // Wait until the *unique* delegation_updated notification for this source calendar is observed.
        awaitAtMost.untilAsserted(() -> {
            List<JsonNode> delegationUpdated = messages.stream()
                .filter(isDelegationUpdated)
                .toList();
            assertThat(delegationUpdated).hasSize(1);
            assertThatJson(delegationUpdated.getFirst().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
        });

        // And make sure no duplicates arrive a bit later (race with async AMQP publishing).
        Awaitility.await()
            .during(Duration.ofSeconds(3))
            .untilAsserted(() -> {
                List<JsonNode> delegationUpdated = messages.stream()
                    .filter(isDelegationUpdated)
                    .toList();
                assertThat(delegationUpdated).hasSize(1);
                assertThatJson(delegationUpdated.getFirst().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
            });
    }

    @Test
    protected void amqpShouldDedupDelegationUpdatedForSameSourceCalendarOnBulkRevoke() throws Exception {
        OpenPaasUser cedric = dockerExtension().newTestUser();
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);
        calDavClient.grantDelegation(bob, bob.id(), cedric, DelegationRight.READ);

        String queueName = "delegation-source-revoke-two-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");
        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        calDavClient.revokeDelegations(bob, bob.id(), List.of(alice, cedric));

        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        String expected = """
            {
              "calendarPath": "%s",
              "calendarProps": {
                "delegation_updated": true
              }
            }
            """.formatted(sourcePath);
        Predicate<JsonNode> isDelegationUpdated = json ->
            sourcePath.equals(json.path("calendarPath").asText())
                && json.path("calendarProps").path("delegation_updated").asBoolean(false);

        awaitAtMost.untilAsserted(() -> {
            List<JsonNode> delegationUpdated = messages.stream()
                .filter(isDelegationUpdated)
                .toList();
            assertThat(delegationUpdated).hasSize(1);
            assertThatJson(delegationUpdated.getFirst().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
        });

        Awaitility.await()
            .during(Duration.ofSeconds(3))
            .untilAsserted(() -> {
                List<JsonNode> delegationUpdated = messages.stream()
                    .filter(isDelegationUpdated)
                    .toList();
                assertThat(delegationUpdated).hasSize(1);
                assertThatJson(delegationUpdated.getFirst().toString()).when(Option.IGNORING_EXTRA_FIELDS).isEqualTo(expected);
            });
    }

    @Test
    protected void amqpShouldPublishDelegationUpdatedWhenRevokeOneOfTwoSharees() throws Exception {
        OpenPaasUser cedric = dockerExtension().newTestUser();
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);
        calDavClient.grantDelegation(bob, bob.id(), cedric, DelegationRight.READ);

        String queueName = "delegation-source-revoke-one-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");
        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        calDavClient.revokeDelegation(bob, bob.id(), alice);

        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json -> assertThatJson(json.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "calendarPath": "%s",
                      "calendarProps": {
                        "delegation_updated": true
                      }
                    }
                    """.formatted(sourcePath))));
    }

    @Test
    protected void amqpShouldPublishDelegationUpdatedForSourceCalendarWhenDelegatedAdminGrantsViaCopiedCalendar() throws Exception {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob delegates his calendar to Alice in admin mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);
        CalendarURL delegatedCalendarURL = awaitAtMost
            .ignoreException(IllegalStateException.class)
            .until(() -> calDavClient.findDelegatedCalendar(alice, bob.id()), calendarURL -> calendarURL != null);

        String queueName = "delegation-source-grant-via-copy-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");
        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        // WHEN Alice delegates her copy of Bob's calendar to Cedric
        calDavClient.grantDelegation(alice, delegatedCalendarURL.calendarId(), cedric, DelegationRight.READ);

        // THEN a delegation_updated message is published for Bob's source calendar
        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json -> assertThatJson(json.toString())
                .when(Option.IGNORING_EXTRA_FIELDS)
                .isEqualTo("""
                    {
                      "calendarPath": "%s",
                      "calendarProps": {
                        "delegation_updated": true
                      }
                    }
                    """.formatted(sourcePath))));
    }

    @Test
    protected void amqpDelegationUpdatedShouldCarryConnectedUserOfGranter() throws Exception {
        String queueName = "delegation-connected-user-grant-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:calendar:updated", "");

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        // WHEN: Bob grants a delegation on his own calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        // THEN: the emitted message reports Bob - who actually performed the action - as the connected user
        String sourcePath = CalendarURL.from(bob.id()).asUri().toString();
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json -> {
                assertThat(json.path("calendarPath").asText()).isEqualTo(sourcePath);
                assertThat(json.path("calendarProps").path("delegation_updated").asBoolean()).isTrue();
                assertThat(json.path("connectedUser").asText()).isEqualTo("principals/users/" + bob.id());
            }));
    }

    @Test
    protected void amqpEventMessageShouldCarryConnectedUserOfDelegateActingOnOwnerCalendar() throws Exception {
        String queueName = "delegation-connected-user-write-" + bob.id();
        dockerExtension().getChannel().queueDeclare(queueName, false, true, true, null);
        dockerExtension().getChannel().queueBind(queueName, "calendar:event:created", "");
        dockerExtension().getChannel().queueBind(queueName, "calendar:event:updated", "");

        // GIVEN: Bob delegates his calendar to Alice with write rights
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        CalendarURL sharedCalendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        BlockingQueue<JsonNode> messages = AmqpTestHelper.listenToQueue(dockerExtension().getChannel(), queueName);

        // WHEN: Alice - the delegate - creates an event on Bob's calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, sharedCalendarURL, eventUid, calendarData);

        // THEN: the emitted message reports Alice - who actually performed the action - and not Bob (the owner) as the connected user
        awaitAtMost.untilAsserted(() ->
            assertThat(messages).anySatisfy(json ->
                assertThat(json.path("connectedUser").asText()).isEqualTo("principals/users/" + alice.id())));
    }

    @Test
    void putCalendarEventShouldThrowErrorWhenCopiedCalendarOnlyHasReadRight() {

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        // WHEN Alice tries to create an event in Bob calendar copy
        // THEN a 403 error is thrown
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData))
            .hasMessageContaining("Unexpected status code: 403 when create/update calendar object");
    }

    @Test
    void putCalendarEventShouldThrowErrorWhenSourceCalendarOnlyHasReadRight() {

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in read-only mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        String updatedCalendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Updated Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        URI bobSourceCalendarEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics");

        // WHEN Bob creates and updates the event directly in his SOURCE calendar
        calDavClient.upsertCalendarEvent(bob, bobSourceCalendarEventUri, calendarData);

        // WHEN Alice tries to update the same event directly in Bob SOURCE calendar
        // THEN a 403 error is thrown
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, bobSourceCalendarEventUri, updatedCalendarData))
            .hasMessageContaining("Unexpected status code: 403 when create/update calendar object");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void aliceCanCreateEventsInReadOnlyDelegationWithDAVWhenAtLeastWriteRight(DelegationRight right) throws Exception {

        // GIVEN Bob has a calendar
        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        // And: Alice cannot place an event in it
        String eventUid2 = "event-" + UUID.randomUUID();
        String calendarData2 = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Alice created event
            DESCRIPTION:Whatever
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid2);
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid2, calendarData2);

        // AND the event is created in Bob original calendar
        Response response = calDavClient.findEventsByTime(bob,
            "20251029T090000",
            "20251031T100000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid2);
        assertThat(result.get(0).summary().get()).isEqualTo("Alice created event");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void upsertedEventViaDelegatedCalendarShouldAppearInOwnerSourceCalendarInDav(DelegationRight right) throws Exception {

        // GIVEN Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // AND Alice gets her delegated calendar copy
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice creates an event via her delegated calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Alice created event
            DESCRIPTION:Created via delegated calendar
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData);

        // THEN the event appears in Bob's SOURCE calendar via CalDAV REPORT
        String bobSourceCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))
        .when()
            .request("REPORT", bobSourceCalendarUri)
        .then()
            .statusCode(207)
            .extract().asString();


        String calendarDataResponse = XMLUtil.extractByXPath(
            responseBody,
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));

        CalendarUtil.CalendarExtractor actualCalendar = CalendarUtil.toExtractor(calendarDataResponse);

        assertThat(actualCalendar.extractPropertyValue(Property.UID)).isEqualTo(eventUid);
        assertThat(actualCalendar.extractPropertyValue(Property.SUMMARY)).isEqualTo("Alice created event");
    }

    @ParameterizedTest(name = "{0}")
        @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void aliceCanDeleteEventsInDelegationWithDAVWhenAtLeastWriteRight(DelegationRight right) throws Exception {

        // GIVEN Bob has a calendar with an event
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Bob's event to delete
            DESCRIPTION:This event will be deleted by Alice
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // WHEN Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // THEN a copy of bob calendar is created in Alice calendar list
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice deletes the event via CalDAV using her delegated calendar
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        // THEN the event is deleted in Bob's original calendar
        Response response = calDavClient.findEventsByTime(bob,
            "20251029T090000",
            "20251031T100000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());
        assertThat(result).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void deletedEventViaDelegatedCalendarShouldBeRemovedFromOwnerSourceCalendarInDav(DelegationRight right) throws Exception {

        // GIVEN Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20251030T090000Z
            DTEND:20251030T100000Z
            SUMMARY:Bob's event to delete
            DESCRIPTION:This event will be deleted by Alice
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // AND Alice gets her delegated calendar copy
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice deletes the event via her delegated calendar
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        // THEN the event is removed from Bob's SOURCE calendar (verified via CalDAV REPORT)
        String bobSourceCalendarUri = "/calendars/" + bob.id() + "/" + bob.id();
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", bob.impersonatedBasicAuth())
            .header("Depth", "1")
            .body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:prop-filter name="UID">
                                    <c:text-match collation="i;octet">%s</c:text-match>
                                </c:prop-filter>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """.formatted(eventUid))
        .when()
            .request("REPORT", bobSourceCalendarUri)
        .then()
            .statusCode(207)
            .extract().asString();

        // No calendar-data should be returned for the deleted event
        String calendarDataResponse = XMLUtil.extractByXPath(
            responseBody,
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav"));
        assertThat(calendarDataResponse).isNullOrEmpty();
    }

    @Test
    void deleteCalendarEventShouldThrowErrorWhenCopiedCalendarOnlyHasReadRight() {

        // GIVEN Bob has a calendar with an event
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Bob's protected event")
            .location("Twake Meeting Room")
            .description("This event should not be deletable by Alice with READ only.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice with READ only
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice tries to delete the event in Bob calendar copy
        // THEN a 403 error is thrown
        assertThatThrownBy(() -> calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid))
            .hasMessageContaining("Unexpected status code: 403 when delete calendar object");
    }

    @Test
    void deleteFromReadDelegatedCalendarShouldNotDeleteOriginCalendar() {

        // GIVEN Bob has a calendar with an event
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Bob's protected delegated event")
            .location("Twake Meeting Room")
            .description("This event must remain in Bob's origin calendar.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice with READ only
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        CalendarURL mirrorCalendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        URI mirrorEventUri = URI.create(mirrorCalendarURL.asUri() + "/" + eventUid + ".ics");

        // WHEN Alice deletes the event via the delegated mirror calendar
        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
        .when()
            .delete(mirrorEventUri.toASCIIString())
        .then()
            .statusCode(403);

        // AND Bob's origin calendar object must remain regardless of the mirror DELETE status
        URI originEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics");
        String originEventResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .accept("text/calendar")
        .when()
            .get(originEventUri.toASCIIString())
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(originEventResponseBody)
            .contains(eventUid)
            .contains("SUMMARY:Bob's protected delegated event");
    }

    @Test
    void partStatUpdateFromReadDelegatedAttendeeCalendarShouldNotPropagateToOrganizerCalendar() {
        OpenPaasUser david = dockerExtension().newTestUser();

        // GIVEN Bob is the organizer of an event with Alice as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300411T080000Z
            DTSTART:20300411T100000Z
            DTEND:20300411T110000Z
            SUMMARY:Bob delegated attendee event
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:%s
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=Alice:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), bob.email(), alice.email());

        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        URI organizerEventUri = URI.create("/calendars/" + bob.id() + "/" + bob.id() + "/" + eventUid + ".ics");
        CalendarURL aliceDefaultCalendarURL = CalendarURL.from(alice.id());

        // AND Alice receives the invited event in her own calendar
        URI aliceEventUri = awaitAtMost.until(
                () -> calDavClient.findFirstUserCalendarObjectUriByEventUid(alice, aliceDefaultCalendarURL, eventUid),
                Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected invited event to be present in Alice calendar"));
        awaitAtMost.untilAsserted(() -> assertSoftly(softly -> {
            softly.assertThat(CalendarUtil.getAttendeePartStat(
                    calDavClient.getCalendarEvent(bob, organizerEventUri), alice.email()))
                .as("Alice PARTSTAT on Bob organizer calendar before David delegated update")
                .isEqualTo(PartStat.NEEDS_ACTION);
            softly.assertThat(CalendarUtil.getAttendeePartStat(
                    calDavClient.getCalendarEvent(alice, aliceEventUri), alice.email()))
                .as("Alice PARTSTAT on Alice attendee calendar before David delegated update")
                .isEqualTo(PartStat.NEEDS_ACTION);
        }));

        // AND Alice delegates her calendar to David with READ only
        calDavClient.grantDelegation(alice, alice.id(), david, DelegationRight.READ);
        CalendarURL delegatedCalendarURL = awaitAtMost
            .ignoreException(IllegalStateException.class)
            .until(() -> calDavClient.findDelegatedCalendar(david, alice.id()), calendarURL -> calendarURL != null);

        // AND David finds the event through his delegated calendar URI
        URI delegatedEventUri = awaitAtMost.until(
                () -> calDavClient.findFirstUserCalendarObjectUriByEventUid(david, delegatedCalendarURL, eventUid),
                Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected event to be present in David delegated calendar"));
        assertThat(delegatedEventUri.toASCIIString()).startsWith(delegatedCalendarURL.asUri().toASCIIString() + "/");

        // WHEN David accepts Alice's participation via his delegated read-only calendar
        String delegatedEventIcs = calDavClient.getCalendarEvent(david, delegatedEventUri);
        assertThat(CalendarUtil.getAttendeePartStat(delegatedEventIcs, alice.email()))
            .as("Alice PARTSTAT on David's delegated calendar before update")
            .isEqualTo(PartStat.NEEDS_ACTION);
        String acceptedDelegatedEventIcs = CalendarUtil.withAttendeePartStat(delegatedEventIcs, alice.email(), PartStat.ACCEPTED);
        given()
            .header("Authorization", david.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(acceptedDelegatedEventIcs.getBytes(StandardCharsets.UTF_8))
        .when()
            .put(delegatedEventUri.toASCIIString())
        .then()
            .statusCode(403);

        // THEN Bob's organizer calendar keeps Alice participation untouched
        Awaitility.await()
            .during(Duration.ofSeconds(2))
            .untilAsserted(() -> assertThat(CalendarUtil.getAttendeePartStat(
                    calDavClient.getCalendarEvent(bob, organizerEventUri), alice.email()))
                .as("Alice PARTSTAT on Bob organizer calendar after David delegated update")
                .isEqualTo(PartStat.NEEDS_ACTION));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void putCalendarEventShouldUpdateEventInOriginalCalendarWhenCopiedCalendarHasWriteRight(DelegationRight right) throws JsonProcessingException {

        // GIVEN Bob has a calendar
        // AND Bob has an event in his calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice updates the event in Bob calendar copy
        String updatedCalendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Updated Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, updatedCalendarData);

        Response response = calDavClient.findEventsByTime(bob,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        // THEN the event is updated in Bob original calendar
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
        assertThat(result.get(0).summary().get()).isEqualTo("Updated Sprint planning #01");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void shouldDeleteCalendarEventInOriginalCalendarWhenCopiedCalendarHasWriteRight(DelegationRight right) throws JsonProcessingException {

        // GIVEN Bob has a calendar
        // AND Bob has an event in his calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice deletes the event in Bob calendar copy
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        Response response = calDavClient.findEventsByTime(bob,
            "20300310T000000",
            "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

        // THEN the event is deleted in Bob original calendar
        assertThat(result).hasSize(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void putCalendarEventShouldSendITIPRequestWhenCopiedCalendarHasWriteRight(DelegationRight right) throws JsonProcessingException {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .attendee(cedric.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();

        // WHEN Alice creates an event in Bob calendar copy with Cedric as attendee
        calDavClient.upsertCalendarEvent(alice, calendarURL, eventUid, calendarData);

        // THEN an ITIP request is sent to Cedric
        awaitAtMost.untilAsserted(() -> {
            Response response = calDavClient.findEventsByTime(cedric,
                cedric.id(),
                "inbox",
                "20300310T000000",
                "20300510T000000");
            List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

            assertThat(result).hasSize(1);
            assertThat(result.get(0).uid()).isEqualTo(eventUid);
            assertThat(result.get(0).method().get()).isEqualTo("REQUEST");
            assertThat(result.get(0).summary().get()).isEqualTo("Sprint planning #01");
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void deleteCalendarEventShouldSendITIPCancelWhenCopiedCalendarHasWriteRight(DelegationRight right) throws JsonProcessingException {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob has an event in his calendar
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(bob.email())
            .attendee(cedric.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(bob, eventUid, calendarData);

        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice deletes the event in Bob calendar copy
        calDavClient.deleteCalendarEvent(alice, calendarURL, eventUid);

        // THEN an ITIP cancel is sent to Cedric
        awaitAtMost.untilAsserted(() -> {
            Response response = calDavClient.findEventsByTime(cedric,
                cedric.id(),
                "inbox",
                "20300310T000000",
                "20300510T000000");
            List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());

            assertThat(result).hasSize(2);
            assertThat(result).anySatisfy(eventData -> {
                assertThat(eventData.uid()).isEqualTo(eventUid);
                assertThat(eventData.method().get()).isEqualTo("CANCEL");
                assertThat(eventData.summary().get()).isEqualTo("Sprint planning #01");
            });
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ", "READ_WRITE"})
    void updateCalendarAclShouldThrowErrorWhenDelegatedUserHasNoAdminRight(DelegationRight right) {

        // GIVEN bob delegated his calendar to Alice without admin rights
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice attempts to manage rights on her local copy
        // THEN is gets rejected
        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(alice, calendarURL, "{DAV:}read"))
            .hasMessageContaining("Unexpected status code: 403 when updating ACL for calendar");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ", "READ_WRITE"})
    void grantDelegationShouldThrowErrorWhenDelegatedUserHasNoAdminRight(DelegationRight right) {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice without admin rights
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice attempts to delegate her local copy to Cedric
        // THEN is gets rejected
        assertThatThrownBy(() -> calDavClient.grantDelegation(alice, calendarURL.calendarId(), cedric, DelegationRight.READ))
            .hasMessageContaining("Unexpected status code: 403 when sharing calendar");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegatedUserCannotUpdateCalendarAclViaOwnerCanonicalUrl(DelegationRight right) {

        // GIVEN Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        // WHEN Alice attempts to manage Bob source calendar directly
        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(alice, CalendarURL.from(bob.id()), "{DAV:}read"))
            .hasMessageContaining("Unexpected status code: 403 when updating ACL for calendar");

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .extract().asString();

        // THEN Bob source calendar ACL is not changed by Alice direct canonical URL access
        assertThat(response)
            .doesNotContain("\"privilege\":\"{DAV:}read\",\"principal\":\"{DAV:}authenticated\"");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DelegationRight.class)
    void delegatedUserCannotGrantDelegationViaOwnerCanonicalUrl(DelegationRight right) {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob delegates his calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:{email}",
                    "dav:read": true
                  }
                ],
                "remove": []
              }
            }
            """.replace("{email}", cedric.email());

        // WHEN Alice attempts to share Bob source calendar directly
        int status = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .body(payload)
        .when()
            .post(CalendarURL.from(bob.id()).asUri() + ".json")
        .then()
            .extract()
            .statusCode();

        assertThat(status).isEqualTo(SC_FORBIDDEN);

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .extract().asString();

        // THEN Bob source calendar delegation list is not changed by Alice direct canonical URL access
        assertThat(response)
            .doesNotContain("mailto:" + cedric.email())
            .doesNotContain("principals/users/" + cedric.id());
    }

    @Test
    public void ownerShouldNotBeAbleToDelegateCalendarToThemself() {
        String calendarId = UUID.randomUUID().toString();

        // GIVEN Bob owns a calendar
        calDavClient.createNewCalendar(bob, calendarId, "Calendar A", 2);
        String bobCalendarsBeforeSelfDelegation = calDavClient.getUserCalendarsJson(bob);

        // WHEN Bob attempts to delegate that calendar to himself
        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:%s",
                    %s
                  }
                ],
                "remove": []
              }
            }
            """.formatted(bob.email(), DelegationRight.READ.getValue());

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("application/json;charset=UTF-8")
            .accept("application/json, text/plain, */*")
            .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
            .post(CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + bob.id() + "/" + calendarId + ".json")
        .then()
            .statusCode(SC_BAD_REQUEST);

        // THEN the self delegation is rejected
        assertThat(calDavClient.getUserCalendarsJson(bob))
            .as("Self delegation by owner must not mutate Bob's calendar list")
            .isEqualTo(bobCalendarsBeforeSelfDelegation);
    }

    @Test
    public void delegatedAdminShouldNotBeAbleToDelegateCalendarToThemself() {
        String calendarId = UUID.randomUUID().toString();

        // GIVEN Bob owns a calendar
        // AND Alice has admin delegation on Bob's calendar
        calDavClient.createNewCalendar(bob, calendarId, "Calendar A", 2);
        calDavClient.grantDelegation(bob, calendarId, alice, DelegationRight.ADMIN);
        CalendarURL delegatedCalendarURL = awaitAtMost
            .ignoreException(IllegalStateException.class)
            .until(() -> calDavClient.findDelegatedCalendar(alice, new CalendarURL(bob.id(), calendarId)), calendarURL -> calendarURL != null);
        String bobCalendarsBeforeSelfDelegation = calDavClient.getUserCalendarsJson(bob);
        String aliceCalendarsBeforeSelfDelegation = calDavClient.getUserCalendarsJson(alice);

        // WHEN Alice attempts to delegate her delegated calendar copy to herself
        String payload = """
            {
              "share": {
                "set": [
                  {
                    "dav:href": "mailto:%s",
                    %s
                  }
                ],
                "remove": []
              }
            }
            """.formatted(alice.email(), DelegationRight.READ.getValue());

        given()
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("application/json;charset=UTF-8")
            .accept("application/json, text/plain, */*")
            .body(payload.getBytes(StandardCharsets.UTF_8))
        .when()
            .post(CalendarURL.CALENDAR_URL_PATH_PREFIX + "/" + alice.id() + "/" + delegatedCalendarURL.calendarId() + ".json")
        .then()
            .statusCode(SC_BAD_REQUEST);

        // THEN the self delegation by delegated admin is rejected
        assertThat(calDavClient.getUserCalendarsJson(bob))
            .as("Self delegation by delegated admin must not mutate Bob's source calendar list")
            .isEqualTo(bobCalendarsBeforeSelfDelegation);
        assertThat(calDavClient.getUserCalendarsJson(alice))
            .as("Self delegation by delegated admin must not mutate Alice's delegated calendar list")
            .isEqualTo(aliceCalendarsBeforeSelfDelegation);
    }

    @Test
    void updateCalendarAclShouldRunSuccessfullyWhenDelegatedUserHasAdminRight() {

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in admin mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice attempts to manage rights on her local copy
        calDavClient.updateCalendarAcl(alice, calendarURL, "{DAV:}read");

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + bob.id() + ".json")
            .then()
            .extract().asString();

        // THEN the right is updated in Bob original calendar
        assertThatJson(response)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/{userId2}/{userId2}.json"
                        }
                    },
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userEmail}", alice.email()));
    }

    @Test
    void grantDelegationShouldRunSuccessfullyWhenDelegatedUserHasAdminRight() {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in admin mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice delegates her local copy to Cedric
        calDavClient.grantDelegation(alice, calendarURL.calendarId(), cedric, DelegationRight.READ);

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + bob.id() + ".json")
            .then()
            .extract().asString();

        // THEN the right is updated in Bob original calendar
        assertThatJson(response)
            .when(Option.IGNORING_ARRAY_ORDER)
            .inPath("_embedded.dav:calendar[0]")
            .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/{userId2}/{userId2}.json"
                        }
                    },
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 5,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail3}",
                            "principal": "principals/users/{userId3}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}share",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId2}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{urn:ietf:params:xml:ns:caldav}read-free-busy",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userId3}", cedric.id())
                .replace("{userEmail}", alice.email())
                .replace("{userEmail3}", cedric.email()));
    }

    @Test
    void delegatedAdminShouldBeAbleToKeepOwnDelegationWhenDelegatingCalendar() {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob delegates his calendar to Alice in admin mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);
        CalendarURL delegatedCalendarURL = awaitAtMost
            .ignoreException(IllegalStateException.class)
            .until(() -> calDavClient.findDelegatedCalendar(alice, bob.id()), calendarURL -> calendarURL != null);

        // WHEN Alice delegates Bob's calendar to Cedric while keeping her existing admin delegation
        calDavClient.grantDelegations(alice, delegatedCalendarURL.calendarId(), Map.of(
            alice, DelegationRight.ADMIN,
            cedric, DelegationRight.READ));

        // THEN Cedric receives the delegated calendar
        awaitAtMost.until(
            () -> {
                try {
                    return Optional.of(calDavClient.findDelegatedCalendar(cedric, bob.id()));
                } catch (IllegalStateException e) {
                    return Optional.<CalendarURL>empty();
                }
            },
            Optional::isPresent)
            .orElseThrow(() -> new AssertionError("Expected Cedric delegated calendar to be present"));
    }

    @Test
    void rightMetadataOfCopiedCalendarShouldBeUpdatedWhenOriginalCalendarIsUpdated() {
        OpenPaasUser cedric = dockerExtension().newTestUser();

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice in read mode
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ);

        // WHEN Bob updates public rights on his calendar
        // AND Bob delegates his calendar to Cedric in read mode
        calDavClient.updateCalendarAcl(bob,"{DAV:}read");
        calDavClient.grantDelegation(bob, bob.id(), cedric, DelegationRight.READ);

        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + alice.id() + ".json")
            .then()
            .extract().asString();

        // THEN the right is updated in Alice local copy of Bob original calendar
        String delegatedSource = "/calendars/" + bob.id() + "/" + bob.id() + ".json";
        assertThatJson(response)
            .inPath("_embedded.dav:calendar")
            .isArray()
            .filteredOn(calendar -> delegatedSource.equals(((Map<?, ?>) calendar).get("calendarserver:delegatedsource")))
            .singleElement()
            .satisfies(calendar -> assertThatJson(calendar)
                .when(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo(String.format("""
                {
                    "_links": {
                        "self": {
                            "href": "${json-unit.ignore}"
                        }
                    },
                    "calendarserver:delegatedsource": "/calendars/{userId2}/{userId2}.json",
                    "dav:name": "#default",
                    "calendarserver:ctag": "http://sabre.io/ns/sync/1",
                    "invite": [
                        {
                            "href": "principals/users/{userId2}",
                            "principal": "principals/users/{userId2}",
                            "properties": [],
                            "access": 1,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail}",
                            "principal": "principals/users/{userId}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        },
                        {
                            "href": "mailto:{userEmail3}",
                            "principal": "principals/users/{userId3}",
                            "properties": [],
                            "access": 2,
                            "comment": null,
                            "inviteStatus": 2
                        }
                    ],
                    "acl": [
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}write-properties",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-read",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "principals/users/{userId}/calendar-proxy-write",
                            "protected": true
                        },
                        {
                            "privilege": "{DAV:}read",
                            "principal": "{DAV:}authenticated",
                            "protected": true
                        }
                    ]
                }
                """.replace("{userId}", alice.id()))
                .replace("{userId2}", bob.id())
                .replace("{userId3}", cedric.id())
                .replace("{userEmail}", alice.email())
                .replace("{userEmail3}", cedric.email())));
    }

    @Test
    void delegatedUserCanUpdateSettingOfCopiedCalendar() {

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        calDavClient.updateCalendarSetting(alice, calendarURL, "new name", "#009688");

        String response = given()
            .headers("Authorization", alice.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + alice.id() + ".json")
            .then()
            .extract().asString();

        assertThat(response)
            .containsOnlyOnce("\"dav:name\":\"new name\"")
            .containsOnlyOnce("\"apple:color\":\"#009688\"");
    }

    @Test
    void updateNameAndColorOfCopiedCalendarShouldNotAffectOriginalCalendar() {

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        calDavClient.updateCalendarSetting(alice, calendarURL, "new name", "#009688");

        String response = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
            .when()
            .get("/calendars/" + bob.id() + ".json")
            .then()
            .extract().asString();

        assertThat(response)
            .doesNotContain("\"dav:name\":\"new name\"")
            .doesNotContain("\"apple:color\":\"#009688\"");
    }

    @Test
    void updateNameOfOriginalCalendarShouldUpdateUncustomizedCopiedCalendarName() {

        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.ADMIN);

        CalendarURL delegatedCalendar = calDavClient.findDelegatedCalendar(alice, bob.id());

        calDavClient.updateCalendarSetting(bob, CalendarURL.from(bob.id()), "new name", "#009688");

        awaitAtMost.untilAsserted(() -> {
            String responseBody = given()
                .contentType("application/xml")
                .accept("application/xml")
                .header("Authorization", alice.impersonatedBasicAuth())
                .header("Depth", "0")
                .body("""
                    <d:propfind xmlns:d="DAV:">
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                    </d:propfind>""")
            .when()
                .request("PROPFIND", delegatedCalendar.asUri().toString())
            .then()
                .statusCode(207)
                .extract().asString();

            XmlAssert.assertThat(responseBody)
                .withNamespaceContext(DAV_NAMESPACES)
                .valueByXPath("//d:response[d:href='%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                    .formatted(delegatedCalendar.asUri()))
                .isEqualTo("new name");
        });
    }

    @Test
    void updateResourceCalendarNameShouldUpdateUncustomizedDelegatedResourceCalendarName() {
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        CalendarURL delegatedResourceCalendar = calDavClient.findDelegatedCalendar(bob, resource.id());

        calDavClient.updateCalendarSetting(technicalToken, CalendarURL.from(resource.id()), "Renamed Resource Calendar", "#009688");

        awaitAtMost.untilAsserted(() -> {
            String responseBody = given()
                .contentType("application/xml")
                .accept("application/xml")
                .header("Authorization", bob.impersonatedBasicAuth())
                .header("Depth", "0")
                .body("""
                    <d:propfind xmlns:d="DAV:">
                      <d:prop>
                        <d:displayname/>
                      </d:prop>
                    </d:propfind>""")
            .when()
                .request("PROPFIND", delegatedResourceCalendar.asUri().toString())
            .then()
                .statusCode(207)
                .extract().asString();

            XmlAssert.assertThat(responseBody)
                .withNamespaceContext(DAV_NAMESPACES)
                .valueByXPath("//d:response[d:href='%s/']/d:propstat[d:status='HTTP/1.1 200 OK']/d:prop/d:displayname"
                    .formatted(delegatedResourceCalendar.asUri()))
                .isEqualTo("Renamed Resource Calendar");
        });
    }

    @Test
    void resourceAdminCanUpdateParticipationStatus() {
        // GIVEN: Create a resource 'whiteboard' with Bob as administrator
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: Grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T100000
            SUMMARY:Whiteboard session
            LOCATION:Meeting Room
            DESCRIPTION:Test event with resource attendee
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=whiteboard:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());

        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // WHEN: Bob locates the event in the resource calendar
        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();
        URI resourceEventUri = calDavClient.findDelegatedCalendar(bob, resource.id()).eventHref(resourceEventId);

        // THEN: Bob can retrieve the event via GET
        String resourceEventResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("text/calendar")
        .when()
            .get(resourceEventUri.toASCIIString())
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourceEventResponseBody).contains(eventUid);

        // WHEN: Bob updates the resource participation status to ACCEPTED
        String updatedEventIcs = resourceEventResponseBody
            .replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(updatedEventIcs.getBytes(StandardCharsets.UTF_8))
        .when()
            .put(resourceEventUri.toASCIIString())
        .then()
            .statusCode(204);

        // THEN: Verify that the participation status is updated
        String updatedResourceEventResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("text/calendar")
        .when()
            .get(resourceEventUri.toASCIIString())
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(updatedResourceEventResponseBody)
            .contains("mailto:" + resource.id() + "@open-paas.org")
            .contains("PARTSTAT=ACCEPTED");
    }

    @Test
    public void shouldPropagateToOrganizerWhenResourceAdminUpdatePartStat() {
        // GIVEN: Create a resource 'whiteboard' with Bob as administrator
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: Grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:20251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:20251028T100000
            SUMMARY:Whiteboard session
            LOCATION:Meeting Room
            DESCRIPTION:Test event with resource attendee
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=whiteboard:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());

        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // WHEN: Bob updates the resource participation status to ACCEPTED
        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();
        URI resourceEventUri = calDavClient.findDelegatedCalendar(bob, resource.id()).eventHref(resourceEventId);
        String resourceEventResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("text/calendar")
        .when()
            .get(resourceEventUri.toASCIIString())
        .then()
            .statusCode(200)
            .extract().asString();

        String updatedEventIcs = resourceEventResponseBody
            .replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");
        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(updatedEventIcs.getBytes(StandardCharsets.UTF_8))
        .when()
            .put(resourceEventUri.toASCIIString())
        .then()
            .statusCode(204);

        // THEN: Verify that Alice sees the updated participation status of the resource
        awaitAtMost.untilAsserted(() -> {
            String aliceEventResponseBody = given()
                .header("Authorization", alice.impersonatedBasicAuth())
                .accept("text/calendar")
            .when()
                .get("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics")
            .then()
                .statusCode(200)
                .extract().asString();

            assertThat(aliceEventResponseBody)
                .contains("mailto:" + resource.id() + "@open-paas.org")
                .contains("PARTSTAT=ACCEPTED");
        });
    }

    @Test
    void resourceAdminCanSendItipCounterViaResourceEventUri() {
        // GIVEN: a resource "whiteboard" with Bob as administrator
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T100000
            SUMMARY:Design meeting
            LOCATION:Meeting Room
            DESCRIPTION:Initial meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=whiteboard;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());

        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);
        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();
        URI resourceEventUri = calDavClient.findDelegatedCalendar(bob, resource.id()).eventHref(resourceEventId);

        // WHEN: Bob (resource admin) sends an ITIP COUNTER on behalf of the resource
        String counterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T030000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T110000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T120000
            SUMMARY:Design meeting - Proposed new time
            ORGANIZER;CN=whiteboard:mailto:%s@open-paas.org
            ATTENDEE;CN=Alice;ROLE=REQ-PARTICIPANT:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, resource.id(), alice.email());

        String counterJsonBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcal)
            .sender(resource.id() + "@open-paas.org")
            .recipient(alice.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json, text/plain, */*")
            .contentType("application/calendar+json")
            .body(counterJsonBody.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("ITIP", resourceEventUri.toASCIIString())
        .then()
            .statusCode(anyOf(is(200), is(202), is(204)));

        // THEN: Alice should receive the COUNTER request in her inbox
        String aliceInboxUri = "/calendars/" + alice.id() + "/inbox/";
        List<JsonNode> aliceInboxItems = calDavClient.reportCalendarEvents(
                alice,
                aliceInboxUri,
                Instant.parse("3025-09-01T00:00:00Z"),
                Instant.parse("3025-11-01T00:00:00Z"))
            .collectList()
            .block();

        assertThat(aliceInboxItems)
            .as("Alice should receive a COUNTER proposal from the resource in her inbox")
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid, "\"COUNTER\"", "mailto:" + alice.email(), "mailto:" + resource.id() + "@open-paas.org");
            });
    }

    @Test
    void resourceAdminCanSendItipCounterViaDelegatedCalendarUri() {
        // GIVEN: Create resource with Bob as admin and grant him read-write rights on the resource calendar
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        // GIVEN: grant Bob read-write rights on the resource calendar using a technical token
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice creates an event that invites the resource as attendee
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T100000
            SUMMARY:Design meeting
            LOCATION:Meeting Room
            DESCRIPTION:Initial meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=whiteboard;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());
        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        // GIVEN: Bob's delegated resource calendar URL and event href
        CalendarURL resourceCalendarUrl = calDavClient.findDelegatedCalendar(bob, resource.id());
        List<String> propfindHrefs = awaitCalendarEntries(
            dockerExtension().davRequestSpecification(),
            bob, resourceCalendarUrl.asUri().toString(), 1);

        // WHEN: Bob (resource admin) sends ITIP COUNTER via delegated calendar event URI
        String counterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T030000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T110000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T120000
            SUMMARY:Design meeting - Proposed new time
            ORGANIZER;CN=whiteboard:mailto:%s@open-paas.org
            ATTENDEE;CN=Alice;ROLE=REQ-PARTICIPANT:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, resource.id(), alice.email());

        String counterJsonBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcal)
            .sender(resource.id() + "@open-paas.org")
            .recipient(alice.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json, text/plain, */*")
            .contentType("application/calendar+json")
            .body(counterJsonBody.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("ITIP", propfindHrefs.getFirst())
        .then()
            .statusCode(anyOf(is(200), is(202), is(204)));

        // THEN: Alice should receive the COUNTER request in her inbox
        String aliceInboxUri = "/calendars/" + alice.id() + "/inbox/";
        List<JsonNode> aliceInboxItems = calDavClient.reportCalendarEvents(
                alice,
                aliceInboxUri,
                Instant.parse("3025-09-01T00:00:00Z"),
                Instant.parse("3025-11-01T00:00:00Z"))
            .collectList()
            .block();

        assertThat(aliceInboxItems)
            .as("Alice should receive a COUNTER proposal from the resource in her inbox")
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid, "\"COUNTER\"", "mailto:" + alice.email(), "mailto:" + resource.id() + "@open-paas.org");
            });
    }

    @Test
    void resourceAdminShouldNotBeAbleToPutAfterDelegationRevoked() {
        // GIVEN: a resource with Bob as delegated admin
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice invites the resource to an event
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T100000
            SUMMARY:Design meeting
            LOCATION:Meeting Room
            DESCRIPTION:Initial meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=whiteboard;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());
        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();
        URI resourceEventUri = calDavClient.findDelegatedCalendar(bob, resource.id()).eventHref(resourceEventId);

        // sanity check: PUT works before revocation
        String initialEventResponseBody = given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("text/calendar")
        .when()
            .get(resourceEventUri.toASCIIString())
        .then()
            .statusCode(200)
            .extract().asString();

        String acceptedEventIcs = initialEventResponseBody.replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");
        Supplier<Response> putAsDelegatedResourceAdmin = () -> given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(acceptedEventIcs.getBytes(StandardCharsets.UTF_8))
        .when()
            .put(resourceEventUri.toASCIIString());

        putAsDelegatedResourceAdmin.get().then().statusCode(204);

        // WHEN: revoke Bob's delegated right on the resource calendar
        revokeResourceAdmin(resource, bob, technicalToken);

        // THEN: PUT on delegated resource event is no longer allowed
        awaitAtMost.untilAsserted(() -> putAsDelegatedResourceAdmin.get().then().statusCode(404));
    }

    @Test
    void resourceAdminShouldNotBeAbleToSendItipAfterDelegationRevoked() {
        // GIVEN: a resource with Bob as delegated admin
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("whiteboard", "Shared whiteboard", bob)
            .block();

        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        // GIVEN: Alice invites the resource to an event
        String eventUid = "event-" + UUID.randomUUID();
        String eventIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T020000Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T090000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T100000
            SUMMARY:Design meeting
            LOCATION:Meeting Room
            DESCRIPTION:Initial meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=whiteboard;CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:%s@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), resource.id());
        calDavClient.upsertCalendarEvent(alice, eventUid, eventIcs);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), bob), Optional::isPresent).get();
        URI resourceEventUri = calDavClient.findDelegatedCalendar(bob, resource.id()).eventHref(resourceEventId);

        String counterIcal = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:30251027T030000Z
            DTSTART;TZID=Asia/Ho_Chi_Minh:30251028T110000
            DTEND;TZID=Asia/Ho_Chi_Minh:30251028T120000
            SUMMARY:Design meeting - Proposed new time
            ORGANIZER;CN=whiteboard:mailto:%s@open-paas.org
            ATTENDEE;CN=Alice;ROLE=REQ-PARTICIPANT:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, resource.id(), alice.email());

        String counterJsonBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcal)
            .sender(resource.id() + "@open-paas.org")
            .recipient(alice.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        // sanity check: ITIP works before revocation
        Supplier<Response> sendItipAsDelegatedResourceAdmin = () -> given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/json, text/plain, */*")
            .contentType("application/calendar+json")
            .body(counterJsonBody.getBytes(StandardCharsets.UTF_8))
        .when()
            .request("ITIP", resourceEventUri.toASCIIString());

        sendItipAsDelegatedResourceAdmin.get().then().statusCode(204);

        // WHEN: revoke Bob's delegated right on the resource calendar
        revokeResourceAdmin(resource, bob, technicalToken);

        // THEN: ITIP via delegated resource event URI is no longer allowed
        awaitAtMost.untilAsserted(() -> sendItipAsDelegatedResourceAdmin.get().then()
            .statusCode(anyOf(is(403), is(404))));
    }

    @Test
    protected void resourceAdminCanListEventsViaDelegatedCalendar() {
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("resourceA", "Shared resource", bob)
            .block();

        // GIVEN: Grant Bob read-write rights on the resource calendar
        String technicalToken = dockerExtension().twakeCalendarProvisioningService().generateToken();
        delegateResourceToAdmin(resource, bob, technicalToken);

        given()
            .header("Authorization", bob.impersonatedBasicAuth())
            .accept("application/calendar+json")
        .when()
            .get("/calendars/" + bob.id() + "/events.json")
        .then()
            .statusCode(200);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = "READ")
    protected void readOnlyDelegatedCalendarShouldOnlyAdvertiseReadPrivileges(DelegationRight right) {

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice PROPFIND the delegated calendar with current-user-privilege-set property
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", alice.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:current-user-privilege-set/>
                  </D:prop>
                </D:propfind>
                """)
        .when()
            .request("PROPFIND", calendarURL.asUri().toString())
        .then()
            .statusCode(207)
            .extract().asString();

        // THEN Alice should see only read privileges in the current-user-privilege-set property of the delegated calendar
        assertThat(responseBody)
            .contains(calendarURL.asUri().toString())
            .contains("<d:read/>")
            .doesNotContain("<d:write/>", "<d:write-content/>", "<d:write-properties/>", "<d:all/>");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DelegationRight.class, names = {"READ_WRITE", "ADMIN"})
    void writeDelegatedCalendarShouldAdvertiseWritePrivileges(DelegationRight right) {

        // GIVEN Bob has a calendar
        // AND Bob delegates that calendar to Alice with write rights
        calDavClient.grantDelegation(bob, bob.id(), alice, right);

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // WHEN Alice PROPFIND the delegated calendar with current-user-privilege-set property
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", alice.impersonatedBasicAuth())
            .body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                  <D:prop>
                    <D:current-user-privilege-set/>
                  </D:prop>
                </D:propfind>
                """)
        .when()
            .request("PROPFIND", calendarURL.asUri().toString())
        .then()
            .statusCode(207)
            .extract().asString();

        // THEN Alice should see write privileges in the current-user-privilege-set property of the delegated calendar
        assertThat(responseBody)
            .contains(calendarURL.asUri().toString())
            .contains("<d:read/>", "<d:write/>");
    }

    private void delegateResourceToAdmin(OpenPaaSResource resource, OpenPaasUser admin, String technicalToken) {
        calDavClient.grantDelegation(resource.id(), admin, DelegationRight.READ_WRITE, technicalToken);
    }

    private void revokeResourceAdmin(OpenPaaSResource resource, OpenPaasUser admin, String technicalToken) {
        calDavClient.revokeDelegation(resource.id(), admin, technicalToken);
    }

    @Test
    void aliceShouldNotSeeDelegatedCalendarInHerCalendarListAfterDeleting() throws Exception {
        // GIVEN Bob grants write delegation to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        // AND Alice's calendar list (JSON response) contains the delegated calendar copy
        String jsonAliceCalendars = calDavClient.getUserCalendarsJson(alice);
        assertThat(jsonAliceCalendars)
            .contains("\"calendarserver:delegatedsource\":\"\\/calendars\\/" + bob.id() + "\\/" + bob.id() + ".json\"");

        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());

        // AND Alice's calendar list (XML response) contains the delegated calendar copy
        List<String> xmlAliceCalendars = getXmlCalendars(alice);
        assertThat(xmlAliceCalendars).contains(calendarURL.asUri() + "/");

        // WHEN Alice deletes the calendar copy
        calDavClient.deleteCalendar(alice, calendarURL);

        // THEN the copy no longer appears in Alice's calendar list (JSON response)
        String updatedJsonAliceCalendars = calDavClient.getUserCalendarsJson(alice);

        assertThat(updatedJsonAliceCalendars)
            .doesNotContain("\"calendarserver:delegatedsource\":\"\\/calendars\\/" + bob.id() + "\\/" + bob.id() + ".json\"");

        // AND the copy no longer appears in Alice's calendar list (XML response)
        List<String> updatedXmlAliceCalendars = getXmlCalendars(alice);
        assertThat(updatedXmlAliceCalendars).doesNotContain(calendarURL.asUri() + "/");
    }

    @Test
    protected void bobInviteShouldPreserveAliceDelegationAccessAfterDeletingDelegatedCalendar() {
        // GIVEN Bob grants write delegation to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        // WHEN Alice deletes the calendar copy
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        calDavClient.deleteCalendar(alice, calendarURL);

        // AND Bob's calendar invite still shows Alice with her original delegation access
        String bobCalendarsResponse = given()
            .headers("Authorization", bob.impersonatedBasicAuth())
            .queryParam("sharedDelegationStatus", "accepted")
            .queryParam("sharedPublicSubscription", true)
            .queryParam("personal", true)
            .queryParam("withRights", true)
        .when()
            .get("/calendars/" + bob.id() + ".json")
        .then()
            .extract().asString();
        assertThatJson(bobCalendarsResponse)
            .inPath("_embedded.dav:calendar[0].invite[1]")
            .isEqualTo("""
                {
                    "href": "mailto:%s",
                    "principal": "principals/users/%s",
                    "properties": [],
                    "access": 3,
                    "comment": null,
                    "inviteStatus": 2
                }
                """.formatted(alice.email(), alice.id()));
    }

    @Test
    protected void aliceShouldBeAbleToWriteEventsToBobCalendarAfterDeletingDelegatedCalendarAndResubscribing() throws Exception {
        // GIVEN Bob sets his calendar as publicly readable
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");
        // AND Bob grants write delegation to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        // AND Alice deletes the calendar copy
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        calDavClient.deleteCalendar(alice, calendarURL);

        // WHEN Alice re-subscribes to Bob's calendar
        String subscriptionId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribeRequest = SubscribedCalendarRequest.builder()
            .id(subscriptionId)
            .sourceUserId(bob.id())
            .name("Bob's calendar")
            .color("#FF0000")
            .readOnly(false)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribeRequest);

        // AND Alice writes an event via the re-subscribed calendar
        String eventUid = "event-" + UUID.randomUUID();
        String calendarData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20300411T100000Z
            DTEND:20300411T110000Z
            SUMMARY:Alice created event after resubscribe
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        List<CalendarURL> calendarUrls = calDavClient.findUserCalendars(alice)
            .filter(url -> !url.calendarId().equals(alice.id()))
            .collectList()
            .block();
        assertThat(calendarUrls).hasSize(1);
        calDavClient.upsertCalendarEvent(alice, calendarUrls.getFirst(), eventUid, calendarData);

        // THEN the event appears in Bob's source calendar
        Response response = calDavClient.findEventsByTime(bob, "20300310T000000", "20300510T000000");
        List<JsonCalendarEventData> result = JsonCalendarEventData.from(response.asString());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo(eventUid);
    }

    @Test
    void resubscribedAliceShouldOnlyHavePublicRightAfterAliceDeletesAndBobRevokes() throws Exception {
        // GIVEN Bob sets his calendar as publicly readable
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");
        // AND Bob grants write delegation to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);
        // AND Bob has an event in his calendar
        String eventUid = "event-" + UUID.randomUUID();
        String bobEventData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20300411T100000Z
            DTEND:20300411T110000Z
            SUMMARY:Bob's event
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        calDavClient.upsertCalendarEvent(bob, eventUid, bobEventData);

        // AND Alice deletes the calendar copy
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        calDavClient.deleteCalendar(alice, calendarURL);

        // AND Bob revokes Alice's delegation
        calDavClient.revokeDelegation(bob, bob.id(), alice);

        // WHEN Alice re-subscribes to Bob's calendar
        String subscriptionId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribeRequest = SubscribedCalendarRequest.builder()
            .id(subscriptionId)
            .sourceUserId(bob.id())
            .name("Bob's calendar")
            .color("#FF0000")
            .readOnly(true)
            .build();
        CalendarURL subscribedCalendarURL = calDavClient.subscribeToSharedCalendar(alice, subscribeRequest);

        // THEN Bob's delegated list does not show Alice with write access
        String bobCalendarsResponse = calDavClient.getUserCalendarsJson(bob);
        assertThat(bobCalendarsResponse).doesNotContain(alice.id());

        // AND Alice can read Bob's calendar via the subscription
        Response readResponse = calDavClient.findEventsByTime(alice, subscribedCalendarURL, "20300310T000000", "20300510T000000");
        List<JsonCalendarEventData> readResult = JsonCalendarEventData.from(readResponse.asString());
        assertThat(readResult).hasSize(1);
        assertThat(readResult.get(0).uid()).isEqualTo(eventUid);

        // AND Alice cannot write to Bob's calendar
        String newEventUid = "event-" + UUID.randomUUID();
        String writeAttemptData = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250929T080000Z
            DTSTART:20300411T100000Z
            DTEND:20300411T110000Z
            SUMMARY:Alice attempts write after revocation
            END:VEVENT
            END:VCALENDAR
            """.formatted(newEventUid);
        assertThatThrownBy(() -> calDavClient.upsertCalendarEvent(alice, subscribedCalendarURL, newEventUid, writeAttemptData))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void newColorShouldBeAppliedAfterDeletingDelegatedCalendarAndResubscribing() {
        // GIVEN Bob sets his calendar as publicly readable
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");
        // AND Bob grants write delegation to Alice
        calDavClient.grantDelegation(bob, bob.id(), alice, DelegationRight.READ_WRITE);

        // AND Alice deletes the calendar copy
        CalendarURL calendarURL = calDavClient.findDelegatedCalendar(alice, bob.id());
        calDavClient.deleteCalendar(alice, calendarURL);

        // WHEN Alice re-subscribes to Bob's calendar
        String subscriptionId = UUID.randomUUID().toString();
        SubscribedCalendarRequest subscribeRequest = SubscribedCalendarRequest.builder()
            .id(subscriptionId)
            .sourceUserId(bob.id())
            .name("Bob's calendar")
            .color("#874D46")
            .readOnly(false)
            .build();
        calDavClient.subscribeToSharedCalendar(alice, subscribeRequest);

        // THEN the new color is reflected in Alice's calendar list
        String jsonAliceCalendars = calDavClient.getUserCalendarsJson(alice);
        assertThat(jsonAliceCalendars).contains("\"apple:color\":\"#874D46\"");
    }

    private List<String> getXmlCalendars(OpenPaasUser user) throws Exception {
        String responseBody = given()
            .contentType("application/xml")
            .accept("application/xml")
            .header("Authorization", user.impersonatedBasicAuth())
        .when()
            .request("PROPFIND", "/calendars/" + user.id())
        .then()
            .statusCode(207)
            .extract().asString();

        List<String> xmlAliceCalendars = XMLUtil.extractMultipleValueByXPath(
            responseBody,
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );
        return xmlAliceCalendars;
    }
}
