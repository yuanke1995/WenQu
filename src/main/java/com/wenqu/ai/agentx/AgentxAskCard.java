package com.wenqu.ai.agentx;

import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.boolOf;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.str;

/**
 * 提问卡载荷构建（从 AgentscopeChatEngine 抽出）：前端认的 {askId, questions, timeoutMs}。
 *
 * <p>一卡多问原样透传每题（含 multi），单选题补成单元素数组；题面交 fastjson 序列化而非手拼转义
 * （引号/换行是常态，手拼必出错）。timeoutMs 走独立的 config key——提问不能和工具审批挤同一份短预算
 * （它恰恰是最需要人从容看完题面的一类）。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxAskCard {

    private final ConfigService configService;

    public String askCard(String askId, Object input, String toolName) {
        Object questions;
        if (input instanceof Map<?, ?> in) {
            Object askList = in.get("questions");
            if (askList instanceof List<?> list && !list.isEmpty()) {
                // 一卡多问：每题自带 multi，原样透传（这里不替它判单选）
                questions = list;
            } else {
                Map<String, Object> one = new LinkedHashMap<>();
                String inTopic = str(in.get("topic"));
                one.put("topic", !inTopic.isBlank() ? inTopic
                        : (toolName == null || toolName.isBlank() ? "确认" : toolName));
                one.put("question", str(in.get("question")));
                Object options = in.get("options");
                one.put("options", options instanceof List<?> l ? l : List.of());
                // multi 只在为真时写：省略=单选，别让用户侧多认一种「显式 false」的写法
                if (boolOf(in.get("multi"))) one.put("multi", true);
                questions = List.of(one);
            }
        } else {
            questions = List.of(Map.of("question", str(input)));
        }
        // 交给 fastjson 序列化而不是手拼转义：题面里出现引号或换行是常态，手拼必出错
        return com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "askId", askId, "questions", questions,
                "timeoutMs", configService.getLong("chat.askTimeoutMs", 3600000L)));
    }
}
