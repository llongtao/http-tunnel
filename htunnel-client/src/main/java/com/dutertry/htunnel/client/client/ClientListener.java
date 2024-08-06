/*
 * htunnel - A simple HTTP tunnel
 * https://github.com/nicolas-dutertry/htunnel
 *
 * Written by Nicolas Dutertry.
 *
 * This file is provided under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.dutertry.htunnel.client.client;

import com.dutertry.htunnel.client.app.AppConnectionManager;
import com.dutertry.htunnel.client.config.Tunnel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Nicolas Dutertry
 */
@Slf4j
public class ClientListener implements Runnable {


    private int port;

    private String resource;

    private TunnelClient tunnelClient;

    private Tunnel tunnel;


    public ClientListener(Tunnel tunnel, TunnelClient tunnelClient) {
        this.port = tunnel.getPort();
        this.resource = tunnel.getResource();
        this.tunnelClient = tunnelClient;
        this.tunnel = tunnel;
    }


    @Override
    @SneakyThrows
    public void run() {

        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new ByteArrayDecoder())
                                    .addLast(new ByteArrayEncoder())
                                    .addLast(new ServerHandler());
                        }
                    });


            ChannelFuture f = b.bind(tunnel.getPort()).sync();

            log.info("绑定 localhost:{} -> {} on {}", port, resource, tunnel.getServerName());

            f.channel().closeFuture().sync();

        } catch (InterruptedException e) {
            log.warn("连接中断:{}", resource);
        } catch (Exception e) {
            log.error("绑定 localhost:{} -> {} on {}失败:{}", port, resource, tunnel.getServerName(), e.getMessage());
            throw new RuntimeException(e);
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }

    }



    public class ServerHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            String id = AppConnectionManager.register(ctx);
            log.debug("{} {} channelActive ", resource, id);
            tunnelClient.sendServer(id, resource, new byte[]{});
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object bytes) throws Exception {

            byte[] array = (byte[]) bytes;
            // 打印从客户端接收到的消息
            log.debug("send from app: {}", array.length);
            String id = AppConnectionManager.register(ctx);
            tunnelClient.sendServer(id, resource, array);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            AppConnectionManager.removeAppConnection(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }

    }

}
