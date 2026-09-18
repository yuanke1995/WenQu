package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.config.UserConfigService;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户级 Memory 的授权写入与主 Agent 上下文读取。
 *
 * <p>由参考实现的 services/memory_service.py 逐函数翻译：prompt 前缀读取、
 * 由当前有效顶层 Run 授权的原子更新（重复内容幂等/replaces 唯一匹配替换）、
 * Memory 已启用用户的历史搜索与读取。
 *
 * <p>必要替换：{@code pg_advisory_xact_lock(hashtextextended("user-memory:{uid}", 0))} →
 * MySQL 命名锁 {@code GET_LOCK}/{@code RELEASE_LOCK}（数值键不变，锁名含 uid）；
 * {@code asyncio.to_thread} → 调用方线程直接执行；ValueError → {@link IllegalArgumentException}；
 * {@code UserConfig.load(db, uid)} → {@link UserConfigService#load(String)}。
 */
@Service
public class MemoryService {

    public static final String MEMORY_PATH = "/agents/MEMORY.md";
    public static final int MEMORY_PROMPT_MAX_BYTES = 24 * 1024;
    public static final int MEMORY_FILE_MAX_BYTES = 128 * 1024;
    public static final int MEMORY_ARGUMENT_MAX_BYTES = 4 * 1024;

    private final UserConfigService userConfigService;
    private final AgentRunRepository agentRunRepository;
    private final ConversationRepository conversationRepository;
    private final JdbcTemplate jdbc;

    public MemoryService(
            UserConfigService userConfigService,
            AgentRunRepository agentRunRepository,
            ConversationRepository conversationRepository,
            JdbcTemplate jdbc) {
        this.userConfigService = userConfigService;
        this.agentRunRepository = agentRunRepository;
        this.conversationRepository = conversationRepository;
        this.jdbc = jdbc;
    }

    /** 开关开启时读取当前用户 Memory 的有界 prompt 前缀。 */
    public String loadMemoryPrompt(String uid) {
        String normalizedUid = uid == null ? "" : String.valueOf(uid).strip();
        if (normalizedUid.isEmpty()) {
            return null;
        }

        UserConfigService.UserConfig config = userConfigService.load(normalizedUid);
        if (!config.getSchema().isEnableMemory()) {
            return null;
        }

        Workspace.FilePrefix prefix;
        try {
            prefix = new Workspace(normalizedUid)
                    .readAuthorizedFilePrefix(MEMORY_PATH, MEMORY_PROMPT_MAX_BYTES);
        } catch (Workspace.NoSuchFileRuntime exc) {
            // 用户可在 Workspace 中删除 MEMORY.md，视为无记忆而非错误
            return null;
        }
        String prompt = new String(prefix.content(), StandardCharsets.UTF_8).strip();
        if (prompt.isEmpty()) {
            return null;
        }
        if (prefix.truncated()) {
            prompt = prompt + "\n\n[MEMORY.md 内容已截断；可在 Workspace 中整理源文件]";
        }
        return prompt;
    }

    /** 由当前有效顶层 Run 原子更新固定用户 Memory 文件。 */
    public Map<String, Object> rememberMemory(
            String uid,
            String threadId,
            String runId,
            String requestId,
            String workerId,
            String content,
            String replaces) {
        String normalizedUid = uid == null ? "" : String.valueOf(uid).strip();
        String normalizedThreadId = threadId == null ? "" : String.valueOf(threadId).strip();
        String normalizedRunId = runId == null ? "" : String.valueOf(runId).strip();
        String normalizedRequestId = requestId == null ? "" : String.valueOf(requestId).strip();
        String normalizedWorkerId = workerId == null ? "" : String.valueOf(workerId).strip();
        if (normalizedUid.isEmpty() || normalizedThreadId.isEmpty() || normalizedRunId.isEmpty()
                || normalizedRequestId.isEmpty() || normalizedWorkerId.isEmpty()) {
            throw new IllegalArgumentException("Memory 写入缺少可信运行身份");
        }

        String normalizedContent = validateArgument(content, "content").strip();
        String normalizedReplaces = replaces == null ? null : validateArgument(replaces, "replaces");

        String lockName = "wenqu:user-memory:" + normalizedUid;
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, lockName);
        boolean held = acquired != null && acquired == 1;
        try {
            var run = agentRunRepository.lockMemoryWrite(
                    normalizedRunId,
                    normalizedUid,
                    normalizedWorkerId,
                    normalizedThreadId,
                    normalizedRequestId,
                    DateTimeUtils.utcNowNaive());
            if (run == null) {
                throw new IllegalArgumentException("Memory 写入对应的 AgentRun 不存在");
            }

            UserConfigService.UserConfig config = userConfigService.load(normalizedUid);
            if (!config.getSchema().isEnableMemory()) {
                throw new IllegalArgumentException("Memory 已关闭");
            }

            String current = readMemoryFile(normalizedUid);
            MemoryUpdate update = buildMemoryUpdate(current, normalizedContent, normalizedReplaces);
            long size;
            if ("unchanged".equals(update.status())) {
                size = current.getBytes(StandardCharsets.UTF_8).length;
            } else {
                Map<String, Object> metadata = replaceMemoryFile(normalizedUid, update.updated());
                size = ((Number) metadata.get("size")).longValue();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", update.status());
            result.put("path", MEMORY_PATH);
            result.put("size", size);
            result.put("start_line", update.startLine());
            result.put("end_line", update.endLine());
            return result;
        } finally {
            if (held) {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
            }
        }
    }

    /** 搜索 Memory 已启用用户的可见主 Agent 历史。 */
    public Map<String, Object> searchThreadMessages(String uid, String query, int limit) {
        String normalizedUid = uid == null ? "" : String.valueOf(uid).strip();
        if (normalizedUid.isEmpty()) {
            throw new IllegalArgumentException("历史搜索缺少用户身份");
        }
        UserConfigService.UserConfig config = userConfigService.load(normalizedUid);
        if (!config.getSchema().isEnableMemory()) {
            throw new IllegalArgumentException("Memory 已关闭");
        }
        return conversationRepository.searchMemoryMessages(normalizedUid, query, limit);
    }

    /** 读取 Memory 已启用用户的可见主 Agent 历史。 */
    public Map<String, Object> readThreadMessages(
            String uid, String threadId, Integer messageId, int limit, boolean includeTools) {
        String normalizedUid = uid == null ? "" : String.valueOf(uid).strip();
        if (normalizedUid.isEmpty()) {
            throw new IllegalArgumentException("历史读取缺少用户身份");
        }
        UserConfigService.UserConfig config = userConfigService.load(normalizedUid);
        if (!config.getSchema().isEnableMemory()) {
            throw new IllegalArgumentException("Memory 已关闭");
        }
        return conversationRepository.readMemoryMessages(
                normalizedUid, threadId, messageId, limit, includeTools);
    }

    // ==================== 内部工具 ====================

    private String validateArgument(String value, String name) {
        String raw = value == null ? "" : String.valueOf(value);
        if (raw.strip().isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MEMORY_ARGUMENT_MAX_BYTES) {
            throw new IllegalArgumentException(name + " 超过 " + MEMORY_ARGUMENT_MAX_BYTES + " bytes");
        }
        return raw;
    }

    private String readMemoryFile(String uid) {
        Workspace.FilePrefix prefix;
        try {
            prefix = new Workspace(uid).readAuthorizedFilePrefix(MEMORY_PATH, MEMORY_FILE_MAX_BYTES);
        } catch (Workspace.NoSuchFileRuntime exc) {
            // 文件被用户删除时按空记忆处理，写入路径会重建
            return "";
        }
        if (prefix.truncated()) {
            throw new IllegalArgumentException("memory_too_large: 请先在 Workspace 中整理 MEMORY.md");
        }
        return strictUtf8(prefix.content());
    }

    /** 参考 content.decode("utf-8")：严格模式，非法序列抛错（Java 默认替换 U+FFFD 会掩盖问题）。 */
    static String strictUtf8(byte[] bytes) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exc) {
            throw new IllegalArgumentException("memory_invalid_encoding: MEMORY.md 必须使用 UTF-8");
        }
    }

    /** _build_memory_update 的返回：(updated, status, start_line, end_line)。 */
    public record MemoryUpdate(String updated, String status, int startLine, int endLine) {}

    private MemoryUpdate buildMemoryUpdate(String current, String content, String replaces) {
        if (replaces == null) {
            String normalizedContent = normalizeForDuplicateCheck(content);
            if (normalizeForDuplicateCheck(current).contains(normalizedContent)) {
                int[] range = findContentLineRange(current, content);
                return new MemoryUpdate(current, "unchanged", range[0], range[1]);
            }

            String appendedContent = content.strip();
            int contentLineCount = appendedContent.split("\n", -1).length;
            if (appendedContent.isEmpty()) {
                contentLineCount = 0;
            }
            String trimmedCurrent = current.stripTrailing();
            String updated;
            int startLine;
            if (trimmedCurrent.isEmpty()) {
                updated = appendedContent + "\n";
                startLine = 1;
            } else {
                updated = trimmedCurrent + "\n\n" + appendedContent + "\n";
                String prefix = trimmedCurrent + "\n\n";
                startLine = countLinesBefore(prefix) + 1;
            }
            int endLine = startLine + Math.max(0, contentLineCount - 1);
            return new MemoryUpdate(updated, "updated", startLine, endLine);
        }

        int matches = countOccurrences(current, replaces);
        if (matches != 1) {
            throw new IllegalArgumentException(
                    "memory_conflict: replaces 必须唯一匹配，当前匹配 " + matches + " 处");
        }

        int splitAt = current.indexOf(replaces);
        String before = current.substring(0, splitAt);
        String after = current.substring(splitAt + replaces.length());
        int startLine = countLinesBefore(before) + 1;
        String updated = before + content + after;
        int endLine = startLine + Math.max(0, countLinesStrict(content) - 1);
        return new MemoryUpdate(updated, "updated", startLine, endLine);
    }

    private Map<String, Object> replaceMemoryFile(String uid, String content) {
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MEMORY_FILE_MAX_BYTES) {
            throw new IllegalArgumentException("memory_too_large: 更新后的 MEMORY.md 超过大小上限");
        }
        return new Workspace(uid).replaceAuthorizedFile(MEMORY_PATH, encoded);
    }

    static String normalizeForDuplicateCheck(String value) {
        // Python " ".join(value.split())：按 Unicode 空白切分再单空格连接
        StringBuilder builder = new StringBuilder();
        boolean inWhitespace = false;
        boolean any = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isPythonWhitespace(c)) {
                inWhitespace = any;
            } else {
                if (inWhitespace) {
                    builder.append(' ');
                }
                builder.append(c);
                inWhitespace = false;
                any = true;
            }
        }
        return builder.toString();
    }

    /** Python str.isspace() 的常用集合（str.split() 的切分集）。 */
    private static boolean isPythonWhitespace(char c) {
        return c == 0x09 || c == 0x0A || c == 0x0B || c == 0x0C || c == 0x0D
                || c == 0x1C || c == 0x1D || c == 0x1E || c == 0x1F || c == 0x20
                || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** Python str.splitlines() 行数（含 \r\n/\u2028 等分隔；Java 侧覆盖常用分隔符）。 */
    private static int countLinesStrict(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        String[] lines = value.split("\n", -1);
        int count = lines.length;
        // 末尾换行不产生新行（Python splitlines 行为）
        if (value.endsWith("\n") && !value.endsWith("\r\n")) {
            count -= 1;
        } else if (value.endsWith("\r\n")) {
            count -= 1;
        }
        return Math.max(count, 0);
    }

    /** prefix 中换行计数（对应 before.count("\n")）。 */
    private static int countLinesBefore(String prefix) {
        int count = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (prefix.charAt(i) == '\n') {
                count += 1;
            }
        }
        return count;
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count += 1;
            index += target.length();
        }
        return count;
    }

    private static int[] findContentLineRange(String text, String target) {
        String trimmedTarget = target.strip();
        if (!trimmedTarget.isEmpty() && text.contains(trimmedTarget)) {
            int at = text.indexOf(trimmedTarget);
            String before = text.substring(0, at);
            int startLine = countLinesBefore(before) + 1;
            int endLine = startLine + Math.max(0, countLinesStrict(trimmedTarget) - 1);
            return new int[] {startLine, endLine};
        }

        // Python splitlines：覆盖 \n、\r\n、\r、\u2028、\u2029、\x0b、\x0c、\x85
        List<String> lines = splitLines(text);
        String normalizedTarget = normalizeForDuplicateCheck(target);
        for (int idx = 0; idx < lines.size(); idx++) {
            if (normalizeForDuplicateCheck(lines.get(idx)).contains(normalizedTarget)) {
                return new int[] {idx + 1, idx + 1};
            }
        }
        return new int[] {1, Math.max(1, lines.size())};
    }

    /** Python str.splitlines() 的 Java 等价（参考 CPython 识别的行边界）。 */
    static List<String> splitLines(String value) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\n') {
                lines.add(value.substring(start, i));
                i += 1;
                start = i;
            } else if (c == '\r') {
                lines.add(value.substring(start, i));
                i += (i + 1 < value.length() && value.charAt(i + 1) == '\n') ? 2 : 1;
                start = i;
            } else if (c == 0x0B || c == 0x0C || c == 0x1C || c == 0x1D || c == 0x1E
                    || c == 0x85 || c == 0x2028 || c == 0x2029) {
                lines.add(value.substring(start, i));
                i += 1;
                start = i;
            } else {
                i += 1;
            }
        }
        if (start < value.length()) {
            lines.add(value.substring(start));
        }
        return lines;
    }
}
