package com.wenqu.ai.service.openrun;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.service.CollectingSseEmitter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 对外 run 的投影出口：继承收集型通道，把 native 事件逐帧翻译成冻结的 v1 事件并写回放缓冲。
 *
 * <p><b>为什么不改 {@code ChatRunBus}</b>：{@code ChatService.chat} 用
 * {@code emitter instanceof CollectingSseEmitter} 判定收集型通道——继承它即可复用同一条问答
 * 流水线，同时天然不登记运行总线、不挂看门狗、不占网页端的无观众宽限。引擎、总线、前端事件
 * 消费路径一行不改，投影失败也掐不断用户那一轮。这是比「在 bus 上挂订阅者」更窄的接缝。</p>
 *
 * <p>事件白名单与 native 名分离但一一对应；未列入的 UI 内部帧（process/todo/deep_state…）
 * 直接丢弃，native 以后新增事件不必改 SDK（SDK 侧已有 onEvent 兜底）。</p>
 */
@Slf4j
public class OpenRunEmitter extends CollectingSseEmitter {

    /** native → v1 事件映射（方案 §3 的冻结子集；turn_start 由 Service 手写 run.started，见下） */
    private static final Map<String, String> EVENTS = Map.of(
            "token", "token",
            "tool_status", "tool.status",
            "plan", "plan.updated",
            "approval_required", "approval.required",
            "ask_user", "approval.required",
            "deep_plan_approval", "approval.required",
            "artifact", "artifact.created",
            "warn", "approval.timed_out",
            "done", "run.done",
            "error", "run.error");

    /** 终态回填（由 OpenRunService 更新 c_ai_open_run） */
    public record Terminal(String status, String code, String message, String answer) {
    }

    private final String runId;
    private final String requestId;
    private final RunEventReplay replay;
    private final Consumer<Terminal> onTerminal;
    /** 从 1 起：Service 已在建 run 时写掉 run.started（id=1） */
    private final AtomicLong seq = new AtomicLong(1);
    private volatile boolean settled;

    public OpenRunEmitter(String runId, String requestId, RunEventReplay replay, Consumer<Terminal> onTerminal) {
        this.runId = runId;
        this.requestId = requestId == null ? "" : requestId;
        this.replay = replay;
        this.onTerminal = onTerminal;
        super.forwardAllFrames(this::project);
    }

    private void project(String nativeType, String rawFrame) {
        String v1 = EVENTS.get(nativeType);
        if (v1 == null || settled) return;
        JSONObject frame = dataOf(rawFrame);
        if (frame == null) {
            log.warn("[OPEN-RUN] 事件帧无 data 段，跳过 run={} type={}", runId, nativeType);
            return;
        }
        Object rawContent = frame.get("content");
        String content = rawContent instanceof String s ? s
                : (rawContent == null ? "" : JSON.toJSONString(rawContent));
        // warn 还用于整轮预算提示与 ask 卡「前台等待结束（本轮继续）」，那些不是超时，一律不投
        if ("approval.timed_out".equals(v1) && !OpenRunErrorCode.isApprovalTimeoutWarn(content)) return;
        // 原生 stopTurn 不发帧，终态仍是 done；标记存在时把这一轮如实收成 stopped
        boolean stopped = "run.done".equals(v1) && replay.consumeStopped(runId);
        Map<String, Object> payload = payload(v1, nativeType, content, stopped);
        long id = seq.incrementAndGet();
        replay.append(runId, id, v1, JSON.toJSONString(payload));
        if ("run.done".equals(v1)) {
            finish(new Terminal(stopped ? "stopped" : "succeeded", null, null, answer()));
        } else if ("run.error".equals(v1)) {
            finish(new Terminal("failed", OpenRunErrorCode.classify(content), content, answer()));
        }
    }

    private void finish(Terminal t) {
        if (settled) return;
        settled = true;
        try {
            onTerminal.accept(t);
        } catch (Exception e) {
            // 终态回填失败必须留痕：否则 run 永远卡在 running，集成方看不到结束
            log.error("[OPEN-RUN] 终态回填失败 run={}: {}", runId, e.getMessage(), e);
        } finally {
            ChatRunRuntime.forgetSseChannel(this);
        }
    }

    private Map<String, Object> payload(String v1, String nativeType, String content, boolean stopped) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        switch (v1) {
            case "token" -> p.put("delta", content);
            case "tool.status" -> {
                p.put("native", contentOf(content, "name", "status", "elapsedMs", "stepId"));
            }
            case "plan.updated" -> p.put("plan", parseOrRaw(content));
            case "approval.required" -> {
                JSONObject c = asObject(content);
                p.put("kind", switch (nativeType) {
                    case "ask_user" -> "ask_user";
                    case "deep_plan_approval" -> "plan_approval";
                    default -> "tool_approval";
                });
                p.put("approvalId", firstNonBlank(c, "approvalId", "planApprovalId", "askId"));
                p.put("payload", parseOrRaw(content));
            }
            case "artifact.created" -> p.put("artifact", contentOf(content,
                    "id", "filename", "ext", "size", "preview", "description"));
            case "approval.timed_out" -> {
                p.put("code", OpenRunErrorCode.APPROVAL_TIMEOUT);
                p.put("message", content);
            }
            case "run.done" -> {
                JSONObject c = asObject(content);
                p.put("status", stopped ? "stopped" : "succeeded");
                p.put("finalContent", c == null ? content : c.getString("finalContent"));
                if (c != null) {
                    p.put("usage", c.get("tokens"));
                    p.put("sources", c.get("sources"));
                    p.put("artifacts", c.get("artifacts"));
                }
            }
            case "run.error" -> {
                p.put("status", "failed");
                p.put("code", OpenRunErrorCode.classify(content));
                p.put("message", content);
                p.put("request_id", requestId);
            }
            default -> p.put("content", content);
        }
        return p;
    }

    /** 从 SSE 文本里取 data: 段的 JSON */
    private static JSONObject dataOf(String raw) {
        int at = raw == null ? -1 : raw.indexOf("data:");
        if (at < 0) return null;
        String text = raw.substring(at + 5);
        int nl = text.indexOf('\n');
        if (nl >= 0) text = text.substring(0, nl);
        try {
            return JSON.parseObject(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject asObject(String content) {
        try {
            return JSON.parseObject(content);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object parseOrRaw(String content) {
        JSONObject o = asObject(content);
        return o != null ? o : content;
    }

    /** 只挑白名单字段，UI 内部字段不外泄 */
    private static Map<String, Object> contentOf(String content, String... fields) {
        JSONObject o = asObject(content);
        Map<String, Object> out = new LinkedHashMap<>();
        if (o == null) {
            out.put("raw", content);
            return out;
        }
        for (String f : fields) if (o.get(f) != null) out.put(f, o.get(f));
        return out;
    }

    private static String firstNonBlank(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = o.getString(k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    public boolean isSettled() {
        return settled;
    }
}
