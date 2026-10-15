package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当 legacy 控制流删除。

import com.wenqu.ai.agent.subagent.SubAgentConsultService;
import com.wenqu.ai.service.ChatRunBus;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.RerankService;
import com.wenqu.ai.service.UserImageService;
import com.wenqu.ai.util.UsageAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 本轮问答的运行时状态（P6 刀 0 自 {@code ChatService} 的私有内部类 AnswerStreamState 提出顶层）。
 * <p><b>为什么要提出来</b>：原先它是非静态内部类，隐式持有整个问答服务——任何一处「抽服务」都会变成
 * 多个类共享同一个可变口袋，而且这个口袋随时能伸手拿服务上的任何东西。提顶层后它变成一个
 * <b>可传递、可核对的对象</b>：它要的运行时能力必须写在 {@link Deps} 里，构造之外一条都拿不到。
 * <p><b>刀 0 第二步（按归属下沉子结构）之后，这一袋只剩「本轮是谁、跑到哪、花了多少」</b>：
 * <ul>
 *   <li>{@link #refs}——引用池（sources / 图片编号 / 联网与 MCP 来源注册），刀 5 的收编对象；</li>
 *   <li>{@link #channel}——出口通道与计时（emitter / emitBuf / 心跳 / 订阅句柄 / 分段耗时）；</li>
 *   <li>{@link #tools}——工具表与档位（权限档位、审批名单、DSML 白名单、库界、咨询台账）；</li>
 *   <li>{@link #board}——沙盒脚本运行面板（P3b，作用域是一次运行）。</li>
 * </ul>
 * 留在本体的四类内容是：本轮身份（sessionId/userId/turnId/handle）、生成与时间线缓冲、
 * token 与容量用量、人在回路与落库归属。它们要等刀 4/5 才有自己的归属。
 * <p>三条纪律（改这里前先读）：
 * <ul>
 *   <li>不许反向持有 {@code ChatService}：需要问答侧代做的动作一律经 {@link Deps} 点名列出（刀 5c 之后只剩两件）；</li>
 *   <li>轮次登记（本对象、身份把手、耗时台账）都只经 {@link TurnRegistry} 进出，别在这里存静态引用；</li>
 *   <li>「本轮已收尾」的幂等闸只有 {@link #claimTerminal()} 一个入口，两条终态路径共用它。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
public final class TurnState {

    /** 中断兜底落库时追加在正文尾部的截断标记：刷新/历史可见的 fail-loud 提示。
     *  落在时间线区间之外，前端 restore 后由尾段兜底渲染成独立的引用块 */
    public static final String TRUNCATION_SUFFIX = "\n\n> ⏹ 回答在此处被中断，以上为已生成的部分";

    /**
     * 用户主动叫停本轮时追加在正文尾部的标记。与 {@link #TRUNCATION_SUFFIX} 区分：那是通道断开/
     * 看门狗掐的，这是用户按下的停止——历史里要能看出「这一轮是我让它停的」，而不是「回答自己断了」。
     */
    public static final String STOP_SUFFIX = "\n\n> ⏹ 已停止本轮，以上为已生成的部分";

    /** 入库原文里的图片占位：[图片] 或 [图片：描述]（尚未编号；填充上下文时替换成编号形态） */
    public static final Pattern IMG_PLACEHOLDER_PATTERN = Pattern.compile("\\[图片(：.*?)?\\]");

    /** 已编号的图片标记：[图片N] 或 [图片N：描述]。上下文填充与终态越界校验都认这一个语法（单源） */
    public static final Pattern IMG_NUMBER_PATTERN = Pattern.compile("\\[图片\\d+[^\\]]*\\]");


    /**
     * 本轮状态需要的运行时依赖：构造时显式交进来，之后不再从别处拿。
     * <p>刀 5a 之后只剩两件——「本轮还挂在运行总线上吗」与「半程正文怎么落」。
     * 原先那四件（配置/重排/图片签名/咨询工厂）都只服务引用池与工具面，现在由它们自己按窄接口拿着。
     */
    public record Deps(ChatRunBus runBus, java.util.function.BiConsumer<TurnState, String> persistPartial) {
    }

    // ==================== 四个按归属下沉的子结构（刀 0 第二步） ====================

    /** 引用池：一条 [1..N] 编号空间 + 来源清单与图片编号（刀 5a 起由流水线建好交进来，本对象不再代凑依赖） */
    public final TurnSourceRegistry refs;
    /** 出口通道与计时 */
    public final TurnChannel channel;
    /** 本轮工具表与权限档位 */
    public final TurnToolset tools;
    /** 沙盒脚本的运行面板（P3b；作用域是一次运行，每次 run_code 起头复位） */
    public final TurnRunBoard board = new TurnRunBoard();

    // ==================== 本轮身份 ====================

    /** 本轮状态本身（构造时交进来，供 {@link Deps#persistPartial} 那类回调使用） */
    public final Deps deps;
    public final String sessionId;
    public final String question;
    /** 本轮所属用户 uid：技能/MCP 都是个人资产，取工具与取技能时只认它 */
    public final String userId;
    /**
     * 本轮身份把手（turnId + userId 的来源）：沿用 {@code chat()} 入口发的那一个；
     * 非 chat 入口（手动压缩等）没有把手，自造一个、也自己摘。
     */
    public final TurnHandle handle;
    /**
     * 本轮唯一 ID。计划卡、子智能体编排、检索统计这些事件都早于本状态构造，
     * 共用同一个 ID 才拼得回一条完整轨迹。
     */
    public final String turnId;
    /** 本轮上传的图片（多模态输入侧，与引用池的图片编号是两套东西） */
    public final List<UserImageService.UserImage> userImgs;
    public final String queryForLog;
    public final String[] thinkingHolder;
    public final List<Map<String, String>> degradations;
    public final Set<String> degradedCodes;
    public final String retrievedJson;

    // ==================== 生成与时间线缓冲 ====================

    public final StringBuilder fullResponse = new StringBuilder();
    public final StringBuilder relatedBlock = new StringBuilder();
/** 本轮问答的工具调用过程记录（name/args摘要/status/耗时），实时发 tool_status SSE + done 汇总 + 持久化 */
    public final java.util.List<Map<String, Object>> toolCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** 本轮回答时间线：正文区间段 {kind:'text',from,to} 与工具/产物下标段 {kind:'tool'|'artifact',i} 按发生
     *  顺序排列。随消息落库并在 done 下发，供刷新/历史会话还原「正文与工具卡片交错」的过程视图——
     *  此前只落整段正文 + 工具终态列表，刷新后只能整段渲染、工具与产物堆在气泡底部。 */
    public final java.util.List<Map<String, Object>> timeline = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** 时间线锚点：下一个文本段的起点（已记入时间线的正文长度），工具/产物插入时先把锚点前的正文成段 */
    public final java.util.concurrent.atomic.AtomicInteger timelineAnchor = new java.util.concurrent.atomic.AtomicInteger(0);
    /** 过程独白（<process> 标签内）：与正文分流累积，随消息落库（process_text 列）并经 SSE process 事件实时下发 */
    public final StringBuilder processResponse = new StringBuilder();
    /** 过程时间线锚点：下一个过程段的起点（已记入时间线的过程独白长度），工具/产物插入时先成段 */
    public final java.util.concurrent.atomic.AtomicInteger processAnchor = new java.util.concurrent.atomic.AtomicInteger(0);
    /** 未闭合 &lt;process&gt; 开口的增量下发状态：processOpen=本开口已下发过首块（首块剥前导空白，
     *  其后原样追加）；openProcessChars=本开口已下发字符数（长时间不闭合时 fail-loud 提示一次） */
    public volatile boolean processOpen;
    public volatile int openProcessChars;
    public volatile boolean processMalformedNoted;

    // ==================== 本轮模型与模式 ====================

    /** 本轮生效模型（会话覆盖 > 个人默认；智能体不绑定模型。主链路解析后回填，生成流按此发送） */
    public volatile String model;
    /** 归一后的深度思考（生效模型能力 + 用户开关）；随 done 写 QA 日志 deep_think */
    public volatile boolean deepThink;
    /** 本轮思考强度档位（请求级优先，null=只有思考开关、无强度语义）；生成流按此下发厂商方言字段 */
    public volatile String reasoningLevel;
    /** 游客分享会话（公开链接）：工具白名单收窄为知识检索+内置项，沙盒/产物/MCP/技能不暴露 */
    public volatile boolean guestMode;
    /** 本轮是否已因"上游拒绝 tools"改为不挂工具（作状态标记：降级只登记一次，且换路后不再挂回工具） */
    public volatile boolean toolsDropped;
    /** 「换路重试」的一次性闸门：toolsDropped 不能当闸门用——它换路后一直是 true，会让后续每次无 token 失败都短路透过重试次数上限、无限重建同一条死流 */
    public volatile boolean toolsRetryQueued;
    /** 本轮已重试次数（上游拒绝 tools 的换路与常规重试共用这条额度，见 chat.streamRetryCount） */
    public final java.util.concurrent.atomic.AtomicInteger retried = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 检索-反思循环模式（Agentic RAG，runChat 按配置与思考档位判定后回填）：开启时本轮强制暴露
     * 知识检索工具（enabledToolCallbacks 两分支 ||），工具结果尾部附「证据充分性自评」约束
     * （KnowledgeRetrievalTool.setReflective 按轮注入）。knowledgeOff / 工作流驱动轮恒为 false。
     */
    public volatile boolean reflectiveRetrieval;
    /**
     * 本轮是否为子智能体分支（P3a）：装配判据（{@code ToolMountContext#turnAllows}）据此
     * 不收「要有人在场」的工具（提问卡等人点、任务清单给人看，而分支那条会话没有观众）。
     * 判据本体在描述符的 {@code viewerRequired} 上，这里只把"这是分支"那个事实带到装配处。
     */
    public volatile boolean branchTurn;

    // ==================== token 与容量用量 ====================

    /** 本轮上下文 token 用量（主链路构建后回填）：实际/预算/填充块数，供 done 下发做 Token 消耗可视化 */
    public volatile int contextTokens;
    public volatile int budgetTokens;
    public volatile int contextHits;
    /** 生效最大输出 token（模型管理中该模型声明的最大输出；0=未声明，不下发 max_tokens）：max_tokens 下发与终态触顶判定共用 */
    public volatile int effectiveMaxOutput;
    /** 网关返回的真实 usage（部分兼容网关末块携带；拿不到保持 0，回落 TokenCounter 估算） */
    public volatile int realPromptTokens;
    public volatile int realOutputTokens;
    /**
     * 网关在末块携带的终止原因（stop/length/tool_calls/…；null=网关未带）。
     * 截断判定的直接依据——此前只看「输出 token ≥ 声明的最大输出」，而模型声明值常远大于
     * 厂商实际上限，那条判定等于永不生效。
     */
    public volatile String lastFinishReason;
    /** 本轮任一轮次报过 finish_reason=length（输出触顶）：截断标注的判据，比逐轮取值抗末块乱序 */
    public volatile boolean finishLength;
    /** 本轮网关返回的思考增量字符数（计入 completion tokens 但不进正文）：生成量对账要扣掉它 */
    public volatile int reasoningChars;
    /** 缓存命中的 prompt token（网关 prompt_tokens_details.cached_tokens；0=网关未回传，面板隐藏该行） */
    public volatile int cachedPromptTokens;
    /**
     * 生成链路的逐轮用量累加器（工具调用循环每轮一次真实请求，累加后才是本次问答实耗）。
     * 与台账（DynamicOpenAiChatModel 出口记账）共用 {@link UsageAccumulator}
     * 的口径，保证「回答气泡上的本次用量」与「个人统计/账单」同源。
     */
    public final UsageAccumulator roundUsage = new UsageAccumulator();
    /** 本轮生效窗口 token 与来源（用户所选档位/模型声明/未声明），容量面板展示「已用 X / 窗口 Y」 */
    public volatile int windowTokens;
    public volatile String windowSource = "";
    /** 容量分类用量（消息/早期摘要/知识块/系统提示词/系统工具/MCP 工具/技能/其他）：估算后按真实 prompt 等比校准 */
    public volatile Map<String, Integer> ctxParts = new LinkedHashMap<>();
    /** 本轮装配历史时的摘要覆盖点（该 sequence 及更早的原样对话已折进早期摘要；0=会话还没压过）；
     *  随 tokens 落库，前端按「比上一轮更靠后」定位那条「已压缩上下文」分隔线 */
    public volatile long summaryUntilSeq;

    // ==================== 编排展示与落库归属 ====================

    /** 编排视图：各子代理分支的最终状态（主链路编排完成后回填，随 done 下发并持久化） */
    public volatile java.util.List<Map<String, Object>> subagentBranches = List.of();
    /** 按需委派的路由结果（{candidates,picked,names}；未启用路由时为 null），随 done 下发并持久化 */
    public volatile Map<String, Object> subagentRoute = null;
    /** 重新生成时被替换的旧回答消息 ID：落库前软删旧行（历史只留最新一版；null=普通问答） */
    public volatile String replaceMessageId;
    /** 本轮用户消息 ID（0.3 段即时落库返回；regenerate 时为 null——沿用上一轮请求已入库的消息）。
     *  随 done 下发：编辑重发后前端用它给新用户消息挂分支切换器、支持再次编辑 */
    public volatile String userMessageId;
    /** 本轮智能体归属快照（buildAnswerStream 回填）：中断兜底落库时 appendMessage 需要，彼时已拿不到闭包里的 agent */
    public volatile String agentId;
    public volatile String agentName;
    /** §4 轮级委派：本轮由 @ 提及的智能体作答时的归属（null=无委派）。随 done 单独下发——
     *  done 的 agentId/agentName 保持「会话绑定」口径（前端绑定镜像依赖它），委派归属不可混入 */
    public volatile String delegatedAgentId;
    public volatile String delegatedAgentName;

    // ==================== 人在回路与轮次终止 ====================

    /** 助手消息落库幂等闸：正常完成（doOnComplete）与中断兜底（disposeSafe → persistPartialAnswer）
     *  两条路径 CAS 先到先得——SSE 超时回调与正常完成存在并发窗口，不加闸同一轮可能落两条 */
    public final java.util.concurrent.atomic.AtomicBoolean answerPersistGate = new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 提问卡（askUser）阻塞计数：>0 表示本轮正挂在「等你作答」上。此刻通道失效<b>不中止本轮</b>
     * ——提问已落库，用户在另一台设备或刷新页面后仍能答，答案必须回到还阻塞着的工具线程。
     */
    public final java.util.concurrent.atomic.AtomicInteger askWaits = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 计划批准卡（planApproval）阻塞计数：>0 表示本轮正挂在「等你批准执行计划」上。
     * 语义与 {@link #askWaits} 相同：此刻通道失效不中止本轮——计划已落库，用户在另一台设备
     * 或刷新页面后仍能批准，批准后的回答必须回到还阻塞着的流水线线程。
     */
    public final java.util.concurrent.atomic.AtomicInteger planWaits = new java.util.concurrent.atomic.AtomicInteger();
    /** 本轮 askUser 已提问次数（上限 MAX_ASKS_PER_TURN）：多轮澄清合法，刷屏循环不合法 */
    public final java.util.concurrent.atomic.AtomicInteger askCount = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 本轮计划快照（计划模式，{plan,status}）：runPlanPhase 裁决落定时写入，助手消息落库时随 plan 列持久化
     * ——刷新/切会话后按轮重建气泡计划卡（此前计划只活在实时流里，刷新即丢）。null=本轮未走计划模式。
     */
    public volatile Map<String, Object> planRecord;
    /** 计划被用户「继续对话」取代（gate 收尾按取代口径：不写「未批准」收束语，发 plan_superseded） */
    public volatile boolean planSuperseded;
    /** 本轮计划是「按消息意图自动开启」（用户没开计划开关）：随计划快照落库，卡片标「自动开启」 */
    public volatile boolean planAuto;
    /**
     * 用户按下「停止本轮」：置位后不再执行任何工具步（含副作用工具），生成流被掐断，
     * 半程正文按 {@link #STOP_SUFFIX} 落库。
     * <p>与 {@code channel.detached} 分开是因为口径相反：断线时「人在等答复」要把本轮留在后台跑完，
     * 而用户叫停是<b>明确要它停</b>——正挂在审批/提问/计划等待上的轮同样必须停下。
     */
    public volatile boolean userStopped;
    /**
     * 收尾时仍未送达的插话：{@link #claimTerminal()} 摘把手之前先把队列转存到这里，
     * 否则 done 组装时把手已不在，没赶上的插话就静默消失了（实测踩过：模型不再调工具直接答完，
     * 插话既没送达也没续发）。
     */
    public final List<String> leftoverSteers = new ArrayList<>();
    /**
     * 工具侧（沙盒那层截断之前）已经外溢留存的登记，条目形如 {@code execute|<spillId>}。
     * <p>为什么在工具里发起而不是在这里补：只有它知道完整原文长什么样——问答侧拿到的已经是被截过的
     * 文本，在这层再截一次只是二次丢失。工具经 {@code ChatService#toolContext} 注入的能力落盘并上报，
     * 这里只接住、把「查看完整输出」挂到对应那张卡片上（不重复落盘，也不把 id 塞进模型看到的文本）。
     */
    public final java.util.concurrent.ConcurrentLinkedQueue<String> toolSpills =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    public TurnState(String sessionId, String question, String userId, SseEmitter emitter,
                       TurnSourceRegistry refs, List<UserImageService.UserImage> userImgs,
                       long startTime, String queryForLog, String[] thinkingHolder,
                       List<Map<String, String>> degradations, Set<String> degradedCodes,
                       String retrievedJson, Deps deps, SubAgentConsultService consult) {
        this.deps = deps;
        this.sessionId = sessionId;
        this.question = question;
        this.userId = userId;
        this.userImgs = userImgs;
        this.queryForLog = queryForLog;
        this.thinkingHolder = thinkingHolder;
        this.degradations = degradations;
        this.degradedCodes = degradedCodes;
        this.retrievedJson = retrievedJson;
        this.channel = new TurnChannel(emitter, startTime);
        // 引用池与工具面都由流水线建好交进来：本对象不再替它们凑依赖（刀 5a）
        this.refs = refs;
        this.tools = new TurnToolset(consult);
        // 挂进整轮台账：看门狗到点要停流并截断落库（收集型通道无台账，自然跳过）
        TurnDeadline d = TurnRegistry.deadline(emitter);
        if (d != null) d.state = this;
        TurnHandle h = TurnRegistry.handle(sessionId);
        if (h == null) {
            // 非 chat 入口（手动压缩等）：自造把手，收尾时由 claimTerminal 按值摘掉
            h = new TurnHandle(java.util.UUID.randomUUID().toString().replace("-", ""), userId, emitter);
            TurnRegistry.registerHandle(sessionId, h);
        }
        this.handle = h;
        this.turnId = h.turnId();
        // 登记进会话轮次表：服务端「停止本轮」按 sessionId 找到它（摘除见 chat 流水线 finally 与落库收尾）
        TurnRegistry.registerState(sessionId, this);
    }

    /**
     * 通道已失效（客户端断开 / emitter 完成 / 发送失败）时问一句：本轮还要不要继续跑？
     * <p>正在等用户作答、或早已转入后台续跑 ⇒ 置 {@code channel.detached} 返回 true：事件丢弃但不掐流，
     * 用户答完后回答照常生成并落库；<b>本轮还挂在运行总线上时同样返回 true</b>——通道没了只是
     * 少了一个观众，何时该停由总线的宽限期回调统一决定（{@code abortTurnWithoutViewer}），
     * 在这里掐等于把「刷新页面就丢这一轮」写回死路径（2026-10-10 真机复测就是撞在这条上：
     * 观众已经接流成功了，六秒后原通道的 onCompletion 仍把整轮 dispose 掉）。
     * 其余情形返回 false，由调用方照常规中止本轮并落半程正文——「断开即止损」的成本闸门口径不变，
     * 只在人在回路这一段让路。
     */
    public boolean keepRunningWithoutChannel() {
        if (askWaits.get() > 0 || planWaits.get() > 0 || channel.detached) {
            channel.detached = true;
            return true;
        }
        return deps.runBus().holdsRun(sessionId);
    }

    /** 本轮是否已收尾（正文已落库，正常完成或截断兜底都算）：台账据此回收 */
    public boolean settled() {
        return answerPersistGate.get();
    }

    /**
     * 占住「本轮已收尾」闸位，并同时摘掉会话轮次登记（{@link TurnRegistry}）。
     * <p>摘登记必须挂在这里而不是流水线线程的 finally：{@code runChat} 在 {@code subscribe} 之后
     * 立刻就返回了，生成与工具循环此后跑在响应式线程上——finally 一摘，正在出字的轮就从轮次表里
     * 消失，「停止本轮」再也找不到它，账本的归属也会断。谁先占闸谁负责摘，两条终态路径共用。
     *
     * @return true=本次占位成功（调用方应当落库）；false=已有别的终态路径收过尾
     */
    public boolean claimTerminal() {
        boolean claimed = answerPersistGate.compareAndSet(false, true);
        if (claimed) {
            // 先把没送达的插话转存进本轮，再摘把手：done 载荷要在把手消失后仍拿得到它们
            String s;
            while ((s = handle.steers().poll()) != null) leftoverSteers.add(s);
            TurnRegistry.unregisterHandle(sessionId, handle);
            TurnRegistry.unregisterState(sessionId, this);
        }
        return claimed;
    }

    public void disposeSafe() {
        // 客户端断开：顺带停整轮流级心跳（防断开后调度任务空转泄漏；发送失败也会自停，双保险）
        java.util.concurrent.ScheduledFuture<?> hb = channel.heartbeat;
        if (hb != null) hb.cancel(false);
        // 本轮已中止：整轮台账与看门狗一并回收（通道断开回调遇后台续跑时会故意留着它）
        TurnRegistry.stopDeadline(channel.emitter);
        Disposable d = channel.disposableRef.get();
        if (d != null) d.dispose();
        // 中断兜底：按截断态落库，刷新/重进会话后已生成的部分仍在历史里（此前只有完整完成
        // 才落库，中断轮在历史里只剩问题）。与正常完成路径共用幂等闸（CAS 先到先得）。
        // 看门狗截断/断线多发生在工具循环里——正文可能一个 token 都没有，价值全在工具卡片
        // 与计划快照上（用户盯着跑了五分钟的一场执行），只认正文非空会让这种轮整条蒸发，
        // 用户看到的是「已输出的内容刷新后全没了」。真正空轮（无正文且无工具记录）由
        // persistPartialAnswer 内部守卫拦住，不落空消息。
        // 本回调可能在容器线程触发（超时/错误），与流线程的正文追加存在理论竞态——
        // dispose() 已先行掐断上游，最坏读到略短的快照，对存档可接受
        if (claimTerminal()) {
            deps.persistPartial().accept(this, userStopped ? STOP_SUFFIX : TRUNCATION_SUFFIX);
        }
    }
}
