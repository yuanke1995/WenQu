package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.mapper.GroupMemberMapper;
import com.wenqu.ai.mapper.GroupRoleMappingMapper;
import com.wenqu.ai.mapper.IdentityProviderMapper;
import com.wenqu.ai.mapper.SyncJobLogMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.GroupMember;
import com.wenqu.ai.model.GroupRoleMapping;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.model.SyncJobLog;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SCIM 2.0 入站（RFC 7644）：Entra/Okta 等 IdP 把用户与组**推**进来，本系统是被管侧。
 * <p>
 * 鉴权：独立 Bearer token（认证源 config.bearerToken，与 JWT 无关）——SCIM 是机器到机器
 * 凭据，不走用户登录态，由 {@link #authenticate} 在控制器入口强校验（常数时间比对）。
 * <p>
 * Users 落位：
 * <ul>
 *   <li>建号刻意不设密码（SSO/目录账号不走本地密码登录，同 OIDC/SAML 口径）；
 *       默认角色取认证源行 {@code default_role}，grant 到 {@code c_ai_user_role} 的 idp 行；</li>
 *   <li>资源 id = 本地 uid（IdP 后续 PATCH/DELETE 引用它，成员 value 也直接是 uid）；</li>
 *   <li>禁用/删除（D4：销户默认 disable 可恢复）：{@code active=false} 或 DELETE → status=0，
 *       身份绑定保留；禁用唯一管理员级账号被拒（防自锁，与成员管理同口径）；</li>
 *   <li>SCIM 属性快照落 {@code c_ai_user_identity.external_attrs}。</li>
 * </ul>
 * Groups 落地：组名登记进 {@code c_ai_group_role_mapping}（role_code 可空，规则由管理员在
 * 映射管理页配置）；成员关系落 {@code c_ai_group_member}（事实源）。组成员增删时，若该组
 * 已配置角色/部门映射，即时联动 grant/revoke（D1：IdP 权威，只动 idp 行）。
 * <p>
 * 每次写操作记一条 {@code c_ai_sync_job_log}（可追溯）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScimService {

    private final IdentityProviderMapper providerMapper;
    private final ConfigCryptoService crypto;
    private final UserMapper userMapper;
    private final UserRoleService userRoleService;
    private final IdentityService identityService;
    private final ExternalUserService externalUserService;
    private final GroupRoleMappingMapper mappingMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final SyncJobLogMapper syncLogMapper;

    private final ObjectMapper json = new ObjectMapper();

    public static final String SCIM_LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String SCIM_USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String SCIM_GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String SCIM_PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SCIM_ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String BASE_PATH = "/api/ai/scim/v2";

    // ==================== 鉴权 ====================

    /** SCIM 请求鉴权：Authorization: Bearer 与某 enabled 认证源的 config.bearerToken 常数时间比对 */
    public IdentityProvider authenticate(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7).trim();
            if (!token.isBlank()) {
                for (IdentityProvider p : enabledScimProviders()) {
                    String expect = bearerToken(p);
                    if (expect != null && constantTimeEquals(token, expect)) return p;
                }
            }
        }
        throw new ScimException(401, "SCIM 凭据无效或未配置认证源");
    }

    private List<IdentityProvider> enabledScimProviders() {
        return providerMapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                .eq(IdentityProvider::getType, "scim")
                .eq(IdentityProvider::getEnabled, 1));
    }

    /** config JSON 里的 bearerToken（整块 RSA 解密后取值） */
    public String bearerToken(IdentityProvider p) {
        JsonNode c = config(p);
        return c == null ? null : text(c, "bearerToken");
    }

    /** config JSON（整块 RSA 解密后解析；坏配置返回 null） */
    public JsonNode config(IdentityProvider p) {
        String raw = p.getConfig();
        if (raw == null || raw.isBlank()) return null;
        if (raw.startsWith("RSA:")) raw = crypto.decrypt(raw);
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            log.warn("[SCIM] 认证源 config 解析失败 provider={}: {}", p.getId(), e.getMessage());
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== Users ====================

    /** 按 id（本地 uid）取用户（控制器 GET /Users/{id} 守门用） */
    public User findUserById(String uid) {
        return uid == null || uid.isBlank() ? null : userMapper.selectById(uid);
    }

    /** User → SCIM 资源（id=本地 uid；externalId 从身份绑定回填） */
    public Map<String, Object> toScimUser(User u) {
        var bindings = identityService.listOf(u.getUid());
        String externalId = bindings.isEmpty() ? null : bindings.get(0).getExternalId();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schemas", List.of(SCIM_USER_SCHEMA));
        m.put("id", u.getUid());
        m.put("userName", u.getUid());
        m.put("displayName", u.getUsername());
        m.put("active", u.getStatus() == null || u.getStatus() == 1);
        if (externalId != null) m.put("externalId", externalId);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("resourceType", "User");
        meta.put("location", BASE_PATH + "/Users/" + u.getUid());
        m.put("meta", meta);
        return m;
    }

    /**
     * 建号：userName（必填）→ uid（非法字符替换，冲突加短哈希）；active=false 直接建成禁用。
     * 不设密码；默认角色 grant idp 行；绑定身份 (provider, externalId ?? userName)。
     */
    public User createScimUser(IdentityProvider p, JsonNode body) {
        String userName = text(body, "userName");
        if (userName == null) throw new ScimException(400, "SCIM User 缺少必填属性 userName");
        JsonNode nameNode = body.get("name");
        String displayName = firstNonBlank(text(body, "displayName"),
                nameNode == null ? null : text(nameNode, "formatted"));
        String externalId = firstNonBlank(text(body, "externalId"), userName);
        boolean active = !Boolean.FALSE.equals(boolOf(body.get("active")));

        User exist = resolveByScimIdentity(p, externalId, userName);
        if (exist != null) throw new ScimException(409, "用户已存在（userName/externalId 冲突）: " + userName);

        String uid = externalUserService.ensureUniqueUid(
                externalUserService.sanitizeUid("scim", userName), externalId);
        String username = externalUserService.ensureUniqueUsername(
                displayName == null ? userName : displayName, externalId);

        User u = new User();
        u.setUid(uid);
        u.setUsername(username);
        u.setStatus(active ? 1 : 0);
        u.setLoginFailCount(0);
        try {
            userMapper.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ScimException(409, "用户创建冲突，请重试: " + userName);
        }
        identityService.bind(uid, p.getId(), externalId);
        upsertAttrs(uid, p.getId(), externalId, body);
        // 默认角色（认证源行配置）：grant idp 行；角色未配置/停用只告警不断链——建号先落，角色后补
        String defaultRole = p.getDefaultRole();
        if (defaultRole != null && !defaultRole.isBlank()) {
            try {
                userRoleService.grant(uid, defaultRole, "idp");
            } catch (Exception e) {
                log.warn("[SCIM] 默认角色授予失败 uid={} role={}: {}", uid, defaultRole, e.getMessage());
            }
        }
        syncLog(p, 1, 0, active ? 0 : 1, "createUser " + userName + (active ? "" : " (inactive)"));
        log.info("[AUDIT] SCIM 建号 provider={} uid={} externalId={} active={}", p.getId(), uid, externalId, active);
        return u;
    }

    /** PUT 全量更新（displayName/active/externalAttrs） */
    public User updateScimUser(IdentityProvider p, String uid, JsonNode body) {
        User u = requireUser(uid);
        JsonNode nameNode = body.get("name");
        String displayName = firstNonBlank(text(body, "displayName"),
                nameNode == null ? null : text(nameNode, "formatted"));
        if (displayName != null) u.setUsername(displayName);
        Boolean active = boolOf(body.get("active"));
        if (active != null) applyActive(p, u, active, "PUT");
        userMapper.updateById(u);
        String externalId = firstNonBlank(text(body, "externalId"), userNameFallback(p, uid));
        upsertAttrs(uid, p.getId(), externalId, body);
        syncLog(p, 0, 1, 0, "updateUser " + uid);
        return u;
    }

    /** PATCH（Entra/Okta 的禁用/启用走这里）：支持 replace active / replace displayName */
    public User patchScimUser(IdentityProvider p, String uid, JsonNode body) {
        User u = requireUser(uid);
        JsonNode ops = body.get("Operations");
        if (ops == null) ops = body.get("operations");
        if (ops == null || !ops.isArray()) throw new ScimException(400, "PATCH 缺少 Operations");
        boolean touched = false;
        for (JsonNode op : ops) {
            String opName = text(op, "op");
            String path = text(op, "path");
            JsonNode value = op.get("value");
            if (opName == null) continue;
            boolean isReplace = "replace".equalsIgnoreCase(opName) || "add".equalsIgnoreCase(opName);
            if (isReplace && "active".equalsIgnoreCase(path)) {
                Boolean v = value == null ? null : value.isBoolean() ? value.asBoolean()
                        : value.isTextual() ? Boolean.parseBoolean(value.asText()) : null;
                if (v == null) throw new ScimException(400, "PATCH active 的 value 必须是布尔");
                applyActive(p, u, v, "PATCH");
                touched = true;
            } else if (isReplace && (path == null || path.equalsIgnoreCase("displayName")) && value != null) {
                String d = value.isTextual() ? value.asText() : text(value, "displayName");
                if (d != null && !d.isBlank()) {
                    u.setUsername(d);
                    touched = true;
                }
            } else if ("remove".equalsIgnoreCase(opName) && "active".equalsIgnoreCase(path)) {
                // remove active 无意义，忽略
            }
        }
        if (touched) userMapper.updateById(u);
        syncLog(p, 0, 1, 0, "patchUser " + uid);
        return u;
    }

    /** DELETE（D4：销户默认 disable 可恢复）——禁用唯一管理员被拒（防自锁） */
    public void deleteScimUser(IdentityProvider p, String uid) {
        User u = requireUser(uid);
        applyActive(p, u, false, "DELETE");
        syncLog(p, 0, 0, 1, "deleteUser(disable) " + uid);
        log.info("[AUDIT] SCIM 销户（disable 可恢复）provider={} uid={}", p.getId(), uid);
    }

    private void applyActive(IdentityProvider p, User u, boolean active, String via) {
        if (!active) {
            if (userRoleService.isAdmin(u.getUid()) && userRoleService.isLastAdminAccount()) {
                throw new ScimException(400, "禁用被拒：「" + u.getUsername() + "」是最后一名管理员级账号（防自锁），请先在 IdP 侧转移角色");
            }
            u.setStatus(0);
        } else {
            u.setStatus(1);
        }
        userMapper.updateById(u);
        log.info("[AUDIT] SCIM 账号{} provider={} uid={} via={}", active ? "启用" : "禁用", p.getId(), u.getUid(), via);
    }

    /** SCIM filter（仅支持 userName eq "x" / externalId eq "x" —— Entra/Okta 主用形态） */
    public User findByFilter(IdentityProvider p, String filter) {
        if (filter == null || filter.isBlank()) return null;
        var m = java.util.regex.Pattern
                .compile("(\\w+)\\s+eq\\s+\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(filter.trim());
        if (!m.matches()) throw new ScimException(400, "不支持的 filter 语法（仅支持 <attr> eq \"value\"）");
        String attr = m.group(1).toLowerCase();
        String value = m.group(2);
        if ("username".equals(attr)) {
            return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUid, value).last("LIMIT 1"));
        }
        if ("externalid".equals(attr)) {
            String uid = identityService.uidOfExternal(p.getId(), value);
            return uid == null ? null : userMapper.selectById(uid);
        }
        if ("displayname".equals(attr)) {
            return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, value).last("LIMIT 1"));
        }
        throw new ScimException(400, "不支持的 filter 属性: " + attr);
    }

    private User requireUser(String uid) {
        User u = uid == null ? null : userMapper.selectById(uid);
        if (u == null) throw new ScimException(404, "用户不存在: " + uid);
        return u;
    }

    /** SCIM 身份三路解析：绑定表 externalId → uid 直认 → userName（兜底） */
    private User resolveByScimIdentity(IdentityProvider p, String externalId, String userName) {
        String uid = identityService.uidOfExternal(p.getId(), externalId);
        if (uid != null) return userMapper.selectById(uid);
        if (userName != null && userName.matches("[A-Za-z0-9_@.\\-]{1,64}")) {
            User u = userMapper.selectById(userName);
            if (u != null) return u;
        }
        return null;
    }

    private String userNameFallback(IdentityProvider p, String uid) {
        var i = identityService.findOf(uid, p.getId());
        return i == null ? null : i.getExternalId();
    }

    /** SCIM 属性快照 + lastSynced 落身份绑定行 */
    private void upsertAttrs(String uid, String providerId, String externalId, JsonNode body) {
        try {
            var i = identityService.findOf(uid, providerId);
            if (i == null) {
                identityService.bind(uid, providerId, externalId);
                i = identityService.findOf(uid, providerId);
            }
            if (i == null) return;
            if (!body.isEmpty()) i.setExternalAttrs(json.writeValueAsString(body));
            i.setLastSynced(LocalDateTime.now());
            identityService.updateAttrs(i);
        } catch (Exception e) {
            log.warn("[SCIM] 属性快照落库失败 uid={}: {}", uid, e.getMessage());
        }
    }

    // ==================== Groups ====================

    /** 组 → SCIM 资源（id=external_group；members 从成员事实源回填） */
    public Map<String, Object> toScimGroup(GroupRoleMapping g) {
        List<GroupMember> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getProviderId, g.getProviderId())
                .eq(GroupMember::getExternalGroup, g.getExternalGroup()));
        List<Map<String, Object>> mems = new ArrayList<>();
        for (GroupMember gm : members) {
            mems.add(Map.of("value", gm.getUid(), "display", gm.getUid()));
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("schemas", List.of(SCIM_GROUP_SCHEMA));
        m.put("id", g.getExternalGroup());
        m.put("displayName", g.getExternalGroup());
        m.put("members", mems);
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("resourceType", "Group");
        meta.put("location", BASE_PATH + "/Groups/" + g.getExternalGroup());
        m.put("meta", meta);
        return m;
    }

    public List<GroupRoleMapping> listGroups(IdentityProvider p) {
        return mappingMapper.selectList(new LambdaQueryWrapper<GroupRoleMapping>()
                .eq(GroupRoleMapping::getProviderId, p.getId())
                .orderByAsc(GroupRoleMapping::getExternalGroup));
    }

    public GroupRoleMapping requireGroup(IdentityProvider p, String groupId) {
        GroupRoleMapping g = mappingMapper.selectOne(new LambdaQueryWrapper<GroupRoleMapping>()
                .eq(GroupRoleMapping::getProviderId, p.getId())
                .eq(GroupRoleMapping::getExternalGroup, groupId).last("LIMIT 1"));
        if (g == null) throw new ScimException(404, "组不存在: " + groupId);
        return g;
    }

    /** 建组（幂等）：登记组名 + 成员落事实源；已配置映射则成员即时授角色/部门 */
    public GroupRoleMapping createGroup(IdentityProvider p, JsonNode body) {
        String displayName = text(body, "displayName");
        if (displayName == null) throw new ScimException(400, "SCIM Group 缺少必填属性 displayName");
        GroupRoleMapping exist = mappingMapper.selectOne(new LambdaQueryWrapper<GroupRoleMapping>()
                .eq(GroupRoleMapping::getProviderId, p.getId())
                .eq(GroupRoleMapping::getExternalGroup, displayName).last("LIMIT 1"));
        GroupRoleMapping g = exist != null ? exist : newGroupMapping(p, displayName);
        applyMembers(p, g, membersOf(body), true);
        syncLog(p, 0, 0, 0, "createGroup " + displayName);
        return g;
    }

    /** PATCH 组：add/remove members（Entra/Okta 推增量） */
    public GroupRoleMapping patchGroup(IdentityProvider p, String groupId, JsonNode body) {
        GroupRoleMapping g = requireGroup(p, groupId);
        JsonNode ops = body.get("Operations");
        if (ops == null) ops = body.get("operations");
        if (ops == null || !ops.isArray()) throw new ScimException(400, "PATCH 缺少 Operations");
        for (JsonNode op : ops) {
            String opName = text(op, "op");
            JsonNode value = op.get("value");
            if (opName == null || value == null || !value.isArray()) continue;
            boolean add = "add".equalsIgnoreCase(opName);
            if (add || "remove".equalsIgnoreCase(opName)) {
                applyMembers(p, g, memberValues(value), add);
            }
        }
        syncLog(p, 0, 0, 0, "patchGroup " + groupId);
        return g;
    }

    /** PUT 组：members 全量对齐（diff：新增/移除，再按映射联动） */
    public GroupRoleMapping updateGroup(IdentityProvider p, String groupId, JsonNode body) {
        GroupRoleMapping g = requireGroup(p, groupId);
        List<String> target = membersOf(body);
        List<GroupMember> current = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getProviderId, p.getId())
                .eq(GroupMember::getExternalGroup, g.getExternalGroup()));
        var currentUids = current.stream().map(GroupMember::getUid).toList();
        for (String uid : target) {
            if (!currentUids.contains(uid)) applyMemberJoin(p, g, uid);
        }
        for (GroupMember gm : current) {
            if (!target.contains(gm.getUid())) applyMemberLeave(p, g, gm.getUid());
        }
        syncLog(p, 0, 0, 0, "updateGroup " + groupId);
        return g;
    }

    /** 删组：清映射与成员，并回收该组授予的 idp 角色 */
    public void deleteGroup(IdentityProvider p, String groupId) {
        GroupRoleMapping g = requireGroup(p, groupId);
        List<GroupMember> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getProviderId, p.getId())
                .eq(GroupMember::getExternalGroup, g.getExternalGroup()));
        for (GroupMember gm : members) {
            applyMemberLeave(p, g, gm.getUid());
        }
        mappingMapper.deleteById(g.getId());
        syncLog(p, 0, 0, 0, "deleteGroup " + groupId);
        log.info("[AUDIT] SCIM 删组 provider={} group={}", p.getId(), groupId);
    }

    private List<String> membersOf(JsonNode body) {
        return body == null ? List.of() : memberValues(body.get("members"));
    }

    /** 从 members 数组节点提取 value 列表（PATCH 的 value 也是同一形态） */
    private List<String> memberValues(JsonNode members) {
        List<String> out = new ArrayList<>();
        if (members != null && members.isArray()) {
            for (JsonNode m : members) {
                String v = text(m, "value");
                if (v != null && !v.isBlank()) out.add(v);
            }
        }
        return out;
    }

    private void applyMembers(IdentityProvider p, GroupRoleMapping g, List<String> memberValues, boolean join) {
        for (String v : memberValues) {
            String uid = resolveMemberUid(p, v);
            if (uid == null) {
                log.warn("[SCIM] 组成员 {} 无法解析为本地账号（跳过）group={}", v, g.getExternalGroup());
                continue;
            }
            if (join) applyMemberJoin(p, g, uid);
            else applyMemberLeave(p, g, uid);
        }
    }

    /** 入组：成员事实源 +（已配置规则时）grant 角色 / 更新部门 */
    private void applyMemberJoin(IdentityProvider p, GroupRoleMapping g, String uid) {
        GroupMember exist = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getProviderId, p.getId())
                .eq(GroupMember::getExternalGroup, g.getExternalGroup())
                .eq(GroupMember::getUid, uid).last("LIMIT 1"));
        if (exist == null) {
            GroupMember gm = new GroupMember();
            gm.setId(UUID.randomUUID().toString());
            gm.setProviderId(p.getId());
            gm.setExternalGroup(g.getExternalGroup());
            gm.setUid(uid);
            groupMemberMapper.insert(gm);
        }
        if (g.getRoleCode() != null && !g.getRoleCode().isBlank()) {
            userRoleService.grant(uid, g.getRoleCode(), "idp");
        }
        if (g.getDepartmentId() != null && !g.getDepartmentId().isBlank()) {
            User u = userMapper.selectById(uid);
            if (u != null && !g.getDepartmentId().equals(u.getDepartmentId())) {
                u.setDepartmentId(g.getDepartmentId());
                userMapper.updateById(u);
            }
        }
    }

    /** 出组：清成员事实源 +（已配置规则时）回收该组授予的 idp 角色行（手动行不动） */
    private void applyMemberLeave(IdentityProvider p, GroupRoleMapping g, String uid) {
        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getProviderId, p.getId())
                .eq(GroupMember::getExternalGroup, g.getExternalGroup())
                .eq(GroupMember::getUid, uid));
        if (g.getRoleCode() != null && !g.getRoleCode().isBlank()) {
            userRoleService.revokeIdpRole(uid, g.getRoleCode());
        }
    }

    /** 成员 value 三路解析：本地 uid 直认 → 身份绑定 externalId → userName */
    private String resolveMemberUid(IdentityProvider p, String value) {
        if (value == null || value.isBlank()) return null;
        if (userMapper.selectById(value) != null) return value;
        String uid = identityService.uidOfExternal(p.getId(), value);
        if (uid != null) return uid;
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, value).last("LIMIT 1"));
        return u == null ? null : u.getUid();
    }

    private GroupRoleMapping newGroupMapping(IdentityProvider p, String displayName) {
        GroupRoleMapping g = new GroupRoleMapping();
        g.setId(UUID.randomUUID().toString());
        g.setProviderId(p.getId());
        g.setExternalGroup(displayName);
        mappingMapper.insert(g);
        log.info("[AUDIT] SCIM 组登记（映射规则待配置）provider={} group={}", p.getId(), displayName);
        return g;
    }

    // ==================== 日志 ====================

    private void syncLog(IdentityProvider p, int created, int updated, int disabled, String detail) {
        try {
            SyncJobLog l = new SyncJobLog();
            l.setId(UUID.randomUUID().toString());
            l.setSourceType("scim");
            l.setSourceId(p.getId());
            l.setUsersCreated(created);
            l.setUsersUpdated(updated);
            l.setUsersDisabled(disabled);
            l.setStatus("success");
            l.setDetail(detail);
            syncLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[SCIM] 同步日志写入失败 provider={}: {}", p.getId(), e.getMessage());
        }
    }

    // ==================== 杂项 ====================

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
    }

    private static Boolean boolOf(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) return Boolean.parseBoolean(v.asText());
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    /** SCIM 协议异常（status = HTTP 状态码） */
    public static class ScimException extends RuntimeException {
        public final int status;

        public ScimException(int status, String detail) {
            super(detail);
            this.status = status;
        }
    }
}
