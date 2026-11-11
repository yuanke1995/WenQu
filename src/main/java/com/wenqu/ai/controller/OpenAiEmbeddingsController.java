package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.service.ApiKeyService;
import com.wenqu.ai.service.DynamicEmbeddingModel;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.UsageLedgerService;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TokenCounter;
import com.wenqu.ai.util.UsageAttr;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.wenqu.ai.openapi.OpenApi;
import com.wenqu.ai.openapi.OpenApiScopes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embeddings 端点：{@code POST /api/ai/v1/embeddings}。
 * <p>
 * 外部项目用任意 OpenAI SDK 把 base_url 指到问渠，就能以
 * {@code client.embeddings.create(input=[...], model="providerId/modelId")} 的语义
 * 借用问渠登记的向量模型——与 chat/completions、responses 同一 v1 命名空间、
 * 同一 API Key 通道、同一 scope 机制（本端点要求 {@code embedding}）。
 * <p>
 * <b>model 语义与问答端点的差别</b>：问答的 model 是智能体 id；这里 model 是向量模型引用
 * {@code providerId/modelId}（与知识库绑定向量模型的写法同一格式）。问渠没有全局默认向量模型
 * （向量按库绑定），所以 model 必填；可用清单走 {@code GET /api/ai/v1/models/embeddings}。
 * <p>
 * <b>计量</b>：每笔调用落一行 {@code c_ai_usage_log}（kind=embedding，按 api_key_id 归属），
 * token 数以网关返回 usage 为准，网关未返回时按 cl100k BPE 估算并标 estimated。
 * <p>
 * <b>边界</b>：单批 ≤256 条、单条 ≤8192 token（超限 400）；按用户/IP 限频
 * （ratelimit.embeddingPerMinute）。供应商额度不足由 {@code DynamicEmbeddingModel}
 * 的额度包装登记并换成可行动文案。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Embeddings（OpenAI 兼容）", description = "外部项目以 OpenAI SDK 语义调用问渠登记的向量模型")
public class OpenAiEmbeddingsController {

    private final DynamicEmbeddingModel embeddingModel;
    private final ModelRegistryService modelRegistryService;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final UsageLedgerService usageLedgerService;

    /** 单批条数上限（对齐主流网关的批量口径，防单请求吃爆内存与供应商配额） */
    private static final int INPUT_MAX_COUNT = 256;
    /** 单条 token 上限（cl100k 计数；主流向量模型窗口普遍 ≥8192） */
    private static final int INPUT_MAX_TOKENS = 8192;

    @Operation(summary = "Embeddings（OpenAI 兼容）",
            description = "body: {\"input\": \"文本\" 或 [\"文本1\",\"文本2\"], \"model\": \"providerId/modelId\", "
                    + "\"encoding_format\": \"float\"(默认)|\"base64\", \"dimensions\": 可选}。"
                    + "model 为向量模型引用（必填，问渠向量按库绑定、无全局默认）；"
                    + "可用清单见 GET /api/ai/v1/models/embeddings。API Key 需开通 embedding scope")
    @OpenApi(scope = OpenApiScopes.EMBEDDING, summary = "Embeddings（OpenAI 兼容）")
    @PostMapping("/api/ai/v1/embeddings")
    public org.springframework.http.ResponseEntity<?> embeddings(@RequestBody JSONObject body) {
        long start = System.currentTimeMillis();
        String uid = RequestUser.uid();
        String apiKeyId = apiKeyIdOf();
        // scope 闸只对 Key 通道生效（JWT 登录态是本人，不受 scope 约束）
        try {
            apiKeyService.requireScope(apiKeyId, ApiKeyService.SCOPE_EMBEDDING);
        } catch (BizException e) {
            return errorResponse(e.getCode(), "permission_error", e.getMessage());
        }
        try {
            rateLimitService.checkRateLimit("embedding", uid);
        } catch (BizException e) {
            return errorResponse(429, "rate_limit_error", e.getMessage());
        }

        String modelRef = body.getString("model");
        if (modelRef == null || modelRef.isBlank()) {
            return bad("invalid_request_error", "model 必填，为向量模型引用 providerId/modelId"
                    + "（可用清单：GET /api/ai/v1/models/embeddings）");
        }
        ModelRegistryService.ModelRoute route = modelRegistryService.resolveReference(modelRef.trim());
        if (route == null) {
            return bad("invalid_request_error", "model 引用无效：「" + modelRef + "」，应为 providerId/modelId 格式"
                    + "（可用清单：GET /api/ai/v1/models/embeddings）");
        }
        // 归属闸：个人供应商只有登记人自己可用（与问答链路同一判据）
        try {
            modelRegistryService.assertUsable(modelRef.trim(), uid, RequestUser.role());
        } catch (BizException e) {
            return errorResponse(403, "permission_error", e.getMessage());
        }

        // input：string 或 string[]（OpenAI 还允许 token 数组，这里如实拒绝，不静默曲解语义）
        List<String> texts = new ArrayList<>();
        Object raw = body.get("input");
        if (raw instanceof String s) {
            texts.add(s);
        } else if (raw instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (!(o instanceof String s)) {
                    return bad("invalid_request_error", "input 数组只支持字符串元素（token 数组请调用方自行解码）");
                }
                texts.add(s);
            }
        } else {
            return bad("invalid_request_error", "input 必填：字符串或字符串数组");
        }
        if (texts.isEmpty()) return bad("invalid_request_error", "input 不能为空数组");
        if (texts.size() > INPUT_MAX_COUNT) {
            return bad("invalid_request_error", "input 条数超上限（最多 " + INPUT_MAX_COUNT + " 条）");
        }
        long promptTokens = 0;
        for (int i = 0; i < texts.size(); i++) {
            int tokens = TokenCounter.estimate(texts.get(i));
            if (tokens > INPUT_MAX_TOKENS) {
                return bad("invalid_request_error", "第 " + (i + 1) + " 条 input 超长（" + tokens
                        + " token，上限 " + INPUT_MAX_TOKENS + "）");
            }
            promptTokens += tokens;
        }

        boolean base64 = "base64".equalsIgnoreCase(body.getString("encoding_format"));
        Integer dimensions = body.getInteger("dimensions");

        EmbeddingResponse resp;
        try {
            org.springframework.ai.embedding.EmbeddingModel model = embeddingModel.forRef(modelRef.trim());
            OpenAiEmbeddingOptions.Builder opts = OpenAiEmbeddingOptions.builder().model(route.modelId());
            if (dimensions != null && dimensions > 0) opts.dimensions(dimensions);
            resp = model.call(new EmbeddingRequest(texts, opts.build()));
        } catch (IllegalArgumentException e) {
            return bad("invalid_request_error", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[EMBEDDINGS] 向量调用失败: model={} uid={} : {}", modelRef, uid, e.getMessage());
            return errorResponse(502, "api_error", "向量模型调用失败：" + e.getMessage());
        }

        List<Embedding> results = resp == null ? List.of() : resp.getResults();
        if (results == null || results.size() != texts.size()) {
            return errorResponse(502, "api_error", "向量模型返回结果条数与请求不一致");
        }
        List<Map<String, Object>> data = new ArrayList<>();
        int dimension = 0;
        for (int i = 0; i < results.size(); i++) {
            float[] vec = results.get(i).getOutput();
            if (vec == null) return errorResponse(502, "api_error", "向量模型返回空向量（第 " + (i + 1) + " 条）");
            dimension = vec.length;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("object", "embedding");
            item.put("index", i);
            if (base64) {
                item.put("embedding", toBase64(vec));
            } else {
                List<Float> list = new ArrayList<>(vec.length);
                for (float v : vec) list.add(v);
                item.put("embedding", list);
            }
            data.add(item);
        }

        // 计量：网关 usage 优先（真实值），缺失时按 BPE 估算兜底（estimated 标记非真实值）
        String modelCanonical = route.providerId() + "/" + route.modelId();
        UsageAttr.Attr attr = UsageAttr.of(uid, null, null, "embedding", apiKeyId);
        org.springframework.ai.chat.metadata.Usage usage =
                resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
        long realPrompt = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        if (realPrompt > 0) {
            usageLedgerService.recordCall(attr, modelCanonical, usage);
        } else {
            usageLedgerService.recordEstimated(attr, modelCanonical, promptTokens, 0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "list");
        out.put("data", data);
        out.put("model", modelCanonical);
        Map<String, Object> usageOut = new LinkedHashMap<>();
        usageOut.put("prompt_tokens", realPrompt > 0 ? realPrompt : promptTokens);
        usageOut.put("total_tokens", realPrompt > 0 ? realPrompt : promptTokens);
        out.put("usage", usageOut);
        if (dimension > 0) out.put("dimension", dimension);
        log.info("[EMBEDDINGS] 完成: model={} uid={} key={} batch={} dim={} 耗时={}ms",
                modelCanonical, uid, apiKeyId == null ? "-" : apiKeyId, texts.size(), dimension,
                System.currentTimeMillis() - start);
        return org.springframework.http.ResponseEntity.ok(out);
    }

    @Operation(summary = "可用向量模型清单",
            description = "当前身份可用的 embedding 模型（按归属过滤后的 enabled 供应商 × enabled 模型，"
                    + "分组结构含 ref=providerId/modelId 直接可作 embeddings 请求的 model 参数；不含 baseUrl/apiKey）")
    @OpenApi(scope = OpenApiScopes.EMBEDDING, summary = "可用向量模型清单")
    @GetMapping("/api/ai/v1/models/embeddings")
    public org.springframework.http.ResponseEntity<?> availableEmbeddingModels() {
        try {
            return org.springframework.http.ResponseEntity.ok(Map.of(
                    "object", "list",
                    "data", modelRegistryService.available(ModelRegistryService.TYPE_EMBEDDING,
                            RequestUser.uid(), RequestUser.role())));
        } catch (RuntimeException e) {
            return errorResponse(500, "api_error", "模型清单获取失败：" + e.getMessage());
        }
    }

    /** OpenAI 的 base64 编码口径：float32 小端逐元素拼接后 base64 */
    private static String toBase64(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) buf.putFloat(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    /** 本请求的 API Key id（Key 通道由拦截器装载；JWT 通道为 null——scope 判定据此分流） */
    private String apiKeyIdOf() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        Object v = attrs == null ? null : attrs.getAttribute(
                com.wenqu.ai.config.SecurityConfig.ATTR_API_KEY_ID,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return v == null ? null : String.valueOf(v);
    }

    /** OpenAI 风格错误体（SDK 按 error.message/type 展示，与 chat/completions 端点同一形状） */
    private org.springframework.http.ResponseEntity<?> errorResponse(int status, String type, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message == null ? "调用失败" : message);
        err.put("type", type);
        String rid = com.wenqu.ai.openapi.RequestIdFilter.current();
        err.put("code", type);
        err.put("request_id", rid == null ? "" : rid);
        return org.springframework.http.ResponseEntity.status(status)
                .body(Map.of("error", err));
    }

    private org.springframework.http.ResponseEntity<?> bad(String type, String message) {
        return errorResponse(400, type, message);
    }
}
