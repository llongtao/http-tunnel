package com.dutertry.htunnel.client;


import com.dutertry.htunnel.client.config.Tunnel;
import com.dutertry.htunnel.client.config.TunnelProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;

@EnableConfigurationProperties(TunnelProperties.class)
@Slf4j
@Component
public class ClientRunner implements ApplicationRunner {


    private final List<Thread> threadList = new ArrayList<>();

    private final TunnelProperties tunnelProperties;

    public ClientRunner(TunnelProperties tunnelProperties) {
        this.tunnelProperties = tunnelProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {

        log.info("load config:{}", tunnelProperties);

        try {
            PrivateKey privateKey = tunnelProperties.getPrivateKey();
            boolean base64Encoding = tunnelProperties.isBase64Encoding();
            List<Tunnel> tunnels = tunnelProperties.getTunnels();
            String username = tunnelProperties.getUsername();
            String password = tunnelProperties.getPassword();
            tunnels.forEach(tunnel -> {
                Thread thread = new Thread(new ClientListener(tunnel, username, password));
                threadList.add(thread);
                thread.start();
            });
        } catch (Throwable e) {
            log.error("run error :{}", tunnelProperties, e);
            Thread.sleep(10000);
        }

    }


    @PreDestroy
    public void destroy() {
        log.info("Destroying listener");
        threadList.forEach(Thread::interrupt);
    }
}
