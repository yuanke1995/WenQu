package com.wenqu.ai.agent.think;

import com.alibaba.fastjson2.JSON;

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.SseClientGoneException;
import com.wenqu.ai.agent.chat.TurnState;
import com.wenqu.ai.agent.context.HistoryCompactionService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>深度思考</b>这一族（P6 刀 4，自 {@code ChatService} 抽出）：生成前先跑一次同步消费的思考流，
 * 从思考文本里提取「精化 query + 子问题」这份检索计划，再把计划交回调用方去多路检索。
 *
 * <p>它只做思考这一段：检索、编排、生成都还在问答侧（阶段3 由调用方执行）。
 * 之所以能做成一个 bean 而不是回传函数——这一族<b>不回头调问答入口</b>（P6 拦路点 2 的那条判据）。
 *
 * <p>三条实测换来的口径：
 * <ul>
 *   <li>思考模式下不发 temperature：主流网关在 thinking 开启时静默忽略采样参数；</li>
 *   <li>qwen 系思考模式下设 max_tokens 会空输出，所以默认不设，只有显式配置 &gt;0 才设；</li>
 *   <li>思考长度护栏中断的是流，不中断这一轮——已收集内容照样走计划提取，能提出计划就还是 ok。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class DeepThinkService {

    private final ConfigService configService;
    private final ChatClient chatClient;
    private final ModelRegistryService modelRegistryService;
    private final SessionService sessionService;
    private final ChatRunRuntime runtime;
    private final HistoryCompactionService historyCompaction;

    public DeepThinkService(ConfigService configService,
                           ChatClient chatClient,
                           ModelRegistryService modelRegistryService,
                           SessionService sessionService,
                           ChatRunRuntime runtime,
                           HistoryCompactionService historyCompaction) {
        this.configService = configService;
        this.chatClient = chatClient;
        this.modelRegistryService = modelRegistryService;
        this.sessionService = sessionService;
        this.runtime = runtime;
        this.historyCompaction = historyCompaction;
    }

    // ==================== 深度思考（生产级） ====================

    /** 深度思考结果：ok=是否成功（失败降级时 refinedQuery 无意义），thinking=剥离 <search> 后的展示文本，reason=失败原因（fail-loud） */
    public record DeepThinkResult(boolean ok, String thinking, String refinedQuery, List<String> subQueries, String reason) {
    }

    /**
     * 深度思考三阶段：
     * 阶段1 流式思考（SSE thinking 增量；thinkingMode=model 从 reasoning_content 提取，prompt 从 content 提取）
     * 阶段2 提取 <search> 检索计划（精化 query + 子问题）
     * 阶段3 由调用方执行多路检索（本方法只返回计划）
     * 失败/超时/未提取到计划 → 返回 ok=false + 已收集思考增量（调用方用思考词元增强降级检索）
     * 思考长度护栏（maxThinkingChars）：超限中断思考流但保留已收集内容继续走计划提取，不整段丢弃
     */
    public DeepThinkResult run(String sessionId, String question, String imgNote,
                                            String attachmentText,
                                            SseEmitter emitter, String resolvedModel, String reasoningLevel) {
        String thinkingMode = configService.get("deepReasoning.thinkingMode");
        boolean enableThinking = configService.getBoolean("deepReasoning.enableThinking");
        boolean multiRetrieval = configService.getBoolean("deepReasoning.multiRetrieval");
        int timeoutMillis = configService.getInt("deepReasoning.timeoutMillis");
        int maxThinkingTokens = configService.getInt("deepReasoning.maxThinkingTokens");
        int maxThinkingChars = configService.getInt("deepReasoning.maxThinkingChars", 3000);

        // 思考 system = 思考引导 prompt + 对话历史（复用 buildHistoryText 裁剪）
        StringBuilder system = new StringBuilder(configService.get("deepReasoning.prompt"));
        String historyText = historyCompaction.buildHistoryText(sessionService.getRecentHistory(sessionId, 2));
        if (!historyText.isEmpty()) {
            system.append("\n\n对话历史：\n").append(historyText);
        }
        // user = 原始问题 + 图片说明（仅"模型不支持读图"时有值）
        StringBuilder user = new StringBuilder(question);
        if (imgNote != null && !imgNote.isBlank()) {
            user.append(imgNote);
        }
        // 附件内容随思考上下文（附件是回答素材，思考阶段就应看到）
        if (attachmentText != null && !attachmentText.isBlank()) {
            user.append("\n\n用户上传了附件，内容如下（仅用于辅助思考）：\n").append(attachmentText);
        }

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(resolvedModel);
        // 思考模式下不发 temperature：主流网关在 thinking 开启时忽略 temperature/top_p 等采样参数
        // （DeepSeek/GLM/豆包/Claude/Qwen 官方文档均明确）——不报错也不生效，属静默失效。
        // 仅 thinkingMode=model（网关原生思考）适用；prompt 模式是提示词引导、思考走 content，
        // 采样参数照常生效。
        if (!("model".equals(thinkingMode) && enableThinking)) {
            optionsBuilder.temperature(configService.getDouble("chat.temperature"));
        }
        // qwen 思考模式 max_tokens 会导致空输出：默认不设，仅显式配置 >0 时才设
        if (maxThinkingTokens > 0) {
            optionsBuilder.maxTokens(maxThinkingTokens);
        }
        // thinkingMode=model：extraBody 透传 enable_thinking，思考从 reasoning_content 提取
        if ("model".equals(thinkingMode) && enableThinking) {
            optionsBuilder.extraBody(Map.of("enable_thinking", true));
        }
        // 思考强度档位（本轮请求级，null 时回落模型登记默认档位；thinkingMode=model 且开着思考时才下发）：
        // 按厂商方言映射成 reasoning_effort / thinking.budget_tokens / thinking_budget。
        // 与上面 enable_thinking 合并（extraBody 单次设置，后者覆盖前者——故在此合并成一个 map）。
        if ("model".equals(thinkingMode) && enableThinking) {
            String thinkLevel = reasoningLevel != null
                    ? reasoningLevel : modelRegistryService.defaultReasoningLevelOf(resolvedModel);
            if (thinkLevel != null) {
                // reasoning_effort 走原生 setter（extraBody 会导致同名字段序列化两次 → 网关 422）
                String effort = modelRegistryService.reasoningEffortValue(resolvedModel, thinkLevel, true);
                if (effort != null) {
                    optionsBuilder.reasoningEffort(effort);
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("enable_thinking", true);
                body.putAll(modelRegistryService.reasoningExtraBody(resolvedModel, thinkLevel, true));
                optionsBuilder.extraBody(body);
                log.info("[REASONING] 深度思考强度已下发: model={} level={} effort={} extra={}",
                        resolvedModel, thinkLevel, effort, body.keySet());
            }
        }

        StringBuilder thinking = new StringBuilder();
        // 用量归属：思考流是独立订阅，doFinally 落在网关 I/O 线程上，ThreadLocal 不可见——
        // 入口线程先取 uid，用 Reactor Context 带上（护栏截断/断连取消时，中断估算同样按此归属）
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        try {
            chatClient.prompt()
                    .system(system.toString())
                    .user(user.toString())
                    .options(optionsBuilder.build())
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        String delta = extractThinkingDelta(resp, thinkingMode);
                        if (delta != null && !delta.isBlank()) {
                            // 思考长度护栏：超上限即中断思考流（保留已收集部分），避免刷爆上下文/token
                            if (maxThinkingChars > 0 && thinking.length() + delta.length() > maxThinkingChars) {
                                throw new ThinkingCappedException();
                            }
                            thinking.append(delta);
                            // 客户端断开或用户叫停：抛异常中止同步消费（blockLast），省余下思考输出，
                            // 调用方检查点兜底不再检索。停止要先于发送判——通道 complete 后发送未必立刻失败
                            // （容器缓冲区还在），那样会白烧掉一截思考 token
                            if (runtime.turnStopRequested(sessionId)
                                    || !runtime.sendSseEvent(emitter, "thinking", delta, sessionId)) {
                                throw new SseClientGoneException();
                            }
                        }
                    })
                    .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                            com.wenqu.ai.util.UsageAttr.of(
                                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                    sessionId, null, "think")))
                    .blockLast(Duration.ofMillis(Math.max(1000, timeoutMillis)));

            return finishDeepThinking(thinking.toString(), question, sessionId, emitter, true);
        } catch (SseClientGoneException e) {
            String partialThinking = stripSearchBlock(thinking.toString(), "search");
            boolean stoppedByUser = runtime.turnStopRequested(sessionId);
            log.info("[SSE] {}，深度思考终止: session={} 已思考={}字",
                    stoppedByUser ? "用户停止本轮" : "客户端断开", sessionId, partialThinking.length());
            if (stoppedByUser) {
                persistThinkingOnlyOnStop(sessionId, partialThinking, runtime.turnIdOf(sessionId));
            }
            return new DeepThinkResult(false, partialThinking, question, List.of(), "disconnected");
        } catch (ThinkingCappedException e) {
            // 思考长度达上限：用已收集内容继续（可能提取到计划则 ok，否则由调用方用思考词元增强降级）
            log.info("[DEEP-THINK] 思考长度达上限截断（{} 字），用已收集内容继续", thinking.length());
            return finishDeepThinking(thinking.toString(), question, sessionId, emitter, false);
        } catch (Exception e) {
            // 思考流异常/超时：若已收集内容含检索计划仍可用，否则降级（调用方用思考词元增强）
            log.warn("[FAIL-LOUD] 深度思考失败/超时，降级检索: {}", e.getMessage());
            return finishDeepThinking(thinking.toString(), question, sessionId, emitter, false);
        }
    }

    /**
     * 思考收尾公共逻辑：提取检索计划、剥离 <search> 块、下发 thinking_done。
     * ok = 流正常完成，或虽中断但已收集内容中提取到了有效检索计划（refinedQuery 非原问题或子问题非空）——
     * 这样长度截断/部分异常时思考内容不白费，能继续多路检索。
     */
    private DeepThinkResult finishDeepThinking(String rawThinking, String question, String sessionId, SseEmitter emitter, boolean streamOk) {
        SearchPlan plan = extractSearchPlan(rawThinking, question);
        String display = stripSearchBlock(rawThinking, plan.searchTag());
        boolean hasPlan = !plan.subQueries().isEmpty()
                || (plan.refinedQuery() != null && !plan.refinedQuery().equals(question));
        boolean ok = streamOk || hasPlan;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", ok ? "ok" : "degraded");
        done.put("thinking", display);
        runtime.sendSseEvent(emitter, "thinking_done", JSON.toJSONString(done), sessionId);
        log.info("[DEEP-THINK] 思考结束 {} 字, ok={}, refined={}, subs={}", display.length(), ok, plan.refinedQuery(), plan.subQueries());
        return new DeepThinkResult(ok, display, plan.refinedQuery(), plan.subQueries(), null);
    }

    /** 思考流长度达上限的中断信号（内部异常，不走 fail-loud） */
    private static final class ThinkingCappedException extends RuntimeException {
    }

    /** 检索计划：精化 query + 子问题列表 */
    private record SearchPlan(String refinedQuery, List<String> subQueries, String searchTag) {
    }

    /** 从思考文本提取 <search>精化query|子问题1|子问题2</search>；未命中返回原始问题 */
    private SearchPlan extractSearchPlan(String thinking, String fallback) {
        String tag = configService.get("deepReasoning.searchTag");
        if (tag == null || tag.isBlank()) tag = "search";
        String escapedTag = Pattern.quote(tag);
        Pattern p = Pattern.compile("<" + escapedTag + ">([\\s\\S]*?)</" + escapedTag + ">");
        Matcher m = p.matcher(thinking == null ? "" : thinking);
        String inner = null;
        if (m.find()) {
            inner = m.group(1);
        } else {
            // 未闭合兜底：思考被长度/异常截断时，检索计划恰好在末尾（<search> 有开头没结尾）——
            // 按闭合匹配会整块丢失，多路检索静默退化为单路。取最后一个开标签到文本末尾。
            Matcher om = Pattern.compile("<" + escapedTag + ">([\\s\\S]*)$").matcher(thinking == null ? "" : thinking);
            if (om.find()) {
                inner = om.group(1);
                log.info("[DEEP-THINK] 检索计划标签未闭合（思考截断），按未闭合提取");
            }
        }
        if (inner != null) {
            String[] parts = inner.split("\\|");
            // 模型不守"|"格式的常见变体：换行/分号分隔的列表——只有一段但含这些分隔符时再切一次
            if (parts.length == 1 && (inner.contains("\n") || inner.contains("；") || inner.contains(";"))) {
                parts = inner.split("\\r?\\n|；|;");
            }
            List<String> list = Arrays.stream(parts)
                    .map(s -> s.replaceFirst("^\\s*(?:\\d+[.、)）]|[-*•])\\s*", "").trim()) // 清理 "1. " / "- " 编号前缀
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!list.isEmpty()) {
                int maxSub = configService.getInt("deepReasoning.maxSubQueries");
                List<String> subs = list.size() > 1
                        ? list.subList(1, Math.min(list.size(), 1 + Math.max(0, maxSub)))
                        : List.of();
                return new SearchPlan(list.get(0), subs, tag);
            }
        }
        return new SearchPlan(fallback, List.of(), tag);
    }

    /** 剥离 <search>...</search> 块（思考展示/持久化不含检索计划） */
    private String stripSearchBlock(String thinking, String tag) {
        if (thinking == null || thinking.isBlank()) return "";
        String escapedTag = Pattern.quote(tag == null || tag.isBlank() ? "search" : tag);
        return thinking.replaceAll("<" + escapedTag + ">[\\s\\S]*?</" + escapedTag + ">", "").trim();
    }

    /** 按 thinkingMode 从 ChatResponse 提取思考增量：model=metadata.reasoningContent（回退 text）；prompt=text */
    private String extractThinkingDelta(ChatResponse resp, String thinkingMode) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) return null;
        Object output = resp.getResult().getOutput();
        if (!(output instanceof AssistantMessage am)) return null;
        if ("model".equals(thinkingMode)) {
            Object rc = am.getMetadata().get("reasoningContent");
            if (rc != null && !String.valueOf(rc).isBlank()) {
                return String.valueOf(rc);
            }
        }
        // prompt 模式或 metadata 无思考内容：回退 content 文本
        String text = am.getText();
        return (text == null || text.isBlank()) ? null : text;
    }

    /** 思考留痕下限（字）：深度思考动辄上千字，少于这个数说明刚起步就被停，不值得占一条回答 */
    private static final int STOP_THINKING_TRACE_MIN_CHARS = 120;

    /**
     * 思考阶段被用户叫停的留痕：这一轮既没有正文也没有工具记录，什么都不落的话历史里只剩一句提问，
     * 用户读到的是「我发了个问题，没回答」而不是「我让它停了」。落一条只带思考与停止标记的消息。
     * <p>为什么单开一条：{@code ChatService#persistPartialAnswer} 挂在流状态上，而 {@code TurnState}
     * 要到生成阶段才构造——思考阶段没有那个状态可借。
     *
     * @param turnId 本轮身份（调用点从把手取，见 {@code ChatRunRuntime#turnIdOf}）：带上它这条留痕才回得去那一轮的账本
     */
    private void persistThinkingOnlyOnStop(String sessionId, String thinking, String turnId) {
        // 留痕下限：思考刚起步就被停（几个字）时落库，得到的是一条读不出任何东西的气泡——
        // 那种情况交给问题上的「本轮已停止」说明，不造空回答
        if (thinking == null || thinking.strip().length() < STOP_THINKING_TRACE_MIN_CHARS) {
            log.info("[SSE] 停止时思考不足 {} 字，不留痕（由问题上的终止标记交代）: session={}",
                    STOP_THINKING_TRACE_MIN_CHARS, sessionId);
            return;
        }
        try {
            sessionService.appendMessageWithTurn(sessionId, "assistant", TurnState.STOP_SUFFIX.trim(), thinking, turnId);
        } catch (Exception e) {
            log.warn("[SSE] 停止留痕落库失败（不影响已停止的本轮）: session={} {}", sessionId, e.getMessage());
        }
    }

}
