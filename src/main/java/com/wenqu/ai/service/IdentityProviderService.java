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

    private final IdentityProviderMapper mapper;
    private final ConfigCryptoService crypto;
    private final IdentityService identityService;
    private final ObjectMapper json = new ObjectMapper();

    /** config JSON 里的敏感字段名（回显掩码、保存时掩码不覆盖） */
    private static final Set<String> SENSITIVE = Set.of("bearerToken", "bindPassword", "spPrivateKey", "clientSecret");
    private static final String MASK = "******";

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (IdentityProvider p : mapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                .orderByAsc(IdentityProvider::getType).orderByAsc(IdentityProvider::getSortOrder))) {
            out.add(toView(p));
        }
        return out;
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
        if (raw.startsWith("RSA:")) raw = crypto.decrypt(raw);
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

    /** 新建：config 整块 RSA 加密入库 */
    public IdentityProvider create(String type, String name, Integer enabled, String defaultRole,
                                   String defaultDepartment, Integer autoCreateUser, Integer sortOrder,
                                   Map<String, Object> config) {
        if (!Set.of("oidc", "saml", "scim", "ldap").contains(type)) {
            throw new BizException("不支持的认证源类型: " + type);
        }
        IdentityProvider p = new IdentityProvider();
        p.setId(UUID.randomUUID().toString());
        p.setType(type);
        applyFields(p, name, enabled, defaultRole, defaultDepartment, autoCreateUser, sortOrder, config, null);
        mapper.insert(p);
        log.info("[AUDIT] 新建认证源 type={} id={} name={}", type, p.getId(), name);
        return p;
    }

    /** 编辑：config 里掩码值保留原值（不覆盖已存敏感字段） */
    public IdentityProvider update(String id, String name, Integer enabled, String defaultRole,
                                   String defaultDepartment, Integer autoCreateUser, Integer sortOrder,
                                   Map<String, Object> config) {
        IdentityProvider p = mapper.selectById(id);
        if (p == null) throw new BizException("认证源不存在");
        applyFields(p, name, enabled, defaultRole, defaultDepartment, autoCreateUser, sortOrder, config, p.getConfig());
        mapper.updateById(p);
        log.info("[AUDIT] 编辑认证源 id={} name={}", id, p.getName());
        return p;
    }

    /** 删除守门：仍有用户绑定即拒（与 OidcProviderService 同口径） */
    public void delete(String id) {
        IdentityProvider p = mapper.selectById(id);
        if (p == null) throw new BizException("认证源不存在");
        long bound = identityService.boundCount(id);
        if (bound > 0) {
            throw new BizException("认证源「" + p.getName() + "」仍绑定 " + bound + " 个账号，请先解绑或回收后再删除");
        }
        mapper.deleteById(id);
        log.info("[AUDIT] 删除认证源 id={} name={}", id, p.getName());
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
                p.setConfig("RSA:" + crypto.encrypt(raw));
            } catch (Exception e) {
                throw new BizException("认证源配置序列化失败: " + e.getMessage());
            }
        }
    }
}
