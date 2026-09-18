package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.DocumentMetaCache;
import com.wisesoft.ai.service.KeywordExtractor;
import com.wisesoft.ai.service.KeywordIndexService;
import com.wisesoft.ai.service.QaLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.wisesoft.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识块管理（新增/查询），以及无命中问题查询
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "无命中问题查询、知识块预览、跨文档搜索、手动创建知识块")
public class KnowledgeController {

    private final KnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final DocumentService documentService;
    private final QaLogService qaLogService;
    private final DocumentMetaCache documentMetaCache;
    private final KeywordExtractor keywordExtractor;
    private final KeywordIndexService keywordIndexService;

    @Operation(summary = "无命中问题列表", description = "获取最近 30 天内无检索命中的问题列表（按频次降序，最多 50 条），供一键入库补齐知识缺口")
    @GetMapping("/knowledge/unmatched")
    public ResultJson listUnmatched() {
        return ResultJson.ok(qaLogService.listUnmatched());
    }

    @Operation(summary = "按文档列知识块", description = "获取指定文档下的所有知识块（含标题、正文、图片），用于知识块预览")
    @GetMapping("/knowledge/list")
    public ResultJson listByDoc(
            @Parameter(description = "文档 ID") @RequestParam("docId") String docId) {
        if (docId == null || docId.isBlank()) {
            throw new BizException("缺少 docId");
        }
        List<Map<String, Object>> list = knowledgeMapper.selectList(
                        new LambdaQueryWrapper<Knowledge>()
                                .eq(Knowledge::getDocId, docId)
                                .orderByAsc(Knowledge::getChunkIndex))
                .stream().map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", k.getId());
                    m.put("title", k.getTitle());
                    m.put("chunkIndex", k.getChunkIndex());
                    m.put("titlePath", k.getTitlePath());
                    m.put("vectorId", k.getVectorId());   // M11：空 = 未向量化（前端角标）
                    m.put("status", k.getStatus());       // 块级启停用：0=生效, 1=停用
                    m.put("content", k.getContent());
                    m.put("images", k.getImages() != null && !k.getImages().isBlank()
                            ? com.alibaba.fastjson2.JSON.parseArray(k.getImages()) : List.of());
                    return m;
                }).toList();
        return ResultJson.ok(list);
    }

    @Operation(summary = "启停用知识块", description = "块级启停用：停用后不参与召回（关键词/向量/引用扩散同步剔除），用于诊断屏蔽坏块。停用移除关键词索引、恢复回写；答案缓存同步失效")
    @PutMapping("/knowledge/{id}/status")
    public ResultJson updateKnowledgeStatus(
            @Parameter(description = "知识块 ID") @PathVariable("id") String id,
            @Parameter(description = "{\"status\": 0|1}") @RequestBody Map<String, Integer> body,
            HttpServletRequest httpRequest) {
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) throw new BizException("非法状态（0=生效, 1=停用）");
        Knowledge k = knowledgeMapper.selectById(id);
        if (k == null) throw new BizException(404, "知识块不存在");
        if (k.getDocId() != null && !k.getDocId().isBlank()) {
            AiDocument doc = documentMapper.selectById(k.getDocId());
            if (doc != null && com.wisesoft.ai.service.FileStatus.PARSING.equals(doc.getStatus())) {
                throw new BizException("文档解析中，暂不可操作");
            }
        }
        Knowledge upd = new Knowledge();
        upd.setId(id);
        upd.setStatus(status);
        knowledgeMapper.updateById(upd);
        // 关键词索引同步：停用移除、恢复回写（best-effort；MySQL LIKE 路由 SQL 过滤兜底）
        try {
            if (status == 1) keywordIndexService.deleteChunks(List.of(id));
            else keywordIndexService.indexChunks(List.of(k));
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 知识块 {} 状态切换索引同步失败: {}", id, e.getMessage());
        }
        log.info("[AUDIT] 知识块{} operator={} id={} title={}", status == 1 ? "停用" : "启用",
                RequestUser.uid(), id, k.getTitle());
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "跨文档搜索知识块", description = "按关键词在全部知识块（含已停用，带状态标记）中搜索，管理端排查坏块用；返回最多 50 条")
    @GetMapping("/knowledge/search")
    public ResultJson searchKnowledge(
            @Parameter(description = "搜索关键词") @RequestParam("keyword") String keyword,
            @Parameter(description = "返回上限（默认 50，最大 50）") @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        if (keyword == null || keyword.isBlank()) throw new BizException("请输入关键词");
        List<String> extracted = new ArrayList<>(keywordExtractor.extract(keyword));
        if (extracted.isEmpty()) extracted.add(keyword.trim());
        final List<String> terms = extracted.size() > 5 ? new ArrayList<>(extracted.subList(0, 5)) : extracted;

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Knowledge> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Knowledge>()
                        .last("LIMIT " + Math.min(Math.max(1, limit), 50));
        wrapper.and(w -> {
            for (int i = 0; i < terms.size(); i++) {
                if (i > 0) w.or();
                String term = terms.get(i);
                w.and(t -> t.like("content", term).or().like("title", term));
            }
        });
        List<Knowledge> hits = knowledgeMapper.selectList(wrapper);
        List<Map<String, Object>> rows = hits.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("docId", k.getDocId());
            m.put("docName", documentMetaCache.getFileName(k.getDocId()));
            m.put("title", k.getTitle());
            m.put("chunkIndex", k.getChunkIndex());
            m.put("status", k.getStatus());
            String content = k.getContent() == null ? "" : k.getContent().replaceAll("\\s+", " ").trim();
            m.put("snippet", content.length() > 80 ? content.substring(0, 80) + "…" : content);
            return m;
        }).toList();
        return ResultJson.ok(rows);
    }

    @Operation(summary = "新增知识块", description = "手动创建知识块（自动生成向量），用于补充知识库缺口。可关联已有文档或独立创建")
    @PostMapping("/knowledge")
    public ResultJson createKnowledge(
            @Parameter(description = "{\"title\": \"问题/标题\", \"content\": \"回答内容\", \"docId\": \"可选关联文档ID\"}")
            @RequestBody Map<String, Object> body) {
        String title = body.get("title") == null ? null : String.valueOf(body.get("title")).trim();
        String content = body.get("content") == null ? null : String.valueOf(body.get("content")).trim();
        String docId = body.get("docId") == null ? null : String.valueOf(body.get("docId")).trim();

        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        if (title.length() > 200) {
            throw new BizException("标题过长（最多200字）");
        }
        if (content == null || content.isBlank()) {
            throw new BizException("内容不能为空");
        }

        // H3：docId 存在性校验（填了无效 docId 会导致检索 inSql 排除、知识块入死区）
        if (docId != null && !docId.isBlank()) {
            Long cnt = documentMapper.selectCount(new LambdaQueryWrapper<AiDocument>()
                    .eq(AiDocument::getId, docId).eq(AiDocument::getStatus, 0));
            if (cnt == null || cnt == 0) {
                throw new BizException("关联文档不存在或已停用");
            }
        }

        Knowledge k = new Knowledge();
        k.setTitle(title);
        k.setContent(content);
        k.setStatus(0);
        k.setDocId(docId != null && !docId.isBlank() ? docId : null);
        k.setContentHash(documentService.contentHash(title, content));
        // 先入库，再生成向量（M11 fail-loud：向量化失败要在响应中显式告知，不再静默"已创建"）
        knowledgeMapper.insert(k);
        boolean embedded = documentService.embedAndStore(k, k.getContent());
        if (!embedded) {
            return ResultJson.ok(Map.of("id", k.getId(), "warn", "知识块已创建但向量化失败，仅可关键词检索（可重新编辑触发向量化）"),
                    "知识块已创建（向量化失败，仅关键词可召回）");
        }
        return ResultJson.ok(Map.of("id", k.getId()), "知识块已创建");
    }

    @Operation(summary = "编辑知识块", description = "修改知识块标题/正文（图片保留），并重新向量化")
    @PutMapping("/knowledge/{id}")
    public ResultJson updateKnowledge(
            @Parameter(description = "知识块 ID") @PathVariable("id") String id,
            @Parameter(description = "{\"title\": \"新标题\", \"content\": \"新内容\"}")
            @RequestBody Map<String, String> body) {
        documentService.updateKnowledge(id, body.get("title"), body.get("content"));
        return ResultJson.ok("知识块已更新");
    }

    @Operation(summary = "删除知识块", description = "删除知识块（同步移除向量并扣减文档知识块数）")
    @DeleteMapping("/knowledge/{id}")
    public ResultJson deleteKnowledge(
            @Parameter(description = "知识块 ID") @PathVariable("id") String id) {
        documentService.deleteKnowledge(id);
        return ResultJson.ok("知识块已删除");
    }
}