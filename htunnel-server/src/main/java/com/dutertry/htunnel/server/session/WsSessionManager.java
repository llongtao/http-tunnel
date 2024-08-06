package com.dutertry.htunnel.server.session;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author lilongtao 2024/7/2
 */
public class WsSessionManager {

    private static final Map<String, WsSession> SESSION_MAP = new ConcurrentHashMap<>();

    public static void registerSession(String username, ChannelHandlerContext ctx) {
        SESSION_MAP.put(username, new WsSession(ctx,username));
    }

    public static void removeSession(ChannelHandlerContext ctx) {
        Object username = getUsername(ctx);
        if (username == null) {
            return;
        }
        SESSION_MAP.remove(username.toString());
    }

    public static WsSession getSession(ChannelHandlerContext ctx) {
        String username = getUsername(ctx);
        if (username == null) {
            return null;
        }
        return SESSION_MAP.get(username);
    }

    public static String getUsername(ChannelHandlerContext ctx) {
        Object username = ctx.channel().attr(AttributeKey.valueOf("user")).get();
        if (username == null) {
            return null;
        }
        return username.toString();
    }
}
