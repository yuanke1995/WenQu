package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.ApiKeyMapper;
import com.wisesoft.ai.model.ApiKey;
import com.wisesoft.ai.service.ResourceVisibilityService.Principal;
import com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind;
import com.wisesoft.ai.util.RequestUser;
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

    private final ApiKeyMapper mapper;
    private final ResourceVisibilityService resourceVisibilityService;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 当前请求者（可见性/可管性判定的输入） */
    private Principal principal() {
        return new Principal(RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 管理动作前的权限闸：不在共享管理范围内则拒绝（未配置共享＝全局，行为与从前一致） */
    private void ensureManageable(ApiKey k) {
        if (k != null && !resourceVisibilityService.canManage(principal(), k.getShareConfig(), k.getCreatedBy(), ResourceKind.API_KEY)) {
            throw new BizException(403, "无权管理该 API Key（不在其共享管理范围内）");
        }
    }

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
        ApiKey k = new ApiKey();
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
        Principal p = principal();
        List<ApiKey> keys = mapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .orderByDesc(ApiKey::getCreateTime));
        LocalDateTime now = LocalDateTime.now();
        return keys.stream()
                // 共享范围之外的人看不到该 Key 记录（未配置共享＝全局；创建者/超管始终可见）
                .filter(k -> resourceVisibilityService.canRead(p, k.getShareConfig(), k.getCreatedBy(), ResourceKind.API_KEY))
                .map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("keyPrefix", k.getKeyPrefix());
            m.put("disabled", k.getDisabled() != null && k.getDisabled() == 1);
            m.put("expired", k.getExpireAt() != null && k.getExpireAt().isBefore(now));
            m.put("expireAt", k.getExpireAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("createdBy", k.getCreatedBy());
            // 必须回传共享范围原文：前端「共享」弹窗靠它预填，缺了会默认「全员」
            // 并在一次保存后把已有范围静默清空
            m.put("shareConfig", k.getShareConfig());
            m.put("createTime", k.getCreateTime());
            return m;
        }).toList();
    }

    /** 重命名（用途备注可随时改，不影响 Key 本身与调用方） */
    public void rename(String id, String name) {
        ensureManageable(mapper.selectById(id));
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setName(name.length() > 200 ? name.substring(0, 200) : name);
        k.setUpdateTime(LocalDateTime.now());
        mapper.updateById(k);
        log.info("[API-KEY] 重命名 {} → {}", id, k.getName());
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

    /** 物理删除（清理不再需要的 Key） */
    public void delete(String id) {
        ensureManageable(mapper.selectById(id));
        mapper.deleteById(id);
        log.info("[API-KEY] 删除 {}", id);
    }

    /**
     * 写入共享范围（空串 = 清空 → 回落全局共享）。
     * <p><b>必须用 {@code set(..., null)} 显式置空</b>：MyBatis-Plus 默认更新策略是 NOT_NULL，
     * {@code updateById} 会跳过 null 字段，导致「恢复全员共享」静默不生效。</p>
     */
    public void updateShareConfig(String id, String shareConfigJson) {
        ensureManageable(mapper.selectById(id));
        resourceVisibilityService.validateShareConfig(shareConfigJson);
        String normalized = (shareConfigJson == null || shareConfigJson.isBlank()) ? null : shareConfigJson;
        mapper.update(null, new LambdaUpdateWrapper<ApiKey>()
                .eq(ApiKey::getId, id)
                .set(ApiKey::getShareConfig, normalized)
                .set(ApiKey::getUpdateTime, LocalDateTime.now()));
        log.info("[API-KEY] 共享范围更新 id={} scope={}", id, normalized == null ? "全局" : "受限");
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
