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

import static com.linagora.dav.DockerOpenPaasExtension.body;
import static com.linagora.dav.DockerOpenPaasExtension.execute;
import static com.linagora.dav.DockerOpenPaasExtension.executeNoContent;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.xmlunit.diff.ComparisonResult.SIMILAR;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xmlunit.assertj3.XmlAssert;
import org.xmlunit.diff.ComparisonResult;
import org.xmlunit.diff.DifferenceEvaluator;

import io.netty.handler.codec.http.HttpMethod;
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
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension();

    @Test
    void propfindShouldListCalendars() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status = executeNoContent(dockerOpenPaasExtension.davHttpClient()
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

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
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


        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
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

        int status2 =executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(testUser::basicAuth)
            .delete()
            .uri("/calendars/" + testUser.id()  + "/testCalendar"));


        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(ICS_1);
    }

    @Test
    void getShouldNotReturnPreviouslyDeletedData() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status3 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        assertThat(status3).isEqualTo(404);
    }

    @Test
    void putShouldUpdateData() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", "bad"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(412);
    }

    @Test
    void conditionalUpdateShouldSucceedWithGoodTag() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", response.responseHeaders().get("ETag")))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DockerOpenPaasExtension.Response response2 = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        int status1 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)));

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", "bad"))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        assertThat(status1).isEqualTo(201);
        assertThat(status2).isEqualTo(412);
    }

    @Test
    void conditionalDeleteShouldSucceedWithGoodTag() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8").add("If-Match", response.responseHeaders().get("ETag")))
            .delete()
            .uri("/calendars/" + testUser.id()  + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        int status3 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Accept", "application/xml"))
            .get()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        assertThat(response.status().code()).isEqualTo(201);
        assertThat(status2).isEqualTo(204);
        AssertionsForClassTypes.assertThat(status3).isEqualTo(404);
    }

    @Test
    void canReportSyncTokenInitialSync() {
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        DockerOpenPaasExtension.Response response = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DockerOpenPaasExtension.Response response2 = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .delete()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics"));

        DockerOpenPaasExtension.Response response3 = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        int status2 = executeNoContent(dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_2)));

        DockerOpenPaasExtension.Response response3 = execute(dockerOpenPaasExtension.davHttpClient()
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
        OpenPaasUser testUser = dockerOpenPaasExtension.newTestUser();

        HttpClientResponse response = dockerOpenPaasExtension.davHttpClient()
            .headers(headers -> testUser.basicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + testUser.id() + "/" + testUser.id() + "/abcd.ics")
            .send(body(ICS_1)).response()
            .block();

        DockerOpenPaasExtension.Response response2 = execute(dockerOpenPaasExtension.davHttpClient()
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
}
