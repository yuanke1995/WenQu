package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.engine.RunEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器操作快照的本轮暂存与前端推送。
 *
 * <p>定位：浏览器面板是<b>只读镜像</b>——助手工具每产生一次页面快照，这里记一条并尽量推给本轮
 * SSE；用户在面板上不能导航/点击回灌给模型。快照生命周期跟着轮次走（内存、不上库），
 * 与浏览器会话本身一样是临时的。
 *
 * <p>与 {@code TurnRegistry} 的关系：工具跑在 Spring AI 的 I/O 线程上，拿不到 RequestUser，但
 * {@code ToolContext} 里有 uid/sessionId，据此查本轮把手拿到 emitter——这条链路与计划卡/子代理
 * 事件同源，不另开通道。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserSnapshotStore {

    /** 单会话保留的最大快照数（越新的越前），与前端侧栏的上限一致 */
    public static final int MAX_PER_SESSION = 20;

    private final ChatRunRuntime chatRunRuntime;
    private final Map<String, Deque<Map<String, Object>>> bySession = new ConcurrentHashMap<>();

    /** 记一条快照并推送给本轮 SSE（推送失败只记 debug，不影响工具结果）。 */
    public void record(String uid, String sessionId, String url, String title, String text, String runtime) {
        if (sessionId == null || sessionId.isBlank()) return;
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("sessionId", sessionId);
        snap.put("url", url == null ? "" : url);
        snap.put("title", title == null ? "" : title);
        snap.put("text", text == null ? "" : text);
        snap.put("runtime", runtime == null ? "" : runtime);
        snap.put("ts", System.currentTimeMillis());
        Deque<Map<String, Object>> q = bySession.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addFirst(snap);
            while (q.size() > MAX_PER_SESSION) q.removeLast();
        }
        TurnHandle handle = TurnRegistry.handle(sessionId);
        if (handle != null && handle.emitter() != null) {
            try {
                chatRunRuntime.sendSseEvent(handle.emitter(), RunEventType.BROWSER_SNAPSHOT.sseName(),
                        JSON.toJSONString(snap), sessionId, uid, handle.turnId(), false);
            } catch (Exception e) {
                log.debug("[BROWSER_SNAPSHOT] 推送失败（忽略） sid={}: {}", sessionId, e.toString());
            }
        }
    }

    /** 新轮开始时清空上一轮的镜像（前端也监听 turn_start 清，两边同源双保险）。 */
    public void clear(String sessionId) {
        if (sessionId != null) bySession.remove(sessionId);
    }

    /** 供调试/回放读取（当前无 HTTP 端点；前端从 SSE 事件拿，不走轮询）。 */
    public List<Map<String, Object>> recent(String sessionId) {
        Deque<Map<String, Object>> q = bySession.get(sessionId);
        if (q == null) return List.of();
        synchronized (q) { return new ArrayList<>(q); }
    }
}
