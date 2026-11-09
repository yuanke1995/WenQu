package com.wenqu.ai.agent.chat;

// 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件。

import com.wenqu.ai.dto.ChatRequest;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 一轮问答的入参对象（把 {@code ChatService.chat(...)} 的十个 telescoping 重载收成这一个）。
 * <p>
 * 为什么值得动：{@code chat()} 若用重载层层委托（10 参 → 11 参 → … → 19 参），
 * 每加一个能力就往尾部加一个参数、再补一个重载。后果不是难看，而是三件具体的事：
 * <ul>
 *   <li><b>位置错位不可见</b>：{@code (String, String, List, List, List, boolean, String, String, String, ...)}
 *       这种签名里，两个相邻的同类型实参写反了编译器不会拦——插话、重新生成、@ 引用三个功能都在这一串里传值。</li>
 *   <li><b>子智能体接不上来</b>：分支要跑完整循环、并继承父轮的上下文摘要与深度预算，
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
     * 本轮附件字节所在的<b>上传目录归属者</b>（空=同 {@link #userId}）。
     * <p><b>为什么必须与 userId 分开</b>：附件是 multipart 上传换 fileId、按 uid 分目录落盘的，
     * 读回时也要拿同一个 uid 才找得到。登录态两者天然同一人；但免登录分享链路里，
     * {@link #userId} 是<b>发布者</b>（检索可见性、默认模型都按他执行），而文件是<b>访客</b>传的、
     * 落在访客自己的目录里——拿发布者的 uid 去读，访客的附件在模型侧一律读成"已过期或不存在"
     * （上传成功、也能落库回显，唯独正文进不了上下文，症状很像"模型没认真看"）。
     * <p>所以这里显式区分「按谁的身份作答」与「去谁的目录取材料」，不再让一个字段兼职两件事。
     */
    final String attachmentOwnerUid;
    /**
     * 思考强度档位（用户显式选的才传；落在模型支持档位内才生效，否则回落该模型默认档位）。
     * null = 本轮不指定强度，只有深度思考开关。
     */
    final String reasoningLevel;
    /** 免登录分享会话（公开链接）：能力白名单收窄、另有恢复语义 */
    final boolean guestMode;
    /**
     * 本轮由 API Key 发起：Key 只开放问答链路，沙盒/文件/命令工具一律不挂。
     * <p>与 guestMode 分开：游客是“没有登录态”，API Key 是“有代表账号但能力面收窄”，
     * 两者后续可能继续分化（例如 Key 是否允许 MCP），不能复用同一个布尔。
     */
    final boolean apiKey;
    /**
     * 发起本调用的 API Key id：用量台账按它落 c_ai_usage_log.api_key_id（Key 主键为 VARCHAR），
     * 供按 Key 对账。仅 X-Api-Key 通道由控制器从拦截器属性取值传入，其余调用方恒为 null
     * （与 {@link #apiKey} 布尔位分开：那个管能力面收窄，这个管计量归属）。
     */
    final String apiKeyId;
    /**
     * 这一轮的<b>访客身份</b>（{@code share-visitor:{客户端随机}}，仅 guestMode 有值）。
     * <p>接流总线按它认归属，不能用 {@link #userId}——后者是<b>发布者</b>（免登录问答要按发布者的
     * 资料可见性检索）。少了这一个，要么分享页接不上流，要么任何人都能接走别人会话的流。
     */
    final String guestUid;
    /**
     * 游客轮所属分享 id（仅 guestMode 有值）：用量台账按它聚合 token，
     * 供分享级日额度闸在下一轮前判断是否超限。
     */
    final String shareId;
    /**
     * 游客轮经发布者显式授权的 MCP server 名单（仅 guestMode 有值；空/缺=未授权，MCP 照旧不挂）。
     * <p>从分享配置的 {@code guest_mcp_servers} 来，经 ShareController 填进命令——工具装配
     * （{@code McpToolProvider}）与执行期预批（{@code WenquAgentxTools#mountFrom}）都读这一份，
     * 名单之外的个人 MCP server 对游客照旧不可见。
     */
    final java.util.Set<String> guestMcpServers;
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
     * 重新计划所基于的旧计划批准 ID（仅计划模式有效）：服务端用它把上一版计划与执行进度拼进本轮提示词，
     * 并随新计划记录落血缘。不存在/不属于本会话/未批准时为空，本轮照常出计划。
     */
    final String planBaseId;
    /** 旧计划是第几版（按同一轮内 planApproval 记录顺序数；无血缘时为 0） */
    final int planBaseVersion;
    /** 旧计划那一轮的执行清单统计（无清单/无血缘均为 0） */
    final int planBaseDone;
    final int planBaseTotal;
    final int planBaseFailed;
    /** 拼进本轮用户消息的「上一版计划 + 执行进度」上下文段（空=没有血缘） */
    final String planBaseContext;
    /**
     * 本轮是**插话续发**：用户在这轮回答跑到一半时补了一句方向，框架那一轮没收下它（撞步数上限、
     * 被叫停或提前收尾），随 done 交回前端后作为新一轮发出。
     * <p>为什么必须显式带标记：作为新一轮发出去时，模型只看到一条孤零零的新用户消息，会当成
     * 「用户又问了一遍」重新起题。
     * 那条上下文声明由 {@link #decorateSteerQuestion(String)} 统一拼在那句话后面，与送进框架会话的
     * 那一条同一个出口。
     */
    final boolean fromSteer;

    /**
     * 本轮由谁派出（P3a 子智能体真全循环）：0 = 用户在对话里发起的轮，≥1 = 第 N 层子智能体分支。
     * <p>这个数在流水线里管三件事，而且必须同源：
     * <ul>
     *   <li><b>还能不能再往下派</b>——分支自己也有子智能体时，到顶（当前层数上限）
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
     * 本轮已处于的<b>工作流嵌套深度</b>（对外能力第二批·工作流当工具）：0 = 顶层轮；
     * 1 = 工作流的子智能体节点起的这一轮；2 = 又一层。与 {@link #subagentDepth} 的分工：
     * 那个数管「框架派遣分支」的递归，这个数管「智能体↔工作流」的相互调用递归——
     * 工作流工具族的装配判据（到顶不挂）与工作流运行入口的 fail-loud 拒绝（
     * {@code WorkflowService.runForAgent}）同源读它。默认 0：所有既有调用方行为逐字不变。
     */
    final int wfDepth;
    /**
     * 父轮交代给这一支的上下文（继承父历史，对标 fork-in-process）：父轮问题 + 最近若干轮对话，
     * 字符预算已按深度裁剪。仅 {@link #subagentDepth} &gt; 0 时有值；null/空 = 不带。
     */
    final String parentBrief;
    /** 派出这一支的父会话 id（日志关联用；产物收养在分支执行器内直接按它落，不经本字段传递语义） */
    final String parentSessionId;
    /** 派出这一支的父轮 id：审批卡上翻后刷新恢复要挂回父轮气泡（仅分支轮有值） */
    final String parentTurnId;
    /**
     * 父轮生效的审批档位（auto/ask/off）。仅分支轮有值；子智能体自己<b>未显式</b>设置
     * {@code tool_approval_mode} 时继承它（显式 &gt; 继承 &gt; 预设），避免「父 auto、子落预设 ask」
     * 挂出一张没人看的审批卡。null = 不继承（顶层轮）。
     */
    final String inheritedApprovalMode;

    /**
     * 本轮的<b>发起方身份</b>（工作区轮次守卫展示用）：{@code user}=网页问答（缺省）、
     * {@code scheduled}=定时任务、{@code workflow}=工作流子智能体节点、{@code eval}=智能体评测回归
     * （非 user 即无在场观众：需人工确认的工具按「无人可问」拒绝——评测只读语义的落点）。
     * 只进租约与报错文案，不参与任何路由/权限判断。
     */
    final String source;
    /**
     * 调用变量（嵌入方随轮传入的业务上下文）：由控制器经 {@link #normalizeVariables(Map)} 归一后传入，
     * {@code TurnPromptAssembler} 注入本轮用户消息的「调用方上下文」段。
     * <p><b>为什么进用户消息而不是系统提示词</b>：系统提示词按会话烤在常驻引擎实例里
     * （实例指纹不含请求内容），轮级变量进 sysPrompt 要么每轮重建实例、要么打爆供应商前缀缓存；
     * 用户消息侧每轮现装，零缓存代价。null = 本轮无变量（上下文段整段缺席，与旧行为逐字节一致）。
     */
    final java.util.Map<String, String> variables;
    /**
     * 发起方的展示名（定时任务名 / 工作流子智能体名）。网页问答不传——守卫侧按会话标题展示。
     * 有了它，工作区被占时的报错才能从含糊的「另一个任务在跑」变成「定时任务『周报』正在跑」。
     */
    final String sourceName;


    /**
     * 服务端收到这一问的墙上时刻（epoch ms）。0 = 调用方没给（定时任务/工作流/子轮等没有前端
     * 请求体的入口）。
     * <p>为什么要在控制器入口就记：气泡顶部报的是「从按下发送到答完」的墙钟，而机器耗时从
     * {@code submit} 之后才起表——两者差着排队、限流、会话补建、实例装配那几秒。只落机器时间，
     * 刷新一次数字就变小。带上它才能把同一本账写到库里。
     */
    final long receivedAtMs;

    /** 是否处于子智能体分支里（{@link #subagentDepth} &gt; 0 的唯一判定点，别在调用方各写一遍） */
    public boolean branchTurn() {
        return subagentDepth > 0;
    }

    /**
     * 本轮附件该去谁的目录取：显式指定了上传归属者就用它，否则与提问用户同人
     * （登录态、定时任务、工作流节点、MCP 调用都是这一档；只有免登录分享会显式指定）。
     */
    public String uploadOwnerUid() {
        return attachmentOwnerUid == null || attachmentOwnerUid.isBlank() ? userId : attachmentOwnerUid;
    }

    /** 只带最小三要素的命令（后台续跑、子 agent、定时任务这类没有前端请求体的调用方用） */
    public static ChatCommandBuilder of(String sessionId, String question, String userId) {
        return builder().sessionId(sessionId).question(question).userId(userId);
    }

    /** 调用变量的上限口径（登录态与游客链路同一把尺，收口在这） */
    public static final int VARIABLES_MAX_ENTRIES = 32;
    public static final int VARIABLES_VALUE_MAX_CHARS = 4096;
    public static final int VARIABLES_TOTAL_MAX_CHARS = 32 * 1024;

    /**
     * 调用变量归一化（fail-loud：坏键名/超限直接 400——静默丢弃会让宿主「明明传了上下文」
     * 而模型「明明没收到」，两边都对不上账）。空键跳过；返回 null = 无有效变量。
     */
    public static java.util.Map<String, String> normalizeVariables(java.util.Map<String, String> in) {
        if (in == null || in.isEmpty()) return null;
        if (in.size() > VARIABLES_MAX_ENTRIES) {
            throw new com.wenqu.ai.common.BizException("调用变量最多 " + VARIABLES_MAX_ENTRIES + " 项");
        }
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        int total = 0;
        for (java.util.Map.Entry<String, String> e : in.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            String v = e.getValue() == null ? "" : e.getValue();
            if (k.isEmpty()) continue;
            if (!k.matches("[A-Za-z0-9_]{1,64}")) {
                throw new com.wenqu.ai.common.BizException(
                        "变量名不合法：" + k + "（仅限字母/数字/下划线，最长 64 字符）");
            }
            if (v.length() > VARIABLES_VALUE_MAX_CHARS) {
                throw new com.wenqu.ai.common.BizException("变量 " + k + " 超长（单值最多 "
                        + VARIABLES_VALUE_MAX_CHARS + " 字符）");
            }
            total += k.length() + v.length();
            if (total > VARIABLES_TOTAL_MAX_CHARS) {
                throw new com.wenqu.ai.common.BizException("调用变量总量超限（最多 "
                        + (VARIABLES_TOTAL_MAX_CHARS / 1024) + "KB）");
            }
            out.put(k, v);
        }
        return out.isEmpty() ? null : out;
    }

    /** 插话的身份声明（只进模型上下文，用户看不见） */
    private static final String STEER_NOTE =
            "（说明：这句是用户在上一轮回答还在跑的时候补的方向，不是一次新的提问。"
                    + "请按它调整你正在做的这件事并继续，不要重新起题，也不要复述这句说明。）";

    /**
     * 给插话拼上身份声明。两条路共用这一个出口：送进框架会话的那一条，和本轮没收下、
     * 随收尾交回前端后作为新一轮补发的那一条（{@link #fromSteer}）。
     * <p>不带它，模型只看到一句孤零零的新用户消息，会当成「用户又问了一遍」重新起题，
     * 插话「改了方向立刻生效」就废了。
     */
    public static String decorateSteerQuestion(String text) {
        String t = text == null ? "" : text.strip();
        return t.isEmpty() ? STEER_NOTE : t + "\n" + STEER_NOTE;
    }
}
