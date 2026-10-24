package com.wenqu.ai.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
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
 * 只往 {@link RuntimeContext} 里 put {@code SandboxContext}（官网「自管沙箱实例」教的做法）
 * <b>不足以让工具落到沙箱</b>：框架的 {@code FilesystemTool} / {@code ShellExecuteTool}
 * 在 <b>build 期</b>就从 builder 字段绑好文件系统，{@code RuntimeContext} 里有没有
 * {@code AbstractFilesystem} 它们都不看。实测后果：模型调 {@code execute("pwd")}，
 * 返回的是<b>宿主项目目录</b>而不是沙箱的 {@code /home/gem/user-data}——
 * 工具静默跑在宿主上，这是最危险的一种失败（看起来成功、实则没隔离）。
 * <p>因此本类必须经 {@code HarnessAgent.Builder#abstractFilesystem} 在 build 期传入。
 * 框架另有声明式的 {@code .filesystem(SandboxFilesystemSpec)}（官网文档口径），走那条会在
 * {@code HarnessAgent#build} 里硬 new 一份框架自带的 {@code SandboxBackedFilesystem}，
 * 既接不进问渠的沙箱语义、也不提供会话日志存储（接口默认实现直接抛）——两条证据见
 * {@code docs/harness-framework-migration.md} §P1-5 复核。
 * <p>正确形态：{@code SandboxContext} 管<b>生命周期</b>（框架 stop / 应用 shutdown），
 * 本类作为 {@code AbstractFilesystem} 管<b>工具实际怎么访问沙箱</b>，两者都要出现在 RuntimeContext 里。
 *
 * <h3>哪些交给框架、哪些必须自己实现</h3>
 * {@link BaseSandboxFilesystem} 类注释原文：*"This class provides default implementations for all
 * {@code AbstractFilesystem} methods by delegating to shell commands via {@code execute}().
 * File listing, grep, and glob use standard Unix commands. Read uses server-side commands
 * for paginated access. Edit uses server-side commands for string replacement."*
 * ——问渠只提供 {@code execute} + 两个字节搬运方法 + {@code sessionStorage}，{@code write}/{@code edit}/
 * {@code grep} 沿用框架那份（实测写改后容器内字节与送进去的一致）。
 * <b>{@code ls}、{@code glob}、{@code read}（文本）这三个必须覆盖</b>：框架那三份都靠 shell 取文本，
 * 而沙盒 runtime 会把输出里的制表符展成空格，原因见下面各方法的说明。
 *
 * <p><b>前提</b>：沙盒镜像需满足 POSIX 工具链（已实测火山 all-in-one-sandbox:1.11.0 通过
 * {@code python3} / {@code tar} / {@code base64} / {@code grep -rHnF --include}）。
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
    private final LocalJournalStorage journalStorage;

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
                : LocalJournalStorage.defaultRoot());
    }

    /**
     * 这份文件系统用的会话日志存储（日志轮转与清理要走同一个 root）。
     *
     * <p>每次 {@code SandboxService#frameworkFilesystem} 都新建一份文件系统，因而也新建一份 storage 对象，
     * 但它们都指向同一个物理 root——轮切换的是目录，不是某个实例里的状态。
     */
    public LocalJournalStorage journalStorage() {
        return journalStorage;
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
        // 模型那把 execute 工具的 timeout 有下界：它 schema 里的 30 只是「不传时」的缺省，
        // 显式传 5 也照样收（真机 2026-10-22 那条 pip 就是这么被掐断的）。
        ProvisionerSandboxBackend.ExecuteResponse r =
                backend.execute(command, ProvisionerSandboxBackend.floorToolTimeout(timeoutSeconds));
        String output = r.output == null ? "" : r.output;
        // 没拿到退出码（沙盒不可达 / 到点还在跑）一律记 124，绝不映射成 0：
        // 0 在这条链路上等于「成功」，卡片会变绿色对勾、输出是空的，模型据此以为这步做完了。
        int exitCode = r.exitCode == null ? ProvisionerSandboxBackend.EXIT_NOT_COMPLETED : r.exitCode;
        // infraError 表示「沙盒压根没通」而非命令跑失败：输出里那句「沙盒不可达」原样带回，
        // 让模型看见「连不上沙盒」而不是一句空的成功。
        return new ExecuteResponse(output, exitCode, r.truncated);
    }

    /**
     * 上传文件字节。
     *
     * <p>框架协议是 {@code Map.Entry<相对路径, 字节数组>}，问渠 client 要的是 {@link File}，
     * 故先落一份临时文件再交给 client；传完即删（不留痕、不落沙盒工作区）。
     *
     * <p><b>路径必须转绝对（真机实测踩坑）</b>：框架的 {@code WorkspacePathNormalizer} 会把
     * {@code /home/gem/user-data/xxx} 剥成工作区相对形式（{@code xxx}），而 runtime 的
     * {@code /v1/file/upload} 端点<b>不认相对路径</b>——它按容器根解析相对路径，实测报
     * {@code Permission denied: 'wenqu-website'}（父目录不存在时）或
     * {@code No such file or directory: ''}（裸文件名，dirname 为空）。故这里统一经
     * {@link ProvisionerSandboxBackend#workspaceAbsolutePath(String)} 落到
     * {@code /home/gem/user-data/...}，与 {@link #read} / {@link #ls} / {@link #glob} 同一口径。
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
            String target = backend.workspaceAbsolutePath(path);
            File tmp = null;
            try {
                tmp = File.createTempFile("wq-upload-", ".bin", new File(UPLOAD_TMP_DIR));
                Files.write(tmp.toPath(), content == null ? new byte[0] : content);
                SandboxRuntimeClient.SandboxWriteResult r =
                        backend.getClient().uploadFile(tmp, target);
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
     *
     * <p><b>路径同样要转绝对</b>：理由同 {@link #uploadFiles}——{@code /v1/file/download}
     * 端点不认工作区相对路径。
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
                for (byte[] chunk : backend.getClient().downloadFile(
                        backend.workspaceAbsolutePath(path), DOWNLOAD_TIMEOUT)) {
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

    // ==================== ls / glob：走结构化端点，不走 shell 文本 ====================

    /**
     * 列目录：走 runtime 的 {@code /v1/file/list}。
     *
     * <p><b>为什么要覆盖框架实现</b>：{@code BaseSandboxFilesystem#ls} 与 {@code #glob} 都靠
     * {@code printf '...%s\t%s...'} 打字段、再用 {@code split("\t")} 切回来，而本沙盒 runtime 的
     * {@code /v1/shell/exec} 会把<b>输出里的制表符展成空格</b>。丢字符的是输出侧、不是命令侧（真机实测：
     * 同一条 {@code printf 'A\tB\n'} 在容器内 {@code | od -c} 看到的是 {@code A \t B \n}，
     * 经 {@code /v1/shell/exec} 取回却是 {@code "A       B"}）。字段切不开不会报错，后果是<b>整行被当成路径</b>——
     * {@code list_files} 交给模型的路径带着尾巴上的大小与时间戳，模型照着读必然 {@code file_not_found}；
     * {@code glob_files} 同理恒空（技能目录 {@code skills/**} 的扫描就死在这里）。
     *
     * <p>这里<b>不再只是换个分隔符</b>（换竖线同样是在赌镜像行为），而是改用结构化 JSON 端点：
     * 路径、类型、大小、时间各成字段，实测连文件名里带的制表符都能原样取回。
     * <p>同一个根因还打到 {@link #read}（框架那份读文本也走 shell），见那里的说明；
     * {@code write} / {@code edit} 不覆盖——那两份把内容做成 base64 载荷经 stdin 送进去，不回读文本通道。
     */
    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String target = backend.workspaceAbsolutePath(path);
        try {
            List<FileInfo> entries = new ArrayList<>();
            for (SandboxRuntimeClient.SandboxFileEntry entry
                    : backend.getClient().listPath(target, false, true).files()) {
                entries.add(entry.isDirectory()
                        ? FileInfo.ofDir(entry.path(), epochMs(entry.modifiedTime()))
                        : FileInfo.ofFile(entry.path(), sizeOf(entry), epochMs(entry.modifiedTime())));
            }
            return LsResult.success(entries);
        } catch (FileNotFoundError e) {
            return LsResult.fail("路径不存在：" + target);
        } catch (RuntimeException e) {
            // runtime 对「那是文件不是目录」回 HTTP 200 + success:false，
            // 已由客户端在这一层抛出来；这里显式报给模型，不回空目录。
            return LsResult.fail("列目录失败（path=" + target + "）：" + e.getMessage());
        }
    }

    /**
     * 读文件：走 runtime 的结构化 {@code /v1/file/read}，<b>不用框架那份</b>。
     *
     * <p>根因与 {@link #ls} 同一条，但后果重得多：框架 {@code BaseSandboxFilesystem#read} 只对<b>非文本</b>
     * 文件用 base64，文本一律 {@code sed -n '<起>,<止>p'} —— 还是那条会把制表符展成空格的文本通道。
     * 真机实测：文件里一个制表符缩进（容器内 {@code od -c} 看得到），经框架那份 {@code read} 取回后
     * 制表符计数为 0、变成了 8 个空格。模型「读进来—改一下—写回去」一轮下来<b>源文件缩进被改写</b>，
     * Python 混用制表符与空格就是 {@code IndentationError}。这不是显示不好看，是静默改坏用户的文件。
     *
     * <p>分页换算、读写权限守卫、图片与二进制判定全部复用 {@link ProvisionerSandboxBackend#read}——
     * 同一套判据不许抄第二份，这里只做两份 {@code ReadResult} 之间的契约转换。
     * 二进制口径沿用问渠那份（明确报「不支持直接读二进制」），不跟随框架的「回 base64 正文」：
     * 后者会让模型把一串编码当成文件内容。
     */
    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        // 框架把 limit<=0 当「不限行数」，问渠那份把 null 当不限、把 <=0 当「不请求行」，先对上口径。
        ProvisionerSandboxBackend.ReadResult read = backend.read(
                backend.workspaceAbsolutePath(filePath), offset, limit > 0 ? limit : null);
        return read.error != null
                ? ReadResult.fail(read.error)
                : ReadResult.success(new FileData(read.fileData.content, read.fileData.encoding));
    }

    /**
     * 按文件名模式找文件：同样走结构化端点（{@code recursive=true} 一次取全，再在 Java 侧按 basename 匹配）。
     *
     * <p>不用 runtime 的 {@code /v1/file/find}：实测它的 glob 只匹配同一层（在 {@code skills/} 下找
     * {@code SKILL.md} 返回空），而框架调用方 {@code WorkspaceSkillRepository}（:619）传的是
     * 「双星号斜杠开头 + 星号」那种跨层递归模式，只匹配同层的端点接不住。
     *
     * <p>匹配与结果口径对齐被替掉的 {@code find -type f -name}：只对 basename 生效（含 {@code /} 的模式
     * 匹配不到）、只返回文件。框架调用方只取 {@code FileInfo#path()}，但尺寸与时间一并给出，
     * 供 {@code glob_files} 的展示文案用。
     */
    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String target = backend.workspaceAbsolutePath(path);
        String namePattern = pattern != null && pattern.startsWith("**/")
                ? pattern.substring(3) : pattern;
        java.util.regex.Pattern matcher = toBasenameMatcher(namePattern);
        try {
            List<SandboxRuntimeClient.SandboxFileEntry> listed =
                    backend.getClient().listPath(target, true, true).files();
            if (listed.size() > MAX_GLOB_SCAN_ENTRIES) {
                return GlobResult.fail("目录下条目过多（" + listed.size() + " 条，上限 "
                        + MAX_GLOB_SCAN_ENTRIES + " 条），请把路径收窄：" + target);
            }
            List<FileInfo> matches = new ArrayList<>();
            for (SandboxRuntimeClient.SandboxFileEntry entry : listed) {
                String p = entry.path();
                if (entry.isDirectory() || p == null || p.isEmpty()) {
                    continue;
                }
                String name = p.substring(p.lastIndexOf('/') + 1);
                if (matcher.matcher(name).matches()) {
                    matches.add(FileInfo.ofFile(p, sizeOf(entry), epochMs(entry.modifiedTime())));
                }
            }
            matches.sort(java.util.Comparator.comparing(FileInfo::path));
            return GlobResult.success(matches);
        } catch (FileNotFoundError e) {
            // 路径不存在不回「0 命中」：那会让上层把「目录没建起来」读成「目录是空的」。
            return GlobResult.fail("路径不存在：" + target);
        } catch (RuntimeException e) {
            return GlobResult.fail("列举失败（path=" + target + "）：" + e.getMessage());
        }
    }

    /** 递归扫描的条目上限：超限宁可报错，也不悄悄给模型一份不完整的清单。 */
    private static final int MAX_GLOB_SCAN_ENTRIES = 5000;

    private static long sizeOf(SandboxRuntimeClient.SandboxFileEntry entry) {
        return entry.size() == null ? 0L : entry.size();
    }

    /** runtime 的 {@code modified_time} 是秒（字符串），FileInfo 要毫秒。 */
    private static long epochMs(String epochSeconds) {
        if (epochSeconds == null || epochSeconds.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(epochSeconds.strip()) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** glob 模式转 basename 正则：只有 {@code *} 与 {@code ?} 是通配，其余一律按字面。 */
    private static java.util.regex.Pattern toBasenameMatcher(String glob) {
        String g = glob == null || glob.isBlank() ? "*" : glob;
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return java.util.regex.Pattern.compile(regex.toString());
    }
}