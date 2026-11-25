package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.KeywordExtractor;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮的用量统计与检索状态行（从 AgentscopeChatEngine 抽出）。
 *
 * <p>只依赖一个外部对象（关键词抽取器），其余都是入参：用量口径、检索状态行的取舍、
 * 流式用量帧的节流都收在这里。核心原则写在 {@link #usageOf} 的注释里——出去的数
 * 每一个都是网关实测值，不本地估；占用报 context 而非 prompt（后者是账单口径会虚高）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnUsage {

    private final KeywordExtractor keywordExtractor;

    public int emitRetrieved(ChatCommand cmd, WenquSourcePool pool, RunChannel channel, int announced, AgentxTurnRecording recording) {
        int refs = pool.sources().size();
        if (refs == announced) return announced;
        // 无真实检索时 retrievedJson 返回 null：本轮尚未（也不必有）召回，跳过这一帧
        String json = retrievedJson(cmd, pool, recording);
        if (json != null) channel.emit(RunEventType.RETRIEVED, json);
        return refs;
    }

    /**
     * 状态行载荷（「搜索 N 个关键词，参考 M 段资料」）。
     * <p>抽成一处是为了<b>同一份也落库</b>：只随 done 下发的话，刷新后这一行整块消失——
     * 手写侧是把 {@code retrievedJson} 存进消息行的，两条运行时得给用户同一个观感。
     * <p>{@code terms} 优先取模型本轮实际发出的检索词（{@code search_knowledge} 的 {@code query} 入参，
     * 见 {@code AgentxTurnRecording#searchQueries}）——它比从用户原问句抽的关键词更贴近「模型真搜了什么」。
     * 模型确实检索过、但没捕获到短语（工具名/入参形状变了、或引用池已有片段）时，退回从问句抽主词元作近似。
     * <p><b>没有真实检索就不出状态行</b>：智能体未配知识库、或本轮没调检索工具时既无检索短语也无召回片段，
     * 返回 {@code null}——前端据此整行不渲染，避免把问句关键词当成「检索词」展示、误导用户以为搜了库。
     *
     * @return 状态行 JSON；无真实检索时返回 null
     */
    public String retrievedJson(ChatCommand cmd, WenquSourcePool pool, AgentxTurnRecording recording) {
        Set<String> actual = recording == null ? null : recording.searchQueries;
        int refs = pool.sources().size();
        // 既没发出检索短语、也没召回任何片段：视为本轮未检索知识库，不出状态行
        if ((actual == null || actual.isEmpty()) && refs == 0) return null;
        List<String> terms;
        if (actual != null && !actual.isEmpty()) {
            terms = new java.util.ArrayList<>(actual);
        } else {
            terms = keywordExtractor.extractMain(cmd.getQuestion());
        }
        return com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "keywords", terms.size(), "refs", refs, "terms", terms));
    }

    /**
     * 流式期的用量帧：本轮每落地一条新消息就重发一份 {@code usage}，让输入框工具栏的容量圆环
     * 在第一次模型回包后就点亮，而不是等整轮收尾才出现（收尾那一发离结束只剩一帧，等于没有）。
     * <p>与 {@link #finish} 里的终值同源同算法（都走 {@link #usageOf}），前端只把它挂在
     * {@code tokensPreview} 上：落库、累计、输出侧仍只认 done 的 tokens，这里不另立口径。
     * <p>按 transcript 条数变化触发，不按循环节拍：分类拆分要对全量上下文做 BPE 计数，
     * 200ms 一跳空转会把跟随线程烧在数 token 上。变化本身由 {@link AgentxTurnMirror} 从事件上认，
     * 因为「问它变了几条」要先做一次全量读——那比数 token 还贵。发不出去只是容量圆环晚一帧，不该带走这一轮的答案，
     * 所以异常只告警不上抛。
     */
    public void emitLiveUsage(AgentSession session, ChatCommand cmd, WenquSourcePool pool,
                               RunChannel channel, int historyBefore, long[] stamp, boolean transcriptChanged,
                               int contextWindow) {
        // 上下文没添新行就不必读全量日志去问「添了几条」：transcript() 走的是从 head 回走的整条 commit 链
        if (!transcriptChanged) return;
        // 墙钟节流（stamp[1]）：tool/result 会让 transcriptChanged 每步都真，大表轮 20 来步就是
        // 20 来次全量读，全折进轮内延迟。容量圆环 4 秒刷一次没有可感知损失。
        long now = System.currentTimeMillis();
        if (stamp[1] > 0 && now - stamp[1] < 4_000L) return;
        stamp[1] = now;
        int size = session.transcript().messages().size();
        if (size == stamp[0]) return;
        stamp[0] = size;
        try {
            Map<String, Object> tokens = usageOf(session, historyBefore, pool, contextWindow);
            // 还没有任何一次模型回包带 usage：此刻报不出占用，等下一跳（空表发出去只会让前端清空预览）
            if (tokens.isEmpty()) return;
            channel.emit(RunEvent.of(RunEventType.USAGE, com.alibaba.fastjson2.JSON.toJSONString(tokens), false));
        } catch (Exception e) {
            log.warn("[USAGE] 流式用量帧发不出来，容量圆环要等本轮收尾: session={}, {}",
                    cmd.getSessionId(), e.toString());
        }
    }

    /**
     * 本轮实测用量：只累计这一轮新增消息的 {@link io.agentscope.core.model.ChatUsage}。
     * <p><b>出去的每一个数都是网关回传的实测值，没有一个是本地估的。</b>要按类别拆窗口占用（系统提示词
     * 多少、工具声明多少）得有<b>这个模型自己的分词器</b>——问渠只持有 OpenAI 那套 BPE 词表，对不上
     * 词表的模型只能估；估出来再摊平到实测总数上，每一格看着严丝合缝、其实都不是实测。
     * 所以这里只报网关给得起的口径：输入、输出、缓存读取、缓存写入、思考。
     * <p>占用报 {@code context}（最终轮输入）而不是 {@code prompt}（各轮输入之和）：工具往返会把同一份
     * 上下文反复送入，几轮下来后者能到前者的两三倍，拿它算占用百分比会把容量条撑到虚高。
     * <p>上下文预算（{@code budget}）框架不给：窗口值（{@code modelContextWindow}）是「模型允许的
     * 上下文上限」，不是问渠「给这一轮留了多少」的预算，报上去会让右栏占用条按一个没人承诺过的数
     * 算百分比。前端对缺字段是「不显示该行」，不填比填个假预算诚实。
     */
    public Map<String, Object> usageOf(AgentSession session, int historyBefore,
                                        WenquSourcePool pool, int contextWindow) {
        List<Msg> messages = session.transcript().messages();
        int from = Math.min(Math.max(historyBefore, 0), messages.size());
        long prompt = 0;
        long output = 0;
        long cached = 0;
        long cacheWrite = 0;
        long reasoning = 0;
        int lastInput = 0;
        long lastOutput = 0;
        boolean any = false;
        for (int i = from; i < messages.size(); i++) {
            io.agentscope.core.model.ChatUsage usage = messages.get(i).getUsage();
            if (usage == null) continue;
            any = true;
            prompt += usage.getInputTokens();
            output += usage.getOutputTokens();
            cached += usage.getCachedTokens();
            cacheWrite += usage.getCacheCreationTokens();
            reasoning += usage.getReasoningTokens();
            // 最后一轮的 prompt≈本轮结束时的上下文占用（工具结果逐轮进上下文），与主链路口径同源
            lastInput = usage.getInputTokens();
            lastOutput = usage.getOutputTokens();
        }
        if (!any) return new java.util.LinkedHashMap<>();
        Map<String, Object> tokens = new java.util.LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("output", output);
        // 最终那一轮的输出：暗截断对账只看它（各轮工具调用的输出不参与触顶与丢失判定，与主链路同口径）
        tokens.put("finalOutput", lastOutput);
        tokens.put("total", prompt + output);
        // 缓存三项是网关分别回传的实测值：读取（cached）、写入（cacheWrite）、未命中即 prompt-cached
        tokens.put("cached", cached);
        tokens.put("cacheWrite", cacheWrite);
        tokens.put("reasoning", reasoning);
        tokens.put("context", lastInput);
        tokens.put("outputIsReal", true);
        tokens.put("hits", pool.sources().size());
        // 窗口取实例真正烤进去的那一份（与压缩阈值同源）：报别的数，容量条的百分比就和
        // 「什么时候会折上下文」各说各话
        if (contextWindow > 0) {
            tokens.put("window", contextWindow);
            tokens.put("windowSource", "framework");
        }
        return tokens;
    }
}
