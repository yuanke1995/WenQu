package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 执行 Trace 与运营闭环（P1，Coze Loop 模式）。
 * <p>
 * 权限：<b>运营功能，不进普通用户白名单</b>——RBAC 下管理员直通，普通角色默认 403
 * （可在权限管理为角色显式绑定 /api/ai/trace/**）。工作流 run 详情不做逐条归属校验（运营视角，与看板同面）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/trace")
@RequiredArgsConstructor
@Tag(name = "执行 Trace", description = "统一执行视图（对话 + 工作流）与线上采样回流评测集（P1 运营闭环）")
public class TraceController {

    private final TraceService traceService;

    @Operation(summary = "执行列表", description = "kind=chat 对话 / workflow 工作流（两类各自分页）。"
            + "筛选：keyword 关键词、hasCitation 有无引用（对话）、rating 评分 1/0（对话）、days 最近 N 天（0=不限）、agentId 智能体")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "kind", defaultValue = "chat") String kind,
                           @RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "size", defaultValue = "20") int size,
                           @RequestParam(value = "keyword", required = false) String keyword,
                           @RequestParam(value = "hasCitation", required = false) Integer hasCitation,
                           @RequestParam(value = "rating", required = false) Integer rating,
                           @RequestParam(value = "days", defaultValue = "0") Integer days,
                           @RequestParam(value = "agentId", required = false) String agentId) {
        return ResultJson.ok(traceService.list(kind, page, size, keyword, hasCitation, rating, days, agentId));
    }

    @Operation(summary = "对话型详情", description = "日志 + 回答消息全过程（sources/toolCalls/tokens/timeline）+ 反馈 + 采样状态；"
            + "存量行（无 message_id 关联）为摘要视图")
    @GetMapping("/chat/{id}")
    public ResultJson chatDetail(@PathVariable("id") String id) {
        return ResultJson.ok(traceService.chatDetail(id));
    }

    @Operation(summary = "工作流型详情", description = "run 全量（含节点级 node_traces）+ 工作流名")
    @GetMapping("/workflow/{runId}")
    public ResultJson workflowDetail(@PathVariable("runId") String runId) {
        return ResultJson.ok(traceService.workflowDetail(runId));
    }

    @Operation(summary = "采样池列表", description = "status=pending/labeled/dismissed/all")
    @GetMapping("/pool")
    public ResultJson pool(@RequestParam(value = "status", defaultValue = "pending") String status,
                           @RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResultJson.ok(traceService.pool(status, page, size));
    }

    @Operation(summary = "标注并回流评测集", description = "body: {knowledgeIds:[知识块ID...], note?}——期望命中的知识块至少 1 个，"
            + "回流走评测集 addCase（版本化机制不变），成功才置 labeled")
    @PostMapping("/pool/{id}/label")
    public ResultJson label(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> knowledgeIds = body.get("knowledgeIds") instanceof List ? (List<String>) body.get("knowledgeIds") : List.of();
        Object note = body.get("note");
        return ResultJson.ok(traceService.label(id, knowledgeIds, note == null ? null : String.valueOf(note)));
    }

    @Operation(summary = "忽略样本", description = "不回流，置 dismissed")
    @PostMapping("/pool/{id}/dismiss")
    public ResultJson dismiss(@PathVariable("id") String id) {
        return ResultJson.ok(traceService.dismiss(id));
    }

    @Operation(summary = "手动触发一次采样", description = "与定时任务同一逻辑（差评必采 + 无引用/随机按配置数量），幂等可重复点")
    @PostMapping("/sample/run")
    public ResultJson sampleNow() {
        return ResultJson.ok(traceService.sampleDaily());
    }

    @Operation(summary = "采样池概览", description = "各状态计数 + 最近入池时间（看板小卡数据源）")
    @GetMapping("/stats")
    public ResultJson stats() {
        return ResultJson.ok(traceService.stats());
    }
}
