package com.wisesoft.wenqu.repositories;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.ScheduledAgentJob;
import com.wisesoft.wenqu.models.ScheduledAgentRun;
import com.wisesoft.wenqu.repository.port.ScheduledAgentJobMapper;
import com.wisesoft.wenqu.repository.port.ScheduledAgentRunMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户定时任务仓储。
 *
 * <p>由参考实现的 repositories/scheduled_agent_repository.py 逐方法翻译：任务列表与读取
 * （可含已软删除、可加锁）、按创建幂等键读取、按用户作用域批量读取每个任务最近的触发记录
 * 及其 Request/Run、认领到期任务（只认领活动用户的）、按统一 Request/Run 事实判断
 * 是否已有非终态执行、以及软删除任务（保留执行记录）。
 *
 * <p>必要替换：
 * <ul>
 *   <li>多表联查（触发记录 + 请求 + 运行、以及认领时联 users 校验活动用户）用原生 SQL；
 *       "每个任务最近 N 条"用窗口函数实现（MySQL 8.0+ 与参考实现同构）。
 *   <li>{@code with_for_update(skip_locked=True)} → {@code FOR UPDATE SKIP LOCKED}。
 *   <li>{@code self.db.get(Model, id)} → 主键查询。
 *   <li>置空字段（软删除）用显式 set。
 * </ul>
 */
@Repository
public class ScheduledAgentRepository {

    private final ScheduledAgentJobMapper jobMapper;
    private final ScheduledAgentRunMapper runMapper;
    private final JdbcTemplate jdbc;

    public ScheduledAgentRepository(
            ScheduledAgentJobMapper jobMapper, ScheduledAgentRunMapper runMapper, JdbcTemplate jdbc) {
        this.jobMapper = jobMapper;
        this.runMapper = runMapper;
        this.jdbc = jdbc;
    }

    /** 一次触发记录及其对应的请求与运行（三者都可能为空，与参考实现的联查一致）。 */
    public record RunWithLinks(ScheduledAgentRun run, Map<String, Object> request, Map<String, Object> agentRun) {}

    public List<ScheduledAgentJob> listJobs(String uid) {
        return jobMapper.selectList(
                new LambdaQueryWrapper<ScheduledAgentJob>()
                        .eq(ScheduledAgentJob::getUid, String.valueOf(uid))
                        .isNull(ScheduledAgentJob::getDeletedAt)
                        .orderByDesc(ScheduledAgentJob::getCreatedAt)
                        .orderByDesc(ScheduledAgentJob::getId));
    }

    /** 读取任务（可选加锁、可选包含已软删除）。 */
    @Transactional
    public ScheduledAgentJob getJob(String jobId, String uid, boolean lock, boolean includeDeleted) {
        LambdaQueryWrapper<ScheduledAgentJob> wrapper =
                new LambdaQueryWrapper<ScheduledAgentJob>()
                        .eq(ScheduledAgentJob::getId, jobId)
                        .eq(ScheduledAgentJob::getUid, String.valueOf(uid));
        if (!includeDeleted) {
            wrapper.isNull(ScheduledAgentJob::getDeletedAt);
        }
        if (lock) {
            wrapper.last("FOR UPDATE");
        }
        return jobMapper.selectOne(wrapper);
    }

    /** 按用户作用域读取幂等创建结果，包括已软删除任务。 */
    public ScheduledAgentJob getJobByCreationRequest(String uid, String requestId) {
        return jobMapper.selectOne(
                new LambdaQueryWrapper<ScheduledAgentJob>()
                        .eq(ScheduledAgentJob::getUid, String.valueOf(uid))
                        .eq(ScheduledAgentJob::getCreationRequestId, requestId));
    }

    /** 新增任务。 */
    @Transactional
    public ScheduledAgentJob addJob(ScheduledAgentJob job) {
        if (job.getCreatedAt() == null) {
            job.setCreatedAt(DateTimeUtils.utcNowNaive());
        }
        if (job.getUpdatedAt() == null) {
            job.setUpdatedAt(DateTimeUtils.utcNowNaive());
        }
        jobMapper.insert(job);
        return job;
    }

    /** 批量读取每个任务最近的触发记录及其 Request/Run。 */
    public List<RunWithLinks> listRecentRuns(List<String> jobIds, String uid, int limitPerJob) {
        List<RunWithLinks> result = new ArrayList<>();
        if (jobIds == null || jobIds.isEmpty()) {
            return result;
        }
        List<Object> args = new ArrayList<>();
        args.addAll(jobIds);
        args.add(limitPerJob);
        args.add(String.valueOf(uid));
        args.addAll(jobIds);
        String sql =
                "SELECT sr.*, rq.id AS rq_id, rq.status AS rq_status, rq.dispatched_run_id AS rq_dispatched_run_id, "
                        + "rq.error_message AS rq_error_message, ar.id AS ar_id, ar.status AS ar_status, "
                        + "ar.error_message AS ar_error_message, ar.finished_at AS ar_finished_at FROM ("
                        + "SELECT id AS scheduled_run_id, ROW_NUMBER() OVER ("
                        + "PARTITION BY job_id ORDER BY scheduled_for DESC, id DESC) AS position "
                        + "FROM scheduled_agent_runs WHERE job_id IN ("
                        + placeholders(jobIds.size())
                        + ")) ranked "
                        + "JOIN scheduled_agent_runs sr ON sr.id = ranked.scheduled_run_id "
                        + "JOIN scheduled_agent_jobs j ON j.id = sr.job_id "
                        + "LEFT JOIN agent_run_requests rq ON rq.request_id = sr.request_id "
                        + "LEFT JOIN agent_runs ar ON ar.id = rq.dispatched_run_id "
                        + "WHERE ranked.position <= ? AND j.uid = ? AND sr.job_id IN ("
                        + placeholders(jobIds.size())
                        + ") ORDER BY sr.job_id ASC, sr.scheduled_for DESC, sr.id DESC";
        for (Map<String, Object> row : jdbc.queryForList(sql, args.toArray())) {
            ScheduledAgentRun run = new ScheduledAgentRun();
            run.setId(RepoValues.asString(row.get("id")));
            run.setJobId(RepoValues.asString(row.get("job_id")));
            run.setRequestId(RepoValues.asString(row.get("request_id")));
            run.setThreadId(RepoValues.asString(row.get("thread_id")));
            run.setTrigger(RepoValues.asString(row.get("trigger")));
            run.setOccurrenceKey(RepoValues.asString(row.get("occurrence_key")));
            run.setScheduledFor(RepoValues.toLocalDateTime(row.get("scheduled_for")));
            run.setProjectId(RepoValues.asString(row.get("project_id")));
            run.setAgentSlug(RepoValues.asString(row.get("agent_slug")));
            run.setConversationTitle(RepoValues.asString(row.get("conversation_title")));
            run.setPrompt(RepoValues.asString(row.get("prompt")));
            run.setToolApprovalMode(RepoValues.asString(row.get("tool_approval_mode")));
            run.setModelSpec(RepoValues.asString(row.get("model_spec")));
            run.setStatus(RepoValues.asString(row.get("status")));
            run.setErrorMessage(RepoValues.asString(row.get("error_message")));
            run.setCreatedAt(RepoValues.toLocalDateTime(row.get("created_at")));
            Map<String, Object> request = null;
            if (row.get("rq_id") != null) {
                request = new LinkedHashMap<>();
                request.put("id", row.get("rq_id"));
                request.put("status", row.get("rq_status"));
                request.put("dispatched_run_id", row.get("rq_dispatched_run_id"));
                request.put("error_message", row.get("rq_error_message"));
            }
            Map<String, Object> agentRun = null;
            if (row.get("ar_id") != null) {
                agentRun = new LinkedHashMap<>();
                agentRun.put("id", row.get("ar_id"));
                agentRun.put("status", row.get("ar_status"));
                agentRun.put("error_message", row.get("ar_error_message"));
                agentRun.put("finished_at", RepoValues.toLocalDateTime(row.get("ar_finished_at")));
            }
            result.add(new RunWithLinks(run, request, agentRun));
        }
        return result;
    }

    /** 按稳定 ID 读取一次触发意图。 */
    public ScheduledAgentRun getRun(String runId) {
        return runMapper.selectById(runId);
    }

    /** 按主键读取任务（对应参考实现 {@code db.get(ScheduledAgentJob, job_id)}）。 */
    public ScheduledAgentJob getJobById(String jobId) {
        return jobMapper.selectById(jobId);
    }

    /** 读取触发记录对应的统一 Request/Run。 */
    public RunWithLinks getRequestAndRun(String requestId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT rq.id AS rq_id, rq.status AS rq_status, rq.dispatched_run_id AS rq_dispatched_run_id, "
                                + "rq.error_message AS rq_error_message, ar.id AS ar_id, ar.status AS ar_status, "
                                + "ar.error_message AS ar_error_message, ar.finished_at AS ar_finished_at "
                                + "FROM agent_run_requests rq "
                                + "LEFT JOIN agent_runs ar ON ar.id = rq.dispatched_run_id WHERE rq.request_id = ?",
                        requestId);
        if (rows.isEmpty()) {
            return new RunWithLinks(null, null, null);
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", row.get("rq_id"));
        request.put("status", row.get("rq_status"));
        request.put("dispatched_run_id", row.get("rq_dispatched_run_id"));
        request.put("error_message", row.get("rq_error_message"));
        Map<String, Object> agentRun = null;
        if (row.get("ar_id") != null) {
            agentRun = new LinkedHashMap<>();
            agentRun.put("id", row.get("ar_id"));
            agentRun.put("status", row.get("ar_status"));
            agentRun.put("error_message", row.get("ar_error_message"));
            agentRun.put("finished_at", RepoValues.toLocalDateTime(row.get("ar_finished_at")));
        }
        return new RunWithLinks(null, request, agentRun);
    }

    /** 锁定活动用户的一个到期任务；触发事实由调用方在同一事务内创建。 */
    @Transactional
    public ScheduledAgentJob claimDueJob(LocalDateTime now) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT j.id FROM scheduled_agent_jobs j JOIN users u ON u.uid = j.uid "
                                + "WHERE u.is_deleted = 0 AND j.enabled = 1 AND j.deleted_at IS NULL "
                                + "AND j.next_run_at <= ? ORDER BY j.next_run_at ASC, j.id ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
                        now);
        if (rows.isEmpty()) {
            return null;
        }
        return jobMapper.selectById(RepoValues.asString(rows.get(0).get("id")));
    }

    /** 按统一 Request/Run 事实判断任务是否已有非终态执行。 */
    public boolean hasActiveRun(String jobId) {
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT sr.id FROM scheduled_agent_runs sr "
                                + "LEFT JOIN agent_run_requests rq ON rq.request_id = sr.request_id "
                                + "LEFT JOIN agent_runs ar ON ar.id = rq.dispatched_run_id "
                                + "WHERE sr.job_id = ? AND (sr.status = 'dispatching' OR (sr.status = 'submitted' AND ("
                                + "rq.id IS NULL OR rq.status = 'queued' OR (rq.status = 'dispatched' AND ("
                                + "ar.id IS NULL OR ar.status NOT IN ('completed', 'failed', 'cancelled', 'interrupted')))))) "
                                + "LIMIT 1) pending",
                        Long.class,
                        jobId);
        return count != null && count > 0;
    }

    /** 新增执行记录。 */
    @Transactional
    public ScheduledAgentRun addRun(ScheduledAgentRun run) {
        if (run.getCreatedAt() == null) {
            run.setCreatedAt(DateTimeUtils.utcNowNaive());
        }
        runMapper.insert(run);
        return run;
    }

    /**
     * 按稳定 ID 加锁读取一次触发意图
     * （对应参考实现 {@code select(ScheduledAgentRun).where(id==…).with_for_update()}）。
     */
    @Transactional
    public ScheduledAgentRun lockRun(String runId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<ScheduledAgentRun>().eq(ScheduledAgentRun::getId, runId).last("FOR UPDATE"));
    }

    /**
     * 就地写回任务的计划相关字段（对应参考实现"改 ORM 属性 + commit"）。
     *
     * <p>用显式 {@code SET}：{@code deleted_at} 等列本身可空，走 {@code updateById} 的 NOT_NULL
     * 策略会跳过本应写入的 null（本项目统一口径）。
     */
    @Transactional
    public void updateJob(ScheduledAgentJob job) {
        jobMapper.update(
                null,
                new LambdaUpdateWrapper<ScheduledAgentJob>()
                        .eq(ScheduledAgentJob::getId, job.getId())
                        .set(ScheduledAgentJob::getProjectId, job.getProjectId())
                        .set(ScheduledAgentJob::getAgentSlug, job.getAgentSlug())
                        .set(ScheduledAgentJob::getName, job.getName())
                        .set(ScheduledAgentJob::getPrompt, job.getPrompt())
                        .set(ScheduledAgentJob::getToolApprovalMode, job.getToolApprovalMode())
                        .set(ScheduledAgentJob::getModelSpec, job.getModelSpec())
                        .set(ScheduledAgentJob::getCronExpression, job.getCronExpression())
                        .set(ScheduledAgentJob::getTimezone, job.getTimezone())
                        .set(ScheduledAgentJob::getEnabled, job.getEnabled())
                        .set(ScheduledAgentJob::getNextRunAt, job.getNextRunAt())
                        .set(ScheduledAgentJob::getDeletedAt, job.getDeletedAt())
                        .set(ScheduledAgentJob::getUpdatedAt, job.getUpdatedAt()));
    }

    /** 就地写回触发记录的状态字段（对应参考实现"改 ORM 属性 + commit"）。 */
    @Transactional
    public void updateRun(ScheduledAgentRun run) {
        runMapper.update(
                null,
                new LambdaUpdateWrapper<ScheduledAgentRun>()
                        .eq(ScheduledAgentRun::getId, run.getId())
                        .set(ScheduledAgentRun::getStatus, run.getStatus())
                        .set(ScheduledAgentRun::getErrorMessage, run.getErrorMessage()));
    }

    /** 读取仍处于派发中且创建时间早于给定时刻的记录。 */
    public List<ScheduledAgentRun> listDispatchingRuns(LocalDateTime before, int limit) {
        return runMapper.selectList(
                new LambdaQueryWrapper<ScheduledAgentRun>()
                        .eq(ScheduledAgentRun::getStatus, "dispatching")
                        .le(ScheduledAgentRun::getCreatedAt, before)
                        .orderByAsc(ScheduledAgentRun::getCreatedAt)
                        .orderByAsc(ScheduledAgentRun::getId)
                        .last("LIMIT " + limit));
    }

    /** 软删除任务，保留执行记录。 */
    @Transactional
    public void deleteJob(ScheduledAgentJob job) {
        LocalDateTime deletedAt = DateTimeUtils.utcNowNaive();
        jobMapper.update(
                null,
                new LambdaUpdateWrapper<ScheduledAgentJob>()
                        .eq(ScheduledAgentJob::getId, job.getId())
                        .set(ScheduledAgentJob::getEnabled, false)
                        .set(ScheduledAgentJob::getDeletedAt, deletedAt)
                        .set(ScheduledAgentJob::getUpdatedAt, deletedAt));
        job.setEnabled(false);
        job.setDeletedAt(deletedAt);
        job.setUpdatedAt(deletedAt);
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }
}
