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
 * 模型供应商接口：供应商 CRUD（管理员）+ 远程拉取模型列表 + 可用模型清单（登录即可用，
 * 供聊天页模型选择器 / 智能体编辑 / 个人设置）。
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

    @Operation(summary = "供应商列表", description = "全部供应商（含停用），apiKey 脱敏显示，附各类型模型数量统计")
    @GetMapping
    public ResultJson list() {
        return ResultJson.ok(modelRegistryService.listProviders());
    }

    @Operation(summary = "可用模型清单", description = "登录即可用（无需管理员）：enabled 供应商下 enabled 模型按供应商分组，预先拼好引用串 ref=providerId/modelId；type 过滤（chat/vision/embedding/rerank），不含 baseUrl/apiKey")
    @GetMapping("/available")
    public ResultJson available(
            @Parameter(description = "模型类型过滤（空=全部）") @RequestParam(value = "type", required = false) String type) {
        return ResultJson.ok(modelRegistryService.available(type));
    }

    @Operation(summary = "新建供应商", description = "{\"name\":\"DeepSeek\",\"icon\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com\",\"apiKey\":\"明文（保存时 RSA 加密）\",\"completionsPath\":\"可选\",\"embeddingsPath\":\"可选\",\"enabled\":true}")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(modelRegistryService.saveProvider(
                null, str(body.get("name")), str(body.get("icon")), str(body.get("baseUrl")),
                str(body.get("apiKey")), str(body.get("completionsPath")), str(body.get("embeddingsPath")),
                str(body.get("apiType")), bool(body.get("enabled")), str(body.get("remark")),
                intOrNull(body.get("sortOrder")), RequestUser.uid()), "已创建");
    }

    @Operation(summary = "更新供应商", description = "字段同新建；apiKey 留空或 **** 掩码表示不修改已存密钥")
    @PutMapping("/{id}")
    public ResultJson update(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return ResultJson.ok(modelRegistryService.saveProvider(
                id, str(body.get("name")), str(body.get("icon")), str(body.get("baseUrl")),
                str(body.get("apiKey")), str(body.get("completionsPath")), str(body.get("embeddingsPath")),
                str(body.get("apiType")), bool(body.get("enabled")), str(body.get("remark")),
                intOrNull(body.get("sortOrder")), RequestUser.uid()), "已保存");
    }

    @Operation(summary = "启用/停用供应商", description = "{\"enabled\":true|false}；停用后其模型不出现在可用清单")
    @PutMapping("/{id}/enabled")
    public ResultJson setEnabled(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        modelRegistryService.setProviderEnabled(id, bool(body.get("enabled")));
        return ResultJson.ok("已更新");
    }

    @Operation(summary = "删除供应商", description = "连同其模型登记一并删除；仍被智能体/用户偏好/系统配置引用时拒绝")
    @DeleteMapping("/{id}")
    public ResultJson delete(
            @Parameter(description = "供应商ID") @PathVariable("id") String id) {
        modelRegistryService.deleteProvider(id);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "供应商模型列表", description = "某供应商下已登记的模型（含类型/启停）")
    @GetMapping("/{id}/models")
    public ResultJson models(
            @Parameter(description = "供应商ID") @PathVariable("id") String id) {
        return ResultJson.ok(modelRegistryService.listModels(id));
    }

    @Operation(summary = "批量保存供应商模型", description = "全量同步语义：[{modelId, modelType(chat/vision/embedding/rerank/other), displayName?, enabled?, remark?}]；不在清单中的已登记模型会被删除")
    @PutMapping("/{id}/models")
    public ResultJson saveModels(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody List<Map<String, Object>> models) {
        modelRegistryService.saveModels(id, models);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "远程拉取模型列表", description = "GET {baseUrl}/v1/models（Bearer 鉴权）获取网关模型候选，不入库；返回 [{modelId, guessedType(自动分类), exists(已登记)}]。providerId 传入时 apiKey 可用 **** 掩码（用库中真实 Key）")
    @PostMapping("/models/fetch")
    public ResultJson fetchModels(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(modelRegistryService.fetchRemoteModels(
                str(body.get("providerId")), str(body.get("baseUrl")), str(body.get("apiKey"))));
    }

    @Operation(summary = "供应商连通性测试", description = "先测后存：用表单未保存值探测（modelType 决定探测方式：chat/vision 发最小补全、embedding 发真实向量、rerank 探测服务）；providerId 传入时 apiKey 可用掩码")
    @PostMapping("/test")
    public ResultJson test(@RequestBody Map<String, Object> body) {
        String providerId = str(body.get("providerId"));
        String modelType = str(body.get("modelType"));
        String baseUrl = str(body.get("baseUrl"));
        String apiKey = str(body.get("apiKey"));
        String model = str(body.get("model"));
        // 掩码 Key 回退库中真实值（RSA 解密；编辑已存供应商时前端只回显掩码）
        if (providerId != null && !providerId.isBlank() && (apiKey == null || apiKey.isBlank() || apiKey.startsWith("****"))) {
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
