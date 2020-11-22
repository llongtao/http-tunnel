package com.dutertry.htunnel.server.controller;

import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;

public class ClientConnection {
    private final String id;
    
    private final String ipAddress;
    
    private final LocalDateTime creationDateTime;
    
    private final SocketChannel socketChannel;
    
    public ClientConnection(String id, String ipAddress, LocalDateTime creationDateTime, SocketChannel socketChannel) {
        super();
        this.id = id;
        this.ipAddress = ipAddress;
        this.creationDateTime = creationDateTime;
        this.socketChannel = socketChannel;
    }

    public String getId() {
        return id;
    }

    public String getIpAddress() {
        return ipAddress;
    }
    
    public LocalDateTime getCreationDateTime() {
        return creationDateTime;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }
}
