/*
 * © 1996-2014 Sopra HR Software. All rights reserved
 */
package com.dutertry.htunnel.server.controller;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * @author ndutertry
 *
 */
@RestController
public class TunnelController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TunnelController.class);
    
    private static final String HEADER_CONNECTION_ID = "X-HTUNNEL-ID";
    private static final int BUFFER_SIZE = 1024;
    private static final long READ_WAIT_TIME = 10000L;
    
    private final Map<String , ClientConnection> connections = new ConcurrentHashMap<>();
    
    @RequestMapping(value = "/connect", method = RequestMethod.GET)
    public String connection(
            HttpServletRequest request,
            @RequestParam String host,
            @RequestParam int port) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        LOGGER.info("New connection received from {} for target {}:{}",
                ipAddress, host, port);
        
        SocketChannel socketChannel = SocketChannel.open();
        SocketAddress socketAddr = new InetSocketAddress(host, port);
        socketChannel.connect(socketAddr);
        socketChannel.configureBlocking(false);
        
        String connectionId = UUID.randomUUID().toString();
        
        connections.put(connectionId, new ClientConnection(connectionId, ipAddress, LocalDateTime.now(), socketChannel));
        
        return connectionId;
    }

    @RequestMapping(value = "/write", method = RequestMethod.POST)
    public void write(
            HttpServletRequest request,
            @RequestHeader(HEADER_CONNECTION_ID) String connectionId,
            @RequestBody byte[] body) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        
        LOGGER.debug("New write request from {} for ID {} with body length {}", ipAddress, connectionId, body.length);
        
        SocketChannel socketChannel = getSocketChannel(ipAddress, connectionId);
        
        byte[] bytes = Base64.getDecoder().decode(body);
        if(bytes.length > 0) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            while(bb.hasRemaining()) {
                socketChannel.write(bb);
            }
        }
    }
    
    @RequestMapping(value = "/read", method = RequestMethod.GET)
    public String read(
            HttpServletRequest request,
            @RequestHeader(HEADER_CONNECTION_ID) String connectionId) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        
        LOGGER.debug("New read request from {} for ID {}", ipAddress, connectionId);
        
        SocketChannel socketChannel = getSocketChannel(ipAddress, connectionId);
        
        ByteBuffer bb = ByteBuffer.allocate(BUFFER_SIZE);
        
        long startTime  = System.currentTimeMillis();
        while(true) {
            int read;
            try {
                read = socketChannel.read(bb);
            } catch(ClosedChannelException e) {
                read = -1;
            }
            
            if(!bb.hasRemaining() || read <= 0) {
                if(bb.position() > 0) {
                    bb.flip();
                    ByteBuffer encodedBuffer = Base64.getEncoder().encode(bb);
                    return StandardCharsets.UTF_8.decode(encodedBuffer).toString();
                } else {
                    if(read == -1) {
                        throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "EOF reached");
                    }
                    
                    long now = System.currentTimeMillis();
                    if(now-startTime >= READ_WAIT_TIME) {
                        return "";
                    }
                }
            }
        }
    }
    
    @RequestMapping(value = "/close", method = RequestMethod.GET)
    public void close(
            HttpServletRequest request,
            @RequestHeader(HEADER_CONNECTION_ID) String connectionId) throws IOException {
        
        String ipAddress = request.getRemoteAddr();
        
        LOGGER.info("New close request from {} for ID {}", ipAddress, connectionId);
        
        SocketChannel socketChannel = getSocketChannel(ipAddress, connectionId);
        
        socketChannel.close();
        
        connections.remove(connectionId);
    }
    
    @RequestMapping(value = "/clean", method = RequestMethod.GET)
    public String clean() {
        LOGGER.info("Cleaning connections");
        int closed = 0;
        int error = 0;
        Iterator<Map.Entry<String, ClientConnection>> it = connections.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, ClientConnection> entry = it.next();
            closed++;
            try {
                entry.getValue().getSocketChannel().close();
            } catch(Exception e) {
                LOGGER.error("Error while cleaning connections", e);
                error++;
            }
            it.remove();
        }
        
        return closed + " connection(s) closed (" + error + " with error)";

    }

    @RequestMapping(value = "/", method = RequestMethod.GET)
    public String about(final HttpServletRequest request, final HttpServletResponse response)
            throws URISyntaxException, IOException {
        return "htunnel";
    }
    
    private SocketChannel getSocketChannel(String ipAddress, String connectionId) {
        ClientConnection connection = connections.get(connectionId);
        if(connection == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unable to find connection");
        }
        if(!StringUtils.equals(ipAddress, connection.getIpAddress())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        
        return connection.getSocketChannel();
    }
}
