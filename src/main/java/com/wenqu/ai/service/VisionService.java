package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.AppProperties;
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
    private final ModelRegistryService modelRegistryService;
    private final ImageDescCache imageDescCache;
    private final RestClient restClient;

    public VisionService(AppProperties properties, ConfigService configService,
                         ModelRegistryService modelRegistryService, ImageDescCache imageDescCache) {
        this.properties = properties;
        this.configService = configService;
        this.modelRegistryService = modelRegistryService;
        this.imageDescCache = imageDescCache;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(properties.getVision().getTimeoutMillis());
        // 不设固定 baseUrl：每次调用按引用（visionRef → 供应商表）解析出网关，请求用绝对 URI
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * 生成图片文字描述（使用配置的默认提示词）；任何失败返回 ""（降级，不中断主流程）。
     * 视觉模型取当前线程的解析期「图片描述」引用（{@link #startParseScope(String)}，文档内嵌图按库）；
     * 未设置（无库上下文）时不描述图片——全局 vision.model 已退役，没有运行时兜底。
     */
    public String describe(byte[] imageBytes, String ext) {
        return describe(imageBytes, ext, configService.get("vision.prompt"), DESC_REF.get());
    }

    /**
     * 解析期「图片描述」引用的当前值（异步任务提交前在解析线程捕获用——ThreadLocal 不跨线程，
     * 图片描述跑在 vision-desc 池线程，那里直接读必然是空）。
     */
    public String currentDescRef() {
        return DESC_REF.get();
    }

    /** 显式引用版图片描述（异步任务用）：ref 由提交线程捕获后传入，其余逻辑同 {@link #describe(byte[], String)} */
    public String describeWithRef(byte[] imageBytes, String ext, String ref) {
        return describe(imageBytes, ext, configService.get("vision.prompt"), ref);
    }

    /**
     * 带引用的图片描述（内部）：ref 非空时解析该引用为视觉网关（解析期的知识库 visionRef）；
     * 空/解析失败 → 跳过描述（全局 vision.model 已退役，无运行时兜底）。
     * 先查内容寻址缓存（同图+同模型+同提示词直接复用上次结果，避免重解析重复调 VLM）；
     * 未命中调 VLM，成功写缓存。失败自动重试 retryCount 次（Ollama 偶发 500/超时）
     */
    private String describe(byte[] imageBytes, String ext, String prompt, String ref) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        // vision.enabled 配置化（设置页可改，保存即生效；未配置时默认开启）
        String cfgEnabled = configService.get("vision.enabled");
        boolean enabled = cfgEnabled == null || cfgEnabled.isBlank() || Boolean.parseBoolean(cfgEnabled.trim());
        if (!enabled) {
            log.debug("视觉模型已关闭（vision.enabled=false），跳过图片描述");
            return "";
        }
        ModelRegistryService.ModelRoute route = routeFor(ref);
        if (route == null) {
            log.info("[Vision] 未指定视觉模型（{}），跳过图片描述（本图不参与向量召回，解析继续）",
                    ref == null || ref.isBlank() ? "未绑定（无库上下文）" : "引用无效");
            return "";
        }
        return callWithCache(imageBytes, ext, prompt, route, false);
    }

    /**
     * OCR 专用严格通道（扫描件 PDF 用）：与 {@link #describe} 的"失败返回空串降级"不同，
     * 本方法 <b>fail-loud 不降级</b>——
     * <ul>
     *   <li>总开关关闭 / 当前线程未设置解析期扫描件 OCR 模型 / 引用无效 → 抛 IllegalStateException
     *       （扫描件没有模型就识别不出内容，解析必须失败并告知原因）；</li>
     *   <li>调用失败（重试后仍异常）→ 抛 IllegalStateException（丢页 = 内容静默残缺，不可接受）；</li>
     *   <li>返回 "" 仅表示模型正常响应但判定页上没有文字（空白页/封面图，合法）。</li>
     * </ul>
     * 路由：优先「扫描件 OCR 模型」槽位（OCR 专用类型）；未单独配置时回落「图片描述模型」槽位
     * （跟随语义，与其他知识库解析参数一致——单槽老配置行为不变）。
     */
    public String describeOcr(byte[] imageBytes, String ext, String prompt) {
        if (imageBytes == null || imageBytes.length == 0) return "";
        String cfgEnabled = configService.get("vision.enabled");
        boolean enabled = cfgEnabled == null || cfgEnabled.isBlank() || Boolean.parseBoolean(cfgEnabled.trim());
        if (!enabled) {
            throw new IllegalStateException("视觉模型总开关已关闭（vision.enabled=false），扫描件无法 OCR；请在系统设置开启或为该库更换非扫描文档");
        }
        String ocrRef = OCR_REF.get();
        if (ocrRef == null || ocrRef.isBlank()) ocrRef = DESC_REF.get();
        ModelRegistryService.ModelRoute route = routeFor(ocrRef);
        if (route == null) {
            throw new IllegalStateException("所属知识库未绑定图片理解模型（知识库编辑 → 解析参数 → 图片描述模型/扫描件 OCR 模型），扫描件无法 OCR");
        }
        return callWithCache(imageBytes, ext, prompt, route, true);
    }

    /** 扫描件 OCR 前置检查：OCR 槽位（未配置回落图片描述槽位）是否可解析；未就绪直接失败避免白渲染 */
    public boolean parseVisionAvailable() {
        String ref = OCR_REF.get();
        if (ref == null || ref.isBlank()) ref = DESC_REF.get();
        return ref != null && !ref.isBlank() && modelRegistryService.resolveReference(ref.trim()) != null;
    }

    /**
     * 视觉调用核心（缓存 + 重试）：strict=false 时失败/空返回 ""（宽松降级，图片描述用）；
     * strict=true 时调用失败抛 IllegalStateException（OCR 用），返回 "" 仅代表模型确认页上无文字。
     */
    private String callWithCache(byte[] imageBytes, String ext, String prompt,
                                 ModelRegistryService.ModelRoute route, boolean strict) {
        String model = route.modelId();
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
                String desc = callOnce(imageBytes, ext, prompt, route);
                if (desc != null && !desc.isBlank()) {
                    result = desc;
                    break;
                }
                // 空响应：模型偶发空输出，重试一次
                if (attempt < retry) log.warn("图片描述为空，第 {} 次重试", attempt + 1);
            } catch (Exception e) {
                lastErr = e;
                if (attempt < retry) log.warn("图片描述失败(第 {} 次)，重试: {}", attempt + 1, causeChain(e));
            }
        }
        if (result.isBlank()) {
            if (strict && lastErr != null) {
                throw new IllegalStateException("视觉模型调用失败（重试 " + retry + " 次后仍失败）: "
                        + causeChain(lastErr), lastErr);
            }
            log.warn("图片描述最终失败: {}", lastErr == null ? "空响应" : causeChain(lastErr));
        } else if (cacheKey != null) {
            imageDescCache.put(cacheKey, result, model);
        }
        return result;
    }

    /**
     * 路由解析：refOverride 非空时按引用取供应商网关；空/引用无效 → null（调用方跳过描述，
     * 不再回落已退役的全局 vision.model）。
     */
    private ModelRegistryService.ModelRoute routeFor(String refOverride) {
        if (refOverride == null || refOverride.isBlank()) return null;
        ModelRegistryService.ModelRoute r = modelRegistryService.resolveReference(refOverride.trim());
        if (r == null) {
            log.warn("[Vision] 视觉模型引用解析失败，跳过图片描述: {}", refOverride);
            return null;
        }
        return r;
    }

    /**
     * 解析期视觉模型引用（线程局部，双槽位）：
     * - {@link #DESC_REF}：图片描述模型（文档内嵌图：docx/PDF 插图截图、图片描述补齐）；
     * - {@link #OCR_REF}：扫描件 OCR 模型（PDF 视觉引擎逐页识别；未配置回落 DESC_REF）。
     * 解析任务 worker 线程开头 set、finally clear（与 ConfigService.putOverrides 同模式）。
     */
    private static final ThreadLocal<String> DESC_REF = new ThreadLocal<>();
    private static final ThreadLocal<String> OCR_REF = new ThreadLocal<>();

    /** 设置当前线程的「图片描述」槽位（单槽：文档内嵌图描述与补齐；不影响 OCR 槽位） */
    public void startParseScope(String ref) {
        if (ref != null && !ref.isBlank()) DESC_REF.set(ref.trim());
    }

    /** 设置当前线程双槽位：descRef=图片描述模型、ocrRef=扫描件 OCR 模型（空=清该槽位，OCR 空时回落 desc） */
    public void startParseScope(String descRef, String ocrRef) {
        if (descRef != null && !descRef.isBlank()) DESC_REF.set(descRef.trim()); else DESC_REF.remove();
        if (ocrRef != null && !ocrRef.isBlank()) OCR_REF.set(ocrRef.trim()); else OCR_REF.remove();
    }

    /** 清除解析期视觉模型引用（解析任务结束必须调用） */
    public void clearParseScope() {
        DESC_REF.remove();
        OCR_REF.remove();
    }

    private String callOnce(byte[] imageBytes, String ext, String prompt, ModelRegistryService.ModelRoute route) {
        String mime = mimeOf(ext);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> body = new HashMap<>();
        body.put("model", route.modelId());
        body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:" + mime + ";base64," + base64)),
                Map.of("type", "text", "text", prompt)))));

        // 网关地址/Key 来自视觉路由（visionRef → 供应商表，Key 在解析时解密；无引用直接跳过，不回落 legacy），
        // 路径容错与 chat 同规则（版本尾缀/完整端点自动识别，支持智谱 /v4 等）
        String baseUrl = route.baseUrl();
        String apiKey = route.apiKey();
        String[] np = DynamicOpenAiChatModel.normalize(baseUrl, route.completionsPath(),
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
     * 防御性解析：兼容标准 OpenAI choices 与 DashScope 原生 {"text":...} 两种格式。
     * 输出规范化：OCR 专用模型（如 PaddleOCR-VL）的原生输出混有版面坐标标记（<|LOC_487|> 等），
     * 那是给版面还原用的，本系统任何环节都不消费坐标——原样入库只会污染向量与上下文
     * （图片描述文本会进分块、嵌入和引用），统一剥离。
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
                    if (content instanceof String s) return stripLayoutTokens(s);
                    if (content instanceof JSONArray arr) {
                        StringBuilder sb = new StringBuilder();
                        for (Object o : arr) {
                            if (o instanceof JSONObject part && "text".equals(part.getString("type"))) {
                                sb.append(part.getString("text"));
                            }
                        }
                        return stripLayoutTokens(sb.toString());
                    }
                }
            }
            // 2) DashScope 原生：{"text": "..."} 或 {"output":{"text":"..."}}
            String text = root.getString("text");
            if (text != null && !text.isBlank()) return stripLayoutTokens(text);
            JSONObject output = root.getJSONObject("output");
            if (output != null) {
                String t = output.getString("text");
                if (t != null) return stripLayoutTokens(t);
            }
        } catch (Exception e) {
            log.warn("解析图片描述响应失败: {}", e.getMessage());
        }
        return "";
    }

    /** 剥离 OCR 模型输出里的版面坐标标记（<|LOC_487|> 等，大小写不敏感），并压掉剥离后产生的连续空行 */
    private static String stripLayoutTokens(String s) {
        if (s == null || s.indexOf("LOC_") < 0 && s.indexOf("loc_") < 0) return s == null ? "" : s.trim();
        return s.replaceAll("(?i)<\\|loc_\\d+\\|>", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
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

    /**
     * 异常类名 + cause 链拼接（截断 600 字符）。Spring RestClientException 的 getMessage()
     * 只有 "Error while extracting response for type ... and content type ..." 这层壳，
     * 真实原因（连接被对端掐断 / 超时 / EOF）在 cause 链里——不展开就永远看不到。
     */
    private static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 600; c = c.getCause() == c ? null : c.getCause()) {
            if (sb.length() > 0) sb.append(" ← ");
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }
}
