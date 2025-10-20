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
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.types.ObjectId;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import io.netty.buffer.Unpooled;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class TwakeCalendarProvisioningService {
    public static final String PASSWORD = "secret";
    public static final String DEFAULT_DOMAIN = "open-paas.org";

    private static final TechnicalTokenService technicalTokenService = new TechnicalTokenService.Impl("technicalTokenSecret", Duration.ofSeconds(3600));

    private final MongoDatabase database;
    private final HttpClient httpClient;

    public TwakeCalendarProvisioningService(String mongoUri, String calendarSideServiceUri) {
        MongoClient mongoClient = MongoClients.create(mongoUri);
        database = mongoClient.getDatabase("esn_docker");

        httpClient = HttpClient.create()
            .baseUrl(calendarSideServiceUri);
    }

    public Document openPaasDomain() {
        return createDomainIfNotExists(DEFAULT_DOMAIN);
    }

    public Document createDomainIfNotExists(String domainName) {
        MongoCollection<Document> domains = database.getCollection("domains");

        Document filter = new Document("name", domainName);

        return Mono.from(domains.find(filter).first())
            .switchIfEmpty(Mono.defer(() -> {
                Document newDomain = new Document()
                    .append("timestamp", new Document()
                        .append("creation", new Date()))
                    .append("hostnames", List.of())
                    .append("name", domainName)
                    .append("company_name", domainName)
                    .append("administrators", List.of());

                return Mono.from(domains.insertOne(newDomain))
                    .then(Mono.from(domains.find(filter).first()));
            }))
            .block();
    }

    public Mono<OpenPaasUser> createUser() {
        String usernameLocalPart = "user_" + UUID.randomUUID();
        return createUserInUsersRepository(usernameLocalPart + "@" + DEFAULT_DOMAIN)
            .then(createUserInMongo(usernameLocalPart, DEFAULT_DOMAIN));
    }

    public Mono<OpenPaasUser> createUser(String localPart) {
        return createUserInMongo(localPart, DEFAULT_DOMAIN);
    }

    public Mono<OpenPaasUser> createUser(String localPart, String domainName) {
        return createUserInMongo(localPart, domainName);
    }

    public Mono<OpenPaaSResource> createResource(String name, String description, OpenPaasUser admin) {
        Document resourceToSave = new Document()
            .append("name", name)
            .append("description", description)
            .append("type", "resource")
            .append("icon", "home")
            .append("deleted", false)
            .append("domain", openPaasDomain().get("_id"))
            .append("creator", new org.bson.types.ObjectId(admin.id()))
            .append("administrators", List.of(new Document()
                .append("id", admin.id())
                .append("objectType", "user")))
            .append("timestamps", new Document()
                .append("creation", java.util.Date.from(java.time.Instant.now()))
                .append("updatedAt", java.util.Date.from(java.time.Instant.now())));

        return Mono.from(database.getCollection("resources").insertOne(resourceToSave))
            .flatMap(success -> Mono.from(
                database.getCollection("resources").find(new Document("_id", success.getInsertedId())).first()))
            .map(doc -> new OpenPaaSResource(
                doc.getObjectId("_id").toString(),
                doc.getString("name"),
                doc.getString("description")
            ));
    }

    public String generateToken() {
        return technicalTokenService.generate(openPaasDomain().get("_id").toString())
            .map(TechnicalTokenService.JwtToken::value)
            .block();
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

    private Mono<OpenPaasUser> createUserInMongo(String localPart, String domainName) {
        Document domainDoc = createDomainIfNotExists(domainName);
        ObjectId domainId = domainDoc.getObjectId("_id");
        String email = localPart + "@" + domainName;

        Document userToSave = new Document()
            .append("firstname", "User_" + localPart)
            .append("lastname", "User_" + localPart)
            .append("password", PASSWORD)
            .append("domains", List.of(new Document("domain_id", domainId)))
            .append("accounts", List.of(new Document()
                .append("type", "email")
                .append("emails", List.of(email))));

        return Mono.from(database.getCollection("users").insertOne(userToSave))
            .flatMap(success ->
                Mono.from(database.getCollection("users").find(new Document("_id", success.getInsertedId())).first()))
            .map(OpenPaasUser::fromDocument);
    }

    public Mono<Void> enableSharedCalendarModule() {
        ObjectId domainId = openPaasDomain().getObjectId("_id");

        List<Document> newConfigurations = List.of(new Document("name", "features").append("value", new Document("isSharingCalendarEnabled", true)));

        Document newModule = new Document("configurations", newConfigurations)
            .append("name", "linagora.esn.calendar");

        var collection = database.getCollection("configurations");

        return Mono.from(collection.updateOne(
                Filters.and(
                    Filters.eq("domain_id", domainId),
                    Filters.eq("user_id", null),
                    Filters.elemMatch("modules", Filters.eq("name", "linagora.esn.calendar"))),
                Updates.set("modules.$.configurations", newConfigurations)))
            .flatMap(result -> {
                if (result.getMatchedCount() == 0) {
                    return Mono.from(collection.updateOne(
                        Filters.and(
                            Filters.eq("domain_id", domainId),
                            Filters.eq("user_id", null)),
                        Updates.push("modules", newModule)));
                } else {
                    return Mono.empty();
                }
            })
            .then();
    }
}