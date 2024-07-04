package com.dutertry.htunnel.server.config;


import com.dutertry.htunnel.server.service.WebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

@Slf4j
@Configuration
public class WebSocketConfig {
    @Value("${websocket.port:8081}")
    private int port;

    @Resource
    AuthConfig authConfig;
    @Resource
    ResourceConfig resourceConfig;


    @Component
    public class WebSocketServer {
        private EventLoopGroup bossGroup;
        private EventLoopGroup workerGroup;
        private ServerBootstrap serverBootstrap;

        @PostConstruct
        public void start() {
            bossGroup = new NioEventLoopGroup(8);
            workerGroup = new NioEventLoopGroup();
            serverBootstrap = new ServerBootstrap();

            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .localAddress(new InetSocketAddress(port))
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast("idleStateHandler", new IdleStateHandler(60, 0, 0));
                            pipeline.addLast(new HttpObjectAggregator(65536000));
                            pipeline.addLast(new WebSocketServerProtocolHandler("/websocket/message",null,true,65536000));
//                            pipeline.addLast(new ObjectToByteEncoder());
//                            pipeline.addLast(new ByteToObjectDecoder());
                            pipeline.addLast(new WebSocketHandler(authConfig,resourceConfig));
                        }
                    })
                    //子处理器处理客户端连接的请求和数据
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    //服务端接受连接的队列长度，如果队列已满，客户端连接将被拒绝
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true); ;

            ChannelFuture channelFuture = serverBootstrap.bind().syncUninterruptibly();

            log.info("WebSocket server started on port " + port);
            channelFuture.channel().closeFuture().addListener(future -> {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            });
        }

        @PreDestroy
        public void stop() {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            log.info("WebSocket server stopped");
        }
    }
}
