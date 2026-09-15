package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.AiAgentMapper;
import com.wisesoft.ai.model.AiAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final AiAgentMapper mapper;

    /** 列表（默认智能体在前，其余按创建时间倒序） */
    public List<AiAgent> list() {
        return mapper.selectList(new LambdaQueryWrapper<AiAgent>()
                .orderByDesc(AiAgent::getIsDefault)
                .orderByDesc(AiAgent::getCreateTime));
    }

    /**
     * 对话页下拉用（普通用户可读）：只暴露「选择智能体」所需的最小字段。
     * <p>不返回 systemPrompt / knowledgeScope / 工具开关——那些是管理配置，
     * 不应经由只读接口外泄；对话页只需要 id 与展示名。</p>
     */
    public List<Map<String, Object>> available() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiAgent a : list()) {
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

    /** 按 ID 取（不存在返回 null，调用方据此降级为全局配置） */
    public AiAgent get(String id) {
        if (!StringUtils.hasText(id)) return null;
        return mapper.selectById(id);
    }

    /** 默认智能体（无则返回 null） */
    public AiAgent defaultAgent() {
        return mapper.selectOne(new LambdaQueryWrapper<AiAgent>()
                .eq(AiAgent::getIsDefault, 1).last("LIMIT 1"));
    }

    /** 新建智能体；isDefault=true 时先清空其它默认 */
    public AiAgent create(Map<String, Object> body) {
        AiAgent a = toEntity(body, new AiAgent());
        a.setCreateTime(LocalDateTime.now());
        a.setUpdateTime(LocalDateTime.now());
        if (Boolean.TRUE.equals(a.getIsDefault()) || Integer.valueOf(1).equals(a.getIsDefault())) {
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
    public AiAgent update(String id, Map<String, Object> body) {
        AiAgent existing = mapper.selectById(id);
        if (existing == null) throw new com.wisesoft.ai.common.BizException(404, "智能体不存在");
        AiAgent a = toEntity(body, existing);
        a.setUpdateTime(LocalDateTime.now());
        if (Integer.valueOf(1).equals(a.getIsDefault())) {
            clearDefault();
            a.setIsDefault(1);
        }
        mapper.updateById(a);
        log.info("[AGENT] 更新智能体 {}（{}）", a.getId(), a.getName());
        return a;
    }

    /** 删除智能体 */
    public void delete(String id) {
        mapper.deleteById(id);
        log.info("[AGENT] 删除智能体 {}", id);
    }

    /** 设为默认（其余清零） */
    public void setDefault(String id) {
        if (mapper.selectById(id) == null) throw new com.wisesoft.ai.common.BizException(404, "智能体不存在");
        clearDefault();
        AiAgent a = new AiAgent();
        a.setId(id);
        a.setIsDefault(1);
        a.setUpdateTime(LocalDateTime.now());
        mapper.updateById(a);
        log.info("[AGENT] 设默认智能体 {}", id);
    }

    /** 把请求体字段映射到实体（仅覆盖 body 中出现的字段，其余保持原值） */
    private AiAgent toEntity(Map<String, Object> body, AiAgent a) {
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
        if (body.containsKey("toolKnowledge")) a.setToolKnowledge(toTri(body.get("toolKnowledge")));
        if (body.containsKey("toolBuiltin")) a.setToolBuiltin(toTri(body.get("toolBuiltin")));
        if (body.containsKey("toolSkill")) a.setToolSkill(toTri(body.get("toolSkill")));
        if (body.containsKey("toolArtifact")) a.setToolArtifact(toTri(body.get("toolArtifact")));
        if (body.containsKey("toolMcp")) a.setToolMcp(toTri(body.get("toolMcp")));
        if (body.containsKey("isDefault")) a.setIsDefault(toTri(body.get("isDefault")));
        return a;
    }

    private void clearDefault() {
        List<AiAgent> all = mapper.selectList(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getIsDefault, 1));
        for (AiAgent a : all) {
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
