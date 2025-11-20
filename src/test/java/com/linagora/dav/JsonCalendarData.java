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

public record JsonCalendarData(String href,
                               String syncToken,
                               List<DavItem> items) {
    public record DavItem(String href, int status, List<JsonCalendarEventData> events) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static JsonCalendarData from(String json) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode items = root.at("/_embedded/dav:item");
        String calendarHref = root.at("/_links/self/href").asText();
        String syncToken = root.at("/sync-token").asText();
        List<DavItem> davItems = Streams.stream(items.elements()).map(jsonNode -> {
            String href = jsonNode.at("/_links/self/href").asText();
            int status = jsonNode.at("/status").asInt();
            Optional<String> method = getFieldValue(jsonNode.at("/data/1"), "method");
            List<JsonCalendarEventData> events = Streams.stream(jsonNode.at("/data/2").elements()).map(vevent -> {
                String uid = getFieldValue(vevent.get(1), "uid").get();
                Optional<String> summary = getFieldValue(vevent.get(1), "summary");
                String dtstart = getFieldValue(vevent.get(1), "dtstart").get();
                String dtend = getFieldValue(vevent.get(1), "dtend").get();
                Optional<String> recurrenceId = getFieldValue(vevent.get(1), "recurrence-id");
                return new JsonCalendarEventData(method, uid, summary, dtstart, dtend, recurrenceId);
            }).toList();
            return new DavItem(href, status, events);
        }).toList();
        return new JsonCalendarData(calendarHref, syncToken, davItems);
    }

    private static Optional<String> getFieldValue(JsonNode element, String fieldName) {
        return Streams.stream(element.elements())
            .filter(jsonNode -> jsonNode.get(0).asText().equals(fieldName))
            .map(jsonNode -> jsonNode.get(3).asText()).findAny();
    }
}
