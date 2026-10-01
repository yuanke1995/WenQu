package com.wisesoft.ai.parser.ocr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU 版面解析引擎（parse.ocrEngine = mineru）。
 * <p>
 * HTTP 契约与问渠新栈 {@code MinerUParser} 一致（MinerU http-server / mineru-api）：
 * <ul>
 *   <li>健康检查：GET {uri}/openapi.json，200 且 paths 含 /file_parse = 可用；</li>
 *   <li>解析：POST {uri}/file_parse，multipart/form-data（files=PDF 字节 + 表单参数，
 *       response_format_zip=true），响应体为 zip，取其中的 .md 即整份 markdown
 *       （标题层级/表格为 markdown 原生语法，无页界 → 约定 page=1）。</li>
 * </ul>
 * S1 范围说明：return_images=false 不回传图片（S2 接老副本 [图片N] 落盘机制后再开），
 * markdown 里的图片引用为空占位，不影响文本与表格结构。
 * 所有失败 fail-loud 抛 BizException，绝不回落文本层。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinerUOcrEngine implements OcrEngine {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConfigService configService;

    @Override
    public String id() {
        return "mineru";
    }

    private String uri() {
        String u = configService.get("parse.ocrMineruUri");
        return (u == null || u.isBlank()) ? "http://localhost:30011" : u.replaceAll("/+$", "");
    }

    private Duration timeout() {
        return Duration.ofMillis(configService.getInt("parse.ocrTimeoutMs", 600000));
    }

    @Override
    public String checkHealth() {
        String base = uri();
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/openapi.json"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "MinerU 服务响应异常: HTTP " + resp.statusCode() + "（" + base + "），请确认服务已启动";
            }
            JSONObject openapi = JSON.parseObject(resp.body());
            JSONObject paths = openapi == null ? null : openapi.getJSONObject("paths");
            if (paths == null || !paths.containsKey("/file_parse")) {
                return "MinerU 服务缺少 /file_parse 端点（" + base + "），疑似版本不兼容";
            }
            return null;
        } catch (ConnectException e) {
            return "MinerU 服务无法连接（" + base + "），请确认服务已启动、地址与 parse.ocrMineruUri 一致";
        } catch (java.net.http.HttpTimeoutException e) {
            return "MinerU 服务健康检查超时（" + base + "）";
        } catch (Exception e) {
            return "MinerU 健康检查失败: " + e.getMessage();
        }
    }

    @Override
    public List<PageMarkdown> parse(Path pdf, String fileName) throws Exception {
        String base = uri();
        // 表单参数对齐 2.7.6 mineru-api（/file_parse）；lang_list 单 part 值 "ch"（FastAPI List[str] 收单值即 ["ch"]）。
        // backend 默认 pipeline：多语言通用、无幻觉、CPU 可跑；hybrid-auto-engine 要本地跑 VLM，
        // CPU-only 容器上极慢（默认值可用 parse.ocrMineruBackend 调整）
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("lang_list", "ch");
        fields.put("backend", configService.get("parse.ocrMineruBackend") == null
                || configService.get("parse.ocrMineruBackend").isBlank()
                ? "pipeline" : configService.get("parse.ocrMineruBackend").trim());
        fields.put("parse_method", "auto");
        fields.put("formula_enable", "true");
        fields.put("table_enable", "true");
        fields.put("start_page_id", "0");
        fields.put("end_page_id", "99999");
        fields.put("return_md", "true");
        fields.put("return_images", "false");          // S1 不回填图片，见类注释
        fields.put("response_format_zip", "true");

        byte[] body = Files.readAllBytes(pdf);
        log.info("[MinerU] {} 提交版面解析（{}，{}KB）", fileName, base, body.length / 1024);
        HttpResponse<byte[]> resp = HTTP.send(postMultipart(base + "/file_parse", fileName, body, fields, timeout()),
                HttpResponse.BodyHandlers.ofByteArray());

        if (resp.statusCode() != 200) {
            String detail = abbreviateDetail(resp.body());
            throw new com.wisesoft.ai.common.BizException("MinerU 版面解析请求失败: HTTP " + resp.statusCode() + " " + detail);
        }
        String md = extractMarkdown(resp.body());
        if (md == null || md.isBlank()) {
            throw new com.wisesoft.ai.common.BizException("MinerU 响应 zip 中未找到 markdown 结果（服务版本或参数不兼容）");
        }
        log.info("[MinerU] {} 完成：整份 markdown {} 字符", fileName, md.length());
        // MinerU 整份 markdown 无页界，约定 page=1；跨块切分后的标题语义见 PdfParser#chunksFromPages
        return List.of(new PageMarkdown(1, md.trim()));
    }

    /**
     * 构造 multipart/form-data 请求体（RFC 7578；boundary 随机）。
     * 包内可见供冒烟验证；字段顺序：普通表单字段在前、文件在后（与新栈 HttpUtil 同语义）。
     * ByteArrayOutputStream.writeBytes 不抛 IOException，无需 try/catch。
     */
    static byte[] buildMultipartBody(String fileName, byte[] fileContent, Map<String, String> fields, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.writeBytes(e.getValue().getBytes(StandardCharsets.UTF_8));
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes("Content-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(fileContent);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpRequest postMultipart(String url, String fileName, byte[] fileContent,
                                       Map<String, String> fields, Duration timeout) {
        String boundary = "----WenQuBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(fileName, fileContent, fields, boundary);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    /** 从 MinerU 响应 zip 中提取第一个 .md 条目（整份 markdown）。包内可见供冒烟验证。 */
    static String extractMarkdown(byte[] zipBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            List<String> seen = new ArrayList<>();
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.toLowerCase().endsWith(".md")) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
                seen.add(name);
                zis.closeEntry();
            }
            log.warn("[MinerU] zip 中无 .md 条目，实际条目: {}", seen);
            return null;
        }
    }

    static String abbreviateDetail(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            JSONObject err = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            Object detail = err.get("detail");
            if (detail != null) return abbreviate(String.valueOf(detail));
        } catch (Exception ignored) {
            // 非 JSON 响应体，走原文截断
        }
        return abbreviate(new String(body, StandardCharsets.UTF_8));
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}
