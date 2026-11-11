package com.wenqu.ai.service.webhook;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.TurnSource;
import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.WebhookDeliveryMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.WebhookDelivery;
import com.wenqu.ai.service.AgentShareService;
import com.wenqu.ai.service.ConfigCryptoService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.NotificationService;
import com.wenqu.ai.util.OutboundWebhook;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体轮终 webhook 出站（对外能力第二批）：智能体配置了 {@code callback_url} 时，
 * 每轮结束把签名结果 POST 给宿主系统——「跑完了主动送出去」，支撑无人值守嵌入。
 * <p>
 * <b>与工作流第 1 期的终态回调是两条线</b>：那边是运行级 fire-and-forget（失败仅告警），
 * 这边是<b>至少一次</b>——每次出站先落 {@code c_ai_webhook_delivery} 再发，失败进退避重试
 * （60s/5min/30min/2h 共 4 次重试），耗尽置 exhausted 并给智能体主人发站内通知。接收方
 * 按 payload 里的 turnId/messageId 幂等去重。
 * <p>
 * <b>挂点与过滤</b>：引擎 finish 的 done 发射之后 + failLoud（开局即败的最小载荷）两处调用。
 * 过滤与 {@code TurnNotifyService} 同一口径但独立实现（取数面不同，不能借它的签名）：
 * 只发用户发起的顶层轮（{@code TurnSource.USER}；定时/工作流子轮/咨询子轮/免登录访客/字面量兜底身份一律不发），
 * 游客轮另有显式闸。
 * <p>
 * <b>异步纪律</b>（对齐 TurnNotifyService）：一切异常只记 WARN 不上抛，投递与重试都不许
 * 反过来影响问答收口；异步线程无登录态，归属一律按会话行回查，不读 RequestUser。
 * <p>
 * 线程池拒投有显式出路（雷区清单第 6 条）：{@code ThreadPoolManager.execute} 返回 false
 * 时投递行保持 pending、next_retry_at=now，扫描任务下一拍补发——不丢数据。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWebhookService {

    public static final String EVENT_TURN_FINISHED = "agent.turn.finished";

    /** 重试退避（毫秒）：首发失败后按序取用，用尽即 exhausted */
    static final long[] RETRY_BACKOFF_MS = {60_000L, 300_000L, 1_800_000L, 7_200_000L};
    /** 给模型的正文进 payload 的截断（64KB——完整正文落库在 c_ai_message，宿主要长文自己拉） */
    private static final int CONTENT_MAX_CHARS = 64_000;

    private final AgentMapper agentMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final ConfigCryptoService cryptoService;
    private final ConfigService configService;
    private final NotificationService notifications;

    // ==================== 轮终一拍（引擎 finish / failLoud 调用） ====================

    /**
     * 一轮结束的出站入口。参数是<b>调用线程上的快照</b>：异步侧只拿标量与已拷贝的集合，
     * 不读任何活对象（cmd 按字段展开，payload 在异步侧新构造）。
     *
     * @param status  completed / stopped / failed（stopped=用户主动停，宿主同样该知道）
     * @param failure 失败原因（给宿主排障的原文；completed/stopped 为空）
     */
    public void onTurnFinished(ChatCommand cmd, String turnId, String status, String failure,
                               String finalContent, String messageId, Map<String, Object> tokens,
                               List<Map<String, Object>> artifacts, long elapsedMs) {
        try {
            if (cmd == null || !enabled()) return;
            // 发起方过滤与 TurnNotifyService.turnUid 同口径：null/空=老调用方没登记（按用户轮放行），
            // 只有显式的非 user 来源（定时/工作流子轮/咨询子轮）不发
            String src = cmd.getSource();
            if (src != null && !src.isBlank() && !TurnSource.USER.equals(src)) return;
            if (cmd.getParentSessionId() != null && !cmd.getParentSessionId().isBlank()) return;
            if (cmd.isGuestMode()) return;   // 免登录分享轮不发（与工具游客口径一致）
            String uid = cmd.getUserId();
            if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid) || "guest".equals(uid)) return;
            if (uid.startsWith(AgentShareService.VISITOR_UID_PREFIX)) return;
            if (cmd.getAgentId() == null || cmd.getAgentId().isBlank()) return;
            final String agentId = cmd.getAgentId();
            final String sessionId = cmd.getSessionId();
            final String apiKeyId = cmd.getApiKeyId();   // 接入方分流用：同一 URL 下按 Key 区分调用来源
            final String uidF = uid;
            final String turnIdF = turnId;
            final String statusF = status;
            final String failureF = failure;
            final String contentF = finalContent == null ? "" : finalContent;
            final String messageIdF = messageId;
            final Map<String, Object> tokensF = tokens == null ? Map.of() : tokens;
            final List<Map<String, Object>> artifactsF = artifacts == null ? List.of() : artifacts;
            final long elapsedF = elapsedMs;
            boolean submitted = com.wenqu.ai.thread.ThreadPoolManager.execute(() -> {
                try {
                    deliver(uidF, agentId, apiKeyId, sessionId, turnIdF, statusF, failureF, contentF,
                            messageIdF, tokensF, artifactsF, elapsedF);
                } catch (Exception e) {
                    log.warn("[WEBHOOK] 轮终投递失败（旁路，不影响问答）: session={} : {}",
                            sessionId, e.getMessage());
                }
            });
            if (!submitted) {
                // 拒投：显式出路——把这一轮记成到期 pending，扫描任务下一拍补发（不丢）
                log.warn("[WEBHOOK] 线程池拒投，转扫描补发: session={}", sessionId);
                scheduleImmediateRetry(uidF, agentId, apiKeyId, sessionId, turnIdF, statusF, failureF,
                        contentF, messageIdF, tokensF, artifactsF, elapsedF);
            }
        } catch (Exception e) {
            log.warn("[WEBHOOK] 轮终出站入口异常（旁路）: {}", e.getMessage());
        }
    }

    // ==================== 投递与重试 ====================

    /** 落行 + 首发（异步线程内执行） */
    private void deliver(String uid, String agentId, String apiKeyId, String sessionId, String turnId, String status,
                         String failure, String content, String messageId,
                         Map<String, Object> tokens, List<Map<String, Object>> artifacts, long elapsedMs) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getCallbackUrl() == null || agent.getCallbackUrl().isBlank()) return;
        String url = agent.getCallbackUrl().trim();
        String secret = decryptSecret(agent.getCallbackSecret());
        String payload = JSON.toJSONString(buildPayload(turnId, sessionId, agentId, apiKeyId, agent.getName(),
                status, failure, content, messageId, tokens, artifacts, elapsedMs));
        WebhookDelivery row = new WebhookDelivery();
        row.setId(UUID.randomUUID().toString());
        row.setUid(uid);
        row.setAgentId(agentId);
        row.setTurnId(turnId);
        row.setSessionId(sessionId);
        row.setEvent(EVENT_TURN_FINISHED);
        row.setUrl(url);
        row.setPayload(payload);
        row.setStatus(WebhookDelivery.STATUS_PENDING);
        row.setAttempts(0);
        row.setCreateTime(LocalDateTime.now());
        deliveryMapper.insert(row);
        attempt(row, secret);
    }

    /** 单次尝试并按结果收口（成功置 success；失败排下一次重试或置 exhausted+通知） */
    private void attempt(WebhookDelivery row, String secret) {
        OutboundWebhook.Receipt r = OutboundWebhook.post(row.getUrl(), row.getEvent(),
                Map.of("X-Wenqu-Turn-Id", row.getTurnId() == null ? "" : row.getTurnId()),
                secret, row.getPayload());
        int attempts = nz(row.getAttempts()) + 1;
        LambdaUpdateWrapper<WebhookDelivery> uw = new LambdaUpdateWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getId, row.getId())
                .set(WebhookDelivery::getAttempts, attempts)
                .set(WebhookDelivery::getLastStatus, r.status())
                .set(WebhookDelivery::getLastError, r.error() == null
                        ? (r.ok() ? null : "HTTP " + r.status())
                        : clip(r.error(), 490));
        if (r.ok()) {
            uw.set(WebhookDelivery::getStatus, WebhookDelivery.STATUS_SUCCESS)
                    .set(WebhookDelivery::getFinishTime, LocalDateTime.now());
            log.info("[WEBHOOK] 送达: turn={} url={} 第 {} 次", row.getTurnId(), row.getUrl(), attempts);
        } else if (attempts > retryMax()) {
            uw.set(WebhookDelivery::getStatus, WebhookDelivery.STATUS_EXHAUSTED)
                    .set(WebhookDelivery::getFinishTime, LocalDateTime.now());
            notifyExhausted(row, attempts, r);
            log.warn("[WEBHOOK] 重试耗尽: turn={} url={} 共 {} 次", row.getTurnId(), row.getUrl(), attempts);
        } else {
            long backoff = RETRY_BACKOFF_MS[Math.min(attempts - 1, RETRY_BACKOFF_MS.length - 1)];
            uw.set(WebhookDelivery::getStatus, WebhookDelivery.STATUS_PENDING)
                    .set(WebhookDelivery::getNextRetryAt, LocalDateTime.now().plusNanos(backoff * 1_000_000));
            log.warn("[WEBHOOK] 投递失败，{}s 后重试（第 {}/{} 次）: turn={} : {}",
                    backoff / 1000, attempts, RETRY_BACKOFF_MS.length + 1, row.getTurnId(),
                    r.error() != null ? r.error() : "HTTP " + r.status());
        }
        deliveryMapper.update(null, uw);
    }

    /** 扫描补发（ScheduleCenter 调）：到期 pending 行逐条重试；返回本轮处理量（空跑免记日志） */
    public int sweepRetry() {
        if (!enabled()) {
            return exhaustAllPending("平台总闸已关闭");
        }
        List<WebhookDelivery> due = deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getStatus, WebhookDelivery.STATUS_PENDING)
                .and(w -> w.isNull(WebhookDelivery::getNextRetryAt)
                        .or().le(WebhookDelivery::getNextRetryAt, LocalDateTime.now()))
                .last("LIMIT 20"));
        int n = 0;
        for (WebhookDelivery row : due) {
            try {
                Agent agent = row.getAgentId() == null ? null : agentMapper.selectById(row.getAgentId());
                // 智能体已删或回调已清：投递行就地 exhausted（配置都没了，重试没有意义）
                if (agent == null || agent.getCallbackUrl() == null || agent.getCallbackUrl().isBlank()) {
                    deliveryMapper.update(null, new LambdaUpdateWrapper<WebhookDelivery>()
                            .eq(WebhookDelivery::getId, row.getId())
                            .set(WebhookDelivery::getStatus, WebhookDelivery.STATUS_EXHAUSTED)
                            .set(WebhookDelivery::getLastError, "智能体已删除或回调已清空")
                            .set(WebhookDelivery::getFinishTime, LocalDateTime.now()));
                    continue;
                }
                attempt(row, decryptSecret(agent.getCallbackSecret()));
                n++;
            } catch (Exception e) {
                log.warn("[WEBHOOK] 重试单笔失败（不影响其余）: id={} : {}", row.getId(), e.getMessage());
            }
        }
        return n;
    }

    /** 总闸关闭时把存量 pending 行就地耗尽：关掉出站就要真的安静，不留永远排着的重试 */
    private int exhaustAllPending(String why) {
        try {
            List<WebhookDelivery> pending = deliveryMapper.selectList(
                    new LambdaQueryWrapper<WebhookDelivery>()
                            .eq(WebhookDelivery::getStatus, WebhookDelivery.STATUS_PENDING)
                            .last("LIMIT 200"));
            for (WebhookDelivery row : pending) {
                deliveryMapper.update(null, new LambdaUpdateWrapper<WebhookDelivery>()
                        .eq(WebhookDelivery::getId, row.getId())
                        .set(WebhookDelivery::getStatus, WebhookDelivery.STATUS_EXHAUSTED)
                        .set(WebhookDelivery::getLastError, why)
                        .set(WebhookDelivery::getFinishTime, LocalDateTime.now()));
            }
            return pending.size();
        } catch (Exception e) {
            log.warn("[WEBHOOK] 总闸关闭清理 pending 行失败: {}", e.getMessage());
            return 0;
        }
    }

    // ==================== 内部 ====================

    /** 线程池拒投的显式出路：直接落一条到期 pending 行（首发都没发出去，attempts=0） */
    private void scheduleImmediateRetry(String uid, String agentId, String apiKeyId, String sessionId, String turnId,
                                        String status, String failure, String content, String messageId,
                                        Map<String, Object> tokens, List<Map<String, Object>> artifacts,
                                        long elapsedMs) {
        try {
            Agent agent = agentMapper.selectById(agentId);
            if (agent == null || agent.getCallbackUrl() == null || agent.getCallbackUrl().isBlank()) return;
            String secret = decryptSecret(agent.getCallbackSecret());
            String payload = JSON.toJSONString(buildPayload(turnId, sessionId, agentId, apiKeyId, agent.getName(),
                    status, failure, content, messageId, tokens, artifacts, elapsedMs));
            WebhookDelivery row = new WebhookDelivery();
            row.setId(UUID.randomUUID().toString());
            row.setUid(uid);
            row.setAgentId(agentId);
            row.setTurnId(turnId);
            row.setSessionId(sessionId);
            row.setEvent(EVENT_TURN_FINISHED);
            row.setUrl(agent.getCallbackUrl().trim());
            row.setPayload(payload);
            row.setStatus(WebhookDelivery.STATUS_PENDING);
            row.setAttempts(0);
            row.setNextRetryAt(LocalDateTime.now());
            row.setCreateTime(LocalDateTime.now());
            deliveryMapper.insert(row);
        } catch (Exception e) {
            log.warn("[WEBHOOK] 拒投兜底落行失败（尽力而为）: session={} : {}", sessionId, e.getMessage());
        }
    }

    /** 出站载荷（接收方契约：幂等键 turnId/messageId；apiKeyId=调用来源 Key（网页轮不带）；正文 ≤64KB） */
    private Map<String, Object> buildPayload(String turnId, String sessionId, String agentId, String apiKeyId,
                                             String agentName, String status, String failure,
                                             String content, String messageId,
                                             Map<String, Object> tokens, List<Map<String, Object>> artifacts,
                                             long elapsedMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", EVENT_TURN_FINISHED);
        payload.put("turnId", turnId);
        payload.put("sessionId", sessionId);
        payload.put("messageId", messageId);
        payload.put("agentId", agentId);
        payload.put("agentName", agentName == null ? "" : agentName);
        // 接入方分流：同一回调地址被多个外部系统共用时，按 Key 归属区分（网页端轮无 Key，省略）
        if (apiKeyId != null && !apiKeyId.isBlank()) payload.put("apiKeyId", apiKeyId);
        payload.put("status", status);
        if (failure != null && !failure.isBlank()) payload.put("failure", failure);
        payload.put("finalContent", content.length() > CONTENT_MAX_CHARS
                ? content.substring(0, CONTENT_MAX_CHARS) + "\n…（超长截断，完整正文请按 messageId 回查）"
                : content);
        payload.put("artifacts", artifacts);
        payload.put("tokens", tokens);
        payload.put("elapsedMs", elapsedMs);
        return payload;
    }

    private void notifyExhausted(WebhookDelivery row, int attempts, OutboundWebhook.Receipt r) {
        try {
            String why = r.error() != null ? clip(r.error(), 120) : "HTTP " + r.status();
            notifications.create(row.getUid(), Notification.TYPE_AGENT_WEBHOOK_FAILED,
                    "轮终回调一直送不出去",
                    "智能体回调 " + clip(row.getUrl(), 80) + " 重试 " + attempts + " 次仍失败（"
                            + why + "）；本轮结果未送达，请检查接收方服务",
                    "agent", row.getAgentId(),
                    "webhook:" + row.getId(), row.getTurnId());
        } catch (Exception e) {
            log.warn("[WEBHOOK] 耗尽通知发送失败: {}", e.getMessage());
        }
    }

    /** 签名密钥解密（null/空=不签名；解不开按无签名出站，与 HTTP 工具鉴权同一兜底口径） */
    private String decryptSecret(String stored) {
        if (stored == null || stored.isBlank()) return null;
        try {
            String plain = cryptoService.decrypt(stored);
            return plain == null || plain.isBlank() ? null : plain;
        } catch (Exception e) {
            log.warn("[WEBHOOK] 回调密钥解密失败，按无签名出站: {}", e.getMessage());
            return null;
        }
    }

    private boolean enabled() {
        return configService.getBoolean("agentWebhook.enabled", true);
    }

    /** 首发失败后的重试次数上限（agentWebhook.retryMax，0=失败即耗尽；负数按 0） */
    private int retryMax() {
        return Math.max(0, configService.getInt("agentWebhook.retryMax", RETRY_BACKOFF_MS.length));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
