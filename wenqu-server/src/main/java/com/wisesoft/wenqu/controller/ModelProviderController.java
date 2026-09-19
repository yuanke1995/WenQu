package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.ModelInfo;
import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.ModelProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立模型供应商配置路由，逐端点对齐参考实现 {@code server/routers/model_provider_router.py}
 * （前缀 {@code /system/model-providers} → 本工程 {@code /api/system/model-providers}）。
 *
 * <p>9 个端点：列表 / 新建 / 单个查询 / 更新 / 删除 / 远端模型拉取 / 强制刷缓存 / v2 模型分组 /
 * 按 spec 测模型状态。
 *
 * <h3>平台差异（必要替换，均不影响状态码与文案）</h3>
 * <ul>
 *   <li>{@code Depends(get_admin_user / get_required_user)} → {@link AuthGuards} 的
 *       {@code requireAdmin()} / {@code requireUser()}（方法首行显式调用），并按 uid 载入
 *       {@link User} 取 {@code username}（参考实现把 ORM 实体注入路由）。</li>
 *   <li>{@code ModelProviderPayload}（pydantic）→ 显式字段白名单 + {@code model_dump(exclude_none=True)}
 *       的等价裁剪；pydantic 默认 {@code extra="ignore"} 会丢弃未声明字段，
 *       故白名单过滤必须发生在"是否显式传值"判断之前。</li>
 *   <li>{@code db.commit()} + {@code _refresh_model_cache()} 的顺序（先提交再刷新）→
 *       本工程每次 Mapper 调用即提交，控制器内不另开事务，顺序天然一致。</li>
 *   <li>{@code httpx.HTTPStatusError} 的分支 → {@link ModelProviderService.HttpStatusFailure}。</li>
 *   <li>响应体为普通字典，无 {@code response_model}，故不做键投影。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/system/model-providers")
@RequiredArgsConstructor
@Tag(name = "model-providers", description = "独立模型供应商配置")
public class ModelProviderController {

    /**
     * 参考实现 {@code ModelProviderPayload} 声明的字段集。
     *
     * <p>pydantic 默认 {@code extra="ignore"}，未声明的 JSON 字段在进入服务层前即被丢弃；
     * 本工程据此在入口处白名单过滤。
     */
    private static final Set<String> PAYLOAD_FIELDS =
            Set.of(
                    "provider_id",
                    "display_name",
                    "provider_type",
                    "default_protocol",
                    "base_url",
                    "embedding_base_url",
                    "rerank_base_url",
                    "models_endpoint",
                    "embedding_models_endpoint",
                    "rerank_models_endpoint",
                    "api_key_env",
                    "api_key",
                    "capabilities",
                    "enabled_models",
                    "headers_json",
                    "extra_json",
                    "is_enabled",
                    "is_builtin");

    /**
     * 显式传 null 会被当作「清空」的字段（参考实现 {@code update_provider} 里的 nullable_field 清单）。
     *
     * <p>{@code model_dump(exclude_none=True)} 会丢掉所有 null；参考实现只把这 8 个可空字段的
     * 显式 null 补回来，其余字段的显式 null 等同「未提供」。
     */
    private static final List<String> NULLABLE_FIELDS =
            List.of(
                    "api_key_env",
                    "api_key",
                    "default_protocol",
                    "embedding_base_url",
                    "rerank_base_url",
                    "models_endpoint",
                    "embedding_models_endpoint",
                    "rerank_models_endpoint");

    private final ModelProviderService modelProviderService;
    private final ModelProviderCache modelProviderCache;
    private final UserRepository userRepository;

    /** 获取独立模型供应商配置列表。 */
    @GetMapping
    @Operation(summary = "模型供应商列表", description = "每条附带 credential_status（ok / warning）")
    public Map<String, Object> listProviders() {
        requireAdminUser();
        List<Object> data = new ArrayList<>();
        for (ModelProvider provider : modelProviderService.getAllModelProviders()) {
            Map<String, Object> item = ModelProviderService.toDict(provider);
            item.put("credential_status", ModelProviderService.checkCredentialStatus(provider));
            data.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /** 创建独立模型供应商配置。 */
    @PostMapping
    @Operation(summary = "新建模型供应商")
    public Map<String, Object> createProvider(@RequestBody Map<String, Object> body) {
        User currentUser = requireAdminUser();
        try {
            ModelProvider provider =
                    modelProviderService.createProviderConfig(
                            filterPayload(body), currentUser.getUsername());
            refreshModelCache();
            return success(ModelProviderService.toDict(provider));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (Exception exc) {
            log.error("创建模型供应商失败: {}", exc.getMessage());
            throw new ApiHttpException(500, "创建模型供应商失败");
        }
    }

    /** 获取单个独立模型供应商配置。 */
    @GetMapping("/{provider_id}")
    @Operation(summary = "模型供应商详情")
    public Map<String, Object> getProvider(@PathVariable("provider_id") String providerId) {
        requireAdminUser();
        ModelProvider provider = modelProviderService.getModelProviderById(providerId);
        if (provider == null) {
            throw new ApiHttpException(404, "供应商 " + providerId + " 不存在");
        }
        Map<String, Object> data = ModelProviderService.toDict(provider);
        data.put("credential_status", ModelProviderService.checkCredentialStatus(provider));
        return success(data);
    }

    /** 更新独立模型供应商配置。 */
    @PutMapping("/{provider_id}")
    @Operation(summary = "更新模型供应商")
    public Map<String, Object> updateProvider(
            @PathVariable("provider_id") String providerId,
            @RequestBody Map<String, Object> body) {
        User currentUser = requireAdminUser();
        try {
            // 获取用户显式设置过的字段（即使值为 None），以便正确处理清空操作
            Map<String, Object> filtered = filterPayload(body);
            Map<String, Object> data = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : filtered.entrySet()) {
                if (entry.getValue() != null) {
                    data.put(entry.getKey(), entry.getValue());
                }
            }
            for (String nullableField : NULLABLE_FIELDS) {
                if (filtered.containsKey(nullableField) && filtered.get(nullableField) == null) {
                    data.put(nullableField, null);
                }
            }

            ModelProvider provider =
                    modelProviderService.updateProviderConfig(
                            providerId, data, currentUser.getUsername());
            if (provider == null) {
                throw new ApiHttpException(404, "供应商 " + providerId + " 不存在");
            }
            refreshModelCache();
            return success(ModelProviderService.toDict(provider));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (Exception exc) {
            log.error("更新模型供应商失败 {}: {}", providerId, exc.getMessage());
            throw new ApiHttpException(500, "更新模型供应商失败");
        }
    }

    /** 删除独立模型供应商配置。 */
    @DeleteMapping("/{provider_id}")
    @Operation(summary = "删除模型供应商")
    public Map<String, Object> deleteProvider(@PathVariable("provider_id") String providerId) {
        requireAdminUser();
        boolean deleted = modelProviderService.deleteProviderConfig(providerId);
        if (!deleted) {
            throw new ApiHttpException(404, "供应商 " + providerId + " 不存在");
        }
        refreshModelCache();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    /** 实时拉取远端 /models，不落库。 */
    @GetMapping("/{provider_id}/remote-models")
    @Operation(summary = "拉取远端模型清单")
    public Map<String, Object> getRemoteModels(@PathVariable("provider_id") String providerId) {
        requireAdminUser();
        ModelProvider provider = modelProviderService.getModelProviderById(providerId);
        if (provider == null) {
            throw new ApiHttpException(404, "供应商 " + providerId + " 不存在");
        }
        try {
            return success(modelProviderService.fetchRemoteModels(provider));
        } catch (ModelProviderService.HttpStatusFailure exc) {
            // 远程 API 返回的错误，不透传状态码避免前端误判为系统认证失败
            String detail = exc.getResponseText();
            if (exc.getStatusCode() == 401) {
                throw new ApiHttpException(502, "远端 API 认证失败，请检查 API Key 配置");
            }
            throw new ApiHttpException(exc.getStatusCode(), "Models 请求失败: " + detail);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("拉取远端模型失败 {}: {}", providerId, exc.getMessage());
            throw new ApiHttpException(400, "拉取远端模型失败: " + exc.getMessage());
        }
    }

    /** 强制刷新模型缓存，从数据库重新加载所有供应商配置到 Redis。 */
    @PostMapping("/models/cache/refresh")
    @Operation(summary = "强制刷新模型缓存")
    public Map<String, Object> refreshModelCacheEndpoint() {
        requireAdminUser();
        refreshModelCache();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "缓存已刷新");
        response.put("model_count", modelProviderCache.getAllSpecs().size());
        return response;
    }

    /** 获取 v2 格式的模型列表，按 provider 分组。 */
    @GetMapping("/models/v2")
    @Operation(summary = "v2 模型列表（按 provider 分组）")
    public Map<String, Object> getV2Models(
            @RequestParam(value = "model_type", defaultValue = "chat") String modelType) {
        AuthGuards.requireUser();

        Map<String, List<ModelInfo>> grouped =
                modelProviderCache.getSpecsGroupedByProvider(modelType);
        Map<String, String> providerNameById = new LinkedHashMap<>();
        for (ModelProvider provider : modelProviderService.getAllModelProviders()) {
            String displayName = provider.getDisplayName();
            providerNameById.put(
                    provider.getProviderId(),
                    displayName == null || displayName.isEmpty()
                            ? provider.getProviderId()
                            : displayName);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ModelInfo>> entry : grouped.entrySet()) {
            String providerId = entry.getKey();
            List<Object> models = new ArrayList<>();
            for (ModelInfo info : entry.getValue()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("spec", info.spec());
                item.put("model_id", info.modelId());
                item.put("display_name", info.displayName());
                item.put("dimension", info.dimension());
                item.put("batch_size", info.batchSize());
                models.add(item);
            }
            Map<String, Object> groupPayload = new LinkedHashMap<>();
            groupPayload.put("provider_id", providerId);
            groupPayload.put(
                    "provider_display_name",
                    providerNameById.getOrDefault(providerId, providerId));
            groupPayload.put("models", models);
            result.put(providerId, groupPayload);
        }
        return success(result);
    }

    /** 根据 full spec 检查模型状态（自动识别 V1/V2、Chat/Embedding）。 */
    @GetMapping("/models/status")
    @Operation(summary = "按 spec 测试模型状态")
    public Map<String, Object> getModelStatusBySpec(@RequestParam("spec") String spec) {
        requireAdminUser();
        try {
            return success(modelProviderService.testModelStatusBySpec(spec));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("测试模型状态失败 {}: {}", spec, exc.getMessage());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("spec", spec);
            data.put("status", "error");
            data.put("message", exc.getMessage());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("data", data);
            return response;
        }
    }

    // ==================== 内部工具 ====================

    /** 刷新模型缓存（CRUD 操作后调用）；失败只记日志，不影响已成功的写操作。 */
    private void refreshModelCache() {
        try {
            List<ModelProvider> providers = modelProviderService.getAllModelProviders();
            modelProviderCache.rebuild(providers);
            log.info(
                    "Model cache refreshed: {} models loaded",
                    modelProviderCache.getAllSpecs().size());
        } catch (Exception exc) {
            log.error("Failed to refresh model cache: {}", exc.getMessage());
        }
    }

    /** pydantic {@code extra="ignore"}：未声明字段在进入服务层前丢弃。 */
    private static Map<String, Object> filterPayload(Map<String, Object> body) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        if (body == null) {
            return filtered;
        }
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (PAYLOAD_FIELDS.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private static Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /** 参考实现 {@code get_admin_user}：管理员 + 载入用户实体（取 username 写审计字段）。 */
    private User requireAdminUser() {
        String uid = AuthGuards.requireAdmin();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }
}
