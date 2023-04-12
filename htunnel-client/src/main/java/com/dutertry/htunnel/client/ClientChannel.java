package com.dutertry.htunnel.client;

import com.dutertry.htunnel.client.config.Tunnel;
import com.dutertry.htunnel.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;
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


        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();


        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        //webSocketClient.setUserProperties(Collections.singletonMap(StandardWebSocketClient.HEADER_SEC_WEBSOCKET_PROTOCOL, "wss"));


        webSocketClient.execute(new AbstractWebSocketHandler() {


            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {

                //noinspection AlibabaAvoidManuallyCreateThread
                new Thread(() -> {
                    try {
                        while (session.isOpen()) {
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
                                            safeSend(session, buffer);
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

                //noinspection AlibabaAvoidManuallyCreateThread
                new Thread(() -> {
                    while (session.isOpen()) {
                        safeSend(session, "ping");
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            log.error("", e);
                        }
                    }
                }).start();

            }


            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                String payload = message.getPayload();
                log.debug("接收消息:{}", payload);
                if (Constants.UN_AUTH_MSG.equals(payload)) {
                    log.error("用户名或密码不正确");
                } else if (payload.startsWith(Constants.ERR_MSG_PRE)) {
                    log.error("连接跳板机失败:" + payload);
                }
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
                log.info("断开连接 on {} code:{}", resource, closeStatus);
                socketChannel.close();
            }

        }, headers, URI.create(server));
    }

    private synchronized void safeSend(WebSocketSession session, Object msg) {
        if (msg instanceof String) {
            send(session, (String) msg);
        } else if (msg instanceof ByteBuffer) {
            send(session, (ByteBuffer) msg);
        }
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
            log.error("发送远程请求失败", e);
        }
    }

    private void send(WebSocketSession session, String msg) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("发送远程请求：{}", msg);
            }
            session.sendMessage(new TextMessage(msg));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
