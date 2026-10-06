package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 模型额度状态登记与通知：把 {@link ModelQuotaGuard} 的识别结果落到「用户看得见的地方」。
 *
 * <h3>职责边界</h3>
 * 识别（是不是额度不足）在 {@link ModelQuotaGuard}，本类只做处置：
 * <ol>
 *   <li><b>登记</b>：在 {@code c_ai_provider} 上打额度异常标记（状态 + 原因 + 发生时刻 + 涉及模型），</li>
 *   <li><b>通知</b>：给供应商归属人发站内通知，内容里<b>带上引用清单</b>
 *       （「你有这些库/这些人在用它」——只说「额度不足」用户依然不知道该改哪里）；</li>
 *   <li><b>恢复</b>：调用成功或连通性测试通过时清除标记。</li>
 * </ol>
 *
 * <h3>三个关键取舍</h3>
 * <b>① 通知发给归属人，不是管理员。</b>数据按 userId 隔离、供应商「谁建归谁」，
 * 只有归属人能充值换 Key；发给管理员等于把问题派给无权处理的人。
 *
 * <b>② 标记会自动恢复，但恢复要「有证据」。</b>充值后如果没人再调用，标记会永远挂着，
 * 把一个已经能用的模型继续在选择器里禁选——比不标记更糟。因此任何一次该供应商的
 * <b>成功调用</b>都会清除标记，不需要用户手动点「我已充值」。
 *
 * <b>③ 旁路语义：登记失败绝不拖垮主链路。</b>问答已经因为额度问题失败了，
 * 登记再抛异常只会让错误信息更难懂，因此全捕获只记 WARN（同 {@link NotificationService} 的口径）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ModelQuotaService {

    private final com.wenqu.ai.mapper.ProviderMapper providerMapper;
    private final NotificationService notificationService;
    private final ModelReferenceScanner referenceScanner;

    /**
     * 处于「额度不足」标记中的供应商 ID（内存快路径）。
     *
     * <p><b>为什么必须有它</b>：{@link #markRecovered} 挂在<b>每一次成功的向量调用</b>上，
     * 而向量化是逐块进行的（一份文档几百块），无条件查库会把这张小表读成热点。
     * 绝大多数供应商从来不会额度不足，这个集合长期为空，于是绝大多数调用只付一次
     * 内存判断（无锁、纳秒级）就直接返回。
     *
     * <p>一致性：仅作<b>读侧加速</b>，不作为状态来源——集合里可能有已过期的条目
     * （最坏代价是多查一次库并发现无需清除）；集合外一定没有标记（登记时必写入，
     * 成功清除时必移除），不会出现「标记了却查不到导致清不掉」。
     */
    private final java.util.Set<String> flagged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 额度异常标记的时效：超过此时长未复现即视为已自愈，避免标记永久挂着禁选模型 */
    private static final int STALE_HOURS = 24;

    /** 通知去重键（同供应商的额度问题在未读窗口内合并，不刷屏） */
    private static String dedupKey(String providerId) {
        return "quota:" + providerId;
    }

    public ModelQuotaService(com.wenqu.ai.mapper.ProviderMapper providerMapper,
                             NotificationService notificationService,
                             ModelReferenceScanner referenceScanner) {
        this.providerMapper = providerMapper;
        this.notificationService = notificationService;
        this.referenceScanner = referenceScanner;
    }

    // ==================== 登记 ====================

    /**
     * 启动时把库中已有的额度标记灌进内存快路径集合。
     *
     * <p>不灌的后果：进程重启后集合为空，那些「数据库里有标记、内存里没有」的供应商
     * 即便调用成功也永远清不掉标记，会一直禁选到 24 小时过期——用户明明已经充好费了。
     */
    @jakarta.annotation.PostConstruct
    public void loadFlags() {
        try {
            List<Provider> rows = providerMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Provider>()
                            .eq(Provider::getQuotaStatus, QUOTA_EXHAUSTED));
            for (Provider p : rows) {
                if (!isStale(p.getQuotaCheckedAt())) flagged.add(p.getId());
            }
            if (!flagged.isEmpty()) {
                log.info("[Quota] 启动载入额度标记供应商 {} 个（过期项已忽略）", flagged.size());
            }
        } catch (Exception e) {
            log.warn("[Quota] 额度标记载入失败（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 登记额度不足并通知归属人（识别已由 {@link ModelQuotaGuard} 完成，本方法不再判断）。
     *
     * @param providerId 供应商 ID
     * @param modelRef    触发时使用的模型引用（{@code providerId/modelId}，可空）
     * @param cause       网关返回的原因摘要（可空）
     */
    public void markExhausted(String providerId, String modelRef, String cause) {
        if (providerId == null || providerId.isBlank()) return;
        try {
            Provider p = providerMapper.selectById(providerId);
            if (p == null) return;

            // 已在标记中且不是新模型触发的：不重复刷库与刷通知（问答会连续多次失败，
            // 每次都发一条通知等于用同一件事把用户的铃铛刷爆）
            boolean alreadyMarked = QUOTA_EXHAUSTED.equals(p.getQuotaStatus())
                    && !isStale(p.getQuotaCheckedAt());
            if (alreadyMarked && sameModel(p.getQuotaModelId(), modelIdOf(modelRef))) {
                log.debug("[Quota] 供应商 {} 已是额度不足标记且模型未变，跳过重复登记", p.getName());
                return;
            }

            providerMapper.update(null, new LambdaUpdateWrapper<Provider>()
                    .eq(Provider::getId, providerId)
                    .set(Provider::getQuotaStatus, QUOTA_EXHAUSTED)
                    .set(Provider::getQuotaMessage, trim(cause, 500))
                    .set(Provider::getQuotaModelId, modelIdOf(modelRef))
                    .set(Provider::getQuotaCheckedAt, LocalDateTime.now()));

            flagged.add(providerId);
            notifyOwner(p, modelIdOf(modelRef), cause);
            log.warn("[Quota] 供应商「{}」额度不足已登记，触发模型={} 原因={}", p.getName(), modelIdOf(modelRef), cause);
        } catch (Exception e) {
            log.warn("[Quota] 额度状态登记失败（不影响主链路）provider={} err={}", providerId, e.getMessage());
        }
    }

    /**
     * 给供应商归属人发通知，内容含引用清单。
     * <p>清单取自 {@link ModelReferenceScanner}——用户收到通知要能立刻知道「我该去改哪几处」。
     */
    private void notifyOwner(Provider p, String modelId, String cause) {
        String owner = p.getOwnerUid();
        if (owner == null || owner.isBlank()) {
            // 无主行（历史遗留）：发给管理员，至少有人能看到
            notificationService.createForAdmins(Notification.TYPE_MODEL_QUOTA,
                    "模型供应商「" + p.getName() + "」额度不足",
                    "该供应商无归属人（历史数据），请在模型供应商页处理。" + causeSuffix(modelId, cause),
                    "provider", p.getId());
            return;
        }
        List<ModelReferenceScanner.Reference> refs = referenceScanner.referencesOf(p.getId());
        String summary = ModelReferenceScanner.summarize(refs, 6);
        StringBuilder sb = new StringBuilder();
        sb.append("请充值或改用其他模型。");
        if (modelId != null) sb.append("触发模型：").append(modelId).append('。');
        if (!summary.isEmpty()) {
            sb.append("当前引用它的位置：").append(summary).append('。');
        } else {
            sb.append("当前没有其他位置引用它，可直接在此处理。");
        }
        if (!refs.isEmpty()) {
            sb.append("在模型供应商页点「查看引用」可逐处跳转修改。");
        }
        sb.append(causeSuffix(modelId, cause));
        notificationService.create(owner, Notification.TYPE_MODEL_QUOTA,
                "模型供应商「" + p.getName() + "」额度不足",
                trim(sb.toString(), 990), "provider", p.getId(), dedupKey(p.getId()), null);
    }

    // ==================== 恢复 ====================

    /**
     * 该供应商调用成功后清除额度标记。
     *
     * <p><b>不在标记集合里直接返回</b>：本方法挂在最高频的路径上（每次问答成功、每次向量化
     * 成功的分块都会调用），而绝大多数供应商从未额度不足。无条件查库会把 {@code c_ai_provider}
     * 读成热点表，因此先用内存集合做快路径判断——不在集合里说明它不可能有标记（登记时必写入，
     * 清除时必移除），直接返回，一次数据库往返都不产生。
     *
     * @param providerId 供应商 ID（可空：调用方未必有路由信息）
     */
    public void markRecovered(String providerId) {
        if (providerId == null || providerId.isBlank()) return;
        if (!flagged.contains(providerId)) return;   // 快路径：绝大多数调用停在这里
        try {
            Provider p = providerMapper.selectById(providerId);
            if (p == null || !QUOTA_EXHAUSTED.equals(p.getQuotaStatus())) {
                flagged.remove(providerId);
                return;
            }
            providerMapper.update(null, new LambdaUpdateWrapper<Provider>()
                    .eq(Provider::getId, providerId)
                    .set(Provider::getQuotaStatus, null)
                    .set(Provider::getQuotaMessage, null)
                    .set(Provider::getQuotaModelId, null)
                    .set(Provider::getQuotaCheckedAt, null));
            flagged.remove(providerId);
            log.info("[Quota] 供应商「{}」调用成功，额度标记已清除", p.getName());
        } catch (Exception e) {
            log.debug("[Quota] 额度标记清除失败（不影响主链路）provider={} err={}", providerId, e.getMessage());
        }
    }

    /** 标记是否已超过时效（视为已自愈：用于避免对同一问题反复发通知） */
    public static boolean isStale(LocalDateTime checkedAt) {
        if (checkedAt == null) return true;
        return checkedAt.plusHours(STALE_HOURS).isBefore(LocalDateTime.now());
    }

    // ==================== 查询（供列表与选择器渲染） ====================

    /**
     * 该供应商是否处于「额度不足」标记（且未过期）。
     *
     * <p>过期即视为否：标记的用途是让用户别再往一个用不了的模型上撞，
     * 一旦过期仍显示就是拿陈旧状态禁选一个可能早已充好费的模型。
     */
    public static boolean isBlocked(Provider p) {
        return p != null && QUOTA_EXHAUSTED.equals(p.getQuotaStatus()) && !isStale(p.getQuotaCheckedAt());
    }

    /** 额度状态常量：额度不足（与 {@code Provider.quotaStatus} 的取值一一对应） */
    public static final String QUOTA_EXHAUSTED = "exhausted";

    // ==================== 工具 ====================

    /** {@code {providerId}/{modelId}} → 裸模型名（引用可空） */
    private static String modelIdOf(String modelRef) {
        if (modelRef == null) return null;
        String v = modelRef.trim();
        int i = v.indexOf('/');
        if (i < 0 || i == v.length() - 1) return v.isEmpty() ? null : v;
        return v.substring(i + 1);
    }

    private static boolean sameModel(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String causeSuffix(String modelId, String cause) {
        String c = cause == null || cause.isBlank() ? "" : ("网关原因：" + trim(cause, 200));
        return c.isEmpty() ? "" : (c + "。");
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * 供列表接口追加的额度字段（避免各处重复拼 map）。
     *
     * @return {quotaStatus, quotaMessage, quotaCheckedAt, quotaBlocked}
     */
    public static Map<String, Object> quotaFields(Provider p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        boolean blocked = isBlocked(p);
        m.put("quotaStatus", blocked ? QUOTA_EXHAUSTED : null);
        m.put("quotaBlocked", blocked);
        m.put("quotaMessage", blocked ? p.getQuotaMessage() : null);
        m.put("quotaCheckedAt", blocked ? p.getQuotaCheckedAt() : null);
        return m;
    }
}