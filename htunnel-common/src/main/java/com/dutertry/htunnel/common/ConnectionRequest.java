package com.dutertry.htunnel.common;

import lombok.Data;

@Data
public class ConnectionRequest {
    private String helloResult;
    
    private ConnectionConfig connectionConfig;
}
