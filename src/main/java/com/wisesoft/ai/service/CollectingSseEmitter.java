package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 收集型 SSE 通道：把问答流水线发出的事件收进内存，而不是写给浏览器。
 * <p>
 * 用途：**没有客户端**却需要跑一次完整问答的场景（当前是定时执行智能体）。复用的是同一条
 * {@link RagService#chat} 流水线，因此检索、引用来源、配图、产物交付、深度思考、工具调用、
 * 子代理编排的行为与网页问答**完全一致**——而不是另写一条"简化版问答"再慢慢补齐差异。
 * <p>
 * 实现：继承 {@link SseEmitter} 并重写 {@code send(SseEventBuilder)}，把事件帧收进列表且
 * **不调用 super**（因此不需要已初始化的 HTTP response）。调用方只需要：
 * <ul>
 *   <li>{@link #awaitDone(long)} 等到 {@code done} / {@code error} 事件或超时；</li>
 *   <li>{@link #answer()} 取拼接好的回答正文；{@link #lastError()} 取错误文案。</li>
 * </ul>
 * ⚠️ 这里解析的是**自家协议**（事件名 + data 里的 JSON）：事件名见 RagService 的
 * {@code sendSseEvent} 调用（正文 {@code token}、完成 {@code done}、错误 {@code error}），
 * 改名要同步本类与前端 {@code api.js} 的事件分发。
 * <p>
 * 另外：本类不参与"客户端断开"语义——{@code ACTIVE_SSE} 的断开标记永远不会被打上，
 * 所以定时任务不会被误判为断开。用完后由 {@link RagService#forgetSseChannel} 主动清登记。
 *
 * @author yuanke
 */
@Slf4j
public class CollectingSseEmitter extends SseEmitter {

    /** 原始事件帧（保留 SSE 文本，便于排查"定时任务为何没输出"） */
    private final List<String> frames = Collections.synchronizedList(new ArrayList<>());
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    /** 等到 done 或 error 时归零 */
    private final CountDownLatch settled = new CountDownLatch(1);
    private volatile String lastError;
    private volatile String donePayload;
    private volatile boolean finished;

    public CollectingSseEmitter() {
        super(0L);   // 不设 SSE 超时：节奏由调用方的 awaitDone 控制
    }

    @Override
    public void send(SseEventBuilder builder) {
        StringBuilder raw = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            Object data = part.getData();
            if (data instanceof String s) raw.append(s);
        }
        String text = raw.toString();
        frames.add(text);
        consume(text);
    }

    /** 解析一帧：event 名 + data 里的 {type,content,...} */
    private void consume(String raw) {
        try {
            int at = raw.indexOf("data:");
            if (at < 0) return;
            JSONObject o = JSON.parseObject(raw.substring(at + 5).trim());
            if (o == null) return;
            String type = o.getString("type");
            if (type == null) return;
            switch (type) {
                case "token" -> answer.append(nullToEmpty(o.getString("content")));
                case "thinking" -> thinking.append(nullToEmpty(o.getString("content")));
                case "error" -> {
                    lastError = nullToEmpty(o.getString("content"));
                    settle();
                }
                case "done" -> {
                    Object c = o.get("content");
                    donePayload = c == null ? null : JSON.toJSONString(c);
                    settle();
                }
                default -> {
                    // stage / retrieved / image / artifact / tool_status / subagent / warn 等过程事件：
                    // 对"跑完一轮并落会话"没有额外用途（产物已落库、来源已写在消息里），只留在 frames
                }
            }
        } catch (Exception e) {
            // 单帧解析失败不影响整轮执行（帧本身已保留）
            log.debug("[SCHEDULED] 事件帧解析失败: {}", e.getMessage());
        }
    }

    private void settle() {
        if (!finished) {
            finished = true;
            settled.countDown();
        }
    }

    /** 等待本轮结束（done / error）；返回 false = 超时（调用方按"可能仍在跑"处理） */
    public boolean awaitDone(long timeoutMs) {
        try {
            return settled.await(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 拼接好的回答正文（token 事件累积） */
    public String answer() {
        return answer.toString();
    }

    /** 思维链（thinking 事件累积；定时任务一般不用，留作排查） */
    public String thinking() {
        return thinking.toString();
    }

    /** error 事件的文案；无错为 null */
    public String lastError() {
        return lastError;
    }

    /** done 事件里的 content（含会话/消息/引用等汇总 JSON 文本）；未收到为 null */
    public String donePayload() {
        return donePayload;
    }

    /** 事件帧原文（排查用） */
    public List<String> frames() {
        return List.copyOf(frames);
    }

    /** 已完成（收到 done 或 error） */
    public boolean isSettled() {
        return finished;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
