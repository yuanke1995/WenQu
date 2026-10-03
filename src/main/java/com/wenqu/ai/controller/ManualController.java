package com.wenqu.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.ManualSeedService;
import com.wenqu.ai.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帮助中心 / 官方内置手册只读接口。
 * <p>
 * 数据源即 {@link ManualSeedService} 同步出的官方内置知识库（builtin=1）：
 * 登录即可读（库 share_config 为全员只读，与网页检索可见性同源），
 * 不暴露任何非手册内容——篇目与内容都限定在内置库内。
 * 内容优先回 classpath 原文（阅读体验与源文件逐字一致），资源缺失时回退知识块拼装
 * （标题行在分块时被抽作块 title，拼装时按块标题还原为小节）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/manual")
@RequiredArgsConstructor
@Tag(name = "帮助中心", description = "问渠使用手册（官方内置知识库的只读视图）")
public class ManualController {

    private final KnowledgeBaseService kbService;
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;

    @Operation(summary = "手册篇目列表", description = "官方内置库的全部篇目（按文件名编号排序）；登录即可读")
    @GetMapping("/documents")
    public ResultJson documents() {
        KnowledgeBase kb = kbService.builtinKb();
        if (kb == null) return ResultJson.ok(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiDocument d : documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getKbId, kb.getId())
                .eq(AiDocument::getDeleted, 0)
                .orderByAsc(AiDocument::getFileName))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("fileName", d.getFileName());
            m.put("title", displayTitle(d.getFileName()));
            m.put("status", d.getStatus());
            m.put("chunkCount", d.getChunkCount());
            m.put("failReason", d.getFailReason());
            m.put("updateTime", d.getUpdateTime());
            out.add(m);
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "单篇内容", description = "返回该篇 Markdown 原文；仅官方内置库内的篇目可读")
    @GetMapping("/documents/{id}/content")
    public ResultJson content(@PathVariable("id") String id) {
        KnowledgeBase kb = kbService.builtinKb();
        if (kb == null) throw new BizException("内置手册尚未同步");
        AiDocument doc = documentMapper.selectById(id);
        if (doc == null || Integer.valueOf(1).equals(doc.getDeleted())
                || !kb.getId().equals(doc.getKbId())) {
            throw new BizException("手册篇目不存在");
        }
        String content = rawClasspathContent(doc.getFileName());
        boolean fromSource = content != null;
        if (content == null) content = joinChunks(doc.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc.getId());
        m.put("fileName", doc.getFileName());
        m.put("title", displayTitle(doc.getFileName()));
        m.put("status", doc.getStatus());
        m.put("failReason", doc.getFailReason());
        m.put("fromSource", fromSource);
        m.put("content", content == null ? "" : content);
        return ResultJson.ok(m);
    }

    /** 文件名 → 展示标题："01-产品简介.md" → "产品简介" */
    static String displayTitle(String fileName) {
        if (fileName == null) return "";
        String t = fileName.trim();
        if (t.toLowerCase().endsWith(".md")) t = t.substring(0, t.length() - 3);
        t = t.replaceFirst("^\\d+[-_ ]+", "");
        return t;
    }

    /** classpath:manual/{fileName} 原文；资源不存在返回 null（回退知识块拼装） */
    private String rawClasspathContent(String fileName) {
        try {
            Resource r = new PathMatchingResourcePatternResolver().getResource("classpath:manual/" + fileName);
            if (!r.exists()) return null;
            byte[] bytes = r.getContentAsByteArray();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 知识块拼装兜底：按 chunkIndex 串联，块标题还原为二级标题（md 分块把标题行抽成了块 title） */
    private String joinChunks(String docId) {
        List<Knowledge> chunks = knowledgeMapper.selectList(new LambdaQueryWrapper<Knowledge>()
                .eq(Knowledge::getDocId, docId)
                .orderByAsc(Knowledge::getChunkIndex));
        StringBuilder sb = new StringBuilder();
        for (Knowledge k : chunks) {
            if (sb.length() > 0) sb.append("\n\n");
            if (k.getTitle() != null && !k.getTitle().isBlank()) {
                sb.append("## ").append(k.getTitle().trim()).append("\n\n");
            }
            sb.append(k.getContent() == null ? "" : k.getContent());
        }
        return sb.toString();
    }
}
