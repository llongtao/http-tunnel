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
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.PrivateKey;

import com.dutertry.htunnel.client.config.Tunnel;
import com.dutertry.htunnel.common.Constants;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * @author Nicolas Dutertry
 */
@Slf4j
public class ClientListener implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientListener.class);

    private int port;

    private String target;

    private String server;

    private String username;

    private String password;


    @Value("${single:false}")
    private boolean single;


    public ClientListener(Tunnel tunnel, String username, String password) {
        this.port = tunnel.getPort();
        this.target = tunnel.getTarget();

        this.username = username;
        this.password = password;
        this.server = tunnel.getServer();

    }

    private void send(WebSocketSession session, ByteBuffer msg) {
        try {
            session.sendMessage(new BinaryMessage(msg));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    public void run() {


        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(Constants.USERNAME_KEY, username);
        headers.add(Constants.PASSWORD_KEY, password);
        headers.add(Constants.TARGET_KEY, target);

        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(new AbstractWebSocketHandler() {


            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {

                new Thread(() -> {
                    try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
                        ssc.socket().bind(new InetSocketAddress("localhost", port));
                        LOGGER.info("绑定 localhost:{} -> {} on {}", port, target, server);


                        while (!Thread.currentThread().isInterrupted()) {

                            SocketChannel socketChannel = ssc.accept();
                            socketChannel.configureBlocking(false);
                            LOGGER.info("New connection received");


                            session.getAttributes().put(Constants.SOCKET_CHANNEL_KEY, socketChannel);

                            while (true) {

                                ByteBuffer bb = ByteBuffer.allocate(8192);

                                int read = 0;
                                try {
                                    read = socketChannel.read(bb);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                if (read < 0) {
                                    break;
                                }
                                send(session, bb);
                                log.info("send(session, bb)");
                            }

                        }


                    } catch (IOException e) {
                        LOGGER.error("Error in listener loop", e);
                    }
                }).start();

            }

            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                log.info("接收消息:{}", message.getPayload());
            }

            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
                log.info("接收二进制消息");
                ByteBuffer payload = message.getPayload();

                while (true){
                    try{
                        SocketChannel socketChannel = (SocketChannel) session.getAttributes().get(Constants.SOCKET_CHANNEL_KEY);

                        if (socketChannel != null) {
                            socketChannel.write(payload);
                            break;
                        } else {
                            log.info("接收到消息 ,req:{}", payload);
                        }
                    }catch (Exception e){
                        log.error("", e);
                    }
                }

            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                LOGGER.error("handleTransportError", exception);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                LOGGER.info("解除绑定 localhost:{} -> {} on {} code:{}", port, target, server, closeStatus);
            }

        }, headers, URI.create(server));


    }


}
