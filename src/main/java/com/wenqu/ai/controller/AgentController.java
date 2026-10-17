package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.AgentShareService;
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
    /** 工具注册中心的对外读口（能力名册）：智能体配置页的工具可选项从这里来，不再前端手抄 */
    private final com.wenqu.ai.service.ToolInventoryService toolInventoryService;
    /** 权限档位（P2）名册：配置页的档位选项与「管理员有没有放开这一档」从这里来，不在前端抄第二份 */
    private final com.wenqu.ai.agent.permission.PermissionPolicyResolver permissionPolicyResolver;

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
            + "（数据按 userId 隔离）；每行带 manageable（是否可管理，供前端收起配置/共享/发布/删除入口）"
            + "与 published（公开发布中 1/0，供卡片标「已发布」）")
    @GetMapping("/list")
    public ResultJson list() {
        List<com.wenqu.ai.model.Agent> agents = agentService.list();
        for (com.wenqu.ai.model.Agent a : agents) {
            a.setManageable(canManage(a) ? 1 : 0);
        }
        // 发布状态整页一次 in 回填（c_ai_agent_share：行存在且 enabled=1 才算发布中；停用/撤销都不算）
        java.util.Set<String> publishedIds = new java.util.HashSet<>();
        for (com.wenqu.ai.model.AgentShare s : agentShareService.listByAgents(agents.stream().map(com.wenqu.ai.model.Agent::getId).toList())) {
            if (s.getEnabled() != null && s.getEnabled() == 1) publishedIds.add(s.getAgentId());
        }
        for (com.wenqu.ai.model.Agent a : agents) {
            a.setPublished(publishedIds.contains(a.getId()) ? 1 : 0);
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

    @Operation(summary = "可读助手名称表", description = "本轮可咨询范围内的 id+名称（含子智能体，按可读性过滤）。"
            + "与 /available 的差别：那份剔除子智能体（不能当问答角色），而咨询清单包含它们——"
            + "前端还原「本轮咨询了谁」的徽标需要这一份，否则子智能体只能显示裸 id")
    @GetMapping("/consultable")
    public ResultJson consultable() {
        return ResultJson.ok(agentService.consultable());
    }

    @Operation(summary = "可委派的子智能体", description = "只返回 is_subagent=1 的智能体（id/name/description/"
            + "subagentMode 这一支实际怎么跑 + subagentModeInherited 是否跟随平台默认），供主智能体配置页勾选允许委派的对象")
    @GetMapping("/sub")
    public ResultJson subAgents() {
        return ResultJson.ok(agentService.subAgents());
    }

    /**
     * 能力名册（智能体配置页用）：平台已声明工具的 family/name/中文名，登录用户可读。
     * <p>为什么挂在这里而不是管理员的 {@code /api/ai/tools}：配置智能体是普通用户也在做的事，
     * 而那份全景清单带全局开关键与 enabled 状态，按既定口径不开洞。这个位置落在
     * {@code SecurityConfig.isAgentSelfEndpoint} 已放行的智能体自助端点分支里，不新增通配规则。
     * <p>它消灭的是第三份工具中文名手抄（前端 AgentsPage 自己那张内置工具选项表）：
     * 后端加一个 @Tool 就得同步改前端，漏改的表现是配置页里那个工具只剩英文名或根本看不见。
     */
    @Operation(summary = "能力名册", description = "返回平台已声明工具的 family/familyLabel/name/label/cardLabel，"
            + "供智能体配置页生成「指定内置工具」的可选项（中文名以工具注册中心为唯一来源）")
    @GetMapping("/tool-catalog")
    public ResultJson toolCatalog() {
        return ResultJson.ok(toolInventoryService.agentCatalog());
    }

    /**
     * 权限档位名册（P2，智能体配置页的档位选项从这里来）。
     * <p>与 {@code /tool-catalog} 同一条访问控制：路径落在 {@code SecurityConfig.isAgentSelfEndpoint}
     * 已放行的「一段路径」分支里，不新增规则。
     * <p>为什么由后端下发而不是前端写死三个档位：档位列表要随平台上限变化（管理员收紧后哪些档可选），
     * 前端抄一份的话「管理员禁了全自主、配置页还让人选」就会出现选了不生效且没有任何提示的界面。
     */
    @Operation(summary = "权限档位名册", description = "返回可选档位：code/label/summary/沙盒执行档/审批建议值，"
            + "以及该档当前是否可用（available）与被上限禁掉的原因（unavailableReason，区分平台上限与个人上限）")
    @GetMapping("/permission-presets")
    public ResultJson permissionPresets() {
        // 按当前请求者算上限：管理员收紧与成员自设的个人上限取低者，界面上灰掉的理由才说得出是谁收的
        return ResultJson.ok(permissionPolicyResolver.catalog(RequestUser.uid()));
    }

    @Operation(summary = "新建智能体", description = "body 字段：name(必填)/icon(图标：wenqu=问渠品牌标（内置「问渠」专属，其它智能体不可用）/ emoji 字符，省略=默认展示)/"
            + "description/model/systemPrompt/knowledgeScope/"
            + "toolKnowledge/toolBuiltin/toolSkill/toolArtifact/toolMcp/toolWebsearch(1开0关，省略=继承)/"
            + "permissionPreset(权限档位 research/standard/autonomous，省略=跟随平台默认档)/"
            + "toolDeny(按名禁用的工具，逗号分隔；省略或空=不禁用)/"
            + "skills/mcps/builtinTools(具体项范围：省略=跟随全局、空串=不使用、逗号串=仅这些)/"
            + "isSubagent(1=子智能体)/subAgentIds(主智能体可委派的子智能体ID)/"
            + "subagentMode(被委派时怎么跑 digest=检索要点 full=全循环，省略=跟随平台默认 agent.subagentMode)/isDefault；创建者=当前用户")
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

    @Operation(summary = "配置版本列表", description = "每次保存落一版（人设/知识范围/工具集等调优字段快照），新→旧返回；"
            + "每版带 config（配置对象）、changes（与上一版的字段级差异 [{field,label,from,to}]）、"
            + "current（是否最新版记录）、identical（内容是否与当前配置一致）；仅可管理者可见")
    @GetMapping("/{id}/versions")
    public ResultJson versions(@PathVariable("id") String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        return ResultJson.ok(agentService.listVersions(id));
    }

    @Operation(summary = "回滚配置到历史版本", description = "body: {version}——把该历史版本的调优字段重新应用，并落一版新快照（reason 记「回滚自 vX」）；"
            + "历史版本本身不被改写，回滚动作可再次回滚；仅可管理者可操作")
    @PostMapping("/{id}/rollback")
    public ResultJson rollback(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        Object v = body == null ? null : body.get("version");
        if (v == null) return ResultJson.error("请指定要回滚的版本号");
        int target;
        try {
            target = Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return ResultJson.error("版本号不合法：" + v);
        }
        return ResultJson.ok(agentService.rollback(id, target));
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

    @Operation(summary = "查询公开分享配置", description = "返回 {enabled, mcpEnabled, guestImages, guestFiles, token, modelRef, visitCount, lastVisitAt}；"
            + "未发布返回 enabled=false（仅可管理者可见）")
    @GetMapping("/{id}/publish")
    public ResultJson getPublish(@PathVariable("id") String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        var share = agentShareService.getByAgent(id);
        // 未发布时能力位一律 false：多模态开关与分享同生命周期，分享没开就没有"游客能不能发图"这回事
        if (share == null) {
            return ResultJson.ok(Map.of("enabled", false, "mcpEnabled", false,
                    "guestImages", false, "guestFiles", false));
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", share.getEnabled() != null && share.getEnabled() == 1);
        out.put("mcpEnabled", share.getMcpEnabled() != null && share.getMcpEnabled() == 1);
        out.put("guestImages", AgentShareService.guestImagesOn(share));
        out.put("guestFiles", AgentShareService.guestFilesOn(share));
        out.put("token", share.getToken() == null ? "" : share.getToken());
        out.put("modelRef", share.getModelRef() == null ? "" : share.getModelRef());
        // 发布出去之后唯一能看到的反馈：被问过几次、最近一次是什么时候（会话内容不对发布者开放）
        out.put("visitCount", share.getVisitCount() == null ? 0 : share.getVisitCount());
        out.put("lastVisitAt", share.getLastVisitAt());
        return ResultJson.ok(out);
    }

    @Operation(summary = "发布/更新公开分享", description = "body: {enabled, mcpEnabled, guestImages, guestFiles, modelRef}——开启后 /s/{token} 免登录可对话；"
            + "mcpEnabled=true 时同一个 token 兼作 MCP 端点 /ai/mcp/{token}（Streamable HTTP，供 Claude/Cursor 等接入）；"
            + "guestImages/guestFiles 分别控制访客能否发图、能否上传文档附件（默认都关）；"
            + "modelRef 为游客对话模型引用（须为自己可用的供应商模型，空=回退本人个人默认模型）；首次发布生成 token，此后不变。"
            + "游客能力收窄：沙盒/产物/MCP/技能执行不暴露，检索可见性与默认模型按发布者执行")
    @PostMapping("/{id}/publish")
    public ResultJson publish(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        boolean mcpEnabled = Boolean.parseBoolean(String.valueOf(body.get("mcpEnabled")));
        boolean guestImages = Boolean.parseBoolean(String.valueOf(body.get("guestImages")));
        boolean guestFiles = Boolean.parseBoolean(String.valueOf(body.get("guestFiles")));
        Object refObj = body.get("modelRef");
        String modelRef = refObj == null ? "" : String.valueOf(refObj).trim();
        // MCP 端点是分享的附加形态：分享停用（enabled=0）时链接与端点一并失效，
        // 因此"开了 MCP 却没开分享"是无效组合，显式拒绝而不是让用户发布完才发现端点不通
        if (mcpEnabled && !enabled) {
            return ResultJson.error("开启 MCP 端点需要先启用公开分享");
        }
        // 多模态能力位同理依赖分享本身：链接都停了就无所谓游客能不能发图——收了会让弹窗回显出
        // 「未发布 + 已允许发图」这种自相矛盾的状态，归一成关
        if (!enabled) {
            guestImages = false;
            guestFiles = false;
        }
        // 模型引用归属校验：只能用自己可用的供应商（平台级 + 自己登记的个人级），发布时 fail-loud
        if (enabled && !modelRef.isBlank()) {
            modelRegistryService.assertUsable(modelRef, RequestUser.uid(), RequestUser.role());
        }
        // 允许游客发图却不校验模型视觉能力：不拦，因为「跟随个人默认模型」这一档在发布时解析不到具体模型，
        // 拦了会把正确配置也挡在门外。改为发布弹窗就地提示（前端按 ModelSelect 选中的模型给警示），
        // 把判断留给最清楚自己用哪个模型的人
        var share = agentShareService.publish(id,
                new AgentShareService.PublishSpec(enabled, mcpEnabled, guestImages, guestFiles, modelRef),
                RequestUser.uid());
        java.util.Map<String, Object> ok = new java.util.LinkedHashMap<>();
        ok.put("enabled", enabled);
        ok.put("mcpEnabled", mcpEnabled);
        ok.put("guestImages", AgentShareService.guestImagesOn(share));
        ok.put("guestFiles", AgentShareService.guestFilesOn(share));
        ok.put("token", share.getToken() == null ? "" : share.getToken());
        ok.put("modelRef", share.getModelRef() == null ? "" : share.getModelRef());
        return ResultJson.ok(ok);
    }

    @Operation(summary = "撤销公开分享", description = "删除分享配置（链接立即失效）；重新发布会生成新 token")
    @DeleteMapping("/{id}/publish")
    public ResultJson revokePublish(@PathVariable("id") String id) {
        if (!canManage(agentService.get(id))) return ResultJson.error("仅可管理自己创建或被授权管理的智能体");
        agentShareService.revoke(id);
        return ResultJson.ok("已撤销分享");
    }
}
