package com.dutertry.htunnel.common.model;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.List;

public class ByteToObjectDecoder extends ByteToMessageDecoder {
 
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBuf byteBuf = in.readBytes(in.readableBytes());
        byte[] data = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(data);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream is = new ObjectInputStream(bis);
        Object o = is.readObject();
        out.add(o);
    }
}