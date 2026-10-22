package com.wenqu.ai.memory;

import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;

/**
 * 问渠的记忆提示词：框架默认 prompt + 产品规则。
 *
 * <p>默认 prompt 是通用 Agent 的口径（「重要技术决策及其理由」「关系背景」都要记），
 * 放到问渠这种通用工作台会把测试过程、检索片段、一次性任务细节一起收进长期记忆。
 * 这里追加的规则只做三件事：限定「值得记」的范围、要求用用户语言、禁止记录密钥/大段原文。
 *
 * <p><b>consolidation prompt 必须恰好包含两个 {@code %d}</b>（max-tokens、max-chars），
 * 否则框架 Builder 在构造时就拒绝；追加的规则里因此不能出现任何 {@code %} 字符。
 */
public final class MemoryPrompts {

    private MemoryPrompts() {
    }

    static final String FLUSH_RULES = """

            Additional rules for WenQu:
            - Write in the user's language (Chinese for Chinese conversations).
            - Only record durable user facts, stable preferences, long-lived project context,
              and explicit "remember this" requests.
            - Never record secrets (API keys, passwords, tokens), full document text,
              retrieval snippets, tool logs, test/probe procedures, or one-off task details.
            - One self-contained bullet per fact; include a date when it matters.
            - At most 8 bullets per flush. If nothing durable, output exactly NO_REPLY.
            """;

    static final String CONSOLIDATION_RULES = """

            Additional rules for WenQu:
            - Use the user's language (Chinese for Chinese conversations).
            - Hard limit: the final MEMORY.md MUST NOT exceed the token budget above.
              If you are over, delete the least durable entries first and keep the file complete.
            - Prefer durable user facts, stable preferences, long-lived project context, and
              decisions the user explicitly asked to keep.
            - Drop test/probe procedures, retrieval snippets, tool logs, full document text,
              secrets, and one-off task details.
            - Keep the existing section headers stable; merge related bullets instead of repeating them.
            """;

    /** Flush（路径 1）使用的 SYSTEM prompt。 */
    public static String flushPrompt() {
        return MemoryFlushManager.DEFAULT_FLUSH_PROMPT + FLUSH_RULES;
    }

    /** Consolidation（路径 2）使用的模板；默认模板自带两个 {@code %d} 占位符。 */
    public static String consolidationPrompt() {
        return MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT + CONSOLIDATION_RULES;
    }

    /**
     * 挂进主对话 sysPrompt 的一条口径：记忆维护对用户不可见。
     *
     * <p><b>为什么要有这一处</b>：框架往 SYSTEM 注入「Update MEMORY.md proactively」引导，
     * {@code memory_search} 又能看到日账里「## Memory Flush — …」的标题样式，弱模型会把
     * 「维护记忆」理解成「在回复里打印一份 Memory Flush 报告」（2026-10-22 线上实测，
     * glm-5.3-flash 在问答回复里输出 20+ 条记忆簿记）。上面 {@link #FLUSH_RULES} 那套
     * 「记什么、记几条」只约束后台提炼调用，管不到主模型——主模型缺的是对偶的那半句：
     * 「要记就用工具静默记，别在回复里演」。
     */
    public static String replyDiscipline() {
        return "\n【记忆维护不可见】记忆沉淀由系统在对话结束后自动完成；确有需要主动记的内容，"
                + "调用 memory_save 工具静默完成即可。回复正文里严禁出现「Memory Flush」「记忆提炼/固化/整理」"
                + "等记忆维护过程及其格式，也不要向用户复述你记了什么——记忆簿记是内部过程，"
                + "用户既不需要也没要求看。回复里只放对他提问的回答。\n";
    }
}
