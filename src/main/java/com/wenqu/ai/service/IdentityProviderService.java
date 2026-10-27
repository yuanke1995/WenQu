package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.IdentityProviderMapper;
import com.wenqu.ai.model.IdentityProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一认证源管理（c_ai_identity_provider，type=saml|scim|ldap）：CRUD + 敏感字段脱敏/加密。
 * <p>
 * 敏感字段（bearerToken/bindPassword/spPrivateKey）回显 {@code ******}；保存时遇掩码值
 * 保留原值（与 OidcProviderService 的 client_secret 同口径）。整块 config RSA 加密落库。
 * OIDC 仍在旧表（迁移期并存，退役见任务 #7），本表承载新协议认证源。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityProviderService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private final IdentityProviderMapper mapper;
    private final TenantService tenantService;
    private final ConfigCryptoService crypto;
    private final IdentityService identityService;
    private final ObjectMapper json = new ObjectMapper();

    /** config JSON 里的敏感字段名（回显掩码、保存时掩码不覆盖） */
    private static final Set<String> SENSITIVE = Set.of("bearerToken", "bindPassword", "spPrivateKey", "clientSecret");
    private static final String MASK = "******";

    /**
     * 认证源是管理面资源（Phase 4）：superadmin 要能看到并改别家租户的认证源，
     * 否则那个租户的认证源一旦建好就再也没有入口能改或删（管理动作不能留这种死路）。
     * 读、写、删整段都要在平台视图里跑——写侧尤其：{@code updateById} 被拼上本租户条件后
     * 匹配 0 行是<b>一声不响</b>的，界面会提示「已保存」而什么都没改。
     * 租户管理员不在放开范围内：他只看得到、也只改得动自己租户的认证源。
     */
    private <T> T asPlatformIfSuper(java.util.function.Supplier<T> body) {
        return com.wenqu.ai.util.RequestUser.superadmin()
                ? com.wenqu.ai.util.TenantContext.asPlatform(body) : body.get();
    }

    public List<Map<String, Object>> list() {
        return asPlatformIfSuper(() -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (IdentityProvider p : mapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                    .orderByAsc(IdentityProvider::getType).orderByAsc(IdentityProvider::getSortOrder))) {
                out.add(toView(p));
            }
            return out;
        });
    }

    public Map<String, Object> toView(IdentityProvider p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("type", p.getType());
        m.put("name", p.getName());
        m.put("enabled", p.getEnabled());
        m.put("defaultRole", p.getDefaultRole());
        m.put("defaultDepartment", p.getDefaultDepartment());
        m.put("autoCreateUser", p.getAutoCreateUser());
        m.put("sortOrder", p.getSortOrder());
        m.put("config", maskedConfig(p));
        m.put("boundUsers", identityService.boundCount(p.getId()));
        // 租户维度只在真有多家时出现：单租户部署里加一列「默认租户」是噪声，
        // 而本表的列由数据键自动派生，多一个键就多一列表头
        if (tenantService.multiTenant()) {
            String t = p.getTenantId() == null || p.getTenantId().isBlank()
                    ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : p.getTenantId();
            m.put("tenantId", t);
            m.put("tenantName", tenantService.nameOf(t));
        }
        return m;
    }

    /** config 解密 → 敏感字段掩码（编辑页回显） */
    private Map<String, Object> maskedConfig(IdentityProvider p) {
        JsonNode c = decryptConfig(p);
        if (c == null) return new LinkedHashMap<>();
        Map<String, Object> m = json.convertValue(c, Map.class);
        for (String k : SENSITIVE) {
            if (m.get(k) != null && !String.valueOf(m.get(k)).isBlank()) m.put(k, MASK);
        }
        return m;
    }

    private JsonNode decryptConfig(IdentityProvider p) {
        return decryptRaw(p.getConfig());
    }

    private JsonNode decryptRaw(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = crypto.decrypt(raw);
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            log.warn("[IDENTITY] config 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public IdentityProvider getById(String id) {
        return mapper.selectById(id);
    }

    /** 管理面读取（测试连接/立即同步）：superadmin 要能点到别家租户的认证源，判据见 {@link #asPlatformIfSuper} */
    public IdentityProvider getByIdForAdmin(String id) {
        return asPlatformIfSuper(() -> mapper.selectById(id));
    }

    /** 新建：config 整块 RSA 加密入库 */
    public IdentityProvider create(String type, String name, Integer enabled, String defaultRole,
                                   String defaultDepartment, Integer autoCreateUser, Integer sortOrder,
                                   Map<String, Object> config, String tenantId) {
        if (!Set.of("oidc", "saml", "scim", "ldap").contains(type)) {
            throw new BizException("不支持的认证源类型: " + type);
        }
        IdentityProvider p = new IdentityProvider();
        p.setId(UUID.randomUUID().toString());
        p.setType(type);
        // 显式带归属：插入时拦截器不再补第二份 tenant_id（判据见 WenquTenantLineHandler.ignoreInsert）
        p.setTenantId(tenantService.requireAssignable(tenantId, null));
        applyFields(p, name, enabled, defaultRole, defaultDepartment, autoCreateUser, sortOrder, config, null);
        mapper.insert(p);
        audit.record("[AUDIT] 新建认证源 type={} id={} name={} 租户={}", type, p.getId(), name, p.getTenantId());
        return p;
    }

    /** 编辑：config 里掩码值保留原值（不覆盖已存敏感字段）；tenantId 留空＝归属不动 */
    public IdentityProvider update(String id, String name, Integer enabled, String defaultRole,
                                   String defaultDepartment, Integer autoCreateUser, Integer sortOrder,
                                   Map<String, Object> config, String tenantId) {
        return asPlatformIfSuper(() -> {
            IdentityProvider p = mapper.selectById(id);
            if (p == null) throw new BizException("认证源不存在");
            String owner = p.getTenantId() == null || p.getTenantId().isBlank()
                    ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : p.getTenantId();
            String target = tenantId == null || tenantId.isBlank()
                    ? owner : tenantService.requireAssignable(tenantId, owner);
            applyFields(p, name, enabled, defaultRole, defaultDepartment, autoCreateUser, sortOrder, config, p.getConfig());
            p.setTenantId(target);
            mapper.updateById(p);
            if (!target.equals(owner)) {
                // 从属行按 provider_id 决定归属，必须跟着认证源一起走，判据见 IdentityProviderMapper.restampBindings
                int b = mapper.restampBindings(id, target);
                int gm = mapper.restampGroupMappings(id, target);
                int gmm = mapper.restampGroupMembers(id, target);
                int sl = mapper.restampSyncLogs(id, target);
                audit.record("[AUDIT] 认证源归属变更 id={} 从租户={} 到租户={} 随迁 绑定={} 组映射={} 组成员={} 同步日志={}",
                        id, owner, target, b, gm, gmm, sl);
            }
            audit.record("[AUDIT] 编辑认证源 id={} name={} 租户={}", id, p.getName(), target);
            return p;
        });
    }

    /** 删除守门：仍有用户绑定即拒（与 OidcProviderService 同口径） */
    public void delete(String id) {
        asPlatformIfSuper(() -> {
            deleteScoped(id);
            return null;
        });
    }

    private void deleteScoped(String id) {
        IdentityProvider p = mapper.selectById(id);
        if (p == null) throw new BizException("认证源不存在");
        long bound = identityService.boundCount(id);
        if (bound > 0) {
            throw new BizException("认证源「" + p.getName() + "」仍绑定 " + bound + " 个账号，请先解绑或回收后再删除");
        }
        mapper.deleteById(id);
        audit.record("[AUDIT] 删除认证源 id={} name={}", id, p.getName());
    }

    private void applyFields(IdentityProvider p, String name, Integer enabled, String defaultRole,
                             String defaultDepartment, Integer autoCreateUser, Integer sortOrder,
                             Map<String, Object> config, String oldRawConfig) {
        if (name != null && !name.isBlank()) p.setName(name.trim());
        if (enabled != null) p.setEnabled(enabled);
        if (defaultRole != null) p.setDefaultRole(defaultRole.isBlank() ? null : defaultRole.trim().toLowerCase());
        if (defaultDepartment != null) p.setDefaultDepartment(defaultDepartment.isBlank() ? null : defaultDepartment.trim());
        if (autoCreateUser != null) p.setAutoCreateUser(autoCreateUser);
        if (sortOrder != null) p.setSortOrder(sortOrder);
        if (config != null) {
            // 掩码值回填旧值：只对已存 config 里的同名敏感字段
            Map<String, Object> merged = new LinkedHashMap<>(config);
            JsonNode old = decryptRaw(oldRawConfig);
            if (old != null) {
                for (String k : SENSITIVE) {
                    Object v = merged.get(k);
                    if (v != null && MASK.equals(String.valueOf(v)) && old.has(k) && !old.get(k).isNull()) {
                        merged.put(k, old.get(k).asText());
                    }
                }
            }
            try {
                String raw = json.writeValueAsString(merged);
                p.setConfig(crypto.encrypt(raw));
            } catch (Exception e) {
                throw new BizException("认证源配置序列化失败: " + e.getMessage());
            }
        }
    }
}
