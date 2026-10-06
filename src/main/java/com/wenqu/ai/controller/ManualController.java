package com.wenqu.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.startup.ManualSeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ManualSeedService manualSeedService;

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

    /**
     * 手动同步官方内置手册（版本升级后把新手册灌进内置库）。
     *
     * <p><b>为什么是手动而不是自动</b>：同步会对每篇手册逐块调 embedding 模型（十余篇 = 数十次远程请求），
     * 成本落在所选向量模型供应商的额度上。挂成自动意味着<b>每次重启都烧用户额度</b>，
     * 而重启是纯运维动作、用户什么都没做；额度耗尽时还会连带「删不掉旧篇目」，手册库就此卡死。
     * 见 {@code StartupDataPolicy}。
     *
     * <p><b>权限</b>：仅管理员。该端点走 SecurityConfig 默认门禁（只读手册端点是按 GET 精确放行的，
     * POST /manual/sync 命中不了那两条分支）——写操作 + 烧额度，不能对普通用户开放。
     *
     * @return {accepted, added, rebuilt, kept, failed, removed, costMs}；
     *         added+rebuilt 即本轮实际发生的向量化篇目数（= 花费）
     */
    @Operation(summary = "手动同步官方手册", description = "把随包分发的 classpath:manual/*.md 同步进官方内置库（幂等增量："
            + "指纹未变的篇目跳过、不产生向量调用）。仅管理员。升级后需手动触发一次——不会随启动自动执行")
    @PostMapping("/sync")
    public ResultJson sync() {
        Map<String, Object> r = manualSeedService.syncNow();
        if (Boolean.FALSE.equals(r.get("accepted"))) {
            return ResultJson.error(String.valueOf(r.get("reason")));
        }
        return ResultJson.ok(r, "同步完成");
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
