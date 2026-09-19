package com.wisesoft.wenqu.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域配置：逐行对齐参考实现 {@code server/main.py} 的 {@code _parse_cors_origins} / {@code _build_cors_options}。
 *
 * <p>语义：
 * <ul>
 *   <li>环境变量 {@code WENQU_CORS_ORIGINS}（逗号分隔）优先，去空白后非空即采用；</li>
 *   <li>否则按 {@code WENQU_ENV}（默认 development）判断：production/prod → 不放行任何来源；
 *       其它环境 → 开发默认来源 {@code http://localhost:5173}、{@code http://127.0.0.1:5173}；</li>
 *   <li>来源含 {@code *} → 放行任意来源且**不**允许携带凭据；否则允许凭据 + 显式方法/请求头清单，
 *       并暴露 {@code Content-Disposition}、{@code X-Lock-Remaining}。</li>
 * </ul>
 *
 * <p>必要替换：{@code CORSMiddleware} → {@link CorsRegistry}；环境变量前缀统一改为本系统标识
 * （{@code WENQU_}）。开发默认来源保持参考实现字面量——本产品前端走 Vite 代理（同源），
 * 如需直连请用 {@code WENQU_CORS_ORIGINS} 指定（本产品前端 dev 端口为 5800）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final List<String> DEFAULT_DEVELOPMENT_CORS_ORIGINS =
            List.of("http://localhost:5173", "http://127.0.0.1:5173");

    private static final List<String> EXPLICIT_CORS_METHODS =
            List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");

    private static final List<String> EXPLICIT_CORS_HEADERS =
            List.of("Accept", "Authorization", "Content-Type", "Last-Event-ID", "X-Requested-With");

    private static final List<String> EXPOSED_HEADERS =
            List.of("Content-Disposition", "X-Lock-Remaining");

    /** 解析允许来源（对应 _parse_cors_origins）。 */
    public static List<String> parseCorsOrigins() {
        String value = System.getenv("WENQU_CORS_ORIGINS");
        List<String> origins = new ArrayList<>();
        if (value != null) {
            for (String origin : value.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
        }
        if (!origins.isEmpty()) {
            return origins;
        }
        String environment = System.getenv("WENQU_ENV");
        String normalized = (environment == null ? "development" : environment.trim()).toLowerCase();
        if ("production".equals(normalized) || "prod".equals(normalized)) {
            return List.of();
        }
        return DEFAULT_DEVELOPMENT_CORS_ORIGINS;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = parseCorsOrigins();
        if (origins.isEmpty()) {
            // 生产且未显式配置来源：不注册任何允许来源（等价于不放行跨域）
            return;
        }
        if (origins.contains("*")) {
            registry.addMapping("/**")
                    .allowedOrigins("*")
                    .allowCredentials(false)
                    .allowedMethods("*")
                    .allowedHeaders("*");
            return;
        }
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(new String[0]))
                .allowCredentials(true)
                .allowedMethods(EXPLICIT_CORS_METHODS.toArray(new String[0]))
                .allowedHeaders(EXPLICIT_CORS_HEADERS.toArray(new String[0]))
                .exposedHeaders(EXPOSED_HEADERS.toArray(new String[0]));
    }
}
