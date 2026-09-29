package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.WorkflowMapper;
import com.wisesoft.ai.model.AiWorkflow;
import com.wisesoft.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流管理：DSL 是唯一真源（架构决策见排班计划），本服务只做
 * 存储 + 校验 + 编译 dry-run 的编排；执行（run）M1 交付。
 * <p>
 * 归属口径（分期边界）：M0~M3 仅<b>创建者本人</b>可见可管（share_config 字段预留，
 * M4 随发布语义一起启用两级可见性）——不做半吊子共享。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    /** DSL 体积上限（含画布坐标；真实工作流几十 KB 封顶，512KB 已留足冗余） */
    private static final int MAX_DSL_CHARS = 512 * 1024;

    private final WorkflowMapper workflowMapper;
    private final WorkflowValidator validator;
    private final WorkflowEngine engine;

    /** 本人工作流列表（更新时间倒序） */
    public List<AiWorkflow> listOwn() {
        return workflowMapper.selectList(new LambdaQueryWrapper<AiWorkflow>()
                .eq(AiWorkflow::getUid, RequestUser.uid())
                .orderByDesc(AiWorkflow::getUpdateTime));
    }

    /** 本人的一条工作流：不存在或不是本人的都按不存在处理（不泄露存在性） */
    public AiWorkflow getOwn(String id) {
        AiWorkflow row = workflowMapper.selectById(id);
        if (row == null || !RequestUser.uid().equals(row.getUid())) {
            throw new BizException(404, "工作流不存在");
        }
        return row;
    }

    /** 新建：名称必填、DSL 必须通过结构校验（不接受存进去的坏图） */
    public AiWorkflow create(String name, String description, String dslText) {
        if (name == null || name.isBlank()) throw new BizException("请填写工作流名称");
        if (name.length() > 100) throw new BizException("工作流名称不超过 100 个字符");
        if (description != null && description.length() > 500) throw new BizException("描述不超过 500 个字符");
        WorkflowDsl dsl = parseDsl(dslText);
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) throw new BizException("工作流校验未通过：" + String.join("；", errors));
        AiWorkflow row = new AiWorkflow();
        row.setId(UUID.randomUUID().toString());
        row.setUid(RequestUser.uid());
        row.setName(name.trim());
        row.setDescription(description == null ? "" : description.trim());
        row.setDsl(dslText.trim());
        row.setStatus("draft");
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        workflowMapper.insert(row);
        log.info("[WORKFLOW] 新建工作流 {}（{}） uid={}", row.getName(), row.getId(), row.getUid());
        return row;
    }

    /** 更新：只改传了的字段；dsl 有变更时重新校验 */
    public AiWorkflow update(String id, String name, String description, String dslText) {
        AiWorkflow row = getOwn(id);
        if (name != null) {
            if (name.isBlank()) throw new BizException("工作流名称不能为空");
            if (name.length() > 100) throw new BizException("工作流名称不超过 100 个字符");
            row.setName(name.trim());
        }
        if (description != null) {
            if (description.length() > 500) throw new BizException("描述不超过 500 个字符");
            row.setDescription(description.trim());
        }
        if (dslText != null) {
            WorkflowDsl dsl = parseDsl(dslText);
            List<String> errors = validator.validate(dsl);
            if (!errors.isEmpty()) throw new BizException("工作流校验未通过：" + String.join("；", errors));
            row.setDsl(dslText.trim());
        }
        row.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(row);
        return row;
    }

    /** 删除（本人的）：连带运行记录保留（审计与回放价值独立于定义存在） */
    public void delete(String id) {
        AiWorkflow row = getOwn(id);
        workflowMapper.deleteById(row.getId());
    }

    /**
     * 校验 + 编译 dry-run（保存前/画布实时校验共用）：
     * 返回 errors（结构问题，逐条可定位）、compiled（翻译层是否吃下）、compileError（未开放类型/库层拒绝原文）。
     */
    public Map<String, Object> validate(String dslText) {
        Map<String, Object> out = new LinkedHashMap<>();
        WorkflowDsl dsl;
        try {
            dsl = parseDsl(dslText);
        } catch (BizException e) {
            out.put("errors", List.of(e.getMessage()));
            out.put("compiled", false);
            return out;
        }
        List<String> errors = validator.validate(dsl);
        out.put("errors", errors);
        out.put("nodeCount", dsl.getNodes() == null ? 0 : dsl.getNodes().size());
        out.put("edgeCount", dsl.getEdges() == null ? 0 : dsl.getEdges().size());
        if (!errors.isEmpty()) {
            out.put("compiled", false);
            return out;
        }
        try {
            engine.compile(dsl);
            out.put("compiled", true);
        } catch (BizException e) {
            out.put("compiled", false);
            out.put("compileError", e.getMessage());
        }
        return out;
    }

    /** DSL 解析（JSON 语法错误包装成用户可读提示，fastjson2 的 offset 信息保留） */
    private WorkflowDsl parseDsl(String dslText) {
        if (dslText == null || dslText.isBlank()) throw new BizException("DSL 不能为空");
        if (dslText.length() > MAX_DSL_CHARS) throw new BizException("DSL 超过体积上限（512KB）");
        try {
            return WorkflowDsl.parse(dslText);
        } catch (Exception e) {
            throw new BizException("DSL 不是合法 JSON：" + e.getMessage());
        }
    }
}
