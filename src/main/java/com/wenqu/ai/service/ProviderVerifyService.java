package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.ProviderMapper;
import com.wenqu.ai.model.Provider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 供应商主动核验：把「这家还能不能用」从<b>被动撞错</b>变成<b>主动可见</b>。
 *
 * <h3>为什么被动通道不够</h3>
 * 既有的额度登记（{@link ModelQuotaService}）是纯被动的——只有真实调用失败才标记。
 * 后果是「供应商坏了」的第一现场永远在对话页/定时任务里：用户先失败一轮，
 * 回到本页才看见红条；夜里没人看着的定时任务更是静默失败到早上。
 * 本服务用 {@code GET /v1/models}（免费、不产生 token 费用）做轻量体检，
 * 页面打开（陈旧项）+ 每日定时兜底，让失效在列表页先于撞错可见。
 *
 * <h3>三个关键取舍</h3>
 * <b>① 核验成功不清额度标记。</b>{@code /v1/models} 不花 token，余额耗尽的网关
 * 照样可能返回模型列表——拿它当「已充值」的证据会把被动标记错误清除，
 * 用户随即在真实调用里再次撞墙。标记的清除只认成功<b>调用</b>（既有口径）。
 * 反过来，核验检出额度问题时照样走 {@link ModelQuotaService#markExhausted}
 * 登记并通知归属人——这正是「预警前置」：不用等问答打脸。
 *
 * <b>② 只用 /models，不做付费探测。</b>网关不实现 /models 时如实记
 * {@code rejected}（附原因），不退回「发一次最小补全」——核验会高频跑，
 * 让体检本身烧钱是本末倒置。个别模型级验证仍在「管理模型」里手动点。
 *
 * <b>③ 并发去重。</b>同一供应商的核验在途时，后来者直接跳过（不排队重复打）；
 * 批量核验并行执行（4 线程），单次调用 2s 连接 / 5s 读超时封顶。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ProviderVerifyService {

    /** 核验结果：可达 */
    public static final String OK = "ok";
    /** 核验结果：网络不通（DNS/连接拒绝/超时） */
    public static final String UNREACHABLE = "unreachable";
    /** 核验结果：网关有响应但拒绝（鉴权失败/路径不存在/5xx） */
    public static final String REJECTED = "rejected";
    /** 核验结果：额度不足（402 或网关报欠费） */
    public static final String EXPIRED = "expired";

    /** 页面打开时视为「够新」的窗口：距上次核验不足该时长的供应商不再重复探测 */
    private static final int FRESH_MINUTES = 10;

    private final ProviderMapper providerMapper;
    private final ModelRegistryService registry;
    private final ConfigCryptoService crypto;
    private final ModelQuotaService quotaService;

    /** 在途核验去重（同 providerId 并发只打一次网关） */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "provider-verify");
        t.setDaemon(true);
        return t;
    });

    public ProviderVerifyService(ProviderMapper providerMapper, ModelRegistryService registry,
                                 ConfigCryptoService crypto, ModelQuotaService quotaService) {
        this.providerMapper = providerMapper;
        this.registry = registry;
        this.crypto = crypto;
        this.quotaService = quotaService;
    }

    // ==================== 单家 / 批量 ====================

    /**
     * 核验一家并写回结果列。
     *
     * @param providerId 供应商 ID
     * @return {providerId, name, verifyStatus, lastVerifyMs, verifyMessage}；供应商不存在返回 null
     */
    public Map<String, Object> verify(String providerId) {
        Provider p = providerMapper.selectById(providerId);
        if (p == null) return null;
        return verifyOne(p);
    }

    /**
     * 批量核验（页面打开的「陈旧项」与批量管理模式共用）。
     * 并行执行、单家失败不影响其余；返回逐家结果列表。
     */
    public List<Map<String, Object>> verifyBatch(List<String> providerIds) {
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (String id : providerIds) {
            futures.add(pool.submit(() -> verify(id)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Future<Map<String, Object>> f : futures) {
            try {
                Map<String, Object> r = f.get();
                if (r != null) out.add(r);
            } catch (Exception e) {
                log.warn("[Verify] 批量核验单家失败: {}", e.getMessage());
            }
        }
        if (!out.isEmpty()) registry.refreshProviderCache();
        return out;
    }

    /**
     * 核验请求者可见且「核验已过期」的启用供应商（页面打开调用）。
     * 从未核验、或上次核验早于 {@value #FRESH_MINUTES} 分钟前的才算陈旧——
     * 刚核过的不重复打网关，开页代价与供应商数量无关。
     */
    public List<Map<String, Object>> verifyStaleFor(String uid, String role) {
        List<String> stale = new ArrayList<>();
        for (Provider p : registry.visibleProviders(uid, role)) {
            if (Integer.valueOf(0).equals(p.getEnabled())) continue;
            if (isStale(p.getLastVerifiedAt())) stale.add(p.getId());
        }
        return verifyBatch(stale);
    }

    /** 距上次核验是否已超过新鲜窗口（从未核验恒为陈旧） */
    public static boolean isStale(LocalDateTime at) {
        return at == null || at.plusMinutes(FRESH_MINUTES).isBefore(LocalDateTime.now());
    }

    // ==================== 每日兜底 ====================

    /**
     * 每日凌晨全量核验所有启用供应商：页面没人打开时，失效也要在早上第一件事之前可见。
     * 逐家串行（夜间无人在等结果，不必抢线程）；单家异常只记日志不中断整轮。
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void dailySweep() {
        List<Provider> enabled = providerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Provider>()
                        .eq(Provider::getEnabled, 1));
        if (enabled.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        int ok = 0, bad = 0;
        for (Provider p : enabled) {
            try {
                Map<String, Object> r = verifyOne(p);
                if (r != null && OK.equals(r.get("verifyStatus"))) ok++; else bad++;
            } catch (Exception e) {
                bad++;
                log.warn("[Verify] 每日核验「{}」异常: {}", p.getName(), e.getMessage());
            }
        }
        registry.refreshProviderCache();
        log.info("[Verify] 每日核验完成: {} 家（可达 {} / 异常 {}），耗时 {}ms",
                enabled.size(), ok, bad, System.currentTimeMillis() - t0);
    }

    // ==================== 探测与写回 ====================

    private Map<String, Object> verifyOne(Provider p) {
        if (!inFlight.add(p.getId())) {
            // 在途：回读当前库里已写的结果（可能刚被另一线程更新），不再重复打网关
            Provider fresh = providerMapper.selectById(p.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("providerId", p.getId());
            m.put("name", p.getName());
            m.put("verifyStatus", fresh == null ? null : fresh.getVerifyStatus());
            m.put("skipped", true);
            return m;
        }
        try {
            long start = System.currentTimeMillis();
            String[] np = DynamicOpenAiChatModel.normalize(p.getBaseUrl(), "", "/v1/models", "/models");
            String key = crypto.decrypt(p.getApiKey());
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(2000);
            f.setReadTimeout(5000);
            String status;
            String message = null;
            try {
                RestClient client = RestClient.builder().requestFactory(f).build();
                RestClient.RequestHeadersSpec<?> spec = client.get().uri(np[0] + np[1]);
                if (key != null && !key.isBlank()) spec = spec.header("Authorization", "Bearer " + key);
                spec.retrieve().toEntity(String.class);
                status = OK;
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                String body = e.getResponseBodyAsString() == null ? "" : e.getResponseBodyAsString();
                if (ModelQuotaGuard.isQuotaExhausted(new IllegalStateException(code + " " + body))) {
                    status = EXPIRED;
                    message = "额度不足（HTTP " + code + "）";
                } else if (code == 401 || code == 403) {
                    status = REJECTED;
                    message = "API Key 可能已失效或被吊销（HTTP " + code + "）";
                } else if (code == 404) {
                    status = REJECTED;
                    message = "网关未提供 /v1/models（HTTP 404）：可达性未知，模型级验证请在「管理模型」内点测试";
                } else {
                    status = REJECTED;
                    message = "网关返回 HTTP " + code + "：" + cut(body);
                }
            } catch (Exception e) {
                status = UNREACHABLE;
                message = "无法连接网关：" + rootMessage(e);
            }
            int ms = (int) (System.currentTimeMillis() - start);

            providerMapper.update(null, new LambdaUpdateWrapper<Provider>()
                    .eq(Provider::getId, p.getId())
                    .set(Provider::getLastVerifiedAt, LocalDateTime.now())
                    .set(Provider::getLastVerifyMs, ms)
                    .set(Provider::getVerifyStatus, status)
                    .set(Provider::getVerifyMessage, OK.equals(status) ? null : trim(message, 500)));

            // 检出额度问题：走被动通道登记 + 通知归属人（预警前置，不用等问答撞墙）。
            // 成功不清标记——/models 不花 token，余额耗尽的网关照样可能放行（见类注释取舍①）。
            if (EXPIRED.equals(status)) {
                quotaService.markExhausted(p.getId(), null, message);
            }
            if (!OK.equals(status)) {
                log.info("[Verify] 「{}」核验={} 耗时={}ms 原因={}", p.getName(), status, ms, message);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("providerId", p.getId());
            out.put("name", p.getName());
            out.put("verifyStatus", status);
            out.put("lastVerifyMs", ms);
            out.put("verifyMessage", message);
            return out;
        } finally {
            inFlight.remove(p.getId());
        }
    }

    private static String cut(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
