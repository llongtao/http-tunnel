package com.dutertry.htunnel.server.conn;

import com.dutertry.htunnel.server.session.WsSession;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * @author lilongtao 2024/7/2
 */
@Slf4j
public class EndpointConnection {

    private InetSocketAddress inetSocketAddress;

    ChannelFuture future;

    //配置NIO线程组
    EventLoopGroup group = new NioEventLoopGroup();
    Bootstrap b = new Bootstrap();

    ChannelHandlerContext channelHandlerContext;

    public EndpointConnection(String connectionId, WsSession wsSession, InetSocketAddress inetSocketAddress) throws InterruptedException {
        this.inetSocketAddress = inetSocketAddress;

        log.info("连接 user:{} -> {}", wsSession.getUsername(), inetSocketAddress);


        //启动类

        //绑定线程组
        b.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast(new ByteArrayDecoder());
                        pipeline.addLast(new ByteArrayEncoder());
                        pipeline.addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                channelHandlerContext = ctx;
                                super.channelActive(ctx);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                log.info("channelInactive");
                                EndpointConnectionManager.removeConnection(connectionId);
                                super.channelInactive(ctx);
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object s) throws Exception {
                                channelHandlerContext = ctx;
                                wsSession.sendClient(connectionId, (byte[]) s);
                                super.channelRead(ctx, s);
                            }


                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                cause.printStackTrace();
                                super.exceptionCaught(ctx, cause);
                            }
                        });
                    }
                });


    }

    public void toServer(byte[] data) {

        if (channelHandlerContext == null) {
            connectSync();
        }
        log.info("send to server:{}", data.length);

        //发送消息
        channelHandlerContext.writeAndFlush(data);
    }


    @SuppressWarnings("AlibabaAvoidManuallyCreateThread")
    private void connectSync() {
        //连接到服务端
        try {
            future = b.connect(inetSocketAddress).sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }


        new Thread(() -> {
            try {
                //等待客户端链路关闭
                future.channel().closeFuture().sync();
                future = null;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                //优雅的退出，释放NIO线程组
                group.shutdownGracefully();
            }
        }).start();

    }


}
