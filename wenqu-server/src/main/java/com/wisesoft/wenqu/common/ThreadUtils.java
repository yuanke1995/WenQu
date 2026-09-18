package com.wisesoft.wenqu.common;

import java.util.Map;

/**
 * 线程事件结构工具。
 *
 * <p>由参考实现的 utils/thread_utils.py 逐函数翻译：extract_thread_id ——
 * 从规范化事件结构中提取 thread_id。
 */
public final class ThreadUtils {

    private ThreadUtils() {}

    /**
     * 从规范化事件结构中提取 thread_id。
     *
     * <p>只读取当前对象和一层稳定容器字段（configurable/metadata/stream_event/meta），
     * 避免递归扫描把未规范化的内部结构误判为路由依据。
     */
    public static String extractThreadId(Object value, String fallback) {
        if (!(value instanceof Map)) {
            return fallback;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        Object[] sources = {
            map,
            map.get("configurable"),
            map.get("metadata"),
            map.get("stream_event"),
            map.get("meta"),
        };
        for (Object source : sources) {
            if (!(source instanceof Map)) {
                continue;
            }
            Object threadId = ((Map<?, ?>) source).get("thread_id");
            if (threadId instanceof String text && !text.strip().isEmpty()) {
                return text.strip();
            }
        }
        return fallback;
    }
}
