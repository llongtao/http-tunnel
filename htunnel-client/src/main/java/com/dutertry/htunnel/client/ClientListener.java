/*
 * © 1996-2014 Sopra HR Software. All rights reserved
 */
package com.dutertry.htunnel.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author ndutertry
 *
 */
@Component
public class ClientListener implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientListener.class);
    
    private int port = 3000;
    
    private String targetHost = "epsilon";
    private int targetPort = 22;
    
    private String tunnelHost = "epsilon.dutertry.com";
    private int tunnelPort = 8080;
    
    private String proxyHost = null;
    private int proxyPort = 0;
    
    private Thread thread;
    
    @PostConstruct
    public void start() throws IOException {
        LOGGER.info("Starting listener thread");
        thread = new Thread(this);
        thread.start();
    }
    
    public void run() {
        try(ServerSocketChannel ssc = ServerSocketChannel.open()) {            
            ssc.socket().bind(new InetSocketAddress(port));
            LOGGER.info("Waiting for connection on port " + port);
        
            while(!Thread.currentThread().isInterrupted()) {
                SocketChannel socketChannel = ssc.accept();
                LOGGER.info("New connection received");
                socketChannel.configureBlocking(false);
                
                TunnelClient tunnel = new TunnelClient(socketChannel,
                        targetHost, targetPort,
                        tunnelHost, tunnelPort,
                        proxyHost, proxyPort);
                Thread thread = new Thread(tunnel);
                thread.setDaemon(true);
                thread.start();
            }
        }catch(IOException e) {
            LOGGER.error("Error in listener loop", e);
        }
        LOGGER.info("Listener thread terminated");
    }
    
    @PreDestroy
    public void destroy() throws IOException {
        LOGGER.info("Destroying listener");
        thread.interrupt();
        thread = null;
    }
}
