package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.mapper.AgentMapper;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.KnowledgeBaseMapper;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.KnowledgeBase;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper kbMapper;
    private final AiDocumentMapper docMapper;
    private final AgentMapper agentMapper;
    private final com.wisesoft.ai.service.ModelRegistryService modelRegistryService;

    /** 默认库缓存（避免每次检索都查库；is_default 变更时由 update/create 失效） */
    private volatile String cachedDefaultId;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper, AiDocumentMapper docMapper,
                                AgentMapper agentMapper,
                                com.wisesoft.ai.service.ModelRegistryService modelRegistryService) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.agentMapper = agentMapper;
        this.modelRegistryService = modelRegistryService;
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

    /** 新建：名称/向量模型必填；isDefault=1 时先把其它库的默认标记清掉（保证唯一默认库） */
    public KnowledgeBase create(Map<String, Object> body, String uid) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(str(body.get("name")));
        kb.setDescription(str(body.get("description")));
        kb.setQueryParams(str(body.get("queryParams")));
        kb.setParseParams(str(body.get("parseParams")));
        kb.setIsDefault(toInt(body.get("isDefault"), 0));
        kb.setShareConfig(str(body.get("shareConfig")));
        kb.setEmbeddingRef(validateEmbeddingRef(str(body.get("embeddingRef")), uid,
                com.wisesoft.ai.util.RequestUser.role()));
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
        if (body.containsKey("parseParams")) upd.set(KnowledgeBase::getParseParams, str(body.get("parseParams")));
        if (body.containsKey("shareConfig")) upd.set(KnowledgeBase::getShareConfig, str(body.get("shareConfig")));
        if (body.containsKey("embeddingRef")) {
            // 归属校验按**当前操作者**判（create/update 都只在请求线程里被控制器调用；
            // 能走到这里的操作者即该库的管理者，见 KnowledgeBaseController 的资源级判定）
            upd.set(KnowledgeBase::getEmbeddingRef, validateEmbeddingRef(str(body.get("embeddingRef")),
                    com.wisesoft.ai.util.RequestUser.uid(), com.wisesoft.ai.util.RequestUser.role()));
            // 模型切换后维度以重嵌结果为准，先清掉旧记录
            upd.set(KnowledgeBase::getEmbeddingDimensions, null);
        }
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
        // 级联清理：从关联智能体（含子智能体）的 knowledgeBaseIds 里摘除本库 ID，避免悬挂引用
        cleanupAgentReferences(id);
        return null;
    }

    /** 知识库删除后同步摘除各智能体 knowledgeBaseIds 中的该库 ID（like 预筛 + splitIds 精确匹配） */
    private void cleanupAgentReferences(String kbId) {
        List<Agent> candidates = agentMapper.selectList(new LambdaQueryWrapper<Agent>()
                .like(Agent::getKnowledgeBaseIds, kbId));
        for (Agent a : candidates) {
            Set<String> ids = splitIds(a.getKnowledgeBaseIds());
            if (!ids.remove(kbId)) continue;
            a.setKnowledgeBaseIds(ids.isEmpty() ? null : String.join(",", ids));
            agentMapper.updateById(a);
            log.info("[KB] 智能体 {}（{}）已摘除对已删除知识库 {} 的引用", a.getId(), a.getName(), kbId);
        }
    }

    /**
     * 校验并归一化本库绑定向量模型引用：**必填**——向量空间与索引一一对应，没有可用的运行时兜底；
     * 历史空值由启动迁移回填（引用或遗留模型名，遗留名经 DynamicEmbeddingModel 走遗留网关）。
     * <p>
     * 归属校验：引用必须对「绑定人」可用（平台级供应商 + 该用户自己登记的个人级）——
     * 否则会出现「张三的知识库挂在李四的 Key 上」。
     *
     * @param ref  前端提交的向量模型引用
     * @param uid  操作者 uid（知识库归属人）
     * @param role 操作者角色编码
     * @return 归一化后的引用（trim 后）
     */
    public String validateEmbeddingRef(String ref, String uid, String role) {
        String v = ref == null ? "" : ref.trim();
        if (v.isEmpty()) {
            throw new com.wisesoft.ai.common.BizException("请为本知识库选择向量模型（必填）");
        }
        modelRegistryService.assertUsable(v, uid, role);
        if (modelRegistryService.resolveReference(v) == null && v.contains("/")) {
            throw new com.wisesoft.ai.common.BizException("向量模型无效或已被删除，请重新选择");
        }
        String type = modelRegistryService.referenceType(v);
        if (type != null && !com.wisesoft.ai.service.ModelRegistryService.TYPE_EMBEDDING.equals(type)) {
            throw new com.wisesoft.ai.common.BizException("知识库向量模型需为向量类型（当前所选为 " + type + " 类型）");
        }
        return v;
    }

    /** 绑定了向量模型的未删除知识库（per-KB 向量索引按此枚举；向量模型必绑后即全部知识库） */
    public List<KnowledgeBase> listCustomEmbedding() {
        return kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getDeleted, 0)
                .isNotNull(KnowledgeBase::getEmbeddingRef)
                .ne(KnowledgeBase::getEmbeddingRef, ""));
    }

    // ==================== 检索侧支撑 ====================

    /**
     * 默认库 ID：没有默认库时自动建一个「默认知识库」，保证新建文档始终有归属、检索范围不会因缺库而变空。
     * （历史 kb_id 为空的文档已由启动迁移一次性归入默认库，此后 kb_id 必填。）
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
     * 库 ID 集合 → 文档 ID 集合（检索按库过滤用）。文档 kb_id 必填（历史空值已由启动迁移归库）。
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
        return ids;
    }

    /**
     * 把文档移到某个知识库（文档管理页切换归属用）；kbId 空 = 移入默认库（显式写默认库 ID）。
     *
     * @return false 表示文档不存在
     */
    public boolean moveDoc(String docId, String kbId) {
        AiDocument doc = docMapper.selectById(docId);
        if (doc == null) return false;
        String target = kbId == null || kbId.isBlank() ? defaultId() : kbId.trim();
        // 显式 set：避免 NOT_NULL 策略跳过导致移动静默失效
        docMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getId, docId)
                .set(AiDocument::getKbId, target)
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
            m.put("parseParams", kb.getParseParams());
            // 向量模型引用要随列表下发：卡片展示绑定模型名 + 编辑弹窗回显（缺失会被当成"未绑定"）
            m.put("embeddingRef", kb.getEmbeddingRef());
            m.put("embeddingDimensions", kb.getEmbeddingDimensions());
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
