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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /** 默认库固定图标：问渠品牌标（前端 KbIcon 按此值渲染 BrandMark；默认库图标不可修改） */
    public static final String ICON_BRAND = "wenqu";

    /** 个人默认知识库固定名称（问渠，与内置问渠智能体同品牌：智能体全局一个，默认库每人一个） */
    public static final String BUILTIN_NAME = "问渠";

    private final KnowledgeBaseMapper kbMapper;
    private final AiDocumentMapper docMapper;
    private final AgentMapper agentMapper;
    private final com.wisesoft.ai.service.ModelRegistryService modelRegistryService;
    /** 启动迁移把原全局默认库划转给第一个管理员（谁建归谁）；RoleService 仅依赖 Mapper，无循环依赖 */
    private final com.wisesoft.ai.mapper.UserMapper userMapper;
    private final com.wisesoft.ai.service.RoleService roleService;
    /** 读全局配置（chunk/解析参数等）；GraphRAG 兜底抽取模型已个人化——见 userConfigService */
    private final ConfigService configService;
    /** 库主个人兜底抽取模型（personalOnly：graphrag.modelRef 按库主 uid 显式解析） */
    private final UserConfigService userConfigService;

    /** 个人默认库 id 缓存（uid → kbId；任何库写入后整体清空，量小且请求内命中） */
    private final java.util.concurrent.ConcurrentHashMap<String, String> defaultIdByUid =
            new java.util.concurrent.ConcurrentHashMap<>();

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper, AiDocumentMapper docMapper,
                                AgentMapper agentMapper,
                                com.wisesoft.ai.service.ModelRegistryService modelRegistryService,
                                com.wisesoft.ai.mapper.UserMapper userMapper,
                                com.wisesoft.ai.service.RoleService roleService,
                                ConfigService configService,
                                UserConfigService userConfigService) {
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.agentMapper = agentMapper;
        this.modelRegistryService = modelRegistryService;
        this.userMapper = userMapper;
        this.roleService = roleService;
        this.configService = configService;
        this.userConfigService = userConfigService;
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

    /** 官方内置知识库（ManualSeedService 同步的「问渠使用手册」；未同步返回 null） */
    public KnowledgeBase builtinKb() {
        return kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getBuiltin, 1)
                .eq(KnowledgeBase::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * 新建：名称/向量模型必填。默认库不再由用户创建（每人一个、系统经 {@link #defaultId(String)} 懒创建），
     * body 带 isDefault 一律忽略；问渠品牌标为默认库专属，任何新建都不可使用。
     */
    public KnowledgeBase create(Map<String, Object> body, String uid) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(str(body.get("name")));
        kb.setDescription(str(body.get("description")));
        kb.setIsDefault(0);
        // 官方内置库只能由系统种子创建（ManualSeedService），任何用户创建路径都是普通库
        kb.setBuiltin(0);
        // 图标（与智能体同口径）：emoji 原样存；空=null 默认展示。品牌标为默认库专属：直接拒绝
        String icon = iconOf(body.get("icon"));
        if (ICON_BRAND.equals(icon)) {
            throw new com.wisesoft.ai.common.BizException("问渠品牌标为默认知识库专属，其它知识库不可使用");
        }
        kb.setIcon(icon);
        kb.setQueryParams(str(body.get("queryParams")));
        kb.setParseParams(str(body.get("parseParams")));
        // P1 GraphRAG 库级开关（新建时同样可带；漏了会让"新建时开开关"被静默丢弃）
        kb.setGraphEnabled(toInt(body.get("graphEnabled"), 0));
        // GraphRAG 抽取模型（库级，归库主）：空=回落库主个人设置的兜底抽取模型 graphrag.modelRef
        kb.setGraphModelRef(validateGraphModelRef(str(body.get("graphModelRef"))));
        kb.setShareConfig(str(body.get("shareConfig")));
        kb.setEmbeddingRef(validateEmbeddingRef(str(body.get("embeddingRef")), uid,
                com.wisesoft.ai.util.RequestUser.role()));
        // 开关开启时保存即校验抽取模型可用（库级优先、回落全局；对库主不可用一律拦下，
        // 避免"开关开着、解析后抽取永远失败"的静默状态）
        ensureGraphModelUsable(kb.getGraphEnabled(), kb.getGraphModelRef(), uid,
                com.wisesoft.ai.util.RequestUser.role());
        // 库级参数里的模型引用归属校验（新建时全部视作"变更"）：防构造请求把重排/视觉引用指到他人供应商
        validateChangedParamRefs(null, null, kb.getQueryParams(), kb.getParseParams(), uid, uid,
                com.wisesoft.ai.util.RequestUser.role());
        kb.setCreatedBy(uid);
        kb.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        kb.setCreateTime(now);
        kb.setUpdateTime(now);
        kbMapper.insert(kb);
        defaultIdByUid.clear();
        return kb;
    }

    /** 更新：仅更新 body 中出现的字段；库被删除时不处理 */
    public KnowledgeBase update(String id, Map<String, Object> body) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) return null;
        // 官方内置库内容随版本自动同步（ManualSeedService），不接受任何人工编辑（控制器 requireManage 已拦，
        // 这里再拦一层：防止未来新调用路径绕过控制器直接进服务层）
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1) {
            throw new com.wisesoft.ai.common.BizException("官方内置知识库「" + kb.getName() + "」不可修改（内容随版本自动同步）");
        }
        // 只在 body 中出现的字段才写（含显式 null = 清空该维度回退到继承）
        LambdaUpdateWrapper<KnowledgeBase> upd = new LambdaUpdateWrapper<KnowledgeBase>().eq(KnowledgeBase::getId, id);
        if (body.containsKey("name")) {
            // 默认库（问渠）名称固定：同内置智能体口径——与现值不同直接拒绝（fail-loud）；
            // 前端置灰回显原名，正常保存无差异不会被拦
            String requested = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
            if (kb.getIsDefault() != null && kb.getIsDefault() == 1 && !requested.equals(kb.getName())) {
                throw new com.wisesoft.ai.common.BizException("默认知识库「" + kb.getName() + "」的名称不可修改");
            }
            upd.set(KnowledgeBase::getName, requested);
        }
        if (body.containsKey("description")) upd.set(KnowledgeBase::getDescription, str(body.get("description")));
        // 图标：emoji 原样存；空串归一为 null（= 默认展示）。
        // 品牌标为默认库专属：默认库恒为 wenqu（改其它值拒绝）；非默认库用 wenqu 也拒绝。
        // isDefault 不再是可写字段（每人一个、系统懒创建），body 带了也忽略——无「晋升为默认库」路径
        boolean isDefaultRow = kb.getIsDefault() != null && kb.getIsDefault() == 1;
        if (body.containsKey("icon")) {
            String icon = iconOf(body.get("icon"));
            if (isDefaultRow && !ICON_BRAND.equals(icon)) {
                throw new com.wisesoft.ai.common.BizException("默认知识库固定使用问渠品牌标，图标不可修改");
            }
            if (!isDefaultRow && ICON_BRAND.equals(icon)) {
                throw new com.wisesoft.ai.common.BizException("问渠品牌标为默认知识库专属，其它知识库不可使用");
            }
            upd.set(KnowledgeBase::getIcon, icon);
        }
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
        // P1 GraphRAG 库级开关（默认关；开启后解析完成自动抽三元组，检索一跳图扩展）
        int resultingGraph = kb.getGraphEnabled() == null ? 0 : kb.getGraphEnabled();
        if (body.containsKey("graphEnabled")) {
            Object v = body.get("graphEnabled");
            boolean on = Boolean.TRUE.equals(v) || "1".equals(String.valueOf(v)) || Integer.valueOf(1).equals(v);
            upd.set(KnowledgeBase::getGraphEnabled, on ? 1 : 0);
            resultingGraph = on ? 1 : 0;
        }
        // GraphRAG 抽取模型（库级绑定，可清除=回落库主个人兜底）：提交即校验引用有效性/类型/归属
        String resultingGraphRef = kb.getGraphModelRef();
        if (body.containsKey("graphModelRef")) {
            resultingGraphRef = validateGraphModelRef(str(body.get("graphModelRef")));
            upd.set(KnowledgeBase::getGraphModelRef, resultingGraphRef);
        }
        // 开关开启时保存即校验抽取模型可用（含"仅改名字"的保存：存量开着开关却没模型的库
        // 在此被拦下并给出明确指引，不让它在运行时静默失败）
        ensureGraphModelUsable(resultingGraph, resultingGraphRef,
                kb.getCreatedBy() == null || kb.getCreatedBy().isBlank()
                        ? com.wisesoft.ai.util.RequestUser.uid() : kb.getCreatedBy(),
                com.wisesoft.ai.util.RequestUser.role());
        // 库级参数里的模型引用归属校验（仅校验本次变更的引用；未变更的存量直通）
        if (body.containsKey("queryParams") || body.containsKey("parseParams")) {
            String refOwner = kb.getCreatedBy() == null || kb.getCreatedBy().isBlank()
                    ? com.wisesoft.ai.util.RequestUser.uid() : kb.getCreatedBy();
            validateChangedParamRefs(kb.getQueryParams(), kb.getParseParams(),
                    body.containsKey("queryParams") ? str(body.get("queryParams")) : kb.getQueryParams(),
                    body.containsKey("parseParams") ? str(body.get("parseParams")) : kb.getParseParams(),
                    refOwner, com.wisesoft.ai.util.RequestUser.uid(), com.wisesoft.ai.util.RequestUser.role());
        }
        upd.set(KnowledgeBase::getUpdateTime, LocalDateTime.now());
        // 必须显式 set：updateById 走 NOT_NULL 策略会跳过 null 列，
        // 导致「清空检索参数 → 恢复继承全局」这类操作静默失效（本项目已踩过同一坑）
        kbMapper.update(null, upd);
        defaultIdByUid.clear();
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
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1) return "官方内置知识库不可删除（内容随版本自动同步）";
        long n = docMapper.selectCount(new LambdaQueryWrapper<AiDocument>().eq(AiDocument::getKbId, id));
        if (n > 0) return "该知识库下还有 " + n + " 个文档，请先移出或删除文档";
        // 必须走 deleteById（MyBatis-Plus 的逻辑删除语句）：deleted 是全局 logic-delete-field，
        // updateById 生成的 SET 子句会剔除该列（只在 WHERE 补 deleted=0）⇒
        // 「setDeleted(1) + updateById」静默无效，库删了还在列表里（与产物删除同一坑）。
        // update_time 由库侧 `on update CURRENT_TIMESTAMP` 随本行 UPDATE 自动刷新。
        kbMapper.deleteById(id);
        defaultIdByUid.clear();
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
     * 校验并归一化本库绑定向量模型引用：**必填且必须为可解析的供应商引用**——向量空间与索引一一对应，
     * 没有任何运行时兜底（全局 embedding.* 网关与遗留裸模型名回落已移除；存量裸名由启动迁移改写为引用）。
     * <p>
     * 归属校验：引用必须对「绑定人」可用（仅该用户自己登记的供应商；管理员级另可见全部）——
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
        if (modelRegistryService.resolveReference(v) == null) {
            throw new com.wisesoft.ai.common.BizException("向量模型无效或已被删除，请重新选择");
        }
        String type = modelRegistryService.referenceType(v);
        if (type != null && !com.wisesoft.ai.service.ModelRegistryService.TYPE_EMBEDDING.equals(type)) {
            throw new com.wisesoft.ai.common.BizException("知识库向量模型需为向量类型（当前所选为 " + type + " 类型）");
        }
        return v;
    }

    /**
     * 库级参数（queryParams / parseParams）里模型引用的归属校验：新增/变更的引用必须对
     * 「库主或当前操作者」可用——防构造请求把重排/视觉引用指到第三方的供应商上（借用他人 Key）。
     * 两人都属该库的合法配置者（共享管理场景各用各的模型），故任一归属即放行。
     * 未变更的存量引用放行：历史供应商归属经 2026-09 迁移，用现状值回刷旧库时可能已不满足
     * 新口径，"改个名字"不应被历史数据卡住（真正防的是"把引用改到别人头上"这个动作）。
     */
    private void validateChangedParamRefs(String oldQuery, String oldParse, String newQuery, String newParse,
                                          String ownerUid, String uid, String role) {
        checkChangedRef(strJsonAttr(oldQuery, "rerank.model"), strJsonAttr(newQuery, "rerank.model"), ownerUid, uid, role);
        checkChangedRef(strJsonAttr(oldParse, "visionRef"), strJsonAttr(newParse, "visionRef"), ownerUid, uid, role);
        checkChangedRef(strJsonAttr(oldParse, "ocrRef"), strJsonAttr(newParse, "ocrRef"), ownerUid, uid, role);
    }

    private void checkChangedRef(String oldRef, String newRef, String ownerUid, String uid, String role) {
        if (newRef == null || newRef.isBlank()) return;                          // 清空/未设置：无引用
        if (newRef.equals(oldRef == null ? "" : oldRef.trim())) return;          // 未变更：存量直通
        try {
            modelRegistryService.assertUsable(newRef, ownerUid, "");
        } catch (com.wisesoft.ai.common.BizException e) {
            modelRegistryService.assertUsable(newRef, uid, role);                // 操作者自己的模型同样放行
        }
    }

    /** 读 JSON 字符串对象的指定属性（解析失败/非对象返回 null——参数 JSON 的合法性由各消费方兜底） */
    private static String strJsonAttr(String json, String attr) {
        if (json == null || json.isBlank()) return null;
        try {
            com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(json);
            return o == null ? null : o.getString(attr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * GraphRAG 抽取模型校验（库级，可空=回落全局）：非空时必须可解析、对库主可用、且为聊天类型。
     * 与 {@link #validateEmbeddingRef} 同口径（谁建库用谁的模型），差别在不强制必填。
     */
    public String validateGraphModelRef(String ref) {
        String v = ref == null ? "" : ref.trim();
        if (v.isEmpty()) return null;
        if (modelRegistryService.resolveReference(v) == null) {
            throw new com.wisesoft.ai.common.BizException("GraphRAG 抽取模型无效或已被删除，请重新选择");
        }
        String type = modelRegistryService.referenceType(v);
        if (type != null && !com.wisesoft.ai.service.ModelRegistryService.TYPE_CHAT.equals(type)) {
            throw new com.wisesoft.ai.common.BizException("GraphRAG 抽取模型需为聊天类型（当前所选为 " + type + " 类型）");
        }
        return v;
    }

    /**
     * 开启 GraphRAG 时的抽取模型把关：库级引用优先，空则回落**库主个人设置**的兜底抽取模型
     * （graphrag.modelRef，personalOnly：按库主 uid 显式解析——抽取异步执行、没有请求线程，
     * ThreadLocal 个人覆盖拿不到）。
     * 保存时即校验"对库主可用"——抽取是异步任务、没有请求身份，运行时判权只按库主，
     * 非库主可用的模型（他人个人供应商）必然被拒，不能等到抽取时才发现。
     */
    private void ensureGraphModelUsable(int graphEnabled, String kbRef, String uid, String role) {
        if (graphEnabled != 1) return;
        String effective = kbRef == null || kbRef.isBlank()
                ? userConfigService.personalValue(uid, "graphrag.modelRef").trim() : kbRef.trim();
        if (effective.isEmpty()) {
            throw new com.wisesoft.ai.common.BizException(
                    "开启 GraphRAG 需先选择抽取模型（本库未绑定，个人设置也未配置兜底抽取模型）");
        }
        try {
            modelRegistryService.assertUsable(effective, uid, role);
        } catch (com.wisesoft.ai.common.BizException e) {
            // 补 GraphRAG 上下文：用户看到"无法使用"时未必知道生效的是库级绑定还是系统兜底模型
            throw new com.wisesoft.ai.common.BizException("GraphRAG 抽取模型不可用：" + e.getMessage()
                    + "（请在知识库编辑里选择你登记过的聊天模型）");
        }
        String type = modelRegistryService.referenceType(effective);
        if (type != null && !com.wisesoft.ai.service.ModelRegistryService.TYPE_CHAT.equals(type)) {
            throw new com.wisesoft.ai.common.BizException("GraphRAG 抽取模型需为聊天类型（当前生效配置为 " + type + " 类型）");
        }
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
     * 个人默认知识库 id（get-or-create，懒创建）：每人一个「问渠」（is_default=1 且 created_by=本人），
     * 未指定归属的上传/导入/移库都落进自己的默认库。自动创建的库<b>不绑定向量模型</b>——
     * 绑定归使用者（只能用自己登记的供应商），首次使用前在知识库页自行绑定（storeForKb 对空绑定 fail-loud）。
     */
    public String defaultId(String uid) {
        String owner = (uid == null || uid.isBlank()) ? "anonymous" : uid.trim();
        String cached = defaultIdByUid.get(owner);
        if (cached != null) return cached;
        synchronized (this) {
            cached = defaultIdByUid.get(owner);
            if (cached != null) return cached;
            KnowledgeBase def = kbMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getIsDefault, 1)
                    .eq(KnowledgeBase::getCreatedBy, owner)
                    .eq(KnowledgeBase::getDeleted, 0)
                    .last("LIMIT 1"));
            if (def == null) {
                def = new KnowledgeBase();
                def.setName(BUILTIN_NAME);
                def.setDescription("你的系统默认知识库：未指定归属的文档都归入本库；先绑定你自己的向量模型即可使用。");
                def.setIsDefault(1);
                // 默认库恒为问渠品牌标（与新建/编辑的强制口径一致）
                def.setIcon(ICON_BRAND);
                def.setDeleted(0);
                def.setCreatedBy(owner);
                LocalDateTime now = LocalDateTime.now();
                def.setCreateTime(now);
                def.setUpdateTime(now);
                kbMapper.insert(def);
                log.info("[KB] 已为用户 {} 懒创建个人默认知识库「问渠」: {}（未绑定向量模型，使用前请先绑定）",
                        owner, def.getId());
            } else if (def.getId() == null || def.getId().isBlank()) {
                // 历史空主键行：ASSIGN_UUID 只补 null 不补 ''，空 id 会让编辑保存 404——触达即自愈
                healEmptyDefaultId(def);
            }
            defaultIdByUid.put(owner, def.getId());
            return def.getId();
        }
    }

    /** 第一个管理员级用户 uid（按创建时间最早；供存量默认库划转） */
    private String firstAdminUid() {
        List<String> codes = roleService.adminCodes();
        if (codes.isEmpty()) return null;
        List<com.wisesoft.ai.model.User> admins = userMapper.selectList(
                new LambdaQueryWrapper<com.wisesoft.ai.model.User>()
                        .in(com.wisesoft.ai.model.User::getRole, codes)
                        .orderByAsc(com.wisesoft.ai.model.User::getCreateTime));
        for (com.wisesoft.ai.model.User u : admins) {
            if (u.getUid() != null && !u.getUid().isBlank()) return u.getUid();
        }
        return null;
    }

    /**
     * 启动迁移：默认库「每人一张」校准（按归属人分组）。
     * <ul>
     *   <li>同一归属人出现多张默认库时保留最早一张，其余降级为普通库。注意分组口径是归属人——
     *       不能全局只留一张：否则每次重启都会把其他用户已懒创建的个人默认库误降级（2026-10-02 修复）；</li>
     *   <li>遗留全局默认库（created_by 为 system/空）按谁建归谁划转：归第一个管理员；</li>
     *   <li>名称统一为「问渠」、品牌标回填；空主键自愈；无归属历史文档归入该库（kb_id 必填不变量）。</li>
     * </ul>
     * 其余用户此后首次触达时由 {@link #defaultId(String)} 懒创建各自的空默认库。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateDefaultKbPerUserOnStartup() {
        try {
            List<KnowledgeBase> defs = kbMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getIsDefault, 1)
                    .eq(KnowledgeBase::getDeleted, 0)
                    .orderByAsc(KnowledgeBase::getCreateTime)
                    .orderByAsc(KnowledgeBase::getId));
            // 每个归属人只保留最早一张；created_by 为 system/空 的遗留全局默认库视作同一组（组主 = "__legacy__"）
            Map<String, KnowledgeBase> keptByOwner = new LinkedHashMap<>();
            List<KnowledgeBase> demoted = new ArrayList<>();
            for (KnowledgeBase d : defs) {
                String owner = (d.getCreatedBy() == null || d.getCreatedBy().isBlank()
                        || "system".equals(d.getCreatedBy())) ? "__legacy__" : d.getCreatedBy();
                if (keptByOwner.putIfAbsent(owner, d) != null) demoted.add(d);
            }
            for (KnowledgeBase extra : demoted) {
                kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, extra.getId())
                        .set(KnowledgeBase::getIsDefault, 0)
                        .set(KnowledgeBase::getUpdateTime, LocalDateTime.now()));
                log.warn("[KB] 默认知识库每人一张，同归属人的多余默认行已降级为普通库: {}（{}）", extra.getName(), extra.getId());
            }
            KnowledgeBase def = keptByOwner.remove("__legacy__");
            if (def == null && !keptByOwner.isEmpty()) {
                def = keptByOwner.values().iterator().next();
            }
            if (def == null) return;
            if (def.getId() == null || def.getId().isBlank()) {
                healEmptyDefaultId(def);
                defaultIdByUid.clear();
            }
            String owner = def.getCreatedBy();
            if (owner == null || owner.isBlank() || "system".equals(owner)) {
                String admin = firstAdminUid();
                if (admin != null && !admin.equals(owner)) {
                    kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                            .eq(KnowledgeBase::getId, def.getId())
                            .set(KnowledgeBase::getCreatedBy, admin));
                    def.setCreatedBy(admin);
                    log.info("[KB] 原全局默认知识库已按谁建归谁划转给管理员 {}: {}（{}）", admin, def.getName(), def.getId());
                }
            }
            if (!BUILTIN_NAME.equals(def.getName())) {
                kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, def.getId())
                        .set(KnowledgeBase::getName, BUILTIN_NAME));
                log.info("[KB] 默认知识库已更名为「{}」: {}", BUILTIN_NAME, def.getId());
            }
            if (def.getIcon() == null || def.getIcon().isBlank()) {
                kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, def.getId())
                        .set(KnowledgeBase::getIcon, ICON_BRAND));
                log.info("[KB] 默认知识库图标已回填为问渠品牌标");
            }
            // 无归属历史文档 → 归入默认库（kb_id 必填不变量；正常已被更早的迁移处理，这里兜底）
            Long orphans = docMapper.selectCount(new LambdaQueryWrapper<AiDocument>()
                    .and(w -> w.eq(AiDocument::getKbId, "").or().isNull(AiDocument::getKbId)));
            if (orphans != null && orphans > 0) {
                docMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                        .and(w -> w.eq(AiDocument::getKbId, "").or().isNull(AiDocument::getKbId))
                        .set(AiDocument::getKbId, def.getId()));
                log.info("[KB] {} 个无归属历史文档已归入默认知识库 {}", orphans, def.getId());
            }
            defaultIdByUid.put(def.getCreatedBy(), def.getId());
        } catch (Exception e) {
            // 启动迁移失败不阻断应用；defaultId(uid) 触达时还有 get-or-create 兜底
            log.warn("[KB] 默认知识库每用户化迁移失败（触达时重试）: {}", e.getMessage());
        }
    }

    /** 空主键默认库自愈本体：换新 id（兼容 '' 与 NULL）+ 迁移 kb_id 空值的历史文档引用。 */
    private synchronized void healEmptyDefaultId(KnowledgeBase def) {
        String newId = java.util.UUID.randomUUID().toString();
        // 只改默认标记行：万一历史上还有其它空主键行，不能把它们一并改成同一个 id（主键冲突）
        kbMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getIsDefault, 1)
                .and(w -> w.eq(KnowledgeBase::getId, "").or().isNull(KnowledgeBase::getId))
                .set(KnowledgeBase::getId, newId));
        docMapper.update(null, new LambdaUpdateWrapper<AiDocument>()
                .eq(AiDocument::getKbId, "")
                .or()
                .isNull(AiDocument::getKbId)
                .set(AiDocument::getKbId, newId));
        def.setId(newId);
        log.warn("[KB] 默认知识库主键为空（历史数据），已自愈为新 id {}", newId);
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
     * 把文档移到某个知识库（文档管理页切换归属用）；kbId 空 = 移入**当前用户**的默认库（显式写库 ID）。
     *
     * @param uid 操作者 uid（默认库按人解析）
     * @return false 表示文档不存在
     */
    public boolean moveDoc(String docId, String kbId, String uid) {
        AiDocument doc = docMapper.selectById(docId);
        if (doc == null) return false;
        String target = kbId == null || kbId.isBlank() ? defaultId(uid) : kbId.trim();
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
            // 图标随列表下发：卡片按 KbIcon 口径渲染（'wenqu'=品牌标 / emoji / 空=默认库图标）
            m.put("icon", kb.getIcon());
            m.put("queryParams", kb.getQueryParams());
            m.put("parseParams", kb.getParseParams());
            // 向量模型引用要随列表下发：卡片展示绑定模型名 + 编辑弹窗回显（缺失会被当成"未绑定"）
            m.put("embeddingRef", kb.getEmbeddingRef());
            m.put("embeddingDimensions", kb.getEmbeddingDimensions());
            // GraphRAG 开关与库级抽取模型同样要随列表下发：编辑弹窗回显（缺失会让开关显示为关，
            // 保存时把已开启的图谱开关静默关掉）
            m.put("graphEnabled", kb.getGraphEnabled());
            m.put("graphModelRef", kb.getGraphModelRef());
            m.put("isDefault", kb.getIsDefault());
            // 官方内置库标记随列表下发：前端挂「官方」徽标并隐藏编辑/删除入口
            m.put("builtin", kb.getBuiltin());
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 图标归一化（与智能体 asText 同口径）：null/空串 → null（默认展示）；超长截断到 32（列宽 VARCHAR(32)） */
    private static String iconOf(Object o) {
        if (o == null) return null;
        String v = String.valueOf(o).trim();
        if (v.isEmpty()) return null;
        return v.length() > 32 ? v.substring(0, 32) : v;
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
