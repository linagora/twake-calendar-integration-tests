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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.bson.Document;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.netty.buffer.Unpooled;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class TwakeCalendarProvisioningService {
    public static final String PASSWORD = "secret";

    private final MongoDatabase database;
    private final HttpClient httpClient;

    public TwakeCalendarProvisioningService(String mongoUri, String calendarSideServiceHost) {
        MongoClient mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("esn_docker");

        httpClient = HttpClient.create()
            .baseUrl("http://" + calendarSideServiceHost + ":8000");
    }

    public Document openPaasDomain() {
        return Mono.from(database.getCollection("domains").find()
            .filter(new Document("name", "open-paas.org"))
            .first()).block();
    }

    public Mono<OpenPaasUser> createUser() {
        UUID randomUUID = UUID.randomUUID();
        return createUserInUsersRepository(randomUUID)
            .then(createUserInMongo(randomUUID));
    }

    public Mono<OpenPaasUser> createUser(String localPart) {
        return createUserInMongo(localPart);
    }

    private Mono<Void> createUserInUsersRepository(UUID randomUUID) {
        String username = "user_" + randomUUID + "@open-paas.org";
        return createUserInUsersRepository(username);
    }

    public Mono<Void> createUserInUsersRepository(String username) {
        String requestBody = String.format("{\"password\":\"%s\"}", PASSWORD);
        return httpClient.put()
            .uri("/users/" + username)
            .send(Mono.just(Unpooled.wrappedBuffer(requestBody.getBytes(StandardCharsets.UTF_8))))
            .responseSingle((response, responseContent) -> {
                if (response.status().code() == 204) {
                    return Mono.empty();
                }
                return responseContent.asString(StandardCharsets.UTF_8)
                    .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                    .flatMap(errorBody -> Mono.error(new RuntimeException(errorBody)));
            });
    }

    public Mono<OpenPaasUser> createUserInMongo(String username, String firstname, String lastname) {
        Document userToSave = new Document()
            .append("firstname", firstname)
            .append("lastname", lastname)
            .append("password", PASSWORD)
            .append("domains",  List.of(new Document("domain_id", openPaasDomain().get("_id"))))
            .append("accounts", List.of(new Document()
                .append("type", "email")
                .append("emails", List.of(username))));

        return Mono.from(database.getCollection("users").insertOne(userToSave))
            .flatMap(success ->
                Mono.from(
                    database.getCollection("users").find(new Document("_id", success.getInsertedId())).first()))
            .map(OpenPaasUser::fromDocument);
    }

    private Mono<OpenPaasUser> createUserInMongo(UUID randomUUID) {
        Document userToSave = new Document()
            .append("firstname", "User_" + randomUUID)
            .append("lastname", "User_" + randomUUID)
            .append("password", PASSWORD)
            .append("domains",  List.of(new Document("domain_id", openPaasDomain().get("_id"))))
            .append("accounts", List.of(new Document()
                .append("type", "email")
                .append("emails", List.of("user_" + randomUUID + "@open-paas.org"))));

        return Mono.from(database.getCollection("users").insertOne(userToSave))
            .flatMap(success ->
                Mono.from(
                    database.getCollection("users").find(new Document("_id", success.getInsertedId())).first()))
            .map(OpenPaasUser::fromDocument);
    }

    private Mono<OpenPaasUser> createUserInMongo(String localPart) {
        Document userToSave = new Document()
                .append("firstname", "User_" + localPart)
                .append("lastname", "User_" + localPart)
                .append("password", PASSWORD)
                .append("domains",  List.of(new Document("domain_id", openPaasDomain().get("_id"))))
                .append("accounts", List.of(new Document()
                        .append("type", "email")
                        .append("emails", List.of(localPart + "@open-paas.org"))));

        return Mono.from(database.getCollection("users").insertOne(userToSave))
                .flatMap(success ->
                        Mono.from(
                                database.getCollection("users").find(new Document("_id", success.getInsertedId())).first()))
                .map(OpenPaasUser::fromDocument);
    }
}