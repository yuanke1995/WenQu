package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.AppVersion;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.config.LogPaths;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.config.StartupState;
import com.wisesoft.wenqu.models.ConfigOption;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.OcrService;
import com.wisesoft.wenqu.service.ReadinessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统路由，逐端点对齐参考实现 {@code server/routers/system_router.py}。
 *
 * <p>错误语义照搬：未知配置项 400 {@code 未知配置项: {key}}；配置项不存在 404 {@code 配置项不存在: {key}}；
 * 日志读取失败 500 {@code 获取系统日志失败: {原因}}；信息配置读取失败 500 {@code 获取信息配置失败} /
 * {@code 重新加载信息配置失败}；就绪检查未就绪 503（body 仍为就绪结果）。
 *
 * <p>必要替换（显式标注）：
 * <ul>
 *   <li>版本号与产品名 → {@link AppVersion}（本系统标识）；</li>
 *   <li>品牌配置文件路径 → 环境变量 {@code WENQU_BRAND_FILE_PATH}（缺省
 *       {@code config/static/info.local.yaml}），版本占位符 {@code {{WENQU_VERSION}}}；</li>
 *   <li>日志文件 → {@link LogPaths#logFile()}（与 logback-spring.xml 的写入路径一致）；</li>
 *   <li>{@code request.app.state.startup_complete / startup_components} → {@link StartupState}；</li>
 *   <li>{@code aiofiles}/{@code yaml} → JDK NIO 流式读取 + SnakeYAML（bean 不加壳，行为等价）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Tag(name = "system", description = "系统状态、配置与信息")
public class SystemController {

    /** 品牌配置文件路径环境变量（参考实现同名环境变量的本产品对应项）。 */
    private static final String BRAND_FILE_PATH_ENV = "WENQU_BRAND_FILE_PATH";

    private static final String DEFAULT_BRAND_FILE = "config/static/info.local.yaml";
    private static final String TEMPLATE_BRAND_FILE = "config/static/info.template.yaml";

    /** 日志尾部保留行数（参考实现常量 1000）。 */
    private static final int LOG_TAIL_LINES = 1000;

    private final OptionsService optionsService;
    private final ReadinessService readinessService;
    private final OcrService ocrService;
    private final StartupState startupState;
    private final UserRepository userRepository;

    // ==================== 健康检查分组 ====================

    @Operation(summary = "进程存活检查", description = "返回 API 进程 liveness，不代表依赖或业务链路就绪")
    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("message", "进程正常运行");
        result.put("version", AppVersion.VERSION);
        return result;
    }

    @Operation(summary = "就绪检查", description = "验证接流量所需的启动状态与核心依赖；未就绪返回 503")
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readinessCheck() {
        Map<String, Object> result;
        try {
            result = readinessService.getReadiness(
                    startupState.isStartupComplete(), startupState.startupComponents());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new ApiHttpException(500, "就绪检查被中断");
        }
        result.put("version", AppVersion.VERSION);
        return ResponseEntity.status("ready".equals(result.get("status")) ? 200 : 503).body(result);
    }

    @Operation(summary = "系统能力发现", description = "公开接口")
    @GetMapping("/discovery")
    public Map<String, Object> discovery() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("knowledge", true);

        Map<String, Object> cli = new LinkedHashMap<>();
        cli.put("min_cli_version", "0.1.0");
        cli.put("browser_login", true);
        cli.put("api_key_auth", true);
        cli.put("remote_config", true);
        cli.put("agent_list", true);
        cli.put("agent_show", true);
        cli.put("kb_upload", true);
        cli.put("kb_list", true);
        cli.put("kb_files", true);
        cli.put("kb_query", true);
        cli.put("kb_open", true);
        cli.put("kb_find", true);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("features", features);
        capabilities.put("cli", cli);

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("health", "/api/system/health");
        endpoints.put("readiness", "/api/system/ready");
        endpoints.put("auth_me", "/api/auth/me");
        endpoints.put("cli_auth_sessions", "/api/auth/cli/sessions");
        endpoints.put("cli_auth_authorize", "/auth/cli/authorize");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", AppVersion.PRODUCT_NAME);
        result.put("version", AppVersion.VERSION);
        result.put("api_prefix", "/api");
        result.put("capabilities", capabilities);
        result.put("endpoints", endpoints);
        return result;
    }

    // ==================== 配置管理分组 ====================

    @Operation(summary = "获取系统配置")
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        AuthGuards.requireUser();
        return serializeSystemConfig(optionsService.get(OptionsService.SYSTEM_OPTIONS));
    }

    @Operation(summary = "更新单个配置项")
    @PostMapping("/config")
    public Map<String, Object> updateConfigSingle(@RequestBody Map<String, Object> body) {
        AuthGuards.requireAdmin();

        Object keyValue = body.get("key");
        if (!(keyValue instanceof String key) || !systemOptionKeys().contains(key)) {
            throw new ApiHttpException(400, "未知配置项: " + keyValue);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, body.get("value"));
        try {
            optionsService.updateOptionValue(
                    OptionsService.SYSTEM_OPTIONS.getKey(), payload, currentUsername());
            optionsService.invalidateOptionCache(OptionsService.SYSTEM_OPTIONS.getKey());
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }
        return serializeSystemConfig(optionsService.get(OptionsService.SYSTEM_OPTIONS));
    }

    @Operation(summary = "批量更新配置项", description = "请求体即待更新键值集合")
    @PostMapping("/config/update")
    public Map<String, Object> updateConfigBatch(@RequestBody Map<String, Object> items) {
        AuthGuards.requireAdmin();
        try {
            optionsService.updateOptionValue(
                    OptionsService.SYSTEM_OPTIONS.getKey(), items, currentUsername());
            optionsService.invalidateOptionCache(OptionsService.SYSTEM_OPTIONS.getKey());
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }
        return serializeSystemConfig(optionsService.get(OptionsService.SYSTEM_OPTIONS));
    }

    @Operation(summary = "获取当前 API 进程日志", description = "可传 levels 按日志级别过滤（逗号分隔）")
    @GetMapping("/logs")
    public Map<String, Object> getSystemLogs(
            @RequestParam(value = "levels", required = false) String levels) {
        AuthGuards.requireAdmin();
        try {
            Set<String> levelFilter = null;
            if (levels != null && !levels.isBlank()) {
                levelFilter = new LinkedHashSet<>();
                for (String level : levels.split(",")) {
                    if (!level.isBlank()) {
                        levelFilter.add(level.strip().toUpperCase());
                    }
                }
            }

            List<String> lines = new ArrayList<>();
            Path logFile = Paths.get(LogPaths.logFile());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(logFile), StandardCharsets.UTF_8))) {
                String raw;
                while ((raw = reader.readLine()) != null) {
                    // readLine 已去除行尾换行；参考实现对每行做 rstrip("\n\r")
                    String filteredLine = raw.stripTrailing();
                    if (levelFilter != null) {
                        // 日志格式: 2026-09-19 08:26:37 - INFO - file:line - message
                        String[] parts = filteredLine.split(" - ", -1);
                        if (parts.length >= 2 && levelFilter.contains(parts[1].strip())) {
                            lines.add(filteredLine + "\n");
                        }
                        // 参考实现在过滤分支内同样维持 1000 行上限
                        if (lines.size() > LOG_TAIL_LINES) {
                            lines.remove(0);
                        }
                    } else {
                        lines.add(filteredLine + "\n");
                        if (lines.size() > LOG_TAIL_LINES) {
                            lines.remove(0);
                        }
                    }
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("log", String.join("", lines));
            result.put("message", "success");
            result.put("log_file", LogPaths.logFile());
            result.put("scope", "api");
            return result;
        } catch (IOException exc) {
            log.error("获取系统日志失败: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取系统日志失败: " + exc.getMessage());
        }
    }

    // ==================== 信息管理分组 ====================

    @Operation(summary = "获取系统信息配置", description = "公开接口，无需认证")
    @GetMapping("/info")
    public Map<String, Object> getInfoConfig() {
        try {
            Map<String, Object> config = loadInfoConfig();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", config);
            return result;
        } catch (Exception exc) {
            log.error("获取信息配置失败: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取信息配置失败");
        }
    }

    @Operation(summary = "重新加载信息配置")
    @PostMapping("/info/reload")
    public Map<String, Object> reloadInfoConfig() {
        AuthGuards.requireAdmin();
        try {
            Map<String, Object> config = loadInfoConfig();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "配置重新加载成功");
            result.put("data", config);
            return result;
        } catch (Exception exc) {
            log.error("重新加载信息配置失败: {}", exc.getMessage());
            throw new ApiHttpException(500, "重新加载信息配置失败");
        }
    }

    /** 加载信息配置文件（参考实现 load_info_config：本地文件优先，缺失回落模板，注入版本号）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadInfoConfig() {
        try {
            String configured = System.getenv().getOrDefault(BRAND_FILE_PATH_ENV, DEFAULT_BRAND_FILE);
            Path configPath = Paths.get(configured);
            if (!Files.exists(configPath)) {
                log.debug("The config file {} does not exist, using default config", configPath);
                configPath = Paths.get(TEMPLATE_BRAND_FILE);
            }

            String content;
            if (Files.exists(configPath)) {
                content = Files.readString(configPath, StandardCharsets.UTF_8);
            } else {
                // 打包为 jar 后 classpath 内读取
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(TEMPLATE_BRAND_FILE)) {
                    if (stream == null) {
                        return new LinkedHashMap<>();
                    }
                    content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }

            content = content.replace("{{WENQU_VERSION}}", AppVersion.VERSION);
            Object parsed = new Yaml().load(content);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> config = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    config.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return config;
            }
            return new LinkedHashMap<>();
        } catch (Exception exc) {
            log.error("Failed to load info config: {}", exc.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ==================== 通用配置项与 OCR 分组 ====================

    @Operation(summary = "通用配置项清单（表单定义与值）")
    @GetMapping("/config/options")
    public Map<String, Object> getConfigOptions() {
        AuthGuards.requireAdmin();
        List<Map<String, Object>> options = new ArrayList<>();
        for (ConfigOption record : optionsService.listOptions()) {
            options.add(optionsService.serializeOption(record));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", options);
        return result;
    }

    @Operation(summary = "保存一个通用配置项的 JSON 值")
    @PutMapping("/config/options/{key}")
    public Map<String, Object> putConfigOption(
            @PathVariable("key") String key, @RequestBody Map<String, Object> payload) {
        AuthGuards.requireAdmin();

        Object rawValue = payload == null ? null : payload.get("value");
        if (!(rawValue instanceof Map)) {
            throw new ApiHttpException(422, "value 字段必填且为对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) rawValue;

        ConfigOption record;
        try {
            record = optionsService.updateOptionValue(key, value, currentUsername());
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }
        if (record == null) {
            throw new ApiHttpException(404, "配置项不存在: " + key);
        }
        optionsService.invalidateOptionCache(key);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("option", optionsService.serializeOption(record));
        return result;
    }

    @Operation(summary = "OCR 方法清单与默认项")
    @GetMapping("/ocr/options")
    public Map<String, Object> getOcrEngineOptions() {
        AuthGuards.requireUser();
        return ocrService.getOcrOptions();
    }

    @Operation(summary = "OCR 健康检查", description = "使用当前有效配置检查全部 OCR 方法")
    @GetMapping("/ocr/health")
    public Map<String, Object> getOcrHealth() {
        AuthGuards.requireUser();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("health", ocrService.checkAllOcrHealth());
        return result;
    }

    // ==================== 内部辅助 ====================

    /** _serialize_system_config：字段说明与值合并。 */
    private Map<String, Object> serializeSystemConfig(Map<String, Object> values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map<String, Object> field : OptionsService.SYSTEM_OPTIONS.getFields()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("des", field.get("label"));
            item.put("default", field.get("default"));
            item.put("type", field.get("type") == null ? "string" : field.get("type"));
            item.put("exclude", false);
            fields.put(String.valueOf(field.get("key")), item);
        }
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put("_config_items", fields);
        return result;
    }

    private Set<String> systemOptionKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> field : OptionsService.SYSTEM_OPTIONS.getFields()) {
            keys.add(String.valueOf(field.get("key")));
        }
        return keys;
    }

    /** 当前用户的 username（参考实现 current_user.username，用于配置审计字段）。 */
    private String currentUsername() {
        String uid = AuthGuards.requireAdmin();
        User user = userRepository.getByUid(uid);
        return user == null ? uid : user.getUsername();
    }
}
