package com.wisesoft.ai.service;

import com.wisesoft.ai.service.HybridRetrievalService.Hit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库精确检索工具（Function Calling / Tool Calling）。
 * <p>
 * 让 LLM 在主链路已注入 RAG 上下文之外，仍能按需主动调用一次"精确检索知识库"，
 * 用于：1) 主链路未召回但模型判断需要补充的信息；2) 用户追问时针对特定主题的二次检索。
 * 默认关闭（ConfigService 的 tool.knowledgeRetrieval.enabled），开启后模型方可调用。
 * <p>
 * 返回内容经裁剪，避免工具结果膨胀挤占上下文预算；命中内容与主链路 RAG 召回天然互补。
 *
 * @author yuanke
 */
@Component
public class KnowledgeRetrievalTool {

    /** 单次工具调用返回的命中块上限（调用方也可用 topK 显式指定，这里兜底截断） */
    private static final int MAX_HITS = 5;
    /** 单块 content 截断字符数（控制工具结果 token 量） */
    private static final int MAX_CONTENT_CHARS = 600;

    private final HybridRetrievalService hybridRetrievalService;

    public KnowledgeRetrievalTool(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * 精确检索产品操作手册知识库，返回与查询相关的内容片段。
     * 当你认为已有信息不足以回答用户问题，或需要核对操作手册中某个具体功能/字段/步骤/报错处理时，调用本工具补充检索。
     *
     * @param query 用于检索知识库的关键词或短语（越精准召回越好，可包含功能名/字段名/操作对象）
     * @param topK  需要返回的结果条数（1~5，默认 5；数值越小返回内容越精简）
     * @return 命中的知识片段列表（含章节路径、标题与正文），未命中返回空
     */
    @Tool(description = "精确检索产品操作手册知识库，返回与查询相关的知识片段（含章节路径、标题、正文）。当现有信息不足以回答、或需要核对手册中具体功能/字段/步骤/报错处理时调用。")
    public String searchKnowledge(
            @ToolParam(description = "检索知识库的关键词或短语，越精准越好，可含功能名/字段名/操作对象") String query,
            @ToolParam(description = "返回结果条数（1~5，默认 5）") Integer topK) {

        if (query == null || query.isBlank()) {
            return "查询关键词不能为空";
        }
        int limit = topK == null ? MAX_HITS : Math.max(1, Math.min(topK, MAX_HITS));
        List<Hit> hits;
        try {
            hits = hybridRetrievalService.search(query.trim());
        } catch (Exception e) {
            return "知识库检索失败：" + e.getMessage();
        }
        if (hits == null || hits.isEmpty()) {
            return "未在知识库中检索到相关内容";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Hit h : hits) {
            if (count >= limit) break;
            count++;
            sb.append("【片段").append(count).append("】");
            if (h.titlePath() != null && !h.titlePath().isBlank()) {
                sb.append("章节：").append(h.titlePath()).append(" / ");
            }
            sb.append("标题：").append(h.title() == null ? "" : h.title()).append("\n");
            String content = h.content() == null ? "" : h.content();
            if (content.length() > MAX_CONTENT_CHARS) {
                content = content.substring(0, MAX_CONTENT_CHARS) + "…（已截断）";
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString().trim();
    }
}
