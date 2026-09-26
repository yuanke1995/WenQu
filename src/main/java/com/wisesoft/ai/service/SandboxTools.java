package com.wisesoft.ai.service;

import com.wisesoft.ai.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.ai.sandbox.SandboxFsBackend;
import com.wisesoft.ai.util.RequestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 沙盒工具（Function Calling）：让模型在隔离容器里真正执行命令、读写文件。
 * <p>
 * 与问渠新栈的差别要说清楚：新栈把沙盒接在 deepagents 的「文件系统中间件」上（{@code FilesystemMiddleware}
 * 提供 read_file/write_file/edit_file/ls/glob/grep/execute 一族工具，并配合大结果裁剪），
 * 而本工程没有该中间件，故这里**按本工程的 {@code @Tool} 机制逐个显式注册**，
 * 只接当前真正用得上的五个：{@code execute} / {@code read_file} / {@code write_file} / {@code edit_file} / {@code ls}。
 * 工具名与新栈保持一致（{@code execute} 等），便于将来两边共用提示词与技能文档。
 * <p>
 * 归属与 scope 的来源：会话 id 走 {@code ToolContext}（与产物交付工具同一约定），uid 走
 * {@code RequestUser}——问答链路在流水线线程里已按本轮用户装载身份（见 {@code RagService.loadIdentity}），
 * 所以这里拿到的是**真实用户**而不是 anonymous。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class SandboxTools {

    /** 单次命令输出超过该长度时，返回给模型的内容会被截断（避免把上下文撑爆）。 */
    private static final int TOOL_OUTPUT_CHARS = 20_000;

    private final SandboxService sandboxService;

    public SandboxTools(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Tool(name = "execute", description = "在隔离沙盒（Linux 容器）中执行 shell 命令，返回标准输出与退出码。"
            + "适合：运行脚本、计算/处理数据、生成文件、安装依赖、查看系统信息。"
            + "沙盒是持久化的：同一会话内创建的文件在后续调用中仍然存在。"
            + "命令在沙盒内的工作目录为用户数据根目录下，请把要保留的文件写在那里。")
    public String execute(
            @ToolParam(description = "要执行的 shell 命令，如 python3 -c \"print(1+1)\" 或 ls -la") String command,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) return NO_CONTEXT;
        if (command == null || command.isBlank()) {
            return "命令不能为空";
        }
        ProvisionerSandboxBackend.ExecuteResponse result = backend.execute(command);
        StringBuilder sb = new StringBuilder();
        if (result.exitCode != null && result.exitCode != 0) {
            sb.append("退出码 ").append(result.exitCode).append('\n');
        }
        String output = result.output == null ? "" : result.output;
        sb.append(output.isEmpty() ? "(无输出)" : output);
        if (result.truncated) {
            sb.append("\n…（输出过长已截断）");
        }
        return clip(sb.toString());
    }

    @Tool(name = "read_file", description = "读取沙盒内某个文本文件的内容（支持 offset/limit 按行读取）。"
            + "只支持 UTF-8 文本与常见图片；PDF/Office 文档与二进制文件不支持。")
    public String read_file(
            @ToolParam(description = "沙盒内的绝对路径，如 /home/gem/user-data/report.md") String path,
            @ToolParam(description = "起始行号（从 1 开始，留空从第 1 行读）", required = false) Integer offset,
            @ToolParam(description = "最多读取的行数（留空默认 2000 行）", required = false) Integer limit,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) return NO_CONTEXT;
        int start = offset == null || offset <= 0 ? 0 : offset - 1;
        ProvisionerSandboxBackend.ReadResult result = backend.read(path, start, limit);
        if (result.error != null) {
            return "读取失败：" + result.error;
        }
        if (result.noLinesRequested) {
            return "（limit ≤ 0，未读取任何行）";
        }
        String content = result.fileData == null ? "" : result.fileData.content;
        StringBuilder sb = new StringBuilder(content == null ? "" : content);
        // 「是否还有更多」用本次返回行数与请求行数比较：read() 的 nextOffset/endLine 在整文件读完时也有值，
        // 单看它会把"读完了"误报成"还有更多"。
        int returned = result.endLine - result.startLine + 1;
        Integer requested = limit == null ? 2000 : limit;
        if (result.endLine > 0 && returned >= requested) {
            sb.append("\n…（本次读到第 ").append(result.endLine)
              .append(" 行，可能还有更多；可继续用 offset=").append(result.endLine + 1).append(" 读取）");
        }
        return clip(sb.toString());
    }

    @Tool(name = "write_file", description = "在沙盒内**创建**一个新文件并写入内容（父目录自动创建）。"
            + "注意：文件已存在时会失败——修改已有文件请改用 edit_file。只能在用户数据根目录下写。")
    public String write_file(
            @ToolParam(description = "沙盒内的绝对路径，如 /home/gem/user-data/calc.py") String path,
            @ToolParam(description = "文件内容（纯文本）") String content,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) return NO_CONTEXT;
        ProvisionerSandboxBackend.WriteResult result = backend.write(path, content == null ? "" : content);
        if (result.error != null) {
            return "写入失败：" + result.error;
        }
        int bytes = content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return "已写入 " + result.path + "（" + bytes + " 字节）";
    }

    @Tool(name = "edit_file", description = "用精确字符串替换修改沙盒内的已有文件（old_string 必须与文件内容完全一致且唯一，"
            + "除非 replace_all）。适合小改动；大面积重写不如删掉重建。")
    public String edit_file(
            @ToolParam(description = "沙盒内的绝对路径") String path,
            @ToolParam(description = "要被替换的原文（必须逐字匹配）") String oldString,
            @ToolParam(description = "替换后的新文本") String newString,
            @ToolParam(description = "是否替换全部匹配（默认只替换第一处）", required = false) Boolean replaceAll,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) return NO_CONTEXT;
        if (oldString == null || oldString.isEmpty()) {
            return "old_string 不能为空";
        }
        ProvisionerSandboxBackend.EditResult result =
                backend.edit(path, oldString, newString == null ? "" : newString, Boolean.TRUE.equals(replaceAll));
        if (result.error != null) {
            return "编辑失败：" + result.error;
        }
        return "已编辑 " + result.path + "（替换 " + result.occurrences + " 处）";
    }

    @Tool(name = "ls", description = "列出沙盒内某个目录的条目（路径、是否目录、大小）。用于确认文件是否生成、"
            + "查看工作目录里有什么。")
    public String ls(
            @ToolParam(description = "沙盒内的绝对路径（目录或文件）") String dir,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) return NO_CONTEXT;
        SandboxFsBackend.LsResult result = backend.ls(dir);
        if (result.hasError()) {
            return "列举失败：" + result.error;
        }
        if (result.entries == null || result.entries.isEmpty()) {
            return "（空目录）";
        }
        StringBuilder sb = new StringBuilder();
        for (SandboxFsBackend.FsEntry entry : result.entries) {
            sb.append(entry.isDir ? "d " : "- ");
            sb.append(entry.isDir ? "" : (entry.size == null ? "" : entry.size + "B")).append('\t');
            sb.append(entry.path).append('\n');
        }
        return clip(sb.toString());
    }

    // ==================== 内部 ====================

    /** 沙盒不可用时的统一文案：把「没配好」与「没会话上下文」分开说，否则报错无从下手。 */
    private static final String NO_CONTEXT = "沙盒不可用：缺少会话或用户上下文，请刷新页面后重试";

    private ProvisionerSandboxBackend backend(ToolContext toolContext) {
        String sessionId = null;
        if (toolContext != null) {
            Object v = toolContext.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
            if (v != null) sessionId = String.valueOf(v);
        }
        String uid = RequestUser.uid();
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) {
            return null;
        }
        return sandboxService.backend(sessionId, uid);
    }

    /** 兜底截断：backend 已按字节限制过命令输出，这里再按字符限制一次（读文件/列目录没走那条路）。 */
    private static String clip(String text) {
        if (text == null) return "";
        if (text.length() <= TOOL_OUTPUT_CHARS) return text;
        return text.substring(0, TOOL_OUTPUT_CHARS) + "\n…（内容过长已截断）";
    }
}
