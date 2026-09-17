package com.wisesoft.ai.service;

import com.wisesoft.ai.util.RequestUser;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.AgentMapper;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.service.ResourceVisibilityService.Principal;
import com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 智能体配置服务（P3：4.1 Agent 配置——模型/知识库/工具/提示词）。
 * <p>
 * 一个智能体把「模型 / 系统提示词 / 知识库范围 / 工具开关」打包成命名预设，对话页下拉切换；
 * 选中后该轮问答按智能体覆盖全局配置（未填维度继承全局）。工具开关三态：1=开 0=关 null=继承。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper mapper;
    private final ResourceVisibilityService resourceVisibilityService;

    /** 当前请求者（可见性/可管性判定的输入） */
    private Principal principal() {
        return new Principal(RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 当前用户是否可读取该智能体（未配置共享＝全局，行为与从前一致） */
    private boolean readable(Agent a) {
        return resourceVisibilityService.canRead(principal(), a.getShareConfig(), a.getCreatedBy(), ResourceKind.AGENT);
    }

    /** 当前用户是否可管理该智能体（出现在管理端点前先过这道闸） */
    private void ensureManageable(Agent a) {
        if (!resourceVisibilityService.canManage(principal(), a.getShareConfig(), a.getCreatedBy(), ResourceKind.AGENT)) {
            throw new BizException(403, "无权管理该智能体（不在其共享管理范围内）");
        }
    }

    /** 列表（默认智能体在前，其余按创建时间倒序） */
    public List<Agent> list() {
        return mapper.selectList(new LambdaQueryWrapper<Agent>()
                .orderByDesc(Agent::getIsDefault)
                .orderByDesc(Agent::getCreateTime));
    }

    /**
     * 对话页下拉用（普通用户可读）：只暴露「选择智能体」所需的最小字段。
     * <p>不返回 systemPrompt / knowledgeScope / 工具开关——那些是管理配置，
     * 不应经由只读接口外泄；对话页只需要 id 与展示名。</p>
     */
    public List<Map<String, Object>> available() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Agent a : list()) {
            // 子智能体不出现在对话页下拉：它只能被主智能体委派调用，不能当作问答角色直接选用
            if (Integer.valueOf(1).equals(a.getIsSubagent())) continue;
            // 共享范围之外的人不应在对话页看到该智能体（未配置共享＝全局，行为不变）
            if (!readable(a)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("description", a.getDescription());
            m.put("model", a.getModel());
            m.put("isDefault", a.getIsDefault() == null ? 0 : a.getIsDefault());
            out.add(m);
        }
        return out;
    }

    /**
     * 可委派的子智能体（供主智能体配置页勾选）。
     * 只返回子智能体（is_subagent=1），且是最小字段——配置页只需要 id 与展示名。
     */
    public List<Map<String, Object>> subAgents() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Agent> subs = mapper.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getIsSubagent, 1)
                .orderByDesc(Agent::getCreateTime));
        for (Agent a : subs) {
            if (!readable(a)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("description", a.getDescription());
            out.add(m);
        }
        return out;
    }

    /**
     * 按 ID 取（不存在返回 null，调用方据此降级为全局配置）。
     * <p>额外做可读校验：共享范围之外的人即使拿到 id，也不能把该智能体套用到自己的问答上——
     * 一律按「不存在」处理，直接回落全局配置（避免用 id 绕过可见性拿到别人的提示词/知识库范围）。</p>
     */
    public Agent get(String id) {
        if (!StringUtils.hasText(id)) return null;
        Agent a = mapper.selectById(id);
        return (a != null && readable(a)) ? a : null;
    }

    /** 默认智能体（无则返回 null） */
    public Agent defaultAgent() {
        return mapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getIsDefault, 1).last("LIMIT 1"));
    }

    /** 新建智能体；isDefault=true 时先清空其它默认 */
    public Agent create(Map<String, Object> body) {
        Agent a = toEntity(body, new Agent());
        a.setCreatedBy(RequestUser.uid());
        a.setCreateTime(LocalDateTime.now());
        a.setUpdateTime(LocalDateTime.now());
        // 子智能体不参与「默认」：它只能被主智能体委派调用，不能作为对话页预选角色
        boolean isSub = Integer.valueOf(1).equals(a.getIsSubagent());
        if (!isSub && (Boolean.TRUE.equals(a.getIsDefault()) || Integer.valueOf(1).equals(a.getIsDefault()))) {
            clearDefault();
            a.setIsDefault(1);
        } else {
            a.setIsDefault(0);
        }
        mapper.insert(a);
        log.info("[AGENT] 新建智能体 {}（{}）", a.getId(), a.getName());
        return a;
    }

    /** 更新智能体；isDefault 变化时同步处理唯一默认 */
    public Agent update(String id, Map<String, Object> body) {
        Agent existing = mapper.selectById(id);
        if (existing == null) throw new BizException(404, "智能体不存在");
        ensureManageable(existing);
        Agent a = toEntity(body, existing);
        a.setUpdateTime(LocalDateTime.now());
        if (Integer.valueOf(1).equals(a.getIsDefault())) {
            clearDefault();
            a.setIsDefault(1);
        }
        mapper.updateById(a);
        log.info("[AGENT] 更新智能体 {}（{}）", a.getId(), a.getName());
        return a;
    }

    /** 删除智能体（内置标记的禁止删除） */
    public void delete(String id) {
        Agent existing = mapper.selectById(id);
        if (existing != null) {
            ensureManageable(existing);
            if (Integer.valueOf(1).equals(existing.getIsBuiltin())) {
                throw new BizException("内置智能体不可删除（如需调整请编辑其配置）");
            }
        }
        mapper.deleteById(id);
        log.info("[AGENT] 删除智能体 {}", id);
    }

    /** 设为默认（其余清零） */
    public void setDefault(String id) {
        Agent target = mapper.selectById(id);
        if (target == null) throw new BizException(404, "智能体不存在");
        ensureManageable(target);
        if (Integer.valueOf(1).equals(target.getIsSubagent())) {
            throw new BizException("子智能体不能设为默认：它只能被主智能体委派调用");
        }
        clearDefault();
        Agent a = new Agent();
        a.setId(id);
        a.setIsDefault(1);
        a.setUpdateTime(LocalDateTime.now());
        mapper.updateById(a);
        log.info("[AGENT] 设默认智能体 {}", id);
    }

    /**
     * 写入共享范围（空串 = 清空 → 回落全局共享）。
     * <p>校验口径与文档一致：必须 {@code version=2}，且管理范围不得宽于读取范围。</p>
     * <p><b>必须用 {@code set(..., null)} 显式置空</b>：MyBatis-Plus 默认更新策略是 NOT_NULL，
     * {@code updateById} 会跳过 null 字段，导致「恢复全员共享」静默不生效。</p>
     */
    public void updateShareConfig(String id, String shareConfigJson) {
        Agent existing = mapper.selectById(id);
        if (existing == null) throw new BizException(404, "智能体不存在");
        ensureManageable(existing);
        resourceVisibilityService.validateShareConfig(shareConfigJson);
        String normalized = (shareConfigJson == null || shareConfigJson.isBlank()) ? null : shareConfigJson;
        mapper.update(null, new LambdaUpdateWrapper<Agent>()
                .eq(Agent::getId, id)
                .set(Agent::getShareConfig, normalized)
                .set(Agent::getUpdateTime, LocalDateTime.now()));
        log.info("[AGENT] 共享范围更新 id={} scope={}", id, normalized == null ? "全局" : "受限");
    }

    /** 把请求体字段映射到实体（仅覆盖 body 中出现的字段，其余保持原值） */
    private Agent toEntity(Map<String, Object> body, Agent a) {
        if (body == null) return a;
        if (body.containsKey("name")) {
            String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
            if (!StringUtils.hasText(name)) throw new com.wisesoft.ai.common.BizException("智能体名称不能为空");
            if (name.length() > 200) name = name.substring(0, 200);
            a.setName(name);
        }
        if (body.containsKey("description")) a.setDescription(asText(body.get("description"), 500));
        if (body.containsKey("model")) a.setModel(asText(body.get("model"), 255));
        if (body.containsKey("systemPrompt")) a.setSystemPrompt(asText(body.get("systemPrompt"), 60000));
        if (body.containsKey("knowledgeScope")) a.setKnowledgeScope(asText(body.get("knowledgeScope"), 2000));
        // 「不使用知识库」：纯角色智能体（法律顾问/写作助手等）——1=跳过检索链路；null 视为 0
        if (body.containsKey("knowledgeDisabled")) a.setKnowledgeDisabled(toTri(body.get("knowledgeDisabled")));
        if (body.containsKey("toolKnowledge")) a.setToolKnowledge(toTri(body.get("toolKnowledge")));
        if (body.containsKey("toolBuiltin")) a.setToolBuiltin(toTri(body.get("toolBuiltin")));
        if (body.containsKey("toolSkill")) a.setToolSkill(toTri(body.get("toolSkill")));
        if (body.containsKey("toolArtifact")) a.setToolArtifact(toTri(body.get("toolArtifact")));
        if (body.containsKey("toolMcp")) a.setToolMcp(toTri(body.get("toolMcp")));
        // 具体项范围（技能 / MCP Server / 内置工具）：null=跟随全局、空串=不使用、逗号串=仅这些
        if (body.containsKey("skills")) a.setSkills(toScopeText(body.get("skills"), 1000));
        if (body.containsKey("mcps")) a.setMcps(toScopeText(body.get("mcps"), 1000));
        if (body.containsKey("builtinTools")) a.setBuiltinTools(toScopeText(body.get("builtinTools"), 500));
        if (body.containsKey("isSubagent")) a.setIsSubagent(toTri(body.get("isSubagent")));
        // 委派列表为空串/空时归一为 null（= 不启用委派，编排走原有多视角策略）
        if (body.containsKey("subAgentIds")) a.setSubAgentIds(asText(body.get("subAgentIds"), 1000));
        if (body.containsKey("isDefault")) a.setIsDefault(toTri(body.get("isDefault")));
        return a;
    }

    private void clearDefault() {
        List<Agent> all = mapper.selectList(new LambdaQueryWrapper<Agent>().eq(Agent::getIsDefault, 1));
        for (Agent a : all) {
            a.setIsDefault(0);
            mapper.updateById(a);
        }
    }

    /** 文本字段：null/空返回 null；超长截断（空字符串也视为未设置→null，避免存空串干扰"继承"判定） */
    private String asText(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 「具体项范围」字段解析（与主流智能体平台资源选择语义一致）：
     * null → null（跟随全局）；空串 → ""（显式一个都不用）；"a,b" → 归一化后的 "a,b"。
     * <p>与 asText 的关键区别：**保留空串语义**——空串表示"显式不使用"，
     * 若像 asText 那样归一成 null 就变成"跟随全局"，两者含义正好相反。</p>
     */
    private String toScopeText(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "";
        String joined = Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        if (joined.isEmpty()) return "";
        return joined.length() > max ? joined.substring(0, max) : joined;
    }

    /** 三态解析：true/1 → 1，false/0 → 0，null/其它 → null（继承） */
    private Integer toTri(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof Number n) return n.intValue() == 0 ? 0 : 1;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) return 1;
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) return 0;
        return null;
    }
}
