package com.wenqu.ai.service;

import com.wenqu.ai.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 分享游客额度闸（费用面第一层）。
 *
 * <p>游客轮次的模型调用由发布者承担（{@code ShareController} 以发布者 uid 装载身份），
 * 已有成员/部门/租户配额兜住「一个人能烧多少」，但分享链接是对外匿名入口——
 * 没有分享级与访客级的日上限时，一条公开链接就能把发布者的额度刷到当天的顶。
 *
 * <p>本闸只做<b>轮次</b>上限（分享级 + 访客级双层），跨副本用 Redis 固定窗口（自然日）；
 * token/费用上限是第二层，需把 shareId 贯穿到用量台账后才能按分享聚合（见
 * {@code plan-p0-gaps.md} §4.2）。轮次上限已经能把「无限刷」压成「有界刷」，
 * 且不依赖 schema 变更。
 *
 * <p>Redis 不可用时 fail-closed：分享页是匿名入口，宁可暂时不可用也不能把额度暴露出去。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareBudgetService {

    private static final String PREFIX = "wenqu:share-budget:";
    private static final int DEFAULT_DAILY_TURNS = 0;
    private static final int DEFAULT_DAILY_TURNS_PER_VISITOR = 0;
    private static final long DEFAULT_DAILY_TOKENS = 0L;

    private final StringRedisTemplate redisTemplate;
    private final ConfigService configService;

    /**
     * 发起一轮游客对话前计数并校验：分享级与访客级任一超限即 429。
     *
     * @param shareId   分享记录 id（c_ai_agent_share.id）
     * @param visitorUid 访客 uid（visitor:xxx，客户端 visitorId 已加前缀）
     */
    public void checkGuestTurn(String shareId, String visitorUid) {
        if (shareId == null || shareId.isBlank()) return;
        int shareLimit = configService.getInt("share.guestDailyTurns", DEFAULT_DAILY_TURNS);
        int visitorLimit = configService.getInt("share.guestDailyTurnsPerVisitor", DEFAULT_DAILY_TURNS_PER_VISITOR);
        if (shareLimit <= 0 && visitorLimit <= 0) return; // 0/负=关闭该层（与限流桶同口径）

        String day = LocalDate.now().toString();
        if (shareLimit > 0) {
            incr("share:" + shareId + ":" + day, shareLimit, "该分享今日游客额度已用完，请明天再试或联系分享者");
        }
        if (visitorLimit > 0 && visitorUid != null && !visitorUid.isBlank()) {
            incr("visitor:" + shareId + ":" + visitorUid + ":" + day, visitorLimit, "你今日的体验次数已用完，请明天再试");
        }
        // 第二层：token 用量（由台账在每笔模型调用后累加），下一轮前在这里拦
        long tokenLimit = configService.getLong("share.guestDailyTokens", DEFAULT_DAILY_TOKENS);
        if (tokenLimit > 0) {
            Long used = readTokenUsed(shareId, day);
            if (used != null && used >= tokenLimit) {
                log.info("[SHARE-BUDGET] 分享 token 日额度用满 share={} used={}/{}", shareId, used, tokenLimit);
                throw new BizException(429, "该分享今日游客额度已用完，请明天再试或联系分享者");
            }
        }
    }

    /** 台账在每笔模型调用后回调：把 token 累加到分享的当日计数（best-effort，不影响问答主链路） */
    public void addTokens(String shareId, long tokens) {
        if (shareId == null || shareId.isBlank() || tokens <= 0) return;
        long tokenLimit = configService.getLong("share.guestDailyTokens", DEFAULT_DAILY_TOKENS);
        if (tokenLimit <= 0) return;
        String key = tokenKey(shareId, LocalDate.now().toString());
        try {
            Long v = redisTemplate.opsForValue().increment(key, tokens);
            if (v != null && v == tokens) redisTemplate.expire(key, Duration.ofHours(26));
        } catch (Exception e) {
            log.warn("[SHARE-BUDGET] token 累加失败（不影响本轮） share={}: {}", shareId, e.toString());
        }
    }

    private Long readTokenUsed(String shareId, String day) {
        try {
            String v = redisTemplate.opsForValue().get(tokenKey(shareId, day));
            return v == null || v.isBlank() ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("[SHARE-BUDGET] token 额度读取失败（按 fail-closed 拒绝） share={}: {}", shareId, e.toString());
            throw new BizException(503, "分享额度暂时无法校验，请稍后重试");
        }
    }

    private static String tokenKey(String shareId, String day) {
        return PREFIX + "tokens:" + shareId + ":" + day;
    }

    private void incr(String key, int limit, String refusal) {
        String k = PREFIX + key;
        try {
            Long count = redisTemplate.opsForValue().increment(k);
            if (count == null) throw new IllegalStateException("Redis 返回空计数");
            if (count == 1) redisTemplate.expire(k, Duration.ofHours(26));
            if (count > limit) {
                log.info("[SHARE-BUDGET] 触发游客日额度 key={} count={}/{}", key, count, limit);
                throw new BizException(429, refusal);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // fail-closed：额度校验不可用时不放行匿名入口
            log.warn("[SHARE-BUDGET] 额度校验失败（按 fail-closed 拒绝） key={}: {}", key, e.toString());
            throw new BizException(503, "分享额度暂时无法校验，请稍后重试");
        }
    }
}
