package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.SteerReceipts;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.str;

/**
 * 计划版本输出与未送达插话的投影（从 AgentscopeChatEngine 抽出）。
 *
 * <p>两者都是「把运行时状态翻给前端/落库」的纯投影：不写引擎字段、不发帧。
 * 插话那部分刻意保留三条口径：随 ack 回带 messageId（前端复用同一气泡补发，
 * 刷新后不会出现两条）、附件一并交回（否则补发那一轮模型看不到材料）、
 * 图片不在此列（展示 URL 不是补发要的 data URL，且已随消息行落库回显）。
 *
 * @author yuanke
 */
@Slf4j
public final class AgentxPlanSteerUtil {

    private AgentxPlanSteerUtil() {
    }

    /** 计划版本给前端的精简投影：只留 plan 与 feedback。 */
    public static Map<String, Object> planVersionOut(Map<String, Object> v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", v == null ? "" : str(v.get("plan")));
        out.put("feedback", v == null ? "" : str(v.get("feedback")));
        return out;
    }

    /** 按回执 id 找回一次插话的送达回执。 */
    public static SteerReceipts.SteerAck steerAckOf(String turnId, String receiptId) {
        return SteerReceipts.find(turnId, receiptId);
    }

    /** 本轮收下但没进上下文的插话：交回前端作为新一轮用户消息补发。 */
    public static List<Map<String, Object>> unsentSteers(String turnId, AgentxTurnRecording recording) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!recording.runEnded) return out;
        for (SteerReceipts.SteerAck ack : SteerReceipts.unsent(turnId, recording.appliedSteers)) {
            if (ack == null || ack.receiptId() == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", ack.text());
            item.put("messageId", ack.messageId() == null ? "" : ack.messageId());
            if (ack.attachments() != null && !ack.attachments().isEmpty()) {
                item.put("attachments", ack.attachments());
            }
            out.add(item);
        }
        if (!out.isEmpty()) {
            log.info("[STEER] 本轮收下的插话有 {} 条没进上下文，交回前端续发: turn={}", out.size(), turnId);
        }
        return out;
    }
}
