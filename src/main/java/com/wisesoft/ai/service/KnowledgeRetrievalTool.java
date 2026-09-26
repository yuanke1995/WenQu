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
    /** 悬浮/弹窗溯源摘要截断长度（注册进 sources 的 snippet） */
    private static final int SNIPPET_CHARS = 120;

    /** 引用来源注册器：流式问答期间由 RagService 注入（instrumentTools 包装层），把工具命中注册进当前回答的来源列表并分配引用编号 */
    public interface SourceRegistrar {
        int register(Hit hit, String snippet);
    }

    /** 工具执行线程内有效（Spring AI 同步执行工具回调），用完即清，避免跨会话串号 */
    private static final ThreadLocal<SourceRegistrar> REGISTRAR = new ThreadLocal<>();

    /**
     * 工具检索范围（与主链路同库界，2026-09-26 库隔离修复）：智能体绑定了知识库时，
     * searchKnowledge 不允许越过范围检索其他库——否则「仅挂刑法库」的智能体经工具仍能
     * 把其他库的块召回并注册进引用来源。kbIds 限定向量检索的库（null=不限，与主链路
     * scopeKbIds 同语义）；docIds 对命中做后过滤（null=不限，空集合=范围内无文档，检索为空）。
     */
    public record KbScope(java.util.Collection<String> kbIds, java.util.Set<String> docIds) {}

    private static final ThreadLocal<KbScope> KB_SCOPE = new ThreadLocal<>();

    public static void setSourceRegistrar(SourceRegistrar r) {
        REGISTRAR.set(r);
    }

    public static void clearSourceRegistrar() {
        REGISTRAR.remove();
    }

    public static void setKbScope(java.util.Collection<String> kbIds, java.util.Set<String> docIds) {
        KB_SCOPE.set(new KbScope(kbIds, docIds));
    }

    public static void clearKbScope() {
        KB_SCOPE.remove();
    }

    private final HybridRetrievalService hybridRetrievalService;

    public KnowledgeRetrievalTool(HybridRetrievalService hybridRetrievalService) {
        this.hybridRetrievalService = hybridRetrievalService;
    }

    /**
     * 精确检索知识库，返回与查询相关的内容片段。
     * 当你认为已有信息不足以回答用户问题，或需要核对知识库中某个具体功能/字段/步骤/报错处理时，调用本工具补充检索。
     *
     * @param query 用于检索知识库的关键词或短语（越精准召回越好，可包含功能名/字段名/操作对象）
     * @param topK  需要返回的结果条数（1~5，默认 5；数值越小返回内容越精简）
     * @return 命中的知识片段列表（含章节路径、标题与正文），未命中返回空
     */
    @Tool(description = "精确检索知识库，返回与查询相关的知识片段（含章节路径、标题、正文）。当现有信息不足以回答、或需要核对知识库中具体功能/字段/步骤/报错处理时调用。")
    public String searchKnowledge(
            @ToolParam(description = "检索知识库的关键词或短语，越精准越好，可含功能名/字段名/操作对象") String query,
            @ToolParam(description = "返回结果条数（1~5，默认 5）") Integer topK) {

        if (query == null || query.isBlank()) {
            return "查询关键词不能为空";
        }
        int limit = topK == null ? MAX_HITS : Math.max(1, Math.min(topK, MAX_HITS));
        // 范围与主链路对齐：向量路按库界检索（kbIds），命中再按文档范围后过滤（docIds，含空集合 fail-closed）
        KbScope scope = KB_SCOPE.get();
        List<Hit> hits;
        try {
            hits = hybridRetrievalService.search(query.trim(), null, scope == null ? null : scope.kbIds());
        } catch (Exception e) {
            return "知识库检索失败：" + e.getMessage();
        }
        if (scope != null && scope.docIds() != null) {
            hits = hits.stream()
                    .filter(h -> h.docId() != null && scope.docIds().contains(h.docId()))
                    .toList();
        }
        if (hits == null || hits.isEmpty()) {
            return "未在知识库中检索到相关内容";
        }
        // 注册模式（流式问答）：命中块注册进当前回答的来源列表续编引用编号，
        // 文本用【引用N】并提示模型按编号标注 → 前端角标悬浮/引用弹窗可溯源；
        // 非注册模式（无流上下文，如直接调用）：保持旧【片段N】格式
        SourceRegistrar registrar = REGISTRAR.get();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Hit h : hits) {
            if (count >= limit) break;
            count++;
            String content = h.content() == null ? "" : h.content();
            String cut = content.length() > MAX_CONTENT_CHARS ? content.substring(0, MAX_CONTENT_CHARS) + "…（已截断）" : content;
            if (registrar != null) {
                String snippet = content.length() > SNIPPET_CHARS ? content.substring(0, SNIPPET_CHARS) + "…" : content;
                sb.append("【引用").append(registrar.register(h, snippet)).append("】");
            } else {
                sb.append("【片段").append(count).append("】");
            }
            if (h.titlePath() != null && !h.titlePath().isBlank()) {
                sb.append("章节：").append(h.titlePath()).append(" / ");
            }
            sb.append("标题：").append(h.title() == null ? "" : h.title()).append("\n");
            sb.append(cut).append("\n\n");
        }
        if (registrar != null) {
            sb.append("（回答中引用以上内容时，请在对应句子后用方括号标注上述引用编号，如 [3]）");
        }
        return sb.toString().trim();
    }
}
