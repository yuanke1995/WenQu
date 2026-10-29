package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当作已退役的控制流删除。

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.subagent.SubAgentConsultService;

import java.util.Map;

/**
 * 两条运行时共用的<b>记录口径</b>（2026-10-15 起只剩静态件）。
 *
 * <p>原先是绑在 {@code TurnState}（手写运行时的状态袋）上的记录 bean：工具卡、时间线、落库、用量快照
 * 全在这里。推理循环删除后那些实例方法随状态袋一起退场，留下的是框架引擎仍在用的几件公共口径——
 * 终态标记、暗截断判据、咨询子对话卡。它们之所以还单独在：两条运行时（今天实际只剩框架引擎）
 * 读的是同一份常量与同一个判据，抄第二份的结果就是两边阈值各自演进。
 *
 * <p>三条口径别在改动中丢：
 * <ul>
 *   <li><b>终态标记必须分开写</b>：用户叫停、通道中断、服务重启、整轮失败、长度截断、内容丢失
 *       是六件不同的事，用户与审计都要分得开；</li>
 *   <li><b>暗截断判据必须含思考字符</b>——deepseek 这一族关着思考也照样回 reasoning，
 *       不算进去就是轮轮误报；</li>
 *   <li>咨询子对话卡只挂<b>终态那一份</b>，并按被咨询那位 id 从台账取走（一轮可问多位，共用槽会串台）。</li>
 * </ul>
 */
public final class TurnRecorder {

    private TurnRecorder() {
    }

    /**
     * 用户主动叫停本轮时追加在正文尾部的标记。与 {@link #TRUNCATION_SUFFIX} 区分：那是通道断开/
     * 看门狗掐的，这是用户按下的停止——历史里要能看出「这一轮是我让它停的」，而不是「回答自己断了」。
     */
    public static final String STOP_SUFFIX = "\n\n> ⏹ 已停止本轮，以上为已生成的部分";

    /**
     * 中断兜底落库时追加在正文尾部的截断标记：刷新/历史可见的 fail-loud 提示。
     * 落在时间线区间之外，前端 restore 后由尾段兜底渲染成独立的引用块。
     */
    public static final String TRUNCATION_SUFFIX = "\n\n> ⏹ 回答在此处被中断，以上为已生成的部分";

    /** 生成量与正文字数严重不符（内容在生成链路里丢失）时的用户可见标注 */
    public static final String OUTPUT_LOST_SUFFIX =
            "\n\n> ⚠️ 这次回答不完整，有内容在生成过程中丢失，建议重新生成";

    /**
     * 整轮机器预算用尽的那句话（唯一口径）。三处共用，不许各写一遍：
     * 存活看门狗的 live warn、框架引擎收尾的落库 warn、以及正文尾部的截断标注。
     * <p>原先看门狗那句是「回答超时已截断，请重试或缩短问题」——对长任务那是错指引：活干到一半，
     * 该说的是「发一句继续」，不是「把问题改短」，也不是让人以为重试同一条命令能接着跑。
     */
    public static final String TURN_BUDGET_NOTE =
            "已到整轮时间上限，后面的步骤没有执行。发一句「继续」可以接着做";

    /** 正文尾部的预算截断标注（刷新/历史可见的那一份） */
    public static final String BUDGET_SUFFIX = "\n\n> ⏹ " + TURN_BUDGET_NOTE;

    /** 生成量对账门槛：最终轮输出不足此 token 数时不参与对账（短回答的字符/token 比波动太大） */
    private static final int OUTPUT_LOSS_MIN_TOKENS = 150;

    /** 生成量对账比例：中文字符/token 实测 0.7~1.4，低于 0.15 才认定有内容丢失（留足误判余量） */
    private static final double OUTPUT_LOSS_CHAR_PER_TOKEN = 0.15;

    /** 工具入参/结果分级截断的全文上限：前端工具卡片展开查看完整入参/输出（8KB） */
    private static final int TOOL_IO_MAX = 8 * 1024;

    /** 子对话卡里那份「它的回答」超长时的收尾说明——截断要写在脸上，不能看着像它只说了这么多 */
    private static final String ANSWER_CLIP_SUFFIX = "\n\n…（它的回答过长，卡片内已截断；完整回报见本卡「输出」）";

    /**
     * 「暗截断」判据（网关没明说 length，但生成量与已交代字符数严重不符 ⇒ 内容在剥离环节丢了）。
     * <p>收成一处是因为两条运行时都要跑它：把常量抄第二份的结果是两边阈值各自演进，
     * 同一件事在一条路上报错、在另一条路上静默。已交代字符数<b>必须含思考字符</b>——
     * deepseek 这一族关着思考也照样回 reasoning，不算进去就是轮轮误报。
     */
    public static boolean looksLikeOutputLost(long finalRoundOutput, int accountedChars, boolean realUsage) {
        return realUsage && finalRoundOutput >= OUTPUT_LOSS_MIN_TOKENS
                && accountedChars < finalRoundOutput * OUTPUT_LOSS_CHAR_PER_TOKEN;
    }

    /**
     * 从派遣调用的入参里取被咨询那位的 id（取不到返回 null：宁可少一张卡，不拿名字去猜归属）。
     * <p>两个键都要认：自研那把工具写的是 {@code agentId}，框架的 {@code agent_spawn} 写的是
     * {@code agent_id}——两条运行时共用这一处判据，分两处读早晚漏一条。
     */
    private static String consultTarget(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            JSONObject j = JSON.parseObject(input);
            if (j == null) return null;
            String id = j.getString("agent_id");
            return id == null || id.isBlank() ? j.getString("agentId") : id;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 咨询卡片要挂的那份「子对话」：按被咨询那位的 id 从本轮台账取走（取走即摘），并裁到卡片长度上限。
     * <p>公开这一个口子是因为并列的第二套运行时（AgentScope 框架引擎）也要挂同一张卡：
     * 截断长度与「只挂终态那一份」的口径分两处写，早晚一边裁了另一边没裁。
     *
     * @param input 这次派遣调用的入参 JSON（取被咨询那位的 id 用）
     * @return 子对话；本轮没有可挂的回报时返回 null
     */
    public static Map<String, Object> consultCardSubChat(
            SubAgentConsultService.Ledger ledger, String input) {
        if (ledger == null) return null;
        Map<String, Object> subChat = ledger.takeReport(consultTarget(input));
        if (subChat == null) return null;
        // 与入参/输出同一把长度尺：一条跑偏的长回答不该把整条消息行撑爆
        if (subChat.get("answer") instanceof String s && s.length() > TOOL_IO_MAX) {
            subChat.put("answer", s.substring(0, TOOL_IO_MAX) + ANSWER_CLIP_SUFFIX);
        }
        return subChat;
    }
}
