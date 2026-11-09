package com.wenqu.ai.service;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 提问工具：{@code askUser}（人在回路）。
 *
 * <p>纯计算类能力模型原生具备，常驻只占 schema；外部世界能力走 MCP/Provider 壳，
 * 因此这里只保留需用户拍板的提问入口。
 *
 * @author yuanke
 */
@Component
public class AskUserTools {

    /** ToolContext 键：askUser 的本轮执行器（ChatService 装配会话态时注入） */
    public static final String CTX_ASK = "wenqu.askUser.fn";

    /** askUser 的单个问题（一卡多问）：话题标签（可空）、问题、选项、是否可多选 */
    public record AskQuestion(
            @ToolParam(description = "话题标签，2~6 字短语（如「晚餐选择」「住宿方案」），便于界面归组展示；可省略") String topic,
            @ToolParam(description = "要问用户的问题：一句话交代背景与要决定的事") String question,
            @ToolParam(description = "候选选项，2~3 个（界面固定为「3 个候选 + 1 行自由输入」，超出会被截断），每项「关键词：一句话说明」；第一项视为推荐项") java.util.List<String> options,
            @ToolParam(description = "该题是否允许多选：只在用户可能同时要几项时填 true（勾选范围、组成部分、可并存的素材）；"
                    + "省略即单选。「两条路选哪条」这类互斥取舍不要设 true", required = false) Boolean multi) {
    }

    /** askUser 执行器签名：一组问题（一卡多问） → 批量答案文本（每问一个答案，并行数组） */
    @FunctionalInterface
    public interface AskFn {
        String ask(java.util.List<AskQuestion> questions);
    }

    /**
     * 向用户提出选择题并等待作答（人在回路），支持一卡多问：可一次传入多个问题（questions 数组），
     * 用户在同一张卡片内逐题翻页、一次性批量作答；只在必须由用户拍板才能继续时调用。
     * 每题 2~3 个候选选项，把最推荐的放在 options 第一位（前端标注「推荐」，供用户参考）；
     * 每个选项用「关键词：一句话说明」格式（如「火锅：热汤驱秋凉」），说明可省略；
     * 用户可点选候选，也可自由输入其他答案。
     * <p>超时或点忽略都<b>不会替用户选</b>：那一题回「（用户未回答这一题…）」，模型须自行判断继续。
     */
    @Tool(description = "向用户提出选择题并等待其作答（人在回路），支持一卡多问。只在必须由用户拍板才能继续时调用："
            + "方案取舍、需求澄清、确认执行范围等；能自行判断或查资料解决的事项不要调用。"
            + "「任务复杂、要做很多步」不是调用本工具的理由：那属于任务清单，直接开工并把进度写成清单即可；"
            + "交付物的整体安排也不要用本工具逐条确认（那属于用户那边的计划卡）。"
            + "一卡多问：传 questions 数组（每项含 question、options，可选 topic），用户在同一张卡片内逐题翻页、一次性批量作答；"
            + "单次最多 6 个问题（超过会被拒绝，请合并问题或分批提问）。每题只给 2~3 个候选选项"
            + "（卡片末行固定是自由输入，候选铺长了就不像选择题），把最推荐的放在 options 第一位"
            + "（界面标注「推荐」，仅供用户参考）；每个选项用「关键词：一句话说明」格式，说明可省略；"
            + "用户可点选候选，也可自由输入其他答案。"
            + "用户超时未答或点忽略时，那一题会返回「（用户未回答这一题…）」而不会替你选定的答案："
            + "此时请基于已有信息继续推进，不要把默认当成用户的选择，也不要反复重新提问。")
    public String askUser(
            @ToolParam(description = "问题列表（一卡多问）") java.util.List<AskQuestion> questions,
            ToolContext toolContext) {
        Object fn = toolContext == null ? null : toolContext.getContext().get(CTX_ASK);
        if (!(fn instanceof AskFn)) {
            return "（askUser 工具当前不可用：请改为在回答正文中直接列出候选选项，请用户回复序号或自行描述。）";
        }
        if (questions == null || questions.isEmpty()) {
            return "（提问内容为空，工具未执行。请给出要问用户的问题后重试。）";
        }
        return ((AskFn) fn).ask(questions);
    }
}
