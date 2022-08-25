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
import com.dutertry.htunnel.client.config.TunnelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nicolas Dutertry
 */
@Slf4j
@SpringBootApplication
public class ClientApp implements ApplicationRunner {

    @Resource
    TunnelProperties tunnelProperties;

    private final List<Thread> threadList = new ArrayList<>();

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ClientApp.class);
        app.addListeners(new ApplicationPidFileWriter());
        app.run(args);
    }

    @Override
    public void run(ApplicationArguments args) {
        PrivateKey privateKey = tunnelProperties.getPrivateKey();
        boolean base64Encoding = tunnelProperties.isBase64Encoding();
        List<Tunnel> tunnels = tunnelProperties.getTunnels();
        String username = tunnelProperties.getUsername();
        String password = tunnelProperties.getPassword();
        tunnels.forEach(tunnel -> {
            Thread thread = new Thread(new ClientListener(tunnel, privateKey, base64Encoding,username,password));
            threadList.add(thread);
            thread.start();
        });
    }


    @PreDestroy
    public void destroy() {
        log.info("Destroying listener");
        threadList.forEach(Thread::interrupt);
    }

}
