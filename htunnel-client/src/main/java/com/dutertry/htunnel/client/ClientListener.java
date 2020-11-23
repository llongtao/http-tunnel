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
package com.dutertry.htunnel.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;

/**
 * @author ndutertry
 *
 */
@Controller
public class ClientListener implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientListener.class);
    
    @Value("${port:3000}")
    private int port;
    
    @Value("${target}")
    private String target;
    
    @Value("${tunnel}")
    private String tunnel;
    
    @Value("${proxy:}")
    private String proxy;
    
    private Thread thread;
    
    @PostConstruct
    public void start() throws IOException {
        LOGGER.info("Starting listener thread");
        thread = new Thread(this);
        thread.start();
    }
    
    public void run() {
        String targetHost = StringUtils.substringBeforeLast(target, ":");
        int targetPort = Integer.parseInt(StringUtils.substringAfterLast(target, ":"));
        try(ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.socket().bind(new InetSocketAddress(port));
            LOGGER.info("Waiting for connection on port " + port);
        
            while(!Thread.currentThread().isInterrupted()) {
                SocketChannel socketChannel = ssc.accept();
                LOGGER.info("New connection received");
                socketChannel.configureBlocking(false);
                
                TunnelClient tunnelClient = new TunnelClient(socketChannel,
                        targetHost, targetPort,
                        tunnel,
                        proxy,
                        true);
                Thread thread = new Thread(tunnelClient);
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
