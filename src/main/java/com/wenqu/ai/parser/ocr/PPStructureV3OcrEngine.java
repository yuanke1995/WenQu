package com.wenqu.ai.parser.ocr;

import com.wenqu.ai.config.ConfigDefaults;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
 * PP-StructureV3 版面解析引擎（parse.ocrEngine = pp_structure_v3）。
 * <p>
 * HTTP 契约与问渠新栈 {@code PPStructureV3Parser} 一致（PaddleX serving）：
 * <ul>
 *   <li>健康检查：GET {uri}/health，200 = 可用；</li>
 *   <li>解析：POST {uri}/layout-parsing，JSON {@code {file: base64, fileType: 0(PDF),
 *       useTableRecognition: true, useFormulaRecognition: true, useSealRecognition: false}}；
 *       响应 {@code errorCode==0} 且 {@code result.layoutParsingResults[].markdown.text} 为每页 markdown
 *       （表格为 HTML 原样保留在正文里）。</li>
 * </ul>
 * 服务自托管（PaddleX 镜像），地址与超时走设置页（parse.ocrPpUri / parse.ocrTimeoutMs）。
 * 所有失败 fail-loud 抛 BizException，绝不回落文本层。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PPStructureV3OcrEngine implements OcrEngine {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConfigService configService;

    @Override
    public String id() {
        return "pp_structure_v3";
    }

    private String uri() {
        String u = configService.get("parse.ocrPpUri");
        return (u == null || u.isBlank()) ? "http://localhost:8080" : u.replaceAll("/+$", "");
    }

    private Duration timeout() {
        return Duration.ofMillis(configService.getInt("parse.ocrTimeoutMs", ConfigDefaults.PARSE_OCR_TIMEOUT_MS));
    }

    @Override
    public String checkHealth() {
        String base = uri();
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return null;
            return "PP-StructureV3 服务响应异常: HTTP " + resp.statusCode() + "（" + base + "），请确认 PaddleX serving 已启动";
        } catch (ConnectException e) {
            return "PP-StructureV3 服务无法连接（" + base + "），请确认服务已启动、地址与 parse.ocrPpUri 一致";
        } catch (java.net.http.HttpTimeoutException e) {
            return "PP-StructureV3 服务健康检查超时（" + base + "）";
        } catch (Exception e) {
            return "PP-StructureV3 健康检查失败: " + e.getMessage();
        }
    }

    @Override
    public List<PageMarkdown> parse(Path pdf, String fileName) throws Exception {
        return parse(pdf, fileName, null);
    }

    @Override
    public List<PageMarkdown> parse(Path pdf, String fileName,
                                    com.wenqu.ai.parser.DocumentParser.ParseProgress progress) throws Exception {
        String base = uri();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("file", Base64.getEncoder().encodeToString(Files.readAllBytes(pdf)));
        payload.put("fileType", 0); // 0=PDF
        payload.put("useTableRecognition", true);
        payload.put("useFormulaRecognition", true);
        payload.put("useSealRecognition", false);

        // 整份提交无逐页回调：等待期按页数×经验单页耗时估进度（进度卡死比慢更让人不安）
        int pages;
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
            pages = d.getNumberOfPages();
        }
        log.info("[PP-StructureV3] {} 提交版面解析（{}，{}KB，{} 页）", fileName, base, Files.size(pdf) / 1024, pages);
        List<PageMarkdown> out;
        try (EstimatedProgress est = EstimatedProgress.start(progress, "PP-StructureV3", pages, EstimatedProgress.PER_PAGE_MS)) {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/layout-parsing"))
                            .timeout(timeout())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                throw new com.wenqu.ai.common.BizException("PP-StructureV3 版面解析请求失败: HTTP " + resp.statusCode()
                        + " " + abbreviate(resp.body()));
            }
            JSONObject root = JSON.parseObject(resp.body());
            Integer errorCode = root.getInteger("errorCode");
            if (errorCode == null || errorCode != 0) {
                throw new com.wenqu.ai.common.BizException("PP-StructureV3 返回错误: "
                        + (root.getString("errorMsg") == null ? "未知错误" : root.getString("errorMsg")));
            }
            JSONObject result = root.getJSONObject("result");
            JSONArray layoutPages = result == null ? null : result.getJSONArray("layoutParsingResults");
            if (layoutPages == null || layoutPages.isEmpty()) {
                throw new com.wenqu.ai.common.BizException("PP-StructureV3 未返回任何版面结果（PDF 可能为空或页数异常）");
            }
            out = new ArrayList<>(layoutPages.size());
            for (int i = 0; i < layoutPages.size(); i++) {
                JSONObject md = layoutPages.getJSONObject(i).getJSONObject("markdown");
                String text = md == null ? null : md.getString("text");
                if (text != null && !text.isBlank()) out.add(new PageMarkdown(i + 1, text.trim()));
            }
        }
        log.info("[PP-StructureV3] {} 完成：{} 页有内容", fileName, out.size());
        return out;
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }
}
