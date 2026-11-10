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
import com.wenqu.ai.security.Permission;

/**
 * 帮助中心 / 官方内置手册只读接口。
 * <p>
 * <b>篇目与正文以随包分发的 {@code classpath:manual/*.md} 为准</b>：手册是版本内容，读它不该依赖
 * 管理员手动同步。内置知识库（builtin=1）只是这份内容的检索副本——同步进库供问答链路召回。
 * 早期实现只读内置库，而库表/文档表带租户行级过滤：手册库落在执行同步的那个租户里，
 * 其他租户的用户（含普通用户）查不到任何篇目，帮助中心就误报「尚未同步」。
 * <p>
 * 现在清单来自 classpath，库内同篇目的状态（成功/失败、块数）作为附加信息合并展示；
 * 未同步时用 {@code src:} 前缀的合成 ID 直接读原文。只暴露手册内容，不暴露任何个人知识库数据。
 * 内容优先回 classpath 原文（阅读体验与源文件逐字一致），资源缺失时回退知识块拼装
 * （标题行在分块时被抽作块 title，拼装时按块标题还原为小节）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/manual")
@RequiredArgsConstructor
@Tag(name = "帮助中心", description = "问渠使用手册（随版本分发的只读内容）")
public class ManualController {

    /** 未入库篇目的合成 ID 前缀：{@code src:01-产品简介.md} */
    private static final String SRC_ID_PREFIX = "src:";

    private final KnowledgeBaseService kbService;
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ManualSeedService manualSeedService;

    @Operation(summary = "手册篇目列表", description = "随包分发的手册篇目（按文件名编号排序）；登录即可读，不依赖是否已同步")
    @GetMapping("/documents")
    public ResultJson documents() {
        List<String> bundled = manualSeedService.bundledFileNames();
        if (bundled.isEmpty()) return ResultJson.ok(List.of());
        // 库内同篇目的状态只是附加信息（失败原因、块数）：查不到（未同步/别的租户）就不展示
        Map<String, AiDocument> synced = new LinkedHashMap<>();
        KnowledgeBase kb = kbService.builtinKb();
        if (kb != null) {
            for (AiDocument d : documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                    .eq(AiDocument::getKbId, kb.getId())
                    .eq(AiDocument::getDeleted, 0))) {
                synced.put(d.getFileName(), d);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String fileName : bundled) {
            AiDocument d = synced.get(fileName);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d == null ? SRC_ID_PREFIX + fileName : d.getId());
            m.put("fileName", fileName);
            m.put("title", displayTitle(fileName));
            m.put("status", d == null ? 0 : d.getStatus());
            m.put("chunkCount", d == null ? 0 : d.getChunkCount());
            m.put("failReason", d == null ? null : d.getFailReason());
            m.put("updateTime", d == null ? null : d.getUpdateTime());
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
     * 见本包包级文档（{@code com.wenqu.ai.startup} 的 package-info）。
     *
     * <p><b>权限</b>：仅管理员。该端点走 SecurityConfig 默认门禁（只读手册端点是按 GET 精确放行的，
     * POST /manual/sync 命中不了那两条分支）——写操作 + 烧额度，不能对普通用户开放。
     *
     * @return {accepted, added, rebuilt, kept, failed, removed, costMs}；
     *         added+rebuilt 即本轮实际发生的向量化篇目数（= 花费）
     */
    @Operation(summary = "手动同步官方手册", description = "把随包分发的 classpath:manual/*.md 同步进官方内置库（幂等增量："
            + "指纹未变的篇目跳过、不产生向量调用）。仅管理员。升级后需手动触发一次——不会随启动自动执行")
    @Permission("manual:sync")
    @PostMapping("/sync")
    public ResultJson sync() {
        Map<String, Object> r = manualSeedService.syncNow();
        if (Boolean.FALSE.equals(r.get("accepted"))) {
            return ResultJson.error(String.valueOf(r.get("reason")));
        }
        return ResultJson.ok(r, "同步完成");
    }

    @Operation(summary = "单篇内容", description = "返回该篇 Markdown 原文；篇目限定为随包分发的手册文件")
    @GetMapping("/documents/{id}/content")
    public ResultJson content(@PathVariable("id") String id) {
        // src: 合成 ID = 未同步（或同步落在别的租户）时直接读 classpath 原文
        if (id != null && id.startsWith(SRC_ID_PREFIX)) {
            return ResultJson.ok(contentOf(null, id.substring(SRC_ID_PREFIX.length())));
        }
        KnowledgeBase kb = kbService.builtinKb();
        if (kb == null) throw new BizException("手册篇目不存在");
        AiDocument doc = documentMapper.selectById(id);
        if (doc == null || Integer.valueOf(1).equals(doc.getDeleted())
                || !kb.getId().equals(doc.getKbId())) {
            throw new BizException("手册篇目不存在");
        }
        return ResultJson.ok(contentOf(doc, doc.getFileName()));
    }

    /** 组装单篇返回体：正文优先 classpath 原文，缺失时回退知识块拼装 */
    private Map<String, Object> contentOf(AiDocument doc, String fileName) {
        // src: ID 由客户端回传，不能当资源路径用：必须是随包清单里的文件名
        if (fileName == null || !manualSeedService.bundledFileNames().contains(fileName)) {
            throw new BizException("手册篇目不存在");
        }
        String content = rawClasspathContent(fileName);
        boolean fromSource = content != null;
        if (content == null && doc != null) content = joinChunks(doc.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", doc != null ? doc.getId() : SRC_ID_PREFIX + fileName);
        m.put("fileName", fileName);
        m.put("title", displayTitle(fileName));
        m.put("status", doc == null ? 0 : doc.getStatus());
        m.put("failReason", doc == null ? null : doc.getFailReason());
        m.put("fromSource", fromSource);
        m.put("content", content == null ? "" : content);
        return m;
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
