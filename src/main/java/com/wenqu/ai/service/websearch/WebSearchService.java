package com.wenqu.ai.service.websearch;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 联网搜索服务：按配置选服务商、执行搜索、把结果收敛成统一模型。
 * <p>
 * 职责边界：本类只做「服务商无关的一次搜索」（选型 + 参数 + 清洗），
 * **不碰**会话语义（配额、引用编号、降级提示）——那些在 WebSearchTools 与 ChatService。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WebSearchService {

    /** 结果摘要清洗后仍为空时的占位（引用面板要显示点东西，但不能伪造内容） */
    private static final String EMPTY_SNIPPET = "（该来源未提供摘要）";
    /** 归一化时剔除的跟踪参数前缀（同 URL 带不同 utm 应视为同一来源） */
    private static final String[] TRACKING_PREFIXES = {"utm_", "spm", "ref_", "from_"};

    private final ConfigService configService;
    private final List<WebSearchProvider> providers;

    public WebSearchService(ConfigService configService, List<WebSearchProvider> providers) {
        this.configService = configService;
        this.providers = providers == null ? List.of() : providers;
    }

    /** 搜索能力是否可用（总开关 + 工具总闸 + 服务商可解析）。不检查 key——缺 key 要在调用时报得更具体 */
    public boolean enabled() {
        if (!configService.getBoolean("tool.enabled")) return false;
        return configService.getBoolean("webSearch.enabled");
    }

    /**
     * 本轮实际下发给服务商的地址。
     * <p>平台全局层那一行只服务 generic（部署方自建的免费 SearXNG 兜底），不能顺手串给
     * tavily/bocha/custom——那会把个人付费厂商的请求连同个人 Key 发到 SearXNG 地址。
     * 个人显式覆盖的地址才是「用户自己指定的端点」，对任何服务商都生效；它由普通用户填写，
     * 公网校验放在 {@link com.wenqu.ai.service.UserConfigService#save} 入库前（服务端出站的
     * 唯一用户可控入口），运行时不再重复判定，以免把部署方自建的回环端点一并挡掉。
     */
    public String configuredBaseUrl(String providerId) {
        String base = configService.get("webSearch.baseUrl");
        if (base == null || base.isBlank()) return "";
        if (configService.layerOf("webSearch.baseUrl") == ConfigService.Layer.USER_OVERRIDE) {
            return base;
        }
        return "generic".equalsIgnoreCase(providerId) ? base : "";
    }

    /**
     * 执行一次搜索。
     *
     * @param query      搜索词
     * @param maxResults 期望条数（null=平台固定 10）
     * @return 结果列表（已去空链接、已补站点名、已按条数截断）
     * @throws WebSearchException 未启用 / 缺配置 / 请求失败（fail-loud，不返回空列表充数）
     */
    public List<WebSearchResult> search(String query, Integer maxResults) throws WebSearchException {
        if (!configService.getBoolean("tool.enabled")) {
            throw new WebSearchException("联网搜索未启用：工具总开关（tool.enabled）处于关闭状态", true);
        }
        if (!configService.getBoolean("webSearch.enabled")) {
            throw new WebSearchException("联网搜索未启用：webSearch.enabled 处于关闭状态", true);
        }
        String providerId = configService.get("webSearch.provider");
        // 个人层口径：服务商/Key/模板是个人专属键（无全局层），个人未设置一律回落平台内置 SearXNG（免费）——
        // 平台层存过的计费 Key（tavily/bocha/custom）绝不替个人买单，所以默认不是平台 def 而是 generic
        if (providerId == null || providerId.isBlank()) providerId = "generic";
        WebSearchProvider provider = null;
        for (WebSearchProvider p : providers) {
            if (p.id().equalsIgnoreCase(providerId.trim())) {
                provider = p;
                break;
            }
        }
        if (provider == null) {
            throw new WebSearchException("未知的联网搜索服务商「" + providerId + "」"
                    + "（可在 个人设置 → 联网搜索 重新选择：generic / tavily / bocha / custom）", true);
        }
        String apiKey = configService.get("webSearch.apiKey");
        // generic 平台内置实例通常无鉴权、custom 看厂商口径（有就带、没有等厂商 401），不强求 key；
        // 其余两家缺 key 必然 401，与其等服务端报错不如先说清。Key 是个人专属键（不回落平台），
        // 没配就是没配，引导去个人设置
        if ((apiKey == null || apiKey.isBlank()) && !"generic".equalsIgnoreCase(provider.id())
                && !"custom".equalsIgnoreCase(provider.id())) {
            throw new WebSearchException("联网搜索未配置服务 Key：请在 个人设置 → 联网搜索 填写「"
                    + providerId + "」的 API Key，或把服务商切换为「generic（平台内置搜索，免费）」", true);
        }
        int cap = ConfigDefaults.WEB_SEARCH_MAX_RESULTS;
        int limit = maxResults == null ? cap : Math.max(1, Math.min(maxResults, cap));
        int timeoutMs = ConfigDefaults.WEB_SEARCH_TIMEOUT_MS;
        String baseUrl = configuredBaseUrl(provider.id());
        if (baseUrl.startsWith("http://")) {
            log.warn("[WEB-SEARCH] 服务地址使用 http，搜索词与密钥将以明文传输：{}", baseUrl);
        }
        WebSearchRequest req = new WebSearchRequest(baseUrl, apiKey, timeoutMs, query, limit);
        List<WebSearchResult> raw;
        try {
            raw = provider.search(req);
        } catch (WebSearchException e) {
            throw e;
        } catch (Exception e) {
            // 带异常类名：NPE 这类无消息异常裸给 message 会变成「联网搜索失败：null」，排障没方向
            throw new WebSearchException("联网搜索失败：" + e);
        }
        if (raw == null || raw.isEmpty()) return List.of();

        List<WebSearchResult> out = new ArrayList<>(raw.size());
        for (WebSearchResult r : raw) {
            if (r.url() == null || r.url().isBlank()) continue; // 无链接无法溯源
            if (out.size() >= limit) break;
            String snippet = r.snippet() == null || r.snippet().isBlank() ? EMPTY_SNIPPET : r.snippet().trim();
            String siteName = (r.siteName() == null || r.siteName().isBlank()) ? hostOf(r.url()) : r.siteName().trim();
            out.add(new WebSearchResult(
                    r.title() == null || r.title().isBlank() ? siteName : r.title().trim(),
                    r.url().trim(),
                    snippet,
                    siteName,
                    r.publishedAt(),
                    r.score(),
                    r.rerankScore()));
        }
        return out;
    }

    /**
     * URL 归一化（同一来源去重键）：小写 host、去 www.、去跟踪参数、去锚点与尾斜杠。
     * 解析失败时原样返回（宁可少去重，也不能把合法链接判成不可用）。
     */
    public static String normalizeUrl(String u) {
        if (u == null || u.isBlank()) return "";
        String s = u.trim();
        try {
            URI uri = URI.create(s);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery();
            String kept = "";
            if (query != null && !query.isBlank()) {
                StringBuilder sb = new StringBuilder();
                for (String pair : query.split("&")) {
                    if (pair.isBlank()) continue;
                    String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                    boolean drop = false;
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String p : TRACKING_PREFIXES) {
                        if (lower.startsWith(p)) {
                            drop = true;
                            break;
                        }
                    }
                    if (drop) continue;
                    if (sb.length() > 0) sb.append('&');
                    sb.append(pair);
                }
                kept = sb.toString();
            }
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            StringBuilder out = new StringBuilder();
            if (uri.getScheme() != null) out.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://");
            out.append(host);
            if (uri.getPort() != -1) out.append(':').append(uri.getPort());
            out.append(path);
            if (!kept.isEmpty()) out.append('?').append(kept);
            String r = out.toString();
            return r.isEmpty() ? s : r;
        } catch (Exception e) {
            return s;
        }
    }

    /** 站点名兜底：从 URL 取 host（去 www.）；解析失败返回原串的前 40 字符 */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return url;
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return url.length() > 40 ? url.substring(0, 40) : url;
        }
    }
}
