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
 *       {@link ConfigCryptoService}）。存量 Key（上线回显前签发）无密文，不可回显、只能重签</li>
 *   <li>校验走哈希等值查询（索引命中），命中后还要判停用/过期</li>
 *   <li>热路径（每次带 Key 的请求）只做一次 update 记录 last_used_at，失败不影响放行</li>
 *   <li>Key 权限固定为问答链路（调用方 SecurityConfig 只放行白名单端点，白名单之外对 Key 身份
 *       一律 403），不授予管理端点；问答链路里再剔掉「账号自服务与能力配置面」（改密/资料/偏好/
 *       技能/MCP 配置/定时任务与会话删除，以及 Key 管理本身），见
 *       {@code SecurityConfig#isKeyDeniedEndpoint}——一把 Key 拿到的是「以某个账号发问」的能力，
 *       不是「成为那个账号」</li>
 *   <li>恒绑签发人（2026-11-02 定稿）：Key 以「谁签发」的身份发问，不存在指定/改绑任何别人——
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
     * @return {id, name, keyPrefix, actAsUid, apiKey}——apiKey 为明文，此后可在列表「眼睛」回显
     */
    public Map<String, Object> create(String name, LocalDateTime expireAt, String createdBy) {
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
        // 恒绑签发人本人（2026-11-02 定稿）：Key 就是以「谁签发」的身份发问，不存在指定别人——
        // 也就不存在「管理员把 Key 指到任意成员」的口子。签发人必然存在且未停用（能登录才能签）。
        k.setActAsUid(createdBy);
        k.setCreatedBy(createdBy);
        k.setCreateTime(LocalDateTime.now());
        mapper.insert(k);
        log.info("[API-KEY] 签发 {}（{}），以签发人 {} 身份发问，过期 {}", k.getName(), k.getKeyPrefix() + "…",
                createdBy, expireAt == null ? "长期" : expireAt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", k.getId());
        out.put("name", k.getName());
        out.put("keyPrefix", k.getKeyPrefix());
        out.put("actAsUid", k.getActAsUid());
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
