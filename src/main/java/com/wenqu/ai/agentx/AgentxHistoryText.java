package com.wenqu.ai.agentx;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.session.AgentSession;
import com.wenqu.ai.agentx.output.ModelOutputParser;

import java.util.List;

/**
 * 从框架 transcript 里取「本轮正文」的几个纯函数（从 AgentscopeChatEngine 抽出）。
 *
 * <p>它们共享一个容易踩的坑：下界必须停在 {@code historyBefore}（本轮开始前的消息数）。
 * 一路扫到 0 会穿透进上一轮，把上一轮的回答当本轮正文存档；步数上限那条更严格——只看最后一条
 * 助手消息，往前翻同样会串轮。这些判据都跟着框架自己留在消息上的 generateReason 走，
 * 不比对文案（框架的内部提示词会改）。
 *
 * <p>{@link #stripFallbackBody} 剥离回退正文的标签时走的是 recording.related 那一套，
 * 不在这里补第二条正则——推荐条数、并回护栏、process 分流都住在它身上。
 *
 * @author yuanke
 */
public final class AgentxHistoryText {

    private AgentxHistoryText() {
    }

    /** 框架留下的失败原因：executionError 优先，其次当前任务的 rejected 状态。 */
    public static String runFailure(AgentSession session, AgentSession.Task current) {
        String error = session.executionError();
        if (error != null && !error.isBlank()) {
            return error.length() > 200 ? error.substring(0, 200) : error;
        }
        if (current != null && "rejected".equals(current.status())) {
            return current.error() == null ? "运行被框架拒绝" : current.error();
        }
        return null;
    }

    /** 本轮最后一条可用助手正文；被中断的一律不取（那是停止/超时的空壳）。 */
    public static String lastAssistantText(AgentSession session, int historyBefore) {
        List<Msg> messages = session.transcript().messages();
        if (messages.size() <= historyBefore) return "";
        for (int i = messages.size() - 1; i >= historyBefore; i--) {
            Msg m = messages.get(i);
            if (!"ASSISTANT".equals(String.valueOf(m.getRole()))) continue;
            if (GenerateReason.INTERRUPTED == m.getGenerateReason()) return "";
            String text = m.getTextContent();
            if (text != null && !text.isBlank()) return text;
        }
        return "";
    }

    /** 步数上限收场时补的那条收尾正文；不是撞上限收场返回 null。只看最后一条助手消息。 */
    public static String maxIterationsWrapUp(AgentSession session, int historyBefore) {
        List<Msg> messages = session.transcript().messages();
        if (messages.size() <= historyBefore) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (!"ASSISTANT".equals(String.valueOf(m.getRole()))) continue;
            if (GenerateReason.MAX_ITERATIONS != m.getGenerateReason()) return null;
            String text = m.getTextContent();
            return text == null || text.isBlank() ? null : text.strip();
        }
        return null;
    }

    /** 回退正文的收尾处理：走 recording.related 剥离标签，不补发 token 帧。 */
    public static String stripFallbackBody(AgentxTurnRecording recording, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        ModelOutputParser.Delta fed = recording.related.feed(raw);
        ModelOutputParser.Delta finished = recording.related.finish();
        recording.process(fed.process() + finished.process());
        return fed.text() + finished.text();
    }
}
