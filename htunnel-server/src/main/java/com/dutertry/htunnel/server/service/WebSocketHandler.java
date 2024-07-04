package com.dutertry.htunnel.server.service;


import com.alibaba.fastjson.JSON;
import com.dutertry.htunnel.common.model.WsMessage;
import com.dutertry.htunnel.common.utils.ByteBufUtils;
import com.dutertry.htunnel.server.config.AuthConfig;
import com.dutertry.htunnel.server.config.ResourceConfig;
import com.dutertry.htunnel.server.conn.EndpointConnection;
import com.dutertry.htunnel.server.conn.EndpointConnectionManager;
import com.dutertry.htunnel.server.session.WsSession;
import com.dutertry.htunnel.server.session.WsSessionManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ObjectUtils;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.Objects;


@Slf4j
public class WebSocketHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private final AuthConfig authConfig;

    public WebSocketHandler(AuthConfig authConfig, ResourceConfig resourceConfig) {
        this.authConfig = authConfig;
        EndpointConnectionManager.setResource(resourceConfig);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame msg) throws Exception {
        // 处理收到的WebSocket消息
        byte[] array = ByteBufUtils.byteBufToByteArray(msg.content());

        ByteArrayInputStream bis = new ByteArrayInputStream(array);
        ObjectInputStream is = new ObjectInputStream(bis);
        WsMessage wsMessage = (WsMessage) is.readObject();

        String type = wsMessage.getType();

        if ("auth".equals(type)) {

            String username = wsMessage.getUsername();
            String password = wsMessage.getPassword();

            String pas = authConfig.getUser().get(username);
            if (Objects.equals(password, pas)) {
                WsSessionManager.registerSession(username, ctx);
                log.info("success registerSession {}", username);
            } else {
                log.info("username password error {}", username);
                WsMessage error = new WsMessage();
                error.setType("error");
                error.setMessage("账号或密码错误");
                new WsSession(ctx,username).sendClient(JSON.toJSONString(error));
                ctx.close();
            }
        } else if ("message".equals(type)) {
            WsSession wsSession = WsSessionManager.getSession(ctx);
            if (ObjectUtils.isEmpty(wsSession)) {
                ctx.close();
                return;
            }

            String resource = wsMessage.getResource();
            String connectionId = wsMessage.getConnectionId();
            byte[] data = wsMessage.getData();
            EndpointConnection endpointConnection;
            try {
                endpointConnection = EndpointConnectionManager.getConnection(wsSession, connectionId, resource);
            } catch (Exception e) {
                log.error("getEndpointConnection error", e);
                return;
            }
            endpointConnection.toServer(data);
        } else {
            ctx.close();
        }


    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 新的WebSocket连接建立时调用
        log.info("WebSocket client connected");

    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // WebSocket连接断开时调用
        log.info("WebSocket client disconnected");
        WsSessionManager.removeSession(ctx);

    }
}
