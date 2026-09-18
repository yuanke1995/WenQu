package com.wisesoft.wenqu.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wisesoft.wenqu.common.BizException;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 资源可见性 / 权限解析器。
 * <p>
 * 共享范围模型：资源带 {@code share_config} JSON（snake_case，v2）：
 * <pre>
 * { "version": 2,
 *   "read_scope":  {access_level: global|department|user, department_ids:[], user_uids:[]},
 *   "manage_scope":{access_level: global|department|user, department_ids:[], user_uids:[]} }
 * </pre>
 * 权限解析顺序（{@link #resolve}）：
 * <ol>
 *   <li>superadmin → MANAGE（短路）</li>
 *   <li>创建者（created_by == uid）→ MANAGE（短路）</li>
 *   <li>manage_scope 命中 且（read_scope 缺失 或 read 也命中）→ MANAGE</li>
 *   <li>read_scope 命中 → READ；否则 NONE</li>
 *   <li>第 3~4 步结果再套 <b>角色上限</b>（{@link ResourceKind#roleCeiling}）取小</li>
 * </ol>
 * 语义要点：
 * <ul>
 *   <li>{@code access_level=global} → 全员命中；{@code department} 比对用户部门；{@code user} 比对 uid</li>
 *   <li>scope 为 {@code null}（未声明）→ <b>不命中任何人</b>：故「manage_scope 缺失」＝除超管/创建者外无人可管理</li>
 *   <li>share_config 为 <b>空</b>（未配置）→ 视作 global（兼容存量数据），与「显式声明 manage_scope=global」等价</li>
 *   <li><b>刻意比对标实现宽松的一点</b>：未声明 {@code version} 的历史配置在<b>读取</b>时不报错（按内容解析），
 *       但<b>写入</b>（{@link #validateShareConfig}）强制要求 {@code version=2}</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
public class ResourceVisibilityService {

    private static final ObjectMapper OM = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** 权限等级（ordinal 顺序用于比较大小） */
    public enum Permission { NONE, READ, MANAGE }

    /** 资源类型 → 角色权限上限 */
    public enum ResourceKind {
        /** 知识库 / 文档：普通用户封顶只读，admin/superadmin 可管理 */
        KNOWLEDGE_BASE,
        /** 智能体 / 技能：全角色可管理 */
        AGENT,
        /** API Key：全角色可管理。共享的是「Key 记录」而非密钥本体（明文仅签发时返回一次，列表只有前缀） */
        API_KEY;

        Permission roleCeiling(String role) {
            return switch (this) {
                case KNOWLEDGE_BASE -> ("admin".equals(role) || "superadmin".equals(role))
                        ? Permission.MANAGE : Permission.READ;
                case AGENT, API_KEY -> ("user".equals(role) || "admin".equals(role) || "superadmin".equals(role))
                        ? Permission.MANAGE : Permission.READ;
            };
        }
    }

    public enum AccessLevel { GLOBAL, DEPARTMENT, USER }

    /** 当前操作者抽象（从登录态 RequestUser 派生） */
    public record Principal(String uid, String departmentId, String role) {
        public static final Principal ANONYMOUS = new Principal("anonymous", null, "user");
        public boolean superadmin() { return "superadmin".equals(role); }
    }

    /**
     * 解析可见性所需的资源共享信息（文档 / 智能体 / API Key 通用载体）。
     * <p>{@code shareConfig} 为 null/空 ＝ 未配置 ＝ 全局共享；{@code createdBy} 用于「创建者短路」。</p>
     */
    public record DocShare(String shareConfig, String createdBy) {}

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Scope {
        public String accessLevel = "global";
        public List<String> departmentIds = new ArrayList<>();
        public List<String> userUids = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShareConfig {
        public Integer version;
        public Scope readScope;
        public Scope manageScope;
    }

    private static Scope defaultScope() {
        Scope s = new Scope();
        s.accessLevel = "global";
        return s;
    }

    /**
     * 解析 share_config 文本。
     * <ul>
     *   <li>空白 / 解析失败 → {@code null}（＝未配置，视作 global；解析失败刻意宽松以免误伤检索）</li>
     *   <li>可解析 → 原样返回：<b>即使两个 scope 皆缺省也按「已配置但显式为空」处理</b>（无人命中），
     *       以贴合「scope 缺失＝不命中任何人」的语义</li>
     * </ul>
     */
    private ShareConfig parse(String shareConfigJson) {
        if (shareConfigJson == null || shareConfigJson.isBlank()) return null;
        try {
            return OM.readValue(shareConfigJson, ShareConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** scope 是否命中该用户（scope 为 null ＝未声明 ＝不命中任何人） */
    private static boolean scopeMatches(Principal p, Scope s) {
        if (s == null) return false;
        AccessLevel lvl = AccessLevel.GLOBAL;
        try {
            lvl = AccessLevel.valueOf(s.accessLevel.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            lvl = AccessLevel.GLOBAL;
        }
        return switch (lvl) {
            case GLOBAL -> true;
            case DEPARTMENT -> s.departmentIds != null && p.departmentId() != null
                    && s.departmentIds.contains(p.departmentId());
            case USER -> s.userUids != null && p.uid() != null && s.userUids.contains(p.uid());
        };
    }

    /** 解析用户的<b>有效权限</b>（所有权 + 共享范围 + 角色上限） */
    public Permission resolve(Principal p, String shareConfigJson, String createdBy, ResourceKind kind) {
        if (p == null) p = Principal.ANONYMOUS;
        if (p.superadmin()) return Permission.MANAGE;

        ShareConfig cfg = parse(shareConfigJson);
        // 未配置 → 视作全局共享；已配置 → 按声明的 scope（缺失＝不命中任何人）
        Scope read = (cfg == null) ? defaultScope() : cfg.readScope;
        Scope manage = (cfg == null) ? defaultScope() : cfg.manageScope;

        if (createdBy != null && !createdBy.isBlank() && createdBy.equals(p.uid())) {
            return Permission.MANAGE; // 创建者短路（不套角色上限）
        }

        Permission granted;
        if (manage != null && scopeMatches(p, manage) && (read == null || scopeMatches(p, read))) {
            granted = Permission.MANAGE;
        } else if (read != null && scopeMatches(p, read)) {
            granted = Permission.READ;
        } else {
            granted = Permission.NONE;
        }
        Permission ceiling = kind.roleCeiling(p.role());
        return granted.ordinal() <= ceiling.ordinal() ? granted : ceiling;
    }

    /** 当前用户是否能读取该资源（知识库语义，兼容既有调用） */
    public boolean canRead(Principal p, String shareConfigJson, String createdBy) {
        return canRead(p, shareConfigJson, createdBy, ResourceKind.KNOWLEDGE_BASE);
    }

    /** 当前用户是否能读取该资源（按资源类型套角色上限） */
    public boolean canRead(Principal p, String shareConfigJson, String createdBy, ResourceKind kind) {
        return resolve(p, shareConfigJson, createdBy, kind).ordinal() >= Permission.READ.ordinal();
    }

    /** 当前用户是否能管理该资源 */
    public boolean canManage(Principal p, String shareConfigJson, String createdBy, ResourceKind kind) {
        return resolve(p, shareConfigJson, createdBy, kind) == Permission.MANAGE;
    }

    /**
     * 写入侧强校验：v2 配置 + 管理范围必须是阅读范围的子集（manage ⊆ read）。
     * 空串 → 合法（等同清空，回落全局可见）。
     *
     * @throws BizException 非法时
     */
    public void validateShareConfig(String shareConfigJson) {
        if (shareConfigJson == null || shareConfigJson.isBlank()) return;
        ShareConfig cfg;
        try {
            cfg = OM.readValue(shareConfigJson, ShareConfig.class);
        } catch (Exception e) {
            throw new BizException("共享范围格式不正确（应为 JSON）");
        }
        if (cfg.version == null || cfg.version != 2) {
            throw new BizException("共享范围配置必须使用 version 2");
        }
        Scope read = normalizeScope(cfg.readScope);
        Scope manage = normalizeScope(cfg.manageScope);
        validateManageScope(read, manage);
    }

    /** 规范化单个 scope：非法访问级别/空列表直接拒绝 */
    private static Scope normalizeScope(Scope s) {
        if (s == null) return null;
        String lvl = (s.accessLevel == null || s.accessLevel.isBlank())
                ? "global" : s.accessLevel.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("global", "department", "user").contains(lvl)) {
            throw new BizException("无效的资源权限范围");
        }
        if ("global".equals(lvl)) return defaultScope();
        if ("department".equals(lvl)) {
            if (s.departmentIds == null || s.departmentIds.isEmpty()) {
                throw new BizException("部门权限至少需要选择一个部门");
            }
            return s;
        }
        if (s.userUids == null || s.userUids.isEmpty()) {
            throw new BizException("指定用户权限至少需要选择一个用户");
        }
        return s;
    }

    /** 管理范围不得宽于读取范围：读取非 global 时两级必须同级，且管理成员 ⊆ 阅读成员 */
    private static void validateManageScope(Scope read, Scope manage) {
        if (read == null || manage == null || "global".equalsIgnoreCase(read.accessLevel)) return;
        String rl = read.accessLevel.toLowerCase(Locale.ROOT);
        String ml = manage.accessLevel.toLowerCase(Locale.ROOT);
        if (!rl.equals(ml)) {
            throw new BizException("管理范围必须包含在读取范围内（需与读取同级）");
        }
        if ("department".equals(rl) && !read.departmentIds.containsAll(manage.departmentIds)) {
            throw new BizException("管理范围包含的部门必须都在阅读范围内");
        }
        if ("user".equals(rl) && !read.userUids.containsAll(manage.userUids)) {
            throw new BizException("管理范围包含的用户必须都在阅读范围内");
        }
    }

    /**
     * 给定候选文档 id → 共享信息映射，返回当前用户【可见】的文档 id 集合（知识库语义，兼容旧调用）。
     */
    public Set<String> filterVisibleDocIds(Principal p, Map<String, DocShare> docShares) {
        return filterVisibleIds(p, docShares, ResourceKind.KNOWLEDGE_BASE);
    }

    /**
     * 给定候选资源 id → 共享信息映射，返回当前用户【可见】的 id 集合。
     * 资源未配置共享（shareConfig 为空）或创建者/超管 → 始终可见。
     */
    public Set<String> filterVisibleIds(Principal p, Map<String, DocShare> shares, ResourceKind kind) {
        if (shares == null || shares.isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, DocShare> e : shares.entrySet()) {
            DocShare ds = e.getValue();
            if (ds == null) { out.add(e.getKey()); continue; }
            if (canRead(p, ds.shareConfig(), ds.createdBy(), kind)) out.add(e.getKey());
        }
        return out;
    }
}
