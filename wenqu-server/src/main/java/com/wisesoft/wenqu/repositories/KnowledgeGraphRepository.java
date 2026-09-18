package com.wisesoft.wenqu.repositories;

import com.wisesoft.wenqu.common.DateTimeUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识图谱仓储。
 *
 * <p>由参考实现的 repositories/knowledge_graph_repository.py 逐方法翻译：向量记录的状态机与
 * 退避重试策略、认领时的租约与锁令牌、完成判定的存在性条件、提及删除后的孤儿清理、
 * 以及 upsert 时的冲突处理字段。
 *
 * <p>必要替换（方言与框架差异，逐条标注）：
 * <ul>
 *   <li>认领的行锁：参考实现 {@code with_for_update(skip_locked=True)} → MySQL 的
 *       {@code FOR UPDATE SKIP LOCKED}（需 MySQL 8.0+）。
 *   <li>upsert：参考实现用 PostgreSQL 的 {@code ON CONFLICT ... DO UPDATE}；
 *       MySQL 用 {@code ON DUPLICATE KEY UPDATE} / {@code INSERT IGNORE}。
 *       **一处语义差异**：参考实现只在指定冲突目标（entity_id / triple_id）上更新，
 *       同一实体标识（kb_id+normalized_name+label）冲突会报错；MySQL 会在任一唯一键冲突时
 *       执行更新。本系统保留参考实现的唯一约束，冲突时以更新处理（更宽松，不会中断抽取向导）。
 *   <li>认领时的计数递增：参考实现先读记录再在应用层 +1；本实现用
 *       {@code COALESCE(vector_attempt_count, 0) + 1} 在同一 UPDATE 内完成，最终状态一致。
 *   <li>时间来源：参考实现的 {@code func.now()} 为本层 SQL 的 {@code CURRENT_TIMESTAMP}。
 *   <li>集合类查询（存在性子查询、孤儿清理、按状态聚合）用原生 SQL，查询仍在本仓储内。
 * </ul>
 */
@Repository
public class KnowledgeGraphRepository {

    /** 向量化最大尝试次数。 */
    public static final int VECTOR_MAX_ATTEMPTS = 3;

    private static final String ENTITY = "knowledge_graph_entities";
    private static final String TRIPLE = "knowledge_graph_triples";
    private static final String ENTITY_MENTION = "knowledge_graph_entity_mentions";
    private static final String TRIPLE_MENTION = "knowledge_graph_triple_mentions";

    /** 实体表可写列（位识别与冲突更新用）。 */
    private static final Set<String> ENTITY_COLUMNS =
            Set.of(
                    "entity_id",
                    "kb_id",
                    "normalized_name",
                    "label",
                    "name",
                    "attributes",
                    "vector_status",
                    "vector_attempt_count",
                    "vector_last_error",
                    "vector_next_retry_at",
                    "vector_locked_until",
                    "vector_lock_token",
                    "created_at",
                    "updated_at");

    /** 三元组表可写列。 */
    private static final Set<String> TRIPLE_COLUMNS =
            Set.of(
                    "triple_id",
                    "kb_id",
                    "source_entity_id",
                    "target_entity_id",
                    "relation_type",
                    "content",
                    "vector_status",
                    "vector_attempt_count",
                    "vector_last_error",
                    "vector_next_retry_at",
                    "vector_locked_until",
                    "vector_lock_token",
                    "created_at",
                    "updated_at");

    private final JdbcTemplate jdbc;

    public KnowledgeGraphRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 返回 (实体数, 三元组数)。 */
    public long[] countByKbId(String kbId) {
        Long entityCount = jdbc.queryForObject("SELECT COUNT(*) FROM " + ENTITY + " WHERE kb_id = ?", Long.class, kbId);
        Long tripleCount = jdbc.queryForObject("SELECT COUNT(*) FROM " + TRIPLE + " WHERE kb_id = ?", Long.class, kbId);
        return new long[] {entityCount == null ? 0 : entityCount, tripleCount == null ? 0 : tripleCount};
    }

    /** 向量化状态分布（实体与三元组合并计数）。 */
    public Map<String, Integer> countVectorStatusesByKbId(String kbId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("pending", 0);
        counts.put("processing", 0);
        counts.put("indexed", 0);
        counts.put("failed", 0);
        for (String table : List.of(ENTITY, TRIPLE)) {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT vector_status AS status, COUNT(*) AS cnt FROM "
                                    + table
                                    + " WHERE kb_id = ? GROUP BY vector_status",
                            kbId);
            for (Map<String, Object> row : rows) {
                String status = String.valueOf(row.get("status"));
                int current = counts.getOrDefault(status, 0);
                counts.put(status, current + ((Number) row.get("cnt")).intValue());
            }
        }
        return counts;
    }

    /** 认领结果：锁令牌 + 待向量化的记录载荷。 */
    public record ClaimResult(String token, List<Map<String, Object>> payloads) {}

    /**
     * 按租约认领待向量化记录。
     *
     * <p>可认领条件：待处理且（无下次重试时间或已到）；或处理中但租约已过期。
     */
    @Transactional
    public ClaimResult claimVectorRecords(String kbId, String recordType, int limit, int leaseSeconds) {
        String table = tableOf(recordType);
        String idField = idFieldOf(recordType);
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        String sql =
                "SELECT * FROM "
                        + table
                        + " WHERE kb_id = ? AND ((vector_status = 'pending' AND (vector_next_retry_at IS NULL OR vector_next_retry_at <= ?)) "
                        + "OR (vector_status = 'processing' AND vector_locked_until < ?)) "
                        + "ORDER BY id ASC LIMIT "
                        + Math.max(limit, 1)
                        + " FOR UPDATE SKIP LOCKED";
        List<Map<String, Object>> records = jdbc.queryForList(sql, kbId, now, now);
        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime lockedUntil = now.plusSeconds(leaseSeconds);
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (Map<String, Object> record : records) {
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET vector_status = 'processing', vector_attempt_count = COALESCE(vector_attempt_count, 0) + 1, "
                            + "vector_locked_until = ?, vector_lock_token = ?, vector_last_error = NULL WHERE id = ?",
                    lockedUntil,
                    token,
                    record.get("id"));
            payloads.add(vectorPayload(recordType, record, idField));
        }
        return new ClaimResult(token, payloads);
    }

    /** 标记指定记录已索引（仅限持有同一锁令牌的记录）。 */
    public void markVectorRecordsIndexed(String recordType, List<String> recordIds, String lockToken) {
        if (recordIds == null || recordIds.isEmpty()) {
            return;
        }
        String table = tableOf(recordType);
        String idField = idFieldOf(recordType);
        jdbc.update(
                "UPDATE "
                        + table
                        + " SET vector_status = 'indexed', vector_last_error = NULL, vector_next_retry_at = NULL, "
                        + "vector_locked_until = NULL, vector_lock_token = NULL WHERE "
                        + idField
                        + " IN ("
                        + placeholders(recordIds.size())
                        + ") AND vector_lock_token = ?",
                buildArgs(recordIds, lockToken));
    }

    /**
     * 标记指定记录失败：未达尝试上限回到待处理，否则置为失败；
     * 下次重试时间按尝试次数退避（1 次→5 秒后，2 次→30 秒后，其后不再自动重试）。
     */
    public void markVectorRecordsFailed(String recordType, List<String> recordIds, String lockToken, String error) {
        if (recordIds == null || recordIds.isEmpty()) {
            return;
        }
        String table = tableOf(recordType);
        String idField = idFieldOf(recordType);
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        String truncated = error == null ? null : error.substring(0, Math.min(error.length(), 4000));
        String sql =
                "UPDATE "
                        + table
                        + " SET vector_status = CASE WHEN vector_attempt_count >= "
                        + VECTOR_MAX_ATTEMPTS
                        + " THEN 'failed' ELSE 'pending' END, vector_last_error = ?, "
                        + "vector_next_retry_at = CASE WHEN vector_attempt_count = 1 THEN ? "
                        + "WHEN vector_attempt_count = 2 THEN ? ELSE NULL END, "
                        + "vector_locked_until = NULL, vector_lock_token = NULL WHERE "
                        + idField
                        + " IN ("
                        + placeholders(recordIds.size())
                        + ") AND vector_lock_token = ?";
        List<Object> args = new ArrayList<>();
        args.add(truncated);
        args.add(now.plusSeconds(5));
        args.add(now.plusSeconds(30));
        args.addAll(recordIds);
        args.add(lockToken);
        jdbc.update(sql, args.toArray());
    }

    /**
     * 收口：结构已索引且其提及的实体、三元组全部完成向量化的知识块，标记为图谱已索引。
     *
     * @return 受影响行数
     */
    @Transactional
    public int finalizeGraphIndexedChunks(String kbId) {
        String sql =
                "UPDATE knowledge_chunks c SET c.graph_indexed = TRUE "
                        + "WHERE c.kb_id = ? AND c.graph_structure_indexed = TRUE "
                        + "AND (c.graph_indexed IS NULL OR c.graph_indexed = FALSE) "
                        + "AND NOT EXISTS (SELECT 1 FROM "
                        + ENTITY_MENTION
                        + " m JOIN "
                        + ENTITY
                        + " e ON e.entity_id = m.entity_id "
                        + "WHERE m.chunk_id = c.chunk_id AND e.vector_status <> 'indexed') "
                        + "AND NOT EXISTS (SELECT 1 FROM "
                        + TRIPLE_MENTION
                        + " tm JOIN "
                        + TRIPLE
                        + " t ON t.triple_id = tm.triple_id "
                        + "WHERE tm.chunk_id = c.chunk_id AND t.vector_status <> 'indexed')";
        return jdbc.update(sql, kbId);
    }

    /**
     * 重置向量记录状态。
     *
     * @param allVectors true 时重置该库全部记录并同时清空知识块的图谱索引标记；false 时只重置失败或租约过期的。
     * @return 受影响行数
     */
    @Transactional
    public int reconcileVectorRecords(String kbId, boolean allVectors) {
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        int total = 0;
        for (String table : List.of(ENTITY, TRIPLE)) {
            String condition = "kb_id = ?";
            List<Object> args = new ArrayList<>();
            args.add(kbId);
            if (!allVectors) {
                condition += " AND (vector_status = 'failed' OR (vector_status = 'processing' AND vector_locked_until < ?))";
                args.add(now);
            }
            total +=
                    jdbc.update(
                            "UPDATE "
                                    + table
                                    + " SET vector_status = 'pending', vector_attempt_count = 0, vector_last_error = NULL, "
                                    + "vector_next_retry_at = NULL, vector_locked_until = NULL, vector_lock_token = NULL WHERE "
                                    + condition,
                            args.toArray());
        }
        if (allVectors) {
            jdbc.update("UPDATE knowledge_chunks SET graph_indexed = FALSE WHERE kb_id = ?", kbId);
        }
        return total;
    }

    /**
     * 写入单个知识块的图谱抽取结果（实体、三元组与它们的提及）。
     *
     * <p>实体与三元组按唯一键 upsert；提及按唯一键去重插入。
     */
    @Transactional
    public void upsertChunkGraph(
            String kbId,
            String fileId,
            String chunkId,
            List<Map<String, Object>> entities,
            List<Map<String, Object>> triples) {
        if (entities != null && !entities.isEmpty()) {
            List<Map<String, Object>> entityRows = new ArrayList<>();
            for (Map<String, Object> entity : entities) {
                Map<String, Object> row = new LinkedHashMap<>(entity);
                row.remove("content");
                RepoValues.fillTimestamps(row);
                entityRows.add(row);
            }
            List<String> entityColumns = unionColumns(entityRows, ENTITY_COLUMNS, ENTITY);
            jdbc.update(
                    buildUpsertSql(ENTITY, entityColumns, "name = VALUES(name), attributes = VALUES(attributes)"),
                    buildUpsertArgs(entityRows, entityColumns));

            List<Map<String, Object>> mentions = new ArrayList<>();
            for (Map<String, Object> entity : entities) {
                Map<String, Object> mention = new LinkedHashMap<>();
                mention.put("entity_id", entity.get("entity_id"));
                mention.put("kb_id", kbId);
                mention.put("file_id", fileId);
                mention.put("chunk_id", chunkId);
                // 提及表只有 created_at（无 updated_at）
                mention.put("created_at", DateTimeUtils.utcNowNaive());
                mentions.add(mention);
            }
            jdbc.batchUpdate(
                    buildInsertIgnoreSql(ENTITY_MENTION, ENTITY_MENTION_COLUMNS),
                    buildInsertArgs(mentions, ENTITY_MENTION_COLUMNS));
        }

        if (triples != null && !triples.isEmpty()) {
            List<Map<String, Object>> tripleRows = new ArrayList<>();
            for (Map<String, Object> triple : triples) {
                Map<String, Object> row = new LinkedHashMap<>(triple);
                row.remove("text");
                row.remove("extractor_type");
                RepoValues.fillTimestamps(row);
                tripleRows.add(row);
            }
            List<String> tripleColumns = unionColumns(tripleRows, TRIPLE_COLUMNS, TRIPLE);
            jdbc.update(
                    buildUpsertSql(
                            TRIPLE, tripleColumns, "content = VALUES(content), relation_type = VALUES(relation_type)"),
                    buildUpsertArgs(tripleRows, tripleColumns));

            List<Map<String, Object>> mentions = new ArrayList<>();
            for (Map<String, Object> triple : triples) {
                Map<String, Object> mention = new LinkedHashMap<>();
                mention.put("triple_id", triple.get("triple_id"));
                mention.put("kb_id", kbId);
                mention.put("file_id", fileId);
                mention.put("chunk_id", chunkId);
                mention.put("text", triple.get("text"));
                mention.put("extractor_type", triple.get("extractor_type"));
                mention.put("created_at", DateTimeUtils.utcNowNaive());
                mentions.add(mention);
            }
            jdbc.batchUpdate(
                    buildInsertIgnoreSql(TRIPLE_MENTION, TRIPLE_MENTION_COLUMNS),
                    buildInsertArgs(mentions, TRIPLE_MENTION_COLUMNS));
        }
    }

    /** 提及表列（写提及用）。 */
    private static final List<String> ENTITY_MENTION_COLUMNS =
            List.of("entity_id", "kb_id", "file_id", "chunk_id", "created_at");

    /** 三元组提及表列。 */
    private static final List<String> TRIPLE_MENTION_COLUMNS =
            List.of("triple_id", "kb_id", "file_id", "chunk_id", "text", "extractor_type", "created_at");

    /**
     * 删除单文件的图谱引用并回收孤儿。
     *
     * <p>返回被回收的孤儿实体 id 与孤儿三元组 id（对应参考实现的二元组返回值）。
     */
    public record DeleteReferencesResult(List<String> orphanEntityIds, List<String> orphanTripleIds) {}

    @Transactional
    public DeleteReferencesResult deleteFileReferences(String fileId) {
        List<String> affectedEntityIds =
                jdbc.queryForList(
                        "SELECT DISTINCT entity_id FROM " + ENTITY_MENTION + " WHERE file_id = ?",
                        String.class,
                        fileId);
        List<String> affectedTripleIds =
                jdbc.queryForList(
                        "SELECT DISTINCT triple_id FROM " + TRIPLE_MENTION + " WHERE file_id = ?",
                        String.class,
                        fileId);

        jdbc.update("DELETE FROM " + TRIPLE_MENTION + " WHERE file_id = ?", fileId);
        jdbc.update("DELETE FROM " + ENTITY_MENTION + " WHERE file_id = ?", fileId);

        List<String> orphanTripleIds = new ArrayList<>();
        if (!affectedTripleIds.isEmpty()) {
            orphanTripleIds =
                    jdbc.queryForList(
                            "SELECT t.triple_id FROM "
                                    + TRIPLE
                                    + " t WHERE t.triple_id IN ("
                                    + placeholders(affectedTripleIds.size())
                                    + ") AND NOT EXISTS (SELECT 1 FROM "
                                    + TRIPLE_MENTION
                                    + " tm WHERE tm.triple_id = t.triple_id)",
                            String.class,
                            affectedTripleIds.toArray());
            if (!orphanTripleIds.isEmpty()) {
                jdbc.update(
                        "DELETE FROM " + TRIPLE + " WHERE triple_id IN (" + placeholders(orphanTripleIds.size()) + ")",
                        orphanTripleIds.toArray());
            }
        }

        List<String> orphanEntityIds = new ArrayList<>();
        if (!affectedEntityIds.isEmpty()) {
            orphanEntityIds =
                    jdbc.queryForList(
                            "SELECT e.entity_id FROM "
                                    + ENTITY
                                    + " e WHERE e.entity_id IN ("
                                    + placeholders(affectedEntityIds.size())
                                    + ") AND NOT EXISTS (SELECT 1 FROM "
                                    + ENTITY_MENTION
                                    + " m WHERE m.entity_id = e.entity_id) "
                                    + "AND NOT EXISTS (SELECT 1 FROM "
                                    + TRIPLE
                                    + " t WHERE t.source_entity_id = e.entity_id OR t.target_entity_id = e.entity_id)",
                            String.class,
                            affectedEntityIds.toArray());
            if (!orphanEntityIds.isEmpty()) {
                jdbc.update(
                        "DELETE FROM " + ENTITY + " WHERE entity_id IN (" + placeholders(orphanEntityIds.size()) + ")",
                        orphanEntityIds.toArray());
            }
        }

        return new DeleteReferencesResult(orphanEntityIds, orphanTripleIds);
    }

    /** 删除单知识库的全部图谱数据（提及 → 三元组 → 实体）。 */
    @Transactional
    public void deleteByKbId(String kbId) {
        jdbc.update("DELETE FROM " + TRIPLE_MENTION + " WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM " + ENTITY_MENTION + " WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM " + TRIPLE + " WHERE kb_id = ?", kbId);
        jdbc.update("DELETE FROM " + ENTITY + " WHERE kb_id = ?", kbId);
    }

    // ==================== 内部工具 ====================

    private static String tableOf(String recordType) {
        return switch (recordType) {
            case "entity" -> ENTITY;
            case "triple" -> TRIPLE;
            default -> throw new IllegalArgumentException("Unsupported graph vector record type: " + recordType);
        };
    }

    private static String idFieldOf(String recordType) {
        return switch (recordType) {
            case "entity" -> "entity_id";
            case "triple" -> "triple_id";
            default -> throw new IllegalArgumentException("Unsupported graph vector record type: " + recordType);
        };
    }

    /** 向量化载荷：实体用规范化名称，三元组用内容并附带两端实体 id。 */
    private static Map<String, Object> vectorPayload(String recordType, Map<String, Object> record, String idField) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.get(idField));
        payload.put("content", "entity".equals(recordType) ? record.get("normalized_name") : record.get("content"));
        if ("triple".equals(recordType)) {
            payload.put("source_id", record.get("source_entity_id"));
            payload.put("target_id", record.get("target_entity_id"));
        }
        return payload;
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }

    private static Object[] buildArgs(List<String> ids, String lockToken) {
        List<Object> args = new ArrayList<>(ids);
        args.add(lockToken);
        return args.toArray();
    }

    private static void validateColumns(Map<String, Object> row, Set<String> allowed, String table) {
        for (String key : row.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unsupported column for " + table + ": " + key);
            }
        }
    }

    /** upsert 语句：冲突时按参考实现指定的字段更新。 */
    private String buildUpsertSql(String table, List<String> columns, String updateClause) {
        return "INSERT INTO "
                + table
                + " ("
                + String.join(", ", columns)
                + ") VALUES ("
                + placeholders(columns.size())
                + ") ON DUPLICATE KEY UPDATE "
                + updateClause
                + ", updated_at = CURRENT_TIMESTAMP";
    }

    /**
     * 计算多行插入的列并集（按首次出现顺序）。
     *
     * <p>参考实现用 ORM 的批量插入，缺失的键按 NULL 落库；此处显式做并集以保证每一行的
     * 值顺序与列顺序一致。
     */
    private static List<String> unionColumns(List<Map<String, Object>> rows, Set<String> allowed, String table) {
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!allowed.contains(key)) {
                    throw new IllegalArgumentException("Unsupported column for " + table + ": " + key);
                }
                if (!columns.contains(key)) {
                    columns.add(key);
                }
            }
        }
        return columns;
    }

    private static Object[] buildUpsertArgs(List<Map<String, Object>> rows, List<String> columns) {
        List<Object> args = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            for (String column : columns) {
                args.add(row.get(column));
            }
        }
        return args.toArray();
    }

    private static String buildInsertIgnoreSql(String table, List<String> columns) {
        return "INSERT IGNORE INTO "
                + table
                + " ("
                + String.join(", ", columns)
                + ") VALUES ("
                + placeholders(columns.size())
                + ")";
    }

    private static List<Object[]> buildInsertArgs(List<Map<String, Object>> rows, List<String> columns) {
        List<Object[]> batch = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                values[i] = row.get(columns.get(i));
            }
            batch.add(values);
        }
        return batch;
    }
}
