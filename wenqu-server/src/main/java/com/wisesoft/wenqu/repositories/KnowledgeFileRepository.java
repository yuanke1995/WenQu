package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.KnowledgeFile;
import com.wisesoft.wenqu.models.TaskRecord;
import com.wisesoft.wenqu.repository.port.KnowledgeFileMapper;
import com.wisesoft.wenqu.repository.port.TaskRecordMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识文件仓储。
 *
 * <p>由参考实现的 repositories/knowledge_file_repository.py 逐方法翻译：可写字段白名单、
 * 排序与分页口径、状态筛选别名（indexed→indexed/done、error_indexing→error_indexing/failed）、
 * 路径前缀规范化与越界拒绝、虚拟文件夹目录视图、统计聚合的九个字段及其 TTL 缓存。
 *
 * <p>必要替换（方言与框架差异，逐条标注）：
 * <ul>
 *   <li>目录树串行化锁：参考实现用 PostgreSQL 的事务级建议锁
 *       {@code pg_advisory_xact_lock(hashtext(kb_id))}；MySQL 无事务级建议锁，改用会话级命名锁
 *       {@code GET_LOCK/RELEASE_LOCK}，作用范围从"事务"变为"会话"，故在方法内成对释放。
 *   <li>字符串函数：{@code split_part} → {@code SUBSTRING_INDEX}、
 *       {@code strpos} → {@code LOCATE}、{@code literal+} 拼接 → {@code CONCAT}。
 *   <li>空值排序：{@code NULLS LAST} MySQL 不支持，用 {@code created_at IS NULL} 作为首个排序键。
 *   <li>{@code UPDATE ... RETURNING} MySQL 不支持，改为更新后回读。
 *   <li>目录视图的联合查询无法用条件构造器表达，改用原生 SQL（查询仍在本仓储内，
 *       与参考实现"仓储持有查询"的分层一致）。
 *   <li>数据库当前时间：{@code timezone('utc', clock_timestamp())} → {@code UTC_TIMESTAMP()}。
 *   <li>统计缓存键前缀取本系统命名空间（前缀标识本系统在共享 Redis 中的键空间）。
 * </ul>
 */
@Repository
public class KnowledgeFileRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFileRepository.class);

    /** 分批大小：参考实现按单条 SQL 参数上限设定。 */
    public static final int SQL_IN_BATCH_SIZE = 10_000;

    /** 文件统计聚合缓存 TTL（秒）。 */
    public static final long KB_FILE_STATS_CACHE_TTL = 10;

    /** 文件统计缓存键前缀（必要替换：本系统命名空间）。 */
    private static final String KB_FILE_STATS_CACHE_PREFIX = "wenqu:kb_file_stats:";

    /** 虚拟文件夹在目录视图中的 id 前缀。 */
    private static final String VIRTUAL_FOLDER_ID_PREFIX = "__virtual_folder__:";

    /** 可写字段白名单。 */
    private static final Set<String> WRITABLE_FIELDS =
            Set.of(
                    "kb_id",
                    "parent_id",
                    "filename",
                    "original_filename",
                    "file_type",
                    "path",
                    "minio_url",
                    "markdown_file",
                    "status",
                    "content_hash",
                    "file_size",
                    "chunk_count",
                    "token_count",
                    "content_type",
                    "processing_params",
                    "is_folder",
                    "error_message",
                    "processing_task_id",
                    "processing_owner",
                    "created_by",
                    "updated_by");

    private final KnowledgeFileMapper fileMapper;
    private final TaskRecordMapper taskMapper;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;

    public KnowledgeFileRepository(
            KnowledgeFileMapper fileMapper,
            TaskRecordMapper taskMapper,
            StringRedisTemplate redis,
            JdbcTemplate jdbc) {
        this.fileMapper = fileMapper;
        this.taskMapper = taskMapper;
        this.redis = redis;
        this.jdbc = jdbc;
    }

    /**
     * 按知识库串行化目录树结构修改。
     *
     * <p>必要性替换：MySQL 无事务级建议锁，改用会话级命名锁并在方法结束时释放。
     */
    public AutoCloseable lockFileTree(String kbId) {
        String lockName = "wenqu:file_tree:" + Math.abs((long) kbId.hashCode());
        Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 10)", Integer.class, lockName);
        boolean held = acquired != null && acquired == 1;
        if (!held) {
            log.warn("目录树锁等待超时（继续执行）: kb_id={}", kbId);
        }
        return () -> {
            if (held) {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
            }
        };
    }

    /** 检测仍以相对路径保存的历史文件记录。 */
    public Map<String, Object> detectVirtualFolderData(String kbId) {
        String sql =
                "SELECT COUNT(file_id) AS file_count, "
                        + "COALESCE(SUM(CHAR_LENGTH(filename) - CHAR_LENGTH(REPLACE(filename, '/', ''))), 0) "
                        + "AS remaining_steps FROM knowledge_files "
                        + "WHERE kb_id = ? AND (is_folder = 0 OR is_folder IS NULL) AND filename LIKE '%/%'";
        Map<String, Object> row = jdbc.queryForMap(sql, kbId);
        long count = row.get("file_count") == null ? 0 : ((Number) row.get("file_count")).longValue();
        long steps = row.get("remaining_steps") == null ? 0 : ((Number) row.get("remaining_steps")).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("has_virtual_folders", count > 0);
        result.put("file_count", count);
        result.put("remaining_steps", steps);
        return result;
    }

    /**
     * 在调用方拥有的任务事务内迁移一批路径。
     *
     * <p>返回 scanned / processed / created_folders / conflict_file_ids / last_file_id。
     */
    @Transactional
    public Map<String, Object> migrateVirtualFolderBatch(
            String kbId, String operatorId, String afterFileId, int batchSize) {
        LambdaQueryWrapper<KnowledgeFile> filters =
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getKbId, kbId)
                        .and(w -> w.eq(KnowledgeFile::getIsFolder, false).or().isNull(KnowledgeFile::getIsFolder))
                        .like(KnowledgeFile::getFilename, "/");
        if (afterFileId != null && !afterFileId.isEmpty()) {
            filters.gt(KnowledgeFile::getFileId, afterFileId);
        }
        filters.orderByAsc(KnowledgeFile::getFileId).last("LIMIT " + batchSize);
        List<KnowledgeFile> records = fileMapper.selectList(filters);
        if (records.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("scanned", 0);
            empty.put("processed", 0);
            empty.put("created_folders", 0);
            empty.put("conflict_file_ids", new ArrayList<String>());
            return empty;
        }

        Map<String, List<KnowledgeFile>> groups = new LinkedHashMap<>();
        List<String> conflictFileIds = new ArrayList<>();
        for (KnowledgeFile record : records) {
            String filename = record.getFilename() == null ? "" : record.getFilename();
            int split = filename.indexOf('/');
            String segment = split < 0 ? filename : filename.substring(0, split);
            String remainder = split < 0 ? "" : filename.substring(split + 1);
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || remainder.isEmpty()) {
                conflictFileIds.add(record.getFileId());
                continue;
            }
            String key = (record.getParentId() == null ? "\u0000" : record.getParentId()) + "\u0001" + segment;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }

        int processed = 0;
        int createdFolders = 0;
        for (Map.Entry<String, List<KnowledgeFile>> entry : groups.entrySet()) {
            String[] parts = entry.getKey().split("\u0001", -1);
            String parentId = "\u0000".equals(parts[0]) ? null : parts[0];
            String segment = parts[1];
            List<KnowledgeFile> group = entry.getValue();

            List<KnowledgeFile> siblings =
                    fileMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeFile>()
                                    .eq(KnowledgeFile::getKbId, kbId)
                                    .eq(parentId != null, KnowledgeFile::getParentId, parentId)
                                    .isNull(parentId == null, KnowledgeFile::getParentId)
                                    .eq(KnowledgeFile::getFilename, segment));
            KnowledgeFile folder;
            if (siblings.size() == 1 && Boolean.TRUE.equals(siblings.get(0).getIsFolder())) {
                folder = siblings.get(0);
            } else if (!siblings.isEmpty()) {
                for (KnowledgeFile record : group) {
                    conflictFileIds.add(record.getFileId());
                }
                continue;
            } else {
                folder = new KnowledgeFile();
                folder.setFileId("folder-" + UUID.randomUUID());
                folder.setKbId(kbId);
                folder.setParentId(parentId);
                folder.setFilename(segment);
                folder.setPath(segment);
                folder.setFileType("folder");
                folder.setStatus("done");
                folder.setIsFolder(true);
                folder.setFileSize(0L);
                folder.setChunkCount(0);
                folder.setTokenCount(0L);
                folder.setCreatedBy(operatorId);
                folder.setCreatedAt(DateTimeUtils.utcNowNaive());
                folder.setUpdatedAt(DateTimeUtils.utcNowNaive());
                fileMapper.insert(folder);
                createdFolders++;
            }

            for (KnowledgeFile record : group) {
                record.setParentId(folder.getFileId());
                String filename = record.getFilename() == null ? "" : record.getFilename();
                record.setFilename(filename.substring(filename.indexOf('/') + 1));
                fileMapper.updateById(record);
                processed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", records.size());
        result.put("processed", processed);
        result.put("created_folders", createdFolders);
        result.put("conflict_file_ids", conflictFileIds);
        result.put("last_file_id", records.get(records.size() - 1).getFileId());
        return result;
    }

    /** 按文件类型聚合真实文件数、大小与知识块数。 */
    public List<Object[]> aggregateDashboardStats() {
        String sql =
                "SELECT file_type, COUNT(file_id) AS cnt, COALESCE(SUM(file_size), 0) AS total_size, "
                        + "COALESCE(SUM(chunk_count), 0) AS nodes FROM knowledge_files "
                        + "WHERE is_folder = 0 OR is_folder IS NULL GROUP BY file_type";
        List<Object[]> result = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            Object type = row.get("file_type");
            result.add(
                    new Object[] {
                        type == null || String.valueOf(type).isEmpty() ? "unknown" : String.valueOf(type),
                        ((Number) row.get("cnt")).longValue(),
                        ((Number) row.get("total_size")).longValue(),
                        ((Number) row.get("nodes")).longValue()
                    });
        }
        return result;
    }

    /** 全部文件记录。 */
    public List<KnowledgeFile> getAll() {
        return fileMapper.selectList(new LambdaQueryWrapper<>());
    }

    /** 按 file_id 取单个文件记录。 */
    public KnowledgeFile getByFileId(String fileId) {
        return fileMapper.selectOne(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getFileId, fileId));
    }

    /** 多文件记录，返回顺序与入参一致（缺失的跳过）。 */
    public List<KnowledgeFile> listByFileIds(List<String> fileIds) {
        List<String> normalizedIds = new ArrayList<>();
        if (fileIds != null) {
            for (String fileId : fileIds) {
                if (fileId != null && !fileId.isEmpty()) {
                    normalizedIds.add(fileId);
                }
            }
        }
        if (normalizedIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, KnowledgeFile> recordsById = new LinkedHashMap<>();
        for (List<String> batch : KnowledgeChunkRepository.iterBatches(normalizedIds)) {
            for (KnowledgeFile record :
                    fileMapper.selectList(
                            new LambdaQueryWrapper<KnowledgeFile>().in(KnowledgeFile::getFileId, batch))) {
                recordsById.put(record.getFileId(), record);
            }
        }
        List<KnowledgeFile> result = new ArrayList<>();
        for (String fileId : normalizedIds) {
            KnowledgeFile record = recordsById.get(fileId);
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }

    /** 单知识库的全部文件记录。 */
    public List<KnowledgeFile> listByKbId(String kbId) {
        return fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKbId, kbId));
    }

    /** 单知识库的文件记录，按 file_id 游标分页（limit 收敛到 1..1000）。 */
    public List<KnowledgeFile> listByKbIdAfter(String kbId, String afterFileId, int limit, boolean filesOnly) {
        LambdaQueryWrapper<KnowledgeFile> wrapper =
                new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKbId, kbId);
        if (afterFileId != null && !afterFileId.isEmpty()) {
            wrapper.gt(KnowledgeFile::getFileId, afterFileId);
        }
        if (filesOnly) {
            wrapper.eq(KnowledgeFile::getIsFolder, false);
        }
        wrapper.orderByAsc(KnowledgeFile::getFileId).last("LIMIT " + Math.min(Math.max(limit, 1), 1000));
        return fileMapper.selectList(wrapper);
    }

    /** 文件名模糊搜索（大小写不敏感，返回记录与总数）。 */
    public Map<String, Object> searchFiles(
            String kbId, String filenameQuery, Set<String> statuses, int offset, int limit, boolean filesOnly) {
        LambdaQueryWrapper<KnowledgeFile> wrapper =
                new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKbId, kbId);
        if (filesOnly) {
            wrapper.eq(KnowledgeFile::getIsFolder, false);
        }
        if (statuses != null) {
            wrapper.in(KnowledgeFile::getStatus, statuses);
        }
        String normalizedQuery = filenameQuery == null ? "" : filenameQuery.trim().toLowerCase();
        if (!normalizedQuery.isEmpty()) {
            String escaped = escapeLike(normalizedQuery);
            wrapper.apply("LOWER(filename) LIKE {0} ESCAPE '\\\\'", "%" + escaped + "%");
        }
        int normalizedOffset = Math.max(offset, 0);
        int normalizedLimit = Math.min(Math.max(limit, 1), 10_000);
        Long total = fileMapper.selectCount(wrapper);
        wrapper.orderByDesc(KnowledgeFile::getUpdatedAt).orderByAsc(KnowledgeFile::getFileId);
        wrapper.last("LIMIT " + normalizedLimit + " OFFSET " + normalizedOffset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", fileMapper.selectList(wrapper));
        result.put("total", total == null ? 0 : total);
        return result;
    }

    /** 按 file_id 批量取文件名。 */
    public Map<String, String> getFilenamesByFileIds(String kbId, List<String> fileIds) {
        List<String> normalizedIds = new ArrayList<>();
        if (fileIds != null) {
            for (String fileId : fileIds) {
                if (fileId != null && !fileId.isEmpty()) {
                    normalizedIds.add(fileId);
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        if (normalizedIds.isEmpty()) {
            return result;
        }
        List<KnowledgeFile> records =
                fileMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeFile>()
                                .eq(KnowledgeFile::getKbId, kbId)
                                .in(KnowledgeFile::getFileId, normalizedIds));
        for (KnowledgeFile record : records) {
            result.put(record.getFileId(), record.getFilename() == null ? "" : record.getFilename());
        }
        return result;
    }

    /** 直接子项（文件夹在前，名称不区分大小写升序）。 */
    public List<KnowledgeFile> listChildren(String kbId, String parentId) {
        LambdaQueryWrapper<KnowledgeFile> wrapper = new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKbId, kbId);
        applyParentCondition(wrapper, parentId);
        wrapper.orderByDesc(KnowledgeFile::getIsFolder);
        wrapper.last(", LOWER(filename) ASC");
        return fileMapper.selectList(wrapper);
    }

    /** 同名文件（排除文件夹与失败状态，按创建时间倒序）。 */
    public List<KnowledgeFile> listSameNameFiles(String kbId, String filename) {
        String normalizedFilename = filename == null ? "" : filename.trim();
        if (normalizedFilename.isEmpty()) {
            return new ArrayList<>();
        }
        return fileMapper.selectList(
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getKbId, kbId)
                        .eq(KnowledgeFile::getIsFolder, false)
                        .apply("LOWER(filename) = {0}", normalizedFilename.toLowerCase())
                        .and(w -> w.isNull(KnowledgeFile::getStatus).or().ne(KnowledgeFile::getStatus, "failed"))
                        .orderByDesc(KnowledgeFile::getCreatedAt));
    }

    /** 文件名包含关键字的文件 id 列表。 */
    public List<String> listFileIdsByFilenameContains(String kbId, String filenamePattern, int limit) {
        String normalizedPattern = filenamePattern == null ? "" : filenamePattern.replace("%", "").trim().toLowerCase();
        if (normalizedPattern.isEmpty()) {
            return new ArrayList<>();
        }
        String escaped = escapeLike(normalizedPattern);
        LambdaQueryWrapper<KnowledgeFile> wrapper =
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getKbId, kbId)
                        .eq(KnowledgeFile::getIsFolder, false)
                        .apply("LOWER(filename) LIKE {0} ESCAPE '\\\\'", "%" + escaped + "%")
                        .orderByAsc(KnowledgeFile::getFileId)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 10_000));
        List<String> result = new ArrayList<>();
        for (KnowledgeFile record : fileMapper.selectList(wrapper)) {
            result.add(record.getFileId());
        }
        return result;
    }

    /** 内容指纹是否已存在（排除文件夹与失败状态）。 */
    public boolean existsByContentHash(String kbId, String contentHash) {
        String normalizedHash = contentHash == null ? "" : contentHash.trim();
        if (normalizedHash.isEmpty()) {
            return false;
        }
        Long count =
                fileMapper.selectCount(
                        new LambdaQueryWrapper<KnowledgeFile>()
                                .eq(KnowledgeFile::getKbId, kbId)
                                .eq(KnowledgeFile::getIsFolder, false)
                                .eq(KnowledgeFile::getContentHash, normalizedHash)
                                .and(w -> w.isNull(KnowledgeFile::getStatus).or().ne(KnowledgeFile::getStatus, "failed"))
                                .last("LIMIT 1"));
        return count != null && count > 0;
    }

    /** 全部文件记录数。 */
    public int countAll() {
        Long count = fileMapper.selectCount(new LambdaQueryWrapper<>());
        return count == null ? 0 : count.intValue();
    }

    /** 指定状态集合的文件 id 列表（limit 收敛到 1..500）。 */
    public List<String> listFileIdsByExactStatuses(String kbId, List<String> statuses, String afterFileId, int limit) {
        List<String> normalizedStatuses = new ArrayList<>();
        if (statuses != null) {
            for (String status : statuses) {
                if (status != null && !status.isEmpty()) {
                    normalizedStatuses.add(status);
                }
            }
        }
        if (normalizedStatuses.isEmpty()) {
            return new ArrayList<>();
        }
        int normalizedLimit = Math.min(Math.max(limit, 1), 500);
        LambdaQueryWrapper<KnowledgeFile> wrapper =
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getKbId, kbId)
                        .eq(KnowledgeFile::getIsFolder, false)
                        .in(KnowledgeFile::getStatus, normalizedStatuses);
        if (afterFileId != null && !afterFileId.isEmpty()) {
            wrapper.gt(KnowledgeFile::getFileId, afterFileId);
        }
        wrapper.orderByAsc(KnowledgeFile::getFileId).last("LIMIT " + normalizedLimit);
        List<String> result = new ArrayList<>();
        for (KnowledgeFile record : fileMapper.selectList(wrapper)) {
            result.add(record.getFileId());
        }
        return result;
    }

    /** 同名文件是否存在（排除失败状态）。 */
    public boolean existsByFilename(String kbId, String filename) {
        Long count =
                fileMapper.selectCount(
                        new LambdaQueryWrapper<KnowledgeFile>()
                                .eq(KnowledgeFile::getKbId, kbId)
                                .eq(KnowledgeFile::getFilename, filename)
                                .apply("is_folder IS NOT TRUE")
                                .and(w -> w.isNull(KnowledgeFile::getStatus).or().ne(KnowledgeFile::getStatus, "failed"))
                                .last("LIMIT 1"));
        return count != null && count > 0;
    }

    /** 目录列表（含虚拟文件夹视图），返回记录与总数。 */
    public Map<String, Object> listDocuments(
            String kbId,
            String parentId,
            String pathPrefix,
            String status,
            int page,
            int pageSize,
            boolean recursive,
            boolean filesOnly) {
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.min(Math.max(pageSize, 1), 500);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        String normalizedPathPrefix = normalizePathPrefix(pathPrefix);
        boolean hasStatusFilter = statusConditionPresent(status);
        boolean effectiveRecursive = recursive && hasStatusFilter;
        if (!effectiveRecursive && !hasStatusFilter) {
            return listDirectoryDocuments(
                    kbId, parentId, normalizedPathPrefix, offset, normalizedPageSize, filesOnly);
        }

        LambdaQueryWrapper<KnowledgeFile> filters = new LambdaQueryWrapper<>();
        filters.eq(KnowledgeFile::getKbId, kbId);
        if (!effectiveRecursive) {
            applyParentCondition(filters, parentId);
        }
        if (filesOnly) {
            filters.eq(KnowledgeFile::getIsFolder, false);
        }
        applyStatusCondition(filters, status);

        Long total = fileMapper.selectCount(filters);
        filters.orderByDesc(KnowledgeFile::getIsFolder);
        filters.last(", LOWER(filename) ASC, created_at DESC, file_id ASC LIMIT " + normalizedPageSize + " OFFSET " + offset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", fileMapper.selectList(filters));
        result.put("total", total == null ? 0 : total);
        return result;
    }

    /**
     * 目录视图：把以相对路径保存的历史记录按第一段路径折叠成虚拟文件夹，
     * 与真实子项合并后统一分页。
     */
    private Map<String, Object> listDirectoryDocuments(
            String kbId, String parentId, String pathPrefix, int offset, int pageSize, boolean filesOnly) {
        String remainder =
                pathPrefix == null || pathPrefix.isEmpty()
                        ? "f.filename"
                        : "SUBSTRING(f.filename, " + (pathPrefix.length() + 1) + ")";
        StringBuilder base = new StringBuilder();
        base.append("f.kb_id = ? AND f.filename IS NOT NULL AND ");
        if (parentId != null && !parentId.isEmpty()) {
            base.append("f.parent_id = ? ");
        } else {
            base.append("f.parent_id IS NULL ");
        }
        List<Object> realArgs = new ArrayList<>();
        realArgs.add(kbId);
        if (parentId != null && !parentId.isEmpty()) {
            realArgs.add(parentId);
        }
        if (pathPrefix != null && !pathPrefix.isEmpty()) {
            base.append("AND f.filename LIKE ? ESCAPE '\\\\' ");
            realArgs.add(escapeLike(pathPrefix) + "%");
        }

        String realSelect =
                "SELECT f.file_id AS file_id, "
                        + remainder
                        + " AS filename, f.file_type AS file_type, f.status AS status, "
                        + "f.created_at AS created_at, f.updated_at AS updated_at, f.file_size AS file_size, "
                        + "f.chunk_count AS chunk_count, f.token_count AS token_count, f.created_by AS created_by, "
                        + "f.is_folder AS is_folder, f.parent_id AS parent_id, f.path AS path, "
                        + "f.minio_url AS minio_url, f.markdown_file AS markdown_file, "
                        + "0 AS is_virtual_folder, NULL AS path_prefix, 0 AS virtual_children_count "
                        + "FROM knowledge_files f WHERE "
                        + base
                        + "AND "
                        + remainder
                        + " <> '' AND LOCATE('/', "
                        + remainder
                        + ") = 0";

        // 虚拟文件夹分支：先在派生表里按首段分组，再在外层投影。
        // 必要性替换：参考实现直接在同层 SELECT 里引用分组表达式并在外层拼接（PostgreSQL 允许），
        // MySQL 默认 only_full_group_by 会拒绝（错误 1055），故改为"先分组再投影"，语义不变。
        String virtualSelect =
                "SELECT CONCAT('"
                        + VIRTUAL_FOLDER_ID_PREFIX
                        + "', ?, ':', ?, g.segment, '/') AS file_id, "
                        + "g.segment AS filename, 'folder' AS file_type, 'done' AS status, "
                        + "NULL AS created_at, NULL AS updated_at, 0 AS file_size, 0 AS chunk_count, "
                        + "0 AS token_count, NULL AS created_by, 1 AS is_folder, ? AS parent_id, NULL AS path, "
                        + "NULL AS minio_url, NULL AS markdown_file, 1 AS is_virtual_folder, "
                        + "CONCAT(?, g.segment, '/') AS path_prefix, "
                        + "g.virtual_children_count AS virtual_children_count FROM (SELECT SUBSTRING_INDEX("
                        + remainder
                        + ", '/', 1) AS segment, COUNT(*) AS virtual_children_count FROM knowledge_files f WHERE "
                        + base
                        + "AND "
                        + remainder
                        + " <> '' AND LOCATE('/', "
                        + remainder
                        + ") > 0 GROUP BY SUBSTRING_INDEX("
                        + remainder
                        + ", '/', 1)) g";

        List<Object> virtualArgs = new ArrayList<>();
        virtualArgs.add(parentId == null || parentId.isEmpty() ? "root" : parentId);
        virtualArgs.add(pathPrefix == null ? "" : pathPrefix);
        virtualArgs.add(parentId);
        virtualArgs.add(pathPrefix == null ? "" : pathPrefix);
        virtualArgs.addAll(realArgs);

        String inner;
        if (filesOnly) {
            inner = realSelect + " AND f.is_folder = 0";
        } else {
            inner = realSelect + " UNION ALL " + virtualSelect;
        }
        String directorySql = "SELECT * FROM (" + inner + ") dir";

        List<Object> countArgs = filesOnly ? realArgs : mergeArgs(realArgs, virtualArgs);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + inner + ") dir", Long.class, countArgs.toArray());
        String pagedSql =
                directorySql
                        + " ORDER BY dir.is_folder DESC, LOWER(dir.filename) ASC, "
                        + "dir.created_at IS NULL, dir.created_at DESC, dir.file_id ASC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(countArgs);
        pageArgs.add(pageSize);
        pageArgs.add(offset);

        List<Map<String, Object>> rows = jdbc.queryForList(pagedSql, pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", rows);
        result.put("total", total == null ? 0L : total);
        return result;
    }

    /** 按父级聚合子项数量。 */
    public Map<String, Integer> countChildrenByParentIds(String kbId, List<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> row :
                fileMapper.selectMaps(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeFile>()
                                .select("parent_id", "COUNT(*) AS cnt")
                                .eq("kb_id", kbId)
                                .in("parent_id", parentIds)
                                .groupBy("parent_id"))) {
            Object parentId = row.get("parent_id");
            if (parentId != null) {
                Long count = RepoValues.toLong(row.get("cnt"));
                result.put(String.valueOf(parentId), count == null ? 0 : count.intValue());
            }
        }
        return result;
    }

    /** 获取知识库文件统计；结果带短 TTL 缓存，避免高频列表请求反复全表聚合。 */
    public Map<String, Object> getKbFileStats(String kbId) {
        String cacheKey = KB_FILE_STATS_CACHE_PREFIX + kbId;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                return JSON.parseObject(cached);
            }
        } catch (Exception exc) {
            log.warn("Failed to load kb file stats cache {}: {}", cacheKey, exc.getMessage());
        }
        Map<String, Object> stats = queryKbFileStats(kbId);
        try {
            redis.opsForValue()
                    .set(cacheKey, JSON.toJSONString(stats), java.time.Duration.ofSeconds(KB_FILE_STATS_CACHE_TTL));
        } catch (Exception exc) {
            log.warn("Failed to store kb file stats cache {}: {}", cacheKey, exc.getMessage());
        }
        return stats;
    }

    /** 直接聚合文件统计，绕过读取缓存（在调用方事务内执行）。 */
    public Map<String, Object> queryKbFileStats(String kbId) {
        String sql =
                "SELECT COUNT(file_id) AS row_count, "
                        + "SUM(CASE WHEN is_folder = 0 THEN 1 ELSE 0 END) AS file_count, "
                        + "SUM(CASE WHEN is_folder = 1 THEN 1 ELSE 0 END) AS folder_count, "
                        + "COALESCE(SUM(CASE WHEN is_folder = 0 THEN file_size ELSE 0 END), 0) AS total_size, "
                        + "COALESCE(SUM(CASE WHEN is_folder = 0 THEN chunk_count ELSE 0 END), 0) AS chunk_count, "
                        + "COALESCE(SUM(CASE WHEN is_folder = 0 THEN token_count ELSE 0 END), 0) AS token_count, "
                        + "SUM(CASE WHEN is_folder = 0 AND status = 'uploaded' THEN 1 ELSE 0 END) "
                        + "AS pending_parse_count, "
                        + "SUM(CASE WHEN is_folder = 0 AND status IN ('parsed', 'error_indexing') THEN 1 ELSE 0 END) "
                        + "AS pending_index_count, "
                        + "SUM(CASE WHEN is_folder = 0 AND status IN ('processing', 'waiting', 'parsing', 'indexing') "
                        + "THEN 1 ELSE 0 END) AS processing_count "
                        + "FROM knowledge_files WHERE kb_id = ?";
        Map<String, Object> row = jdbc.queryForMap(sql, kbId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("row_count", numberOrZero(row.get("row_count")));
        stats.put("file_count", numberOrZero(row.get("file_count")));
        stats.put("folder_count", numberOrZero(row.get("folder_count")));
        stats.put("total_size", numberOrZero(row.get("total_size")));
        stats.put("chunk_count", numberOrZero(row.get("chunk_count")));
        stats.put("token_count", numberOrZero(row.get("token_count")));
        stats.put("pending_parse_count", numberOrZero(row.get("pending_parse_count")));
        stats.put("pending_index_count", numberOrZero(row.get("pending_index_count")));
        stats.put("processing_count", numberOrZero(row.get("processing_count")));
        return stats;
    }

    /** 按 file_id upsert 文件记录（有则更新白名单字段，无则插入）。 */
    public KnowledgeFile upsert(String fileId, Map<String, Object> data) {
        Map<String, Object> sanitized = sanitizeData(data);
        KnowledgeFile existing =
                fileMapper.selectOne(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getFileId, fileId));
        if (existing == null) {
            KnowledgeFile record = new KnowledgeFile();
            record.setFileId(fileId);
            applyFileFields(record, sanitized);
            // 时间列默认值在参考实现由 ORM 填充，本层不经 ORM，需显式补上
            record.setCreatedAt(record.getCreatedAt() == null ? DateTimeUtils.utcNowNaive() : record.getCreatedAt());
            record.setUpdatedAt(record.getUpdatedAt() == null ? DateTimeUtils.utcNowNaive() : record.getUpdatedAt());
            fileMapper.insert(record);
            return record;
        }
        applyFileFields(existing, sanitized);
        fileMapper.updateById(existing);
        return existing;
    }

    /** 按 file_id 更新白名单字段（可限定 kb_id）。 */
    public KnowledgeFile updateFields(String fileId, Map<String, Object> data, String kbId) {
        Map<String, Object> sanitized = sanitizeData(data);
        if (sanitized.isEmpty()) {
            return getByFileId(fileId);
        }
        LambdaQueryWrapper<KnowledgeFile> filters = new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getFileId, fileId);
        if (kbId != null && !kbId.isEmpty()) {
            filters.eq(KnowledgeFile::getKbId, kbId);
        }
        KnowledgeFile record = fileMapper.selectOne(filters);
        if (record == null) {
            return null;
        }
        applyFileFields(record, sanitized);
        fileMapper.updateById(record);
        return record;
    }

    /**
     * 仅当文件仍处于允许状态（且归属指定处理任务/持有者）时更新字段。
     *
     * <p>带租约时先校验任务仍在运行且未过期（与参考实现同顺序：先锁任务、再锁文件、最后取数据库时间）。
     */
    public KnowledgeFile updateFieldsIfStatus(
            String kbId,
            String fileId,
            Set<String> allowedStatuses,
            Map<String, Object> data,
            String processingTaskId,
            String processingOwner) {
        String leaseTaskId = processingTaskId != null ? processingTaskId : RepoValues.asString(data.get("processing_task_id"));
        String leaseOwner = processingOwner != null ? processingOwner : RepoValues.asString(data.get("processing_owner"));
        Map<String, Object> sanitized = sanitizeData(data);
        if (sanitized.isEmpty()) {
            return getByFileId(fileId);
        }

        LambdaQueryWrapper<KnowledgeFile> filters =
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getKbId, kbId)
                        .eq(KnowledgeFile::getFileId, fileId)
                        .in(KnowledgeFile::getStatus, new java.util.TreeSet<>(allowedStatuses));
        if (processingTaskId != null) {
            filters.eq(KnowledgeFile::getProcessingTaskId, processingTaskId);
        }
        if (processingOwner != null) {
            filters.eq(KnowledgeFile::getProcessingOwner, processingOwner);
        }

        if (leaseTaskId != null && leaseOwner != null) {
            TaskRecord taskRecord =
                    taskMapper.selectOne(
                            new LambdaQueryWrapper<TaskRecord>()
                                    .eq(TaskRecord::getId, leaseTaskId)
                                    .eq(TaskRecord::getStatus, "running")
                                    .eq(TaskRecord::getWorkerId, leaseOwner)
                                    .last("FOR UPDATE"));
            if (taskRecord == null) {
                return null;
            }
            KnowledgeFile fileRecord = fileMapper.selectOne(filters.last("FOR UPDATE"));
            if (fileRecord == null) {
                return null;
            }
            LocalDateTime databaseNow = jdbc.queryForObject("SELECT UTC_TIMESTAMP()", LocalDateTime.class);
            if (taskRecord.getLeaseExpiresAt() == null || !taskRecord.getLeaseExpiresAt().isAfter(databaseNow)) {
                return null;
            }
            applyFileFields(fileRecord, sanitized);
            fileMapper.update(null, applyFieldSets(
                    kbId, fileId, allowedStatuses, processingTaskId, processingOwner, sanitized));
            return fileRecord;
        }

        KnowledgeFile record = fileMapper.selectOne(filters);
        if (record == null) {
            return null;
        }
        applyFileFields(record, sanitized);
        fileMapper.update(null, applyFieldSets(
                kbId, fileId, allowedStatuses, processingTaskId, processingOwner, sanitized));
        return record;
    }

    /**
     * 按白名单键构造显式 SET 更新（null 也写入）。
     *
     * <p>不要改回 {@code applyFileFields + updateById}：MyBatis-Plus 默认 NOT_NULL 策略会跳过
     * 实体里的 null 字段，导致成功分支的 {@code error_message: null} 落不到库——解析失败后重试
     * 成功的文件会一直挂着旧错误文案（与「清空/恢复继承禁 updateById」同一口径）。
     */
    private LambdaUpdateWrapper<KnowledgeFile> applyFieldSets(
            String kbId,
            String fileId,
            Set<String> allowedStatuses,
            String processingTaskId,
            String processingOwner,
            Map<String, Object> data) {
        LambdaUpdateWrapper<KnowledgeFile> update = new LambdaUpdateWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKbId, kbId)
                .eq(KnowledgeFile::getFileId, fileId)
                .in(KnowledgeFile::getStatus, new java.util.TreeSet<>(allowedStatuses));
        if (processingTaskId != null) {
            update.eq(KnowledgeFile::getProcessingTaskId, processingTaskId);
        }
        if (processingOwner != null) {
            update.eq(KnowledgeFile::getProcessingOwner, processingOwner);
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "kb_id" -> update.set(KnowledgeFile::getKbId, RepoValues.asString(value));
                case "parent_id" -> update.set(KnowledgeFile::getParentId, RepoValues.asString(value));
                case "filename" -> update.set(KnowledgeFile::getFilename, RepoValues.asString(value));
                case "original_filename" -> update.set(KnowledgeFile::getOriginalFilename, RepoValues.asString(value));
                case "file_type" -> update.set(KnowledgeFile::getFileType, RepoValues.asString(value));
                case "path" -> update.set(KnowledgeFile::getPath, RepoValues.asString(value));
                case "minio_url" -> update.set(KnowledgeFile::getMinioUrl, RepoValues.asString(value));
                case "markdown_file" -> update.set(KnowledgeFile::getMarkdownFile, RepoValues.asString(value));
                case "status" -> update.set(KnowledgeFile::getStatus, RepoValues.asString(value));
                case "content_hash" -> update.set(KnowledgeFile::getContentHash, RepoValues.asString(value));
                case "file_size" -> update.set(KnowledgeFile::getFileSize, RepoValues.toLong(value));
                case "chunk_count" -> update.set(KnowledgeFile::getChunkCount, RepoValues.toInt(value));
                case "token_count" -> update.set(KnowledgeFile::getTokenCount, RepoValues.toLong(value));
                case "content_type" -> update.set(KnowledgeFile::getContentType, RepoValues.asString(value));
                case "processing_params" -> update.set(KnowledgeFile::getProcessingParams, RepoValues.toJsonText(value));
                case "is_folder" -> update.set(KnowledgeFile::getIsFolder, RepoValues.toBoolean(value));
                case "error_message" -> update.set(KnowledgeFile::getErrorMessage, RepoValues.asString(value));
                case "processing_task_id" -> update.set(KnowledgeFile::getProcessingTaskId, RepoValues.asString(value));
                case "processing_owner" -> update.set(KnowledgeFile::getProcessingOwner, RepoValues.asString(value));
                case "created_by" -> update.set(KnowledgeFile::getCreatedBy, RepoValues.asString(value));
                case "updated_by" -> update.set(KnowledgeFile::getUpdatedBy, RepoValues.asString(value));
                case "updated_at" -> update.set(KnowledgeFile::getUpdatedAt, RepoValues.toLocalDateTime(value));
                default -> {
                    // 白名单外：已在上游过滤，不会到达
                }
            }
        }
        return update;
    }

    /** 仅收敛仍由指定任务拥有的文件中间态，返回受影响行数。 */
    @Transactional
    public int failTaskProcessingInSession(String taskId, String error) {
        LambdaUpdateWrapper<KnowledgeFile> wrapper =
                new LambdaUpdateWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getProcessingTaskId, taskId)
                        .in(KnowledgeFile::getStatus, List.of("parsing", "indexing"))
                        .set(KnowledgeFile::getErrorMessage, error)
                        .set(KnowledgeFile::getProcessingTaskId, null)
                        .set(KnowledgeFile::getProcessingOwner, null)
                        .set(KnowledgeFile::getUpdatedAt, DateTimeUtils.utcNowNaive());
        wrapper.setSql("status = CASE WHEN status = 'parsing' THEN 'error_parsing' ELSE 'error_indexing' END");
        return fileMapper.update(null, wrapper);
    }

    /** 删除单文件记录。 */
    public void delete(String fileId) {
        KnowledgeFile record =
                fileMapper.selectOne(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getFileId, fileId));
        if (record != null) {
            fileMapper.deleteById(record);
        }
    }

    /** 删除单知识库的全部文件记录。 */
    public void deleteByKbId(String kbId) {
        fileMapper.delete(new LambdaQueryWrapper<KnowledgeFile>().eq(KnowledgeFile::getKbId, kbId));
    }

    // ==================== 内部工具 ====================

    private static long numberOrZero(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** 联合查询的参数顺序必须与 SQL 文本一致：真实记录分支在前，虚拟文件夹分支在后。 */
    private static List<Object> mergeArgs(List<Object> realArgs, List<Object> virtualArgs) {
        List<Object> merged = new ArrayList<>(realArgs);
        merged.addAll(virtualArgs);
        return merged;
    }

    /** 按白名单过滤并写入更新时间。 */
    static Map<String, Object> sanitizeData(Map<String, Object> data) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (WRITABLE_FIELDS.contains(entry.getKey())) {
                    sanitized.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (!sanitized.isEmpty()) {
            sanitized.put("updated_at", DateTimeUtils.utcNowNaive());
        }
        return sanitized;
    }

    private static void applyFileFields(KnowledgeFile file, Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            switch (entry.getKey()) {
                case "kb_id" -> file.setKbId(RepoValues.asString(value));
                case "parent_id" -> file.setParentId(RepoValues.asString(value));
                case "filename" -> file.setFilename(RepoValues.asString(value));
                case "original_filename" -> file.setOriginalFilename(RepoValues.asString(value));
                case "file_type" -> file.setFileType(RepoValues.asString(value));
                case "path" -> file.setPath(RepoValues.asString(value));
                case "minio_url" -> file.setMinioUrl(RepoValues.asString(value));
                case "markdown_file" -> file.setMarkdownFile(RepoValues.asString(value));
                case "status" -> file.setStatus(RepoValues.asString(value));
                case "content_hash" -> file.setContentHash(RepoValues.asString(value));
                case "file_size" -> file.setFileSize(RepoValues.toLong(value));
                case "chunk_count" -> file.setChunkCount(RepoValues.toInt(value));
                case "token_count" -> file.setTokenCount(RepoValues.toLong(value));
                case "content_type" -> file.setContentType(RepoValues.asString(value));
                case "processing_params" -> file.setProcessingParams(RepoValues.toJsonText(value));
                case "is_folder" -> file.setIsFolder(RepoValues.toBoolean(value));
                case "error_message" -> file.setErrorMessage(RepoValues.asString(value));
                case "processing_task_id" -> file.setProcessingTaskId(RepoValues.asString(value));
                case "processing_owner" -> file.setProcessingOwner(RepoValues.asString(value));
                case "created_by" -> file.setCreatedBy(RepoValues.asString(value));
                case "updated_by" -> file.setUpdatedBy(RepoValues.asString(value));
                case "updated_at" -> file.setUpdatedAt(RepoValues.toLocalDateTime(value));
                default -> {
                    // 白名单外：已在上游过滤，不会到达
                }
            }
        }
    }

    /** 状态筛选条件（含参考实现的两组别名）。 */
    static boolean statusConditionPresent(String status) {
        return status != null && !status.isEmpty() && !"all".equals(status);
    }

    private static void applyStatusCondition(LambdaQueryWrapper<KnowledgeFile> wrapper, String status) {
        if (!statusConditionPresent(status)) {
            return;
        }
        wrapper.eq(KnowledgeFile::getIsFolder, false);
        switch (status) {
            case "indexed" -> wrapper.in(KnowledgeFile::getStatus, List.of("indexed", "done"));
            case "error_indexing" -> wrapper.in(KnowledgeFile::getStatus, List.of("error_indexing", "failed"));
            default -> wrapper.eq(KnowledgeFile::getStatus, status);
        }
    }

    private static void applyParentCondition(LambdaQueryWrapper<KnowledgeFile> wrapper, String parentId) {
        if (parentId != null && !parentId.isEmpty()) {
            wrapper.eq(KnowledgeFile::getParentId, parentId);
        } else {
            wrapper.isNull(KnowledgeFile::getParentId);
        }
    }

    /** 路径前缀规范化：拒绝绝对路径与上跳引用。 */
    static String normalizePathPrefix(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isEmpty()) {
            return "";
        }
        String normalized = pathPrefix.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("path_prefix must be relative");
        }
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split("/")) {
            if (!part.isEmpty() && !".".equals(part)) {
                parts.add(part);
            }
        }
        if (parts.contains("..")) {
            throw new IllegalArgumentException("path_prefix must not contain parent directory references");
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join("/", parts) + "/";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
