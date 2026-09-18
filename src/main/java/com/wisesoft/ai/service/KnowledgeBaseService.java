package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.KnowledgeBaseMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.KnowledgeBase;
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

    /** 默认库缓存（避免每次检索都查库；is_default 变更时由 update/create 失效） */
    private volatile String cachedDefaultId;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper, AiDocumentMapper docMapper) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
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
    public KnowledgeBase create(Map<String, Object> body, String uid) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(str(body.get("name")));
        kb.setDescription(str(body.get("description")));
        kb.setQueryParams(str(body.get("queryParams")));
        kb.setIsDefault(toInt(body.get("isDefault"), 0));
        kb.setShareConfig(str(body.get("shareConfig")));
        kb.setCreatedBy(uid);
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
        LambdaUpdateWrapper<KnowledgeBase> upd = new LambdaUpdateWrapper<KnowledgeBase>().eq(KnowledgeBase::getId, id);
        if (body.containsKey("name")) upd.set(KnowledgeBase::getName, str(body.get("name")));
        if (body.containsKey("description")) upd.set(KnowledgeBase::getDescription, str(body.get("description")));
        if (body.containsKey("queryParams")) upd.set(KnowledgeBase::getQueryParams, str(body.get("queryParams")));
        if (body.containsKey("shareConfig")) upd.set(KnowledgeBase::getShareConfig, str(body.get("shareConfig")));
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
            cachedDefaultId = def.getId();
            return def.getId();
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
            long n = docMapper.selectCount(new LambdaQueryWrapper<AiDocument>().eq(AiDocument::getKbId, kb.getId()));
            m.put(kb.getId(), (int) n);
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
            m.put("id", kb.getId());
            m.put("name", kb.getName());
            m.put("description", kb.getDescription());
            m.put("queryParams", kb.getQueryParams());
            m.put("isDefault", kb.getIsDefault());
            m.put("createdBy", kb.getCreatedBy());
            m.put("shareConfig", kb.getShareConfig());
            m.put("createTime", kb.getCreateTime());
            m.put("updateTime", kb.getUpdateTime());
            m.put("docCount", counts.getOrDefault(kb.getId(), 0));
            out.add(m);
        }
        return out;
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
