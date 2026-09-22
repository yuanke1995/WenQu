package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import com.wisesoft.wenqu.knowledge.ZipUtils;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MinerU 文档解析器 - 使用 HTTP API 进行文档理解和解析
 * （knowledge/parser/mineru.py 全量移植）。
 *
 * <p>必要替换：Python {@code requests} → JDK {@link HttpClient}
 * （multipart 由 {@link HttpUtil} 以同一表单语义构造）。
 */
@Slf4j
public class MinerUParser extends BaseDocumentProcessor {

    private final String serverUrl;
    private final String parseEndpoint;

    public MinerUParser() {
        this(Map.of());
    }

    public MinerUParser(Map<String, Object> kwargs) {
        ParserCapabilities.ParserCapability capability = ParserCapabilities.getParserCapability("mineru_ocr");
        this.serviceName = capability.serviceName();
        this.displayName = capability.displayName();
        this.supportedExtensions = capability.supportedExtensions();
        Object serverUrlParam = kwargs.get("server_url");
        String url = serverUrlParam instanceof String text && !text.isEmpty()
                ? text
                : envOrDefault("MINERU_API_URI", "http://localhost:30001");
        this.serverUrl = url.replaceAll("/+$", "");
        this.parseEndpoint = this.serverUrl + "/file_parse";
    }

    static void register() {
        DocumentProcessorFactory.register("mineru_ocr", MinerUParser::new);
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    @Override
    public Map<String, Object> checkHealth() {
        try {
            // 尝试访问 OpenAPI JSON 端点来检查服务是否可用
            String healthUrl = serverUrl + "/openapi.json";
            HttpResponse<String> response = HttpUtil.get(healthUrl, Duration.ofSeconds(5));

            if (response.statusCode() == 200) {
                try {
                    Map<String, Object> openapiData = com.alibaba.fastjson2.JSON.parseObject(
                            response.body(), new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                    // 检查是否包含 file_parse 端点
                    boolean hasFileParse = false;
                    Object paths = openapiData.get("paths");
                    if (paths instanceof Map<?, ?> pathsMap) {
                        hasFileParse = pathsMap.containsKey("/file_parse");
                    }

                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("server_url", serverUrl);
                    if (hasFileParse) {
                        Object info = openapiData.get("info");
                        String apiVersion = "unknown";
                        if (info instanceof Map<?, ?> infoMap && infoMap.get("version") != null) {
                            apiVersion = String.valueOf(infoMap.get("version"));
                        }
                        details.put("api_version", apiVersion);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "healthy");
                        result.put("message", "MinerU 服务运行正常");
                        result.put("details", details);
                        return result;
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "unhealthy");
                    result.put("message", "MinerU 服务缺少必要的端点");
                    result.put("details", details);
                    return result;
                } catch (RuntimeException exc) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("server_url", serverUrl);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "unhealthy");
                    result.put("message", "MinerU 响应格式错误: " + exc.getMessage());
                    result.put("details", details);
                    return result;
                }
            } else {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("server_url", serverUrl);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "unhealthy");
                result.put("message", "MinerU 服务响应异常: " + response.statusCode());
                result.put("details", details);
                return result;
            }
        } catch (java.net.ConnectException exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "unavailable");
            result.put("message", "MinerU 服务无法连接,请检查服务是否启动");
            result.put("details", details);
            return result;
        } catch (java.net.http.HttpTimeoutException exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "timeout");
            result.put("message", "MinerU 服务连接超时");
            result.put("details", details);
            return result;
        } catch (Exception exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("server_url", serverUrl);
            details.put("error", String.valueOf(exc.getMessage()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("message", "MinerU 健康检查失败: " + exc.getMessage());
            result.put("details", details);
            return result;
        }
    }

    @Override
    public String processFile(String filePath, Map<String, Object> params) {
        Path file = Path.of(filePath);
        if (!Files.exists(file)) {
            throw new DocumentParserException(
                    "文件不存在: " + filePath, getServiceName(), "file_not_found");
        }

        String fileExt = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(filePath).toLowerCase();
        if (!supportsFileType(fileExt)) {
            throw new DocumentParserException(
                    "不支持的文件类型: " + fileExt, getServiceName(), "unsupported_file_type");
        }

        // 解析参数
        Map<String, Object> effectiveParams = params == null ? Map.of() : params;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lang_list", effectiveParams.getOrDefault("lang_list", java.util.List.of("ch")));
        data.put("backend", effectiveParams.getOrDefault("backend", "hybrid-auto-engine"));
        data.put("parse_method", effectiveParams.getOrDefault("parse_method", "auto"));
        data.put("formula_enable", effectiveParams.getOrDefault("formula_enable", true));
        data.put("table_enable", effectiveParams.getOrDefault("table_enable", true));
        data.put("image_analysis", effectiveParams.getOrDefault("image_analysis", true));
        data.put("start_page_id", effectiveParams.getOrDefault("start_page_id", 0));
        data.put("end_page_id", effectiveParams.getOrDefault("end_page_id", 99999));
        data.put("return_md", true);
        data.put("response_format_zip", true);
        data.put("return_images", true);

        Object serverUrlParam = effectiveParams.get("server_url");
        if (serverUrlParam instanceof String override && !override.isEmpty()) {
            data.put("server_url", override);
        }

        long startTime = System.nanoTime();
        int timeoutSeconds = 1800;
        Object timeoutParam = effectiveParams.get("timeout_seconds");
        if (timeoutParam instanceof Number number) {
            timeoutSeconds = number.intValue();
        } else {
            String envTimeout = System.getenv("MINERU_TIMEOUT");
            if (envTimeout != null && !envTimeout.isEmpty()) {
                timeoutSeconds = Integer.parseInt(envTimeout);
            }
        }

        try {
            log.info(
                    "MinerU 开始处理: {} (backend={}, lang={})",
                    file.getFileName().toString(), data.get("backend"), data.get("lang_list"));

            // 打开文件并以 multipart/form-data 发送（files + data 同体）
            byte[] fileBytes = Files.readAllBytes(file);
            HttpResponse<byte[]> response = HttpUtil.postMultipart(
                    parseEndpoint,
                    Map.of(),
                    Map.of("files", file.getFileName().toString()),
                    fileBytes,
                    data,
                    Duration.ofSeconds(timeoutSeconds));

            log.debug("MinerU 响应状态: {}, Content-Type: {}",
                    response.statusCode(), response.headers().firstValue("content-type").orElse(""));

            if (response.statusCode() != 200) {
                String errorDetail = "未知错误";
                try {
                    Map<String, Object> errorData = com.alibaba.fastjson2.JSON.parseObject(
                            new String(response.body(), StandardCharsets.UTF_8),
                            new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
                    Object detail = errorData.get("detail");
                    errorDetail = detail != null ? String.valueOf(detail) : String.valueOf(errorData);
                } catch (RuntimeException ignored) {
                    errorDetail = response.body() != null && response.body().length > 0
                            ? new String(response.body(), StandardCharsets.UTF_8)
                            : "HTTP " + response.statusCode();
                }

                log.error("MinerU HTTP错误 {}: {}", response.statusCode(), errorDetail);
                throw new DocumentParserException(
                        "MinerU 处理失败: " + errorDetail,
                        getServiceName(),
                        "http_" + response.statusCode());
            }

            // 解析响应：直接从响应内容获取 ZIP 数据，保存到临时文件并处理
            try {
                byte[] zipData = response.body();
                Path tmpZip = Files.createTempFile("wenqu-mineru-", ".zip");
                String text;
                try {
                    Files.write(tmpZip, zipData);
                    String imageBucket = String.valueOf(effectiveParams.getOrDefault(
                            "image_bucket", MinioStorageClient.getInstance().KB_BUCKETS.get("images")));
                    String imagePrefix = String.valueOf(
                            effectiveParams.getOrDefault("image_prefix", "unknown/kb-images"));

                    text = ZipUtils.processZipFileSync(tmpZip.toString(), imageBucket, imagePrefix);
                } finally {
                    Files.deleteIfExists(tmpZip);
                }

                if (text == null || text.isEmpty()) {
                    log.error("MinerU 未返回任何文本内容");
                    // 参考实现该异常同样落入外层 except 被二次包装为「响应解析失败」
                    throw new DocumentParserException(
                            "MinerU 未返回任何文本内容",
                            getServiceName(),
                            "no_content");
                }

                double processingTime = (System.nanoTime() - startTime) / 1e9;
                log.info("MinerU 处理成功: {} - {} 字符 ({}s)",
                        file.getFileName().toString(), text.length(), String.format("%.2f", processingTime));

                return text;
            } catch (RuntimeException exc) {
                // 参考实现 except Exception：包括 no_content 在内统一二次包装
                throw new DocumentParserException(
                        "MinerU 响应解析失败: " + exc.getMessage(),
                        getServiceName(),
                        "response_parse_error");
            }
        } catch (DocumentParserException exc) {
            throw exc;
        } catch (java.net.http.HttpTimeoutException exc) {
            double elapsed = (System.nanoTime() - startTime) / 1e9;
            String errorMsg = String.format(
                    "MinerU 处理超时 (%.2fs), 可以配置 MINERU_TIMEOUT 环境变量。", elapsed);
            log.error(errorMsg);
            throw new DocumentParserException(errorMsg, getServiceName(), "timeout");
        } catch (java.net.ConnectException exc) {
            String errorMsg = "MinerU 连接失败,请检查服务是否运行";
            log.error(errorMsg);
            throw new DocumentParserException(errorMsg, getServiceName(), "connection_error");
        } catch (Exception exc) {
            String errorMsg = "MinerU 处理失败: " + exc.getMessage();
            log.error("{} ({:.2f}s)", errorMsg, (System.nanoTime() - startTime) / 1e9);
            throw new DocumentParserException(errorMsg, getServiceName(), "processing_failed");
        }
    }
}
