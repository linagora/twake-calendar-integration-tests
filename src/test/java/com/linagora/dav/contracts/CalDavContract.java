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

package com.linagora.dav.contracts;

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static com.linagora.dav.contracts.CalendarSharingContract.MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.xmlunit.diff.ComparisonResult.SIMILAR;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.DifferenceEvaluator;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarUtil;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestUtil;
import com.linagora.dav.TwakeCalendarEvent;
import com.linagora.dav.XMLUtil;

import io.netty.handler.codec.http.HttpMethod;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.ScheduleStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClientResponse;

public abstract class CalDavContract {
    public static final DifferenceEvaluator DIFFERENCE_EVALUATOR = (comparison, outcome) -> {
        if (outcome.equals(ComparisonResult.DIFFERENT) &&
            comparison.getControlDetails().getXPath() != null &&
            comparison.getControlDetails().getXPath().contains("getetag")) {
            return SIMILAR;
        }
        if (outcome.equals(ComparisonResult.DIFFERENT) &&
            comparison.getControlDetails().getXPath() != null &&
            comparison.getControlDetails().getXPath().contains("getlastmodified")) {
            return SIMILAR;
        }
        if (outcome.equals(ComparisonResult.DIFFERENT) &&
            comparison.getControlDetails().getXPath() == null &&
            comparison.getControlDetails().getValue() == null &&
            comparison.getControlDetails().getTarget() == null &&
            comparison.getControlDetails().getParentXPath().equals("/multistatus[1]/response[2]/propstat[1]/prop[1]")) {
            return SIMILAR;
        }
        return outcome;
    };

    public static final String ICS_1 = "BEGIN:VCALENDAR\r\n" +
        "VERSION:2.0\r\n" +
        "PRODID:-//Sabre//Sabre VObject 4.1.3//EN\r\n" +
        "CALSCALE:GREGORIAN\r\n" +
        "BEGIN:VTIMEZONE\r\n" +
        "TZID:Europe/Paris\r\n" +
        "BEGIN:DAYLIGHT\r\n" +
        "TZOFFSETFROM:+0100\r\n" +
        "TZOFFSETTO:+0200\r\n" +
        "TZNAME:CEST\r\n" +
        "DTSTART:19700329T020000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" +
        "END:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\n" +
        "TZOFFSETFROM:+0200\r\n" +
        "TZOFFSETTO:+0100\r\n" +
        "TZNAME:CET\r\n" +
        "DTSTART:19701025T030000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" +
        "END:STANDARD\r\n" +
        "END:VTIMEZONE\r\n" +
        "BEGIN:VEVENT\r\n" +
        "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b\r\n" +
        "TRANSP:OPAQUE\r\n" +
        "DTSTART;TZID=Europe/Paris:20250214T110000\r\n" +
        "DTEND;TZID=Europe/Paris:20250214T114500\r\n" +
        "CLASS:PUBLIC\r\n" +
        "X-OPENPAAS-VIDEOCONFERENCE:\r\n" +
        "SUMMARY:OW2con'25\r\n" +
        "DESCRIPTION:Avoir un draft de prêt\r\n" +
        "LOCATION:https://jitsi.linagora.com/ow2\r\n" +
        "ORGANIZER;CN=Julie VERRIER:mailto:jverrier@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Alexandre PUJOL:mailto:apujol@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Benoît TELLIER:mailto:btellier@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Xavier GUIMARD:mailto:xguimard@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Frédéric HERMELIN:mailto:fhermelin@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:j\r\n" +
        " verrier@linagora.com\r\n" +
        "DTSTAMP:20250205T170516Z\r\n" +
        "SEQUENCE:0\r\n" +
        "END:VEVENT\r\n" +
        "END:VCALENDAR\r\n";

    public static final String ICS_3_A = "BEGIN:VCALENDAR\r\n" +
        "VERSION:2.0\r\n" +
        "PRODID:-//Sabre//Sabre VObject 4.1.3//EN\r\n" +
        "CALSCALE:GREGORIAN\r\n" +
        "BEGIN:VTIMEZONE\r\n" +
        "TZID:Europe/Paris\r\n" +
        "BEGIN:DAYLIGHT\r\n" +
        "TZOFFSETFROM:+0100\r\n" +
        "TZOFFSETTO:+0200\r\n" +
        "TZNAME:CEST\r\n" +
        "DTSTART:19700329T020000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" +
        "END:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\n" +
        "TZOFFSETFROM:+0200\r\n" +
        "TZOFFSETTO:+0100\r\n" +
        "TZNAME:CET\r\n" +
        "DTSTART:19701025T030000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" +
        "END:STANDARD\r\n" +
        "END:VTIMEZONE\r\n" +
        "BEGIN:VEVENT\r\n" +
        "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b\r\n" +
        "TRANSP:OPAQUE\r\n" +
        "DTSTART;TZID=Europe/Paris:20250214T110000\r\n" +
        "DTEND;TZID=Europe/Paris:20250214T114500\r\n" +
        "CLASS:PUBLIC\r\n" +
        "X-OPENPAAS-VIDEOCONFERENCE:\r\n" +
        "SUMMARY:OW2con'25\r\n" +
        "DESCRIPTION:Avoir un draft de prêt\r\n" +
        "LOCATION:https://jitsi.linagora.com/ow2\r\n" +
        "DTSTAMP:20250205T170516Z\r\n" +
        "SEQUENCE:0\r\n" +
        "END:VEVENT\r\n" +
        "END:VCALENDAR\r\n";

    public static final String ICS_3_B = "BEGIN:VCALENDAR\r\n" +
        "VERSION:2.0\r\n" +
        "PRODID:-//Sabre//Sabre VObject 4.1.3//EN\r\n" +
        "CALSCALE:GREGORIAN\r\n" +
        "BEGIN:VTIMEZONE\r\n" +
        "TZID:Europe/Paris\r\n" +
        "BEGIN:DAYLIGHT\r\n" +
        "TZOFFSETFROM:+0100\r\n" +
        "TZOFFSETTO:+0200\r\n" +
        "TZNAME:CEST\r\n" +
        "DTSTART:19700329T020000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" +
        "END:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\n" +
        "TZOFFSETFROM:+0200\r\n" +
        "TZOFFSETTO:+0100\r\n" +
        "TZNAME:CET\r\n" +
        "DTSTART:19701025T030000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" +
        "END:STANDARD\r\n" +
        "END:VTIMEZONE\r\n" +
        "BEGIN:VEVENT\r\n" +
        "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b\r\n" +
        "TRANSP:OPAQUE\r\n" +
        "DTSTART;TZID=Europe/Paris:20250214T110000\r\n" +
        "DTEND;TZID=Europe/Paris:20250214T114500\r\n" +
        "CLASS:PUBLIC\r\n" +
        "X-OPENPAAS-VIDEOCONFERENCE:\r\n" +
        "SUMMARY:OW2con'25\r\n" +
        "DESCRIPTION:Avoir un draft de prêt\r\n" +
        "LOCATION:https://jitsi.linagora.com/ow2\r\n" +
        "ORGANIZER;CN=Julie VERRIER:mailto:[REPLACE]\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Alexandre PUJOL:mailto:apujol@linagora.com\r\n" +
        "DTSTAMP:20250205T170516Z\r\n" +
        "SEQUENCE:0\r\n" +
        "END:VEVENT\r\n" +
        "END:VCALENDAR\r\n";

    public static final String ICS_2 = "BEGIN:VCALENDAR\r\n" +
        "VERSION:2.0\r\n" +
        "PRODID:-//Sabre//Sabre VObject 4.1.3//EN\r\n" +
        "CALSCALE:GREGORIAN\r\n" +
        "BEGIN:VTIMEZONE\r\n" +
        "TZID:Europe/Paris\r\n" +
        "BEGIN:DAYLIGHT\r\n" +
        "TZOFFSETFROM:+0100\r\n" +
        "TZOFFSETTO:+0200\r\n" +
        "TZNAME:CEST\r\n" +
        "DTSTART:19700329T020000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" +
        "END:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\n" +
        "TZOFFSETFROM:+0200\r\n" +
        "TZOFFSETTO:+0100\r\n" +
        "TZNAME:CET\r\n" +
        "DTSTART:19701025T030000\r\n" +
        "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" +
        "END:STANDARD\r\n" +
        "END:VTIMEZONE\r\n" +
        "BEGIN:VEVENT\r\n" +
        "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b\r\n" +
        "TRANSP:OPAQUE\r\n" +
        "DTSTART;TZID=Europe/Paris:20250214T110000\r\n" +
        "DTEND;TZID=Europe/Paris:20250214T114500\r\n" +
        "CLASS:PUBLIC\r\n" +
        "X-OPENPAAS-VIDEOCONFERENCE:\r\n" +
        "SUMMARY:OW2con'25\r\n" +
        "DESCRIPTION:Avoir un draft de prêt\r\n" +
        "LOCATION:https://jitsi.linagora.com/ow2\r\n" +
        "ORGANIZER;CN=Julie VERRIER:mailto:jverrier@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Alexandre PUJOL:mailto:apujol@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL\r\n" +
        " ;CN=Benoît TELLIER:mailto:btellier@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Xavier GUIMARD:mailto:xguimard@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
        " DUAL;CN=Frédéric HERMELIN:mailto:fhermelin@linagora.com\r\n" +
        "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:j\r\n" +
        " verrier@linagora.com\r\n" +
        "DTSTAMP:20250205T170516Z\r\n" +
        "SEQUENCE:0\r\n" +
        "END:VEVENT\r\n" +
        "END:VCALENDAR\r\n";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(30, TimeUnit.SECONDS);

    private CalDavClient calDavClient;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
    }

    @Test
    void propfindShouldListCalendars() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        assertThat(actual).containsExactlyInAnyOrder("/calendars/" + testUser.id() + "/",
            "/calendars/" + testUser.id() + "/" + testUser.id() + "/",
            "/calendars/" + testUser.id() + "/inbox/",
            "/calendars/" + testUser.id() + "/outbox/");
    }

    @Test
    void proppatchShouldUpdateDisplayName() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/calendars/" + testUser.id())
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Calendar Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(status).isEqualTo(207);
        assertThat(response.status()).isEqualTo(207);

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        assertThat(actual).containsExactlyInAnyOrder("/calendars/" + testUser.id() + "/",
            "/calendars/" + testUser.id() + "/" + testUser.id() + "/",
            "/calendars/" + testUser.id() + "/inbox/",
            "/calendars/" + testUser.id() + "/outbox/");
    }

    @Test
    void propfindShouldListEmptyCalendar() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/1</cs:getctag><s:sync-token>1</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void propfindShouldReturnCreatedCalendar() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/calendars/" + testUser.id()  + "/testCalendar")
            .send(body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                     <D:displayname>New Event XYZ</D:displayname>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """)));


        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()  ));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(207);

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        assertThat(actual).contains("/calendars/" + testUser.id() + "/testCalendar/");
    }

    @Test
    void propfindShouldNotReturnDeletedCalendar() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/calendars/" + testUser.id()  + "/testCalendar")
            .send(body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                     <D:displayname>New Event XYZ</D:displayname>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """)));

        int status2 =executeNoContent(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .delete()
            .uri("/calendars/" + testUser.id()  + "/testCalendar"));


        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()  ));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(response.status()).isEqualTo(207);

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        assertThat(actual).doesNotContain("/calendars/" + testUser.id() + "/testCalendar/");
    }

    @Test
    void propfindShouldListCreatedEvents() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id()));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(207);

        List<String> actual = XMLUtil.extractMultipleValueByXPath(
            response.body(),
            "//d:multistatus/d:response/d:href",
            Map.of("d", "DAV:")
        );

        assertThat(actual).contains("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics");
    }

    @Test
    void propfindShouldNotListDeletedEvents() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id()));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/3</cs:getctag><s:sync-token>3</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(DIFFERENCE_EVALUATOR)
            .areSimilar();
    }

    @Test
    void getShouldReturnPreviouslyUploadedData() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(ICS_1);
    }

    @Test
    void getShouldNotReturnPreviouslyDeletedData() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status3 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(status3).isEqualTo(404);
    }

    @Test
    void putShouldUpdateData() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(ICS_2);
    }

    @Test
    void putShouldUpdateAttendeeWhenNone() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_3_A)));

        String payload = ICS_3_B.replace("[REPLACE]", testUser.email());
        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(payload)));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("apujol@linagora.com");
    }

    @Test
    void conditionalUpdateShouldFailWithBadTag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", "bad"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(412);
    }

    @Test
    void conditionalUpdateShouldSucceedWithGoodTag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", response.responseHeaders().get("ETag")))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        AssertionsForClassTypes.assertThat(response2.status()).isEqualTo(200);
        AssertionsForClassTypes.assertThat(response2.body()).isEqualTo(ICS_2);
    }

    @Test
    void conditionalDeleteShouldFailWithBadTag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", "bad"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(412);
    }

    @Test
    void conditionalDeleteShouldSucceedWithGoodTag() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", response.responseHeaders().get("ETag")))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        int status3 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        AssertionsForClassTypes.assertThat(status3).isEqualTo(404);
    }

    @Test
    void canReportSyncTokenInitialSync() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "0"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("<d:propfind xmlns:d=\"DAV:\" xmlns:cs=\"http://calendarserver.org/ns/\">" +
                "  <d:prop>\n" +
                "     <d:sync-token />" +
                "  </d:prop>" +
                "</d:propfind>")));

        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:sync-token>http://sabre.io/ns/sync/1</d:sync-token></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void syncTokenCanReportAdded() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "0"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
                "<d:sync-collection xmlns:d=\"DAV:\">" +
                "  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(response2.status()).isEqualTo(207);
        XmlAssert.assertThat(response2.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:response>\n" +
                "  <d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics</d:href>\n" +
                "  <d:propstat>\n" +
                "   <d:prop>\n" +
                "    <d:getetag>&quot;8c97fb06c60212c47d46a9c8c0f625ef&quot;</d:getetag>\n" +
                "   </d:prop>\n" +
                "   <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "  </d:propstat>\n" +
                " </d:response>\n" +
                " <d:sync-token>http://sabre.io/ns/sync/2</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void syncTokenCanReportDeleted() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        DavResponse response3 = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "0"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
                "<d:sync-collection xmlns:d=\"DAV:\">" +
                "  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        XmlAssert.assertThat(response3.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:response>\n" +
                "  <d:status>HTTP/1.1 404 Not Found</d:status>\n" +
                "  <d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics</d:href>\n" +
                "  <d:propstat>\n" +
                "   <d:prop/>\n" +
                "   <d:status>HTTP/1.1 418 I'm a teapot</d:status>\n" +
                "  </d:propstat>\n" +
                " </d:response>\n" +
                " <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areSimilar();
    }

    @Test
    void syncTokenCanReportUpdated() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DavResponse response3 = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "0"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
                "<d:sync-collection xmlns:d=\"DAV:\">" +
                "  <d:sync-token>http://sabre.io/ns/sync/1</d:sync-token>" +
                "  <d:sync-level>1</d:sync-level>" +
                "  <d:prop>" +
                "    <d:getetag/>" +
                "  </d:prop>" +
                "</d:sync-collection>")));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        XmlAssert.assertThat(response3.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">\n" +
                " <d:response>\n" +
                "  <d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics</d:href>\n" +
                "  <d:propstat>\n" +
                "   <d:prop>\n" +
                "    <d:getetag>&quot;7ad7ef37853526213d24cb72d76bea6d&quot;</d:getetag>\n" +
                "   </d:prop>\n" +
                "   <d:status>HTTP/1.1 200 OK</d:status>\n" +
                "  </d:propstat>\n" +
                " </d:response>\n" +
                " <d:sync-token>http://sabre.io/ns/sync/3</d:sync-token>\n" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(DIFFERENCE_EVALUATOR)
            .areSimilar();
    }

    @Test
    void shouldSupportExport() {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(testUser::impersonatedBasicAuth)
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "?export"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(response2.status()).isEqualTo(200);

        Calendar actualCalendar = CalendarUtil.parseIcs(response2.body());
        actualCalendar.removeAll(Property.PRODID);
        Calendar expectedCalendar = CalendarUtil.parseIcs("BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "CALSCALE:GREGORIAN\n" +
            "X-WR-CALNAME:#default\n" +
            "BEGIN:VTIMEZONE\n" +
            "TZID:Europe/Paris\n" +
            "BEGIN:DAYLIGHT\n" +
            "TZOFFSETFROM:+0100\n" +
            "TZOFFSETTO:+0200\n" +
            "TZNAME:CEST\n" +
            "DTSTART:19700329T020000\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\n" +
            "END:DAYLIGHT\n" +
            "BEGIN:STANDARD\n" +
            "TZOFFSETFROM:+0200\n" +
            "TZOFFSETTO:+0100\n" +
            "TZNAME:CET\n" +
            "DTSTART:19701025T030000\n" +
            "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\n" +
            "END:STANDARD\n" +
            "END:VTIMEZONE\n" +
            "BEGIN:VEVENT\n" +
            "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b\n" +
            "TRANSP:OPAQUE\n" +
            "DTSTART;TZID=Europe/Paris:20250214T110000\n" +
            "DTEND;TZID=Europe/Paris:20250214T114500\n" +
            "CLASS:PUBLIC\n" +
            "X-OPENPAAS-VIDEOCONFERENCE:\n" +
            "SUMMARY:OW2con'25\n" +
            "DESCRIPTION:Avoir un draft de prêt\n" +
            "LOCATION:https://jitsi.linagora.com/ow2\n" +
            "ORGANIZER;CN=Julie VERRIER:mailto:jverrier@linagora.com\n" +
            "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\n" +
            " DUAL;CN=Alexandre PUJOL:mailto:apujol@linagora.com\n" +
            "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\n" +
            " DUAL;CN=Benoît TELLIER:mailto:btellier@linagora.com\n" +
            "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\n" +
            " DUAL;CN=Xavier GUIMARD:mailto:xguimard@linagora.com\n" +
            "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\n" +
            " DUAL;CN=Frédéric HERMELIN:mailto:fhermelin@linagora.com\n" +
            "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:j\n" +
            " verrier@linagora.com\n" +
            "DTSTAMP:20250205T170516Z\n" +
            "SEQUENCE:0\n" +
            "END:VEVENT\n" +
            "END:VCALENDAR\n");

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void inboxShouldContainInvites() throws Exception {
        // CF https://sabre.io/dav/scheduling/
        OpenPaasUser testUser1 = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        int status1 = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> testUser1.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser1.id()  + "/" + testUser1.id() + "/abcd.ics")
            .send(body("BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//Sabre//Sabre VObject 4.1.3//EN\r\n" +
                "CALSCALE:GREGORIAN\r\n" +
                "BEGIN:VTIMEZONE\r\n" +
                "TZID:Europe/Paris\r\n" +
                "BEGIN:DAYLIGHT\r\n" +
                "TZOFFSETFROM:+0100\r\n" +
                "TZOFFSETTO:+0200\r\n" +
                "TZNAME:CEST\r\n" +
                "DTSTART:19700329T020000\r\n" +
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\n" +
                "END:DAYLIGHT\r\n" +
                "BEGIN:STANDARD\r\n" +
                "TZOFFSETFROM:+0200\r\n" +
                "TZOFFSETTO:+0100\r\n" +
                "TZNAME:CET\r\n" +
                "DTSTART:19701025T030000\r\n" +
                "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\n" +
                "END:STANDARD\r\n" +
                "END:VTIMEZONE\r\n" +
                "BEGIN:VEVENT\r\n" +
                "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b\r\n" +
                "TRANSP:OPAQUE\r\n" +
                "DTSTART;TZID=Europe/Paris:20250214T110000\r\n" +
                "DTEND;TZID=Europe/Paris:20250214T114500\r\n" +
                "CLASS:PUBLIC\r\n" +
                "X-OPENPAAS-VIDEOCONFERENCE:\r\n" +
                "SUMMARY:OW2con'25\r\n" +
                "DESCRIPTION:Avoir un draft de prêt\r\n" +
                "LOCATION:https://jitsi.linagora.com/ow2\r\n" +
                "ORGANIZER;CN=Julie VERRIER:mailto:" + testUser1.email() + "\r\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
                " DUAL;CN=AP:mailto:" + testUser2.email() + "\r\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
                " DUAL;CN=Benoît TELLIER:mailto:btellier@linagora.com\r\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
                " DUAL;CN=Xavier GUIMARD:mailto:xguimard@linagora.com\r\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI\r\n" +
                " DUAL;CN=Frédéric HERMELIN:mailto:fhermelin@linagora.com\r\n" +
                "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:j\r\n" +
                " verrier@linagora.com\r\n" +
                "DTSTAMP:20250205T170516Z\r\n" +
                "SEQUENCE:0\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR\r\n")));

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(testUser2::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser2.id() + "/inbox"));


        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(207);
        System.out.println(XMLUtil.extractMultipleValueByXPath(response.body(),
            "//d:multistatus/d:response/d:href", Map.of("d", "DAV:")));
        List<String> actual = XMLUtil.extractMultipleValueByXPath(response.body(),
            "//d:multistatus/d:response/d:href", Map.of("d", "DAV:"));
        assertThat(actual).hasSize(2);
        assertThat(actual.get(0)).isEqualTo("/calendars/" + testUser2.id() + "/inbox/");
        assertThat(actual.get(1)).startsWith("/calendars/" + testUser2.id() + "/inbox/sabredav-").endsWith(".ics");
    }

    @Test
    void attendeeInboxShouldReceiveItipRequestWhenInvitedByOrganizer() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        // GIVEN: An organizer and an attendee
        String eventUid = "event-" + UUID.randomUUID();

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T080000Z
            DTSTART:20251009T090000Z
            DTEND:20251009T100000Z
            SUMMARY:Meeting invitation
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), attendee.email());

        // WHEN: The organizer creates an event including the attendee
        String calendarUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(organizer, URI.create(calendarUri), ics);

        // THEN: The attendee should receive an ITIP REQUEST message in their inbox
        Function<String, List<JsonNode>> findEvents = (uri) ->
            calDavClient.reportCalendarEvents(attendee, uri,
                    Instant.parse("2024-09-01T00:00:00"),
                    Instant.parse("2026-11-01T00:00:00"))
                .collectList()
                .block();

        String attendeeInboxUri = "/calendars/" + attendee.id() + "/inbox/";
        awaitAtMost.untilAsserted(() -> assertThat(findEvents.apply(attendeeInboxUri))
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + attendee.email());
                assertThat(json).contains("Meeting invitation");
                assertThatJson(item)
                    .inPath("data[1]")
                    .isArray()
                    .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                        .isEqualTo("""
                            ["method", {}, "text", "REQUEST"]"""));
            }));
    }

    @Test
    void attendeeInboxShouldBeAbleToDeleteInboxItems() throws Exception {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        // GIVEN: An organizer and an attendee
        String eventUid = "event-" + UUID.randomUUID();

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T080000Z
            DTSTART:20251009T090000Z
            DTEND:20251009T100000Z
            SUMMARY:Meeting invitation
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), attendee.email());
        // And: The organizer creates an event including the attendee
        String calendarUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(organizer, URI.create(calendarUri), ics);

        // When: The attendee deletes the INBOX item
        Function<String, List<JsonNode>> findEvents = (uri) ->
            calDavClient.reportCalendarEvents(attendee, uri,
                    Instant.parse("2024-09-01T00:00:00"),
                    Instant.parse("2026-11-01T00:00:00"))
                .collectList()
                .block();
        String attendeeInboxUri = "/calendars/" + attendee.id() + "/inbox/";
        awaitAtMost.untilAsserted(() -> assertThat(findEvents.apply(attendeeInboxUri))
            .anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + attendee.email());
                assertThat(json).contains("Meeting invitation");
                assertThatJson(item)
                    .inPath("data[1]")
                    .isArray()
                    .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                        .isEqualTo("""
                            ["method", {}, "text", "REQUEST"]"""));
            }));

        calDavClient.deleteCalendarEvent(attendee, new URI(findEvents.apply(attendeeInboxUri).get(0)
            .get("_links").get("self").get("href").asText()));

        // THEN the item is deleted
        assertThat(findEvents.apply(attendeeInboxUri).size()).isZero();
    }

    @Test
    void attendeeInboxShouldReceiveItipRequestWhenOrganizerUpdatesEvent() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        // GIVEN: An organizer created an event with an attendee
        String eventUid = "event-" + UUID.randomUUID();

        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T080000Z
            DTSTART:20251009T090000Z
            DTEND:20251009T100000Z
            SUMMARY:Initial meeting
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), attendee.email());

        String calendarUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(organizer, URI.create(calendarUri), initialIcs);

        // WHEN: The organizer updates the event (e.g. change summary)
        String updatedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T090000Z
            DTSTART:20251009T100000Z
            DTEND:20251009T110000Z
            SUMMARY:Updated meeting
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), attendee.email());

        calDavClient.upsertCalendarEvent(organizer, URI.create(calendarUri), updatedIcs);

        // THEN: The attendee should receive a new ITIP REQUEST (update) in their inbox
        String attendeeInboxUri = "/calendars/" + attendee.id() + "/inbox/";
        awaitAtMost.untilAsserted(() -> {
            var events = calDavClient.reportCalendarEvents(attendee, attendeeInboxUri,
                    Instant.parse("2024-09-01T00:00:00"),
                    Instant.parse("2026-11-01T00:00:00"))
                .collectList().block();

            assertThat(events)
                .as("Attendee should have received an ITIP update (REQUEST)")
                .anySatisfy(item -> {
                    String json = item.toString();
                    assertThat(json).contains(eventUid);
                    assertThat(json).contains("Updated meeting");
                    assertThatJson(item)
                        .inPath("data[1]")
                        .isArray()
                        .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                            .isEqualTo("""
                                ["method", {}, "text", "REQUEST"]"""));
                });
        });
    }

    @Test
    void attendeeInboxShouldReceiveItipCancelWhenOrganizerDeletesEvent() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        // GIVEN: An organizer created an event with an attendee
        String eventUid = "event-" + UUID.randomUUID();

        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251008T080000Z
            DTSTART:20251009T090000Z
            DTEND:20251009T100000Z
            SUMMARY:Meeting to be cancelled
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), attendee.email());

        String calendarUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(organizer, URI.create(calendarUri), ics);

        // WHEN: The organizer deletes the event
        calDavClient.deleteCalendarEvent(organizer, URI.create(calendarUri));

        // THEN: The attendee should receive an ITIP CANCEL message in their inbox
        String attendeeInboxUri = "/calendars/" + attendee.id() + "/inbox/";
        awaitAtMost.untilAsserted(() -> {
            var events = calDavClient.reportCalendarEvents(attendee, attendeeInboxUri,
                    Instant.parse("2025-09-01T00:00:00"),
                    Instant.parse("2025-11-01T00:00:00"))
                .collectList().block();

            assertThat(events)
                .as("Attendee should have received an ITIP CANCEL message after organizer deletion")
                .anySatisfy(item -> {
                    String json = item.toString();
                    assertThat(json).contains(eventUid);
                    assertThat(json).contains("Meeting to be cancelled");
                    assertThatJson(item)
                        .inPath("data[1]")
                        .isArray()
                        .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                            .isEqualTo("""
                                ["method", {}, "text", "CANCEL"]"""));
                });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "TENTATIVE", "DECLINED"})
    void organizerInboxShouldReceiveItipReplyWhenAttendeeReply(String partstat) {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser attendee = dockerExtension().newTestUser();

        // GIVEN: The organizer created an event with the attendee
        String eventUid = "event-" + UUID.randomUUID();
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080000Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Reply Scenario Meeting
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), attendee.email());

        String organizerEventUri = "/calendars/" + organizer.id() + "/" + organizer.id() + "/" + eventUid + ".ics";
        calDavClient.upsertCalendarEvent(organizer, URI.create(organizerEventUri), initialIcs);

        String attendeeDefaultCalendarUri = "/calendars/" + attendee.id() + "/" + attendee.id();
        String organizerInboxUri = "/calendars/" + organizer.id() + "/inbox/";

        // Locate the attendee's resource href to upsert replies on their own copy
        List<JsonNode> attendeeInitialEvents = calDavClient.reportCalendarEvents(attendee, attendeeDefaultCalendarUri,
                Instant.parse("2025-09-01T00:00:00"),
                Instant.parse("2025-11-01T00:00:00"))
            .collectList()
            .block();

        String attendeeEventHref = attendeeInitialEvents.stream()
            .filter(node -> node.toString().contains(eventUid))
            .map(node -> node.at("/_links/self/href").asText())
            .findFirst()
            .orElseThrow(() -> new AssertionError("Attendee's copy of the event not found"));

        // Build attendee's REPLY ICS (only PARTSTAT changes; keep SUMMARY as-is)
        String attendeeReplyIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20251003T080100Z
            DTSTART:20251005T090000Z
            DTEND:20251005T100000Z
            SUMMARY:Reply Scenario Meeting
            ORGANIZER;CN=Organizer:mailto:%s
            ATTENDEE;CN=Attendee;PARTSTAT=%s:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, organizer.email(), partstat, attendee.email());

        // WHEN: The attendee sends a REPLY with the given PARTSTAT
        calDavClient.upsertCalendarEvent(attendee, URI.create(attendeeEventHref), attendeeReplyIcs);

        // THEN: The organizer's inbox should contain a REPLY for this UID
        Function<String, List<JsonNode>> organizerEventsForUri = (uri) ->
            calDavClient.reportCalendarEvents(organizer, uri,
                    Instant.parse("2025-09-01T00:00:00"),
                    Instant.parse("2025-11-01T00:00:00"))
                .collectList()
                .block();

        awaitAtMost.untilAsserted(() -> {
            List<JsonNode> organizerInbox = organizerEventsForUri.apply(organizerInboxUri);
            assertThat(organizerInbox).anySatisfy(item -> {
                String json = item.toString();
                assertThat(json).contains(eventUid);
                assertThat(json).contains("mailto:" + attendee.email());
                assertThatJson(item)
                    .inPath("data[1]")
                    .isArray()
                    .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                        .isEqualTo("""
                            ["method", {}, "text", "REPLY"]"""));

                assertThatJson(item)
                    .inPath("data[2][0][1]")
                    .isArray()
                    .anySatisfy(node -> assertThatJson(MAPPER.writeValueAsString(node))
                        .isEqualTo("""
                              [
                              "attendee",
                              {
                                "cn": "Attendee",
                                "partstat": "%s"
                              },
                              "cal-address",
                              "mailto:%s"
                            ]""".formatted(partstat, attendee.email())));
            });
        });
    }

    @Test
    void lookupByDate() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();

        HttpClientResponse response = dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DavResponse response2 = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("<c:calendar-query xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">" +
                "    <d:prop>" +
                "        <d:getetag />" +
                "    <c:calendar-data>\n" +
                "      <c:expand start=\"20250201T000000Z\" end=\"20250215T235959Z\"/>\n" +
                "    </c:calendar-data>" +
                "    </d:prop>" +
                "    <c:filter>" +
                "    <c:comp-filter name=\"VCALENDAR\">" +
                "      <c:comp-filter name=\"VEVENT\">" +
                "        <c:time-range start=\"20250201T000000Z\" end=\"20250215T235959Z\"/>" +
                "      </c:comp-filter>" +
                "    </c:comp-filter>" +
                "    </c:filter>" +
                "</c:calendar-query>")));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(response2.status()).isEqualTo(207);

        String actual = XMLUtil.extractByXPath(
            response2.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );
        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        actualCalendar.removeAll(Property.PRODID);

        assertThat(actualCalendar.toString()).isEqualToNormalizingNewlines("""
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b
            TRANSP:OPAQUE
            DTSTART:20250214T100000Z
            DTEND:20250214T104500Z
            CLASS:PUBLIC
            X-OPENPAAS-VIDEOCONFERENCE:
            SUMMARY:OW2con'25
            DESCRIPTION:Avoir un draft de prêt
            LOCATION:https://jitsi.linagora.com/ow2
            ORGANIZER;CN=Julie VERRIER:mailto:jverrier@linagora.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=Alexandre PUJOL:mailto:apujol@linagora.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN="Benoît TELLIER":mailto:btellier@linagora.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=Xavier GUIMARD:mailto:xguimard@linagora.com
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN="Frédéric HERMELIN":mailto:fhermelin@linagora.com
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:jverrier@linagora.com
            DTSTAMP:20250205T170516Z
            SEQUENCE:0
            END:VEVENT
            END:VCALENDAR
            """);
    }

    @Test
    void attendeeShouldSeeUpdatedCalendar() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #02",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next 2 weeks.",
            "30250411T150000",
            "30250411T160000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser2.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser2.id() + "/" + testUser2.id())
            .send(body("""
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
                """.replace("{eventUid}", eventUid))));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        Calendar expectedCalendar = CalendarUtil.parseIcs(updatedCalendarData);
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void attendeeShouldSeeUpdatedCalendarWhenAttendeeGrantFullDelegationToOrganizer() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        calDavClient.findUserCalendars(testUser).collectList().block();
        calDavClient.grantDelegation(testUser2, testUser2.id(), testUser, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #02",
            "Twake Meeting Room 2",
            "This is a meeting to discuss the sprint planning for the next 2 weeks.",
            "30250411T150000",
            "30250411T160000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, updatedCalendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser2.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser2.id() + "/" + testUser2.id())
            .send(body("""
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
                """.replace("{eventUid}", eventUid))));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        Calendar expectedCalendar = CalendarUtil.parseIcs(updatedCalendarData);
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void organizerShouldSeeAttendeeAcceptedStatus() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            "ACCEPTED");
        calDavClient.upsertCalendarEvent(testUser2, attendeeEventId, updatedCalendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("""
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
                """.replace("{eventUid}", eventUid))));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        Calendar expectedCalendar = CalendarUtil.parseIcs(updatedCalendarData);
        expectedCalendar.getComponent(Component.VEVENT).get().getProperty(Property.ATTENDEE).get().add(new ScheduleStatus("2.0"));

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void organizerShouldSeeAttendeeAcceptedStatusWhenAttendeeGrantFullDelegationToOrganizer() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        calDavClient.findUserCalendars(testUser).collectList().block();
        calDavClient.grantDelegation(testUser2, testUser2.id(), testUser, DelegationRight.READ);

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            "ACCEPTED");
        calDavClient.upsertCalendarEvent(testUser2, attendeeEventId, updatedCalendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("""
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
                """.replace("{eventUid}", eventUid))));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        Calendar expectedCalendar = CalendarUtil.parseIcs(updatedCalendarData);
        expectedCalendar.getComponent(Component.VEVENT).get().getProperty(Property.ATTENDEE).get().add(new ScheduleStatus("2.0"));

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void organizerShouldSeeAttendeeDeclinedStatusWhenAttendeeDeleteEvent() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000");
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String attendeeEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(testUser2), Optional::isPresent).get();

        String updatedCalendarData = generateCalendarData(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            "ACCEPTED");
        calDavClient.upsertCalendarEvent(testUser2, attendeeEventId, updatedCalendarData);

        calDavClient.deleteCalendarEvent(testUser2, attendeeEventId);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id())
            .send(body("""
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
                """.replace("{eventUid}", eventUid))));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);

        assertThat(actualCalendar.toString()).isEqualToNormalizingNewlines("""
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=DECLINED;CN="Benoît TELLIER";SCHEDULE-STATUS=2.0:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email()));
    }

    @Test
    void userShouldBeAbleToRequestParticipationOfResource() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", testUser)
            .block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarDataWithResource(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();

        String token = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // To ensure calendar directory is activated
        try {
            calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        } catch (Exception ignored) {
        }

        String actual = calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponent(Component.VEVENT).get().removeAll(Property.DTSTAMP);

        assertThat(actualCalendar.toString()).isEqualToNormalizingNewlines("""
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN="Benoît TELLIER":mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id()));
    }

    @Test
    void normalUserShouldNotBeAbleToAcceptParticipationOfResource() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", testUser2)
            .block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarDataWithResource(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();

        String token = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // To ensure calendar directory is activated
        try {
            calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        } catch (Exception ignored) {
        }

        String updatedCalendarData = generateCalendarDataWithResource(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id(),
            "ACCEPTED");

        assertThatThrownBy(() -> upsertResourceCalendarEvent(resource.id(), resourceEventId, testUser, updatedCalendarData))
            .hasMessageContaining("Unexpected status code: 403");
    }

    @Test
    void shouldBeAbleToAcceptParticipationOfResourceByUsingToken() {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", testUser)
            .block();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = generateCalendarDataWithResource(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id());
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), testUser), Optional::isPresent).get();

        String token = dockerExtension().twakeCalendarProvisioningService().generateToken();

        // To ensure calendar directory is activated
        try {
            calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        } catch (Exception ignored) {
        }

        String updatedCalendarData = generateCalendarDataWithResource(
            eventUid,
            testUser.email(),
            testUser2.email(),
            "Sprint planning #01",
            "Twake Meeting Room",
            "This is a meeting to discuss the sprint planning for the next week.",
            "30250411T100000",
            "30250411T110000",
            resource.id(),
            "ACCEPTED");
        calDavClient.upsertCalendarEvent(resource.id(), resourceEventId, updatedCalendarData, token);

        String actual = calDavClient.getCalendarEvent(resource.id(), resourceEventId, token);
        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        actualCalendar.removeAll(Property.PRODID);

        assertThat(actualCalendar.toString()).isEqualToNormalizingNewlines("""
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            LOCATION:Twake Meeting Room
            DESCRIPTION:This is a meeting to discuss the sprint planning for the next week.
            ORGANIZER;CN=Van Tung TRAN;SCHEDULE-STATUS=1.1:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN="Benoît TELLIER":mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", testUser.email())
            .replace("{attendeeEmail}", testUser2.email())
            .replace("{resourceId}", resource.id()));
    }

    @Test
    void reportShouldShowRecurringEvent() throws Exception {
        OpenPaasUser testUser = dockerExtension().newTestUser();
        OpenPaasUser testUser2 = dockerExtension().newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(testUser.email())
            .attendee(testUser2.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .rrule("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1;COUNT=5")
            .exDate("20300603T100000")
            .recurrenceOverride("20300506T100000", "20300506T150000", "20300506T160000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(testUser, eventUid, calendarData);

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> testUser2.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + testUser2.id() + "/" + testUser2.id())
            .send(body("""
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                    <d:prop>
                        <d:getetag />
                        <c:calendar-data/>
                    </d:prop>
                    <c:filter>
                        <c:comp-filter name="VCALENDAR">
                            <c:comp-filter name="VEVENT">
                                <c:time-range start="20300411T000000" end="20300911T110000"/>
                            </c:comp-filter>
                        </c:comp-filter>
                    </c:filter>
                </c:calendar-query>
                """)));

        String actual = XMLUtil.extractByXPath(
            response.body(),
            "//cal:calendar-data",
            Map.of("cal", "urn:ietf:params:xml:ns:caldav")
        );

        Calendar actualCalendar = CalendarUtil.parseIcs(actual);
        Calendar expectedCalendar = CalendarUtil.parseIcs(calendarData);
        actualCalendar.removeAll(Property.PRODID);
        actualCalendar.getComponents(Component.VEVENT)
            .forEach(calendarComponent -> calendarComponent.removeAll(Property.DTSTAMP).removeAll(Property.SEQUENCE));
        expectedCalendar.removeAll(Property.PRODID);
        expectedCalendar.getComponents(Component.VEVENT)
            .forEach(calendarComponent -> calendarComponent.removeAll(Property.DTSTAMP).removeAll(Property.SEQUENCE));

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
        /* test an existing user in ldap by sending the whole uid containing domain name,
         * ex: ldap_user1@open-paas.org:password */
    void ldapUserAuthenticate() {
        OpenPaasUser testUser = dockerExtension().newTestUser("ldap_user1");

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(header -> header.add("Authorization", basicAuth(testUser.email(), testUser.password())))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
        /* test an existing user in ldap by sending only the local part of the uid
         * ex: ldap_user1:password */
    void ldapLocalPartUserAuthenticate() {
        OpenPaasUser testUser = dockerExtension().newTestUser("ldap_user1");

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(header -> header.add("Authorization", basicAuth("ldap_user1", "secret")))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
        /* test an existing user in ldap with a wrong password */
    void ldapUserAuthenticateWrongPassword() {
        // the password for ldap_user2 is secret123 in LDAP
        OpenPaasUser testUser = dockerExtension().newTestUser("ldap_user2");

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(header -> header.add("Authorization", basicAuth("ldap_user2", "invalidPass")))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
        /* test a non-existing user in ldap */
    void ldapUserAuthenticateNonExistingUser() {
        OpenPaasUser testUser = dockerExtension().newTestUser("ldap_user3");

        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(header -> header.add("Authorization", basicAuth("non-existing-user", "invalidPass")))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(401);
    }

    public static String basicAuth(String email, String password) {
        String userPassword = email + ":" + password;
        byte[] base64UserPassword = Base64
            .getEncoder()
            .encode(userPassword.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8);
    }

    private void upsertResourceCalendarEvent(String resourceId, String eventId, OpenPaasUser user, String calendarData) {
        dockerExtension().davHttpClient().headers(headers -> user.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + resourceId + "/" + resourceId + "/" + eventId + ".ics")
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

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend) {
        return generateCalendarData(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, "NEEDS-ACTION");
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String partStat) {

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            LOCATION:{location}
            DESCRIPTION:{description}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary)
            .replace("{location}", location)
            .replace("{description}", description)
            .replace("{dtstart}", dtstart)
            .replace("{dtend}", dtend)
            .replace("{partStat}", partStat);
    }

    private String generateCalendarDataWithResource(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String resourceId) {
        return generateCalendarDataWithResource(eventUid, organizerEmail, attendeeEmail, summary, location, description, dtstart, dtend, resourceId, "TENTATIVE");
    }

    private String generateCalendarDataWithResource(String eventUid, String organizerEmail, String attendeeEmail,
                                        String summary,
                                        String location,
                                        String description,
                                        String dtstart,
                                        String dtend,
                                        String resourceId,
                                        String partStat) {

        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:30250411T022032Z
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:{dtstart}
            DTEND;TZID=Asia/Ho_Chi_Minh:{dtend}
            SUMMARY:{summary}
            LOCATION:{location}
            DESCRIPTION:{description}
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;CN=Benoît TELLIER:mailto:{attendeeEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={partStat};RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{attendeeEmail}", attendeeEmail)
            .replace("{summary}", summary)
            .replace("{location}", location)
            .replace("{description}", description)
            .replace("{dtstart}", dtstart)
            .replace("{dtend}", dtend)
            .replace("{partStat}", partStat)
            .replace("{resourceId}", resourceId);
    }
}
