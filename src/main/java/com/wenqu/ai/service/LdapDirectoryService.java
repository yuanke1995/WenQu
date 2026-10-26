package com.wenqu.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenqu.ai.model.IdentityProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LDAP/AD 目录客户端（JNDI 原生，零新增依赖）：连接、拉用户、拉组、成员归属。
 * <p>
 * config JSON 字段：
 * <pre>{ "url": "ldap://ad.corp.com:389", "baseDn": "DC=corp,DC=com",
 *        "bindDn": "CN=svc-wenqu,DC=corp,DC=com", "bindPassword": "..."（RSA: 加密）,
 *        "userFilter": "(objectClass=user)", "uidAttr": "sAMAccountName",
 *        "nameAttr": "displayName", "memberOfAttr": "memberOf",      ← AD 形态（用户侧组属性）
 *        "groupBaseDn": "OU=Groups,...", "groupFilter": "(objectClass=group)",
 *        "groupNameAttr": "cn", "groupMemberAttr": "member" }        ← OpenLDAP 形态（组反查）
 *        "syncIntervalMinutes": 60 }</pre>
 * 组归属解析：配置了 {@code memberOfAttr} 用用户属性（AD 主流）；否则按组反查
 * {@code (groupMemberAttr=<用户dn>)}（OpenLDAP/posixGroup 主流）——两种可同时配，取并集。
 * 组值统一映射为「组名」（AD 的 memberOf 是 dn → 取 groupNameAttr 段；OpenLDAP 直接 groupNameAttr）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LdapDirectoryService {

    private final ConfigCryptoService crypto;

    /** 远端目录里的一个用户（含组归属） */
    public record LdapUser(String externalId, String uid, String displayName, List<String> groups) {
    }

    // ==================== 连接 ====================

    /** 连接测试（管理员「先测后存」用）：bind 成功 + baseDn 可搜索即通过 */
    public void testConnection(IdentityProvider p) {
        LdapConfig c = config(p);
        LdapContext ctx = null;
        try {
            ctx = connect(c);
            SearchControls sc = searchOne();
            ctx.search(c.baseDn, "(objectClass=*)", sc);
        } catch (NamingException e) {
            throw new IllegalArgumentException("LDAP 连接失败: " + e.getMessage(), e);
        } finally {
            close(ctx);
        }
    }

    /** 拉全部用户（含组归属）：sizeLimit 防御性上限 10000，分页 PagedResults 企业规模再补 */
    public List<LdapUser> fetchUsers(IdentityProvider p) {
        LdapConfig c = config(p);
        LdapContext ctx = null;
        try {
            ctx = connect(c);
            SearchControls sc = searchOne();
            sc.setReturningAttributes(returningAttrs(c));
            List<LdapUser> out = new ArrayList<>();
            NamingEnumeration<SearchResult> rs = ctx.search(c.baseDn, c.userFilter(), sc);
            while (rs.hasMore()) {
                SearchResult r = rs.next();
                Attributes attrs = r.getAttributes();
                String uid = attr(attrs, c.uidAttr());
                if (uid == null) continue; // 无账号属性（如纯联系人条目）跳过
                String externalId = r.getNameInNamespace(); // dn 作为外部唯一标识
                String displayName = attr(attrs, c.nameAttr());
                List<String> groups = resolveGroups(c, ctx, externalId, attrs);
                out.add(new LdapUser(externalId, uid, displayName, groups));
                if (out.size() >= 10000) {
                    log.warn("[LDAP] 目录用户数达到防御上限 10000，本次截断 provider={}", p.getId());
                    break;
                }
            }
            return out;
        } catch (NamingException e) {
            throw new IllegalStateException("LDAP 拉取用户失败: " + e.getMessage(), e);
        } finally {
            close(ctx);
        }
    }

    // ==================== 组归属 ====================

    /** 用户 dn → 组名清单（memberOf 用户属性 与 组反查 两种形态取并集） */
    private List<String> resolveGroups(LdapConfig c, LdapContext ctx, String userDn, Attributes attrs) throws NamingException {
        List<String> out = new ArrayList<>();
        if (c.memberOfAttr() != null) {
            NamingEnumeration<?> vals = attrs.get(c.memberOfAttr()) == null
                    ? null : attrs.get(c.memberOfAttr()).getAll();
            if (vals != null) {
                while (vals.hasMore()) {
                    String dn = String.valueOf(vals.next());
                    String name = cnOf(dn);
                    if (name != null) out.add(name);
                }
            }
        }
        if (c.groupBaseDn() != null && c.groupMemberAttr() != null) {
            SearchControls sc = searchOne();
            sc.setReturningAttributes(new String[]{c.groupNameAttr()});
            NamingEnumeration<SearchResult> rs = ctx.search(c.groupBaseDn(),
                    "(" + c.groupMemberAttr() + "=" + escape(userDn) + ")", sc);
            while (rs.hasMore()) {
                String name = attr(rs.next().getAttributes(), c.groupNameAttr());
                if (name != null && !out.contains(name)) out.add(name);
            }
        }
        return out;
    }

    // ==================== config ====================

    public LdapConfig config(IdentityProvider p) {
        String raw = p.getConfig();
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("LDAP 认证源未配置");
        if (raw.startsWith("RSA:")) raw = crypto.decrypt(raw);
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            String url = text(n, "url");
            String baseDn = text(n, "baseDn");
            if (url == null || baseDn == null) {
                throw new IllegalArgumentException("LDAP 认证源缺少 url / baseDn 配置");
            }
            return new LdapConfig(url, baseDn,
                    text(n, "bindDn"), text(n, "bindPassword"),
                    orDft(text(n, "userFilter"), "(objectClass=person)"),
                    orDft(text(n, "uidAttr"), "uid"),
                    orDft(text(n, "nameAttr"), "displayName"),
                    text(n, "memberOfAttr"),
                    text(n, "groupBaseDn"),
                    orDft(text(n, "groupFilter"), "(objectClass=groupOfNames)"),
                    orDft(text(n, "groupNameAttr"), "cn"),
                    orDft(text(n, "groupMemberAttr"), "member"),
                    parseInt(text(n, "syncIntervalMinutes"), 60));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("LDAP config 解析失败: " + e.getMessage(), e);
        }
    }

    public record LdapConfig(String url, String baseDn, String bindDn, String bindPassword,
                             String userFilter, String uidAttr, String nameAttr, String memberOfAttr,
                             String groupBaseDn, String groupFilter, String groupNameAttr, String groupMemberAttr,
                             int syncIntervalMinutes) {
    }

    // ==================== JNDI ====================

    private LdapContext connect(LdapConfig c) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, c.url());
        env.put(Context.SECURITY_AUTHENTICATION, c.bindDn() != null ? "simple" : "none");
        if (c.bindDn() != null) {
            env.put(Context.SECURITY_PRINCIPAL, c.bindDn());
            env.put(Context.SECURITY_CREDENTIALS, c.bindPassword() == null ? "" : c.bindPassword());
        }
        // STARTTLS（389 明文口）。SSL(636) 由 url=ldaps:// 支持（truststore 走 JVM 默认）
        env.put("java.naming.ldap.version", "3");
        env.put(Context.OBJECT_FACTORIES, "java.naming");
        return new InitialLdapContext(env, null);
    }

    private static SearchControls searchOne() {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setCountLimit(0);
        return sc;
    }

    private static String[] returningAttrs(LdapConfig c) {
        List<String> l = new ArrayList<>(List.of(c.uidAttr(), c.nameAttr()));
        if (c.memberOfAttr() != null) l.add(c.memberOfAttr());
        return l.toArray(new String[0]);
    }

    private static String attr(Attributes attrs, String name) throws NamingException {
        if (name == null || attrs.get(name) == null) return null;
        String v = String.valueOf(attrs.get(name).get());
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** dn → 最左 cn 段（memberOf 值是 dn，统一折算组名） */
    private static String cnOf(String dn) {
        if (dn == null) return null;
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.length() > 3 && (t.startsWith("cn=") || t.startsWith("CN="))) return t.substring(3);
        }
        return null;
    }

    private static String escape(String v) {
        return v == null ? "" : v.replace("\\", "\\5c").replace("(", "\\28")
                .replace(")", "\\29").replace("*", "\\2a");
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return null;
        String s = v.asText("");
        return s.isBlank() ? null : s.trim();
    }

    private static String orDft(String v, String dft) {
        return v == null ? dft : v;
    }

    private static int parseInt(String v, int dft) {
        try {
            return v == null ? dft : Math.max(5, Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static void close(LdapContext ctx) {
        if (ctx != null) try {
            ctx.close();
        } catch (NamingException ignored) {
        }
    }
}
