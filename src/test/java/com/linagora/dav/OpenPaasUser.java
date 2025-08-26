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

    HttpHeaders impersonatedBasicAuth(HttpHeaders headers) {
        return headers.add("Authorization", impersonatedBasicAuth(email));
    }

    String impersonatedBasicAuth() {
        return impersonatedBasicAuth(email);
    }

    public static String impersonatedBasicAuth(String email) {
        String userPassword = "admin&" + email + ":secret123";
        byte[] base64UserPassword = Base64
            .getEncoder()
            .encode(userPassword.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8);
    }

    HttpHeaders basicAuth(HttpHeaders headers) {
        return headers.add("Authorization", basicAuth(email));
    }

    public static String basicAuth(String email) {
        String userPassword = email + ":secret";
        byte[] base64UserPassword = Base64
            .getEncoder()
            .encode(userPassword.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8);
    }

    HttpHeaders localPartBasicAuth(HttpHeaders headers) {
        return headers.add("Authorization", localPartBasicAuth(email));
    }

    // authenticate with user uid without "@open-pass.org"
    public static String localPartBasicAuth(String email) {
        String userPassword = email.split("@")[0] + ":secret";
        byte[] base64UserPassword = Base64
                .getEncoder()
                .encode(userPassword.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(base64UserPassword, StandardCharsets.UTF_8);
    }
}
