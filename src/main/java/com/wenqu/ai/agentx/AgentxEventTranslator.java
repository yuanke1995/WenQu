package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.SteerQueueHooks;
import com.wenqu.ai.skill.SkillStatsStore;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.RelatedPrompt;
import com.wenqu.ai.agent.chat.TurnRecorder;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.plan.PlanCopy;
import com.wenqu.ai.agentx.output.ModelOutputParser;
import com.wenqu.ai.agentx.turn.TurnDonePayload;
import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator.PendingKind;
import com.wenqu.ai.agentx.turn.TurnPromptAssembler;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import com.wenqu.ai.agentx.turn.TurnTranscriptRecorder;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.engine.ChatEngine;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.service.ChatAttachmentService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.util.TextIo;
import com.wenqu.ai.util.TurnBlocks;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.DefaultJedisClientConfig;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.*;

/**
 * 框架 Session Log 的事件词汇 → 问渠前端事件名（从 AgentscopeChatEngine 抽出）。
 * 只依赖 toolCardBuilder（拼工具卡）与 slf4j；noteToolUseDelta / noteSkillToolRead 是它内部用的
 * 早产工具卡与技能读取小工具，一并搬来。translate 的调用方（drain）留在引擎，调用本类实例。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxEventTranslator {

    private static final Logger log = LoggerFactory.getLogger(AgentxEventTranslator.class);

    private final AgentxToolCardBuilder toolCardBuilder;
    private final SkillStatsStore skillStatsStore;
    /** steerQueueHooks 由 bindSteerQueue 后绑定，用 Supplier 惰性取（构造时可能还是 null）。 */
    private final Supplier<SteerQueueHooks> steerQueueHooks;

    /** 框架技能正文读取工具名：tool/requested 出现这三把之一，就是模型在按需读一条技能。 */
    private static final java.util.Set<String> SKILL_READ_TOOLS = java.util.Set.of(
            "load_skill_through_path", "read_skill", "use_skill");

    /**
     * 框架 Session Log 的事件词汇 → 问渠前端事件名。
     * <p>刻意不发：{@code run/*}、{@code step/*}、{@code state/checkpoint}、{@code model/dispatch} 这些
     * 没有前端对应物的帧——前端对认不出的事件名会打 warn（{@code api.js:158}），不如不发。
     */
    public void translate(SessionEvent event, RunChannel channel, String turnId, AgentxTurnRecording recording) {
        // 事件幂等闸：同一轮可能有两条跟随路径（原跟随循环 + ensureFollowing 补位）各自从 Session Log
        // 读事件喂进来，重投的那份若照收，正文就会被整段记录/重发两遍。
        // 框架事件自带轮内单调 seq，这里按「本轮已见 seq」跳过重投件：谁先到谁处理，后到的直接丢。
        if (!recording.firstDelivery(event.seq())) return;
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        switch (event.type()) {
            case "model/chunk" -> on_model_chunk(data, channel, turnId, recording);
            case "tool/requested" -> on_tool_requested(data, channel, turnId, recording);
            case "tool/result" -> on_tool_result(data, channel, turnId, recording);
            case "tool/decision" -> on_tool_decision(data, channel, turnId, recording);
            case "plan/changed" -> on_plan_changed(data, channel, turnId, recording);
            case "run/end" -> recording.runEnded = true;
            case "run/start", "inbox/opened" -> on_run_start_inbox_opened(data, channel, turnId, recording);
            case "inbox/applied" -> on_inbox_applied(data, channel, turnId, recording);
            case "turn/interrupted" -> channel.emit(RunEventType.WARN, "本轮已被中断");
            // 轮内自动压缩：它跑的是两次 SUMMARY 用途的模型调用，思考与正文都被上面的 purpose 闸
            // 滤掉，不补这两帧，那二十多秒在界面上就是纯静默。
            case "compaction/start" -> channel.emit(RunEventType.COMPACT_STATE, "{\"phase\":\"start\"}");
            case "compaction/end" -> on_compaction_end(data, channel, turnId, recording);
            default -> {
            }
        }
    }

    /**
     * 早产工具卡：把一次 {@code tool_use} 入参增量交给 {@link AgentxTurnRecording#noteToolUseDelta}，
     * 够门槛就发一帧 {@code tool_status start}（带 {@code preparing/chars/brief}）。
     * <p>前端据 preparing 标记把这张卡渲染成「进行中」，正式 {@code tool/requested} 到达时原地升级，
     * 所以这里不需要记卡、也不需要等工具真正开始执行。
     */
    void noteToolUseDelta(RunChannel channel, AgentxTurnRecording recording, Map<?, ?> block) {
        AgentxTurnRecording.PreparingTick tick = recording.noteToolUseDelta(
                str(block.get("name")), str(block.get("id")), str(block.get("content")),
                System.currentTimeMillis());
        if (tick == null) return;
        try {
            AgentxEmitUtil.emit(channel, RunEventType.TOOL_STATUS,
                    toolCardBuilder.toolCard(recording.uid, tick.name(), "start", 0L, tick.startedAtMs(), 0L,
                            null, null, null, recording.hooks.step().index(),
                            true, tick.chars(), tick.brief(), false));
        } catch (RuntimeException gone) {
            // 通道已收（用户切走/刷新）：这一帧发不出去不影响正文与工具结果，别让跟随循环因此收场
            log.debug("[agentx] 早产工具卡帧没发出去（通道已收）: session={} tool={}",
                    channel.sessionId(), tick.name());
        }
    }

    /** 模型按需读取技能正文：记用量 + 发 skill_hit 帧（同轮多次读取各记一笔，前端按 name 合并芯片）。 */
    void noteSkillToolRead(AgentxTurnRecording recording, RunChannel channel, Object input) {
        if (!SKILL_READ_TOOLS.contains(recording.lastToolName)) return;
        if (!(input instanceof Map<?, ?> in)) return;
        String name = null;
        for (String key : new String[]{"skillId", "skill_id", "skill_name", "skillName", "name"}) {
            Object v = in.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                name = String.valueOf(v).trim();
                break;
            }
        }
        if (name == null) return;
        // 模型传的是框架 skillId（{技能名}_{source}，source 是技能根目录或 workspace 标记）：
        // 命中帧与统计都截回裸名，与点名那一路（emitPickedSkillHits）同口径，否则界面按带路径的
        // id 反查技能正文会查无此条。
        int cut = name.lastIndexOf("_/");
        if (cut > 0) name = name.substring(0, cut);
        else {
            int us = name.lastIndexOf('_');
            if (us > 0) name = name.substring(0, us);
        }
        if (name.isEmpty()) return;
        skillStatsStore.recordRead(recording.uid, name, channel.sessionId());
        try {
            channel.emit(RunEventType.SKILL_HIT, com.alibaba.fastjson2.JSON.toJSONString(
                    java.util.Map.of("name", name, "source", "model")));
        } catch (RuntimeException gone) {
            log.debug("[SKILL] skill_hit 帧没发出去（通道已收）: skill={}", name);
        }
    }
    /** translate 的 case "model/chunk" 分支。 */
    private void on_model_chunk(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 辅助模型调用（记忆提炼 / 压缩摘要，payload.purpose=SUMMARY）与主推理走同一条
                // Session Log：框架的 SessionModels.Recorded 把 purpose 放进每个 chunk 载荷，不认它
                // 的表现是提炼/摘要的正文（「## Memory Flush —（本轮新增）」「## SESSION INTENT /
                // SUMMARY / ARTIFACTS / NEXT STEPS」）被当成用户正文发 TOKEN 帧并落库，
                // 其 thinking 块同时把本轮思考撑爆 TEXT 列 → 整条消息落库失败。只透传
                // 主推理（REASONING）；SUMMARY 的账另有去处（记忆文件/压缩摘要消息），不进聊天流。
                if (!"REASONING".equals(String.valueOf(data.get("purpose")))) return;
                if (!(data.get("chunk") instanceof Map<?, ?> chunk)) return;
                if (!(chunk.get("content") instanceof List<?> blocks)) return;
                // 收尾聚合帧拦截：框架在流末会再发一条携带 finishReason 的
                // 「聚合后段」帧——正文在 ~2 万字处被一分为二，delta 流已完整覆盖全文，聚合帧只重发后段；
                // 照收等于尾部整段重投 + 接缝错位。识别规则：
                // finishReason 在场且帧文本已被已记录正文包含（纯重复）→ 整块跳过；正常收尾帧
                // （空正文/真正的续写）不受影响。
                boolean aggregateTail = chunk.get("finishReason") != null;
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> b)) continue;
                    if ("thinking".equals(b.get("type"))) {
                        String thinkingDelta = str(b.get("thinking"));
                        // 聚合帧同样会重发思考的尾段：正文有上面那道去重闸，思考没有，尾段就会在
                        // 回答末尾多出一行「深度思考」。
                        // 已记录过的一律跳过——它不是新一步的思考，只是同一段的尾巴。
                        if (aggregateTail && recording.thinkingContains(thinkingDelta)) {
                            log.info("[TIMELINE] 流末聚合帧重复思考尾段已跳过 {} 字: session={}",
                                    thinkingDelta.length(), recording.uid);
                            continue;
                        }
                        recording.thinking(thinkingDelta);
                        AgentxEmitUtil.emit(channel, RunEventType.THINKING, thinkingDelta);
                    } else if ("text".equals(b.get("type"))) {
                        String rawText = str(b.get("text"));
                        if (aggregateTail && rawText.length() > 0 && recording.answerContains(rawText)) {
                            continue;
                        }
                        // 正文开始 = 这一段思考收场：补一条 thinking_done，前端据此停转圈并折叠就地那段
                        AgentxEmitUtil.closeThinkingFrame(channel, recording);
                        ModelOutputParser.Delta parsed = recording.related.feed(rawText);
                        // 同一个 delta 里可能同时有过程与正文：必须按原文先后下发（processFirst），
                        // 否则「过程独白紧跟正文」被拆到正文后面，过程块就劈成两段。
                        boolean processFirst = parsed.processFirst();
                        if (processFirst) AgentxEmitUtil.emitProcess(channel, recording, parsed.process());
                        if (!parsed.text().isEmpty()) {
                            recording.answer(parsed.text());
                            AgentxEmitUtil.emit(channel, RunEventType.TOKEN, parsed.text());
                        }
                        // 过程独白（<process> 标签内）按增量实时下发 PROCESS：正文走 TOKEN，
                        // 过程另走一条通道，前端 onProcess 累积成「执行过程」灰字块。收尾帧在 finish() 里补齐。
                        if (!processFirst) AgentxEmitUtil.emitProcess(channel, recording, parsed.process());
                    } else if ("tool_use".equals(b.get("type"))) {
                        // 入参还在流式生成：先把「这一步在跑」发给前端（见 PREPARING_MIN_CHARS 那段说明）。
                        // 真正的 tool/requested 随后到达时，前端把同名早产卡升级成正式卡，不会再推第二张。
                        noteToolUseDelta(channel, recording, b);
                    }
                }
    }

    /** translate 的 case "tool/requested" 分支。 */
    private void on_tool_requested(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 正式调用来了：早产卡状态收摊，后面不再发进度帧（前端收到本帧会把那张卡升级成正式卡）
                recording.clearPreparing();
                // 调工具也是「这一段想完了」的信号：不在这儿收场，思考段会一直挂着转圈直到下一段正文出现，
                // 而时间线里这段思考也会压到工具卡后面（区间是按关闭时刻写的，顺序即到达顺序）
                AgentxEmitUtil.closeThinkingFrame(channel, recording);
                // 载荷形状：{toolCallId, call:{type,id,name,input}} —— 名字在 call.name 里，
                // 不在顶层；按顶层取会拿到空串，工具卡就成了没有名字的空白行
                recording.lastToolName = toolNameOf(data.get("call"));
                Object requestedInput = toolInputOf(data.get("call"));
                // 必须序列化成 JSON：咨询卡片按入参里的 agentId 取那一份子对话，
                // 用 Map.toString() 就得到 "{agentId=xxx}" 这种 Java 形状，解析必然失败
                recording.lastToolInput = com.alibaba.fastjson2.JSON.toJSONString(requestedInput);
                // 按这一次调用登记入参与开始时刻：一步里并发两把工具时，共用字段会被后到的盖掉（见 toolInputByKey）
                String callKey = AgentxTurnRecording.toolCallKey(toolCallIdOf(data), recording.lastToolName);
                recording.toolInputByKey.put(callKey, recording.lastToolInput);
                // 技能正文被模型按需读取：记一笔使用统计并给前端发命中帧（非技能工具是空操作）
                noteSkillToolRead(recording, channel, requestedInput);
                // 收集模型实际发出的检索词：search_knowledge 的 query 入参即它真去搜的短语
                if (requestedInput instanceof Map<?, ?> in) {
                    if ("search_knowledge".equals(recording.lastToolName)) {
                        Object q = in.get("query");
                        if (q != null && !String.valueOf(q).isBlank()) recording.searchQueries.add(String.valueOf(q).trim());
                    }
                    // 计划正文只出现在 plan_write 的 content 入参里（plan_exit 只有一句 summary）
                    if ("plan_write".equals(recording.lastToolName)) {
                        String content = str(in.get("content")).trim();
                        if (!content.isEmpty()) recording.planMarkdown = content;
                    }
                }
                long beganAt = recording.markToolStart(callKey);
                recording.toolCall(recording.lastToolName, "start", recording.lastToolInput, 0L, beganAt, 0L, null);
                // 进行中必须是 "start"（不是 "running"）：前端 onToolStatus 只认 status==='start' 才建/合并
                // 实时工具卡。发 "running" 会让前端把这张当终态卡 push 出去，随后的 done 又找不到可合并的
                // start 卡再 push 一张——一次调用渲染成两张，其中停在 "running" 的那张显示为失败(❌)。
                AgentxEmitUtil.emit(channel, RunEventType.TOOL_STATUS,
                        toolCardBuilder.toolCard(recording.uid, recording.lastToolName, "start", 0L, beganAt, 0L, recording.lastToolInput, null, null,
                                recording.hooks.step().index()));
    }

    /** translate 的 case "tool/result" 分支。 */
    private void on_tool_result(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 兜底收摊：没有配上的 tool/requested 时（从游标中途接流）也别让早产进度帧继续发
                recording.clearPreparing();
                String name = toolNameOf(data.get("message"));
                if (name.isBlank()) name = recording.lastToolName;
                // 入参与耗时都按这一次调用取回：读共用那份就是「甲张卡的入参是乙次调用的参数」
                String callKey = AgentxTurnRecording.toolCallKey(toolCallIdOf(data), name);
                String args = recording.toolInputByKey.remove(callKey);
                if (args == null) args = recording.lastToolInput;   // 配不上对应的 requested（如从游标中途接流）才沿用
                // 失败定性分三路，一条口径「有没有真的拿到结果」：
                // ① 本轮有基建故障上报（run_code 沙盒没通这类）→ error，优先于一切；
                // ② 结果带 executionDetails（execute 一类）→ 只看退出码：拿到码=业务结果
                //    （非 0 也是 done，码与输出都原样交给了模型），没拿到码=工具失败；
                // ③ 其余工具 → 结构化 state 判定。
                boolean failed = recording.hooks.infra().take(name) != null || toolResultFailed(data);
                // 被权限/守卫拒绝是第三种终态：既不是成功（✓），也不是工具执行失败（error）。
                // 以前只分 failed/非 failed，被拒的卡全落成 done——用户看到 6 个 ✓，实际一个都没跑。
                boolean denied = toolResultDenied(data);
                // 输出文本只取一次：落库那份和 SSE 那一帧必须是同一串，否则刷新前后两张卡说的不是一件事
                String resultText = toolResultText(data);
                // 进度账：失败/被拒的一步没有让任何东西向前走——换一个参数再失败也还是原地踏步
                recording.progress().note(name, args, resultText, failed || denied);
                // 耗时＝墙钟窗口扣掉这段等待：提问卡挂三分钟不是「这个工具跑三分钟」，是「用户读了三分钟」。
                // 扣掉的那段另发一份 waitedMs：整段跨度减它才剩机器时间，只在单卡上扣会留在区间中间出不去。
                long beganAt = recording.takeToolStart(callKey);
                // 扣账与记账分开：这张卡自己的耗时要把盖住它的等待减干净（同一步里别家挂卡也一样被闸门盖住），
                // 而对外那份 waitedMs 只在挂卡那一张上出现——跨度按各张之和减，记两张就扣两遍。
                long covered = recording.takeToolWait(callKey);
                long charged = recording.takeToolCharge(callKey);
                long elapsed = beganAt <= 0L ? 0L
                        : Math.max(0L, System.currentTimeMillis() - beganAt - covered);
                String terminal = failed ? "error" : (denied ? "denied" : "done");
                java.util.Map<String, Object> subChat = recording.toolCall(name, terminal, args, elapsed, beganAt, charged, resultText);
                // 前台等待到点的提问：这一帧不是「问完了」，卡片仍可答（答案稍后作为新发言送达）
                AgentxEmitUtil.emit(channel, RunEventType.TOOL_STATUS, toolCardBuilder.toolCard(recording.uid, name, terminal,
                        elapsed, beganAt, charged, args, resultText, subChat, recording.hooks.step().index(),
                        false, 0L, null, recording.isAskContinued(toolCallIdOf(data))));
    }

    /** translate 的 case "tool/decision" 分支。 */
    private void on_tool_decision(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 权限判定由框架说出（字段 decision=allowed|denied）：拒绝必须让用户看见，
                // 否则「这个能力没生效」会被读成没反应
                if (str(data.get("decision")).toUpperCase().contains("DENY")) {
                    channel.emit(RunEventType.WARN, "该操作被权限档位拒绝：" + recording.lastToolName);
                }
    }

    /** translate 的 case "plan/changed" 分支。 */
    private void on_plan_changed(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 问渠的计划卡认两种帧：plan（整份步骤列表）与 deep_plan_delta（流式追加）。
                // 框架这里给的是变更后的计划，能解析成列表就发整份，否则退成增量文本
                Object plan = firstNonNull(data.get("steps"), firstNonNull(data.get("plan"), data.get("content")));
                if (plan instanceof List<?> list && !list.isEmpty()) {
                    channel.emit(RunEventType.PLAN, com.alibaba.fastjson2.JSON.toJSONString(list));
                } else if (!str(plan).isBlank()) {
                    recording.planDelta(str(plan));
                    AgentxEmitUtil.emit(channel, RunEventType.DEEP_PLAN_DELTA, str(plan));
                }
    }

    /** translate 的 case "run/start", "inbox/opened" 分支。 */
    private void on_run_start_inbox_opened(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 框架的 run 到这里才算真的活着：此前 session.steer 必被拒。排队的插话在这一拍送进去，
                // 早了会白等一整轮，晚了就错过「下一次调模型前」这个送达点。
                // 两个事件都认：inbox 的提交路由不同（有的后端只把 run/* 写进主日志），
                // drain 自身按「有没有活轮 + 有没有排队项」幂等，重复唤醒不会重发。
                com.wenqu.ai.agent.chat.SteerQueueHooks hooks = steerQueueHooks.get();
                if (hooks != null) hooks.drain(channel.sessionId());
    }

    /** translate 的 case "inbox/applied" 分支。 */
    private void on_inbox_applied(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // 框架把插话写进会话上下文的凭证（载荷是 {commandIds:[...]}，一次可能收好几条）。
                // 记在 recording 上而不是只记在镜像里：收尾那一次全量补读传的镜像是 null，
                // 而那正是最后一条插话最可能被应用的时刻。
                // 同一刻还要给它留痕（回答时间线里的一段 + 一帧 steer）：界面上那句该长在
                // 「模型真听到它」的位置，而不是整段回答的上面或下面。
                if (data.get("commandIds") instanceof List<?> ids) {
                    for (Object id : ids) {
                        String receiptId = String.valueOf(id);
                        if (!recording.markSteerApplied(receiptId)) continue;   // 补读会重读同一条
                        com.wenqu.ai.agent.chat.SteerQueueHooks queueHooks = steerQueueHooks.get();
                        if (queueHooks != null) queueHooks.applied(channel.sessionId(), receiptId);
                        com.wenqu.ai.agent.chat.SteerReceipts.SteerAck ack =
                                AgentxPlanSteerUtil.steerAckOf(turnId, receiptId);
                        if (ack == null) continue;
                        recording.steer(ack.text(), ack.messageId(), ack.images(), ack.attachments());
                        Map<String, Object> frame = new java.util.LinkedHashMap<>();
                        frame.put("text", ack.text());
                        frame.put("messageId", ack.messageId() == null ? "" : ack.messageId());
                        if (ack.images() != null && !ack.images().isEmpty()) frame.put("images", ack.images());
                        if (ack.attachments() != null && !ack.attachments().isEmpty()) {
                            frame.put("attachments", ack.attachments());
                        }
                        AgentxEmitUtil.emit(channel, RunEventType.STEER, com.alibaba.fastjson2.JSON.toJSONString(frame));
                    }
                }
    }

    /** translate 的 case "compaction/end" 分支。 */
    private void on_compaction_end(Map<String, Object> data, RunChannel channel, String turnId, AgentxTurnRecording recording) {
                // status 必须带给前端：压完了那条线要原地换成「已压缩上下文」，压砸了则整条退场——
                // 只发「结束」不发「结束得怎么样」，界面上就只能一律报喜。
                // 框架载荷是 {compactionId, status=completed|failed|cancelled}，缺键按 failed 处理。
                String status = str(data.get("status"));
                boolean done = "completed".equalsIgnoreCase(status);
                if (done) recording.compactedThisTurn = true;
                Map<String, Object> frame = new java.util.LinkedHashMap<>();
                frame.put("phase", "end");
                frame.put("status", done ? "completed" : (status.isBlank() ? "failed" : status));
                channel.emit(RunEventType.COMPACT_STATE, com.alibaba.fastjson2.JSON.toJSONString(frame));
    }

}
