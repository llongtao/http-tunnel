package com.dutertry.htunnel.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "conn")
public class ConnConfig {

    Map<String,String> map = new HashMap<>();
}
