package com.wenqu.ai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置三处真源的契约：{@code config-schema.json}（设置页可见面）与
 * {@code ConfigService.defaults()}（运行时默认值）必须一一对得上。
 *
 * <p>允许 defaults 里存在「设置页不暴露」的隐藏/部署项，但必须登记在 {@link #HIDDEN_DEFAULTS}
 * 并写明用途——新增一条不登记就判红，防止「改库能生效」的隐形键再次长出来。
 */
class ConfigContractTest {

    private static final Path SCHEMA = Path.of("src", "main", "resources", "config-schema.json");
    private static final Path CONFIG_SERVICE = Path.of("src", "main", "java", "com", "wenqu", "ai", "service", "ConfigService.java");

    /** 设置页不可见、但仍是运行时默认值的键（隐藏调参 / env 部署项）。新增必须在这里登记。 */
    private static final Set<String> HIDDEN_DEFAULTS = Set.of(
            // 隐藏调参：只给排障/回滚用，不暴露给最终用户
            "cache.docMetaTtlSeconds", "chat.detachGraceMs", "chat.pipelineThreads",
            "chat.resumeEnabled", "chat.temperature", "tool.mcpCiteMaxRefs", "tool.spillLocalWorkspace",
            "trace.turnIdBackfill",
            // OCR / 解析引擎连接项：随部署环境走，不在设置页
            "parse.embedBatchSize", "parse.embedRetryCount", "parse.ocrMineruBackend",
            "parse.ocrMineruUri", "parse.ocrPpUri", "parse.ocrTimeoutMs", "parse.recoverStuckOnStartup",
            // 限流、会话、分享配额：部署侧参数
            "ratelimit.chatPerMinute", "ratelimit.embeddingPerMinute", "ratelimit.uploadPerMinute", "ratelimit.windowSeconds",
            "session.expireMinutes", "session.maxHistory",
            "share.guestDailyTokens", "share.guestDailyTurns", "share.guestDailyTurnsPerVisitor",
            // 沙盒 / 检索连接项：随部署环境走（重排平台层已退役：rerank.baseUrl 直连回落一并下线）
            "retrieval.searchTimeoutMs",
            "sandbox.callbackBaseUrl", "sandbox.cleanupIntervalMs", "sandbox.deleteTimeoutSeconds",
            "sandbox.keepaliveIntervalSeconds", "sandbox.maxOutputBytes", "sandbox.provisionerUrl",
            "sandbox.token", "sandbox.userDataHostDir", "sandbox.virtualPathPrefix",
            // 读取点历史上直接带魔法默认值的键：统一登记为隐藏默认值
            // （chunk.maxImages/maxChunks、parse.childSize/ocrDpi/ocrMinText 的全局槽位已随
            //   检索/解析参数全局默认值层退役（2026-11-14）：defaults() 不再种子，读点 fallback 兜底）
            "chat.maxAttachmentsPerMessage", "chat.maxAttachmentMb", "chat.maxMentionsPerMessage",
            "chat.maxHistoryRefsPerMessage", "chat.attachmentMaxChars", "chat.attachmentTotalChars",
            "graphrag.chunkViewMaxChars", "keyword.timeoutMillis", "keyword.failCooldownMs",
            "rerank.timeoutMillis", "rerank.failCooldownMs", "retrieval.keywordMaxTerms",
            "retrieval.keywordMaxTotal", "retrieval.keywordTimeoutMs", "tenant.autoApprove.enabled",
            // 设置页已退役、仍运行时生效的键（OpenApiCorsConfig 每请求现读，经库/配置接口调整）
            "apiOpen.allowedOrigins"
    );

    /** schema 有 def、但 defaults() 用 env/properties/常量表达式求值的键：不参与字面量比对。 */
    private static final Set<String> DYNAMIC_DEFAULTS = Set.of(
            "agentWebhook.retryMax", "chat.agentscopeStateDir",
            "cleanup.assetRetentionDays", "cleanup.intervalMs",
            "cleanup.recordRetentionDays", "cleanup.tempFileRetentionDays",
            "dashboard.alertIntervalMs", "dashboard.likeAlertRate", "dashboard.noHitAlertRate",
            "dashboard.weeklyReportIntervalMs",
            "keyword.apiKey", "keyword.baseUrl", "keyword.engine",
            "ops.checkupAlertEnabled", "ops.checkupIntervalMs", "ops.checkupRetentionDays",
            "parse.embedConcurrency", "ratelimit.enabled",
            "workflow.maxSteps", "workflow.runQueueCapacity"
    );

    @Test
    void schemaAndDefaultsStayInSync() throws Exception {
        Set<String> schemaKeys = schemaKeys();
        Set<String> defaultsKeys = defaultsKeys();

        Set<String> schemaOnly = new LinkedHashSet<>(schemaKeys);
        schemaOnly.removeAll(defaultsKeys);
        Set<String> defaultsOnly = new LinkedHashSet<>(defaultsKeys);
        defaultsOnly.removeAll(schemaKeys);

        assertThat(schemaOnly).as("schema 有而 defaults 缺（设置页可改但默认值为空）").isEmpty();
        assertThat(defaultsOnly)
                .as("defaults 有而 schema 无：隐藏/部署项必须登记到 HIDDEN_DEFAULTS")
                .containsExactlyInAnyOrderElementsOf(HIDDEN_DEFAULTS);

        assertThat(schemaKeys).as("schema 字段数变了：同步 README 的设置项口径").hasSize(90);
        assertThat(defaultsKeys).as("defaults 键数变了：同步 README 的隐藏项口径").hasSize(140);
    }

    @Test
    void readKeysAreRegistered() throws Exception {
        Set<String> known = new LinkedHashSet<>(schemaKeys());
        known.addAll(defaultsKeys());
        // 库级键（知识库 query_params 唯一真源 KbParamCodec.KEYS）：无全局槽位、不进 defaults，
        // 读取点（如 RerankService 的窗口两键）按库收敛取值，登记面就是 KEYS 本身
        known.addAll(com.wenqu.ai.service.KbParamCodec.KEYS);
        // 库级解析参数（parse_params 白名单 DocumentService.PARSE_PARAM_KEYS）：全局槽位已退役，
        // 解析读点经线程局部覆盖按库生效（configService 读取是覆盖机制的入口），登记面就是白名单本身
        known.addAll(com.wenqu.ai.service.DocumentService.PARSE_PARAM_KEYS);

        Set<String> unknown = new java.util.TreeSet<>();
        Map<String, String> keyConstants = new LinkedHashMap<>();
        for (java.lang.reflect.Field f : ConfigKeys.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                keyConstants.put(f.getName(), (String) f.get(null));
            }
        }
        Pattern literalRead = Pattern.compile(
                "configService\\.get(?:Int|Long|Boolean|String|Double)\\(\\s*\"([^\"]+)\"");
        Pattern constantRead = Pattern.compile(
                "configService\\.get(?:Int|Long|Boolean|String|Double)\\(\\s*ConfigKeys\\.([A-Z0-9_]+)");
        try (var files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = literalRead.matcher(src);
                while (m.find()) {
                    String key = m.group(1);
                    // ratelimit. 是拼接用的前缀，不是完整键
                    if ("ratelimit.".equals(key)) continue;
                    if (!known.contains(key)) unknown.add(key);
                }
                Matcher c = constantRead.matcher(src);
                while (c.find()) {
                    String key = keyConstants.get(c.group(1));
                    if (key != null && !known.contains(key)) unknown.add(key);
                }
            }
        }
        assertThat(unknown)
                .as("configService 读取了未登记的键：登记到 schema 或 defaults（隐藏项进 HIDDEN_DEFAULTS）")
                .isEmpty();
    }

    @Test
    void literalDefaultsMatchSchemaDefs() throws Exception {
        Map<String, String> defs = schemaDefs();
        Map<String, String> literals = literalDefaults();

        Map<String, String> mismatch = new LinkedHashMap<>();
        for (String key : defs.keySet()) {
            String lit = literals.get(key);
            if (lit != null && !lit.equals(defs.get(key))) {
                mismatch.put(key, lit + " != schema " + defs.get(key));
            }
        }
        assertThat(mismatch).as("defaults 字面量与 schema def 不一致").isEmpty();

        Set<String> dynamic = new LinkedHashSet<>(defs.keySet());
        dynamic.retainAll(allDefaultsPutKeys());
        dynamic.removeAll(literals.keySet());
        assertThat(dynamic)
                .as("schema 有 def 但 defaults 不是字面量：登记到 DYNAMIC_DEFAULTS")
                .containsExactlyInAnyOrderElementsOf(DYNAMIC_DEFAULTS);
    }

    @Test
    void webhookRetryDefaultHasSingleValue() throws Exception {
        JsonNode field = null;
        for (JsonNode f : new ObjectMapper().readTree(Files.readString(SCHEMA)).path("fields")) {
            if ("agentWebhook.retryMax".equals(f.path("backendKey").asText())) {
                field = f;
                break;
            }
        }
        assertThat(field).as("schema 缺少 agentWebhook.retryMax").isNotNull();
        assertThat(field.path("def").asInt()).isEqualTo(ConfigDefaults.AGENT_WEBHOOK_RETRY_MAX);
        assertThat(defaultsKeys()).contains("agentWebhook.retryMax");
    }

    private static Set<String> schemaKeys() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode f : new ObjectMapper().readTree(Files.readString(SCHEMA)).path("fields")) {
            String key = f.path("backendKey").asText(null);
            if (key != null && !key.isBlank()) keys.add(key);
        }
        return keys;
    }

    private static Set<String> defaultsKeys() throws Exception {
        // 有效默认值 = schema 基座 ∪ defaults() 里显式覆盖的键（动态值 + 隐藏/部署项）。
        Set<String> keys = new LinkedHashSet<>(new ConfigSchemaService().storedDefaults().keySet());
        keys.addAll(allDefaultsPutKeys());
        return keys;
    }

    private static Set<String> allDefaultsPutKeys() throws Exception {
        Matcher m = Pattern.compile("d\\.put\\(\\s*\"([^\"]+)\"")
                .matcher(defaultsBlock(Files.readString(CONFIG_SERVICE)));
        Set<String> keys = new LinkedHashSet<>();
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    private static Map<String, String> schemaDefs() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode f : new ObjectMapper().readTree(Files.readString(SCHEMA)).path("fields")) {
            String key = f.path("backendKey").asText(null);
            JsonNode def = f.get("def");
            if (key == null || def == null || def.isNull() || def.isContainerNode()) continue;
            out.put(key, def.isBoolean() ? String.valueOf(def.asBoolean()) : def.asText());
        }
        return out;
    }

    private static Map<String, String> literalDefaults() throws Exception {
        Matcher m = Pattern.compile("d\\.put\\(\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]*)\"\\s*\\)")
                .matcher(defaultsBlock(Files.readString(CONFIG_SERVICE)));
        Map<String, String> out = new LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static String defaultsBlock(String src) {
        int decl = src.indexOf("private Map<String, String> defaults() {");
        assertThat(decl).as("ConfigService 里找不到 defaults()").isGreaterThanOrEqualTo(0);
        int open = src.indexOf('{', decl);
        int depth = 0;
        boolean inStr = false, inChar = false, inLine = false, inBlock = false;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && n == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && n == '*') {
                inBlock = true;
                i++;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("defaults() 没有闭合");
    }
}
