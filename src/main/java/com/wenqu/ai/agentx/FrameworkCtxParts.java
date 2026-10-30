package com.wenqu.ai.agentx;

import com.wenqu.ai.util.TokenCounter;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ToolSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 框架引擎这一轮的上下文容量分类（{@code tokens.parts} 的产出方）。
 *
 * <p><b>为什么按框架结构分类，而不是沿用手写侧那十类</b>：手写侧的十类靠「拼装 system 时的分段位置」
 * 切出（{@code ChatService} 的 {@code partRoleEnd/partMemoryEnd/partSkillEnd/partOtherEnd}），
 * 问渠自己拼 system 才切得出来。框架引擎的上下文由 AgentScope 装配，问渠既没有那四个标记，
 * 也没有对应的拼装过程——硬套过来只能靠猜（「这段大概是技能吧」），而猜出来的数字会直接进容量条，
 * 比不显示更坏。所以这里的分类一律按框架自己的数据结构判定：{@link MsgRole} + 内容块类型。
 *
 * <p>分类与识别依据（前端 {@code CTX_PART_META} 按同序渲染）：
 * <ol>
 *   <li>{@code system}——本轮<b>真实下发</b>的系统提示词，取 {@link AgentxSystemCaptureMiddleware}
 *       从模型调用请求里取景的那一份（角色提示词 + 框架的技能目录、子智能体与异步任务指引、
 *       工作区与记忆上下文、工具引导、related 提示行都在里面）。
 *       <p>为什么不在 transcript 里找：框架的 {@code SessionViews.transcript()} 只回放
 *       user/assistant/tool 三类事件，SYSTEM 消息<b>从来不在表里</b>。缺这一段，
 *       {@link #calibrate} 就把这块真实占用按倍数摊给其余各段、舍入残差落进最大那段——
 *       真机实测它把 5,708 的「工具声明」虚报成 13,525，容量条就此把人带向错误结论。
 *       取不到（实例还没跑过模型调用）时回落 transcript 里的 SYSTEM，两条路都空就少这一段。</li>
 *   <li>{@code history}——本轮之前的 {@code USER/ASSISTANT} 对话（用户与助手合为一段：框架不区分
 *       「近期原文」与「早期摘要」，压缩产物落 Session Log，问渠侧拿不到稳定口径，拆开就是造假）；</li>
 *   <li>{@code input}——本轮新增的 {@code USER} 消息，即用户这轮的问题；</li>
 *   <li>{@code retrieval}——{@code role=TOOL} 且工具名是检索类的那些结果块（{@link #RETRIEVAL_TOOLS}）；</li>
 *   <li>{@code toolOutput}——其余 {@code role=TOOL} 结果块（读文件、写表、沙箱执行、子智能体回报…）。
 *       框架下工具结果是主力占用之一，手写侧从来没有对应项；</li>
 *   <li>{@code thinking}——{@code role=ASSISTANT} 里的 {@link ThinkingBlock}。deepseek 这一族关着思考
 *       也照样回 reasoning，把它混进对话历史里就看不出「这一轮烧在思考上多少」；</li>
 *   <li>{@code toolSchema}——请求 {@code tools} 字段的 schema 估算（遍历 {@link ToolSchema}）。
 *       手写侧拆成系统工具/MCP 工具两桶，框架侧按 {@code Toolkit#getToolNames()} 拿不到稳定的
 *       「哪些是 MCP」判据（{@code isExternalTool} 认的是 provider 侧外部工具，不是这一层），
 *       合成一桶更诚实。</li>
 * </ol>
 *
 * <p><b>估算与校准</b>：各段都是 {@link TokenCounter} 估算直和（中文按字、英文按词折算），
 * 与网关实测的 prompt 必然有偏差，所以整体按 {@code prompt / 估算和} 等比缩放
 * （{@link #calibrate}，与问渠台账侧同一把尺）。
 * 缩放后各类之和严格等于本轮真实 prompt——容量条的分段与顶部百分比不会各说各话。
 */
final class FrameworkCtxParts {

    /**
     * 检索类工具名（框架侧 {@code @Tool} 声明的裸名）：这些工具的结果块是「检索回来的资料」，
     * 与「工具执行产物」在容量含义上完全两回事，必须分开——MCP server 或沙箱执行的结果往往比
     * 检索片段还大，混在一处就看不出上下文到底是被资料吃掉还是被工具输出吃掉。
     */
    private static final Set<String> RETRIEVAL_TOOLS = Set.of(
            "search_knowledge", "searchKnowledge");

    private FrameworkCtxParts() {
    }

    /**
     * 按框架结构切出本轮的上下文分类用量。
     *
     * @param messages     框架会话的全量 transcript（跨轮常驻）
     * @param historyBefore 本轮开跑前的消息条数——{@code index < historyBefore} 即「上一轮及更早」
     * @param toolSchemas  本轮请求下发的工具声明（{@code tools} 字段），可为 null（没挂工具时）
     * @param capturedSystem 本轮真实下发的 SYSTEM 消息（模型调用请求取景），可为空表（回落 transcript）
     * @param realPrompt   网关实测的本轮 prompt（最终轮输入 token）；<=0 时不校准，如实给估算
     * @return 分类 → token 数，键序即前端渲染序；估不出任何一段时返回空表（前端退化为「无明细」）
     */
    static Map<String, Integer> of(List<Msg> messages, int historyBefore,
                                   List<ToolSchema> toolSchemas, List<Msg> capturedSystem,
                                   long realPrompt) {
        Map<String, Integer> parts = new LinkedHashMap<>();
        if (messages == null) return parts;
        int from = Math.min(Math.max(historyBefore, 0), messages.size());

        // 系统提示词优先用请求侧取景的那份：transcript 里没有它（见类注释）。
        // 取景为空才回落数 transcript 里的 SYSTEM——两条路都空时这一段干脆不出现。
        int captured = 0;
        if (capturedSystem != null) {
            for (Msg m : capturedSystem) {
                if (m != null) captured += textTokens(m);
            }
        }
        boolean useCapture = captured > 0;

        int system = 0;
        int history = 0;
        int input = 0;
        int retrieval = 0;
        int toolOutput = 0;
        int thinking = 0;

        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (m == null) continue;
            MsgRole role = m.getRole();
            // SYSTEM：取景路径已经有它时这里必须跳过，否则同一段字符被数两次
            if (role == MsgRole.SYSTEM) {
                if (!useCapture) system += textTokens(m);
                continue;
            }
            if (i < from) {
                // 历史对话：用户与助手合算。ASSISTANT 里的 thinking 已在下面单独计，这里要扣掉，
                // 否则同一段字符会被数两次（历史里的旧思考不该计入「本轮思考」——
                // 它在上一轮就付过费了，混进来会让本轮思考量虚高）。
                history += textTokens(m) - thinkingTokens(m);
                continue;
            }
            // 本轮新增：USER 是这轮的问题，其余按内容块类型分
            if (role == MsgRole.USER) {
                input += textTokens(m);
            } else if (role == MsgRole.TOOL) {
                int retrievalTokens = retrievalTokens(m);
                retrieval += retrievalTokens;
                toolOutput += textTokens(m) - retrievalTokens;
            } else if (role == MsgRole.ASSISTANT) {
                // 本轮助手输出不构成「历史」——它就是这一轮的生成物，生成侧的用量已在 output 里，
                // 再塞进上下文分类就是同一段字符算两次。只有 thinking 单独成段（它是实打实进上下文的）。
                thinking += thinkingTokens(m);
            }
        }

        put(parts, "system", useCapture ? captured : system);
        put(parts, "history", history);
        put(parts, "input", input);
        put(parts, "retrieval", retrieval);
        put(parts, "toolOutput", toolOutput);
        put(parts, "thinking", thinking);
        put(parts, "toolSchema", schemaTokens(toolSchemas));
        return calibrate(parts, realPrompt);
    }

    /** 检索类工具的结果块占用；非检索类返回 0（由调用方归入「工具结果」）。 */
    private static int retrievalTokens(Msg m) {
        int sum = 0;
        for (ContentBlock block : m.getContent()) {
            if (block instanceof ToolResultBlock tr && tr.getName() != null
                    && RETRIEVAL_TOOLS.contains(tr.getName())) {
                sum += outputTokens(tr.getOutput());
            }
        }
        return sum;
    }

    /**
     * 一条消息的文本量（不含 thinking——它有自己的分类）。
     * <p>TOOL 消息要单独走一支：它的内容块是 {@link ToolResultBlock}（装着工具返回的 output），
     * 不是 {@link TextBlock}。按 TextBlock 取会一条都取不到、算出 0——于是
     * 「工具结果 = 总量 − 检索量」变成负数被丢掉，整段凭空消失（真机实测：非检索类工具
     * 一条都不显示，而检索段还在，看起来像「只有检索占上下文」）。
     */
    private static int textTokens(Msg m) {
        if (m instanceof SystemMessage || m instanceof UserMessage || m instanceof AssistantMessage) {
            return TokenCounter.estimate(m.getTextContent());
        }
        if (m.getRole() == MsgRole.TOOL) {
            int sum = 0;
            for (ContentBlock block : m.getContent()) {
                if (block instanceof ToolResultBlock tr) sum += outputTokens(tr.getOutput());
            }
            return sum;
        }
        return contentTokens(m.getContent(), TextBlock.class);
    }

    /** 一条消息里 thinking 块的文本量。 */
    private static int thinkingTokens(Msg m) {
        return contentTokens(m.getContent(), ThinkingBlock.class);
    }

    /** 工具声明的 schema 量：按「名称 + 描述 + 入参」估算，与手写侧 {@code ToolDefinition} 同口径。 */
    private static int schemaTokens(List<ToolSchema> schemas) {
        if (schemas == null || schemas.isEmpty()) return 0;
        int sum = 0;
        for (ToolSchema s : schemas) {
            if (s == null) continue;
            sum += TokenCounter.estimate(s.getName() + " " + s.getDescription() + " "
                    + (s.getParameters() == null ? "" : s.getParameters()));
        }
        return sum;
    }

    private static int contentTokens(List<ContentBlock> blocks, Class<? extends ContentBlock> type) {
        if (blocks == null || blocks.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (type.isInstance(b)) sb.append(textOf(b));
        }
        return TokenCounter.estimate(sb.toString());
    }

    private static int outputTokens(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : output) sb.append(textOf(b));
        return TokenCounter.estimate(sb.toString());
    }

    /** 内容块的文本：文本块给 {@code getText}，其余（图片/音视频）按固定权重折算——
     * 直读图片是这轮上下文的真实占用，不计就凭空少一块。 */
    private static String textOf(ContentBlock b) {
        if (b instanceof TextBlock t) {
            String s = t.getText();
            return s == null ? "" : s;
        }
        if (b instanceof ThinkingBlock th) {
            String s = th.getThinking();
            return s == null ? "" : s;
        }
        if (b == null) return "";
        // 非文本块：按类型名折一段固定长度，保证「有就占位」，不至于被当成 0
        return "" + b.getClass().getSimpleName();
    }

    private static void put(Map<String, Integer> parts, String key, int v) {
        if (v > 0) parts.put(key, v);
    }

    /**
     * 按网关实测 prompt 等比校准，并让各类之和严格等于它（舍入误差归到最大的一段）。
     * <p>与 {@code TurnRecorder} 侧同源同算法：容量条的「分类之和 = 网关 prompt」这条不变量
     * 两条运行时共用一把尺，不能一个校准一个不校准。
     * 缩放系数用「已缩放后的值」补差，否则末段会被还原回缩放前的值、和偏离 prompt。
     */
    private static Map<String, Integer> calibrate(Map<String, Integer> parts, long realPrompt) {
        if (parts.isEmpty() || realPrompt <= 0) return parts;
        long sum = 0;
        for (int v : parts.values()) sum += v;
        if (sum <= 0) return parts;
        double k = realPrompt / (double) sum;
        Map<String, Integer> out = new LinkedHashMap<>();
        long acc = 0;
        String largestKey = null;
        int largest = -1;
        for (Map.Entry<String, Integer> e : parts.entrySet()) {
            int v = (int) Math.round(e.getValue() * k);
            // 缩放后可能把原本 >0 的小段压成 0：丢掉它，别让容量条画出一条零宽的段
            if (v <= 0) continue;
            out.put(e.getKey(), v);
            acc += v;
            if (v > largest) {
                largest = v;
                largestKey = e.getKey();
            }
        }
        if (largestKey != null) {
            out.put(largestKey, out.get(largestKey) + (int) (realPrompt - acc));
        }
        return out;
    }
}
