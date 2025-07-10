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

import java.net.URI;
import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

public record CalendarURL(String base, String calendarId) {
    public static final String CALENDAR_URL_PATH_PREFIX = "/calendars";

    public static CalendarURL from(String id) {
        return new CalendarURL(id, id);
    }

    public static CalendarURL deserialize(String rawValue) {
        List<String> parts = Splitter.on('/')
            .omitEmptyStrings().trimResults()
            .splitToList(rawValue);
        Preconditions.checkArgument(parts.size() == 2, "Invalid CalendarURL format: %s", rawValue);
        String base = parts.get(0);
        String calendarId = new String(parts.get(1));
        return new CalendarURL(base, calendarId);
    }

    public CalendarURL {
        Preconditions.checkArgument(base != null, "baseCalendarId must not be null");
        Preconditions.checkArgument(calendarId != null, "calendarId must not be null");
    }

    public URI asUri() {
        return URI.create(CALENDAR_URL_PATH_PREFIX + "/" + base + "/" + calendarId);
    }

    public String serialize() {
        return base() + "/" + calendarId;
    }
}
