package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import com.wisesoft.wenqu.knowledge.ZipUtils;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MinerU 官方 API 解析器（knowledge/parser/mineru_official.py 全量移植）。
 *
 * <p>必要替换：Python {@code requests} → JDK {@link HttpClient}（见 {@link HttpUtil}）；
 * {@code time.sleep} → {@link Thread#sleep}。
 */
@Slf4j
public class MinerUOfficialParser extends BaseDocumentProcessor {

    private final String apiKey;
    private final String apiBase;
    private final Map<String, String> headers;

    public MinerUOfficialParser() {
        this(Map.of());
    }

    public MinerUOfficialParser(Map<String, Object> kwargs) {
        ParserCapabilities.ParserCapability capability = ParserCapabilities.getParserCapability("mineru_official");
        this.serviceName = capability.serviceName();
        this.displayName = capability.displayName();
        this.supportedExtensions = capability.supportedExtensions();

        // 使用配置中心解析后的凭证和官方端点初始化解析器
        Object apiKeyParam = kwargs.get("api_key");
        this.apiKey = apiKeyParam instanceof String text && !text.isEmpty()
                ? text
                : System.getenv("MINERU_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new DocumentParserException(
                    "MINERU_API_KEY 环境变量未设置", "mineru_official", "missing_api_key");
        }

        Object apiBaseParam = kwargs.get("api_base");
        String base = apiBaseParam instanceof String text && !text.isEmpty()
                ? text
                : "https://mineru.net/api/v4";
        this.apiBase = base.replaceAll("/+$", "");
        this.headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + apiKey);
    }

    static void register() {
        DocumentProcessorFactory.register("mineru_official", MinerUOfficialParser::new);
    }

    @Override
    public Map<String, Object> checkHealth() {
        // 报告凭证配置状态，避免健康检查创建真实解析任务
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("api_base", apiBase);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "configured");
        result.put("message", "MinerU 官方 API Key 已配置，将在解析时验证");
        result.put("details", details);
        return result;
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

        // 处理参数；官方 API 不支持直接文件上传，先上传到可访问 URL（批量文件上传接口）
        Map<String, Object> effectiveParams = params == null ? Map.of() : params;
        long startTime = System.nanoTime();
        try {
            log.info("MinerU Official 开始处理: {}", file.getFileName().toString());

            // 步骤 1: 申请文件上传链接
            String batchId = uploadFile(filePath, effectiveParams);
            log.info("文件上传成功，batch_id: {}", batchId);

            // 步骤 2: 轮询任务结果
            long maxWaitTime = effectiveParams.get("max_wait_seconds") instanceof Number number
                    ? number.longValue()
                    : 600L;
            double pollInterval = effectiveParams.get("poll_interval_seconds") instanceof Number number
                    ? number.doubleValue()
                    : 5.0;
            Map<String, Object> result = pollBatchResult(batchId, maxWaitTime, pollInterval);
            log.info("任务完成，状态: {}", result.get("state"));

            String zipUrl = result.get("full_zip_url") == null ? null : String.valueOf(result.get("full_zip_url"));
            String zipPath = downloadZip(zipUrl);
            String text;
            try {
                String imageBucket = String.valueOf(effectiveParams.getOrDefault(
                        "image_bucket", MinioStorageClient.getInstance().KB_BUCKETS.get("images")));
                String imagePrefix = String.valueOf(
                        effectiveParams.getOrDefault("image_prefix", "unknown/kb-images"));

                text = ZipUtils.processZipFileSync(zipPath, imageBucket, imagePrefix);
            } finally {
                try {
                    Files.deleteIfExists(Path.of(zipPath));
                } catch (IOException ignored) {
                    // 参考实现 except Exception: pass
                }
            }

            double processingTime = (System.nanoTime() - startTime) / 1e9;
            log.info("MinerU Official 处理成功: {} - {} 字符 ({}s)",
                    file.getFileName().toString(), text.length(), String.format("%.2f", processingTime));

            return text;
        } catch (DocumentParserException exc) {
            throw exc;
        } catch (Exception exc) {
            double processingTime = (System.nanoTime() - startTime) / 1e9;
            String errorMsg = "MinerU Official 处理失败: " + exc.getMessage();
            log.error("{} ({}s)", errorMsg, String.format("%.2f", processingTime));
            throw new DocumentParserException(errorMsg, getServiceName(), "processing_failed");
        }
    }

    /** 上传文件并返回 batch_id。 */
    private String uploadFile(String filePath, Map<String, Object> params) throws IOException, InterruptedException {
        String filename = Path.of(filePath).getFileName().toString();

        String dataId = params.get("data_id") == null ? filename : String.valueOf(params.get("data_id"));
        if (dataId.length() > 30) {
            dataId = dataId.substring(0, 30) + "_" + HashUtils.hashstr(dataId, 8, false, null);
        }

        Map<String, Object> fileEntry = new LinkedHashMap<>();
        fileEntry.put("name", filename);
        fileEntry.put("is_ocr", params.getOrDefault("is_ocr", true));
        fileEntry.put("data_id", dataId);
        fileEntry.put("page_ranges", params.get("page_ranges"));

        Map<String, Object> uploadData = new LinkedHashMap<>();
        uploadData.put("enable_formula", params.getOrDefault("enable_formula", true));
        uploadData.put("enable_table", params.getOrDefault("enable_table", true));
        uploadData.put("language", params.getOrDefault("language", "ch"));
        uploadData.put("files", List.of(fileEntry));

        // 申请上传链接
        HttpResponse<String> response = HttpUtil.postJson(
                apiBase + "/file-urls/batch", headers, uploadData, Duration.ofSeconds(30));

        if (response.statusCode() != 200) {
            throw new DocumentParserException(
                    "申请上传链接失败: HTTP " + response.statusCode(),
                    getServiceName(),
                    "upload_url_failed");
        }

        Map<String, Object> result = parseJson(response.body());
        if (!Integer.valueOf(0).equals(asInt(result.get("code")))) {
            String errorMsg = result.get("msg") == null ? "未知错误" : String.valueOf(result.get("msg"));
            throw new DocumentParserException(
                    "申请上传链接失败: " + errorMsg,
                    getServiceName(),
                    "api_error_" + (result.get("code") == null ? "unknown" : result.get("code")));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String batchId = data == null ? null : (String) data.get("batch_id");
        @SuppressWarnings("unchecked")
        List<String> uploadUrls = data == null ? null : (List<String>) data.get("file_urls");

        if (uploadUrls == null || uploadUrls.isEmpty()) {
            throw new DocumentParserException("未获取到文件上传链接", getServiceName(), "no_upload_url");
        }

        // 上传文件
        String uploadUrl = uploadUrls.get(0);
        byte[] fileBytes = Files.readAllBytes(Path.of(filePath));
        HttpResponse<byte[]> uploadResponse = HttpUtil.put(uploadUrl, fileBytes, Duration.ofSeconds(60));

        if (uploadResponse.statusCode() != 200) {
            throw new DocumentParserException(
                    "文件上传失败: HTTP " + uploadResponse.statusCode(),
                    getServiceName(),
                    "file_upload_failed");
        }

        return batchId;
    }

    /** 轮询批量任务结果。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pollBatchResult(String batchId, long maxWaitTime, double pollInterval)
            throws IOException, InterruptedException {
        long startTime = System.nanoTime();

        while ((System.nanoTime() - startTime) / 1e9 < maxWaitTime) {
            HttpResponse<String> response = HttpUtil.get(
                    apiBase + "/extract-results/batch/" + batchId, headers, Duration.ofSeconds(30));

            if (response.statusCode() != 200) {
                throw new DocumentParserException(
                        "查询任务状态失败: HTTP " + response.statusCode(),
                        getServiceName(),
                        "status_query_failed");
            }

            Map<String, Object> result = parseJson(response.body());
            if (!Integer.valueOf(0).equals(asInt(result.get("code")))) {
                String errorMsg = result.get("msg") == null ? "未知错误" : String.valueOf(result.get("msg"));
                throw new DocumentParserException(
                        "查询任务状态失败: " + errorMsg,
                        getServiceName(),
                        "api_error_" + (result.get("code") == null ? "unknown" : result.get("code")));
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            List<Map<String, Object>> extractResults = data == null || data.get("extract_result") == null
                    ? List.of()
                    : (List<Map<String, Object>>) data.get("extract_result");
            if (extractResults.isEmpty()) {
                Thread.sleep((long) (pollInterval * 1000));
                continue;
            }

            // 检查第一个文件的状态
            Map<String, Object> fileResult = extractResults.get(0);
            String state = fileResult.get("state") == null ? null : String.valueOf(fileResult.get("state"));

            if ("done".equals(state)) {
                return fileResult;
            } else if ("failed".equals(state)) {
                String errMsg = fileResult.get("err_msg") == null
                        ? "未知错误"
                        : String.valueOf(fileResult.get("err_msg"));
                throw new DocumentParserException(
                        "文档解析失败: " + errMsg, getServiceName(), "parsing_failed");
            }

            // 继续等待
            Thread.sleep((long) (pollInterval * 1000));
        }

        throw new DocumentParserException("任务处理超时", getServiceName(), "timeout");
    }

    /** 下载结果 ZIP 到临时文件并返回路径。 */
    private String downloadZip(String zipUrl) throws IOException, InterruptedException {
        if (zipUrl == null || zipUrl.isEmpty()) {
            throw new DocumentParserException("未获取到结果下载链接", getServiceName(), "no_download_url");
        }
        HttpResponse<byte[]> response = HttpUtil.getBinary(zipUrl, Duration.ofSeconds(60));
        if (response.statusCode() != 200) {
            throw new DocumentParserException(
                    "下载结果失败: HTTP " + response.statusCode(), getServiceName(), "download_failed");
        }
        Path tmpFile = Files.createTempFile("wenqu-mineru-official-", ".zip");
        Files.write(tmpFile, response.body());
        return tmpFile.toString();
    }

    static Map<String, Object> parseJson(String body) {
        return com.alibaba.fastjson2.JSON.parseObject(
                body, new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
    }

    static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isEmpty()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
