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

import org.bson.Document;

public record OpenPaaSTeamCalendar(String id, String name, String displayName, String domainName) {
    public static OpenPaaSTeamCalendar fromDocument(Document document) {
        return new OpenPaaSTeamCalendar(
            document.getObjectId("_id").toString(),
            document.getString("name"),
            document.getString("displayName"),
            document.getString("domainName"));
    }

    public String email() {
        return id + "@" + domainName;
    }

    public String principalHref() {
        return "/principals/team-calendars/" + id + "/";
    }
}
