package com.dutertry.htunnel.server.service;


import com.dutertry.htunnel.common.Constants;
import com.dutertry.htunnel.server.config.AuthConfig;
import com.dutertry.htunnel.server.config.ConnConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

@Component
@Slf4j
public class WebSocketHandler extends AbstractWebSocketHandler {

    @Resource
    ConnConfig config;

    @Resource
    AuthConfig authConfig;


    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //socket连接成功后触发
        log.info("建立websocket连接");


//        WebSocketSessionManager.add(session.getId(), session);

        HttpHeaders handshakeHeaders = session.getHandshakeHeaders();
        String username = handshakeHeaders.getFirst("username");
        String password = handshakeHeaders.getFirst("password");
        Map<String, String> user = authConfig.getUser();

        if (ObjectUtils.isEmpty(password) || !Objects.equals(user.get(username), password)) {
            log.error("username password not in permit list username:{} password:{}", username, password);
            send(session, "[error]unAuth");
            session.close(CloseStatus.NOT_ACCEPTABLE);
        }


        String target = handshakeHeaders.getFirst("target");


        Map<String, String> map = config.getMap();
        String addrInfo = map.get(target);
        if (ObjectUtils.isEmpty(addrInfo)) {
            log.error("unknownTarget:{}", target);
            send(session, "[error]unknownTarget:" + target);
            session.close(CloseStatus.NOT_ACCEPTABLE);
        }

        String[] split = addrInfo.trim().split(":");
        InetSocketAddress inetSocketAddress = new InetSocketAddress(split[0], Integer.parseInt(split[1]));

        Map<String, Object> attributes = session.getAttributes();

        attributes.put("username", username);

        log.info("handshakeHeaders {}", handshakeHeaders);

        Selector selector = Selector.open();

        log.info("连接 ip:{} -> {}", session.getRemoteAddress(), inetSocketAddress);
        SocketChannel remoteSocketChannel = SocketChannel.open();
        remoteSocketChannel.connect(inetSocketAddress);
        remoteSocketChannel.configureBlocking(false);
        remoteSocketChannel.register(selector, SelectionKey.OP_READ);
        session.getAttributes().put(Constants.SOCKET_CHANNEL_KEY, remoteSocketChannel);

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
                            } else {
                                buffer.flip();
                                send(session, buffer);
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


        // 客户端发送普通文件信息时触发
        log.info("发送文本消息");
        // 获得客户端传来的消息
        String payload = message.getPayload();


        if ("ping".equals(payload)) {
            send(session, "pong");
            return;
        }

        Object username = session.getAttributes().get("username");

        log.info("服务端接收到消息 uid:{},req:{}", username, payload);


    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        //客户端发送二进信息是触发
        log.info("接收二进制消息");
        ByteBuffer payload = message.getPayload();
//        byte[] bytes = new byte[payload.remaining()];
//        payload.get(bytes);
//        String request = new String(bytes).trim();
//        System.out.println("接收：" + request);
        SocketChannel socketChannel = (SocketChannel) session.getAttributes().get(Constants.SOCKET_CHANNEL_KEY);
        if (socketChannel != null) {
            try {
                socketChannel.write(payload);
            }catch (ClosedChannelException e){

            }catch (Exception e) {
                log.error(" ", e);
            }
        } else {
            log.info("接收到消息 ,req:{}", payload);
        }


    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        //异常时触发
        log.error("异常处理", exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // socket连接关闭后触发
        log.info("关闭websocket连接");
    }

    private void send(WebSocketSession session, String msg) {
        try {
            session.sendMessage(new TextMessage(msg));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void send(WebSocketSession session, ByteBuffer msg) {
        try {
            log.info("send:{}", msg);
            byte[] bytes = new byte[msg.remaining()];
            msg.get(bytes);
            String request = new String(bytes).trim();
            System.out.println("send：" + request);
            session.sendMessage(new BinaryMessage(ByteBuffer.wrap(bytes)));

        } catch (IOException e) {
            log.error("", e);
        }
    }

    private static final int LOCAL_PORT = 3000;
    private static final String REMOTE_HOST = "192.168.0.25";
    private static final int REMOTE_PORT = 3306;

    public static void main(String[] args) throws IOException {
        Selector selector = Selector.open();

        // 创建本地 ServerSocketChannel，监听 3000 端口
        ServerSocketChannel localServerSocketChannel = ServerSocketChannel.open();
        localServerSocketChannel.bind(new InetSocketAddress(LOCAL_PORT));
        localServerSocketChannel.configureBlocking(false);
        localServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

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

                    // 连接远程 MySQL 服务器
                    //SocketChannel remoteSocketChannel = SocketChannel.open();
                    //remoteSocketChannel.connect(new InetSocketAddress(REMOTE_HOST, REMOTE_PORT));
                    //remoteSocketChannel.configureBlocking(false);

                    // 注册本地 SocketChannel 和远程 SocketChannel 到 Selector 中
                    localSocketChannel.register(selector, SelectionKey.OP_READ,ByteBuffer.allocate(8192));
                    //remoteSocketChannel.register(selector, SelectionKey.OP_READ, localSocketChannel);
                } else if (key.isReadable()) {
                    // 读取数据并转发到对应的 SocketChannel 中
                    SocketChannel socketChannel = (SocketChannel) key.channel();
                    //SocketChannel targetSocketChannel = (SocketChannel) key.attachment();

                    ByteBuffer buffer = ByteBuffer.allocate(1024);

                    int bytesRead = socketChannel.read(buffer);
                    if (bytesRead == -1) {
                        // 关闭连接
                        socketChannel.close();
                       // targetSocketChannel.close();
                    } else {
                        buffer.flip();
                        System.out.println(buffer);
                        //targetSocketChannel.write(buffer);
                    }
                }
            }
        }
    }

}