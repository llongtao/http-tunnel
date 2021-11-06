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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.PrivateKey;

import com.dutertry.htunnel.client.config.Tunnel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * @author Nicolas Dutertry
 */
public class ClientListener implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientListener.class);

    private int port;

    private String target;

    private String server;

    private String proxy;

    private int bufferSize;

    @Value("${base64:false}")
    private boolean base64Encoding;

    @Value("${private-key:}")
    private String privateKeyPath;

    private PrivateKey privateKey;

    @Value("${single:false}")
    private boolean single;


    public ClientListener(Tunnel tunnel, PrivateKey privateKey, boolean base64Encoding) {
        this.port = tunnel.getPort();
        this.target = tunnel.getTarget();
        this.server = tunnel.getServer();
        this.proxy = tunnel.getProxy();
        this.bufferSize = tunnel.getBufferSize();
        this.privateKey = privateKey;
        this.base64Encoding = base64Encoding;
    }

    public void run() {
        String targetHost = StringUtils.substringBeforeLast(target, ":");
        int targetPort = Integer.parseInt(StringUtils.substringAfterLast(target, ":"));
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.socket().bind(new InetSocketAddress("localhost", port));
            LOGGER.info("绑定 localhost:{} -> {} on {}", port, target, server);

            while (!Thread.currentThread().isInterrupted()) {
                SocketChannel socketChannel = ssc.accept();
                LOGGER.info("New connection received");
                socketChannel.configureBlocking(false);

                TunnelClient tunnelClient = new TunnelClient(socketChannel,
                        targetHost, targetPort,
                        server,
                        proxy,
                        bufferSize,
                        base64Encoding,
                        privateKey);
                Thread thread = new Thread(tunnelClient);
                thread.start();

                if (single) {
                    break;
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error in listener loop", e);
        }
        LOGGER.info("Listener thread terminated");
    }


}
