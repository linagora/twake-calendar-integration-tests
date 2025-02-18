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

import org.bson.types.ObjectId;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class DockerOpenPaasExtension implements ParameterResolver {

    public record Response(int status, String body) {}
    public static final boolean DEBUG = true;

    // Ensuring DockerOpenPaasSetupSingleton is loaded to classpath
    private static DockerOpenPaasSetup dockerOpenPaasSetupSingleton = DockerOpenPaasSetupSingleton.singleton;

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerOpenPaasSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return DockerOpenPaasSetupSingleton.singleton;
    }

    public DockerOpenPaasSetup getDockerOpenPaasSetupSingleton() {
        return DockerOpenPaasSetupSingleton.singleton;
    }

    public OpenPaasUser newTestUser() {
        return DockerOpenPaasSetupSingleton.singleton
            .getOpenPaaSProvisioningService()
            .createUser()
            .block();
    }


    public String domainId() {
        return ((ObjectId) DockerOpenPaasSetupSingleton.singleton
            .getOpenPaaSProvisioningService()
            .openPaasDomain()
            .get("_id")).toString();
    }

    public HttpClient davHttpClient() {
        return HttpClient.create()
            .baseUrl("http://" + TestContainersUtils.getContainerPrivateIpAddress(getDockerOpenPaasSetupSingleton().getSabreDavContainer()) + ":80");
    }

    public static Mono<ByteBuf> body(String body) {
        return Mono.just(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)));
    }

    public static Response execute(HttpClient.ResponseReceiver<?> client) {
        Response block = client.responseSingle((response, content) -> content.asString()
                .map(stringContent -> new Response(response.status().code(), stringContent)))
            .block();

        if (DEBUG) {
            System.out.println("============");
            System.out.println("Code: " + block.status);
            System.out.println(block.body);
            System.out.println("============");
        }

        return block;
    }


    public static int executeNoContent(HttpClient.ResponseReceiver<?> client) {
        return client.response()
            .block()
            .status()
            .code();
    }
}