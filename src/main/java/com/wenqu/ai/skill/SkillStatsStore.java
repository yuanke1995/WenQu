package com.wenqu.ai.skill;

import com.wenqu.ai.sandbox.SandboxPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 技能使用统计（按 uid 落盘到 {@code user-data/skill-stats}）。
 *
 * <h3>为什么是文件而不是数据库</h3>
 * 技能读取是热路径（每轮模型按需读 N 条），统计写入必须异步且不能阻塞问答。
 * 按 uid 一个 JSON 文件、单线程 executor + 每 uid 一把锁，写入成本与
 * {@link UserSkillStore} 的 {@code .disabled} 清单同级；不引入表结构变更，
 * 容器重建不丢（随 {@code user-data/} 一起备份）。
 *
 * <h3>数据形状</h3>
 * <pre>
 * {
 *   "form-subtable": {
 *     "days": {"2026-11-10": 3, "2026-11-09": 1, ...},
 *     "lastUsedAt": 1730000000000,
 *     "recent": [{"sessionId":"...", "at": 1730000000000}, ...]
 *   },
 *   ...
 * }
 * </pre>
 * 天数保留 60 天；recent 最多 20 条；每个 uid 最多 500 个技能条目。
 *
 * <h3>线程模型</h3>
 * 调用方（引擎）在问答线程上调用 {@link #recordRead}，该方法把写入委托给单线程 executor，
 * 返回前不抛异常（写入失败只记日志，不影响问答）。读路径（{@link #summary}/{@link #detail}）
 * 走内存快照，{@link #flush()} 在 executor 里定期跑。
 */
@Service
public class SkillStatsStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStatsStore.class);

    private static final int MAX_SKILLS_PER_UID = 500;
    private static final int MAX_RECENT = 20;
    private static final int RETENTION_DAYS = 60;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path root;
    private final ConcurrentHashMap<String, Object> uidLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> memory = new ConcurrentHashMap<>();
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-stats-io");
        t.setDaemon(true);
        return t;
    });

    public SkillStatsStore(
            @Value("${wenqu.skills.stats-dir:user-data/skill-stats}") String statsDir) {
        this.root = Paths.get(statsDir == null || statsDir.isBlank() ? "user-data/skill-stats" : statsDir)
                .toAbsolutePath().normalize();
        try { Files.createDirectories(root); } catch (IOException ignored) {}
        io.scheduleWithFixedDelay(this::flushAll, 5, 30, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushAll));
    }

    /** 记录一次技能读取（问答线程调用，永不抛异常）。 */
    public void recordRead(String uid, String name, String sessionId) {
        if (uid == null || uid.isBlank() || name == null || name.isBlank()) return;
        try {
            io.execute(() -> {
                try {
                    touch(uid, name, sessionId);
                } catch (Exception e) {
                    log.warn("[SKILL-STATS] 记录失败 uid={} skill={}: {}", uid, name, e.toString());
                }
            });
        } catch (Exception e) {
            // executor 已关闭（JVM 退出）：忽略
        }
    }

    /** 某用户全部技能的 7/30 天统计（前端列表页用）。 */
    public Map<String, Map<String, Object>> summary(String uid) {
        Map<String, Object> data = load(uid);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            out.put(e.getKey(), summarize(m));
        }
        return out;
    }

    /** 单条技能的 30 天明细（抽屉「使用数据」Tab 用）。 */
    public Map<String, Object> detail(String uid, String name) {
        Map<String, Object> data = load(uid);
        Object raw = data.get(name);
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(summarize(m));
        // 30 天序列
        List<Map<String, Object>> series = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 29; i >= 0; i--) {
            String d = today.minusDays(i).format(DATE_FMT);
            int c = 0;
            Object days = m.get("days");
            if (days instanceof Map<?, ?> dm) {
                Object v = dm.get(d);
                if (v instanceof Number n) c = n.intValue();
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", d);
            p.put("count", c);
            series.add(p);
        }
        out.put("series30d", series);
        // 最近触发
        Object recent = m.get("recent");
        if (recent instanceof List<?> rl) {
            List<Map<String, Object>> outRecent = new ArrayList<>();
            for (Object o : rl) {
                if (o instanceof Map<?, ?> r) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", r.get("sessionId"));
                    item.put("at", r.get("at"));
                    outRecent.add(item);
                }
            }
            out.put("recent", outRecent);
        } else {
            out.put("recent", List.of());
        }
        return out;
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String uid) {
        Map<String, Object> cached = memory.get(uid);
        if (cached != null) return cached;
        Path file = fileFor(uid);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            memory.put(uid, empty);
            return empty;
        }
        try {
            String text = Files.readString(file);
            Map<String, Object> parsed = (Map<String, Object>) com.alibaba.fastjson2.JSON.parse(text);
            Map<String, Object> safe = parsed == null ? new LinkedHashMap<>() : parsed;
            memory.put(uid, safe);
            return safe;
        } catch (Exception e) {
            log.warn("[SKILL-STATS] 读取失败 uid={}: {}", uid, e.toString());
            Map<String, Object> empty = new LinkedHashMap<>();
            memory.put(uid, empty);
            return empty;
        }
    }

    private void touch(String uid, String name, String sessionId) {
        Object lock = uidLocks.computeIfAbsent(uid, k -> new Object());
        synchronized (lock) {
            Map<String, Object> data = load(uid);
            if (data.size() >= MAX_SKILLS_PER_UID && !data.containsKey(name)) {
                log.warn("[SKILL-STATS] uid={} 技能条目已达上限 {}，跳过 {}", uid, MAX_SKILLS_PER_UID, name);
                return;
            }
            Object raw = data.computeIfAbsent(name, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("days", new LinkedHashMap<>());
                m.put("recent", new ArrayList<>());
                return m;
            });
            if (!(raw instanceof Map<?, ?> r)) return;
            Map<String, Object> m = (Map<String, Object>) r;
            String today = LocalDate.now().format(DATE_FMT);
            Object days = m.get("days");
            if (days instanceof Map<?, ?> dm) {
                Map<String, Object> daysMap = new LinkedHashMap<>((Map<String, Object>) dm);
                Object cur = daysMap.get(today);
                int n = cur instanceof Number nb ? nb.intValue() : 0;
                daysMap.put(today, n + 1);
                // 保留近 60 天
                LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
                daysMap.keySet().removeIf(k -> {
                    try { return LocalDate.parse(k).isBefore(cutoff); } catch (Exception ignored) { return false; }
                });
                m.put("days", daysMap);
            }
            m.put("lastUsedAt", System.currentTimeMillis());
            // recent 追加（去重同 sessionId 只显示最新）
            Object recent = m.get("recent");
            if (recent instanceof List<?> rl) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Object o : rl) {
                    if (o instanceof Map<?, ?> r2) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sessionId", r2.get("sessionId"));
                        item.put("at", r2.get("at"));
                        list.add(item);
                    }
                }
                list.removeIf(x -> sessionId != null && sessionId.equals(x.get("sessionId")));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sessionId", sessionId);
                entry.put("at", System.currentTimeMillis());
                list.add(0, entry);
                if (list.size() > MAX_RECENT) list = list.subList(0, MAX_RECENT);
                m.put("recent", list);
            }
            memory.put(uid, data);
        }
    }

    private void flushAll() {
        for (Map.Entry<String, Map<String, Object>> e : memory.entrySet()) {
            try {
                Path tmp = Files.createTempFile(root, "stats-", ".tmp");
                String text = com.alibaba.fastjson2.JSON.toJSONString(e.getValue());
                Files.writeString(tmp, text);
                Files.move(tmp, fileFor(e.getKey()),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) {
                log.warn("[SKILL-STATS] 落盘失败 uid={}: {}", e.getKey(), ex.toString());
            }
        }
    }

    private Path fileFor(String uid) {
        return root.resolve(SandboxPaths.uidDirname(uid) + ".json");
    }

    /** 单条技能的 7/30 天汇总（不含序列）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> summarize(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        int reads7d = 0, reads30d = 0;
        LocalDate today = LocalDate.now();
        Object days = m.get("days");
        if (days instanceof Map<?, ?> dm) {
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                String k = String.valueOf(e.getKey());
                int v = e.getValue() instanceof Number nb ? nb.intValue() : 0;
                try {
                    LocalDate d = LocalDate.parse(k);
                    long ago = java.time.temporal.ChronoUnit.DAYS.between(d, today);
                    if (ago >= 0 && ago < 7) reads7d += v;
                    if (ago >= 0 && ago < 30) reads30d += v;
                } catch (Exception ignored) {}
            }
        }
        out.put("reads7d", reads7d);
        out.put("reads30d", reads30d);
        out.put("lastUsedAt", m.get("lastUsedAt"));
        // 7 天序列（列表页 sparkline 用）
        List<Integer> s7 = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String d = today.minusDays(i).format(DATE_FMT);
            int c = 0;
            if (days instanceof Map<?, ?> dm) {
                Object v = dm.get(d);
                if (v instanceof Number n) c = n.intValue();
            }
            s7.add(c);
        }
        out.put("series7d", s7);
        return out;
    }
}
