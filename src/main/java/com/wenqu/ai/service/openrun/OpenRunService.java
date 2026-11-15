package com.wenqu.ai.service.openrun;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.OpenRunMapper;
import com.wenqu.ai.model.OpenRun;
import com.wenqu.ai.openapi.RequestIdFilter;
import com.wenqu.ai.service.ChatService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.TurnSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 对外开放运行的建轮 / 归属 / 状态 / 事件流。
 *
 * <p>建 run 不复制引擎：直接调 {@link ChatService#chat} 同一条流水线，出口用
 * {@link OpenRunEmitter}（收集型通道的子类）。{@code chat()} 内部自行提交流水线线程并立即返回，
 * 所以 POST 能马上把 runId 交回集成方，事件由投影层在流水线线程里逐帧写回放缓冲。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenRunService {

    private final OpenRunMapper mapper;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final AgentMapper agentMapper;
    private final RunEventReplay replay;

    /** 建一次运行；Idempotency-Key 命中时直接回同一 runId，不新起轮 */
    public Map<String, Object> create(String uid, String apiKeyId, String agentId, String question,
                                      String sessionId, String idempotencyKey, String requestId) {
        if (question == null || question.isBlank()) {
            throw new BizException(400, "question 必填");
        }
        String idem = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (idem != null) {
            OpenRun hit = mapper.selectOne(new LambdaQueryWrapper<OpenRun>()
                    .eq(OpenRun::getUid, uid).eq(OpenRun::getIdempotencyKey, idem).last("LIMIT 1"));
            if (hit != null) {
                Map<String, Object> out = detail(hit, requestId);
                out.put("idempotent", true);
                return out;
            }
        }
        if (agentId != null && !agentId.isBlank() && agentMapper.selectById(agentId) == null) {
            throw new BizException(400, "智能体不存在：" + agentId);
        }
        // 显式 sessionId 才持久（D2）；没传就按本次调用新建一个，归属恒为 Key 签发人
        String sid;
        if (sessionId == null || sessionId.isBlank()) {
            sid = sessionService.createSession(uid);
        } else {
            sid = sessionService.assertOwned(sessionId, uid).getId();
        }
        String runId = UUID.randomUUID().toString().replace("-", "");
        OpenRun row = new OpenRun();
        row.setRunId(runId);
        row.setSessionId(sid);
        row.setUid(uid);
        row.setAgentId(agentId == null || agentId.isBlank() ? null : agentId);
        row.setApiKeyId(apiKeyId);
        row.setIdempotencyKey(idem);
        row.setStatus("running");
        row.setIdempotentHit(0);
        row.setCreateTime(LocalDateTime.now());
        mapper.insert(row);

        // run.started 必须手写：ChatService 对收集型通道刻意不发 turn_start（没有请求体秒表基准）
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("runId", runId);
        started.put("sessionId", sid);
        started.put("agentId", row.getAgentId() == null ? "" : row.getAgentId());
        started.put("status", "running");
        replay.append(runId, 1L, "run.started", JSON.toJSONString(started));

        OpenRunEmitter emitter = new OpenRunEmitter(runId, requestId, replay, t -> finish(runId, t));
        ChatCommand cmd = ChatCommand.builder()
                .sessionId(sid).question(question).userId(uid)
                .agentId(row.getAgentId())
                .source(TurnSource.USER).sourceName("open-run")
                .build();
        chatService.chat(cmd, emitter);
        log.info("[OPEN-RUN] 建轮 uid={} run={} session={} agent={}", uid, runId, sid, row.getAgentId());
        return detail(row, requestId);
    }

    /** 归属校验：run 不存在与非本人同码，避免用 403/404 差异探测别人的 runId */
    public OpenRun mustOwn(String uid, String runId) {
        OpenRun row = runId == null ? null : mapper.selectById(runId);
        if (row == null || !row.getUid().equals(uid)) {
            throw new BizException(404, "运行不存在");
        }
        return row;
    }

    /** GET /runs/{id} 与 run.done 同口径 */
    public Map<String, Object> detail(OpenRun row, String requestId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", row.getRunId());
        out.put("status", row.getStatus());
        out.put("sessionId", row.getSessionId());
        out.put("agentId", row.getAgentId());
        out.put("createdAt", row.getCreateTime() == null ? null : row.getCreateTime().toString());
        out.put("finishedAt", row.getFinishTime() == null ? null : row.getFinishTime().toString());
        out.put("errorCode", row.getErrorCode());
        out.put("errorMessage", row.getErrorMessage());
        String terminal = replay.terminalData(row.getRunId());
        if (terminal != null) {
            try {
                out.put("output", JSON.parseObject(terminal, Map.class));
            } catch (Exception e) {
                out.put("output", terminal);
            }
        } else {
            out.put("answer", row.getAnswer());
        }
        out.put("requestId", requestId == null ? "" : requestId);
        return out;
    }

    private void finish(String runId, OpenRunEmitter.Terminal t) {
        mapper.update(null, new LambdaUpdateWrapper<OpenRun>()
                .eq(OpenRun::getRunId, runId)
                .set(OpenRun::getStatus, t.status())
                .set(OpenRun::getErrorCode, t.code())
                .set(OpenRun::getErrorMessage, t.message() == null ? null : trim(t.message(), 500))
                .set(OpenRun::getAnswer, t.answer() == null ? null : trim(t.answer(), 4000))
                .set(OpenRun::getFinishTime, LocalDateTime.now()));
        log.info("[OPEN-RUN] 终态 run={} status={}", runId, t.status());
    }

    /**
     * SSE 事件流：先按 Last-Event-ID 回放，再轮询回放缓冲推增量，直到终态。
     * <p>缓冲过期时如实回 {@code snapshot + run.error(resume_expired)}，由 SDK 降级重拉
     * GET /runs/{id}；不把过期伪装成一条新流从头开始。</p>
     */
    public void pump(String runId, long lastEventId, SseEmitter emitter) {
        long cursor = lastEventId;
        long deadline = System.currentTimeMillis() + RunEventReplay.RETENTION.toMillis();
        try {
            while (System.currentTimeMillis() < deadline) {
                List<Map<String, Object>> frames = replay.since(runId, cursor);
                if (frames == null) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("runId", runId);
                    err.put("status", "failed");
                    err.put("code", "resume_expired");
                    err.put("message", "事件回放缓冲已过期，请重拉 GET /runs/{id}");
                    err.put("request_id", RequestIdFilter.current() == null ? "" : RequestIdFilter.current());
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(Math.max(cursor, replay.latestId(runId))))
                            .name("run.error").data(JSON.toJSONString(err)));
                    break;
                }
                for (Map<String, Object> f : frames) {
                    long id = f.get("id") instanceof Number n ? n.longValue() : cursor;
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(id))
                            .name(String.valueOf(f.get("event")))
                            .data(String.valueOf(f.get("data"))));
                    cursor = id;
                }
                OpenRun row = mapper.selectById(runId);
                if (row != null && !"running".equals(row.getStatus()) && frames.isEmpty()) break;
                Thread.sleep(150L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 客户端断开是 SSE 常态；其余异常留痕，不静默
            log.debug("[OPEN-RUN] 事件流结束 run={} cursor={}: {}", runId, cursor, e.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // complete 在已断开的连接上会抛，收尾阶段无需再报
            }
        }
    }

    /** 回放首帧 + 启动后台推送；用 commonPool 而非问答池，长连接不占业务线程 */
    public void attach(String runId, long lastEventId, SseEmitter emitter) {
        List<Map<String, Object>> frames = replay.since(runId, lastEventId);
        long cursor = lastEventId;
        if (frames == null) {
            frames = List.of();
        } else {
            for (Map<String, Object> f : frames) {
                try {
                    long id = f.get("id") instanceof Number n ? n.longValue() : cursor;
                    emitter.send(SseEmitter.event().id(String.valueOf(id))
                            .name(String.valueOf(f.get("event"))).data(String.valueOf(f.get("data"))));
                    cursor = id;
                } catch (Exception e) {
                    log.warn("[OPEN-RUN] 回放首帧失败 run={}: {}", runId, e.getMessage());
                }
            }
        }
        OpenRun row = mapper.selectById(runId);
        if (row != null && !"running".equals(row.getStatus()) && frames.isEmpty()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            return;
        }
        final long at = cursor;
        CompletableFuture.runAsync(() -> pump(runId, at, emitter));
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
