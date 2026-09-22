package com.wisesoft.wenqu.agents.backends.sandbox;

import com.alibaba.cloud.ai.graph.agent.extension.file.EditResult;
import com.alibaba.cloud.ai.graph.agent.extension.file.FileInfo;
import com.alibaba.cloud.ai.graph.agent.extension.file.FilesystemBackend;
import com.alibaba.cloud.ai.graph.agent.extension.file.GrepMatch;
import com.alibaba.cloud.ai.graph.agent.extension.file.WriteResult;
import com.wisesoft.wenqu.agents.BackendPaths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ProvisionerSandboxBackend} → 引擎面 {@link FilesystemBackend} 适配器。
 *
 * <p>对应参考实现 {@code composite.py} 里
 * {@code CompositeBackend(default=ProvisionerSandboxBackend(...), routes={}, artifacts_root=...)}
 * 的两项实际能力：<b>default backend</b> 与 <b>artifacts_root</b>。参考实现里
 * {@code routes={}}（空路由表），故 CompositeBackend 的分发语义在本调用点为空操作，无需照搬；
 * 引擎面需要 {@link FilesystemBackend} 这一「字符串 in / 字符串 out」协议（大工具结果落盘用），
 * 而 {@link ProvisionerSandboxBackend} 返回的是结构化 DTO，故用本适配器投影。
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>框架落盘目录硬编码</b>：{@code LargeResultEvictionInterceptor.LARGE_RESULTS_DIR}
 *       为 {@code System.getProperty("user.dir") + "/large_tool_results/"}，而本工程契约是
 *       {@code <artifacts_root>/large_tool_results/}（{@link BackendPaths#workdirRuntimePaths}）。
 *       该常量与 {@code processLargeResult} 均为私有、不可覆写，故本适配器在 I/O 边界做
 *       <b>路径重映射</b>（{@link #remapFrameworkPath}）：凡落点位于框架硬编码目录者，改写为
 *       {@code <artifacts_root>/large_tool_results/}，使写入真正落在沙盒授权根内。
 *       未重映射则写入必被沙盒拒绝（授权根外），退化成「有裁剪消息、无落盘」的静默降级。
 *       注意：框架回给模型的提示文案里仍是框架路径，属该能力差异的残留（文案不可覆写）。</li>
 *   <li><b>ls / glob 无错误通道</b>：{@link FilesystemBackend#lsInfo} / {@link #globInfo} 只返回列表，
 *       无 error 位。{@link ProvisionerSandboxBackend} 的 {@code ls} / {@code glob} 失败时返回
 *       error，本适配器只能转成空列表并 <b>warn 日志</b>（不静默丢弃诊断信息）。</li>
 *   <li><b>read 基64 载荷不可表达</b>：框架协议为 {@code String}，沙盒侧图片等二进制以
 *       {@code encoding="base64"} 返回。本适配器对 base64 载荷返回其 base64 文本（不做解码丢失），
 *       文本载荷返回原文；<b>面向模型的文件读取工具不走本适配器</b>（见
 *       {@code FilesystemMiddleware} 的能力差异说明），故无功能损失。</li>
 *   <li><b>filesUpdate</b>：框架 DTO 的 {@code filesUpdate} 为 state backend（把文件存进 agent state）
 *       准备；外部存储按接口注释置 {@code null}。</li>
 * </ol>
 */
public class SandboxFilesystemBackendAdapter implements FilesystemBackend {

    private static final Logger log = LoggerFactory.getLogger(SandboxFilesystemBackendAdapter.class);

    /** 大工具结果目录名（与 {@link BackendPaths#LARGE_TOOL_RESULTS_DIR_NAME} 同源）。 */
    private static final String LARGE_RESULTS_MARKER = "/" + BackendPaths.LARGE_TOOL_RESULTS_DIR_NAME + "/";

    private final ProvisionerSandboxBackend backend;
    /** 大工具结果落盘根：{@code <artifacts_root>/large_tool_results/}（参考实现从 artifacts_root 派生）。 */
    private final String largeResultsRoot;

    public SandboxFilesystemBackendAdapter(ProvisionerSandboxBackend backend, String artifactsRoot) {
        this.backend = backend;
        String root = artifactsRoot == null ? "" : artifactsRoot.replaceAll("/+$", "");
        this.largeResultsRoot = root + LARGE_RESULTS_MARKER;
    }

    /** 大工具结果落盘根（供中间件/诊断引用）。 */
    public String getLargeResultsRoot() {
        return largeResultsRoot;
    }

    @Override
    public String read(String filePath, int offset, int limit) {
        ProvisionerSandboxBackend.ReadResult result = backend.read(filePath, offset, limit);
        if (result.error != null) {
            return result.error;
        }
        if (result.fileData == null) {
            return result.noLinesRequested ? "" : "";
        }
        return result.fileData.content == null ? "" : result.fileData.content;
    }

    /**
     * 读取文件并保留完整结果（窗口元数据 / 编码 / 错误），供 {@code read_file} 工具按
     * deepagents 的状态头格式组装输出（{@code read} 只返回纯正文，丢失了元数据）。
     */
    public ProvisionerSandboxBackend.ReadResult readResult(String filePath, int offset, Integer limit) {
        return backend.read(filePath, offset, limit);
    }

    @Override
    public WriteResult write(String filePath, String content) {
        String target = remapFrameworkPath(filePath);
        ProvisionerSandboxBackend.WriteResult result = backend.write(target, content);
        return new WriteResult(result.path == null ? target : result.path, result.error, null);
    }

    @Override
    public EditResult edit(String filePath, String oldString, String newString, boolean replaceAll) {
        String target = remapFrameworkPath(filePath);
        ProvisionerSandboxBackend.EditResult result = backend.edit(target, oldString, newString, replaceAll);
        return new EditResult(
                result.path == null ? target : result.path,
                result.occurrences == null ? 0 : result.occurrences,
                result.error,
                null);
    }

    @Override
    public List<FileInfo> lsInfo(String path) {
        SandboxFsBackend.LsResult result = backend.ls(path);
        if (result.hasError()) {
            log.warn("Sandbox ls failed for {}: {}", path, result.error);
            return List.of();
        }
        if (result.entries == null) {
            return List.of();
        }
        List<FileInfo> infos = new ArrayList<>(result.entries.size());
        for (SandboxFsBackend.FsEntry entry : result.entries) {
            infos.add(new FileInfo(entry.path, entry.isDir, entry.size, null));
        }
        return infos;
    }

    @Override
    public List<FileInfo> globInfo(String pattern, String path) {
        ProvisionerSandboxBackend.GlobResult result = backend.glob(pattern, path);
        if (result.error != null) {
            log.warn("Sandbox glob failed for {} under {}: {}", pattern, path, result.error);
            return List.of();
        }
        if (result.matches == null) {
            return List.of();
        }
        List<FileInfo> infos = new ArrayList<>(result.matches.size());
        for (ProvisionerSandboxBackend.GlobMatch match : result.matches) {
            infos.add(new FileInfo(match.path, null, null, null));
        }
        return infos;
    }

    @Override
    public Object grepRaw(String pattern, String path, String glob) {
        ProvisionerSandboxBackend.GrepResult result = backend.grep(pattern, path, glob, null);
        if (result.error != null) {
            return result.error;
        }
        if (result.matches == null) {
            return List.of();
        }
        List<GrepMatch> matches = new ArrayList<>(result.matches.size());
        for (ProvisionerSandboxBackend.GrepMatch match : result.matches) {
            matches.add(new GrepMatch(match.path, match.line, match.text));
        }
        return matches;
    }

    /**
     * 把框架硬编码的大结果落盘路径改写为本工程契约路径。
     *
     * <p>识别 {@code <user.dir>/large_tool_results/<name>} 与 {@code /large_tool_results/<name>}
     * 两种形态，改写为 {@code <artifacts_root>/large_tool_results/<name>}；其余路径原样返回。
     */
    String remapFrameworkPath(String path) {
        if (path == null) {
            return null;
        }
        int index = path.lastIndexOf(LARGE_RESULTS_MARKER);
        if (index < 0) {
            return path;
        }
        String prefix = path.substring(0, index);
        if (!prefix.isEmpty() && !prefix.equals(System.getProperty("user.dir"))) {
            return path;
        }
        return largeResultsRoot + path.substring(index + LARGE_RESULTS_MARKER.length());
    }
}
