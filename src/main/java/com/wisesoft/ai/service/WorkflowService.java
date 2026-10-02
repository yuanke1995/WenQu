package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.wisesoft.ai.model.Notification;
import com.wisesoft.ai.model.ToolApproval;
import com.wisesoft.ai.model.User;
import com.wisesoft.ai.thread.ThreadPoolManager;
import com.wisesoft.ai.util.BatchResults;
import com.wisesoft.ai.util.RequestUser;
import com.wisesoft.ai.util.SsrfGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /** 定时触发默认时区（与定时智能体任务同口径） */
    private static final String DEFAULT_SCHEDULE_ZONE = "Asia/Shanghai";
    /** 单轮扫描最多触发的定时工作流数（防一次积压打满线程池） */
    private static final int SCHEDULE_SCAN_LIMIT = 20;
    /** 定时运行失败自动重试次数（计划：失败重试 1 次） */
    private static final int SCHEDULE_MAX_RETRY = 1;
    /** 终态回调出站超时（ms） */
    private static final int CALLBACK_TIMEOUT_MS = 10_000;

    /** 回调出站客户端（复用一个实例；跟随常规重定向） */
    private static final HttpClient CALLBACK_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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
    private final WorkflowRunStreamService stream;
    private final NotificationService notificationService;

    /**
     * 异步运行派发池（第 2 期：run 异步化 + 排队可见）：core=max=workflow.maxConcurrentRuns，
     * 有界队列 workflow.runQueueCapacity——队列满即拒绝（fail-loud，不无限堆积）。
     * 与 run 级超时用的 {@link #RUN_POOL} 分开：异步任务若与超时包装同池，池满时会自我等待死锁。
     * 并发/队列容量在首次使用时读配置（改配置需重启生效）。
     */
    private volatile java.util.concurrent.ThreadPoolExecutor dispatchPool;

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

    /**
     * 可见的工作流列表：{@code {mine:[...], shared:[...]}}（我创建的 / 共享给我的）。
     * <p>
     * 「共享给我的」= 他人创建且显式配置了 share_config、且当前用户命中读取范围的工作流。
     * 共享范围存在 JSON 列里无法下推到 SQL，故先按「非本人 + 有 share_config」粗筛再逐个判定
     * （个人资产量级下可接受；判定与执行期 {@link #loadVisible} 完全同口径，不会出现"列表可见却打不开"）。
     */
    public Map<String, Object> listVisible() {
        Principal p = Principal.current();
        ResourceVisibilityService.Principal vp = vp(p);
        List<Workflow> mine = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getUid, p.uid())
                .orderByDesc(Workflow::getUpdateTime));
        List<Workflow> candidates = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .ne(Workflow::getUid, p.uid())
                .isNotNull(Workflow::getShareConfig)
                .ne(Workflow::getShareConfig, "")
                .orderByDesc(Workflow::getUpdateTime));
        List<Workflow> shared = new ArrayList<>();
        for (Workflow w : candidates) {
            if (visibility.canRead(vp, w.getShareConfig(), w.getUid(), ResourceVisibilityService.ResourceKind.WORKFLOW)) {
                shared.add(w);
            }
        }
        fillLastRun(mine);
        fillLastRun(shared);
        for (Workflow w : mine) decorate(w, p, true);
        for (Workflow w : shared) decorate(w, p, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mine", mine);
        out.put("shared", shared);
        return out;
    }

    /** 批量回填最近一次运行状态与时刻（列表页展示；派生信息不落库） */
    private void fillLastRun(List<Workflow> rows) {
        if (rows.isEmpty()) return;
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
    }

    /** 详情（共享只读者也能加载画布）：附 myPermission/mine，回调密钥本体不外泄 */
    public Workflow getVisible(String id) {
        Principal p = Principal.current();
        Workflow row = loadVisible(id, p);
        decorate(row, p, p.uid() != null && p.uid().equals(row.getUid()));
        return row;
    }

    /** 派生字段填充 + 敏感字段脱敏（回调签名密钥只回"是否已设置"，不回明文） */
    private void decorate(Workflow w, Principal p, boolean mine) {
        w.setMine(mine);
        w.setMyPermission(visibility.canManage(vp(p), w.getShareConfig(), w.getUid(),
                ResourceVisibilityService.ResourceKind.WORKFLOW) ? "MANAGE" : "READ");
        w.setCallbackSecretSet(w.getCallbackSecret() != null && !w.getCallbackSecret().isBlank());
        w.setCallbackSecret(null);
    }

    private static ResourceVisibilityService.Principal vp(Principal p) {
        return new ResourceVisibilityService.Principal(p.uid(), p.departmentId(), p.role());
    }

    /** 取一条工作流并要求管理权（创建者或管理范围命中）；不存在与无权都按 404（不泄露存在性） */
    public Workflow requireManage(String id) {
        Principal p = Principal.current();
        Workflow row = workflowMapper.selectById(id);
        if (row == null) throw new BizException(404, "工作流不存在");
        if (!visibility.canManage(vp(p), row.getShareConfig(), row.getUid(),
                ResourceVisibilityService.ResourceKind.WORKFLOW)) {
            throw new BizException(404, "工作流不存在");
        }
        return row;
    }

    /** 取一条工作流并要求读取权（创建者 / 共享读取 / 共享管理）；不存在与无权都按 404 */
    public Workflow requireRead(String id) {
        return loadVisible(id, Principal.current());
    }

    /**
     * 取一条工作流并要求管理权。保留方法名以兼容既有调用点（更新/删除/发布/下线/回滚）；
     * 语义已从「仅创建者」放宽为「创建者或共享管理范围命中」——只读共享者仍拿不到管理权。
     */
    public Workflow getOwn(String id) {
        return requireManage(id);
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

    // --------------------------------------------------------------------------------------------------
    // 批量操作：逐条执行、部分成功是批量的固有语义——失败条目逐条带原因汇报，不静默吞
    //（结果结构收口在 {@link BatchResults}，与其余各域的批量接口同口径）
    // --------------------------------------------------------------------------------------------------

    /** 批量删除（仅本人创建的可删）：返回 {succeeded:[id], failed:[{id,name,error}]} */
    public Map<String, Object> batchDelete(List<String> ids) {
        List<Workflow> rows = loadOwnRows(ids);
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Workflow row : rows) {
            try {
                workflowMapper.deleteById(row.getId());
                log.info("[WORKFLOW] 批量删除工作流 {}（{}）uid={}", row.getName(), row.getId(), row.getUid());
                succeeded.add(row.getId());
            } catch (Exception e) {
                failed.add(BatchResults.failItem(row.getId(), row.getName(), BatchResults.errMsg(e)));
            }
        }
        return BatchResults.result(ids, succeeded, failed);
    }

    /** 批量发布：逐条复用单条发布语义（草稿校验 → 版本冻结）；校验不过的条目进 failed 带原因 */
    public Map<String, Object> batchPublish(List<String> ids, String note) {
        List<Workflow> rows = loadOwnRows(ids);
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Workflow row : rows) {
            try {
                publish(row.getId(), note);
                succeeded.add(row.getId());
            } catch (BizException e) {
                failed.add(BatchResults.failItem(row.getId(), row.getName(), e.getMessage()));
            }
        }
        return BatchResults.result(ids, succeeded, failed);
    }

    /** 批量下线：逐条已发布 → 草稿；非已发布条目进 failed 带原因（不静默跳过） */
    public Map<String, Object> batchUnpublish(List<String> ids) {
        List<Workflow> rows = loadOwnRows(ids);
        List<String> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Workflow row : rows) {
            try {
                unpublish(row.getId());
                succeeded.add(row.getId());
            } catch (BizException e) {
                failed.add(BatchResults.failItem(row.getId(), row.getName(), e.getMessage()));
            }
        }
        return BatchResults.result(ids, succeeded, failed);
    }

    /** 批量口径只碰本人创建的工作流；空选 = 参数错误 */
    private List<Workflow> loadOwnRows(List<String> ids) {
        if (ids == null || ids.isEmpty()) throw new BizException("请先选择要操作的工作流");
        List<Workflow> rows = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .in(Workflow::getId, ids)
                .eq(Workflow::getUid, RequestUser.uid()));
        if (rows.isEmpty()) throw new BizException("所选工作流不存在或无权操作");
        return rows;
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

    /** 版本历史（新→旧）：版本号/说明/发布者/时间；DSL 大体量，只在详情接口给。共享只读者可见 */
    public List<Map<String, Object>> listVersions(String id) {
        Workflow row = requireRead(id);
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
        Principal p = Principal.current();
        Workflow row = loadVisible(id, p);
        RunTarget t = resolveRunTarget(row, p);
        return execute(row, t.dslText, "manual", p.uid(), null, t.version, t.dslSource, inputs, null, null, p);
    }

    /**
     * 第 2 期：异步调试运行——创建即返回 {@code status=queued} 的 run，实际执行在派发池里跑，
     * 前端订阅 {@code /run/{runId}/stream} 边跑边亮。DSL 选择与同步 {@link #run} 同口径
     * （管理权跑草稿、共享只读跑已发布版本）；DSL 在入队前先解析（坏图 fail-loud，不占队列）。
     */
    public WorkflowRun runAsync(String id, Map<String, Object> inputs) {
        Principal p = Principal.current();
        Workflow row = loadVisible(id, p);
        RunTarget t = resolveRunTarget(row, p);
        WorkflowDsl dsl = parseDsl(t.dslText);   // 入队前解析：坏 DSL 立即报错，不占用队列
        WorkflowRun run = newRun(row, t.dslText, "manual", p.uid(), null, t.version, t.dslSource, inputs);
        run.setStatus("queued");
        WorkflowRunCtx ctx = newCtx(run.getId(), p, inputs, null, null);
        ctx.setTraceSink(tr -> stream.publishTrace(run.getId(), tr));   // 节点完成即推
        runMapper.insert(run);
        submitAsync(run, ctx, row, dsl);
        return run;
    }

    /** 本次调试运行要跑哪份 DSL（管理权→草稿；共享只读→已发布版本，未发布则 fail-loud） */
    private RunTarget resolveRunTarget(Workflow row, Principal p) {
        if (visibility.canManage(vp(p), row.getShareConfig(), row.getUid(),
                ResourceVisibilityService.ResourceKind.WORKFLOW)) {
            return new RunTarget(row.getDsl(), null, "draft");
        }
        if (!"published".equals(row.getStatus()) || row.getPublishedDsl() == null || row.getPublishedDsl().isBlank()) {
            throw new BizException("工作流「" + row.getName() + "」尚未发布：共享只读用户只能运行已发布版本");
        }
        return new RunTarget(row.getPublishedDsl(), row.getPublishedVersion(), "published");
    }

    private record RunTarget(String dslText, Integer version, String dslSource) {
    }

    /** 派发池（懒建；core=max=并发上限，有界队列=排队容量，满则拒绝） */
    private java.util.concurrent.ThreadPoolExecutor dispatchPool() {
        java.util.concurrent.ThreadPoolExecutor p = dispatchPool;
        if (p != null) return p;
        synchronized (this) {
            if (dispatchPool == null) {
                int concurrency = Math.max(1, configService.getInt("workflow.maxConcurrentRuns", 4));
                int capacity = Math.max(1, configService.getInt("workflow.runQueueCapacity", 50));
                dispatchPool = new java.util.concurrent.ThreadPoolExecutor(concurrency, concurrency,
                        60L, java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(capacity),
                        r -> {
                            Thread t = new Thread(r, "wf-async");
                            t.setDaemon(true);
                            return t;
                        });
            }
            return dispatchPool;
        }
    }

    /**
     * 投递异步运行。任务启动时用<b>条件更新 queued→running</b> 占位：被取消（状态已非 queued）则直接退出——
     * 与 {@link #cancelQueuedRun} 的 queued→failed 条件更新互斥，天然无竞态（不靠 Future.cancel 的时序）。
     * 队列满则拒绝并落 failed（fail-loud，不静默排队到天荒地老）。
     */
    private void submitAsync(WorkflowRun run, WorkflowRunCtx ctx, Workflow row, WorkflowDsl dsl) {
        try {
            dispatchPool().execute(() -> {
                int started = runMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                        .eq(WorkflowRun::getId, run.getId())
                        .eq(WorkflowRun::getStatus, "queued")
                        .set(WorkflowRun::getStatus, "running"));
                if (started == 0) return;   // 已被取消：状态不再是 queued
                run.setStatus("running");
                stream.publishStatus(run.getId(), "running", null);
                runGraph(run, ctx, row, dsl);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            run.setStatus("failed");
            run.setError("运行队列已满（workflow.runQueueCapacity=" + configService.getInt("workflow.runQueueCapacity", 50)
                    + "），请稍后重试");
            run.setFinishedAt(LocalDateTime.now());
            run.setDurationMs(0L);
            runMapper.updateById(run);
            stream.publishDone(run.getId(), run);
            throw new BizException("运行队列已满，请稍后重试");
        }
    }

    /**
     * 取消排队中的运行（第 2 期）：条件更新 queued→failed，与任务启动的 queued→running 互斥——
     * 只有一个能成功。已在运行 / 已结束则报错（不做中断式取消：运行中的 LLM/HTTP 节点无法安全打断）。
     */
    public WorkflowRun cancelQueuedRun(String workflowId, String runId) {
        requireRead(workflowId);
        WorkflowRun run = runMapper.selectById(runId);
        if (run == null || !workflowId.equals(run.getWorkflowId())) {
            throw new BizException(404, "运行记录不存在");
        }
        String reason = "已取消排队（用户取消）";
        int updated = runMapper.update(null, new LambdaUpdateWrapper<WorkflowRun>()
                .eq(WorkflowRun::getId, runId)
                .eq(WorkflowRun::getStatus, "queued")
                .set(WorkflowRun::getStatus, "failed")
                .set(WorkflowRun::getError, reason)
                .set(WorkflowRun::getFinishedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw new BizException("该运行已开始或已结束，无法取消排队");
        }
        run.setStatus("failed");
        run.setError(reason);
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(0L);
        stream.publishDone(runId, run);
        log.info("[WORKFLOW] 取消排队运行：workflow={} run={}", workflowId, runId);
        return run;
    }

    /**
     * 同步执行内核（调试运行 / API / 智能体 / 定时共用）：建 ctx + run 记录 → 图执行 → 收口。
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
        WorkflowRun run = newRun(row, dslText, triggerType, triggeredBy, apiKeyId, version, dslSource, inputs);
        WorkflowRunCtx ctx = newCtx(run.getId(), p, inputs, tokenSink, imageSink);
        runMapper.insert(run);
        return runGraph(run, ctx, row, dsl);
    }

    /** 建运行记录（未落库；status 由调用方按同步/异步设定） */
    private WorkflowRun newRun(Workflow row, String dslText, String triggerType, String triggeredBy,
                               String apiKeyId, Integer version, String dslSource, Map<String, Object> inputs) {
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID().toString());
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
        return run;
    }

    /** 建运行上下文（trace 增量回调由调用方按需安装，见 {@code ctx.setTraceSink}） */
    private WorkflowRunCtx newCtx(String runId, Principal p, Map<String, Object> inputs,
                                  Consumer<String> tokenSink, Consumer<List<String>> imageSink) {
        return new WorkflowRunCtx(runId, p.uid(), p.departmentId(), p.role(),
                configService.currentOverrides(), inputs,
                configService.getDouble("chat.temperature"),
                configService.getInt("workflow.maxSteps", 50),
                tokenSink, imageSink);
    }

    /** 图执行内核（同步/异步共用）：入参校验 → 模型预解析（fail-fast）→ 编译 → 执行 → 出参提取 → 收口 */
    private WorkflowRun runGraph(WorkflowRun run, WorkflowRunCtx ctx, Workflow row, WorkflowDsl dsl) {
        try {
            WorkflowEngine.checkStartInputs(dsl, ctx.inputs);
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
        ctx.setTraceSink(tr -> stream.publishTrace(runId, tr));   // 续跑也边跑边亮（画布流未关时可见）
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
        ctx.setTraceSink(tr -> stream.publishTrace(runId, tr));   // 续跑也边跑边亮（画布流未关时可见）
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

    /**
     * 运行记录超期物理清理（ScheduleCenter「工作流运行记录清理」周期调用）：按 startedAt 删超期行——
     * dsl_snapshot 与 node_traces 是大字段，不清理会随运行次数无限膨胀（与任务执行日志/产物清理同一口径）。
     * 挂起审批的 run 不删：c_ai_tool_approval 还引用着它，删了会丢人工裁决现场（正常由审批超时回收兜底落终态）。
     */
    public int cleanupExpiredRuns(int retentionDays) {
        return runMapper.delete(new LambdaQueryWrapper<WorkflowRun>()
                .lt(WorkflowRun::getStartedAt, LocalDateTime.now().minusDays(retentionDays))
                .ne(WorkflowRun::getStatus, "waiting_approval"));
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
        fireCallbackIfConfigured(run);   // 终态回调（配置了才发；异步、失败不影响收口）
        notifyRunOutcome(run);           // 站内通知（旁路，失败不影响收口）
        // 进度流收口：终态关流；挂起（非终态）只推状态、保持流打开（等审批/超时回收再关）
        if (WorkflowRunStreamService.isTerminal(run.getStatus())) {
            stream.publishDone(run.getId(), run);
        } else {
            stream.publishStatus(run.getId(), run.getStatus(), null);
        }
        return run;
    }

    /**
     * 运行终态/挂起的站内通知（通知是旁路，内部已全捕获，失败不影响收口）：
     * <ul>
     *   <li><b>挂起待审核</b>：一律通知发起人——挂起意味着"等人裁决"，人在画布前也可能中途离开；</li>
     *   <li><b>失败/超时</b>：非 manual 触发才通知——manual 的发起人正在画布前看 SSE 实时进度，
     *       通知是噪音；schedule/api/agent 触发的发起人此时不在现场，必须可感知。</li>
     * </ul>
     */
    private void notifyRunOutcome(WorkflowRun run) {
        String status = run.getStatus();
        if ("waiting_approval".equals(status)) {
            notificationService.create(run.getTriggeredBy(), Notification.TYPE_WORKFLOW_APPROVAL,
                    "工作流「" + workflowName(run.getWorkflowId()) + "」运行挂起待审核",
                    "运行已在人工审核节点暂停，前往工作流画布查看并裁决。",
                    "workflow", run.getWorkflowId());
        } else if (("failed".equals(status) || "timeout".equals(status))
                && !"manual".equals(run.getTriggerType())) {
            String type = "timeout".equals(status) ? Notification.TYPE_WORKFLOW_TIMEOUT : Notification.TYPE_WORKFLOW_FAILED;
            notificationService.create(run.getTriggeredBy(), type,
                    "工作流「" + workflowName(run.getWorkflowId()) + "」运行" + ("timeout".equals(status) ? "超时" : "失败"),
                    (run.getError() == null || run.getError().isBlank()) ? null : run.getError(),
                    "workflow", run.getWorkflowId());
        }
    }

    /** 工作流名（通知文案用；行已删/不存在时回落 ID，不让通知发不出去） */
    private String workflowName(String workflowId) {
        Workflow w = workflowMapper.selectById(workflowId);
        return w == null ? workflowId : w.getName();
    }

    /** 某工作流的运行记录（最近 50 条，新→旧）：读取权校验后按 started_at 倒序（共享只读者可见） */
    public List<WorkflowRun> listRuns(String workflowId) {
        requireRead(workflowId);
        return runMapper.selectList(new LambdaQueryWrapper<WorkflowRun>()
                .eq(WorkflowRun::getWorkflowId, workflowId)
                .orderByDesc(WorkflowRun::getStartedAt)
                .last("LIMIT 50"));
    }

    /** 单条运行记录（含完整 trace）：读取权校验 + run 归属该工作流（共享只读者可见） */
    public WorkflowRun getRun(String workflowId, String runId) {
        requireRead(workflowId);
        WorkflowRun r = runMapper.selectById(runId);
        if (r == null || !workflowId.equals(r.getWorkflowId())) {
            throw new BizException(404, "运行记录不存在");
        }
        return r;
    }

    /**
     * 订阅某次运行的进度流（第 2 期）：读取权校验后，按 run 快照（当前状态 + 已产生 trace）订阅——
     * 订阅前完成的节点由快照回放补齐，已终态则回放后立即关闭（断线重连 / 迟到订阅都不丢时间线）。
     */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribeRunStream(String workflowId, String runId) {
        WorkflowRun run = getRun(workflowId, runId);
        return stream.subscribe(runId, run.getStatus(), parseTraceList(run.getNodeTraces()));
    }

    // --------------------------------------------------------------------------------------------------
    // 第 1 期：共享范围（谁建归谁为默认，共享=显式授权）
    // --------------------------------------------------------------------------------------------------

    /**
     * 设置共享范围（仅创建者 / 管理范围命中可改）：写入前走
     * {@link ResourceVisibilityService#validateShareConfig} 强校验（version=2 且 manage ⊆ read）；
     * 空串 = 清空共享（回落「谁建归谁」的私有语义）。
     */
    public Map<String, Object> share(String id, String shareConfigJson) {
        Workflow row = requireManage(id);
        visibility.validateShareConfig(shareConfigJson);
        String value = shareConfigJson == null || shareConfigJson.isBlank() ? null : shareConfigJson.trim();
        // share_config 可为 NULL：updateById 的 NOT_NULL 策略会跳过 null 列（清空共享写不进去），
        // 必须走 LambdaUpdateWrapper 显式 set（与 ScheduledJobService 置空 next_run_at 是同一个坑）
        workflowMapper.update(null, new LambdaUpdateWrapper<Workflow>()
                .eq(Workflow::getId, row.getId())
                .set(Workflow::getShareConfig, value)
                .set(Workflow::getUpdateTime, LocalDateTime.now()));
        log.info("[WORKFLOW] 设置共享范围 {}（{}）uid={} 共享={}", row.getName(), row.getId(), row.getUid(),
                value == null ? "私有" : value);
        return Map.of("id", row.getId(), "shareConfig", value == null ? "" : value);
    }

    // --------------------------------------------------------------------------------------------------
    // 第 1 期：定时触发（cron 绑定已发布版本）与运行终态回调
    // --------------------------------------------------------------------------------------------------

    /**
     * 保存自动化配置（定时 + 回调），仅管理权可改。
     * <p>
     * 定时只跑<b>已发布版本</b>——草稿变更不生效（与 API / 智能体触发同口径）；未发布时配置可保存，
     * 但触发时跳过并告警（让用户先把定时配好、发布后自然生效）。回调地址限 http/https，出站前过
     * {@link SsrfGuard}（内网地址拒绝，与 http 节点同口径）。
     *
     * @param body {scheduleEnabled?, cron?, timezone?, callbackUrl?, callbackSecret?}
     */
    public Map<String, Object> saveAutomation(String id, Map<String, Object> body) {
        Workflow row = requireManage(id);
        if (body == null) body = Map.of();

        boolean enabled = asBool(body.get("scheduleEnabled"));
        String cron = asStr(body.get("cron"));
        String timezone = body.containsKey("timezone") ? asStr(body.get("timezone")) : null;
        if (enabled && (cron == null || cron.isBlank())) {
            throw new BizException("启用定时触发前请填写 cron 表达式");
        }
        String tz = timezone == null || timezone.isBlank()
                ? (row.getScheduleTimezone() == null ? DEFAULT_SCHEDULE_ZONE : row.getScheduleTimezone())
                : timezone.trim();
        if (cron != null && !cron.isBlank()) ScheduledJobService.validateCron(cron, tz);
        LocalDateTime next = enabled ? ScheduledJobService.nextRunAt(cron, tz, LocalDateTime.now()) : null;

        boolean setUrl = body.containsKey("callbackUrl");
        String callbackUrl = setUrl ? asStr(body.get("callbackUrl")) : null;
        if (setUrl && callbackUrl != null && callbackUrl.isBlank()) callbackUrl = null;
        if (callbackUrl != null) assertCallbackUrl(callbackUrl);
        boolean setSecret = body.containsKey("callbackSecret");
        String secret = setSecret ? asStr(body.get("callbackSecret")) : null;
        if (setSecret && secret != null && secret.isBlank()) secret = null;

        LambdaUpdateWrapper<Workflow> up = new LambdaUpdateWrapper<Workflow>()
                .eq(Workflow::getId, row.getId())
                .set(Workflow::getScheduleEnabled, enabled ? 1 : 0)
                .set(Workflow::getScheduleCron, cron == null || cron.isBlank() ? null : cron.trim())
                .set(Workflow::getScheduleTimezone, tz)
                .set(Workflow::getScheduleNextRunAt, next)
                .set(Workflow::getUpdateTime, LocalDateTime.now());
        if (setUrl) up.set(Workflow::getCallbackUrl, callbackUrl);
        if (setSecret) up.set(Workflow::getCallbackSecret, secret);
        workflowMapper.update(null, up);
        log.info("[WORKFLOW] 保存自动化配置 {}（{}）：定时={} cron={} 回调={}", row.getName(), row.getId(),
                enabled, cron, callbackUrl == null ? "未配置" : callbackUrl);
        return automationConfig(row.getId());
    }

    /** 自动化配置（密钥脱敏）：定时开关/cron/时区/下次执行 + 回调地址/是否已设密钥。含回调地址，读取同样要管理权 */
    public Map<String, Object> automationConfig(String id) {
        Workflow row = requireManage(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("scheduleEnabled", Integer.valueOf(1).equals(row.getScheduleEnabled()));
        m.put("cron", row.getScheduleCron());
        m.put("timezone", row.getScheduleTimezone());
        m.put("nextRunAt", row.getScheduleNextRunAt());
        m.put("callbackUrl", row.getCallbackUrl());
        m.put("callbackSecretSet", row.getCallbackSecret() != null && !row.getCallbackSecret().isBlank());
        m.put("published", "published".equals(row.getStatus()));
        return m;
    }

    /**
     * 定时触发扫描（ScheduleCenter 周期调用）：取到期的启用定时工作流，<b>先推进 next_run_at 再异步派发</b>，
     * 防同一轮重复触发（与定时智能体任务同一范式）。只跑已发布版本；失败自动重试 1 次。
     *
     * @return 本轮派发的数量
     */
    public int tickSchedules() {
        LocalDateTime now = LocalDateTime.now();
        List<Workflow> due = workflowMapper.selectList(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getScheduleEnabled, 1)
                .isNotNull(Workflow::getScheduleNextRunAt)
                .le(Workflow::getScheduleNextRunAt, now)
                .orderByAsc(Workflow::getScheduleNextRunAt)
                .last("LIMIT " + SCHEDULE_SCAN_LIMIT));
        for (Workflow w : due) {
            LocalDateTime next = ScheduledJobService.nextRunAt(w.getScheduleCron(), w.getScheduleTimezone(), now);
            workflowMapper.update(null, new LambdaUpdateWrapper<Workflow>()
                    .eq(Workflow::getId, w.getId())
                    .set(Workflow::getScheduleNextRunAt, next));
            final String wid = w.getId();
            ThreadPoolManager.execute(() -> runScheduled(wid, 0));
        }
        return due.size();
    }

    /** 手动立即触发一次定时运行（管理权）：同步返回终态 run，便于在界面直接看 trace */
    public WorkflowRun runScheduleNow(String id) {
        Workflow row = requireManage(id);
        WorkflowRun run = runScheduledSync(row.getId());
        if (run == null) throw new BizException("该工作流尚未发布：定时运行只跑已发布版本");
        return run;
    }

    /** 异步派发的定时运行：只跑已发布版本；失败重试 1 次 */
    private void runScheduled(String workflowId, int attempt) {
        try {
            WorkflowRun run = runScheduledSync(workflowId);
            if (run == null) return;
            if (attempt < SCHEDULE_MAX_RETRY
                    && ("failed".equals(run.getStatus()) || "timeout".equals(run.getStatus()))) {
                log.warn("[WORKFLOW] 定时运行失败将重试一次：workflow={} run={} err={}",
                        workflowId, run.getId(), run.getError());
                runScheduled(workflowId, attempt + 1);
            }
        } catch (Exception e) {
            log.warn("[WORKFLOW] 定时运行异常（已跳过本轮）：workflow={} err={}", workflowId, e.getMessage());
        }
    }

    /**
     * 跑一次已发布版本（定时触发）。未发布 / 已删除则跳过并告警（不产生 run 记录）；
     * 含人工审核节点也跳过（定时无审批交互面，与 API 触发同口径 fail-loud）。
     * 触发身份 = 工作流创建者（定时任务的身份就是归属人）。
     */
    private WorkflowRun runScheduledSync(String workflowId) {
        Workflow row = workflowMapper.selectById(workflowId);
        if (row == null) return null;
        if (!"published".equals(row.getStatus()) || row.getPublishedDsl() == null || row.getPublishedDsl().isBlank()) {
            log.warn("[WORKFLOW] 定时触发跳过：{}（{}）未发布（定时只跑已发布版本）", row.getName(), row.getId());
            return null;
        }
        try {
            assertNoApprovalNode(parseDsl(row.getPublishedDsl()), "定时触发");
        } catch (BizException e) {
            log.warn("[WORKFLOW] 定时触发跳过：{}（{}）{}", row.getName(), row.getId(), e.getMessage());
            return null;
        }
        Principal p = Principal.ofUser(row.getUid(), userMapper);
        return execute(row, row.getPublishedDsl(), "schedule", row.getUid(), null, row.getPublishedVersion(),
                "published", Map.of(), null, null, p);
    }

    /** 回调地址校验：http/https + 过 SSRF（内网地址拒绝，与 http 节点同口径） */
    private static void assertCallbackUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new BizException("回调地址不是合法 URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException("回调地址必须以 http:// 或 https:// 开头");
        }
        try {
            SsrfGuard.requirePublicHost(uri);
        } catch (Exception e) {
            throw new BizException("回调地址不允许指向内网：" + e.getMessage());
        }
    }

    /** 终态回调（仅 success/failed/timeout）：异步 POST，失败只告警、不影响运行收口 */
    private void fireCallbackIfConfigured(WorkflowRun run) {
        String status = run.getStatus();
        if (!"success".equals(status) && !"failed".equals(status) && !"timeout".equals(status)) return;
        Workflow wf = run.getWorkflowId() == null ? null : workflowMapper.selectById(run.getWorkflowId());
        if (wf == null || wf.getCallbackUrl() == null || wf.getCallbackUrl().isBlank()) return;
        final String url = wf.getCallbackUrl().trim();
        final String secret = wf.getCallbackSecret();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("workflowId", run.getWorkflowId());
        payload.put("status", status);
        payload.put("triggerType", run.getTriggerType());
        payload.put("version", run.getVersion());
        payload.put("startedAt", run.getStartedAt() == null ? null : run.getStartedAt().toString());
        payload.put("finishedAt", run.getFinishedAt() == null ? null : run.getFinishedAt().toString());
        payload.put("durationMs", run.getDurationMs());
        payload.put("outputs", parseJsonObject(run.getOutputs()));
        payload.put("error", run.getError());
        final String body = JSON.toJSONString(payload);
        ThreadPoolManager.execute(() -> postCallback(url, body, secret, run.getId()));
    }

    /** 回调出站（HMAC-SHA256 签名头 X-Wenqu-Signature；10s 超时；失败只告警） */
    private static void postCallback(String url, String body, String secret, String runId) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(CALLBACK_TIMEOUT_MS))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Wenqu-Event", "workflow.run.finished")
                    .header("X-Wenqu-Run-Id", runId)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (secret != null && !secret.isBlank()) {
                b.header("X-Wenqu-Signature", "sha256=" + hmacSha256Hex(secret, body));
            }
            HttpResponse<String> resp = CALLBACK_CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("[WORKFLOW] 回调返回非 2xx：run={} status={} url={}", runId, resp.statusCode(), url);
            } else {
                log.info("[WORKFLOW] 回调成功：run={} url={}", runId, url);
            }
        } catch (Exception e) {
            log.warn("[WORKFLOW] 回调失败（不影响运行）：run={} url={} err={}", runId, url, e.getMessage());
        }
    }

    private static String hmacSha256Hex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static boolean asBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o));
    }
}
