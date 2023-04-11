/*
 * htunnel - A simple HTTP tunnel
 * https://github.com/nicolas-dutertry/htunnel
 *
 * Written by Nicolas Dutertry.
 *
 * This file is provided under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.dutertry.htunnel.server.connection;

import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dutertry.htunnel.common.ConnectionConfig;

/**
 * @author Nicolas Dutertry
 */
@Component
public class ClientConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConnectionManager.class);


    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();

    public String createConnection(String username, String ipAddress, ConnectionConfig connectionConfig, SocketChannel socketChannel) {
        String connectionId = UUID.randomUUID().toString();

        connections.put(connectionId, new ClientConnection(connectionId, username, ipAddress, connectionConfig, LocalDateTime.now(), socketChannel));

        return connectionId;
    }

    public ClientConnection getConnection(String connectionId) {
        return connections.get(connectionId);
    }

    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
    }

    private void cleanConnections(LocalDateTime minSleepTime) {
        LOGGER.info("Cleaning connections  active before {}", minSleepTime);
        int closed = 0;
        int error = 0;
        Iterator<Map.Entry<String, ClientConnection>> it = connections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ClientConnection> entry = it.next();
            ClientConnection connection = entry.getValue();
            if (connection.getLastUseTime().isBefore(minSleepTime)) {
                closed++;
                try {
                    connection.getSocketChannel().close();
                } catch (Exception e) {
                    LOGGER.error("Error while cleaning connections", e);
                    error++;
                }
                it.remove();
            }
        }
        LOGGER.info("{} connection(s) closed ({} with error)", closed, error);
    }

    @PostConstruct
    public void init() {
        Thread cleaner = new Thread(() -> {
            while (true) {
                LocalDateTime now = LocalDateTime.now();
                cleanConnections(now.minusSeconds(600));
                LOGGER.info("active connection {}", connections.size());
                connections.forEach((id,con)->{
                    LOGGER.info("active connection ip:{} user:{}", con.getIpAddress(),con.getUsername());
                });
                try {
                    //noinspection BusyWait
                    Thread.sleep(60_000L);
                } catch (InterruptedException e) {
                    LOGGER.error("Cleaner thread interrupted", e);
                }
            }
        });
        cleaner.setDaemon(true);
//        cleaner.start();
    }
}
