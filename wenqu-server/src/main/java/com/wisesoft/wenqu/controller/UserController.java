package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.config.UserConfigService;
import com.wisesoft.wenqu.models.APIKey;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.APIKeyRepository;
import com.wisesoft.wenqu.repositories.AgentEnvRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.storage.MinioUploads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用户级配置与凭据路由，逐端点对齐参考实现 {@code server/routers/user_router.py}。
 *
 * <p>错误语义照搬：未登录 401 {@code 请登录后再访问}（带 {@code WWW-Authenticate: Bearer}）；
 * 未绑定部门 400 {@code 当前用户未绑定部门}；API Key 不存在 404 {@code API Key 不存在} /
 * 无权访问 403 {@code 无权操作此 API Key}；幂等冲突 409、关联用户缺失 404、部门不一致 403（文案取自仓储层）。
 *
 * <p>平台差异（必要替换）：{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}
 * （当前用户行由 uid 反查，等价于参考实现的 User 依赖对象）；
 * pydantic 字段约束 → 方法内显式校验（422，等价于 FastAPI 的校验失败状态码）；
 * {@code dict} 请求体 → {@code Map<String, Object>}（沿用本工程既有控制器约定，键名保持 snake_case）。
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "user", description = "用户级配置与凭据")
public class UserController {

    /** 参考实现模块常量。 */
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final int MAX_ENV_COUNT = 200;
    private static final int MAX_ENV_KEY_LENGTH = 128;
    private static final int MAX_ENV_VALUE_LENGTH = 32768;
    private static final long MAX_USER_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;

    /** APIKeyCreate.request_id 的 pydantic 约束。 */
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private final UserConfigService userConfigService;
    private final APIKeyRepository apiKeyRepository;
    private final AgentEnvRepository agentEnvRepository;
    private final UserRepository userRepository;

    // ==================== 用户配置 ====================

    @Operation(summary = "读取当前用户配置")
    @GetMapping("/config")
    public Map<String, Object> getUserConfig() {
        String uid = AuthGuards.requireUser();
        return userConfigService.load(uid).dumpConfig();
    }

    @Operation(summary = "更新当前用户配置")
    @PutMapping("/config")
    public Map<String, Object> updateUserConfig(@RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        boolean enableMemory = Boolean.TRUE.equals(body.get("enable_memory"));
        UserConfigService.UserConfig saved =
                userConfigService.save(new UserConfigService.UserConfig(
                        uid, new UserConfigService.UserConfigSchema(enableMemory), null));
        return saved.dumpConfig();
    }

    // ==================== 头像上传 ====================

    @Operation(summary = "上传用户图片", description = "仅 PNG/JPEG/WebP/GIF，且不超过 5MB")
    @PostMapping("/upload-image")
    public Map<String, Object> uploadUserImage(@RequestPart("file") MultipartFile file) {
        String uid = AuthGuards.requireUser();
        String imageUrl;
        try {
            imageUrl = MinioUploads.uploadImageToMinio(
                    file, "images/" + uid, MAX_USER_IMAGE_SIZE_BYTES, "图片大小不能超过 5MB");
        } catch (MinioUploads.ValueErrorException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("image_url", imageUrl);
        result.put("url", imageUrl);
        return result;
    }

    // ==================== API Key ====================

    @Operation(summary = "可见 API Key 列表")
    @GetMapping({"/apikey", "/apikey/"})
    public Map<String, Object> listApiKeys(
            @RequestParam(value = "skip", defaultValue = "0") int skip,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        User currentUser = currentUser();
        boolean isSuperadmin = "superadmin".equals(currentUser.getRole());
        Map<String, Object> page = apiKeyRepository.listVisible(currentUser.getId(), isSuperadmin, skip, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        Object records = page.get("records");
        if (records instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof APIKey apiKey) {
                    items.add(APIKeyRepository.toDict(apiKey));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_keys", items);
        result.put("total", page.get("total"));
        return result;
    }

    @Operation(summary = "创建 API Key", description = "返回明文 secret（仅此一次）")
    @PostMapping({"/apikey", "/apikey/"})
    public Map<String, Object> createApiKey(@RequestBody Map<String, Object> body) {
        User currentUser = currentUser();

        String requestId = requireRequestId(body.get("request_id"));
        Object nameValue = body.get("name");
        if (!(nameValue instanceof String name)) {
            throw new ApiHttpException(422, "name 字段必填且为字符串");
        }

        Integer requestedUserId = asInteger(body.get("user_id"));
        if (requestedUserId != null
                && !requestedUserId.equals(currentUser.getId())
                && !"superadmin".equals(currentUser.getRole())) {
            throw new ApiHttpException(403, "无权为其他用户创建 API Key");
        }
        int targetUserId = requestedUserId != null ? requestedUserId : currentUser.getId();
        Integer departmentId = asInteger(body.get("department_id"));

        // 参考实现不接受空字符串，仅在字段存在且可转换时取 UTC 无时区值
        LocalDateTime expiresAt = null;
        Object expiresValue = body.get("expires_at");
        if (expiresValue != null) {
            OffsetDateTime aware = safeCoerceDatetime(expiresValue);
            if (aware != null) {
                expiresAt = aware.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
        }

        String[] derived = AuthUtils.deriveApiKey("user-request:" + requestId, targetUserId);
        String fullKey = derived[0];
        String keyHash = derived[1];
        String keyPrefix = derived[2];

        APIKey apiKey;
        try {
            apiKey = apiKeyRepository.create(
                    keyHash, keyPrefix, requestId, name, targetUserId, departmentId,
                    expiresAt, String.valueOf(currentUser.getId()));
        } catch (APIKeyRepository.IdempotencyConflictException exc) {
            throw new ApiHttpException(409, exc.getMessage());
        } catch (APIKeyRepository.SubjectUnavailableException exc) {
            throw new ApiHttpException(404, exc.getMessage());
        } catch (APIKeyRepository.DepartmentConflictException exc) {
            throw new ApiHttpException(403, exc.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_key", APIKeyRepository.toDict(apiKey));
        result.put("secret", fullKey);
        return result;
    }

    @Operation(summary = "API Key 详情")
    @GetMapping("/apikey/{api_key_id}")
    public Map<String, Object> getApiKey(@PathVariable("api_key_id") int apiKeyId) {
        User currentUser = currentUser();
        APIKey apiKey = accessibleApiKey(apiKeyId, currentUser);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_key", APIKeyRepository.toDict(apiKey));
        return result;
    }

    @Operation(summary = "更新 API Key")
    @PutMapping("/apikey/{api_key_id}")
    public Map<String, Object> updateApiKey(
            @PathVariable("api_key_id") int apiKeyId, @RequestBody Map<String, Object> body) {
        User currentUser = currentUser();
        APIKey apiKey = accessibleApiKey(apiKeyId, currentUser);

        Map<String, Object> updates = new LinkedHashMap<>();
        Object nameValue = body.get("name");
        if (nameValue != null) {
            updates.put("name", nameValue);
        }
        Object expiresValue = body.get("expires_at");
        if (expiresValue != null) {
            OffsetDateTime aware = safeCoerceDatetime(expiresValue);
            updates.put(
                    "expires_at",
                    aware == null ? null : aware.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
        Object enabledValue = body.get("is_enabled");
        if (enabledValue != null) {
            updates.put("is_enabled", enabledValue);
        }

        APIKey updated = apiKeyRepository.update(apiKey, updates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api_key", APIKeyRepository.toDict(updated));
        return result;
    }

    @Operation(summary = "撤销 API Key")
    @DeleteMapping("/apikey/{api_key_id}")
    public Map<String, Object> deleteApiKey(@PathVariable("api_key_id") int apiKeyId) {
        User currentUser = currentUser();
        APIKey apiKey = accessibleApiKey(apiKeyId, currentUser);
        apiKeyRepository.delete(apiKey);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    // ==================== Agent 环境变量 ====================

    @Operation(summary = "读取 Agent 环境变量")
    @GetMapping("/agent-env")
    public Map<String, Object> getAgentEnv() {
        String uid = AuthGuards.requireUser();
        com.wisesoft.wenqu.models.AgentEnv agentEnv = agentEnvRepository.getByUid(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        if (agentEnv == null) {
            result.put("env", new LinkedHashMap<String, Object>());
            result.put("updated_at", null);
            return result;
        }
        // 库中为 JSON 文本，序列化前解析回对象（参考实现 env 列即 dict）
        com.alibaba.fastjson2.JSONObject parsed = com.wisesoft.wenqu.repositories.RepoValues.parseObject(
                agentEnv.getEnv());
        result.put("env", parsed == null ? new LinkedHashMap<String, Object>() : parsed);
        result.put("updated_at", DateTimeUtils.formatUtcDatetime(agentEnv.getUpdatedAt()));
        return result;
    }

    @Operation(summary = "更新 Agent 环境变量")
    @PutMapping("/agent-env")
    public Map<String, Object> updateAgentEnv(@RequestBody Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        Object rawEnv = body.get("env");
        Map<String, Object> envInput = new LinkedHashMap<>();
        if (rawEnv instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                envInput.put(entry.getKey() == null ? null : String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (rawEnv != null) {
            throw new ApiHttpException(422, "env 字段必须是对象");
        }

        Map<String, String> env = validateAgentEnv(envInput);
        AgentEnvRepository.AgentEnvWriteResult saved =
                agentEnvRepository.upsert(uid, env, DateTimeUtils.utcNowNaive());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("env", saved.env() == null ? new LinkedHashMap<>() : saved.env());
        result.put("updated_at", DateTimeUtils.formatUtcDatetime(saved.updatedAt()));
        return result;
    }

    // ==================== 内部辅助 ====================

    /** 当前登录用户行（对应参考实现 get_required_user 返回的 User 对象）。 */
    private User currentUser() {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** 读取当前用户可见的 API Key，并保持既有错误状态码（参考实现 get_accessible_api_key）。 */
    private APIKey accessibleApiKey(int apiKeyId, User currentUser) {
        APIKeyRepository.AccessResult access = apiKeyRepository.getAccessible(
                apiKeyId, currentUser.getId(), "superadmin".equals(currentUser.getRole()));
        if (access.apiKey() != null) {
            return access.apiKey();
        }
        if (!access.exists()) {
            throw new ApiHttpException(404, "API Key 不存在");
        }
        throw new ApiHttpException(403, "无权操作此 API Key");
    }

    /** APIKeyCreate.request_id 的约束校验（min_length=8 / max_length=64 / pattern）。 */
    private String requireRequestId(Object value) {
        if (!(value instanceof String requestId)) {
            throw new ApiHttpException(422, "request_id 字段必填且为字符串");
        }
        if (requestId.length() < 8 || requestId.length() > 64) {
            throw new ApiHttpException(422, "request_id 长度必须在 8 到 64 之间");
        }
        if (!REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            throw new ApiHttpException(422, "request_id 含非法字符");
        }
        return requestId;
    }

    /** 环境变量校验（参考实现 validate_agent_env，报错文案逐字一致）。 */
    private Map<String, String> validateAgentEnv(Map<String, Object> env) {
        if (env.size() > MAX_ENV_COUNT) {
            throw new ApiHttpException(400, "环境变量数量不能超过 " + MAX_ENV_COUNT + " 个");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : env.entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey == null) {
                throw new ApiHttpException(400, "环境变量名必须是字符串");
            }
            String name = rawKey.strip();
            if (name.isEmpty()) {
                throw new ApiHttpException(400, "环境变量名不能为空");
            }
            if (name.length() > MAX_ENV_KEY_LENGTH) {
                throw new ApiHttpException(400, "环境变量名长度不能超过 " + MAX_ENV_KEY_LENGTH);
            }
            if (!ENV_KEY_PATTERN.matcher(name).matches()) {
                throw new ApiHttpException(400, "环境变量名 " + name + " 格式不正确");
            }
            if (normalized.containsKey(name)) {
                throw new ApiHttpException(400, "环境变量名 " + name + " 重复");
            }
            Object value = entry.getValue();
            if (!(value instanceof String text)) {
                throw new ApiHttpException(400, "环境变量 " + name + " 的值必须是字符串");
            }
            if (text.length() > MAX_ENV_VALUE_LENGTH) {
                throw new ApiHttpException(400, "环境变量 " + name + " 的值过长");
            }
            normalized.put(name, text);
        }
        return normalized;
    }

    /** 宽松取整：非数值一律视为未传（对应参考实现的可选 int 字段）。 */
    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).strip());
        } catch (NumberFormatException exc) {
            return null;
        }
    }

    /** 宽松时间解析：无法解析按未传处理（参考实现 coerce 返回 None 的分支）。 */
    private static OffsetDateTime safeCoerceDatetime(Object value) {
        try {
            return DateTimeUtils.coerceAnyToUtcDatetime(value);
        } catch (IllegalArgumentException exc) {
            return null;
        }
    }
}
