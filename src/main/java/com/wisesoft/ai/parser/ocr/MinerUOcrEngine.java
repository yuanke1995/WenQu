package com.wisesoft.ai.parser.ocr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.parser.DocumentParser;
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
 * HTTP 契约与问渠新栈 {@code MinerUParser} 一致（MinerU http-server / mineru-api 2.x）：
 * <ul>
 *   <li>健康检查：GET {uri}/openapi.json，200 且 paths 含 /file_parse = 可用；</li>
 *   <li>解析：POST {uri}/file_parse，multipart/form-data（files=PDF 字节 + 表单参数，
 *       response_format_zip=true），响应体为 zip，取其中的 .md 即该批 markdown。</li>
 * </ul>
 * <b>分批提交</b>：按「目标单批时长 ~4 分钟」反推批大小（pipeline 24 页/批，hybrid 8 页/批），
 * 逐批 start_page_id/end_page_id 提交、批 markdown 以批起始页号为 page 号顺序拼接。
 * 动机：民法典 188 页整份提交要 30+ 分钟，撞死 parse.ocrTimeoutMs（10 分钟）——
 * HTTP 连接挂半小时也脆，且等待期只能估算进度。分批后单请求短、批完成即真实推进、
 * 单批超时预算随批页数自适应（max(配置值, 批页数×单页经验×2)）。
 * <p>
 * S1 范围说明：return_images=false 不回传图片（接老副本 [图片N] 落盘机制后再开）。
 * 所有失败 fail-loud 抛 BizException，绝不回落文本层。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinerUOcrEngine implements OcrEngine {

    /** 目标单批时长：批大小按此反推（CPU pipeline ≈24 页、hybrid ≈8 页），控制单请求时长与 HTTP 连接风险 */
    private static final long TARGET_BATCH_MS = 240_000L;

    private static final int MIN_BATCH_PAGES = 4;
    private static final int MAX_BATCH_PAGES = 30;

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
        return parse(pdf, fileName, null);
    }

    @Override
    public List<PageMarkdown> parse(Path pdf, String fileName,
                                    com.wisesoft.ai.parser.DocumentParser.ParseProgress progress) throws Exception {
        String base = uri();
        // 表单参数对齐 2.7.6 mineru-api（/file_parse）；lang_list 单 part 值 "ch"（FastAPI List[str] 收单值即 ["ch"]）。
        // backend 默认 pipeline：多语言通用、无幻觉、CPU 可跑；hybrid-auto-engine 要本地跑 VLM，
        // CPU-only 容器上极慢（默认值可用 parse.ocrMineruBackend 调整）
        String backend = configService.get("parse.ocrMineruBackend") == null
                || configService.get("parse.ocrMineruBackend").isBlank()
                ? "pipeline" : configService.get("parse.ocrMineruBackend").trim();
        long perPageMs = "pipeline".equals(backend) ? EstimatedProgress.PER_PAGE_MS : EstimatedProgress.PER_PAGE_MS * 3;
        long floorTimeoutMs = configService.getInt("parse.ocrTimeoutMs", 600000);

        int total;
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            total = d.getNumberOfPages();
        }
        // 目标单批时长反推批大小：pipeline 24 页、hybrid 8 页（clamp 到 [4,30]）
        int batchPages = (int) Math.max(MIN_BATCH_PAGES,
                Math.min(MAX_BATCH_PAGES, TARGET_BATCH_MS / perPageMs));
        int batchCount = (total + batchPages - 1) / batchPages;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("lang_list", "ch");
        fields.put("backend", backend);
        fields.put("parse_method", "auto");
        fields.put("formula_enable", "true");
        fields.put("table_enable", "true");
        fields.put("start_page_id", "0");              // 批内覆盖
        fields.put("end_page_id", "99999");
        fields.put("return_md", "true");
        fields.put("return_images", "false");          // 不回填图片，见类注释
        fields.put("response_format_zip", "true");

        byte[] body = Files.readAllBytes(pdf);
        log.info("[MinerU] {} 提交版面解析（{}，{}KB，{} 页，backend={}，分 {} 批 × ≤{} 页）",
                fileName, base, body.length / 1024, total, backend, batchCount, batchPages);

        DocumentParser.ParseProgress cb = progress != null ? progress : (p, d) -> { };
        List<PageMarkdown> out = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int start = 0, batchNo = 1; start < total; start += batchPages, batchNo++) {
            int endIncl = Math.min(start + batchPages, total) - 1;   // end_page_id 为闭区间
            int batchPageCount = endIncl - start + 1;
            int fromPct = 12 + start * 16 / total;                    // 已完成 start 页
            int toPct = Math.max(fromPct + 1, 12 + (endIncl + 1) * 16 / total);
            // 单批预算 = max(配置下限, 批页数 × 单页经验 × 2)——页数多的文档自适应放宽，不再撞死固定超时
            long budgetMs = Math.max(floorTimeoutMs, batchPageCount * perPageMs * 2);

            try (EstimatedProgress est = EstimatedProgress.start(cb,
                    "MinerU 第 " + batchNo + "/" + batchCount + " 批（第 " + (start + 1) + "~" + (endIncl + 1)
                            + " 页 / 共 " + total + " 页）",
                    batchPageCount, perPageMs, fromPct, toPct)) {
                fields.put("start_page_id", String.valueOf(start));
                fields.put("end_page_id", String.valueOf(endIncl));
                HttpResponse<byte[]> resp;
                try {
                    resp = HTTP.send(postMultipart(base + "/file_parse", fileName, body, fields, Duration.ofMillis(budgetMs)),
                            HttpResponse.BodyHandlers.ofByteArray());
                } catch (java.net.http.HttpTimeoutException e) {
                    throw new com.wisesoft.ai.common.BizException("「" + fileName + "」第 " + (start + 1) + "~" + (endIncl + 1)
                            + " 页批次解析超时（预算 " + budgetMs / 60000 + " 分钟，CPU 经验约 " + perPageMs / 1000 + "s/页）。"
                            + "可在设置页调大 parse.ocrTimeoutMs；大文档建议改用 none 引擎或部署 GPU 版服务");
                }
                if (resp.statusCode() != 200) {
                    String detail = abbreviateDetail(resp.body());
                    throw new com.wisesoft.ai.common.BizException("MinerU 第 " + (start + 1) + "~" + (endIncl + 1)
                            + " 页批次请求失败: HTTP " + resp.statusCode() + " " + detail);
                }
                String md = extractMarkdown(resp.body());
                if (md != null && !md.isBlank()) {
                    out.add(new PageMarkdown(start + 1, md.trim()));
                }
                // 空批（纯空白页）合法跳过，与整份模式对空页的处理一致
            }
        }

        if (out.isEmpty()) {
            throw new com.wisesoft.ai.common.BizException("「" + fileName + "」MinerU 解析 " + total + " 页未得到任何文本内容");
        }
        long totalMs = System.currentTimeMillis() - t0;
        log.info("[MinerU] {} 完成：{} 批 / {} 页 / markdown 共 {} 字符，耗时 {}", fileName, batchCount, total,
                out.stream().mapToInt(m -> m.markdown().length()).sum(),
                totalMs < 90_000 ? totalMs / 1000 + "s" : totalMs / 60000 + " 分钟");
        return out;
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
