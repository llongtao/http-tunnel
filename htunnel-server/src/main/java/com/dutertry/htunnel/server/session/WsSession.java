package com.dutertry.htunnel.server.session;

import cn.hutool.core.util.ObjectUtil;
import com.dutertry.htunnel.common.model.WsMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

/**
 * @author lilongtao 2024/7/2
 */
@Slf4j
public class WsSession {

    private ChannelHandlerContext context;

    public WsSession(ChannelHandlerContext ctx, String username) {
        if (ctx == null) {
            return;
        }
        ctx.channel().attr(AttributeKey.valueOf("user")).set(username);
        context = ctx;
    }

    public ChannelHandlerContext getContext() {
        return context;
    }

    public String getUsername() {
        if (context == null) {
            return null;
        }
        Object username = context.channel().attr(AttributeKey.valueOf("user")).get();
        if (username == null) {
            return null;
        }
        return username.toString();
    }

    public void sendClient(String connectionId, byte[] bytes) {
        try {

            WsMessage wsMessage = new WsMessage();
            wsMessage.setData(bytes);
            wsMessage.setConnectionId(connectionId);
            wsMessage.setType("message");
            log.info("send to client: {}",wsMessage.getData().length);
            ByteBuf bf = Unpooled.wrappedBuffer(ObjectUtil.serialize(wsMessage));
            context.writeAndFlush(new BinaryWebSocketFrame(bf));
        } catch (Exception e) {
            log.error("消息发送失败", e);
        }
    }
    public void sendClient(String data) {
        try {
            context.writeAndFlush(new TextWebSocketFrame(data));
        } catch (Exception e) {
            log.error("消息发送失败", e);
        }
    }

    public void close() {
        context.close();
    }
}
