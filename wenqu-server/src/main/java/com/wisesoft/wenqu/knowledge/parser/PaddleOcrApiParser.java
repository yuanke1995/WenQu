package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import com.wisesoft.wenqu.knowledge.KbUtils;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PaddleOCR API jobs parser（knowledge/parser/paddleocr_api.py 全量移植）。
 *
 * <p>必要替换：Python {@code requests} → JDK {@link HttpClient}（见 {@link HttpUtil}）；
 * 两个引擎子类 {@code PaddleOCRVLParser}/{@code PaddleOCRPPOCRv6Parser} 与参考同文件结构一致，
 * 由本文件的嵌套静态类承载（类名经工厂注册表对应，不影响分派）。
 */
@Slf4j
public class PaddleOcrApiParser extends BaseDocumentProcessor {

    static final String DEFAULT_PADDLEOCR_API_URL = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs";

    protected String model = "";
    protected Map<String, Object> defaultOptionalPayload = new LinkedHashMap<>();

    protected final String apiToken;
    protected final String apiUrl;

    public PaddleOcrApiParser() {
        this(Map.of());
    }

    public PaddleOcrApiParser(Map<String, Object> kwargs) {
        ParserCapabilities.ParserCapability capability = ParserCapabilities.getParserCapability("paddleocr_vl_1_6");
        this.serviceName = capability.serviceName();
        this.displayName = capability.displayName();
        this.supportedExtensions = capability.supportedExtensions();

        Object apiTokenParam = kwargs.get("api_token");
        this.apiToken = apiTokenParam instanceof String text && !text.isEmpty()
                ? text
                : System.getenv("PADDLEOCR_API_TOKEN");
        Object apiUrlParam = kwargs.get("api_url");
        String url = apiUrlParam instanceof String text && !text.isEmpty()
                ? text
                : envOrDefault("PADDLEOCR_API_URL", DEFAULT_PADDLEOCR_API_URL);
        this.apiUrl = url.replaceAll("/+$", "");
    }

    static void register() {
        DocumentProcessorFactory.register("paddleocr_vl_1_6", PaddleOCRVLParser::new);
        DocumentProcessorFactory.register("paddleocr_pp_ocrv6", PaddleOCRPPOCRv6Parser::new);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    @Override
    public Map<String, Object> checkHealth() {
        if (apiToken == null || apiToken.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("api_url", apiUrl);
            details.put("model", model);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unavailable");
            result.put("message", "PADDLEOCR_API_TOKEN 未配置");
            result.put("details", details);
            return result;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("api_url", apiUrl);
        details.put("model", model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "configured");
        result.put("message", "PaddleOCR API token 已配置，将在解析时验证");
        result.put("details", details);
        return result;
    }

    @Override
    public String processFile(String filePath, Map<String, Object> params) {
        if (!Files.exists(Path.of(filePath)) && !filePath.startsWith("http://") && !filePath.startsWith("https://")) {
            throw new DocumentParserException(
                    "文件不存在: " + filePath, getServiceName(), "file_not_found");
        }

        String fileExt = fileExtension(filePath);
        if (!fileExt.isEmpty() && !supportsFileType(fileExt)) {
            throw new DocumentParserException(
                    "不支持的文件类型: " + fileExt, getServiceName(), "unsupported_file_type");
        }

        requireApiToken();
        Map<String, Object> effectiveParams = params == null ? Map.of() : params;
        long startTime = System.nanoTime();

        try {
            log.info("PaddleOCR API 开始处理: {} ({})", Path.of(filePath).getFileName().toString(), model);
            String jobId = submitJob(filePath, effectiveParams);
            double pollIntervalSeconds = effectiveParams.get("poll_interval_seconds") instanceof Number number
                    ? number.doubleValue()
                    : 5.0;
            double maxWaitSeconds = effectiveParams.get("max_wait_seconds") instanceof Number number
                    ? number.doubleValue()
                    : 600.0;
            String resultUrl = pollJobResult(jobId, pollIntervalSeconds, maxWaitSeconds);
            List<Map<String, Object>> rows = downloadJsonl(resultUrl);
            String text = extractMarkdown(rows, effectiveParams);

            double processingTime = (System.nanoTime() - startTime) / 1e9;
            log.info("PaddleOCR API 处理成功: {} ({}) - {} 字符 ({}s)",
                    Path.of(filePath).getFileName().toString(),
                    model,
                    text.length(),
                    String.format("%.2f", processingTime));
            return text;
        } catch (DocumentParserException exc) {
            throw exc;
        } catch (Exception exc) {
            double processingTime = (System.nanoTime() - startTime) / 1e9;
            String errorMsg = "PaddleOCR API 处理失败: " + exc.getMessage();
            log.error("{} ({}s)", errorMsg, String.format("%.2f", processingTime));
            throw new DocumentParserException(errorMsg, getServiceName(), "processing_failed");
        }
    }

    private void requireApiToken() {
        if (apiToken == null || apiToken.isEmpty()) {
            throw new DocumentParserException(
                    "PADDLEOCR_API_TOKEN 未配置", getServiceName(), "missing_api_token");
        }
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "bearer " + apiToken);
    }

    private String fileExtension(String filePath) {
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            String path = java.net.URI.create(filePath).getRawPath();
            return PosixPathLite.suffixOf(path).toLowerCase();
        }
        return PosixPathLite.suffixOf(filePath).toLowerCase();
    }

    private Map<String, Object> resolveOptionalPayload(Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>(defaultOptionalPayload);
        Object overrides = params.get("optional_payload");
        if (overrides instanceof Map<?, ?> overridesMap) {
            for (String key : payload.keySet()) {
                if (overridesMap.containsKey(key)) {
                    payload.put(key, overridesMap.get(key));
                }
            }
        }
        for (String key : payload.keySet()) {
            if (params.containsKey(key)) {
                payload.put(key, params.get(key));
            }
        }
        return payload;
    }

    @SuppressWarnings("unchecked")
    private String submitJob(String filePath, Map<String, Object> params)
            throws IOException, InterruptedException {
        Map<String, Object> optionalPayload = resolveOptionalPayload(params);
        Map<String, String> baseHeaders = headers();

        int statusCode;
        String responseBody;
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            Map<String, String> requestHeaders = new LinkedHashMap<>(baseHeaders);
            requestHeaders.put("Content-Type", "application/json");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileUrl", filePath);
            body.put("model", model);
            body.put("optionalPayload", optionalPayload);
            HttpResponse<String> jsonResponse = HttpUtil.postJson(apiUrl, requestHeaders, body, Duration.ofSeconds(60));
            statusCode = jsonResponse.statusCode();
            responseBody = jsonResponse.body();
        } else {
            byte[] fileContent = Files.readAllBytes(Path.of(filePath));
            Map<String, Object> dataFields = new LinkedHashMap<>();
            dataFields.put("model", model);
            dataFields.put(
                    "optionalPayload",
                    com.alibaba.fastjson2.JSON.toJSONString(optionalPayload));
            HttpResponse<byte[]> multipartResponse = HttpUtil.postMultipart(
                    apiUrl,
                    baseHeaders,
                    Map.of("file", Path.of(filePath).getFileName().toString()), fileContent,
                    dataFields, Duration.ofSeconds(60));
            statusCode = multipartResponse.statusCode();
            responseBody = new String(multipartResponse.body(), StandardCharsets.UTF_8);
        }

        if (statusCode != 200) {
            throw new DocumentParserException(
                    "提交 PaddleOCR 任务失败: HTTP " + statusCode + " " + responseBody,
                    getServiceName(),
                    "submit_failed");
        }

        Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(
                responseBody, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
        Integer code = MinerUOfficialParser.asInt(body.get("code"));
        if (code != null && code != 0) {
            throw new DocumentParserException(
                    "提交 PaddleOCR 任务失败: "
                            + (body.get("msg") == null ? "未知错误" : body.get("msg")),
                    getServiceName(),
                    "api_error_" + code);
        }

        Map<String, Object> data = (Map<String, Object>) body.get("data");
        Object jobId = data == null ? null : data.get("jobId");
        if (jobId == null) {
            throw new DocumentParserException(
                    "提交 PaddleOCR 任务后未返回 jobId", getServiceName(), "missing_job_id");
        }

        return String.valueOf(jobId);
    }

    @SuppressWarnings("unchecked")
    private String pollJobResult(String jobId, double pollIntervalSeconds, double maxWaitSeconds)
            throws IOException, InterruptedException {
        long startTime = System.nanoTime();

        while ((System.nanoTime() - startTime) / 1e9 < maxWaitSeconds) {
            HttpResponse<String> response = HttpUtil.get(apiUrl + "/" + jobId, headers(), Duration.ofSeconds(30));
            if (response.statusCode() != 200) {
                throw new DocumentParserException(
                        "查询 PaddleOCR 任务失败: HTTP " + response.statusCode() + " " + response.body(),
                        getServiceName(),
                        "status_query_failed");
            }

            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(
                    response.body(), new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
            Map<String, Object> data = (Map<String, Object>) body.get("data");
            if (data == null) {
                data = new LinkedHashMap<>();
            }
            String state = data.get("state") == null ? null : String.valueOf(data.get("state"));
            if ("done".equals(state)) {
                Map<String, Object> resultUrl = (Map<String, Object>) data.get("resultUrl");
                String jsonUrl = resultUrl == null || resultUrl.get("jsonUrl") == null
                        ? ""
                        : String.valueOf(resultUrl.get("jsonUrl")).strip();
                if (jsonUrl.isEmpty()) {
                    throw new DocumentParserException(
                            "PaddleOCR 任务完成但未返回 jsonUrl", getServiceName(), "missing_result_url");
                }
                return jsonUrl;
            }

            if ("failed".equals(state)) {
                String errorMsg = data.get("errorMsg") == null ? "未知错误" : String.valueOf(data.get("errorMsg"));
                throw new DocumentParserException(
                        "PaddleOCR 任务失败: " + errorMsg, getServiceName(), "job_failed");
            }

            if (!"pending".equals(state) && !"running".equals(state)) {
                throw new DocumentParserException(
                        "PaddleOCR 任务状态异常: " + state, getServiceName(), "unknown_job_state");
            }

            Thread.sleep((long) (pollIntervalSeconds * 1000));
        }

        throw new DocumentParserException("PaddleOCR 任务处理超时", getServiceName(), "timeout");
    }

    private List<Map<String, Object>> downloadJsonl(String jsonUrl) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpUtil.get(jsonUrl, Duration.ofSeconds(60));
        if (response.statusCode() != 200) {
            throw new DocumentParserException(
                    "下载 PaddleOCR 结果失败: HTTP " + response.statusCode() + " " + response.body(),
                    getServiceName(),
                    "download_failed");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : response.body().split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            rows.add(com.alibaba.fastjson2.JSON.parseObject(
                    trimmed, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {}));
        }

        if (rows.isEmpty()) {
            throw new DocumentParserException("PaddleOCR 结果为空", getServiceName(), "empty_result");
        }

        return rows;
    }

    String extractMarkdown(List<Map<String, Object>> rows, Map<String, Object> params) {
        throw new UnsupportedOperationException("extract_markdown");
    }

    /** 下载 Markdown 图片并上传到 MinIO，返回经后端鉴权代理访问的 URL。 */
    @SuppressWarnings("unchecked")
    protected String uploadMarkdownImage(String imageUrl, String imagePath, Map<String, Object> params)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HttpUtil.getBinary(imageUrl, Duration.ofSeconds(60));
        if (response.statusCode() != 200) {
            throw new DocumentParserException(
                    "下载 PaddleOCR Markdown 图片失败: HTTP " + response.statusCode(),
                    getServiceName(),
                    "image_download_failed");
        }

        String imageBucket = String.valueOf(params.getOrDefault(
                "image_bucket", MinioStorageClient.getInstance().KB_BUCKETS.get("images")));
        String imagePrefix = String.valueOf(params.getOrDefault("image_prefix", "unknown/kb-images"))
                .replaceAll("^/+|/+$", "");
        if (imagePrefix.isEmpty()) {
            imagePrefix = "unknown/kb-images";
        }
        String filename = PosixPathLite.suffixOf(imagePath).isEmpty() && imagePath.contains("/")
                ? imagePath.substring(imagePath.lastIndexOf('/') + 1)
                : imagePath.substring(Math.max(imagePath.lastIndexOf('/') + 1, 0));
        if (filename.isEmpty()) {
            filename = "paddleocr_image";
        }
        String suffix = PosixPathLite.suffixOf(filename);
        if (suffix.isEmpty()) {
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            suffix = guessExtension(contentType.split(";")[0].strip());
            filename = filename + suffix;
        }

        String objectName = imagePrefix + "/" + Instant.now().toEpochMilli() * 1000 + "_" + filename;
        MinioStorageClient minioClient = MinioStorageClient.getInstance();
        minioClient.ensureBucketExists(imageBucket);
        minioClient.uploadFile(imageBucket, objectName, response.body(), null);
        return KbUtils.buildKbImageProxyUrl(objectName);
    }

    /** Python mimetypes.guess_extension 的最小子集（图片类型）。 */
    private static String guessExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            default -> ".jpg";
        };
    }

    /** PaddleOCR-VL parser that returns layout Markdown. */
    public static class PaddleOCRVLParser extends PaddleOcrApiParser {

        public PaddleOCRVLParser() {
            super();
        }

        public PaddleOCRVLParser(Map<String, Object> kwargs) {
            super(kwargs);
            ParserCapabilities.ParserCapability capability =
                    ParserCapabilities.getParserCapability("paddleocr_vl_1_6");
            this.model = "PaddleOCR-VL-1.6";
            this.serviceName = capability.serviceName();
            this.displayName = capability.displayName();
            this.supportedExtensions = capability.supportedExtensions();
            this.defaultOptionalPayload = new LinkedHashMap<>(Map.of(
                    "useDocOrientationClassify", false,
                    "useDocUnwarping", false,
                    "useChartRecognition", false));
        }

        @Override
        @SuppressWarnings("unchecked")
        String extractMarkdown(List<Map<String, Object>> rows, Map<String, Object> params) {
            List<String> markdownParts = new ArrayList<>();

            for (Map<String, Object> row : rows) {
                Map<String, Object> result = (Map<String, Object>) row.get("result");
                if (result == null) {
                    result = new LinkedHashMap<>();
                }
                List<Map<String, Object>> layoutParsingResults =
                        (List<Map<String, Object>>) (result.get("layoutParsingResults") == null
                                ? List.of()
                                : result.get("layoutParsingResults"));
                for (Map<String, Object> item : layoutParsingResults) {
                    Map<String, Object> markdown = (Map<String, Object>) (item.get("markdown") == null
                            ? new LinkedHashMap<>()
                            : item.get("markdown"));
                    Object textObj = markdown.get("text");
                    if (!(textObj instanceof String text)) {
                        continue;
                    }

                    Map<String, Object> images = (Map<String, Object>) (markdown.get("images") == null
                            ? new LinkedHashMap<>()
                            : markdown.get("images"));
                    for (Map.Entry<String, Object> imageEntry : images.entrySet()) {
                        String imagePath = imageEntry.getKey();
                        Object imageUrl = imageEntry.getValue();
                        if (imagePath == null || imagePath.isEmpty() || imageUrl == null) {
                            continue;
                        }
                        String uploadedUrl;
                        try {
                            uploadedUrl = uploadMarkdownImage(
                                    String.valueOf(imageUrl), String.valueOf(imagePath), params);
                        } catch (java.io.IOException | InterruptedException exc) {
                            // 参考实现网络异常上抛，由 process_file 的 except Exception 统一包装
                            throw new IllegalStateException(exc.getMessage(), exc);
                        }
                        text = text.replace("](" + imagePath + ")", "](" + uploadedUrl + ")");
                        text = text.replace(String.valueOf(imageUrl), uploadedUrl);
                        // 能力补齐（显式标注，蓝本 paddleocr_api.py 无此两行）：PaddleOCR-VL 当前对版式
                        // 元素返回 HTML 形式 <img src="imgs/…">，蓝本只替换 markdown 形式与远程 URL，
                        // 对 HTML src 不生效 ⇒ 图片已上传但正文引用留在相对路径。按蓝本
                        // _upload_markdown_image「把图片链接改写为代理 URL」的意图补上 src 替换。
                        text = text.replace("src=\"" + imagePath + "\"", "src=\"" + uploadedUrl + "\"");
                        text = text.replace("src='" + imagePath + "'", "src='" + uploadedUrl + "'");
                    }

                    if (!text.strip().isEmpty()) {
                        markdownParts.add(text.strip());
                    }
                }
            }

            return String.join("\n\n", markdownParts).strip();
        }
    }

    /** PP-OCRv6 parser that returns plain OCR text. */
    public static class PaddleOCRPPOCRv6Parser extends PaddleOcrApiParser {

        public PaddleOCRPPOCRv6Parser() {
            super();
        }

        public PaddleOCRPPOCRv6Parser(Map<String, Object> kwargs) {
            super(kwargs);
            ParserCapabilities.ParserCapability capability =
                    ParserCapabilities.getParserCapability("paddleocr_pp_ocrv6");
            this.model = "PP-OCRv6";
            this.serviceName = capability.serviceName();
            this.displayName = capability.displayName();
            this.supportedExtensions = capability.supportedExtensions();
            this.defaultOptionalPayload = new LinkedHashMap<>(Map.of(
                    "useDocOrientationClassify", false,
                    "useDocUnwarping", false,
                    "useTextlineOrientation", false));
        }

        @Override
        @SuppressWarnings("unchecked")
        String extractMarkdown(List<Map<String, Object>> rows, Map<String, Object> params) {
            List<String> lines = new ArrayList<>();

            for (Map<String, Object> row : rows) {
                Map<String, Object> result = (Map<String, Object>) row.get("result");
                if (result == null) {
                    result = new LinkedHashMap<>();
                }
                List<Map<String, Object>> ocrResults =
                        (List<Map<String, Object>>) (result.get("ocrResults") == null
                                ? List.of()
                                : result.get("ocrResults"));
                for (Map<String, Object> item : ocrResults) {
                    Map<String, Object> prunedResult = (Map<String, Object>) (item.get("prunedResult") == null
                            ? new LinkedHashMap<>()
                            : item.get("prunedResult"));
                    List<Object> recTexts = (List<Object>) (prunedResult.get("rec_texts") == null
                            ? List.of()
                            : prunedResult.get("rec_texts"));
                    for (Object text : recTexts) {
                        if (text instanceof String value && !value.strip().isEmpty()) {
                            lines.add(value.strip());
                        }
                    }
                }
            }

            return String.join("\n", lines).strip();
        }
    }
}
