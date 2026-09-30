package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.ToolApprovalMapper;
import com.wisesoft.ai.mapper.WorkflowMapper;
import com.wisesoft.ai.mapper.WorkflowRunMapper;
import com.wisesoft.ai.model.AiWorkflow;
import com.wisesoft.ai.model.AiWorkflowRun;
import com.wisesoft.ai.model.ToolApproval;
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

/**
 * 工作流管理：DSL 是唯一真源（架构决策见排班计划），本服务做
 * 存储 + 校验 + 编译 dry-run 的编排，以及调试运行（run）与运行记录查询。
 * <p>
 * 归属口径（分期边界）：M0~M3 仅<b>创建者本人</b>可见可管（share_config 字段预留，
 * M4 随发布语义一起启用两级可见性）——不做半吊子共享。
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

    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper runMapper;
    private final ToolApprovalMapper toolApprovalMapper;
    private final WorkflowValidator validator;
    private final WorkflowEngine engine;
    private final ConfigService configService;

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
    public AiWorkflowRun run(String id, Map<String, Object> inputs) {
        AiWorkflow row = getOwn(id);
        WorkflowDsl dsl = parseDsl(row.getDsl());
        String runId = UUID.randomUUID().toString();
        WorkflowRunCtx ctx = new WorkflowRunCtx(runId, RequestUser.uid(), RequestUser.departmentId(), RequestUser.role(),
                configService.currentOverrides(), inputs,
                configService.getDouble("chat.temperature"),
                configService.getInt("workflow.maxSteps", 50));
        AiWorkflowRun run = new AiWorkflowRun();
        run.setId(runId);
        run.setWorkflowId(row.getId());
        run.setDslSnapshot(row.getDsl());   // 执行不可变：锁定本次运行用的 DSL（对齐 Coze run 语义）
        run.setTriggerType("manual");       // agent/api 触发 M4 接入
        run.setTriggeredBy(RequestUser.uid());
        run.setStatus("running");
        run.setInputs(JSON.toJSONString(inputs == null ? Map.of() : inputs));
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        try {
            WorkflowEngine.checkStartInputs(dsl, inputs);
            engine.resolveModels(dsl, ctx);
            CompiledGraph compiled = engine.compile(dsl, ctx);
            Optional<com.alibaba.cloud.ai.graph.OverAllState> result = compiled.invoke(Map.of());
            if (result.isEmpty()) {
                throw new BizException("图执行未返回终态（可能被步数上限截断，workflow.maxSteps=" + ctx.maxSteps + "）");
            }
            Map<String, Object> outputs = engine.extractOutputs(dsl, result.get());
            run.setStatus("success");
            run.setOutputs(JSON.toJSONString(outputs));
            log.info("[WORKFLOW] 运行成功：{}（{}）run={} 耗时 {}ms", row.getName(), row.getId(), run.getId(),
                    System.currentTimeMillis() - ctx.t0);
            return finish(run, ctx, null);
        } catch (BizException e) {
            log.warn("[WORKFLOW] 运行失败：{}（{}）run={}：{}", row.getName(), row.getId(), run.getId(), e.getMessage());
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
            return finish(run, ctx, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
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
    public AiWorkflowRun approveRun(String workflowId, String runId, boolean approved) {
        AiWorkflowRun run = getRun(workflowId, runId);
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
            Optional<com.alibaba.cloud.ai.graph.OverAllState> result = compiled.invoke(Map.of());
            if (result.isEmpty()) {
                throw new BizException("恢复执行未返回终态（可能被步数上限截断，workflow.maxSteps=" + ctx.maxSteps + "）");
            }
            run.setStatus("success");
            run.setOutputs(JSON.toJSONString(engine.extractOutputs(dsl, result.get())));
            run.setStateSnapshot(null);
            log.info("[WORKFLOW] 审批{}后恢复运行成功：run={}（{} 节点短路回放）", approved ? "批准" : "拒绝", runId, ctx.resumeOutputs.size());
            return finish(run, ctx, null);
        } catch (BizException e) {
            log.warn("[WORKFLOW] 恢复运行失败：run={}：{}", runId, e.getMessage());
            return finish(run, ctx, e.getMessage());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[WORKFLOW] 恢复运行异常：run={}：{}", runId, cause.toString());
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
        List<AiWorkflowRun> waiting = runMapper.selectList(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getStatus, "waiting_approval")
                .last("LIMIT 100"));
        int reaped = 0;
        for (AiWorkflowRun run : waiting) {
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

    /** 收口：写终态 + trace + 耗时（status=failed 时 error 带原因；成功时 error 置空） */
    private AiWorkflowRun finish(AiWorkflowRun run, WorkflowRunCtx ctx, String error) {
        if (error != null) run.setStatus("failed");
        run.setError(error == null ? null : WorkflowRunCtx.abbreviate(error, 1000));
        run.setNodeTraces(JSON.toJSONString(ctx.tracesSnapshot()));
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(System.currentTimeMillis() - ctx.t0);
        runMapper.updateById(run);
        return run;
    }

    /** 某工作流的运行记录（最近 50 条，新→旧）：归属校验后按 started_at 倒序 */
    public List<AiWorkflowRun> listRuns(String workflowId) {
        getOwn(workflowId);
        return runMapper.selectList(new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getWorkflowId, workflowId)
                .orderByDesc(AiWorkflowRun::getStartedAt)
                .last("LIMIT 50"));
    }

    /** 单条运行记录（含完整 trace）：归属两层校验（工作流是本人的 + run 属于该工作流） */
    public AiWorkflowRun getRun(String workflowId, String runId) {
        getOwn(workflowId);
        AiWorkflowRun r = runMapper.selectById(runId);
        if (r == null || !workflowId.equals(r.getWorkflowId())) {
            throw new BizException(404, "运行记录不存在");
        }
        return r;
    }
}
