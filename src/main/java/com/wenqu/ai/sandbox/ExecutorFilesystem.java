package com.wenqu.ai.sandbox;

import com.wenqu.ai.executor.ExecutorRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把框架的文件/命令工具接到<b>用户电脑上的执行器</b>。
 *
 * <p>与 {@link ProvisionerSandboxFilesystem} 同一层：都是 {@link BaseSandboxFilesystem} 的实现，
 * 上层工具、权限档位、审批卡完全不变；区别只是执行发生在本机执行器而不是服务器的沙盒容器。
 * 服务端不碰用户文件——它只把「读/写/列/跑」这些调用经长轮询交给执行器，执行器在授权目录内执行。
 *
 * <p>路径口径：框架传来的是工作区内的路径（相对或带前导 /）。这里统一解析成执行器上的绝对路径，
 * 越界由执行器再独立校验一次（服务端解析 + 执行器白名单，两道）。
 */
public class ExecutorFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(ExecutorFilesystem.class);

    /** 单次调用的等待上限：执行器侧按 timeoutSeconds 掐命令，这里再加网络余量。 */
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final ExecutorRegistry registry;
    private final String executorId;
    private final String rootPath;
    private final LocalJournalStorage journalStorage;

    public ExecutorFilesystem(ExecutorRegistry registry, String executorId, String rootPath) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.executorId = Objects.requireNonNull(executorId, "executorId is required");
        this.rootPath = rootPath == null || rootPath.isBlank() ? "/" : rootPath;
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

    /** 工作区路径 → 执行器上的绝对路径。带前导 / 的按工作区相对处理（框架就是这样传的）。 */
    String resolve(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        String root = rootPath.endsWith("/") ? rootPath.substring(0, rootPath.length() - 1) : rootPath;
        if (p.isEmpty() || ".".equals(p)) return root;
        if (p.equals(root) || p.startsWith(root + "/")) return p;
        while (p.startsWith("/")) p = p.substring(1);
        return root + "/" + p;
    }

    private ExecutorRegistry.Result call(String op, Map<String, Object> args, long timeoutMs) {
        Map<String, Object> payload = new LinkedHashMap<>(args);
        payload.put("root", rootPath);
        return registry.call(executorId, op, payload, timeoutMs);
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
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", resolve(filePath));
        args.put("content", content == null ? "" : content);
        ExecutorRegistry.Result r = call("write", args, DEFAULT_TIMEOUT_MS);
        return r.ok() ? WriteResult.ok(filePath) : WriteResult.fail("Failed to write file '" + filePath + "': " + errorText(r));
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

    /** 供上层判断"这个路径是不是工作区内"，不参与工具调用。 */
    public String rootPath() {
        return rootPath;
    }
}
