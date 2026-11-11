package com.wenqu.ai.agentx.turn;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 一轮 done 载荷的唯一构造点：键位、条件键与序列化特性都在这里，引擎收尾只负责填输入。
 * <p>BrowserCompatible 是结构性免疫：thinking 里出现 U+001A 这类不可见控制字符时，
 * 手工拼接会让整包 JSON 非法；这里统一输出转义序列。
 */
public final class TurnDonePayload {

    private TurnDonePayload() {
    }

    @Builder
    public static final class Input {
        private final String finalContent;
        private final String messageId;
        private final String userMessageId;
        private final String turnId;
        private final String model;
        private final String agentId;
        private final String agentName;
        private final List<Map<String, Object>> sources;
        private final List<String> finalImages;
        private final List<Map<String, String>> degradations;
        private final long elapsedMs;
        private final long humanWaitMs;
        private final long turnWallMs;
        private final long prepMs;
        private final Map<String, Object> tokens;
        private final List<Map<String, Object>> toolCalls;
        private final Map<String, Object> blocks;
        private final Map<String, Object> changes;
        private final List<Map<String, Object>> artifacts;
        private final List<String> related;
        private final String thinking;
        private final Map<String, Object> planRecord;
        private final List<Map<String, Object>> pendingSteers;
        /**
         * 定稿正文相对流式全文是否被改写过（裁决唯一出自 {@code BodyReconcile}）。
         * done 显式宣告这一事实，前端不再拿 finalContent 与本地累积现场比对——那是第三套判据，
         * 与后端两套对账判据的边界从来对不齐（接流回放、丢帧、旧服务端都让比对失真）。
         */
        private final boolean bodyRewritten;
        /**
         * 分支自己的扩展键（工作流的 workflowRun、轮级委派归属）。
         * <p>核心键与序列化特性只在这一处，分支不再各写一份 done；只有<b>确属该分支独有</b>的键才走这里，
         * 且不得与上面的核心键重名——重名会在末尾覆盖核心值。
         */
        private final Map<String, Object> extras;
    }

    public static String toJson(Input in) {
        return JSON.toJSONString(build(in),
                com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue,
                com.alibaba.fastjson2.JSONWriter.Feature.BrowserCompatible);
    }

    static JSONObject build(Input in) {
        JSONObject done = new JSONObject();
        done.put("finalContent", in.finalContent);
        done.put("messageId", in.messageId);
        done.put("userMessageId", in.userMessageId);
        done.put("turnId", in.turnId);
        done.put("model", in.model);
        done.put("agentId", in.agentId);
        done.put("agentName", in.agentName);
        done.put("sources", in.sources);
        done.put("finalImages", in.finalImages);
        done.put("degradations", in.degradations);
        done.put("elapsedMs", in.elapsedMs);
        done.put("humanWaitMs", in.humanWaitMs);
        done.put("turnWallMs", in.turnWallMs);
        done.put("prepMs", in.prepMs);
        done.put("tokens", in.tokens);
        done.put("toolCalls", in.toolCalls);
        if (in.blocks != null) done.put("blocks", in.blocks);
        if (in.changes != null) done.put("changes", in.changes);
        done.put("artifacts", in.artifacts == null ? List.of() : in.artifacts);
        done.put("related", in.related);
        done.put("thinking", in.thinking);
        done.put("bodyRewritten", in.bodyRewritten);
        if (in.planRecord != null && "superseded".equals(in.planRecord.get("status"))) {
            done.put("planSuperseded", true);
        }
        if (in.planRecord != null && "rejected".equals(in.planRecord.get("status"))) {
            done.put("planCancelled", true);
        }
        if (in.planRecord != null) done.put("planRecord", in.planRecord);
        if (in.pendingSteers != null && !in.pendingSteers.isEmpty()) done.put("pendingSteers", in.pendingSteers);
        if (in.extras != null) done.putAll(in.extras);
        return done;
    }
}
