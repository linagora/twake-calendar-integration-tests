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

public record AddressBookURL(String base, String addressBookId) {
    public static final String URL_PATH_PREFIX = "/addressbooks";

    public static AddressBookURL from(String id) {
        return new AddressBookURL(id, id);
    }

    public static AddressBookURL deserialize(String rawValue) {
        List<String> parts = Splitter.on('/')
            .omitEmptyStrings().trimResults()
            .splitToList(rawValue);
        Preconditions.checkArgument(parts.size() == 2, "Invalid AddressBookURL format: %s", rawValue);
        String base = parts.get(0);
        String addressBookId = new String(parts.get(1));
        return new AddressBookURL(base, addressBookId);
    }

    public AddressBookURL {
        Preconditions.checkArgument(base != null, "base must not be null");
        Preconditions.checkArgument(addressBookId != null, "addressBookId must not be null");
    }

    public URI asUri() {
        return URI.create(URL_PATH_PREFIX + "/" + base + "/" + addressBookId);
    }

    public String serialize() {
        return base() + "/" + addressBookId;
    }
}
