package com.dutertry.htunnel.client.client;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSON;
import com.dutertry.htunnel.client.app.AppConnection;
import com.dutertry.htunnel.client.app.AppConnectionManager;
import com.dutertry.htunnel.common.model.WsMessage;
import com.dutertry.htunnel.common.utils.ByteBufUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.URI;

/**
 * @author lilongtao 2024/7/2
 */
@Slf4j
public class TunnelClient {

    private final URI uri;
    private int port;
    private final SslContext sslCtx;

    private Channel channel;


    private String username;
    private String password;


    public TunnelClient(String url, String username, String password) throws Exception {
        this.username = username;
        this.password = password;
        this.uri = new URI(url);
        this.port = uri.getPort();
        String scheme = uri.getScheme() == null ? "ws" : uri.getScheme();
        final boolean ssl = "wss".equalsIgnoreCase(scheme);
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            if (this.port < 0) {
                this.port = 443;
            }
        } else {
            sslCtx = null;
            if (this.port < 0) {
                this.port = 80;
            }
        }
        connect();
    }

    @SuppressWarnings("AlibabaAvoidManuallyCreateThread")
    public void connect() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();

        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslCtx != null) {
                            p.addLast(sslCtx.newHandler(ch.alloc(), uri.getHost(), port));
                        }
                        p.addLast(new HttpClientCodec(),
                                new HttpObjectAggregator(65536000),
                                new WebSocketClientProtocolHandler(
                                        WebSocketClientHandshakerFactory.newHandshaker(
                                                uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders(), 65536000)),
                                new WebSocketClientHandler());
                    }
                });


        channel = b.connect(uri.getHost(), port).sync().channel();
        WebSocketClientHandler handler = channel.pipeline().get(WebSocketClientHandler.class);

        WsMessage wsMessage = new WsMessage();
        wsMessage.setUsername(username);
        wsMessage.setPassword(password);
        wsMessage.setType("auth");
        ByteBuf byteBuf = Unpooled.wrappedBuffer(ObjectUtil.serialize(wsMessage));
        log.info("开始连接到远程服务:{}", uri);
        Thread.sleep(1000);
        channel.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
        log.info("连接到远程服务:{}", uri);
        new Thread(() -> {
            // 执行 WebSocket 握手
            try {
                handler.handshakeFuture().sync();
                channel.closeFuture().sync();
            } catch (InterruptedException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                // 释放资源
                group.shutdownGracefully();

            }
        }).start();


    }


    public void sendServer(String id, String resource, byte[] data) throws Exception {
        if (!channel.isActive()) {
            connect();
        }

        WsMessage wsMessage = new WsMessage();
        wsMessage.setData(data);
        wsMessage.setType("message");
        wsMessage.setConnectionId(id);
        wsMessage.setResource(resource);
        ByteBuf byteBuf = Unpooled.wrappedBuffer(ObjectUtil.serialize(wsMessage));

        channel.writeAndFlush(new BinaryWebSocketFrame(byteBuf));
        log.info("send to server: {} data:{} success", id, data.length);
    }


    private class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
        private ChannelPromise handshakeFuture;

        public ChannelFuture handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            handshakeFuture = ctx.newPromise();
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, Object msg) throws IOException, ClassNotFoundException {
            if (msg instanceof FullHttpResponse) {
                FullHttpResponse response = (FullHttpResponse) msg;
                throw new IllegalStateException(
                        "Unexpected FullHttpResponse (getStatus=" + response.status() + ", content=" + response.content().toString(CharsetUtil.UTF_8) + ')');
            } else if (msg instanceof TextWebSocketFrame) {
                TextWebSocketFrame frame = (TextWebSocketFrame) msg;
                WsMessage wsMessage = JSON.parseObject(frame.text(), WsMessage.class);
                log.error("{}连接错误:{}", uri, wsMessage.getMessage());
                String connectionId = wsMessage.getConnectionId();
                AppConnection appConnection = AppConnectionManager.getAppConnection(connectionId);
                if (appConnection != null) {
                    appConnection.close();
                }
            } else if (msg instanceof BinaryWebSocketFrame) {
                BinaryWebSocketFrame frame = (BinaryWebSocketFrame) msg;

                byte[] array = ByteBufUtils.byteBufToByteArray(frame.content());

                ByteArrayInputStream bis = new ByteArrayInputStream(array);
                ObjectInputStream is = new ObjectInputStream(bis);
                WsMessage wsMessage = (WsMessage) is.readObject();

                String connectionId = wsMessage.getConnectionId();

                AppConnection appConnection = AppConnectionManager.getAppConnection(connectionId);
                if (appConnection != null) {
                    appConnection.onServerData(wsMessage.getData());
                }
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent) {
                WebSocketClientProtocolHandler.ClientHandshakeStateEvent handshakeEvent = (WebSocketClientProtocolHandler.ClientHandshakeStateEvent) evt;
                if (handshakeEvent == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                    handshakeFuture.setSuccess();
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }


}
