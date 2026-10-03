package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.parser.ocr.OcrEngine;
import com.wenqu.ai.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 深度解析引擎健康检查（parse.ocrEngine 配套排障端点）。
 * <p>
 * 只读：逐引擎探活（连通性 + 端点存在性，5s 级），并标注当前生效引擎；
 * 真实解析入口（上传/重新解析）在调用前同样 fail-loud 校验，本端点用于设置页调参后的快速自检。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/ocr")
@RequiredArgsConstructor
@Tag(name = "OCR", description = "PDF 深度解析引擎健康检查（parse.ocrEngine 配套）")
public class OcrController {

    private final List<OcrEngine> engines;
    private final ConfigService configService;

    @Operation(summary = "逐引擎探活", description = "返回当前生效引擎与各版面引擎的健康状态（vision 恒可用，其绑定校验在解析调用点）")
    @GetMapping("/health")
    public ResultJson health() {
        String current = configService.get("parse.ocrEngine");
        if (current == null || current.isBlank()) current = "none";

        List<Map<String, Object>> list = new ArrayList<>();
        for (OcrEngine engine : engines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", engine.id());
            m.put("current", engine.id().equals(current));
            long t0 = System.currentTimeMillis();
            String err = engine.checkHealth();
            m.put("costMs", System.currentTimeMillis() - t0);
            m.put("healthy", err == null);
            m.put("message", err == null ? "可用" : err);
            list.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", current);
        out.put("engines", list);
        return ResultJson.ok(out);
    }
}
