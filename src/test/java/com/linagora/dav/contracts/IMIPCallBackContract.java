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

import static com.linagora.dav.DockerTwakeCalendarExtension.QUEUE_NAME;
import static com.linagora.dav.contracts.ITIPRequestContract.awaitAtMost;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TestUtil;

public abstract class IMIPCallBackContract {

    public abstract DockerTwakeCalendarExtension extension();

    private CalDavClient calDavClient;

    private OpenPaasUser alice;
    private OpenPaasUser bob;

    @BeforeEach
    void setUp() throws Exception {
        calDavClient = new CalDavClient(extension().davHttpClient());
        extension().getDockerTwakeCalendarSetupSingleton()
            .getTwakeCalendarProvisioningService()
            .enableSharedCalendarModule()
            .block();

        alice = extension().newTestUser();
        bob = extension().newTestUser();

        extension().getChannel().queueBind(QUEUE_NAME, "calendar:event:notificationEmail:send", "");
    }

    @Test
    void shouldPublishNewEventRequestToNotificationQueue() {
        String eventUid = "event-" + UUID.randomUUID();
        // Alice creates an event inviting Bob
        String initialIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example Corp.//CalDAV Client//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20261003T080000Z
            DTSTART:20761005T090000Z
            DTEND:20761005T100000Z
            SUMMARY:Initial Meeting
            ORGANIZER;CN=Alice:mailto:%s
            ATTENDEE;CN=Bob;PARTSTAT=NEEDS-ACTION:mailto:%s
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid, alice.email(), bob.email());

        URI eventUri = URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics");
        calDavClient.upsertCalendarEvent(alice, eventUri, initialIcs);

        // THEN: Bob should have received one ITIP request in his inbox
        String bobInbox = "/calendars/" + bob.id() + "/inbox";
        TestUtil.awaitCalendarEntries(extension().davHttpClient(), bob, bobInbox, 1);

        // AND: a notification message should be published to the RabbitMQ queue
        byte[] rawMessage = awaitAtMost.atMost(Duration.ofSeconds(20))
            .until(() -> extension().getChannel().basicGet(QUEUE_NAME, true), Objects::nonNull)
            .getBody();

        String actualMessage = new String(rawMessage);

        // Verify message structure and key fields
        assertThatJson(actualMessage)
            .isEqualTo("""
                {
                    "senderEmail": "%s",
                    "recipientEmail": "%s",
                    "method": "REQUEST",
                    "event": "${json-unit.ignore}",
                    "notify": true,
                    "calendarURI": "%s",
                    "eventPath": "${json-unit.ignore}",
                    "isNewEvent": true
                }
                """.formatted(alice.email(), bob.email(), alice.id()));
    }
}
