package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 产物交付工具（present_artifacts 模式）：模型在回答中调用本工具，把结构化的文本内容
 * 落盘为文件产物（文本类与 HTML，清单见 {@link com.wenqu.ai.util.PreviewTypes#TEXT_ARTIFACT_EXTS}），
 * 通过 SSE 实时下发给前端渲染成可预览/下载的卡片。
 * <p>
 * 典型用法：用户要求"把检索结果整理成表格/导出清单"，模型在回答末尾调用本工具生成文件。
 * 通过 {@code toolContext}（ChatService 以 toolContext 注入 sessionId）定位当前会话，
 * 落盘到会话隔离目录并实时下发 artifact 事件。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class PresentArtifactTool {

    /** toolContext 中会话 ID 的键名（ChatService 注入） */
    public static final String CTX_SESSION_ID = "sessionId";

    /**
     * toolContext 中用户 ID 的键名（ChatService 注入）。
     * <p>沙盒工具的归属 uid 必须从这里取，不能读 {@code RequestUser.uid()}：工具回调在 Spring AI
     * 响应式 I/O 线程上执行，而 {@code RequestUser} 是 ThreadLocal，由流水线线程装载，
     * 跨线程即失效、回落成 SYSTEM —— 表现为沙盒被建到 {@code shared/system/workspace}。
     * 与会话 ID 一起经 toolContext 透传，才是跨线程安全的取身份方式（参见 SubAgentOrchestrator 的同样处理）。
     */
    public static final String CTX_USER_ID = "userId";

    /** 「见 xxx.html」这类引用句只可能出现在短内容里；正文产物超过这个长度就不再按引用判，
     * 避免工具把一句引用照字面落库成产物、还回「已生成产物」。 */
    private static final int POINTER_MAX_CHARS = 200;

    /** 文件引用句：以「见/详见/请见/参见/see」开头并点名一个受支持扩展名的文件 */
    private static final Pattern FILE_REFERENCE = Pattern.compile(
            "^[\\s\"'“”‘’]*(?:见|参见|详见|请见|见附件|请看|see|refer\\s+to)\\s*[\"'“”‘’]?"
                    + "[\\w./\\\\-]+\\.(?:html?|md|markdown|csv|tsv|json|txt|log|xml|ya?ml)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** 整个 content 只有一个文件名（模型把「文件名」当成了「内容」） */
    private static final Pattern BARE_FILENAME = Pattern.compile(
            "^[\\s\"'“”‘’]*[\\w./\\\\-]+\\.(?:html?|md|markdown|csv|tsv|json|txt|log|xml|ya?ml)[\\s\"'“”‘’]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private final ArtifactService artifactService;

    public PresentArtifactTool(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 生成并交付一个文件产物（文本类与 HTML）。
     * 当你需要把回答内容整理成可下载/预览的清单、表格或文档时调用；产物会实时推送给用户。
     *
     * @param content     产物内容（纯文本；Markdown 用 .md，逗号分隔表格用 .csv，结构化数据用 .json，网页用 .html）
     * @param filename    产物文件名（建议含扩展名，如 操作清单.md / 数据汇总.csv；清单见 PreviewTypes.TEXT_ARTIFACT_EXTS）
     * @param description 给用户的产物说明（简短，一两句话，说明这是什么）
     * @return 产物访问地址与信息
     */
    @Tool(description = "生成并交付一个文件产物（文本类：Markdown/纯文本/CSV/JSON/YAML/XML/代码等；网页：HTML）。"
            + "当且仅当用户要下载或预览成品文件时调用；"
            + "用户要拿去复制、粘贴或导入的片段（配置、代码、结构化数据），直接写进回答的代码块，不要调用本工具。"
            + "产物会实时推送给用户展示为卡片。"
            + "content 必须是完整文件正文，不能只写文件名或「见 xxx」这类转述；文件已经写在沙盒/工作区时，改用 deliver_artifact 按文件路径交付。")
    public String presentArtifact(
            @ToolParam(description = "产物完整内容（纯文本；Markdown 表格用 .md，逗号分隔数据用 .csv，结构化数据用 .json/.yaml，网页用 .html）。"
                    + "不是文件名/路径，也不要写「见 index.html」之类的引用句") String content,
            @ToolParam(description = "产物文件名（含扩展名，如 操作清单.md / 数据汇总.csv；支持常见文本扩展名——md/txt/csv/json/yaml/xml/代码等——与 html/htm）") String filename,
            @ToolParam(description = "给用户的产物说明（简短，说明这是什么，如“已生成动态子表配置对照表”）") String description,
            ToolContext toolContext) {

        String sessionId = null;
        if (toolContext != null) {
            Object v = toolContext.getContext().get(CTX_SESSION_ID);
            if (v != null) sessionId = String.valueOf(v);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return "无法定位当前会话，产物交付失败";
        }
        String rejection = contentRejection(filename, content);
        if (rejection != null) {
            log.warn("[ARTIFACT] presentArtifact 拒绝入库 session={} file={} chars={}：{}",
                    sessionId, filename, content == null ? 0 : content.length(), rejection);
            return "产物生成失败：" + rejection;
        }
        try {
            // 落盘 + 登记 c_ai_artifact（归属取会话的 user_id），返回 {id,url,filename,ext,size,description}
            java.util.Map<String, Object> info =
                    artifactService.write(sessionId, filename, content, description);
            // 实时推送给前端（客户端断开则静默忽略，不影响工具返回值）；payload 即产物卡片所需字段
            artifactService.publish(sessionId, "artifact", JSON.toJSONString(info));
            return "已生成产物：" + (description == null ? "" : description)
                    + " 访问地址：" + info.get("url");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "产物生成失败：" + e.getMessage();
        }
    }

    /**
     * content 不是文件正文时返回可执行的拒绝原因；正常返回 null。
     * <p>必须在落库前拦：presentArtifact 对 content 是「字面照写」，一旦把引用句写进去，
     * 产物卡片看起来成功、点开却只有一句话，模型也不会重试。
     */
    static String contentRejection(String filename, String content) {
        String text = content == null ? "" : content.trim();
        String ext = extOf(filename);
        if (("html".equals(ext) || "htm".equals(ext)) && text.indexOf('<') < 0) {
            return "content 只有纯文本，不是完整网页。请把 <!DOCTYPE html> 起的完整 HTML 放进 content 重新调用；"
                    + "页面若已写在沙盒/工作区文件里，改用 deliver_artifact(filePath=文件路径) 交付。";
        }
        if (text.length() <= POINTER_MAX_CHARS
                && (FILE_REFERENCE.matcher(text).find() || BARE_FILENAME.matcher(text).matches())) {
            return "content 只是对文件的引用（" + text + "），不是文件内容。请把完整内容放进 content 重新调用；"
                    + "文件若已写在沙盒/工作区，改用 deliver_artifact(filePath=文件路径) 交付。";
        }
        return null;
    }

    private static String extOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
