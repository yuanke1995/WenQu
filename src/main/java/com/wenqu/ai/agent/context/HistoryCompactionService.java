package com.wenqu.ai.agent.context;

import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.TokenCounter;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史文本共享读取面。
 *
 * <p><b>自动/手动压缩已交 AgentScope</b>：框架的 {@code CompactionMiddleware} 按模型窗口推导阈值并滚动压缩，
 * 手动 /compact 走 {@code FrameworkSessions#compactNow}。问渠侧原来那套「预算装配 + 滚动摘要 +
 * context.historyCompress/compressRatio/compressTimeoutMs」的读写点已全部删除，不再保留第二套压缩口径。
 *
 * <p>本类只剩两个仍被非问答链路使用的只读工具：
 * <ul>
 *   <li>{@link #currentSummary(String)}：会话里已落库的早期摘要（兼容存量会话的分隔线展示）；</li>
 *   <li>{@link #buildHistoryText(List)}：工作流 inputs.history 等旁路用的短历史文本。</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
public class HistoryCompactionService {

    /** 原样保留的问答对数：界面按「次问答」说话，与历史摘要覆盖点口径一致 */
    private static final int HISTORY_MIN_KEEP = 4;

    private final SessionService sessionService;

    public HistoryCompactionService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 当前这份滚动摘要（对话流里「已压缩上下文」分隔线点开要看的东西）。
     * <p>会话只有<b>一份</b>摘要：每次压缩都把更早的对话并入同一份、覆盖点往后推。所以从任何一条
     * 分隔线点进来看到的都是这一份——它不是"当时那一版快照"，而是"现在生效的这份"，界面上按这个口径说。
     */
    public Map<String, Object> currentSummary(String sessionId) {
        com.wenqu.ai.model.Session session = sessionService.sessionById(sessionId);
        String summary = (session == null || session.getHistorySummary() == null) ? "" : session.getHistorySummary();
        long untilSeq = (session == null || session.getSummaryUntilSeq() == null) ? 0L : session.getSummaryUntilSeq();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("summaryTokens", TokenCounter.estimate(summary));
        out.put("untilSeq", untilSeq);
        out.put("keepTurns", HISTORY_MIN_KEEP / 2);
        return out;
    }

    /**
     * 近期历史的短文本（非问答链路用：工作流 inputs.history、无知识库分支的历史）。
     * 按字符截断 + token 总上限，保证旁路不会把整段历史塞进提示词。
     */
    public String buildHistoryText(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        int perMsgChars = 200;
        int maxTokens = 1200;
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            content = stripImageMarks(content).trim();
            if (content.isEmpty()) continue;
            if (content.length() > perMsgChars) {
                content = content.substring(0, perMsgChars) + "…";
            }
            String line = role + ": " + content + "\n";
            int tokens = TokenCounter.estimate(line);
            if (total + tokens > maxTokens) {
                break; // 超总上限，丢弃更早的历史
            }
            sb.append(line);
            total += tokens;
        }
        return sb.toString();
    }

    /**
     * 剥离文本中的图片标记 [图片N] / [图片N：描述] / [图片：描述]（用于注入历史，避免编号与本轮冲突）
     */
    private String stripImageMarks(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\[图片\\d*(?:[：:][^\\]]*)?\\]", " ").replaceAll("\\s+", " ").trim();
    }
}
