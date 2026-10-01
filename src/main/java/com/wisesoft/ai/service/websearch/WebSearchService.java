package com.wisesoft.ai.service.websearch;

import com.wisesoft.ai.service.ConfigService;
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
 * **不碰**会话语义（配额、引用编号、降级提示）——那些在 WebSearchTools 与 RagService。
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
     * 执行一次搜索。
     *
     * @param query      搜索词
     * @param maxResults 期望条数（null=用配置 webSearch.maxResults）
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
        if (providerId == null || providerId.isBlank()) providerId = "tavily";
        WebSearchProvider provider = null;
        for (WebSearchProvider p : providers) {
            if (p.id().equalsIgnoreCase(providerId.trim())) {
                provider = p;
                break;
            }
        }
        if (provider == null) {
            throw new WebSearchException("未知的联网搜索服务商「" + providerId + "」（可选：tavily / bocha / generic）", true);
        }
        String apiKey = configService.get("webSearch.apiKey");
        // generic 自建实例通常无鉴权，不强求 key；另两家缺 key 必然 401，与其等服务端报错不如先说清
        if ((apiKey == null || apiKey.isBlank()) && !"generic".equalsIgnoreCase(provider.id())) {
            throw new WebSearchException("联网搜索未配置 API Key（webSearch.apiKey 为空，服务商 " + provider.id() + " 需要密钥）", true);
        }
        int cap = configService.getInt("webSearch.maxResults", 5);
        int limit = maxResults == null ? cap : Math.max(1, Math.min(maxResults, cap));
        int timeoutMs = configService.getInt("webSearch.timeoutMs", 8000);
        String baseUrl = configService.get("webSearch.baseUrl");
        if (baseUrl != null && baseUrl.startsWith("http://")) {
            log.warn("[WEB-SEARCH] 服务地址使用 http，搜索词与密钥将以明文传输：{}", baseUrl);
        }
        WebSearchRequest req = new WebSearchRequest(baseUrl, apiKey, timeoutMs, query, limit);
        List<WebSearchResult> raw;
        try {
            raw = provider.search(req);
        } catch (WebSearchException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSearchException("联网搜索失败：" + e.getMessage());
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
                    r.score()));
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
