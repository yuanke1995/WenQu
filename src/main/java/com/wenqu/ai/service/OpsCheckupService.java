package com.wenqu.ai.service;

import com.wenqu.ai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 系统巡检（运维保障）：一键把「这套平台现在还能不能正常服务」摸一遍，出一份入审计的报告。
 * <p>
 * 判据与 scripts/mac/check-enterprise.py 的真机回归同源（审计链完整性、Redis 边界、供应商在位），
 * 但这里是<b>产品能力</b>而不是工程师的命令行——管理员不需要登服务器也能回答「现在平台健康吗」。
 * <p>
 * 各项互相独立：一项挂了不影响其余各项出结果（巡检本身不能成为第二个故障点）。
 * 级别：ok=正常 warn=要留意 fail=必须处理；overall 取最差项。最近一次报告进程内保留供回看。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsCheckupService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AuditService audit;
    private final KeywordIndexService keywordIndex;
    private final AppProperties properties;

    private final AtomicReference<Map<String, Object>> lastReport = new AtomicReference<>();

    /** 巡检项 */
    public record Check(String key, String name, String level, String detail, long ms) {
    }

    public Map<String, Object> run() {
        long start = System.currentTimeMillis();
        List<Check> checks = new ArrayList<>();
        checks.add(checkMysql());
        checks.add(checkRedis());
        checks.add(checkKeywordEngine());
        checks.add(checkDisk());
        checks.add(checkAuditChain());
        checks.add(checkProviders());
        checks.add(checkLockedAccounts());

        String overall = "ok";
        long fail = 0, warn = 0;
        for (Check c : checks) {
            if ("fail".equals(c.level())) { overall = "fail"; fail++; }
            else if ("warn".equals(c.level()) && !"fail".equals(overall)) overall = "warn";
            if ("warn".equals(c.level())) warn++;
        }
        long took = System.currentTimeMillis() - start;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", overall);
        out.put("at", LocalDateTime.now().toString());
        out.put("tookMs", took);
        out.put("checks", checks);
        out.put("failCount", fail);
        out.put("warnCount", warn);
        lastReport.set(out);
        audit.record("[AUDIT] 系统巡检 overall={} fail={} warn={} tookMs={}", overall, fail, warn, took);
        return out;
    }

    /** 最近一次报告（未巡检过返回 null，前端据此显示「从未巡检」） */
    public Map<String, Object> last() {
        return lastReport.get();
    }

    // ==================== 各巡检项 ====================

    private Check checkMysql() {
        long t = System.currentTimeMillis();
        try {
            String version = jdbc.queryForObject("select version()", String.class);
            return new Check("mysql", "MySQL 数据库", "ok", "连接正常，版本 " + version, System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("mysql", "MySQL 数据库", "fail", "连不上：" + e.getMessage(), System.currentTimeMillis() - t);
        }
    }

    private Check checkRedis() {
        long t = System.currentTimeMillis();
        try {
            String pong = redis.execute((RedisCallback<String>) c -> c.ping());
            if (!"PONG".equals(pong)) throw new IllegalStateException("ping 返回 " + pong);
            Properties mem = redis.execute((RedisCallback<Properties>) c -> c.info("memory"));
            String used = mem == null ? "?" : String.valueOf(mem.getProperty("used_memory_human", "?")).trim();
            return new Check("redis", "Redis（运行态与协调态）", "ok",
                    "在线，内存占用 " + used + "；启动期硬依赖：Redis 不在线后端起不来", System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("redis", "Redis（运行态与协调态）", "fail", "不可用：" + e.getMessage(), System.currentTimeMillis() - t);
        }
    }

    private Check checkKeywordEngine() {
        long t = System.currentTimeMillis();
        try {
            if (!keywordIndex.enabled()) {
                return new Check("keyword", "关键词召回引擎", "ok",
                        "当前用 mysql LIKE（零依赖），meilisearch 未启用", System.currentTimeMillis() - t);
            }
            boolean ok = keywordIndex.checkAvailable();
            String reason = ok ? "" : ("，不可用原因：" + keywordIndex.debugUnavailableReason());
            return new Check("keyword", "关键词召回引擎", ok ? "ok" : "warn",
                    "meilisearch " + (ok ? "可达" : "不可达（检索已自动降级回 mysql LIKE，不影响问答，只影响召回质量）") + reason,
                    System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("keyword", "关键词召回引擎", "warn", "检查失败：" + e.getMessage(), System.currentTimeMillis() - t);
        }
    }

    private Check checkDisk() {
        long t = System.currentTimeMillis();
        try {
            File root = new File(properties.getImages().getDir());
            if (!root.exists()) {
                return new Check("disk", "落盘数据目录", "fail", "目录不存在：" + root.getAbsolutePath(), System.currentTimeMillis() - t);
            }
            long free = root.getFreeSpace(), total = root.getTotalSpace();
            if (total <= 0) {
                return new Check("disk", "落盘数据目录", "warn", "无法读取磁盘容量（" + root.getAbsolutePath() + "）", System.currentTimeMillis() - t);
            }
            int pctUsed = (int) Math.round(100.0 * (total - free) / total);
            String detail = root.getAbsolutePath() + " 所在盘已用 " + pctUsed + "%（剩余 " + gb(free) + " / 共 " + gb(total) + "）";
            String level = pctUsed >= 90 ? "fail" : pctUsed >= 80 ? "warn" : "ok";
            return new Check("disk", "落盘数据目录", level, detail + (pctUsed >= 90 ? "：剩余不足 10%，源文件/图片/产物写入随时会失败" : ""), System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("disk", "落盘数据目录", "warn", "检查失败：" + e.getMessage(), System.currentTimeMillis() - t);
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
            return new Check("audit", "审计链完整性", level, detail, System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("audit", "审计链完整性", "fail", "校验跑不起来：" + e.getMessage(), System.currentTimeMillis() - t);
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
                return new Check("providers", "模型供应商", "fail", "没有启用的供应商——问答链路整体不可用", System.currentTimeMillis() - t);
            }
            String level = noKey > 0 ? "warn" : "ok";
            return new Check("providers", "模型供应商", level,
                    "启用 " + enabled + " 家" + (noKey > 0 ? "，其中 " + noKey + " 家未配 API Key（调用会失败）" : "，均配了凭据"),
                    System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("providers", "模型供应商", "warn", "检查失败：" + e.getMessage(), System.currentTimeMillis() - t);
        }
    }

    /** 登录安全态势：当前处于锁定态的账号（有人连撞密码，或真有人忘了密码） */
    private Check checkLockedAccounts() {
        long t = System.currentTimeMillis();
        try {
            Integer locked = jdbc.queryForObject(
                    "select count(*) from c_ai_user where locked_until is not null and locked_until > now()", Integer.class);
            int n = locked == null ? 0 : locked;
            return new Check("login", "登录安全态势", n > 0 ? "warn" : "ok",
                    n > 0 ? n + " 个账号处于登录锁定态（连续失败触发；管理员重置密码即解锁）" : "无锁定中的账号",
                    System.currentTimeMillis() - t);
        } catch (Exception e) {
            return new Check("login", "登录安全态势", "warn", "检查失败：" + e.getMessage(), System.currentTimeMillis() - t);
        }
    }

    private static String gb(long bytes) {
        return String.format("%.1f GB", bytes / 1073741824.0);
    }
}
