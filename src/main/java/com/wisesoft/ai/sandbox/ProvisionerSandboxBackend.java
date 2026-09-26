package com.wisesoft.ai.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.HexFormat;

/**
 * 沙盒 backend（对应参考实现 backends/sandbox/backend.py 的 {@code ProvisionerSandboxBackend}）。
 *
 * <p>逐字对齐：模块级常量与错误文案、路径/权限纯逻辑（_normalize_path / _is_same_or_child /
 * _path_overlaps_root / _glob_for_search_root / _can_read_path / _can_list_path / _can_write_path /
 * _readable_search_paths / _filter_readable_*）、文件分类（_read_file_kind / _get_file_type）、
 * 行窗口（_read_window）、结果塑形（_text_read_result / _normalize_read_content / _describe_read_error /
 * _looks_like_binary / _is_missing_file_error）、安全脚本生成（_ensure_parent_directory /
 * upload_authorized_file_from_path / list_authorized_directory / create_authorized_directory /
 * delete_authorized_path / _download_scoped_file_to_path）与错误映射（_raise_authorized_path_operation_error）。
 *
 * <h3>能力差异（显式标注）</h3>
 * <ul>
 *   <li>参考实现继承 deepagents 的沙盒 backend 基类并经 {@code agent_sandbox} Python SDK 持有一个
 *       {@code Sandbox} / {@code AsyncSandbox} 远程 client。本工程未照搬 deepagents 框架，I/O 动词
 *       （read_file / shell.exec_command / list_path / find_files / write_file / str_replace_editor /
 *       upload_file / download_file）经 {@link SandboxRuntimeClient} 接口隔离，{@link #buildClient} 返回
 *       {@link AgentSandboxRuntimeClient}（按 SDK 的 wire 契约重建 file / shell 两组端点）。
 *       运行仍以沙盒 runtime 在线为前提，不可达时按异常上抛——<b>绝不静默成功</b>。</li>
 *   <li>{@code deepagents.backends.sandbox.MAX_BINARY_BYTES} 为框架常量，本工程以 {@link #MAX_BINARY_BYTES}
 *       取代并标注（预览上限）。</li>
 *   <li>{@code deepagents.backends.utils._get_file_type} 为 magic 嗅探，本工程以扩展名启发式
 *       {@link #getFileType} 取代（未知扩展名默认 text；已知音视频/归档/可执行扩展名判 binary）。</li>
 *   <li>参考实现 {@code grep} 委托 deepagents 基类扫描；本工程以 {@link #superGrep} 提供等价进程内实现
 *       （find_files + 读 + 正则），能力差异仅在于实现位置。</li>
 * </ul>
 */
/**
 * <p><b>移植来源</b>：本类整体照搬问渠新栈
 * {@code com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend}（1586 行，含全部
 * 路径/权限纯逻辑、结果塑形、安全脚本生成与结果 DTO）。移植时只动了四处，都是环境差异：
 * <ul>
 *   <li>虚拟根由{@code BackendPaths} 的静态常量改为构造期从 provider（读 {@code sandbox.*} 配置）取值
 *       ——新栈读环境变量 {@code SANDBOX_VIRTUAL_PATH_PREFIX}，本工程配置唯一来源是 config-schema。</li>
 *   <li>执行超时与输出上限同上（新栈读 {@code SANDBOX_EXEC_TIMEOUT_SECONDS} / {@code SANDBOX_MAX_OUTPUT_BYTES}）。</li>
 *   <li>runtime client 的 token 由 {@code provider.token()} 静态取用
 *       改为 {@code provider.token()}（本工程没有静态单例）。</li>
 *   <li>去掉 {@code BackendPaths} 依赖（它其余方法是「项目 Workdir ↔ 沙盒路径」的双向映射，
 *       本工程无项目/工作区概念，不做该映射）。</li>
 * </ul>
 * <p><b>未接线（显式标注）</b>：{@code downloadAuthorizedFileToPath} / {@code uploadAuthorizedFileFromPath} /
 * {@code listAuthorizedDirectory} / {@code createAuthorizedDirectory} / {@code deleteAuthorizedPath}
 * 这五个「授权文件搬运」方法在新栈服务于「技能投影 + 大结果落盘」，本工程当前只接线
 * {@code execute} / {@code read} / {@code write} / {@code ls}（见 {@code SandboxTools}），
 * 其余方法保留原样但暂未接线——不是遗漏，是等对应的使用场景出现（如产物从沙盒落回「我的产物」）再接。
 */
public class ProvisionerSandboxBackend implements SandboxFsBackend {

    /** 沙盒内 user-data 虚拟根（可读写；新栈读 SANDBOX_VIRTUAL_PATH_PREFIX）。 */
    private final String userDataRoot;
    /** 沙盒内 skills 只读根（参考实现硬编码，不受虚拟根配置影响）。 */
    private final String skillsRoot;
    private static final int MAX_BINARY_BYTES = 1_000_000;

    private static final java.util.Set<String> IMAGE_EXTENSIONS = Set.of(
            ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png", ".webp");
    private static final java.util.Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".doc", ".docx", ".pdf", ".ppt", ".pptx", ".xls", ".xlsx");
    private static final java.util.Set<String> BINARY_BY_EXTENSION = Set.of(
            ".mp3", ".mp4", ".wav", ".mov", ".avi", ".mkv", ".webm",
            ".zip", ".gz", ".tar", ".tgz", ".7z", ".rar", ".bz2", ".xz",
            ".bin", ".exe", ".dll", ".so", ".dylib", ".class", ".o", ".pyc");

    private static final String BINARY_PREVIEW_TOO_LARGE_ERROR =
            "Binary file exceeds maximum preview size of " + MAX_BINARY_BYTES + " bytes";
    private static final String DOCUMENT_READ_ERROR =
            "read_file does not support PDF or Office documents. Use ocr_parse_file to convert the file to Markdown first.";
    private static final String BINARY_READ_ERROR =
            "read_file only supports UTF-8 text and image files. This file type is not supported.";

    private final ProvisionerSandboxProvider provider;
    private final String threadId;
    private final String uid;
    private final String workdirPath;
    private final boolean inheritEnv;
    private final boolean createIfMissing;
    private final int commandTimeoutSeconds;
    private final int maxOutputBytes;

    // 测试覆盖：绕过 provider / 远程 client 构造（对应参考单测的 monkeypatch _get_client / _provider）。
    SandboxConnection overrideConnection;
    SandboxRuntimeClient overrideClient;

    public ProvisionerSandboxBackend(
            ProvisionerSandboxProvider provider,
            String threadId,
            String uid,
            String workdirPath,
            boolean createIfMissing,
            boolean inheritEnv) {
        this.provider = provider;
        this.threadId = threadId == null ? "" : threadId.strip();
        if (this.threadId.isEmpty()) {
            throw new IllegalArgumentException("thread_id is required for ProvisionerSandboxBackend");
        }
        this.uid = uid == null ? "" : uid.strip();
        if (this.uid.isEmpty()) {
            throw new IllegalArgumentException("uid is required for ProvisionerSandboxBackend");
        }
        this.inheritEnv = inheritEnv;
        this.createIfMissing = createIfMissing;
        this.workdirPath = workdirPath == null ? "" : workdirPath.strip();
        this.userDataRoot = provider.virtualPathPrefix();
        this.skillsRoot = provider.virtualSkillsPath();
        this.commandTimeoutSeconds = provider.commandTimeoutSeconds();
        this.maxOutputBytes = provider.maxOutputBytes();
    }

    // ==================== 路径 / 权限纯逻辑 ====================

    static String normalizePath(String path) {
        String raw = path == null ? "" : path.strip();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        if (!raw.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        for (String part : raw.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("path traversal is not allowed");
            }
        }
        return raw;
    }

    /** candidate 是否为 ancestor 自身或其后裔（对应 _is_same_or_child）。 */
    static boolean isSameOrChild(String candidate, String ancestor) {
        String root = ancestor.replaceAll("/+$", "");
        if (root.isEmpty()) {
            root = "/";
        }
        if ("/".equals(root)) {
            return "/".equals(candidate) || candidate.startsWith("/");
        }
        return candidate.equals(root) || candidate.startsWith(root + "/");
    }

    static boolean pathOverlapsRoot(String path, String root) {
        return isSameOrChild(path, root) || isSameOrChild(root, path);
    }

    static String globForSearchRoot(String pattern, String root) {
        String bare = (pattern == null ? "*" : pattern).replaceAll("^/+", "");
        String bareRoot = root.replaceAll("^/+", "").replaceAll("/+$", "");
        if (bare.equals(bareRoot)) {
            return "*";
        }
        String prefix = bareRoot + "/";
        if (bare.startsWith(prefix)) {
            String rest = bare.substring(prefix.length());
            return rest.isEmpty() ? "*" : rest;
        }
        return pattern;
    }

    private static String permissionError(String operation, String path) {
        return "permission denied for " + operation + " on '" + path + "'";
    }

    private List<String> readableRoots() {
        return List.of(userDataRoot, skillsRoot);
    }

    private List<String> writableRoots() {
        return List.of(userDataRoot);
    }

    private boolean canReadPath(String path) {
        for (String root : readableRoots()) {
            if (isSameOrChild(path, root)) {
                return true;
            }
        }
        return false;
    }

    private boolean canListPath(String path) {
        for (String root : readableRoots()) {
            if (pathOverlapsRoot(path, root)) {
                return true;
            }
        }
        return false;
    }

    private boolean canWritePath(String path) {
        for (String root : writableRoots()) {
            if (isSameOrChild(path, root)) {
                return true;
            }
        }
        return false;
    }

    private List<String> readableSearchPaths(String path) {
        if (canReadPath(path)) {
            return List.of(path);
        }
        List<String> result = new ArrayList<>();
        for (String root : readableRoots()) {
            if (isSameOrChild(root, path)) {
                result.add(root);
            }
        }
        return result;
    }

    private List<FsEntry> filterReadableInfos(List<FsEntry> infos) {
        List<FsEntry> result = new ArrayList<>();
        for (FsEntry info : infos) {
            String path;
            try {
                path = normalizePath(info.path);
            } catch (RuntimeException exc) {
                continue;
            }
            if (canListPath(path)) {
                result.add(info);
            }
        }
        return result;
    }

    private List<GrepMatch> filterReadableMatches(List<GrepMatch> matches) {
        List<GrepMatch> result = new ArrayList<>();
        for (GrepMatch match : matches) {
            String path;
            try {
                path = normalizePath(match.path);
            } catch (RuntimeException exc) {
                continue;
            }
            if (canReadPath(path)) {
                result.add(match);
            }
        }
        return result;
    }

    // ==================== 文件分类 / 行窗口 ====================

    private static String suffix(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return path.substring(dot).toLowerCase();
    }

    private static String readFileKind(String path) {
        String ext = suffix(path);
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return "image";
        }
        if (DOCUMENT_EXTENSIONS.contains(ext)) {
            return "document";
        }
        if (!"text".equals(getFileType(path))) {
            return "binary";
        }
        return "text";
    }

    /** 扩展名启发式文件类型（对应 deepagents _get_file_type；能力差异见类注释）。 */
    private static String getFileType(String path) {
        String ext = suffix(path);
        if (BINARY_BY_EXTENSION.contains(ext)) {
            return "binary";
        }
        return "text";
    }

    private static int[] readWindow(int offset, Integer limit) {
        int startLine = Math.max(0, offset);
        Integer endLine = limit == null ? null : startLine + limit;
        return new int[] {startLine, endLine == null ? Integer.MAX_VALUE : endLine};
    }

    // ==================== 结果塑形 / 错误识别 ====================

    private static boolean looksLikeBinary(byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(content));
            return false;
        } catch (Exception exc) {
            return true;
        }
    }

    private static boolean isUtf8DecodeFailure(RuntimeException exc) {
        String detail = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase();
        return detail.contains("utf-8") && (detail.contains("decode") || detail.contains("malformed"));
    }

    private static boolean isMissingFileError(RuntimeException exc) {
        if (exc instanceof FileNotFoundError) {
            return true;
        }
        String detail = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase();
        return detail.contains("status_code: 404") || detail.contains("file does not exist");
    }

    private static void raiseAuthorizedPathOperationError(String output, String path, String fallback) {
        String detail = output == null ? "" : output;
        if (detail.contains("FileNotFoundError") || detail.contains("No such file or directory")) {
            throw new FileNotFoundError(path);
        }
        if (detail.contains("IsADirectoryError") || detail.contains("source is a directory")) {
            throw new IsADirectoryError(path);
        }
        if (detail.contains("OverflowError") || detail.contains("exceeds transfer limit")) {
            throw new FileTransferLimitError("file exceeds transfer limit");
        }
        if (detail.contains("NotADirectoryError")
                || detail.contains("PermissionError")
                || detail.contains("Too many levels of symbolic links")
                || detail.contains("source is not regular")) {
            throw new PermissionError(path);
        }
        throw new RuntimeException(detail.isEmpty() ? fallback : detail);
    }

    private static String describeReadError(String file_path, RuntimeException exc) {
        if (exc instanceof FileNotFoundError) {
            return "Error: File '" + file_path + "' not found";
        }
        if (exc instanceof IsADirectoryError) {
            return "Error: Path '" + file_path + "' is a directory";
        }
        if (exc instanceof PermissionError) {
            return "Error: Access denied for '" + file_path + "'";
        }
        if (exc instanceof IllegalArgumentException) {
            return "Error: Invalid path '" + file_path + "': " + exc.getMessage();
        }
        String detail = exc.getMessage() == null ? "" : exc.getMessage().strip();
        if (!detail.isEmpty()) {
            return "Error: Failed to read '" + file_path + "': " + detail;
        }
        return "Error: Failed to read '" + file_path + "'";
    }

    private static String stripErrorPrefix(String error) {
        if (error.startsWith("Error: ")) {
            return error.substring("Error: ".length());
        }
        return error;
    }

    // ==================== 连接 / client ====================

    public String id() {
        return ProvisionerSandboxProvider.sandboxIdForThread(threadId, uid);
    }

    SandboxConnection getConnection() {
        if (overrideConnection != null) {
            return overrideConnection;
        }
        SandboxConnection connection = provider.get(threadId, uid, createIfMissing, inheritEnv, workdirPath);
        if (connection == null) {
            throw new RuntimeException("sandbox is unavailable for thread " + threadId);
        }
        return connection;
    }

    SandboxRuntimeClient getClient() {
        if (overrideClient != null) {
            return overrideClient;
        }
        SandboxConnection connection = getConnection();
        return buildClient(connection.getSandboxUrl());
    }

    /** 构造沙盒 runtime 远程 client（对应 _build_client / _build_async_client）。 */
    SandboxRuntimeClient buildClient(String sandboxUrl) {
        return new AgentSandboxRuntimeClient(
                sandboxUrl,
                provider.token(),
                Duration.ofSeconds(commandTimeoutSeconds));
    }

    /** 显式确保本实例 sandbox 已创建并返回稳定 ID（对应 ensure_available）。 */
    public String ensureAvailable() {
        getClient();
        return id();
    }

    // ==================== 读 ====================

    private static byte[] normalizeReadContent(SandboxRuntimeClient.SandboxReadFileResult result) {
        if (result.content() == null) {
            return new byte[0];
        }
        if ("base64".equalsIgnoreCase(result.encoding())) {
            return Base64.getDecoder().decode(result.content());
        }
        return result.content();
    }

    private byte[] readBinary(String path, int offset, Integer limit) {
        int[] window = readWindow(offset, limit);
        SandboxRuntimeClient client = getClient();
        SandboxRuntimeClient.SandboxReadFileResult result = client.readFile(
                path, window[0], limit == null ? null : window[1]);
        return normalizeReadContent(result);
    }

    /** 等价 {@code read_binary(path)}：读取整个文件（对应 Python 单参签名）。 */
    private byte[] readBinary(String path) {
        return readBinary(path, 0, null);
    }

    private int fileSizeBytes(String path) {
        String pathB64 = Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8));
        String command = "python3 -c \"import base64, os, stat; "
                + "path = base64.b64decode('" + pathB64 + "').decode('utf-8'); "
                + "st = os.stat(path); "
                + "print(st.st_size if stat.S_ISREG(st.st_mode) else -1)\"";
        ExecuteResponse result = execute(command);
        if (result.exitCode() != null && result.exitCode() != 0) {
            String detail = result.output() == null ? "" : result.output().strip();
            throw new RuntimeException(detail.isEmpty() ? "failed to stat '" + path + "'" : detail);
        }
        String output = result.output() == null ? "" : result.output().strip();
        String[] lines = output.split("\n");
        String last = lines.length == 0 ? "" : lines[lines.length - 1].strip();
        int size;
        try {
            size = Integer.parseInt(last);
        } catch (NumberFormatException exc) {
            throw new RuntimeException("failed to stat '" + path + "'");
        }
        if (size < 0) {
            throw new IsADirectoryError(path);
        }
        return size;
    }

    private String readFileBase64(String path) {
        String pathB64 = Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8));
        String outputPath = "/tmp/yuxi-read-file-" + UUID.randomUUID().toString().replace("-", "") + ".b64";
        String outputPathB64 = Base64.getEncoder().encodeToString(outputPath.getBytes(StandardCharsets.UTF_8));
        String command = "python3 -c \"import base64; "
                + "path = base64.b64decode('" + pathB64 + "').decode('utf-8'); "
                + "output_path = base64.b64decode('" + outputPathB64 + "').decode('utf-8'); "
                + "open(output_path, 'w').write(base64.b64encode(open(path, 'rb').read()).decode('ascii'))\"";
        SandboxRuntimeClient client = getClient();
        try {
            ExecuteResponse result = execute(command);
            String output = result.output() == null ? "" : result.output();
            if (result.exitCode() != null && result.exitCode() != 0) {
                throw new RuntimeException(output.strip().isEmpty() ? "failed to read '" + path + "'" : output.strip());
            }
            String content = new String(readBinary(outputPath), StandardCharsets.US_ASCII).strip();
            Base64.getDecoder().decode(content);
            return content;
        } finally {
            try {
                client.execCommand("rm -f " + outputPath, Duration.ofSeconds(10), false);
            } catch (RuntimeException ignore) {
                // best-effort cleanup
            }
        }
    }

    private ReadResult readBase64File(String path) {
        if (fileSizeBytes(path) > MAX_BINARY_BYTES) {
            return ReadResult.errorOf(BINARY_PREVIEW_TOO_LARGE_ERROR);
        }
        return new ReadResult(null, false, new FileData(readFileBase64(path), "base64"), 0, 0, 0, null);
    }

    private ReadResult textContentReadResult(byte[] content, int startLine) {
        if (looksLikeBinary(content)) {
            return ReadResult.errorOf(BINARY_READ_ERROR);
        }
        return textReadResult(new String(content, StandardCharsets.UTF_8), startLine);
    }

    private static ReadResult textReadResult(String text, int startLine) {
        String[] lines = text.split("\n", -1);
        int linesReturned = lines.length;
        if (linesReturned <= 0) {
            return new ReadResult(null, false, new FileData(text, "utf-8"), startLine, 0, 0, null);
        }
        int endLine = startLine + linesReturned - 1;
        return new ReadResult(null, false, new FileData(text, "utf-8"), startLine, endLine, endLine, null);
    }

    public ReadResult read(String file_path) {
        return read(file_path, 0, 2000);
    }

    public ReadResult read(String file_path, int offset, Integer limit) {
        String normalized;
        try {
            normalized = normalizePath(file_path);
        } catch (RuntimeException exc) {
            return ReadResult.errorOf("Invalid path '" + file_path + "': " + exc.getMessage());
        }
        if (!canReadPath(normalized)) {
            return ReadResult.errorOf(permissionError("read", normalized));
        }
        if (limit != null && limit <= 0) {
            return ReadResult.noLinesRequested();
        }
        int startLine = Math.max(0, offset) + 1;
        try {
            String kind = readFileKind(normalized);
            if ("image".equals(kind)) {
                return readBase64File(normalized);
            }
            if ("document".equals(kind)) {
                fileSizeBytes(normalized);
                return ReadResult.errorOf(DOCUMENT_READ_ERROR);
            }
            if ("binary".equals(kind)) {
                fileSizeBytes(normalized);
                return ReadResult.errorOf(BINARY_READ_ERROR);
            }
            byte[] content;
            try {
                content = readBinary(normalized, startLine - 1, limit);
            } catch (RuntimeException exc) {
                if (!isUtf8DecodeFailure(exc)) {
                    throw exc;
                }
                return ReadResult.errorOf(BINARY_READ_ERROR);
            }
            return textContentReadResult(content, startLine);
        } catch (RuntimeException exc) {
            String error = describeReadError(file_path, exc);
            return ReadResult.errorOf(stripErrorPrefix(error));
        }
    }

    // ==================== 执行 ====================

    public ExecuteResponse execute(String command, Integer timeout) {
        try {
            SandboxRuntimeClient client = getClient();
            Duration t = timeout == null ? null : Duration.ofSeconds(timeout);
            SandboxRuntimeClient.SandboxExecResult result = client.execCommand(command, t, false);
            String output = result.output() == null ? "" : result.output();
            Integer exitCode = result.exitCode();
            boolean truncated = false;
            byte[] encoded = output.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > maxOutputBytes) {
                output = new String(encoded, 0, maxOutputBytes, StandardCharsets.UTF_8);
                truncated = true;
            }
            return new ExecuteResponse(output, exitCode, truncated);
        } catch (RuntimeException exc) {
            System.err.println("Sandbox execute failed for thread " + threadId + ": " + exc);
            return new ExecuteResponse("Error: " + exc, 1, false);
        }
    }

    /** 等价 {@code execute(command)}（对应 Python 单参签名，无显式超时）。 */
    public ExecuteResponse execute(String command) {
        return execute(command, null);
    }

    // ==================== 列目录 ====================

    @Override
    public LsResult ls(String dir) {
        String normalized;
        try {
            normalized = normalizePath(dir);
        } catch (RuntimeException exc) {
            return new LsResult("Invalid path '" + dir + "': " + exc.getMessage(), null);
        }
        if (!canListPath(normalized)) {
            return new LsResult(permissionError("read", normalized), null);
        }
        try {
            SandboxRuntimeClient client = getClient();
            SandboxRuntimeClient.SandboxListPathResult result = client.listPath(normalized, false, true);
            List<SandboxRuntimeClient.SandboxFileEntry> entries =
                    result.files() == null ? List.of() : result.files();
            List<FsEntry> infos = new ArrayList<>();
            for (SandboxRuntimeClient.SandboxFileEntry entry : entries) {
                infos.add(new FsEntry(entry.path(), entry.isDirectory(), entry.size()));
            }
            return new LsResult(null, filterReadableInfos(infos));
        } catch (RuntimeException exc) {
            String message = exc.getMessage() == null ? "Failed to list '" + dir + "'" : exc.getMessage();
            return new LsResult(message, null);
        }
    }

    // ==================== 写 / 编辑 ====================

    private void ensureParentDirectory(String file_path) {
        String rel = file_path.substring(userDataRoot.length()).replaceAll("^/+", "");
        String[] allParts = rel.split("/");
        if (allParts.length <= 1) {
            return;
        }
        StringBuilder partsTuple = new StringBuilder("(");
        for (int i = 0; i < allParts.length - 1; i++) {
            if (i > 0) {
                partsTuple.append(", ");
            }
            partsTuple.append("'").append(allParts[i]).append("'");
        }
        partsTuple.append(")");
        String script = "import os\n\n"
                + "directory_fd = os.open('" + userDataRoot + "', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)\n"
                + "try:\n"
                + "    for part in " + partsTuple + ":\n"
                + "        try:\n"
                + "            os.mkdir(part, 0o755, dir_fd=directory_fd)\n"
                + "        except FileExistsError:\n"
                + "            pass\n"
                + "        child_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "        os.close(directory_fd)\n"
                + "        directory_fd = child_fd\n"
                + "finally:\n"
                + "    os.close(directory_fd)\n";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        ExecuteResponse result = execute(
                "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"");
        if (result.exitCode() != null && result.exitCode() != 0) {
            throw new PermissionError(result.output() == null || result.output().isEmpty()
                    ? file_path : result.output());
        }
    }

    public WriteResult write(String file_path, String content) {
        String normalized;
        try {
            normalized = normalizePath(file_path);
        } catch (RuntimeException exc) {
            return new WriteResult("Error: Invalid path '" + file_path + "': " + exc.getMessage(), null);
        }
        if (!canWritePath(normalized)) {
            return new WriteResult("Error: " + permissionError("write", normalized), null);
        }
        if (content == null || !(content instanceof String)) {
            return new WriteResult(
                    "Error: write() only supports text content; use upload_files() for binary data", null);
        }
        try {
            readBinary(normalized);
            return new WriteResult("Error: File '" + file_path + "' already exists", null);
        } catch (RuntimeException ignore) {
            // 文件不存在 → 允许写入
        }
        try {
            ensureParentDirectory(normalized);
            SandboxRuntimeClient client = getClient();
            SandboxRuntimeClient.SandboxWriteResult result = client.writeFile(normalized, content);
            if (!result.success()) {
                return new WriteResult(
                        result.message() != null ? result.message() : "Failed to write file '" + file_path + "'",
                        null);
            }
        } catch (RuntimeException exc) {
            return new WriteResult(
                    exc.getMessage() != null ? exc.getMessage() : "Failed to write file '" + file_path + "'", null);
        }
        return new WriteResult(null, normalized);
    }

    public EditResult edit(String file_path, String oldString, String newString, boolean replaceAll) {
        String normalized;
        try {
            normalized = normalizePath(file_path);
        } catch (RuntimeException exc) {
            return new EditResult("Error: Invalid path '" + file_path + "': " + exc.getMessage(), null, null);
        }
        if (!canWritePath(normalized)) {
            return new EditResult("Error: " + permissionError("write", normalized), null, null);
        }
        String text;
        try {
            text = new String(readBinary(normalized), StandardCharsets.UTF_8);
        } catch (RuntimeException ignore) {
            return new EditResult("Error: File '" + file_path + "' not found", null, null);
        }
        int count = countOccurrences(text, oldString);
        if (count == 0) {
            return new EditResult("Error: String not found in file: '" + oldString + "'", null, null);
        }
        if (count > 1 && !replaceAll) {
            return new EditResult(
                    "Error: String '" + oldString + "' appears multiple times. "
                            + "Use replace_all=True to replace all occurrences.",
                    null, null);
        }
        String replaceMode = replaceAll ? "ALL" : "FIRST";
        try {
            SandboxRuntimeClient client = getClient();
            SandboxRuntimeClient.SandboxWriteResult result = client.strReplaceEditor(
                    "str_replace", normalized, oldString, newString, replaceMode);
            if (!result.success()) {
                return new EditResult(
                        result.message() != null ? result.message() : "Error editing file '" + file_path + "'",
                        null, null);
            }
        } catch (RuntimeException exc) {
            return new EditResult("Error editing file: " + exc, null, null);
        }
        return new EditResult(null, normalized, replaceAll ? count : 1);
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ==================== grep / glob ====================

    public GrepResult grep(String pattern, String path, String glob, Integer maxCount) {
        String normalized;
        try {
            normalized = normalizePath(path == null ? "/" : path);
        } catch (RuntimeException exc) {
            return new GrepResult(null, "Invalid path '" + (path == null ? "/" : path) + "': " + exc.getMessage(), false);
        }
        List<String> searchPaths = readableSearchPaths(normalized);
        if (searchPaths.isEmpty()) {
            return new GrepResult(null, permissionError("read", normalized), false);
        }
        List<GrepMatch> matches = new ArrayList<>();
        boolean truncated = false;
        for (String searchPath : searchPaths) {
            Integer remaining = null;
            if (maxCount != null) {
                remaining = Math.max(maxCount - matches.size(), 0);
                if (remaining == 0) {
                    truncated = true;
                    break;
                }
            }
            GrepResult part = superGrep(pattern, searchPath, glob, remaining);
            if (part.error() != null) {
                return part;
            }
            matches.addAll(part.matches());
            truncated = truncated || part.truncated();
        }
        if (maxCount != null && matches.size() > maxCount) {
            matches = matches.subList(0, maxCount);
            truncated = true;
        }
        return new GrepResult(filterReadableMatches(matches), null, truncated);
    }

    /** 进程内 grep 实现（对应 deepagents 基类 grep；能力差异见类注释）。测试可覆写。 */
    protected GrepResult superGrep(String pattern, String path, String glob, Integer maxCount) {
        SandboxRuntimeClient client = getClient();
        String g = glob != null && !glob.isEmpty() ? glob : "**/*";
        SandboxRuntimeClient.SandboxFindFilesResult fr = client.findFiles(path, g);
        List<String> files = fr.files() == null ? List.of() : fr.files();
        List<GrepMatch> matches = new ArrayList<>();
        Pattern p = Pattern.compile(Pattern.quote(pattern));
        int count = 0;
        boolean truncated = false;
        for (String f : files) {
            if (maxCount != null && count >= maxCount) {
                truncated = true;
                break;
            }
            byte[] content;
            try {
                content = readBinary(f, 0, null);
            } catch (RuntimeException ignore) {
                continue;
            }
            String text;
            try {
                text = new String(content, StandardCharsets.UTF_8);
            } catch (RuntimeException ignore) {
                continue;
            }
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (maxCount != null && count >= maxCount) {
                    truncated = true;
                    break;
                }
                if (p.matcher(lines[i]).find()) {
                    matches.add(new GrepMatch(f, i + 1, lines[i]));
                    count++;
                }
            }
        }
        return new GrepResult(matches, null, truncated);
    }

    public GlobResult glob(String pattern, String path) {
        String normalized;
        try {
            normalized = normalizePath(path);
        } catch (RuntimeException exc) {
            return new GlobResult(null, "Invalid path '" + path + "': " + exc.getMessage());
        }
        if (containsTraversal(pattern)) {
            return new GlobResult(null, "Invalid glob pattern: path traversal is not allowed");
        }
        List<String> searchPaths = readableSearchPaths(normalized);
        if (searchPaths.isEmpty()) {
            return new GlobResult(null, permissionError("read", normalized));
        }
        List<FsEntry> infos = new ArrayList<>();
        for (String searchPath : searchPaths) {
            SandboxRuntimeClient client = getClient();
            SandboxRuntimeClient.SandboxFindFilesResult result =
                    client.findFiles(searchPath, globForSearchRoot(pattern, searchPath));
            if (result.files() != null) {
                for (String file : result.files()) {
                    infos.add(new FsEntry(file, false, null));
                }
            }
        }
        infos = filterReadableInfos(infos);
        infos.sort((a, b) -> a.path.compareTo(b.path));
        List<GlobMatch> matches = new ArrayList<>();
        for (FsEntry e : infos) {
            matches.add(new GlobMatch(e.path));
        }
        return new GlobResult(matches, null);
    }

    private static boolean containsTraversal(String pattern) {
        for (String part : pattern.split("/")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 上传 / 下载 ====================

    public List<FileUploadResponse> uploadFiles(List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            String path = entry.getKey();
            byte[] content = entry.getValue();
            try {
                String normalized = normalizePath(path);
                if (!canWritePath(normalized)) {
                    responses.add(new FileUploadResponse(normalized, "permission_denied"));
                    continue;
                }
                ensureParentDirectory(normalized);
                SandboxRuntimeClient client = getClient();
                SandboxRuntimeClient.SandboxWriteResult result = client.writeFile(
                        normalized, Base64.getEncoder().encodeToString(content), "base64");
                if (!result.success()) {
                    throw new RuntimeException(result.message() != null ? result.message() : "Upload failed");
                }
                responses.add(new FileUploadResponse(normalized, null));
            } catch (PermissionError exc) {
                responses.add(new FileUploadResponse(path, "permission_denied"));
            } catch (IsADirectoryError exc) {
                responses.add(new FileUploadResponse(path, "is_directory"));
            } catch (FileNotFoundError exc) {
                responses.add(new FileUploadResponse(path, "file_not_found"));
            } catch (RuntimeException exc) {
                responses.add(new FileUploadResponse(path, "invalid_path"));
            }
        }
        return responses;
    }

    @Override
    public List<DownloadResult> downloadFiles(List<String> paths) {
        List<DownloadResult> responses = new ArrayList<>();
        for (String path : paths) {
            try {
                String normalized = normalizePath(path);
                if (!canReadPath(normalized)) {
                    responses.add(new DownloadResult("permission_denied", null));
                    continue;
                }
                SandboxRuntimeClient client = getClient();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                for (byte[] chunk : client.downloadFile(normalized, Duration.ofSeconds(commandTimeoutSeconds))) {
                    buf.writeBytes(chunk);
                }
                responses.add(new DownloadResult(null, buf.toByteArray()));
            } catch (IllegalArgumentException exc) {
                responses.add(new DownloadResult("invalid_path", null));
            } catch (RuntimeException exc) {
                if (isMissingFileError(exc)) {
                    responses.add(new DownloadResult("file_not_found", null));
                } else {
                    responses.add(new DownloadResult("read_failed: " + exc, null));
                }
            }
        }
        return responses;
    }

    // ==================== 授权路径安全操作 ====================

    public boolean regularFileExists(String path) {
        String normalized = normalizePath(path);
        if (!canReadPath(normalized)) {
            return false;
        }
        String root = firstChildRoot(normalized);
        if (root == null) {
            return false;
        }
        String pathB64 = Base64.getEncoder().encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
        String rootB64 = Base64.getEncoder().encodeToString(root.getBytes(StandardCharsets.UTF_8));
        String command = "python3 -c \"import base64, os, stat; "
                + "path = base64.b64decode('" + pathB64 + "').decode('utf-8'); "
                + "root = base64.b64decode('" + rootB64 + "').decode('utf-8'); "
                + "st = os.lstat(path); "
                + "inside = os.path.commonpath([os.path.realpath(path), os.path.realpath(root)]) == os.path.realpath(root); "
                + "raise SystemExit(0 if stat.S_ISREG(st.st_mode) and inside else 2)\"";
        try {
            ExecuteResponse result = execute(command);
            return result.exitCode() == null || result.exitCode() == 0;
        } catch (RuntimeException exc) {
            return false;
        }
    }

    private String firstChildRoot(String normalized) {
        for (String candidate : readableRoots()) {
            if (!candidate.equals(normalized) && isSameOrChild(normalized, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int downloadScopedFileToPath(String normalized, String root, String targetPath, int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("file download limit must be non-negative");
        }
        String[] relativeParts = normalized.substring(root.length() + 1).split("/");
        String exportPath = "/tmp/.yuxi-file-snapshot-" + UUID.randomUUID().toString().replace("-", "");
        String script = buildSnapshotScript(root, relativeParts, exportPath, maxBytes);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        RuntimeException operationError = null;
        try {
            ExecuteResponse result = execute(
                    "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"");
            if (result.exitCode() != null && result.exitCode() != 0) {
                raiseAuthorizedPathOperationError(result.output(), normalized, "authorized file snapshot failed: " + normalized);
            }
            String snapshotLine = firstSnapshotLine(result.output());
            if (snapshotLine == null) {
                ExecuteResponse metadata = execute(metadataScript(exportPath));
                if (metadata.exitCode() != null && metadata.exitCode() != 0) {
                    raiseAuthorizedPathOperationError(
                            metadata.output(), normalized, "sandbox snapshot metadata read failed");
                }
                snapshotLine = firstSnapshotLine(metadata.output());
                if (snapshotLine == null) {
                    throw new RuntimeException("sandbox file snapshot did not report size and checksum");
                }
            }
            String[] tokens = snapshotLine.split(" ");
            int tokenCount = tokens.length;
            int expectedSize = Integer.parseInt(tokens[tokenCount - 2]);
            String expectedDigest = tokens[tokenCount - 1];

            SandboxRuntimeClient client = getClient();
            MessageDigest actualDigest;
            try {
                actualDigest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exc) {
                throw new RuntimeException(exc);
            }
            int actualSize = 0;
            try {
                FileOutputStream out = new FileOutputStream(targetPath);
                try {
                    for (byte[] chunk : client.downloadFile(exportPath, Duration.ofSeconds(commandTimeoutSeconds))) {
                        actualSize += chunk.length;
                        if (actualSize > maxBytes) {
                            throw new FileTransferLimitError("file exceeds transfer limit: " + normalized);
                        }
                        actualDigest.update(chunk);
                        out.write(chunk);
                    }
                } finally {
                    try {
                        out.close();
                    } catch (java.io.IOException ignore) {
                        // best-effort
                    }
                }
            } catch (java.io.IOException exc) {
                throw new RuntimeException(exc);
            }
            if (actualSize != expectedSize
                    || !HexFormat.of().formatHex(actualDigest.digest()).equals(expectedDigest)) {
                throw new IllegalArgumentException("file changed during transfer: " + normalized);
            }
            return actualSize;
        } catch (RuntimeException exc) {
            operationError = exc;
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(targetPath));
            } catch (Exception ignore) {
                // best-effort
            }
            throw exc;
        } finally {
            try {
                ExecuteResponse cleanup = execute("python3 -c \"import base64,os; "
                        + "p=base64.b64decode('"
                        + Base64.getEncoder().encodeToString(exportPath.getBytes(StandardCharsets.UTF_8))
                        + "').decode(); os.path.exists(p) and os.unlink(p)\"");
                if (cleanup.exitCode() != null && cleanup.exitCode() != 0) {
                    if (operationError == null) {
                        try {
                            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(targetPath));
                        } catch (Exception ignore) {
                            // best-effort
                        }
                        throw new RuntimeException("sandbox file snapshot cleanup failed: " + exportPath);
                    }
                }
            } catch (RuntimeException exc) {
                if (operationError == null) {
                    try {
                        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(targetPath));
                    } catch (Exception ignore) {
                        // best-effort
                    }
                    throw exc;
                }
                System.err.println("Failed to remove sandbox file snapshot " + exportPath + ": " + exc);
            }
        }
    }

    private static String buildSnapshotScript(String root, String[] relativeParts, String exportPath, int maxBytes) {
        StringBuilder partsTuple = new StringBuilder("(");
        for (int i = 0; i < relativeParts.length; i++) {
            if (i > 0) {
                partsTuple.append(", ");
            }
            partsTuple.append("'").append(relativeParts[i]).append("'");
        }
        partsTuple.append(")");
        return "import hashlib\n"
                + "import os\n"
                + "import stat\n\n"
                + "root = '" + root + "'\n"
                + "parts = " + partsTuple + "\n"
                + "export_path = '" + exportPath + "'\n"
                + "max_bytes = " + maxBytes + "\n"
                + "directory_fd = None\n"
                + "source_fd = None\n"
                + "target_fd = None\n"
                + "try:\n"
                + "    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)\n"
                + "    for part in parts[:-1]:\n"
                + "        child_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "        os.close(directory_fd)\n"
                + "        directory_fd = child_fd\n"
                + "    source_fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "    source_mode = os.fstat(source_fd).st_mode\n"
                + "    if stat.S_ISDIR(source_mode):\n"
                + "        raise IsADirectoryError('source is a directory')\n"
                + "    if not stat.S_ISREG(source_mode):\n"
                + "        raise PermissionError('source is not regular')\n"
                + "    target_fd = os.open(export_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)\n"
                + "    size = 0\n"
                + "    digest = hashlib.sha256()\n"
                + "    while True:\n"
                + "        chunk = os.read(source_fd, 1048576)\n"
                + "        if not chunk:\n"
                + "            break\n"
                + "        size += len(chunk)\n"
                + "        if size > max_bytes:\n"
                + "            raise OverflowError('file exceeds transfer limit')\n"
                + "        digest.update(chunk)\n"
                + "        offset = 0\n"
                + "        while offset < len(chunk):\n"
                + "            offset += os.write(target_fd, chunk[offset:])\n"
                + "    os.close(target_fd)\n"
                + "    target_fd = None\n"
                + "    print(f'YUXI_FILE_SNAPSHOT {size} {digest.hexdigest()}')\n"
                + "except Exception:\n"
                + "    if target_fd is not None:\n"
                + "        os.close(target_fd)\n"
                + "    try:\n"
                + "        os.unlink(export_path)\n"
                + "    except FileNotFoundError:\n"
                + "        pass\n"
                + "    raise\n"
                + "finally:\n"
                + "    if source_fd is not None:\n"
                + "        os.close(source_fd)\n"
                + "    if directory_fd is not None:\n"
                + "        os.close(directory_fd)\n";
    }

    private static String metadataScript(String exportPath) {
        return "python3 -c \"import base64,hashlib,os,stat; "
                + "p=base64.b64decode('"
                + Base64.getEncoder().encodeToString(exportPath.getBytes(StandardCharsets.UTF_8))
                + "').decode(); "
                + "fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW); st=os.fstat(fd); "
                + "(_ for _ in ()).throw(PermissionError()) if not stat.S_ISREG(st.st_mode) else None; "
                + "digest=hashlib.sha256(); "
                + "size=sum((digest.update(chunk) or len(chunk)) "
                + "for chunk in iter(lambda:os.read(fd,1048576),b'')); "
                + "os.close(fd); "
                + "print(f'YUXI_FILE_SNAPSHOT {size} {digest.hexdigest()}')\"";
    }

    private static String firstSnapshotLine(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\n")) {
            if (line.startsWith("YUXI_FILE_SNAPSHOT ")) {
                return line;
            }
        }
        return null;
    }

    public int downloadAuthorizedFileToPath(String path, String targetPath, int maxBytes) {
        String normalized = normalizePath(path);
        String root = firstChildRoot(normalized);
        if (root == null) {
            throw new IllegalArgumentException("file path is outside authorized roots: " + normalized);
        }
        return downloadScopedFileToPath(normalized, root, targetPath, maxBytes);
    }

    public void uploadAuthorizedFileFromPath(String path, String sourcePath) {
        String normalized = normalizePath(path);
        if (normalized.equals(userDataRoot) || !isSameOrChild(normalized, userDataRoot)) {
            throw new IllegalArgumentException("write path is outside authorized roots: " + normalized);
        }
        String[] relativeParts = normalized.substring(userDataRoot.length() + 1).split("/");
        String exportPath = "/tmp/.yuxi-file-upload-" + UUID.randomUUID().toString().replace("-", "");
        String script = buildUploadScript(userDataRoot, relativeParts, exportPath);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        SandboxRuntimeClient client = getClient();
        try (java.io.FileInputStream source = new java.io.FileInputStream(sourcePath)) {
            SandboxRuntimeClient.SandboxWriteResult result = client.uploadFile(
                    new java.io.File(sourcePath), exportPath);
            if (!result.success()) {
                throw new RuntimeException(result.message() != null ? result.message() : "failed to upload source for " + normalized);
            }
        } catch (java.io.IOException exc) {
            throw new RuntimeException("failed to read source for " + normalized, exc);
        }
        ExecuteResponse install = execute(
                "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"");
        if (install.exitCode() != null && install.exitCode() != 0) {
            raiseAuthorizedPathOperationError(install.output(), normalized, "failed to write " + normalized);
        }
    }

    private static String buildUploadScript(String root, String[] relativeParts, String exportPath) {
        StringBuilder partsTuple = new StringBuilder("(");
        for (int i = 0; i < relativeParts.length; i++) {
            if (i > 0) {
                partsTuple.append(", ");
            }
            partsTuple.append("'").append(relativeParts[i]).append("'");
        }
        partsTuple.append(")");
        return "import os\n"
                + "import stat\n\n"
                + "root = '" + root + "'\n"
                + "parts = " + partsTuple + "\n"
                + "source_path = '" + exportPath + "'\n"
                + "temp_name = '.yuxi-write-" + UUID.randomUUID().toString().replace("-", "") + "'\n"
                + "directory_fd = None\n"
                + "source_fd = None\n"
                + "target_fd = None\n"
                + "try:\n"
                + "    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)\n"
                + "    for part in parts[:-1]:\n"
                + "        try:\n"
                + "            os.mkdir(part, 0o755, dir_fd=directory_fd)\n"
                + "        except FileExistsError:\n"
                + "            pass\n"
                + "        child_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "        os.close(directory_fd)\n"
                + "        directory_fd = child_fd\n"
                + "    source_fd = os.open(source_path, os.O_RDONLY | os.O_NOFOLLOW)\n"
                + "    if not stat.S_ISREG(os.fstat(source_fd).st_mode):\n"
                + "        raise ValueError('upload source is not regular')\n"
                + "    target_fd = os.open(temp_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=directory_fd)\n"
                + "    while True:\n"
                + "        chunk = os.read(source_fd, 1048576)\n"
                + "        if not chunk:\n"
                + "            break\n"
                + "        offset = 0\n"
                + "        while offset < len(chunk):\n"
                + "            offset += os.write(target_fd, chunk[offset:])\n"
                + "    os.close(target_fd)\n"
                + "    target_fd = None\n"
                + "    os.rename(temp_name, parts[-1], src_dir_fd=directory_fd, dst_dir_fd=directory_fd)\n"
                + "finally:\n"
                + "    if target_fd is not None:\n"
                + "        os.close(target_fd)\n"
                + "    if source_fd is not None:\n"
                + "        os.close(source_fd)\n"
                + "    if directory_fd is not None:\n"
                + "        try:\n"
                + "            os.unlink(temp_name, dir_fd=directory_fd)\n"
                + "        except FileNotFoundError:\n"
                + "            pass\n"
                + "        os.close(directory_fd)\n"
                + "    try:\n"
                + "        os.unlink(source_path)\n"
                + "    except FileNotFoundError:\n"
                + "        pass\n";
    }

    public List<Map<String, Object>> listAuthorizedDirectory(String path, String root) {
        String normalized = normalizePath(path);
        String normalizedRoot = normalizePath(root);
        if (!normalized.equals(normalizedRoot) && !isSameOrChild(normalized, normalizedRoot)) {
            throw new IllegalArgumentException("directory path is outside authorized root: " + normalized);
        }
        String[] relativeParts = normalized.equals(normalizedRoot)
                ? new String[0]
                : normalized.substring(normalizedRoot.length() + 1).split("/");
        String script = buildListScript(normalizedRoot, relativeParts);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        ExecuteResponse result = execute(
                "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"");
        if (result.exitCode() != null && result.exitCode() != 0) {
            throw new FileNotFoundError(normalized);
        }
        String payload = firstSafeListLine(result.output());
        if (payload == null) {
            throw new RuntimeException("sandbox directory listing did not return a safe payload");
        }
        return parseSafeList(payload);
    }

    private static String buildListScript(String root, String[] relativeParts) {
        StringBuilder partsTuple = new StringBuilder("(");
        for (int i = 0; i < relativeParts.length; i++) {
            if (i > 0) {
                partsTuple.append(", ");
            }
            partsTuple.append("'").append(relativeParts[i]).append("'");
        }
        partsTuple.append(")");
        return "import base64\n"
                + "import json\n"
                + "import os\n"
                + "import stat\n\n"
                + "root = '" + root + "'\n"
                + "parts = " + partsTuple + "\n"
                + "directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)\n"
                + "try:\n"
                + "    for part in parts:\n"
                + "        child_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "        os.close(directory_fd)\n"
                + "        directory_fd = child_fd\n"
                + "    entries = []\n"
                + "    for name in sorted(os.listdir(directory_fd), key=str.lower):\n"
                + "        item_stat = os.stat(name, dir_fd=directory_fd, follow_symlinks=False)\n"
                + "        if not (stat.S_ISDIR(item_stat.st_mode) or stat.S_ISREG(item_stat.st_mode)):\n"
                + "            continue\n"
                + "        entries.append({\n"
                + "            'name': name,\n"
                + "            'is_dir': stat.S_ISDIR(item_stat.st_mode),\n"
                + "            'size': 0 if stat.S_ISDIR(item_stat.st_mode) else item_stat.st_size,\n"
                + "            'modified_at': item_stat.st_mtime,\n"
                + "        })\n"
                + "    print('YUXI_SAFE_LIST ' + base64.b64encode(json.dumps(entries).encode()).decode())\n"
                + "finally:\n"
                + "    os.close(directory_fd)\n";
    }

    private static String firstSafeListLine(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\n")) {
            if (line.startsWith("YUXI_SAFE_LIST ")) {
                return line.substring("YUXI_SAFE_LIST ".length());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseSafeList(String payload) {
        byte[] decoded = Base64.getDecoder().decode(payload);
        Object parsed = com.alibaba.fastjson2.JSON.parse(new String(decoded, StandardCharsets.UTF_8));
        if (parsed instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    public String createAuthorizedDirectory(String parentPath, String name, String root) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("directory name must be one path component");
        }
        String normalizedParent = normalizePath(parentPath);
        String normalizedRoot = normalizePath(root);
        if (!normalizedParent.equals(normalizedRoot) && !isSameOrChild(normalizedParent, normalizedRoot)) {
            throw new IllegalArgumentException("parent path is outside authorized root");
        }
        String targetPath = normalizedParent.replaceAll("/+$", "") + "/" + name;
        String[] relativeParts = normalizedParent.equals(normalizedRoot)
                ? new String[0]
                : normalizedParent.substring(normalizedRoot.length() + 1).split("/");
        String script = buildMkdirScript(normalizedRoot, relativeParts, name);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        ExecuteResponse result = execute(
                "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"");
        if (result.exitCode() != null && result.exitCode() != 0) {
            throw new IllegalArgumentException(result.output() == null || result.output().isEmpty()
                    ? "failed to create directory" : result.output());
        }
        return targetPath;
    }

    private static String buildMkdirScript(String root, String[] relativeParts, String name) {
        StringBuilder partsTuple = new StringBuilder("(");
        for (int i = 0; i < relativeParts.length; i++) {
            if (i > 0) {
                partsTuple.append(", ");
            }
            partsTuple.append("'").append(relativeParts[i]).append("'");
        }
        partsTuple.append(")");
        return "import os\n"
                + "root = '" + root + "'\n"
                + "parts = " + partsTuple + "\n"
                + "name = '" + name + "'\n"
                + "directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)\n"
                + "try:\n"
                + "    for part in parts:\n"
                + "        child_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "        os.close(directory_fd)\n"
                + "        directory_fd = child_fd\n"
                + "    os.mkdir(name, 0o755, dir_fd=directory_fd)\n"
                + "finally:\n"
                + "    os.close(directory_fd)\n";
    }

    public void deleteAuthorizedPath(String path, String root) {
        String normalized = normalizePath(path);
        String normalizedRoot = normalizePath(root);
        if (normalized.equals(normalizedRoot) || !isSameOrChild(normalized, normalizedRoot)) {
            throw new IllegalArgumentException("delete path is outside authorized root or is the root");
        }
        String[] parts = normalized.substring(normalizedRoot.length() + 1).split("/");
        String script = buildDeleteScript(normalizedRoot, parts);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        ExecuteResponse result = execute(
                "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"");
        if (result.exitCode() != null && result.exitCode() != 0) {
            throw new FileNotFoundError(normalized);
        }
    }

    private static String buildDeleteScript(String root, String[] parts) {
        StringBuilder partsTuple = new StringBuilder("(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                partsTuple.append(", ");
            }
            partsTuple.append("'").append(parts[i]).append("'");
        }
        partsTuple.append(")");
        return "import os\n"
                + "import stat\n\n"
                + "root = '" + root + "'\n"
                + "parts = " + partsTuple + "\n\n"
                + "def remove_entry(parent_fd, name):\n"
                + "    item_stat = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)\n"
                + "    if not stat.S_ISDIR(item_stat.st_mode):\n"
                + "        os.unlink(name, dir_fd=parent_fd)\n"
                + "        return\n"
                + "    child_fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)\n"
                + "    try:\n"
                + "        for child_name in os.listdir(child_fd):\n"
                + "            remove_entry(child_fd, child_name)\n"
                + "    finally:\n"
                + "        os.close(child_fd)\n"
                + "    os.rmdir(name, dir_fd=parent_fd)\n\n"
                + "directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)\n"
                + "try:\n"
                + "    for part in parts[:-1]:\n"
                + "        child_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)\n"
                + "        os.close(directory_fd)\n"
                + "        directory_fd = child_fd\n"
                + "    remove_entry(directory_fd, parts[-1])\n"
                + "finally:\n"
                + "    os.close(directory_fd)\n";
    }

    // ==================== 结果 DTO ====================

    /** 读结果（对应 deepagents ReadResult 字段子集）。 */
    public static final class ReadResult {
        public final String error;
        public final boolean noLinesRequested;
        public final FileData fileData;
        public final int startLine;
        public final int endLine;
        public final int nextOffset;
        public final Integer totalLines;

        public ReadResult(String error, boolean noLinesRequested, FileData fileData,
                int startLine, int endLine, int nextOffset, Integer totalLines) {
            this.error = error;
            this.noLinesRequested = noLinesRequested;
            this.fileData = fileData;
            this.startLine = startLine;
            this.endLine = endLine;
            this.nextOffset = nextOffset;
            this.totalLines = totalLines;
        }

        public static ReadResult errorOf(String error) {
            return new ReadResult(error, false, null, 0, 0, 0, null);
        }

        public static ReadResult noLinesRequested() {
            return new ReadResult(null, true, null, 0, 0, 0, null);
        }
    }

    /** 文件内容载荷（对应 file_data 的 content / encoding）。 */
    public static final class FileData {
        public final String content;
        public final String encoding;

        public FileData(String content, String encoding) {
            this.content = content;
            this.encoding = encoding;
        }
    }

    /** 写结果（对应 WriteResult）。 */
    public static final class WriteResult {
        public final String error;
        public final String path;

        public WriteResult(String error, String path) {
            this.error = error;
            this.path = path;
        }
    }

    /** 编辑结果（对应 EditResult）。 */
    public static final class EditResult {
        public final String error;
        public final String path;
        public final Integer occurrences;

        public EditResult(String error, String path, Integer occurrences) {
            this.error = error;
            this.path = path;
            this.occurrences = occurrences;
        }
    }

    /** grep 匹配（对应 GrepMatch）。 */
    public static final class GrepMatch {
        public final String path;
        public final int line;
        public final String text;

        public GrepMatch(String path, int line, String text) {
            this.path = path;
            this.line = line;
            this.text = text;
        }
    }

    /** grep 结果（对应 GrepResult）。 */
    public static final class GrepResult {
        public final List<GrepMatch> matches;
        public final String error;
        public final boolean truncated;

        public GrepResult(List<GrepMatch> matches, String error, boolean truncated) {
            this.matches = matches;
            this.error = error;
            this.truncated = truncated;
        }

        public List<GrepMatch> matches() {
            return matches;
        }

        public String error() {
            return error;
        }

        public boolean truncated() {
            return truncated;
        }
    }

    /** glob 匹配（对应 find_files 返回的 {"path": ...}）。 */
    public static final class GlobMatch {
        public final String path;

        public GlobMatch(String path) {
            this.path = path;
        }
    }

    /** glob 结果（对应 GlobResult）。 */
    public static final class GlobResult {
        public final List<GlobMatch> matches;
        public final String error;

        public GlobResult(List<GlobMatch> matches, String error) {
            this.matches = matches;
            this.error = error;
        }
    }

    /** 执行结果（对应 ExecuteResponse）。 */
    public static final class ExecuteResponse {
        public final String output;
        public final Integer exitCode;
        public final boolean truncated;

        public ExecuteResponse(String output, Integer exitCode, boolean truncated) {
            this.output = output;
            this.exitCode = exitCode;
            this.truncated = truncated;
        }

        public String output() {
            return output;
        }

        public Integer exitCode() {
            return exitCode;
        }

        public boolean truncated() {
            return truncated;
        }
    }

    /** 上传结果（对应 FileUploadResponse）。 */
    public static final class FileUploadResponse {
        public final String path;
        public final String error;

        public FileUploadResponse(String path, String error) {
            this.path = path;
            this.error = error;
        }
    }
}
