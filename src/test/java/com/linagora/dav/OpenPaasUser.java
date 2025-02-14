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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.dav;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.bson.Document;

import io.netty.handler.codec.http.HttpHeaders;

public record OpenPaasUser(String id, String firstname, String lastname, String email, String password) {
    public static OpenPaasUser fromDocument(Document document) {
        return new OpenPaasUser(
            document.getObjectId("_id").toString(),
            document.getString("firstname"),
            document.getString("lastname"),
            document.getList("accounts", Document.class)
                .getFirst().getList("emails", String.class).getFirst(),
            document.getString("password"));
    }

    HttpHeaders basicAuth(HttpHeaders headers) {
        String userPassword = "admin&" + email + ":secret123";
        byte[] base64UserPassword = Base64
            .getEncoder()
            .encode(userPassword.getBytes(StandardCharsets.UTF_8));

        return headers.add("Authorization", "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8));
    }
}
