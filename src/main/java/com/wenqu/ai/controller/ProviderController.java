package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.util.BatchResults;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.service.ConnectivityProbeService;
import com.wenqu.ai.service.ModelQuotaGuard;
import com.wenqu.ai.service.ModelQuotaService;
import com.wenqu.ai.service.ModelReferenceScanner;
import com.wenqu.ai.service.ModelRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型供应商接口：供应商 CRUD + 远程拉取模型列表 + 可用模型清单（登录即可用，
 * 供聊天页模型选择器 / 智能体编辑 / 个人设置）。
 * <p>
 * 归属（2026-10）：谁建归谁——供应商一律归属创建人（ownerUid=创建人 uid），仅归属人可见可用，
 * 不存在平台共享。管理员级保留全部供应商的管理视角（运维需要），但其新建的同样归本人。
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
    private final ModelReferenceScanner referenceScanner;
    private final ModelQuotaService quotaService;

    @Operation(summary = "供应商列表", description = "按归属过滤：仅见自己登记的（数据按 userId 隔离）。apiKey 脱敏；每行带 ownerUid/manageable")
    @GetMapping
    public ResultJson list() {
        return ResultJson.ok(modelRegistryService.listProviders(RequestUser.uid(), RequestUser.role()));
    }

    @Operation(summary = "可用模型清单", description = "登录即可用（无需管理员）：按归属过滤后，enabled 供应商下 enabled 模型按供应商分组，预先拼好引用串 ref=providerId/modelId；type 过滤（chat/vision/embedding/rerank），不含 baseUrl/apiKey")
    @GetMapping("/available")
    public ResultJson available(
            @Parameter(description = "模型类型过滤（空=全部；支持逗号分隔多类型，如 vision,ocr）") @RequestParam(value = "type", required = false) String type) {
        return ResultJson.ok(modelRegistryService.available(type, RequestUser.uid(), RequestUser.role()));
    }

    @Operation(summary = "新建供应商", description = "{\"name\":\"DeepSeek\",\"icon\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com\",\"apiKey\":\"明文（保存时 RSA 加密）\",\"completionsPath\":\"可选\",\"embeddingsPath\":\"可选\",\"enabled\":true}。谁建归谁：仅创建人可见可用")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(modelRegistryService.saveProvider(
                null, str(body.get("name")), str(body.get("icon")), str(body.get("baseUrl")),
                str(body.get("apiKey")), str(body.get("completionsPath")), str(body.get("embeddingsPath")),
                str(body.get("apiType")), bool(body.get("enabled")), str(body.get("remark")),
                intOrNull(body.get("sortOrder")), RequestUser.uid()), "已创建");
    }

    @Operation(summary = "更新供应商", description = "字段同新建；apiKey 留空或 **** 掩码表示不修改已存密钥。仅可改自己登记的")
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
                intOrNull(body.get("sortOrder")), RequestUser.uid()), "已保存");
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
        modelRegistryService.deleteProvider(id, referenceScanner);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "供应商被引用清单", description = "列出该供应商下模型当前被哪些位置引用（知识库向量/图谱模型、"
            + "个人默认模型、个人设置、系统配置槽位、游客对话模型），每条带类型标签、可读名称与跳转 ID。"
            + "用于额度不足时定位「该去改哪几处」；与删除守门同一口径。返回 "
            + "{total, editableCount, hasBlocking, byKind, items:[{kind,label,name,id,hint,editable}]}")
    @GetMapping("/{id}/references")
    public ResultJson references(
            @Parameter(description = "供应商ID") @PathVariable("id") String id) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        // 会话级覆盖的提示项由扫描器统一附带（overviewOf 内已 add sessionHint），
        // 控制器不自行拼装——否则两处各记一份「有哪些引用」，必然漂移
        return ResultJson.ok(referenceScanner.overviewOf(id));
    }

    // --------------------------------------------------------------------------------------------------
    // 批量操作：逐条执行、部分成功是批量的固有语义——失败条目逐条带原因（结构收口在 BatchResults）
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "批量删除供应商", description = "body: {ids:[...]}；逐条按单条口径判权（仅可删自己登记的），"
            + "仍被引用的条目失败并带原因；返回 {succeeded:[id], failed:[{id,name,error}]}")
    @PostMapping("/batch-delete")
    public ResultJson batchDelete(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        if (ids.isEmpty()) return ResultJson.error("请先选择要删除的供应商");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            com.wenqu.ai.model.Provider p = modelRegistryService.providerById(id);
            if (p == null) {
                failed.add(BatchResults.failItem(id, null, "供应商不存在（可能已被删除）"));
                continue;
            }
            if (!modelRegistryService.canManage(p, RequestUser.uid(), RequestUser.role())) {
                failed.add(BatchResults.failItem(id, p.getName(), "仅可管理自己登记的供应商"));
                continue;
            }
            try {
                modelRegistryService.deleteProvider(id, referenceScanner);
                succeeded.add(id);
            } catch (Exception e) {
                failed.add(BatchResults.failItem(id, p.getName(), BatchResults.errMsg(e)));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }

    @Operation(summary = "批量启用/停用供应商", description = "body: {ids:[...], enabled:true|false}；逐条按单条口径判权；"
            + "停用后其模型不出现在可用清单；返回 {succeeded:[id], failed:[{id,name,error}]}")
    @PostMapping("/batch-enabled")
    public ResultJson batchEnabled(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        if (ids.isEmpty()) return ResultJson.error("请先选择要操作的供应商");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            com.wenqu.ai.model.Provider p = modelRegistryService.providerById(id);
            if (p == null) {
                failed.add(BatchResults.failItem(id, null, "供应商不存在（可能已被删除）"));
                continue;
            }
            if (!modelRegistryService.canManage(p, RequestUser.uid(), RequestUser.role())) {
                failed.add(BatchResults.failItem(id, p.getName(), "仅可管理自己登记的供应商"));
                continue;
            }
            try {
                modelRegistryService.setProviderEnabled(id, enabled);
                succeeded.add(id);
            } catch (Exception e) {
                failed.add(BatchResults.failItem(id, p.getName(), BatchResults.errMsg(e)));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }

    @Operation(summary = "供应商模型列表", description = "某供应商下已登记的模型（含类型/启停）；属管理动作，普通用户仅可查自己登记的")
    @GetMapping("/{id}/models")
    public ResultJson models(
            @Parameter(description = "供应商ID") @PathVariable("id") String id) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        return ResultJson.ok(modelRegistryService.listModels(id));
    }

    @Operation(summary = "批量保存供应商模型", description = "全量同步语义：[{modelId, modelType(chat/vision/embedding/rerank/audio/omni/other), visionCapable(图片理解三态 1/0/auto), toolCapable(工具调用三态 1/0/auto), thinking(auto/none/switchable/always), reasoningLevels(思考强度档位数组), defaultReasoningLevel(默认强度，须在档位内), displayName?, enabled?, remark?}]；不在清单中的已登记模型会被删除。普通用户仅可改自己登记的")
    @PutMapping("/{id}/models")
    public ResultJson saveModels(
            @Parameter(description = "供应商ID") @PathVariable("id") String id,
            @RequestBody List<Map<String, Object>> models) {
        ResultJson denied = denyUnlessManageable(id);
        if (denied != null) return denied;
        modelRegistryService.saveModels(id, models);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "远程拉取模型列表", description = "GET {baseUrl}/v1/models（Bearer 鉴权）获取网关模型候选，不入库；返回 [{modelId, guessedType(自动分类), guessVisionCapable(图片理解自动预填), exists(已登记)}]。providerId 传入时 apiKey 可用 **** 掩码（用库中真实 Key），但须对该供应商有管理权")
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

    @Operation(summary = "供应商连通性测试", description = "先测后存：用表单未保存值探测（modelType 决定探测方式：chat/vision/omni 发最小补全、embedding 发真实向量、rerank 探测服务、audio 探网关可达）；providerId 传入时 apiKey 可用掩码（用库中真实密钥），但须对该供应商有管理权")
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
            case ModelRegistryService.TYPE_VISION, ModelRegistryService.TYPE_OCR -> "vision";
            case ModelRegistryService.TYPE_EMBEDDING -> "embedding";
            case ModelRegistryService.TYPE_RERANK -> "rerank";
            case ModelRegistryService.TYPE_OMNI -> "chat";      // 全模态走 chat completions，可真实探测
            case ModelRegistryService.TYPE_AUDIO -> "audio";    // 语音：网关可达性探测（ASR/TTS 协议各家不一）
            default -> "chat";
        };
        String path = str(body.get("completionsPath"));
        Map<String, Object> probe = connectivityProbeService.probe(group, baseUrl, apiKey, model, path);
        // 探测结果同样要反映到额度状态：用户充值/换 Key 后测一次就解除禁选（不必等下一次真实调用）；
        // 反过来，测出余额不足时也立刻登记——用户在设置页点一次就能确认问题，不用先被问答打脸。
        if (providerId != null && !providerId.isBlank()) {
            boolean available = Boolean.TRUE.equals(probe.get("available"));
            String detail = str(probe.get("detail"));
            if (available) {
                quotaService.markRecovered(providerId);
            } else if (ModelQuotaGuard.isQuotaExhausted(
                    new IllegalStateException(detail == null ? "" : detail))) {
                quotaService.markExhausted(providerId, model, detail);
            }
        }
        return ResultJson.ok(probe);
    }

    /**
     * 写操作前置校验：请求者必须对该供应商有管理权——归属人本人；管理员级可管全部（运维视角）。
     *
     * @return null = 放行；非 null = 直接返回该拒绝结果（fail-loud，不静默跳过）
     */
    private ResultJson denyUnlessManageable(String providerId) {
        com.wenqu.ai.model.Provider p = modelRegistryService.providerById(providerId);
        if (p == null) return ResultJson.error("供应商不存在（可能已被删除，请刷新后重试）");
        if (modelRegistryService.canManage(p, RequestUser.uid(), RequestUser.role())) return null;
        return ResultJson.error("仅可管理自己登记的供应商；「" + p.getName() + "」不是你登记的");
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
