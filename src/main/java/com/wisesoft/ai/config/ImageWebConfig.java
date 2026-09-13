package com.wisesoft.ai.config;

import com.wisesoft.ai.service.ImageUrlSigner;
import lombok.RequiredArgsConstructor;
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

    private final AiAppProperties properties;
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ImageAuthInterceptor(imageUrlSigner))
                .addPathPatterns("/images/**", "/artifacts/**");
    }
}
