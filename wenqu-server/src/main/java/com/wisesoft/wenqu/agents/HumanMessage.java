package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain HumanMessage 的最小承载（能力差异，显式标注）。
 *
 * <p>参考实现直接使用 {@code langchain.messages.HumanMessage}（pydantic 模型）；
 * 本工程无 LangChain，agents 运行时引擎照搬前由本类承载其数据面：
 * content 为字符串或多模态块列表（{@code {type: text|image_url, ...}}）。
 * {@link #rawMessage()} 对应 {@code model_dump()}（type 固定 "human"），
 * {@link #validate(Object)} 对应 {@code model_validate()}（非法输入抛错）。
 */
public final class HumanMessage {

    private final Object content;

    private HumanMessage(Object content) {
        this.content = content;
    }

    /** HumanMessage(content=str) 或 HumanMessage(content=list[part])。 */
    public static HumanMessage of(Object content) {
        if (!(content instanceof String) && !(content instanceof List)) {
            throw new IllegalArgumentException("HumanMessage content 必须是字符串或多模态块列表");
        }
        return new HumanMessage(content);
    }

    public Object getContent() {
        return content;
    }

    /** model_dump()：{"type": "human", "content": ...}（键序与 langchain 一致）。 */
    public Map<String, Object> rawMessage() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("content", content);
        dump.put("type", "human");
        return dump;
    }

    /** model_validate(raw)：从持久化字典恢复；langchain 校验失败时抛 IllegalArgumentException。 */
    public static HumanMessage validate(Object raw) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("invalid raw_message for chat input message");
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        Object content = map.get("content");
        if (content instanceof String || content instanceof List) {
            return new HumanMessage(content);
        }
        throw new IllegalArgumentException("invalid raw_message for chat input message");
    }
}
