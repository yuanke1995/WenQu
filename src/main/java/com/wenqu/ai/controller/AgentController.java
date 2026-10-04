package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ResourceVisibilityService;
import com.wenqu.ai.util.BatchResults;
import com.wenqu.ai.util.RequestUser;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 智能体配置接口：**用户可自建自管**（2026-09-26 从"仅管理员"放开）。
 * <p>
 * 数据隔离口径（{@link ResourceVisibilityService}，资源类型 AGENT）——谁建归谁，按 userId 隔离：
 * <ul>
 *   <li>创建者 → 可管理自己的智能体（创建者短路）；未配置共享＝私有，仅创建者可见；</li>
 *   <li>他人 → 仅按智能体显式配置的 share_config 共享范围可见可用；</li>
 *   <li>内置「问渠」是系统默认：所有登录用户可读可用，<b>管理员级可配置</b>（不再依赖 created_by 恰好是管理员 uid）；</li>
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
    private final com.wenqu.ai.service.AgentShareService agentShareService;
    private final com.wenqu.ai.service.ModelRegistryService modelRegistryService;

    /**
     * 当前用户能否管理该智能体（数据按 userId 隔离：创建者或共享 manage 命中；内置「问渠」为管理员级可配置）。
     * <p>统一委托 {@link AgentService#manageable}，与写路径 {@code ensureManageable} 同一入口——此处此前自带一份
     * 判定，与服务层口径不一致：内置行在服务层被 {@link ResourceVisibilityService} 判 NONE，列表的
     * {@code manageable} 字段却按另一套算，前端据此把管理员本可编辑的内置行收起了编辑入口。</p>
     */
    private boolean canManage(com.wenqu.ai.model.Agent a) {
        return agentService.manageable(a);
    }

    @Operation(summary = "智能体列表", description = "默认智能体在前；其余按创建时间倒序；所有人只返回自己创建的、内置问渠与显式共享给自己的"
            + "（数据按 userId 隔离）；每行带 manageable（是否可管理，供前端收起配置/共享/发布/删除入口）")
    @GetMapping("/list")
    public ResultJson list() {
        List<com.wenqu.ai.model.Agent> agents = agentService.list();
        for (com.wenqu.ai.model.Agent a : agents) {
            a.setManageable(canManage(a) ? 1 : 0);
        }
        agents = agents.stream().filter(agentService::readable).toList();
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

    @Operation(summary = "新建智能体", description = "body 字段：name(必填)/icon(图标：wenqu=问渠品牌标（内置「问渠」专属，其它智能体不可用）/ emoji 字符，省略=默认展示)/"
            + "description/model/systemPrompt/knowledgeScope/"
            + "toolKnowledge/toolBuiltin/toolSkill/toolArtifact/toolMcp/toolWebsearch(1开0关，省略=继承)/"
            + "skills/mcps/builtinTools(具体项范围：省略=跟随全局、空串=不使用、逗号串=仅这些)/"
            + "isSubagent(1=子智能体)/subAgentIds(主智能体可委派的子智能体ID)/isDefault；创建者=当前用户")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(agentService.create(body));
    }

    @Operation(summary = "编辑智能体", description = "仅更新 body 中出现的字段（icon：wenqu=问渠品牌标（内置「问渠」专属）/ emoji；内置智能体的名称不可修改）；"
            + "工具开关传 null 表示恢复继承；仅创建者/被授权人/管理员可改")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        return ResultJson.ok(agentService.update(id, body));
    }

    @Operation(summary = "删除智能体", description = "物理删除；仅创建者/被授权人/管理员可删")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        agentService.delete(id);
        return ResultJson.ok(Map.of("id", id));
    }

    // --------------------------------------------------------------------------------------------------
    // 批量操作：逐条执行、部分成功是批量的固有语义——失败条目逐条带原因（结构收口在 BatchResults）
    // --------------------------------------------------------------------------------------------------

    @Operation(summary = "批量删除智能体", description = "body: {ids:[...]}；逐条按单条删除口径判权"
            + "（创建者/被授权人/管理员，内置智能体不可删）；返回 {succeeded:[id], failed:[{id,name,error}]}")
    @PostMapping("/batch-delete")
    public ResultJson batchDelete(@RequestBody Map<String, Object> body) {
        List<String> ids = BatchResults.parseIds(body);
        if (ids.isEmpty()) return ResultJson.error("请先选择要删除的智能体");
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            // get() 对不存在/不可读一律返回 null：逐条进 failed 对账，不静默丢
            com.wenqu.ai.model.Agent a = agentService.get(id);
            if (a == null) {
                failed.add(BatchResults.failItem(id, null, "不存在或无权操作"));
                continue;
            }
            if (!canManage(a)) {
                failed.add(BatchResults.failItem(id, a.getName(), "仅可管理自己创建或被授权管理的智能体"));
                continue;
            }
            try {
                agentService.delete(id);
                succeeded.add(id);
            } catch (Exception e) {
                failed.add(BatchResults.failItem(id, a.getName(), BatchResults.errMsg(e)));
            }
        }
        return ResultJson.ok(BatchResults.result(ids, succeeded, failed));
    }

    @Operation(summary = "设为默认", description = "设为默认智能体（其余清零）；仅影响前端下拉预选，不自动强制应用。全局动作，仅管理员")
    @PostMapping("/{id}/default")
    public ResultJson setDefault(@PathVariable("id") String id) {
        agentService.setDefault(id);
        return ResultJson.ok(Map.of("id", id, "isDefault", 1));
    }

    @Operation(summary = "设置共享范围", description = "body: {shareConfig}——空串 = 清空（回落私有：仅自己可见）；"
            + "非空须为 version 2 JSON，且管理范围不得宽于读取范围。共享范围之外的人不可见、不可用、不可管理该智能体")
    @PutMapping("/{id}/share")
    public ResultJson updateShare(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        Object v = body == null ? null : body.get("shareConfig");
        agentService.updateShareConfig(id, v == null ? null : String.valueOf(v));
        return ResultJson.ok("共享范围已保存");
    }

    @Operation(summary = "查询公开分享配置", description = "返回 {enabled, mcpEnabled, token, modelRef}；未发布返回 enabled=false（仅可管理者可见）")
    @GetMapping("/{id}/publish")
    public ResultJson getPublish(@PathVariable("id") String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        var share = agentShareService.getByAgent(id);
        if (share == null) return ResultJson.ok(Map.of("enabled", false, "mcpEnabled", false));
        return ResultJson.ok(Map.of(
                "enabled", share.getEnabled() != null && share.getEnabled() == 1,
                "mcpEnabled", share.getMcpEnabled() != null && share.getMcpEnabled() == 1,
                "token", share.getToken() == null ? "" : share.getToken(),
                "modelRef", share.getModelRef() == null ? "" : share.getModelRef()));
    }

    @Operation(summary = "发布/更新公开分享", description = "body: {enabled, mcpEnabled, modelRef}——开启后 /s/{token} 免登录可对话；"
            + "mcpEnabled=true 时同一个 token 兼作 MCP 端点 /ai/mcp/{token}（Streamable HTTP，供 Claude/Cursor 等接入）；"
            + "modelRef 为游客对话模型引用（须为自己可用的供应商模型，空=回退本人个人默认模型）；首次发布生成 token，此后不变。"
            + "游客能力收窄：沙盒/产物/MCP/技能执行不暴露，检索可见性与默认模型按发布者执行")
    @PostMapping("/{id}/publish")
    public ResultJson publish(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        boolean mcpEnabled = Boolean.parseBoolean(String.valueOf(body.get("mcpEnabled")));
        Object refObj = body.get("modelRef");
        String modelRef = refObj == null ? "" : String.valueOf(refObj).trim();
        // MCP 端点是分享的附加形态：分享停用（enabled=0）时链接与端点一并失效，
        // 因此"开了 MCP 却没开分享"是无效组合，显式拒绝而不是让用户发布完才发现端点不通
        if (mcpEnabled && !enabled) {
            return ResultJson.error("开启 MCP 端点需要先启用公开分享");
        }
        // 模型引用归属校验：只能用自己可用的供应商（平台级 + 自己登记的个人级），发布时 fail-loud
        if (enabled && !modelRef.isBlank()) {
            modelRegistryService.assertUsable(modelRef, RequestUser.uid(), RequestUser.role());
        }
        var share = agentShareService.publish(id, enabled, mcpEnabled, modelRef, RequestUser.uid());
        return ResultJson.ok(Map.of(
                "enabled", enabled,
                "mcpEnabled", mcpEnabled,
                "token", share.getToken() == null ? "" : share.getToken(),
                "modelRef", share.getModelRef() == null ? "" : share.getModelRef()));
    }

    @Operation(summary = "撤销公开分享", description = "删除分享配置（链接立即失效）；重新发布会生成新 token")
    @DeleteMapping("/{id}/publish")
    public ResultJson revokePublish(@PathVariable("id") String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        agentShareService.revoke(id);
        return ResultJson.ok("已撤销分享");
    }
}
