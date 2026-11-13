package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.SecurityConfig;
import com.wenqu.ai.mapper.ApiKeyMapper;
import com.wenqu.ai.mapper.UsageLogMapper;
import com.wenqu.ai.model.ApiKey;
import com.wenqu.ai.openapi.OpenApi;
import com.wenqu.ai.openapi.OpenApiRegistry;
import com.wenqu.ai.service.ApiKeyService;
import com.wenqu.ai.service.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集成方自检：{@code GET /api/ai/v1/me}——一把 Key 到底能干什么、代表谁、被限到多少、跑了多少。
 *
 * <p>B4「把『我这把 Key 能干什么』从猜变成查」的落点。刻意不要求任何 scope：
 * 自检本身就是给「还不知道自己有没有权限」的人用的，任意有效 Key 都应能查自己；
 * 这也是 {@link OpenApi} 的 scope 留空（keyAccessible=true）的语义。</p>
 *
 * <p>口径上有两处必须说清，否则自检反而会误导集成方：</p>
 * <ul>
 *   <li><b>恒绑签发人</b>：{@code actAs} 恒为签发人本人，不存在机器号或改绑别人的口子，
 *       所以额外回 {@code actAsBinding=issuer} 与说明，不留「bot-svc 之类的服务账号」想象空间；</li>
 *   <li><b>限流不是 Key 级配额</b>：平台按「桶 + 租户 + 账号/IP」分桶，一把 Key 没有独占额度，
 *       回显的是当前生效的桶配置，并附 note；不虚构一个不存在的 per-key 限额。</li>
 * </ul>
 *
 * <p>用量取 {@code c_ai_usage_log.api_key_id}，与 API Key 管理页「近 30 天用量」同一本账。
 * 聚合失败时回 {@code available=false} 而不是把失败伪装成「0 次调用」——自检的价值就是可信。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OpenApiMeController {

    /** 自检统计窗口（与 API Key 管理页的近 30 天小计同口径） */
    private static final int USAGE_WINDOW_DAYS = 30;

    private final ApiKeyMapper apiKeyMapper;
    private final UsageLogMapper usageLogMapper;
    private final ConfigService configService;

    @OpenApi(summary = "API Key 自检：能力范围、发问身份、限流与近 30 天用量")
    @GetMapping("/api/ai/v1/me")
    public Map<String, Object> me(HttpServletRequest request) {
        String keyId = (String) request.getAttribute(SecurityConfig.ATTR_API_KEY_ID);
        if (keyId == null || keyId.isBlank()) {
            // JWT 登进来的请求不该拿到 Key 自检结果：这里没有「这把 Key」，编一份就是假的
            throw new BizException(401, "此端点只接受 API Key 凭据（X-Api-Key 或 Authorization: Bearer sk-…）；"
                    + "请在「个人设置 → API 密钥」签发后重试");
        }
        ApiKey key = apiKeyMapper.selectById(keyId);
        if (key == null) {
            throw new BizException(401, "API Key 记录已不存在，请在「个人设置 → API 密钥」重新签发");
        }
        List<String> scopes = ApiKeyService.parseScopes(key.getScopes());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key.getName());
        out.put("keyPrefix", key.getKeyPrefix());
        out.put("scopes", scopes);
        out.put("actAs", key.getActAsUid() == null ? key.getCreatedBy() : key.getActAsUid());
        out.put("actAsBinding", "issuer");
        out.put("actAsNote", "Key 恒以签发人本人身份发问，不支持指定其他代表账号");
        out.put("expiresAt", key.getExpireAt() == null ? null : key.getExpireAt().toString());
        out.put("limits", limits());
        out.put("usage30d", usage30d(keyId));
        out.put("contract", OpenApiRegistry.PREFIX + "openapi.json");
        return out;
    }

    /** 回显当前真正生效的限流配置（读点与 RateLimitService.checkRateLimit 同源） */
    private Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", configService.getBoolean("ratelimit.enabled", false));
        m.put("windowSeconds", configService.getInt("ratelimit.windowSeconds", 60));
        m.put("chatPerMinute", configService.getInt("ratelimit.chatPerMinute", 10));
        m.put("embeddingPerMinute", configService.getInt("ratelimit.embeddingPerMinute", 60));
        m.put("note", "限流按「桶 + 租户 + 账号/IP」分桶，不是单把 Key 独占额度；0 表示该桶不限流");
        return m;
    }

    private Map<String, Object> usage30d(String keyId) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Map<String, Object> row = usageLogMapper.statOneApiKeyUsage(
                    keyId, LocalDateTime.now().minusDays(USAGE_WINDOW_DAYS));
            m.put("available", true);
            m.put("calls", row == null ? 0L : number(row.get("c")));
            m.put("tokens", row == null ? 0L : number(row.get("t")));
        } catch (Exception e) {
            log.warn("[API-KEY] /v1/me 用量聚合失败 key={}: {}", keyId, e.getMessage());
            m.put("available", false);
            m.put("reason", "用量台账暂不可查，本次自检不代表 0 次调用");
        }
        return m;
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
