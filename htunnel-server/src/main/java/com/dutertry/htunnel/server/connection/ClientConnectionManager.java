package com.dutertry.htunnel.server.connection;

import java.nio.channels.SocketChannel;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dutertry.htunnel.common.ConnectionConfig;

@Component
public class ClientConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientConnectionManager.class);
    private static int MAX_CONNECTION_DURATION_HOUR = 8;
    
    private final Map<String , ClientConnection> connections = new ConcurrentHashMap<>();
    
    public String createConnection(String ipAddress, ConnectionConfig connectionConfig, SocketChannel socketChannel) {
        String connectionId = UUID.randomUUID().toString();
        
        connections.put(connectionId, new ClientConnection(connectionId, ipAddress, connectionConfig, LocalDateTime.now(), socketChannel));
        
        return connectionId;
    }
    
    public ClientConnection getConnection(String connectionId) {
        return connections.get(connectionId);
    }
    
    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
    }
    
    private void cleanConnections(LocalDateTime minCreationDateTime) {
        LOGGER.debug("Cleaning connections created before {}", minCreationDateTime);
        int closed = 0;
        int error = 0;
        Iterator<Map.Entry<String, ClientConnection>> it = connections.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, ClientConnection> entry = it.next();
            ClientConnection connection = entry.getValue();
            if(connection.getCreationDateTime().isBefore(minCreationDateTime)) {
                closed++;
                try {
                    connection.getSocketChannel().close();
                } catch(Exception e) {
                    LOGGER.error("Error while cleaning connections", e);
                    error++;
                }
                it.remove();
            }
        }
        LOGGER.debug("{} connection(s) closed ({} with error)", closed, error);
    }
    
    @PostConstruct
    public void init() {
        Thread cleaner = new Thread(() ->  {
            cleanConnections(LocalDateTime.now().minusHours(MAX_CONNECTION_DURATION_HOUR));
            try {
                Thread.sleep(600_000L);
            } catch(InterruptedException e) {
                LOGGER.error("Cleaner thread interrupted", e);
            }
        });
        cleaner.setDaemon(true);
        cleaner.start();
    }
}
