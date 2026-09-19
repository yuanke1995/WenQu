package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.agents.ToolApproval;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.AuthUtils;
import com.wisesoft.wenqu.common.CanonicalJson;
import com.wisesoft.wenqu.common.CronSchedule;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.ScheduledAgentJob;
import com.wisesoft.wenqu.models.ScheduledAgentRun;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.repositories.ScheduledAgentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 用户 Agent 定时任务的用例、校验和 worker 调度。
 *
 * <p>由参考实现的 services/scheduled_agent_service.py 逐函数翻译；常量、校验分支顺序、
 * 错误文案、幂等键构造与状态机取值均逐字对齐。
 *
 * <h3>平台差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li>{@code croniter.is_valid} / {@code croniter(...).get_next(datetime)} → {@link CronSchedule}
 *       （自实现 5 段 cron，能力差异见该类注解）。</li>
 *   <li>{@code json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False)} →
 *       {@link CanonicalJson#dumps(Object)}；{@code hashlib.sha256(...).hexdigest()} →
 *       {@link AuthUtils#sha256Hex(String)}（小写十六进制，UTF-8 入参）。</li>
 *   <li>{@code datetime.isoformat()}（naive，秒总输出）→ {@link DateTimeUtils#localIsoformat}；
 *       用于 {@code occurrence_key}，避免 Java 的 {@code LocalDateTime.toString()} 省略秒。</li>
 *   <li>{@code pg_manager.get_async_session_context()}（自管会话、异常回滚、正常提交）→
 *       {@link TransactionTemplate}（{@code PROPAGATION_REQUIRES_NEW}）：每个
 *       {@code async with} 块对应一次独立事务执行。</li>
 *   <li>{@code IntegrityError} → {@link DataIntegrityViolationException}（唯一键冲突与
 *       外键冲突同型）。</li>
 *   <li>{@code len(str)} 的"字符数"口径 → {@code codePointCount}（对齐 Python 按码点计数，
 *       非 Java 的 UTF-16 单元数）。</li>
 *   <li>{@code str(HTTPException)} → {@code "<status>: <detail>"}（对齐 Starlette
 *       {@code HTTPException.__str__}；该字符串会落到 {@code error_message}）。</li>
 * </ul>
 *
 * <h3>会话模型的必要替换（{@link #dispatchScheduledRun(String)}）</h3>
 * 参考实现在**同一个会话**里依次完成「锁定触发记录 → 校验 Project/智能体 → 调用
 * {@code submit_agent_request} → 标记 submitted → 提交」。本工程
 * {@link AgentRequestService#submitAgentRequest} 自带 {@code REQUIRES_NEW} 事务，
 * 若在外层事务持有 {@code projects}/{@code users} 行锁时调用，内层新事务会去申请同一把行锁
 * 而互相等待（自死锁）。故按**同一结果的阶段拆分**执行：阶段一（事务）锁定并校验、
 * 阶段二（无外层事务）走统一请求链路、阶段三（事务）标记 submitted 与推进 job.updated_at。
 * 可见结果与参考实现一致；若阶段三提交失败，触发记录仍为 {@code dispatching}，
 * 恢复/重试会借 {@code request_id} 幂等收敛到同一 Request。
 */
@Service
public class ScheduledAgentService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledAgentService.class);

    /** 触发来源标识（参考实现 SCHEDULED_AGENT_SOURCE）。 */
    public static final String SCHEDULED_AGENT_SOURCE = "scheduled_agent";

    /** prompt 长度上限（参考实现 MAX_PROMPT_LENGTH）。 */
    public static final int MAX_PROMPT_LENGTH = 32_000;

    /** name 长度上限（参考实现 MAX_NAME_LENGTH）。 */
    public static final int MAX_NAME_LENGTH = 255;

    /** 稳定请求 ID 的允许字符（参考实现 REQUEST_ID_PATTERN）。 */
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private final ProjectRepository projectRepository;
    private final AgentRepository agentRepository;
    private final ScheduledAgentRepository scheduledAgentRepository;
    private final UserRepository userRepository;
    private final AgentManager agentManager;
    private final AgentRequestService agentRequestService;
    private final TransactionTemplate selfManagedTransactionTemplate;

    public ScheduledAgentService(
            ProjectRepository projectRepository,
            AgentRepository agentRepository,
            ScheduledAgentRepository scheduledAgentRepository,
            UserRepository userRepository,
            AgentManager agentManager,
            AgentRequestService agentRequestService,
            PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.agentRepository = agentRepository;
        this.scheduledAgentRepository = scheduledAgentRepository;
        this.userRepository = userRepository;
        this.agentManager = agentManager;
        this.agentRequestService = agentRequestService;
        this.selfManagedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.selfManagedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // =========================================================================
    // === 纯函数：ID 构造、校验、时间计算
    // =========================================================================

    /** 为调度对象生成稳定、长度受限的 ID（对应 {@code build_request_id}）。 */
    public static String buildRequestId(String prefix, String value) {
        String head = prefix == null ? "" : prefix;
        if (head.length() > 16) {
            head = head.substring(0, 16);
        }
        return head + "-" + AuthUtils.sha256Hex(value).substring(0, 47);
    }

    /** 校验客户端持有的稳定请求 ID（对应 {@code _normalize_request_id}）。 */
    public static String normalizeRequestId(Object value) {
        String requestId = value == null ? "" : String.valueOf(value).strip();
        int length = requestId.codePointCount(0, requestId.length());
        if (length < 8 || length > 64 || !REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            throw ApiHttpException.unprocessable("request_id 必须是 8 到 64 位字母、数字或 ._:-");
        }
        return requestId;
    }

    /** 为规范化创建意图生成稳定摘要（对应 {@code _intent_hash}）。 */
    static String intentHash(Map<String, Object> data) {
        return AuthUtils.sha256Hex(CanonicalJson.dumps(data));
    }

    /**
     * 校验 cron 表达式和 IANA 时区（对应 {@code validate_schedule}）。
     *
     * @return {@code [expression, timezone]}（Java 侧以数组承载参考实现的元组）
     */
    public static String[] validateSchedule(Object cronExpression, Object timezone) {
        String expression = cronExpression == null ? "" : String.valueOf(cronExpression).strip();
        if (expression.isEmpty()) {
            throw ApiHttpException.unprocessable("cron_expression 不能为空");
        }
        if (expression.split("\\s+").length != 5 || !CronSchedule.isValid(expression)) {
            throw ApiHttpException.unprocessable("cron_expression 不是有效的 5 段 cron 表达式");
        }
        String zone = timezone == null ? "" : String.valueOf(timezone).strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException | IllegalArgumentException error) {
            throw ApiHttpException.unprocessable("timezone 必须是有效的 IANA 时区");
        }
        try {
            nextRunAt(expression, zone, DateTimeUtils.utcNowNaive());
        } catch (IllegalStateException | ArithmeticException | DateTimeException | IllegalArgumentException error) {
            throw ApiHttpException.unprocessable("cron_expression 没有可计算的下一次触发时间");
        }
        return new String[] {expression, zone};
    }

    /**
     * 计算下一次 UTC 触发时间，数据库统一保存无时区 UTC（对应 {@code next_run_at}）。
     */
    public static LocalDateTime nextRunAt(String cronExpression, String timezone, LocalDateTime after) {
        return CronSchedule.nextRunAtUtc(cronExpression, timezone, after);
    }

    /** 归一化文本字段（对应 {@code _normalize_text}）。 */
    private static String normalizeText(Object value, String field, int maximum) {
        String normalized = value == null ? "" : String.valueOf(value).strip();
        if (normalized.isEmpty()) {
            throw ApiHttpException.unprocessable(field + " 不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > maximum) {
            throw ApiHttpException.unprocessable(field + " 不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }

    /**
     * 归一化 model_spec：去空白→空串归 null→超长 422
     * （对应参考实现两处 {@code str(data.get("model_spec") or "").strip() or None} + 512 校验）。
     */
    private static String normalizeModelSpec(Object value) {
        String modelSpec = value == null ? "" : String.valueOf(value).strip();
        if (modelSpec.isEmpty()) {
            return null;
        }
        if (modelSpec.length() > 512) {
            throw ApiHttpException.unprocessable("model_spec 不能超过 512 个字符");
        }
        return modelSpec;
    }

    /** 归一化审批模式；非法值转 422（对应 {@code except ValueError as exc: raise HTTPException(422, str(exc))}）。 */
    private static String normalizeToolApprovalMode(Object value) {
        try {
            return ToolApproval.normalizeToolApprovalMode(value);
        } catch (IllegalArgumentException error) {
            throw ApiHttpException.unprocessable(error.getMessage());
        }
    }

    /** Python {@code str(exc)} 的等价描述：HTTPException → {@code "<status>: <detail>"}。 */
    private static String describeError(Exception error) {
        if (error instanceof ApiHttpException httpError) {
            return httpError.getStatus() + ": " + error.getMessage();
        }
        return String.valueOf(error);
    }

    // =========================================================================
    // === 作用域校验
    // =========================================================================

    /** 锁定任务绑定的活动 Project，直到调用方提交事务（对应 {@code _validate_project}）。 */
    private void validateProject(String projectId, User user) {
        if (projectRepository.lockActiveForUser(projectId, String.valueOf(user.getUid())) == null) {
            throw ApiHttpException.notFound("Project 不存在或不可访问");
        }
    }

    /** 校验智能体可见性与后端存在性（对应 {@code _validate_agent}）。 */
    private void validateAgent(String agentSlug, User user) {
        Agent agent = agentRepository.getVisibleBySlug(
                agentSlug, PermissionSubject.of(user), AgentRepository.AgentEntryKind.MAIN);
        if (agent == null) {
            throw ApiHttpException.notFound("智能体不存在或不可访问");
        }
        if (agentManager.getAgent(agent.getBackendId()) == null) {
            throw ApiHttpException.notFound("智能体后端不存在");
        }
    }

    // =========================================================================
    // === 触发记录构造与投影
    // =========================================================================

    /** 从任务快照创建一次触发意图（对应 {@code _new_scheduled_run}）。 */
    private static ScheduledAgentRun newScheduledRun(
            ScheduledAgentJob job,
            String trigger,
            String occurrenceKey,
            LocalDateTime scheduledFor,
            boolean activeRun,
            String identity) {
        String resolvedIdentity = identity == null || identity.isEmpty() ? job.getId() + ":" + occurrenceKey : identity;
        ScheduledAgentRun run = new ScheduledAgentRun();
        run.setId(buildRequestId("scheduled-run", resolvedIdentity));
        run.setJobId(job.getId());
        run.setRequestId(buildRequestId("scheduled-request", resolvedIdentity));
        run.setThreadId(buildRequestId("scheduled-thread", resolvedIdentity));
        run.setTrigger(trigger);
        run.setOccurrenceKey(occurrenceKey);
        run.setScheduledFor(scheduledFor);
        run.setProjectId(job.getProjectId());
        run.setAgentSlug(job.getAgentSlug());
        run.setConversationTitle(job.getName());
        run.setPrompt(job.getPrompt());
        run.setToolApprovalMode(job.getToolApprovalMode());
        run.setModelSpec(job.getModelSpec());
        run.setStatus(activeRun ? "skipped" : "dispatching");
        run.setErrorMessage(activeRun ? "上一次运行尚未结束" : null);
        return run;
    }

    /** 创建包含配置快照且禁止重叠的执行记录（对应 {@code _create_run_record}）。 */
    private ScheduledAgentRun createRunRecord(
            ScheduledAgentRepository repo,
            ScheduledAgentJob job,
            String trigger,
            String occurrenceKey,
            LocalDateTime scheduledFor) {
        boolean activeRun = repo.hasActiveRun(job.getId());
        return repo.addRun(newScheduledRun(job, trigger, occurrenceKey, scheduledFor, activeRun, null));
    }

    /**
     * 以 Request/Run 为执行状态事实源，装配调度记录摘要（对应 {@code _execution_to_dict}）。
     */
    public static Map<String, Object> executionToDict(
            ScheduledAgentRun scheduledRun, Map<String, Object> request, Map<String, Object> run) {
        Map<String, Object> data = scheduledRun.toDict();
        data.put("conversation_available", request != null);
        if (!"submitted".equals(scheduledRun.getStatus()) || request == null) {
            return data;
        }

        data.put("run_id", request.get("dispatched_run_id"));
        if (!"dispatched".equals(request.get("status")) || run == null) {
            data.put("status", request.get("status"));
            data.put("error_message", request.get("error_message"));
            return data;
        }

        data.put("status", run.get("status"));
        data.put("error_message", run.get("error_message"));
        Object finishedAt = run.get("finished_at");
        data.put("completed_at", finishedAt instanceof LocalDateTime ? DateTimeUtils.formatUtcDatetime((LocalDateTime) finishedAt) : null);
        return data;
    }

    // =========================================================================
    // === 用例：列表 / 创建 / 更新 / 删除 / 立即运行
    // =========================================================================

    /** 列出当前用户自己的定时任务（对应 {@code list_scheduled_jobs}）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listScheduledJobs(User user) {
        String uid = String.valueOf(user.getUid());
        List<ScheduledAgentJob> jobs = scheduledAgentRepository.listJobs(uid);
        Map<String, List<Map<String, Object>>> runsByJob = new LinkedHashMap<>();
        List<String> jobIds = new ArrayList<>();
        for (ScheduledAgentJob job : jobs) {
            runsByJob.put(job.getId(), new ArrayList<>());
            jobIds.add(job.getId());
        }
        for (ScheduledAgentRepository.RunWithLinks links : scheduledAgentRepository.listRecentRuns(jobIds, uid, 3)) {
            List<Map<String, Object>> bucket = runsByJob.get(links.run().getJobId());
            if (bucket != null) {
                bucket.add(executionToDict(links.run(), links.request(), links.agentRun()));
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScheduledAgentJob job : jobs) {
            Map<String, Object> item = job.toDict();
            item.put("runs", runsByJob.get(job.getId()));
            result.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobs", result);
        return response;
    }

    /** 按稳定请求 ID 幂等创建用户定时任务（对应 {@code create_scheduled_job}）。 */
    public Map<String, Object> createScheduledJob(User user, Map<String, Object> data) {
        String uid = String.valueOf(user.getUid());
        String requestId = normalizeRequestId(data.get("request_id"));
        String projectId = normalizeText(data.get("project_id"), "project_id", 64);
        String agentSlug = normalizeText(data.get("agent_slug"), "agent_slug", 64);
        String name = normalizeText(data.get("name"), "name", MAX_NAME_LENGTH);
        String prompt = normalizeText(data.get("prompt"), "prompt", MAX_PROMPT_LENGTH);
        String[] schedule = validateSchedule(data.get("cron_expression"), data.get("timezone"));
        String expression = schedule[0];
        String timezone = schedule[1];
        String modelSpec = normalizeModelSpec(data.get("model_spec"));
        String toolApprovalMode = normalizeToolApprovalMode(
                data.containsKey("tool_approval_mode") ? data.get("tool_approval_mode") : "default");
        boolean enabled = AgentRunService.truthy(data.containsKey("enabled") ? data.get("enabled") : Boolean.TRUE);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("project_id", projectId);
        intent.put("agent_slug", agentSlug);
        intent.put("name", name);
        intent.put("prompt", prompt);
        intent.put("tool_approval_mode", toolApprovalMode);
        intent.put("model_spec", modelSpec);
        intent.put("cron_expression", expression);
        intent.put("timezone", timezone);
        intent.put("enabled", enabled);
        String intentDigest = intentHash(intent);

        ScheduledAgentJob existing = scheduledAgentRepository.getJobByCreationRequest(uid, requestId);
        if (existing != null) {
            if (!intentDigest.equals(existing.getCreationIntentHash())) {
                throw ApiHttpException.conflict("request_id 已用于其他定时任务创建意图");
            }
            return existing.toDict();
        }

        LocalDateTime now = DateTimeUtils.utcNowNaive();
        ScheduledAgentJob created = new ScheduledAgentJob();
        created.setId(UUID.randomUUID().toString());
        created.setUid(uid);
        created.setCreationRequestId(requestId);
        created.setCreationIntentHash(intentDigest);
        created.setProjectId(projectId);
        created.setAgentSlug(agentSlug);
        created.setName(name);
        created.setPrompt(prompt);
        created.setToolApprovalMode(toolApprovalMode);
        created.setModelSpec(modelSpec);
        created.setCronExpression(expression);
        created.setTimezone(timezone);
        created.setEnabled(enabled);
        created.setNextRunAt(nextRunAt(expression, timezone, now));
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        ScheduledAgentJob job;
        try {
            selfManagedTransactionTemplate.executeWithoutResult(status -> {
                validateProject(projectId, user);
                validateAgent(agentSlug, user);
                scheduledAgentRepository.addJob(created);
            });
            job = created;
        } catch (DataIntegrityViolationException error) {
            ScheduledAgentJob replay = scheduledAgentRepository.getJobByCreationRequest(uid, requestId);
            if (replay == null) {
                throw ApiHttpException.conflict("定时任务创建冲突");
            }
            if (!intentDigest.equals(replay.getCreationIntentHash())) {
                throw ApiHttpException.conflict("request_id 已用于其他定时任务创建意图");
            }
            job = replay;
        }
        return job.toDict();
    }

    /**
     * 更新当前用户拥有的任务；修改计划时从当前时刻重新计算下一次触发
     * （对应 {@code update_scheduled_job}）。
     *
     * @return 更新后的字典；任务不存在返回 null
     */
    public Map<String, Object> updateScheduledJob(String jobId, User user, Map<String, Object> data) {
        String uid = String.valueOf(user.getUid());
        return selfManagedTransactionTemplate.execute(status -> {
            ScheduledAgentJob job = scheduledAgentRepository.getJob(jobId, uid, true, false);
            if (job == null) {
                return null;
            }
            if (data.containsKey("project_id")) {
                String projectId = normalizeText(data.get("project_id"), "project_id", 64);
                validateProject(projectId, user);
                job.setProjectId(projectId);
            }
            if (data.containsKey("agent_slug")) {
                String agentSlug = normalizeText(data.get("agent_slug"), "agent_slug", 64);
                validateAgent(agentSlug, user);
                job.setAgentSlug(agentSlug);
            }
            if (data.containsKey("name")) {
                job.setName(normalizeText(data.get("name"), "name", MAX_NAME_LENGTH));
            }
            if (data.containsKey("prompt")) {
                job.setPrompt(normalizeText(data.get("prompt"), "prompt", MAX_PROMPT_LENGTH));
            }
            if (data.containsKey("tool_approval_mode")) {
                job.setToolApprovalMode(normalizeToolApprovalMode(data.get("tool_approval_mode")));
            }
            if (data.containsKey("model_spec")) {
                job.setModelSpec(normalizeModelSpec(data.get("model_spec")));
            }
            Object expressionRaw = data.containsKey("cron_expression") ? data.get("cron_expression") : job.getCronExpression();
            Object timezoneRaw = data.containsKey("timezone") ? data.get("timezone") : job.getTimezone();
            String[] schedule = validateSchedule(expressionRaw, timezoneRaw);
            String expression = schedule[0];
            String timezone = schedule[1];
            if (!expression.equals(job.getCronExpression()) || !timezone.equals(job.getTimezone())) {
                job.setNextRunAt(nextRunAt(expression, timezone, DateTimeUtils.utcNowNaive()));
            }
            job.setCronExpression(expression);
            job.setTimezone(timezone);
            LocalDateTime now = DateTimeUtils.utcNowNaive();
            if (data.containsKey("enabled")) {
                boolean enabled = AgentRunService.truthy(data.get("enabled"));
                if (enabled && !Boolean.TRUE.equals(job.getEnabled())) {
                    job.setNextRunAt(nextRunAt(expression, timezone, now));
                }
                job.setEnabled(enabled);
            }
            job.setUpdatedAt(now);
            scheduledAgentRepository.updateJob(job);
            return job.toDict();
        });
    }

    /** 软删除任务定义，保留触发记录与 AgentRun（对应 {@code delete_scheduled_job}）。 */
    public boolean deleteScheduledJob(String jobId, User user) {
        Boolean deleted = selfManagedTransactionTemplate.execute(status -> {
            ScheduledAgentJob job =
                    scheduledAgentRepository.getJob(jobId, String.valueOf(user.getUid()), true, false);
            if (job == null) {
                return false;
            }
            scheduledAgentRepository.deleteJob(job);
            return true;
        });
        return Boolean.TRUE.equals(deleted);
    }

    /**
     * 按稳定请求 ID 幂等创建手动触发记录（对应 {@code run_scheduled_job_now}）。
     *
     * @return 触发记录投影；任务不存在返回 null
     */
    public Map<String, Object> runScheduledJobNow(String jobId, Object requestIdValue, User user) {
        String uid = String.valueOf(user.getUid());
        String requestId = normalizeRequestId(requestIdValue);
        String identity = uid + ":manual:" + requestId;
        String runId = buildRequestId("scheduled-run", identity);
        ScheduledAgentRun run;
        try {
            run = selfManagedTransactionTemplate.execute(status -> {
                ScheduledAgentJob job = scheduledAgentRepository.getJob(jobId, uid, true, false);
                if (job == null) {
                    return null;
                }
                ScheduledAgentRun existing = scheduledAgentRepository.getRun(runId);
                if (existing != null) {
                    if (!existing.getJobId().equals(job.getId())) {
                        throw ApiHttpException.conflict("request_id 已用于其他立即运行意图");
                    }
                    return existing;
                }
                validateProject(job.getProjectId(), user);
                validateAgent(job.getAgentSlug(), user);
                LocalDateTime now = DateTimeUtils.utcNowNaive();
                return scheduledAgentRepository.addRun(newScheduledRun(
                        job,
                        "manual",
                        "manual:" + requestId,
                        now,
                        scheduledAgentRepository.hasActiveRun(job.getId()),
                        identity));
            });
        } catch (DataIntegrityViolationException error) {
            ScheduledAgentRun replay = scheduledAgentRepository.getRun(runId);
            if (replay == null) {
                throw ApiHttpException.conflict("立即运行请求冲突");
            }
            ScheduledAgentJob job = scheduledAgentRepository.getJob(jobId, uid, false, false);
            if (job == null || !replay.getJobId().equals(job.getId())) {
                throw ApiHttpException.conflict("request_id 已用于其他立即运行意图");
            }
            run = replay;
        }
        if (run == null) {
            return null;
        }
        if (!"dispatching".equals(run.getStatus())) {
            return run.toDict();
        }
        return dispatchScheduledRun(run.getId());
    }

    // =========================================================================
    // === 派发
    // =========================================================================

    /** 派发阶段一的结果：要么短路返回，要么带上继续派发所需快照。 */
    private record DispatchPlan(
            ScheduledAgentRun run, ScheduledAgentJob job, User user, Map<String, Object> shortCircuit) {}

    /** 将持久触发意图幂等提交到统一 AgentRun 链路（对应 {@code dispatch_scheduled_run}）。 */
    public Map<String, Object> dispatchScheduledRun(String scheduledRunId) {
        try {
            DispatchPlan plan = selfManagedTransactionTemplate.execute(status -> {
                ScheduledAgentRun scheduledRun = scheduledAgentRepository.lockRun(scheduledRunId);
                if (scheduledRun == null) {
                    return null;
                }
                if (!"dispatching".equals(scheduledRun.getStatus())) {
                    return new DispatchPlan(scheduledRun, null, null, scheduledRun.toDict());
                }
                ScheduledAgentJob job = scheduledAgentRepository.getJobById(scheduledRun.getJobId());
                User user = job == null ? null : userRepository.lockActiveByUid(job.getUid());
                if (job == null || user == null) {
                    scheduledRun.setStatus("cancelled");
                    scheduledRun.setErrorMessage("任务已删除、停用或用户不存在");
                    scheduledAgentRepository.updateRun(scheduledRun);
                    return new DispatchPlan(scheduledRun, null, null, scheduledRun.toDict());
                }
                if ("scheduled".equals(scheduledRun.getTrigger())
                        && (!Boolean.TRUE.equals(job.getEnabled()) || job.getDeletedAt() != null)) {
                    scheduledRun.setStatus("cancelled");
                    scheduledRun.setErrorMessage("任务已停用或删除");
                    scheduledAgentRepository.updateRun(scheduledRun);
                    return new DispatchPlan(scheduledRun, null, null, scheduledRun.toDict());
                }
                validateProject(scheduledRun.getProjectId(), user);
                validateAgent(scheduledRun.getAgentSlug(), user);
                return new DispatchPlan(scheduledRun, job, user, null);
            });
            if (plan == null) {
                return null;
            }
            if (plan.shortCircuit() != null) {
                return plan.shortCircuit();
            }

            agentRequestService.submitAgentRequest(buildScheduledRequestInput(plan.run(), plan.job()), plan.user());

            selfManagedTransactionTemplate.executeWithoutResult(status -> {
                ScheduledAgentRun latest = scheduledAgentRepository.lockRun(scheduledRunId);
                if (latest != null) {
                    latest.setStatus("submitted");
                    scheduledAgentRepository.updateRun(latest);
                }
                ScheduledAgentJob latestJob = scheduledAgentRepository.getJobById(plan.job().getId());
                if (latestJob != null) {
                    latestJob.setUpdatedAt(DateTimeUtils.utcNowNaive());
                    scheduledAgentRepository.updateJob(latestJob);
                }
            });

            ScheduledAgentRun settledRun = scheduledAgentRepository.getRun(scheduledRunId);
            if (settledRun == null) {
                return plan.run().toDict();
            }
            ScheduledAgentRepository.RunWithLinks links =
                    scheduledAgentRepository.getRequestAndRun(settledRun.getRequestId());
            return executionToDict(settledRun, links.request(), links.agentRun());
        } catch (ApiHttpException error) {
            Map<String, Object> settled = settleDispatchError(scheduledRunId, error, true);
            if (settled == null) {
                throw error;
            }
            return settled;
        } catch (RuntimeException error) {
            Map<String, Object> settled = settleDispatchError(scheduledRunId, error, false);
            if (settled != null && "submitted".equals(settled.get("status"))) {
                return settled;
            }
            throw error;
        }
    }

    /** 构造统一的 Agent 请求入口输入（对应 {@code submit_agent_request(request_input=…)} 实参块）。 */
    private static AgentRequestService.AgentRequestInput buildScheduledRequestInput(
            ScheduledAgentRun run, ScheduledAgentJob job) {
        Map<String, Object> originMetadata = new LinkedHashMap<>();
        originMetadata.put("scheduled_job_id", job.getId());
        originMetadata.put("scheduled_run_id", run.getId());
        Map<String, Object> requestMetadata = new LinkedHashMap<>();
        requestMetadata.put("scheduled_job_id", job.getId());
        requestMetadata.put("scheduled_run_id", run.getId());
        return new AgentRequestService.AgentRequestInput(
                run.getAgentSlug(),
                run.getThreadId(),
                run.getRequestId(),
                InputMessageService.buildChatInputMessage(run.getPrompt(), null),
                new AgentRequestService.RunOrigin(SCHEDULED_AGENT_SOURCE, "worker", run.getId(), originMetadata),
                requestMetadata,
                run.getModelSpec(),
                run.getToolApprovalMode(),
                "enqueue",
                true,
                run.getConversationTitle(),
                run.getProjectId());
    }

    /**
     * 串行重查 Request；仅明确不可重试错误终结触发记录
     * （对应 {@code _settle_dispatch_error}）。
     *
     * @return 触发记录投影；触发记录不存在返回 null
     */
    private Map<String, Object> settleDispatchError(String scheduledRunId, Exception error, boolean terminal) {
        return selfManagedTransactionTemplate.execute(status -> {
            ScheduledAgentRun scheduledRun = scheduledAgentRepository.lockRun(scheduledRunId);
            if (scheduledRun == null) {
                return null;
            }
            Map<String, Object> request = null;
            Map<String, Object> run = null;
            if ("dispatching".equals(scheduledRun.getStatus())) {
                ScheduledAgentRepository.RunWithLinks links =
                        scheduledAgentRepository.getRequestAndRun(scheduledRun.getRequestId());
                request = links.request();
                run = links.agentRun();
                if (request != null) {
                    scheduledRun.setStatus("submitted");
                } else if (terminal) {
                    scheduledRun.setStatus("failed");
                    scheduledRun.setErrorMessage(describeError(error));
                }
                if (request != null || terminal) {
                    scheduledAgentRepository.updateRun(scheduledRun);
                }
            }
            return executionToDict(scheduledRun, request, run);
        });
    }

    // =========================================================================
    // === worker 入口
    // =========================================================================

    /** 恢复 worker 中断后遗留的定时触发意图（对应 {@code recover_scheduled_dispatches}）。 */
    public int recoverScheduledDispatches(int limit) {
        List<ScheduledAgentRun> records = selfManagedTransactionTemplate.execute(status ->
                scheduledAgentRepository.listDispatchingRuns(DateTimeUtils.utcNowNaive().minusSeconds(30), limit));
        int recovered = 0;
        for (ScheduledAgentRun record : records == null ? List.<ScheduledAgentRun>of() : records) {
            try {
                dispatchScheduledRun(record.getId());
                recovered += 1;
            } catch (RuntimeException error) {
                log.error("恢复定时任务触发失败: scheduled_run=" + record.getId(), error);
            }
        }
        return recovered;
    }

    /**
     * 在一个事务中领取到期任务、推进计划并创建触发意图
     * （对应 {@code _claim_due_run}）。
     *
     * <p>必须由事务模板调用：{@code claimDueJob} 的 {@code FOR UPDATE SKIP LOCKED} 依赖
     * 同一事务内的行锁，且循环内先写回 {@code next_run_at} 才能推进到下一个任务。
     *
     * @return 新建的触发意图；没有到期任务返回 null
     */
    ScheduledAgentRun claimDueRun(LocalDateTime now) {
        while (true) {
            ScheduledAgentJob job = scheduledAgentRepository.claimDueJob(now);
            if (job == null) {
                return null;
            }
            LocalDateTime scheduledFor = job.getNextRunAt();
            try {
                job.setNextRunAt(nextRunAt(job.getCronExpression(), job.getTimezone(), now));
            } catch (IllegalStateException | ArithmeticException | DateTimeException | IllegalArgumentException error) {
                job.setEnabled(false);
                job.setUpdatedAt(now);
                scheduledAgentRepository.updateJob(job);
                log.error("已停用无法计算下次触发时间的定时任务: scheduled_job=" + job.getId(), error);
                continue;
            }
            job.setUpdatedAt(now);
            scheduledAgentRepository.updateJob(job);
            return createRunRecord(
                    scheduledAgentRepository,
                    job,
                    "scheduled",
                    "scheduled:" + DateTimeUtils.localIsoformat(scheduledFor),
                    scheduledFor);
        }
    }

    /** 批量领取到期任务并提交对应 AgentRun（对应 {@code claim_and_dispatch_due_jobs}）。 */
    public int claimAndDispatchDueJobs(int limit) {
        int count = 0;
        for (int index = 0; index < Math.max(0, limit); index++) {
            ScheduledAgentRun run = selfManagedTransactionTemplate.execute(status ->
                    claimDueRun(DateTimeUtils.utcNowNaive()));
            if (run == null) {
                break;
            }
            String runId = run.getId();
            if ("dispatching".equals(run.getStatus())) {
                try {
                    dispatchScheduledRun(runId);
                } catch (RuntimeException error) {
                    log.error("提交到期定时任务失败: scheduled_run=" + runId, error);
                }
            }
            count += 1;
        }
        return count;
    }
}
