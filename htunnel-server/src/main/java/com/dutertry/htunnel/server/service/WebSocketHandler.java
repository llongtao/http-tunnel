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
import java.nio.channels.SocketChannel;
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


        Map<String, Object> attributes = session.getAttributes();

        attributes.put("username", username);

        log.info("handshakeHeaders {}", handshakeHeaders);

        new Thread(() -> {

            try {
                while (true) {
                    SocketChannel socketChannel = SocketChannel.open();
                    SocketAddress socketAddr = new InetSocketAddress(split[0], Integer.parseInt(split[1]));
                    socketChannel.configureBlocking(true);
                    socketChannel.connect(socketAddr);
                    attributes.put(Constants.SOCKET_CHANNEL_KEY, socketChannel);
                    while (true) {
                        ByteBuffer bb = ByteBuffer.allocate(8192);
                        try {
                            int read = socketChannel.read(bb);
                            if (read < 0) {
                                break;
                            }
                        } catch (IOException e) {
                            log.error("",e);
                            break;
                        }
                        send(session, bb);
                        log.info("send(session, bb)");
                    }
                }


            } catch (Exception e) {
                log.error("e", e);
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
        log.info("发送二进制消息");
        ByteBuffer payload = message.getPayload();
        while (true){
            SocketChannel socketChannel = (SocketChannel) session.getAttributes().get(Constants.SOCKET_CHANNEL_KEY);
            if (socketChannel != null) {
                try{
                    socketChannel.write(payload);
                }catch (Exception e){
                    log.error(" ", e);
                }


            } else {
                log.info("接收到消息 ,req:{}", payload);
            }
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
            session.sendMessage(new BinaryMessage(msg));
        } catch (IOException e) {
            log.error("",e);
        }
    }

    public static void main(String[] args) throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        SocketAddress socketAddr = new InetSocketAddress("192.168.0.25", 3306);
        socketChannel.configureBlocking(true);
        socketChannel.connect(socketAddr);
        ByteBuffer bb = ByteBuffer.allocate(8192);
        while (true) {
            int read = socketChannel.read(bb);
            System.out.println(read);
        }


    }

}