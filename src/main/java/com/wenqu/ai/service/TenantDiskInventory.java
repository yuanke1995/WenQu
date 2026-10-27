package com.wenqu.ai.service;

import com.wenqu.ai.memory.MemoryPaths;
import com.wenqu.ai.sandbox.SandboxPaths;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个租户在<b>库里</b>与<b>盘上</b>的全部落点清单（Phase 4 刀三 · 备份与按租户恢复）。
 *
 * <p><b>为什么要这份清单</b>：租户的隔离是行级归属，而磁盘目录按 {@code uid}/uuid 分片
 * （记忆 {@code agentscope/{uid}}、沙盒 {@code shared/{uid}/workspace}、产物、附件、外溢原文…），
 * 目录名里没有租户段。要按租户备份/恢复，就得有人把「这个租户的成员对应哪些目录」摊出来——
 * 这段映射规则只活在各服务里，在脚本里再抄一份必然漂移，所以由应用自己报。
 *
 * <p>刻意<b>不</b>把目录改成 {@code tenants/{id}/...}：那会让存量部署升级后找不到自己的记忆文件
 * （静默丢数据），换来的只有备份便利，而便利由这份清单提供。取舍记录见
 * {@code docs/enterprise-upgrade-plan.md} §4.5 刀二「刻意没做」。
 *
 * <p>清单只列<b>存在</b>与<b>不存在</b>，不判定该不该存在：缺目录可能是这个人从没写过文件，
 * 也可能是路径配错了——两者都由运维现场判断，脚本不替人下结论。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDiskInventory {

    private final JdbcTemplate jdbc;
    private final ConfigService configService;
    private final ChatUploadService chatUploadService;
    private final ToolSpillService toolSpillService;
    private final ArtifactService artifactService;
    private final ProjectFileService projectFileService;
    private final Environment environment;

    /** 单个目录的盘上事实 */
    private static Map<String, Object> entry(String kind, Path p) {
        Map<String, Object> m = new LinkedHashMap<>();
        String abs = p.toAbsolutePath().normalize().toString();
        m.put("kind", kind);
        m.put("path", abs);
        boolean exists = Files.exists(p);
        m.put("exists", exists);
        long files = 0;
        long bytes = 0;
        if (exists && Files.isDirectory(p)) {
            try (var stream = Files.walk(p)) {
                List<Path> regular = new ArrayList<>();
                stream.filter(Files::isRegularFile).limit(200_000).forEach(regular::add);
                files = regular.size();
                for (Path f : regular) {
                    try {
                        bytes += Files.size(f);
                    } catch (IOException ignored) {
                        // 单个文件读不到大小不影响清单可用性（备份时真读不到会当场报错）
                    }
                }
            } catch (Exception e) {
                m.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } else if (exists) {
            files = 1;
            try {
                bytes = Files.size(p);
            } catch (IOException ignored) {
            }
        }
        m.put("files", files);
        m.put("bytes", bytes);
        return m;
    }

    /**
     * 某租户的完整落点清单：成员、按租户归属的表与行数、盘上目录、Redis 键形态、以及不随租户走的全局部分。
     * <p>整段在平台视图里跑——它本来就是「按 tenant_id 显式过滤」的跨租户取数，
     * 再套一层本租户条件会把别家租户的清单读成空。
     */
    public Map<String, Object> of(String tenantId) {
        String t = tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT_TENANT : tenantId.trim();
        return TenantContext.asPlatform(() -> ofScoped(t));
    }

    private Map<String, Object> ofScoped(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", tenantId);

        List<Map<String, Object>> memberRows = jdbc.queryForList(
                "select uid, username, department_id, status from c_ai_user where tenant_id = ? order by uid",
                tenantId);
        List<String> uids = new ArrayList<>();
        for (Map<String, Object> r : memberRows) {
            uids.add(String.valueOf(r.get("uid")));
        }
        out.put("members", memberRows);

        // 盘上目录：每个成员五处 + 该租户的知识库/会话目录（会话与工作区都在本人 workspace 之下）
        List<Map<String, Object>> dirs = new ArrayList<>();
        for (String uid : uids) {
            dirs.add(dirEntry("记忆（MEMORY.md 与日账）", uid, MemoryPaths.root(configService, uid)));
            dirs.add(dirEntry("沙盒工作区（工作区与会话目录）", uid, projectFileService.workspaceRootOf(uid)));
            dirs.add(dirEntry("产物", uid, artifactService.userArtifactDir(uid)));
            dirs.add(dirEntry("聊天附件", uid, chatUploadService.dirOf(uid)));
            dirs.add(dirEntry("工具外溢原文", uid, toolSpillService.dirOf(uid)));
            dirs.add(dirEntry("自装技能", uid, Path.of(
                    environment.getProperty("wenqu.skills.users-dir", "user-data/skills-users"),
                    SandboxPaths.uidDirname(uid))));
        }
        out.put("files", dirs);
        out.put("filesBytes", dirs.stream().mapToLong(m -> ((Number) m.get("bytes")).longValue()).sum());
        out.put("filesMissing", dirs.stream().filter(m -> !Boolean.TRUE.equals(m.get("exists"))).count());

        // 表与行数：凡带 tenant_id 列的表都列出来（新表自动进清单，不在这份代码里点名）
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.columns "
                        + "where table_schema = database() and column_name = 'tenant_id' "
                        + "group by table_name order by table_name", String.class);
        List<Map<String, Object>> perTable = new ArrayList<>();
        long totalRows = 0;
        for (String table : tables) {
            if ("c_ai_tenant".equals(table)) continue;
            try {
                Long n = jdbc.queryForObject("select count(*) from `" + table + "` where tenant_id = ?",
                        Long.class, tenantId);
                long rows = n == null ? 0 : n;
                totalRows += rows;
                if (rows > 0) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("table", table);
                    m.put("rows", rows);
                    perTable.add(m);
                }
            } catch (Exception e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("table", table);
                m.put("error", e.getMessage());
                perTable.add(m);
            }
        }
        out.put("tables", perTable);
        out.put("rows", totalRows);

        // Redis 侧形态（值本身可由库重算，向量索引除外——它要按库重建）
        try {
            List<String> kbIds = jdbc.queryForList("select kb_id from c_ai_knowledge_base where tenant_id = ?",
                    String.class, tenantId);
            List<String> keyPatterns = new ArrayList<>();
            for (String kb : kbIds) {
                keyPatterns.add(KbVectorStoreRegistry.KB_KEY_PREFIX + kb + "*");
            }
            keyPatterns.add("wenqu:ratelimit:*:t:" + tenantId + ":*");
            out.put("redisKeyPatterns", keyPatterns);
            out.put("kbCount", kbIds.size());
        } catch (Exception e) {
            // 这一段读不动不影响清单其余部分可用，但必须留痕：空列表会被当成「这个租户没有库」
            out.put("redisKeyPatterns", List.of());
            out.put("redisKeyPatternsError", e.getMessage());
            log.warn("[TENANT] 租户 {} 的向量索引键枚举失败: {}", tenantId, e.getMessage());
        }

        out.put("notInTenantScope", List.of(
                "模型供应商与模型库（平台级共享，恢复本租户不需要）",
                "菜单与角色定义（平台级共享）",
                "平台参数 c_ai_config（平台级共享）",
                "默认租户之外没有归属的全局行：见 restore 时的 --keep-global 说明"));
        return out;
    }

    private Map<String, Object> dirEntry(String kind, String uid, Path p) {
        Map<String, Object> m = entry(kind, p);
        m.put("uid", uid);
        return m;
    }
}
