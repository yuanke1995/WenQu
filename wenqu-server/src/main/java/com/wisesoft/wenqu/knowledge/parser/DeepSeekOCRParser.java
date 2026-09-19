package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DeepSeek OCR Parser using SiliconFlow API
 * （knowledge/parser/deepseek_ocr.py 全量移植）。
 *
 * <p>必要替换：pypdfium2 {@code page.render(scale).to_pil()} →
 * PDFBox {@code PDFRenderer}（scale 数值语义一致：72dpi 的倍数）；
 * Python {@code requests} → JDK {@link HttpClient}。
 */
@Slf4j
public class DeepSeekOCRParser extends BaseDocumentProcessor {

    /** MIME type mapping for supported formats. */
    private static final Map<String, String> MIME_TYPE_MAP = Map.of(
            ".pdf", "application/pdf",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".bmp", "image/bmp",
            ".webp", "image/webp");

    private static final Pattern REF_TAG = Pattern.compile("<\\|ref\\|>.*?<\\|/ref\\|>", Pattern.DOTALL);
    private static final Pattern DET_TAG = Pattern.compile("<\\|det\\|>.*?<\\|/det\\|>", Pattern.DOTALL);

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final Map<String, String> headers;

    public DeepSeekOCRParser() {
        this(Map.of());
    }

    public DeepSeekOCRParser(Map<String, Object> kwargs) {
        ParserCapabilities.ParserCapability capability = ParserCapabilities.getParserCapability("deepseek_ocr");
        this.serviceName = capability.serviceName();
        this.displayName = capability.displayName();
        this.supportedExtensions = capability.supportedExtensions();

        // 使用配置中心传入的 SiliconFlow 凭证和固定服务端点初始化解析器
        Object apiKeyParam = kwargs.get("api_key");
        this.apiKey = apiKeyParam instanceof String text && !text.isEmpty()
                ? text
                : System.getenv("SILICONFLOW_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new DocumentParserException(
                    "SILICONFLOW_API_KEY environment variable not set", "deepseek_ocr", "missing_api_key");
        }

        Object apiUrlParam = kwargs.get("api_url");
        this.apiUrl = apiUrlParam instanceof String text && !text.isEmpty()
                ? text
                : "https://api.siliconflow.cn/v1/chat/completions";
        this.model = "deepseek-ai/DeepSeek-OCR";

        this.headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey);
    }

    static void register() {
        DocumentProcessorFactory.register("deepseek_ocr", DeepSeekOCRParser::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> checkHealth() {
        // 检查模型列表端点的可达性与 Key 有效性
        try {
            String modelsUrl = apiUrl.substring(0, apiUrl.lastIndexOf("/chat/completions")) + "/models";
            HttpResponse<String> response = HttpUtil.get(modelsUrl, headers, Duration.ofSeconds(10));

            if (response.statusCode() == 200) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("api_url", apiUrl);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "healthy");
                result.put("message", "DeepSeek OCR (SiliconFlow) is available");
                result.put("details", details);
                return result;
            } else if (response.statusCode() == 401) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("error_code", "401");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "unhealthy");
                result.put("message", "Invalid API Key");
                result.put("details", details);
                return result;
            } else {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status_code", response.statusCode());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "unhealthy");
                result.put("message", "API Error: " + response.statusCode());
                result.put("details", details);
                return result;
            }
        } catch (Exception exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("error", String.valueOf(exc.getMessage()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unavailable");
            result.put("message", "Connection failed: " + exc.getMessage());
            result.put("details", details);
            return result;
        }
    }

    @Override
    public String processFile(String filePath, Map<String, Object> params) {
        if (!Files.exists(Path.of(filePath))) {
            throw new DocumentParserException(
                    "File not found: " + filePath, getServiceName(), "file_not_found");
        }

        String fileExt = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(filePath).toLowerCase();
        if (!supportsFileType(fileExt)) {
            throw new DocumentParserException(
                    "Unsupported file type: " + fileExt, getServiceName(), "unsupported_file_type");
        }

        try {
            long startTime = System.nanoTime();
            log.info("DeepSeek OCR starting: {}", Path.of(filePath).getFileName().toString());

            Map<String, Object> effectiveParams = params == null ? Map.of() : params;
            String content;
            if (".pdf".equals(fileExt)) {
                content = processPdf(filePath, effectiveParams);
            } else {
                content = processImage(filePath, effectiveParams);
            }

            double processingTime = (System.nanoTime() - startTime) / 1e9;
            log.info("DeepSeek OCR finished: {} - {} chars ({}s)",
                    Path.of(filePath).getFileName().toString(),
                    content.length(),
                    String.format("%.2f", processingTime));

            return content;
        } catch (DocumentParserException exc) {
            throw exc;
        } catch (IOException | InterruptedException exc) {
            String errorMsg = "DeepSeek OCR failed: " + exc.getMessage();
            log.error(errorMsg);
            throw new DocumentParserException(errorMsg, getServiceName(), "processing_failed");
        }
    }

    /** Process PDF by converting pages to images. */
    private String processPdf(String filePath, Map<String, Object> params) throws IOException, InterruptedException {
        try (org.apache.pdfbox.pdmodel.PDDocument pdf =
                org.apache.pdfbox.Loader.loadPDF(new java.io.File(filePath))) {
            List<String> fullText = new java.util.ArrayList<>();

            int totalPages = pdf.getNumberOfPages();
            log.info("Processing PDF with {} pages", totalPages);

            // 参考实现 scale 是 72dpi 的倍数，200dpi 对应 scale=200/72
            int dpi = params.get("pdf_dpi") instanceof Number number ? number.intValue() : 200;
            float scale = dpi / 72.0f;

            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(pdf);
            for (int i = 0; i < totalPages; i++) {
                log.debug("Processing page {}/{}", i + 1, totalPages);
                BufferedImage bitmap = renderer.renderImage(i, scale);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bitmap, "png", buf);
                byte[] imgBytes = buf.toByteArray();

                String pageText = callApi(imgBytes, "image/png", params);
                fullText.add(pageText);
            }

            return String.join("\n\n", fullText);
        }
    }

    private static final java.io.File File = null;

    /** Process single image file. */
    private String processImage(String filePath, Map<String, Object> params)
            throws IOException, InterruptedException {
        String mimeType = getMimeType(filePath);
        byte[] fileContent = Files.readAllBytes(Path.of(filePath));
        return callApi(fileContent, mimeType, params);
    }

    /** Call SiliconFlow API. */
    @SuppressWarnings("unchecked")
    private String callApi(byte[] dataBytes, String mimeType, Map<String, Object> params)
            throws IOException, InterruptedException {
        String encodedString = Base64.getEncoder().encodeToString(dataBytes);
        String dataUrl = "data:" + mimeType + ";base64," + encodedString;

        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUrl);
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", "<image>\n<|grounding|>Convert the document to markdown. ");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(imagePart, textPart));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", List.of(message));
        payload.put("max_tokens", params.get("max_tokens") instanceof Number number ? number.intValue() : 4096);
        payload.put("temperature",
                params.get("temperature") instanceof Number number ? number.doubleValue() : 0.1);

        int timeout = params.get("timeout_seconds") instanceof Number number ? number.intValue() : 120;
        HttpResponse<String> response = HttpUtil.postJson(apiUrl, headers, payload, Duration.ofSeconds(timeout));

        if (response.statusCode() != 200) {
            String errorMsg = "API Error " + response.statusCode() + ": " + response.body();
            log.error(errorMsg);
            throw new DocumentParserException(
                    errorMsg, getServiceName(), "http_" + response.statusCode());
        }

        Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(
                response.body(), new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        Map<String, Object> firstChoice = choices.get(0);
        Map<String, Object> messageOut = (Map<String, Object>) firstChoice.get("message");
        String content = String.valueOf(messageOut.get("content"));

        // Clean up special tags like <|ref|>...<|/ref|> and <|det|>...<|/det|>
        content = REF_TAG.matcher(content).replaceAll("");
        content = DET_TAG.matcher(content).replaceAll("");

        return content.strip();
    }

    private String getMimeType(String filePath) {
        String fileExt = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(filePath).toLowerCase();
        // Default fallback
        return MIME_TYPE_MAP.getOrDefault(fileExt, "image/jpeg");
    }
}
