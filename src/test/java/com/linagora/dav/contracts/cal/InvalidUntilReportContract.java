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


import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.dav.CalDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.DockerTwakeCalendarSetup.DockerService;
import com.linagora.dav.OpenPaasUser;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;

import io.restassured.response.Response;
import reactor.core.publisher.Mono;

/**
 * Reproduces https://github.com/linagora/esn-sabre/issues/374
 *
 * An ICS carrying an invalid RRULE UNTIL value (e.g. {@code UNTIL=INVALID DATE}) makes Sabre blow
 * up with a 500:
 *
 * <pre>
 * Sabre\VObject\InvalidDataException
 * The supplied iCalendar datetime value is incorrect: INVALID DATE
 * </pre>
 *
 * Such a value is now rejected on write. The expected behaviour for an object stored before that
 * is to be lenient on read and not crash the whole time-range REPORT because of a single malformed
 * occurrence: the REPORT should succeed (the broken occurrence may simply be filtered out of the
 * result).
 */
public abstract class InvalidUntilReportContract {

    public abstract DockerTwakeCalendarExtension dockerExtension();

    private CalDavClient calDavClient;
    private OpenPaasUser alice;

    @BeforeEach
    void setUp() {
        calDavClient = new CalDavClient(dockerExtension().davHttpClient());
        alice = dockerExtension().newTestUser();
    }

    @Test
    void putShouldRejectEventWithInvalidUntil() {
        // GIVEN an event whose RRULE carries an invalid UNTIL value
        String eventUid = UUID.randomUUID().toString();
        String ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Linagora//Twake Calendar//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260601T080000Z
            DTSTART:20260608T170000Z
            DTEND:20260608T173000Z
            RRULE:FREQ=WEEKLY;INTERVAL=1;UNTIL=INVALID DATE;BYDAY=MO
            SUMMARY:Recurring event with broken UNTIL
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);

        // WHEN it is PUT, THEN it is rejected
        given(dockerExtension().davRequestSpecification())
            .header("Authorization", alice.impersonatedBasicAuth())
            .contentType("text/calendar; charset=utf-8")
            .body(ics)
        .when()
            .put("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics")
        .then()
            .log().ifValidationFails()
            .statusCode(SC_BAD_REQUEST)
            .body(containsString("Validation error in iCalendar: UNTIL in RRULE must be a DATE or DATE-TIME value"));
    }

    @Test
    void timeRangeReportShouldNotFailWhenEventHasInvalidUntil() {
        // GIVEN an event stored before invalid UNTIL values were rejected on write:
        // PUT no longer accepts one, so the stored object is altered directly
        String eventUid = UUID.randomUUID().toString();
        calDavClient.upsertCalendarEvent(alice, eventUid, """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Linagora//Twake Calendar//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260601T080000Z
            DTSTART:20260608T170000Z
            DTEND:20260608T173000Z
            RRULE:FREQ=WEEKLY;INTERVAL=1;BYDAY=MO
            SUMMARY:Recurring event with broken UNTIL
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid));
        String storedIcs = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Linagora//Twake Calendar//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260601T080000Z
            DTSTART:20260608T170000Z
            DTEND:20260608T173000Z
            RRULE:FREQ=WEEKLY;INTERVAL=1;UNTIL=INVALID DATE;BYDAY=MO
            SUMMARY:Recurring event with broken UNTIL
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
        String mongoUri = dockerExtension().getDockerTwakeCalendarSetupSingleton()
            .getServiceUri(DockerService.MONGO, "mongodb").toString();
        try (MongoClient mongo = MongoClients.create(mongoUri)) {
            long modified = Mono.from(mongo.getDatabase("sabre").getCollection("calendarobjects")
                .updateOne(Filters.eq("uid", eventUid), Updates.combine(
                    Updates.set("calendardata", storedIcs),
                    Updates.set("size", storedIcs.length()))))
                .block().getModifiedCount();
            assertThat(modified).isEqualTo(1);
        }

        // WHEN a time-range REPORT has to expand that recurrence
        Response response = calDavClient.findEventsByTime(alice, "20260601T000000", "20260701T000000");

        // THEN the REPORT answers gracefully instead of failing with a 500,
        // and the malformed object is filtered out of the result
        String responseBody = response.then()
            .log().ifValidationFails()
            .statusCode(200)
            .extract().asString();
        assertThatJson(responseBody)
            .whenIgnoringPaths("_embedded.sync-token")
            .isEqualTo("""
                {
                    "_links": {
                        "self": {
                            "href": "/calendars/%s/%s.json"
                        }
                    },
                    "_embedded": {
                        "dav:item": []
                    }
                }
                """.formatted(alice.id(), alice.id()));

        // AND the stored object can still be fetched as is
        String fetchedIcs = calDavClient.getCalendarEvent(alice,
            URI.create("/calendars/" + alice.id() + "/" + alice.id() + "/" + eventUid + ".ics"));
        assertThat(fetchedIcs).isEqualTo(storedIcs);
    }
}
