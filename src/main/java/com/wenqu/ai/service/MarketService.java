package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.MarketInstallMapper;
import com.wenqu.ai.mapper.MarketItemMapper;
import com.wenqu.ai.model.MarketInstall;
import com.wenqu.ai.model.MarketItem;
import com.wenqu.ai.model.User;
import com.wenqu.ai.mapper.UserMapper;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 市场（v1 技能市场）：上架 → 发现 → 一键安装。
 *
 * <h3>核心语义：安装即复制（fork）</h3>
 * 物品包是上架那一刻的<b>快照</b>（zip 落 {@code user-data/market/skill/{id}.zip}），
 * 安装把快照完整复制进用户自己的技能目录，装完与市场条目再无引用关系——
 * 与全平台「业务资产归使用者」的所有权约定一致。因此：
 * <ul>
 *   <li>没有订阅/运行时依赖：作者改版、下架、删除都不影响已装副本；</li>
 *   <li>「有更新」由<b>内容指纹</b>（快照 zip 的 SHA-256）驱动，不依赖版本号——
 *       作者忘升版本号也能检出内容变化，反之只改市场卡片文案（简介/标签）不会误报；</li>
 *   <li>上架校验链与匿名上传完全相同（市场快照内容来自其他用户，信任边界不放宽）。</li>
 * </ul>
 *
 * <h3>类型扩展位</h3> {@code item_type} 现只开放 skill；agent / workflow / mcp
 * 接入时按类型换「打包 + 落地」两条路径即可，登记表与安装记录表不动。
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

    private final MarketItemMapper itemMapper;
    private final MarketInstallMapper installMapper;
    private final UserSkillStore skillStore;
    private final WenquSkillRepository skillRepository;
    private final UserMapper userMapper;
    private final RoleService roleService;

    /** 物品包根目录（与 user-data 同域，可配）。 */
    private final Path payloadRoot;

    public MarketService(
            @Value("${wenqu.market.payload-dir:user-data/market}") String payloadDir,
            MarketItemMapper itemMapper, MarketInstallMapper installMapper,
            UserSkillStore skillStore, WenquSkillRepository skillRepository,
            UserMapper userMapper, RoleService roleService) {
        this.payloadRoot = Paths.get(payloadDir == null || payloadDir.isBlank()
                ? "user-data/market" : payloadDir).toAbsolutePath().normalize();
        this.itemMapper = itemMapper;
        this.installMapper = installMapper;
        this.skillStore = skillStore;
        this.skillRepository = skillRepository;
        this.userMapper = userMapper;
        this.roleService = roleService;
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
     * 页签计数：与列表走同一条筛选链（类型/关键词/标签），mineOnly 决定数「全部」还是「我的上架」。
     * 不套列表的条数上限，因此列表被截断时计数仍是全量。
     */
    public long count(String uid, String type, String keyword, String tag, boolean mineOnly) {
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

    /** 详情：元信息 + 详细介绍 + SKILL.md 正文预览（装前可审阅）。 */
    public Map<String, Object> detail(String uid, String id) {
        MarketItem item = requireVisible(uid, id);
        Map<String, Object> m = view(item, installsOf(uid, Set.of(id)).get(id), Map.of(), uid);
        m.put("description", item.getDescription());
        m.put("skillMd", readSkillMdFromPayload(item));
        return m;
    }

    // ==================== 上架 ====================

    /**
     * 把<b>自己安装的技能</b>上架：打 zip 快照落市场目录 + 登记/更新条目。
     * <p>同一作者同名技能重复上架 = 更新同一条（快照覆盖、版本刷新、重新上架）。
     */
    public Map<String, Object> publish(String uid, String skillName, String summary,
                                       String description, String tags, String version) {
        UserSkillStore.InstalledSkill meta = skillStore.find(uid, skillName);
        if (meta == null) {
            throw new BizException("技能不存在，或不是你安装的技能：" + skillName
                    + "（内置技能随版本分发人人都有，无需上架；工作区技能请在对话里另存后再上架）");
        }
        String name = clean(meta.name(), MAX_NAME, "展示名");
        String finalSummary = clean(summary == null || summary.isBlank()
                ? meta.description() : summary.trim(), MAX_SUMMARY, "简介");
        String finalVersion = resolveVersion(uid, meta.name(), version);
        String finalTags = normalizeTags(tags);

        MarketItem item = itemMapper.selectOne(new QueryWrapper<MarketItem>()
                .eq("item_type", MarketItem.TYPE_SKILL)
                .eq("item_key", meta.name())
                .eq("author_uid", uid));
        boolean update = item != null;
        if (item == null) item = new MarketItem();
        if (!update) {
            item.setId(UUID.randomUUID().toString().replace("-", ""));
            item.setItemType(MarketItem.TYPE_SKILL);
            item.setItemKey(meta.name());
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
        String rel = "skill/" + item.getId() + ".zip";
        writePayload(uid, meta.name(), rel);
        item.setPayloadPath(rel);
        // 内容指纹：徽标只认内容变化，不认版本号（版本号纯展示）。
        // writeZip 对同一份技能是字节确定的（文件按名排序、条目时间取 mtime），内容没变指纹就不变
        item.setContentHash(sha256Of(payloadRoot.resolve(rel)));
        if (update) {
            itemMapper.updateById(item);
        } else {
            itemMapper.insert(item);
        }
        log.info("[AUDIT] 市场上架 uid={} item={} skill={} version={} update={}",
                uid, item.getId(), meta.name(), finalVersion, update);
        return view(item, null, Map.of(), uid);
    }

    // ==================== 安装 ====================

    /**
     * 一键安装：市场快照 zip → 用户技能目录（走与上传 zip 完全相同的校验链），
     * 成功后落安装记录并计数。同名冲突由调用方决定是否覆盖重装。
     */
    public Map<String, Object> install(String uid, String id, boolean overwrite) {
        MarketItem item = requireVisible(uid, id);
        Path zip = payloadOf(item);
        UserSkillStore.InstalledSkill skill = skillStore.installZipFile(uid, zip,
                null, overwrite, new LinkedHashSet<>(skillRepository.builtinNames()));
        // 装完才记版本：安装失败不留「装过」痕迹，徽标不会谎报
        installMapper.delete(new QueryWrapper<MarketInstall>()
                .eq("uid", uid).eq("item_id", id));
        MarketInstall rec = new MarketInstall();
        rec.setUid(uid);
        rec.setItemId(id);
        rec.setItemType(item.getItemType());
        rec.setVersion(item.getVersion());
        rec.setContentHash(item.getContentHash());
        installMapper.insert(rec);
        itemMapper.update(null, new UpdateWrapper<MarketItem>()
                .eq("id", id).setSql("install_count = install_count + 1"));
        log.info("[AUDIT] 市场安装 uid={} item={} skill={} version={} overwrite={}",
                uid, id, skill.name(), item.getVersion(), overwrite);
        Map<String, Object> m = view(item, rec, Map.of(), uid);
        m.put("skillName", skill.name());
        return m;
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
        String role = RequestUser.role();
        return role != null && roleService.isAdminCode(role);
    }

    private Path payloadOf(MarketItem item) {
        Path zip = payloadRoot.resolve(item.getPayloadPath()).normalize();
        if (!zip.startsWith(payloadRoot)) throw new BizException("物品包路径不合法");
        if (!Files.isRegularFile(zip)) {
            throw new BizException("物品包缺失，请联系作者重新上架（已安装的副本不受影响）");
        }
        return zip;
    }

    private void writePayload(String uid, String skillName, String rel) {
        Path target = payloadRoot.resolve(rel).normalize();
        if (!target.startsWith(payloadRoot)) throw new BizException("物品包路径不合法");
        try {
            Files.createDirectories(target.getParent());
            try (var out = Files.newOutputStream(target)) {
                skillStore.writeZip(uid, skillName, out);
            }
        } catch (IOException e) {
            throw new BizException("物品包写入失败：" + e.getMessage());
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

    /** 版本号：显式填的 > SKILL.md frontmatter 里的 > "1"。 */
    private String resolveVersion(String uid, String skillName, String override) {
        String v = override == null ? "" : override.trim();
        if (v.isEmpty()) v = skillStore.versionOf(uid, skillName);
        if (v.isEmpty()) v = "1";
        if (v.length() > MAX_VERSION) {
            throw new BizException("版本号过长（最多 " + MAX_VERSION + " 字符）");
        }
        return v;
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
        for (User u : userMapper.selectBatchIds(uids)) {
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
    private static String sha256Of(Path file) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new BizException("计算物品指纹失败：" + e.getMessage());
        }
    }
}
