package com.wenqu.ai.agent.chat;

import java.util.regex.Pattern;

/**
 * 引用语法的<b>唯一定义处</b>：正文标记的正则 + 给模型的编号约定文案。
 * <p>此前约定散在三处各写一遍（知识库工具、联网工具、MCP 回报），已经付出过代价：给知识库/联网
 * 加句级归属规则时 MCP 那份没加上——同一件事对模型说了两套话，防线在一条通道上静默漏掉。
 * 语法同理：前端 {@code web/src/utils/markdown.js} 的角标/图片正则与本类必须同认一套标记，
 * 由 CitationSyntaxContractTest 钉住两端一致。
 */
public final class CitationConvention {

    /** 正文里的引用标注 [N]（前端角标渲染认同一语法） */
    public static final Pattern CITE_PATTERN = Pattern.compile("\\[(\\d+)]");

    /**
     * 已编号的图片标记：[图片N] 或 [图片N：描述]。上下文填充与终态越界校验都认这一个语法——
     * 与引用编号 [N] 是两套空间。
     */
    public static final Pattern IMG_NUMBER_PATTERN = Pattern.compile("\\[图片\\d+[^\\]]*\\]");

    /** 句级归属：每个编号指向实际提供该句内容的那一条来源（三条通道共用，历史上 MCP 漏过这一段） */
    private static String attribution() {
        return "每个编号必须指向实际提供了该句内容的那一条来源：先确认该来源原文支撑句子的核心结论，"
                + "再落编号；没有任何来源直接支撑的句子不要标编号；"
                + "多条来源都提到同一件事时，取该句内容出自的那一条。";
    }

    /** 全局编号：多次检索结果编号连续有效、必须原样使用（知识库/联网通道用） */
    private static String globalNumbering() {
        return "引用编号是本轮全局编号：同一次回答里多次检索结果中的编号连续有效、不会再变，"
                + "必须原样使用各结果给出的编号，不得自行重新编号或另起一套。";
    }

    /** 知识库检索结果尾注：句级归属 + 全局编号 + 截图条款 */
    public static String knowledgeBase() {
        return "（回答中引用以上内容时，请在对应句子后用方括号标注上述引用编号，如 [3]。"
                + attribution() + globalNumbering()
                + "若要在回答中插入截图，只能使用上列「本段相关截图」中给出的 [图片N] 编号，"
                + "没有列出的编号一律不要输出——不存在对应的图片）";
    }

    /** 联网搜索结果尾注：句级归属 + 与知识库同等对待 */
    public static String webSearch() {
        return "（回答中引用以上内容时，请在对应句子后用方括号标注上述引用编号，如 [3]。"
                + attribution()
                + "只能使用上列编号，未列出的编号一律不要输出。"
                + "联网来源与知识库来源同等对待，都必须标注）";
    }

    /**
     * MCP 结果尾注：{@code footnotes} 是已拼好的来源清单（[N] 标题（站点）；…）。
     * 句级归属与知识库/联网同一份文案——三通道对模型的规则不许再有第四种说法。
     */
    public static String mcp(String footnotes) {
        return "（本结果包含以下可溯源来源，回答中引用其内容时，请在对应句子后用方括号标注对应编号："
                + footnotes + "。" + attribution() + "未列出的编号一律不要输出）";
    }

    private CitationConvention() {}
}
