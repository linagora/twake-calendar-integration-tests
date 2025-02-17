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

import org.testcontainers.containers.ContainerState;

import com.github.dockerjava.api.model.ContainerNetwork;

public class TestContainersUtils {

    public static String getContainerPrivateIpAddress(ContainerState containerState) {
        return containerState.getContainerInfo()
            .getNetworkSettings()
            .getNetworks()
            .values()
            .stream().findFirst()
            .map(ContainerNetwork::getIpAddress)
            .orElseThrow(() -> new IllegalStateException("Unable to retrieve ip address for container " + containerState.getContainerId()));
    }
}
