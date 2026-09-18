package com.wisesoft.wenqu.config;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.ConfigOption;
import com.wisesoft.wenqu.repository.port.ConfigOptionMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用配置项定义、持久化、校验和运行时解析。
 *
 * <p>由参考实现的 config/options.py 逐函数翻译：系统代码维护 {@code params}，
 * 管理员只修改 {@code value}。本模块只支持受控的基础字段，不提供任意动态组件或可执行协议；
 * OCR 只是第一批消费者。
 *
 * <p>必要替换：
 * <ul>
 *   <li>异步 Redis → {@link StringRedisTemplate}；缓存版本比较写入与失效的 Lua 脚本
 *       原样保留（{@link DefaultRedisScript}）。缓存键前缀替换为本产品命名空间
 *       （{@code wenqu:config_option:} / {@code wenqu:config_option_version:}）。
 *   <li>PostgreSQL 事务级建议锁 {@code pg_advisory_xact_lock(94721801)} →
 *       MySQL 命名锁 {@code GET_LOCK}/{@code RELEASE_LOCK}（沿用同一数值键）。
 *   <li>pydantic {@code HttpUrl} 校验 → {@link #normalizeHttpUrl}（覆盖常规 ASCII URL：
 *       http/https 强制、host 小写、无路径补 "/"；用户信息/punycode 等罕见形态不处理）。
 *   <li>ocr_engine 校验依赖 knowledge.parser.capabilities 的引擎清单（尚未照搬）→
 *       {@link #registerOcrEngineIds} 端口，缺省为空集（即除 {@code disable} 外全部拒绝，
 *       待 knowledge 模块照搬后注册真实清单）。
 *   <li>{@code Option.get(db)} 事务内读取 → {@link #getInTransaction}；无参缓存路径 →
 *       {@link #get(OptionDefinition)}。
 * </ul>
 */
@Slf4j
@Service
public class OptionsService {

    public static final String OPTION_CACHE_PREFIX = "wenqu:config_option:";
    public static final String OPTION_CACHE_VERSION_PREFIX = "wenqu:config_option_version:";
    public static final int OPTION_CACHE_TTL_SECONDS = 300;
    public static final String SYSTEM_OPTIONS_MIGRATION_VERSION_PARAM = "migration_version";

    private static final String SYNC_LOCK_NAME = "wenqu:config-options-sync:94721801";

    /** 由代码定义并持久化到数据库的管理员配置项（对应参考实现 Option dataclass）。 */
    @Getter
    public static final class OptionDefinition {

        private final String key;
        private final String name;
        private final String description;
        private final Map<String, Object> params;

        public OptionDefinition(String key, String name, String description, Map<String, Object> params) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.params = params;
        }

        /** 按数据库、环境变量、默认值顺序解析有效配置。 */
        public Map<String, Object> resolve(Map<String, Object> stored) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map<String, Object> field : getFields()) {
                String fieldKey = String.valueOf(field.get("key"));
                if (stored.containsKey(fieldKey) && "list[str]".equals(field.get("type"))) {
                    resolved.put(fieldKey, stored.get(fieldKey));
                    continue;
                }
                Object storedValue = stored.get(fieldKey);
                Object environmentValue =
                        field.get("environment") == null ? null : System.getenv(String.valueOf(field.get("environment")));
                resolved.put(
                        fieldKey,
                        firstPresent(storedValue, environmentValue, field.get("default")));
            }
            return resolved;
        }

        /** params.fields 字段定义清单。 */
        public List<Map<String, Object>> getFields() {
            Object fields = params.get("fields");
            if (!(fields instanceof List)) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : (List<?>) fields) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) item;
                    result.add(map);
                }
            }
            return result;
        }

        /** 含敏感字段的配置不走 Redis 缓存。 */
        public boolean isCacheable() {
            for (Map<String, Object> field : getFields()) {
                if (Boolean.TRUE.equals(field.get("sensitive"))) {
                    return false;
                }
            }
            return true;
        }

        private static Object firstPresent(Object... values) {
            // 参考实现 `stored_value or environment_value or default` 的 or 链语义（真值判断）
            for (Object value : values) {
                if (com.wisesoft.wenqu.common.QuestionUtils.truthy(value)) {
                    return value;
                }
            }
            return values.length == 0 ? null : values[values.length - 1];
        }
    }

    public static final OptionDefinition SYSTEM_OPTIONS = new OptionDefinition(
            "system_options",
            "系统配置",
            "API 与 worker 共用的管理员配置。",
            Map.of(
                    "internal", true,
                    "fields", List.of(
                            Map.of(
                                    "key", "default_model",
                                    "label", "默认对话模型",
                                    "type", "model",
                                    "default", "siliconflow-cn:deepseek-ai/DeepSeek-V4-Flash"),
                            Map.of(
                                    "key", "fast_model",
                                    "label", "快速响应模型",
                                    "type", "model",
                                    "default", "siliconflow-cn:deepseek-ai/DeepSeek-V4-Flash"),
                            Map.of(
                                    "key", "embed_model",
                                    "label", "默认 Embedding 模型",
                                    "type", "model",
                                    "default", "siliconflow-cn:Pro/BAAI/bge-m3"),
                            Map.of(
                                    "key", "reranker",
                                    "label", "默认 Re-Ranker 模型",
                                    "type", "model",
                                    "default", "siliconflow-cn:Pro/BAAI/bge-reranker-v2-m3"),
                            Map.of(
                                    "key", "default_ocr_engine",
                                    "label", "默认 OCR 解析引擎",
                                    "type", "ocr_engine",
                                    "default", "rapid_ocr"))));

    public static final OptionDefinition MINERU_OCR_HOST_OPTS = new OptionDefinition(
            "mineru_ocr_host_opts",
            "MinerU 服务",
            "配置自托管 MinerU 服务地址。",
            Map.of(
                    "fields", List.of(Map.of(
                            "key", "server_url",
                            "label", "服务地址",
                            "type", "url",
                            "environment", "MINERU_API_URI",
                            "placeholder", "http://mineru-api:30001",
                            "help", "留空时读取 MINERU_API_URI。"))));

    public static final OptionDefinition MINERU_OFFICIAL_API_OPTS = new OptionDefinition(
            "mineru_official_api_opts",
            "MinerU Official",
            "配置 MinerU 官方云服务凭证。",
            Map.of(
                    "fields", List.of(Map.of(
                            "key", "api_key",
                            "label", "API Key",
                            "type", "password",
                            "environment", "MINERU_API_KEY",
                            "sensitive", true,
                            "help", "留空时读取 MINERU_API_KEY，建议优先使用环境变量。"))));

    public static final OptionDefinition PP_STRUCTURE_V3_OCR_HOST_OPTS = new OptionDefinition(
            "pp_structure_v3_ocr_host_opts",
            "PP-Structure-V3 服务",
            "配置自托管 PaddleX 服务地址。",
            Map.of(
                    "fields", List.of(Map.of(
                            "key", "server_url",
                            "label", "服务地址",
                            "type", "url",
                            "environment", "PADDLEX_URI",
                            "placeholder", "http://paddlex:8080",
                            "help", "留空时读取 PADDLEX_URI。"))));

    public static final OptionDefinition PADDLEOCR_API_OPTS = new OptionDefinition(
            "paddleocr_api_opts",
            "PaddleOCR API",
            "PaddleOCR-VL 和 PP-OCRv6 共用此配置。",
            Map.of(
                    "fields", List.of(
                            Map.of(
                                    "key", "api_url",
                                    "label", "API 地址",
                                    "type", "url",
                                    "environment", "PADDLEOCR_API_URL",
                                    "placeholder", "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs",
                                    "help", "留空时读取 PADDLEOCR_API_URL。"),
                            Map.of(
                                    "key", "api_token",
                                    "label", "Access Token",
                                    "type", "password",
                                    "environment", "PADDLEOCR_API_TOKEN",
                                    "sensitive", true,
                                    "help", "留空时读取 PADDLEOCR_API_TOKEN，建议优先使用环境变量。"))));

    public static final OptionDefinition REMOTE_SKILL_SOURCE_POLICY = new OptionDefinition(
            "remote_skill_source_policy",
            "远程 Skill 来源",
            "配置允许远程安装 Skill 的来源域名。",
            Map.of(
                    "fields", List.of(Map.of(
                            "key", "allowed_hosts",
                            "label", "允许的来源域名",
                            "type", "list[str]",
                            "default", List.of("github.com", "modelscope.cn"),
                            "help", "仅精确匹配域名；保存空列表会关闭远程安装。"))));

    public static final Map<String, OptionDefinition> OPTION_DEFINITIONS;

    static {
        Map<String, OptionDefinition> definitions = new LinkedHashMap<>();
        for (OptionDefinition option : Arrays.asList(
                MINERU_OCR_HOST_OPTS,
                MINERU_OFFICIAL_API_OPTS,
                PP_STRUCTURE_V3_OCR_HOST_OPTS,
                PADDLEOCR_API_OPTS,
                REMOTE_SKILL_SOURCE_POLICY,
                SYSTEM_OPTIONS)) {
            definitions.put(option.getKey(), option);
        }
        OPTION_DEFINITIONS = java.util.Collections.unmodifiableMap(definitions);
    }

    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('INCR', KEYS[1])
            return redis.call('DEL', KEYS[2])
            """,
            Long.class);

    private static final DefaultRedisScript<Object> SAVE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            end
            return nil
            """,
            Object.class);

    /** OCR 引擎清单端口：knowledge.parser.capabilities 照搬后注册（缺省空集）。 */
    private static volatile Supplier<List<String>> ocrEngineIds = List::of;

    public static void registerOcrEngineIds(Supplier<List<String>> supplier) {
        ocrEngineIds = supplier;
    }

    private final ConfigOptionMapper configOptionMapper;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public OptionsService(ConfigOptionMapper configOptionMapper, StringRedisTemplate redis, JdbcTemplate jdbc) {
        this.configOptionMapper = configOptionMapper;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    // ==================== 运行时读取 ====================

    /** 无事务路径：带缓存的读取（对应参考实现 {@code Option.get(db=None)}）。 */
    public Map<String, Object> get(OptionDefinition option) {
        String cacheVersion = null;
        if (option.isCacheable()) {
            Map<String, Object> cached = loadCachedValue(option.getKey());
            if (cached != null) {
                return option.resolve(cached);
            }
            cacheVersion = loadCacheVersion(option.getKey());
        }

        Map<String, Object> stored = loadStoredValue(option.getKey());
        if (option.isCacheable()) {
            saveCachedValue(option.getKey(), stored, cacheVersion);
        }
        return option.resolve(stored);
    }

    /** 事务内路径：直接从数据库读取（对应参考实现 {@code Option.get(db)}）。 */
    public Map<String, Object> getInTransaction(OptionDefinition option) {
        return option.resolve(loadStoredValue(option.getKey()));
    }

    /** 从数据库读取原始配置值；缺项抛 IllegalStateException。 */
    private Map<String, Object> loadStoredValue(String key) {
        ConfigOption record = getOption(key);
        if (record == null) {
            throw new IllegalStateException("配置项不存在: " + key);
        }
        Map<String, Object> value = parseJsonObject(record.getValue());
        return value == null ? new LinkedHashMap<>() : value;
    }

    private Map<String, Object> loadCachedValue(String key) {
        try {
            String raw = redis.opsForValue().get(OPTION_CACHE_PREFIX + key);
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            return parseJsonObject(raw);
        } catch (RuntimeException exc) {
            log.warn("Failed to load option cache {}: {}", key, exc.getMessage());
            return null;
        }
    }

    private String loadCacheVersion(String key) {
        try {
            redis.opsForValue().setIfAbsent(OPTION_CACHE_VERSION_PREFIX + key, "0");
            String value = redis.opsForValue().get(OPTION_CACHE_VERSION_PREFIX + key);
            return value == null ? "0" : value;
        } catch (RuntimeException exc) {
            log.warn("Failed to load option cache version {}: {}", key, exc.getMessage());
            return null;
        }
    }

    private void saveCachedValue(String key, Map<String, Object> value, String expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        try {
            redis.execute(
                    SAVE_SCRIPT,
                    List.of(OPTION_CACHE_VERSION_PREFIX + key, OPTION_CACHE_PREFIX + key),
                    expectedVersion,
                    JSON.toJSONString(value),
                    String.valueOf(OPTION_CACHE_TTL_SECONDS));
        } catch (RuntimeException exc) {
            log.warn("Failed to save option cache {}: {}", key, exc.getMessage());
        }
    }

    // ==================== 持久化与同步 ====================

    /** 幂等同步系统定义，保留管理员已经保存的值。 */
    @Transactional
    public List<ConfigOption> ensureOptionsInDb() {
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, SYNC_LOCK_NAME);
        boolean held = acquired != null && acquired == 1;
        try {
            List<ConfigOption> existing =
                    configOptionMapper.selectList(new LambdaQueryWrapper<ConfigOption>()
                            .in(ConfigOption::getKey, OPTION_DEFINITIONS.keySet()));
            Map<String, ConfigOption> existingByKey = new LinkedHashMap<>();
            for (ConfigOption record : existing) {
                existingByKey.put(record.getKey(), record);
            }
            List<ConfigOption> synced = new ArrayList<>();
            LocalDateTime now = DateTimeUtils.utcNowNaive();
            for (Map.Entry<String, OptionDefinition> entry : OPTION_DEFINITIONS.entrySet()) {
                String key = entry.getKey();
                OptionDefinition definition = entry.getValue();
                ConfigOption record = existingByKey.get(key);
                if (record == null) {
                    record = new ConfigOption();
                    record.setKey(key);
                    record.setName(definition.getName());
                    record.setDescription(definition.getDescription());
                    record.setParams(JSON.toJSONString(definition.getParams()));
                    record.setValue("{}");
                    record.setCreatedBy("system");
                    record.setUpdatedBy("system");
                    record.setCreatedAt(now);
                    record.setUpdatedAt(now);
                    configOptionMapper.insert(record);
                } else {
                    record.setName(definition.getName());
                    record.setDescription(definition.getDescription());
                    Map<String, Object> params = cloneParams(definition.getParams());
                    if (SYSTEM_OPTIONS.getKey().equals(definition.getKey())) {
                        Map<String, Object> storedParams = parseJsonObject(record.getParams());
                        Object migrationVersion = storedParams == null ? null : storedParams.get(SYSTEM_OPTIONS_MIGRATION_VERSION_PARAM);
                        params.put(
                                SYSTEM_OPTIONS_MIGRATION_VERSION_PARAM,
                                migrationVersion instanceof Number number ? number.intValue() : 0);
                    }
                    record.setParams(JSON.toJSONString(params));
                    record.setUpdatedAt(now);
                    configOptionMapper.updateById(record);
                }
                synced.add(record);
            }
            return synced;
        } finally {
            if (held) {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, SYNC_LOCK_NAME);
            }
        }
    }

    /** 除 system_options 外的全部配置项（按 id 升序）。 */
    public List<ConfigOption> listOptions() {
        return configOptionMapper.selectList(new LambdaQueryWrapper<ConfigOption>()
                .ne(ConfigOption::getKey, SYSTEM_OPTIONS.getKey())
                .orderByAsc(ConfigOption::getId));
    }

    /** 按键读取单个配置项。 */
    public ConfigOption getOption(String key) {
        return configOptionMapper.selectOne(new LambdaQueryWrapper<ConfigOption>().eq(ConfigOption::getKey, key));
    }

    /** 返回表单定义和值；密钥只返回来源和脱敏预览。 */
    public Map<String, Object> serializeOption(ConfigOption record) {
        Map<String, Object> value = parseJsonObject(record.getValue());
        if (value == null) {
            value = new LinkedHashMap<>();
        }
        Map<String, Object> sensitiveConfigured = new LinkedHashMap<>();
        Map<String, Object> sensitiveState = new LinkedHashMap<>();
        for (Map<String, Object> field : fields(record)) {
            if (!Boolean.TRUE.equals(field.get("sensitive"))) {
                continue;
            }
            String fieldKey = String.valueOf(field.get("key"));
            String storedValue = value.get(fieldKey) == null ? "" : String.valueOf(value.get(fieldKey));
            String environmentValue =
                    field.get("environment") == null ? null : System.getenv(String.valueOf(field.get("environment")));
            Map<String, Object> state = new LinkedHashMap<>();
            if (!storedValue.isEmpty()) {
                state.put("source", "database");
                state.put("configured", true);
                state.put("preview", maskSensitiveValue(storedValue));
            } else if (environmentValue != null && !environmentValue.isEmpty()) {
                state.put("source", "environment");
                state.put("configured", true);
                state.put("preview", null);
            } else {
                state.put("source", "none");
                state.put("configured", false);
                state.put("preview", null);
            }
            sensitiveState.put(fieldKey, state);
            sensitiveConfigured.put(fieldKey, state.get("configured"));
            value.put(fieldKey, "");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", record.getKey());
        result.put("name", record.getName());
        result.put("description", record.getDescription());
        result.put("params", parseJsonObject(record.getParams()) == null ? new LinkedHashMap<>() : parseJsonObject(record.getParams()));
        result.put("value", value);
        result.put("sensitive_configured", sensitiveConfigured);
        result.put("sensitive_state", sensitiveState);
        return result;
    }

    /** 更新配置值（仅允许 params 定义过的字段）；返回 null 表示配置项不存在。 */
    @Transactional
    public ConfigOption updateOptionValue(String key, Map<String, Object> value, String updatedBy) {
        ConfigOption record = configOptionMapper.selectOne(new LambdaQueryWrapper<ConfigOption>()
                .eq(ConfigOption::getKey, key)
                .last("FOR UPDATE"));
        if (record == null) {
            return null;
        }

        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        for (Map<String, Object> field : fields(record)) {
            fields.put(String.valueOf(field.get("key")), field);
        }
        List<String> unknown = new ArrayList<>();
        for (String fieldKey : value.keySet()) {
            if (!fields.containsKey(fieldKey)) {
                unknown.add(fieldKey);
            }
        }
        if (!unknown.isEmpty()) {
            java.util.Collections.sort(unknown);
            throw new IllegalArgumentException("未知配置字段: " + String.join(", ", unknown));
        }

        Map<String, Object> updated = parseJsonObject(record.getValue());
        if (updated == null) {
            updated = new LinkedHashMap<>();
        }
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Map<String, Object> field = fields.get(entry.getKey());
            updated.put(entry.getKey(), normalizeOptionValue(field, entry.getValue()));
        }
        record.setValue(JSON.toJSONString(updated));
        record.setUpdatedBy(updatedBy);
        record.setUpdatedAt(DateTimeUtils.utcNowNaive());
        configOptionMapper.updateById(record);
        return record;
    }

    /** 数据库提交后删除 Option 缓存（版本号自增使并发读失效）。 */
    public void invalidateOptionCache(String key) {
        try {
            redis.execute(
                    INVALIDATE_SCRIPT,
                    List.of(OPTION_CACHE_VERSION_PREFIX + key, OPTION_CACHE_PREFIX + key));
        } catch (RuntimeException exc) {
            log.warn("Failed to invalidate option cache {}: {}", key, exc.getMessage());
        }
    }

    // ==================== 校验与工具 ====================

    /** 单字段取值归一化：list[str] 严格校验；url 归一化；ocr_engine 白名单校验。 */
    public Object normalizeOptionValue(Map<String, Object> field, Object value) {
        if ("list[str]".equals(field.get("type"))) {
            if (!(value instanceof List)) {
                throw new IllegalArgumentException("配置值必须是列表");
            }
            for (Object item : (List<?>) value) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("配置值必须是字符串列表");
                }
            }
            return value;
        }

        String normalized = value == null ? "" : String.valueOf(value);
        normalized = normalized.strip();
        if ("url".equals(field.get("type")) && !normalized.isEmpty()) {
            return normalizeHttpUrl(normalized);
        }
        if ("ocr_engine".equals(field.get("type")) && !normalized.isEmpty()) {
            List<String> engineIds = ocrEngineIds.get();
            if (!"disable".equals(normalized) && !engineIds.contains(normalized)) {
                throw new IllegalArgumentException("不支持的默认 OCR 引擎: " + normalized);
            }
        }
        return normalized;
    }

    /**
     * pydantic {@code HttpUrl} 的替代归一化：http/https 强制、host 小写、
     * 无路径补 "/"；查询串与片段保留。不合法时抛 IllegalArgumentException。
     */
    static String normalizeHttpUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("value is not a valid url: " + value);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || host == null || host.isEmpty()) {
            throw new IllegalArgumentException("value is not a valid url: " + value);
        }
        StringBuilder builder = new StringBuilder();
        builder.append(scheme).append("://").append(host.toLowerCase());
        if (uri.getPort() > 0) {
            builder.append(':').append(uri.getPort());
        }
        builder.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            builder.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            builder.append('#').append(uri.getRawFragment());
        }
        return builder.toString();
    }

    private static List<Map<String, Object>> fields(ConfigOption record) {
        Map<String, Object> params = parseJsonObject(record.getParams());
        Object fields = params == null ? null : params.get("fields");
        List<Map<String, Object>> result = new ArrayList<>();
        if (fields instanceof List) {
            for (Object item : (List<?>) fields) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) item;
                    result.add(map);
                }
            }
        }
        return result;
    }

    private static Map<String, Object> cloneParams(Map<String, Object> params) {
        return new LinkedHashMap<>(params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            Object parsed = JSON.parse(raw);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (RuntimeException exc) {
            return null;
        }
    }

    /** 密钥脱敏预览：单字符全掩码；短值首尾各留 1 位；其余首尾各留 2 位。 */
    static String maskSensitiveValue(String value) {
        if (value.length() == 1) {
            return "*******";
        }
        if (value.length() <= 4) {
            return value.charAt(0) + "*******" + value.charAt(value.length() - 1);
        }
        return value.substring(0, 2) + "*******" + value.substring(value.length() - 2);
    }
}
