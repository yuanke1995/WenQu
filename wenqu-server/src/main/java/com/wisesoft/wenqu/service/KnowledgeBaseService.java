package com.wisesoft.wenqu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.repository.AiDocumentMapper;
import com.wisesoft.wenqu.repository.KnowledgeMapper;
import com.wisesoft.wenqu.repository.KnowledgeBaseMapper;
import com.wisesoft.wenqu.model.AiDocument;
import com.wisesoft.wenqu.model.Knowledge;
import com.wisesoft.wenqu.model.KnowledgeBase;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库服务：文档的容器、检索的作用域、检索参数的归属。
 * <p>
 * 这一层只负责「知识库自身」的读写与「库 → 文档集合」的解析，
 * 不做可见性判定（可见性由检索层与 {@link ResourceVisibilityService} 统一处理），
 * 避免两处各算一套导致口径不一致。
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final AiDocumentMapper docMapper;

    /** 知识块 Mapper：统计库内知识块数（chunk_count / 待索引判断） */
    private final KnowledgeMapper knowledgeMapper;

    /** 默认库缓存（避免每次检索都查库；is_default 变更时由 update/create 失效） */
    private volatile String cachedDefaultId;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper, AiDocumentMapper docMapper,
                                KnowledgeMapper knowledgeMapper) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.knowledgeMapper = knowledgeMapper;
    }

    // ==================== 读写 ====================

    /** 未删除的知识库列表（按默认库优先、创建时间升序） */
    public List<KnowledgeBase> list() {
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDeleted, 0)
                .orderByDesc(KnowledgeBase::getIsDefault)
                .orderByAsc(KnowledgeBase::getCreateTime));
    }

    public KnowledgeBase get(String id) {
        if (id == null || id.isBlank()) return null;
        return kbMapper.selectById(id);
    }

    /** 新建：名称必填；isDefault=1 时先把其它库的默认标记清掉（保证唯一默认库） */
    public KnowledgeBase create(Map<String, Object> body, String name) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(str(body.get("description")));
        kb.setKbType(strOr(body.get("kb_type"), "milvus"));
        kb.setEmbeddingModelSpec(str(body.get("embedding_model_spec")));
        kb.setLlmModelSpec(str(body.get("llm_model_spec")));
        kb.setQueryParams(jsonStr(body.get("query_params")));
        kb.setAdditionalParams(jsonStr(body.get("additional_params")));
        kb.setIsDefault(toInt(body.get("is_default"), 0));
        kb.setShareConfig(jsonStr(body.get("share_config")));
        kb.setCreatedBy(strOr(body.get("created_by"), "admin"));
        kb.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        kb.setCreateTime(now);
        kb.setUpdateTime(now);
        if (kb.getIsDefault() != null && kb.getIsDefault() == 1) clearDefault();
        kbMapper.insert(kb);
        cachedDefaultId = null;
        return kb;
    }

    /** 更新：仅更新 body 中出现的字段；库被删除时不处理 */
    public KnowledgeBase update(String id, Map<String, Object> body) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) return null;
        // 只在 body 中出现的字段才写（含显式 null = 清空该维度回退到继承）
        LambdaUpdateWrapper<KnowledgeBase> upd = new LambdaUpdateWrapper<KnowledgeBase>().eq(KnowledgeBase::getKbId, id);
        if (body.containsKey("name")) upd.set(KnowledgeBase::getName, str(body.get("name")));
        if (body.containsKey("description")) upd.set(KnowledgeBase::getDescription, str(body.get("description")));
        if (body.containsKey("query_params")) upd.set(KnowledgeBase::getQueryParams, jsonStr(body.get("query_params")));
        // additional_params 为**合并**语义：只覆盖 body 提到的键，保留其余（如 chunk_preset_id）
        if (body.containsKey("additional_params")) {
            Object inc = body.get("additional_params");
            if (inc == null) {
                upd.set(KnowledgeBase::getAdditionalParams, null);            // 显式清空
            } else if (inc instanceof Map<?, ?> m) {
                Map<String, Object> merged = parseJsonObj(kb.getAdditionalParams());
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
                upd.set(KnowledgeBase::getAdditionalParams,
                        com.alibaba.fastjson2.JSON.toJSONString(merged));
            }
        }
        if (body.containsKey("share_config")) upd.set(KnowledgeBase::getShareConfig, jsonStr(body.get("share_config")));
        if (body.containsKey("kb_type")) upd.set(KnowledgeBase::getKbType, str(body.get("kb_type")));
        if (body.containsKey("embedding_model_spec")) upd.set(KnowledgeBase::getEmbeddingModelSpec, str(body.get("embedding_model_spec")));
        if (body.containsKey("llm_model_spec")) upd.set(KnowledgeBase::getLlmModelSpec, str(body.get("llm_model_spec")));
        if (body.containsKey("isDefault")) {
            int isDef = toInt(body.get("isDefault"), 0);
            if (isDef == 1) clearDefault();
            upd.set(KnowledgeBase::getIsDefault, isDef);
        }
        upd.set(KnowledgeBase::getUpdateTime, LocalDateTime.now());
        // 必须显式 set：updateById 走 NOT_NULL 策略会跳过 null 列，
        // 导致「清空检索参数 → 恢复继承全局」这类操作静默失效（本项目已踩过同一坑）
        kbMapper.update(null, upd);
        cachedDefaultId = null;
        return kbMapper.selectById(id);
    }

    /**
     * 逻辑删除；**默认库与仍含文档的库禁止删除**（删了会让文档失去归属、检索范围突变）。
     *
     * @return null 表示可删并已删除；否则返回不可删的原因
     */
    public String delete(String id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) return "知识库不存在";
        if (kb.getIsDefault() != null && kb.getIsDefault() == 1) return "默认知识库不可删除";
        long n = docMapper.selectCount(new LambdaQueryWrapper<AiDocument>().eq(AiDocument::getKbId, id));
        if (n > 0) return "该知识库下还有 " + n + " 个文档，请先移出或删除文档";
        kb.setDeleted(1);
        kb.setUpdateTime(LocalDateTime.now());
        kbMapper.updateById(kb);
        cachedDefaultId = null;
        return null;
    }

    // ==================== 检索侧支撑 ====================

    /**
     * 默认库 ID：没有默认库时自动建一个「默认知识库」，
     * 保证历史文档（kb_id 为空）与新建文档始终有归属，检索范围不会因缺库而变空。
     */
    public String defaultId() {
        String cached = cachedDefaultId;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedDefaultId != null) return cachedDefaultId;
            KnowledgeBase def = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getIsDefault, 1)
                    .eq(KnowledgeBase::getDeleted, 0)
                    .last("LIMIT 1"));
            if (def == null) {
                def = new KnowledgeBase();
                def.setName("默认知识库");
                def.setDescription("未显式指定归属的文档都归入本库（历史数据自动兼容）");
                def.setIsDefault(1);
                def.setDeleted(0);
                def.setCreatedBy("system");
                LocalDateTime now = LocalDateTime.now();
                def.setCreateTime(now);
                def.setUpdateTime(now);
                kbMapper.insert(def);
            }
            cachedDefaultId = def.getKbId();
            return def.getKbId();
        }
    }

    /**
     * 库 ID 集合 → 文档 ID 集合（检索按库过滤用）。
     * <p>注意：{@code kb_id} 为空的文档归入默认库，因此当入参包含默认库时，
     * 结果还要并上「kb_id 为空」的文档，否则历史文档会整体检索不到。
     *
     * @return 文档 ID 集合；入参为空返回空集合
     */
    public Set<String> docIdsOf(Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        for (AiDocument d : docMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .select(AiDocument::getId)
                .in(AiDocument::getKbId, kbIds))) {
            if (d.getId() != null) ids.add(d.getId());
        }
        // 含默认库 → 并上未指定归属的文档（历史数据兼容）
        if (kbIds.contains(defaultId())) {
            for (AiDocument d : docMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                    .select(AiDocument::getId)
                    .isNull(AiDocument::getKbId))) {
                if (d.getId() != null) ids.add(d.getId());
            }
        }
        return ids;
    }

    /**
     * 把文档移到某个知识库（文档管理页切换归属用）。
     *
     * @return false 表示文档不存在
     */
    public boolean moveDoc(String docId, String kbId) {
        AiDocument doc = docMapper.selectById(docId);
        if (doc == null) return false;
        // 显式 set：kbId 传 null（移回默认库）时也能真正写入，不被 NOT_NULL 策略跳过
        docMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getKbId, kbId)
                .set(AiDocument::getUpdateTime, LocalDateTime.now()));
        return true;
    }

    /** 各库的文档数量（列表页展示用）：key=库ID */
    public Map<String, Integer> docCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (KnowledgeBase kb : list()) {
            long n = docMapper.selectCount(new LambdaQueryWrapper<AiDocument>().eq(AiDocument::getKbId, kb.getKbId()));
            m.put(kb.getKbId(), (int) n);
        }
        long orphan = docMapper.selectCount(new LambdaQueryWrapper<AiDocument>().isNull(AiDocument::getKbId));
        if (orphan > 0) m.put(defaultId(), m.getOrDefault(defaultId(), 0) + (int) orphan);
        return m;
    }

    /** 列表页用：库 + 文档数 + 是否默认 */
    public List<Map<String, Object>> listWithCounts() {
        Map<String, Integer> counts = docCounts();
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeBase kb : list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", kb.getKbId());
            m.put("name", kb.getName());
            m.put("description", kb.getDescription());
            m.put("queryParams", kb.getQueryParams());
            m.put("additionalParams", kb.getAdditionalParams());
            m.put("isDefault", kb.getIsDefault());
            m.put("createdBy", kb.getCreatedBy());
            m.put("shareConfig", kb.getShareConfig());
            m.put("createTime", kb.getCreateTime());
            m.put("updateTime", kb.getUpdateTime());
            m.put("docCount", counts.getOrDefault(kb.getKbId(), 0));
            out.add(m);
        }
        return out;
    }

    /**
     * 取某知识库的**分块配置原文**：{@code {"chunk_preset_id":"laws","chunk_parser_config":{...}}}。
     * 空 Map = 该库未配置，调用方沿用预设默认值与全局设置。
     * <p>本方法只负责"取出配置"，**合并顺序与生效值由 {@link ChunkPresets#resolveChunkProcessingParams}
     * 统一决定**——参数解析只允许有一处实现，避免各调用点各写一套合并规则。
     * <p>键名与参考实现保持一致（snake_case），便于配置可读性与跨实现对照。
     */
    public Map<String, Object> chunkConfigOf(String kbId) {
        if (kbId == null || kbId.isBlank()) return Map.of();
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null || kb.getAdditionalParams() == null || kb.getAdditionalParams().isBlank()) return Map.of();
        try {
            Map<String, Object> m = com.alibaba.fastjson2.JSON.parseObject(kb.getAdditionalParams());
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            // 配置损坏按"无覆盖"处理：解析是重活，不该被配置格式问题挡住
            return Map.of();
        }
    }

    /** 读检索参数（供接口直接回传；空 = 未配置） */
    public Map<String, Object> parseQueryParamsPublic(String json) {
        return parseJsonObj(json);
    }

    /**
     * 写检索参数（PUT /databases/{kb_id}/query-params）。
     * <p>body 直接是参数对象；传空对象 = 清空（恢复继承全局）。
     * 显式 set 以规避框架默认更新策略跳过 null 列的老问题。
     *
     * @return null 表示库不存在
     */
    public KnowledgeBase saveQueryParams(String kbId, Map<String, Object> params) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) return null;
        String json = (params == null || params.isEmpty())
                ? null : com.alibaba.fastjson2.JSON.toJSONString(params);
        kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getKbId, kbId)
                .set(KnowledgeBase::getQueryParams, json)
                .set(KnowledgeBase::getUpdateTime, LocalDateTime.now()));
        return kbMapper.selectById(kbId);
    }

    // ==================== 响应序列化与统计（字段与参考实现的响应结构对齐） ====================

    /**
     * 组装知识库响应对象，字段与参考实现的 serialize_knowledge_base 一一对应：
     * kb_id / name / description / kb_type / embedding_model_spec / llm_model_spec /
     * query_params / metadata / created_by / created_at / status / stats / row_count /
     * share_config / additional_params。
     * <p>未实现的附加项（mindmap/sample_questions/files）不输出——宁可缺字段，也不填假值。
     */
    public Map<String, Object> serializeKnowledgeBase(KnowledgeBase kb) {
        Map<String, Object> stats = statsOf(kb.getKbId());
        Map<String, Object> additional = parseJsonObj(kb.getAdditionalParams());
        Map<String, Object> metadata = new LinkedHashMap<>(additional);
        metadata.put("stats", stats);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kb_id", kb.getKbId());
        r.put("name", kb.getName());
        r.put("description", kb.getDescription());
        r.put("kb_type", kb.getKbType() == null || kb.getKbType().isBlank() ? "milvus" : kb.getKbType());
        r.put("embedding_model_spec", kb.getEmbeddingModelSpec());
        r.put("llm_model_spec", kb.getLlmModelSpec());
        r.put("query_params", parseJsonObj(kb.getQueryParams()));
        r.put("metadata", metadata);
        r.put("created_by", kb.getCreatedBy());
        r.put("created_at", kb.getCreateTime() == null ? null : kb.getCreateTime().toString());
        r.put("status", "已连接");
        r.put("stats", stats);
        r.put("row_count", stats.get("row_count"));
        r.put("share_config", kb.getShareConfig() == null || kb.getShareConfig().isBlank()
                ? null : parseJsonObj(kb.getShareConfig()));
        r.put("additional_params", additional);
        return r;
    }

    /**
     * 知识库统计，字段与参考实现的 _knowledge_base_stats 对齐：
     * file_count / folder_count / row_count / total_size / chunk_count / token_count /
     * pending_parse_count / pending_index_count / processing_count。
     * <p>本系统无"文件夹"与"表格行"概念，folder_count 恒为 0、row_count 取文件数
     * （与参考实现对非表格型知识库的回落一致）。
     */
    public Map<String, Object> statsOf(String kbId) {
        Set<String> docIds = docIdsOf(List.of(kbId));
        long fileCount = docIds.size();
        long totalSize = 0, chunkCount = 0, tokenCount = 0, pendingParse = 0, pendingIndex = 0, processing = 0;
        if (!docIds.isEmpty()) {
            List<AiDocument> docs = docMapper.selectList(
                    new LambdaQueryWrapper<AiDocument>().in(AiDocument::getId, docIds));
            for (AiDocument d : docs) {
                totalSize += d.getFileSize() == null ? 0 : d.getFileSize();
                chunkCount += d.getChunkCount() == null ? 0 : d.getChunkCount();
                // 计数口径与参考实现的两个待处理集合保持一致（待解析仅 uploaded；待索引含解析失败可重试）
                String st = d.getStatus();
                if (FileStatus.isPendingParse(st)) pendingParse++;
                if (FileStatus.isPendingIndex(st)) pendingIndex++;
                if (FileStatus.PARSING.equals(st) || FileStatus.INDEXING.equals(st)) processing++;
            }
            // 另计向量缺口：文件状态都是终态、但仍有块未向量化时，也算存在待索引
            long totalChunks = knowledgeMapper.selectCount(
                    new LambdaQueryWrapper<Knowledge>().in(Knowledge::getDocId, docIds));
            long indexedChunks = knowledgeMapper.selectCount(
                    new LambdaQueryWrapper<Knowledge>().in(Knowledge::getDocId, docIds)
                            .isNotNull(Knowledge::getVectorId).ne(Knowledge::getVectorId, ""));
            if (totalChunks > indexedChunks) pendingIndex++;
            chunkCount = Math.max(chunkCount, totalChunks);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("file_count", fileCount);
        stats.put("folder_count", 0);
        stats.put("row_count", fileCount);
        stats.put("total_size", totalSize);
        stats.put("chunk_count", chunkCount);
        stats.put("token_count", tokenCount);
        stats.put("pending_parse_count", pendingParse);
        stats.put("pending_index_count", pendingIndex);
        stats.put("processing_count", processing);
        return stats;
    }

    /** JSON 串 → Map（空/非法一律返回空 Map，调用方无需判空） */
    private static Map<String, Object> parseJsonObj(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> m = com.alibaba.fastjson2.JSON.parseObject(json);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ==================== 内部工具 ====================

    private void clearDefault() {
        List<KnowledgeBase> all = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getIsDefault, 1).eq(KnowledgeBase::getDeleted, 0));
        for (KnowledgeBase k : all) {
            k.setIsDefault(0);
            k.setUpdateTime(LocalDateTime.now());
            kbMapper.updateById(k);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strOr(Object o, String fallback) {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }

    /**
     * 把请求里的对象统一存成 JSON 文本：已是字符串则原样（调用方可能直接给了 JSON 串）；
     * Map/List 等结构化对象序列化；null 保持 null（= 未配置）。
     */
    private static String jsonStr(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s.isBlank() ? null : s;
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 解析「逗号分隔 id」为集合（智能体的 knowledgeBaseIds 用） */
    public static Set<String> splitIds(String csv) {
        Set<String> s = new HashSet<>();
        if (csv == null || csv.isBlank()) return s;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }
}
