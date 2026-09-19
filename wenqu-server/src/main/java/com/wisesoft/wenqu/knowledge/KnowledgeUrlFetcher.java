package com.wisesoft.wenqu.knowledge;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * URL 内容抓取（对应参考实现 knowledge/utils/url_fetcher.py）。
 *
 * <p>逐项翻译：下载大小上限、Content-Type 白名单、私网地址阻断、
 * 手动重定向（每一跳重新过白名单与私网校验、最多 5 跳）、UA 与超时。
 * 例外语义与参考一致：所有失败都以 {@link IllegalArgumentException} 抛出
 * （对应 Python 侧的 {@code ValueError}）。
 *
 * <h3>必要替换（已显式标注）</h3>
 * {@code httpx.AsyncClient} → JDK {@link HttpClient}；{@code asyncio.to_thread}
 * 的异步 DNS → 同步 {@code InetAddress}（Java 侧无事件循环，无需转线程）。
 *
 * <h3>能力差异（已显式标注）</h3>
 * 参考实现用 {@code ipaddress.is_private} 判定私网，Java 无同名等价物，
 * 此处以 {@code isSiteLocalAddress/isLoopbackAddress/isLinkLocalAddress/isAnyLocalAddress}
 * 组合判定，覆盖回环、RFC1918 私有段、链路本地、0.0.0.0 等主要场景；
 * Python 额外枚举的少数保留段（如 198.18.0.0/15）不在组合覆盖内。
 */
@Slf4j
public final class KnowledgeUrlFetcher {

    /** 最大允许下载大小（10MB，与参考实现一致）。 */
    public static final int MAX_DOWNLOAD_SIZE = 10 * 1024 * 1024;

    /** 允许的 Content-Type（与参考实现一致）。 */
    public static final List<String> ALLOWED_CONTENT_TYPES = List.of("text/html", "application/xhtml+xml");

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final int MAX_REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private KnowledgeUrlFetcher() {}

    /** 抓取结果（对应参考实现返回的 {@code (content_bytes, final_url)}）。 */
    public record FetchedContent(byte[] content, String finalUrl) {}

    /** 主机名是否解析到私网/回环/链路本地地址。解析失败时放行（与参考一致）。 */
    public static boolean isPrivateIp(String hostname) {
        if (hostname == null || hostname.isEmpty()) {
            return false;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(hostname)) {
                if (address.isSiteLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            log.warn("Failed to resolve hostname {}: {}", hostname, e.getMessage());
            // 解析失败按参考实现放行，由连接阶段自然失败
            return false;
        }
    }

    /** 按默认上限抓取 URL 内容。 */
    public static FetchedContent fetchUrlContent(String url) {
        return fetchUrlContent(url, MAX_DOWNLOAD_SIZE);
    }

    /**
     * 抓取 URL 内容，带完整安全校验（大小上限、Content-Type、私网阻断、逐跳重定向校验）。
     *
     * @param url     目标 URL
     * @param maxSize 允许的最大字节数
     * @return 内容字节 + 最终 URL
     */
    public static FetchedContent fetchUrlContent(String url, int maxSize) {
        if (!KnowledgeUrlValidator.isUrlParsingEnabled()) {
            throw new IllegalArgumentException("URL parsing feature is disabled");
        }

        KnowledgeUrlValidator.ValidationResult validation = KnowledgeUrlValidator.validateUrl(url);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Invalid URL: " + validation.error());
        }

        if (isPrivateIp(hostOf(url))) {
            throw new IllegalArgumentException("Access to private IP addresses is forbidden");
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();

        String currentUrl = url;
        int redirectCount = 0;

        try {
            while (true) {
                log.info("Fetching URL: {}", currentUrl);

                HttpRequest request = HttpRequest.newBuilder(URI.create(currentUrl))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        closeQuietly(response.body());
                        throw new IllegalArgumentException("Too many redirects");
                    }
                    redirectCount++;
                    Optional<String> location = response.headers().firstValue("Location");
                    if (location.isEmpty()) {
                        closeQuietly(response.body());
                        throw new IllegalArgumentException("Redirect response missing Location header");
                    }
                    currentUrl = resolveUrl(currentUrl, location.get());

                    // 新目标必须重新过白名单与私网校验
                    validation = KnowledgeUrlValidator.validateUrl(currentUrl);
                    if (!validation.valid()) {
                        closeQuietly(response.body());
                        throw new IllegalArgumentException("Redirected to invalid URL: " + validation.error());
                    }
                    if (isPrivateIp(hostOf(currentUrl))) {
                        closeQuietly(response.body());
                        throw new IllegalArgumentException("Redirected to private IP address");
                    }
                    closeQuietly(response.body());
                    continue;
                }

                if (status >= 400) {
                    closeQuietly(response.body());
                    throw new IllegalArgumentException("Failed to fetch URL: HTTP " + status);
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                if (ALLOWED_CONTENT_TYPES.stream().noneMatch(contentType::contains)) {
                    closeQuietly(response.body());
                    throw new IllegalArgumentException(
                            "Unsupported Content-Type: " + contentType + ". Only HTML is supported.");
                }

                return new FetchedContent(readWithLimit(response.body(), maxSize), currentUrl);
            }
        } catch (IOException e) {
            log.error("HTTP error fetching {}: {}", url, e.getMessage());
            throw new IllegalArgumentException("Failed to fetch URL: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Error fetching {}: {}", url, e.getMessage());
            throw new IllegalArgumentException("Error fetching URL: " + e.getMessage(), e);
        }
    }

    /** 流式读取正文并在超过上限时立即中断（对应 aiter_bytes + 长度检查）。 */
    private static byte[] readWithLimit(InputStream in, int maxSize) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > maxSize) {
                    throw new IllegalArgumentException("Content size exceeds limit of " + maxSize + " bytes");
                }
            }
            return out.toByteArray();
        }
    }

    /** 相对重定向目标解析（对应 {@code urljoin}）。 */
    private static String resolveUrl(String base, String location) {
        try {
            return URI.create(base).resolve(location).toString();
        } catch (RuntimeException e) {
            return location;
        }
    }

    /** 安全取主机名，解析失败返回 null。 */
    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败不影响主流程
        }
    }
}
