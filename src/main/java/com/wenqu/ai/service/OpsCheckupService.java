package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.OpsCheckupReportMapper;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.OpsCheckupReport;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 系统巡检（运维保障）：一键把「这套平台现在还能不能正常服务」摸一遍，出一份入审计的报告。
 * <p>
 * 判据与 scripts/mac/check-enterprise.py 的真机回归同源（审计链完整性、Redis 边界、供应商在位），
 * 但这里是<b>产品能力</b>而不是工程师的命令行——管理员不需要登服务器也能回答「现在平台健康吗」。
 * <p>
 * 各项互相独立：一项挂了不影响其余各项出结果（巡检本身不能成为第二个故障点）。
 * 级别：ok=正常 warn=要留意 fail=必须处理；overall 取最差项。
 * <p>
 * 2026-11：报告从「进程内一份」升级为<b>落库历史</b>。原因是重启即丢的缓存回答不了运维真正的问题
 * ——「昨天 22 点那次失败长什么样」。落库后定时巡检、趋势与失败告警才有可回溯的底座；
 * 进程内 {@code lastReport} 只保留作数据库暂不可用时的兜底。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsCheckupService {

    private static final String GROUP_DEPS = "平台依赖";
    private static final String GROUP_SECURITY = "安全与审计";
    private static final String GROUP_CHAIN = "问答链路";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AuditService audit;
    private final KeywordIndexService keywordIndex;
    private final AppProperties properties;
    private final com.wenqu.ai.agent.tool.ToolRegistry toolRegistry;
    private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;
    private final com.wenqu.ai.startup.OrphanTurnRunner orphanTurns;
    private final OpsCheckupReportMapper reportMapper;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final ConfigService configService;

    private final AtomicReference<Map<String, Object>> lastReport = new AtomicReference<>();

    /** 巡检项：group 供前端分组，hint 是给管理员的处置建议（不是工程师日志） */
    public record Check(String key, String group, String name, String level, String detail, long ms, String hint) {
    }

    /** 兼容旧调用点：默认按手动触发 */
    public Map<String, Object> run() {
        return run("manual");
    }

    /** 执行一次巡检并落库；返回与历史详情同口径的报告 */
    public Map<String, Object> run(String trigger) {
        long start = System.currentTimeMillis();
        List<Check> checks = new ArrayList<>();
        checks.add(checkMysql());
        checks.add(checkRedis());
        checks.add(checkKeywordEngine());
        checks.add(checkDisk());
        checks.add(checkAuditChain());
        checks.add(checkProviders());
        checks.add(checkLockedAccounts());
        checks.add(checkToolAssembly());
        checks.add(checkTurnResume());

        String overall = "ok";
        long fail = 0, warn = 0;
        for (Check c : checks) {
            if ("fail".equals(c.level())) { overall = "fail"; fail++; }
            else if ("warn".equals(c.level()) && !"fail".equals(overall)) overall = "warn";
            if ("warn".equals(c.level())) warn++;
        }
        long took = System.currentTimeMillis() - start;
        LocalDateTime at = LocalDateTime.now();
        String id = UUID.randomUUID().toString();

        OpsCheckupReport row = new OpsCheckupReport();
        row.setId(id);
        // 平台视图下拦截器不加租户谓词；显式写入当前租户（无上下文回落 default），历史才分得清归属
        String tenant = TenantContext.current();
        row.setTenantId(tenant == null || tenant.isBlank() ? TenantContext.DEFAULT_TENANT : tenant);
        row.setAt(at);
        row.setTriggerType(trigger == null || trigger.isBlank() ? "manual" : trigger);
        row.setOverall(overall);
        row.setTookMs(took);
        row.setFailCount((int) fail);
        row.setWarnCount((int) warn);
        row.setChecksJson(serializeChecks(checks));
        row.setCreatedBy(RequestUser.SYSTEM.equals(RequestUser.uid()) ? null : RequestUser.uid());
        row.setCreateTime(at);
        try {
            reportMapper.insert(row);
        } catch (Exception e) {
            // 落库失败不能把巡检本身变成第二个故障点：报告仍返回，进程内缓存兜底
            log.warn("[OPS] 巡检报告落库失败（本次报告仅在进程内保留）：{}", e.getMessage());
        }

        Map<String, Object> out = toMap(row, checks);
        out.put("checks", checks);
        lastReport.set(out);
        audit.record("[AUDIT] 系统巡检 id={} trigger={} overall={} fail={} warn={} tookMs={}",
                id, row.getTriggerType(), overall, fail, warn, took);
        alertIfNeeded(id, checks);
        return out;
    }

    /**
     * 定时巡检：逐租户跑一遍。
     * <p>系统线程没有租户上下文，单跑一次只会把报告写进 default；而登录安全态势这一项
     * 恰恰要看本租户的账号。逐租户绑定再跑，报告与告警才落在各自的租户里。
     */
    public void runScheduled() {
        for (String tenant : distinctTenants()) {
            try {
                TenantContext.as(tenant, () -> {
                    run("schedule");
                    return null;
                });
            } catch (Exception e) {
                log.warn("[OPS] 租户 {} 的定时巡检失败：{}", tenant, e.getMessage());
            }
        }
    }

    /** 最近一次报告（优先读库；库不可用时回落进程内缓存） */
    public Map<String, Object> last() {
        try {
            return TenantContext.asPlatformIfSuper(() -> {
                OpsCheckupReport row = reportMapper.selectOne(new LambdaQueryWrapper<OpsCheckupReport>()
                        .orderByDesc(OpsCheckupReport::getAt).last("limit 1"));
                return row == null ? lastReport.get() : toMap(row, parseChecks(row.getChecksJson()));
            });
        } catch (Exception e) {
            log.warn("[OPS] 读取最近一次巡检报告失败，回落进程内缓存：{}", e.getMessage());
            return lastReport.get();
        }
    }

    /** 历史报告摘要（不含逐项 checks，避免列表把整份 JSON 拖下来） */
    public List<Map<String, Object>> history(int days, int limit) {
        int d = Math.max(1, Math.min(90, days));
        int n = Math.max(1, Math.min(100, limit));
        LocalDateTime from = LocalDateTime.now().minusDays(d);
        return TenantContext.asPlatformIfSuper(() -> reportMapper.selectList(
                        new LambdaQueryWrapper<OpsCheckupReport>()
                                .ge(OpsCheckupReport::getAt, from)
                                .orderByDesc(OpsCheckupReport::getAt)
                                .last("limit " + n)))
                .stream().map(this::summary).collect(Collectors.toList());
    }

    /** 按天聚合趋势：每天跑了几次、失败/留意几次、平均耗时与最差结论 */
    public List<Map<String, Object>> trend(int days) {
        int d = Math.max(1, Math.min(30, days));
        LocalDateTime from = LocalDate.now().minusDays(d - 1L).atStartOfDay();
        List<OpsCheckupReport> rows = TenantContext.asPlatformIfSuper(() -> reportMapper.selectList(
                new LambdaQueryWrapper<OpsCheckupReport>()
                        .ge(OpsCheckupReport::getAt, from)
                        .orderByAsc(OpsCheckupReport::getAt)));
        Map<String, List<OpsCheckupReport>> byDay = rows.stream().collect(Collectors.groupingBy(
                r -> r.getAt().toLocalDate().toString(), LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<OpsCheckupReport>> e : byDay.entrySet()) {
            List<OpsCheckupReport> list = e.getValue();
            String overall = "ok";
            long failRuns = 0, warnRuns = 0, took = 0;
            for (OpsCheckupReport r : list) {
                if ("fail".equals(r.getOverall())) { overall = "fail"; failRuns++; }
                else if ("warn".equals(r.getOverall()) && !"fail".equals(overall)) overall = "warn";
                if ("warn".equals(r.getOverall())) warnRuns++;
                took += r.getTookMs() == null ? 0 : r.getTookMs();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", e.getKey());
            m.put("runs", list.size());
            m.put("fail", failRuns);
            m.put("warn", warnRuns);
            m.put("overall", overall);
            m.put("avgTookMs", list.isEmpty() ? 0 : took / list.size());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("date"))));
        return out;
    }

    /** 单次报告详情（含 checks_json） */
    public Map<String, Object> detail(String id) {
        return TenantContext.asPlatformIfSuper(() -> {
            OpsCheckupReport row = reportMapper.selectById(id);
            return row == null ? null : toMap(row, parseChecks(row.getChecksJson()));
        });
    }

    /** 删除超过保留期的报告（0 或负数 = 不清理） */
    public int purgeExpired(int days) {
        if (days <= 0) return 0;
        LocalDateTime before = LocalDateTime.now().minusDays(days);
        return TenantContext.asPlatform(() -> reportMapper.delete(
                new LambdaQueryWrapper<OpsCheckupReport>().lt(OpsCheckupReport::getAt, before)));
    }

    // ==================== 各巡检项 ====================

    private Check checkMysql() {
        long t = System.currentTimeMillis();
        try {
            String version = jdbc.queryForObject("select version()", String.class);
            return new Check("mysql", GROUP_DEPS, "MySQL 数据库", "ok",
                    "连接正常，版本 " + version, System.currentTimeMillis() - t,
                    "无需处理；版本升级请走维护窗口，避免在业务高峰做 DDL。");
        } catch (Exception e) {
            return new Check("mysql", GROUP_DEPS, "MySQL 数据库", "fail",
                    "连不上：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先确认数据库进程与连接串；数据库不可用时成员/会话/配置/审计全部不可用。");
        }
    }

    private Check checkRedis() {
        long t = System.currentTimeMillis();
        try {
            String pong = redis.execute((RedisCallback<String>) c -> c.ping());
            if (!"PONG".equals(pong)) throw new IllegalStateException("ping 返回 " + pong);
            Properties mem = redis.execute((RedisCallback<Properties>) c -> c.info("memory"));
            String used = mem == null ? "?" : String.valueOf(mem.getProperty("used_memory_human", "?")).trim();
            return new Check("redis", GROUP_DEPS, "Redis（运行态与协调态）", "ok",
                    "在线，内存占用 " + used + "；启动期硬依赖：Redis 不在线后端起不来",
                    System.currentTimeMillis() - t,
                    "内存接近上限时先查会话缓存与检索缓存；扩容前确认淘汰策略不会误删锁键。");
        } catch (Exception e) {
            return new Check("redis", GROUP_DEPS, "Redis（运行态与协调态）", "fail",
                    "不可用：" + e.getMessage(), System.currentTimeMillis() - t,
                    "Redis 是启动期硬依赖：先恢复实例再重启后端，否则后端会起不来。");
        }
    }

    private Check checkKeywordEngine() {
        long t = System.currentTimeMillis();
        try {
            if (!keywordIndex.enabled()) {
                return new Check("keyword", GROUP_DEPS, "关键词召回引擎", "ok",
                        "当前用 mysql LIKE（零依赖），meilisearch 未启用", System.currentTimeMillis() - t,
                        "无需处理；启用 meilisearch 后这里会开始探测可达性。");
            }
            boolean ok = keywordIndex.checkAvailable();
            String reason = ok ? "" : ("，不可用原因：" + keywordIndex.debugUnavailableReason());
            return new Check("keyword", GROUP_DEPS, "关键词召回引擎", ok ? "ok" : "warn",
                    "meilisearch " + (ok ? "可达" : "不可达（检索已自动降级回 mysql LIKE，不影响问答，只影响召回质量）") + reason,
                    System.currentTimeMillis() - t,
                    "不可达先查 meili 容器与防火墙；检索已自动降级，问答仍可用，不必停服。");
        } catch (Exception e) {
            return new Check("keyword", GROUP_DEPS, "关键词召回引擎", "warn",
                    "检查失败：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先看 meili 连通性；该项失败不影响问答，只影响召回质量。");
        }
    }

    private Check checkDisk() {
        long t = System.currentTimeMillis();
        try {
            File root = new File(properties.getImages().getDir());
            if (!root.exists()) {
                return new Check("disk", GROUP_DEPS, "落盘数据目录", "fail",
                        "目录不存在：" + root.getAbsolutePath(), System.currentTimeMillis() - t,
                        "先确认数据卷挂载与目录权限；目录不存在时源文件/图片/产物写入都会失败。");
            }
            long free = root.getFreeSpace(), total = root.getTotalSpace();
            if (total <= 0) {
                return new Check("disk", GROUP_DEPS, "落盘数据目录", "warn",
                        "无法读取磁盘容量（" + root.getAbsolutePath() + "）", System.currentTimeMillis() - t,
                        "确认容器内的文件系统是否暴露容量信息；容量不可读时按未检查成功处理。");
            }
            int pctUsed = (int) Math.round(100.0 * (total - free) / total);
            String detail = root.getAbsolutePath() + " 所在盘已用 " + pctUsed + "%（剩余 " + gb(free) + " / 共 " + gb(total) + "）";
            String level = pctUsed >= 90 ? "fail" : pctUsed >= 80 ? "warn" : "ok";
            return new Check("disk", GROUP_DEPS, "落盘数据目录", level,
                    detail + (pctUsed >= 90 ? "：剩余不足 10%，源文件/图片/产物写入随时会失败" : ""),
                    System.currentTimeMillis() - t,
                    "优先清理超过保留期的聊天附件、工具外溢原文与临时目录；再看产物与归档。不要直接删目录。");
        } catch (Exception e) {
            return new Check("disk", GROUP_DEPS, "落盘数据目录", "warn",
                    "检查失败：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先确认数据目录存在且当前进程有读权限；容量不可读时按未检查成功处理。");
        }
    }

    /** 审计链：哈希不符必须为 0（有人动过账）；序号洞如实报——那是修复前遗留的历史洞，按设计不算坏 */
    private Check checkAuditChain() {
        long t = System.currentTimeMillis();
        try {
            List<Map<String, Object>> problems = audit.verify(2000);
            long tampered = problems.stream().filter(p -> String.valueOf(p.get("problem")).startsWith("内容被改写")).count();
            long others = problems.size() - tampered;
            String level = tampered > 0 ? "fail" : "ok";
            String detail = tampered > 0
                    ? "哈希链被改写 " + tampered + " 处——审计记录被人为改动，必须立即追责排查"
                    : "抽验 2000 行无内容改写" + (others > 0 ? "；另有 " + others + " 处历史序号洞（修复前遗留，按设计如实报，非新问题）" : "");
            return new Check("audit", GROUP_SECURITY, "审计链完整性", level, detail, System.currentTimeMillis() - t,
                    "发现内容被改写先冻结写入、导出 CSV 留证，再按审计事件定位操作人；序号洞先确认是否修复前遗留。");
        } catch (Exception e) {
            return new Check("audit", GROUP_SECURITY, "审计链完整性", "fail",
                    "校验跑不起来：" + e.getMessage(), System.currentTimeMillis() - t,
                    "审计链校验失败本身就要处理：先确认审计表可读、副本配置正确，再重跑校验。");
        }
    }

    /** 问答链路底线：一个启用的供应商都没有，平台就答不了问题 */
    private Check checkProviders() {
        long t = System.currentTimeMillis();
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "select count(*) enabled_count, " +
                    "sum(case when api_key is null or api_key = '' then 1 else 0 end) no_key " +
                    "from c_ai_provider where enabled = 1");
            long enabled = ((Number) row.get("enabled_count")).longValue();
            long noKey = row.get("no_key") == null ? 0 : ((Number) row.get("no_key")).longValue();
            if (enabled == 0) {
                return new Check("providers", GROUP_CHAIN, "模型供应商", "fail",
                        "没有启用的供应商——问答链路整体不可用", System.currentTimeMillis() - t,
                        "至少启用一家有 Key 的供应商，否则问答整体不可用。");
            }
            String level = noKey > 0 ? "warn" : "ok";
            return new Check("providers", GROUP_CHAIN, "模型供应商", level,
                    "启用 " + enabled + " 家" + (noKey > 0 ? "，其中 " + noKey + " 家未配 API Key（调用会失败）" : "，均配了凭据"),
                    System.currentTimeMillis() - t,
                    noKey > 0 ? "缺 Key 的供应商一调用就失败：补凭据或先停用，别让默认模型指向它。" : "无需处理。");
        } catch (Exception e) {
            return new Check("providers", GROUP_CHAIN, "模型供应商", "warn",
                    "检查失败：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先确认 c_ai_provider 可读；检查失败不等于供应商不可用，但不要当成正常。");
        }
    }

    /** 登录安全态势：当前处于锁定态的账号（有人连撞密码，或真有人忘了密码） */
    private Check checkLockedAccounts() {
        long t = System.currentTimeMillis();
        try {
            String tenant = TenantContext.current();
            String sql = "select count(*) from c_ai_user where locked_until is not null and locked_until > now()";
            Integer locked = (tenant == null || tenant.isBlank())
                    ? jdbc.queryForObject(sql, Integer.class)
                    : jdbc.queryForObject(sql + " and tenant_id = ?", Integer.class, tenant);
            int n = locked == null ? 0 : locked;
            return new Check("login", GROUP_SECURITY, "登录安全态势", n > 0 ? "warn" : "ok",
                    n > 0 ? n + " 个账号处于登录锁定态（连续失败触发；管理员重置密码即解锁）" : "无锁定中的账号",
                    System.currentTimeMillis() - t,
                    "锁定数突然升高先看是不是同一 IP 在撞库；确认是本人忘记密码就走「重置密码」。");
        } catch (Exception e) {
            return new Check("login", GROUP_SECURITY, "登录安全态势", "warn",
                    "检查失败：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先确认成员表可读；检查失败不能当成「没有锁定账号」。");
        }
    }

    /**
     * 工具装配对账：登记名与实现是否逐字对上、档位名单覆盖的那几把沙盒工具有没有漏进组管理。
     * <p>为什么值得单列一项：这两件事坏起来都是静默的——少一行登记时清单页与审批判据都问不到那把工具，
     * 漏一个组时「档位收紧」退化成「模型看得见、调了被拒」，白烧一步预算而屏幕上没有异常。
     * 这两道本各有启动期 ERROR 日志与每轮双跑比对兜着，这里给的是<b>不用翻日志</b>的那个出口。
     * <p>没有常驻实例时只核登记面并如实说出来：巡检不该为了看一眼就建一条会话实例。
     */
    private Check checkToolAssembly() {
        long t = System.currentTimeMillis();
        try {
            Map<String, String> gaps = toolRegistry.declarationGaps();
            if (!gaps.isEmpty()) {
                return new Check("tools", GROUP_CHAIN, "工具装配对账", "fail",
                        "登记名与实现不一致：" + String.join("；", gaps.values()), System.currentTimeMillis() - t,
                        "按清单修 ToolRegistry 的登记名或实现名，两者必须逐字对上。");
            }
            List<String> ungrouped = new ArrayList<>();
            int instances = 0;
            for (var toolkit : frameworkSessions.liveToolkits()) {
                instances++;
                for (String name : com.wenqu.ai.agentx.WenquAgentxTools.frameworkSurfaceToolNames()) {
                    if (toolkit.getTool(name) != null && toolkit.getToolGroup(name) == null
                            && !ungrouped.contains(name)) ungrouped.add(name);
                }
            }
            if (!ungrouped.isEmpty()) {
                return new Check("tools", GROUP_CHAIN, "工具装配对账", "warn",
                        "这几把沙盒/记忆工具没在档位名单的组管理里（模型一直看得到，档位拦不住）："
                                + String.join("、", ungrouped), System.currentTimeMillis() - t,
                        "把这些工具补进档位名单的组管理；否则模型看得见、档位拦不住。");
            }
            return new Check("tools", GROUP_CHAIN, "工具装配对账", "ok",
                    instances > 0 ? "登记与实现逐字对上；本进程 " + instances
                            + " 条常驻会话的沙盒与记忆工具都已进档位名单"
                            : "登记与实现逐字对上（本进程暂无常驻会话实例，未核对运行中的工具表）",
                    System.currentTimeMillis() - t,
                    "无需处理；出现不一致时按详情里的清单修登记或档位名单。");
        } catch (Exception e) {
            return new Check("tools", GROUP_CHAIN, "工具装配对账", "warn",
                    "检查失败：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先确认工具注册表可读；检查失败不等于装配正确。");
        }
    }

    /**
     * 长任务接续态势：进程停过之后，那些「发出去了却没人跑完」的轮有没有被接回来。
     * <p>为什么单列一项：这件事的两种结果都不出现在界面上——接回了，用户只看到答案晚了一点；
     * 没接回，那条问题旁边就永远空着，而用户不知道平台有没有试过。让管理员一眼看到「试了几条、接了几条」
     * 是这一项的全部意义。
     */
    private Check checkTurnResume() {
        long t = System.currentTimeMillis();
        try {
            var sweep = orphanTurns.lastSweep();
            if (sweep.at().isBlank()) {
                return new Check("resume", GROUP_CHAIN, "长任务接续", "ok",
                        "这一轮还没扫过（服务起来后立即跑首轮，之后按「长任务接续间隔」）", System.currentTimeMillis() - t,
                        "无需处理；等首轮扫描跑完后这里会给出「发现几条、收口几条」。");
            }
            if (sweep.found() == 0) {
                return new Check("resume", GROUP_CHAIN, "长任务接续", "ok",
                        "没有没答完的轮（最近一次扫描 " + sweep.at() + "）", System.currentTimeMillis() - t,
                        "无需处理。");
            }
            String line = "最近一次扫描发现 " + sweep.found() + " 条没人答完的轮，已按中断收口 "
                    + sweep.taken() + " 条";
            if (sweep.taken() < sweep.found() || !sweep.error().isBlank()) {
                return new Check("resume", GROUP_CHAIN, "长任务接续", "warn",
                        line + "；接不上的原因：" + sweep.error(), System.currentTimeMillis() - t,
                        "看 error 原因；接续只收口不重跑，不会替用户多算一遍。");
            }
            return new Check("resume", GROUP_CHAIN, "长任务接续", "ok", line, System.currentTimeMillis() - t,
                    "无需处理。");
        } catch (Exception e) {
            return new Check("resume", GROUP_CHAIN, "长任务接续", "warn",
                    "检查失败：" + e.getMessage(), System.currentTimeMillis() - t,
                    "先确认接续扫描状态可读；检查失败不等于没有孤儿轮。");
        }
    }

    // ==================== 落库/读数辅助 ====================

    private void alertIfNeeded(String reportId, List<Check> checks) {
        try {
            if (!configService.getBoolean("ops.checkupAlertEnabled", ConfigDefaults.OPS_CHECKUP_ALERT_ENABLED)) return;
            List<Check> failed = checks.stream().filter(c -> "fail".equals(c.level())).toList();
            if (failed.isEmpty()) return;
            String names = failed.stream().map(Check::name).collect(Collectors.joining("、"));
            String keys = failed.stream().map(Check::key).sorted().collect(Collectors.joining(","));
            notificationService.createForAdmins(Notification.TYPE_OPS_ALERT,
                    "系统巡检发现必须处理的问题",
                    "巡检项：" + names + "。打开「安全与运维 → 系统巡检」查看详情与处置建议。",
                    "ops", reportId, "ops:checkup:" + keys);
        } catch (Exception e) {
            log.warn("[OPS] 巡检告警发送失败（不影响巡检本身）：{}", e.getMessage());
        }
    }

    private String serializeChecks(List<Check> checks) {
        try {
            return objectMapper.writeValueAsString(checks);
        } catch (Exception e) {
            log.warn("[OPS] 巡检项序列化失败：{}", e.getMessage());
            return "[]";
        }
    }

    private List<Map<String, Object>> parseChecks(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("[OPS] 巡检项 JSON 解析失败：{}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> toMap(OpsCheckupReport row, List<?> checks) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.getId());
        out.put("overall", row.getOverall());
        out.put("at", row.getAt() == null ? null : row.getAt().toString());
        out.put("trigger", row.getTriggerType());
        out.put("tookMs", row.getTookMs());
        out.put("checks", checks);
        out.put("failCount", row.getFailCount());
        out.put("warnCount", row.getWarnCount());
        return out;
    }

    private Map<String, Object> summary(OpsCheckupReport row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("overall", row.getOverall());
        m.put("at", row.getAt() == null ? null : row.getAt().toString());
        m.put("trigger", row.getTriggerType());
        m.put("tookMs", row.getTookMs());
        m.put("failCount", row.getFailCount());
        m.put("warnCount", row.getWarnCount());
        return m;
    }

    /** 巡检逐租户跑：系统线程没有租户上下文，默认租户之外的历史/告警会丢 */
    private List<String> distinctTenants() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(TenantContext.DEFAULT_TENANT);
        try {
            for (String t : jdbc.queryForList("select distinct tenant_id from c_ai_user", String.class)) {
                if (t != null && !t.isBlank()) out.add(t);
            }
        } catch (Exception e) {
            log.warn("[OPS] 租户清单查询失败，按 default 单租户巡检：{}", e.getMessage());
        }
        return List.copyOf(out);
    }

    private static String gb(long bytes) {
        return String.format("%.1f GB", bytes / 1073741824.0);
    }
}
