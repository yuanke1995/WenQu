package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PP-Structure-V3 文档解析器 - 使用 PP-Structure-V3 进行版面解析
 * （knowledge/parser/pp_structure_v3.py 全量移植）。
 *
 * <p>必要替换：Python {@code requests} → JDK {@link HttpClient}（见 {@link HttpUtil}）。
 */
@Slf4j
public class PPStructureV3Parser extends BaseDocumentProcessor {

    private final String serverUrl;
    private final String baseUrl;
    private final String endpoint;

    public PPStructureV3Parser() {
        this(Map.of());
    }

    public PPStructureV3Parser(Map<String, Object> kwargs) {
        ParserCapabilities.ParserCapability capability = ParserCapabilities.getParserCapability("pp_structure_v3_ocr");
        this.serviceName = capability.serviceName();
        this.displayName = capability.displayName();
        this.supportedExtensions = capability.supportedExtensions();

        Object serverUrlParam = kwargs.get("server_url");
        String url = serverUrlParam instanceof String text && !text.isEmpty()
                ? text
                : envOrDefault("PADDLEX_URI", "http://localhost:8080");
        this.serverUrl = url;
        this.baseUrl = url.replaceAll("/+$", "");
        this.endpoint = this.baseUrl + "/layout-parsing";
    }

    static void register() {
        DocumentProcessorFactory.register("pp_structure_v3_ocr", PPStructureV3Parser::new);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    /** 将文件编码为 Base64。 */
    private String encodeFileToBase64(String filePath) throws IOException {
        byte[] content = Files.readAllBytes(Path.of(filePath));
        return Base64.getEncoder().encodeToString(content);
    }

    /** 处理文件输入：本地文件路径、URL 或 Base64 内容。 */
    private String processFileInput(String fileInput) throws IOException {
        // 检查是否为本地文件路径
        if (Files.exists(Path.of(fileInput))) {
            log.info("📁 检测到本地文件: {}", fileInput);
            log.info("📏 文件大小: {} MB", String.format("%.2f", Files.size(Path.of(fileInput)) / 1024.0 / 1024.0));
            return encodeFileToBase64(fileInput);
        }

        // 检查是否为 URL
        if (fileInput.startsWith("http://") || fileInput.startsWith("https://")) {
            log.info("🌐 检测到URL: {}", fileInput);
            return fileInput;
        }

        // 否则假设为 Base64 编码内容
        log.info("📝 假设为Base64编码内容，长度: {} 字符", fileInput.length());
        return fileInput;
    }

    /** 调用 PP-Structure-V3 版面解析 API。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callLayoutApi(
            String fileInput,
            Integer fileType,
            boolean useTableRecognition,
            boolean useFormulaRecognition,
            boolean useSealRecognition,
            Map<String, Object> kwargs)
            throws IOException, InterruptedException {
        // 处理文件输入
        String processedFileInput = processFileInput(fileInput);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", processedFileInput);

        // 添加核心参数（非空才放）
        Map<String, Object> optionalParams = new LinkedHashMap<>();
        optionalParams.put("fileType", fileType);
        optionalParams.put("useTableRecognition", useTableRecognition);
        optionalParams.put("useFormulaRecognition", useFormulaRecognition);
        optionalParams.put("useSealRecognition", useSealRecognition);
        for (Map.Entry<String, Object> entry : optionalParams.entrySet()) {
            if (entry.getValue() != null) {
                payload.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, Object> effectiveKwargs = new LinkedHashMap<>(kwargs);
        int timeoutSeconds = effectiveKwargs.get("timeout_seconds") instanceof Number number
                ? number.intValue()
                : 300;
        effectiveKwargs.remove("timeout_seconds");

        // 添加其他 kwargs 参数（非空才放）
        for (Map.Entry<String, Object> entry : effectiveKwargs.entrySet()) {
            if (entry.getValue() != null) {
                payload.put(entry.getKey(), entry.getValue());
            }
        }

        HttpResponse<String> response = HttpUtil.postJson(
                endpoint,
                Map.of("Content-Type", "application/json"),
                payload,
                Duration.ofSeconds(timeoutSeconds));

        if (response.statusCode() == 200) {
            return parseJson(response.body());
        } else {
            String errorMsg = "PP-Structure-V3 API请求失败: " + response.statusCode();
            try {
                Map<String, Object> errorResult = parseJson(response.body());
                throw new DocumentParserException(
                        errorMsg + ": " + errorResult, getServiceName(), "api_error");
            } catch (DocumentParserException exc) {
                throw exc;
            } catch (RuntimeException ignored) {
                throw new DocumentParserException(
                        errorMsg + ": " + response.body(), getServiceName(), "api_error");
            }
        }
    }

    /** 解析 API 返回结果。 */
    private Map<String, Object> parseApiResult(Map<String, Object> apiResult, String filePath) {
        // 基本信息
        Map<String, Object> parsedResult = new LinkedHashMap<>();
        parsedResult.put("success", true);
        parsedResult.put("file_path", filePath);
        parsedResult.put("file_name", Path.of(filePath).getFileName().toString());
        parsedResult.put("log_id", apiResult.get("logId"));
        parsedResult.put("total_pages", 0);
        parsedResult.put("pages", new ArrayList<>());
        parsedResult.put("full_text", "");
        parsedResult.put("summary", new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        Map<String, Object> resultData = (Map<String, Object>) apiResult.get("result");
        List<Map<String, Object>> layoutResults = resultData == null || resultData.get("layoutParsingResults") == null
                ? List.of()
                : (List<Map<String, Object>>) resultData.get("layoutParsingResults");

        // 数据信息
        parsedResult.put("total_pages", layoutResults.size());

        // 统计信息
        int totalTables = 0;
        int totalFormulas = 0;
        List<String> allTextContent = new ArrayList<>();

        // 解析每页结果
        for (Map<String, Object> pageResult : layoutResults) {
            // Markdown 内容
            if (pageResult.containsKey("markdown")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> markdown = (Map<String, Object>) pageResult.get("markdown");
                Object text = markdown.get("text");
                if (text != null && !String.valueOf(text).isEmpty()) {
                    allTextContent.add(String.valueOf(text));
                }
            }

            // 详细识别结果
            if (pageResult.containsKey("prunedResult")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pruned = (Map<String, Object>) pageResult.get("prunedResult");

                // 表格识别
                Object tableResult = pruned.get("table_result");
                if (tableResult instanceof List<?> list) {
                    totalTables += list.size();
                }

                // 公式识别
                Object formulaResult = pruned.get("formula_result");
                if (formulaResult instanceof List<?> list) {
                    totalFormulas += list.size();
                }
            }
        }

        // 汇总全文内容
        parsedResult.put("full_text", String.join("\n\n", allTextContent));

        // 汇总统计信息
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_tables", totalTables);
        summary.put("total_formulas", totalFormulas);
        summary.put("total_characters", ((String) parsedResult.get("full_text")).length());
        parsedResult.put("summary", summary);

        return parsedResult;
    }

    @Override
    public Map<String, Object> checkHealth() {
        try {
            HttpResponse<String> response = HttpUtil.get(baseUrl + "/health", Duration.ofSeconds(5));

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            if (response.statusCode() == 200) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "healthy");
                result.put("message", "PP-Structure-V3 服务运行正常");
                result.put("details", details);
                return result;
            } else {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "unhealthy");
                result.put("message", "PP-Structure-V3 服务响应异常: " + response.statusCode());
                result.put("details", details);
                return result;
            }
        } catch (java.net.ConnectException exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unavailable");
            result.put("message", "PP-Structure-V3 服务无法连接,请检查服务是否启动");
            result.put("details", details);
            return result;
        } catch (java.net.http.HttpTimeoutException exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "timeout");
            result.put("message", "PP-Structure-V3 服务连接超时");
            result.put("details", details);
            return result;
        } catch (Exception exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            details.put("error", String.valueOf(exc.getMessage()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("message", "PP-Structure-V3 健康检查失败: " + exc.getMessage());
            result.put("details", details);
            return result;
        }
    }

    @Override
    public String processFile(String filePath, Map<String, Object> params) {
        if (!Files.exists(Path.of(filePath))) {
            throw new DocumentParserException(
                    "文件不存在: " + filePath, getServiceName(), "file_not_found");
        }

        String fileExt = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(filePath).toLowerCase();
        if (!supportsFileType(fileExt)) {
            throw new DocumentParserException(
                    "不支持的文件类型: " + fileExt, getServiceName(), "unsupported_file_type");
        }

        // 先检查服务健康状态
        Map<String, Object> health = checkHealth();
        if (!"healthy".equals(health.get("status"))) {
            throw new DocumentParserException(
                    "PP-Structure-V3 服务不可用: " + health.get("message"),
                    getServiceName(),
                    String.valueOf(health.get("status")));
        }

        try {
            long startTime = System.nanoTime();
            Map<String, Object> effectiveParams = params == null ? Map.of() : params;

            // 判断文件类型
            int fileType = ".pdf".equals(fileExt) ? 0 : 1;

            log.info("PP-Structure-V3 开始处理: {}", Path.of(filePath).getFileName().toString());

            // 调用 API
            Map<String, Object> apiResult = callLayoutApi(
                    filePath,
                    fileType,
                    Boolean.TRUE.equals(effectiveParams.getOrDefault("use_table_recognition", true)),
                    Boolean.TRUE.equals(effectiveParams.getOrDefault("use_formula_recognition", true)),
                    Boolean.TRUE.equals(effectiveParams.getOrDefault("use_seal_recognition", false)),
                    Map.of("timeout_seconds",
                            effectiveParams.getOrDefault("timeout_seconds", 300)));

            // 检查 API 调用是否成功
            if (!Integer.valueOf(0).equals(asInt(apiResult.get("errorCode")))) {
                throw new DocumentParserException(
                        "PP-Structure-V3 API错误: "
                                + (apiResult.get("errorMsg") == null ? "未知错误" : apiResult.get("errorMsg")),
                        getServiceName(),
                        "api_error");
            }

            // 解析结果
            Map<String, Object> result = parseApiResult(apiResult, filePath);
            String text = result.get("full_text") == null ? "" : String.valueOf(result.get("full_text"));

            double processingTime = (System.nanoTime() - startTime) / 1e9;
            log.info("PP-Structure-V3 处理成功: {} - {} 字符 ({}s)",
                    Path.of(filePath).getFileName().toString(),
                    text.length(),
                    String.format("%.2f", processingTime));

            // 记录统计信息
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) result.get("summary");
            if (summary != null && !summary.isEmpty()) {
                log.info("  统计: {} 表格, {} 公式",
                        summary.getOrDefault("total_tables", 0), summary.getOrDefault("total_formulas", 0));
            }

            return text;
        } catch (DocumentParserException exc) {
            throw exc;
        } catch (IOException | InterruptedException | RuntimeException exc) {
            String errorMsg = "PP-Structure-V3 处理失败: " + exc.getMessage();
            log.error(errorMsg);
            throw new DocumentParserException(errorMsg, getServiceName(), "processing_failed");
        }
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    static Map<String, Object> parseJson(String body) {
        return com.alibaba.fastjson2.JSON.parseObject(
                body, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
    }
}
