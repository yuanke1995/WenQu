package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 关键词索引服务（Meilisearch，裸 HTTP 调用，与 RerankService/VisionService 风格一致）
 * <p>
 * 用途：替代 MySQL 的 content/title LIKE 全表扫描，提供中文分词 + 相关度打分的关键词召回。
 * 索引文档 = 知识块（primaryKey=知识块 id），仅存检索必需字段，正文内容仍以 MySQL 为准。
 * <p>
 * 可靠性策略（与重排服务一致）：
 * - 启用开关 keyword.engine=meilisearch；未启用/探测失败/调用失败 → 调用方自动降级回 MySQL LIKE
 * - 探测结果缓存 + 失败冷却（keyword.failCooldownMs），冷却结束自动重探，避免每请求都撞
 * - 配置（baseUrl/timeout）变更自动重建客户端并重置探测
 * - 写索引全部 best-effort（失败只告警不抛），最终一致由周期精确对账兜底：按 (id, contentHash)
 *   逐块比对 MySQL 有效块与索引文档，仅定向修复差异块（缺失/过期按主键批量拉取比对、孤儿由计数
 *   差值门控后才全量扫描、多副本用 MySQL 命名锁互斥；按主键拉取不可用时整轮降级两侧全量拉取比对，
 *   兼容旧版 Meilisearch。schedule 包调度中心驱动：启动先跑一次 + keyword.reconcileIntervalMs
 *   周期执行；差异过大降级全量重建。切换引擎保存配置时也自动全量重建，运维可调 /api/ai/search-index/reindex）
 */
@Slf4j
@Service
public class KeywordIndexService {

    private final AppProperties properties;
    private final ConfigService configService;
    private final KnowledgeMapper knowledgeMapper;
    private final DataSource dataSource;

    private final AtomicBoolean supportChecked = new AtomicBoolean(false);
    private volatile boolean available = false;
    private volatile long lastFailTs = 0L;
    /** 索引与 settings 是否已初始化（每次客户端重建后重新初始化） */
    private volatile boolean indexReady = false;

    /** 全量重建进行中标志（防并发重复重建） */
    private final AtomicBoolean reindexing = new AtomicBoolean(false);

    /** 精确对账进行中标志（防 daemon 周期重叠） */
    private final AtomicBoolean reconciling = new AtomicBoolean(false);

    /** 对账分布式锁名（MySQL 命名锁；多副本部署防重复对账） */
    private static final String RECONCILE_LOCK = "wenqu:keyword-reconcile";

    /** 索引写失败累计计数（M12 fail-loud：/search-index/stats 暴露，运维可见漂移之外的写故障） */
    private final AtomicLong writeFailCount = new AtomicLong();

    private volatile RestClient client;
    private volatile String clientBaseUrl = "";
    private volatile String clientApiKey = "";
    private volatile int clientTimeout = 0;

    public KeywordIndexService(AppProperties properties, ConfigService configService,
                               KnowledgeMapper knowledgeMapper, DataSource dataSource) {
        this.properties = properties;
        this.configService = configService;
        this.knowledgeMapper = knowledgeMapper;
        this.dataSource = dataSource;
    }

    // ==================== 配置（动态读取，保存即生效） ====================

    /** 当前关键词引擎（mysql / meilisearch） */
    public String engine() {
        String e = configService.get("keyword.engine");
        return e == null || e.isBlank() ? properties.getKeyword().getEngine() : e.trim();
    }

    /** 是否启用 Meilisearch 引擎（仅看配置，不含可用性） */
    public boolean enabled() {
        return "meilisearch".equalsIgnoreCase(engine());
    }

    private String baseUrl() {
        String b = configService.get("keyword.baseUrl");
        return b == null || b.isBlank() ? properties.getKeyword().getBaseUrl() : b.trim();
    }

    private int timeoutMillis() {
        int t = configService.getInt("keyword.timeoutMillis", properties.getKeyword().getTimeoutMillis());
        return t > 0 ? t : 1000;
    }

    private long failCooldownMs() {
        return configService.getInt("keyword.failCooldownMs", 60_000);
    }

    /**
     * master key：优先读设置页配置（c_ai_config 的 keyword.apiKey，RSA 加密入库，保存即生效免重启，
     * ConfigService.get 透明解密），未配置时回退 yml/env 的 AI_MEILI_KEY。
     */
    private String apiKey() {
        String k = configService.get("keyword.apiKey");
        return k == null || k.isBlank() ? properties.getKeyword().getApiKey() : k.trim();
    }

    private String index() {
        return properties.getKeyword().getIndex();
    }

    /**
     * 客户端（惰性构建）：首次使用时按当前配置构建，配置变更时重建。
     * <p>变更判定刻意比较<b>解密后的明文</b>而不是库中密文：keyword.apiKey 走 RSA-OAEP 加密，
     * 同一明文每次加密都产生不同密文（随机种子），用密文比较会在"重存了同一个 key"时误判为变更。
     * <p>日志区分两种情形（原先都打成"重建"，启动首建那条会被误读成"有人改了配置"）：
     * <ul>
     *   <li><b>就绪</b>：进程内首次构建（client == null），打印生效配置；</li>
     *   <li><b>重建</b>：配置确实变了，附具体变更项的旧值 → 新值，API Key 按 {@code ****后4位} 掩码。</li>
     * </ul>
     */
    private RestClient client() {
        RestClient current = client;
        if (current != null) {
            // 快路径：配置未变时不进同步块（client() 每次检索 / 每次写索引都会走）
            String url = baseUrl();
            int t = timeoutMillis();
            String key = apiKeyOrEmpty();
            if (url.equals(clientBaseUrl) && t == clientTimeout && key.equals(clientApiKey)) {
                return current;
            }
        }
        build();
        return client;
    }

    /** 重建（或首次构建）客户端：双重检查，并发下只有一个线程真正构建 */
    private void build() {
        synchronized (this) {
            String oldUrl = clientBaseUrl;
            int oldT = clientTimeout;
            String oldKey = clientApiKey;
            boolean first = client == null;
            String url = baseUrl();
            int t = timeoutMillis();
            String key = apiKeyOrEmpty();
            if (!first && url.equals(oldUrl) && t == oldT && key.equals(oldKey)) {
                return; // 等待锁期间已被按同一份配置重建
            }
            // 变更明细：只列出真正变化的项，便于判断是改了地址/超时还是换了 Key
            List<String> changes = List.of();
            if (!first) {
                List<String> items = new ArrayList<>(3);
                if (!url.equals(oldUrl)) items.add("地址 " + oldUrl + " → " + url);
                if (t != oldT) items.add("超时 " + oldT + "ms → " + t + "ms");
                if (!key.equals(oldKey)) items.add("Key " + maskSecret(oldKey) + " → " + maskSecret(key));
                changes = items;
            }
            // 注意：不能用 SimpleClientHttpRequestFactory（HttpURLConnection 不支持 PATCH，settings 校准会抛
            // ProtocolException: Invalid HTTP method: PATCH）；改用 JdkClientHttpRequestFactory（java.net.http.HttpClient）。
            // 其连接超时只能在构造 HttpClient 时配置，读超时用 setReadTimeout(Duration)
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(2000))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(t));
            RestClient.Builder builder = RestClient.builder().baseUrl(url).requestFactory(factory);
            if (!key.isBlank()) {
                builder.defaultHeader("Authorization", "Bearer " + key);
            }
            client = builder.build();
            clientBaseUrl = url;
            clientTimeout = t;
            clientApiKey = key;
            supportChecked.set(false);
            available = false;
            indexReady = false;
            if (first) {
                log.info("[Keyword] Meilisearch 客户端就绪: {}，索引 {}，超时 {}ms，Key {}", url, index(), t, maskSecret(key));
            } else {
                log.info("[Keyword] Meilisearch 配置变更，客户端已重建: {}（变更: {}）", url, String.join("；", changes));
            }
        }
    }

    /** API Key（null 归一为空串） */
    private String apiKeyOrEmpty() {
        String k = apiKey();
        return k == null ? "" : k;
    }

    /** 密钥展示掩码（与 ConfigService 快照同一口径：****后4位） */
    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) return "未配置";
        if (secret.length() <= 4) return "****";
        return "****" + secret.substring(secret.length() - 4);
    }

    // ==================== 可用性探测 ====================

    /**
     * 引擎是否可用于本次检索：未启用直接 false；探测结果缓存，失败冷却期内不重试
     */
    public boolean isAvailable() {
        if (!enabled()) return false;
        return checkSupport();
    }

    private boolean checkSupport() {
        if (supportChecked.get()) {
            if (available) return true;
            if (System.currentTimeMillis() - lastFailTs < failCooldownMs()) return false;
            supportChecked.set(false); // 冷却结束，允许重新探测
        }
        synchronized (this) {
            if (supportChecked.get()) return available;
            try {
                // 探测受保护端点 /indexes（/health 是公开端点不校验 key）：
                // 服务端设了 master key 而客户端未配/配错 → 401 → 这里即失败，避免"探测通过但实际 401 降级"
                String resp = client().get().uri("/indexes").retrieve().body(String.class);
                available = resp != null;
                if (!available) {
                    lastFailTs = System.currentTimeMillis();
                    log.warn("[Keyword] /indexes 无有效响应，Meilisearch 不可用（关键词路降级 MySQL）");
                }
            } catch (Exception e) {
                available = false;
                lastFailTs = System.currentTimeMillis();
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean authIssue = msg.contains("401") || msg.contains("403")
                        || msg.contains("Unauthorized") || msg.contains("invalid_api_key");
                log.warn("[Keyword] Meilisearch 探测失败（关键词路降级 MySQL）baseUrl={}: {}{}",
                        baseUrl(), msg, authIssue ? "（疑似 master key 未配置或与服务端不一致，请在设置页配置 Meilisearch Key）" : "");
            }
            supportChecked.set(true);
            return available;
        }
    }

    /** 强制探测（绕过缓存，供设置页切换引擎前校验） */
    public boolean checkAvailable() {
        supportChecked.set(false);
        available = false;
        boolean ok = checkSupport();
        log.info("[Keyword] 强制探测结果: {}", ok);
        return ok;
    }

    /** 不可用原因（可用时返回 null），供检索调试与运维接口展示 */
    public String debugUnavailableReason() {
        if (!enabled()) return "未启用（keyword.engine=" + engine() + "）";
        if (supportChecked.get() && !available) {
            long left = failCooldownMs() - (System.currentTimeMillis() - lastFailTs);
            return left > 0
                    ? "服务不可用，冷却中（" + (left / 1000) + "s 后重试）"
                    : "服务探测失败：" + baseUrl() + "（若服务端已设置 master key，请检查设置页的 Meilisearch Key 是否一致）";
        }
        return null;
    }

    /** 惰性创建索引并设置可搜索/可过滤字段（幂等；失败仅告警） */
    private void ensureIndex() {
        if (indexReady) return;
        synchronized (this) {
            if (indexReady) return;
            try {
                // 创建索引（已存在时 Meilisearch 返回 index_already_exists，忽略）
                try {
                    client().post().uri("/indexes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("uid", index(), "primaryKey", "id"))
                            .retrieve().body(String.class);
                } catch (Exception ignored) {
                    // 已存在或并发创建，交由下面的 settings 校准
                }
                // 可搜索字段按优先级排序（title > titlePath > content）；可过滤：docId（按文档删除用）
                // + id（精确对账按主键批量拉回指纹用——POST /documents/fetch 的 filter 主键同样须先登记，
                // Meilisearch 不默认放行主键过滤；settings 为异步任务，落地前首轮对账自动降级旧算法）
                client().patch().uri("/indexes/" + index() + "/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "searchableAttributes", List.of("title", "titlePath", "content"),
                                "filterableAttributes", List.of("docId", "id")))
                        .retrieve().body(String.class);
                indexReady = true;
                log.info("[Keyword] 索引 {} 初始化完成（searchable: title/titlePath/content, filterable: docId/id）", index());
            } catch (Exception e) {
                log.warn("[Keyword] 索引初始化失败: {}", e.getMessage());
            }
        }
    }

    // ==================== 检索 ====================

    /** 关键词召回结果：知识块 id + 相关度分（Meilisearch _rankingScore，0~1） */
    public record ScoredId(String id, double score) {
    }

    /**
     * 关键词召回：返回按相关度降序的知识块 id + 分数；失败返回空列表（调用方降级）
     */
    public List<ScoredId> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        if (!isAvailable()) return List.of();
        ensureIndex();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", query);
            body.put("limit", limit);
            body.put("showRankingScore", true);
            body.put("attributesToRetrieve", List.of("id"));
            String resp = client().post().uri("/indexes/" + index() + "/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class);
            if (resp == null) return List.of();
            JSONArray hits = JSON.parseObject(resp).getJSONArray("hits");
            if (hits == null || hits.isEmpty()) return List.of();
            List<ScoredId> out = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                JSONObject h = hits.getJSONObject(i);
                String id = h.getString("id");
                if (id == null || id.isBlank()) continue;
                Double s = h.getDouble("_rankingScore");
                out.add(new ScoredId(id, s == null ? 0 : s));
            }
            return out;
        } catch (Exception e) {
            markFailed("检索", e);
            return List.of();
        }
    }

    // ==================== 索引写入（best-effort，失败只告警） ====================

    /** upsert 知识块（按 primaryKey=id 覆盖） */
    public void indexChunks(List<Knowledge> blocks) {
        if (!enabled() || blocks == null || blocks.isEmpty()) return;
        if (!isAvailable()) return;
        ensureIndex();
        try {
            List<Map<String, Object>> docs = new ArrayList<>(blocks.size());
            for (Knowledge k : blocks) {
                if (k == null || k.getId() == null) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", k.getId());
                d.put("docId", k.getDocId() == null ? "" : k.getDocId());
                d.put("title", k.getTitle() == null ? "" : k.getTitle());
                d.put("titlePath", k.getTitlePath() == null ? "" : k.getTitlePath());
                d.put("content", k.getContent() == null ? "" : k.getContent());
                // 对账指纹（不入 searchableAttributes，不影响检索）：精确对账按它识别"数量相同但内容过期"的漂移
                d.put("contentHash", k.getContentHash() == null ? "" : k.getContentHash());
                docs.add(d);
            }
            if (docs.isEmpty()) return;
            client().post().uri("/indexes/" + index() + "/documents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(docs)
                    .retrieve().body(String.class);
            log.debug("[Keyword] 索引写入 {} 块", docs.size());
        } catch (Exception e) {
            markWriteFailed("索引写入", e);
        }
    }

    /** 按知识块 id 批量删除 */
    public void deleteChunks(List<String> ids) {
        if (!enabled() || ids == null || ids.isEmpty()) return;
        if (!isAvailable()) return;
        try {
            client().post().uri("/indexes/" + index() + "/documents/delete-batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ids)
                    .retrieve().body(String.class);
            log.debug("[Keyword] 索引删除 {} 块", ids.size());
        } catch (Exception e) {
            markWriteFailed("索引删除", e);
        }
    }

    /** 按文档删除该文档下所有块（filter=docId） */
    public void deleteByDoc(String docId) {
        if (!enabled() || docId == null || docId.isBlank()) return;
        if (!isAvailable()) return;
        ensureIndex();
        try {
            client().post().uri("/indexes/" + index() + "/documents/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", "docId = \"" + docId + "\""))
                    .retrieve().body(String.class);
            log.debug("[Keyword] 索引删除文档 {} 的全部块", docId);
        } catch (Exception e) {
            markWriteFailed("按文档删除索引", e);
        }
    }

    /** 清空索引全部文档（运维：重建前使用） */
    public void clearAll() {
        if (!enabled()) return;
        if (!isAvailable()) return;
        try {
            client().delete().uri("/indexes/" + index() + "/documents").retrieve().body(String.class);
            log.info("[Keyword] 索引已清空");
        } catch (Exception e) {
            markWriteFailed("清空索引", e);
        }
    }

    /**
     * 全量重建索引：分批（每批 1000）从 MySQL 读取 push，避免一次性载入全部块。
     * 首次切换引擎、索引漂移后使用；切换引擎保存配置时自动触发，也可手动调 /reindex。
     * 防并发重复（AtomicBoolean），线程池后台执行不阻塞调用方。
     */
    public void reindexAll() {
        if (!enabled()) {
            log.info("[Reindex] 关键词引擎未启用 Meilisearch（keyword.engine=mysql），跳过全量重建");
            return;
        }
        // 先确保索引存在（首次切换/索引被删时 404 index_not_found 会误判为服务不可用；ensureIndex 幂等，失败仅告警）
        ensureIndex();
        if (!isAvailable()) {
            log.warn("[Reindex] Meilisearch 服务不可用，跳过全量重建");
            return;
        }
        if (!reindexing.compareAndSet(false, true)) {
            log.info("[Reindex] 全量重建进行中，跳过本次触发");
            return;
        }
        ThreadPoolManager.execute(() -> {
            long total = 0;
            try {
                String lastId = "";
                while (true) {
                    // 只灌有效块（status=0 文档的块 + 手动块 docId IS NULL，与对账同口径），使"索引集合 = 有效块集合"闭环：
                    // 弃用/失败文档的块不入索引，对账（indexedCount vs 有效块数）不会因残留而每次重建
                    List<Knowledge> batch = knowledgeMapper.selectList(
                            effectiveChunkQuery()
                                    .gt(Knowledge::getId, lastId) // UUID 字符串升序游标
                                    .orderByAsc(Knowledge::getId)
                                    .last("LIMIT 1000"));
                    if (batch.isEmpty()) break;
                    indexChunks(batch);
                    lastId = batch.get(batch.size() - 1).getId();
                    total += batch.size();
                    log.info("[Reindex] 已重建 {} 块", total);
                    if (batch.size() < 1000) break;
                }
                log.info("[Reindex] 全量重建完成，共 {} 块", total);
            } catch (Exception e) {
                log.error("[Reindex] 全量重建失败（已完成 {} 块）: {}", total, e.getMessage());
            } finally {
                reindexing.set(false);
            }
        });
    }

    /**
     * 精确对账：按 (id, contentHash) 逐块比对 MySQL 有效块集合与索引文档集合，只定向修复差异块——
     * 相比计数对账能发现"数量相同但内容过期"的漂移（如 upsert 失败后索引残留旧内容）。
     * 缺失/过期块补写（差异过大降级全量重建），多余块删除（已删块/已删文档/弃用文档的块）。
     * 首轮会给存量文档补写 contentHash 字段（索引缺该字段判为过期，upsert 一次自愈，无需手动重建）。
     * <p>
     * 规模化设计（数据量随用户增长，不允许 O(N) 内存与 O(N²) 请求成为常态）：
     * <ul>
     *   <li><b>缺失/过期</b>：MySQL 有效块按 id 游标分批（1000/批），按主键过滤批量拉回索引指纹
     *       （POST /documents/fetch，filter: id IN [...]，需 filterableAttributes 含 id，ensureIndex 已写入），
     *       内存只保留当前批与差异清单；差异超过 max(1000, 有效块/3) 提前转全量重建；</li>
     *   <li><b>多余（孤儿）</b>：先用 O(1) 的索引计数（/stats）与有效块计数做差值门控——
     *       孤儿数 = 索引数 − 有效块数 + 缺失数，仅证明存在孤儿才全量拉取索引 id 流式反查 MySQL（罕见分支）；</li>
     *   <li><b>多副本安全</b>：MySQL 命名锁（GET_LOCK）互斥，其他实例持锁时跳过本轮；锁能力不可用
     *       （外部库不支持等）退化为单机防重继续——对账幂等，不能因锁缺失而停摆。</li>
     * </ul>
     * 按主键拉取不可用（旧版 Meilisearch 不支持 documents 过滤、settings 异步任务未落地等）时整轮
     * 降级旧算法 {@link #legacyReconcile()}（两侧全量拉取比对，内存 O(N)），老版本行为不回归。
     * 调度（启动首轮 + 周期间隔，见 keyword.reconcileOnStartup / keyword.reconcileIntervalMs）
     * 由 schedule 包 ScheduleCenter 驱动；失败只告警不抛，下轮重试。
     */
    public void reconcile() {
        if (!enabled()) return;
        if (!isAvailable()) {
            log.info("[Reconcile] Meilisearch 不可用，跳过本轮对账");
            return;
        }
        if (reindexing.get()) {
            log.info("[Reconcile] 全量重建进行中，跳过本轮对账");
            return;
        }
        if (!reconciling.compareAndSet(false, true)) return;
        ensureIndex(); // 索引/settings 未就绪先校准（幂等）；否则按主键过滤拉取 404/不可过滤
        try (ReconcileLock lock = ReconcileLock.tryAcquire(dataSource, RECONCILE_LOCK)) {
            if (lock.busy()) {
                log.info("[Reconcile] 其他实例正在对账（分布式锁占用），跳过本轮");
                return;
            }
            doReconcile();
        } catch (Exception e) {
            log.warn("[Reconcile] 对账失败（下轮重试）: {}", e.getMessage());
        } finally {
            reconciling.set(false);
        }
    }

    /** 精确对账主流程（已持对账锁与单机 CAS） */
    private void doReconcile() {
        long effectiveCount = countEffectiveChunks();
        long indexedCount = indexedCount(); // /stats O(1)；-1=拉取失败（本轮顺延孤儿核对）
        if (effectiveCount == 0 && indexedCount == 0) {
            log.info("[Reconcile] 关键词索引一致（有效块 0 = 索引 0），无需修复");
            return;
        }

        // Pass 1：缺失/过期——MySQL 有效块分批，按主键批量拉回索引指纹逐块比对
        long driftLimit = Math.max(1000, effectiveCount / 3); // 差异超过该值转全量重建（与全量重灌划算的阈值一致）
        List<String> stale = new ArrayList<>();
        long missing = 0;
        long mismatch = 0;
        String lastId = "";
        while (true) {
            List<Knowledge> batch = knowledgeMapper.selectList(
                    effectiveChunkQuery()
                            .select(Knowledge::getId, Knowledge::getContentHash)
                            .gt(Knowledge::getId, lastId) // UUID 字符串升序游标
                            .orderByAsc(Knowledge::getId)
                            .last("LIMIT 1000"));
            if (batch.isEmpty()) break;
            lastId = batch.get(batch.size() - 1).getId();
            List<String> batchIds = new ArrayList<>(batch.size());
            for (Knowledge k : batch) batchIds.add(k.getId());
            Map<String, String> idx = fetchHashesByIds(batchIds);
            if (idx == null) {
                log.info("[Reconcile] 按主键批量拉取不可用，本轮降级旧算法（两侧全量拉取比对）");
                legacyReconcile();
                return;
            }
            for (Knowledge k : batch) {
                String mysqlHash = k.getContentHash() == null ? "" : k.getContentHash();
                String idxHash = idx.get(k.getId());
                if (idxHash == null) { // 索引缺失
                    missing++;
                    stale.add(k.getId());
                } else if (!mysqlHash.isEmpty() && !mysqlHash.equals(idxHash)) { // 内容过期（存量空 hash 只比存在性，重解析后自然收敛）
                    mismatch++;
                    stale.add(k.getId());
                }
            }
            if (stale.size() > driftLimit) {
                log.info("[Reconcile] 差异过大（缺失/过期 {} 块，有效 {}），转全量重建", stale.size(), effectiveCount);
                reindexAll();
                return;
            }
            if (batch.size() < 1000) break;
        }

        // Pass 2：多余（孤儿）——计数差值门控（o = 索引数 − 有效块数 + 缺失数），仅证明存在才扫描。
        // Meili 写入是异步任务，stats 计数与逐块核对间有短暂口径差：差值>0 但扫描为空时以扫描为准
        List<String> orphans = new ArrayList<>();
        if (indexedCount - effectiveCount + missing > 0) {
            List<String> found = findOrphans();
            if (found != null) orphans = found;
        }

        if (stale.isEmpty() && orphans.isEmpty()) {
            log.info("[Reconcile] 关键词索引一致（有效块 {}，索引 {}，逐块核对无差异），无需修复",
                    effectiveCount, indexedCount < 0 ? "计数未知" : indexedCount);
            return;
        }
        log.warn("[Reconcile] 索引漂移：缺失 {}、内容过期 {}、多余 {}（有效 {}，索引 {}）→ 定向修复",
                missing, mismatch, orphans.size(), effectiveCount, indexedCount);
        for (int i = 0; i < orphans.size(); i += 1000) {
            deleteChunks(orphans.subList(i, Math.min(i + 1000, orphans.size())));
        }
        for (int i = 0; i < stale.size(); i += 1000) {
            indexChunks(knowledgeMapper.selectBatchIds(stale.subList(i, Math.min(i + 1000, stale.size()))));
        }
        log.info("[Reconcile] 修复完成：补写 {} 块、删除 {} 块", stale.size(), orphans.size());
    }

    /**
     * 旧对账算法（降级保留）：两侧全量拉取构建 id→hash 映射后双向比对，内存 O(N)、
     * Meili 侧 offset 深分页——仅在不支持按主键过滤拉取的旧版 Meilisearch / settings 未落地时整轮退到此路。
     */
    private void legacyReconcile() {
        Map<String, String> effective = loadEffectiveHashes();
        Map<String, String> indexed = loadIndexedHashes();
        if (effective == null || indexed == null) return; // 读侧失败，下轮重试

        List<String> stale = new ArrayList<>(); // 缺失或内容过期的块 id → 补写
        for (Map.Entry<String, String> e : effective.entrySet()) {
            String idxHash = indexed.get(e.getKey());
            // MySQL 侧 hash 为空（存量老块）只做存在性比较，避免每轮误判重灌；重解析后块带 hash 自然收敛
            if (idxHash == null || (!e.getValue().isEmpty() && !e.getValue().equals(idxHash))) {
                stale.add(e.getKey());
            }
        }
        List<String> extra = new ArrayList<>(); // 索引多余块 id（已删块/已删文档/弃用文档的块）→ 删除
        for (String id : indexed.keySet()) {
            if (!effective.containsKey(id)) extra.add(id);
        }

        if (stale.isEmpty() && extra.isEmpty()) {
            log.info("[Reconcile] 关键词索引一致（有效块 {} = 索引 {}），无需修复", effective.size(), indexed.size());
            return;
        }
        log.warn("[Reconcile] 索引漂移：缺失/过期 {} 块、多余 {} 块（有效 {}，索引 {}）→ 定向修复",
                stale.size(), extra.size(), effective.size(), indexed.size());
        for (int i = 0; i < extra.size(); i += 1000) {
            deleteChunks(extra.subList(i, Math.min(i + 1000, extra.size())));
        }
        if (stale.size() > Math.max(1000, effective.size() / 3)) {
            // 差异过大（首轮 hash 补齐 / 长期漂移）：逐块补写不如全量重灌划算
            log.info("[Reconcile] 差异过大（{} 块），转全量重建", stale.size());
            reindexAll();
        } else {
            for (int i = 0; i < stale.size(); i += 1000) {
                List<Knowledge> rows = knowledgeMapper.selectBatchIds(
                        stale.subList(i, Math.min(i + 1000, stale.size())));
                indexChunks(rows);
            }
        }
        log.info("[Reconcile] 修复完成：补写 {} 块、删除 {} 块", stale.size(), extra.size());
    }

    /** 有效块查询条件（全量重建/对账同口径）：手动块（docId IS NULL）+ 启用且未删文档的块 */
    private LambdaQueryWrapper<Knowledge> effectiveChunkQuery() {
        return new LambdaQueryWrapper<Knowledge>()
                .and(w -> w.isNull(Knowledge::getDocId)
                        .or().inSql(Knowledge::getDocId,
                                "SELECT id FROM c_ai_document WHERE status=0 AND deleted=0"));
    }

    /** 有效块计数（对账孤儿门控用，单条 COUNT） */
    private long countEffectiveChunks() {
        Long n = knowledgeMapper.selectCount(effectiveChunkQuery());
        return n == null ? 0 : n;
    }

    /** MySQL 有效块 id → contentHash（status=0 文档的块 + 手动块 docId IS NULL；扫描失败返回 null） */
    private Map<String, String> loadEffectiveHashes() {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            String lastId = "";
            while (true) {
                List<Knowledge> batch = knowledgeMapper.selectList(
                        effectiveChunkQuery()
                                .select(Knowledge::getId, Knowledge::getContentHash)
                                .gt(Knowledge::getId, lastId)
                                .orderByAsc(Knowledge::getId)
                                .last("LIMIT 1000"));
                if (batch.isEmpty()) break;
                for (Knowledge k : batch) {
                    map.put(k.getId(), k.getContentHash() == null ? "" : k.getContentHash());
                }
                lastId = batch.get(batch.size() - 1).getId();
                if (batch.size() < 1000) break;
            }
            return map;
        } catch (Exception e) {
            log.warn("[Reconcile] 有效块扫描失败: {}", e.getMessage());
            return null;
        }
    }

    /** 索引文档 id → contentHash（分页拉取全部；服务异常返回 null；索引不存在视为空集合） */
    private Map<String, String> loadIndexedHashes() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            int offset = 0;
            while (true) {
                final int off = offset;
                String resp = client().get()
                        .uri(b -> b.path("/indexes/" + index() + "/documents")
                                .queryParam("fields", "id,contentHash")
                                .queryParam("limit", 1000)
                                .queryParam("offset", off)
                                .build())
                        .retrieve().body(String.class);
                // Meilisearch v1：GET /documents 返回 {"results":[...], "offset":.., "limit":.., "total":..}（对象包装），
                // 旧版裸数组格式已废弃——按 results 字段解析，否则 JSON 解析必失败（illegal input）
                JSONObject body = resp == null ? null : JSON.parseObject(resp);
                JSONArray docs = body == null ? null : body.getJSONArray("results");
                if (docs == null || docs.isEmpty()) break;
                for (int i = 0; i < docs.size(); i++) {
                    JSONObject d = docs.getJSONObject(i);
                    String id = d.getString("id");
                    if (id != null && !id.isBlank()) {
                        String h = d.getString("contentHash");
                        map.put(id, h == null ? "" : h);
                    }
                }
                offset += docs.size();
            }
            return map;
        } catch (Exception e) {
            // 索引尚未创建（404 index_not_found）= 空集合：全部判缺失走全量重建，不是服务故障
            if (e.getMessage() != null && e.getMessage().contains("index_not_found")) return map;
            markFailed("对账拉取索引文档", e);
            return null;
        }
    }

    /**
     * 按主键批量拉回索引指纹（POST /documents/fetch，filter: id IN [...]；需 filterableAttributes 含 id，
     * ensureIndex 已写入）。只保留本批指纹，内存 O(批大小)。
     * 失败返回 null，调用方整轮降级 {@link #legacyReconcile()}（兼容不支持 documents 过滤的旧版 Meilisearch）。
     */
    private Map<String, String> fetchHashesByIds(List<String> ids) {
        try {
            StringBuilder filter = new StringBuilder("id IN [");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) filter.append(',');
                // 块 id 为 MyBatis-Plus 生成的 UUID hex，不含引号，无需转义
                filter.append('"').append(ids.get(i)).append('"');
            }
            filter.append(']');
            String resp = client().post().uri("/indexes/" + index() + "/documents/fetch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("filter", filter.toString(), "limit", ids.size(),
                            "fields", List.of("id", "contentHash")))
                    .retrieve().body(String.class);
            JSONObject body = resp == null ? null : JSON.parseObject(resp);
            JSONArray docs = body == null ? null : body.getJSONArray("results");
            Map<String, String> map = new HashMap<>();
            if (docs != null) {
                for (int i = 0; i < docs.size(); i++) {
                    JSONObject d = docs.getJSONObject(i);
                    String id = d.getString("id");
                    if (id != null && !id.isBlank()) {
                        String h = d.getString("contentHash");
                        map.put(id, h == null ? "" : h);
                    }
                }
            }
            return map;
        } catch (Exception e) {
            log.info("[Reconcile] 按主键拉取索引指纹失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 孤儿扫描（仅计数差值证明存在多余块时触发）：offset 分页拉索引文档 id，按批反查 MySQL 有效块，
     * 差集即孤儿——只保留批与孤儿清单，不构建两侧全量映射。失败返回 null（本轮跳过删除，下轮重试）。
     */
    private List<String> findOrphans() {
        try {
            List<String> orphans = new ArrayList<>();
            int offset = 0;
            while (true) {
                final int off = offset;
                String resp = client().get()
                        .uri(b -> b.path("/indexes/" + index() + "/documents")
                                .queryParam("fields", "id")
                                .queryParam("limit", 1000)
                                .queryParam("offset", off)
                                .build())
                        .retrieve().body(String.class);
                JSONObject body = resp == null ? null : JSON.parseObject(resp);
                JSONArray docs = body == null ? null : body.getJSONArray("results");
                if (docs == null || docs.isEmpty()) break;
                List<String> pageIds = new ArrayList<>(docs.size());
                for (int i = 0; i < docs.size(); i++) {
                    String id = docs.getJSONObject(i).getString("id");
                    if (id != null && !id.isBlank()) pageIds.add(id);
                }
                if (!pageIds.isEmpty()) {
                    Set<String> effectiveIds = new HashSet<>();
                    for (Knowledge k : knowledgeMapper.selectList(
                            effectiveChunkQuery().select(Knowledge::getId).in(Knowledge::getId, pageIds))) {
                        effectiveIds.add(k.getId());
                    }
                    for (String id : pageIds) {
                        if (!effectiveIds.contains(id)) orphans.add(id);
                    }
                }
                offset += docs.size();
                if (docs.size() < 1000) break;
            }
            return orphans;
        } catch (Exception e) {
            log.warn("[Reconcile] 孤儿扫描失败（本轮跳过删除，下轮重试）: {}", e.getMessage());
            return null;
        }
    }

    /** 索引内文档数（探测失败或未启用返回 -1；索引尚未创建返回 0） */
    public long indexedCount() {
        if (!isAvailable()) return -1;
        try {
            String resp = client().get().uri("/indexes/" + index() + "/stats").retrieve().body(String.class);
            if (resp == null) return -1;
            Long n = JSON.parseObject(resp).getLong("numberOfDocuments");
            return n == null ? -1 : n;
        } catch (Exception e) {
            // 索引尚未创建（404 index_not_found）= 0 文档，不是服务故障：不触发冷却，
            // 由 ensureIndex/reindexAll 自动创建索引后重建
            if (e.getMessage() != null && e.getMessage().contains("index_not_found")) {
                return 0;
            }
            markFailed("查询索引统计", e);
            return -1;
        }
    }

    /** 调用失败：标记不可用并进入冷却（与重排服务一致，避免每请求都撞） */
    private void markFailed(String action, Exception e) {
        available = false;
        supportChecked.set(true);
        lastFailTs = System.currentTimeMillis();
        log.warn("[Keyword] Meilisearch {}失败（{}s 后自动重试）: {}", action, failCooldownMs() / 1000, e.getMessage());
    }

    /** 写操作失败：计数 + 冷却（M12 fail-loud：写失败可经 /search-index/stats 观察，不止日志告警） */
    private void markWriteFailed(String action, Exception e) {
        writeFailCount.incrementAndGet();
        markFailed(action, e);
    }

    /** 写失败累计次数（stats 接口展示用） */
    public long writeFailCount() {
        return writeFailCount.get();
    }

    /**
     * 对账互斥锁（MySQL 命名锁 GET_LOCK，探测超时 0=立即返回）：锁随连接会话生效，
     * 持锁连接须保持打开直到 {@link #close()} 先 RELEASE_LOCK 再归还。
     * <ul>
     *   <li>持锁成功：connection 非空且 busy=false；</li>
     *   <li>其他实例持锁：busy=true，调用方跳过本轮；</li>
     *   <li>锁能力不可用（外部库不支持 GET_LOCK / 连接异常）：connection=null 且 busy=false，
     *       退化为单机防重继续——对账幂等，重复执行无害，不能因锁能力缺失停摆对账。</li>
     * </ul>
     */
    private record ReconcileLock(Connection connection, String name, boolean busy) implements AutoCloseable {

        static ReconcileLock tryAcquire(DataSource dataSource, String name) {
            try {
                Connection c = dataSource.getConnection();
                try {
                    try (PreparedStatement ps = c.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                        ps.setString(1, name);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next() && rs.getInt(1) == 1) return new ReconcileLock(c, name, false);
                        }
                    }
                } catch (Exception e) {
                    try { c.close(); } catch (Exception ignored) { }
                    log.warn("[Reconcile] 分布式锁探测失败（{}），退化为单机防重继续", e.getMessage());
                    return new ReconcileLock(null, name, false);
                }
                try { c.close(); } catch (Exception ignored) { }
                return new ReconcileLock(null, name, true);
            } catch (Exception e) {
                log.warn("[Reconcile] 分布式锁探测失败（{}），退化为单机防重继续", e.getMessage());
                return new ReconcileLock(null, name, false);
            }
        }

        @Override
        public void close() {
            if (connection == null) return;
            try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                ps.setString(1, name);
                ps.executeQuery();
            } catch (Exception ignored) {
                // 释放失败仅告警意义不大：连接关闭后 MySQL 会话锁随之释放
            }
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
    }
}
