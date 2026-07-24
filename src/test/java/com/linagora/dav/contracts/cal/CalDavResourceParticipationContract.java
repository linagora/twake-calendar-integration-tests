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

import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaaSResource;
import com.linagora.dav.OpenPaasUser;

public abstract class CalDavResourceParticipationContract {

    private final ConditionFactory awaitAtMost = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await()
        .atMost(30, TimeUnit.SECONDS);

    private CalDavClient calDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
    }

    @Test
    @Disabled("https://github.com/linagora/esn-sabre/issues/441 - resource administrator lacks {DAV:}write-content on the resource calendar")
    void resourceAdministratorShouldBeAbleToUpdateParticipation() {
        OpenPaasUser organizer = dockerExtension().newTestUser();
        OpenPaasUser resourceAdmin = dockerExtension().newTestUser();
        OpenPaaSResource resource = dockerExtension().twakeCalendarProvisioningService()
            .createResource("projector", "This is a projector", resourceAdmin)
            .block();

        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(organizer, eventUid, generateCalendarData(eventUid, organizer.email(), resource.id(), "NEEDS-ACTION"));

        String resourceEventId = awaitAtMost.until(() -> calDavClient.findFirstEventId(resource.id(), organizer), Optional::isPresent).get();

        URI resourceEventUri = URI.create("/calendars/" + resource.id() + "/" + resource.id() + "/" + resourceEventId + ".ics");
        String acceptedCalendarData = generateCalendarData(eventUid, organizer.email(), resource.id(), "ACCEPTED");

        assertThatCode(() -> calDavClient.upsertCalendarEvent(resourceAdmin, resourceEventUri, acceptedCalendarData))
            .doesNotThrowAnyException();
    }

    private String generateCalendarData(String eventUid, String organizerEmail, String resourceId, String resourcePartStat) {
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
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:30250411T100000
            DTEND;TZID=Asia/Ho_Chi_Minh:30250411T110000
            SUMMARY:Sprint planning #01
            ORGANIZER;CN=Van Tung TRAN:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:{organizerEmail}
            ATTENDEE;PARTSTAT={resourcePartStat};RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=RESOURCE;CN=projector:mailto:{resourceId}@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{organizerEmail}", organizerEmail)
            .replace("{resourcePartStat}", resourcePartStat)
            .replace("{resourceId}", resourceId);
    }
}
