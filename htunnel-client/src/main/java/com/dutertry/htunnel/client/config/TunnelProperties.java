package com.dutertry.htunnel.client.config;

import com.dutertry.htunnel.common.crypto.CryptoUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.security.PrivateKey;
import java.util.List;

@Slf4j
@Data
@ConfigurationProperties(prefix = "tunnel")
public class TunnelProperties {

    private String username;

    private String password;

    private boolean base64Encoding = false;

    private String privateKeyStr;

    private PrivateKey privateKey;

    private List<Tunnel> tunnels;


    public PrivateKey getPrivateKey() {
        if (privateKey != null) {
            return privateKey;
        }
        if (privateKeyStr != null) {
            try {
                privateKey = CryptoUtils.readRSAPrivateKey(privateKeyStr);
            } catch (IOException e) {
                log.warn("私钥配置不正确");
            }
        }

        return privateKey;
    }


    @Override
    public String toString() {
        return "TunnelProperties{" +
                "username='" + username + '\'' +
                ", password='******" +  '\'' +
                ", base64Encoding=" + base64Encoding +
                ", privateKeyStr='" + privateKeyStr + '\'' +
                ", privateKey=" + privateKey +
                ", tunnels=" + tunnels +
                '}';
    }
}
