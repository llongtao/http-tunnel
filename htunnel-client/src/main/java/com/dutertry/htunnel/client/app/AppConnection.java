package com.dutertry.htunnel.client.app;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * @author lilongtao 2024/7/3
 */
@Slf4j
public class AppConnection {
    private ChannelHandlerContext context;
    private String id;

    public AppConnection(ChannelHandlerContext context) {
        String id = (String) context.attr(AttributeKey.valueOf("id")).get();
        if (id == null) {
            id = UUID.randomUUID().toString();
            context.attr(AttributeKey.valueOf("id")).set(id);
        }
        this.id = id;
        this.context = context;
    }


    public String getId() {
        return id;
    }

    public void onServerData(byte[] data) {
        context.writeAndFlush(data);
        log.info("AppConnection received {} data:{}", id, data.length);
    }

    public boolean isClose() {
        return this.context.isRemoved();
    }

    public void close() {
        this.context.close();
    }
}
