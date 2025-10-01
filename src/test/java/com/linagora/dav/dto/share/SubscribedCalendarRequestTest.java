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

package com.linagora.dav.dto.share;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import org.junit.jupiter.api.Test;

import net.javacrumbs.jsonunit.core.Option;

public class SubscribedCalendarRequestTest {

    @Test
    void testSerializeSubscribedCalendarWithReadOnlyTrue() {
        String sourceId = "68d61948ae1f70005e587e5e";
        SubscribedCalendarRequest calendar = SubscribedCalendarRequest.builder()
            .id("calendar-123")
            .sourceUserId(sourceId)
            .name("ReadOnly agenda")
            .color("#2196f3")
            .readOnly(true)
            .build();

        String json = calendar.serialize();

        assertThatJson(json)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                     "id": "calendar-123",
                     "calendarserver:source": {
                         "name": "ReadOnly agenda",
                         "color": "#2196f3",
                         "id": "68d61948ae1f70005e587e5e",
                         "invite": [
                             {
                                 "href": "principals/users/68d61948ae1f70005e587e5e",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "properties": [],
                                 "access": 1,
                                 "comment": null,
                                 "inviteStatus": 2
                             }
                         ],
                         "acl": [
                             {
                                 "privilege": "{DAV:}share",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}share",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write-properties",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write-properties",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-read",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "{DAV:}authenticated",
                                 "protected": true
                             }
                         ],
                         "rights": {
                             "_userEmails": {
                                 "68d61948ae1f70005e587e5e": "principals/users/68d61948ae1f70005e587e5e"
                             },
                             "_ownerId": "68d61948ae1f70005e587e5e",
                             "_public": "{DAV:}read",
                             "_type": "user",
                             "_sharee": {}
                         },
                         "readOnly": true,
                         "type": "user",
                         "description": "",
                         "calendarHomeId": "68d61948ae1f70005e587e5e",
                         "selected": false,
                         "href": "/calendars/68d61948ae1f70005e587e5e/68d61948ae1f70005e587e5e.json"
                     },
                     "acl": [
                         {
                             "privilege": "{DAV:}share",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}share",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write-properties",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write-properties",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-read",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "{DAV:}authenticated",
                             "protected": true
                         }
                     ],
                     "caldav:description": "",
                     "apple:color": "#2196f3",
                     "dav:name": "ReadOnly agenda",
                     "invite": [
                         {
                             "href": "principals/users/68d61948ae1f70005e587e5e",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "properties": [],
                             "access": 1,
                             "comment": null,
                             "inviteStatus": 2
                         }
                     ]
                 }
                """);
    }


    @Test
    void testSerializeSubscribedCalendarWithReadOnlyFalse() {
        String sourceId = "68d61948ae1f70005e587e5e";
        SubscribedCalendarRequest calendar = SubscribedCalendarRequest.builder()
            .id("calendar-456")
            .sourceUserId(sourceId)
            .name("Writable agenda")
            .color("#ff0000")
            .readOnly(false)
            .build();

        String json = calendar.serialize();

        assertThatJson(json)
            .withOptions(Option.IGNORING_ARRAY_ORDER)
            .isEqualTo("""
                {
                     "id": "calendar-456",
                     "calendarserver:source": {
                         "name": "Writable agenda",
                         "color": "#ff0000",
                         "id": "68d61948ae1f70005e587e5e",
                         "invite": [
                             {
                                 "href": "principals/users/68d61948ae1f70005e587e5e",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "properties": [],
                                 "access": 1,
                                 "comment": null,
                                 "inviteStatus": 2
                             }
                         ],
                         "acl": [
                             {
                                 "privilege": "{DAV:}share",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}share",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write-properties",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write-properties",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-read",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}read",
                                 "principal": "{DAV:}authenticated",
                                 "protected": true
                             },
                             {
                                 "privilege": "{DAV:}write",
                                 "principal": "{DAV:}authenticated",
                                 "protected": true
                             }
                         ],
                         "rights": {
                             "_userEmails": {
                                 "68d61948ae1f70005e587e5e": "principals/users/68d61948ae1f70005e587e5e"
                             },
                             "_ownerId": "68d61948ae1f70005e587e5e",
                             "_sharee": {},
                             "_public": "{DAV:}write",
                             "_type": "user"
                         },
                         "readOnly": false,
                         "type": "user",
                         "description": "",
                         "href": "/calendars/68d61948ae1f70005e587e5e/68d61948ae1f70005e587e5e.json",
                         "calendarHomeId": "68d61948ae1f70005e587e5e",
                         "selected": false
                     },
                     "acl": [
                         {
                             "privilege": "{DAV:}share",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}share",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write-properties",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write-properties",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-read",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "principals/users/68d61948ae1f70005e587e5e/calendar-proxy-write",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}read",
                             "principal": "{DAV:}authenticated",
                             "protected": true
                         },
                         {
                             "privilege": "{DAV:}write",
                             "principal": "{DAV:}authenticated",
                             "protected": true
                         }
                     ],
                     "invite": [
                         {
                             "href": "principals/users/68d61948ae1f70005e587e5e",
                             "principal": "principals/users/68d61948ae1f70005e587e5e",
                             "properties": [],
                             "access": 1,
                             "comment": null,
                             "inviteStatus": 2
                         }
                     ],
                     "caldav:description": "",
                     "apple:color": "#ff0000",
                     "dav:name": "Writable agenda"
                 }
                """);
    }
}
