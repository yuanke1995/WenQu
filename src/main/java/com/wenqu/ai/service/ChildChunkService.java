package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.KnowledgeChildMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeChild;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 父子分块索引服务（小块检索、父块作答）
 * <p>
 * parse.childEnabled 开启时，解析期把超过 parse.childSize 的知识块（父块）确定性切成
 * 小子块，按子块向量化：短文本的向量语义更聚焦（不混多个主题），召回精度高于整大块；
 * 命中子块后经 knowledge_id 取回父块完整正文进上下文与引用，回答不失上下文。
 * <p>
 * 与 {@link QaIndexService} 同构的存储约束：子块向量 id = 本表 id（RedisVectorStore 同 id
 * 覆盖语义）；行落库供重嵌/迁移恢复向量与命中解析（metadata 可能被 RedisVectorStore 丢弃）。
 * 纯确定性切分（无 LLM 调用），成本仅为子块 embedding。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChildChunkService {

    private final KnowledgeChildMapper childMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final ConfigService configService;

    /** 子块尺寸上下限（字符） */
    private static final int MIN_CHILD_SIZE = 100;
    private static final int MAX_CHILD_SIZE = 2000;

    private int childSize() {
        return Math.max(MIN_CHILD_SIZE, Math.min(MAX_CHILD_SIZE, configService.getInt("parse.childSize", 400)));
    }

    /**
     * 为知识块列表生成子块索引（解析/回滚/块编辑后重建用）：
     * 超过 childSize 的块切段 → 落库 → 攒批向量化；父块粒度已合适（≤childSize）的块跳过。
     * 向量化失败补偿删除本次落库行并抛出（与解析链路"失败不残留"口径一致）。
     *
     * @return 生成的子块条数
     */
    public int generateForBlocks(String docId, List<Knowledge> blocks, VectorStore store,
                                 java.util.function.BiConsumer<Integer, String> progress) {
        int size = childSize();
        int total = 0;
        List<KnowledgeChild> rows = new ArrayList<>();
        List<Document> vectors = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Knowledge k = blocks.get(i);
            if (progress != null) {
                progress.accept(i * 100 / Math.max(1, blocks.size()), "子块索引 " + (i + 1) + "/" + blocks.size());
            }
            String content = k.getContent() == null ? "" : k.getContent();
            if (content.length() <= size) continue; // 父块本身粒度已合适，块向量足够
            List<String> slices = split(content, size);
            if (slices.size() <= 1) continue; // 切不开（单段落刚好超限被硬切为一整段）无意义
            for (int s = 0; s < slices.size(); s++) {
                KnowledgeChild row = new KnowledgeChild();
                row.setDocId(docId);
                row.setKnowledgeId(k.getId());
                row.setContent(slices.get(s));
                row.setChunkSeq(s);
                childMapper.insert(row);
                rows.add(row);
                Map<String, Object> md = new HashMap<>();
                md.put("docId", docId);
                md.put("knowledgeId", k.getId());
                md.put("title", k.getTitle() == null ? "" : k.getTitle());
                md.put("kind", "child");
                vectors.add(new Document(row.getId(), embedText(k.getTitle(), k.getTitlePath(), slices.get(s)), md));
                total++;
            }
        }
        if (!vectors.isEmpty()) {
            try {
                store.add(vectors);
            } catch (Exception e) {
                try {
                    childMapper.deleteBatchIds(rows.stream().map(KnowledgeChild::getId).toList());
                } catch (Exception ex) {
                    log.warn("[ChildChunk] 向量化失败后补偿删行也失败（残留 {} 行，下次重解析清理）: {}",
                            rows.size(), ex.getMessage());
                }
                throw new IllegalStateException("子块向量化失败: " + e.getMessage(), e);
            }
        }
        log.info("[ChildChunk] 文档 {} 子块索引完成: {} 条", docId, total);
        return total;
    }

    /**
     * 段落边界优先的确定性切分：按空行/换行聚段到 childSize；单段超长在句读边界
     * （。！？；.!?)）硬切，仍超长按字符硬切。不回加重叠（子块只做索引，正文由父块供给）
     */
    private List<String> split(String content, int size) {
        List<String> slices = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String para : content.split("\\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            // 单段超限：句读边界优先硬切（避免整段一坨塞进同一子块）
            while (p.length() > size) {
                int cut = sentenceBoundary(p, size);
                flushSlice(slices, buf, p.substring(0, cut), size);
                p = p.substring(cut).trim();
            }
            if (p.isEmpty()) continue;
            if (buf.length() > 0 && buf.length() + p.length() > size) {
                slices.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(p).append('\n');
        }
        if (buf.length() > 0) flushSlice(slices, buf, "", size);
        return slices;
    }

    /** 把一段（可能超限的）文本入缓冲；缓冲已超限时先落盘 */
    private void flushSlice(List<String> slices, StringBuilder buf, String extra, int size) {
        if (!extra.isEmpty()) buf.append(extra).append('\n');
        if (buf.length() >= size) {
            slices.add(buf.toString().trim());
            buf.setLength(0);
        }
    }

    /** 句读边界：在 size 内找最后一个句读符号；找不到退回 size（字符硬切） */
    private int sentenceBoundary(String text, int size) {
        int limit = Math.min(size, text.length());
        for (int i = limit - 1; i > limit / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '.' || c == '!' || c == '?' || c == ';') {
                return i + 1;
            }
        }
        return limit;
    }

    /** 子块 embedding 文本：标题 + 章节路径前缀（与父块 buildEmbedText 同构，补足子块上下文） */
    private String embedText(String title, String titlePath, String slice) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title).append('\n');
        if (titlePath != null && !titlePath.isBlank()) sb.append("【上下文】").append(titlePath).append("\n\n");
        sb.append(slice);
        return sb.toString();
    }

    // ==================== 向量/行清理与恢复（供文档/块删除、迁移、重嵌调用） ====================

    /** 按文档取全部子块向量 id */
    public List<String> vectorIdsByDoc(String docId) {
        if (docId == null || docId.isBlank()) return List.of();
        return childMapper.selectList(new LambdaQueryWrapper<KnowledgeChild>()
                        .eq(KnowledgeChild::getDocId, docId).select(KnowledgeChild::getId))
                .stream().map(KnowledgeChild::getId).toList();
    }

    /** 按父块取子块向量 id */
    public List<String> vectorIdsByKnowledge(Collection<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return List.of();
        return childMapper.selectList(new LambdaQueryWrapper<KnowledgeChild>()
                        .in(KnowledgeChild::getKnowledgeId, knowledgeIds).select(KnowledgeChild::getId))
                .stream().map(KnowledgeChild::getId).toList();
    }

    /** 物理删行（按文档；删除文档/回滚用） */
    public void deleteByDocPhysical(String docId) {
        if (docId == null || docId.isBlank()) return;
        childMapper.delete(new LambdaQueryWrapper<KnowledgeChild>().eq(KnowledgeChild::getDocId, docId));
    }

    /** 物理删行（按父块；增量清理 staleOld/块删除/块编辑用） */
    public void deleteByKnowledgePhysical(Collection<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return;
        childMapper.delete(new LambdaQueryWrapper<KnowledgeChild>().in(KnowledgeChild::getKnowledgeId, knowledgeIds));
    }

    /**
     * 从落库行恢复子块向量（跨库迁移/按库重嵌用；确定性切分结果，无需重切）。
     * embedding 文本需重拼父块标题/章节路径前缀（与生成时同构，父块改名后跟随新语义）。
     *
     * @return 恢复的向量条数
     */
    public int reembedForDocs(Collection<String> docIds, VectorStore target) {
        if (docIds == null || docIds.isEmpty()) return 0;
        List<KnowledgeChild> rows = childMapper.selectList(new LambdaQueryWrapper<KnowledgeChild>()
                .in(KnowledgeChild::getDocId, docIds));
        if (rows.isEmpty()) return 0;
        java.util.Set<String> kidIds = rows.stream().map(KnowledgeChild::getKnowledgeId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<String, Knowledge> parents = kidIds.isEmpty() ? Map.of()
                : knowledgeMapper.selectBatchIds(kidIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                k -> String.valueOf(k.getId()), k -> k, (a, b) -> a));
        List<Document> vectors = new ArrayList<>();
        for (KnowledgeChild row : rows) {
            Knowledge p = parents.get(row.getKnowledgeId());
            String title = p == null ? "" : (p.getTitle() == null ? "" : p.getTitle());
            String path = p == null ? null : p.getTitlePath();
            Map<String, Object> md = new HashMap<>();
            md.put("docId", row.getDocId());
            md.put("knowledgeId", row.getKnowledgeId());
            md.put("title", title);
            md.put("kind", "child");
            vectors.add(new Document(row.getId(), embedText(title, path, row.getContent()), md));
        }
        int batchSize = 50;
        int ok = 0;
        for (int i = 0; i < vectors.size(); i += batchSize) {
            List<Document> batch = vectors.subList(i, Math.min(i + batchSize, vectors.size()));
            try {
                target.add(batch);
                ok += batch.size();
            } catch (Exception e) {
                log.warn("[FAIL-LOUD] [Child-Reembed] 子块向量恢复失败 {}-{}（可重解析补齐）: {}",
                        i + 1, i + batch.size(), e.getMessage());
            }
        }
        return ok;
    }
}
