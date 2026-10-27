package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.ModelInfoMapper;
import com.wenqu.ai.mapper.QuotaMapper;
import com.wenqu.ai.mapper.UsageLogMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.model.Quota;
import com.wenqu.ai.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推理配额与预算（Phase 2 计量计费与配额，docs/enterprise-upgrade-plan.md §4.2）。
 *
 * <p><b>账从哪来</b>：用量唯一的事实源是 {@code c_ai_usage_log}，金额按 {@code c_ai_model} 的现行单价现算。
 * 配额检查<b>不在 Redis 另存一份累加计数</b>——那样等于同一笔钱有两条账：Redis 被清、被换实例、
 * 或与「先 INSERT 后 INCR」的并发次序抢跑，都会让上限悄悄失准，而且失准方向不可知。
 * 这里直接按台账聚合，结果缓存 30 秒：闸门晚 30 秒收紧，但不会错。
 *
 * <p><b>三档作用域各自独立</b>：成员可以同时被「平台月预算 + 本部门日配额 + 个人月配额」卡住，
 * 拒绝文案必须点名是哪一条规则、用了多少、上限多少、什么时候恢复——只说「超额」是没法追问的。
 *
 * <p><b>拦什么</b>：只拦用户主动发起的调用（对话 / 子智能体 / 工作流）。历史压缩、记忆提炼、
 * 引用自检这类辅助调用不拦——把它们掐断不是省钱，是让上下文撑爆、主轮反而更贵的另一种坏。
 * 配额行 {@code kinds} 显式写了用途时以该清单为准。
 *
 * <p><b>告警档</b>：{@code enforce=0} 只通报不拦（新策略先观察一个周期）。到额通报按
 * 「每档每周期每人」去重，不然一个活跃成员能把管理员通知刷爆。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class UsageQuotaService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    /** 默认受限用途：用户主动发起的三类（辅助调用不拦，见类注释） */
    private static final List<String> BILLABLE_KINDS = List.of("chat", "subagent", "workflow");
    /** 用量聚合缓存时长：闸门锁紧的延迟上界，换来每次调用不打一条聚合 SQL */
    private static final long USAGE_CACHE_SECONDS = 30;
    /** 配额行与单价缓存时长 */
    private static final long RULES_CACHE_SECONDS = 60;
    private static final String NOTIFY_KEY = "wenqu:quota:notify:";

    private final QuotaMapper quotaMapper;
    private final UsageLogMapper usageLogMapper;
    private final ModelInfoMapper modelInfoMapper;
    private final UserMapper userMapper;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;

    /**
     * 配额行缓存按<b>租户</b>分桶（Phase 4）。
     * <p>那条查询本身由租户拦截器过滤，共用一份全局缓存就把它变成「谁先读到算谁的」：
     * A 租户的管理员保存过预算后，60 秒内 B 租户的成员会看见、也会被那条规则拦住。
     * 未绑定的线程单独占一格（读到的是全平台行），不与任何租户的格子互相污染。
     */
    private static final String UNBOUND_TENANT = "\u0000unbound";
    private final Map<String, CachedRules> rulesCache = new ConcurrentHashMap<>();
    private volatile Map<String, BigDecimal[]> priceCache = Map.of();
    private volatile long priceExpiresAt;
    private final Map<String, CachedUsage> usageCache = new ConcurrentHashMap<>();
    /** 成员 → 部门 id（空串=无部门）；量级=成员数，随规则缓存一起过期 */
    private final Map<String, CachedDept> deptCache = new ConcurrentHashMap<>();

    private record CachedUsage(UsageAgg usage, long expiresAt) {
    }

    private record CachedDept(String deptId, long expiresAt) {
    }

    /** 某个租户眼里看到的配额行（缓存按租户分格，理由见 {@code rulesCache}） */
    private record CachedRules(List<Quota> rules, long expireAt) {
    }

    /** 一个作用域在本周期内的用量 */
    public record UsageAgg(long tokens, double cost, long sinceRows) {
        static UsageAgg zero() {
            return new UsageAgg(0, 0, 0);
        }
    }

    public UsageQuotaService(QuotaMapper quotaMapper, UsageLogMapper usageLogMapper, ModelInfoMapper modelInfoMapper,
                        UserMapper userMapper, NotificationService notificationService,
                        StringRedisTemplate redis, AuditService audit) {
        this.quotaMapper = quotaMapper;
        this.usageLogMapper = usageLogMapper;
        this.modelInfoMapper = modelInfoMapper;
        this.userMapper = userMapper;
        this.notificationService = notificationService;
        this.redis = redis;
        this.audit = audit;
    }

    // ==================== 强制点 ====================

    /**
     * 网关层配额闸：超限直接拒（{@code BizException(429)}，与限流同一档语义）。
     * <p>由两个模型出口共同调用（{@code DynamicOpenAiChatModel} 与框架侧 {@code MeteredModel}），
     * 判据只有这一份——两处各写一遍就会漂，而这条闸管的是钱。
     *
     * @param uid  归属成员（null/空=系统侧调用，不受成员档约束，只受平台档约束）
     * @param kind 调用用途
     */
    public void assertWithinBudget(String uid, String kind) {
        String k = kind == null || kind.isBlank() ? "chat" : kind;
        String deptId = uid == null || uid.isBlank() ? null : departmentOf(uid);
        for (Quota q : applicable(uid, deptId, k)) {
            UsageAgg agg = usage(q);
            String breach = breachOf(q, agg);
            if (breach == null) continue;
            notifyOnce(q, uid, breach);
            if (q.getEnforce() != null && q.getEnforce() == 0) {
                continue;   // 告警档：只通报不拦
            }
            throw new BizException(429, refusalText(q, agg, breach));
        }
    }

    /** 到第几档了：token 到顶 / 金额到顶 / 没到 */
    private String breachOf(Quota q, UsageAgg agg) {
        if (q.getTokenLimit() != null && q.getTokenLimit() > 0 && agg.tokens() >= q.getTokenLimit()) {
            return "token";
        }
        if (q.getCostLimit() != null && q.getCostLimit().signum() > 0
                && agg.cost() >= q.getCostLimit().doubleValue()) {
            return "cost";
        }
        return null;
    }

    /** 拒绝文案：点名哪条规则、用了多少、上限多少、什么时候恢复（用户要能据此决定下一步） */
    private String refusalText(Quota q, UsageAgg agg, String breach) {
        String scope = scopeLabel(q);
        String period = "day".equals(q.getPeriod()) ? "本日" : "本月";
        StringBuilder sb = new StringBuilder("配额已用满，本轮不再发起模型调用：");
        if ("token".equals(breach)) {
            sb.append(scope).append(' ').append(period).append(" token 上限 ")
                    .append(fmtTokens(q.getTokenLimit())).append("，已用 ").append(fmtTokens(agg.tokens()));
        } else {
            sb.append(scope).append(' ').append(period).append("费用上限 ")
                    .append(q.getCostLimit().stripTrailingZeros().toPlainString()).append(" 元，已用 ")
                    .append(String.format("%.2f", agg.cost())).append(" 元");
        }
        sb.append("。恢复时间：").append(resetAtLabel(q.getPeriod()))
                .append("；确需更多额度请联系管理员（规则设在「数据看板 → 费用 → 配额与预算」）。");
        return sb.toString();
    }

    // ==================== 规则 ====================

    /** 这条调用受哪些配额约束（三档作用域 + 用途清单） */
    private List<Quota> applicable(String uid, String deptId, String kind) {
        List<Quota> all = rules();
        if (all.isEmpty()) return List.of();
        List<Quota> out = new ArrayList<>();
        for (Quota q : all) {
            if (!matchesScope(q, uid, deptId)) continue;
            if (!matchesKind(q, kind)) continue;
            boolean capped = (q.getTokenLimit() != null && q.getTokenLimit() > 0)
                    || (q.getCostLimit() != null && q.getCostLimit().signum() > 0);
            if (!capped) continue;   // 两个上限都没设＝这条规则不限任何东西，别拿它去查库
            out.add(q);
        }
        return out;
    }

    private boolean matchesScope(Quota q, String uid, String deptId) {
        return switch (q.getScope() == null ? "" : q.getScope()) {
            case "user" -> uid != null && uid.equals(q.getScopeId());
            case "department" -> deptId != null && deptId.equals(q.getScopeId());
            case "tenant" -> {
                // 双保险：缓存已按租户分格，这里再认一次规则自己的归属——
                // 线程绑着租户时，别家租户的预算一律不参与判定（未绑定线程维持原有语义，见 rulesCache 注释）
                String bound = com.wenqu.ai.util.TenantContext.current();
                yield bound == null || bound.equals(q.getTenantId() == null || q.getTenantId().isBlank()
                        ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : q.getTenantId());
            }
            default -> false;
        };
    }

    private boolean matchesKind(Quota q, String kind) {
        String kinds = q.getKinds();
        if (kinds == null || kinds.isBlank()) return BILLABLE_KINDS.contains(kind);
        for (String k : kinds.split(",")) {
            if (k.trim().equals(kind)) return true;
        }
        return false;
    }

    private List<Quota> rules() {
        String key = tenantCacheKey();
        long now = System.currentTimeMillis();
        CachedRules hit = rulesCache.get(key);
        if (hit != null && now < hit.expireAt()) return hit.rules();
        List<Quota> rows;
        try {
            rows = quotaMapper.selectList(new LambdaQueryWrapper<Quota>()
                    .orderByAsc(Quota::getScope).orderByAsc(Quota::getPeriod));
        } catch (Exception e) {
            log.error("[Quota] 读取配额规则失败（本轮按不限额放行，但必须显形）: {}", e.getMessage());
            rows = List.of();
        }
        List<Quota> safe = rows == null ? List.of() : List.copyOf(rows);
        rulesCache.put(key, new CachedRules(safe, now + RULES_CACHE_SECONDS * 1000L));
        return safe;
    }

    private static String tenantCacheKey() {
        String t = com.wenqu.ai.util.TenantContext.current();
        return t == null ? UNBOUND_TENANT : t;
    }

    /** 规则改了就丢缓存（本进程立即生效，别的副本靠广播跟着丢；按租户分格所以要整格清空） */
    public void invalidateRules() {
        rulesCache.clear();
        usageCache.clear();
    }

    // ==================== 用量 ====================

    /** 本周期内这条规则作用域的用量（token 与金额）；结果缓存 30 秒 */
    public UsageAgg usage(Quota q) {
        String periodKey = periodKey(q.getPeriod());
        String cacheKey = q.getId() + ":" + periodKey;
        long now = System.currentTimeMillis();
        CachedUsage hit = usageCache.get(cacheKey);
        if (hit != null && now < hit.expiresAt()) return hit.usage();
        List<String> kinds = kindList(q);
        UsageAgg agg = UsageAgg.zero();
        try {
            List<Map<String, Object>> rows = usageLogMapper.sumForQuota(
                    "user".equals(q.getScope()) ? q.getScopeId() : null,
                    "department".equals(q.getScope()) ? q.getScopeId() : null,
                    periodStart(q.getPeriod()), kinds);
            long tokens = 0;
            double cost = 0;
            Map<String, BigDecimal[]> prices = prices();
            for (Map<String, Object> row : rows) {
                long p = num(row.get("p")), c = num(row.get("c"));
                tokens += p + c;
                BigDecimal[] price = prices.get(row.get("model") == null ? "" : String.valueOf(row.get("model")));
                if (price != null) {
                    cost += p / 1_000_000.0 * price[0].doubleValue() + c / 1_000_000.0 * price[1].doubleValue();
                }
            }
            agg = new UsageAgg(tokens, cost, rows.size());
        } catch (Exception e) {
            log.error("[Quota] 用量聚合失败（这条规则本轮不拦，账目异常需人工看）: quota={} {}", q.getId(), e.getMessage());
        }
        usageCache.put(cacheKey, new CachedUsage(agg, now + USAGE_CACHE_SECONDS * 1000L));
        return agg;
    }

    private List<String> kindList(Quota q) {
        String kinds = q.getKinds();
        if (kinds == null || kinds.isBlank()) return BILLABLE_KINDS;
        List<String> out = new ArrayList<>();
        for (String k : kinds.split(",")) {
            if (!k.isBlank()) out.add(k.trim());
        }
        return out;
    }

    private long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** 模型引用 → {输入单价, 输出单价}；NULL 单价的模型不进金额口径（未计价≠免费，界面要说清） */
    private Map<String, BigDecimal[]> prices() {
        long now = System.currentTimeMillis();
        if (now < priceExpiresAt) return priceCache;
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        try {
            for (ModelInfo m : modelInfoMapper.selectList(null)) {
                if (m.getInputPrice() == null || m.getOutputPrice() == null) continue;
                out.put(m.getProviderId() + "/" + m.getModelId(),
                        new BigDecimal[]{m.getInputPrice(), m.getOutputPrice()});
            }
        } catch (Exception e) {
            log.error("[Quota] 读取模型单价失败（金额配额本轮按 0 计，不会误拦但会少算）: {}", e.getMessage());
        }
        priceCache = out;
        priceExpiresAt = now + RULES_CACHE_SECONDS * 1000L;
        return priceCache;
    }

    private String departmentOf(String uid) {
        long now = System.currentTimeMillis();
        CachedDept hit = deptCache.get(uid);
        if (hit != null && now < hit.expiresAt()) return hit.deptId();
        String deptId = null;
        try {
            User u = userMapper.selectById(uid);
            deptId = u == null || u.getDepartmentId() == null || u.getDepartmentId().isBlank() ? null : u.getDepartmentId();
        } catch (Exception e) {
            log.warn("[Quota] 查成员部门失败（按无部门处理，部门档配额对它不生效）: uid={} {}", uid, e.getMessage());
        }
        deptCache.put(uid, new CachedDept(deptId, now + RULES_CACHE_SECONDS * 1000L));
        return deptId;
    }

    // ==================== 周期 ====================

    private String periodKey(String period) {
        LocalDate today = LocalDate.now();
        return "day".equals(period) ? today.toString().replace("-", "")
                : YearMonth.from(today).toString().replace("-", "");
    }

    private LocalDateTime periodStart(String period) {
        return "day".equals(period) ? LocalDate.now().atStartOfDay()
                : YearMonth.now().atDay(1).atStartOfDay();
    }

    private String resetAtLabel(String period) {
        return "day".equals(period) ? "明日 00:00"
                : YearMonth.now().plusMonths(1).atDay(1) + " 00:00";
    }

    private Duration ttlToPeriodEnd(String period) {
        LocalDateTime end = "day".equals(period)
                ? LocalDate.now().plusDays(1).atStartOfDay()
                : YearMonth.now().plusMonths(1).atDay(1).atStartOfDay();
        return Duration.between(LocalDateTime.now(), end).plusHours(1);
    }

    // ==================== 通报 ====================

    /** 到额通报：每档每周期每人只发一次（Redis SETNX，随周期过期） */
    private void notifyOnce(Quota q, String uid, String breach) {
        String key = NOTIFY_KEY + q.getId() + ":" + periodKey(q.getPeriod()) + ":" + (uid == null ? "sys" : uid);
        try {
            Boolean first = redis.opsForValue().setIfAbsent(key, breach, ttlToPeriodEnd(q.getPeriod()));
            if (!Boolean.TRUE.equals(first)) return;
            String who = uid == null || uid.isBlank() ? "系统侧调用" : "成员 " + uid;
            String title = "配额触顶：" + scopeLabel(q);
            String body = who + " 触到「" + scopeLabel(q) + "」的" + ("day".equals(q.getPeriod()) ? "日" : "月")
                    + "配额" + ("token".equals(breach) ? "（token 到顶）" : "（费用到顶）")
                    + "。" + (q.getEnforce() != null && q.getEnforce() == 0
                    ? "本规则设为只告警，调用未被拦下。" : "后续该用途的模型调用已被拦下。")
                    + " 需要调整请到「数据看板 → 费用 → 配额与预算」。";
            notificationService.createForAdmins("quota", title, body, "quota", q.getId());
            audit.record("[AUDIT][QUOTA] 触顶 quota={} scope={} scopeId={} period={} breach={} uid={} enforce={}",
                    q.getId(), q.getScope(), q.getScopeId(), q.getPeriod(), breach, uid, q.getEnforce());
        } catch (Exception e) {
            // 通报失败不该拖垮闸；但也不能静默——管理员看不到就等于这条规则没在跑
            log.warn("[Quota] 触顶通报发送失败: quota={} {}", q.getId(), e.getMessage());
        }
    }

    private String scopeLabel(Quota q) {
        return switch (q.getScope() == null ? "" : q.getScope()) {
            case "tenant" -> "本租户预算";
            case "department" -> "部门配额";
            case "user" -> "成员配额";
            default -> "配额规则";
        };
    }

    /** token 数说人话：百万 / 万 / 原值三档，与看板同一读法 */
    private String fmtTokens(long tokens) {
        if (tokens >= 1_000_000) return String.format("%.2f 百万 token", tokens / 1_000_000.0);
        if (tokens >= 10_000) return String.format("%.1f 万 token", tokens / 10_000.0);
        return tokens + " token";
    }

    // ==================== 管理侧读写 ====================

    /** 规则清单 + 此刻用量与进度（看板一张表看完，不在前端二次拼） */
    public List<Map<String, Object>> listWithUsage() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Quota q : quotaMapper.selectList(new LambdaQueryWrapper<Quota>()
                .orderByAsc(Quota::getScope).orderByAsc(Quota::getPeriod))) {
            UsageAgg agg = usage(q);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("scope", q.getScope());
            m.put("scopeId", q.getScopeId());
            m.put("scopeName", scopeDisplayName(q));
            m.put("period", q.getPeriod());
            m.put("tokenLimit", q.getTokenLimit());
            m.put("costLimit", q.getCostLimit());
            m.put("kinds", q.getKinds());
            m.put("enforce", q.getEnforce());
            m.put("remark", q.getRemark());
            m.put("usedTokens", agg.tokens());
            m.put("usedCost", round2(agg.cost()));
            m.put("tokenPercent", percent(agg.tokens(), q.getTokenLimit()));
            m.put("costPercent", q.getCostLimit() == null ? null : percent(Math.round(agg.cost() * 100),
                    q.getCostLimit().multiply(BigDecimal.valueOf(100)).longValue()));
            m.put("breach", breachOf(q, agg));
            out.add(m);
        }
        return out;
    }

    /** 成员自查：我现在被哪几条规则管着、各用了多少（个人设置与配额拒绝都指向同一份数） */
    public List<Map<String, Object>> mine(String uid) {
        String deptId = departmentOf(uid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Quota q : applicable(uid, deptId, "chat")) {
            UsageAgg agg = usage(q);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", q.getScope());
            m.put("scopeName", scopeDisplayName(q));
            m.put("period", q.getPeriod());
            m.put("tokenLimit", q.getTokenLimit());
            m.put("costLimit", q.getCostLimit());
            m.put("usedTokens", agg.tokens());
            m.put("usedCost", round2(agg.cost()));
            m.put("enforce", q.getEnforce());
            out.add(m);
        }
        return out;
    }

    private String scopeDisplayName(Quota q) {
        if ("tenant".equals(q.getScope())) return "本租户";
        try {
            if ("user".equals(q.getScope())) {
                User u = userMapper.selectById(q.getScopeId());
                if (u == null) return q.getScopeId();
                return u.getUsername() == null || u.getUsername().isBlank() ? u.getUid() : u.getUsername();
            }
        } catch (Exception ignored) {
            // 查不到名字就回原标识，不编一个
        }
        return q.getScopeId();
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 进度百分比：不夹到 999——「999%」看着像截断的数，真实倍数（如 1894%）才说明超了多少 */
    private Integer percent(long used, Long limit) {
        if (limit == null || limit <= 0) return null;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(used * 100.0 / limit));
    }

    /** 新建或覆盖（同作用域同周期唯一，撞了就改那一条） */
    public Quota save(String id, String scope, String scopeId, String period, Long tokenLimit,
                      BigDecimal costLimit, String kinds, Integer enforce, String remark) {
        if (!Set.of("tenant", "department", "user").contains(scope)) {
            throw new BizException("配额作用域只能是平台/部门/成员");
        }
        if (!Set.of("day", "month").contains(period)) {
            throw new BizException("配额周期只能是按日或按月");
        }
        if ("tenant".equals(scope)) scopeId = null;
        if (!"tenant".equals(scope) && (scopeId == null || scopeId.isBlank())) {
            throw new BizException("部门档要选部门，成员档要选成员");
        }
        boolean capped = (tokenLimit != null && tokenLimit > 0) || (costLimit != null && costLimit.signum() > 0);
        if (!capped) throw new BizException("至少填一个上限（token 或费用），否则这条规则不限任何东西");

        Quota q = id == null || id.isBlank() ? null : quotaMapper.selectById(id);
        boolean fresh = q == null;
        if (fresh) {
            q = new Quota();
            q.setId(UUID.randomUUID().toString());
        }
        q.setScope(scope);
        q.setScopeId(scopeId);
        q.setPeriod(period);
        q.setTokenLimit(tokenLimit == null ? 0L : tokenLimit);
        q.setCostLimit(costLimit);
        q.setKinds(kinds == null || kinds.isBlank() ? null : kinds.trim());
        q.setEnforce(enforce == null ? 1 : enforce);
        q.setRemark(remark);
        if (fresh) {
            quotaMapper.insert(q);
            audit.record("[AUDIT] 新建配额 scope={} scopeId={} period={} token={} cost={} enforce={}",
                    scope, scopeId, period, q.getTokenLimit(), costLimit, q.getEnforce());
        } else {
            quotaMapper.updateById(q);
            audit.record("[AUDIT] 修改配额 id={} scope={} token={} cost={} enforce={}", q.getId(), scope,
                    q.getTokenLimit(), costLimit, q.getEnforce());
        }
        invalidateRules();
        return q;
    }

    public void delete(String id) {
        Quota q = quotaMapper.selectById(id);
        if (q == null) throw new BizException("配额规则不存在");
        quotaMapper.deleteById(id);
        audit.record("[AUDIT] 删除配额 id={} scope={} scopeId={} period={}", id, q.getScope(), q.getScopeId(), q.getPeriod());
        invalidateRules();
    }
}
