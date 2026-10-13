package com.wenqu.ai.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime;
import com.wenqu.ai.agent.tool.ToolRegistry;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.ChatRunBus;
import com.wenqu.ai.service.SessionEventService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.ToolSpillService;
import com.wenqu.ai.util.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本轮的<b>记录与落库</b>（P6 刀 5c，自 {@code ChatService} 抽出）：一条问答轮从「实时看得见的过程」
 * 变成「刷新后还看得见的那条消息」，全在这里——工具卡状态、实时输出增量、过程独白分流、回答时间线、
 * Token 用量快照、截断判定与标注、半程正文兜底落库、计划收尾轮落库。
 *
 * <p><b>为什么这一族必须是一个 bean</b>：它们改的是同一批容器（{@code toolCalls}/
 * {@code timeline}/两个锚点/{@code roundUsage}/{@code degradations}）与同一张消息表。
 * 原先这条链摊在问答服务里，于是"done 落库"与"中断兜底落库"是两处各写一遍的同款 21 个位置参数——
 * 少传一位的表现是刷新后某个字段整块消失（时间线、plan、tokens 都曾这样丢过）。
 * 收在这里之后幂等闸、快照口径与落库参数只有一个出处。
 *
 * <p>三条口径别在改动中丢：
 * <ul>
 *   <li><b>终态才落库、start 只走实时</b>：{@code toolCalls} 里只留 done/error 那一份（全文上限 8KB），
 *       SSE 那份是短摘要（200 字），askUser 例外——它的入参被截会让前端 JSON 解析失败，
 *       看起来像"随机故障"（刷新后又好了）；</li>
 *   <li><b>时间线只存区间不存正文</b>，夹取按最终正文长度；接流快照<b>绝不动锚点</b>
 *       （动了就会在接流那一刻凭空多切一段，把用户正看着的表格劈开）；</li>
 *   <li><b>用量与账单同口径</b>：逐轮累加的真实 usage 优先，估算只在网关没回时兜底，
 *       且分类之和必须严格等于网关 prompt_tokens（舍入误差归到最后一个分类）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class TurnRecorder {

    private final SessionService sessionService;
    private final ArtifactService artifactService;
    private final ChatRunRuntime runtime;
    private final SessionEventService sessionEventService;
    private final ToolRegistry toolRegistry;
    private final ToolSpillService toolSpillService;
    private final RunCodeSubCallRuntime subCall;
    private final ChatRunBus chatRunBus;

    public TurnRecorder(SessionService sessionService,
                        ArtifactService artifactService,
                        ChatRunRuntime runtime,
                        SessionEventService sessionEventService,
                        ToolRegistry toolRegistry,
                        ToolSpillService toolSpillService,
                        RunCodeSubCallRuntime subCall,
                        ChatRunBus chatRunBus) {
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.runtime = runtime;
        this.sessionEventService = sessionEventService;
        this.toolRegistry = toolRegistry;
        this.toolSpillService = toolSpillService;
        this.subCall = subCall;
        this.chatRunBus = chatRunBus;
    }

    /**
     * 进程收尾（重启/部署/被容器杀掉）时追加的标记。第三种成因，必须与前两个分开：用户既没按停止、
     * 连接也没断，是服务本身换了个进程——写成「已停止本轮」会让人以为是自己手滑停的。
     */
    public static final String RESTART_SUFFIX = "\n\n> ⏹ 服务在这一轮中途重启，以上为已生成的部分";

    /**
     * 整轮失败（模型流抛异常 / 工具失败冒泡）时追加在正文尾部的标注。
     * 与 TurnState.TRUNCATION_SUFFIX 区分：中断是「本来还能继续」，失败是「这次没能答完」。
     * 工具卡片已经记录了失败原因，这行只交代正文本身的完整性，刷新后仍在。
     */
    public static final String STREAM_ERROR_SUFFIX =
            "\n\n> ⚠️ 本轮回答未能完成，以上为已生成的部分（失败的工具调用记录见下方）";

    /** 网关 finish_reason=length（输出达长度上限）时的用户可见标注：正文尾部直书，刷新后仍在 */
    public static final String LENGTH_TRUNCATED_SUFFIX =
            "\n\n> ⏹ 回答写到长度上限被截断，可让我接着说完，或重新生成";

    /** 生成量与正文字数严重不符（内容在生成链路里丢失）时的用户可见标注 */
    public static final String OUTPUT_LOST_SUFFIX =
            "\n\n> ⚠️ 这次回答不完整，有内容在生成过程中丢失，建议重新生成";

    /** 生成量对账门槛：最终轮输出不足此 token 数时不参与对账（短回答的字符/token 比波动太大） */
    private static final int OUTPUT_LOSS_MIN_TOKENS = 150;

    /** 生成量对账比例：中文字符/token 实测 0.7~1.4，低于 0.15 才认定有内容丢失（留足误判余量） */
    private static final double OUTPUT_LOSS_CHAR_PER_TOKEN = 0.15;


    /** 工具入参/结果分级截断：SSE 实时只发 200 字摘要（防超长工具 I/O 撑爆 SSE 帧与实时渲染） */
    private static final int TOOL_IO_SSE_BRIEF = 200;

    /** 终态记录（done 汇总 + 随消息持久化）保存的全文上限：前端工具卡片展开查看完整入参/输出 */
    private static final int TOOL_IO_MAX = 8 * 1024;

    /** 原文已外溢成功时挂在预览尾部的说明（前端据此显示「查看完整输出」，这句只兜住展开前的一眼） */
    private static final String SPILL_SUFFIX = "\n\n…（以上为预览，完整输出可点卡片下方「查看完整输出」读回）";

    /** 子对话卡里那份「它的回答」超长时的收尾说明——截断要写在脸上，不能看着像它只说了这么多 */
    private static final String ANSWER_CLIP_SUFFIX = "\n\n…（它的回答过长，卡片内已截断；完整回报见本卡「输出」）";

    /** 从 consultAgent 的入参里取被咨询那位的 id（取不到返回 null：宁可少一张卡，不拿名字去猜归属） */
    private static String consultTarget(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            JSONObject j = JSON.parseObject(input);
            return j == null ? null : j.getString("agentId");
        } catch (Exception e) {
            return null;
        }
    }

    /** 取出工具侧上报的外溢标识（按工具名匹配最早那条未消费的；没有返回 null） */
    private static String takeToolSpill(TurnState st, String name) {
        for (java.util.Iterator<String> it = st.toolSpills.iterator(); it.hasNext(); ) {
            String e = it.next();
            int bar = e.indexOf('|');
            if (bar > 0 && name.equals(e.substring(0, bar))) {
                it.remove();
                return e.substring(bar + 1);
            }
        }
        return null;
    }

    /** 超出展示上限<b>又没能留住原文</b>（外溢开关关闭或落盘失败）时的实话：说清楚少了多少，别装作完整 */
    private static String spillNote(String text) {
        if (text == null || text.length() <= TOOL_IO_MAX) return "";
        return "\n\n…（其余 " + (text.length() - TOOL_IO_MAX) + " 字未显示，且原文未能留存，请缩小范围重跑）";
    }

    /**
     * 哪些工具的 SSE 实时副本必须带完整入参/结果（不受 {@link #TOOL_IO_SSE_BRIEF} 截断）。
     *
     * <p>截断对这些工具不是「摘要」而是「破坏」：前端要 JSON.parse 出来才能渲染，
     * 砍一刀后解析必然失败，于是卡片只剩半截。askUser 尤其致命——多问题的入参
     * （questions 数组）动辄 600+ 字符，200 字截断后既丢问题、又把答案的 JSON
     * 数组当纯文本原样打印，且**刷新后走8KB 全文又正常**，看起来像随机故障。
     */
    private static boolean needsFullToolIO(String name) {
        return "askUser".equals(name);
    }

    public static String truncBrief(String s, int max) {
        return s == null ? "" : s.substring(0, Math.min(max, s.length()));
    }

    /** 工具实时输出增量：SSE tool_output 事件（前端在运行中的工具卡片里实时滚动）。不落库——
     *  完整输出随 done 汇总的 result 走持久化；本事件仅服务执行期的"看着它跑"。 */
    public void recordToolOutput(TurnState st, String toolName, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String brief = delta.length() > 4096 ? delta.substring(0, 4096) : delta; // 单片上限，防异常大块撑爆 SSE 帧
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", toolName);
        rec.put("delta", brief);
        String payload = JSON.toJSONString(rec);
        // 总线优先：接流的观众也要看着工具卡片转圈与实时输出滚动（快照给不了「正在跑」这一条）
        if (!chatRunBus.publishRaw(st.channel.emitter, "tool_output", payload, st.sessionId)) {
            try {
                st.channel.emitter.send(SseEmitter.event().name("tool_output")
                        .data("{\"type\":\"tool_output\",\"content\":" + payload
                                + ",\"sessionId\":\"" + st.sessionId + "\"}"));
            } catch (Exception e) {
                log.debug("[TOOL-OUTPUT] SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
    }

    /**
     * 把锚点到 pos 之间的正文记为一个文本段（无新增正文则忽略）。工具/产物插入时间线前调用，
     * 使「正文 → 工具 → 正文」的交错顺序可被记录（只存区间，不复制正文）。
     */
    public void flushTimelineText(TurnState st, int pos) {
        int from = st.timelineAnchor.get();
        if (pos <= from) return;
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", "text");
        seg.put("from", from);
        seg.put("to", pos);
        st.timeline.add(seg);
        st.timelineAnchor.set(pos);
    }

    /**
     * 过程时间线成段：锚点到 pos 之间的过程独白记为一个过程段（区间指向 processText，与正文段同构）。
     * 连续追加时锚点不前移、区间自动延伸；工具/产物插入时先成段再插卡，保持「正文—过程—工具」真实交错。
     */
    public void flushTimelineProcess(TurnState st, int pos) {
        int from = st.processAnchor.get();
        if (pos <= from) return;
        synchronized (st.timeline) {
            // 连续追加合并进末段：过程独白是按 token 增量下发的（routeProcessText 每条都调本方法），
            // 无条件新建段会把一段独白切成上百个 1~2 字的过程段，done 落库后前端每个碎片各渲染一个
            // 「执行过程」折叠头。末段仍是过程段且正好接在锚点上（其间没插工具/产物段）时延伸它。
            // 工具/产物打断时末段已是 tool/artifact 段，此处自然另起新段，交错顺序如实保留。
            if (!st.timeline.isEmpty()) {
                Map<String, Object> last = st.timeline.get(st.timeline.size() - 1);
                if (last != null && "process".equals(last.get("kind")) && toInt(last.get("to")) == from) {
                    last.put("to", pos);
                    st.processAnchor.set(pos);
                    return;
                }
            }
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("kind", "process");
            seg.put("from", from);
            seg.put("to", pos);
            st.timeline.add(seg);
        }
        st.processAnchor.set(pos);
    }

    /**
     * 过程独白入通道：累积 processResponse、推进时间线过程段、SSE process 实时下发。
     * 客户端断开只丢实时显示，累积与落库不受影响。
     */
    public void routeProcessText(TurnState st, String delta) {
        if (delta == null || delta.isEmpty()) return;
        st.processResponse.append(delta);
        flushTimelineProcess(st, st.processResponse.length());
        runtime.sendSseEvent(st.channel.emitter, "process", delta, st.sessionId);
    }

    /** 产物生成时刻：正文成段后插入产物段（下标指向本轮产物清单，与 artifacts 数组同源同序） */
    public void pushTimelineArtifact(TurnState st, int artifactIndex) {
        flushTimelineText(st, st.fullResponse.length());
        flushTimelineProcess(st, st.processResponse.length());
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", "artifact");
        seg.put("i", artifactIndex);
        st.timeline.add(seg);
    }

    /**
     * 落库/下发用的时间线快照：补上尾部正文并按最终正文长度夹取。
     * 引用自检可能删除若干引用标记使最终正文短于流式累积，区间按最终版收敛（不越界）；
     * 只有「单段覆盖全文」时返回空（与整段渲染等价，不必占一列存储）。
     */
    public List<Map<String, Object>> buildTimelineSnapshot(TurnState st, String answer,
                                                            int toolCount, int artifactCount) {
        return timelineSnapshot(st, answer == null ? 0 : answer.length(), toolCount, artifactCount, true);
    }

    /**
     * 时间线快照。<b>flush 是这个方法的唯一分岔</b>：
     * <ul>
     *   <li>{@code true}（终态落库/done）：把锚点之后尚未成段的正文/过程补成段并<b>推进锚点</b>——
     *       本轮到此结束，锚点没有后续可言；</li>
     *   <li>{@code false}（断线重连接流）：<b>绝不动锚点</b>。在实时跑着的一轮上推进锚点，等于在接流那一刻
     *       凭空多切一个文本段，把用户正在看的表格/代码块从中间劈开，而 done 之后又是另一份切法
     *       （实时视图与刷新视图本应同源）。尾段只在副本上补齐，用于让接流方看到「已经流出的那段正文」。</li>
     * </ul>
     */
    public List<Map<String, Object>> timelineSnapshot(TurnState st, int len,
                                                       int toolCount, int artifactCount, boolean flush) {
        int processLen = st.processResponse.length();
        List<Map<String, Object>> src;
        synchronized (st.timeline) {
            src = new ArrayList<>(st.timeline.size() + 2);
            for (Map<String, Object> seg : st.timeline) {
                if (seg != null) src.add(new LinkedHashMap<>(seg));
            }
        }
        if (flush) {
            flushTimelineText(st, len);
            flushTimelineProcess(st, processLen);
        } else {
            appendPendingTimelineTails(st, src, len, processLen);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> seg : src) {
            if (seg == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(seg);
            if ("text".equals(copy.get("kind"))) {
                int from = Math.min(toInt(copy.get("from")), len);
                int to = Math.min(toInt(copy.get("to")), len);
                if (to <= from) continue; // 空段：不渲染也不打断交错
                copy.put("from", from);
                copy.put("to", to);
            } else if ("process".equals(copy.get("kind"))) {
                // 过程段区间指向 processText，按其最终长度夹取（与正文段同规则）
                int from = Math.min(toInt(copy.get("from")), processLen);
                int to = Math.min(toInt(copy.get("to")), processLen);
                if (to <= from) continue;
                copy.put("from", from);
                copy.put("to", to);
            } else if ("tool".equals(copy.get("kind"))) {
                // 下标段必须与终态清单一一对齐：工具并发（终态入列顺序与 start 不同）时
                // 该段无法指回确定的工具，丢弃比张冠李戴更诚实（卡片信息不会错配）
                int i = toInt(copy.get("i"));
                if (i < 0 || i >= toolCount) continue;
            } else if ("artifact".equals(copy.get("kind"))) {
                int i = toInt(copy.get("i"));
                if (i < 0 || i >= artifactCount) continue;
            }
            out.add(copy);
        }
        if (out.size() == 1 && "text".equals(out.get(0).get("kind"))
                && toInt(out.get(0).get("from")) == 0 && toInt(out.get(0).get("to")) == len) {
            return List.of();
        }
        return out;
    }

    /**
     * 只读尾段补齐：把「锚点之后尚未成段的正文/过程」接在副本末尾（与 flushTimelineText/Process 同规则：
     * 末段同类且正好接在锚点上就延伸它，否则另起一段）。只改 {@code src}，不碰 {@code st.timeline} 与锚点。
     */
    private static void appendPendingTimelineTails(TurnState st, List<Map<String, Object>> src,
                                                   int len, int processLen) {
        appendTailIfLonger(src, "text", st.timelineAnchor.get(), len);
        appendTailIfLonger(src, "process", st.processAnchor.get(), processLen);
    }

    private static void appendTailIfLonger(List<Map<String, Object>> src, String kind, int from, int to) {
        if (to <= from) return;
        Map<String, Object> last = src.isEmpty() ? null : src.get(src.size() - 1);
        if (last != null && kind.equals(last.get("kind")) && toInt(last.get("to")) == from) {
            last.put("to", to);
            return;
        }
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", kind);
        seg.put("from", from);
        seg.put("to", to);
        src.add(seg);
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 中断/失败兜底落库：客户端停止/断开、看门狗截断、流错误把生成流掐断时，把已产出的半程内容
     * 按截断态落库。此前只有完整走完 doOnComplete 才落库，中断轮在历史里只剩问题——刷新/重进会话后
     * 「已生成的部分」全部蒸发（连用户盯着跑了五分钟的工具卡片与计划快照一起）。与正常完成路径
     * 共用 answerPersistGate 幂等闸（CAS 先到先得，谁先落库谁赢）。
     * 只落「已下发给前端」的正文（fullResponse 与 token 事件同源；尾部扣留的标签前缀缓冲不参与）；
     * 引用自检/图片过滤/记忆提取/QA 日志均不再执行（那是完整轮的收尾语义），正文尾部追加标记，
     * 时间线仍按原始半程长度夹取（标记落在区间之外，前端 restore 后由尾段兜底渲染）。
     * <p>「正文为空但已有工具记录」同样落库：工具执行/计划确认这类轮的价值全在工具卡片与计划快照上，
     * 正文可能一个 token 都没有——按「正文非空」判会让整轮记录蒸发。真正空轮（正文、工具记录
     * 都为空，如客户端秒断）不落库：落一条空消息只会污染历史。
     *
     * @param tailMarker 正文尾部追加的可见标记（截断态/失败态各自不同，均落在时间线区间之外）
     */
    public void persistPartialAnswer(TurnState st, String tailMarker) {
        try {
            String answer = st.fullResponse.toString();
            // 空轮不落库：正文、工具记录、过程独白、已完成的思考全空时，落了也是一条空消息，只污染历史。
            // 思考要算进来——深度思考收尾后、正文首字之前被掐的轮（断线/看门狗/用户停止都在这窗口），
            // 那一轮并非「什么都没发生」：刷新后至少留得住它想过什么。
            String thinkingSoFar = st.thinkingHolder[0];
            if (answer.isEmpty() && st.toolCalls.isEmpty() && st.processResponse.length() == 0
                    && (thinkingSoFar == null || thinkingSoFar.isBlank())) {
                return;
            }
            List<Map<String, Object>> sources = st.refs.sources;
            List<String> finalImgs = new ArrayList<>(st.refs.imgIndex.values());
            String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
            // retrieved 状态行 refs 对齐 + 编排视图随持久化（与 doOnComplete 同款小步：来源可能被工具续编）
            String finalRetrievedJson = st.retrievedJson;
            try {
                Map<String, Object> rj = JSON.parseObject(st.retrievedJson);
                if (rj != null) {
                    Integer oldRefs = rj.get("refs") instanceof Number n ? n.intValue() : null;
                    if (oldRefs == null || oldRefs != sources.size()) {
                        rj.put("refs", sources.size());
                    }
                    if (!st.subagentBranches.isEmpty()) {
                        rj.put("branches", st.subagentBranches);
                    }
                    if (st.subagentRoute != null) {
                        rj.put("route", st.subagentRoute);
                    }
                    finalRetrievedJson = JSON.toJSONString(rj);
                }
            } catch (Exception ignored) {
            }
            List<Map<String, Object>> sessionArtifacts = artifactService.takeArtifacts(st.sessionId);
            List<Map<String, Object>> toolCallSnapshot = new ArrayList<>(st.toolCalls);
            String toolCallsJson = toolCallSnapshot.isEmpty() ? null : JSON.toJSONString(toolCallSnapshot);
            // 时间线按原始半程正文夹取；截断标记追加在快照之后（区间之外，前端尾段兜底渲染）
            List<Map<String, Object>> timelineSnapshot =
                    buildTimelineSnapshot(st, answer, toolCallSnapshot.size(), sessionArtifacts.size());
            String timelineJson = timelineSnapshot.isEmpty() ? null : JSON.toJSONString(timelineSnapshot);
            String processText = st.processResponse.toString();
            Map<String, Object> tokens = buildUsageSnapshot(st, answer);
            // 重新生成被中断：同样软删被替换的旧回答并登记分支版本组——用户已看到新版在气泡里替代旧版，
            // 历史保持同观感（半程截断版同样挂组，多版本口径一致）
            String replaceGroup = null;
            if (st.replaceMessageId != null && !st.replaceMessageId.isBlank()) {
                replaceGroup = sessionService.beginAnswerReplace(st.replaceMessageId);
            }
            // 走全量重载（带 plan）：中断轮同样把计划快照随截断版落库，刷新后计划卡与半程回答一起回显
            String partialMessageId = sessionService.appendMessage(st.sessionId, "assistant", answer + tailMarker,
                    finalImgs, sourcesJson, st.thinkingHolder[0], finalRetrievedJson,
                    sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                    toolCallsJson, null, JSON.toJSONString(tokens), timelineJson,
                    processText.isEmpty() ? null : processText,
                    st.agentId, st.agentName, st.model,
                    null, null, null,   // related / mentions / historyRefs：中断兜底沿用原有口径不带
                    planRecordJson(st), st.turnId);
            if (replaceGroup != null && partialMessageId != null) {
                sessionService.setMessageVariant(partialMessageId, replaceGroup);
            }
            // 中断轮同样留降级证据（半程回答也是这一轮的事实）
            sessionService.persistDegradations(partialMessageId, st.degradations);
            // 中断即终态：产物 emitter/监听注册表一并清理（此前断开路径无人清理，靠下一轮覆盖兜底）
            artifactService.unregisterEmitter(st.sessionId);
            log.info("[SSE] 中断/失败兜底：半程回答已落库 (session={}, chars={}, tools={})",
                    st.sessionId, answer.length(), toolCallSnapshot.size());
        } catch (Exception e) {
            // 兜底失败只记日志：管道已断无处下发错误，回退为现状「中断即丢失」
            log.warn("[SSE] 中断兜底落库失败 (session={}): {}", st.sessionId, e.getMessage());
        }
    }

    /**
     * Token 用量快照（done 下发与落库共用）：工具调用循环逐轮求和（native 原始口径）=全部请求轮次总量，
     * 与供应商账单同口径；最终轮（prompt 最大：工具结果逐轮入上下文）的输出用于触顶判定、其 prompt 用于
     * ctxParts 校准（分类拆的是最终上下文而非各轮总和）。无逐轮数据时回落旧单值字段，
     * 连真实 usage 都没有再回落 TokenCounter 估算；估算才需 10% 余量，实报应如实。
     * 中断兜底路径复用同一口径：逐轮实测（若有）如实反映已耗用量，无则按半程正文估算。
     */
    public Map<String, Object> buildUsageSnapshot(TurnState st, String answer) {
        long sumPrompt = st.roundUsage.promptTotal(), sumOutput = st.roundUsage.completionTotal();
        long sumCached = st.roundUsage.cachedTotal();
        long finalPrompt = st.roundUsage.finalRoundPrompt();
        long finalRoundOutput = st.roundUsage.finalRoundCompletion();
        boolean hasRounds = !st.roundUsage.isEmpty();
        boolean realOutput = hasRounds ? sumOutput > 0 : st.realOutputTokens > 0;
        int outputTokens = realOutput ? (int) (hasRounds ? sumOutput : st.realOutputTokens)
                : TokenCounter.estimate(answer);
        // 输出触顶 fail-loud：网关真实 completion_tokens 达到输出上限 ⇒ 大概率被
        // 截断（finish_reason=length），回答/related 推荐块不完整。原实现静默落库，
        // 用户只会看到残缺回答而无任何提示（"没有回答出内容"的帮凶之一）。
        // 上限与 max_tokens 下发同源（模型管理中该模型声明的最大输出）；未声明输出上限时无从判定，跳过。
        // 多轮时只看生成最终回答那一轮的输出（各轮工具调用参数的输出不参与触顶判定）
        int maxOutput = st.effectiveMaxOutput;
        int answerRoundOutput = hasRounds ? (int) finalRoundOutput : outputTokens;
        if (maxOutput > 0 && realOutput && answerRoundOutput >= maxOutput) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
                    "回答达到输出长度上限（" + maxOutput + " tokens），可能不完整；可在模型管理中调大该模型的最大输出");
        }
        int promptTokens = hasRounds ? (int) sumPrompt
                : (st.realPromptTokens > 0 ? st.realPromptTokens : st.contextTokens);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("context", st.contextTokens);
        tokens.put("budget", st.budgetTokens);
        tokens.put("hits", st.contextHits);
        tokens.put("output", outputTokens);
        tokens.put("prompt", promptTokens);
        tokens.put("outputIsReal", realOutput);
        tokens.put("total", promptTokens + outputTokens);
        // 容量面板数据：生效窗口/来源 + 分类用量（按网关真实 prompt 等比校准）+ 缓存命中
        tokens.put("window", st.windowTokens);
        tokens.put("windowSource", st.windowSource);
        tokens.put("cached", hasRounds ? (int) sumCached : st.cachedPromptTokens);
        tokens.put("parts", calibrateParts(st.ctxParts,
                hasRounds ? (int) finalPrompt : promptTokens, hasRounds || st.realPromptTokens > 0));
        // 摘要覆盖点（本轮装配时「折到哪条消息为止」）：前端按「比上一轮更靠后」在对话流里画
        // 那条「已压缩上下文」分隔线——手动 /compact 推后了它，下一轮同样会被记下来，两条链路共用一份数据
        if (st.summaryUntilSeq > 0) {
            tokens.put("summaryUntil", st.summaryUntilSeq);
        }
        return tokens;
    }

    /**
     * 回答被截断的判定与用户可见标注文案（null=未截断）。两条独立证据：
     * ① 网关明说 finish_reason=length（输出达上限）；
     * ② 暗截断——最终轮真实输出 token 折算的字符量，远高于实际进入回答/过程/思考/工具入参的字符数，
     *    说明有内容在剥离环节消失了（此前这类轮次连一条提示都没有，用户只看到断在半句的回答）。
     * ② 只在网关回了真实 usage 时参与判定，且门槛留足余量：中英混排、表格与 markdown 都会拉低
     * 字符/token 比，宁可漏报不可误报。
     */
    public String truncationSuffix(TurnState st, String answer) {
        if (st.finishLength) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
                    "回答达到模型输出长度上限被截断；可在模型管理中调大该模型的最大输出");
            log.warn("[FAIL-LOUD] 网关 finish_reason=length，回答被截断: session={}, 正文 {} 字",
                    st.sessionId, answer.length());
            return LENGTH_TRUNCATED_SUFFIX;
        }
        long finalRoundOutput = st.roundUsage.finalRoundCompletion();
        boolean realUsage = st.roundUsage.completionTotal() > 0 || st.realOutputTokens > 0;
        // 工具入参同样是模型生成的输出（写文件类工具动辄几百 token），不计进"已交代"必然误判
        int toolArgChars = 0;
        for (Map<String, Object> rec : st.toolCalls) {
            Object args = rec.get("args");
            if (args != null) toolArgChars += String.valueOf(args).length();
        }
        int accounted = answer.length() + st.processResponse.length() + st.reasoningChars + toolArgChars;
        if (realUsage && finalRoundOutput >= OUTPUT_LOSS_MIN_TOKENS
                && accounted < finalRoundOutput * OUTPUT_LOSS_CHAR_PER_TOKEN) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "outputLost",
                    "模型本轮生成 " + finalRoundOutput + " tokens，仅 " + accounted + " 字进入回答，内容疑似丢失");
            log.warn("[FAIL-LOUD] 生成量与正文字数不符: session={}, 最终轮输出={} tokens, 已交代={} 字"
                            + "（正文 {}/过程 {}/思考 {}/工具入参 {}）, finishReason={}",
                    st.sessionId, finalRoundOutput, accounted, answer.length(),
                    st.processResponse.length(), st.reasoningChars, toolArgChars, st.lastFinishReason);
            return OUTPUT_LOST_SUFFIX;
        }
        return null;
    }

    public void recordToolStatus(TurnState st, String name, String input,
                                  String status, String resultOrError, long elapsedMs, int attempts,
                                  boolean denied) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", name);
        // 展示名与悬停说明随后端下发：工具卡上写「execute」用户读不懂，而前端那份手抄映射
        // 对 MCP 动态工具与新增工具一律失效（writeTodo 上线时清单与卡片都只剩英文名）
        // 展示名与悬停说明随后端下发：工具卡上写「execute」用户读不懂，而前端那份手抄映射
        // 对 MCP 动态工具与新增工具一律失效（writeTodo 上线时清单与卡片都只剩英文名）
        toolRegistry.putDisplayFields(rec, name);
        rec.put("status", status);
        rec.put("elapsedMs", elapsedMs);
        // 被守卫在执行前挡下：卡片照常留一行（失败要看得见），但这条不计入「跑了 N 步工具」
        if (denied) rec.put("denied", true);
        if (attempts > 1) {
            rec.put("attempts", attempts); // 自动重试后成功：前端显示「重试 N 次」
        }
        int sseLimit = needsFullToolIO(name) ? TOOL_IO_MAX : TOOL_IO_SSE_BRIEF;
        String argsBrief = truncBrief(input, sseLimit);
        rec.put("args", argsBrief);
        if (resultOrError != null) {
            rec.put(status.equals("error") ? "error" : "result", truncBrief(resultOrError, sseLimit));
        }
        // run_code 的扇出清单（P3b 过程下钻）：一次运行内部调了哪些工具、各自多久、有没有被挡下。
        // 只带终态那一份，且随卡片一起落库——刷新后仍然点得开，不然这段结构只有实时看得到
        if ("run_code".equals(name) && !"start".equals(status)) {
            // 这一段脚本结束了：面板不再刷（终态那一份就挂在下面这条记录里，随消息落库）
            st.board.runBoardActive = false;
            subCall.stopRunBoardTicker(st);
            if (!st.board.subCallLog.isEmpty()) rec.put("fanOut", subCall.buildFanOut(st, false));
            // 这一次运行到底是跑完了、超时了，还是沙盒压根没通——字段化随卡片落库，
            // 刷新后仍然看得出来（正文里那句话只有读的人才知道它在说什么）
            if (st.board.runOutcome != null) {
                rec.put("run", st.board.runOutcome);
                st.board.runOutcome = null;
            }
        }
        // 咨询另一位助手：卡片上挂一份「子对话」（我们问它什么、它答了什么、它据哪些资料），
        // 随卡片落库——刷新/换设备后仍然看得到那一问一答，而不是只剩一串裸 JSON 入参。
        // 与 run_code 的 fanOut/run 同一条路子：只挂终态那一份，按被咨询那位 id 从本轮台账取走
        //（一轮可以问多位、工具在弹性线程上并发跑，共用一个槽会串台）
        if ("consultAgent".equals(name) && !"start".equals(status)) {
            Map<String, Object> subChat = st.tools.consultLedger.takeReport(consultTarget(input));
            if (subChat != null) {
                // 与入参/输出同一把长度尺：一条跑偏的长回答不该把整条消息行撑爆
                if (subChat.get("answer") instanceof String s && s.length() > TOOL_IO_MAX) {
                    subChat.put("answer", s.substring(0, TOOL_IO_MAX) + ANSWER_CLIP_SUFFIX);
                }
                rec.put("subChat", subChat);
            }
        }
        // 工具发起时刻：锚点前已产出的正文/过程独白先各自成段，再插入工具段——
        // 记录的是「正文与过程说到多少之后发起的工具」，刷新后据此把卡片放回原位
        if ("start".equals(status)) {
            flushTimelineText(st, st.fullResponse.length());
            flushTimelineProcess(st, st.processResponse.length());
            Map<String, Object> toolSeg = new LinkedHashMap<>();
            toolSeg.put("kind", "tool");
            // 终态记录在 start 之后才入 toolCalls 列表，此处 size 正是它将落到的下标
            toolSeg.put("i", st.toolCalls.size());
            st.timeline.add(toolSeg);
        }
        // 只把终态（done/error）记入持久化列表：start 仅实时下发（前端转圈显示），
        // 否则快照里 start/done 成对存在，前端 done 汇总覆盖后工具状态行会出现重复双行
        if (!"start".equals(status)) {
            // 终态记录升级为全文（上限 8KB）：done 汇总与落库都用它，前端卡片展开可见完整入参/输出，
            // 历史恢复同样可展开（实时 SSE 副本保持短摘要，两者字段同名、前端合并时全文覆盖摘要）
            Map<String, Object> full = new LinkedHashMap<>(rec);
            // 超过上限的那一段不再「截断即丢」：整份原文外溢到磁盘，卡片留 spillId 供「查看完整输出」按需读回。
            // 写失败退回原样截断（旁路语义，绝不影响本轮问答）
            String argsSpillId = input != null && input.length() > TOOL_IO_MAX
                    ? toolSpillService.spill(st.sessionId, st.userId, name, input) : null;
            full.put("args", truncBrief(input, TOOL_IO_MAX)
                    + (argsSpillId == null ? spillNote(input) : SPILL_SUFFIX));
            if (argsSpillId != null) full.put("argsSpillId", argsSpillId);
            if (resultOrError != null) {
                String key = status.equals("error") ? "error" : "result";
                // 工具侧截断时已留存过就用它那一份（那才是全文）；否则由这层的上限补一份
                String spillId = takeToolSpill(st, name);
                if (spillId == null && resultOrError.length() > TOOL_IO_MAX) {
                    spillId = toolSpillService.spill(st.sessionId, st.userId, name, resultOrError);
                }
                full.put(key, truncBrief(resultOrError, TOOL_IO_MAX)
                        + (spillId == null ? spillNote(resultOrError) : SPILL_SUFFIX));
                if (spillId != null) full.put("spillId", spillId);
            }
            st.toolCalls.add(full);
        }
        // 后端日志同步留痕（与 [RAG]/[CTX] 等阶段日志同级可观测）：开始/完成/失败各一行，结果截断防爆量
        if ("start".equals(status)) {
            log.info("[TOOL] {} 调用开始 args={}", name, argsBrief);
        } else if ("error".equals(status)) {
            log.warn("[TOOL] {} 调用失败 ({}ms) error={}", name, elapsedMs, rec.get("error"));
        } else {
            log.info("[TOOL] {} 调用完成 ({}ms) result={}", name, elapsedMs,
                    resultOrError == null ? "" : truncBrief(resultOrError.replace('\n', ' '), 160));
        }
        String ssePayload = JSON.toJSONString(rec);
        // 总线优先：接流的观众看到的工具卡片要有实时状态（快照只给到接流那一刻为止的记录）
        if (!chatRunBus.publishRaw(st.channel.emitter, "tool_status", ssePayload, st.sessionId)) {
            try {
                st.channel.emitter.send(SseEmitter.event().name("tool_status")
                        .data("{\"type\":\"tool_status\",\"content\":" + ssePayload
                                + ",\"sessionId\":\"" + st.sessionId + "\"}"));
            } catch (Exception e) {
                log.debug("[TOOL-STATUS] SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
        // 账本留痕：工具步的「何时开始/结束、耗时、成败」是一轮执行轨迹的主干。载荷用 SSE 那份短摘要
        // ——终态全文（8KB）已随消息落库，账本不重复存大字段；start 也记，回放才看得出每步的起点。
        sessionEventService.append(st.sessionId, st.userId, st.turnId, "tool", ssePayload);
    }

    /**
     * 缓存命中 token 查阅（①强类型 native usage ②Map 方言字段③DeepSeek prompt_cache_hit_tokens）。
     * 实现统一放在 UsageAccumulator：台账与回答气泡共用一套方言解析，口径不会漂移；
     * 全部取不到返回 0——容量面板/台账的缓存列隐藏，不显示假数据。
     */
    public int extractCachedTokens(org.springframework.ai.chat.metadata.Usage usage) {
        return (int) com.wenqu.ai.util.UsageAccumulator.cachedTokensOf(usage);
    }

    /**
     * 分类用量按网关真实 prompt token 等比校准：估算器（TokenCounter 启发式）与真实 tokenizer 有偏差，
     * 直接用估算值会让「分类之和 ≠ 网关报的 prompt_tokens」，面板看起来自相矛盾。
     * 有真实值时整体等比缩放（保持各分类相对比例），无真实值时原样返回估算。
     */
    private Map<String, Integer> calibrateParts(Map<String, Integer> parts, int promptTokens, boolean hasReal) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (parts == null || parts.isEmpty()) return out;
        long sum = 0;
        for (Integer v : parts.values()) sum += (v == null ? 0 : v);
        if (!hasReal || sum <= 0 || promptTokens <= 0) {
            for (Map.Entry<String, Integer> e : parts.entrySet()) out.put(e.getKey(), e.getValue());
            return out;
        }
        double k = promptTokens / (double) sum;
        long acc = 0;
        Map.Entry<String, Integer> last = null;
        for (Map.Entry<String, Integer> e : parts.entrySet()) {
            int v = (int) Math.round(e.getValue() * k);
            out.put(e.getKey(), v);
            acc += v;
            last = e;
        }
        // 舍入误差归到最后一个分类，保证分类之和严格等于网关 prompt_tokens。
        // 注意必须用「已缩放后的值」(out.get) 累加补差，不能用原始 last.getValue()——
        // 否则末项会被还原回缩放前的值，使之和偏离 prompt_tokens（本仓实测偏差=末项缩放前-缩放后）。
        if (last != null) out.put(last.getKey(), out.get(last.getKey()) + (int) (promptTokens - acc));
        return out;
    }

    /** 计划快照 → 助手消息 plan 列 JSON（null=本轮未走计划模式）；落库点见 buildAnswerStream 与中断兜底 */
    public String planRecordJson(TurnState st) {
        return st.planRecord == null ? null : JSON.toJSONString(st.planRecord);
    }

    /**
     * 计划被拒/超时的收尾落库：本轮没有流式回答（gate==null 直接终止，不会走 buildAnswerStream 的落库）。
     */
    public void persistPlanRejected(TurnState st) {
        persistPlanCloseout(st, "（计划未批准，本轮已停止）", "拒绝");
    }

    /**
     * 「继续对话」取代的收尾落库：与拒绝轮同构，但卡片终态是「已被取代」而不是「未批准」——
     * 用户并没有否定计划内容，只是提出了修改；新一轮会按他的意见产出修改后的计划。
     * <p>正文留空：这一行的本体是 plan 快照（执行计划卡）与本轮执行快照，「已被取代」已经写在卡片
     * 角标上，再落一句收束语只是把同一件事说两遍。留空后前端历史过滤要认 plan 快照，否则卡片随刷新蒸发。
     */
    public void persistPlanSuperseded(TurnState st) {
        persistPlanCloseout(st, "", "取代");
    }

    /**
     * 计划收尾轮（未批准 / 已被取代）的兜底落库：正文（未批准=收束语，取代=空）+ plan 快照 + 本轮执行快照。
     * <p>执行快照（提问卡问答、工具卡片、过程独白、时间线）必须一并落：这些在实时气泡里是用户
     * 看得见的「已输出的内容」（如计划前的那张问答记录卡），只落收束语会让它们刷新后整块消失，
     * 而计划卡本身也随刷新蒸发——历史只剩一条孤零零的用户提问。
     * <p>answerPersistGate 先到先得：占住闸位也宣告本轮已收尾，通道断开兜底（disposeSafe）与
     * 看门狗不再对同一轮补落第二条。
     */
    public void persistPlanCloseout(TurnState st, String content, String phase) {
        if (!st.claimTerminal()) return;   // 已有终态路径落过库
        try {
            List<Map<String, Object>> sessionArtifacts = artifactService.takeArtifacts(st.sessionId);
            List<Map<String, Object>> toolCallSnapshot = new ArrayList<>(st.toolCalls);
            String toolCallsJson = toolCallSnapshot.isEmpty() ? null : JSON.toJSONString(toolCallSnapshot);
            // 时间线按收束语长度夹取：计划轮的正文只可能是收束语（无回答流），工具/过程段照常保留
            List<Map<String, Object>> timelineSnapshot =
                    buildTimelineSnapshot(st, content, toolCallSnapshot.size(), sessionArtifacts.size());
            String timelineJson = timelineSnapshot.isEmpty() ? null : JSON.toJSONString(timelineSnapshot);
            String processText = st.processResponse.toString();
            String closeoutMessageId = sessionService.appendMessage(st.sessionId, "assistant", content,
                    null, null, st.thinkingHolder[0], st.retrievedJson,
                    sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                    toolCallsJson, null, null, timelineJson, processText.isEmpty() ? null : processText,
                    st.agentId, st.agentName, st.model,
                    null, null, null,
                    planRecordJson(st), st.turnId);
            // 降级条同样要能刷新后还在（计划收尾轮也会登记降级，如"上游不认工具"）
            sessionService.persistDegradations(closeoutMessageId, st.degradations);
        } catch (Exception e) {
            log.warn("[PLAN] {}轮收尾落库失败 (session={}): {}", phase, st.sessionId, e.getMessage());
        }
    }
}
