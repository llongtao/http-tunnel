package com.dutertry.htunnel.common.model;

import lombok.Data;

import java.io.Serializable;

/**
 * @author lilongtao 2024/7/3
 */
@Data
public class WsMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    String type;
    String username;
    String password;
    String resource;
    String connectionId;
    String message;
    byte[] data;
}
