package com.dutertry.htunnel.client;

import com.dutertry.htunnel.client.config.Tunnel;
import com.dutertry.htunnel.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * @author lilongtao 2023/4/12
 */
@Slf4j
public class ClientChannel {


    private String resource;

    private String server;

    private Integer port;

    private String username;

    private String password;

    private final SocketChannel socketChannel;

    private final Selector selector;

    public ClientChannel(SocketChannel socketChannel, Tunnel tunnel, String username, String password) {
        this.socketChannel = socketChannel;


        try {
            selector = Selector.open();
            socketChannel.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        this.resource = tunnel.getResource();
        this.username = username;
        this.password = password;
        this.server = tunnel.getServer();
        this.port = tunnel.getPort();


    }

    public void run() {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(Constants.USERNAME_KEY, username);
        headers.add(Constants.PASSWORD_KEY, password);
        headers.add(Constants.RESOURCE_KEY, resource);


        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketClient.execute(new AbstractWebSocketHandler() {


            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {

                new Thread(() -> {
                    try {
                        while (true) {
                            selector.select();

                            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                            while (iterator.hasNext()) {
                                SelectionKey key = iterator.next();
                                iterator.remove();

                                if (key.isReadable()) {
                                    // 读取数据并转发到对应的 SocketChannel 中
                                    SocketChannel socketChannel = (SocketChannel) key.channel();

                                    ByteBuffer buffer = ByteBuffer.allocate(8192);
                                    //buffer.clear();

                                    try {
                                        int bytesRead = socketChannel.read(buffer);
                                        if (bytesRead == -1) {
                                            // 关闭连接
                                            log.info("客户端断开连接: {}", socketChannel.getRemoteAddress());
                                            socketChannel.close();
                                            session.close();
                                            return;
                                        } else {
                                            buffer.flip();
                                            log.debug("send(session, buffer)");
                                            send(session, buffer);
                                        }
                                    } catch (SocketException e) {
                                        log.error(" ", e);
                                        key.cancel();
                                    }

                                }
                            }
                        }


                    } catch (IOException e) {
                        log.error("Error in listener loop", e);
                    }
                }).start();

            }


            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                log.info("接收消息:{}", message.getPayload());
            }

            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
                log.debug("接收二进制消息");
                ByteBuffer payload = message.getPayload();
//                byte[] bytes = new byte[payload.remaining()];
//                payload.get(bytes);
//                String request = new String(bytes).trim();
//                System.out.println("接收请求：" + request);


                try {
                    if (socketChannel != null) {
                        log.debug("返回消息:{}", payload);
                        socketChannel.write(payload);
                        //socketChannel.register(selector, SelectionKey.OP_WRITE, payload);
                    } else {
                        log.debug("接收到消息 ,req:{}", payload);
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    log.error("", e);
                }


            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
                log.error("handleTransportError", exception);
                socketChannel.close();
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                log.info("解除绑定 localhost:{} -> {} on {} code:{}", port, resource, server, closeStatus);
                socketChannel.close();
            }

        }, headers, URI.create(server));
    }

    private void send(WebSocketSession session, ByteBuffer msg) {
        try {
            byte[] bytes = new byte[msg.remaining()];
            msg.get(bytes);

            if (log.isDebugEnabled()) {
                String request = new String(bytes).trim();
                log.debug("发送远程请求：" + request);
            }
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
