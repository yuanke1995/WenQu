package com.wenqu.ai.service;

import com.wenqu.ai.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 网页抓取：手动逐跳重定向（每一跳都做内网地址校验，防 SSRF）+ 封顶读取。
 * <p>
 * 从 {@link DocumentService} 抽出：本类无状态、不依赖数据库与配置，供网页导入与网页源定时刷新共用。
 * 抓取结果以 {@link FetchResult} 返回；需要走上传链路时用 {@link InMemoryPage} 包装成 MultipartFile。
 */
@Component
public class WebPageFetcher {

    /** 网页抓取上限：防止超大页面打爆堆内存（超限 fail-loud 拒绝导入） */
    private static final long MAX_PAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    /** 抓取结果：最终 URL（跟随重定向后）+ 响应体 + Content-Type */
    public record FetchResult(URI uri, byte[] bytes, String contentType) {
    }

    /** 抓取网页：手动逐跳重定向（每跳都过 requirePublicHost 内网校验），大小封顶读取 */
    public FetchResult fetchPage(URI start) {
        HttpClient client = HttpClient.newBuilder()
                // JDK HttpClient 默认 HTTP/2 会对明文 http 发 h2c 升级（沙盒 422 同源坑），显式钉死 HTTP/1.1
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                // 重定向 NEVER + 手动跟进：Redirect.NORMAL 会自动跟随到内网地址，绕过单次校验
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublicHost(current);
            HttpRequest req = HttpRequest.newBuilder(current)
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("User-Agent", "wen-qu-kb-importer (knowledge-base web import)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .GET().build();
            HttpResponse<InputStream> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException("网页抓取被中断");
            } catch (java.io.IOException e) {
                throw new BizException("网页抓取失败: " + rootMessage(e));
            }
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                String location = resp.headers().firstValue("Location").orElse(null);
                try { resp.body().close(); } catch (Exception ignored) { }
                if (location == null || location.isBlank()) {
                    throw new BizException("网页重定向缺少 Location（HTTP " + code + "）");
                }
                current = current.resolve(location.trim());
                continue;
            }
            if (code != 200) {
                try { resp.body().close(); } catch (Exception ignored) { }
                throw new BizException("网页返回 HTTP " + code + "（" + current.getHost() + "）");
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            String lower = contentType.toLowerCase();
            if (!(lower.contains("text/html") || lower.contains("application/xhtml") || lower.contains("text/plain"))) {
                try { resp.body().close(); } catch (Exception ignored) { }
                throw new BizException("URL 返回的不是网页（Content-Type: " + contentType + "）");
            }
            return new FetchResult(current, readCapped(resp.body()), contentType);
        }
        throw new BizException("重定向超过 " + MAX_REDIRECTS + " 次，已停止跟进");
    }

    /** 封顶读取响应体：超过 MAX_PAGE_BYTES 直接拒绝（不静默截断，避免半篇正文当完整知识入库） */
    private byte[] readCapped(InputStream in) {
        try (in) {
            byte[] data = in.readNBytes((int) MAX_PAGE_BYTES + 1);
            if (data.length > MAX_PAGE_BYTES) {
                throw new BizException("网页内容超过 " + (MAX_PAGE_BYTES / 1024 / 1024) + "MB 上限，已拒绝导入");
            }
            return data;
        } catch (BizException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new BizException("读取网页内容失败: " + rootMessage(e));
        }
    }

    /**
     * SSRF 防护：仅 http/https；主机解析出的所有地址（含 IPv6）都不得为
     * 回环/站点内网/任意/链路本地/多播地址——服务端抓取不能变成内网探测通道。
     * （实现已提取到 {@link com.wenqu.ai.util.SsrfGuard} 供工作流 HTTP 节点等同口径复用）
     */
    private void requirePublicHost(URI uri) {
        com.wenqu.ai.util.SsrfGuard.requirePublicHost(uri);
    }

    /** 页面标题提取（charsetName=null 让 jsoup 自动探测） */
    public String pageTitle(byte[] bytes) {
        try {
            String t = org.jsoup.Jsoup.parse(new java.io.ByteArrayInputStream(bytes), null, "").title();
            return t == null ? "" : t.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String rootMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** 网页导入用：把抓取到的 HTML 字节包装成 MultipartFile，完整复用上传链路（同名替换/落盘/异步解析） */
    public record InMemoryPage(byte[] bytes, String fileName) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return "text/html"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }
}
