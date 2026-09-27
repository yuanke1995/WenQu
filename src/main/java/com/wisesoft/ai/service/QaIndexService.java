package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.KnowledgeQaMapper;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.model.KnowledgeQa;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QA 增强检索索引服务（对标 FastGPT 问答对模式）
 * <p>
 * 解析期按块调对话模型生成问答对，按「问法」向量化入库：
 * 用户问法与生成问法的语义距离通常远小于问法与正文的距离 ⇒ 问法命中率显著提升；
 * 命中后经 knowledge_id 取回来源块正文进上下文与引用（问法本身不进上下文）。
 * <p>
 * 关键设计（受 RedisVectorStore 语义约束）：
 * - 问答对向量 id = c_ai_knowledge_qa.id（独立 id）：同 id 在 Redis 向量库是覆盖而非追加，
 *   若复用 knowledgeId 会把块向量顶掉；
 * - 行必须落库：RedisVectorStore 可能丢弃 metadata（M6），命中解析以本表 knowledge_id 为准，
 *   且重嵌/跨库迁移可从本表恢复向量，无需再调 LLM；
 * - 所有按 knowledgeId/docId 删除向量的路径必须同步删问答对向量，否则残留僵尸命中。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaIndexService {

    private final KnowledgeQaMapper qaMapper;
    private final ConfigService configService;
    /** 对话模型（DynamicOpenAiChatModel；迁移后无全局兜底模型，生成用模型来自 parse.qaModel 配置） */
    private final ChatModel chatModel;

    /** 送入 LLM 的块正文上限（超出截断：QA 是问法增强，不需要全文） */
    private static final int MAX_CONTENT_CHARS = 3000;
    /** 低于该长度的小块不生成（一段话生成 QA 得不偿失） */
    private static final int MIN_CONTENT_CHARS = 100;

    /**
     * 为知识块列表生成问答对（解析/回滚/块编辑后重建用）：
     * 逐块 LLM 生成 → 落库 → 攒批向量化。单块 LLM 失败跳过继续；向量化失败抛出
     * 并补偿删除本次落库行（与解析链路"失败不残留"口径一致），由调用方计入终态描述。
     *
     * @return 成功生成的问答对条数
     */
    public int generateForBlocks(String docId, List<Knowledge> blocks, VectorStore store,
                                 java.util.function.BiConsumer<Integer, String> progress) {
        // 生成用模型：parse.qaModel 独立配置（eval.judgeModel 同模式——chat.model 全局兜底退役后，
        // DynamicOpenAiChatModel 的模型名必须 per-request 显式提供，裸调请求体会被网关 400 拒绝）。
        // 未配置属功能性误配（qaEnabled 已开却没给模型），抛出走调用方既有 best-effort 可见性通道（日志/终态描述），不静默吞
        String model = configService.get("parse.qaModel");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("未配置 parse.qaModel（问答对增强需在设置页「分块与索引」指定生成用模型，"
                    + "格式 {providerId}/{modelId}；chat.model 全局兜底已退役）");
        }
        int perChunk = Math.max(1, Math.min(5, configService.getInt("parse.qaPerChunk", 2)));
        int total = 0;
        int failedBlocks = 0;
        List<KnowledgeQa> rows = new ArrayList<>();
        List<Document> vectors = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Knowledge k = blocks.get(i);
            if (progress != null) {
                progress.accept(i * 100 / Math.max(1, blocks.size()), "问答对 " + (i + 1) + "/" + blocks.size());
            }
            List<String[]> pairs;
            try {
                pairs = generate(k, perChunk, model);
            } catch (Exception e) {
                failedBlocks++;
                log.warn("[QA] 块 {} 生成失败（跳过）: {}", k.getId(), e.getMessage());
                continue;
            }
            for (String[] qa : pairs) {
                KnowledgeQa row = new KnowledgeQa();
                row.setDocId(docId);
                row.setKnowledgeId(k.getId());
                row.setQuestion(qa[0]);
                row.setAnswer(qa[1]);
                qaMapper.insert(row);
                rows.add(row);
                Map<String, Object> md = new HashMap<>();
                md.put("docId", docId);
                md.put("knowledgeId", k.getId());
                md.put("title", row.getQuestion());
                md.put("kind", "qa");
                vectors.add(new Document(row.getId(), row.getQuestion(), md));
                total++;
            }
        }
        if (!vectors.isEmpty()) {
            try {
                store.add(vectors);
            } catch (Exception e) {
                // 补偿：向量写不进去的行是死数据（召回只认向量），删行后抛出让调用方显式标记
                try {
                    qaMapper.deleteBatchIds(rows.stream().map(KnowledgeQa::getId).toList());
                } catch (Exception ex) {
                    log.warn("[QA] 向量化失败后补偿删行也失败（残留 {} 行，下次重解析清理）: {}",
                            rows.size(), ex.getMessage());
                }
                throw new IllegalStateException("QA 向量化失败: " + e.getMessage(), e);
            }
        }
        log.info("[QA] 文档 {} 生成完成: {} 条（{} 块中 {} 块失败跳过）", docId, total, blocks.size(), failedBlocks);
        return total;
    }

    /** 单块 LLM 生成：只输出 JSON 数组，容忍 ```json 围栏；问法超长截断（列宽 500） */
    private List<String[]> generate(Knowledge k, int n, String model) {
        String content = k.getContent() == null ? "" : k.getContent();
        if (content.length() < MIN_CONTENT_CHARS) return List.of();
        if (content.length() > MAX_CONTENT_CHARS) content = content.substring(0, MAX_CONTENT_CHARS);
        String sys = "你是知识库数据加工助手。根据给定文本片段生成 " + n
                + " 个用户最可能提问的问题及对应答案，问法要口语化、贴近真实用户提问。"
                + "只输出 JSON 数组，格式：[{\"q\":\"问题\",\"a\":\"答案\"}]，不要输出任何其他内容。";
        String user = "标题: " + (k.getTitle() == null ? "" : k.getTitle()) + "\n\n" + content;
        String out = chatModel.call(new Prompt(List.of(new SystemMessage(sys), new UserMessage(user)),
                        OpenAiChatOptions.builder().model(model).build()))
                .getResult().getOutput().getText();
        String json = out == null ? "" : out.trim().replaceAll("^```(json)?\\s*|\\s*```$", "");
        JSONArray arr = JSON.parseArray(json);
        List<String[]> pairs = new ArrayList<>();
        if (arr == null) return pairs;
        for (int i = 0; i < arr.size() && pairs.size() < n; i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o == null) continue;
            String q = o.getString("q");
            if (q == null || q.isBlank()) continue;
            q = q.trim();
            String a = o.getString("a") == null ? "" : o.getString("a").trim();
            pairs.add(new String[]{q.length() > 500 ? q.substring(0, 500) : q, a});
        }
        return pairs;
    }

    // ==================== 向量/行清理与恢复（供文档/块删除、迁移、重嵌调用） ====================

    /** 按文档取全部问答对向量 id */
    public List<String> vectorIdsByDoc(String docId) {
        if (docId == null || docId.isBlank()) return List.of();
        return qaMapper.selectList(new LambdaQueryWrapper<KnowledgeQa>()
                        .eq(KnowledgeQa::getDocId, docId).select(KnowledgeQa::getId))
                .stream().map(KnowledgeQa::getId).toList();
    }

    /** 按来源知识块取问答对向量 id */
    public List<String> vectorIdsByKnowledge(Collection<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return List.of();
        return qaMapper.selectList(new LambdaQueryWrapper<KnowledgeQa>()
                        .in(KnowledgeQa::getKnowledgeId, knowledgeIds).select(KnowledgeQa::getId))
                .stream().map(KnowledgeQa::getId).toList();
    }

    /** 物理删行（按文档；删除文档/回滚用） */
    public void deleteByDocPhysical(String docId) {
        if (docId == null || docId.isBlank()) return;
        qaMapper.delete(new LambdaQueryWrapper<KnowledgeQa>().eq(KnowledgeQa::getDocId, docId));
    }

    /** 物理删行（按来源块；增量清理 staleOld/块删除/块编辑用） */
    public void deleteByKnowledgePhysical(Collection<String> knowledgeIds) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return;
        qaMapper.delete(new LambdaQueryWrapper<KnowledgeQa>().in(KnowledgeQa::getKnowledgeId, knowledgeIds));
    }

    /**
     * 从落库行恢复问答对向量（跨库迁移/按库重嵌用；不调 LLM，问法已在库）。
     * 逐批 add（embedding 批量上限与解析链路同量级），失败告警不中断（可重解析补齐）。
     *
     * @return 恢复的向量条数
     */
    public int reembedForDocs(Collection<String> docIds, VectorStore target) {
        if (docIds == null || docIds.isEmpty()) return 0;
        List<KnowledgeQa> rows = qaMapper.selectList(new LambdaQueryWrapper<KnowledgeQa>()
                .in(KnowledgeQa::getDocId, docIds));
        if (rows.isEmpty()) return 0;
        List<Document> vectors = new ArrayList<>();
        for (KnowledgeQa qa : rows) {
            Map<String, Object> md = new HashMap<>();
            md.put("docId", qa.getDocId());
            md.put("knowledgeId", qa.getKnowledgeId());
            md.put("title", qa.getQuestion());
            md.put("kind", "qa");
            vectors.add(new Document(qa.getId(), qa.getQuestion(), md));
        }
        int batchSize = 50;
        int ok = 0;
        for (int i = 0; i < vectors.size(); i += batchSize) {
            List<Document> batch = vectors.subList(i, Math.min(i + batchSize, vectors.size()));
            try {
                target.add(batch);
                ok += batch.size();
            } catch (Exception e) {
                log.warn("[FAIL-LOUD] [QA-Reembed] 问答对向量恢复失败 {}-{}（可重解析补齐）: {}",
                        i + 1, i + batch.size(), e.getMessage());
            }
        }
        return ok;
    }
}
