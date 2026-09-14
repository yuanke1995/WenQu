package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.AiApiKeyMapper;
import com.wisesoft.ai.model.AiApiKey;
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
 *   <li>明文只在创建时返回一次；库里存 SHA-256 哈希 + 前 8 位前缀（列表可辨识，不泄露全文）</li>
 *   <li>校验走哈希等值查询（索引命中），命中后还要判停用/过期</li>
 *   <li>热路径（每次带 Key 的请求）只做一次 update 记录 last_used_at，失败不影响放行</li>
 *   <li>Key 权限固定为问答链路（调用方 SecurityConfig 只放行白名单端点），不授予管理端点</li>
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

    private final AiApiKeyMapper mapper;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 签发新 Key。
     *
     * @param name      用途名称
     * @param expireAt  过期时间（null=长期有效）
     * @param createdBy 创建人标识
     * @return {id, name, keyPrefix, apiKey}——apiKey 为明文，**仅此一次返回**
     */
    public Map<String, Object> create(String name, LocalDateTime expireAt, String createdBy) {
        byte[] buf = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(buf);
        String plain = PREFIX + HexFormat.of().formatHex(buf);
        AiApiKey k = new AiApiKey();
        k.setName(name == null || name.isBlank() ? "未命名 Key" : name.trim());
        k.setKeyHash(sha256(plain));
        k.setKeyPrefix(plain.substring(0, PREFIX.length() + 8));
        k.setDisabled(0);
        k.setExpireAt(expireAt);
        k.setCreatedBy(createdBy);
        k.setCreateTime(LocalDateTime.now());
        mapper.insert(k);
        log.info("[API-KEY] 签发 {}（{}），过期 {}", k.getName(), k.getKeyPrefix() + "…",
                expireAt == null ? "长期" : expireAt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", k.getId());
        out.put("name", k.getName());
        out.put("keyPrefix", k.getKeyPrefix());
        out.put("apiKey", plain);
        return out;
    }

    /** 列表（不含哈希，仅前缀等元信息），按创建时间倒序 */
    public List<Map<String, Object>> list() {
        List<AiApiKey> keys = mapper.selectList(new LambdaQueryWrapper<AiApiKey>()
                .orderByDesc(AiApiKey::getCreateTime));
        LocalDateTime now = LocalDateTime.now();
        return keys.stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("keyPrefix", k.getKeyPrefix());
            m.put("disabled", k.getDisabled() != null && k.getDisabled() == 1);
            m.put("expired", k.getExpireAt() != null && k.getExpireAt().isBefore(now));
            m.put("expireAt", k.getExpireAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("createdBy", k.getCreatedBy());
            m.put("createTime", k.getCreateTime());
            return m;
        }).toList();
    }

    /** 启用/停用（吊销即停用；不物理删除，保留审计线索） */
    public void setDisabled(String id, boolean disabled) {
        AiApiKey k = new AiApiKey();
        k.setId(id);
        k.setDisabled(disabled ? 1 : 0);
        k.setUpdateTime(LocalDateTime.now());
        mapper.updateById(k);
        log.info("[API-KEY] {} {}", disabled ? "停用" : "启用", id);
    }

    /** 物理删除（清理不再需要的 Key） */
    public void delete(String id) {
        mapper.deleteById(id);
        log.info("[API-KEY] 删除 {}", id);
    }

    /**
     * 校验 API Key：哈希命中 + 未停用 + 未过期 → 返回 Key 记录（供记录使用时间），否则 null。
     * 不做恒定时间比较——查的是哈希，攻击者也无法通过时序推断明文。
     */
    public AiApiKey verify(String plainKey) {
        if (plainKey == null || plainKey.isBlank() || !plainKey.startsWith(PREFIX)) return null;
        try {
            AiApiKey k = mapper.selectOne(new LambdaQueryWrapper<AiApiKey>()
                    .eq(AiApiKey::getKeyHash, sha256(plainKey)).last("LIMIT 1"));
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
