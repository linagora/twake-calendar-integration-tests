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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class AmqpTestHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Listen to a queue and push parsed JSON messages into a BlockingQueue.
     */
    public static BlockingQueue<JsonNode> listenToQueue(Channel channel, String queueName) throws IOException {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            try {
                JsonNode json = MAPPER.readTree(delivery.getBody());
                messages.add(json);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse message JSON", e);
            }
        };

        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
        return messages;
    }
}
