package com.dutertry.htunnel.server.service;


import com.dutertry.htunnel.common.Constants;
import com.dutertry.htunnel.server.config.AuthConfig;
import com.dutertry.htunnel.server.config.ResourceConfig;
import com.dutertry.htunnel.server.utils.LogUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
public class WebSocketHandler extends AbstractWebSocketHandler {

    @Resource
    ResourceConfig config;

    @Resource
    AuthConfig authConfig;


    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //socket连接成功后触发

        HttpHeaders handshakeHeaders = session.getHandshakeHeaders();
        String username = handshakeHeaders.getFirst(Constants.USERNAME_KEY);
        String password = handshakeHeaders.getFirst(Constants.PASSWORD_KEY);
        Map<String, String> user = authConfig.getUser();

        if (ObjectUtils.isEmpty(password) || !Objects.equals(user.get(username), password)) {
            log.error("账号密码不在允许列表 username:{} password:{}", username, password);
            safeSend(session, Constants.UN_AUTH_MSG);
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        MDC.put(Constants.TRACE_ID_KEY, username);

        String resource = handshakeHeaders.getFirst(Constants.RESOURCE_KEY);

        Map<String, String> map = config.getMap();
        String addrInfo = map.get(resource);
        if (ObjectUtils.isEmpty(addrInfo)) {
            log.error("找不到资源:{}", resource);
            safeSend(session, Constants.ERR_MSG_PRE + "unknownResource:" + resource);
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        String[] split = addrInfo.trim().split(":");
        InetSocketAddress inetSocketAddress = new InetSocketAddress(split[0], Integer.parseInt(split[1]));

        Map<String, Object> attributes = session.getAttributes();

        attributes.put(Constants.USERNAME_KEY, username);

        log.info("handshakeHeaders {}", handshakeHeaders);

        Selector selector = Selector.open();

        log.info("连接 ip:{} -> {}", session.getRemoteAddress(), inetSocketAddress);
        SocketChannel remoteSocketChannel = SocketChannel.open();
        remoteSocketChannel.connect(inetSocketAddress);
        remoteSocketChannel.configureBlocking(false);
        remoteSocketChannel.register(selector, SelectionKey.OP_READ);
        setSocketChannel(session, remoteSocketChannel);

        //noinspection AlibabaAvoidManuallyCreateThread
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
                            int bytesRead = socketChannel.read(buffer);

                            if (bytesRead == -1) {
                                // 关闭连接
                                socketChannel.close();
                                session.close();
                            } else {
                                buffer.flip();
                                safeSend(session, buffer);
                            }
                        }
                    }
                }


            } catch (IOException e) {
                log.error("Error in listener loop", e);
            }

        }).start();

    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        LogUtils.setTrace(session);

        // 客户端发送普通文件信息时触发
        log.debug("收到文本消息");
        // 获得客户端传来的消息
        String payload = message.getPayload();

        if ("ping".equals(payload)) {
            safeSend(session, "pong");
            return;
        }


        log.info("服务端接收到消息 req:{}", payload);


    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        LogUtils.setTrace(session);

        //客户端发送二进信息是触发
        log.debug("接收二进制消息");
        ByteBuffer payload = message.getPayload();
        if (log.isDebugEnabled()) {
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            String request = new String(bytes).trim();
            log.debug("接收：" + request);
        }

        Optional<SocketChannel> socketChannel = getSocketChannel(session);

        if (socketChannel.isPresent()) {
            socketChannel.get().write(payload);
        } else {
            log.info("接收到消息 ,req:{}", payload);
        }


    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        LogUtils.setTrace(session);
        //异常时触发
        log.error("WebSocket异常", exception);
        Optional<SocketChannel> socketChannel = getSocketChannel(session);
        if (socketChannel.isPresent()) {
            socketChannel.get().close();
        }
        session.close();

    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        LogUtils.setTrace(session);
        // socket连接关闭后触发
        log.info("关闭websocket连接");
    }

    private synchronized void safeSend(WebSocketSession session, Object msg) {
        if (msg instanceof String) {
            send(session, (String) msg);
        } else if (msg instanceof ByteBuffer) {
            send(session, (ByteBuffer) msg);
        }
    }
    private void send(WebSocketSession session, String msg) {
        try {
            session.sendMessage(new TextMessage(msg));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void send(WebSocketSession session, ByteBuffer msg) {
        LogUtils.setTrace(session);
        try {
            LogUtils.setTrace(session);
            log.debug("send:{}", msg);
            byte[] bytes = new byte[msg.remaining()];
            msg.get(bytes);
            if (log.isDebugEnabled()) {
                String request = new String(bytes).trim();
                System.out.println("send：" + request);
            }
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));
        } catch (IOException e) {
            log.error("", e);
        }
    }

    Optional<SocketChannel> getSocketChannel(WebSocketSession session) {
        return Optional.ofNullable((SocketChannel) session.getAttributes().get(Constants.SOCKET_CHANNEL_KEY));
    }

    void setSocketChannel(WebSocketSession session, SocketChannel socketChannel) {
        session.getAttributes().put(Constants.SOCKET_CHANNEL_KEY, socketChannel);
    }


}