package com.wenqu.ai.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.wenqu.ai.executor.ExecutorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 {@link ExecutorRegistry.VirtualExecutor} 的 op 协议翻译成 SSH：SFTP 文件系统 + ChannelExec + 审计。
 *
 * <p>op 语义与 wenqu-agent（本机执行器）保持一致，因此 {@code ExecutorFilesystem}（模型工具）与
 * {@code ExecutorFiles}（文件页）可以完全复用。路径围栏两道：
 * <ol>
 *   <li>{@link SshPathGuard} 词法规整 + 根前缀；</li>
 *   <li>变更/读取类操作再跑一次远端 {@code realpath -m}，防符号链接逃逸（root 的 realpath 缓存）。</li>
 * </ol>
 *
 * @author yuanke
 */
public class SshRemoteExecutor implements ExecutorRegistry.VirtualExecutor {

    private static final Logger log = LoggerFactory.getLogger(SshRemoteExecutor.class);

    private static final int READ_ALL_CAP = 20 * 1024 * 1024;
    private static final int MAX_TREE_FILES = 4000;
    private static final int MAX_GREP_LINES = 4000;

    private final String hostId;
    private final String virtualId;
    private final SshHostService hostService;
    private final SshConnectionManager manager;
    private final SshAuditService audit;
    /** 主机快照：在装配它的请求线程取好；执行线程不再回库（工具线程可能没有租户上下文）。 */
    private final SshHost host;

    /** root 的真实路径缓存（远端 realpath），key=root 原始路径。 */
    private final Map<String, String> rootRealCache = new java.util.concurrent.ConcurrentHashMap<>();

    public SshRemoteExecutor(String hostId, SshHostService hostService,
                             SshConnectionManager manager, SshAuditService audit) {
        this.hostId = hostId;
        this.virtualId = SshHost.executorIdOf(hostId);
        this.hostService = hostService;
        this.manager = manager;
        this.audit = audit;
        this.host = hostService.byId(hostId);
    }

    @Override
    public boolean online() {
        return host != null && SshHost.STATUS_ACTIVE.equals(host.getStatus());
    }

    @Override
    public void close() {
        // 不主动断连接：同一主机可能被并发会话使用；失效由 SshHostService 显式 invalidate。
    }

    @Override
    public ExecutorRegistry.Result execute(String op, Map<String, Object> args) {
        long began = System.currentTimeMillis();
        if (host == null || !SshHost.STATUS_ACTIVE.equals(host.getStatus())) {
            return ExecutorRegistry.Result.fail("E_EXECUTOR_OFFLINE", "SSH 主机不存在或已停用");
        }
        // 首次实际使用且指纹未固定：先实连一次把指纹 TOFU 落库，再执行本次调用。
        if (host.getHostKeyFingerprint() == null || host.getHostKeyFingerprint().isBlank()) {
            try {
                hostService.ensurePinned(host);
            } catch (SshOpException e) {
                return ExecutorRegistry.Result.fail(e.code(), e.getMessage());
            } catch (Exception e) {
                return ExecutorRegistry.Result.fail("E_EXECUTOR_OFFLINE", "SSH 主机首次连接失败：" + e.getMessage());
            }
        }
        SshCredentials creds;
        try {
            creds = hostService.credentialsOf(host);
        } catch (Exception e) {
            return ExecutorRegistry.Result.fail("E_PERMISSION", "SSH 凭据读取失败：" + e.getMessage());
        }
        List<SshPathGuard.Root> roots = roots(args == null ? null : args.get("roots"));
        boolean unrestricted = args != null && "danger-full-access".equals(String.valueOf(args.get("policy")));
        String auditedPath = pathOf(args);
        String userId = str(args == null ? null : args.get("uid"));
        if (userId.isBlank()) userId = str(args == null ? null : args.get("_uid"));
        String tenantId = str(args == null ? null : args.get("_tenantId"));
        String sessionId = str(args == null ? null : args.get("sessionId"));
        String turnId = str(args == null ? null : args.get("turnId"));
        try {
            ExecutorRegistry.Result r = dispatch(op, args, host, creds, roots, unrestricted);
            audit.record(tenantId, userId, "ssh", virtualId, hostId, null, sessionId, turnId,
                    op, SshPathGuard.relativeTo(auditedPath, roots), r.ok(), r.code(),
                    System.currentTimeMillis() - began, 0, estimateBytes(r));
            return r;
        } catch (SshPathGuard.PathOutsideException e) {
            audit.record(tenantId, userId, "ssh", virtualId, hostId, null, sessionId, turnId,
                    op, SshPathGuard.relativeTo(auditedPath, roots), false, "E_PATH_OUTSIDE",
                    System.currentTimeMillis() - began, 0, 0);
            return ExecutorRegistry.Result.fail("E_PATH_OUTSIDE", e.getMessage());
        } catch (SshOpException e) {
            audit.record(tenantId, userId, "ssh", virtualId, hostId, null, sessionId, turnId,
                    op, SshPathGuard.relativeTo(auditedPath, roots), false, e.code(),
                    System.currentTimeMillis() - began, 0, 0);
            return ExecutorRegistry.Result.fail(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            audit.record(tenantId, userId, "ssh", virtualId, hostId, null, sessionId, turnId,
                    op, SshPathGuard.relativeTo(auditedPath, roots), false, "E_INVALID",
                    System.currentTimeMillis() - began, 0, 0);
            return ExecutorRegistry.Result.fail("E_INVALID", e.getMessage());
        } catch (Exception e) {
            log.warn("[SSH] 远端执行失败 host={} op={}: {}", hostId, op, e.toString());
            audit.record(tenantId, userId, "ssh", virtualId, hostId, null, sessionId, turnId,
                    op, SshPathGuard.relativeTo(auditedPath, roots), false, "E_IO",
                    System.currentTimeMillis() - began, 0, 0);
            return ExecutorRegistry.Result.fail("E_IO", "SSH 远端执行失败：" + e.getMessage());
        }
    }

    private ExecutorRegistry.Result dispatch(String op, Map<String, Object> args, SshHost host,
                                             SshCredentials creds, List<SshPathGuard.Root> roots,
                                             boolean unrestricted) throws Exception {
        String primary = primaryRoot(roots);
        switch (op) {
            case "exec":
                return exec(args, host, creds, primary);
            case "list":
                return list(args, host, creds, roots, unrestricted, primary);
            case "stat":
                return stat(args, host, creds, roots, unrestricted);
            case "read":
                return read(args, host, creds, roots, unrestricted);
            case "readBytes":
                return readBytes(args, host, creds, roots, unrestricted);
            case "write":
                return write(args, host, creds, roots, unrestricted);
            case "writeBytes":
                return writeBytes(args, host, creds, roots, unrestricted);
            case "writeChunk":
                return writeChunk(args, host, creds, roots, unrestricted);
            case "mkdir":
                return mkdir(args, host, creds, roots, unrestricted);
            case "move":
                return move(args, host, creds, roots, unrestricted);
            case "remove":
                return remove(args, host, creds, roots, unrestricted);
            case "tree":
                return tree(args, host, creds, roots, unrestricted);
            case "grep":
                return grep(args, host, creds, roots, unrestricted);
            case "find":
                return find(args, host, creds, roots, unrestricted);
            case "openPath":
                return ExecutorRegistry.Result.fail("E_UNSUPPORTED", "远程主机没有桌面文件管理器，无法“在电脑上打开”");
            case "pickDir":
                return ExecutorRegistry.Result.fail("E_UNSUPPORTED", "远程主机不支持原生目录选择，请用远端目录浏览");
            case "turnStart":
            case "turnEnd":
            case "turnDiff":
                return ExecutorRegistry.Result.fail("E_UNSUPPORTED", "SSH 工作区暂不支持每轮文件改动快照");
            default:
                return ExecutorRegistry.Result.fail("E_UNSUPPORTED", "SSH 执行器不支持操作：" + op);
        }
    }

    // ==================== 命令 ====================

    private ExecutorRegistry.Result exec(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         String primary) {
        String command = str(args.get("command"));
        if (command.isBlank()) throw new IllegalArgumentException("命令不能为空");
        int seconds = args.get("timeoutSeconds") instanceof Number n ? n.intValue() : 120;
        seconds = Math.max(1, Math.min(seconds, 600));
        String cwd = primary == null || primary.isBlank() ? "~" : SshShell.quote(primary);
        String full = "cd " + cwd + " && { " + command + "\n}";
        SshConnectionManager.ExecResult r = manager.exec(host, creds, full, (seconds + 2) * 1000);
        if (r.exitCode() == null) {
            return ExecutorRegistry.Result.fail("E_TIMEOUT", "SSH 命令超时未返回（上限 " + seconds + "s）");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("output", r.output());
        data.put("exitCode", r.exitCode());
        data.put("truncated", r.truncated());
        return ExecutorRegistry.Result.ok(data);
    }

    // ==================== 文件 ====================

    private ExecutorRegistry.Result list(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted, String primary) {
        String raw = str(args.get("path"));
        String path = raw.isBlank() ? (primary == null || primary.isBlank() ? home(host, creds) : primary)
                : guard(raw, roots, unrestricted, false, host, creds);
        List<Map<String, Object>> entries = sftp(host, creds, s -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : s.ls(path)) {
                if (!(o instanceof ChannelSftp.LsEntry e)) continue;
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                SftpATTRS a = e.getAttrs();
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("path", join(path, name));
                one.put("name", name);
                one.put("isDir", a.isDir());
                one.put("isSymlink", a.isLink());
                one.put("size", a.getSize());
                one.put("mtimeMs", a.getMTime() * 1000L);
                out.add(one);
            }
            return out;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", path);
        data.put("entries", entries);
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result stat(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        Map<String, Object> data = sftp(host, creds, s -> statMap(s, path));
        return ExecutorRegistry.Result.ok(data);
    }

    private Map<String, Object> statMap(ChannelSftp s, String path) throws SftpException {
        SftpATTRS a = s.stat(path);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("name", baseName(path));
        m.put("size", a.getSize());
        m.put("mtimeMs", a.getMTime() * 1000L);
        m.put("isDir", a.isDir());
        m.put("isSymlink", a.isLink());
        return m;
    }

    private ExecutorRegistry.Result read(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        int offset = args.get("offset") instanceof Number n ? Math.max(0, n.intValue()) : 0;
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 0;
        Map<String, Object> data = sftp(host, creds, s -> {
            SftpATTRS a = s.stat(path);
            if (a.isDir()) throw new SshOpException("E_IO", "是一个文件夹，不能按文本读取：" + path);
            String text = new String(readAll(s, path, READ_ALL_CAP), StandardCharsets.UTF_8);
            String content = sliceLines(text, offset, limit);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("content", content);
            m.put("encoding", "utf-8");
            return m;
        });
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result readBytes(Map<String, Object> args, SshHost host, SshCredentials creds,
                                              List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        long offset = args.get("offset") instanceof Number n ? Math.max(0, n.longValue()) : 0L;
        int length = args.get("length") instanceof Number n ? n.intValue() : 0;
        Map<String, Object> data = sftp(host, creds, s -> {
            SftpATTRS a = s.stat(path);
            long total = a.getSize();
            int want = length <= 0 ? (int) Math.min(total - offset, READ_ALL_CAP)
                    : (int) Math.min(length, Math.max(0, total - offset));
            byte[] bytes;
            if (want <= 0) {
                bytes = new byte[0];
            } else {
                try (InputStream in = s.get(path)) {
                    skipFully(in, offset);
                    bytes = readN(in, want);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contentBase64", Base64.getEncoder().encodeToString(bytes));
            m.put("totalBytes", total);
            return m;
        });
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result write(Map<String, Object> args, SshHost host, SshCredentials creds,
                                          List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        String content = args.get("content") == null ? "" : String.valueOf(args.get("content"));
        String expectedHash = args.get("expectedHash") == null ? null : String.valueOf(args.get("expectedHash"));
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        sftp(host, creds, s -> {
            if (expectedHash != null && !expectedHash.isBlank()) {
                byte[] old;
                try {
                    old = readAll(s, path, READ_ALL_CAP);
                } catch (SshOpException e) {
                    throw new SshOpException("E_STALE", "文件已被删除或读取失败，请重新读取后再改");
                }
                if (!sha256Hex(old).equalsIgnoreCase(expectedHash)) {
                    throw new SshOpException("E_STALE", "文件在读取之后已被改动，请重新读取后再改");
                }
            }
            writeAtomic(s, path, bytes);
            return null;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bytes", bytes.length);
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result writeBytes(Map<String, Object> args, SshHost host, SshCredentials creds,
                                               List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        byte[] bytes = Base64.getDecoder().decode(str(args.get("contentBase64")));
        sftp(host, creds, s -> {
            mkdirs(s, parentOf(path));
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                s.put(in, path, ChannelSftp.OVERWRITE);
            }
            return null;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bytes", bytes.length);
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result writeChunk(Map<String, Object> args, SshHost host, SshCredentials creds,
                                               List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        long offset = args.get("offset") instanceof Number n ? Math.max(0, n.longValue()) : 0L;
        byte[] chunk = Base64.getDecoder().decode(str(args.get("contentBase64")));
        sftp(host, creds, s -> {
            mkdirs(s, parentOf(path));
            long size = 0;
            try {
                size = s.stat(path).getSize();
            } catch (SftpException ignore) {
            }
            if (offset == size) {
                try (InputStream in = new ByteArrayInputStream(chunk)) {
                    s.put(in, path, size == 0 ? ChannelSftp.OVERWRITE : ChannelSftp.APPEND);
                }
            } else {
                byte[] old = size > 0 ? readAll(s, path, READ_ALL_CAP) : new byte[0];
                int end = (int) Math.max(offset + chunk.length, old.length);
                byte[] merged = new byte[end];
                System.arraycopy(old, 0, merged, 0, old.length);
                System.arraycopy(chunk, 0, merged, (int) offset, chunk.length);
                writeAtomic(s, path, merged);
            }
            return null;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bytes", chunk.length);
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result mkdir(Map<String, Object> args, SshHost host, SshCredentials creds,
                                          List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        sftp(host, creds, s -> {
            mkdirs(s, path);
            return null;
        });
        return ExecutorRegistry.Result.ok(Map.of());
    }

    private ExecutorRegistry.Result move(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted) {
        String from = guard(str(args.get("from")), roots, unrestricted, true, host, creds);
        String to = guard(str(args.get("to")), roots, unrestricted, true, host, creds);
        sftp(host, creds, s -> {
            mkdirs(s, parentOf(to));
            try {
                s.rename(from, to);
            } catch (SftpException e) {
                // 目标已存在：先删再改名（页面移动的 replace 语义）
                try {
                    removeRecursive(s, to);
                    s.rename(from, to);
                } catch (SftpException again) {
                    throw translate(again);
                }
            }
            return null;
        });
        return ExecutorRegistry.Result.ok(Map.of());
    }

    private ExecutorRegistry.Result remove(Map<String, Object> args, SshHost host, SshCredentials creds,
                                           List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, true, host, creds);
        sftp(host, creds, s -> {
            removeRecursive(s, path);
            return null;
        });
        return ExecutorRegistry.Result.ok(Map.of());
    }

    private ExecutorRegistry.Result tree(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, false, host, creds);
        int maxFiles = args.get("maxFiles") instanceof Number n ? Math.min(n.intValue(), MAX_TREE_FILES) : 500;
        int maxDepth = args.get("maxDepth") instanceof Number n ? Math.min(n.intValue(), 12) : 8;
        List<Map<String, Object>> entries = sftp(host, creds, s -> {
            List<Map<String, Object>> out = new ArrayList<>();
            walk(s, path, "", 0, maxDepth, maxFiles, out);
            return out;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entries", entries);
        return ExecutorRegistry.Result.ok(data);
    }

    private void walk(ChannelSftp s, String base, String rel, int depth, int maxDepth, int maxFiles,
                      List<Map<String, Object>> out) throws SftpException {
        if (depth >= maxDepth || out.size() >= maxFiles) return;
        for (Object o : s.ls(base)) {
            if (!(o instanceof ChannelSftp.LsEntry e)) continue;
            String name = e.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            SftpATTRS a = e.getAttrs();
            if (a.isLink()) continue;
            String childRel = rel.isEmpty() ? name : rel + "/" + name;
            String childAbs = join(base, name);
            if (a.isDir()) {
                walk(s, childAbs, childRel, depth + 1, maxDepth, maxFiles, out);
            } else {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("path", childRel);
                one.put("name", name);
                one.put("isDir", false);
                one.put("size", a.getSize());
                one.put("mtimeMs", a.getMTime() * 1000L);
                out.add(one);
                if (out.size() >= maxFiles) return;
            }
        }
    }

    private ExecutorRegistry.Result grep(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted) {
        String pattern = str(args.get("pattern"));
        if (pattern.isEmpty()) throw new IllegalArgumentException("搜索内容不能为空");
        String path = guard(str(args.get("path")), roots, unrestricted, false, host, creds);
        String glob = str(args.get("glob"));
        StringBuilder cmd = new StringBuilder("grep -rHnF");
        if (!glob.isBlank()) cmd.append(" --include=").append(SshShell.quote(glob));
        cmd.append(" -e ").append(SshShell.quote(pattern)).append(' ').append(SshShell.quote(path))
                .append(" 2>/dev/null | head -n ").append(MAX_GREP_LINES);
        SshConnectionManager.ExecResult r = manager.exec(host, creds, cmd.toString(), 30_000);
        List<Map<String, Object>> matches = new ArrayList<>();
        for (String line : r.output().split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(":", 3);
            if (parts.length < 3) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", parts[0]);
            try {
                m.put("line", Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                m.put("line", 0);
            }
            m.put("text", parts[2]);
            matches.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matches", matches);
        return ExecutorRegistry.Result.ok(data);
    }

    private ExecutorRegistry.Result find(Map<String, Object> args, SshHost host, SshCredentials creds,
                                         List<SshPathGuard.Root> roots, boolean unrestricted) {
        String path = guard(str(args.get("path")), roots, unrestricted, false, host, creds);
        String pattern = str(args.get("pattern"));
        if (pattern.isBlank()) pattern = "*";
        final java.nio.file.PathMatcher matcher;
        try {
            matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("非法的文件名通配符：" + pattern);
        }
        final String base = path;
        List<Map<String, Object>> entries = sftp(host, creds, s -> {
            List<Map<String, Object>> out = new ArrayList<>();
            walkFind(s, base, "", matcher, out);
            return out;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entries", entries);
        return ExecutorRegistry.Result.ok(data);
    }

    /** 用 SFTP 递归找文件（不依赖远端 find/stat 的 GNU 选项，mac/BSD 也能用）。 */
    private void walkFind(ChannelSftp s, String base, String rel, java.nio.file.PathMatcher matcher,
                          List<Map<String, Object>> out) throws SftpException {
        if (out.size() >= MAX_TREE_FILES) return;
        for (Object o : s.ls(base)) {
            if (!(o instanceof ChannelSftp.LsEntry e)) continue;
            String name = e.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            SftpATTRS a = e.getAttrs();
            if (a.isLink()) continue;
            String childRel = rel.isEmpty() ? name : rel + "/" + name;
            String childAbs = join(base, name);
            if (a.isDir()) {
                walkFind(s, childAbs, childRel, matcher, out);
            } else if (matcher.matches(java.nio.file.Path.of(name))) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("path", childAbs);
                one.put("name", name);
                one.put("isDir", false);
                one.put("size", a.getSize());
                one.put("mtimeMs", a.getMTime() * 1000L);
                out.add(one);
                if (out.size() >= MAX_TREE_FILES) return;
            }
        }
    }

    // ==================== 围栏 ====================

    private String guard(String raw, List<SshPathGuard.Root> roots, boolean unrestricted, boolean checkReal,
                         SshHost host, SshCredentials creds) {
        String path = SshPathGuard.check(raw, roots, unrestricted);
        if (checkReal) realPathCheck(host, creds, path, roots, unrestricted);
        return path;
    }

    /** 远端 realpath 真实路径校验（防软链逃逸）。在需要严格校验的写操作上由调用方选择启用。 */
    private void realPathCheck(SshHost host, SshCredentials creds, String path,
                               List<SshPathGuard.Root> roots, boolean unrestricted) {
        if (unrestricted || roots == null || roots.isEmpty()) return;
        String real = manager.exec(host, creds, "realpath -m -- " + SshShell.quote(path) + " 2>/dev/null", 8_000)
                .output().strip();
        if (real.isBlank()) return; // 远端没有 realpath：词法围栏已在前面生效
        for (SshPathGuard.Root r : roots) {
            if (r.path() == null || r.path().isBlank()) continue;
            String rootReal = rootRealCache.computeIfAbsent(r.path(),
                    k -> manager.exec(host, creds, "realpath -m -- " + SshShell.quote(k) + " 2>/dev/null",
                            8_000).output().strip());
            if (rootReal.isBlank()) continue;
            if (SshPathGuard.isSameOrChild(real, rootReal)) return;
        }
        throw new SshPathGuard.PathOutsideException("路径的真实位置越出工作区源文件夹（可能是符号链接）：" + path);
    }

    // ==================== SFTP 工具 ====================

    @FunctionalInterface
    private interface SftpCall<T> {
        T run(ChannelSftp sftp) throws Exception;
    }

    private <T> T sftp(SshHost host, SshCredentials creds, SftpCall<T> call) {
        return manager.withSftp(host, creds, s -> {
            try {
                return call.run(s);
            } catch (SftpException e) {
                throw translate(e);
            } catch (SshOpException e) {
                throw e;
            } catch (Exception e) {
                throw new SshOpException("E_IO", "SFTP 操作失败：" + e.getMessage());
            }
        });
    }

    private static SshOpException translate(SftpException e) {
        int id = e.id;
        if (id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            return new SshOpException("E_NOT_FOUND", "远端文件不存在：" + e.getMessage());
        }
        if (id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            return new SshOpException("E_PERMISSION", "远端权限不足：" + e.getMessage());
        }
        return new SshOpException("E_IO", "SFTP 错误：" + e.getMessage());
    }

    private static byte[] readAll(ChannelSftp s, String path, int cap) throws Exception {
        try (InputStream in = s.get(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                int room = cap - out.size();
                if (room <= 0) break;
                out.write(buf, 0, Math.min(n, room));
            }
            return out.toByteArray();
        }
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int left = n;
        while (left > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, left));
            if (r == -1) break;
            out.write(buf, 0, r);
            left -= r;
        }
        return out.toByteArray();
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) return;
                left--;
            } else {
                left -= skipped;
            }
        }
    }

    private static void writeAtomic(ChannelSftp s, String path, byte[] bytes) throws Exception {
        mkdirs(s, parentOf(path));
        String tmp = join(parentOf(path), ".wenqu-tmp-" + UUID.randomUUID().toString().replace("-", ""));
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            s.put(in, tmp, ChannelSftp.OVERWRITE);
        }
        try {
            s.rename(tmp, path);
        } catch (SftpException e) {
            try {
                removeRecursive(s, path);
                s.rename(tmp, path);
            } catch (SftpException again) {
                try {
                    s.rm(tmp);
                } catch (SftpException ignore) {
                }
                throw again;
            }
        }
    }

    private static void mkdirs(ChannelSftp s, String path) throws SftpException {
        if (path == null || path.isBlank() || "/".equals(path) || ".".equals(path)) return;
        String cur = "";
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            cur = cur + "/" + seg;
            try {
                SftpATTRS a = s.stat(cur);
                if (!a.isDir()) throw new SshOpException("E_IO", "同名文件已存在，无法建目录：" + cur);
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    s.mkdir(cur);
                } else {
                    throw e;
                }
            }
        }
    }

    private static void removeRecursive(ChannelSftp s, String path) throws SftpException {
        SftpATTRS a;
        try {
            a = s.stat(path);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return;
            throw e;
        }
        if (a.isDir()) {
            for (Object o : s.ls(path)) {
                if (!(o instanceof ChannelSftp.LsEntry e)) continue;
                String name = e.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;
                removeRecursive(s, join(path, name));
            }
            s.rmdir(path);
        } else {
            s.rm(path);
        }
    }

    private String home(SshHost host, SshCredentials creds) {
        String home = manager.exec(host, creds, "printf %s \"$HOME\"", 5_000).output().strip();
        return home.isBlank() ? "/" : home;
    }

    private static String primaryRoot(List<SshPathGuard.Root> roots) {
        if (roots == null || roots.isEmpty()) return null;
        for (SshPathGuard.Root r : roots) if (r.primary()) return r.path();
        return roots.get(0).path();
    }

    private static List<SshPathGuard.Root> roots(Object raw) {
        List<SshPathGuard.Root> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                Object alias = m.get("alias");
                Object path = m.get("path");
                boolean primary = Boolean.TRUE.equals(m.get("primary"));
                if (path != null && !String.valueOf(path).isBlank()) {
                    out.add(new SshPathGuard.Root(alias == null ? "" : String.valueOf(alias),
                            String.valueOf(path), primary));
                }
            }
        }
        return out;
    }

    private static String pathOf(Map<String, Object> args) {
        if (args == null) return null;
        Object p = args.get("path");
        if (p == null) p = args.get("from");
        return p == null ? null : String.valueOf(p);
    }

    private static long estimateBytes(ExecutorRegistry.Result r) {
        if (r == null || r.data() == null) return 0;
        Object b = r.data().get("bytes");
        if (b instanceof Number n) return n.longValue();
        Object out = r.data().get("output");
        return out instanceof String s ? s.length() : 0;
    }

    private static String sliceLines(String text, int offset, int limit) {
        if (text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        int from = Math.min(offset, lines.length);
        int to = limit > 0 ? Math.min(from + limit, lines.length) : lines.length;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String join(String base, String name) {
        if (base == null || base.isEmpty()) return "/" + name;
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private static String parentOf(String path) {
        if (path == null) return "/";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        if (i <= 0) return "/";
        return p.substring(0, i);
    }

    private static String baseName(String path) {
        if (path == null) return "";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
