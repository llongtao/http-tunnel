package com.dutertry.htunnel.client.config;

import lombok.Data;

@Data
public class Tunnel {

    private int port;

    private String target;

    private String server;

}