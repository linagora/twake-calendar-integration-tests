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

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Streams;

public record JsonCalendarEventData(String uid,
                                    Optional<String> summary,
                                    String dtstart,
                                    String dtend,
                                    Optional<String> recurrenceId) {

    public static class Builder {
        private Optional<String> uid = Optional.empty();
        private Optional<String> summary = Optional.empty();
        private Optional<String> dtstart = Optional.empty();
        private Optional<String> dtend = Optional.empty();
        private Optional<String> recurrenceId = Optional.empty();

        public Builder uid(String uid) {
            this.uid = Optional.of(uid);
            return this;
        }

        public Builder summary(String summary) {
            this.summary = Optional.of(summary);
            return this;
        }

        public Builder dtstart(String dtstart) {
            this.dtstart = Optional.of(dtstart);
            return this;
        }

        public Builder dtend(String dtend) {
            this.dtend = Optional.of(dtend);
            return this;
        }

        public Builder recurrenceId(String recurrenceId) {
            this.recurrenceId = Optional.of(recurrenceId);
            return this;
        }

        public JsonCalendarEventData build() {
            return new JsonCalendarEventData(
                uid.get(),
                summary,
                dtstart.get(),
                dtend.get(),
                recurrenceId
            );
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static Builder builder() {
        return new Builder();
    }

    public static List<JsonCalendarEventData> from(String json) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode vevents = root.at("/_embedded/dav:item/0/data/2");
        return Streams.stream(vevents.elements()).map(vevent -> {
            String uid = getEventDataField(vevent.get(1), "uid").get();
            Optional<String> summary = getEventDataField(vevent.get(1), "summary");
            String dtstart = getEventDataField(vevent.get(1), "dtstart").get();
            String dtend = getEventDataField(vevent.get(1), "dtend").get();
            Optional<String> recurrenceId = getEventDataField(vevent.get(1), "recurrence-id");
            return new JsonCalendarEventData(uid, summary, dtstart, dtend, recurrenceId);
        }).toList();
    }

    private static Optional<String> getEventDataField(JsonNode vevent, String fieldName) {
        return Streams.stream(vevent.elements())
            .filter(jsonNode -> jsonNode.get(0).asText().equals(fieldName))
            .map(jsonNode -> jsonNode.get(3).asText()).findAny();
    }
}
