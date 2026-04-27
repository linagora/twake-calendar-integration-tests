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

import static com.linagora.dav.TestUtil.body;
import static com.linagora.dav.TestUtil.execute;
import static com.linagora.dav.TestUtil.executeNoContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalDavClient.DelegationRight;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.ITIPJsonBodyRequest;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.dto.share.SubscribedCalendarRequest;

import io.netty.handler.codec.http.HttpMethod;

public abstract class CalDavMultitenancyContract {

    private static final String SECOND_DOMAIN = "second-domain.org";

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser bob;
    private OpenPaasUser john;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        dockerExtension().twakeCalendarProvisioningService().createDomainIfNotExists(SECOND_DOMAIN);
        bob = dockerExtension().newTestUser();
        john = dockerExtension().twakeCalendarProvisioningService()
            .createUser(UUID.randomUUID().toString(), SECOND_DOMAIN).block();
    }

    @Disabled("https://github.com/linagora/twake-calendar-integration-tests/issues/209")
    @Test
    void propfindOnCalendarsRootShouldNotExposeCrossDomainCalendarHome() {
        DavResponse response = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Depth", "1")
                .add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/")
            .send(body("""
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:current-user-principal/>
                  </d:prop>
                </d:propfind>""")));

        assertThat(response.body()).doesNotContain(john.id());
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void propfindShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPFIND"))
            .uri("/calendars/" + john.id()));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void mkcolShouldReturn403ForCrossDomainUser() {
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/calendars/" + john.id() + "/newCalendar")
            .send(body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                     <D:displayname>New Calendar</D:displayname>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void mkcalendarShouldReturn403ForCrossDomainUser() {
        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCALENDAR"))
            .uri("/calendars/" + john.id() + "/newCalendar")
            .send(body("""
                <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:displayname>New Calendar</D:displayname>
                   </D:prop>
                 </D:set>
                </C:mkcalendar>
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void putEventShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Content-Type", "text/calendar ; charset=utf-8"))
            .put()
            .uri("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics")
            .send(body(CalDavContract.ICS_1)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void deleteEventShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .delete()
            .uri("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void getEventShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void proppatchShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("PROPPATCH"))
            .uri("/calendars/" + john.id() + "/" + john.id())
            .send(body("""
                <d:propertyupdate xmlns:d="DAV:">
                  <d:set>
                    <d:prop>
                      <d:displayname>Hacked Calendar</d:displayname>
                    </d:prop>
                  </d:set>
                </d:propertyupdate>
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void headEventShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .request(HttpMethod.valueOf("HEAD"))
            .uri("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void exportShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(bob::impersonatedBasicAuth)
            .get()
            .uri("/calendars/" + john.id() + "/" + john.id() + "?export"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void reportShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/xml")
                .add("Depth", "1"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + john.id() + "/" + john.id())
            .send(body("""
                <?xml version="1.0" encoding="utf-8" ?>
                <C:free-busy-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:time-range start="20300401T000000Z" end="20300430T000000Z"/>
                </C:free-busy-query>
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void copyEventBetweenCalendarsShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/calendars/" + john.id() + "/secondCalendar")
            .send(body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """)));

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Destination", "/calendars/" + john.id() + "/secondCalendar/abcd.ics"))
            .request(HttpMethod.valueOf("COPY"))
            .uri("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void moveEventBetweenCalendarsShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");
        calDavClient.upsertCalendarEvent(john, "abcd", CalDavContract.ICS_1);

        executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> john.impersonatedBasicAuth(headers).add("Content-Type", "application/xml"))
            .request(HttpMethod.valueOf("MKCOL"))
            .uri("/calendars/" + john.id() + "/secondCalendar")
            .send(body("""
                <D:mkcol xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                 <D:set>
                   <D:prop>
                     <D:resourcetype>
                       <D:collection/>
                       <C:calendar/>
                     </D:resourcetype>
                   </D:prop>
                 </D:set>
                </D:mkcol>
                """)));

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Destination", "/calendars/" + john.id() + "/secondCalendar/abcd.ics"))
            .request(HttpMethod.valueOf("MOVE"))
            .uri("/calendars/" + john.id() + "/" + john.id() + "/abcd.ics"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void updateAclShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(bob, CalendarURL.from(john.id()), "{DAV:}read"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void getJsonCalendarListShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/json"))
            .get()
            .uri("/calendars/" + john.id() + ".json"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void putJsonEventShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        String eventUid = UUID.randomUUID().toString();
        String jsonCalendarData = """
            [
                "vcalendar",
                [],
                [
                    [
                        "vevent",
                        [
                            [
                                "uid",
                                {},
                                "text",
                                "{eventUid}"
                            ],
                            [
                                "dtstart",
                                {
                                    "tzid": "Asia/Ho_Chi_Minh"
                                },
                                "date-time",
                                "3025-04-11T10:00:00"
                            ],
                            [
                                "dtend",
                                {
                                    "tzid": "Asia/Ho_Chi_Minh"
                                },
                                "date-time",
                                "3025-04-11T11:00:00"
                            ],
                            [
                                "summary",
                                {},
                                "text",
                                "Sprint planning #01"
                            ],
                            [
                                "dtstamp",
                                {},
                                "date-time",
                                "3025-04-11T02:20:32Z"
                            ]
                        ],
                        []
                    ]
                ]
            ]
            """.replace("{eventUid}", eventUid);

        assertThatThrownBy(() -> calDavClient.upsertJsonCalendarEvent(bob, john.id(), john.id(), eventUid, jsonCalendarData))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("Currently returns 404")
    @Test
    void getJsonEventShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        String eventUid = "event-" + UUID.randomUUID();
        calDavClient.upsertCalendarEvent(john, eventUid, CalDavContract.ICS_1);

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/json"))
            .get()
            .uri("/calendars/" + john.id() + "/" + john.id() + "/" + eventUid + ".json"));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void exportJsonCalendarShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers).add("Accept", "application/json"))
            .get()
            .uri("/calendars/" + john.id() + "/" + john.id() + ".json?export"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void postJsonNewCalendarShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        DavResponse destinationResponse = execute(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .post()
            .uri("/calendars/" + john.id() + ".json")
            .send(body("""
                {"id":"newCal","dav:name":"New Calendar","apple:order":2}
                """)));

        assertThat(destinationResponse.status()).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void updateJsonCalendarMetadataShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.updateCalendarSetting(bob, CalendarURL.from(john.id()), "Hacked Name", "#FF0000"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Test
    void deleteJsonCalendarShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.deleteCalendar(bob, CalendarURL.from(john.id())))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void reportJsonCalendarShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Accept", "application/json")
                .add("Content-Type", "application/json"))
            .request(HttpMethod.valueOf("REPORT"))
            .uri("/calendars/" + john.id() + "/" + john.id() + ".json")
            .send(body("""
                {"match":{"start":"20300401T000000","end":"20300430T000000"}}
                """)));

        assertThat(status).isEqualTo(403);
    }

    @Test
    void updateJsonAclShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        assertThatThrownBy(() -> calDavClient.updateCalendarAcl(bob, CalendarURL.from(john.id()), "{DAV:}read"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void subscribeShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(bob, "{DAV:}read");

        SubscribedCalendarRequest request = SubscribedCalendarRequest.builder()
            .id(UUID.randomUUID().toString())
            .sourceUserId(bob.id())
            .name("Bob's shared calendar")
            .color("#00FF00")
            .readOnly(true)
            .build();

        assertThatThrownBy(() -> calDavClient.subscribeToSharedCalendar(john, request))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void delegateShouldReturn403ForCrossDomainUser() {
        assertThatThrownBy(() -> calDavClient.grantDelegation(bob, bob.id(), john, DelegationRight.READ_WRITE))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void freeBusyShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        int status = executeNoContent(dockerExtension().davHttpClient()
            .headers(headers -> bob.impersonatedBasicAuth(headers)
                .add("Content-Type", "application/json")
                .add("Accept", "application/json"))
            .post()
            .uri("/calendars/freebusy")
            .send(body("{\"start\":\"20300411T020000\",\"end\":\"20300411T050000\",\"users\":[\"" + john.id() + "\"]}")));

        assertThat(status).isEqualTo(403);
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void itipRequestShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(john, "{DAV:}write");

        String eventUid = "event-" + UUID.randomUUID();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:REQUEST
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T080000Z
            DTSTART:20300405T090000Z
            DTEND:20300405T100000Z
            SUMMARY:Cross-domain meeting
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=John;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), john.email());

        String itipBody = ITIPJsonBodyRequest.builder()
            .ical(ics)
            .sender(bob.email())
            .recipient(john.email())
            .uid(eventUid)
            .method("REQUEST")
            .buildJson();

        assertThatThrownBy(() -> calDavClient.sendITIPRequest(bob, URI.create("/calendars/" + john.id()), itipBody).block())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }

    @Disabled("https://github.com/linagora/twake-calendar-side-service/issues/673")
    @Test
    void itipCounterShouldReturn403ForCrossDomainUser() {
        calDavClient.updateCalendarAcl(bob, "{DAV:}write");

        String eventUid = "event-" + UUID.randomUUID();
        String counterIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            CALSCALE:GREGORIAN
            METHOD:COUNTER
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20300401T081000Z
            DTSTART:20300405T100000Z
            DTEND:20300405T110000Z
            SUMMARY:Cross-domain meeting - counter proposal
            ORGANIZER;CN=Bob:mailto:%s
            ATTENDEE;CN=John;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, bob.email(), john.email());

        String counterBody = ITIPJsonBodyRequest.builder()
            .ical(counterIcs)
            .sender(john.email())
            .recipient(bob.email())
            .uid(eventUid)
            .method("COUNTER")
            .buildJson();

        assertThatThrownBy(() -> calDavClient.sendITIPRequest(john, URI.create("/calendars/" + bob.id()), counterBody).block())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("403");
    }
}
