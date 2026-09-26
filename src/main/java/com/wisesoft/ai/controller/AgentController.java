package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.AgentService;
import com.wisesoft.ai.service.ResourceVisibilityService;
import com.wisesoft.ai.util.RequestUser;
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
 * 智能体配置接口：**用户可自建自管**（2026-09-26 从"仅管理员"放开）。
 * <p>
 * 数据隔离口径（{@link ResourceVisibilityService}，资源类型 AGENT：全角色可管理）：
 * <ul>
 *   <li>创建者 → 可管理自己的智能体（创建者短路）；</li>
 *   <li>他人 → 按智能体的 share_config 共享范围（未配置=全局可见）可见可用；</li>
 *   <li>列表/编辑/删除/共享都按上述范围判定，看不见的智能体当不存在（不泄露存在性）。</li>
 * </ul>
 * 刻意保留管理员专属：{@code /{id}/default}（设默认是全局动作，影响所有人的下拉预选），
 * 该路径不在 SecurityConfig 用户白名单内，仍由拦截器拦下。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
@Tag(name = "智能体配置", description = "P3：4.1 Agent 配置——模型/知识库/工具/提示词打包成命名预设")
public class AgentController {

    private final AgentService agentService;
    private final ResourceVisibilityService visibility;
    private final com.wisesoft.ai.service.RoleService roleService;

    /** 当前登录态的权限主体 */
    private ResourceVisibilityService.Principal principal() {
        return new ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 当前用户能否管理该智能体（管理员级角色照旧全量；普通用户=创建者或共享 manage 命中） */
    private boolean canManage(com.wisesoft.ai.model.Agent a) {
        if (a == null) return false;
        if (roleService.isAdminCode(RequestUser.role())) return true;
        return visibility.canManage(principal(), a.getShareConfig(), a.getCreatedBy(),
                ResourceVisibilityService.ResourceKind.AGENT);
    }

    @Operation(summary = "智能体列表", description = "默认智能体在前；其余按创建时间倒序；普通用户只返回共享范围内可见的")
    @GetMapping("/list")
    public ResultJson list() {
        List<?> agents = agentService.list();
        if (!roleService.isAdminCode(RequestUser.role())) {
            var p = principal();
            agents = agents.stream().filter(o -> {
                com.wisesoft.ai.model.Agent a = (com.wisesoft.ai.model.Agent) o;
                return visibility.canRead(p, a.getShareConfig(), a.getCreatedBy(),
                        ResourceVisibilityService.ResourceKind.AGENT);
            }).toList();
        }
        return ResultJson.ok(agents);
    }

    @Operation(summary = "对话页可用的智能体", description = "问答用户可读的精简列表（仅 id/name/description/model/isDefault），供对话页下拉选择；"
            + "不含提示词/知识库范围/工具开关等管理配置。该路径在问答用户白名单内")
    @GetMapping("/available")
    public ResultJson available() {
        return ResultJson.ok(agentService.available());
    }

    @Operation(summary = "可委派的子智能体", description = "只返回 is_subagent=1 的智能体（id/name/description），供主智能体配置页勾选允许委派的对象")
    @GetMapping("/sub")
    public ResultJson subAgents() {
        return ResultJson.ok(agentService.subAgents());
    }

    @Operation(summary = "新建智能体", description = "body 字段：name(必填)/description/model/systemPrompt/knowledgeScope/"
            + "toolKnowledge/toolBuiltin/toolSkill/toolArtifact/toolMcp(1开0关，省略=继承)/"
            + "skills/mcps/builtinTools(具体项范围：省略=跟随全局、空串=不使用、逗号串=仅这些)/"
            + "isSubagent(1=子智能体)/subAgentIds(主智能体可委派的子智能体ID)/isDefault；创建者=当前用户")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(agentService.create(body));
    }

    @Operation(summary = "编辑智能体", description = "仅更新 body 中出现的字段；工具开关传 null 表示恢复继承；仅创建者/被授权人/管理员可改")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        return ResultJson.ok(agentService.update(id, body));
    }

    @Operation(summary = "删除智能体", description = "物理删除；仅创建者/被授权人/管理员可删")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        agentService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "设为默认", description = "设为默认智能体（其余清零）；仅影响前端下拉预选，不自动强制应用。全局动作，仅管理员")
    @PostMapping("/{id}/default")
    public ResultJson setDefault(@PathVariable String id) {
        agentService.setDefault(id);
        return ResultJson.ok(Map.of("id", id, "isDefault", 1));
    }

    @Operation(summary = "设置共享范围", description = "body: {shareConfig}——空串 = 清空（回落全局共享）；"
            + "非空须为 version 2 JSON，且管理范围不得宽于读取范围。共享范围之外的人不可见、不可用、不可管理该智能体")
    @PutMapping("/{id}/share")
    public ResultJson updateShare(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        Object v = body == null ? null : body.get("shareConfig");
        agentService.updateShareConfig(id, v == null ? null : String.valueOf(v));
        return ResultJson.ok("共享范围已保存");
    }
}
