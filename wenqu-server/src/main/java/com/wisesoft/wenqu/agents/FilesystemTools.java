package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.backends.sandbox.SandboxFilesystemBackendAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * 文件系统工具（对应参考实现里由 {@code deepagents.middleware.filesystem.FilesystemMiddleware}
 * 实例化、绑定沙盒 backend 的工具本体；见 {@link FilesystemMiddleware} 类注释「能力差异 2」：
 * 工具供给由本注册处承载，{@link FilesystemMiddleware#getTools()} 的 allowlist 决定谁可见）。
 *
 * <h3>照搬范围（显式标注）</h3>
 * <p>参考实现的文件工具组是 {@code ls / read_file / write_file / edit_file / glob / grep / execute}
 * （{@code _AGENT_FS_TOOLS}，显式排除 delete）。本类<b>目前只搬 {@code read_file}</b>：
 * 两次端到端验收中模型都调用了它（蓝本语义下基础工具始终可见，模型按提示词约定使用），
 * 缺失会导致 {@code No ToolCallback found} 使整个 Run 失败。其余工具属未搬状态，补齐时应
 * 继续放本类并保持 deepagents 的入参 schema 与输出格式。
 *
 * <p>输出格式对齐 {@code deepagents.middleware.filesystem}：错误回给模型（{@code Error: …}，
 * 对应参考实现 ToolNode 的 {@code handle_tool_errors} 语义——不致命、模型可自行纠正）；
 * 文本结果以状态头 {@code @@ lines A-B[ of T][ | next offset N] @@} 开头，其后每行都是文件原文；
 * 空文件返回 {@code EMPTY_CONTENT_WARNING}。默认窗口 {@code offset=0, limit=100}。
 */
public final class FilesystemTools {

    public static final String READ_FILE_TOOL = "read_file";

    /** 对应 deepagents {@code READ_FILE_TOOL_DESCRIPTION}（text-only 变体，逐字）。 */
    public static final String READ_FILE_DESCRIPTION = """
            Reads a file from the filesystem. Assume any path the user provides is valid; reading a missing file returns an error.

            Usage:
            - By default, it reads up to 100 lines starting from the beginning of the file. Use `offset`/`limit` to page through large files instead of reading them whole.
            - A status header, `@@ field | field | ... @@`, sits above the file content, and every line after it is verbatim file content. When content is truncated, there may be an explanation before the header. Never include the header when editing.
            - Speculatively batch multiple `read_file` calls in one response when several files may be useful.
            - An empty file returns a system-reminder warning in place of contents.
            - Large tool results may be offloaded to a file; the tool message gives the path. Read that path here, paging with `offset`/`limit`.
            - Images (`.png`, `.jpg`, etc.), audio, video, and PDFs return multimodal content blocks (https://docs.langchain.com/oss/python/langchain/messages#multimodal).
            - For images and PDFs, pagination via `offset`/`limit` is text-only - supply `file_path` only
            - Always read a file before editing it.""";

    /** 对应 deepagents {@code ReadFileSchema}。 */
    private static final String READ_FILE_SCHEMA = """
            {"type":"object","properties":{"file_path":{"type":"string","description":"Absolute path to the file to read. Must be absolute, not relative."},"offset":{"type":"integer","description":"Line number to start reading from (0-indexed). Use for pagination of large files.","default":0},"limit":{"type":"integer","description":"Maximum number of lines to read. Use for pagination of large files.","default":100}},"required":["file_path"]}""";

    private FilesystemTools() {}

    /** 注册到 {@link ToolkitsRegistry}（category=buildin：基础工具不受 Skill 门控、始终可见）。 */
    public static ReadFileTool register() {
        ReadFileTool tool = new ReadFileTool(null);
        ToolkitsRegistry.register(tool, new ToolkitsRegistry.ToolExtraMetadata(
                "buildin", List.of("文件"), "读取文件", "", ""));
        return tool;
    }

    /** {@code read_file} 工具（每 Run 经 {@code boundTo} 绑定本 Run 的沙盒 backend）。 */
    public static final class ReadFileTool implements ToolkitsRegistry.ToolDefinition, ToolCallback {

        private final SandboxFilesystemBackendAdapter backend;

        public ReadFileTool(SandboxFilesystemBackendAdapter backend) {
            this.backend = backend;
        }

        public ReadFileTool boundTo(SandboxFilesystemBackendAdapter backend) {
            return new ReadFileTool(backend);
        }

        @Override
        public String getName() {
            return READ_FILE_TOOL;
        }

        @Override
        public String getDescription() {
            return READ_FILE_DESCRIPTION;
        }

        @Override
        public Map<String, Object> getArgsSchema() {
            return JSON.parseObject(READ_FILE_SCHEMA);
        }

        public String getCategory() {
            return "buildin";
        }

        public String getDisplayName() {
            return "读取文件";
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(READ_FILE_TOOL)
                    .description(READ_FILE_DESCRIPTION)
                    .inputSchema(READ_FILE_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            if (backend == null) {
                return "Error: read_file 未绑定本次 Run 的沙盒 backend（构图方必须先 boundTo）";
            }
            Map<String, Object> args;
            try {
                args = JSON.parseObject(toolInput == null ? "{}" : toolInput,
                        new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
            } catch (RuntimeException exc) {
                return "Error: invalid tool input: " + exc.getMessage();
            }
            String path = stringArg(args, "file_path");
            if (path == null || path.isBlank()) {
                return "Error: file_path is required";
            }
            int offset = intArg(args, "offset", 0);
            int limit = intArg(args, "limit", 100);

            ProvisionerSandboxBackend.ReadResult result = backend.readResult(path, offset, limit);
            if (result.error != null) {
                return "Error: " + result.error;
            }
            if (result.noLinesRequested) {
                return "System reminder: no lines were read because `limit` was " + limit
                        + ". The file was not inspected and may have contents; retry with `limit` >= 1 to read it.";
            }
            if (result.fileData == null) {
                return "Error: no data returned for '" + path + "'";
            }
            // 能力差异（显式标注）：deepagents 对 base64/图片走多模态内容块；本工程的工具结果
            // 只有文本通道，无法向模型附图。按其文档语义给出明确提示而不是静默失败。
            if ("base64".equals(result.fileData.encoding)) {
                return "[read_file: '" + path + "' 是图片或二进制内容，本工程无法把多模态内容块附给模型；"
                        + "如为 PDF/Office 文档请改用 ocr_parse_file 解析后再读]";
            }
            String content = result.fileData.content == null ? "" : result.fileData.content;
            boolean wholeFile = result.startLine == 1
                    && result.totalLines != null
                    && result.totalLines.intValue() == result.endLine;
            if (wholeFile && content.isBlank()) {
                return "System reminder: File exists but has empty contents";
            }
            List<String> fields = new ArrayList<>();
            String span = "lines " + result.startLine + "-" + result.endLine;
            if (result.totalLines != null) {
                span += " of " + result.totalLines;
            }
            fields.add(span);
            if (result.nextOffset > 0
                    && (result.totalLines == null || result.endLine < result.totalLines)) {
                fields.add("next offset " + result.nextOffset);
            }
            return "@@ " + String.join(" | ", fields) + " @@\n" + content;
        }

        private static String stringArg(Map<String, Object> args, String key) {
            Object value = args.get(key);
            return value == null ? null : String.valueOf(value);
        }

        private static int intArg(Map<String, Object> args, String key, int fallback) {
            Object value = args.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }
    }
}
