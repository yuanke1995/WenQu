package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigKeys;

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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * - 写索引全部 best-effort（失败只告警不抛）；索引漂移不做自动对账，由运维在「设置 → 定时任务 →
 *   索引与健康」看计数差，再用切换引擎保存或 /api/ai/search-index/reindex 全量重建对齐
 */
@Slf4j
@Service
public class KeywordIndexService {

    private final AppProperties properties;
    private final ConfigService configService;
    private final KnowledgeMapper knowledgeMapper;

    private final AtomicBoolean supportChecked = new AtomicBoolean(false);
    private volatile boolean available = false;
    private volatile long lastFailTs = 0L;
    /** 索引与 settings 是否已初始化（每次客户端重建后重新初始化） */
    private volatile boolean indexReady = false;

    /** 全量重建进行中标志（防并发重复重建） */
    private final AtomicBoolean reindexing = new AtomicBoolean(false);

    /** 索引写失败累计计数（M12 fail-loud：/search-index/stats 暴露，运维可见漂移之外的写故障） */
    private final AtomicLong writeFailCount = new AtomicLong();

    private volatile RestClient client;
    private volatile String clientBaseUrl = "";
    private volatile String clientApiKey = "";
    private volatile int clientTimeout = 0;

    public KeywordIndexService(AppProperties properties, ConfigService configService,
                               KnowledgeMapper knowledgeMapper) {
        this.properties = properties;
        this.configService = configService;
        this.knowledgeMapper = knowledgeMapper;
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
        int t = configService.getInt(ConfigKeys.KEYWORD_TIMEOUT_MILLIS, properties.getKeyword().getTimeoutMillis());
        return t > 0 ? t : 1000;
    }

    private long failCooldownMs() {
        return configService.getInt(ConfigKeys.KEYWORD_FAIL_COOLDOWN_MS, 60_000);
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

    /**
     * 按「即将保存的值」强制探测（设置页切换引擎时用），<b>不读也不改已保存配置</b>。
     * <p>
     * 为什么不能用 {@link #checkAvailable()}：它探测的是**库里的旧地址/旧 Key**。而设置页的
     * 「引擎服务地址 / 引擎 Key」只有选中 meilisearch 后才可见（schema 的 vif 联动），用户正在
     * 配的正是这两个值——用旧值探测等于「拿上一次的地址去验这一次的新地址」，首次配置必然失败，
     * 失败后前端又把引擎静默改回 mysql，字段随之消失，用户永远配不成（死锁）。
     * <p>
     * 这里用传入值构造一次性探针：{@code baseUrl} 为空才回落已保存配置，{@code apiKey} 为空
     * 或仍是 {@code ****} 掩码（前端未修改时原样回传）同样回落已保存配置——与保存流程的
     * 掩码回写保护同一口径。
     *
     * @param baseUrl 表单当前服务地址（空/空白则用已保存值）
     * @param apiKey  表单当前 master key（空或 **** 掩码则用已保存值）
     * @return 服务是否可用（在线且密钥被接受）
     */
    public boolean checkAvailable(String baseUrl, String apiKey) {
        String url = baseUrl == null || baseUrl.isBlank() ? baseUrl() : baseUrl.trim();
        String key = apiKey == null || apiKey.isBlank() || apiKey.startsWith("****") ? apiKey() : apiKey.trim();
        if (url.isBlank()) {
            log.info("[Keyword] 强制探测（表单值）结果: false（服务地址为空）");
            return false;
        }
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(2000))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(timeoutMillis()));
            RestClient.Builder builder = RestClient.builder().baseUrl(url).requestFactory(factory);
            if (!key.isBlank()) {
                builder.defaultHeader("Authorization", "Bearer " + key);
            }
            // 与 checkSupport 同口径：探 /indexes（受保护端点）而非公开的 /health，
            // 否则「服务端设了 master key、客户端没配对」会探成可用，实际调用全 401
            String resp = builder.build().get().uri("/indexes").retrieve().body(String.class);
            boolean ok = resp != null;
            log.info("[Keyword] 强制探测（表单值 地址={} Key={}）结果: {}", url, maskSecret(key), ok);
            return ok;
        } catch (Exception e) {
            log.warn("[Keyword] 强制探测（表单值 地址={}）失败: {} {}", url, e.getMessage(), rootMessage(e));
            return false;
        }
    }

    /** 沿异常链取根因消息（Spring 包装异常时 getMessage 常只有状态码，真实原因在 cause 里） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
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
                client().patch().uri("/indexes/" + index() + "/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "searchableAttributes", List.of("title", "titlePath", "content"),
                                "filterableAttributes", List.of("docId")))
                        .retrieve().body(String.class);
                indexReady = true;
                log.info("[Keyword] 索引 {} 初始化完成（searchable: title/titlePath/content, filterable: docId）", index());
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
        // 提交被拒（队列满/池关闭）时 lambda 永不执行，finally 里的复位够不着——reindexing 永久
        // true，之后重建全部静默跳过（自愈机制失效，直到重启）。拒了就地复位。
        if (!ThreadPoolManager.execute(() -> {
            long total = 0;
            try {
                String lastId = "";
                while (true) {
                    // 只灌有效块（status=0 文档的块 + 手动块 docId IS NULL），使"索引集合 = 有效块集合"闭环：
                    // 弃用/失败文档的块不入索引，健康卡的计数差（indexedCount vs 有效块数）不会因残留而常亮
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
        })) {
            reindexing.set(false);
            log.error("[Reindex] 提交线程池被拒（队列满/池已关闭），全量重建未执行");
        }
    }

    /** 有效块查询条件（全量重建同口径）：手动块（docId IS NULL）+ 启用且未删文档的块 */
    private LambdaQueryWrapper<Knowledge> effectiveChunkQuery() {
        return new LambdaQueryWrapper<Knowledge>()
                .and(w -> w.isNull(Knowledge::getDocId)
                        .or().inSql(Knowledge::getDocId,
                                "SELECT id FROM c_ai_document WHERE status=0 AND deleted=0"));
    }

    /** 有效块计数（面板索引漂移与知识库健康共用，单条 COUNT；与索引灌库同口径） */
    public long countEffectiveChunks() {
        Long n = knowledgeMapper.selectCount(effectiveChunkQuery());
        return n == null ? 0 : n;
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
}
