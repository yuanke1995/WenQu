package com.wenqu.ai.agent.chat;

import com.wenqu.ai.dto.ChatRequest;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 一轮问答的入参对象（P1 seam 化：把 {@code RagService.chat(...)} 的十个 telescoping 重载收成这一个）。
 * <p>
 * 为什么值得动：迁移前 {@code chat()} 有 10 个重载层层委托（10 参 → 11 参 → … → 19 参），
 * 每加一个能力就往尾部加一个参数、再补一个重载。后果不是难看，而是三件具体的事：
 * <ul>
 *   <li><b>位置错位不可见</b>：{@code (String, String, List, List, List, boolean, String, String, String, ...)}
 *       这种签名里，两个相邻的同类型实参写反了编译器不会拦——插话、重新生成、@ 引用三个功能都在这一串里传值。</li>
 *   <li><b>子智能体接不上来</b>：P3a 要分支跑完整循环、并继承父轮的上下文摘要与深度预算，
 *       那意味着第 11 个重载；对象化之后加字段不再动调用方。</li>
 *   <li><b>会话互斥的边界说不清</b>：19 个参数里哪些属于「这一轮问什么」、哪些属于「往哪儿写」
 *       混在一个列表里。本类型只装前者，{@code SseEmitter} 单独作方法入参——它不是这一轮的内容，
 *       是这一轮的出口（工作流子 agent、定时任务、MCP 对外端点用的都是另一种出口）。</li>
 * </ul>
 * 刻意<b>不</b>做默认值归一化：今天有些调用方传 null、有些传空列表，两种语义在下游是有区别的
 * （null=未提供，空集合=明确不提供）。构造时统一成空集合会把那个区别抹掉。
 *
 * @author yuanke
 */
@Builder
@Getter
public final class ChatCommand {

    /** 会话 ID（为空则由流水线新建会话） */
    final String sessionId;
    /** 用户问题原文 */
    final String question;
    /** 本轮上传图片 */
    final List<String> userImages;
    /** 本轮文档类附件（服务端已解析为文本，注入本轮上下文） */
    final List<ChatRequest.Attachment> attachments;
    /** 用户本轮主动选用的技能名（全文注入 system prompt） */
    final List<String> skills;
    /**
     * 输入框 @ 引用：<b>可见性校验由调用方（控制器）完成</b>，这里不再鉴权，只按语义用——
     * kb=本轮检索收窄到这些库；doc=该文档的块不经检索直接前置进上下文。
     */
    final List<ChatRequest.Mention> mentions;
    /**
     * 输入框 # 引用的会话历史消息：<b>同样由调用方完成归属校验并按库回填内容</b>，
     * 这里只负责把内容前置进本轮上下文。
     */
    final List<ChatRequest.HistoryRef> historyRefs;
    /** 是否深度思考：由用户自己的开关 + 模型能力决定，平台不代为路由 */
    final boolean deepThink;
    /** 本轮使用的智能体（为空则默认入口） */
    final String agentId;
    /** 聊天页的会话级模型覆盖（仅用户手动切换时传；引用或遗留名） */
    final String modelOverride;
    /** 提问用户：解析个人默认模型、个人资产（技能/MCP）归属、数据权限都按它 */
    final String userId;
    /**
     * 思考强度档位（用户显式选的才传；落在模型支持档位内才生效，否则回落该模型默认档位）。
     * null = 本轮不指定强度，只有深度思考开关。
     */
    final String reasoningLevel;
    /** 前端请求的上下文窗口（与会话级覆盖、模型登记值一起裁决，见 resolveContextWindow） */
    final Integer requestedContextWindow;
    /** 免登录分享会话（公开链接）：能力白名单收窄、不登记接流总线，另有恢复语义 */
    final boolean guestMode;
    /**
     * 重新生成/自动重试的重发标记：该问题的用户消息已随上一轮<b>即时落库</b>，
     * true 时跳过开始期的用户消息落库，避免重发产生重复历史行。
     */
    final boolean regenerate;
    /** 重新生成时被替换的旧回答消息 ID：落库前软删它，历史里只留最新一版 */
    final String replaceMessageId;
    /** 编辑重发/重新生成的版本组 ID（同一问的多版归组，前端切换查看） */
    final String editVariantGroup;
    /** 计划模式：先出计划卡、批准后执行 */
    final boolean planMode;
    /**
     * 本轮是**插话续发**：用户在这轮回答跑到一半时补了一句方向，它没赶上工具步、随 done 回落成新一轮。
     * <p>为什么必须显式带标记：作为新一轮发出去时，模型只看到一条孤零零的新用户消息，会当成
     * 「用户又问了一遍」重新起题（实测短问答必现，模型会复述「你消息里的 X 这一段…」）。
     * 那条上下文声明由 {@code decorateSteerQuestion} 统一拼在问题前面。
     */
    final boolean fromSteer;

    /**
     * 本轮由谁派出（P3a 子智能体真全循环）：0 = 用户在对话里发起的轮，≥1 = 第 N 层子智能体分支。
     * <p>这个数在流水线里管三件事，而且必须同源：
     * <ul>
     *   <li><b>还能不能再往下派</b>——分支自己也有子智能体时，到顶（{@code agent.subagentMaxDepth}）
     *       就不再往下跑全循环，否则父派子、子派孙，一层层并发乘下去，没有任何一处会报错，只会把
     *       流水线线程池与额度吃满；</li>
     *   <li><b>预算逐层递减</b>——工具步数按层减半（见 {@code resolveMaxToolSteps}）；</li>
     *   <li><b>成本归属</b>——&gt;0 时台账记 {@code kind=subagent}，账能查到是谁派出去的开销；
     *       人在回路那几件（提问卡/任务清单）不再挂载：没有人在看那条临时会话，问了也没人答。</li>
     * </ul>
     * 默认 0：所有既有调用方（网页问答、分享、定时任务、工作流节点）行为逐字不变。
     */
    final int subagentDepth;
    /**
     * 父轮交代给这一支的上下文（继承父历史，对标 fork-in-process）：父轮问题 + 最近若干轮对话，
     * 字符预算已按深度裁剪。仅 {@link #subagentDepth} &gt; 0 时有值；null/空 = 不带。
     */
    final String parentBrief;
    /** 派出这一支的父会话 id（日志关联用；产物收养在分支执行器内直接按它落，不经本字段传递语义） */
    final String parentSessionId;

    /** 是否处于子智能体分支里（{@link #subagentDepth} &gt; 0 的唯一判定点，别在调用方各写一遍） */
    public boolean branchTurn() {
        return subagentDepth > 0;
    }

    /** 只带最小三要素的命令（后台续跑、子 agent、定时任务这类没有前端请求体的调用方用） */
    public static ChatCommandBuilder of(String sessionId, String question, String userId) {
        return builder().sessionId(sessionId).question(question).userId(userId);
    }
}
