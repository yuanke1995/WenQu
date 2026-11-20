package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Office 文档工具：格式转换 / 公式重算 / 渲染预览。
 *
 * <p>文件读写走<b>当前会话的工作区或沙盒文件系统</b>（{@link SandboxService#frameworkFilesystem}），
 * 因此本机执行器工作区、直连工作区与云端沙盒三种后端是同一套语义；服务端不另存一份原件。
 * 处理内核在 {@link OfficeDocumentService}（纯字节进字节出，可单测）。
 *
 * <p>产物落地：输出文件写到输入文件同目录；若体积在产物上限内，同时登记为可下载产物并推卡片，
 * 方便用户直接点开。输出路径回给模型，便于它继续用 deliver_artifact 或读取。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class OfficeTools {

    static final long ARTIFACT_MAX_BYTES = 1024 * 1024;

    private final SandboxService sandboxService;
    private final OfficeDocumentService office;
    private final ArtifactService artifactService;

    public OfficeTools(SandboxService sandboxService, OfficeDocumentService office, ArtifactService artifactService) {
        this.sandboxService = sandboxService;
        this.office = office;
        this.artifactService = artifactService;
    }

    @Tool(name = "officeConvert",
            description = "把工作区/沙盒里的文档转成另一种格式：docx/xlsx/pptx/pdf/文本类 → txt/csv/html/pdf。"
                    + "只处理文件，不返回全文；需要看内容时转成 txt 后自行读取，或直接 officeRender 出图给用户看。"
                    + "转 PDF 需要服务器安装 LibreOffice。")
    public String officeConvert(
            @ToolParam(description = "工作区/沙盒内的文件路径（相对路径，如 资料/季度报告.docx）") String filePath,
            @ToolParam(description = "目标格式：txt / csv / html / pdf") String targetFormat,
            @ToolParam(description = "输出文件路径；省略=与输入同目录，文件名追加 -转换目标", required = false) String outputPath,
            ToolContext ctx) {
        String uid = uid(ctx), sid = sid(ctx);
        if (uid == null || sid == null) return "无法定位当前会话，文档转换失败";
        String srcExt = OfficeDocumentService.extOf(filePath);
        if (!OfficeDocumentService.supported(srcExt)) {
            return "不支持的文件类型：" + (srcExt.isEmpty() ? "（无扩展名）" : srcExt);
        }
        String target = norm(targetFormat);
        try {
            AbstractFilesystem fs = fs(sid, uid);
            if (fs == null) return "当前会话没有可用的工作区/沙盒文件系统，无法读取文件。";
            byte[] input = read(fs, ctx, filePath);
            byte[] out = office.convert(input, srcExt, target);
            String outPath = outputPath == null || outputPath.isBlank()
                    ? sibling(filePath, "-" + target, "." + target) : outputPath;
            write(fs, ctx, outPath, out);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ok", true);
            res.put("input", filePath);
            res.put("output", outPath);
            res.put("format", target);
            res.put("bytes", out.length);
            deliver(sid, outPath, out, "文档转换结果：" + outPath);
            return JSON.toJSONString(res);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            log.warn("[OFFICE] 转换失败 uid={} file={}: {}", uid, filePath, e.toString());
            return "文档转换失败：" + e.getMessage();
        }
    }

    @Tool(name = "officeRecalculate",
            description = "重算 Excel（xlsx）里的公式并把结果写回文件，返回重算的公式格数与失败格。"
                    + "用于「打开发现数字是旧的/公式没算」的场景；只支持 xlsx。")
    public String officeRecalculate(
            @ToolParam(description = "工作区/沙盒内的 .xlsx 文件路径") String filePath,
            @ToolParam(description = "输出文件路径；省略=覆盖原文件同目录的 -已重算 副本", required = false) String outputPath,
            ToolContext ctx) {
        String uid = uid(ctx), sid = sid(ctx);
        if (uid == null || sid == null) return "无法定位当前会话，公式重算失败";
        String ext = OfficeDocumentService.extOf(filePath);
        if (!"xlsx".equals(ext)) return "公式重算只支持 .xlsx（当前：" + (ext.isEmpty() ? "无扩展名" : ext) + "）";
        try {
            AbstractFilesystem fs = fs(sid, uid);
            if (fs == null) return "当前会话没有可用的工作区/沙盒文件系统，无法读取文件。";
            byte[] input = read(fs, ctx, filePath);
            OfficeDocumentService.RecalcResult r = office.recalculateXlsx(input);
            String outPath = outputPath == null || outputPath.isBlank()
                    ? sibling(filePath, "-已重算", ".xlsx") : outputPath;
            write(fs, ctx, outPath, r.bytes());
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ok", true);
            res.put("input", filePath);
            res.put("output", outPath);
            res.put("formulaCells", r.formulaCells());
            res.put("errors", r.errors());
            res.put("failedCells", r.failedCells());
            res.put("bytes", r.bytes().length);
            deliver(sid, outPath, r.bytes(), "公式重算结果：" + outPath);
            return JSON.toJSONString(res);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            log.warn("[OFFICE] 重算失败 uid={} file={}: {}", uid, filePath, e.toString());
            return "公式重算失败：" + e.getMessage();
        }
    }

    @Tool(name = "officeRender",
            description = "把工作区/沙盒里的文档渲染成 PDF 或 PNG 图片（每页一张），用于给用户看版面/图表/PPT。"
                    + "docx/xlsx/pptx 渲染需要服务器安装 LibreOffice；pdf 直接栅格化。png 默认只渲染第 1 页，"
                    + "可用 pages 指定（最多 20 页）。")
    public String officeRender(
            @ToolParam(description = "工作区/沙盒内的文件路径") String filePath,
            @ToolParam(description = "输出格式：png（默认）或 pdf", required = false) String format,
            @ToolParam(description = "要渲染的页码，逗号分隔（1-based）；省略=第 1 页（pdf 输出时为全部）", required = false) String pages,
            @ToolParam(description = "PNG 清晰度 DPI（72~300，默认 144）", required = false) Integer dpi,
            @ToolParam(description = "输出文件路径前缀；省略=与输入同目录同名", required = false) String outputPath,
            ToolContext ctx) {
        String uid = uid(ctx), sid = sid(ctx);
        if (uid == null || sid == null) return "无法定位当前会话，渲染失败";
        String srcExt = OfficeDocumentService.extOf(filePath);
        if (!OfficeDocumentService.supported(srcExt)) {
            return "不支持的文件类型：" + (srcExt.isEmpty() ? "（无扩展名）" : srcExt);
        }
        String fmt = norm(format) .isEmpty() ? "png" : norm(format);
        try {
            AbstractFilesystem fs = fs(sid, uid);
            if (fs == null) return "当前会话没有可用的工作区/沙盒文件系统，无法读取文件。";
            byte[] input = read(fs, ctx, filePath);
            List<Integer> pageList = parsePages(pages);
            OfficeDocumentService.RenderResult r = office.render(input, srcExt, fmt, pageList, dpi);
            List<String> outputs = new ArrayList<>();
            if ("pdf".equals(fmt)) {
                String outPath = outputPath == null || outputPath.isBlank()
                        ? sibling(filePath, "-渲染", ".pdf") : ensureExt(outputPath, ".pdf");
                write(fs, ctx, outPath, r.pages().get(0));
                outputs.add(outPath);
                deliver(sid, outPath, r.pages().get(0), "文档渲染结果：" + outPath);
            } else {
                String base = outputPath == null || outputPath.isBlank()
                        ? stripExt(filePath) : stripKnownExt(outputPath);
                int pageNo = 0;
                for (byte[] png : r.pages()) {
                    pageNo++;
                    String outPath = r.pages().size() == 1
                            ? base + ".png" : base + "-p" + pageNo + ".png";
                    write(fs, ctx, outPath, png);
                    outputs.add(outPath);
                    deliver(sid, outPath, png, "文档渲染结果：第 " + pageNo + " 页");
                }
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ok", true);
            res.put("input", filePath);
            res.put("format", fmt);
            res.put("outputs", outputs);
            res.put("pages", r.pages().size());
            return JSON.toJSONString(res);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            log.warn("[OFFICE] 渲染失败 uid={} file={}: {}", uid, filePath, e.toString());
            return "文档渲染失败：" + e.getMessage();
        }
    }

    // ==================== 文件与交付 ====================

    private AbstractFilesystem fs(String sid, String uid) {
        try {
            return sandboxService.frameworkFilesystem(sid, uid, false);
        } catch (RuntimeException e) {
            log.info("[OFFICE] 解析文件系统失败 sid={}: {}", sid, e.getMessage());
            return null;
        }
    }

    private static RuntimeContext rc(String sid, String uid) {
        return RuntimeContext.builder().userId(uid).sessionId(sid).build();
    }

    private byte[] read(AbstractFilesystem fs, ToolContext ctx, String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("缺少文件路径");
        RuntimeContext rc = rc(sid(ctx), uid(ctx));
        List<FileDownloadResponse> rs = fs.downloadFiles(rc, List.of(path));
        if (rs == null || rs.isEmpty()) throw new IllegalStateException("读取文件失败：" + path);
        FileDownloadResponse r = rs.get(0);
        if (!r.isSuccess()) throw new IllegalStateException("读取文件失败：" + r.error());
        return r.content();
    }

    private void write(AbstractFilesystem fs, ToolContext ctx, String path, byte[] bytes) {
        RuntimeContext rc = rc(sid(ctx), uid(ctx));
        List<FileUploadResponse> rs = fs.uploadFiles(rc,
                List.of(new AbstractMap.SimpleEntry<>(path, bytes)));
        if (rs == null || rs.isEmpty() || !rs.get(0).isSuccess()) {
            String err = rs == null || rs.isEmpty() ? "无返回" : rs.get(0).error();
            throw new IllegalStateException("写入输出文件失败：" + path + "（" + err + "）");
        }
    }

    /** 产物登记与卡片推送（尽力而为：超过 1MB 或类型不允许时只报路径，不影响工具成功）。 */
    private void deliver(String sid, String path, byte[] bytes, String desc) {
        if (bytes == null || bytes.length > ARTIFACT_MAX_BYTES) return;
        try {
            String filename = fileName(path);
            Map<String, Object> info = artifactService.writeBytes(sid, filename, bytes, desc);
            artifactService.publish(sid, "artifact", JSON.toJSONString(info));
        } catch (Exception e) {
            log.debug("[OFFICE] 产物登记跳过 path={}: {}", path, e.getMessage());
        }
    }

    // ==================== 路径小工具 ====================

    private static String sibling(String path, String suffix, String ext) {
        String dir = dirOf(path);
        String name = baseName(path);
        String stem = stripExt(name);
        return (dir.isEmpty() ? "" : dir + "/") + stem + suffix + ext;
    }

    private static String stripExt(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot <= slash ? path : path.substring(0, dot);
    }

    /** 只剥掉已知文档扩展名，避免把文件名里的点误当扩展名（如 report.v2 的 .v2）。 */
    private static String stripKnownExt(String path) {
        String ext = OfficeDocumentService.extOf(path);
        return OfficeDocumentService.supported(ext) ? stripExt(path) : path;
    }

    private static String ensureExt(String path, String ext) {
        return path.toLowerCase().endsWith(ext) ? path : path + ext;
    }

    private static String dirOf(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String baseName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String fileName(String path) {
        return baseName(path);
    }

    private static List<Integer> parsePages(String pages) {
        if (pages == null || pages.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String part : pages.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(t));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("页码格式不对：" + t + "（应为 1,2,3 这样的数字）");
            }
        }
        return out;
    }

    private static String norm(String v) {
        return v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String uid(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(PresentArtifactTool.CTX_USER_ID);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static String sid(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(PresentArtifactTool.CTX_SESSION_ID);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
