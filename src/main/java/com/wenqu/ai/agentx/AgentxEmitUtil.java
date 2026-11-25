package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import io.agentscope.core.session.SessionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.firstNonNull;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.toolNameOf;

/**
 * SSE 帧发射与人工等待记账的小工具（从 AgentscopeChatEngine 抽出）。
 *
 * <p>这些方法不碰引擎字段：只写 channel、recording 与 TurnRegistry，都是「一进一出」的包装。
 * 抽出来是为了让引擎少扛一层事件词汇的细节——它仍决定「什么时候发哪一帧」，
 * 但「这一帧怎么装、等待怎么记」收在这里。
 *
 * @author yuanke
 */
public final class AgentxEmitUtil {

    private AgentxEmitUtil() {
    }

    /** 进入人工等待：emitter 上起表，recording 记下当前挂着的那几张卡的调用键。 */
    public static void beginHumanWait(RunChannel channel, AgentxTurnRecording recording,
                                      Map<String, SessionEvent> pending) {
        if (channel.emitter() != null) TurnRegistry.beginHumanWait(channel.emitter());
        recording.beginToolWait(pendingCallKeys(pending));
    }

    /** 离开人工等待：emitter 上收表，recording 写终值。 */
    public static void endHumanWait(RunChannel channel, AgentxTurnRecording recording) {
        if (channel.emitter() != null) TurnRegistry.endHumanWait(channel.emitter());
        recording.endToolWait();
    }

    /**
     * 挂着的卡属于哪次工具调用：配对键与 {@link AgentxTurnRecording#toolCallKey} 同一套算法，
     * 这样「这段等待」记在挂卡那张头上。认不出来时返回空集，由 endToolWait 兜底记给在飞的第一把。
     */
    public static List<String> pendingCallKeys(Map<String, SessionEvent> pending) {
        List<String> out = new ArrayList<>();
        for (SessionEvent e : pending.values()) {
            Map<String, Object> data = e.data() == null ? Map.of() : e.data();
            Object call = firstNonNull(data.get("call"), data);
            String name = toolNameOf(call);
            String id = call instanceof Map<?, ?> cm && cm.get("id") != null ? String.valueOf(cm.get("id")) : null;
            if (name.isBlank() && (id == null || id.isBlank())) continue;
            out.add(AgentxTurnRecording.toolCallKey(id, name));
        }
        return out;
    }

    /** 过程独白增量：空串不发帧也不入块。 */
    public static void emitProcess(RunChannel channel, AgentxTurnRecording recording, String delta) {
        if (delta == null || delta.isEmpty()) return;
        channel.emit(RunEventType.PROCESS, delta);
        recording.process(delta);
    }

    /** 非空才发帧。 */
    public static void emit(RunChannel channel, RunEventType type, String content) {
        if (content != null && !content.isEmpty()) channel.emit(type, content);
    }

    /** 收场当前思考段：记时间线 + 补 thinking_done（带本轮累计全文）；没在思考中就是空操作。 */
    public static void closeThinkingFrame(RunChannel channel, AgentxTurnRecording recording) {
        String closed = recording.closeThinking();
        if (closed == null) return;
        channel.emit(RunEventType.THINKING_DONE,
                com.alibaba.fastjson2.JSON.toJSONString(Map.of("thinking", closed)));
    }
}
