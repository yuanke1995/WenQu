package com.wenqu.ai.service;

import com.wenqu.ai.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSO 一次性登录 code 存储：OIDC 回调与 SAML ACS 成功后，把「token + user + redirectPath」
 * 暂存于此，浏览器只携带 code（60 秒一次性）跳回前端回调页，前端 POST
 * {@code /api/ai/auth/oidc/exchange-code} 兑换（兑换即失效）——登录令牌不落 URL，
 * 不进浏览器历史/访问日志。OIDC 与 SAML 共用：兑换端点语义是「SSO 登录结果兑换」，与协议无关。
 * <p>
 * 注意：存储为进程内存 Map——与 OidcService 的 state 同属运行态，Phase 0（运行态外置）时
 * 随 ChatRunBus/FrameworkSessions 一并迁 Redis；单实例语义下正确，多副本需粘性或外置。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SsoLoginCodeService {

    /** 一次性登录 code 有效期（浏览器跳回前端后立即兑换，无需长） */
    private static final long LOGIN_CODE_TTL_MS = 60 * 1000L;

    private final SecureRandom random = new SecureRandom();
    /** 一次性登录 code → 登录响应（一次性，消费即删） */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();

    /** 暂存一次登录结果，返回一次性 code */
    public String issue(Map<String, Object> payload) {
        String code = newToken();
        codes.put(code, new CodeEntry(payload, System.currentTimeMillis() + LOGIN_CODE_TTL_MS));
        // 顺带清过期项：登录频度低，全表扫描代价可忽略
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        return code;
    }

    /** 用一次性 code 兑换登录结果（兑换即失效，避免令牌留在浏览器历史/日志里） */
    public Map<String, Object> exchange(String code) {
        CodeEntry ce = code == null || code.isBlank() ? null : codes.remove(code);
        if (ce == null || ce.expiresAt() < System.currentTimeMillis()) {
            throw new BizException("登录 code 无效或已过期，请重新登录");
        }
        return ce.payload();
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 一次性登录结果 */
    private record CodeEntry(Map<String, Object> payload, long expiresAt) {
    }
}
