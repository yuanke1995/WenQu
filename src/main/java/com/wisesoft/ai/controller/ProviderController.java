package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.util.RequestUser;
import com.wisesoft.ai.service.ConnectivityProbeService;
import com.wisesoft.ai.service.ModelRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型供应商接口：供应商 CRUD + 远程拉取模型列表 + 可用模型清单（登录即可用，
 * 供聊天页模型选择器 / 智能体编辑 / 个人设置）。
 * <p>
 * 归属（2026-09-26）：管理员级建的 = 平台级（所有人可见可用，只读给普通用户）；
 * 普通用户建的 = 个人级（ownerUid=本人，只有自己可见可用，不共享）。
 * 写操作一律先按归属判权，越权 fail-loud，不做静默忽略。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/provider")
@RequiredArgsConstructor
@Tag(name = "模型供应商", description = "供应商管理与模型库（OpenAI 兼容网关）")
public class ProviderController {

    private final ModelRegistryService modelRegistryService;
    private final ConnectivityProbeService connectivityProbeService;

    @Operation(summary = "供应商列表", description = "按归属过滤：管理员级见全部；普通用户见「平台级 + 自己登记的」。apiKey 脱敏；每行带 platform/mine/manageable")
    @GetMapping
    public ResultJson list() {
        return ResultJson.ok(modelRegistryService.listProviders(RequestUser.uid(), RequestUser.role()));
    }

    @Operation(summary = "可用模型清单", description = "登录即可用（无需管理员）：按归属过滤后，enabled 供应商下 enabled 模型按供应商分组，预先拼好引用串 ref=providerId/modelId；type 过滤（chat/vision/embedding/rerank），不含 baseUrl/apiKey")
    @GetMapping("/available")
    public ResultJson available(
            @Parameter(description = "模型类型过滤（空=全部）") @RequestParam(value = "type", required = false) String type) {
        return ResultJson.ok(modelRegistryService.available(type, RequestUser.uid(), RequestUser.role()));
    }

    @Operation(summary = "新建供应商", description = "{\"name\":\"DeepSeek\",\"icon\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com\",\"apiKey\":\"明文（保存时 RSA 加密）\",\"completionsPath\":\"可选\",\"embeddingsPath\":\"可选\",\"enabled\":true}。管理员级建的=平台级（所有人可用），普通用户建的=个人级（仅自己可用）")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(modelRegistryService.saveProvider(
                null, str(body.get("name")), str(body.get("icon")), str(body.get("baseUrl")),
                str(body.get("apiKey")), str(body.get("completionsPath")), str(body.get("embeddingsPath")),
                str(body.get("apiType")), bool(body.get("enabled")), str(body.get("remark")),
                intOrNull(body.get("sortOrder")), RequestUser.uid(), RequestUser.role()), "已创建");
    }

    @Operation(summary = "更新供应商", description = "字段同新建；apiKey 留空或 **** 掩码表示不修改已存密钥。普通用户仅可改自己登记的（平台级供应商只读）")
    @PutMapping("/{id}")
    public ResultJson update(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        return ResultJson.ok(modelRegistryService.saveProvider(
                id, str(body.get("name")), str(body.get("icon")), str(body.get("baseUrl")),
                str(body.get("apiKey")), str(body.get("completionsPath")), str(body.get("embeddingsPath")),
                str(body.get("apiType")), bool(body.get("enabled")), str(body.get("remark")),
                intOrNull(body.get("sortOrder")), RequestUser.uid(), RequestUser.role()), "已保存");
    }

    @Operation(summary = "启用/停用供应商", description = "{\"enabled\":true|false}；停用后其模型不出现在可用清单。普通用户仅可操作自己登记的")
    @PutMapping("/{id}/enabled")
    public ResultJson setEnabled(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        modelRegistryService.setProviderEnabled(id, bool(body.get("enabled")));
        return ResultJson.ok("已更新");
    }

    @Operation(summary = "删除供应商", description = "连同其模型登记一并删除；仍被智能体/用户偏好/系统配置引用时拒绝。普通用户仅可删自己登记的")
    @DeleteMapping("/{id}")
    public ResultJson delete(
            @Parameter(description = "供应商ID") @PathVariable("id") String id) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        modelRegistryService.deleteProvider(id);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "供应商模型列表", description = "某供应商下已登记的模型（含类型/启停）；属管理动作，普通用户仅可查自己登记的")
    @GetMapping("/{id}/models")
    public ResultJson models(
            @Parameter(description = "供应商ID") @PathVariable("id") String id) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        return ResultJson.ok(modelRegistryService.listModels(id));
    }

    @Operation(summary = "批量保存供应商模型", description = "全量同步语义：[{modelId, modelType(chat/vision/embedding/rerank/other), displayName?, enabled?, remark?}]；不在清单中的已登记模型会被删除。普通用户仅可改自己登记的")
    @PutMapping("/{id}/models")
    public ResultJson saveModels(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody List<Map<String, Object>> models) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        modelRegistryService.saveModels(id, models);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "远程拉取模型列表", description = "GET {baseUrl}/v1/models（Bearer 鉴权）获取网关模型候选，不入库；返回 [{modelId, guessedType(自动分类), exists(已登记)}]。providerId 传入时 apiKey 可用 **** 掩码（用库中真实 Key），但须对该供应商有管理权")
    @PostMapping("/models/fetch")
    public ResultJson fetchModels(@RequestBody Map<String, Object> body) {
        String providerId = str(body.get("providerId"));
        if (providerId != null && !providerId.isBlank()) {
            ResultJson denied = denyUnlessManageable(providerId);
            if (denied != null) return denied;
        }
        return ResultJson.ok(modelRegistryService.fetchRemoteModels(
                providerId, str(body.get("baseUrl")), str(body.get("apiKey"))));
    }

    @Operation(summary = "供应商连通性测试", description = "先测后存：用表单未保存值探测（modelType 决定探测方式：chat/vision 发最小补全、embedding 发真实向量、rerank 探测服务）；providerId 传入时 apiKey 可用掩码（用库中真实密钥），但须对该供应商有管理权")
    @PostMapping("/test")
    public ResultJson test(@RequestBody Map<String, Object> body) {
        String providerId = str(body.get("providerId"));
        String modelType = str(body.get("modelType"));
        String baseUrl = str(body.get("baseUrl"));
        String apiKey = str(body.get("apiKey"));
        String model = str(body.get("model"));
        // 掩码 Key 回退库中真实值（RSA 解密；编辑已存供应商时前端只回显掩码）——须先确认有权管理它，
        // 否则普通用户可借他人 providerId 让服务端用别人的 Key 发起请求
        if (providerId != null && !providerId.isBlank() && (apiKey == null || apiKey.isBlank() || apiKey.startsWith("****"))) {
            ResultJson denied = denyUnlessManageable(providerId);
            if (denied != null) return denied;
            apiKey = modelRegistryService.decryptedApiKey(providerId);
        }
        String group = switch (modelType == null ? "" : modelType) {
            case ModelRegistryService.TYPE_VISION -> "vision";
            case ModelRegistryService.TYPE_EMBEDDING -> "embedding";
            case ModelRegistryService.TYPE_RERANK -> "rerank";
            default -> "chat";
        };
        String path = str(body.get("completionsPath"));
        return ResultJson.ok(connectivityProbeService.probe(group, baseUrl, apiKey, model, path));
    }

    /**
     * 写操作前置校验：请求者必须对该供应商有管理权——管理员级可管全部；
     * 普通用户只能管自己登记的个人级供应商（平台级共享供应商对他们只读）。
     *
     * @return null = 放行；非 null = 直接返回该拒绝结果（fail-loud，不静默跳过）
     */
    private ResultJson denyUnlessManageable(String providerId) {
        com.wisesoft.ai.model.Provider p = modelRegistryService.providerById(providerId);
        if (p == null) return ResultJson.error("供应商不存在（可能已被删除，请刷新后重试）");
        if (modelRegistryService.canManage(p, RequestUser.uid(), RequestUser.role())) return null;
        return ResultJson.error("仅可管理自己登记的供应商；「" + p.getName() + "」是平台共享供应商，对普通用户只读");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Boolean bool(Object o) {
        return o == null || Boolean.parseBoolean(String.valueOf(o));
    }

    private static Integer intOrNull(Object o) {
        try {
            return o == null ? null : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
