package com.wenqu.ai.agentx;

import lombok.RequiredArgsConstructor;

import java.util.Map;

import static com.wenqu.ai.agentx.AgentxToolCardCodec.esc;

/**
 * 工具卡载荷（SSE {@code tool_status} 帧的 JSON）构建（从 AgentscopeChatEngine 抽出）。
 *
 * <p>名字/展示名/提示/状态/耗时/入参/输出/子问答，按前端约定拼成一个 JSON 字符串。
 * 只依赖 {@link ToolDisplayNames}（取展示名），其余都是入参。
 * 入参与结果按字符串下发（与落库那份同形），超长字段折成预览，避免整份文件正文随每帧 SSE 重发。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxToolCardBuilder {

    private final ToolDisplayNames displayNames;

    public String toolCard(String uid, String name, String status, long elapsedMs, long startedAtMs, long waitedMs,
                            String args, String result, java.util.Map<String, Object> subChat, int stepId) {
        return toolCard(uid, name, status, elapsedMs, startedAtMs, waitedMs, args, result, subChat, stepId,
                false, 0L, null, false);
    }

    /**
     * 工具卡载荷的完整版：在上一份的基础上多一组「早产进度」字段（{@code preparing/chars/brief}）
     * 与 {@code askContinued}。
     * <p>{@code preparing=true} 表示这次调用还在生成入参、工具尚未开始执行；前端把这张卡渲染成进行中，
     * 并在正式 {@code tool/requested} 帧到达时原地升级（不新增第二张卡）。
     * <p>{@code askContinued=true} 表示这张提问卡的前台等待已到点、按 pending 送回了模型：卡片仍可作答，
     * 前端据此保留提问面板，而不是按终态撤卡。
     */
    public String toolCard(String uid, String name, String status, long elapsedMs, long startedAtMs, long waitedMs,
                            String args, String result, java.util.Map<String, Object> subChat, int stepId,
                            boolean preparing, long chars, String brief, boolean askContinued) {
        StringBuilder sb = head(uid, name, status, elapsedMs);
        flags(sb, status, stepId, startedAtMs, waitedMs);
        preparing(sb, preparing, chars, brief);
        payload(sb, args, result, subChat);
        if (askContinued) sb.append(",\"askContinued\":true");
        return sb.append("}").toString();
    }

    /** 头段：name + 展示名 + 提示 + 状态 + 耗时。 */
    private StringBuilder head(String uid, String name, String status, long elapsedMs) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(esc(name)).append("\"");
        String label = displayNames.label(uid, name);
        if (label != null) sb.append(",\"label\":\"").append(esc(label)).append("\"");
        String hint = displayNames.hint(uid, name);
        if (hint != null) sb.append(",\"hint\":\"").append(esc(hint)).append("\"");
        return sb.append(",\"status\":\"").append(status).append("\",\"elapsedMs\":").append(elapsedMs);
    }

    /** 终态/步骤/计时标志位：只在真有意义时写，缺字段=不显示而不是谎报 0。 */
    private void flags(StringBuilder sb, String status, int stepId, long startedAtMs, long waitedMs) {
        if ("denied".equals(status)) sb.append(",\"denied\":true");
        if (stepId >= 0) sb.append(",\"stepId\":").append(stepId);
        if (startedAtMs > 0L) sb.append(",\"startedAtMs\":").append(startedAtMs);
        if (waitedMs > 0L) sb.append(",\"waitedMs\":").append(waitedMs);
    }

    /** 早产卡：还在生成入参时标记 preparing 与字数/摘要。 */
    private void preparing(StringBuilder sb, boolean preparing, long chars, String brief) {
        if (!preparing) return;
        sb.append(",\"preparing\":true");
        if (chars > 0L) sb.append(",\"chars\":").append(chars);
        if (brief != null && !brief.isBlank()) sb.append(",\"brief\":\"").append(esc(brief)).append("\"");
    }

    /** 入参/输出/子问答：按字符串下发（与落库同形），超长折预览。 */
    private void payload(StringBuilder sb, String args, String result, java.util.Map<String, Object> subChat) {
        if (args != null && !args.isBlank()) {
            sb.append(",\"args\":").append(com.alibaba.fastjson2.JSON.toJSONString(AgentxTurnRecording.toolArgsForDisplay(args)));
        }
        if (result != null && !result.isBlank()) sb.append(",\"result\":").append(com.alibaba.fastjson2.JSON.toJSONString(result));
        if (subChat != null && !subChat.isEmpty()) sb.append(",\"subChat\":").append(com.alibaba.fastjson2.JSON.toJSONString(subChat));
    }
}
