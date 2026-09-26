package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.AgentMapper;
import com.wisesoft.ai.mapper.ScheduledJobMapper;
import com.wisesoft.ai.mapper.ScheduledRunMapper;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.ScheduledJob;
import com.wisesoft.ai.model.ScheduledRun;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时执行智能体：用户自建的计划任务，到点后用「智能体 + 提示词」跑一次完整问答。
 * <p>
 * <b>关键实现选择</b>：执行时复用 {@link RagService#chat} 这条**网页问答同款流水线**，
 * 用 {@link CollectingSseEmitter} 把 SSE 事件收进内存（没有浏览器连着的场景），而不是另写
 * 一条"非流式问答"——后者必然慢慢与主链路出现行为差异（引用编号、配图、产物、深度思考、
 * 工具调用、子代理编排），这正是要避免的。因此需要一次问答的其它场景（评估回放、对外 API）
 * 也可以照这个模式复用。
 * <p>
 * <b>与平台版的差异</b>（照搬语义、按本工程改造，不是逐字对齐）：
 * <ul>
 *   <li>去掉 project（项目）依赖——本工程没有项目概念；</li>
 *   <li>去掉统一请求队列 + worker 派发，改为直接调问答链路（本工程没有那套队列）；</li>
 *   <li>去掉 tool_approval_mode（本工程没有工具审批机制）；</li>
 *   <li>cron 用 Spring 自带的 {@link CronExpression}（5 段），不引 croniter 依赖；</li>
 *   <li>结果会话**一个任务一个**（历史执行追加在里面），平台版是每轮新建会话——后者会把
 *       会话列表刷满，而"这个任务历史都跑了什么"恰恰需要连起来看。</li>
 * </ul>
 * 调度：由 {@link com.wisesoft.ai.schedule.ScheduleCenter} 的节拍任务调用 {@link #tick()}；
 * 扫描时先推进 {@code next_run_at} 再执行 ⇒ 单实例下天然防同一轮重复触发。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ScheduledJobService {

    /** 缺省时区（cron 按它解释） */
    private static final String DEFAULT_ZONE = "Asia/Shanghai";
    /** 名称/提示词长度上限（与表列宽一致） */
    private static final int MAX_NAME = 100;
    private static final int MAX_PROMPT = 2000;
    /** 单次扫描最多触发的任务数（防一次积压把线程池打满） */
    private static final int SCAN_LIMIT = 20;
    /** run.answer 摘录长度（列表预览用） */
    private static final int ANSWER_PREVIEW = 1000;
    /**
     * Spring 的 {@link CronExpression} **只接受 6 段**（含秒），而用户与平台版口径都是标准 5 段
     * （分 时 日 月 周）⇒ 解析前补一个秒位。冒烟实测：直接喂 5 段会抛
     * "Cron expression must consist of 6 fields"。
     */
    private static final String SPRING_SECONDS_PREFIX = "0 ";

    private final ScheduledJobMapper jobMapper;
    private final ScheduledRunMapper runMapper;
    private final ConfigService configService;
    private final RagService ragService;
    private final SessionService sessionService;
    private final AgentMapper agentMapper;

    /** 正在执行的任务 id（防同一任务上轮未跑完又被触发；单实例内有效） */
    private final Set<String> runningJobs = ConcurrentHashMap.newKeySet();

    public ScheduledJobService(ScheduledJobMapper jobMapper, ScheduledRunMapper runMapper,
                               ConfigService configService, RagService ragService,
                               SessionService sessionService, AgentMapper agentMapper) {
        this.jobMapper = jobMapper;
        this.runMapper = runMapper;
        this.configService = configService;
        this.ragService = ragService;
        this.sessionService = sessionService;
        this.agentMapper = agentMapper;
    }

    // ==================== CRUD ====================

    /** 我的任务列表（时间倒序），附最近一次执行状态 */
    public List<Map<String, Object>> list(String uid) {
        List<ScheduledJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<ScheduledJob>()
                .eq(ScheduledJob::getUid, uid)
                .eq(ScheduledJob::getDeleted, 0)
                .orderByDesc(ScheduledJob::getCreateTime));
        List<Map<String, Object>> out = new ArrayList<>(jobs.size());
        for (ScheduledJob job : jobs) {
            Map<String, Object> m = toDto(job);
            ScheduledRun last = lastRun(job.getId());
            m.put("lastRun", last == null ? null : runDto(last));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> detail(String uid, String id) {
        return toDto(mustOwn(uid, id));
    }

    public Map<String, Object> create(String uid, Map<String, Object> body) {
        int max = configService.getInt("scheduled.maxPerUser", 20);
        long mine = jobMapper.selectCount(new LambdaQueryWrapper<ScheduledJob>()
                .eq(ScheduledJob::getUid, uid).eq(ScheduledJob::getDeleted, 0));
        if (max > 0 && mine >= max) {
            throw new BizException("定时任务数量已达上限（" + max + " 个），请先删除不用的任务");
        }
        ScheduledJob job = new ScheduledJob();
        job.setUid(uid);
        job.setDeleted(0);
        applyBody(job, body, true);
        job.setNextRunAt(Integer.valueOf(1).equals(job.getEnabled())
                ? nextRunAt(job.getCron(), job.getTimezone(), LocalDateTime.now()) : null);
        jobMapper.insert(job);
        log.info("[SCHEDULED] uid={} 新建定时任务 {}（{}，下次 {}）", uid, job.getName(), job.getCron(), job.getNextRunAt());
        return toDto(job);
    }

    public Map<String, Object> update(String uid, String id, Map<String, Object> body) {
        ScheduledJob job = mustOwn(uid, id);
        applyBody(job, body, false);
        // 改了 cron/时区/启停都要重算下次执行时刻（否则会按旧节奏跑，用户改完看不到变化）
        job.setNextRunAt(Integer.valueOf(1).equals(job.getEnabled())
                ? nextRunAt(job.getCron(), job.getTimezone(), LocalDateTime.now()) : null);
        jobMapper.updateById(job);
        return toDto(job);
    }

    public boolean delete(String uid, String id) {
        ScheduledJob job = mustOwn(uid, id);
        job.setDeleted(1);
        job.setEnabled(0);
        job.setNextRunAt(null);
        jobMapper.updateById(job);
        log.info("[SCHEDULED] uid={} 删除定时任务 {}", uid, job.getName());
        return true;
    }

    /** 启停：启用时重算下次执行时刻（从"现在"往后找，不会因为停机期间错过而立刻补跑） */
    public Map<String, Object> toggle(String uid, String id, boolean enabled) {
        ScheduledJob job = mustOwn(uid, id);
        job.setEnabled(enabled ? 1 : 0);
        job.setNextRunAt(enabled ? nextRunAt(job.getCron(), job.getTimezone(), LocalDateTime.now()) : null);
        jobMapper.updateById(job);
        return toDto(job);
    }

    /** 某任务的执行历史（最近 limit 条） */
    public List<Map<String, Object>> runs(String uid, String jobId, int limit) {
        mustOwn(uid, jobId);
        int n = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        return runMapper.selectList(new LambdaQueryWrapper<ScheduledRun>()
                        .eq(ScheduledRun::getJobId, jobId)
                        .orderByDesc(ScheduledRun::getCreateTime)
                        .last("limit " + n))
                .stream().map(this::runDto).toList();
    }

    // ==================== 调度与执行 ====================

    /** 节拍扫描：把到期的启用任务派发给线程池执行（不阻塞调度线程） */
    public void tick() {
        if (!configService.getBoolean("scheduled.enabled")) return;
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledJob> due = jobMapper.selectList(new LambdaQueryWrapper<ScheduledJob>()
                .eq(ScheduledJob::getEnabled, 1)
                .eq(ScheduledJob::getDeleted, 0)
                .isNotNull(ScheduledJob::getNextRunAt)
                .le(ScheduledJob::getNextRunAt, now)
                .orderByAsc(ScheduledJob::getNextRunAt)
                .last("limit " + SCAN_LIMIT));
        for (ScheduledJob job : due) {
            // 先推进 next_run_at 再执行：即使执行很久，也不会在下一拍被重复触发
            job.setNextRunAt(nextRunAt(job.getCron(), job.getTimezone(), now));
            jobMapper.updateById(job);
            dispatch(job, "scheduled");
        }
    }

    /** 手动立即执行（异步：执行可能持续几十秒，接口立即返回，状态看执行历史） */
    public Map<String, Object> runNow(String uid, String jobId) {
        ScheduledJob job = mustOwn(uid, jobId);
        if (!dispatch(job, "manual")) {
            throw new BizException("该任务正在执行中，请稍候再试");
        }
        return toDto(job);
    }

    /** 提交执行（同一任务串行：上轮没跑完就跳过，并留一条 skipped 记录，便于解释"这轮为什么没输出"） */
    private boolean dispatch(ScheduledJob job, String trigger) {
        if (!runningJobs.add(job.getId())) {
            log.warn("[SCHEDULED] 任务 {} 上一轮仍在执行，本轮跳过", job.getName());
            if ("scheduled".equals(trigger)) {
                insertRun(job, trigger, "skipped", "上一轮仍在执行，本轮跳过", null, null);
            }
            return false;
        }
        ThreadPoolManager.execute(() -> {
            try {
                execute(job, trigger);
            } finally {
                runningJobs.remove(job.getId());
            }
        });
        return true;
    }

    /**
     * 跑一轮：懒创建结果会话 → 用收集型通道调问答流水线 → 等结束 → 记录状态。
     * <p>归属身份走参数（{@code userId}）而不是 {@code RequestUser}——流水线跑在独立线程池里，
     * 那里没有请求上下文（这是既有事实，网页问答同理）。
     */
    private void execute(ScheduledJob job, String trigger) {
        long t0 = System.currentTimeMillis();
        ScheduledRun run = insertRun(job, trigger, "running", null, null, null);
        try {
            // 一个任务一个会话：首轮创建并按任务名命名，之后每轮追加（历史执行连起来看）
            String sessionId = job.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = sessionService.createSession(job.getUid());
                sessionService.renameSession(job.getUid(), sessionId, "定时任务 · " + job.getName());
                job.setSessionId(sessionId);
                jobMapper.updateById(job);
            } else {
                sessionService.ensureSession(sessionId, job.getUid());
            }
            run.setSessionId(sessionId);
            runMapper.updateById(run);

            int timeoutMs = Math.max(30_000, configService.getInt("scheduled.timeoutMs", 300_000));
            CollectingSseEmitter sink = new CollectingSseEmitter();
            try {
                ragService.chat(sessionId, job.getPrompt(), null, null, null,
                        Integer.valueOf(1).equals(job.getDeepThink()),
                        job.getAgentId(), job.getModelRef(), job.getUid(), sink);
                if (!sink.awaitDone(timeoutMs)) {
                    throw new IllegalStateException("执行超时（" + timeoutMs + " ms 内未收到完成事件）");
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    throw new IllegalStateException(sink.lastError());
                }
                run.setStatus("succeeded");
                run.setAnswer(trimTo(sink.answer(), ANSWER_PREVIEW));
            } finally {
                // 收集型通道不触发 onCompletion，必须主动清登记，否则 RagService 的 ACTIVE_SSE 残留
                RagService.forgetSseChannel(sink);
            }
        } catch (Exception e) {
            run.setStatus("failed");
            run.setError(trimTo(String.valueOf(e.getMessage()), 500));
            log.warn("[FAIL-LOUD] 定时任务执行失败 job={} name={}: {}", job.getId(), job.getName(), e.getMessage());
        } finally {
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
            job.setLastRunAt(run.getStartedAt());
            jobMapper.updateById(job);
            log.info("[SCHEDULED] 任务「{}」执行结束：status={} 用时={}ms session={}",
                    job.getName(), run.getStatus(), System.currentTimeMillis() - t0, run.getSessionId());
        }
    }

    // ==================== cron ====================

    /** 校验 cron 与 timezone：**必须 5 段**（分 时 日 月 周）——与平台版口径一致（不接受含秒写法） */
    public static void validateCron(String cron, String timezone) {
        String c = normalizeCron(cron);
        if (c.isEmpty()) throw new BizException("cron 表达式不能为空");
        if (c.split(" ").length != 5) {
            throw new BizException("cron 需为 5 段（分 时 日 月 周），如 0 9 * * 1-5 表示工作日 9 点");
        }
        try {
            CronExpression.parse(SPRING_SECONDS_PREFIX + c);
        } catch (IllegalArgumentException e) {
            // 不把 Spring 的内部报错（含补位后的 6 段写法）透给用户，避免"我写的是 5 段它却说 6 段"
            throw new BizException("cron 表达式不合法（写法：分 时 日 月 周），如 0 9 * * 1-5 表示工作日 9 点");
        }
        zoneOf(timezone);
    }

    /**
     * 下一次执行时刻：cron 按 timezone 解释，得到**绝对时刻**后转成系统默认时区表示
     * （库里的 DATETIME 与 {@code LocalDateTime.now()} 比较，必须同一时区口径）。
     */
    public static LocalDateTime nextRunAt(String cron, String timezone, LocalDateTime after) {
        CronExpression expr = CronExpression.parse(SPRING_SECONDS_PREFIX + normalizeCron(cron));
        ZonedDateTime next = expr.next(after.atZone(zoneOf(timezone)));
        return next == null ? null : next.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static String normalizeCron(String cron) {
        return cron == null ? "" : cron.trim().replaceAll("\\s+", " ");
    }

    private static ZoneId zoneOf(String timezone) {
        String tz = (timezone == null || timezone.isBlank()) ? DEFAULT_ZONE : timezone.trim();
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            throw new BizException("时区不合法：" + tz + "（如 Asia/Shanghai）");
        }
    }

    // ==================== 内部工具 ====================

    private ScheduledJob mustOwn(String uid, String id) {
        ScheduledJob job = id == null ? null : jobMapper.selectById(id);
        if (job == null || Integer.valueOf(1).equals(job.getDeleted())) throw new BizException("定时任务不存在");
        if (!job.getUid().equals(uid)) throw new BizException("无权操作他人的定时任务");
        return job;
    }

    private void applyBody(ScheduledJob job, Map<String, Object> body, boolean creating) {
        if (body == null) body = Map.of();
        Object name = body.get("name");
        if (creating || name != null) {
            String n = str(name);
            if (n.isEmpty()) throw new BizException("请填写任务名称");
            job.setName(trimTo(n, MAX_NAME));
        }
        Object prompt = body.get("prompt");
        if (creating || prompt != null) {
            String p = str(prompt);
            if (p.isEmpty()) throw new BizException("请填写每轮发送给智能体的指令");
            job.setPrompt(trimTo(p, MAX_PROMPT));
        }
        Object cron = body.get("cron");
        if (creating || cron != null) {
            String c = normalizeCron(str(cron));
            String tz = body.get("timezone") != null ? str(body.get("timezone"))
                    : (job.getTimezone() == null ? DEFAULT_ZONE : job.getTimezone());
            validateCron(c, tz);
            job.setCron(c);
            job.setTimezone(tz);
        }
        if (body.containsKey("agentId")) {
            String agentId = str(body.get("agentId"));
            if (!agentId.isEmpty() && agentMapper.selectById(agentId) == null) {
                throw new BizException("指定的智能体不存在");
            }
            job.setAgentId(agentId.isEmpty() ? null : agentId);
        }
        if (body.containsKey("modelRef")) {
            String ref = str(body.get("modelRef"));
            job.setModelRef(ref.isEmpty() ? null : ref);
        }
        if (body.containsKey("deepThink")) {
            job.setDeepThink(bool(body.get("deepThink")) ? 1 : 0);
        } else if (creating) {
            job.setDeepThink(0);
        }
        if (body.containsKey("enabled")) {
            job.setEnabled(bool(body.get("enabled")) ? 1 : 0);
        } else if (creating) {
            job.setEnabled(1);
        }
    }

    /** 最近一次执行记录（列表页展示状态用） */
    private ScheduledRun lastRun(String jobId) {
        List<ScheduledRun> rows = runMapper.selectList(new LambdaQueryWrapper<ScheduledRun>()
                .eq(ScheduledRun::getJobId, jobId)
                .orderByDesc(ScheduledRun::getCreateTime)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ScheduledRun insertRun(ScheduledJob job, String trigger, String status,
                                   String error, String answer, String sessionId) {
        ScheduledRun run = new ScheduledRun();
        run.setJobId(job.getId());
        run.setUid(job.getUid());
        run.setSessionId(sessionId);
        run.setTriggerType(trigger);
        run.setStatus(status);
        run.setAnswer(answer);
        run.setError(error);
        run.setStartedAt(LocalDateTime.now());
        run.setCreateTime(LocalDateTime.now());
        if (!"running".equals(status)) run.setFinishedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    private Map<String, Object> toDto(ScheduledJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.getId());
        m.put("name", job.getName());
        m.put("prompt", job.getPrompt());
        m.put("agentId", job.getAgentId());
        Agent agent = job.getAgentId() == null ? null : agentMapper.selectById(job.getAgentId());
        m.put("agentName", agent == null ? null : agent.getName());
        m.put("cron", job.getCron());
        m.put("timezone", job.getTimezone());
        m.put("deepThink", Integer.valueOf(1).equals(job.getDeepThink()));
        m.put("modelRef", job.getModelRef());
        m.put("enabled", Integer.valueOf(1).equals(job.getEnabled()));
        m.put("nextRunAt", ts(job.getNextRunAt()));
        m.put("lastRunAt", ts(job.getLastRunAt()));
        m.put("sessionId", job.getSessionId());
        m.put("createTime", ts(job.getCreateTime()));
        return m;
    }

    private Map<String, Object> runDto(ScheduledRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId());
        m.put("jobId", run.getJobId());
        m.put("sessionId", run.getSessionId());
        m.put("trigger", run.getTriggerType());
        m.put("status", run.getStatus());
        m.put("answer", run.getAnswer());
        m.put("error", run.getError());
        m.put("startedAt", ts(run.getStartedAt()));
        m.put("finishedAt", ts(run.getFinishedAt()));
        return m;
    }

    private static String ts(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o));
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
