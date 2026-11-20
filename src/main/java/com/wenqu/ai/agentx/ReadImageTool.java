package com.wenqu.ai.agentx;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 读图工具 {@code read_image}：模型主动读取<b>工作区/沙盒</b>里的一张图片文件，并把图片本身
 * 作为多模态结果回灌（{@link ImageBlock}），让模型「点开来看」，而不是只拿到一段文字描述。
 *
 * <p>与既有「图片作为输入附件」（{@link com.wenqu.ai.service.UserImageService}，随提问一起送上）
 * 的分工：那条是用户主动发的图；本工具是模型在任务过程中按需打开工作区里的一张图（截图、图表、
 * 设计稿、截下来的报表…）。DSH 对照表里「模型点开工作区里的一张图看」即此。
 *
 * <p>实现要点：
 * <ul>
 *   <li>二进制读取走框架同一个 {@link AbstractFilesystem#downloadFiles}，本机执行器（{@code readBytes}）
 *       与云端沙盒（{@code /v1/file/download}）两条后端共用，不另造一套文件通道；</li>
 *   <li>结果用 {@link ToolResultBlock} 携带 {@link ImageBlock}，非纯文本——这是让模型真看到图的关键；</li>
 *   <li>只读、不审批：读文件不改世界；体积有上限（超大图会撑爆上下文与计费），超限如实拒绝。</li>
 * </ul>
 *
 * <p>只在<b>本轮模型支持读图</b>时由引擎挂载（见 {@code AgentscopeChatEngine#registerReadImageTool}）：
 * 读不了图的模型挂上它只会拿到一张上游不认的图片块。
 *
 * @author yuanke
 */
public final class ReadImageTool extends ToolBase {

    public static final String NAME = "read_image";

    private static final String DESCRIPTION =
            "读取工作区/沙盒里的一个图片文件，并把图片本身返回给你看（PNG/JPEG/GIF/WebP/BMP）。"
                    + "需要看截图、图表、设计稿、扫描件等图片时用它；path 用相对工作区根的路径。"
                    + "读不了的文件类型或过大的图片会明确告诉你原因。";

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "path", Map.of("type", "string",
                            "description", "图片文件路径（相对工作区根；也可用绝对路径，越界由文件系统围栏拦）")),
            "required", List.of("path"));

    private final AbstractFilesystem filesystem;
    private final int maxBytes;

    public ReadImageTool(AbstractFilesystem filesystem, int maxBytes) {
        // 参数顺序与 SpringAiToolAdapter 一致：第 4 个布尔是 readOnly，第 5 个是 concurrencySafe。
        // readOnly=true：读图不改任何东西；concurrencySafe=true：读文件可并发。
        super(NAME, DESCRIPTION, SCHEMA, true, true, false, null, false, false);
        this.filesystem = filesystem;
        this.maxBytes = maxBytes > 0 ? maxBytes : 8 * 1024 * 1024;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        Object pv = input.get("path");
        String path = pv == null ? "" : String.valueOf(pv).trim();
        if (path.isEmpty()) {
            return Mono.just(ToolResultBlock.text("缺少图片路径 path。"));
        }
        return Mono.fromCallable(() -> {
            if (filesystem == null) {
                return ToolResultBlock.text("当前会话没有可用的文件系统，读不了图片：" + path);
            }
            List<FileDownloadResponse> rs = filesystem.downloadFiles(param.getRuntimeContext(), List.of(path));
            if (rs == null || rs.isEmpty()) {
                return ToolResultBlock.text("读取图片失败：文件系统没有返回结果（" + path + "）。");
            }
            FileDownloadResponse r = rs.get(0);
            if (r == null || !r.isSuccess()) {
                String why = r == null ? "未知原因" : (r.error() == null ? "读取失败" : r.error());
                return ToolResultBlock.text("读取图片失败：" + why + "（" + path + "）。");
            }
            byte[] bytes = r.content();
            if (bytes == null || bytes.length == 0) {
                return ToolResultBlock.text("图片为空或不存在：" + path);
            }
            if (bytes.length > maxBytes) {
                return ToolResultBlock.text("图片过大（" + human(bytes.length) + "），超过上限 "
                        + human(maxBytes) + "；请缩小后再读，或改用能接受更大图片的方式。");
            }
            String mime = mimeOf(path, bytes);
            if (mime == null) {
                return ToolResultBlock.text("这不是可识别的图片文件（" + path
                        + "）。支持 PNG/JPEG/GIF/WebP/BMP。");
            }
            String b64 = Base64.getEncoder().encodeToString(bytes);
            List<ContentBlock> blocks = new ArrayList<>(2);
            blocks.add(TextBlock.builder()
                    .text("已读取图片：" + path + "（" + mime + "，" + human(bytes.length) + "）。")
                    .build());
            blocks.add(ImageBlock.builder().source(new Base64Source(mime, b64)).build());
            return ToolResultBlock.of(getName(), getName(), blocks);
        }).onErrorResume(e -> Mono.just(ToolResultBlock.text("读取图片失败：" + msg(e))));
    }

    /**
     * 读图不改变任何状态，直接放行——不覆写会沿用 {@link ToolBase} 默认的 passthrough，
     * 而权限引擎对 passthrough 的默认分支是「执行前确认」，读一张图也要弹卡没有道理。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("读取图片是只读操作"));
    }

    /** 按扩展名 + 魔数判图片类型；不是图片返回 null。 */
    static String mimeOf(String path, byte[] b) {
        if (b != null) {
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
            if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
            if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
        }
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return null;
    }

    private static String human(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private static String msg(Throwable t) {
        return t == null ? "未知错误" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }
}
