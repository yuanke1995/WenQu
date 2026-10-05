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

    @Operation(summary = "图谱视图（§5 聚合）", description = "按关联度取前 N 个实体及其间关系（谓词聚合计数），画图专用数据；"
            + "超出前 N 时 truncated=true，定位走 /search + /neighbor")
    @GetMapping("/{kbId}/view")
    public ResultJson view(@PathVariable("kbId") String kbId,
                           @RequestParam(value = "limit", defaultValue = "300") int limit) {
        return ResultJson.ok(graphRagService.view(kbId, limit));
    }

    @Operation(summary = "实体详情", description = "实体的全部三元组（主/客两侧），带来源文档与知识块定位（溯源跳块用）")
    @GetMapping("/{kbId}/entity/{entityId}")
    public ResultJson entity(@PathVariable("kbId") String kbId, @PathVariable("entityId") String entityId) {
        return ResultJson.ok(graphRagService.entityDetail(kbId, entityId));
    }

    @Operation(summary = "实体邻域子图", description = "以实体为中心的一跳邻域（nodes/edges 与 /view 同构）："
            + "搜索命中的实体不在默认视图内时，前端据此重渲染聚焦视图")
    @GetMapping("/{kbId}/neighbor/{entityId}")
    public ResultJson neighbor(@PathVariable("kbId") String kbId, @PathVariable("entityId") String entityId) {
        return ResultJson.ok(graphRagService.neighbor(kbId, entityId));
    }

    @Operation(summary = "实体搜索", description = "按显示名/归一名模糊匹配（前 20 个，提及次数降序），图谱内定位入口")
    @GetMapping("/{kbId}/search")
    public ResultJson search(@PathVariable("kbId") String kbId, @RequestParam("q") String q) {
        return ResultJson.ok(graphRagService.search(kbId, q));
    }

    @Operation(summary = "知识块内容", description = "按 chunkId 取块原文与所属文档（实体三元组「查看源块」弹层用）；跨库一律 404")
    @GetMapping("/{kbId}/chunk/{chunkId}")
    public ResultJson chunk(@PathVariable("kbId") String kbId, @PathVariable("chunkId") String chunkId) {
        return ResultJson.ok(graphRagService.chunk(kbId, chunkId));
    }

    @Operation(summary = "清空图谱", description = "删该库全部三元组/实体/抽取记录（显式动作；开关关闭不删数据）")
    @DeleteMapping("/{kbId}")
    public ResultJson clear(@PathVariable("kbId") String kbId) {
        return ResultJson.ok(graphRagService.clear(kbId));
    }
}
