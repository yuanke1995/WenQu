package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.GraphRagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GraphRAG 管理端点（P1）：构建 / 状态 / 三元组浏览 / 清图。
 * <p>
 * 权限：管理员功能（不进普通用户白名单，RBAC fail-closed）；开关在知识库编辑页（库管理者可改），
 * 构建与清图影响全库图谱数据，收敛到管理员面。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/graph")
@RequiredArgsConstructor
@Tag(name = "GraphRAG", description = "知识图谱构建与管理（P1，库级开关默认关）")
public class GraphController {

    private final GraphRagService graphRagService;

    @Operation(summary = "构建图谱（存量回溯）", description = "逐文档串行抽取该库全部生效文档；进度经 /status 查询，重复触发会被拒")
    @PostMapping("/{kbId}/build")
    public ResultJson build(@PathVariable("kbId") String kbId) {
        return ResultJson.ok(graphRagService.build(kbId));
    }

    @Operation(summary = "构建进度与图谱规模", description = "building/total/done/failed/extracted + 实体数/三元组数")
    @GetMapping("/{kbId}/status")
    public ResultJson status(@PathVariable("kbId") String kbId) {
        return ResultJson.ok(graphRagService.status(kbId));
    }

    @Operation(summary = "三元组浏览", description = "带实体名与来源文档名的分页列表（溯源核对用）")
    @GetMapping("/{kbId}/triples")
    public ResultJson triples(@PathVariable("kbId") String kbId,
                              @RequestParam(value = "page", defaultValue = "1") int page,
                              @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResultJson.ok(graphRagService.triples(kbId, page, size));
    }

    @Operation(summary = "清空图谱", description = "删该库全部三元组/实体/抽取记录（显式动作；开关关闭不删数据）")
    @DeleteMapping("/{kbId}")
    public ResultJson clear(@PathVariable("kbId") String kbId) {
        return ResultJson.ok(graphRagService.clear(kbId));
    }
}
