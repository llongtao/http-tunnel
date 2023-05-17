package com.dutertry.htunnel.server.utils;

import com.dutertry.htunnel.common.Constants;
import org.slf4j.MDC;
import org.springframework.web.socket.WebSocketSession;

/**
 * @author lilongtao 2023/4/12
 */
public  class LogUtils {
    public static void setTrace(WebSocketSession session) {
        if (session == null) {
            return;
        }
        String username = (String) session.getAttributes().get("username");
        MDC.put(Constants.TRACE_ID_KEY, username);
    }
}
