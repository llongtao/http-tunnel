package com.dutertry.htunnel.server.conn;

import com.alibaba.fastjson.JSON;
import com.dutertry.htunnel.common.model.WsMessage;
import com.dutertry.htunnel.server.config.ResourceConfig;
import com.dutertry.htunnel.server.session.WsSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author lilongtao 2024/7/2
 */
@Slf4j
public class EndpointConnectionManager {


    private static ResourceConfig config;

    private static final Map<String, EndpointConnection> CONNECTION_MAP = new ConcurrentHashMap<>();

    public static EndpointConnection getConnection(WsSession wsSession, String connectionId, String resource) {
        EndpointConnection endpointConnection = CONNECTION_MAP.get(connectionId);
        if (endpointConnection == null) {
            endpointConnection = createConnection(wsSession, connectionId, resource);
        }
        return endpointConnection;
    }

    private static EndpointConnection createConnection(WsSession wsSession, String connectionId, String resource) {
        String addrInfo = config.getMap().get(resource);
        if (ObjectUtils.isEmpty(addrInfo)) {
            log.error("找不到资源:{}", resource);
            WsMessage wsMessage = new WsMessage();
            wsMessage.setType("error");
            wsMessage.setMessage("找不到资源:" + resource);
            wsMessage.setConnectionId(connectionId);
            wsSession.sendClient(JSON.toJSONString(wsMessage));
            wsSession.close();
            throw new RuntimeException("unknownResource:" + resource);
        }
        String[] split = addrInfo.trim().split(":");
        InetSocketAddress inetSocketAddress = new InetSocketAddress(split[0], Integer.parseInt(split[1]));

        EndpointConnection endpointConnection = null;
        try {
            endpointConnection = new EndpointConnection(connectionId, wsSession, inetSocketAddress);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        CONNECTION_MAP.put(connectionId, endpointConnection);
        return endpointConnection;
    }

    public static void setResource(ResourceConfig resourceConfig) {
        EndpointConnectionManager.config = resourceConfig;
    }

    public static void removeConnection(String connectionId) {
        CONNECTION_MAP.remove(connectionId);
    }
}
