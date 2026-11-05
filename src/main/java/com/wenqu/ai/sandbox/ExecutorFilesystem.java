package com.wenqu.ai.sandbox;

import com.wenqu.ai.executor.ExecutorRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把框架的文件/命令工具接到<b>用户电脑上的执行器</b>。
 *
 * <p>工作区 = 用户本机上的一个或多个真实目录。主目录（primary）是相对路径与命令执行的根；
 * 附加目录用绝对路径或别名访问。服务端不碰用户文件——只把「读/写/列/跑」经长轮询交给执行器，
 * 执行器在授权目录内执行；{@code danger-full-access} 档才允许越出这些目录。
 *
 * <p>路径口径：框架传来的是工作区内的路径（相对或带前导 /）。这里统一解析成执行器上的绝对路径；
 * 越界由执行器再独立校验一次（服务端解析 + 执行器围栏，两道）。
 */
public class ExecutorFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(ExecutorFilesystem.class);

    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** 工作区的一个源文件夹；{@code alias} 是虚拟路径首段（工作区文件页用它寻址）。 */
    public record Root(String alias, String path, boolean primary) {
    }

    private final ExecutorRegistry registry;
    private final String executorId;
    private final List<Root> roots;
    private final String primaryPath;
    /** danger-full-access：不再把路径收束在工作区目录内（执行器按 policy 放开） */
    private final boolean unrestricted;
    private final LocalJournalStorage journalStorage;
    /** 本会话当前轮的 turnId 供给方（调用期读取；实例可能跨轮被缓存，不能烘焙进构造器）。可为 null。 */
    private final java.util.function.Supplier<String> turnIdSupplier;

    public ExecutorFilesystem(ExecutorRegistry registry, String executorId, List<Root> roots, boolean unrestricted) {
        this(registry, executorId, roots, unrestricted, null);
    }

    public ExecutorFilesystem(ExecutorRegistry registry, String executorId, List<Root> roots, boolean unrestricted,
                              java.util.function.Supplier<String> turnIdSupplier) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.executorId = Objects.requireNonNull(executorId, "executorId is required");
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.primaryPath = this.roots.stream().filter(Root::primary).map(Root::path).findFirst()
                .orElse(this.roots.isEmpty() ? "/" : this.roots.get(0).path());
        this.unrestricted = unrestricted;
        this.turnIdSupplier = turnIdSupplier;
        this.journalStorage = new LocalJournalStorage(LocalJournalStorage.defaultRoot());
    }

    @Override
    public String id() {
        return "executor:" + executorId;
    }

    public LocalJournalStorage journalStorage() {
        return journalStorage;
    }

    @Override
    public io.agentscope.core.session.AtomicSessionStorage sessionStorage(RuntimeContext runtimeContext) {
        return journalStorage;
    }

    // ==================== 路径与调用 ====================

    private static String trimTrailing(String p) {
        String s = p == null ? "" : p.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 工作区路径 → 执行器上的绝对路径。
     * <p>空/相对路径以主目录为根；首段命中某个根别名时映射到那个根的剩余路径；
     * 已经是绝对路径的原样交给执行器（由它按 roots + policy 再校验）。
     */
    String resolve(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        String primary = trimTrailing(primaryPath);
        if (p.isEmpty() || ".".equals(p)) return primary;
        if (p.startsWith("/") || p.matches("^[A-Za-z]:/.*")) return p;
        for (Root r : roots) {
            if (r.alias() == null || r.alias().isBlank()) continue;
            String a = r.alias();
            if (p.equals(a)) return trimTrailing(r.path());
            if (p.startsWith(a + "/")) return trimTrailing(r.path()) + "/" + p.substring(a.length() + 1);
        }
        return primary + "/" + p;
    }

    private Map<String, Object> payload(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>(args);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (Root r : roots) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("alias", r.alias());
            one.put("path", r.path());
            one.put("primary", r.primary());
            rs.add(one);
        }
        out.put("roots", rs);
        out.put("policy", unrestricted ? "danger-full-access" : "workspace-write");
        // 每轮文件改动：变更型调用带上当前轮身份，执行器据此在改动前抓一份原文（内容不出那台电脑）。
        // 读类调用带不带都行——执行器只在写类操作上读它。
        if (turnIdSupplier != null) {
            String turnId = turnIdSupplier.get();
            if (turnId != null && !turnId.isBlank()) out.put("turnId", turnId);
        }
        return out;
    }

    private ExecutorRegistry.Result call(String op, Map<String, Object> args, long timeoutMs) {
        return registry.call(executorId, op, payload(args), timeoutMs);
    }

    private static String errorText(ExecutorRegistry.Result r) {
        String code = r.code() == null ? "E_IO" : r.code();
        String msg = r.message() == null ? "执行器返回失败" : r.message();
        return code + "：" + msg;
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    // ==================== 工具面 ====================

    @Override
    public ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        int seconds = timeoutSeconds == null || timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", command);
        args.put("timeoutSeconds", seconds);
        ExecutorRegistry.Result r = call("exec", args, (seconds + 30L) * 1000L);
        if (!r.ok()) {
            // 未执行成功一律 124：0 在这条链路上等于"成功"，模型会据此以为这步做完了
            return new ExecuteResponse(errorText(r), 124, false);
        }
        String output = r.data().get("output") == null ? "" : String.valueOf(r.data().get("output"));
        int exit = (int) asLong(r.data().get("exitCode"));
        boolean truncated = Boolean.TRUE.equals(r.data().get("truncated"));
        return new ExecuteResponse(output, exit, truncated);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", resolve(filePath));
        args.put("offset", offset);
        args.put("limit", limit);
        ExecutorRegistry.Result r = call("read", args, DEFAULT_TIMEOUT_MS);
        if (!r.ok()) return ReadResult.fail(errorText(r));
        String content = r.data().get("content") == null ? "" : String.valueOf(r.data().get("content"));
        String encoding = r.data().get("encoding") == null ? "utf-8" : String.valueOf(r.data().get("encoding"));
        return ReadResult.success(new FileData(content, encoding));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return writeWithExpectedHash(runtimeContext, filePath, content, null);
    }

    /**
     * 带内容哈希的写入：读-改-写之间若文件被用户/其他进程改过，执行器拒绝写入而不是静默覆盖。
     * 只有 edit 走这条路；write_file 是模型明确要整文件覆盖，没有“读到的原文”可比。
     */
    private WriteResult writeWithExpectedHash(RuntimeContext runtimeContext, String filePath,
                                              String content, String expectedHash) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", resolve(filePath));
        args.put("content", content == null ? "" : content);
        if (expectedHash != null) args.put("expectedHash", expectedHash);
        ExecutorRegistry.Result r = call("write", args, DEFAULT_TIMEOUT_MS);
        return r.ok() ? WriteResult.ok(filePath) : WriteResult.fail("Failed to write file '" + filePath + "': " + errorText(r));
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> out = new ArrayList<>();
        if (files == null) return out;
        for (Map.Entry<String, byte[]> f : files) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", resolve(f.getKey()));
            args.put("contentBase64", Base64.getEncoder().encodeToString(f.getValue()));
            ExecutorRegistry.Result r = call("writeBytes", args, DEFAULT_TIMEOUT_MS);
            out.add(r.ok() ? FileUploadResponse.success(f.getKey()) : FileUploadResponse.fail(f.getKey(), errorText(r)));
        }
        return out;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> out = new ArrayList<>();
        if (paths == null) return out;
        for (String path : paths) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", resolve(path));
            ExecutorRegistry.Result r = call("readBytes", args, DEFAULT_TIMEOUT_MS);
            if (!r.ok()) {
                out.add(FileDownloadResponse.fail(path, errorText(r)));
                continue;
            }
            String b64 = r.data().get("contentBase64") == null ? "" : String.valueOf(r.data().get("contentBase64"));
            out.add(FileDownloadResponse.success(path, Base64.getDecoder().decode(b64)));
        }
        return out;
    }

    /**
     * 精确字符串替换（edit_file 工具）。
     *
     * <p>不用框架默认实现——那条路把替换命令拼进 shell 交给 execute，既依赖本机 shell 与辅助程序，
     * 又丢掉问渠围栏与「改动前捕获」的语义。这里直接走已覆写的 read/write：同样的路径解析、
     * 同样的执行器围栏、同样挂每轮文件改动的账。
     */
    @Override
    public EditResult edit(RuntimeContext runtimeContext, String filePath, String oldString,
                           String newString, boolean replaceAll) {
        if (oldString == null || oldString.isEmpty()) {
            return EditResult.fail("old_string 不能为空");
        }
        ReadResult r = read(runtimeContext, filePath, 0, 0);
        if (!r.isSuccess()) {
            return EditResult.fail("Error: File '" + filePath + "' not found or unreadable");
        }
        String content = r.fileData() == null ? null : r.fileData().content();
        if (content == null) return EditResult.fail("Error: File '" + filePath + "' is empty");
        int count = countOccurrences(content, oldString);
        if (count == 0) {
            String brief = oldString.length() > 120 ? oldString.substring(0, 120) + "…" : oldString;
            return EditResult.fail("Error: String not found in file: '" + brief + "'");
        }
        if (count > 1 && !replaceAll) {
            return EditResult.fail("Error: Found " + count + " occurrences of the string. "
                    + "Set replace_all=true to replace all, or provide a longer unique string.");
        }
        String replacement = newString == null ? "" : newString;
        String replaced;
        if (replaceAll) {
            replaced = content.replace(oldString, replacement);
        } else {
            int at = content.indexOf(oldString);
            replaced = content.substring(0, at) + replacement + content.substring(at + oldString.length());
        }
        WriteResult w = writeWithExpectedHash(runtimeContext, filePath, replaced, sha256(content));
        return w.error() == null || w.error().isEmpty()
                ? EditResult.ok(filePath, count)
                : EditResult.fail(w.error());
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /**
     * 内容搜索（grep_files 工具）。执行器端递归做固定串匹配（跳依赖与隐藏目录、二进制跳过、
     * 结果截断），框架默认实现把 `grep -r` 拼进 shell——Windows 执行器上没有这条命路。
     */
    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        if (pattern == null || pattern.isEmpty()) {
            return GrepResult.fail("搜索内容不能为空");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", resolve(path == null || path.isBlank() ? "" : path));
        args.put("pattern", pattern);
        if (glob != null && !glob.isBlank()) args.put("glob", glob.startsWith("**/")
                ? glob.substring(3) : glob);
        ExecutorRegistry.Result r = call("grep", args, DEFAULT_TIMEOUT_MS * 2);
        if (!r.ok()) return GrepResult.fail(errorText(r));
        List<GrepMatch> matches = new ArrayList<>();
        Object raw = r.data().get("matches");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                matches.add(new GrepMatch(
                        String.valueOf(m.get("path")),
                        m.get("line") instanceof Number n ? n.intValue() : 0,
                        String.valueOf(m.get("text"))));
            }
        }
        return GrepResult.success(matches);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) return false;
        return call("stat", Map.of("path", resolve(path)), DEFAULT_TIMEOUT_MS).ok();
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String target = resolve(path);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", target);
        ExecutorRegistry.Result r = call("list", args, DEFAULT_TIMEOUT_MS);
        if (!r.ok()) return LsResult.fail(errorText(r) + "（" + target + "）");
        return LsResult.success(toFileInfos(r.data().get("entries"), false));
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String target = resolve(path);
        String namePattern = pattern != null && pattern.startsWith("**/") ? pattern.substring(3) : pattern;
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", target);
        args.put("pattern", namePattern == null || namePattern.isBlank() ? "*" : namePattern);
        ExecutorRegistry.Result r = call("find", args, DEFAULT_TIMEOUT_MS * 2);
        if (!r.ok()) return GlobResult.fail(errorText(r) + "（" + target + "）");
        return GlobResult.success(toFileInfos(r.data().get("entries"), true));
    }

    @SuppressWarnings("unchecked")
    private static List<FileInfo> toFileInfos(Object raw, boolean filesOnly) {
        List<FileInfo> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String path = m.get("path") == null ? "" : String.valueOf(m.get("path"));
            boolean isDir = Boolean.TRUE.equals(m.get("isDir")) || Boolean.TRUE.equals(m.get("isDirectory"));
            if (path.isEmpty()) continue;
            if (isDir) {
                if (!filesOnly) out.add(FileInfo.ofDir(path, asLong(m.get("mtimeMs"))));
            } else {
                out.add(FileInfo.ofFile(path, asLong(m.get("size")), asLong(m.get("mtimeMs"))));
            }
        }
        return out;
    }

    /** 主目录绝对路径（提示词/日志用）。 */
    public String rootPath() {
        return primaryPath;
    }
}
