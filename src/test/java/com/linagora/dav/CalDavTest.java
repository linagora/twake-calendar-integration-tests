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
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.xmlunit.diff.ComparisonResult.SIMILAR;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.DifferenceEvaluator;

import io.netty.handler.codec.http.HttpMethod;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.ScheduleStatus;
import reactor.netty.http.client.HttpClientResponse;

class CalDavTest {
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

    @RegisterExtension
    static DockerTwakeCalendarExtension dockerExtension = new DockerTwakeCalendarExtension();

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    private CalDavClient calDavClient;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension.davHttpClient());
    }

    @Test
    void propfindShouldListCalendars() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/1</cs:getctag><s:sync-token>1</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/outbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-outbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void proppatchShouldUpdateDisplayName() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status = executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/calendars/" + testUser.id())
            .send(body("<d:propertyupdate xmlns:d=\"DAV:\">" +
                "          <d:set>" +
                "            <d:prop>" +
                "              <d:displayname>New Calendar Name</d:displayname>" +
                "            </d:prop>" +
                "          </d:set>" +
                "        </d:propertyupdate>")));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()));

        assertThat(status).isEqualTo(207);
        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/1</cs:getctag><s:sync-token>1</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/outbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-outbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void propfindShouldListEmptyCalendar() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
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
    void propfindShouldReturnCreatedCalendar() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "application/xml"))
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


        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()  ));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/1</cs:getctag><s:sync-token>1</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/testCalendar/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/1</cs:getctag><s:sync-token>1</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>New Event XYZ</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/outbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-outbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void propfindShouldNotReturnDeletedCalendar() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "application/xml"))
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

        int status2 =executeNoContent(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/calendars/" + testUser.id()  + "/testCalendar"));


        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id()  ));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/1</cs:getctag><s:sync-token>1</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/outbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-outbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .areIdentical();
    }

    @Test
    void propfindShouldListCreatedEvents() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser.id() + "/" + testUser.id()));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:calendar/></d:resourcetype><cs:getctag>http://sabre.io/ns/sync/2</cs:getctag><s:sync-token>2</s:sync-token><cal:supported-calendar-component-set><cal:comp name=\"VEVENT\"/><cal:comp name=\"VTODO\"/></cal:supported-calendar-component-set><cal:schedule-calendar-transp><cal:opaque/></cal:schedule-calendar-transp><d:displayname>#default</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics</d:href><d:propstat><d:prop><d:getlastmodified>Mon, 17 Feb 2025 20:05:02 GMT</d:getlastmodified><d:getcontentlength>1482</d:getcontentlength><d:resourcetype/><d:getetag>&quot;8c97fb06c60212c47d46a9c8c0f625ef&quot;</d:getetag><d:getcontenttype>text/calendar; charset=utf-8; component=vevent</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(DIFFERENCE_EVALUATOR)
            .areSimilar();
    }

    @Test
    void propfindShouldNotListDeletedEvents() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(ICS_1);
    }

    @Test
    void getShouldNotReturnPreviouslyDeletedData() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status3 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(status3).isEqualTo(404);
    }

    @Test
    void putShouldUpdateData() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(ICS_2);
    }

    @Test
    void conditionalUpdateShouldFailWithBadTag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", "bad"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(412);
    }

    @Test
    void conditionalUpdateShouldSucceedWithGoodTag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", response.responseHeaders().get("ETag")))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DavResponse response2 = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        AssertionsForClassTypes.assertThat(response2.status()).isEqualTo(200);
        AssertionsForClassTypes.assertThat(response2.body()).isEqualTo(ICS_2);
    }

    @Test
    void conditionalDeleteShouldFailWithBadTag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", "bad"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(412);
    }

    @Test
    void conditionalDeleteShouldSucceedWithGoodTag() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", response.responseHeaders().get("ETag")))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        int status3 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        AssertionsForClassTypes.assertThat(status3).isEqualTo(404);
    }

    @Test
    void canReportSyncTokenInitialSync() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DavResponse response2 = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        DavResponse response3 = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DavResponse response3 = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DavResponse response2 = execute(dockerExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "?export"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(response2.status()).isEqualTo(200);
        assertThat(response2.body()).isEqualToNormalizingNewlines("BEGIN:VCALENDAR\n" +
            "VERSION:2.0\n" +
            "CALSCALE:GREGORIAN\n" +
            "PRODID:-//SabreDAV//SabreDAV 3.2.2//EN\n" +
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
    }

    @Test
    void inboxShouldContainInvites() {
        // CF https://sabre.io/dav/scheduling/
        OpenPaasUser testUser1 = dockerExtension.newTestUser();
        OpenPaasUser testUser2 = dockerExtension.newTestUser();

        int status1 = executeNoContent(dockerExtension.davHttpClient()
            .headers(headers -> testUser1.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
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

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(testUser2::basicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + testUser2.id() + "/inbox"));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(207);
        XmlAssert.assertThat(response.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser2.id() + "/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><cal:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "<d:response><d:href>/calendars/" + testUser2.id() + "/inbox/sabredav-85eacd97-b6ea-47d4-9f0c-0d16cbb94761.ics</d:href><d:propstat><d:prop><d:getlastmodified>Mon, 17 Feb 2025 21:56:02 GMT</d:getlastmodified><d:getcontentlength>1558</d:getcontentlength><d:resourcetype/><d:getetag>&quot;a54079bf751be394f27ea363e7e8a072&quot;</d:getetag><d:getcontenttype>text/calendar; charset=utf-8</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator((comparison, outcome) -> {
                if (outcome.equals(ComparisonResult.DIFFERENT) &&
                    comparison.getControlDetails().getXPath() != null &&
                    comparison.getControlDetails().getXPath().contains("getetag")) {
                    return SIMILAR;
                }
                if (outcome.equals(ComparisonResult.DIFFERENT) &&
                    comparison.getControlDetails().getXPath() != null &&
                    comparison.getControlDetails().getXPath().contains("href")) {
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
                if (outcome.equals(ComparisonResult.DIFFERENT) &&
                    comparison.getControlDetails().getXPath() == null &&
                    comparison.getControlDetails().getValue() == null &&
                    comparison.getControlDetails().getTarget() == null &&
                    comparison.getControlDetails().getParentXPath().equals("/multistatus[1]/response[2]")) {
                    return SIMILAR;
                }
                return outcome;
            })
            .areSimilar();
    }

    @Test
    void lookupByDate() {
        OpenPaasUser testUser = dockerExtension.newTestUser();

        HttpClientResponse response = dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DavResponse response2 = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        XmlAssert.assertThat(response2.body())
            .and("<?xml version=\"1.0\"?>\n" +
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:s=\"http://sabredav.org/ns\" xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:card=\"urn:ietf:params:xml:ns:carddav\">" +
                "<d:response><d:href>/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics</d:href><d:propstat><d:prop><d:getetag>&quot;8c97fb06c60212c47d46a9c8c0f625ef&quot;</d:getetag><cal:calendar-data>BEGIN:VCALENDAR&#13;\n" +
                "VERSION:2.0&#13;\n" +
                "PRODID:-//Sabre//Sabre VObject 4.1.3//EN&#13;\n" +
                "CALSCALE:GREGORIAN&#13;\n" +
                "BEGIN:VEVENT&#13;\n" +
                "UID:47d90176-b477-4fe1-91b3-a36ec0cfc67b&#13;\n" +
                "TRANSP:OPAQUE&#13;\n" +
                "DTSTART:20250214T100000Z&#13;\n" +
                "DTEND:20250214T104500Z&#13;\n" +
                "CLASS:PUBLIC&#13;\n" +
                "X-OPENPAAS-VIDEOCONFERENCE:&#13;\n" +
                "SUMMARY:OW2con'25&#13;\n" +
                "DESCRIPTION:Avoir un draft de prêt&#13;\n" +
                "LOCATION:https://jitsi.linagora.com/ow2&#13;\n" +
                "ORGANIZER;CN=Julie VERRIER:mailto:jverrier@linagora.com&#13;\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI&#13;\n" +
                " DUAL;CN=Alexandre PUJOL:mailto:apujol@linagora.com&#13;\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI&#13;\n" +
                " DUAL;CN=Benoît TELLIER:mailto:btellier@linagora.com&#13;\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI&#13;\n" +
                " DUAL;CN=Xavier GUIMARD:mailto:xguimard@linagora.com&#13;\n" +
                "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI&#13;\n" +
                " DUAL;CN=Frédéric HERMELIN:mailto:fhermelin@linagora.com&#13;\n" +
                "ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:j&#13;\n" +
                " verrier@linagora.com&#13;\n" +
                "DTSTAMP:20250205T170516Z&#13;\n" +
                "SEQUENCE:0&#13;\n" +
                "END:VEVENT&#13;\n" +
                "END:VCALENDAR&#13;\n" +
                "</cal:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                "</d:multistatus>")
            .ignoreChildNodesOrder()
            .withDifferenceEvaluator(DIFFERENCE_EVALUATOR)
            .areSimilar();
    }

    @Test
    void attendeeShouldSeeUpdatedCalendar() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();
        OpenPaasUser testUser2 = dockerExtension.newTestUser();

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

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser2.basicAuth(headers)
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

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void attendeeShouldSeeUpdatedCalendarWhenAttendeeGrantFullDelegationToOrganizer() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();
        OpenPaasUser testUser2 = dockerExtension.newTestUser();

        calDavClient.findUserCalendars(testUser).collectList().block();
        calDavClient.grantFullDelegation(testUser2, testUser2.id(), testUser);

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

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser2.basicAuth(headers)
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

        assertThat(actualCalendar).isEqualTo(expectedCalendar);
    }

    @Test
    void organizerShouldSeeAttendeeAcceptedStatus() throws Exception {
        OpenPaasUser testUser = dockerExtension.newTestUser();
        OpenPaasUser testUser2 = dockerExtension.newTestUser();

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

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();
        OpenPaasUser testUser2 = dockerExtension.newTestUser();

        calDavClient.findUserCalendars(testUser).collectList().block();
        calDavClient.grantFullDelegation(testUser2, testUser2.id(), testUser);

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

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
        OpenPaasUser testUser = dockerExtension.newTestUser();
        OpenPaasUser testUser2 = dockerExtension.newTestUser();

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

        DavResponse response = execute(dockerExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers)
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
    /* test an existing user in ldap by sending the whole uid containing domain name,
     * ex: ldap_user1@open-paas.org:password */
    void ldapUserAuthenticate() {
        OpenPaasUser testUser = dockerExtension.newTestUser("ldap_user1");

        DavResponse response = execute(dockerExtension.davHttpClient()
                .headers(testUser::basicAuth)
                .request(HttpMethod.valueOf("PROPFIND"))
                .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
    /* test an existing user in ldap by sending only the local part of the uid
     * ex: ldap_user1:password */
    void ldapLocalPartUserAuthenticate() {
        OpenPaasUser testUser = dockerExtension.newTestUser("ldap_user1");

        DavResponse response = execute(dockerExtension.davHttpClient()
                .headers(testUser::localPartBasicAuth)
                .request(HttpMethod.valueOf("PROPFIND"))
                .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(207);
    }

    @Test
    /* test an existing user in ldap with a wrong password */
    void ldapUserAuthenticateWrongPassword() {
        // the password for ldap_user2 is secret123 in LDAP
        OpenPaasUser testUser = dockerExtension.newTestUser("ldap_user2");

        DavResponse response = execute(dockerExtension.davHttpClient()
                .headers(testUser::basicAuth)
                .request(HttpMethod.valueOf("PROPFIND"))
                .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(500);
    }

    @Test
    /* test a non-existing user in ldap */
    void ldapUserAuthenticateNonExistingUser() {
        OpenPaasUser testUser = dockerExtension.newTestUser("ldap_user3");

        DavResponse response = execute(dockerExtension.davHttpClient()
                .headers(testUser::basicAuth)
                .request(HttpMethod.valueOf("PROPFIND"))
                .uri("/calendars/" + testUser.id()));

        assertThat(response.status()).isEqualTo(500);
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
}
