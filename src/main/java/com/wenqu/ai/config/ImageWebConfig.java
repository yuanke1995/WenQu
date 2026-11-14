package com.wenqu.ai.config;

import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.util.PreviewTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 文档图片静态资源映射 + 鉴权拦截
 * 将 /images/** 映射到本地 data/images 目录（配合 context-path=/ai，实际访问 /ai/images/**）
 * 图片鉴权恒开，校验签名 URL
 *
 * @author yuanke
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ImageWebConfig implements WebMvcConfigurer {

    private final AppProperties properties;
    private final ImageUrlSigner imageUrlSigner;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // toUri() 得到 file: 形式，Windows 反斜杠也能正确处理
        String imagesDir = Paths.get(properties.getImages().getDir(), "images")
                .toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/images/**")
                .addResourceLocations(imagesDir);
        // 产物交付（present_artifacts）：/artifacts/** 映射到 data/artifacts，配合 context-path=/ai 实际访问 /ai/artifacts/**
        // 与图片同一鉴权拦截器（开启签名校验时 URL 带 expire+sig），文件名已在 ArtifactService 白名单净化
        String artifactsDir = Paths.get(properties.getImages().getDir(), "artifacts")
                .toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/artifacts/**")
                .addResourceLocations(artifactsDir);
    }

    /**
     * 网页会以子资源方式引用的类型：产物 HTML 里的 {@code <script src>} / {@code <link>} 靠这条映射
     * 决定浏览器认不认。把 js 改成 text/plain 会被 {@code nosniff} 直接拒执行，页面就哑了——
     * 所以这几样即使两张表里都缺条目也不补，保持浏览器默认行为。
     */
    private static final java.util.Set<String> WEB_ASSET_EXTS =
            java.util.Set.of("js", "mjs", "css", "svg", "wasm");

    /**
     * 文本类产物的 Content-Type。md / markdown / py 在 Tomcat 的 {@code MimeTypeMappings.properties}
     * 与 Spring 的 {@code mime.types} 两张表里都没有条目，静态资源解析就不发
     * Content-Type——浏览器对顶层导航会嗅探成纯文本显示，但没有任何编码线索时按单字节解，
     * 中文产物会渲染成「# 鏍囬」这类乱码。产物里 md 是主力类型，编码错等于预览是坏的。
     * <p>按 {@link PreviewTypes#TEXT_ARTIFACT_EXTS} 整张扫（逐个点补就是下一次漏一个），并且**一律覆盖**，
     * 不只是补缺：表里已有的条目并不都适合直接读——{@code ts} 被映射成 {@code video/mp2t}、
     * {@code rs} 成 {@code application/rls-services+xml}、{@code sql} 成 {@code application/x-sql}，
     * 顶层导航打开会被当媒体/未知类型处理（下载而不是显示）。产物里的这些类型要看的是源码。
     * 页面档（html/htm）与 {@link #WEB_ASSET_EXTS} 跳过：前者要真渲染，后者改了会打断产物页子资源。
     * <p>这条映射作用于整个 context：{@code /images/} 只走图片类型（不在本清单里），不受影响；
     * {@code /project-files/} 的文本预览走控制器另给类型，静态地址只用于页面/图片/PDF 与新标签。
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> artifactTextMimeMappings() {
        return factory -> factory.addContextCustomizers(context -> {
            java.util.List<String> exts = textArtifactMimeExts();
            for (String ext : exts) {
                context.addMimeMapping(ext, "text/plain;charset=UTF-8");
            }
            log.info("[ARTIFACT] 文本产物 Content-Type 已设 {} 种：{}", exts.size(), exts);
        });
    }

    /** 需要给 text/plain;charset=UTF-8 的产物扩展名（排序后）：页面档与网页子资源不在此列 */
    static java.util.List<String> textArtifactMimeExts() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String ext : PreviewTypes.TEXT_ARTIFACT_EXTS) {
            if (PreviewTypes.PAGE_EXTS.contains(ext) || WEB_ASSET_EXTS.contains(ext)) continue;
            out.add(ext);
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ImageAuthInterceptor(imageUrlSigner))
                .addPathPatterns("/images/**", "/artifacts/**")
                // 用户头像是公开展示图（不可猜 UUID，等同聊天图片的暴露级别），从图片鉴权拦截中排除：
                // <img> 不带签名也能加载，避免头像 401
                .excludePathPatterns("/images/avatar/**");
        // 鉴权之后再打隔离头：签名不过根本到不了响应，头也就没必要发
        // 工作区文件的预览地址（/project-files/**，签名校验在控制器里）与产物同一待遇：
        // 模型产出的 HTML 会被顶层导航真的渲染，必须落进不透明源，否则脚本能读本域登录令牌
        registry.addInterceptor(new ArtifactSandboxInterceptor())
                .addPathPatterns("/artifacts/**", "/project-files/**");
    }
}
