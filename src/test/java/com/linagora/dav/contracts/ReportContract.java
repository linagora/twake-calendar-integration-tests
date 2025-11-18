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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.linagora.dav.CalDavClient;
import com.linagora.dav.CalendarURL;
import com.linagora.dav.DavResponse;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup;
import com.linagora.dav.JsonCalendarData;
import com.linagora.dav.JsonCalendarEventData;
import com.linagora.dav.OpenPaasUser;
import com.linagora.dav.TwakeCalendarEvent;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

public abstract class ReportContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser alice;
    private OpenPaasUser bob;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(RestAssuredConfig.newConfig().encoderConfig(EncoderConfig.encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setBaseUri(dockerExtension().getDockerTwakeCalendarSetupSingleton().getServiceUri(DockerTwakeCalendarSetup.DockerService.SABRE_DAV, "http").toString())
            .build();

        alice = dockerExtension().newTestUser();
        bob = dockerExtension().newTestUser();
    }

    @Test
    void reportShouldReturnOnlyCreatedEventsAfterGivenSync() throws JsonProcessingException {
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(alice.email())
            .summary("Sprint planning #01")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next week.")
            .dtstart("20300411T100000")
            .dtend("20300411T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        String eventUid2 = UUID.randomUUID().toString();
        String calendarData2 = TwakeCalendarEvent.builder()
            .uid(eventUid2)
            .organizer(alice.email())
            .summary("Sprint planning #02")
            .location("Twake Meeting Room")
            .description("This is a meeting to discuss the sprint planning for the next next week.")
            .dtstart("20300418T100000")
            .dtend("20300418T110000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid2, calendarData2);

        DavResponse response = calDavClient.findEventsByTimeAndSyncToken(alice,
            CalendarURL.from(alice.id()),
            "20300110T000000",
            "20301210T000000",
            "http://sabre.io/ns/sync/2");
        JsonCalendarData result = JsonCalendarData.from(response.body());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.items()).hasSize(1);
            softly.assertThat(result.items().get(0).events()).hasSize(1);
            softly.assertThat(result.items().get(0).events().get(0).uid()).isEqualTo(eventUid2);
            softly.assertThat(result.items().get(0).events().get(0).summary().get()).isEqualTo("Sprint planning #02");
        });
    }

    @Test
    void reportShouldReturnAllEventsOnInitialSync() throws JsonProcessingException {
        String eventUid1 = UUID.randomUUID().toString();
        String calendarData1 = TwakeCalendarEvent.builder()
            .uid(eventUid1)
            .organizer(alice.email())
            .summary("Kickoff Meeting")
            .location("Room 1")
            .description("Initial project kickoff.")
            .dtstart("20300401T090000")
            .dtend("20300401T100000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid1, calendarData1);

        String eventUid2 = UUID.randomUUID().toString();
        String calendarData2 = TwakeCalendarEvent.builder()
            .uid(eventUid2)
            .organizer(alice.email())
            .summary("Review Meeting")
            .location("Room 2")
            .description("Project review.")
            .dtstart("20300402T110000")
            .dtend("20300402T120000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid2, calendarData2);

        DavResponse response = calDavClient.findEventsByTimeAndSyncToken(alice,
            CalendarURL.from(alice.id()),
            "20300101T000000",
            "20301203T000000",
            "http://sabre.io/ns/sync/1");
        JsonCalendarData result = JsonCalendarData.from(response.body());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.items()).hasSize(2);
            softly.assertThat(result.items()).flatExtracting(JsonCalendarData.DavItem::events)
                .extracting(JsonCalendarEventData::uid)
                .containsExactlyInAnyOrder(eventUid1, eventUid2);
        });
    }

    @Test
    void reportShouldReturnOnlyDeletedEventsAfterGivenSync() throws JsonProcessingException {
        String eventUid1 = UUID.randomUUID().toString();
        String calendarData1 = TwakeCalendarEvent.builder()
            .uid(eventUid1)
            .organizer(alice.email())
            .summary("Planning Meeting")
            .location("Room 3")
            .description("Planning for next sprint.")
            .dtstart("20300405T090000")
            .dtend("20300405T100000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid1, calendarData1);

        String eventUid2 = UUID.randomUUID().toString();
        String calendarData2 = TwakeCalendarEvent.builder()
            .uid(eventUid2)
            .organizer(alice.email())
            .summary("Retrospective Meeting")
            .location("Room 4")
            .description("Sprint retrospective.")
            .dtstart("20300406T110000")
            .dtend("20300406T120000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid2, calendarData2);

        calDavClient.deleteCalendarEvent(alice, eventUid1);

        DavResponse response = calDavClient.findEventsByTimeAndSyncToken(alice,
            CalendarURL.from(alice.id()),
            "20300101T000000",
            "20301210T000000",
            "http://sabre.io/ns/sync/3");
        JsonCalendarData result = JsonCalendarData.from(response.body());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.items()).hasSize(1);
            softly.assertThat(result.syncToken()).isEqualTo("http://sabre.io/ns/sync/4");

            softly.assertThat(result.items().get(0).href()).isEqualTo("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid1 + ".ics");
            softly.assertThat(result.items().get(0).events()).isEmpty();
            softly.assertThat(result.items().get(0).status()).isEqualTo(404);
        });
    }

    @Test
    void reportShouldReturnOnlyUpdatedEventAfterGivenSync() throws JsonProcessingException {
        String eventUid = UUID.randomUUID().toString();
        String calendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(alice.email())
            .summary("Initial Summary")
            .location("Room 5")
            .description("Initial description.")
            .dtstart("20300410T090000")
            .dtend("20300410T100000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid, calendarData);

        String eventUid2 = UUID.randomUUID().toString();
        String calendarData2 = TwakeCalendarEvent.builder()
            .uid(eventUid2)
            .organizer(alice.email())
            .summary("Retrospective Meeting")
            .location("Room 4")
            .description("Sprint retrospective.")
            .dtstart("20300406T110000")
            .dtend("20300406T120000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid2, calendarData2);

        // Update event summary
        String updatedCalendarData = TwakeCalendarEvent.builder()
            .uid(eventUid)
            .organizer(alice.email())
            .summary("Updated Summary")
            .location("Room 5")
            .description("Initial description.")
            .dtstart("20300410T090000")
            .dtend("20300410T100000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid, updatedCalendarData);

        DavResponse response = calDavClient.findEventsByTimeAndSyncToken(alice,
            CalendarURL.from(alice.id()),
            "20300101T000000",
            "20301215T000000",
            "http://sabre.io/ns/sync/3");
        JsonCalendarData result = JsonCalendarData.from(response.body());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.items()).hasSize(1);
            softly.assertThat(result.items().get(0).events()).hasSize(1);
            softly.assertThat(result.items().get(0).events().get(0).uid()).isEqualTo(eventUid);
            softly.assertThat(result.items().get(0).events().get(0).summary().get()).isEqualTo("Updated Summary");
        });
    }

    @Test
    void reportShouldReturnEmptyListWhenNoChangesAfterGivenSync() throws JsonProcessingException {
        String eventUid1 = UUID.randomUUID().toString();
        String calendarData1 = TwakeCalendarEvent.builder()
            .uid(eventUid1)
            .organizer(alice.email())
            .summary("Kickoff Meeting")
            .location("Room 1")
            .description("Initial project kickoff.")
            .dtstart("20300411T090000")
            .dtend("20300411T100000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid1, calendarData1);

        String eventUid2 = UUID.randomUUID().toString();
        String calendarData2 = TwakeCalendarEvent.builder()
            .uid(eventUid2)
            .organizer(alice.email())
            .summary("Review Meeting")
            .location("Room 2")
            .description("Project review.")
            .dtstart("20300502T110000")
            .dtend("20300502T120000")
            .build()
            .toString();
        calDavClient.upsertCalendarEvent(alice, eventUid2, calendarData2);

        // Sync with token after no changes
        DavResponse response = calDavClient.findEventsByTimeAndSyncToken(alice,
            CalendarURL.from(alice.id()),
            "20300401T000000",
            "20300425T000000",
            "http://sabre.io/ns/sync/2");
        JsonCalendarData result = JsonCalendarData.from(response.body());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.syncToken()).isEqualTo("http://sabre.io/ns/sync/3");
            softly.assertThat(result.items()).isEmpty();
        });
    }
}
