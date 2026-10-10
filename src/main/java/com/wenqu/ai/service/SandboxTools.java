package com.wenqu.ai.service;

import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import com.wenqu.ai.sandbox.SandboxFsBackend;
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
 * 接当前用得上的六个：{@code execute} / {@code read_file} / {@code write_file} / {@code edit_file} /
 * {@code ls} / {@code deliver_artifact}（沙盒文件 → 我的产物）。工具名与新栈保持一致
 * （{@code deliver_artifact} 为本工程新增），便于将来两边共用提示词与技能文档。
 * <p>
 * 归属与 scope 的来源：会话 id 与用户 uid 都走 {@code ToolContext}（与产物交付工具同一约定），
 * 由 RagService 发起流式请求时注入。注意：工具回调跑在 Spring AI 响应式 I/O 线程上，
 * 而 {@code RequestUser} 是流水线线程装载的 ThreadLocal，跨线程读不到（回落 anonymous），
 * 所以这里必须读 toolContext 透传的 uid，而不是 RequestUser.uid()——否则沙盒会被建到
 * {@code shared/anonymous/workspace}（已踩坑）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class SandboxTools {

    /** 单次命令输出超过该长度时，返回给模型的内容会被截断（避免把上下文撑爆）。 */
    private static final int TOOL_OUTPUT_CHARS = 20_000;

    /** toolContext 里「工具输出增量回调」的键（Consumer&lt;String&gt;，RagService 注入 → SSE tool_output 实时下发）。 */
    public static final String CTX_OUTPUT_SINK = "wq_tool_output_sink";
    /**
     * toolContext 里「基础设施故障上报」的键（Consumer&lt;String&gt;，RagService 注入）。
     * <p>沙盒不可用（provisioner 没起 / 连不上 / 鉴权被拒）时，工具**不抛异常**——抛出去会掐断
     * 整轮流、让已生成的正文与全部工具记录一起落空。工具照常把失败文本返回给模型让它自行调整，
     * 同时经此回调把「这次失败是基础设施层、不是命令本身跑失败」告诉 RagService，
     * 让工具记录落成 status=error（红色失败）而不是 status=done（绿色对勾）。
     * <p>不这么做的后果：命令退出码非 0（业务失败）与沙盒压根没通（基础设施故障）都是
     * "exitCode=1 +一段文本"，记录一律显示成功，排查时完全看不出沙盒没通。
     */
    public static final String CTX_INFRA_ERROR_SINK = "wq_tool_infra_error_sink";

    /**
     * 输出被截断时把<b>整份原文</b>外溢留存的能力：(工具名, 原文) → spillId，由问答侧经 ToolContext 注入。
     * <p>走上下文而不是全局单例：留存要按「哪一轮的哪个工具」归属，工具执行在弹性线程上并发跑，
     * setter 式单例会串号（与 P1 要修掉的那对 setSourceRegistrar/setSink 同病灶）。
     */
    public static final String CTX_SPILL = "wq_tool_spill";
    /** 留存成功后的上报（工具名, spillId）：问答侧据此把「查看完整输出」挂到对应那张工具卡上 */
    public static final String CTX_SPILL_REPORT = "wq_tool_spill_report";
    /** 支持流式输出的工具名（RagService 按 name 判定是否注入输出回调）。 */
    public static final String STREAMING_TOOL_NAME = "execute";
    /**
     * 本组件注册的全部工具名（RagService 判定「是否沙盒工具」用，决定要不要注入
     * CTX_INFRA_ERROR_SINK）。刻意列全而非用前缀匹配：工具名是 @Tool 显式声明的字符串，
     * 改名时编译期就会暴露这里的漏项风险（新增工具忘记加入 → 基础设施故障仍显示为成功），
     * 而前缀匹配会在重构时静默失配。
     */
    public static final java.util.Set<String> TOOL_NAMES = java.util.Set.of(
            "execute", "read_file", "write_file", "edit_file", "ls", "deliver_artifact");

    private final SandboxService sandboxService;
    private final ArtifactService artifactService;

    public SandboxTools(SandboxService sandboxService, ArtifactService artifactService) {
        this.sandboxService = sandboxService;
        this.artifactService = artifactService;
    }

    @Tool(name = "execute", description = "在隔离沙盒（Linux 容器）中执行 shell 命令，返回标准输出与退出码。"
            + "适合：运行脚本、计算/处理数据、生成文件、安装依赖、查看系统信息。"
            + "沙盒是持久化的：同一会话内创建的文件在后续调用中仍然存在。"
            + "命令在沙盒内的工作目录为用户数据根目录下，请把要保留的文件写在那里。")
    public String execute(
            @ToolParam(description = "要执行的 shell 命令，如 python3 -c \"print(1+1)\" 或 ls -la") String command,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        if (command == null || command.isBlank()) {
            return "命令不能为空";
        }
        // 有输出回调（问答流式链路注入）→ 走流式执行：命令后台脱离 + 按行轮询，增量经回调转 SSE
        // tool_output 实时下发（前端「看着它跑」）；无回调（无流上下文的调用方）保持原同步执行。
        java.util.function.Consumer<String> sink = outputSink(toolContext);
        ProvisionerSandboxBackend.ExecuteResponse result = sink != null
                ? backend.executeStreaming(command, null, sink)
                : backend.execute(command);
        // 基础设施故障（provisioner 不可达/ 鉴权失败）：文本照常回给模型，但额外上报让记录落成
        // status=error（见 CTX_INFRA_ERROR_SINK 注释）。注意与「命令退出码非 0」区分开：
        // 后者是正常业务结果，改命令即可，不该显示成失败。
        if (result.infraError) {
            reportInfra(toolContext, result.output);
        }
        StringBuilder sb = new StringBuilder();
        if (result.exitCode != null && result.exitCode != 0) {
            sb.append("退出码 ").append(result.exitCode).append('\n');
        }
        String output = result.output == null ? "" : result.output;
        sb.append(output.isEmpty() ? "(无输出)" : output);
        if (result.truncated) {
            sb.append("\n…（输出过长已截断）");
        }
        return clip(sb.toString(), "execute", toolContext);
    }

    @Tool(name = "read_file", description = "读取沙盒内某个文本文件的内容（支持 offset/limit 按行读取）。"
            + "只支持 UTF-8 文本与常见图片；PDF/Office 文档与二进制文件不支持。")
    public String read_file(
            @ToolParam(description = "沙盒内的绝对路径，如 /home/gem/user-data/report.md") String path,
            @ToolParam(description = "起始行号（从 1 开始，留空从第 1 行读）", required = false) Integer offset,
            @ToolParam(description = "最多读取的行数（留空默认 2000 行）", required = false) Integer limit,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        int start = offset == null || offset <= 0 ? 0 : offset - 1;
        ProvisionerSandboxBackend.ReadResult result = backend.read(path, start, limit);
        if (result.error != null) {
            return reportFsError(toolContext, "读取失败：" + result.error);
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
        return clip(sb.toString(), "read_file", toolContext);
    }

    @Tool(name = "write_file", description = "在沙盒内创建新文件或整体替换已有文件（父目录自动创建）。"
            + "只在新建或整文件重写时使用；局部修改优先用 edit_file，更省且不会误伤其余内容。"
            + "只能在用户数据根目录下写。")
    public String write_file(
            @ToolParam(description = "沙盒内的绝对路径，如 /home/gem/user-data/calc.py") String path,
            @ToolParam(description = "文件内容（纯文本）") String content,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        ProvisionerSandboxBackend.WriteResult result = backend.write(path, content == null ? "" : content);
        if (result.error != null) {
            return reportFsError(toolContext, "写入失败：" + result.error);
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
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        if (oldString == null || oldString.isEmpty()) {
            return "old_string 不能为空";
        }
        ProvisionerSandboxBackend.EditResult result =
                backend.edit(path, oldString, newString == null ? "" : newString, Boolean.TRUE.equals(replaceAll));
        if (result.error != null) {
            return reportFsError(toolContext, "编辑失败：" + result.error);
        }
        return "已编辑 " + result.path + "（替换 " + result.occurrences + " 处）";
    }

    @Tool(name = "ls", description = "列出沙盒内某个目录的条目（路径、是否目录、大小）。用于确认文件是否生成、"
            + "查看工作目录里有什么。")
    public String ls(
            @ToolParam(description = "沙盒内的绝对路径（目录或文件）") String dir,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        SandboxFsBackend.LsResult result = backend.ls(dir);
        if (result.hasError()) {
            return reportFsError(toolContext, "列举失败：" + result.error);
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
        return clip(sb.toString(), "ls", toolContext);
    }

    /**
     * 沙盒文件 → 我的产物（交付语义，模型显式调用；不做轮末自动扫描——那会把临时文件全灌进产物页）。
     * 路径限定用户数据根（skills 投影只读且非产物，拒绝）；扩展名白名单放宽到沙盒典型输出，
     * 大小上限 1MB（与文本产物一致）。交付后走产物卡片 SSE 实时推送，前端/产物页零改动。
     */
    @Tool(name = "deliver_artifact", description = "把沙盒里生成的文件交付为正式产物（进入用户的「我的产物」，"
            + "并实时推送产物卡片）。当沙盒中产出了用户要保留/下载的文件（脚本、数据表、图表、文档等）时调用；"
            + "仅支持用户数据目录下的文件，单个不超过 1MB。"
            + "硬性约束：文字里向用户承诺「可以交付/一并交付」某个文件，就必须在同一轮对本工具逐个调用、当场交付，"
            + "禁止只口头承诺而不调用；用户索取沙盒内的任何已有文件时直接交付该文件本身，"
            + "不要只交付别的文件或只复述文件内容。")
    public String deliver_artifact(
            @ToolParam(description = "沙盒内文件的绝对路径，如 /home/gem/user-data/sales.csv") String path,
            @ToolParam(description = "产物文件名（含扩展名；留空则沿用沙盒内原文件名）", required = false) String filename,
            @ToolParam(description = "给用户的产物说明（简短，说明这是什么）", required = false) String description,
            ToolContext toolContext) {
        ProvisionerSandboxBackend backend = backend(toolContext);
        if (backend == null) {
            return reportInfra(toolContext, NO_CONTEXT);
        }
        String sessionId = sessionId(toolContext);
        if (sessionId == null || sessionId.isBlank()) {
            return "无法定位当前会话，产物交付失败";
        }
        String p = path == null ? "" : path.strip();
        String root = sandboxService.userDataRoot();
        if (!p.startsWith(root + "/") && !p.equals(root)) {
            return "只能交付用户数据目录（" + root + "）下的文件";
        }
        java.util.List<SandboxFsBackend.DownloadResult> results = backend.downloadFiles(java.util.List.of(p));
        SandboxFsBackend.DownloadResult r = results == null || results.isEmpty() ? null : results.get(0);
        if (r == null || r.hasError() || r.content == null || r.content.length == 0) {
            return reportFsError(toolContext, "读取沙盒文件失败：" + (r == null ? "文件不存在" : r.error));
        }
        String name = filename == null || filename.isBlank() ? p.substring(p.lastIndexOf('/') + 1) : filename;
        try {
            java.util.Map<String, Object> info = artifactService.writeBytes(sessionId, name, r.content, description);
            artifactService.publish(sessionId, "artifact", com.alibaba.fastjson2.JSON.toJSONString(info));
            return "已交付产物：" + info.get("filename") + "（" + r.content.length + " 字节） 访问地址：" + info.get("url");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return reportFsError(toolContext, "产物交付失败：" + e.getMessage());
        }
    }

    /** 从 toolContext 取会话 id（与 {@link #backend(ToolContext)} 同键，供产物归属使用）。 */
    private String sessionId(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object v = toolContext.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
        return v == null ? null : String.valueOf(v);
    }

    /** 取输出增量回调（RagService 按工具名注入；缺省 null = 无流式上下文，走同步执行）。 */
    @SuppressWarnings("unchecked")
    private java.util.function.Consumer<String> outputSink(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object v = toolContext.getContext().get(CTX_OUTPUT_SINK);
        return v instanceof java.util.function.Consumer ? (java.util.function.Consumer<String>) v : null;
    }

    /**
     * 上报失败并原样返回文案给模型（沙盒不可用 / 执行失败共用此出口）。
     * <p>失败文本必须照常回给模型——它据此调整下一步（改命令、改路径，或如实告知用户）。
     * 但必须同时上报，否则这条记录会显示成绿色对勾的「成功」：排查时完全看不出沙盒压根没通。
     */
    private String reportInfra(ToolContext toolContext, String message) {
        java.util.function.Consumer<String> sink = infraErrorSink(toolContext);
        if (sink != null) {
            try {
                sink.accept(message);
            } catch (Exception e) {
                log.debug("[SANDBOX] 工具失败上报失败（不影响工具返回）: {}", e.getMessage());
            }
        }
        return message;
    }

    /**
     * 文件类工具（读/写/编辑/列举/交付）的失败上报：与 {@link #reportInfra} 同机制。
     * <p>这些失败未必都是基础设施问题（路径非法、文件已存在同样算失败），此处统一按失败记——
     * 「文件没写进去」在工具记录里就该显示为失败，而不是一条看起来成功的普通输出。
     */
    private String reportFsError(ToolContext toolContext, String message) {
        return reportInfra(toolContext, message);
    }

    /** 取基础设施故障上报回调（RagService 注入；缺省 null = 无问答流上下文，只返回文本不落状态）。 */
    @SuppressWarnings("unchecked")
    private java.util.function.Consumer<String> infraErrorSink(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object v = toolContext.getContext().get(CTX_INFRA_ERROR_SINK);
        return v instanceof java.util.function.Consumer ? (java.util.function.Consumer<String>) v : null;
    }

    // ==================== 内部 ====================

    /** 沙盒不可用时的统一文案：把「没配好」与「没会话上下文」分开说，否则报错无从下手。 */
    private static final String NO_CONTEXT = "沙盒不可用：缺少会话或用户上下文，请刷新页面后重试";

    private ProvisionerSandboxBackend backend(ToolContext toolContext) {
        String sessionId = null;
        String uid = null;
        if (toolContext != null) {
            Object s = toolContext.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
            if (s != null) sessionId = String.valueOf(s);
            // 用户归属必须走 toolContext 透传（CTX_USER_ID），不能读 RequestUser.uid()：
            // 工具回调执行在 Spring AI 响应式 I/O 线程，RequestUser 的 ThreadLocal 在该线程上未装载、回落 anonymous。
            Object u = toolContext.getContext().get(PresentArtifactTool.CTX_USER_ID);
            if (u != null) uid = String.valueOf(u);
        }
        if (uid == null || uid.isBlank()) {
            // 跨线程身份透传缺失（理论上 RagService 必然注入 CTX_USER_ID）——显式报警，绝不静默回落 anonymous 建沙盒。
            log.warn("[FAIL-LOUD] 沙盒工具缺少 toolContext 用户归属，拒绝建沙盒（session={}）", sessionId);
            return null;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return sandboxService.backend(sessionId, uid);
    }

    /**
     * 兜底截断：backend 已按字节限制过命令输出，这里再按字符限制一次（读文件/列目录没走那条路）。
     * <p>被截掉那一段不再静默丢掉：问答侧注入了留存能力时，把<b>整份原文</b>交出去落盘并上报 spillId，
     * 用户拿到的是「预览 + 可查看完整输出」；没有留存能力（非问答链路、或写盘失败）就写明丢了多少钱字，
     * 而不是留一句「已截断」让人以为那就是全部。
     */
    private static String clip(String text, String toolName, ToolContext toolContext) {
        if (text == null) return "";
        if (text.length() <= TOOL_OUTPUT_CHARS) return text;
        java.util.function.BiFunction<String, String, String> spill = ctxFn(toolContext, CTX_SPILL);
        String spillId = spill == null ? null : safeSpill(spill, toolName, text);
        if (spillId != null) {
            java.util.function.BiConsumer<String, String> report = ctxFn(toolContext, CTX_SPILL_REPORT);
            if (report != null) {
                try {
                    report.accept(toolName, spillId);
                } catch (Exception ignored) {
                    // 上报失败不影响回给模型的文本（卡片少一个续读入口，原文仍在盘上）
                }
            }
            return text.substring(0, TOOL_OUTPUT_CHARS)
                    + "\n…（输出过长，只展示前 " + TOOL_OUTPUT_CHARS + " 字；完整 " + text.length()
                    + " 字已留存，可从本轮工具卡片「查看完整输出」读回）";
        }
        return text.substring(0, TOOL_OUTPUT_CHARS)
                + "\n…（内容过长已截断，另有 " + (text.length() - TOOL_OUTPUT_CHARS) + " 字未能留存）";
    }

    /** 取上下文里注入的能力（问答侧经 ToolContext 传的函数）；不在上下文里就返回 null 走降级路径 */
    @SuppressWarnings("unchecked")
    private static <T> T ctxFn(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) return null;
        Object v = toolContext.getContext().get(key);
        return v == null ? null : (T) v;
    }

    /** 留存是旁路：它抛任何异常都不该让这次工具调用跟着失败 */
    private static String safeSpill(java.util.function.BiFunction<String, String, String> spill,
                                    String toolName, String text) {
        try {
            return spill.apply(toolName, text);
        } catch (Exception e) {
            return null;
        }
    }
}
