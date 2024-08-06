package com.dutertry.htunnel.common.utils;

import com.alibaba.fastjson.JSON;
import com.dutertry.htunnel.common.model.WsMessage;
import io.netty.buffer.ByteBuf;

/**
 * @author lilongtao 2024/7/3
 */
public class ByteBufUtils {
    public static byte[] byteBufToByteArray(ByteBuf buf) {
        byte[] bytes;
        if (buf.hasArray()) {
            bytes = buf.array();
        } else {
            bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
        }
        return bytes;
    }

    public static void main(String[] args) {

        WsMessage wsMessage = new WsMessage();
        wsMessage.setData(new byte[]{1,2});

        System.out.println(JSON.toJSONString(wsMessage));;
    }
}
