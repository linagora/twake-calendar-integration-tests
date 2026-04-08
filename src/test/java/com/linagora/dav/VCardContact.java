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
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record VCardContact(String firstName,
                           String lastName,
                           Optional<String> tel,
                           Optional<String> email,
                           Optional<String> categories,
                           Optional<String> org,
                           Optional<String> role) {

    public static class Builder {
        private Optional<String> firstName = Optional.empty();
        private Optional<String> lastName = Optional.empty();
        private Optional<String> tel = Optional.empty();
        private Optional<String> email = Optional.empty();
        private Optional<String> categories = Optional.empty();
        private Optional<String> org = Optional.empty();
        private Optional<String> role = Optional.empty();

        public Builder firstName(String firstName) {
            this.firstName = Optional.of(firstName);
            return this;
        }

        public Builder lastName(String lastName) {
            this.lastName = Optional.of(lastName);
            return this;
        }

        public Builder tel(String tel) {
            this.tel = Optional.of(tel);
            return this;
        }

        public Builder email(String email) {
            this.email = Optional.of(email);
            return this;
        }

        public Builder categories(String categories) {
            this.categories = Optional.of(categories);
            return this;
        }

        public Builder org(String org) {
            this.org = Optional.of(org);
            return this;
        }

        public Builder role(String role) {
            this.role = Optional.of(role);
            return this;
        }

        public VCardContact build() {
            return new VCardContact(
                firstName.orElseThrow(() -> new IllegalArgumentException("First name is required")),
                lastName.orElseThrow(() -> new IllegalArgumentException("Last name is required")),
                tel,
                email,
                categories,
                org,
                role
            );
        }
    }

    public enum Format { VCARD, JSON }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static Builder builder() {
        return new Builder();
    }

    public byte[] toBytes(Format format, String uid) {
        return switch (format) {
            case VCARD -> toVCardPayload(uid);
            case JSON -> toVCardJsonPayload(uid);
        };
    }

    public byte[] toVCardPayload(String uid) {
        StringBuilder vcard = new StringBuilder()
            .append("BEGIN:VCARD\n")
            .append("VERSION:3.0\n")
            .append("UID:").append(uid).append("\n")
            .append("FN:").append(firstName).append(" ").append(lastName).append("\n")
            .append("N:").append(lastName).append(";").append(firstName).append("\n");
        tel.ifPresent(t -> vcard.append("TEL;TYPE=WORK:").append(t).append("\n"));
        email.ifPresent(e -> vcard.append("EMAIL;TYPE=WORK:").append(e).append("\n"));
        org.ifPresent(o -> vcard.append("ORG:").append(o).append("\n"));
        role.ifPresent(r -> vcard.append("ROLE:").append(r).append("\n"));
        categories.ifPresent(c -> vcard.append("CATEGORIES:").append(c).append("\n"));
        vcard.append("END:VCARD");
        return vcard.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] toVCardJsonPayload(String uid) {
        try {
            ArrayNode root = OBJECT_MAPPER.createArrayNode().add("vcard");

            ArrayNode nValue = OBJECT_MAPPER.createArrayNode()
                .add(lastName)
                .add(firstName);

            ArrayNode props = OBJECT_MAPPER.createArrayNode()
                .add(textProp("version", "3.0"))
                .add(textProp("uid", uid))
                .add(textProp("fn", firstName + " " + lastName))
                .add(arrayProp("n", nValue));

            tel.ifPresent(t -> {
                ObjectNode telParams = OBJECT_MAPPER.createObjectNode();
                ArrayNode telType = OBJECT_MAPPER.createArrayNode()
                    .add("Work");
                telParams.set("type", telType);
                ArrayNode telProp = OBJECT_MAPPER.createArrayNode()
                    .add("tel")
                    .add(telParams)
                    .add("uri")
                    .add(t);
                props.add(telProp);
            });

            email.ifPresent(e -> {
                ObjectNode emailParams = OBJECT_MAPPER.createObjectNode();
                ArrayNode emailType = OBJECT_MAPPER.createArrayNode()
                    .add("Work");
                emailParams.set("type", emailType);
                ArrayNode emailProp = OBJECT_MAPPER.createArrayNode()
                    .add("email")
                    .add(emailParams)
                    .add("text")
                    .add(e);
                props.add(emailProp);
            });

            categories.ifPresent(c -> props.add(textProp("categories", c)));

            org.ifPresent(o -> {
                ArrayNode orgValue = OBJECT_MAPPER.createArrayNode()
                    .add(o);
                props.add(arrayProp("org", orgValue));
            });

            role.ifPresent(r -> props.add(textProp("role", r)));

            root.add(props);
            root.add(OBJECT_MAPPER.createArrayNode());

            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize VCardContact to jCard JSON", e);
        }
    }

    private ArrayNode textProp(String name, String value) {
        return OBJECT_MAPPER.createArrayNode()
            .add(name)
            .add(OBJECT_MAPPER.createObjectNode())
            .add("text")
            .add(value);
    }

    private ArrayNode arrayProp(String name, ArrayNode value) {
        return OBJECT_MAPPER.createArrayNode()
            .add(name)
            .add(OBJECT_MAPPER.createObjectNode())
            .add("text")
            .add(value);
    }
}
