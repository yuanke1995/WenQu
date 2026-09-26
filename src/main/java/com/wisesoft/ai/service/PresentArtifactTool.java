package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 产物交付工具（present_artifacts 模式）：模型在回答中调用本工具，把结构化的文本内容
 * 落盘为文件产物（Markdown/CSV/JSON/HTML），通过 SSE 实时下发给前端渲染成可预览/下载的卡片。
 * <p>
 * 典型用法：用户要求"把检索结果整理成表格/导出清单"，模型在回答末尾调用本工具生成文件。
 * 通过 {@code toolContext}（RagService 以 toolContext 注入 sessionId）定位当前会话，
 * 落盘到会话隔离目录并实时下发 artifact 事件。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class PresentArtifactTool {

    /** toolContext 中会话 ID 的键名（RagService 注入） */
    public static final String CTX_SESSION_ID = "sessionId";

    private final ArtifactService artifactService;

    public PresentArtifactTool(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 生成并交付一个文件产物（Markdown/CSV/JSON/HTML）。
     * 当你需要把回答内容整理成可下载/预览的清单、表格或文档时调用；产物会实时推送给用户。
     *
     * @param content     产物内容（纯文本；Markdown 用 .md，逗号分隔表格用 .csv，结构化数据用 .json，网页用 .html）
     * @param filename    产物文件名（建议含扩展名，如 操作清单.md / 数据汇总.csv；支持 md/txt/csv/json/html）
     * @param description 给用户的产物说明（简短，一两句话，说明这是什么）
     * @return 产物访问地址与信息
     */
    @Tool(description = "生成并交付一个文件产物（Markdown/CSV/JSON/HTML）。当用户要求把回答整理成可下载、可预览的清单/表格/文档时调用；产物会实时推送给用户展示为卡片。")
    public String presentArtifact(
            @ToolParam(description = "产物内容（纯文本；Markdown 表格用 .md，逗号分隔数据用 .csv，结构化数据用 .json，网页片段用 .html）") String content,
            @ToolParam(description = "产物文件名（含扩展名，如 操作清单.md / 数据汇总.csv；支持 md/txt/csv/json/html）") String filename,
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
}
