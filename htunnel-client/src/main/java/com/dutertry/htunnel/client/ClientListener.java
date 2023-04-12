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
package com.dutertry.htunnel.client;

import com.dutertry.htunnel.client.config.Tunnel;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;

/**
 * @author Nicolas Dutertry
 */
@Slf4j
public class ClientListener implements Runnable {


    private int port;

    private String resource;

    private String server;

    private String username;

    private String password;
    private Tunnel tunnel;


    @Value("${single:false}")
    private boolean single;


    public ClientListener(Tunnel tunnel, String username, String password) {
        this.port = tunnel.getPort();
        this.resource = tunnel.getResource();
        this.username = username;
        this.password = password;
        this.server = tunnel.getServer();
        this.tunnel = tunnel;
    }


    @SneakyThrows
    public void run() {

        Selector serverSelector = Selector.open();
        ServerSocketChannel localServerSocketChannel = ServerSocketChannel.open();
        localServerSocketChannel.bind(new InetSocketAddress(port));
        localServerSocketChannel.configureBlocking(false);
        localServerSocketChannel.register(serverSelector, SelectionKey.OP_ACCEPT);


        log.info("绑定 localhost:{} -> {} on {}", port, resource, server);


        while (true) {
            try {
                serverSelector.select();
                Iterator<SelectionKey> iterator2 = serverSelector.selectedKeys().iterator();
                while (iterator2.hasNext()) {
                    SelectionKey key = iterator2.next();
                    iterator2.remove();
                    if (key.isAcceptable()) {
                        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
                        SocketChannel localSocketChannel = serverSocketChannel.accept();
                        localSocketChannel.configureBlocking(false);

                        // 连接远程 MySQL 服务器
                        // 注册本地 SocketChannel 和远程 SocketChannel 到 Selector 中

                        log.info("客户端连接成功：{}->{} on localhost:{}", localSocketChannel.getRemoteAddress(), tunnel.getResource(), tunnel.getPort());

                        new ClientChannel(localSocketChannel, tunnel, username, password).run();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }


    }


}
