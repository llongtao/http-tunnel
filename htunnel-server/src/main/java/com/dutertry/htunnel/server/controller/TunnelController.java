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
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
    
    private static final int BUFFER_SIZE = 1024;
    private static final long WAIT_TIME = 10000L;
    
    private final Map<String , SocketChannel> channels = new ConcurrentHashMap<>();
    
    @RequestMapping(value = "/connect", method = RequestMethod.GET)
    public String connection(
            @RequestParam String host,
            @RequestParam int port) throws IOException {
        
        LOGGER.info("New connection received for {}:{}", host, port);
        
        SocketChannel socketChannel = SocketChannel.open();
        SocketAddress socketAddr = new InetSocketAddress(host, port);
        socketChannel.connect(socketAddr);
        socketChannel.configureBlocking(false);
        
        String connectionId = UUID.randomUUID().toString();
        channels.put(connectionId, socketChannel);
        
        return connectionId;
    }

    @RequestMapping(value = "/write", method = RequestMethod.POST)
    public void write(@RequestHeader("X-SOH-ID") String connectionId, @RequestBody String body) throws IOException {
        
        LOGGER.debug("New write request for ID {} with body {}", connectionId, body);
        
        SocketChannel socketChannel = channels.get(connectionId);
        if(socketChannel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unable to find channel");
        }
        
        byte[] bytes = Base64.getDecoder().decode(body);
        if(bytes.length > 0) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            while(bb.hasRemaining()) {
                socketChannel.write(bb);
            }
        }
    }
    
    @RequestMapping(value = "/read", method = RequestMethod.GET)
    public String read(@RequestHeader("X-SOH-ID") String connectionId) throws IOException {
        
        LOGGER.debug("New read request for ID {}", connectionId);
        
        SocketChannel socketChannel = channels.get(connectionId);
        if(socketChannel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unable to find channel");
        }
        
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
                    if(now-startTime >= WAIT_TIME) {
                        return "";
                    }
                }
            }
        }
    }
    
    @RequestMapping(value = "/close", method = RequestMethod.GET)
    public void close(@RequestHeader("X-SOH-ID") String connectionId) throws IOException {
        
        LOGGER.info("New close request for ID {}", connectionId);
        
        SocketChannel socketChannel = channels.get(connectionId);
        if(socketChannel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unable to find channel");
        }
        
        socketChannel.close();
        
        channels.remove(connectionId);
    }
    
    @RequestMapping(value = "/clean", method = RequestMethod.GET)
    public String clean() {
        LOGGER.info("Cleaning connections");
        int closed = 0;
        int error = 0;
        Iterator<Map.Entry<String, SocketChannel>> it = channels.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, SocketChannel> entry = it.next();
            closed++;
            try {
                entry.getValue().close();
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
        return "SOH";

    }
}
