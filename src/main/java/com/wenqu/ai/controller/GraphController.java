package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.GraphRagService;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.ResourceVisibilityService;
import com.wenqu.ai.service.RoleService;
import com.wenqu.ai.util.RequestUser;
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
import com.wenqu.ai.security.Permission;

/**
 * GraphRAG 管理端点：构建 / 状态 / 三元组浏览 / 清图。
 * <p>
 * 权限两层，缺一不可：
 * <ul>
 *   <li>角色授权点：{@code graph:read}（只读）/ {@code graph:build}（构建、重建）/
 *       {@code graph:delete}（清图），由 {@code user} 等角色在权限管理里绑定；</li>
 *   <li>资源归属：每个带 kbId 的端点都按知识库管理侧口径校验——谁建归谁，
 *       只读端点走 {@link ResourceVisibilityService#canReadKbManagement}，
 *       写端点走 {@link ResourceVisibilityService#canManage}；官方内置库仅管理员级可维护。
 *       与 KnowledgeBaseController 的 requireManage 同源，避免出现「有授权点就能读任意库图谱」的越权。</li>
 * </ul>
 * 无 kbId 的 /repair-moved 是全库扫描，无法按归属过滤，故不标授权点（未标注 = 仅管理员级可达）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/graph")
@RequiredArgsConstructor
@Tag(name = "GraphRAG", description = "知识图谱构建与管理（P1，库级开关默认关）")
public class GraphController {

    private final GraphRagService graphRagService;
    private final KnowledgeBaseService kbService;
    private final ResourceVisibilityService visibility;
    private final RoleService roleService;

    private ResourceVisibilityService.Principal principal() {
        return new ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    private boolean admin() {
        return roleService.isAdminAny(RequestUser.roles());
    }

    private KnowledgeBase mustGet(String kbId) {
        KnowledgeBase kb = kbService.get(kbId);
        if (kb == null) throw new BizException("知识库不存在");
        return kb;
    }

    /** 只读端点：管理侧可见即可读（内置库仅管理员级，其余按创建者/角色数据范围） */
    private void requireRead(String kbId) {
        KnowledgeBase kb = mustGet(kbId);
        if (!visibility.canReadKbManagement(principal(), kb.getBuiltin(), kb.getCreatedBy(), admin())) {
            throw new BizException("无权查看该知识库的图谱");
        }
    }

    /** 写端点：谁建归谁，仅创建者可构建/重建/清空自己库的图谱；官方内置库仅管理员级 */
    private void requireManage(String kbId) {
        KnowledgeBase kb = mustGet(kbId);
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1) {
            if (!admin()) throw new BizException("官方内置知识库的图谱仅管理员可维护");
            return;
        }
        if (!visibility.canManage(principal(), kb.getCreatedBy())) {
            throw new BizException("仅可管理自己创建的知识库的图谱");
        }
    }

    @Operation(summary = "构建图谱（存量回溯）", description = "逐文档串行抽取该库全部生效文档；进度经 /status 查询，重复触发会被拒")
    @Permission("graph:build")
    @PostMapping("/{kbId}/build")
    public ResultJson build(@PathVariable("kbId") String kbId) {
        requireManage(kbId);
        return ResultJson.ok(graphRagService.build(kbId));
    }

    @Operation(summary = "补全实体类型（重建抽取）", description = "清空本库抽取账本后强制重建图谱，让存量实体重新抽取 type；"
            + "会重新消耗抽取模型额度，仅在用户显式触发时执行")
    @Permission("graph:build")
    @PostMapping("/{kbId}/types/rebuild")
    public ResultJson rebuildTypes(@PathVariable("kbId") String kbId) {
        requireManage(kbId);
        return ResultJson.ok(graphRagService.rebuildTypes(kbId));
    }

    @Operation(summary = "构建进度与图谱规模", description = "building/total/done/failed/extracted + 实体数/三元组数")
    @Permission("graph:read")
    @GetMapping("/{kbId}/status")
    public ResultJson status(@PathVariable("kbId") String kbId) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.status(kbId));
    }

    @Operation(summary = "三元组浏览", description = "带实体名与来源文档名的分页列表（溯源核对用）")
    @Permission("graph:read")
    @GetMapping("/{kbId}/triples")
    public ResultJson triples(@PathVariable("kbId") String kbId,
                              @RequestParam(value = "page", defaultValue = "1") int page,
                              @RequestParam(value = "size", defaultValue = "20") int size) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.triples(kbId, page, size));
    }

    @Operation(summary = "图谱视图（§5 聚合）", description = "按关联度取前 N 个实体及其间关系（谓词聚合计数），画图专用数据；"
            + "超出前 N 时 truncated=true，定位走 /search + /neighbor")
    @Permission("graph:read")
    @GetMapping("/{kbId}/view")
    public ResultJson view(@PathVariable("kbId") String kbId,
                           @RequestParam(value = "limit", defaultValue = "300") int limit) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.view(kbId, limit));
    }

    @Operation(summary = "实体详情", description = "实体的全部三元组（主/客两侧），带来源文档与知识块定位（溯源跳块用）")
    @Permission("graph:read")
    @GetMapping("/{kbId}/entity/{entityId}")
    public ResultJson entity(@PathVariable("kbId") String kbId, @PathVariable("entityId") String entityId) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.entityDetail(kbId, entityId));
    }

    @Operation(summary = "实体邻域子图", description = "以实体为中心的一跳邻域（nodes/edges 与 /view 同构）："
            + "搜索命中的实体不在默认视图内时，前端据此重渲染聚焦视图")
    @Permission("graph:read")
    @GetMapping("/{kbId}/neighbor/{entityId}")
    public ResultJson neighbor(@PathVariable("kbId") String kbId, @PathVariable("entityId") String entityId) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.neighbor(kbId, entityId));
    }

    @Operation(summary = "实体搜索", description = "按显示名/归一名模糊匹配（前 20 个，提及次数降序），图谱内定位入口")
    @Permission("graph:read")
    @GetMapping("/{kbId}/search")
    public ResultJson search(@PathVariable("kbId") String kbId, @RequestParam("q") String q) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.search(kbId, q));
    }

    @Operation(summary = "知识块内容", description = "按 chunkId 取块原文与所属文档（实体三元组「查看源块」弹层用）；跨库一律 404")
    @Permission("graph:read")
    @GetMapping("/{kbId}/chunk/{chunkId}")
    public ResultJson chunk(@PathVariable("kbId") String kbId, @PathVariable("chunkId") String chunkId) {
        requireRead(kbId);
        return ResultJson.ok(graphRagService.chunk(kbId, chunkId));
    }

    @Operation(summary = "清空图谱", description = "删该库全部三元组/实体/抽取记录（显式动作；开关关闭不删数据）")
    @Permission("graph:delete")
    @DeleteMapping("/{kbId}")
    public ResultJson clear(@PathVariable("kbId") String kbId) {
        requireManage(kbId);
        return ResultJson.ok(graphRagService.clear(kbId));
    }

    @Operation(summary = "修复移动文档的图谱归属（存量）", description = "图谱随迁修复上线前被跨库移动过的文档，"
            + "三元组/实体仍挂在旧库：扫「三元组库 ≠ 文档现库」的数据逐文档迁移（与移动链路同一算法）。"
            + "幂等可重复执行，纯行级搬运不调 LLM。全库扫描无 kbId 可校验归属，仅管理员级可达（不标授权点）")
    @PostMapping("/repair-moved")
    public ResultJson repairMoved() {
        return ResultJson.ok(graphRagService.repairMovedDocsGraph());
    }
}
