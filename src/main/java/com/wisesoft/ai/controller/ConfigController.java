package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.parser.DocumentParser;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.ConnectivityProbeService;
import com.wisesoft.ai.service.KeywordIndexService;
import com.wisesoft.ai.service.RerankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 模型配置接口（配置界面）
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/config")
@RequiredArgsConstructor
@Tag(name = "系统配置", description = "模型参数配置（保存即生效）")
public class ConfigController {

    private final ConfigService configService;
    private final List<DocumentParser> parsers;
    private final RerankService rerankService;
    private final KeywordIndexService keywordIndexService;
    private final ConnectivityProbeService connectivityProbeService;
    private final com.wisesoft.ai.config.ConfigSchemaService configSchemaService;

    @Operation(summary = "获取全量配置", description = "获取所有模型配置项（分组展示 + editable 标记；apiKey 脱敏显示）")
    @GetMapping
    public ResultJson getConfig() {
        return ResultJson.ok(configService.snapshot());
    }

    /**
     * 配置字段定义（设置页渲染 + 后端保存校验的唯一定义源）。
     * <p>前端据此渲染表单（不再内置字段副本），字段的属性/范围/文案与后端校验规则来自同一份定义。
     */
    @Operation(summary = "获取配置字段定义", description = "面板、字段（类型/范围/默认值/条件显隐/文案）与核心项清单")
    @GetMapping("/schema")
    public ResultJson schema() {
        return ResultJson.ok(configSchemaService.describe());
    }

    /**
     * 前端运行时配置（文档上传限制等）：动态获取，保持与后端配置一致（改后端配置前端自动同步）
     */
    @Operation(summary = "获取前端运行时配置", description = "文档上传大小上限/支持格式等（前端校验用，与后端业务配置一致）")
    @GetMapping("/public")
    public ResultJson publicConfig() {
        Map<String, Object> upload = new LinkedHashMap<>();
        // 业务上传上限（字节，来自 c_ai_config 的 upload.maxFileSize，保存即生效）
        long maxBytes = configService.getLong("upload.maxFileSize");
        if (maxBytes <= 0) {
            maxBytes = 200L * 1024 * 1024;
        }
        upload.put("maxFileSize", maxBytes);
        upload.put("maxFileSizeLabel", fmtSize(maxBytes));
        // 聚合所有解析器支持的扩展名（去重排序，供前端上传校验）
        TreeSet<String> exts = new TreeSet<>();
        for (DocumentParser p : parsers) {
            exts.addAll(p.supportedExts());
        }
        upload.put("allowedExts", new ArrayList<>(exts));
        return ResultJson.ok(Map.of("upload", upload));
    }

    /** 字节数 → 可读标签（如 209715200 → "200MB"） */
    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.0fMB", bytes / (1024.0 * 1024));
    }

    /** 重排服务可用性探测（设置页"启用重排"开关打开前校验；绕过缓存真实请求） */
    @Operation(summary = "探测重排服务", description = "强制探测 rerank 服务（/v1/models），返回 available 供前端开启开关前校验")
    @GetMapping("/rerank/check")
    public ResultJson checkRerank() {
        return ResultJson.ok(Map.of("available", rerankService.checkAvailable()));
    }

    /** Meilisearch 可用性探测（设置页切换关键词引擎前校验；绕过缓存真实请求） */
    @Operation(summary = "探测 Meilisearch", description = "强制探测 Meilisearch /health，返回 available 供前端切换引擎前校验")
    @GetMapping("/keyword/check")
    public ResultJson checkKeyword() {
        return ResultJson.ok(Map.of("available", keywordIndexService.checkAvailable()));
    }

    /**
     * 通用连通性探测（设置页各模型/服务地址旁的「测试连接」按钮）。
     * 用表单里<b>尚未保存</b>的值真实探测，实现"先测后存"；不落库、不触发重嵌入/索引重建。
     */
    @Operation(summary = "测试连通", description = "用传入的（未保存）配置真实探测模型网关或服务是否可达。"
            + "group=chat|vision|embedding|rerank|keyword；返回 {available, latencyMs, detail}")
    @PostMapping("/probe")
    public ResultJson probe(
            @Parameter(description = "{\"group\":\"chat\",\"baseUrl\":\"..\",\"apiKey\":\"..\",\"model\":\"..\",\"path\":\"..\"}")
            @RequestBody Map<String, String> body) {
        String group = body.get("group");
        if (group == null || group.isBlank()) {
            return ResultJson.error(400, "缺少 group（chat/vision/embedding/rerank/keyword）");
        }
        return ResultJson.ok(connectivityProbeService.probe(group, body.get("baseUrl"),
                body.get("apiKey"), body.get("model"), body.get("path")));
    }

    /** 向量模型全量重嵌入已退役：向量模型归各知识库绑定，重嵌入随知识库编辑/启动迁移按库触发 */
    @Operation(summary = "保存配置", description = "保存可编辑的模型配置项（chat.temperature、chat.systemPrompt、vision.prompt、检索/解析参数等），保存即生效无需重启；*.apiKey 以 RSA 加密入库。业务模型已归属使用者：知识库绑定向量/解析视觉/检索重排模型，聊天模型走会话覆盖>个人默认")
    @PutMapping
    public ResultJson updateConfig(
            @Parameter(description = "{\"chat\": {\"model\": \"..\", \"temperature\": \"0.3\"}, \"vision\": {\"model\": \"..\", \"prompt\": \"..\"}}")
            @RequestBody Map<String, Map<String, String>> body) {
        try {
            Map<String, String> updated = configService.update(body);
            return ResultJson.ok(updated, "配置已保存并生效");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        } catch (Exception e) {
            return ResultJson.error(500, "保存失败: " + e.getMessage());
        }
    }

    @Operation(summary = "恢复分组默认值", description = "将指定配置分组（chat/vision/chunk/parse/upload/retrieval/rerank/keyword/context/deepReasoning/ratelimit）恢复为出厂默认。不触碰 embedding 组与各模型 API Key，防止误触发全量重嵌入/误清密钥")
    @PostMapping("/reset")
    public ResultJson resetGroup(@Parameter(description = "{\"groups\": [\"chat\"]}")
                                 @RequestBody Map<String, Object> body) {
        List<String> groups = body.get("groups") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        try {
            Map<String, String> reset = configService.resetDefaults(groups);
            return ResultJson.ok(reset, "已恢复默认");
        } catch (IllegalArgumentException e) {
            return ResultJson.error(400, e.getMessage());
        }
    }
}
