package com.wisesoft.ai.config;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型归属迁移（一次性、幂等）：把退役的全局模型设置下沉到使用者，对齐"业务模型归知识库"的归属模型。
 * <p>
 * 在 {@link SchemaMigrator}（ApplicationRunner，补列）之后执行（ApplicationReadyEvent 晚于所有 Runner）：
 * <ol>
 *   <li><b>文档归库</b>：kb_id 为空的历史文档一次性 UPDATE 进默认库——此后 kb_id 必填，
 *       代码里的"空=默认库"特殊分支全部移除；</li>
 *   <li><b>向量模型回填</b>：embedding_ref 为空的库回填退役前的全局 embedding.model
 *       （引用或遗留纯模型名，遗留名经 DynamicEmbeddingModel 走遗留网关，行为与"跟随全局"一致）；
 *       仅对本次回填的库触发按库重嵌入（向量从共享 ai-doc-index 迁到独立 ai-doc-kb-{kbId}）；
 *       幂等性天然成立：回填后 ref 非空，下次启动不再命中；</li>
 *   <li><b>重排模型回填</b>：全局 rerank.model 并进每个库的 queryParams["rerank.model"]（键已存在则跳过），行为不变；</li>
 *   <li><b>视觉模型回填</b>：全局 vision.model 并进每个库的 parse_params["visionRef"]（键已存在则跳过），
 *       文档解析图片描述行为不变；</li>
 *   <li><b>退役键清理</b>：删除 c_ai_config 中的 embedding.model / vision.model / rerank.model 孤儿行。</li>
 * </ol>
 * 全局值本身就空时：向量模型回填跳过并 FAIL-LOUD 告警（该库检索不可用，需在知识库管理手动绑定）；
 * rerank/vision 回填跳过（重排不执行、解析不描述图片，均为安全的降级行为）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelOwnershipMigrator {

    static final String KEY_EMBEDDING = "embedding.model";
    static final String KEY_RERANK = "rerank.model";
    static final String KEY_VISION = "vision.model";

    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeBaseService kbService;
    private final DocumentService documentService;

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            String globalEmbedding = readConfig(KEY_EMBEDDING);
            String globalRerank = readConfig(KEY_RERANK);
            String globalVision = readConfig(KEY_VISION);

            int moved = moveDocsToDefaultKb();
            int embeddingBackfilled = 0;
            int rerankBackfilled = 0;
            int visionBackfilled = 0;
            if (kbTableNeedsManualRename()) {
                // 存量库主键列还是 id（schema.sql 已改用 kb_id，SchemaMigrator 补了一个空的 kb_id 列）：
                // 此时按 kb_id 读写知识库全是空值，回填/重嵌入都会落空——给出明确手工迁移指引并跳过
                log.error("[FAIL-LOUD] [Migrate] c_ai_knowledge_base 主键列尚未重命名（id 与 kb_id 并存），"
                        + "知识库模型归属迁移已跳过。请先执行：ALTER TABLE `c_ai_knowledge_base` "
                        + "CHANGE COLUMN `id` `kb_id` VARCHAR(50) NOT NULL COMMENT '知识库ID'，重启后自动续跑");
            } else {
                LinkedHashMap<String, String> reembedTasks = new LinkedHashMap<>();
                embeddingBackfilled = backfillEmbeddingRefs(globalEmbedding, reembedTasks);
                rerankBackfilled = backfillJsonField("query_params", "rerank.model", globalRerank, "重排模型");
                visionBackfilled = backfillJsonField("parse_params", "visionRef", globalVision, "视觉模型");
                if (!reembedTasks.isEmpty()) {
                    documentService.reembedKbsSequentialAsync(reembedTasks);
                }
            }
            deleteRetiredKeys();

            log.info("[Migrate] 模型归属迁移完成: 文档归库 {}，向量模型回填 {} 库，重排回填 {} 库，视觉回填 {} 库",
                    moved, embeddingBackfilled, rerankBackfilled, visionBackfilled);
        } catch (Exception e) {
            log.warn("[Migrate] 模型归属迁移失败（不阻塞启动，下轮启动重试）: {}", e.getMessage(), e);
        }
    }

    /**
     * 存量库主键列未重命名的检测：c_ai_knowledge_base 同时存在 id 与 kb_id 两列
     * （SchemaMigrator 只补列不改名，老库会得到一个空 kb_id 列，数据仍在 id 里）。
     */
    private boolean kbTableNeedsManualRename() {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'c_ai_knowledge_base' " +
                            "AND COLUMN_NAME IN ('id', 'kb_id')", Integer.class);
            return n != null && n >= 2;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读退役前的全局配置值（直查 DB，不依赖缓存状态）；空返回空串 */
    private String readConfig(String key) {
        try {
            return jdbcTemplate.query(
                    "SELECT config_value FROM c_ai_config WHERE config_key = ?",
                    rs -> rs.next() ? (rs.getString(1) == null ? "" : rs.getString(1).trim()) : "");
        } catch (Exception e) {
            log.warn("[Migrate] 读取配置 {} 失败（按空处理）: {}", key, e.getMessage());
            return "";
        }
    }

    /** kb_id 为空的历史文档归入默认库；返回迁移条数 */
    private int moveDocsToDefaultKb() {
        String defaultKbId = kbService.defaultId();
        Integer orphans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM c_ai_document WHERE kb_id IS NULL OR kb_id = ''", Integer.class);
        if (orphans == null || orphans == 0) return 0;
        jdbcTemplate.update("UPDATE c_ai_document SET kb_id = ? WHERE kb_id IS NULL OR kb_id = ''", defaultKbId);
        log.info("[Migrate] {} 个历史文档（kb_id 为空）已归入默认知识库 {}", orphans, defaultKbId);
        return orphans;
    }

    /** 空 embedding_ref 的库回填全局向量模型；回填结果写入 reembedTasks（kbId → 新引用）。返回回填条数 */
    private int backfillEmbeddingRefs(String globalEmbedding, LinkedHashMap<String, String> reembedTasks) {
        List<Map<String, Object>> empties = jdbcTemplate.queryForList(
                "SELECT kb_id, name FROM c_ai_knowledge_base WHERE deleted = 0 " +
                        "AND (embedding_ref IS NULL OR embedding_ref = '')");
        if (empties.isEmpty()) return 0;
        if (globalEmbedding == null || globalEmbedding.isBlank()) {
            log.warn("[FAIL-LOUD] [Migrate] {} 个知识库未绑定向量模型，且退役的全局 embedding.model 为空，"
                    + "这些库的向量检索不可用——请在知识库管理中逐个绑定", empties.size());
            return 0;
        }
        int n = 0;
        for (Map<String, Object> row : empties) {
            String kbId = String.valueOf(row.get("kb_id"));
            String name = String.valueOf(row.get("name"));
            try {
                jdbcTemplate.update("UPDATE c_ai_knowledge_base SET embedding_ref = ? WHERE kb_id = ?",
                        globalEmbedding, kbId);
                reembedTasks.put(kbId, globalEmbedding);
                n++;
                log.info("[Migrate] 知识库「{}」({}) 向量模型已回填: {}", name, kbId, globalEmbedding);
            } catch (Exception e) {
                log.warn("[Migrate] 知识库「{}」({}) 向量模型回填失败: {}", name, kbId, e.getMessage());
            }
        }
        return n;
    }

    /**
     * 把退役的全局模型值并进知识库 JSON 字段（query_params → "rerank.model"；parse_params → "visionRef"）。
     * 键已存在的库跳过（尊重库上已显式配置的值）；全局值为空时全部跳过。返回回填条数。
     */
    private int backfillJsonField(String column, String jsonKey, String globalValue, String label) {
        if (globalValue == null || globalValue.isBlank()) {
            log.info("[Migrate] 全局 {} 为空，跳过 {}（{}）回填（相关能力按未配置降级）", label, column, jsonKey);
            return 0;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT kb_id, `" + column + "` AS v FROM c_ai_knowledge_base WHERE deleted = 0");
        int n = 0;
        for (Map<String, Object> row : rows) {
            String kbId = String.valueOf(row.get("kb_id"));
            Object rawObj = row.get("v");
            String raw = rawObj == null ? "" : String.valueOf(rawObj);
            JSONObject json;
            try {
                json = raw.isBlank() ? new JSONObject() : JSONObject.parseObject(raw);
            } catch (Exception e) {
                log.warn("[Migrate] 知识库 {} 的 {} 不是合法 JSON，跳过 {} 回填: {}", kbId, column, label, e.getMessage());
                continue;
            }
            if (json.containsKey(jsonKey)) continue;
            json.put(jsonKey, globalValue);
            try {
                jdbcTemplate.update("UPDATE c_ai_knowledge_base SET `" + column + "` = ? WHERE kb_id = ?",
                        json.toJSONString(), kbId);
                n++;
            } catch (Exception e) {
                log.warn("[Migrate] 知识库 {} 回写 {} 失败: {}", kbId, column, e.getMessage());
            }
        }
        return n;
    }

    /** 删除退役的全局模型配置行（孤儿数据清理；失败仅告警） */
    private void deleteRetiredKeys() {
        for (String key : new String[]{KEY_EMBEDDING, KEY_RERANK, KEY_VISION}) {
            try {
                jdbcTemplate.update("DELETE FROM c_ai_config WHERE config_key = ?", key);
            } catch (Exception e) {
                log.warn("[Migrate] 清理退役配置 {} 失败（无害，可手工删除）: {}", key, e.getMessage());
            }
        }
    }
}
