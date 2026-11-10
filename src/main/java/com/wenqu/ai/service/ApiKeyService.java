package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.ApiKeyMapper;
import com.wenqu.ai.model.ApiKey;
import com.wenqu.ai.service.ResourceVisibilityService.Principal;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 服务：签发 / 列表 / 吊销 / 校验。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>明文保管两份口径：SHA-256 哈希用于请求校验（不可逆）；明文本体以部署密钥 JWE 加密存
 *       {@code key_cipher}，供列表「眼睛」回显/复制（与模型供应商 api_key 同一套
 *       {@link ConfigCryptoService}）。存量 Key 无密文，不可回显、只能重签</li>
 *   <li>校验走哈希等值查询（索引命中），命中后还要判停用/过期</li>
 *   <li>热路径（每次带 Key 的请求）只做一次 update 记录 last_used_at，失败不影响放行</li>
 *   <li>Key 权限固定为问答链路（调用方 SecurityConfig 只放行白名单端点，白名单之外对 Key 身份
 *       一律 403），不授予管理端点；问答链路里再剔掉「账号自服务与能力配置面」（改密/资料/偏好/
 *       技能/MCP 配置/定时任务与会话删除，以及 Key 管理本身），见
 *       {@code SecurityConfig#isKeyDeniedEndpoint}——一把 Key 拿到的是「以某个账号发问」的能力，
 *       不是「成为那个账号」</li>
 *   <li>恒绑签发人：Key 以「谁签发」的身份发问，不存在指定/改绑任何别人——
 *       平台没有机器号概念，也不给「把 Key 指到任意成员」留口子。签名授权交给 1c 硬边界：
 *       无论签发人是什么角色，Key 的 API 面恒为问答白名单</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    /** 密钥前缀（便于识别来源；不代表平台约定，仅可读性） */
    private static final String PREFIX = "sk-";
    /** 随机字节数（32 字节 → 64 位十六进制字符，暴力不可行） */
    private static final int RANDOM_BYTES = 32;

    // ==================== API Key 能力范围（scope） ====================
    // scope 只在 API Key 通道参与判定（登录态不受约束）；每把 Key 的可调面 = 问答白名单 ∩ scope。
    // 判点在各 v1 端点内（apiKeyId 非空才校验），白名单层保持不变——两层各管一件事：
    // 白名单管「Key 能不能碰这类端点」，scope 管「这把 Key 被授权了哪几类能力」。

    /** 问答（/api/ai/chat、OpenAI 兼容端点、Responses） */
    public static final String SCOPE_CHAT = "chat";
    /** 向量化（/api/ai/v1/embeddings） */
    public static final String SCOPE_EMBEDDING = "embedding";
    /** 知识库只读（检索 API、库详情、文档列表） */
    public static final String SCOPE_KB_READ = "kb.read";
    /** 知识库写入（建库、上传文档）——默认不含，签发时显式勾选才放开 */
    public static final String SCOPE_KB_WRITE = "kb.write";
    /** 工作流触发（/api/ai/v1/workflows/{id}/run） */
    public static final String SCOPE_WORKFLOW_RUN = "workflow.run";
    /** 全部合法 scope（前端勾选项与后端校验共用这一份名单） */
    public static final List<String> ALL_SCOPES = List.of(
            SCOPE_CHAT, SCOPE_EMBEDDING, SCOPE_KB_READ, SCOPE_KB_WRITE, SCOPE_WORKFLOW_RUN);
    /** 默认集：除 kb.write 外全部——存量 Key（scopes=NULL）与新签未收窄的 Key 都落在这里，
     *  与「Key 可走问答/向量/只读/工作流，写库需显式授权」的最小权限口径一致 */
    public static final List<String> DEFAULT_SCOPES = List.of(
            SCOPE_CHAT, SCOPE_EMBEDDING, SCOPE_KB_READ, SCOPE_WORKFLOW_RUN);

    /** 解析 scopes 列：NULL/空/解析失败一律按默认集（fail-safe 方向与存量行为一致，不静默清空权限） */
    public static List<String> parseScopes(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_SCOPES;
        try {
            List<String> list = com.alibaba.fastjson2.JSON.parseArray(raw, String.class);
            if (list == null || list.isEmpty()) return DEFAULT_SCOPES;
            // 只保留合法值：脏数据（改名/拼错的 scope）不参与判定，避免「写了个没人认识的串」把 Key 锁死
            List<String> valid = list.stream().filter(ALL_SCOPES::contains).distinct().toList();
            return valid.isEmpty() ? DEFAULT_SCOPES : valid;
        } catch (Exception e) {
            return DEFAULT_SCOPES;
        }
    }

    /** 校验并归一化调用方提交的 scope 列表（非法名直接拒，空列表=恢复默认集） */
    public static List<String> normalizeScopes(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) throw new BizException("scopes 必须是数组");
        List<String> out = new java.util.ArrayList<>();
        for (Object o : list) {
            String s = o == null ? "" : String.valueOf(o).trim();
            if (!ALL_SCOPES.contains(s)) {
                throw new BizException("未知 scope：「" + s + "」，可用：" + String.join(" / ", ALL_SCOPES));
            }
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    private final ApiKeyMapper mapper;
    private final ResourceVisibilityService resourceVisibilityService;
    private final ConfigCryptoService crypto;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 当前请求者（可见性/可管性判定的输入） */
    private Principal principal() {
        return new Principal(RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 管理动作前的权限闸：非创建者则拒绝（Key 是个人资产，谁建归谁） */
    private void ensureManageable(ApiKey k) {
        if (k != null && !resourceVisibilityService.canManage(principal(), k.getCreatedBy())) {
            throw new BizException(403, "无权管理该 API Key");
        }
    }

    /**
     * 签发新 Key。
     *
     * @param name      用途名称
     * @param expireAt  过期时间（null=长期有效）
     * @param createdBy 创建人标识（= 代表账号：Key 恒以签发人身份发问）
     * @param scopes    能力范围（null/空=默认集：除 kb.write 外全部；非法名由调用方先经 normalizeScopes 拒绝）
     * @return {id, name, keyPrefix, actAsUid, scopes, apiKey}——apiKey 为明文，此后可在列表「眼睛」回显
     */
    public Map<String, Object> create(String name, LocalDateTime expireAt, String createdBy, List<String> scopes) {
        byte[] buf = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(buf);
        String plain = PREFIX + HexFormat.of().formatHex(buf);
        ApiKey k = new ApiKey();
        k.setName(name == null || name.isBlank() ? "未命名 Key" : name.trim());
        k.setKeyHash(sha256(plain));
        k.setKeyPrefix(plain.substring(0, PREFIX.length() + 8));
        // 明文本体加密保管（JWE，部署密钥）：列表「眼睛」随时回显；哈希仍是唯一的校验凭据
        k.setKeyCipher(crypto.encrypt(plain));
        k.setDisabled(0);
        k.setExpireAt(expireAt);
        // 默认集不落库（NULL=默认集）：只有显式收窄/扩权的 Key 才存 scopes，
        // 也让存量行为与「未勾选任何收窄」的新 Key 完全同形
        List<String> eff = scopes == null || scopes.isEmpty() ? null
                : (scopes.size() == DEFAULT_SCOPES.size() && DEFAULT_SCOPES.containsAll(scopes) ? null : scopes);
        k.setScopes(eff == null ? null : com.alibaba.fastjson2.JSON.toJSONString(eff));
        // 恒绑签发人本人：Key 就是以「谁签发」的身份发问，不存在指定别人——
        // 也就不存在「管理员把 Key 指到任意成员」的口子。签发人必然存在且未停用（能登录才能签）。
        k.setActAsUid(createdBy);
        k.setCreatedBy(createdBy);
        k.setCreateTime(LocalDateTime.now());
        mapper.insert(k);
        log.info("[API-KEY] 签发 {}（{}），以签发人 {} 身份发问，过期 {}，scope {}", k.getName(), k.getKeyPrefix() + "…",
                createdBy, expireAt == null ? "长期" : expireAt, parseScopes(k.getScopes()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", k.getId());
        out.put("name", k.getName());
        out.put("keyPrefix", k.getKeyPrefix());
        out.put("actAsUid", k.getActAsUid());
        out.put("scopes", parseScopes(k.getScopes()));
        out.put("apiKey", plain);
        return out;
    }

    /**
     * 回显 Key 明文（列表「眼睛」/复制按钮的数据源）。
     * <p>明文以部署密钥 JWE 加密存 {@code key_cipher}，只有能管理该 Key 的人（创建者）可解；
     * 每次回显记一行审计日志。没有密文的行是残缺数据——当场说清让持有人重签，不静默返回空串。
     */
    public String reveal(String id) {
        ApiKey k = mapper.selectById(id);
        ensureManageable(k);
        if (k == null || k.getKeyCipher() == null || k.getKeyCipher().isBlank()) {
            throw new BizException("该 Key 没有可回显的密文，请重新签发一把（新 Key 恒以签发人身份发问）");
        }
        String plain = crypto.decrypt(k.getKeyCipher());
        log.info("[API-KEY] 回显明文 {}（{}）by {}", k.getId(), k.getKeyPrefix() + "…", RequestUser.uid());
        return plain;
    }

    /** 列表（不含哈希与密文，仅前缀等元信息），按创建时间倒序 */
    public List<Map<String, Object>> list() {
        Principal p = principal();
        List<ApiKey> keys = mapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .orderByDesc(ApiKey::getCreateTime));
        LocalDateTime now = LocalDateTime.now();
        return keys.stream()
                // Key 是个人资产：仅创建者可见（谁建归谁）
                .filter(k -> resourceVisibilityService.canRead(p, k.getCreatedBy()))
                .map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("keyPrefix", k.getKeyPrefix());
            m.put("disabled", k.getDisabled() != null && k.getDisabled() == 1);
            m.put("scopes", parseScopes(k.getScopes()));
            m.put("mcpEnabled", k.getMcpEnabled() != null && k.getMcpEnabled() == 1);
            m.put("expired", k.getExpireAt() != null && k.getExpireAt().isBefore(now));
            m.put("expireAt", k.getExpireAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("actAsUid", k.getActAsUid());
            m.put("createdBy", k.getCreatedBy());
            m.put("createTime", k.getCreateTime());
            return m;
        }).toList();
    }

    /**
     * 编辑 Key 的可改属性：用途名称与有效期。绑定（act_as_uid）恒为签发人、不可改；
     * Key 本身不可改（明文加密保管，列表「眼睛」可回显）。
     * <p><b>{@code expireAt} 必须走 {@code set(...)} 显式写入</b>：MyBatis-Plus 默认更新策略是 NOT_NULL，
     * {@code updateById} 会跳过 null 字段，「改回长期有效」就会静默不生效。
     */
    public void updateInfo(String id, String name, LocalDateTime expireAt) {
        ApiKey existing = mapper.selectById(id);
        ensureManageable(existing);
        String trimmed = name.length() > 200 ? name.substring(0, 200) : name;
        LambdaUpdateWrapper<ApiKey> upd = new LambdaUpdateWrapper<ApiKey>()
                .eq(ApiKey::getId, id)
                .set(ApiKey::getName, trimmed)
                .set(ApiKey::getExpireAt, expireAt)
                .set(ApiKey::getUpdateTime, LocalDateTime.now());
        mapper.update(null, upd);
        log.info("[API-KEY] 编辑 {}：名称 {}，有效期 {}", id, trimmed, expireAt == null ? "长期" : expireAt);
    }

    /** 启用/停用（吊销即停用；不物理删除，保留审计线索） */
    public void setDisabled(String id, boolean disabled) {
        ensureManageable(mapper.selectById(id));
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setDisabled(disabled ? 1 : 0);
        k.setUpdateTime(LocalDateTime.now());
        mapper.updateById(k);
        log.info("[API-KEY] {} {}", disabled ? "停用" : "启用", id);
    }

    /**
     * 设置 Key 的能力范围（scope）。空列表=恢复默认集（与签发未勾选时同形：落 NULL）。
     * <p>收紧立即生效：v1 端点每次请求现读 Key 行的 scopes，没有缓存一致窗口。
     */
    public void updateScopes(String id, List<String> scopes) {
        ApiKey existing = mapper.selectById(id);
        ensureManageable(existing);
        boolean isDefault = scopes == null || scopes.isEmpty()
                || (scopes.size() == DEFAULT_SCOPES.size() && DEFAULT_SCOPES.containsAll(scopes));
        LambdaUpdateWrapper<ApiKey> upd = new LambdaUpdateWrapper<ApiKey>()
                .eq(ApiKey::getId, id)
                .set(ApiKey::getScopes, isDefault ? null : com.alibaba.fastjson2.JSON.toJSONString(scopes))
                .set(ApiKey::getUpdateTime, LocalDateTime.now());
        mapper.update(null, upd);
        log.info("[API-KEY] 设置 scope {} → {}", id, isDefault ? "默认集" : scopes);
    }

    /**
     * scope 判定（仅 API Key 通道调用；JWT 请求不该走到这里）。
     * <p>按 id 现读 Key 行：一把 Key 的 scope 收紧后，下一次调用立刻按新范围拒绝。
     */
    public boolean hasScope(String apiKeyId, String scope) {
        if (apiKeyId == null || apiKeyId.isBlank()) return true;  // 非 Key 通道不受 scope 约束
        ApiKey k = mapper.selectById(apiKeyId);
        if (k == null) return false;  // Key 行已不存在（删除/清理）：宁拒不放
        return parseScopes(k.getScopes()).contains(scope);
    }

    /**
     * v1 端点内的 scope 闸：Key 通道缺对应 scope → 403 并说清这把 Key 还缺什么、去哪儿开。
     * <p>判点放端点内而不是拦截器：拦截器只回答「Key 能不能碰这类端点」（白名单），
     * 这里回答「这把 Key 被授权了这项能力没有」——两层各自可核对，不互相绕。
     */
    public void requireScope(String apiKeyId, String scope) {
        if (apiKeyId == null || apiKeyId.isBlank()) return;  // JWT 通道直通
        if (hasScope(apiKeyId, scope)) return;
        throw new BizException(403, "该 API Key 未开通「" + scope + "」能力；请在问渠「个人设置 → API 密钥」"
                + "编辑该 Key 的权限范围后重试");
    }

    /**
     * 授权 / 收回该 Key 访问平台级 MCP 入口（/ai/mcp）的资格。
     * <p>与"停用"是两件事：停用吊销的是整把 Key；这里只控制它能否走 MCP 这条入口。
     * 默认不授权——Key 的既有权限面是问答链路，MCP 入口需显式开（最小权限）。</p>
     */
    public void setMcpEnabled(String id, boolean enabled) {
        ensureManageable(mapper.selectById(id));
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setMcpEnabled(enabled ? 1 : 0);
        k.setUpdateTime(LocalDateTime.now());
        mapper.updateById(k);
        log.info("[API-KEY] {} MCP 入口 key={}", enabled ? "授权" : "收回", id);
    }

    /** 物理删除（清理不再需要的 Key） */
    public void delete(String id) {
        ensureManageable(mapper.selectById(id));
        mapper.deleteById(id);
        log.info("[API-KEY] 删除 {}", id);
    }

    /**
     * 校验 API Key：哈希命中 + 未停用 + 未过期 → 返回 Key 记录（供记录使用时间），否则 null。
     * 不做恒定时间比较——查的是哈希，攻击者也无法通过时序推断明文。
     */
    public ApiKey verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank() || !plainKey.startsWith(PREFIX)) return null;
        try {
            ApiKey k = mapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                    .eq(ApiKey::getKeyHash, sha256(plainKey)).last("LIMIT 1"));
            if (k == null) return null;
            if (k.getDisabled() != null && k.getDisabled() == 1) return null;
            if (k.getExpireAt() != null && k.getExpireAt().isBefore(LocalDateTime.now())) return null;
            return k;
        } catch (Exception e) {
            // fail-closed：校验异常不放行（Key 是外部入口，宁可拒绝不可误放）
            log.warn("[API-KEY] 校验异常（拒绝）: {}", e.getMessage());
            return null;
        }
    }

    /** 记录使用时间（best-effort，失败只告警不影响放行） */
    public void touchLastUsed(String id) {
        try {
            mapper.touchLastUsed(id, LocalDateTime.now());
        } catch (Exception e) {
            log.debug("[API-KEY] 记录使用时间失败: {}", e.getMessage());
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
