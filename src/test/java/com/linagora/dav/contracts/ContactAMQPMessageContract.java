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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.dav.CardDavClient;
import com.linagora.dav.DockerTwakeCalendarExtension;
import com.linagora.dav.OpenPaasUser;

public abstract class ContactAMQPMessageContract {

    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    private CardDavClient cardDavClient;

    public abstract DockerTwakeCalendarExtension dockerExtension();

    @BeforeEach
    void setUp() {
        cardDavClient = new CardDavClient(dockerExtension().davHttpClient());
    }

    @Test
    void shouldReceiveMessageFromContactCreatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "sabre:contact:created", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8));

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "path" : "addressbooks/{userId}/collected/{vcardUid}.vcf",
              "owner" : "principals/users/{userId}",
              "carddata" : "BEGIN:VCARD\\nVERSION:3.0\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\nUID:{vcardUid}\\nFN:John Doe\\nEMAIL;TYPE=Work:john.doe@example.com\\nEND:VCARD\\n"
            }
            """.replace("{userId}", testUser.id())
            .replace("{vcardUid}", vcardUid);

        assertThatJson(actual).isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromContactUpdatedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "sabre:contact:updated", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8));

        String updatedVcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe Updated
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, updatedVcard.getBytes(StandardCharsets.UTF_8));

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "path" : "addressbooks/{userId}/collected/{vcardUid}.vcf",
              "owner" : "principals/users/{userId}",
              "carddata" : "BEGIN:VCARD\\nVERSION:3.0\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\nUID:{vcardUid}\\nFN:John Doe Updated\\nEMAIL;TYPE=Work:john.doe@example.com\\nEND:VCARD\\n"
            }
            """.replace("{userId}", testUser.id())
            .replace("{vcardUid}", vcardUid);

        assertThatJson(actual).isEqualTo(expected);
    }

    @Test
    void shouldReceiveMessageFromContactDeletedExchange() throws IOException {
        dockerExtension().getChannel().queueBind(QUEUE_NAME, "sabre:contact:deleted", "");

        OpenPaasUser testUser = dockerExtension().newTestUser();

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid);

        cardDavClient.upsertContact(testUser, addressBook, vcardUid, vcard.getBytes(StandardCharsets.UTF_8));

        cardDavClient.deleteContact(testUser, addressBook, vcardUid);

        String actual = new String(getMessageFromQueue(), StandardCharsets.UTF_8);

        String expected = """
            {
              "path" : "addressbooks/{userId}/collected/{vcardUid}.vcf",
              "owner" : "principals/users/{userId}",
              "carddata" : "BEGIN:VCARD\\nVERSION:3.0\\nPRODID:-//Sabre//Sabre VObject 4.1.3//EN\\nUID:{vcardUid}\\nFN:John Doe\\nEMAIL;TYPE=Work:john.doe@example.com\\nEND:VCARD\\n"
            }
            """.replace("{userId}", testUser.id())
            .replace("{vcardUid}", vcardUid);

        assertThatJson(actual).isEqualTo(expected);
    }

    private byte[] getMessageFromQueue() {
        return awaitAtMost.atMost(Duration.ofSeconds(20))
            .until(() -> dockerExtension().getChannel().basicGet(QUEUE_NAME, true), Objects::nonNull)
            .getBody();
    }
}
