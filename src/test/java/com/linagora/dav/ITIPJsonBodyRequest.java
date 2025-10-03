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

import static com.linagora.dav.contracts.CalendarSharingContract.MAPPER;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ITIPJsonBodyRequest {

    public String ical;
    public String sender;
    public String recipient;
    public String replyTo;
    public String uid;
    public String dtstamp;
    public String method;
    public String sequence;

    @JsonProperty("recurrence-id")
    public String recurrenceId;

    public static Builder builder() {
        return new Builder();
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Cannot serialize ITIP JSON", e);
        }
    }

    public static final class Builder {
        private final ITIPJsonBodyRequest bodyRequest = new ITIPJsonBodyRequest();

        public Builder ical(String ical) {
            bodyRequest.ical = ical;
            return this;
        }

        public Builder sender(String sender) {
            bodyRequest.sender = sender;
            return this;
        }

        public Builder recipient(String recipient) {
            bodyRequest.recipient = recipient;
            return this;
        }

        public Builder replyTo(String replyTo) {
            bodyRequest.replyTo = replyTo;
            return this;
        }

        public Builder uid(String uid) {
            bodyRequest.uid = uid;
            return this;
        }

        public Builder dtstamp(Instant dtstamp) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC);
            bodyRequest.dtstamp = fmt.format(dtstamp);
            return this;
        }

        public Builder method(String method) {
            bodyRequest.method = method;
            return this;
        }

        public Builder sequence(String sequence) {
            bodyRequest.sequence = sequence;
            return this;
        }

        public Builder recurrenceId(String recurrenceId) {
            bodyRequest.recurrenceId = recurrenceId;
            return this;
        }

        public ITIPJsonBodyRequest build() {
            if (bodyRequest.replyTo == null) bodyRequest.replyTo = bodyRequest.sender;
            bodyRequest.sequence = bodyRequest.sequence == null ? "0" : bodyRequest.sequence;
            if (bodyRequest.dtstamp == null) {
                dtstamp(Instant.now());
            }
            return bodyRequest;
        }

        public String buildJson() {
            return build().toJson();
        }
    }
}
