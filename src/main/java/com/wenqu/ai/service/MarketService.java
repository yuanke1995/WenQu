package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.MarketInstallMapper;
import com.wenqu.ai.mapper.MarketItemMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.mapper.UserMcpMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.MarketInstall;
import com.wenqu.ai.model.MarketItem;
import com.wenqu.ai.model.User;
import com.wenqu.ai.model.UserMcp;
import com.wenqu.ai.model.Workflow;
import com.wenqu.ai.skill.UserSkillStore;
import com.wenqu.ai.skill.WenquSkillRepository;
import com.wenqu.ai.util.RequestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 市场：上架 → 发现 → 一键安装。物品类型（{@code item_type}）：
 * <ul>
 *   <li><b>skill</b> 技能：payload = zip 快照，安装 = 复制进本人技能目录（与上传 zip 同校验链）；</li>
 *   <li><b>agent</b> 应用模板：payload = {agent 配置快照, 可选绑定工作流 DSL}，安装 = <b>fork</b>
 *       成安装人自己的智能体（引用按安装人权限裁剪并逐项告警）；</li>
 *   <li><b>workflow</b> 工作流：payload = {name, description, dsl}，安装 = 创建为自己的草稿；</li>
 *   <li>官方条目（{@code author_uid=system}，内置模板启动期种入）：显示「平台官方」，不可删只可下架。</li>
 * </ul>
 *
 * <h3>核心语义：安装即复制（fork）</h3>
 * 物品包是上架那一刻的<b>快照</b>（skill=zip、agent/workflow=JSON，落 {@code user-data/market/{type}/{id}}），
 * 安装把快照完整复制进用户自己的资产，装完与市场条目再无引用关系——
 * 与全平台「业务资产归使用者」的所有权约定一致。因此：
 * <ul>
 *   <li>没有订阅/运行时依赖：作者改版、下架、删除都不影响已装副本；</li>
 *   <li>「有更新」由<b>内容指纹</b>（快照文件的 SHA-256）驱动，不依赖版本号——
 *       作者忘升版本号也能检出内容变化，反之只改市场卡片文案（简介/标签）不会误报；</li>
 *   <li>上架校验链与匿名上传完全相同（市场快照内容来自其他用户，信任边界不放宽）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class MarketService {

    private static final int MAX_SUMMARY = 256;
    private static final int MAX_NAME = 128;
    private static final int MAX_VERSION = 32;
    private static final int MAX_LIST = 200;
    private static final int MAX_SKILL_MD_BYTES = 512 * 1024;
    private static final int MAX_PAYLOAD_JSON_BYTES = 512 * 1024;

    private final MarketItemMapper itemMapper;
    private final MarketInstallMapper installMapper;
    private final UserSkillStore skillStore;
    private final WenquSkillRepository skillRepository;
    private final UserMapper userMapper;
    private final RoleService roleService;
    private final AgentService agentService;
    private final WorkflowService workflowService;
    private final KnowledgeBaseMapper kbMapper;
    private final UserMcpMapper userMcpMapper;
    private final ResourceVisibilityService visibility;
    private final UserRoleService userRoleService;

    /** 物品包根目录（与 user-data 同域，可配）。 */
    private final Path payloadRoot;

    public MarketService(
            @Value("${wenqu.market.payload-dir:user-data/market}") String payloadDir,
            MarketItemMapper itemMapper, MarketInstallMapper installMapper,
            UserSkillStore skillStore, WenquSkillRepository skillRepository,
            UserMapper userMapper, RoleService roleService,
            AgentService agentService, WorkflowService workflowService,
            KnowledgeBaseMapper kbMapper, UserMcpMapper userMcpMapper,
            ResourceVisibilityService visibility, UserRoleService userRoleService) {
        this.payloadRoot = Paths.get(payloadDir == null || payloadDir.isBlank()
                ? "user-data/market" : payloadDir).toAbsolutePath().normalize();
        this.itemMapper = itemMapper;
        this.installMapper = installMapper;
        this.skillStore = skillStore;
        this.skillRepository = skillRepository;
        this.userMapper = userMapper;
        this.roleService = roleService;
        this.agentService = agentService;
        this.workflowService = workflowService;
        this.kbMapper = kbMapper;
        this.userMcpMapper = userMcpMapper;
        this.visibility = visibility;
        this.userRoleService = userRoleService;
    }

    // ==================== 读：列表 / 详情 ====================

    /**
     * 市场列表：上架中的物品 + 我自己上架的（含已下架，便于重新上架）。
     * 每条带 installed / hasUpdate / mine 徽标与作者显示名。
     */
    public List<Map<String, Object>> list(String uid, String type, String keyword,
                                          String tag, boolean mineOnly) {
        QueryWrapper<MarketItem> w = listWrapper(uid, type, keyword, tag, mineOnly);
        w.orderByDesc("update_time").last("LIMIT " + MAX_LIST);
        List<MarketItem> items = itemMapper.selectList(w);

        Map<String, MarketInstall> installed = installsOf(uid,
                items.stream().map(MarketItem::getId).collect(Collectors.toSet()));
        Map<String, String> authorNames = authorNames(items.stream()
                .map(MarketItem::getAuthorUid).collect(Collectors.toSet()));
        return items.stream()
                .map(i -> view(i, installed.get(i.getId()), authorNames, uid))
                .collect(Collectors.toList());
    }

    /**
     * 页签计数：三个类型页签各数自己那一类，「我的上架」跨类型。
     * 计数不能跟着当前页签的类型过滤，否则切页签时三个角标会全变成同一类物品的数量。
     * 与列表共用关键词/标签条件；不套列表的条数上限，因此列表被截断时计数仍是全量。
     */
    public Map<String, Object> counts(String uid, String keyword, String tag) {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (String type : List.of(MarketItem.TYPE_SKILL, MarketItem.TYPE_AGENT, MarketItem.TYPE_WORKFLOW)) {
            byType.put(type, count(uid, type, keyword, tag, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byType", byType);
        out.put("mine", count(uid, null, keyword, tag, true));
        return out;
    }

    private long count(String uid, String type, String keyword, String tag, boolean mineOnly) {
        return itemMapper.selectCount(listWrapper(uid, type, keyword, tag, mineOnly));
    }

    /** 列表与计数共用的条件构造：mineOnly=false 含「上架中的 + 我自己上架的（含已下架）」。 */
    private QueryWrapper<MarketItem> listWrapper(String uid, String type, String keyword,
                                                 String tag, boolean mineOnly) {
        QueryWrapper<MarketItem> w = new QueryWrapper<>();
        if (type != null && !type.isBlank()) w.eq("item_type", type.trim());
        if (mineOnly) {
            w.eq("author_uid", uid);
        } else {
            w.and(q -> q.eq("status", MarketItem.STATUS_ONLINE).or().eq("author_uid", uid));
        }
        String k = keyword == null ? "" : keyword.trim();
        if (!k.isEmpty()) {
            w.and(q -> q.like("name", k).or().like("summary", k).or().like("tags", k));
        }
        if (tag != null && !tag.isBlank()) w.like("tags", tag.trim());
        return w;
    }

    /** 详情：元信息 + 详细介绍 + 内容预览（skill=SKILL.md 原文；agent/workflow=载荷 JSON，装前可审阅）。 */
    public Map<String, Object> detail(String uid, String id) {
        MarketItem item = requireVisible(uid, id);
        Map<String, Object> m = view(item, installsOf(uid, Set.of(id)).get(id), Map.of(), uid);
        m.put("description", item.getDescription());
        if (MarketItem.TYPE_SKILL.equals(item.getItemType())) {
            m.put("skillMd", readSkillMdFromPayload(item));
        } else {
            m.put("configJson", prettyPayload(item));
        }
        return m;
    }

    // ==================== 上架 ====================

    /**
     * 上架（三类型）：skill=自己安装的技能（ref=技能名）；agent=自己可管理的智能体（ref=agentId）；
     * workflow=自己可管理的工作流（ref=workflowId）。
     * <p>同一作者同 item_type + item_key 重复上架 = 更新同一条（快照覆盖、重新上架）。
     */
    public Map<String, Object> publish(String uid, String type, String ref,
                                       String summary, String description, String tags, String version) {
        String t = normalizeType(type);
        String itemKey;
        String name;
        String defaultSummary;
        byte[] payload;
        String payloadExt;
        switch (t) {
            case MarketItem.TYPE_SKILL -> {
                UserSkillStore.InstalledSkill meta = skillStore.find(uid, ref);
                if (meta == null) {
                    throw new BizException("技能不存在，或不是你安装的技能：" + ref
                            + "（内置技能随版本分发人人都有，无需上架；工作区技能请在对话里另存后再上架）");
                }
                itemKey = meta.name();
                name = clean(meta.name(), MAX_NAME, "展示名");
                defaultSummary = meta.description();
                payloadExt = ".zip";
                payload = skillZipBytes(uid, meta.name());
            }
            case MarketItem.TYPE_AGENT -> {
                Agent a = agentService.get(ref);
                if (a == null) throw new BizException(404, "智能体不存在或对当前用户不可见");
                if (!agentService.manageable(a)) throw new BizException("只能上架自己可管理的智能体");
                itemKey = a.getName();
                name = clean(a.getName(), MAX_NAME, "展示名");
                defaultSummary = a.getDescription();
                payloadExt = ".json";
                payload = agentPayloadJson(uid, a).getBytes(StandardCharsets.UTF_8);
            }
            case MarketItem.TYPE_WORKFLOW -> {
                Workflow wf = workflowService.requireManage(ref);
                itemKey = wf.getName();
                name = clean(wf.getName(), MAX_NAME, "展示名");
                defaultSummary = wf.getDescription();
                payloadExt = ".json";
                payload = workflowPayloadJson(wf.getName(), wf.getDescription(), wf.getDsl())
                        .getBytes(StandardCharsets.UTF_8);
            }
            default -> throw new BizException("暂不支持该物品类型：" + type);
        }
        String finalSummary = clean(summary == null || summary.isBlank()
                ? defaultSummary : summary.trim(), MAX_SUMMARY, "简介");
        String finalVersion = resolveVersion(uid, t, ref, version);
        String finalTags = normalizeTags(tags);

        MarketItem item = itemMapper.selectOne(new QueryWrapper<MarketItem>()
                .eq("item_type", t)
                .eq("item_key", itemKey)
                .eq("author_uid", uid));
        boolean update = item != null;
        if (item == null) item = new MarketItem();
        if (!update) {
            item.setId(UUID.randomUUID().toString().replace("-", ""));
            item.setItemType(t);
            item.setItemKey(itemKey);
            item.setAuthorUid(uid);
            item.setInstallCount(0);
        }
        item.setName(name);
        item.setSummary(finalSummary);
        item.setDescription(description == null ? "" : description.trim());
        item.setTags(finalTags);
        item.setVersion(finalVersion);
        item.setStatus(MarketItem.STATUS_ONLINE);
        // 快照先落位再写库：库里路径永远指向存在的文件；写库失败留下的孤儿文件会被下次同 id 上架覆盖
        String rel = t + "/" + item.getId() + payloadExt;
        writePayloadBytes(payload, rel);
        item.setPayloadPath(rel);
        // 内容指纹：徽标只认内容变化，不认版本号（版本号纯展示）。
        // 技能 zip 对同一份技能字节确定（文件按名排序、条目时间取 mtime），内容没变指纹就不变
        item.setContentHash(sha256Of(payload));
        if (update) {
            itemMapper.updateById(item);
        } else {
            itemMapper.insert(item);
        }
        log.info("[AUDIT] 市场上架 uid={} item={} type={} key={} version={} update={}",
                uid, item.getId(), t, itemKey, finalVersion, update);
        return view(item, null, Map.of(), uid);
    }

    // ==================== 安装 ====================

    /**
     * 一键安装（安装即复制）：
     * <ul>
     *   <li>skill：快照 zip → 本人技能目录（与上传 zip 同校验链），同名时按 overwrite 决定是否整包替换；</li>
     *   <li>agent / workflow：<b>每次安装都是新建一份</b>（fork），无同名冲突概念，overwrite 不参与；
     *       智能体模板里的引用（知识库 / MCP / 技能 / 绑定工作流）按安装人权限裁剪，逐项告警放进 warnings。</li>
     * </ul>
     */
    public Map<String, Object> install(String uid, String id, boolean overwrite) {
        MarketItem item = requireVisible(uid, id);
        List<String> warnings = new ArrayList<>();
        Map<String, Object> m;
        switch (Objects.requireNonNullElse(item.getItemType(), MarketItem.TYPE_SKILL)) {
            case MarketItem.TYPE_SKILL -> {
                Path zip = payloadOf(item);
                UserSkillStore.InstalledSkill skill = skillStore.installZipFile(uid, zip,
                        null, overwrite, new LinkedHashSet<>(skillRepository.builtinNames()));
                m = finishInstall(uid, item);
                m.put("skillName", skill.name());
            }
            case MarketItem.TYPE_WORKFLOW -> {
                JSONObject payload = payloadJson(item);
                Workflow wf = workflowService.create(
                        str(payload, "name"), str(payload, "description"), str(payload, "dsl"));
                m = finishInstall(uid, item);
                m.put("workflowId", wf.getId());
                m.put("workflowName", wf.getName());
            }
            case MarketItem.TYPE_AGENT -> {
                JSONObject payload = payloadJson(item);
                Agent created = forkAgent(uid, payload, warnings);
                m = finishInstall(uid, item);
                m.put("agentId", created.getId());
                m.put("agentName", created.getName());
            }
            default -> throw new BizException("暂不支持该物品类型：" + item.getItemType());
        }
        m.put("warnings", warnings);
        return m;
    }

    /** 安装收尾：落安装记录（记当前指纹）+ 计数 + 视图；失败路径不会走到这里（不留「装过」痕迹）。 */
    private Map<String, Object> finishInstall(String uid, MarketItem item) {
        installMapper.delete(new QueryWrapper<MarketInstall>()
                .eq("uid", uid).eq("item_id", item.getId()));
        MarketInstall rec = new MarketInstall();
        rec.setUid(uid);
        rec.setItemId(item.getId());
        rec.setItemType(item.getItemType());
        rec.setVersion(item.getVersion());
        rec.setContentHash(item.getContentHash());
        installMapper.insert(rec);
        itemMapper.update(null, new UpdateWrapper<MarketItem>()
                .eq("id", item.getId()).setSql("install_count = install_count + 1"));
        log.info("[AUDIT] 市场安装 uid={} item={} type={} key={} version={}",
                uid, item.getId(), item.getItemType(), item.getItemKey(), item.getVersion());
        return view(item, rec, Map.of(), uid);
    }

    /**
     * 应用模板 → 安装人自己的新智能体：引用按安装人权限裁剪、逐项写进 warnings——
     * 装完的东西必须「在这台机器上真的能跑」，而不是带着一串断链引用静默降级。
     */
    private Agent forkAgent(String uid, JSONObject payload, List<String> warnings) {
        JSONObject cfg = payload.getJSONObject("agent");
        if (cfg == null || cfg.isEmpty()) throw new BizException("物品包损坏（缺少智能体配置），请联系作者重新上架");
        // 知识库：装的人可读才保留；全数不可见时清空（=回落默认知识库，见 c_ai_agent.knowledge_base_ids 语义）
        String kbIds = str(cfg, "knowledgeBaseIds");
        if (kbIds != null && !kbIds.isBlank()) {
            List<String> keep = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            ResourceVisibilityService.Principal p = principalOf(uid);
            for (String kbId : splitCsv(kbIds)) {
                KnowledgeBase kb = kbMapper.selectById(kbId);
                if (kb != null && visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(),
                        ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
                    keep.add(kbId);
                } else {
                    dropped.add(kb != null ? kb.getName() : kbId);
                }
            }
            if (!dropped.isEmpty()) {
                warnings.add("知识库未保留（无权限或已删除）：" + String.join("、", dropped)
                        + (keep.isEmpty() ? "；检索范围已回落默认知识库" : ""));
            }
            cfg.put("knowledgeBaseIds", keep.isEmpty() ? null : String.join(",", keep));
        }
        // MCP：装的人登记过同名 Server 才保留（MCP 配置按用户隔离，引用断链不如明确剔除）
        String mcps = str(cfg, "mcps");
        if (mcps != null && !mcps.isBlank()) {
            Set<String> mine = userMcpMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserMcp>()
                            .eq(UserMcp::getUid, uid))
                    .stream().map(UserMcp::getName).collect(Collectors.toSet());
            List<String> keep = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (String n : splitCsv(mcps)) {
                if (mine.contains(n)) keep.add(n);
                else dropped.add(n);
            }
            if (!dropped.isEmpty()) {
                warnings.add("MCP 未保留（你未登记同名 Server）：" + String.join("、", dropped));
            }
            cfg.put("mcps", keep.isEmpty() ? "" : String.join(",", keep));
        }
        // 技能：引用保留但缺装的提示（技能的获得渠道就是技能市场，装上即生效）
        String skills = str(cfg, "skills");
        if (skills != null && !skills.isBlank()) {
            Set<String> mine = new LinkedHashSet<>(skillRepository.builtinNames());
            for (UserSkillStore.InstalledSkill s : skillStore.list(uid)) mine.add(s.name());
            List<String> missing = splitCsv(skills).stream()
                    .filter(n -> !mine.contains(n)).collect(Collectors.toList());
            if (!missing.isEmpty()) {
                warnings.add("技能未安装（装上后引用即生效，可在技能市场找找）：" + String.join("、", missing));
            }
        }
        // 绑定工作流：模板附带 DSL 时 fork 一份新的再重链。副本建完立即<b>发布</b>——
        // 能绑到模板作者智能体上的工作流必然是已发布态（草稿绑不上），副本保持同态，
        // 否则 AgentService.create 的绑定校验会当场拒绝这次安装
        JSONObject wfPayload = payload.getJSONObject("workflow");
        if (wfPayload != null && str(wfPayload, "dsl") != null && !str(wfPayload, "dsl").isBlank()) {
            Workflow wf = workflowService.create(str(wfPayload, "name"),
                    str(wfPayload, "description"), str(wfPayload, "dsl"));
            workflowService.publish(wf.getId(), "市场模板安装");
            cfg.put("workflowId", wf.getId());
        }
        // 快照键与 AgentService.create(toEntity) 的 body 口径一致；权限档位原样保留——
        // 超出平台上限时运行时钳制（与作者本人跑起来的行为一致，见 PermissionPolicyResolver）
        return agentService.create(cfg);
    }

    // ==================== 管理：状态 / 删除 ====================

    /** 上架人/管理员：上下架。下架后列表不可见、安装被拒；已装副本不受影响。 */
    public Map<String, Object> setStatus(String uid, String id, boolean online) {
        MarketItem item = requireManageable(uid, id);
        itemMapper.update(null, new UpdateWrapper<MarketItem>()
                .eq("id", id).set("status", online ? MarketItem.STATUS_ONLINE : MarketItem.STATUS_OFFLINE));
        log.info("[AUDIT] 市场{} uid={} item={}", online ? "上架" : "下架", uid, id);
        return view(itemMapper.selectById(id), null, Map.of(), uid);
    }

    /** 上架人/管理员：删除条目（连快照与安装记录；已装进别人目录的技能副本动不了，也不该动）。 */
    public void delete(String uid, String id) {
        MarketItem item = requireManageable(uid, id);
        if (MarketItem.AUTHOR_SYSTEM.equals(item.getAuthorUid())) {
            throw new BizException("官方条目不可删除；不需要时请下架（下架后不再展示，历史安装不受影响）");
        }
        itemMapper.deleteById(id);
        installMapper.delete(new QueryWrapper<MarketInstall>().eq("item_id", id));
        try {
            Files.deleteIfExists(payloadOf(item));
        } catch (IOException e) {
            log.warn("[MARKET] 快照删除失败 item={}: {}", id, e.toString());
        }
        log.info("[AUDIT] 市场删除 uid={} item={}", uid, id);
    }

    /** 下载快照包（装前留档/离线分发）：可见即可下（市场物品本身是公开内容）。 */
    public Path payloadZip(String uid, String id) {
        MarketItem item = requireVisible(uid, id);
        return payloadOf(item);
    }

    public MarketItem requireItem(String id) {
        MarketItem item = id == null || id.isBlank() ? null : itemMapper.selectById(id.trim());
        if (item == null) throw new BizException("市场物品不存在，可能已被作者删除");
        return item;
    }

    // ==================== 内部 ====================

    /** 可见性：上架中，或者是我上架的，或者我是管理员。 */
    private MarketItem requireVisible(String uid, String id) {
        MarketItem item = requireItem(id);
        if (!MarketItem.STATUS_ONLINE.equals(item.getStatus())
                && !uid.equals(item.getAuthorUid()) && !isAdmin()) {
            throw new BizException("该物品已下架");
        }
        return item;
    }

    /** 可管理：作者本人或管理员。 */
    private MarketItem requireManageable(String uid, String id) {
        MarketItem item = requireItem(id);
        if (!uid.equals(item.getAuthorUid()) && !isAdmin()) {
            throw new BizException("只有上架人（或管理员）可以管理该物品");
        }
        return item;
    }

    private boolean isAdmin() {
        return roleService.isAdminAny(RequestUser.roles());
    }

    private Path payloadOf(MarketItem item) {
        Path zip = payloadRoot.resolve(item.getPayloadPath()).normalize();
        if (!zip.startsWith(payloadRoot)) throw new BizException("物品包路径不合法");
        if (!Files.isRegularFile(zip)) {
            throw new BizException("物品包缺失，请联系作者重新上架（已安装的副本不受影响）");
        }
        return zip;
    }

    private void writePayloadBytes(byte[] bytes, String rel) {
        Path target = payloadRoot.resolve(rel).normalize();
        if (!target.startsWith(payloadRoot)) throw new BizException("物品包路径不合法");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new BizException("物品包写入失败：" + e.getMessage());
        }
    }

    /** 技能 → zip 字节（与技能页下载同一打包口径）。 */
    private byte[] skillZipBytes(String uid, String skillName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            skillStore.writeZip(uid, skillName, out);
        } catch (IOException e) {
            throw new BizException("技能打包失败：" + e.getMessage());
        }
        return out.toByteArray();
    }

    /** 智能体 → 应用模板载荷：配置快照（VERSION_FIELDS 白名单，剔除 fork 后必断链的引用）+ 可选绑定工作流 DSL。 */
    private String agentPayloadJson(String uid, Agent a) {
        JSONObject cfg = JSON.parseObject(agentService.configJson(a));
        // subAgentIds 引用别的智能体 id，装到谁名下都必断链——模板里带上只会制造幽灵引用
        cfg.remove("subAgentIds");
        // workflowId 不进快照：安装时按附带 DSL 重链新副本
        cfg.remove("workflowId");
        JSONObject wfPayload = null;
        Workflow wf = workflowService.getOwnOrNull(a.getWorkflowId());
        if (wf != null) {
            wfPayload = new JSONObject();
            wfPayload.put("name", wf.getName());
            wfPayload.put("description", wf.getDescription() == null ? "" : wf.getDescription());
            wfPayload.put("dsl", wf.getDsl() == null ? "" : wf.getDsl());
        }
        JSONObject payload = new JSONObject();
        payload.put("agent", cfg);
        payload.put("workflow", wfPayload);
        return payload.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }

    /** 工作流 → 载荷 JSON（与 /workflow/{id}/export 同一份格式）。 */
    private String workflowPayloadJson(String name, String description, String dsl) {
        JSONObject payload = new JSONObject();
        payload.put("name", name);
        payload.put("description", description == null ? "" : description);
        payload.put("dsl", dsl == null ? "" : dsl);
        return payload.toJSONString();
    }

    /** 读 JSON 载荷（agent/workflow）：文件缺失/超限/坏 JSON 都按「物品包损坏」fail-loud。 */
    private JSONObject payloadJson(MarketItem item) {
        Path file = payloadOf(item);
        try {
            long size = Files.size(file);
            if (size > MAX_PAYLOAD_JSON_BYTES) throw new BizException("物品包过大（上限 512KB）");
            JSONObject o = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (o == null) throw new BizException("物品包内容为空");
            return o;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("物品包损坏，请联系作者重新上架");
        }
    }

    /** 详情页的载荷预览（pretty JSON，超限截断）。 */
    private String prettyPayload(MarketItem item) {
        try {
            String raw = Files.readString(payloadOf(item), StandardCharsets.UTF_8);
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_JSON_BYTES) {
                return raw.substring(0, MAX_PAYLOAD_JSON_BYTES / 2) + "\n…（内容过长已截断）";
            }
            return JSON.toJSONString(JSON.parseObject(raw), JSONWriter.Feature.PrettyFormat);
        } catch (Exception e) {
            return "（载荷读取失败：" + e.getMessage() + "）";
        }
    }

    /** 从快照 zip 里读 SKILL.md（详情页预览，让装的人在装前看到模型将读到的正文）。 */
    private String readSkillMdFromPayload(MarketItem item) {
        if (!MarketItem.TYPE_SKILL.equals(item.getItemType())) return "";
        try {
            Path zip = payloadOf(item);
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (!"SKILL.md".equals(n) && !n.endsWith("/SKILL.md")) continue;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int read = 0;
                    int r;
                    while ((r = zis.read(buf)) >= 0) {
                        read += r;
                        if (read > MAX_SKILL_MD_BYTES) return "";
                        out.write(buf, 0, r);
                    }
                    return out.toString(StandardCharsets.UTF_8);
                }
            }
            return "";
        } catch (IOException | RuntimeException e) {
            log.warn("[MARKET] 读取快照 SKILL.md 失败 item={}: {}", item.getId(), e.toString());
            return "";
        }
    }

    /** 物品类型归一：空/未知按 skill 兜底（v1 只有技能时的旧调用不带 type）。 */
    private static String normalizeType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "", MarketItem.TYPE_SKILL -> MarketItem.TYPE_SKILL;
            case MarketItem.TYPE_AGENT -> MarketItem.TYPE_AGENT;
            case MarketItem.TYPE_WORKFLOW -> MarketItem.TYPE_WORKFLOW;
            default -> throw new BizException("暂不支持该物品类型：" + raw + "（可选 skill / agent / workflow）");
        };
    }

    /** 版本号：显式填的 > 技能 SKILL.md frontmatter 里的 > "1"（agent/workflow 无 frontmatter 可回落）。 */
    private String resolveVersion(String uid, String type, String ref, String override) {
        String v = override == null ? "" : override.trim();
        if (v.isEmpty() && MarketItem.TYPE_SKILL.equals(type)) v = skillStore.versionOf(uid, ref);
        if (v.isEmpty()) v = "1";
        if (v.length() > MAX_VERSION) {
            throw new BizException("版本号过长（最多 " + MAX_VERSION + " 字符）");
        }
        return v;
    }

    /** csv 拆分（空白容忍，保序去空）。 */
    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，\\s]+")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static String str(JSONObject o, String key) {
        if (o == null) return null;
        Object v = o.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 安装人的身份三元组（KB 可读性判定用；角色取主角色——管理员优先，判定口径一致）。 */
    private ResourceVisibilityService.Principal principalOf(String uid) {
        User u = userMapper.selectById(uid);
        return new ResourceVisibilityService.Principal(uid,
                u == null ? null : u.getDepartmentId(),
                u == null ? "user" : userRoleService.primaryRole(uid));
    }

    /**
     * 官方工作流模板种入市场（启动期幂等）：不存在则建（默认上架中），已存在则在 DSL 变化时刷新内容
     * 与指纹——状态（含管理员下架过的）与安装计数不动。
     */
    public void seedOfficialWorkflow(String key, String name, String description, String dsl) {
        MarketItem item = itemMapper.selectOne(new QueryWrapper<MarketItem>()
                .eq("item_type", MarketItem.TYPE_WORKFLOW)
                .eq("item_key", key)
                .eq("author_uid", MarketItem.AUTHOR_SYSTEM));
        String payload = workflowPayloadJson(name, description, dsl);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String hash = sha256Of(bytes);
        if (item == null) {
            item = new MarketItem();
            item.setId(UUID.randomUUID().toString().replace("-", ""));
            item.setItemType(MarketItem.TYPE_WORKFLOW);
            item.setItemKey(key);
            item.setAuthorUid(MarketItem.AUTHOR_SYSTEM);
            item.setInstallCount(0);
            item.setName(name);
            item.setSummary(clean(description, MAX_SUMMARY, "简介"));
            item.setDescription(description);
            item.setTags("");
            item.setVersion("1");
            item.setStatus(MarketItem.STATUS_ONLINE);
            item.setPayloadPath(MarketItem.TYPE_WORKFLOW + "/" + item.getId() + ".json");
            writePayloadBytes(bytes, item.getPayloadPath());
            item.setContentHash(hash);
            itemMapper.insert(item);
            log.info("[MARKET] 官方模板已种入市场：{}", name);
            return;
        }
        // 已存在：DSL 没变就不动库（避免 updateTime 跳动改列表排序）；文件始终重写一份（容错孤儿删除）
        writePayloadBytes(bytes, item.getPayloadPath());
        if (!Objects.equals(normalizeHash(item.getContentHash()), hash)) {
            item.setName(name);
            item.setSummary(clean(description, MAX_SUMMARY, "简介"));
            item.setDescription(description);
            item.setContentHash(hash);
            itemMapper.updateById(item);
            log.info("[MARKET] 官方模板内容已刷新：{}", name);
        }
    }

    /** 标签：逗号/顿号/空白分隔 → 去重、单个 ≤32 字、总量 ≤256 字（列宽）。 */
    private static String normalizeTags(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,，、\\s]+")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (t.length() > 32) throw new BizException("单个标签过长（最多 32 字）：" + t);
            out.add(t);
        }
        String joined = String.join(",", out);
        if (joined.length() > 256) throw new BizException("标签过多（总计最多 256 字）");
        return joined;
    }

    private static String clean(String raw, int max, String label) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) throw new BizException("缺少" + label);
        if (v.length() > max) throw new BizException(label + "过长（最多 " + max + " 字）");
        return v;
    }

    private Map<String, MarketInstall> installsOf(String uid, Set<String> itemIds) {
        if (itemIds.isEmpty()) return Map.of();
        Map<String, MarketInstall> out = new LinkedHashMap<>();
        for (MarketInstall r : installMapper.selectList(new QueryWrapper<MarketInstall>()
                .eq("uid", uid).in("item_id", itemIds))) {
            out.put(r.getItemId(), r);
        }
        return out;
    }

    private Map<String, String> authorNames(Set<String> uids) {
        if (uids.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        // 官方条目作者不在用户表，显示名固定「平台官方」（不回退裸 uid）
        if (uids.contains(MarketItem.AUTHOR_SYSTEM)) out.put(MarketItem.AUTHOR_SYSTEM, "平台官方");
        // selectBatchIds 对空集合会生成 IN () 直接 SQL 语法错——整页都是官方条目时就会踩到
        Set<String> real = uids.stream()
                .filter(id -> !MarketItem.AUTHOR_SYSTEM.equals(id)).collect(Collectors.toSet());
        if (real.isEmpty()) return out;
        for (User u : userMapper.selectBatchIds(real)) {
            out.put(u.getUid(), u.getUsername() == null || u.getUsername().isBlank()
                    ? u.getUid() : u.getUsername());
        }
        return out;
    }

    /** 列表/详情共用的视图：元信息 + mine/installed/hasUpdate 徽标。 */
    private Map<String, Object> view(MarketItem item, MarketInstall install,
                                     Map<String, String> authorNames, String uid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("itemType", item.getItemType());
        m.put("itemKey", item.getItemKey());
        m.put("name", item.getName());
        m.put("summary", item.getSummary());
        m.put("tags", item.getTags() == null || item.getTags().isBlank()
                ? List.of() : List.of(item.getTags().split(",")));
        m.put("version", item.getVersion());
        m.put("status", item.getStatus());
        m.put("online", MarketItem.STATUS_ONLINE.equals(item.getStatus()));
        m.put("installCount", item.getInstallCount() == null ? 0 : item.getInstallCount());
        m.put("updateTime", item.getUpdateTime() == null ? null : item.getUpdateTime().toString());
        m.put("authorUid", item.getAuthorUid());
        m.put("authorName", authorNames.getOrDefault(item.getAuthorUid(), item.getAuthorUid()));
        m.put("mine", uid != null && uid.equals(item.getAuthorUid()));
        m.put("installed", install != null);
        m.put("installedVersion", install == null ? null : install.getVersion());
        // 「有更新」按内容指纹判定；指纹缺失（历史行/异常路径）退回版本号比对
        String curHash = normalizeHash(item.getContentHash());
        String insHash = install == null ? null : normalizeHash(install.getContentHash());
        boolean hasUpdate;
        if (curHash != null && insHash != null) {
            hasUpdate = !curHash.equals(insHash);
        } else {
            hasUpdate = install != null && !item.getVersion().equals(install.getVersion());
        }
        m.put("hasUpdate", hasUpdate);
        return m;
    }

    /** 空串归一成 null（指纹缺失的统一表达）。 */
    private static String normalizeHash(String h) {
        return h == null || h.isBlank() ? null : h.trim();
    }

    /** 文件 SHA-256（hex）。失败抛业务异常——指纹缺失会让徽标退回版本比对，宁可上架失败也不静默降级。 */
    private static String sha256Of(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new BizException("计算物品指纹失败：" + e.getMessage());
        }
    }
}
