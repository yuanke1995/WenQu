package com.wenqu.ai.config;

import com.wenqu.ai.service.ImageUrlSigner;
import lombok.RequiredArgsConstructor;
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
 * 开启 AI_IMAGES_AUTH_ENABLED=true 时校验签名 URL
 *
 * @author yuanke
 */
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
     * 文本类产物的 Content-Type。md / markdown / py 在 Tomcat 的 {@code MimeTypeMappings.properties}
     * 与 Spring 的 {@code mime.types} 两张表里都没有条目（两个 jar 各翻过一遍），静态资源解析就不发
     * Content-Type——浏览器对顶层导航会嗅探成纯文本显示，但没有任何编码线索时按单字节解，
     * 实测（无头 Edge）中文产物渲染成「# 鏍囬」这类乱码。产物里 md 是主力类型，编码错等于预览是坏的。
     * <p>只补两张表里都缺的扩展名：{@code addMimeMapping} 会覆盖同名条目，动到 java 这类已有映射的
     * 会连带改变 /images/ 那条静态路的响应类型；已有条目的补编码靠
     * {@code server.servlet.encoding.force-response}（见 application.yml）。
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> artifactTextMimeMappings() {
        return factory -> factory.addContextCustomizers(context -> {
            context.addMimeMapping("md", "text/plain;charset=UTF-8");
            context.addMimeMapping("markdown", "text/plain;charset=UTF-8");
            context.addMimeMapping("py", "text/plain;charset=UTF-8");
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ImageAuthInterceptor(imageUrlSigner))
                .addPathPatterns("/images/**", "/artifacts/**")
                // 用户头像是公开展示图（不可猜 UUID，等同聊天图片的暴露级别），从图片鉴权拦截中排除：
                // 开启 AI_IMAGES_AUTH_ENABLED 时 <img> 不带签名也能加载，避免头像 401
                .excludePathPatterns("/images/avatar/**");
        // 鉴权之后再打隔离头：签名不过根本到不了响应，头也就没必要发
        // 工作区文件的预览地址（/project-files/**，签名校验在控制器里）与产物同一待遇：
        // 模型产出的 HTML 会被顶层导航真的渲染，必须落进不透明源，否则脚本能读本域登录令牌
        registry.addInterceptor(new ArtifactSandboxInterceptor())
                .addPathPatterns("/artifacts/**", "/project-files/**");
    }
}
