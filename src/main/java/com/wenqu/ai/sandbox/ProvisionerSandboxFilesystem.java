package com.wenqu.ai.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 框架 {@link BaseSandboxFilesystem} 的问渠实现 —— <b>这一类才让框架的工具真正落到 provisioner 沙箱</b>。
 *
 * <h3>为什么必须有它（踩过的坑）</h3>
 * 只往 {@link RuntimeContext} 里put {@code SandboxContext}（官网「自管沙箱实例」教的做法）
 * <b>不足以让工具落到沙箱</b>：框架的 {@code FilesystemTool} / {@code ShellExecuteTool}
 * 解析的是 {@code ctx.get(AbstractFilesystem.class)}，而
 * {@code HarnessAgent#ensureSessionDefaults}（:1087）只透传 {@code SandboxContext}。
 * 实测后果：模型调 {@code execute("pwd")}，返回的是<b>宿主项目目录</b>而不是沙箱的
 * {@code /home/gem/user-data}——工具静默跑在宿主上，这是最危险的一种失败（看起来成功、实则没隔离）。
 * <p>正确形态：{@code SandboxContext} 管<b>生命周期</b>（框架 stop / 应用 shutdown），
 * 本类作为 {@code AbstractFilesystem} 管<b>工具实际怎么访问沙箱</b>，两者都要出现在 RuntimeContext 里。
 *
 * <h3>为什么只实现四个方法</h3>
 * {@link BaseSandboxFilesystem} 类注释原文：*"This class provides default implementations for all
 * {@code AbstractFilesystem} methods by delegating to shell commands via {@code execute}().
 * File listing, grep, and glob use standard Unix commands. Read uses server-side commands
 * for paginated access. Edit uses server-side commands for string replacement."*
 * ——即 read/write/edit/ls/grep/glob 全部由框架实现，问渠只提供 execute + 两个字节搬运方法。
 *
 * <p><b>前提</b>：沙盒镜像需满足 POSIX 工具链（已实测火山 all-in-one-sandbox:1.11.0 通过
 * {@code python3} / {@code tar} / {@code base64} / {@code stat -c} / {@code grep -rHnF --include}）。
 */
public class ProvisionerSandboxFilesystem extends BaseSandboxFilesystem {

    private static final Logger log = LoggerFactory.getLogger(ProvisionerSandboxFilesystem.class);

    /** 字节搬运的分块大小；下载流按块拼接，单文件过大时避免一次性占满堆。 */
    private static final int DOWNLOAD_CHUNK = 64 * 1024;

    /** 下载超时的兜底（问渠 client 的默认行为是可不给，由沙盒侧超时决定）。 */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

    /** 上传用的临时目录；进程退出即清理，不落沙盒工作区。 */
    private static final String UPLOAD_TMP_DIR = System.getProperty("java.io.tmpdir");

    private final ProvisionerSandboxBackend backend;
    private final String sandboxId;
    /** 会话日志存储：与沙箱容器生命周期无关，落宿主本地（见 {@link #sessionStorage}）。 */
    private final io.agentscope.core.session.AtomicSessionStorage journalStorage;

    public ProvisionerSandboxFilesystem(ProvisionerSandboxBackend backend, String sandboxId) {
        this(backend, sandboxId, null);
    }

    public ProvisionerSandboxFilesystem(
            ProvisionerSandboxBackend backend,
            String sandboxId,
            java.nio.file.Path journalRoot) {
        this.backend = Objects.requireNonNull(backend, "backend is required");
        this.sandboxId = Objects.requireNonNull(sandboxId, "sandboxId is required");
        // journalRoot 为 null 时给一个基于用户 home 的稳定目录：
        // 必须有（框架强制非空），但不能落到沙箱或工作区里——那两处都会被容器回收。
        this.journalStorage = new LocalJournalStorage(journalRoot != null
                ? journalRoot
                : java.nio.file.Paths.get(System.getProperty("user.home"), ".wenqu", "agent-journal"));
    }

    @Override
    public String id() {
        return sandboxId;
    }

    /**
     * 会话日志的原子存储（落宿主，不落沙箱）。
     *
     * <p><b>为什么必须提供而不是返回 null</b>：框架 {@code HarnessAgent#sessionLog} 用
     * {@code ctx.get(AbstractFilesystem.class).sessionStorage(ctx)} 构造 {@code JournalSessionLog}，
     * 而后者对 storage 强制 {@code requireNonNull}（{@code JournalSessionLog:55}）。
     * 返回 null 会让整轮问答在 {@code FrameworkSessions#open} 里以 NPE 掐断——
     * 真机验证踩过这个坑。
     *
     * <p>接口默认实现是抛 {@link UnsupportedOperationException}，同样会断；
     * 而接口注释要求 *"Implementations must durably store bytes before ACK"* 且
     * *"The runtime journal storage must not be writable through model filesystem tools"*——
     * 会话日志要跨容器生命周期存活（沙箱会被空闲回收）且模型不该能改它，故落宿主本地。
     */
    @Override
    public io.agentscope.core.session.AtomicSessionStorage sessionStorage(
            RuntimeContext runtimeContext) {
        return journalStorage;
    }

    /**
     * 执行 shell 命令 —— 框架全部文件工具的公共底座。
     *
     * <p>委托 {@link ProvisionerSandboxBackend#execute(String, Integer)}，它已完成
     * {@code cd /home/gem/user-data && { ... }} 工作目录包装（框架发来的相对路径不会丢到容器临时层）、
     * 输出按 maxOutputBytes 截断、异常转infraError 而不是上抛、底层 client 带带外删除自愈。
     */
    @Override
    public ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        ProvisionerSandboxBackend.ExecuteResponse r = backend.execute(command, timeoutSeconds);
        String output = r.output == null ? "" : r.output;
        int exitCode = r.exitCode == null ? 0 : r.exitCode;
        // infraError 表示「沙盒压根没通」而非命令跑失败：此时 exitCode 已被置 1，
        // 这里把输出原样带回，让模型看见「连不上沙盒」而不是一句空的成功。
        return new ExecuteResponse(output, exitCode, r.truncated);
    }

    /**
     * 上传文件字节。
     *
     * <p>框架协议是 {@code Map.Entry<相对路径, 字节数组>}，问渠 client 要的是 {@link File}，
     * 故先落一份临时文件再交给 client；传完即删（不留痕、不落沙盒工作区）。
     */
    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> results = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return results;
        }
        for (Map.Entry<String, byte[]> entry : files) {
            String path = entry.getKey();
            byte[] content = entry.getValue();
            File tmp = null;
            try {
                tmp = File.createTempFile("wq-upload-", ".bin", new File(UPLOAD_TMP_DIR));
                Files.write(tmp.toPath(), content == null ? new byte[0] : content);
                SandboxRuntimeClient.SandboxWriteResult r =
                        backend.getClient().uploadFile(tmp, path);
                results.add(r.success()
                        ? FileUploadResponse.success(path)
                        : FileUploadResponse.fail(path, r.message()));
            } catch (IOException | RuntimeException e) {
                log.warn("[SANDBOX] 上传失败 path={}: {}", path, e.toString());
                results.add(FileUploadResponse.fail(path, "上传失败：" + e.getMessage()));
            } finally {
                if (tmp != null && tmp.exists() && !tmp.delete()) {
                    tmp.deleteOnExit();
                }
            }
        }
        return results;
    }

    /**
     * 下载文件字节。
     *
     * <p>问渠 client 返回分块流（{@code Iterable<byte[]>}），框架要单个 byte[]，
     * 故按块拼装后再返回。单文件上限沿用后端既有的二进制预览上限，
     * 超过即返回明确错误而不是把内存吃穿。
     */
    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>();
        if (paths == null || paths.isEmpty()) {
            return results;
        }
        for (String path : paths) {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                for (byte[] chunk : backend.getClient().downloadFile(path, DOWNLOAD_TIMEOUT)) {
                    if (chunk == null || chunk.length == 0) continue;
                    if (buf.size() + chunk.length > MaxDownloadBytes) {
                        results.add(FileDownloadResponse.fail(path,
                                "文件超过可下载上限（" + MaxDownloadBytes + " 字节），请改用分段读取或命令处理"));
                        buf = null;
                        break;
                    }
                    buf.write(chunk);
                }
                if (buf != null) {
                    results.add(FileDownloadResponse.success(path, buf.toByteArray()));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("[SANDBOX] 下载失败 path={}: {}", path, e.toString());
                results.add(FileDownloadResponse.fail(path, "下载失败：" + e.getMessage()));
            }
        }
        return results;
    }

    /** 单文件下载上限；与后端二进制预览上限同量级，避免框架工具为大文件吃满堆。 */
    private static final int MaxDownloadBytes = 64 * 1024 * 1024;

    /** 未使用的常量占位移除后的说明：{@link #DOWNLOAD_CHUNK} 供未来按块限流使用。 */
    static int downloadChunkSize() {
        return DOWNLOAD_CHUNK;
    }

    // ==================== ls / glob：自己实现，不用框架那份 ====================

    /**
     * 列目录。
     *
     * <p><b>为什么要覆盖框架实现</b>：{@code BaseSandboxFilesystem#ls} 与 {@code #glob} 都靠
     * {@code printf '...%s\t%s...'} 打字段、再用 {@code split("\t")} 切回来，而本沙盒 runtime 的
     * {@code /v1/shell/exec} 会把<b>输出里的制表符展成空格</b>（实测：同一条 {@code printf 'a\tb'}
     * 在容器内 {@code od -c} 看到的是 {@code a \t b}，取回来的 JSON 却是 {@code "a       b"}）。
     * 字段切不开不会报错，后果是<b>整行被当成路径</b>——{@code list_files} 交给模型的路径带着
     * 尾巴上的大小与时间戳，模型照着读必然 {@code file_not_found}；{@code glob_files} 同理恒空
     * （技能目录 {@code skills/**} 的扫描就死在这里）。
     *
     * <p>这里改用竖线分列，并把<b>路径放在最后一个字段</b>：限次切分把剩下的整段原样留作路径，
     * 路径里真出现竖线也不丢字节。竖线与两个连续空格都实测能原样穿过
     * runtime（被吞的只有制表符）。
     */
    @Override
    public io.agentscope.harness.agent.filesystem.model.LsResult ls(
            RuntimeContext runtimeContext, String path) {
        String dir = shellArg(path == null || path.isBlank() ? "." : path);
        String cmd = "if [ ! -e " + dir + " ]; then echo 'WQ|NOT_EXISTS';"
                + " elif [ ! -d " + dir + " ]; then echo 'WQ|NOT_A_DIR';"
                + " else for f in " + dir + "/*; do"
                + "   if [ -d \"$f\" ]; then m=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
                + "     printf 'WQ|D|%s|%s\\n' \"$m\" \"$f\";"
                + "   elif [ -f \"$f\" ]; then s=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
                + "     m=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
                + "     printf 'WQ|F|%s|%s|%s\\n' \"$s\" \"$m\" \"$f\";"
                + "   fi;"
                + " done; fi";
        ExecuteResponse r = execute(runtimeContext, cmd, null);
        String out = r.output() == null ? "" : r.output().trim();
        if (!r.isSuccess()) {
            return io.agentscope.harness.agent.filesystem.model.LsResult.fail(
                    "列目录失败（exit=" + r.exitCode() + "）：" + brief(out, path));
        }
        if ("WQ|NOT_EXISTS".equals(out)) {
            return io.agentscope.harness.agent.filesystem.model.LsResult.fail("路径不存在：" + path);
        }
        if ("WQ|NOT_A_DIR".equals(out)) {
            return io.agentscope.harness.agent.filesystem.model.LsResult.fail("不是目录：" + path);
        }
        List<io.agentscope.harness.agent.filesystem.model.FileInfo> entries = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.startsWith("WQ|D|")) {
                String[] f = line.substring(5).split("\\|", 2);
                entries.add(io.agentscope.harness.agent.filesystem.model.FileInfo.ofDir(
                        f.length > 1 ? f[1] : "", epochMs(f)));
            } else if (line.startsWith("WQ|F|")) {
                String[] f = line.substring(5).split("\\|", 3);
                entries.add(io.agentscope.harness.agent.filesystem.model.FileInfo.ofFile(
                        f.length > 2 ? f[2] : "", num(f.length > 0 ? f[0] : null), epochMs(f)));
            }
        }
        return io.agentscope.harness.agent.filesystem.model.LsResult.success(entries);
    }

    /**
     * 按文件名模式找文件：只需路径（框架的调用方 {@code WorkspaceSkillRepository} 就只用
     * {@code FileInfo#path()}），所以干脆不打尺寸与时间，一行一条路径，没有可切错的字段。
     */
    @Override
    public io.agentscope.harness.agent.filesystem.model.GlobResult glob(
            RuntimeContext runtimeContext, String pattern, String path) {
        String recursive = pattern != null && pattern.startsWith("**/")
                ? pattern.substring(3) : pattern;
        String cmd = "find " + shellArg(path == null || path.isBlank() ? "." : path)
                + " -type f -name " + shellArg(recursive == null ? "*" : recursive)
                + " 2>/dev/null | sort";
        ExecuteResponse r = execute(runtimeContext, cmd, null);
        if (!r.isSuccess()) {
            return io.agentscope.harness.agent.filesystem.model.GlobResult.fail(
                    "列举失败（exit=" + r.exitCode() + "）：" + brief(r.output(), path));
        }
        String out = r.output() == null ? "" : r.output().trim();
        List<io.agentscope.harness.agent.filesystem.model.FileInfo> matches = new ArrayList<>();
        if (!out.isEmpty()) {
            for (String line : out.split("\n")) {
                String p = line.trim();
                if (!p.isEmpty()) {
                    matches.add(io.agentscope.harness.agent.filesystem.model.FileInfo.ofFile(p, 0, ""));
                }
            }
        }
        return io.agentscope.harness.agent.filesystem.model.GlobResult.success(matches);
    }

    private static String shellArg(String s) {
        return io.agentscope.harness.agent.filesystem.util.FilesystemUtils.shellQuote(s);
    }

    private static long num(String s) {
        try {
            return s == null ? 0L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** shell 里 {@code stat -c '%Y'} 给的是秒，FileInfo 要毫秒。 */
    private static long epochMs(String[] fields) {
        if (fields == null || fields.length == 0) return 0L;
        return num(fields[0]) * 1000L;
    }

    /** 失败信息里带上命令的开头，便于判断是「路径没对上」还是「沙盒没通」。 */
    private static String brief(String output, String path) {
        String o = output == null ? "" : output.strip().replace('\n', ' ');
        return "path=" + path + " output=" + (o.length() > 200 ? o.substring(0, 200) : o);
    }
}