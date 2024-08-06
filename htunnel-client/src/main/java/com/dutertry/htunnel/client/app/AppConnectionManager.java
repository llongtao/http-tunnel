package com.dutertry.htunnel.client.app;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * @author lilongtao 2024/7/3
 */
@Slf4j
public class AppConnectionManager {
    private static Map<String, AppConnection> appConnectionMap = new HashMap<>();


    public static String register(ChannelHandlerContext ctx){
        String id = (String) ctx.attr(AttributeKey.valueOf("id")).get();
        AppConnection appConnection = appConnectionMap.get(id);
        if (appConnection != null) {
            return id;
        }
        appConnection = new AppConnection(ctx);
        appConnectionMap.put(appConnection.getId(),appConnection);
        return appConnection.getId();
    }

    public static AppConnection getAppConnection(String id) {
        return appConnectionMap.get(id);
    }


    public static void removeAppConnection(ChannelHandlerContext ctx) {
        AppConnection appConnection = new AppConnection(ctx);
        appConnectionMap.remove(appConnection.getId());
    }
}
