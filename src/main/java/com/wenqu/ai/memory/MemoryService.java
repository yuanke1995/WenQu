package com.wenqu.ai.memory;

import com.wenqu.ai.agentx.FrameworkSessions;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 个人长期记忆的读写与治理（面板 + 账号删除 + 硬上限）。
 *
 * <p>存储仍是框架的 {@code MEMORY.md} + {@code memory/YYYY-MM-DD.md}——这里只做“人看的那一层”：
 * 查看、编辑、清空、删除某天日账、按上限裁剪。不另建表、不双写，避免两套真源漂移。
 *
 * <p>边界：
 * <ul>
 *   <li>uid 只从登录态取，路径走 {@link MemoryPaths} 白名单；</li>
 *   <li>日账是 append-only 原始记录，面板只读；删除某天会丢当天原始记录，但不会回改 {@code MEMORY.md}；</li>
 *   <li>清空/删除后 {@code markConfigStale(uid)}，让常驻实例下一轮重建，避免旧的 consolidation 再把内容写回。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ConfigService configService;
    private final FrameworkSessions sessions;

    // ==================== 读 ====================

    public Map<String, Object> overview(String uid) {
        String u = MemoryPaths.requireUid(uid);
        Path root = MemoryPaths.root(configService, u);
        String content = readOrEmpty(root.resolve(MemoryPaths.MEMORY_MD));
        int maxTokens = maxTokens();
        int maxChars = maxChars(maxTokens);
        int tokens = MemoryBudget.estimateTokens(content);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("size", content.length());
        out.put("tokens", tokens);
        out.put("maxTokens", maxTokens);
        out.put("maxChars", maxChars);
        out.put("over", content.length() > maxChars);
        out.put("exists", !content.isEmpty());
        out.put("daily", listDaily(root));
        out.put("lastConsolidatedAt", lastConsolidatedAt(root));
        return out;
    }

    public Map<String, Object> content(String uid, String relativePath) {
        Path file = MemoryPaths.resolveContent(configService, uid, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new BizException(404, "记忆文件不存在");
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", relativePath);
            out.put("content", text);
            out.put("size", text.length());
            return out;
        } catch (IOException e) {
            log.warn("[MEMORY] 读取记忆文件失败 uid={} path={}: {}", uid, relativePath, e.getMessage());
            throw new BizException(500, "读取记忆文件失败");
        }
    }

    // ==================== 写 ====================

    public Map<String, Object> saveMemoryMd(String uid, String content) {
        String u = MemoryPaths.requireUid(uid);
        Path root = MemoryPaths.root(configService, u);
        String text = content == null ? "" : content;
        int maxChars = maxChars(maxTokens());
        if (text.length() > maxChars) {
            throw new BizException("记忆内容超过上限（" + text.length() + " / " + maxChars
                    + " 字符）。请先精简，或把超出部分留在日账里让系统自动合并。");
        }
        writeAtomic(root.resolve(MemoryPaths.MEMORY_MD), text);
        markStale(u);
        log.info("[AUDIT] 用户编辑长期记忆 uid={} chars={}", u, text.length());
        return overview(u);
    }

    /** 清空全部记忆（MEMORY.md + 日账 + 归档 + consolidation 游标）。 */
    public Map<String, Object> clearAll(String uid) {
        String u = MemoryPaths.requireUid(uid);
        Path root = MemoryPaths.root(configService, u);
        // 先关实例再删文件：否则正在跑的会话收尾时还会 flush，清空后又被写回一条。
        // 关掉不会丢历史（stateStore 里仍在），下一次提问按原会话重建。
        try {
            sessions.evictUser(u);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 清空记忆前关闭会话失败 uid={}: {}", u, e.getMessage());
        }
        try {
            Files.deleteIfExists(root.resolve(MemoryPaths.MEMORY_MD));
            deleteRecursively(root.resolve(MemoryPaths.MEMORY_DIR));
        } catch (IOException e) {
            log.warn("[MEMORY] 清空记忆失败 uid={}: {}", u, e.getMessage());
            throw new BizException(500, "清空记忆失败");
        }
        markStale(u);
        log.info("[AUDIT] 用户清空长期记忆 uid={}", u);
        return overview(u);
    }

    public Map<String, Object> deleteDaily(String uid, String date) {
        String u = MemoryPaths.requireUid(uid);
        LocalDate day;
        try {
            day = LocalDate.parse(date == null ? "" : date.trim());
        } catch (DateTimeParseException e) {
            throw new BizException("日期格式应为 YYYY-MM-DD");
        }
        Path file = MemoryPaths.resolveContent(configService, u, "memory/" + day + ".md");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[MEMORY] 删除日账失败 uid={} date={}: {}", u, day, e.getMessage());
            throw new BizException(500, "删除日账失败");
        }
        markStale(u);
        log.info("[AUDIT] 用户删除长期记忆日账 uid={} date={}", u, day);
        return overview(u);
    }

    /** 按当前 token 上限裁剪 MEMORY.md（面板「按上限裁剪」按钮；写入路径另有自动裁剪）。 */
    public Map<String, Object> compact(String uid) {
        String u = MemoryPaths.requireUid(uid);
        Path file = MemoryPaths.root(configService, u).resolve(MemoryPaths.MEMORY_MD);
        String text = readOrEmpty(file);
        int maxChars = maxChars(maxTokens());
        String trimmed = MemoryBudget.trim(text, maxChars);
        if (!trimmed.equals(text)) {
            writeAtomic(file, trimmed);
            markStale(u);
            log.info("[AUDIT] 用户按上限裁剪长期记忆 uid={} chars={} -> {}",
                    u, text.length(), trimmed.length());
        }
        return overview(u);
    }

    // ==================== 内部 ====================

    private int maxTokens() {
        return Math.max(500, configService.getInt("memory.maxMdTokens", ConfigDefaults.MEMORY_MAX_MD_TOKENS));
    }

    private static int maxChars(int maxTokens) {
        return Math.max(500, maxTokens) * 4;
    }

    private void markStale(String uid) {
        try {
            sessions.markConfigStale(uid);
        } catch (RuntimeException e) {
            log.warn("[MEMORY] 标记会话配置失效失败 uid={}: {}", uid, e.getMessage());
        }
    }

    private List<Map<String, Object>> listDaily(Path root) {
        Path dir = root.resolve(MemoryPaths.MEMORY_DIR);
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(400)
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("date", name.substring(0, name.length() - 3));
                        try {
                            row.put("size", Files.size(p));
                            row.put("updatedAt", Files.getLastModifiedTime(p).toInstant().toString());
                        } catch (IOException e) {
                            row.put("size", 0);
                            row.put("updatedAt", "");
                        }
                        out.add(row);
                    });
        } catch (IOException e) {
            log.warn("[MEMORY] 读取日账列表失败: {}", e.getMessage());
        }
        return out;
    }

    private String lastConsolidatedAt(Path root) {
        String value = readOrEmpty(root.resolve(MemoryPaths.MEMORY_DIR).resolve(".consolidation_state"));
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            log.warn("[MEMORY] 读取记忆文件失败 path={}: {}", file, e.getMessage());
            return "";
        }
    }

    private static void writeAtomic(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".memory-", ".tmp");
            Files.writeString(tmp, text == null ? "" : text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException(500, "写入记忆文件失败: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }
}
