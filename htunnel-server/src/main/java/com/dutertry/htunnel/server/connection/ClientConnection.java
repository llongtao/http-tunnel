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

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;

import com.dutertry.htunnel.common.ConnectionConfig;

/**
 * @author Nicolas Dutertry
 */
public class ClientConnection {
    private final String id;

    private final String username;

    private final String ipAddress;

    private ConnectionConfig connectionConfig;

    private final LocalDateTime creationDateTime;

    private LocalDateTime lastUseTime;

    private final SocketChannel socketChannel;

    private final ByteBuffer readBuffer;

    ClientConnection(String id, String username, String ipAddress, ConnectionConfig connectionConfig, LocalDateTime creationDateTime, SocketChannel socketChannel) {
        super();
        this.id = id;
        this.username = username;
        this.ipAddress = ipAddress;
        this.connectionConfig = connectionConfig;
        this.creationDateTime = creationDateTime;
        this.lastUseTime = creationDateTime;
        this.socketChannel = socketChannel;
        this.readBuffer = ByteBuffer.allocate(connectionConfig.getBufferSize());
    }

    public String getId() {
        return id;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public ConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }

    public void setConnectionConfig(ConnectionConfig connectionConfig) {
        this.connectionConfig = connectionConfig;
    }

    public LocalDateTime getCreationDateTime() {
        return creationDateTime;
    }

    public LocalDateTime getLastUseTime() {
        return lastUseTime;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public ByteBuffer getReadBuffer() {
        return readBuffer;
    }

    public void updateUseTime() {
        lastUseTime = LocalDateTime.now();
    }

    public String getUsername() {
        return username;
    }
}
