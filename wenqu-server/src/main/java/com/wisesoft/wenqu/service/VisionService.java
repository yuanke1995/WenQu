package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视觉模型服务（OpenAI 兼容协议，裸 HTTP 调用）
 * 用于将文档中提取的图片转换为文字描述，供向量检索命中
 *
 * @author yuanke
 */
@Slf4j
@Service
public class VisionService {

    private final AppProperties properties;
    private final ConfigService configService;
    private final ImageDescCache imageDescCache;
    private final RestClient restClient;

    public VisionService(AppProperties properties, ConfigService configService, ImageDescCache imageDescCache) {
        this.properties = properties;
        this.configService = configService;
        this.imageDescCache = imageDescCache;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(properties.getVision().getTimeoutMillis());
        // 不设固定 baseUrl：网关地址每次调用动态读 DB（vision.baseUrl 热切换，保存即生效），请求用绝对 URI
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 生成图片文字描述（使用配置的默认提示词）；任何失败返回 ""（降级，不中断主流程）
     */
    public String describe(byte[] imageBytes, String ext) {
        return describe(imageBytes, ext, configService.get("vision.prompt"));
    }

    /**
     * 生成图片文字描述（自定义提示词，如 OCR）；任何失败返回 ""（降级，不中断主流程）
     * 先查内容寻址缓存（同图+同模型+同提示词直接复用上次结果，避免重解析重复调 VLM）；
     * 未命中调 VLM，成功写缓存。失败自动重试 retryCount 次（Ollama 偶发 500/超时）
     */
    public String describe(byte[] imageBytes, String ext, String prompt) {
        // L12 fail-loud：vision.enabled 配置化（设置页可改，保存即生效；未配置时回退 yml/环境变量值）
        String cfgEnabled = configService.get("vision.enabled");
        boolean enabled = cfgEnabled == null ? properties.getVision().isEnabled() : Boolean.parseBoolean(cfgEnabled.trim());
        if (!enabled || imageBytes == null || imageBytes.length == 0) {
            if (!enabled && imageBytes != null && imageBytes.length > 0) {
                log.debug("视觉模型已关闭（vision.enabled=false），跳过图片描述");
            }
            return "";
        }
        String model = configService.get("vision.model");
        String cacheKey = imageDescCache.key(imageBytes, prompt, model);
        if (cacheKey != null) {
            String cached = imageDescCache.get(cacheKey);
            if (cached != null) {
                log.debug("[Vision] 图片描述缓存命中");
                return cached;
            }
        }

        int retry = Math.max(0, properties.getVision().getRetryCount());
        Exception lastErr = null;
        String result = "";
        for (int attempt = 0; attempt <= retry; attempt++) {
            try {
                String desc = callOnce(imageBytes, ext, prompt);
                if (desc != null && !desc.isBlank()) {
                    result = desc;
                    break;
                }
                // 空响应：模型偶发空输出，重试一次
                if (attempt < retry) log.warn("图片描述为空，第 {} 次重试", attempt + 1);
            } catch (Exception e) {
                lastErr = e;
                if (attempt < retry) log.warn("图片描述失败(第 {} 次)，重试: {}", attempt + 1, e.getMessage());
            }
        }
        if (result.isBlank()) {
            log.warn("图片描述最终失败: {}", lastErr == null ? "空响应" : lastErr.getMessage());
        } else if (cacheKey != null) {
            imageDescCache.put(cacheKey, result, model);
        }
        return result;
    }

    private String callOnce(byte[] imageBytes, String ext, String prompt) {
        String mime = mimeOf(ext);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> body = new HashMap<>();
        // 模型配置界面：视觉模型名动态读 DB（保存即生效）
        body.put("model", configService.get("vision.model"));
        // qwen3 系列默认思考模式：关闭以提速且输出稳定（实测 max_tokens 在思考模型下会导致空输出，保持 0 不发送）
        if (!properties.getVision().isThink()) {
            body.put("think", false);
        }
        // Ollama 支持 keep_alive 保持模型常驻，避免每个文档解析都重新加载模型（云端服务不支持需配置为 0）
        int keepAlive = properties.getVision().getKeepAliveMinutes();
        if (keepAlive > 0) {
            body.put("keep_alive", keepAlive + "m");
        }
        // Ollama num_ctx：1280px 识别图视觉 token 约 1600-2500，默认 4096 会截断描述输出；
        // 云端服务（阿里云 MaaS 等）通常忽略未知字段，若报错可将 vision.num-ctx 置 0 关闭
        int numCtx = properties.getVision().getNumCtx();
        if (numCtx > 0) {
            body.put("options", Map.of("num_ctx", numCtx));
        }
        body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:" + mime + ";base64," + base64)),
                Map.of("type", "text", "text", prompt)))));

        // 网关地址/Key 动态读 DB（vision.baseUrl / vision.apiKey，保存即生效；get 对 apiKey 透明解密），
        // DB 未配置时回退 yml/env；路径容错与 chat 同规则（版本尾缀/完整端点自动识别，支持智谱 /v4 等）
        String baseUrl = configService.get("vision.baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = properties.getVision().getBaseUrl();
        }
        String apiKey = configService.get("vision.apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = properties.getVision().getApiKey();
        }
        String[] np = DynamicOpenAiChatModel.normalize(baseUrl, "",
                "/v1/chat/completions", "/chat/completions");
        String url = np[0] + np[1];

        String resp = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseContent(resp);
    }

    /**
     * 防御性解析：兼容标准 OpenAI choices 与 DashScope 原生 {"text":...} 两种格式
     */
    private String parseContent(String resp) {
        if (resp == null || resp.isBlank()) return "";
        try {
            JSONObject root = JSON.parseObject(resp);
            // 1) 标准 OpenAI：choices[0].message.content（String 或 分段数组）
            JSONArray choices = root.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
                if (msg != null) {
                    Object content = msg.get("content");
                    if (content instanceof String s) return s.trim();
                    if (content instanceof JSONArray arr) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : arr) {
                            if (o instanceof JSONObject part && "text".equals(part.getString("type"))) {
                                sb.append(part.getString("text"));
                            }
                        }
                        return sb.toString().trim();
                    }
                }
            }
            // 2) DashScope 原生：{"text": "..."} 或 {"output":{"text":"..."}}
            String text = root.getString("text");
            if (text != null && !text.isBlank()) return text.trim();
            JSONObject output = root.getJSONObject("output");
            if (output != null) {
                String t = output.getString("text");
                if (t != null) return t.trim();
            }
        } catch (Exception e) {
            log.warn("解析图片描述响应失败: {}", e.getMessage());
        }
        return "";
    }

    private String mimeOf(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
    }
}
