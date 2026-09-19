package com.wisesoft.wenqu.knowledge;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.models.KnowledgeFile;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import com.wisesoft.wenqu.repositories.RepoValues;
import com.wisesoft.wenqu.service.ModelSelectors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库内容生成（思维导图 / 示例问题）的持久化与模型调用层。
 *
 * <p>与参考实现的模块划分一一对应：
 * <ul>
 *   <li>{@code package/&lt;ref&gt;/knowledge/utils/mindmap_utils.py} 中需要 DB/LLM 的 11 个函数
 *       → {@link #getMindmapDatabaseFiles} / {@link #getMindmapDiff} /
 *       {@link #updateMindmapIncremental} / {@link #generateDatabaseMindmap} /
 *       {@link #getMindmapDatabasesOverview} / {@link #getDatabaseMindmapData} /
 *       {@link #removeFileFromMindmap} / {@link #batchRemoveFilesFromMindmap}
 *       及两个内部函数 {@link #listMindmapFilesPage} / {@link #loadMindmapCurrentFiles}；</li>
 *   <li>{@code package/&lt;ref&gt;/knowledge/utils/sample_question_utils.py} 的 2 个函数
 *       → {@link #generateDatabaseSampleQuestions} / {@link #getDatabaseSampleQuestions}。</li>
 * </ul>
 * 无外部依赖的纯函数面已在 {@link KnowledgeMindmap} / {@link KnowledgeSampleQuestions}，
 * 本类只做编排，不重复实现纯逻辑。
 *
 * <h3>平台差异（必要替换 / 能力差异，均不影响状态码与文案）</h3>
 * <ul>
 *   <li>{@code fastapi.HTTPException(status_code, detail)} → {@link ApiHttpException}，
 *       状态码与中文文案逐字保留。</li>
 *   <li>{@code datetime.now(UTC).isoformat()} → {@link DateTimeUtils#pythonIsoformatUtc()}
 *       （Java 默认输出 {@code Z} 且裁微秒尾零，与 Python 的 {@code +00:00} + 固定 6 位不一致）。</li>
 *   <li>{@code select_model(model_spec=...)["default_model"]} →
 *       {@link OptionsService#get(OptionsService#SYSTEM_OPTIONS)} 后取同名键，
 *       再走 {@link ModelSelectors#selectModel(String)}；{@code await model.call(messages, stream=False)}
 *       → {@link ModelSelectors.ChatAdapter#call(Object, boolean)}，取 {@code response.content}。</li>
 *   <li>{@code KnowledgeBaseFactory.get_kb_class(kb_type).supports_documents} →
 *       {@link KnowledgeBaseManager#databaseTypeSupportsDocuments(String)}
 *       （本工程按 kb_type 判定，无逐类型类实例）。</li>
 *   <li>参考实现里"取 ORM 行属性"（{@code kb.mindmap} 等）在本工程是 JSON 文本列，
 *       读取时经 {@code JSON.parse} 还原为 dict / list；写回时经
 *       {@code KnowledgeBaseRepository.update} 转为 JSON 文本。</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
public class KnowledgeContentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeContentService.class);

    /** 知识库不存在时的 detail 文案（参考实现逐字一致）。 */
    private static final String KB_NOT_FOUND_TEMPLATE = "知识库 %s 不存在";

    private final KnowledgeBaseRepository kbRepository;
    private final KnowledgeFileRepository fileRepository;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final OptionsService optionsService;
    private final ModelSelectors modelSelectors;

    public KnowledgeContentService(
            KnowledgeBaseRepository kbRepository,
            KnowledgeFileRepository fileRepository,
            KnowledgeBaseManager knowledgeBaseManager,
            OptionsService optionsService,
            ModelSelectors modelSelectors) {
        this.kbRepository = kbRepository;
        this.fileRepository = fileRepository;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.optionsService = optionsService;
        this.modelSelectors = modelSelectors;
    }

    /** 一次思维导图文件分页的结果（Java 无元组，用 record 承载）。 */
    public record MindmapFiles(Map<String, Map<String, Object>> files, int total) {}

    /** 批量移除项（对应参考实现的 {@code (file_id, filename)} 二元组）。 */
    public record Removal(String fileId, String filename) {}

    // ==================== 思维导图 ====================

    /** 分页读取知识库文件并转为导图文件映射（对应 {@code _list_mindmap_files_page}）。 */
    public MindmapFiles listMindmapFilesPage(String kbId, int pageSize) {
        Map<String, Object> page =
                fileRepository.searchFiles(kbId, null, null, 0, pageSize, true);
        List<KnowledgeFile> records = recordsOf(page);
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        for (KnowledgeFile record : records) {
            files.put(record.getFileId(), KnowledgeMindmap.fileRecordToMindmapFile(record));
        }
        return new MindmapFiles(files, intOf(page.get("total")));
    }

    /**
     * 载入"上一轮导图追踪的文件 + 本页文件"的并集（对应 {@code _load_mindmap_current_files}）。
     *
     * <p>追踪集合中的文件可能已不在首页（分页外），单独按 file_id 取回，且只保留同库、非目录的记录。
     */
    public MindmapFiles loadMindmapCurrentFiles(String kbId, List<String> trackedFileIds) {
        MindmapFiles page = listMindmapFilesPage(kbId, KnowledgeMindmap.MINDMAP_FILE_PAGE_SIZE);
        Map<String, Map<String, Object>> files = page.files();

        List<String> trackedIds = new ArrayList<>();
        if (trackedFileIds != null) {
            for (String fileId : trackedFileIds) {
                if (fileId != null && !fileId.isEmpty()) {
                    trackedIds.add(fileId);
                }
            }
        }
        if (trackedIds.isEmpty()) {
            return new MindmapFiles(files, page.total());
        }

        for (KnowledgeFile record : fileRepository.listByFileIds(trackedIds)) {
            boolean isFolder = Boolean.TRUE.equals(record.getIsFolder());
            if (kbId.equals(record.getKbId()) && !isFolder) {
                files.put(record.getFileId(), KnowledgeMindmap.fileRecordToMindmapFile(record));
            }
        }
        return new MindmapFiles(files, page.total());
    }

    /** 指定知识库的全部文件列表（对应 {@code get_mindmap_database_files}）。 */
    public Map<String, Object> getMindmapDatabaseFiles(String kbId) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }

        MindmapFiles page = listMindmapFilesPage(kbId, KnowledgeMindmap.MINDMAP_FILE_PAGE_SIZE);
        List<Map<String, Object>> files = KnowledgeMindmap.buildDatabaseFileList(page.files());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("kb_id", kbId);
        response.put("slug", kbId);
        response.put("db_name", kb.getName());
        response.put("files", files);
        response.put("total", page.total());
        response.put("truncated", page.total() > page.files().size());
        return response;
    }

    /** 导图与知识库当前文件的变更检测（对应 {@code get_mindmap_diff}）。 */
    public Map<String, Object> getMindmapDiff(String kbId) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }

        Map<String, String> tracked = parseStringMap(kb.getMindmapFileIds());
        MindmapFiles page = loadMindmapCurrentFiles(kbId, new ArrayList<>(tracked.keySet()));

        Map<String, Object> changes = KnowledgeMindmap.detectMindmapChanges(
                RepoValues.parseObject(kb.getMindmap()), tracked, page.files());
        changes.put("current_total", page.total());
        changes.put("current_files_truncated", page.total() > page.files().size());
        changes.put("kb_id", kbId);
        changes.put("slug", kbId);
        changes.put("message", "success");
        return changes;
    }

    /**
     * 增量更新思维导图（对应 {@code update_mindmap_incremental}）。
     *
     * <p>纯删除场景不调用模型，仅做树手术；有新增时才调模型整合。
     */
    public Map<String, Object> updateMindmapIncremental(String kbId, String userPrompt) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        Map<String, Object> mindmapData = kb == null ? null : RepoValues.parseObject(kb.getMindmap());
        if (kb == null || mindmapData == null || mindmapData.isEmpty()) {
            throw new ApiHttpException(400, "知识库没有现有思维导图，请使用全量生成");
        }

        Map<String, String> storedFileIds = parseStringMap(kb.getMindmapFileIds());
        MindmapFiles page = loadMindmapCurrentFiles(kbId, new ArrayList<>(storedFileIds.keySet()));
        String dbName = (kb.getName() == null || kb.getName().isEmpty()) ? "知识库" : kb.getName();

        Map<String, Object> changes =
                KnowledgeMindmap.detectMindmapChanges(mindmapData, storedFileIds, page.files());
        changes.put("current_files_truncated", page.total() > page.files().size());

        if (!needsUpdateOf(changes)) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "success");
            response.put("mindmap", mindmapData);
            response.put("kb_id", kbId);
            response.put("slug", kbId);
            response.put("db_name", dbName);
            response.put("no_ai_needed", true);
            response.put("no_changes", true);
            return response;
        }

        List<String> removedFileIds = removedFileIdsOf(changes);
        List<Map<String, Object>> addedFiles = addedFilesOf(changes);

        // 追踪映射：优先用存量；旧数据缺映射时按叶子节点文件名反向重建
        Map<String, String> updatedFileIds = new LinkedHashMap<>();
        if (!storedFileIds.isEmpty()) {
            updatedFileIds.putAll(storedFileIds);
        } else {
            Set<String> leafFilenames = KnowledgeMindmap.collectLeafFilenames(mindmapData);
            for (Map.Entry<String, Map<String, Object>> entry : page.files().entrySet()) {
                String filename = strOf(
                        entry.getValue() == null ? null : entry.getValue().get("filename"));
                if (leafFilenames.contains(filename)) {
                    updatedFileIds.put(entry.getKey(), filename);
                }
            }
        }

        if (!removedFileIds.isEmpty()) {
            Set<String> removedFilenames = new LinkedHashSet<>();
            for (String fileId : removedFileIds) {
                String filename = updatedFileIds.get(fileId);
                if (filename != null) {
                    removedFilenames.add(filename);
                }
            }
            mindmapData = KnowledgeMindmap.removeFilesFromMindmap(mindmapData, removedFilenames);
            for (String fileId : removedFileIds) {
                updatedFileIds.remove(fileId);
            }
        }

        if (!addedFiles.isEmpty()) {
            List<String> addedIds = new ArrayList<>();
            for (Map<String, Object> item : addedFiles) {
                addedIds.add(strOf(item.get("file_id")));
            }
            List<Map<String, String>> addedFilesInfo =
                    KnowledgeMindmap.collectMindmapFiles(page.files(), addedIds);
            if (!addedFilesInfo.isEmpty()) {
                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(messageRow("system", KnowledgeMindmap.MINDMAP_INCREMENTAL_SYSTEM_PROMPT));
                messages.add(messageRow(
                        "user",
                        KnowledgeMindmap.buildMindmapIncrementalUserMessage(
                                dbName, mindmapData, addedFilesInfo, userPrompt)));
                String content = callDefaultModel(messages);
                try {
                    mindmapData = KnowledgeMindmap.parseMindmapContent(content);
                } catch (IllegalArgumentException exc) {
                    log.error("增量AI返回的JSON解析失败: {}, 原始内容: {}", exc.getMessage(), content);
                    throw new ApiHttpException(500, "AI返回格式错误: " + exc.getMessage());
                }
            }
            for (Map<String, Object> item : addedFiles) {
                updatedFileIds.put(strOf(item.get("file_id")), strOf(item.get("filename")));
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generated_at", DateTimeUtils.pythonIsoformatUtc());
        metadata.put("file_count", updatedFileIds.size());
        metadata.put("incremental", true);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mindmap", mindmapData);
        payload.put("mindmap_file_ids", updatedFileIds);
        payload.put("mindmap_metadata", metadata);
        if (kbRepository.update(kbId, payload) == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }
        log.info("思维导图增量更新成功: {}", kbId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("mindmap", mindmapData);
        response.put("kb_id", kbId);
        response.put("slug", kbId);
        response.put("db_name", dbName);
        response.put("no_ai_needed", addedFiles.isEmpty());
        return response;
    }

    /** 全量生成（或增量更新）思维导图（对应 {@code generate_database_mindmap}）。 */
    public Map<String, Object> generateDatabaseMindmap(
            String kbId, List<String> fileIds, String userPrompt, boolean incremental) {
        if (incremental) {
            return updateMindmapIncremental(kbId, userPrompt);
        }

        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }

        String dbName = (kb.getName() == null || kb.getName().isEmpty()) ? "知识库" : kb.getName();

        Map<String, Map<String, Object>> allFiles;
        List<String> selectedFileIds;
        int originalCount;
        if (fileIds != null && !fileIds.isEmpty()) {
            originalCount = fileIds.size();
            List<String> selected = new ArrayList<>(
                    fileIds.subList(0, Math.min(fileIds.size(), KnowledgeMindmap.MINDMAP_GENERATION_FILE_LIMIT)));
            if (fileIds.size() > KnowledgeMindmap.MINDMAP_GENERATION_FILE_LIMIT) {
                log.info("文件数量超过限制，已从{}个文件中选择前{}个文件生成思维导图",
                        originalCount, KnowledgeMindmap.MINDMAP_GENERATION_FILE_LIMIT);
            }
            allFiles = new LinkedHashMap<>();
            for (KnowledgeFile record : fileRepository.listByFileIds(selected)) {
                boolean isFolder = Boolean.TRUE.equals(record.getIsFolder());
                if (kbId.equals(record.getKbId()) && !isFolder) {
                    allFiles.put(record.getFileId(), KnowledgeMindmap.fileRecordToMindmapFile(record));
                }
            }
            selectedFileIds = new ArrayList<>(selected);
        } else {
            MindmapFiles page =
                    listMindmapFilesPage(kbId, KnowledgeMindmap.MINDMAP_GENERATION_FILE_LIMIT);
            allFiles = page.files();
            originalCount = page.total();
            selectedFileIds = new ArrayList<>(allFiles.keySet());
        }

        if (selectedFileIds.isEmpty()) {
            throw new ApiHttpException(400, "知识库中没有文件");
        }

        List<Map<String, String>> filesInfo =
                KnowledgeMindmap.collectMindmapFiles(allFiles, selectedFileIds);
        if (filesInfo.isEmpty()) {
            throw new ApiHttpException(400, "选择的文件不存在");
        }

        log.info("开始生成思维导图，知识库: {}, 文件数量: {}", dbName, filesInfo.size());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(messageRow("system", KnowledgeMindmap.MINDMAP_SYSTEM_PROMPT));
        messages.add(messageRow(
                "user", KnowledgeMindmap.buildMindmapUserMessage(dbName, filesInfo, userPrompt)));
        String content = callDefaultModel(messages);

        Map<String, Object> mindmapData;
        try {
            mindmapData = KnowledgeMindmap.parseMindmapContent(content);
        } catch (IllegalArgumentException exc) {
            log.error("AI返回的JSON解析失败: {}, 原始内容: {}", exc.getMessage(), content);
            throw new ApiHttpException(500, "AI返回格式错误: " + exc.getMessage());
        }

        log.info("思维导图生成成功");

        Map<String, String> mindmapFileIds = new LinkedHashMap<>();
        for (String fileId : selectedFileIds) {
            Map<String, Object> info = allFiles.get(fileId);
            if (info != null) {
                mindmapFileIds.put(fileId, strOf(info.get("filename")));
            }
        }

        Map<String, Object> mindmapMetadata = new LinkedHashMap<>();
        mindmapMetadata.put("generated_at", DateTimeUtils.pythonIsoformatUtc());
        mindmapMetadata.put("file_count", filesInfo.size());
        mindmapMetadata.put("incremental", false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mindmap", mindmapData);
        payload.put("mindmap_file_ids", mindmapFileIds);
        payload.put("mindmap_metadata", mindmapMetadata);
        if (kbRepository.update(kbId, payload) == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }
        log.info("思维导图已保存到知识库: {}", kbId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("mindmap", mindmapData);
        response.put("kb_id", kbId);
        response.put("slug", kbId);
        response.put("db_name", dbName);
        response.put("file_count", filesInfo.size());
        response.put("original_file_count", originalCount);
        response.put("truncated", filesInfo.size() < originalCount);
        return response;
    }

    /** 思维导图界面用的知识库概览（对应 {@code get_mindmap_databases_overview}）。 */
    public Map<String, Object> getMindmapDatabasesOverview(String uid) {
        List<Map<String, Object>> dbList = new ArrayList<>();
        for (KnowledgeBaseSummary db : knowledgeBaseManager.getDatabasesByUid(uid)) {
            String kbId = db.kbId();
            if (kbId == null || kbId.isEmpty()) {
                continue;
            }
            Map<String, Object> stats = fileRepository.getKbFileStats(kbId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kb_id", kbId);
            item.put("slug", kbId);
            item.put("name", db.name());
            item.put("description", db.description() == null ? "" : db.description());
            item.put("kb_type", db.kbType());
            item.put("file_count", stats.get("file_count"));
            dbList.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("databases", dbList);
        response.put("total", dbList.size());
        return response;
    }

    /** 读取知识库已保存的导图与追踪信息（对应 {@code get_database_mindmap_data}）。 */
    public Map<String, Object> getDatabaseMindmapData(String kbId) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("mindmap", RepoValues.parseObject(kb.getMindmap()));
        response.put("kb_id", kbId);
        response.put("slug", kbId);
        response.put("db_name", kb.getName());
        response.put("mindmap_file_ids", parseStringMap(kb.getMindmapFileIds()));
        response.put("mindmap_metadata", RepoValues.parseObject(kb.getMindmapMetadata()));
        return response;
    }

    /**
     * 文件删除后从导图中摘除对应叶子（对应 {@code remove_file_from_mindmap}）。
     *
     * <p>纯树手术，不调用模型；失败只记日志（与参考实现一致，删除主流程不因此中断）。
     */
    public void removeFileFromMindmap(String kbId, String fileId, String filename) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        Map<String, Object> mindmapData = kb == null ? null : RepoValues.parseObject(kb.getMindmap());
        if (kb == null || mindmapData == null || mindmapData.isEmpty()) {
            return;
        }

        Map<String, String> storedFileIds = parseStringMap(kb.getMindmapFileIds());
        String removedFilename = null;
        if (!storedFileIds.isEmpty() && storedFileIds.containsKey(fileId)) {
            removedFilename = storedFileIds.get(fileId);
        } else if (filename != null && !filename.isEmpty()) {
            if (KnowledgeMindmap.collectLeafFilenames(mindmapData).contains(filename)) {
                removedFilename = filename;
            }
        }
        if (removedFilename == null || removedFilename.isEmpty()) {
            return;
        }

        Map<String, Object> updatedMindmap =
                KnowledgeMindmap.removeFilesFromMindmap(mindmapData, Set.of(removedFilename));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mindmap", updatedMindmap);
        if (!storedFileIds.isEmpty()) {
            Map<String, String> kept = new LinkedHashMap<>(storedFileIds);
            kept.remove(fileId);
            payload.put("mindmap_file_ids", kept);
        } else {
            payload.put("mindmap_file_ids", null);
        }

        try {
            kbRepository.update(kbId, payload);
            log.info("思维导图中已移除文件: {}", removedFilename);
        } catch (RuntimeException exc) {
            log.error("从思维导图移除文件失败: {}", exc.getMessage());
        }
    }

    /** 批量摘除已删除文件（对应 {@code batch_remove_files_from_mindmap}，单次 DB 读写）。 */
    public void batchRemoveFilesFromMindmap(String kbId, List<Removal> removals) {
        if (removals == null || removals.isEmpty()) {
            return;
        }

        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        Map<String, Object> mindmapData = kb == null ? null : RepoValues.parseObject(kb.getMindmap());
        if (kb == null || mindmapData == null || mindmapData.isEmpty()) {
            return;
        }

        Map<String, String> storedFileIds = parseStringMap(kb.getMindmapFileIds());
        Set<String> staleFilenames = new LinkedHashSet<>();
        Set<String> staleFileIds = new LinkedHashSet<>();
        for (Removal removal : removals) {
            if (removal == null) {
                continue;
            }
            if (!storedFileIds.isEmpty() && storedFileIds.containsKey(removal.fileId())) {
                staleFilenames.add(storedFileIds.get(removal.fileId()));
                staleFileIds.add(removal.fileId());
            } else if (removal.filename() != null && !removal.filename().isEmpty()) {
                staleFilenames.add(removal.filename());
                staleFileIds.add(removal.fileId());
            }
        }
        if (staleFilenames.isEmpty()) {
            return;
        }

        Map<String, Object> updatedMindmap =
                KnowledgeMindmap.removeFilesFromMindmap(mindmapData, staleFilenames);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mindmap", updatedMindmap);
        if (!storedFileIds.isEmpty()) {
            Map<String, String> kept = new LinkedHashMap<>(storedFileIds);
            kept.keySet().removeAll(staleFileIds);
            payload.put("mindmap_file_ids", kept);
        } else {
            payload.put("mindmap_file_ids", null);
        }

        try {
            kbRepository.update(kbId, payload);
            log.info("思维导图批量清理完成: {}, 移除 {} 个文件", kbId, staleFilenames.size());
        } catch (RuntimeException exc) {
            log.error("从思维导图批量移除文件失败: {}", exc.getMessage());
        }
    }

    // ==================== 示例问题 ====================

    /** 基于知识库文件生成示例问题（对应 {@code generate_database_sample_questions}）。 */
    public Map<String, Object> generateDatabaseSampleQuestions(String kbId, int count) {
        KnowledgeBaseDetail dbInfo = knowledgeBaseManager.getDatabaseInfo(kbId, true);
        if (dbInfo == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }

        String kbType = dbInfo.kbType() == null ? "" : dbInfo.kbType().toLowerCase();
        if (!knowledgeBaseManager.databaseTypeSupportsDocuments(kbType)) {
            String label = (dbInfo.name() == null || dbInfo.name().isEmpty()) ? kbType : dbInfo.name();
            throw new ApiHttpException(400, label + " 不支持基于文件生成测试问题");
        }

        String dbName = dbInfo.name();
        Map<String, Map<String, Object>> allFiles =
                dbInfo.files() == null ? new LinkedHashMap<>() : dbInfo.files();
        if (allFiles.isEmpty()) {
            throw new ApiHttpException(400, "知识库中没有文件");
        }

        List<Map<String, String>> filesInfo =
                KnowledgeSampleQuestions.buildSampleQuestionFileList(allFiles);
        log.info("开始生成知识库问题，知识库: {}, 文件数量: {}, 问题数量: {}",
                dbName, filesInfo.size(), count);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(messageRow("system", KnowledgeSampleQuestions.SAMPLE_QUESTIONS_SYSTEM_PROMPT));
        messages.add(messageRow(
                "user",
                KnowledgeSampleQuestions.buildSampleQuestionsUserMessage(dbName, filesInfo, count)));
        String content = callDefaultModel(messages);

        List<String> questions;
        try {
            questions = KnowledgeSampleQuestions.parseSampleQuestionsContent(content);
        } catch (IllegalArgumentException exc) {
            log.error("AI返回的JSON解析失败: {}, 原始内容: {}", exc.getMessage(), content);
            throw new ApiHttpException(500, "AI返回格式错误: " + exc.getMessage());
        }

        log.info("成功生成{}个问题", questions.size());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sample_questions", questions);
        if (kbRepository.update(kbId, payload) == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }
        log.info("成功保存 {} 个问题到知识库 {}", questions.size(), kbId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("questions", questions);
        response.put("count", questions.size());
        response.put("kb_id", kbId);
        response.put("db_name", dbName);
        return response;
    }

    /** 读取已保存的示例问题（对应 {@code get_database_sample_questions}）。 */
    public Map<String, Object> getDatabaseSampleQuestions(String kbId) {
        KnowledgeBase kb = kbRepository.getByKbId(kbId);
        if (kb == null) {
            throw new ApiHttpException(404, KB_NOT_FOUND_TEMPLATE.formatted(kbId));
        }

        List<String> questions = parseStringList(kb.getSampleQuestions());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "success");
        response.put("questions", questions);
        response.put("count", questions.size());
        response.put("kb_id", kbId);
        return response;
    }

    // ==================== 内部工具 ====================

    /** 按系统配置的默认对话模型发起一次非流式调用，返回文本内容。 */
    private String callDefaultModel(List<Map<String, Object>> messages) {
        Map<String, Object> systemOptions = optionsService.get(OptionsService.SYSTEM_OPTIONS);
        String modelSpec = strOf(systemOptions.get("default_model"));
        ModelSelectors.GeneralResponse response =
                modelSelectors.selectModel(modelSpec).call(messages, false);
        return response == null ? "" : strOf(response.content);
    }

    private static Map<String, Object> messageRow(String role, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<KnowledgeFile> recordsOf(Map<String, Object> page) {
        Object records = page == null ? null : page.get("records");
        if (records instanceof List<?> list) {
            return (List<KnowledgeFile>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> addedFilesOf(Map<String, Object> changes) {
        Object value = changes.get("added_files");
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> removedFileIdsOf(Map<String, Object> changes) {
        Object value = changes.get("removed_file_ids");
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    private static boolean needsUpdateOf(Map<String, Object> changes) {
        return Boolean.TRUE.equals(changes.get("needs_update"));
    }

    /** JSON 文本列 → 字符串映射（空值/非法一律给空表，与 {@code dict(x or {})} 语义一致）。 */
    private static Map<String, String> parseStringMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(json);
            if (parsed == null) {
                return result;
            }
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        } catch (RuntimeException ignored) {
            // 参考实现的 `kb.mindmap_file_ids or {}` 同样把异常值当空处理
        }
        return result;
    }

    /** JSON 文本列 → 字符串列表（对应参考实现的 {@code kb.sample_questions or []}）。 */
    private static List<String> parseStringList(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            List<Object> parsed = JSON.parseArray(json, Object.class);
            if (parsed == null) {
                return result;
            }
            for (Object item : parsed) {
                result.add(item == null ? "" : String.valueOf(item));
            }
        } catch (RuntimeException ignored) {
            // 同上：非列表值按空列表处理
        }
        return result;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String strOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
