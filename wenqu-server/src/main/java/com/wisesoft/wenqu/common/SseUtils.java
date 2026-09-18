package com.wisesoft.wenqu.common;

import com.alibaba.fastjson2.JSON;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE 流共享辅助与时序常量。
 *
 * <p>由参考实现的 utils/sse_utils.py 逐函数翻译：SSE 帧格式（event/data/id + 心跳注释帧）。
 */
public final class SseUtils {

    public static final int SSE_HEARTBEAT_SECONDS =
            Integer.parseInt(System.getenv().getOrDefault("RUN_SSE_HEARTBEAT_SECONDS", "15"));

    /**
     * Compose 对开发服务器优雅停机单独设限，避免活跃流阻塞热重载整段连接生命周期。
     */
    public static final int SSE_MAX_CONNECTION_MINUTES =
            Integer.parseInt(System.getenv().getOrDefault("RUN_SSE_MAX_CONNECTION_MINUTES", "30"));

    public static final double SSE_POLL_INTERVAL_SECONDS =
            Double.parseDouble(System.getenv().getOrDefault("RUN_SSE_POLL_INTERVAL_SECONDS", "1.0"));

    private SseUtils() {}

    /** 组装 SSE 帧：event 行、data 行（JSON，非 ASCII 不转义）、可选 id 行、结尾空行。 */
    public static String formatSse(Map<String, Object> data, String event, String eventId) {
        StringBuilder lines = new StringBuilder();
        lines.append("event: ").append(event).append('\n');
        lines.append("data: ")
                .append(data == null ? "null" : JSON.toJSONString(data))
                .append('\n');
        if (eventId != null && !eventId.isEmpty()) {
            lines.append("id: ").append(eventId).append('\n');
        }
        lines.append('\n');
        return lines.toString();
    }

    /** SSE 注释心跳帧。 */
    public static String formatHeartbeat() {
        return ": heartbeat\n\n";
    }
}
