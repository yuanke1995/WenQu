package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.AiWorkflow;
import com.wisesoft.ai.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作流管理接口（个人资产，M0~M3 仅创建者本人可见可管；归属取登录令牌 uid）。
 * <p>
 * 分期：M0 = CRUD + 校验/编译 dry-run；M1 = 运行接口 + trace；M4 = 发布/共享/API 触发。
 * 路径在 SecurityConfig 登录白名单内（与 memory/scheduled 同口径），归属校验在服务层按 uid 做。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/workflow")
@RequiredArgsConstructor
@Tag(name = "工作流", description = "工作流定义：CRUD / DSL 校验与编译 dry-run（执行随 M1 开放）")
public class WorkflowController {

    private final WorkflowService workflowService;

    @Operation(summary = "本人工作流列表", description = "按更新时间倒序；M4 前仅返回本人创建的")
    @GetMapping("/list")
    public ResultJson list() {
        List<AiWorkflow> rows = workflowService.listOwn();
        return ResultJson.ok(rows);
    }

    @Operation(summary = "工作流详情", description = "含完整 DSL（画布加载即还原图形）")
    @GetMapping("/{id}")
    public ResultJson get(@PathVariable String id) {
        return ResultJson.ok(workflowService.getOwn(id));
    }

    @Operation(summary = "新建工作流", description = "body: {name, description?, dsl}；DSL 必须通过结构校验（不收坏图）")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        AiWorkflow row = workflowService.create(str(body, "name"), str(body, "description"), str(body, "dsl"));
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "更新工作流", description = "body: {name?, description?, dsl?}；只传要改的字段，dsl 变更会重新校验")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AiWorkflow row = workflowService.update(id, str(body, "name"), str(body, "description"), str(body, "dsl"));
        return ResultJson.ok(Map.of("id", row.getId(), "name", row.getName()));
    }

    @Operation(summary = "删除工作流", description = "只删本人的；运行记录保留（回放/审计价值独立于定义）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        workflowService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "DSL 校验 + 编译 dry-run", description = "body: {dsl}；返回 errors（结构问题逐条）/ compiled（翻译层是否吃下）/ nodeCount / edgeCount。画布实时校验与保存前校验共用")
    @PostMapping("/validate")
    public ResultJson validate(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(workflowService.validate(str(body, "dsl")));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
