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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.PrivateKey;
import java.util.Iterator;

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
            byte[] bytes = new byte[msg.remaining()];
            msg.get(bytes);
            String request = new String(bytes).trim();
            System.out.println("发送请求：" + request);

            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    public void run() {

        Selector selector = Selector.open();
        ServerSocketChannel localServerSocketChannel = ServerSocketChannel.open();
        localServerSocketChannel.bind(new InetSocketAddress(port));
        localServerSocketChannel.configureBlocking(false);
        localServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT);


        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(Constants.USERNAME_KEY, username);
        headers.add(Constants.PASSWORD_KEY, password);
        headers.add(Constants.TARGET_KEY, target);


        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(new AbstractWebSocketHandler() {

            SocketChannel socketChannel = null;

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {

                new Thread(() -> {
                    try {

                        LOGGER.info("绑定 localhost:{} -> {} on {}", port, target, server);

                        while (true) {
                            selector.select();

                            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                            while (iterator.hasNext()) {
                                SelectionKey key = iterator.next();
                                iterator.remove();

                                if (key.isAcceptable()) {

                                    // 本地 ServerSocketChannel 接受连接请求
                                    ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();

                                    SocketChannel localSocketChannel = serverSocketChannel.accept();
                                    localSocketChannel.configureBlocking(false);

                                    socketChannel = localSocketChannel;

                                    // 连接远程 MySQL 服务器
                                    // 注册本地 SocketChannel 和远程 SocketChannel 到 Selector 中

                                    localSocketChannel.register(selector, SelectionKey.OP_READ, ByteBuffer.allocate(8192));
                                    System.out.println("客户端连接成功：" + localSocketChannel.getRemoteAddress());

                                } else if (key.isReadable()) {
                                    // 读取数据并转发到对应的 SocketChannel 中
                                    SocketChannel socketChannel = (SocketChannel) key.channel();

                                    ByteBuffer buffer = (ByteBuffer) key.attachment();
                                    buffer.clear();

                                    try {
                                        LOGGER.info("socketChannel.read 1");
                                        int bytesRead = socketChannel.read(buffer);
                                        LOGGER.info("socketChannel.read 2");

                                        if (bytesRead == -1) {
                                            // 关闭连接
                                            socketChannel.close();
                                            LOGGER.error("socketChannel.close()");
                                            return;
                                        } else {
                                            buffer.flip();

                                            LOGGER.info("send(session, buffer)");
                                            send(session, buffer);

                                        }
                                    } catch (Exception e) {
                                        LOGGER.error(" ", e);
                                    }

                                }
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
//                byte[] bytes = new byte[payload.remaining()];
//                payload.get(bytes);
//                String request = new String(bytes).trim();
//                System.out.println("接收请求：" + request);
                while (true) {
                    try {
                        if (socketChannel != null) {
                            log.info("返回消息:{}", payload);
                            socketChannel.write(payload);
                            //socketChannel.register(selector, SelectionKey.OP_WRITE, payload);
                            break;
                        } else {
                            log.info("接收到消息 ,req:{}", payload);
                            Thread.sleep(100);
                        }
                    } catch (Exception e) {
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
