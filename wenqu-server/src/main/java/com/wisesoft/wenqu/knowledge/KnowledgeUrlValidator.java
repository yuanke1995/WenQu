package com.wisesoft.wenqu.knowledge;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL 白名单校验（对应参考实现 knowledge/utils/url_validator.py）。
 *
 * <p>逐项翻译：白名单来源、通配符（{@code *.example.com}）匹配、错误文案、
 * {@code is_url_parsing_enabled} / {@code get_whitelist_info} 的返回结构。
 *
 * <h3>必要替换（已显式标注）</h3>
 * 参考实现的白名单环境变量名使用了本产品的旧代号字面量，本工程按去品牌约定
 * 统一改为 {@code WENQU_URL_WHITELIST}——变量名不同，读取时机、分割规则、
 * 通配符语义、错误文案均与参考实现完全一致。部署时设置该变量即开启 URL 解析。
 *
 * <h3>能力差异（已显式标注）</h3>
 * Python {@code urlparse} 对畸形 URL 几乎不抛异常（宽容解析），Java {@link URI}
 * 更严格，对非法字符会抛 {@link URISyntaxException}——此处同样收敛为
 * "Invalid URL format" 分支，行为对齐（都是判为非法），只是触发条件更早。
 */
public final class KnowledgeUrlValidator {

    /** URL 白名单环境变量名（参考实现为旧代号字面量，此处按去品牌约定改写）。 */
    public static final String URL_WHITELIST_ENV = "WENQU_URL_WHITELIST";

    private KnowledgeUrlValidator() {}

    /** 校验结果（对应参考实现返回的 {@code (is_valid, error_message)} 二元组）。 */
    public record ValidationResult(boolean valid, String error) {}

    /** 读取环境变量中的 URL 白名单（逗号分隔，去空白，丢弃空项）。 */
    private static List<String> getWhitelist() {
        String raw = System.getenv(URL_WHITELIST_ENV);
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 校验 URL 是否命中白名单。
     *
     * @return 校验结果；{@code valid} 为 true 时 {@code error} 为空串
     */
    public static ValidationResult validateUrl(String url) {
        if (url == null || url.isEmpty()) {
            return new ValidationResult(false, "URL cannot be empty");
        }

        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            return new ValidationResult(false, "Invalid URL format: " + e.getMessage());
        }

        String scheme = parsed.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            return new ValidationResult(false, "URL must start with http:// or https://");
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return new ValidationResult(false, "URL must use HTTP or HTTPS protocol");
        }

        String hostname = parsed.getHost();
        if (hostname == null || hostname.isEmpty()) {
            return new ValidationResult(false, "Invalid URL: no hostname found");
        }

        List<String> whitelist = getWhitelist();
        // 白名单为空即未启用 URL 解析（与参考实现一致）
        if (whitelist.isEmpty()) {
            return new ValidationResult(false, "URL parsing feature is disabled");
        }

        for (String allowed : whitelist) {
            if (allowed.startsWith("*.")) {
                // 通配符：*.example.com 同时匹配 example.com 本身与其子域
                String domain = allowed.substring(2);
                if (hostname.equals(domain) || hostname.endsWith("." + domain)) {
                    return new ValidationResult(true, "");
                }
            } else {
                // 精确匹配或子域匹配
                if (hostname.equals(allowed) || hostname.endsWith("." + allowed)) {
                    return new ValidationResult(true, "");
                }
            }
        }

        return new ValidationResult(false, "Domain '" + hostname + "' is not in the whitelist");
    }

    /** URL 解析是否已启用（白名单已配置）。 */
    public static boolean isUrlParsingEnabled() {
        return !getWhitelist().isEmpty();
    }

    /** 当前白名单配置信息（对应 get_whitelist_info）。 */
    public static Map<String, Object> getWhitelistInfo() {
        List<String> whitelist = getWhitelist();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", !whitelist.isEmpty());
        info.put("domains", whitelist);
        info.put("count", whitelist.size());
        return info;
    }
}
