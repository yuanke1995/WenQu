package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.ApiKeyMapper;
import com.wisesoft.ai.mapper.ToolApprovalMapper;
import com.wisesoft.ai.mapper.UserMapper;
import com.wisesoft.ai.mapper.WorkflowMapper;
import com.wisesoft.ai.mapper.WorkflowRunMapper;
import com.wisesoft.ai.mapper.WorkflowVersionMapper;
import com.wisesoft.ai.model.Workflow;
import com.wisesoft.ai.model.WorkflowRun;
import com.wisesoft.ai.model.WorkflowVersion;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.ToolApproval;
import com.wisesoft.ai.model.User;
import com.wisesoft.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工作流管理：DSL 是唯一真源（架构决策见排班计划），本服务做
 * 存储 + 校验 + 编译 dry-run 的编排，以及调试运行（run）与运行记录查询。
 * <p>
 * 归属口径：M0~M3 仅<b>创建者本人</b>可见可管；M4 起启用发布语义与两级可见性——
 * {@code share_config} 同款（{@link ResourceVisibilityService#canRead}），
 * 共享给他人的是「可触发/可读」，管理权仍只在创建者与共享范围里的管理级。
 * <p>
 * M4 的三种触发与各自跑的 DSL：
 * <ul>
 *   <li><b>manual</b>（画布调试／本服务 {@link #run}）：跑<b>草稿</b> DSL，支持人工审核挂起；</li>
 *   <li><b>api</b>（外部 Key 触发 {@link #runByApi}）：跑<b>已发布</b> DSL，遇审核节点 fail-loud；</li>
 *   <li><b>agent</b>（智能体绑定 {@link #runForAgent}）：跑<b>已发布</b> DSL，流式 token 透传，
 *       同样不支持审核节点。</li>
 * </ul>
 * <p>
 * 运行语义：每次运行把当时的 DSL <b>快照</b>进 {@code c_ai_workflow_run}（执行不可变，
 * 改画布不影响历史回放）；run 记录先落（status=running）再执行、finally 收口——
 * 崩在中途也留得住 trace。同步调试失败不抛 500，返回 status=failed 的 run 对象，
 * 前端直接展示 trace 定位问题节点。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    /** DSL 体积上限（含画布坐标；真实工作流几十 KB 封顶，512KB 已留足冗余） */
    private static final int MAX_DSL_CHARS = 512 * 1024;

    /**
     * run 级执行线程池（M5 硬超时用）：把图调用从请求线程挪到独立线程，主线程带时限等待。
     * cached + daemon：并发量 = 同时在跑的工作流数，线程随用随建、空闲即回收。
     */
    private static final java.util.concurrent.ExecutorService RUN_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "wf-run");
                t.setDaemon(true);
                return t;
            });

    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowVersionMapper versionMapper;
    private final ToolApprovalMapper toolApprovalMapper;
    private final UserMapper userMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final AgentService agentService;
    private final WorkflowValidator validator;
    private final WorkflowEngine engine;
    private final ConfigService configService;
    private final ResourceVisibilityService visibility;

    /**
     * 运行主体（触发者身份）：M4 起触发者不一定是登录用户——外部 API Key 触发时身份是
     * <b>Key 的归属用户</b>（{@code c_ai_api_key.created_by}），故身份显式封装传递，
     * 不直接读 {@link RequestUser}（API Key 请求没有登录令牌，ThreadLocal 是 anonymous）。
     */
    public record Principal(String uid, String departmentId, String role) {
        public static Principal current() {
            return new Principal(RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
        }

        public static Principal ofUser(String uid, UserMapper userMapper) {
            String dept = null, role = "user";
            if (uid != null && !uid.isBlank()) {
                User u = userMapper.selectById(uid);
                if (u != null) {
                    dept = u.getDepartmentId();
                    role = u.getRole() == null || u.getRole().isBlank() ? "user" : u.getRole();
                }
            }
            return new Principal(uid, dept, role);
        }
    }

    /** 本人工作流列表（更新时间倒序），附带最近一次运行的状态与时刻（列表页展示，派生信息不落库） */
    public List<Workflow> listOwn() {
        List<Workflow> rows = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getUid, RequestUser.uid())
                .orderByDesc(Workflow::getUpdateTime));
        if (rows.isEmpty()) return rows;
        List<String> ids = rows.stream().map(Workflow::getId).toList();
        List<WorkflowRun> recent = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .in(WorkflowRun::getWorkflowId, ids)
                .orderByDesc(WorkflowRun::getStartedAt)
                .last("LIMIT 500"));
        Map<String, WorkflowRun> last = new LinkedHashMap<>();
        for (WorkflowRun r : recent) last.putIfAbsent(r.getWorkflowId(), r);   // 已按时间倒序，首条即最近
        for (Workflow w : rows) {
            WorkflowRun r = last.get(w.getId());
            if (r != null) {
                w.setLastRunStatus(r.getStatus());
                w.setLastRunAt(r.getStartedAt());
                w.setLastRunId(r.getId());
            }
        }
        return rows;
    }

    /** 本人的一条工作流：不存在或不是本人的都按不存在处理（不泄露存在性） */
    public Workflow getOwn(String id) {
        Workflow row = workflowMapper.selectById(id);
        if (row == null || !RequestUser.uid().equals(row.getUid())) {
            throw new BizException(404, "工作流不存在");
        }
        return row;
    }

    /** 新建：名称必填、DSL 必须通过结构校验（不接受存进去的坏图） */
    public Workflow create(String name, String description, String dslText) {
        if (name == null || name.isBlank()) throw new BizException("请填写工作流名称");
        if (name.length() > 100) throw new BizException("工作流名称不超过 100 个字符");
        if (description != null && description.length() > 500) throw new BizException("描述不超过 500 个字符");
        WorkflowDsl dsl = parseDsl(dslText);
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) throw new BizException("工作流校验未通过：" + String.join("；", errors));
        Workflow row = new Workflow();
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
    public Workflow update(String id, String name, String description, String dslText) {
        Workflow row = getOwn(id);
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
        Workflow row = getOwn(id);
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

    // --------------------------------------------------------------------------------------------------
    // M5：模板库 / 委派编排转工作流
    // --------------------------------------------------------------------------------------------------

    /** 内置模板清单（见 {@link WorkflowTemplates}）：dsl 字段画布/创建接口直接可用 */
    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkflowTemplates.Template t : WorkflowTemplates.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t.key());
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("nodeCount", t.nodeCount());
            m.put("dsl", t.dsl());
            out.add(m);
        }
        return out;
    }

    /**
     * M5：把智能体的「委派编排关系」一键转成工作流 DSL 并创建（衔接 AgentsPage 的委派配置）。
     * <p>
     * 转换语义：{@code subAgentIds} 的并行委派 → start 扇出 N 个 subagent 节点（各自委派对应
     * 子智能体、prompt 均取用户问题）→ template 节点聚合各视角 → llm 总结 → end。
     * 子智能体自身的提示词/知识库范围在问答管线内生效（与主链路委派编排一致），转换不复制内容。
     * <p>
     * 可见性：主智能体与全部子智能体都须对当前用户可读（{@link AgentService#get} 逐个校验），
     * 不可见的委派关系不静默裁剪——fail-loud 报出第一个不可见者。
     */
    public Workflow createFromAgent(String agentId) {
        Agent agent = agentService.get(agentId);
        if (agent == null) {
            throw new BizException(404, "智能体不存在或对当前用户不可见");
        }
        List<String> subIds = splitCsv(agent.getSubAgentIds());
        if (subIds.isEmpty()) {
            throw new BizException("智能体「" + agent.getName() + "」未配置委派编排（子智能体为空），没有可转换的编排关系");
        }
        // 子智能体逐个可见性校验（顺带拿名称做节点标注；不可见 = 编排关系对当前用户不完整）
        Map<String, Agent> subs = new LinkedHashMap<>();
        for (String sid : subIds) {
            Agent sub = agentService.get(sid);
            if (sub == null) {
                throw new BizException("子智能体（" + sid + "）不存在或对当前用户不可见，无法转换完整编排");
            }
            subs.put(sid, sub);
        }
        // ---- DSL 构造：start 扇出 → subagent×N → template 聚合 → llm 总结 → end ----
        List<WorkflowDsl.Node> nodes = new ArrayList<>();
        WorkflowDsl.Node start = new WorkflowDsl.Node();
        start.setId("start");
        start.setType("start");
        start.setPosition(pos(0));
        start.configPut("inputs", List.of(Map.of("key", "question", "type", "string", "required", true)));
        nodes.add(start);
        StringBuilder tpl = new StringBuilder();
        int i = 0;
        for (Agent sub : subs.values()) {
            i++;
            WorkflowDsl.Node n = new WorkflowDsl.Node();
            n.setId("sub_" + i);
            n.setType("subagent");
            n.setPosition(pos(1));
            n.configPut("agentId", sub.getId());
            n.configPut("prompt", "{{start.question}}");
            nodes.add(n);
            tpl.append("【").append(sub.getName()).append("】\n{{sub_").append(i).append(".answer}}\n\n");
        }
        WorkflowDsl.Node tplNode = new WorkflowDsl.Node();
        tplNode.setId("template_1");
        tplNode.setType("template");
        tplNode.setPosition(pos(2));
        tplNode.configPut("template", tpl.toString().trim());
        nodes.add(tplNode);
        WorkflowDsl.Node llmSum = new WorkflowDsl.Node();
        llmSum.setId("llm_sum");
        llmSum.setType("llm");
        llmSum.setPosition(pos(3));
        llmSum.configPut("prompt", "以下是多个智能体对同一问题的各自回答：\n\n{{template_1.text}}"
                + "\n\n请综合各视角（去重、消解冲突）给出一条最终回答，不要逐条罗列原文。");
        llmSum.configPut("temperature", 0.5);
        nodes.add(llmSum);
        WorkflowDsl.Node end = new WorkflowDsl.Node();
        end.setId("end_1");
        end.setType("end");
        end.setPosition(pos(4));
        end.configPut("outputs", Map.of("answer", "{{llm_sum.answer}}"));
        nodes.add(end);
        List<WorkflowDsl.Edge> edges = new ArrayList<>();
        for (WorkflowDsl.Node n : nodes) {
            if ("subagent".equals(n.getType())) edges.add(edge("start", n.getId()));
        }
        edges.add(edge("template_1", "llm_sum"));
        edges.add(edge("llm_sum", "end_1"));
        // start → sub_i 边已在上面生成；sub_i → template_1 依序补
        for (int k = 1; k <= subs.size(); k++) edges.add(edge("sub_" + k, "template_1"));
        WorkflowDsl dsl = new WorkflowDsl();
        dsl.setNodes(nodes);
        dsl.setEdges(edges);
        String dslText = dsl.toJson();
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) {
            throw new BizException("编排转换结果未通过校验：" + String.join("；", errors));
        }
        return create(agent.getName() + " · 编排转工作流",
                "由智能体「" + agent.getName() + "」的委派编排（" + subs.size() + " 个子智能体并行）一键转换；"
                        + "可在画布继续调整（改用模板节点直接拼接、或给总结节点指定模型）。",
                dslText);
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String t = part == null ? "" : part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Map<String, Object> pos(int col) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", 80 + col * 260);
        m.put("y", 180);
        return m;
    }

    private static WorkflowDsl.Edge edge(String from, String to) {
        WorkflowDsl.Edge e = new WorkflowDsl.Edge();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    // --------------------------------------------------------------------------------------------------
    // M4：发布与版本（草稿可改，已发布行为冻结；回滚 = 以历史版本 DSL 再发一版）
    // --------------------------------------------------------------------------------------------------

    /**
     * 发布当前草稿为新版本：草稿校验通过 → 版本号 +1 → 落版本行 → 工作流切到该版本
     * （{@code status=published}，{@code published_dsl} 锁定）。
     * <p>
     * 发布后草稿继续可改，改动画布<b>不影响</b>已发布版本的行为——API 触发与智能体绑定跑的
     * 都是 {@code published_dsl}（对齐 Dify/Coze 的「发布版本」语义）。
     */
    public Map<String, Object> publish(String id, String note) {
        Workflow row = getOwn(id);
        WorkflowDsl dsl = parseDsl(row.getDsl());
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) throw new BizException("工作流校验未通过，无法发布：" + String.join("；", errors));
        int version = nextVersion(row.getId());
        WorkflowVersion v = new WorkflowVersion();
        v.setId(UUID.randomUUID().toString());
        v.setWorkflowId(row.getId());
        v.setVersion(version);
        v.setDsl(row.getDsl().trim());
        v.setNote(note == null || note.isBlank() ? null : note.trim());
        v.setPublishedBy(RequestUser.uid());
        v.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(v);
        row.setStatus("published");
        row.setPublishedDsl(v.getDsl());
        row.setPublishedVersion(version);
        row.setPublishedAt(v.getPublishedAt());
        row.setPublishedBy(v.getPublishedBy());
        row.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(row);
        log.info("[WORKFLOW] 发布工作流 {}（{}）版本 v{} uid={}", row.getName(), row.getId(), version, row.getUid());
        return Map.of("id", row.getId(), "version", version, "publishedAt", v.getPublishedAt().toString(),
                "status", row.getStatus());
    }

    /** 下线（回到草稿态）：已发布版本记录保留（历史可查，再发布即新版本） */
    public Map<String, Object> unpublish(String id) {
        Workflow row = getOwn(id);
        if (!"published".equals(row.getStatus())) {
            throw new BizException("该工作流当前不是已发布状态（" + row.getStatus() + "）");
        }
        row.setStatus("draft");
        row.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(row);
        log.info("[WORKFLOW] 下线工作流 {}（{}）uid={}", row.getName(), row.getId(), row.getUid());
        return Map.of("id", row.getId(), "status", row.getStatus());
    }

    /** 版本历史（新→旧）：版本号/说明/发布者/时间；DSL 大体量，只在详情接口给 */
    public List<Map<String, Object>> listVersions(String id) {
        Workflow row = getOwn(id);
        List<WorkflowVersion> rows = versionMapper.selectList(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getWorkflowId, row.getId())
                .orderByDesc(WorkflowVersion::getVersion));
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkflowVersion v : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v.getVersion());
            m.put("note", v.getNote());
            m.put("publishedBy", v.getPublishedBy());
            m.put("publishedAt", v.getPublishedAt());
            m.put("current", v.getVersion() != null && v.getVersion().equals(row.getPublishedVersion()));
            out.add(m);
        }
        return out;
    }

    /**
     * 回滚到指定版本：取该版本的 DSL <b>再发一版</b>（版本号继续递增）。
     * 不改写历史——回滚动作本身也是一次发布，可再次回滚（写指针式回滚会让历史不可追溯）。
     */
    public Map<String, Object> rollback(String id, int targetVersion) {
        Workflow row = getOwn(id);
        WorkflowVersion target = versionMapper.selectOne(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getWorkflowId, row.getId())
                .eq(WorkflowVersion::getVersion, targetVersion)
                .last("LIMIT 1"));
        if (target == null) throw new BizException(404, "版本 v" + targetVersion + " 不存在");
        WorkflowDsl dsl = parseDsl(target.getDsl());
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) throw new BizException("版本 v" + targetVersion + " 的 DSL 已不符合当前校验规则，无法回滚："
                + String.join("；", errors));
        int version = nextVersion(row.getId());
        WorkflowVersion v = new WorkflowVersion();
        v.setId(UUID.randomUUID().toString());
        v.setWorkflowId(row.getId());
        v.setVersion(version);
        v.setDsl(target.getDsl());
        v.setNote("回滚至 v" + targetVersion);
        v.setPublishedBy(RequestUser.uid());
        v.setPublishedAt(LocalDateTime.now());
        versionMapper.insert(v);
        row.setStatus("published");
        row.setPublishedDsl(v.getDsl());
        row.setPublishedVersion(version);
        row.setPublishedAt(v.getPublishedAt());
        row.setPublishedBy(v.getPublishedBy());
        row.setUpdateTime(LocalDateTime.now());
        workflowMapper.updateById(row);
        log.info("[WORKFLOW] 回滚工作流 {}（{}）至 v{} → 新版本 v{}", row.getName(), row.getId(), targetVersion, version);
        return Map.of("id", row.getId(), "version", version, "rolledBackTo", targetVersion);
    }

    /** 下一个版本号（当前最大 + 1；无历史从 1 起） */
    private int nextVersion(String workflowId) {
        List<WorkflowVersion> rows = versionMapper.selectList(new LambdaQueryWrapper<WorkflowVersion>()
                .eq(WorkflowVersion::getWorkflowId, workflowId)
                .orderByDesc(WorkflowVersion::getVersion)
                .last("LIMIT 1"));
        return rows.isEmpty() || rows.get(0).getVersion() == null ? 1 : rows.get(0).getVersion() + 1;
    }

    // --------------------------------------------------------------------------------------------------
    // M4：API 触发 / 智能体绑定（跑已发布版本）
    // --------------------------------------------------------------------------------------------------

    /**
     * 外部 API Key 触发：跑<b>已发布</b> DSL。
     * <p>
     * 权限：工作流对该 Key 的归属用户可读（创建者短路或 share_config 命中）才允许触发；
     * 不存在/不可见一律 404（不泄露存在性）。未发布 → 400 fail-loud（外部系统集成在发布前调
     * 会拿到明确原因，而不是跑一份随时会变的草稿）。
     * <b>人工审核节点不支持</b>：API 调用方没有审批卡交互面，直接 fail-loud 说明——
     * 不做"自动批准"之类的静默降级。
     */
    public WorkflowRun runByApi(String workflowId, Map<String, Object> inputs, String apiKeyId) {
        Principal p = resolvePrincipal(apiKeyId);
        Workflow row = loadVisible(workflowId, p);
        if (!"published".equals(row.getStatus()) || row.getPublishedDsl() == null || row.getPublishedDsl().isBlank()) {
            throw new BizException("工作流「" + row.getName() + "」尚未发布：API 触发只跑已发布版本（请先在画布发布）");
        }
        assertNoApprovalNode(parseDsl(row.getPublishedDsl()), "API 触发");
        return execute(row, row.getPublishedDsl(), "api", p.uid(), apiKeyId, row.getPublishedVersion(),
                "published", inputs, null, null, p);
    }

    /** 外部调用方查运行记录（补查/回溯用）：可见性口径与触发一致（不可见一律 404） */
    public WorkflowRun getRunForApi(String workflowId, String runId, String apiKeyId) {
        Principal p = resolvePrincipal(apiKeyId);
        Workflow row = loadVisible(workflowId, p);
        WorkflowRun r = runMapper.selectById(runId);
        if (r == null || !workflowId.equals(r.getWorkflowId())) {
            throw new BizException(404, "运行记录不存在");
        }
        return r;
    }

    /**
     * 触发者身份解析：带 API Key → Key 的归属用户（外部调用方没有登录令牌，
     * ThreadLocal 里是 anonymous，必须回查 Key 归属）；否则 → 当前登录用户。
     */
    private Principal resolvePrincipal(String apiKeyId) {
        if (apiKeyId != null && !apiKeyId.isBlank()) {
            var key = apiKeyMapper.selectById(apiKeyId);
            if (key == null || key.getCreatedBy() == null) {
                throw new BizException(403, "API Key 无效（未绑定归属用户）");
            }
            return Principal.ofUser(key.getCreatedBy(), userMapper);
        }
        return Principal.current();
    }

    /** 按可见性取工作流（不存在与不可见都按 404，不泄露存在性） */
    private Workflow loadVisible(String workflowId, Principal p) {
        Workflow row = workflowMapper.selectById(workflowId);
        if (row == null) throw new BizException(404, "工作流不存在");
        if (!visibility.canRead(new ResourceVisibilityService.Principal(p.uid(), p.departmentId(), p.role()),
                row.getShareConfig(), row.getUid(), ResourceVisibilityService.ResourceKind.WORKFLOW)) {
            throw new BizException(404, "工作流不存在");
        }
        return row;
    }

    /**
     * 智能体绑定的对话型运行（chatflow）：跑<b>已发布</b> DSL，llm 节点的流式 token 经
     * {@code tokenSink} 透传给调用方（由 RagService 转成 SSE token 事件）。
     * 同样不支持人工审核节点（会话里没有工作流审批卡交互面，fail-loud 而非自动放行）。
     */
    public WorkflowRun runForAgent(String workflowId, Map<String, Object> inputs, Principal p,
                                     Consumer<String> tokenSink) {
        return runForAgent(workflowId, inputs, p, tokenSink, null);
    }

    /**
     * 智能体对话触发（带图片通道）：imageSink 非空时 retrieval 节点完成图片编号后回调
     * 累计有序原始 URL 列表（RagService 签名后发 SSE image 事件，时序先于 LLM token）。
     */
    public WorkflowRun runForAgent(String workflowId, Map<String, Object> inputs, Principal p,
                                     Consumer<String> tokenSink, Consumer<List<String>> imageSink) {
        Workflow row = workflowMapper.selectById(workflowId);
        if (row == null) throw new BizException(404, "工作流不存在");
        if (!"published".equals(row.getStatus()) || row.getPublishedDsl() == null || row.getPublishedDsl().isBlank()) {
            throw new BizException("绑定的工作流「" + row.getName() + "」尚未发布：请先在画布发布后再使用该智能体");
        }
        if (!visibility.canRead(new ResourceVisibilityService.Principal(p.uid(), p.departmentId(), p.role()),
                row.getShareConfig(), row.getUid(), ResourceVisibilityService.ResourceKind.WORKFLOW)) {
            throw new BizException(404, "工作流不存在");
        }
        assertNoApprovalNode(parseDsl(row.getPublishedDsl()), "智能体对话");
        return execute(row, row.getPublishedDsl(), "agent", p.uid(), null, row.getPublishedVersion(),
                "published", inputs, tokenSink, imageSink, p);
    }

    /** 人工审核节点在无审批交互面的触发方式下 fail-loud（不静默自动放行） */
    private static void assertNoApprovalNode(WorkflowDsl dsl, String triggerName) {
        if (dsl.getNodes() == null) return;
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            if ("approval".equals(n.getType())) {
                throw new BizException(triggerName + "不支持人工审核节点（节点「" + n.getId()
                        + "」）：审核需要人在回路的交互面，请在画布调试运行中处理，或从发布版本里移除该节点");
            }
        }
    }

    // --------------------------------------------------------------------------------------------------
    // 调试运行（M1）
    // --------------------------------------------------------------------------------------------------

    /**
     * 同步调试运行：入参校验（必填项）→ 模型预解析（fail-fast）→ 编译 → 执行 → 出参提取。
     * 失败不抛（返回 status=failed 的 run，error 带原因）——调试入口要让用户拿到 trace。
     * <b>人工审核挂起</b>：审核节点落 PENDING 审批记录后抛 WorkflowSuspendException，
     * run 落 waiting_approval + 状态快照（已完成节点全量输出），审批接口按快照恢复续跑。
     *
     * @param inputs 开始节点入参（{{start.key}} 的取值来源）
     */
    public WorkflowRun run(String id, Map<String, Object> inputs) {
        Workflow row = getOwn(id);
        return execute(row, row.getDsl(), "manual", RequestUser.uid(), null, null, "draft",
                inputs, null, null, Principal.current());
    }

    /**
     * 执行内核：落 run 记录 → 入参校验 → 模型预解析（fail-fast）→ 编译 → 执行 → 出参提取。
     * 失败不抛（返回 status=failed 的 run，error 带原因）——调试入口要让用户拿到 trace。
     * <b>人工审核挂起</b>：审核节点落 PENDING 审批记录后抛 WorkflowSuspendException，
     * run 落 waiting_approval + 状态快照（已完成节点全量输出），审批接口按快照恢复续跑。
     *
     * @param dslText    本次运行锁定的 DSL（草稿或已发布版本）
     * @param tokenSink  非空时 llm 节点改走流式并把文本块即时交给它（对话型接入）
     */
    private WorkflowRun execute(Workflow row, String dslText, String triggerType, String triggeredBy,
                                  String apiKeyId, Integer version, String dslSource,
                                  Map<String, Object> inputs, Consumer<String> tokenSink,
                                  Consumer<List<String>> imageSink, Principal p) {
        WorkflowDsl dsl = parseDsl(dslText);
        String runId = UUID.randomUUID().toString();
        WorkflowRunCtx ctx = new WorkflowRunCtx(runId, p.uid(), p.departmentId(), p.role(),
                configService.currentOverrides(), inputs,
                configService.getDouble("chat.temperature"),
                configService.getInt("workflow.maxSteps", 50),
                tokenSink, imageSink);
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setWorkflowId(row.getId());
        run.setDslSnapshot(dslText);   // 执行不可变：锁定本次运行用的 DSL（对齐 Coze run 语义）
        run.setTriggerType(triggerType);
        run.setTriggeredBy(triggeredBy);
        run.setApiKeyId(apiKeyId);
        run.setVersion(version);
        run.setDslSource(dslSource);
        run.setStatus("running");
        run.setInputs(JSON.toJSONString(inputs == null ? Map.of() : inputs));
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        try {
            WorkflowEngine.checkStartInputs(dsl, inputs);
            engine.resolveModels(dsl, ctx);
            CompiledGraph compiled = engine.compile(dsl, ctx);
            Optional<com.alibaba.cloud.ai.graph.OverAllState> result = invokeGraph(compiled);
            if (result.isEmpty()) {
                throw new BizException("图执行未返回终态（可能被步数上限截断，workflow.maxSteps=" + ctx.maxSteps + "）");
            }
            Map<String, Object> outputs = engine.extractOutputs(dsl, result.get());
            run.setStatus("success");
            run.setOutputs(JSON.toJSONString(outputs));
            log.info("[WORKFLOW] 运行成功：{}（{}）run={} 耗时 {}ms", row.getName(), row.getId(), run.getId(),
                    System.currentTimeMillis() - ctx.t0);
            return finish(run, ctx, null);
        } catch (RunTimeoutException e) {
            // M5：run 硬超时——落 timeout 终态（error 带原因与调参指引），执行线程已中断回收
            log.warn("[WORKFLOW] 运行超时：{}（{}）run={}：{} 秒", row.getName(), row.getId(), run.getId(), e.seconds);
            run.setStatus("timeout");
            writeCheckpoint(run, ctx);   // 中断前已完成的节点进检查点，支持从失败点续跑
            return finish(run, ctx, "运行超时（" + e.seconds + " 秒未跑完，workflow.runTimeoutSeconds，0=不限制）");
        } catch (BizException e) {
            log.warn("[WORKFLOW] 运行失败：{}（{}）run={}：{}", row.getName(), row.getId(), run.getId(), e.getMessage());
            writeCheckpoint(run, ctx);   // M5 检查点：已完成的节点输出进快照（空则不写，无检查点可言）
            return finish(run, ctx, e.getMessage());
        } catch (Exception e) {
            WorkflowSuspendException suspend = findSuspend(e);
            if (suspend != null) {
                // 挂起收口：waiting_approval + 状态快照（已完成节点全量输出），等审批接口恢复
                run.setStatus("waiting_approval");
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("outputs", ctx.fullOutputs);
                snapshot.put("pendingNode", suspend.nodeId);
                run.setStateSnapshot(JSON.toJSONString(snapshot));
                log.info("[WORKFLOW] 运行挂起待审核：{}（{}）run={} 节点={} approval={}",
                        row.getName(), row.getId(), run.getId(), suspend.nodeId, suspend.approvalId);
                return finish(run, ctx, null);
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[WORKFLOW] 运行异常：{}（{}）run={}：{}", row.getName(), row.getId(), run.getId(), cause.toString());
            writeCheckpoint(run, ctx);   // M5 检查点：同上（引擎层失败重试耗尽后的终态收口）
            return finish(run, ctx, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    /**
     * M5 失败检查点：failed/timeout 收口前，把<b>已成功节点的全量输出</b>写进 run.state_snapshot
     * （挂起-快照-短路重放范式的失败场景复用）。快照为空（第一个节点都没跑成）时不写——
     * 没有可恢复的内容，续跑等价于重跑，前端不展示「续跑」入口。
     */
    private static void writeCheckpoint(WorkflowRun run, WorkflowRunCtx ctx) {
        if (ctx.fullOutputs.isEmpty()) return;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("outputs", ctx.fullOutputs);
        run.setStateSnapshot(JSON.toJSONString(snapshot));
    }

    /** run 级超时信号（内部用）：带超时秒数，收口处转 timeout 终态 */
    private static final class RunTimeoutException extends RuntimeException {
        final int seconds;
        RunTimeoutException(int seconds) { super("run timeout: " + seconds + "s"); this.seconds = seconds; }
    }

    /**
     * 执行图（M5 run 硬超时）：{@code workflow.runTimeoutSeconds} &gt; 0 时把图调用丢进独立线程池、
     * 主线程带时限等待——超时 cancel(true) 中断执行线程并抛 {@link RunTimeoutException}。
     * 图内部跑在框架的 reactor 调度上，中断能解开"主线程阻塞等待"这一层；真正的长任务
     * （LLM/HTTP/子智能体）各有节点级超时兜底，不会无限占用公共池。
     * 0 = 不限制（直接同步调用，不多付一次线程切换）。
     */
    private Optional<com.alibaba.cloud.ai.graph.OverAllState> invokeGraph(CompiledGraph compiled) throws Exception {
        int timeoutSeconds = configService.getInt("workflow.runTimeoutSeconds", 600);
        if (timeoutSeconds <= 0) {
            return compiled.invoke(Map.of());
        }
        java.util.concurrent.Future<Optional<com.alibaba.cloud.ai.graph.OverAllState>> future =
                RUN_POOL.submit(() -> compiled.invoke(Map.of()));
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            future.cancel(true);
            throw new RunTimeoutException(timeoutSeconds);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable c = ee.getCause() != null ? ee.getCause() : ee;
            throw c instanceof Exception ex ? ex : ee;
        }
    }

    /** 异常链里找挂起信号（框架会把节点异常包进 ExecutionException/CompletionException） */
    private static WorkflowSuspendException findSuspend(Throwable t) {
        while (t != null) {
            if (t instanceof WorkflowSuspendException s) return s;
            t = t.getCause();
        }
        return null;
    }

    /**
     * 审批裁决并恢复续跑（挂起-快照-短路重放）：仅运行发起人可裁决；
     * 快照里的已完成节点输出直接回放（不再执行节点体），只有挂起的审核节点与其下游真正执行。
     * 恢复执行在本次请求内同步完成——接口返回的就是终态 run。
     */
    public WorkflowRun approveRun(String workflowId, String runId, boolean approved) {
        WorkflowRun run = getRun(workflowId, runId);
        if (!"waiting_approval".equals(run.getStatus())) {
            throw new BizException("该运行不在等待审批状态（当前：" + run.getStatus() + "）");
        }
        if (!RequestUser.uid().equals(run.getTriggeredBy())) {
            throw new BizException(403, "仅运行发起人可审批");
        }
        ToolApproval rec = toolApprovalMapper.selectOne(new LambdaQueryWrapper<ToolApproval>()
                .eq(ToolApproval::getSessionId, runId)
                .eq(ToolApproval::getStatus, "PENDING")
                .last("LIMIT 1"));
        if (rec == null) {
            throw new BizException("未找到待处理的审批记录（可能已超时回收）");
        }
        rec.setStatus(approved ? "APPROVED" : "REJECTED");
        rec.setResolvedAt(LocalDateTime.now());
        toolApprovalMapper.updateById(rec);

        Map<String, Object> snapshot = JSON.parseObject(run.getStateSnapshot() == null ? "{}" : run.getStateSnapshot());
        String pendingNode = snapshot.get("pendingNode") == null ? "" : String.valueOf(snapshot.get("pendingNode"));
        WorkflowDsl dsl = parseDsl(run.getDslSnapshot());
        WorkflowRunCtx ctx = new WorkflowRunCtx(runId, RequestUser.uid(), RequestUser.departmentId(), RequestUser.role(),
                configService.currentOverrides(),
                parseJsonObject(run.getInputs()),
                configService.getDouble("chat.temperature"),
                configService.getInt("workflow.maxSteps", 50));
        // 快照回填：已完成节点输出短路回放；裁决传给审核节点路由
        Map<String, Object> outputs = (Map<String, Object>) snapshot.get("outputs");
        if (outputs != null) {
            for (Map.Entry<String, Object> e : outputs.entrySet()) {
                Map<String, Object> nodeOut = new LinkedHashMap<>();
                if (e.getValue() instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> en : m.entrySet()) nodeOut.put(String.valueOf(en.getKey()), en.getValue());
                }
                ctx.resumeOutputs.put(e.getKey(), nodeOut);
            }
        }
        ctx.approvalDecisions.put(pendingNode, approved);
        // 之前的 trace 原样保留（恢复运行的短路与新增节点续写在后面）
        List<Map<String, Object>> prevTraces = parseTraceList(run.getNodeTraces());
        ctx.traces.addAll(prevTraces);
        try {
            engine.resolveModels(dsl, ctx);
            CompiledGraph compiled = engine.compile(dsl, ctx);
            Optional<com.alibaba.cloud.ai.graph.OverAllState> result = invokeGraph(compiled);
            if (result.isEmpty()) {
                throw new BizException("恢复执行未返回终态（可能被步数上限截断，workflow.maxSteps=" + ctx.maxSteps + "）");
            }
            run.setStatus("success");
            run.setOutputs(JSON.toJSONString(engine.extractOutputs(dsl, result.get())));
            run.setStateSnapshot(null);
            log.info("[WORKFLOW] 审批{}后恢复运行成功：run={}（{} 节点短路回放）", approved ? "批准" : "拒绝", runId, ctx.resumeOutputs.size());
            return finish(run, ctx, null);
        } catch (RunTimeoutException e) {
            log.warn("[WORKFLOW] 恢复运行超时：run={}：{} 秒", runId, e.seconds);
            run.setStatus("timeout");
            return finish(run, ctx, "恢复执行超时（" + e.seconds + " 秒未跑完，workflow.runTimeoutSeconds，0=不限制）");
        } catch (BizException e) {
            log.warn("[WORKFLOW] 恢复运行失败：run={}：{}", runId, e.getMessage());
            return finish(run, ctx, e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[WORKFLOW] 恢复运行异常：run={}：{}", runId, cause.toString());
            return finish(run, ctx, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    /**
     * M5 失败检查点续跑（挂起-快照-短路重放的失败场景复用）：仅运行发起人可续跑；
     * 按 run 锁定的 DSL 快照恢复执行，<b>已成功节点短路回放</b>（不重复消耗 LLM 调用），
     * 只有失败节点与其下游真正执行。恢复在本次请求内同步完成——返回的就是终态 run。
     * <p>
     * 状态门：failed / timeout 且带检查点快照才可续跑（waiting_approval 走审批接口；
     * running / success 没有续跑语义）；成功后清快照，再失败则落<b>新的</b>检查点
     * （短路回放节点同步登记进 fullOutputs，快照始终完整）。
     */
    public WorkflowRun resumeRun(String workflowId, String runId) {
        WorkflowRun run = getRun(workflowId, runId);
        if (!"failed".equals(run.getStatus()) && !"timeout".equals(run.getStatus())) {
            throw new BizException("仅失败（failed）或超时（timeout）的运行可续跑（当前：" + run.getStatus()
                    + "；待审批请走审批接口）");
        }
        if (!RequestUser.uid().equals(run.getTriggeredBy())) {
            throw new BizException(403, "仅运行发起人可续跑");
        }
        if (run.getStateSnapshot() == null || run.getStateSnapshot().isBlank()) {
            throw new BizException("该运行没有检查点快照（失败发生在首个节点完成之前），请直接重新运行");
        }
        Map<String, Object> snapshot = JSON.parseObject(run.getStateSnapshot());
        Map<String, Object> outputs = (Map<String, Object>) snapshot.get("outputs");
        if (outputs == null || outputs.isEmpty()) {
            throw new BizException("检查点快照为空（失败发生在首个节点完成之前），请直接重新运行");
        }
        // 执行不可变：续跑沿用 run 锁定的 DSL 快照（不是当前草稿——改画布不影响这次运行的延续语义）
        WorkflowDsl dsl = parseDsl(run.getDslSnapshot());
        WorkflowRunCtx ctx = new WorkflowRunCtx(runId, RequestUser.uid(), RequestUser.departmentId(), RequestUser.role(),
                configService.currentOverrides(),
                parseJsonObject(run.getInputs()),
                configService.getDouble("chat.temperature"),
                configService.getInt("workflow.maxSteps", 50));
        // 快照回填：已完成节点输出短路回放；同时登记进 fullOutputs——本轮再失败时新检查点仍完整
        for (Map.Entry<String, Object> e : outputs.entrySet()) {
            Map<String, Object> nodeOut = new LinkedHashMap<>();
            if (e.getValue() instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> en : m.entrySet()) nodeOut.put(String.valueOf(en.getKey()), en.getValue());
            }
            ctx.resumeOutputs.put(e.getKey(), nodeOut);
            ctx.fullOutputs.put(e.getKey(), nodeOut);
        }
        // 之前的 trace 原样保留（续跑节点的新 trace 续写在后面，时间线连续可回放）
        List<Map<String, Object>> prevTraces = parseTraceList(run.getNodeTraces());
        ctx.traces.addAll(prevTraces);
        try {
            engine.resolveModels(dsl, ctx);
            CompiledGraph compiled = engine.compile(dsl, ctx);
            Optional<com.alibaba.cloud.ai.graph.OverAllState> result = invokeGraph(compiled);
            if (result.isEmpty()) {
                throw new BizException("续跑未返回终态（可能被步数上限截断，workflow.maxSteps=" + ctx.maxSteps + "）");
            }
            run.setStatus("success");
            run.setOutputs(JSON.toJSONString(engine.extractOutputs(dsl, result.get())));
            run.setStateSnapshot(null);
            run.setError(null);
            log.info("[WORKFLOW] 检查点续跑成功：run={}（{} 节点短路回放）", runId, ctx.resumeOutputs.size());
            return finish(run, ctx, null);
        } catch (RunTimeoutException e) {
            log.warn("[WORKFLOW] 续跑超时：run={}：{} 秒", runId, e.seconds);
            run.setStatus("timeout");
            writeCheckpoint(run, ctx);
            return finish(run, ctx, "续跑超时（" + e.seconds + " 秒未跑完，workflow.runTimeoutSeconds，0=不限制）");
        } catch (BizException e) {
            log.warn("[WORKFLOW] 续跑失败：run={}：{}", runId, e.getMessage());
            writeCheckpoint(run, ctx);
            return finish(run, ctx, e.getMessage());
        } catch (Exception e) {
            WorkflowSuspendException suspend = findSuspend(e);
            if (suspend != null) {
                // 续跑撞上人工审核节点（如审批超时终止后的续跑）：落回 waiting_approval，走正常审批流
                run.setStatus("waiting_approval");
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("outputs", ctx.fullOutputs);
                snap.put("pendingNode", suspend.nodeId);
                run.setStateSnapshot(JSON.toJSONString(snap));
                log.info("[WORKFLOW] 续跑挂起待审核：run={} 节点={} approval={}", runId, suspend.nodeId, suspend.approvalId);
                return finish(run, ctx, null);
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[WORKFLOW] 续跑异常：run={}：{}", runId, cause.toString());
            writeCheckpoint(run, ctx);
            return finish(run, ctx, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        }
    }

    /** 某次运行的待审批信息（前端审批卡：prompt/超时/挂起时刻）；无则返回 null */
    public ToolApproval pendingApproval(String workflowId, String runId) {
        getRun(workflowId, runId);
        return toolApprovalMapper.selectOne(new LambdaQueryWrapper<ToolApproval>()
                .eq(ToolApproval::getSessionId, runId)
                .eq(ToolApproval::getStatus, "PENDING")
                .last("LIMIT 1"));
    }
    /**
     * 审批超时回收（ScheduleCenter 周期调用）：挂起超过节点 timeoutSeconds 的 run 落 timeout 终态、
     * 审批记录落 TIMEOUT。审核等待是"人在回路"，没有自动恢复语义——超时即终止，重跑新开一轮。
     */
    public int reapApprovalTimeouts() {
        List<WorkflowRun> waiting = runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getStatus, "waiting_approval")
                .last("LIMIT 100"));
        int reaped = 0;
        for (WorkflowRun run : waiting) {
            ToolApproval rec = toolApprovalMapper.selectOne(new LambdaQueryWrapper<ToolApproval>()
                    .eq(ToolApproval::getSessionId, run.getId())
                    .eq(ToolApproval::getStatus, "PENDING")
                    .last("LIMIT 1"));
            int timeoutSeconds = 120;
            java.time.LocalDateTime anchor = java.time.LocalDateTime.now();
            if (rec != null) {
                anchor = rec.getCreatedAt() == null ? anchor : rec.getCreatedAt();
                Map<String, Object> args = parseJsonObject(rec.getRequestArgs());
                Object ts = args.get("timeoutSeconds");
                if (ts instanceof Number num) timeoutSeconds = num.intValue();
            } else {
                // 状态挂着但无 PENDING 记录（异常态）：按记录丢失终止，不留永久挂起
                run.setStatus("failed");
                run.setError("审批记录丢失，运行终止");
                run.setFinishedAt(LocalDateTime.now());
                run.setDurationMs(run.getStartedAt() == null ? null
                        : java.time.Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis());
                runMapper.updateById(run);
                reaped++;
                continue;
            }
            if (anchor.plusSeconds(Math.max(30, timeoutSeconds)).isAfter(LocalDateTime.now())) continue;
            rec.setStatus("TIMEOUT");
            rec.setResolvedAt(LocalDateTime.now());
            toolApprovalMapper.updateById(rec);
            run.setStatus("timeout");
            run.setError("人工审核超时（" + timeoutSeconds + " 秒未处理），运行终止");
            run.setFinishedAt(LocalDateTime.now());
            run.setDurationMs(java.time.Duration.between(run.getStartedAt() == null ? anchor : run.getStartedAt(),
                    LocalDateTime.now()).toMillis());
            runMapper.updateById(run);
            reaped++;
        }
        return reaped;
    }

    /** JSON 对象解析（空/非法返回空 map，不抛） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String s) {
        if (s == null || s.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.parseObject(s);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** trace JSON 数组解析（空/非法返回空列表，不抛） */
    private static List<Map<String, Object>> parseTraceList(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        try {
            JSONArray arr = JSON.parseArray(s);
            List<Map<String, Object>> out = new ArrayList<>();
            if (arr != null) {
                for (Object o : arr) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) t.put(String.valueOf(e.getKey()), e.getValue());
                        out.add(t);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 收口：写终态 + trace + 耗时（error 非空时仅在 run 还在 running 才置 failed——timeout 等终态由调用方先行设定） */
    private WorkflowRun finish(WorkflowRun run, WorkflowRunCtx ctx, String error) {
        if (error != null && "running".equals(run.getStatus())) run.setStatus("failed");
        run.setError(error == null ? null : WorkflowRunCtx.abbreviate(error, 1000));
        run.setNodeTraces(JSON.toJSONString(ctx.tracesSnapshot()));
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(System.currentTimeMillis() - ctx.t0);
        runMapper.updateById(run);
        return run;
    }

    /** 某工作流的运行记录（最近 50 条，新→旧）：归属校验后按 started_at 倒序 */
    public List<WorkflowRun> listRuns(String workflowId) {
        getOwn(workflowId);
        return runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, workflowId)
                .orderByDesc(WorkflowRun::getStartedAt)
                .last("LIMIT 50"));
    }

    /** 单条运行记录（含完整 trace）：归属两层校验（工作流是本人的 + run 属于该工作流） */
    public WorkflowRun getRun(String workflowId, String runId) {
        getOwn(workflowId);
        WorkflowRun r = runMapper.selectById(runId);
        if (r == null || !workflowId.equals(r.getWorkflowId())) {
            throw new BizException(404, "运行记录不存在");
        }
        return r;
    }
}
