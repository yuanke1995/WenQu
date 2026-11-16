package com.wenqu.ai.agentx;


import com.wenqu.ai.agentx.output.ModelOutputParser;
import com.wenqu.ai.agentx.turn.TurnTranscriptRecorder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本轮状态袋：本轮「已经产出什么」的唯一真源，由每轮 new 一个实例持有。
 * <p>从 {@code AgentscopeChatEngine} 原样搬出（原先是它的 static 内部类）。不挂在引擎单例上，
 * 两条并发线程（流水线 + 接流刷新）共读时靠 StringBuffer / synchronized 集合 / 快照副本保持一致；
 * 这些同步原语是本类的契约，不可拆解。
 * <p>原先依赖引擎的 5 个静态常量与 {@code partialArgBrief} 一并搬入，逐字不变，行为完全等价。
 */
public final class AgentxTurnRecording {

    /** 工具入参在展示（落库 + SSE）里的单字段上限：超过就折成预览 + 省略字数。 */
    private static final int TOOL_ARG_PREVIEW_CHARS = 2000;

    /**
     * 工具入参的展示形态：超长字符串字段折成「前 2000 字 + 已省略 N 字」，其余原样。
     * <p>触发点是文件类工具：{@code presentArtifact} / {@code write_file} 的 {@code content} 是整份正文
     * （几万字的 HTML 报告很常见），原样回放会同时撑肥消息行、每帧 SSE 与历史回显，界面上则是一屏转义源码。
     * 完整正文仍在产物库/沙箱文件里，展示层只需要交代「这一步写了什么」。
     * <p>折叠只改字符串值、保持 JSON 结构：前端读 args 的 {@code JSON.parse} 与入参摘要都不受影响。
     */
    static String toolArgsForDisplay(String args) {
        if (args == null || args.length() <= TOOL_ARG_PREVIEW_CHARS) return args;
        Object parsed;
        try {
            parsed = com.alibaba.fastjson2.JSON.parse(args);
        } catch (Exception e) {
            return clipArgText(args); // 不是 JSON（MCP 兼容的纯文本入参）：按整段折
        }
        if (!(parsed instanceof java.util.Map) && !(parsed instanceof java.util.List)) return clipArgText(args);
        return com.alibaba.fastjson2.JSON.toJSONString(trimLongArgText(parsed));
    }

    private static Object trimLongArgText(Object v) {
        if (v instanceof String s) {
            return s.length() <= TOOL_ARG_PREVIEW_CHARS ? s : clipArgText(s);
        }
        if (v instanceof java.util.Map<?, ?> m) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), trimLongArgText(e.getValue()));
            }
            return out;
        }
        if (v instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>(l.size());
            for (Object o : l) out.add(trimLongArgText(o));
            return out;
        }
        return v;
    }

    private static String clipArgText(String s) {
        return s.substring(0, TOOL_ARG_PREVIEW_CHARS) + "…（已省略 " + (s.length() - TOOL_ARG_PREVIEW_CHARS) + " 字）";
    }

    // ===== 从引擎搬入的静态常量（逐字不变） =====
    /**
     * 会挂「子对话」卡的框架派遣工具名。只认 {@code agent_spawn}：它的入参带 {@code agent_id}
     * （逐字=问渠的智能体 id），能与本轮台账对上；{@code agent_send} 的入参只有 {@code agent_key}/
     * {@code label}，认不出是哪一位，宁可不挂也不把上一支的子对话错配到这一张卡上。
     */
    private static final java.util.Set<String> CONSULT_CARD_TOOLS = java.util.Set.of("agent_spawn");

    /**
     * 早产工具卡：模型一边流式生成工具入参，界面一边就能看到「这一步在跑」。
     * <p>框架只在 assistant 消息完整后才写 {@code tool/requested}：长入参生成期间前端一个工具都没有，
     * 界面就是一段纯静默。{@code model/chunk} 里带 {@code tool_use} 增量块（首块有 name/id，后续块是参数片段），
     * 据此提前发一张 {@code tool_status start} 卡即可把这段静默变成可见的进行中。
     * <p>短入参的检索类调用不值得闪一下：累计到这个字数、或生成超过这个毫秒数才发第一帧。
     */
    private static final int PREPARING_MIN_CHARS = 500;
    private static final long PREPARING_MIN_MS = 600L;
    /** 早产卡进度帧的最小间隔（字数只用于行尾摘要，不必逐块刷）。 */
    private static final long PREPARING_EVERY_MS = 1500L;
    /** 框架 OpenAI 适配层给「同一次调用的后续参数片段」占位的工具名（OpenAIResponseParser.FRAGMENT_PLACEHOLDER）。 */
    private static final String PREPARING_FRAGMENT_NAME = "__fragment__";
    /** 从入参前缀里捞一个能给用户看的摘要（文件名/路径/检索词/命令）；值还没生成完整时不命中。 */
    private static final Pattern PARTIAL_ARG_BRIEF = Pattern.compile(
            "\"(filename|filePath|path|dir|query|command)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    // ===== 从引擎搬入的入参摘要函数（逐字不变） =====
    /**
     * 从流式入参前缀捞一个用户看得懂的摘要（文件名/路径/检索词/命令）。
     * <p>只在值已经生成完整时命中：半截的 {@code "filename": "ind} 不会误报成 {@code ind}。
     */
    static String partialArgBrief(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var m = PARTIAL_ARG_BRIEF.matcher(raw);
        if (!m.find()) return null;
        String v = m.group(2)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", " ")
                .replace("\\r", "")
                .replace("\\t", " ")
                .trim();
        if (v.isEmpty()) return null;
        return v.length() > 40 ? v.substring(0, 40) + "…" : v;
    }

        // 这几份是「本轮已经产出什么」的唯一真源，而接流那条线程会与流水线线程同时读它们：
        // StringBuffer 的 toString/append 自带同步，两份清单靠 snapshotFields() 取快照副本，
        // 于是刷新时重建气泡不会读到半截（手写运行时那一份状态袋同样是被两条线程共读的）。
        final StringBuffer thinking = new StringBuffer();
        /**
         * 本轮实际流出的正文（与发给前端的 {@code token} 帧同源）。
         * <p>为什么不只取会话里那条最终 assistant 消息：框架把「一条完整的助手消息」在跑完时才写进
         * Transcript，被叫停或等待超时的那一跑根本没有 committed text——那时去取「最后一条有正文的
         * 助手消息」拿到的是<b>上一轮的答复</b>，等于拿别人的话给这一轮存档；多轮会话里
         * 这一取法就会落成上一轮的内容。
         */
        private final StringBuffer answerText = new StringBuffer();
        /**
         * 本轮已处理过的事件 seq（见 translate() 的幂等闸）。
         * <p>两条跟随路径读同一条 Session Log，重投件靠它拦下；HashSet 按轮新建、轮末即弃，
         * 一轮几千条的量级没有内存顾虑。synchronizedSet：两条路径并发 add 各自的 seq。
         */
        private final java.util.Set<Long> seenEventSeqs =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        /** 首次见到这条事件返回 true；重投（另一条跟随路径已处理过）返回 false，调用方整条跳过。 */
        boolean firstDelivery(long seq) {
            return seenEventSeqs.add(seq);
        }

        /** 本轮的进度账：读它判「该收尾/该终止」，写它只在 {@code tool/result} 一处。 */
        AgentxTurnProgress progress() {
            return progress;
        }

        /** 已记录正文是否包含该片段（收尾聚合帧的重复识别用；全文 indexOf，按帧头 60 字粗筛 + 全文确认）。 */
        boolean answerContains(String piece) {
            String full = answerText.toString();
            if (piece.isEmpty() || piece.length() > full.length()) return false;
            String probe = piece.length() > 60 ? piece.substring(0, 60) : piece;
            int at = full.indexOf(probe);
            return at >= 0 && full.regionMatches(at, piece, 0, piece.length());
        }

        /** 已记录思考是否包含该片段：收尾聚合帧对思考的重复识别，与 {@link #answerContains} 同一把尺。 */
        boolean thinkingContains(String piece) {
            String full = thinking.toString();
            if (piece.isEmpty() || piece.length() > full.length()) return false;
            String probe = piece.length() > 60 ? piece.substring(0, 60) : piece;
            int at = full.indexOf(probe);
            return at >= 0 && full.regionMatches(at, piece, 0, piece.length());
        }

        final java.util.List<java.util.Map<String, Object>> toolCalls =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /**
         * 本轮工具入参的<b>原文</b>长度合计（折叠展示之前）。
         * <p>入参落库/下发前会把超长字段折成预览（见 {@link #toolArgsForDisplay}），而「暗截断对账」判的是
         * 模型这一轮到底生成了多少字——读折叠后的那一份，会把「写文件」这类正常轮误判成内容丢失。
         */
        private long toolArgRawChars;
        /**
         * 本轮「有没有前进」的账（工具结果喂它）：连续无进展由引擎收口，见 {@link AgentxTurnProgress}。
         * 与工具卡同一份结果文本，避免两处各判一次「这步算不算白做」。
         */
        private final AgentxTurnProgress progress = new AgentxTurnProgress();
        /** 工具大输出外溢的落盘标识（{@code 工具名|spillId}），收尾时挂回对应工具卡，卡片才有「查看完整输出」。 */
        private final java.util.Queue<String> spills;
        /** 本轮的咨询台账（卡片要挂的那份「子对话」从这里取）。 */
        private final com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger;
        /** 本轮落点与工具装配口（run_code 的扇出面板从这里取）。 */
        private final WenquAgentxTools tools;
        final WenquAgentxTools.TurnHooks hooks;
        /**
         * 工具卡展示名解析口：落库的这份必须与实时 {@code tool_status} 帧同源，
         * 否则会出现「实时看着是中文、刷新后变英文名」——那张卡在两个时刻由两段代码生成，
         * 展示名一旦分开取，两边迟早漂移。
         */
        private final ToolDisplayNames displayNames;
        /**
         * 本轮归属用户：MCP 动态工具的中文名按它查（MCP 是个人资产，翻译缓存跟着归属走）。
         * 记在轮上而不是引擎实例上，与 {@link #lastToolName} 同一条纪律——引擎是单例，
         * 挂实例上就是甲轮的卡查了乙户的缓存。
         */
        final String uid;
        /**
         * tool/decision 与 tool/result 的载荷不一定再带工具名，从 requested 帧沿用。
         * <p>记在这里而不是引擎字段上：引擎 bean 是单例、两条会话可以同时在跑，挂在实例上就是
         * 甲轮的工具卡收了乙轮的尾巴。
         */
        String lastToolName = "";
        /**
         * 本轮内框架自动压缩<b>完成</b>过（{@code compaction/end} 且 status=completed）。
         * <p>收尾要拿它往消息行的 tokens 里落一个标记：那条「已压缩上下文」分隔线只有这一个数据源，
         * 不落库就只在流里闪一下——刷新即无痕，用户回头看不出这一轮压过上下文。
         * 失败/取消的压缩不算：那一次什么都没折进摘要，留痕就是留假痕。
         */
        volatile boolean compactedThisTurn;
        String lastToolInput = "";
        // ---- 早产工具卡：模型还在流式生成 tool_use 入参时，先把「这一步在跑」亮出来 ----
        // 只在内存里用、不落库：正式 tool/requested 到达后这张卡被前端原地升级，落库那份从一开始就是正式卡。
        /** 当前正在生成入参的那次调用名（空=没有） */
        private String preparingName = "";
        /** 那次调用的 id（模型一步里并发点名同名工具时用它区分；空 id 只按名字走） */
        private String preparingCallId = "";
        private long preparingStartedAt;
        private long preparingChars;
        private long preparingLastFrameAt;
        private boolean preparingShown;
        /** 入参前缀（只留前 {@link #PREPARING_MIN_CHARS} 个字符，用来捞文件名这类摘要；不攒全文） */
        final StringBuilder preparingHead = new StringBuilder();

        /** 一次 tool_use 增量累积后的进度；只为「该发帧」的那几拍产出。 */
        record PreparingTick(String name, long chars, String brief, long startedAtMs) {}

        /**
         * 累积一次 {@code tool_use} 参数增量（首块带 name/id，后续块是 {@code __fragment__} 片段）。
         * <p>返回 null = 这一拍不值得发帧（还没到门槛，或进度帧间隔没到）。
         */
        synchronized PreparingTick noteToolUseDelta(String name, String id, String content, long now) {
            String name0 = name == null ? "" : name;
            String id0 = id == null ? "" : id;
            boolean named = !name0.isBlank() && !PREPARING_FRAGMENT_NAME.equals(name0);
            // 新的一次调用：名字变了，或同名的另一把（id 不同）
            boolean newCall = named && (!name0.equals(preparingName)
                    || (!id0.isBlank() && !id0.equals(preparingCallId)));
            if (newCall) {
                preparingName = name0;
                preparingCallId = id0;
                preparingStartedAt = now;
                preparingChars = 0L;
                preparingLastFrameAt = 0L;
                preparingShown = false;
                preparingHead.setLength(0);
            }
            if (preparingName.isEmpty()) return null;
            String piece = content == null ? "" : content;
            preparingChars += piece.length();
            if (preparingHead.length() < PREPARING_MIN_CHARS) {
                preparingHead.append(piece, 0,
                        Math.min(piece.length(), PREPARING_MIN_CHARS - preparingHead.length()));
            }
            boolean due = preparingShown
                    ? (now - preparingLastFrameAt >= PREPARING_EVERY_MS)
                    : (preparingChars >= PREPARING_MIN_CHARS || now - preparingStartedAt >= PREPARING_MIN_MS);
            if (!due) return null;
            preparingShown = true;
            preparingLastFrameAt = now;
            return new PreparingTick(preparingName, preparingChars,
                    partialArgBrief(preparingHead.toString()), preparingStartedAt);
        }

        /** 正式 {@code tool/requested} 到了：早产状态收摊，后面的入参片段不再发进度帧。 */
        synchronized void clearPreparing() {
            preparingName = "";
            preparingCallId = "";
            preparingChars = 0L;
            preparingShown = false;
            preparingHead.setLength(0);
        }
        /**
         * 每次调用的入参，按 {@link #toolCallKey} 配回<b>它自己那一次</b>调用。
         * <p>为什么不能共用 {@link #lastToolInput} 那一个字段：模型一步里可以同时点两把工具（框架并发派发），
         * 后到的 requested 会盖掉先到的入参，先那把的 done 帧就带着别人的参数下发。而前端
         * {@code onToolStatus} 收终态是「按名字找最近那张 start 卡并把 args 写回去」，于是那张卡的
         * 「入参」整段变成另一个工具的参数。
         * 落库那份是在 start 时写的、本来就是对的，所以症状是「实时看到的入参是错的、刷新又好了」。
         */
        final Map<String, String> toolInputByKey = new java.util.LinkedHashMap<>();
        /**
         * 收集模型实际发出的检索词：{@code search_knowledge} 工具的 {@code query} 入参即它真去搜的短语
         * （模型自己怎么改写的检索短语，比从用户原问句抽关键词更贴近「它真搜了什么」）。
         * 状态行 {@code retrieved.terms} 优先用这一份，收集不到才退回问题关键词（见 {@link #retrievedJson}）。
         */
        final Set<String> searchQueries = new LinkedHashSet<>();
        /**
         * 本轮<b>已经被框架写进会话上下文</b>的插话收据号（凭证是 {@code inbox/applied} 事件里的 commandIds）。
         * <p>记在这里而不是只记在 {@code TurnMirror} 上：收尾那次全量补读传的镜像是 null（出轮之后没人再读
         * 会话状态），而那恰恰是最后一条插话最可能被应用的时刻。
         */
        final Set<String> appliedSteers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /**
         * 这一跑是否已经真的结束（看到过 {@code run/end}）。
         * <p>插话对账要用它。撞到整轮机器预算的那一支现在会当场叫停那一跑（见 followTurn 的
         * {@code ranOutBudget}），所以框架会写下终态的 {@code run/end}——那句「模型还没收到这句话」
         * 从此可以如实报出来；没等到 {@code run/end} 就说明那一跑还活着，插话会在下一次调模型前
         * 正常应用，这时候交回前端等于把同一句话发两遍。
         */
        volatile boolean runEnded;

        /** 记一笔「这条插话已经送达模型」——收尾按它认账，没到账的交回前端补发。
         *  @return false = 这条收据已经记过（收尾那次全量补读会把同一事件再读一遍），别重复留痕 */
        boolean markSteerApplied(String receiptId) {
            return receiptId != null && !receiptId.isBlank() && appliedSteers.add(receiptId);
        }

        /**
         * 前台等待到点、按 pending 送回模型的那几张提问卡（按框架待办号＝工具调用号记）。
         * <p>它们随后那条 {@code tool/result} 要带 {@code askContinued}：前端据此把提问面板留着仍可答，
         * 而不是按终态撤卡——撤了就等于「超时＝用户跳过」，正是 DSH 明确分开的那两件事。
         */
        private final Set<String> continuedAsks = java.util.concurrent.ConcurrentHashMap.newKeySet();

        void markAskContinued(String requestId) {
            if (requestId != null && !requestId.isBlank()) continuedAsks.add(requestId);
        }

        boolean isAskContinued(String toolCallId) {
            return toolCallId != null && continuedAsks.contains(toolCallId);
        }

        /**
         * 本轮 {@code plan_write} 工具写入的计划正文（框架 {@code PlanWriteTool} 的入参 {@code content}）。
         * <p><b>为什么必须单独攒</b>：框架的计划正文走 {@code plan_write} 落到工作区 {@code plans/PLAN.md}，
         * 而 {@code plan_exit} 的入参只有一个可选的 {@code summary}（一句话摘要，见 {@code PlanExitTool}
         * 的 inputSchema）——正文不在退出确认的入参里。问渠的计划卡要展示「整份计划」，
         * 若直接拿 {@code plan_exit} 的入参当 plan 字段，用户看到的就是 {@code [object Object]}。
         * <p>同轮多次 {@code plan_write} 是覆盖语义（框架侧 replace the file），保留最后一份即可。
         */
        String planMarkdown = "";
        /**
         * 当前这一版计划正在流式产出的正文（{@code plan/changed} 的 PLAN_DELTA 增量累积）。
         * <p>与 {@link #planMarkdown} 不同：后者是 plan_write 入参里的整份稿，只有工具请求那一刻才有；
         * 接流快照要回答「此刻屏幕上那张计划卡里写到哪了」，只能用这份增量缓冲。
         * 新的 deep_plan_approval 落地后清空（权威稿以 planVersions 最后一版为准）。
         */
        private final StringBuffer planDraft = new StringBuffer();
        /** 正在等用户裁决最新一版计划（快照据此区分「生成中」与「待确认」） */
        private volatile boolean planPending;
        /** 最新一版计划挂出待确认的时刻（快照算 remainingMs 用，只给时长不给绝对时刻） */
        private volatile long planPendingAt;
        /** 每次调用的开始时刻（与入参同一个配对键），终态时算耗时下发——前端工具卡读 elapsedMs，缺它就显示 0ms。 */
        private final Map<String, Long> toolStartMs = new java.util.LinkedHashMap<>();
        /**
         * 在飞工具各自要<b>扣掉</b>的「等人」时长（配对键 → 累计毫秒）。
         * <p>提问卡/计划卡/审批卡挂出到用户点完，那一段既在工具的开始-结束窗口里、又不是机器在干活。
         * 不拆出去的后果：一张让人思考了三分钟的提问卡，卡片自己写着「3m12s」，读起来就是「这个工具慢得离谱」。
         * <p>扣账摊给<b>等待期间仍在飞的每一把</b>，不是只摊给挂卡那一把：人在回路的这道闸门卡住的是
         * 整个工具步，同一步里别家的 result 也要等用户点完才到；只摊给挂卡那一把时，
         * 那张卡就会顶着一整段等待时长上界面。
         */
        private final Map<String, Long> toolWaitDeduct = new java.util.LinkedHashMap<>();
        /**
         * 每段等待<b>记在哪一张卡上</b>（对外下发与落库的那份 {@code waitedMs}）。
         * <p>一张闸门只记一笔：前端整段跨度算的是「首尾之差 − 各张卡 waitedMs 之和」，同一段时间摊到两张
         * 卡上就扣了两遍，跨度会被夹成 0。
         */
        private final Map<String, Long> toolWaitCharge = new java.util.LinkedHashMap<>();
        /** 当前这段等待的起点（0 = 此刻没在等人）；与 followTurn 里的 {@code waitBeganAt} 同步进出。 */
        private long toolWaitBeganAt;

        /**
         * 一次工具调用的配对键：有 {@code toolCallId} 就按它（并发派发时唯一不串号的标识，
         * 框架两侧都带：requested 在顶层、result 在结果块里），没有才退回按工具名配。
         */
        static String toolCallKey(String toolCallId, String name) {
            return toolCallId == null || toolCallId.isBlank() ? (name == null ? "" : name) : toolCallId;
        }

        /** 登记开始时刻并把它回给调用方：同一个时刻既要进等待账、也要写进卡片那份 {@code startedAtMs}。 */
        long markToolStart(String key) {
            long at = System.currentTimeMillis();
            if (key != null && !key.isBlank()) toolStartMs.put(key, at);
            return at;
        }

        /** 取回这一次调用的开始时刻；配不上 start（如从游标中途接流）时为 0，调用方按「没有计时」处理，不许当 0 毫秒。 */
        long takeToolStart(String key) {
            Long t = key == null ? null : toolStartMs.remove(key);
            return t == null ? 0L : t;
        }

        /** 挂卡那几把调用的配对键：这段等待要<b>记账</b>在其中第一张上（认不出来时兜底给在飞的第一把）。 */
        private final java.util.List<String> toolWaitKeys = new java.util.ArrayList<>();

        /** 等待开始：记下挂的是哪几把调用的卡；扣账在收口时按当时在飞的清单现算。 */
        void beginToolWait(java.util.Collection<String> keys) {
            toolWaitBeganAt = System.currentTimeMillis();
            toolWaitKeys.clear();
            if (keys != null) toolWaitKeys.addAll(keys);
        }

        /**
         * 等待收口：<b>扣账摊给此刻仍在飞的每一把</b>（闸门卡住整个工具步，谁的窗口都盖住了这段），
         * <b>记账只落挂卡那一张</b>（跨度按各张 waitedMs 之和来减，记两张等于同一段时间扣两遍）。
         * <p>一把都没在飞（本轮只等人、没有未结算的调用）时两处都不记，这段只进整轮那本账。
         */
        void endToolWait() {
            if (toolWaitBeganAt <= 0L) return;
            long wait = System.currentTimeMillis() - toolWaitBeganAt;
            toolWaitBeganAt = 0L;
            java.util.List<String> inflight = new java.util.ArrayList<>(toolStartMs.keySet());
            if (inflight.isEmpty()) {
                toolWaitKeys.clear();
                return;
            }
            for (String key : inflight) toolWaitDeduct.merge(key, wait, Long::sum);
            String chargeKey = null;
            for (String key : toolWaitKeys) {
                if (toolStartMs.containsKey(key)) { chargeKey = key; break; }
            }
            if (chargeKey == null) chargeKey = inflight.get(0);   // 归属认不出来（字段形状变了）才兜底
            toolWaitCharge.merge(chargeKey, wait, Long::sum);
            toolWaitKeys.clear();
        }

        /**
         * 这一次调用的窗口里被「等人」盖住了多久（用于算它自己的 {@code elapsedMs}），同时销掉这笔扣账。
         * <p>与 {@link #takeToolCharge} 分开的理由见那两个字段各自的注释：一张卡该报真实干活时长，
         * 而整段跨度只按挂卡那一张减一次。
         */
        long takeToolWait(String key) {
            // 等待账跟着这次结算一起销掉：键是一次性的，留着只会让本轮的表越长越大
            long wait = key == null ? 0L : toolWaitDeduct.getOrDefault(key, 0L);
            if (key != null) toolWaitDeduct.remove(key);
            return wait;
        }

        /** 这一段等待对外记在哪张卡上的那份数（只有挂卡那把拿得到非零值），同时销掉这笔记账。 */
        long takeToolCharge(String key) {
            long wait = key == null ? 0L : toolWaitCharge.getOrDefault(key, 0L);
            if (key != null) toolWaitCharge.remove(key);
            return wait;
        }
        /**
         * 回答时间线（与 {@code TurnRecorder} 同一套段格式：正文区间段 / 工具下标段 / 思考区间段）。
         * <p>不落库的后果：刷新后前端拿不到交错点，{@code hasTimelineBlocks} 为假，工具卡掉进
         * 「历史恢复兜底」列表、沉到正文下方——实时看着对、刷新就错位。
         */
        private final List<Map<String, Object>> timeline =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /** 正文区间段的锚点（已成段的正文长度），与 {@code TurnRecorder.timelineAnchor} 同义。 */
        private int timelineAnchor;

        /** 把锚点到 pos 之间的正文记为一个文本段（无新增则忽略）；插工具段前调用，保持「正文—工具」交错。 */
        private void flushTimelineText(int pos) {
            if (pos <= timelineAnchor) return;
            timeline.add(Map.of("kind", "text", "from", timelineAnchor, "to", pos));
            timelineAnchor = pos;
        }

        /**
         * 插话留痕：这一轮<b>在这一刻把用户补的那句听进了上下文</b>（框架 {@code inbox/applied} 的时机），
         * 所以它夹在正文与工具之间，而不是整段回答之前或之后。与工具段同一手法：先把此前的正文成段，
         * 再插段，交错顺序如实保留。
         * <p>只在这句话真被应用时才留痕：没赶上的那句由 {@code done.pendingSteers} 交回前端，
         * 作为新一轮的问题重新出现——它没进过这一轮，就不该长在这一轮的回答里。
         */
        void steer(String text, String messageId, java.util.List<String> images,
                   java.util.List<java.util.Map<String, Object>> attachments) {
            flushTimelineText(answerText.length());
            Map<String, Object> seg = new java.util.LinkedHashMap<>();
            seg.put("kind", "steer");
            seg.put("text", text);
            seg.put("messageId", messageId == null ? "" : messageId);
            // 带图插话：图也要长在这一段上。否则独立用户气泡被这段顶掉后，图在界面上就凭空消失
            if (images != null && !images.isEmpty()) seg.put("images", images);
            // 附件同理：它是这句话的一部分，气泡被顶掉后文件名与体积不能跟着不见
            if (attachments != null && !attachments.isEmpty()) seg.put("attachments", attachments);
            timeline.add(seg);
            transcript.steer(text, messageId, images, attachments);
        }

        /**
         * 落库用时间线：工具段按调用顺序夹在正文段之间，末段补到 body 全长（含追加的相关推荐/中断标记）。
         * <p>body 若被引用自检整体替换过（{@code outcome.text()}），正文区间就对不上了——此时<b>只丢 text 段</b>，
         * 保留思考/过程/工具段：正文由前端按整段接在末尾（{@code timelineView} 的尾段），过程与工具卡仍点得开。
         * 整条返回 null 会让前端在 done 那一步把时间线清空：用户刚看着跑完的几次检索/咨询，
         * 在答完的同一秒整体消失。
         */
        String timelineJson(String body, String answerText) {
            // 裁决唯一出自 BodyReconcile——与 TurnTranscriptRecorder.reconcileBody 同一判据
            if (body == null || com.wenqu.ai.agentx.turn.BodyReconcile.classify(answerText, body)
                    == com.wenqu.ai.agentx.turn.BodyReconcile.Verdict.REPLACED) {
                java.util.List<Map<String, Object>> kept = new java.util.ArrayList<>();
                synchronized (timeline) {
                    for (Map<String, Object> seg : timeline) {
                        if (seg != null && !"text".equals(seg.get("kind"))) kept.add(seg);
                    }
                }
                return kept.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(kept);
            }
            flushTimelineText(body.length());
            return timeline.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(timeline);
        }

        /**
         * 思考段是否还开着：框架没有「思考阶段结束」这个事件，只能在正文块或工具调用到来时补收尾帧。
         * <p>{@code thinkFrom} 记这一段在累计全文里的起点——一次问答常有多轮思考（每轮工具调用前各一次），
         * 只交全文会让它们首尾粘成一块；按轮成段写进时间线，前端才能把「它在哪一步想了什么」就地渲染。
         */
        private volatile boolean thinkingOpen;
        private int thinkFrom;
        /**
         * {@code <related>}／{@code <process>} 的流式剥离器：正文增量先过它再下发。
         * <p>不接的表现为推荐块标签原文印在回答里——那句「回答末尾用 &lt;related&gt;…」的提示词
         * 是两条运行时共用的（{@code RelatedPrompt} 一处出处），提示了就得负责剥。
         */
        final ModelOutputParser related = new ModelOutputParser();
        private final TurnTranscriptRecorder transcript = new TurnTranscriptRecorder();
        /**
         * 本轮出过的计划卡（按版本顺序）。裁决之后随助手消息落 {@code plan} 列——
         * 否则刷新后只剩「还在等确认」的那张（它由待办恢复端点重建），已批过的每张计划都消失，
         * 用户回头看不出这一轮到底是按哪一版计划跑的。
         */
        private final java.util.List<Map<String, Object>> planVersions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /**
         * 最后一版计划卡的批准 ID（随助手消息落 plan 列）：血缘需要一个稳定锚点——
         * 旧卡要能被新卡指回、刷新后「查看新一轮」要能按 id 定位；只有正文的 plan 记录做不到这一点。
         */
        private volatile String planApprovalId;

        /** 计划草稿增量（{@code plan/changed} 的 PLAN_DELTA）：新草稿一出现就不再是「待确认」状态。 */
        void planDelta(String delta) {
            if (delta == null || delta.isEmpty()) return;
            synchronized (planDraft) {
                planDraft.append(delta);
            }
            planPending = false;
        }

        /** 一版计划挂出待确认：记下权威稿与产出它的意见，清空草稿缓冲。 */
        void planVersion(String plan, String feedback, String approvalId) {
            Map<String, Object> version = new java.util.LinkedHashMap<>();
            version.put("plan", plan == null ? "" : plan);
            version.put("feedback", feedback == null ? "" : feedback);
            if (approvalId != null) version.put("planApprovalId", approvalId);
            synchronized (planVersions) {
                planVersions.add(version);
            }
            synchronized (planDraft) {
                planDraft.setLength(0);
            }
            if (approvalId != null) planApprovalId = approvalId;
            planPending = true;
            planPendingAt = System.currentTimeMillis();
        }

        /** 快照读取：计划版本链副本（调用方按返回副本渲染，不直接暴露内部列表） */
        java.util.List<Map<String, Object>> planVersionsCopy() {
            synchronized (planVersions) {
                return new java.util.ArrayList<>(planVersions);
            }
        }

        String planDraftText() {
            synchronized (planDraft) {
                return planDraft.toString();
            }
        }

        boolean hasPlanActivity() {
            return planPending || !planDraftText().isEmpty() || !planVersionsCopy().isEmpty();
        }

        boolean planPendingNow() {
            return planPending;
        }

        long planPendingAtMs() {
            return planPendingAt;
        }

        String planApprovalIdValue() {
            return planApprovalId;
        }

        public AgentxTurnRecording(java.util.Queue<String> spills,
                  com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger,
                  WenquAgentxTools tools, WenquAgentxTools.TurnHooks hooks,
                  ToolDisplayNames displayNames, String uid) {
            this.spills = spills;
            this.consultLedger = consultLedger;
            this.tools = tools;
            this.hooks = hooks;
            this.displayNames = displayNames;
            this.uid = uid;
        }

        void thinking(String delta) {
            if (delta == null || delta.isEmpty()) return;
            if (!thinkingOpen) {
                thinkingOpen = true;
                // 先把此前已出的正文落成段：思考段要等 closeThinking 那一刻才追加进时间线，
                // 而末尾正文只在轮末 timelineJson 才补段。不先落段，这段思考就会记到正文前面，
                // 刷新后从答案下方（实时按到达顺序）跳到答案上方——工具/插话入口本来就有同样的 flush。
                flushTimelineText(answerText.length());
                transcript.thinkingStart();
                thinkFrom = thinking.length();
            }
            thinking.append(delta);
            transcript.thinkingDelta(delta);
        }

        /** 正文增量：与发给前端的 {@code token} 帧同源，中断轮靠这一份存档（见 {@link #answerText}）。 */
        void answer(String delta) {
            if (delta == null) return;
            // 正文缓冲与 blocks 必须同拍：快照线程按「缓冲 + blocks」取一致点，分开写会补出重复正文
            synchronized (transcript) {
                answerText.append(delta);
                transcript.textDelta(delta);
            }
        }

        /** 过程独白增量：正文走 TOKEN、过程走 PROCESS，blocks 里按落点成段。 */
        void process(String delta) {
            transcript.processDelta(delta);
        }

        String blocksJson(String turnId, String body) {
            return transcript.toJson(turnId, body);
        }

        /** 终态提示块：结构化原因随 blocks 落库与下发；正文尾标在双写窗口内照旧写进 content。 */
        void notice(String tone, String text) {
            transcript.notice(tone, text);
        }

        void artifacts(java.util.List<java.util.Map<String, Object>> artifacts) {
            transcript.artifacts(artifacts);
        }

        String answerText() {
            return answerText.toString();
        }

        /**
         * 思考段收尾：把这一段的区间记进时间线，并交出累计全文（前端据此停转圈并折叠这一段）。
         * 没开过返回 null（不发空帧）。
         * <p>框架把思考当流内块、没有「思考阶段结束」这件事，只能在正文块/工具调用到来或整轮收尾时补这一帧。
         * 全文不清空、继续累加：落库的 {@code thinking} 列与暗截断对账都要按「本轮一共想了多少字」算，
         * 分段只靠区间，不做第二份存储。
         */
        String closeThinking() {
            if (!thinkingOpen) return null;
            thinkingOpen = false;
            int end = thinking.length();
            if (end > thinkFrom) timeline.add(Map.of("kind", "think", "from", thinkFrom, "to", end));
            transcript.thinkingEnd();
            return thinking.toString();
        }

        /** 快照线程读「思考段是否还开着」：与 thinking()/closeThinking() 同一把锁，避免读到断段瞬间 */
        synchronized boolean thinkingOpenSnapshot() {
            return thinkingOpen;
        }

        /**
         * 工具卡登记：start 建一张带本次入参的开始时刻，终态按名找最近那张 start 改状态并补耗时。
         *
         * @param args      这一次调用自己的入参（JSON 文本）。并发派发时它必须由调用方按
         *                  {@link #toolCallKey} 配好传进来，读共用字段就是甲张卡收乙次的参数
         * @param elapsedMs 这一次调用的机器耗时（盖住它的等待已扣干净，同一步里别家挂的卡也算）；
         *                  0 = 没有可报的数，不写这个字段
         * @param beganAtMs 服务端开始时刻；0 = 配不上 start，落库那份改记当下
         * @param waitedMs  这段等待<b>记本账的那一笔</b>：只有挂卡那一张拿到非零值，整段跨度按它减一次；
         *                  0 = 这张不是挂卡那张（它的等待已经体现在 elapsedMs 里，但跨度不该重复减）
         * @param result    这一步的输出文本（{@link #toolResultText} 那份，已按 2000 字截断）。
         *                  <b>必须和 SSE 那一帧同形落库</b>：落库那份若不带 result，刷新后
         *                  就只剩入参——工具卡上是一个看不出原因的红叉，「这步为什么失败」只能回去翻服务端日志
         */
        // 返回这一把（若是咨询类工具）的 subChat，供实时 tool_status 终态帧一并下发——
        // 只落库不实时发的话，咨询卡的结果（问/答/资料/子对话）直播看不见、刷新才冒出来。
        java.util.Map<String, Object> toolCall(String name, String status, String args, long elapsedMs, long beganAtMs, long waitedMs,
                      String result) {
            if (name == null || name.isBlank()) return null;
            boolean runCode = com.wenqu.ai.service.SandboxTools.RUN_CODE_TOOL_NAME.equals(name);
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                java.util.Map<String, Object> prior = toolCalls.get(i);
                if (name.equals(prior.get("name")) && "start".equals(prior.get("status"))) {
                    prior.put("status", status);
                    putTiming(prior, elapsedMs, beganAtMs, waitedMs);
                    if (result != null && !result.isBlank()) prior.put("result", result);
                    String spill = takeSpill(name);
                    if (spill != null) prior.put("spill", spill);
                    // 咨询卡片挂那一问一答（与手写侧同一个实现与同一把长度尺）：刷新后仍点得开。
                    // 派遣改由框架驱使后工具名换成 agent_spawn，认哪几把见 CONSULT_CARD_TOOLS
                    // 同一份 subChat 既落库也通过下方返回的引用交回实时帧：直播与刷新同构，
                    // 不再出现「当场只见一张空卡、刷新后才长出结果」的错位
                    java.util.Map<String, Object> subChat = null;
                    if (CONSULT_CARD_TOOLS.contains(name)) {
                        subChat = com.wenqu.ai.agent.chat.TurnRecorder
                                .consultCardSubChat(consultLedger, args);
                        if (subChat != null) prior.put("subChat", subChat);
                    }
                    // run_code 终态：停面板 ticker 并把扇出清单与这次运行的结构化结果挂上卡片
                    if (runCode) prior.putAll(tools.finishRunCodeCard(hooks));
                    transcript.tool(prior);
                    return subChat;
                }
            }
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", name);
            // 展示名与悬停说明随后端下发：
            // 落库的这份是刷新后重建工具卡的唯一依据，写裸名等于把中文名全押在前端那份手抄映射上
            displayNames.putDisplayFields(entry, uid, name);
            entry.put("status", status);
            // 步骤归属随卡落库（取值时刻＝开工这一刻）：刷新后执行详情仍能把工具归到原步骤。
            // 终态回填时不覆盖——那时清单可能已经推进到下一步，拿它盖会把这一步的工具挪到下一步去
            int stepId = hooks.step().index();
            if (stepId >= 0) entry.put("stepId", stepId);
            // 入参随卡片落库：前端刷新后的工具卡与咨询卡「被咨询那一位的名字」都读这一列（缺它两处一起退化）。
            // 超长字段在这里折成预览：整份文件正文（presentArtifact/write_file 的 content）原样进库，
            // 只会把消息行和每次刷新的历史回显一起撑肥，而完整正文本来就在产物库/沙箱文件里。
            // 原文长度单独计入 toolArgRawChars——暗截断对账要的是模型生成了多少字，不能读折叠后的这份。
            if (args != null && !args.isBlank()) {
                toolArgRawChars += args.length();
                entry.put("args", toolArgsForDisplay(args));
            }
            // 输出与入参同一条规则：只在有内容时写，且与 SSE 帧同一个键名（前端读 t.result）
            if (result != null && !result.isBlank()) entry.put("result", result);
            // 耗时与开始时刻同样落库：只靠 SSE 那一帧带 elapsedMs，刷新后每张卡都没有数，
            // 「执行了 N 个操作」后面跟着的是各张卡之和（全为 0）——当场看着有、回头读成 0ms。
            putTiming(entry, elapsedMs, beganAtMs > 0L ? beganAtMs : System.currentTimeMillis(), waitedMs);
            // 时间线：先把这把工具之前的正文成段，再插工具段（下标=它在 toolCalls 里的位置），
            // 刷新后前端据此把工具卡还原到正文之间的原位，而不是一律沉底。
            flushTimelineText(answerText.length());
            timeline.add(Map.of("kind", "tool", "i", toolCalls.size()));
            toolCalls.add(entry);
            // 脚本开始跑：开这块实时面板（作用域是一次运行，两段脚本各自复位）
            if (runCode) tools.beginRunCode(hooks);
            // 终态事件先于 start 到达（如从游标中途接流）时这条新卡也照挂 subChat，免得直播缺这块
            if (CONSULT_CARD_TOOLS.contains(name)) {
                java.util.Map<String, Object> subChat = com.wenqu.ai.agent.chat.TurnRecorder
                        .consultCardSubChat(consultLedger, args);
                if (subChat != null) entry.put("subChat", subChat);
                transcript.tool(entry);
                return subChat;
            }
            transcript.tool(entry);
            return null;
        }

        /**
         * 这个名字的工具当前有没有还挂着「进行中」的卡。
         * <p>判据与 {@link #toolCall} 的合并规则同源（按名字找最后一条 start）：只有它返回 true，
         * 补一份终态才是「把这张卡关掉」，否则就是凭空多推一张卡——同一次调用渲染成两张。
         */
        boolean hasOpenTool(String name) {
            if (name == null || name.isBlank()) return false;
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                java.util.Map<String, Object> prior = toolCalls.get(i);
                if (name.equals(prior.get("name"))) return "start".equals(prior.get("status"));
            }
            return false;
        }

        /**
         * 计时字段只在有值时才写：「没有这条计时」与「跑了 0 毫秒」在两处消费者（工具卡、时间跨度）
         * 那里必须长得不一样，都写成 0 就等于用假数字填掉了缺口。
         */
        private static void putTiming(java.util.Map<String, Object> entry, long elapsedMs,
                                      long beganAtMs, long waitedMs) {
            if (beganAtMs > 0L) entry.put("startedAtMs", beganAtMs);
            if (elapsedMs > 0L) entry.put("elapsedMs", elapsedMs);
            if (waitedMs > 0L) entry.put("waitedMs", waitedMs);
        }

        /** 按工具名取最早那条未消费的外溢标识（与手写侧 {@code TurnRecorder.takeToolSpill} 同一口径）。 */
        private String takeSpill(String name) {
            if (spills == null) return null;
            for (String e : spills) {
                int bar = e.indexOf('|');
                if (bar > 0 && name.equals(e.substring(0, bar)) && spills.remove(e)) {
                    return e.substring(bar + 1);
                }
            }
            return null;
        }

        /**
         * 落 {@code c_ai_message.thinking} 的那一份：纯文本，不是 JSON。
         * <p>时间线里的 think 段存的是「from/to 指向本文偏移」，前端历史回看按 {@code slice(from,to)} 切段，
         * 所以这一列必须与算偏移时那份**逐字符相同**——套一层 JSON 转义会让每个换行多占两字符、
         * 整列偏移错位，界面上还把 \n 打成字面量。
         */
        String thinkingText() {
            return thinking.length() == 0 ? null : thinking.toString();
        }

        String toolCallsJson() {
            return toolCalls.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(toolCalls);
        }

        java.util.List<java.util.Map<String, Object>> toolCallList() {
            return toolCalls;
        }

        /** 本轮工具入参原文字符数（不受展示折叠影响），供暗截断对账使用。 */
        long toolArgRawChars() {
            return toolArgRawChars;
        }

        /**
         * 接流快照里属于本轮状态的几份内容（正文 / 过程独白 / 思考全文 / 工具卡 / 时间线），全部取副本。
         * <p>读它的是「刷新进来接流」那条线程，写它的始终是本轮流水线线程：两份清单用同步容器、
         * 两段全文用 {@link StringBuffer}，就是为了这一刻不读到半截。
         * <p>时间线末尾那段正文在这里现算（不回填 {@link #timeline}）：工具段按下标指向同一时刻的
         * {@code toolCalls}，所以两份必须出自同一次快照，错开一步前端就把卡片插到别的位置上。
         */
        Map<String, Object> snapshotFields() {
            Map<String, Object> snap = new java.util.LinkedHashMap<>();
            String body;
            String blocksJson;
            // 正文与 blocks 在同一把锁里取：两块来自同一次「已经追加到哪」的真值
            synchronized (transcript) {
                body = answerText.toString();
                blocksJson = transcript.snapshotJson(null);
            }
            snap.put("content", body);
            snap.put("processText", related.process());
            if (thinking.length() > 0) snap.put("thinking", thinking.toString());
            // 思考段还开着吗：前端据此续写同一段，而不是把下一片增量另起一段
            snap.put("thinkingOpen", thinkingOpenSnapshot());
            snap.put("toolCalls", new java.util.ArrayList<>(toolCalls));
            java.util.List<Map<String, Object>> tl = new java.util.ArrayList<>(timeline);
            if (body.length() > timelineAnchor) {
                tl.add(Map.of("kind", "text", "from", timelineAnchor, "to", body.length()));
            }
            snap.put("timeline", tl);
            // 接流时新前端按 blocks 重建；此刻正文还不是终稿，答案通道按当前末尾正文先标
            if (blocksJson != null) {
                snap.put("blocks", com.alibaba.fastjson2.JSON.parseObject(blocksJson));
            }
            return snap;
        }
}
