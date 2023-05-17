package com.dutertry.htunnel.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * @author lilongtao
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "resource")
public class ResourceConfig {

    Map<String,String> map = new HashMap<>();
}
