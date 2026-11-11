package com.wenqu.ai.openapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开放契约回归：默认拒绝、模板匹配、CORS 方法覆盖、机器可读契约的生成。
 *
 * <p>这里不启 Spring 上下文——路由的来源本来就是 Spring 的 handler mapping，
 * 启动期扫描的完整性由 {@link OpenApiRegistry#onContextRefreshed()} 的 fail-loud 校验保证；
 * 单测用一组真实形状的条目验证「查表判定」这一层不会把未登记端点放给 API Key。</p>
 */
class OpenApiRegistryTest {

    private OpenApiRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new OpenApiRegistry(null);
        List<OpenApiRegistry.Endpoint> endpoints = List.of(
                new OpenApiRegistry.Endpoint("/api/ai/v1/kbs", "GET",
                        OpenApiScopes.KB_READ, "知识库列表", true, "getKbs"),
                new OpenApiRegistry.Endpoint("/api/ai/v1/kbs/{id}", "GET",
                        OpenApiScopes.KB_READ, "知识库详情", true, "getKbsById"),
                new OpenApiRegistry.Endpoint("/api/ai/v1/kbs", "POST",
                        OpenApiScopes.KB_WRITE, "创建知识库", true, "postKbs"),
                new OpenApiRegistry.Endpoint("/api/ai/v1/workflows/webhook/{token}", "POST",
                        "", "Webhook 入站", false, "postWorkflowsWebhook"));
        Field f = OpenApiRegistry.class.getDeclaredField("endpoints");
        f.setAccessible(true);
        f.set(registry, endpoints);
    }

    @Test
    @DisplayName("未登记路径默认拒绝，不会自动对 Key 开放")
    void unregisteredPathIsDenied() {
        assertThat(registry.match("POST", "/api/ai/v1/chat/completions")).isEmpty();
        assertThat(registry.match("GET", "/api/ai/v1/kbs/new-endpoint/secret")).isEmpty();
        assertThat(registry.match("GET", "/api/ai/session")).isEmpty();
    }

    @Test
    @DisplayName("不对 Key 开放的端点不会从注册表放行")
    void webhookIsNotKeyAccessible() {
        assertThat(registry.match("POST", "/api/ai/v1/workflows/webhook/t-123")).isEmpty();
    }

    @Test
    @DisplayName("路径模板与方法必须同时命中，scope 随之返回")
    void templateAndMethodMustBothMatch() {
        assertThat(registry.match("GET", "/api/ai/v1/kbs/kb-1").orElseThrow().scope())
                .isEqualTo(OpenApiScopes.KB_READ);
        assertThat(registry.match("POST", "/api/ai/v1/kbs").orElseThrow().scope())
                .isEqualTo(OpenApiScopes.KB_WRITE);
        // 方法写错不能落到同路径的另一条上
        assertThat(registry.match("DELETE", "/api/ai/v1/kbs")).isEmpty();
    }

    @Test
    @DisplayName("CORS 预检拿到该路径登记的全部方法，GET 不再漏掉")
    void corsMethodsCoverGetAndPost() {
        assertThat(registry.methodsFor("/api/ai/v1/kbs")).containsExactlyInAnyOrder("GET", "POST");
        assertThat(registry.methodsFor("/api/ai/v1/kbs/kb-1")).containsExactly("GET");
        assertThat(registry.methodsFor("/api/ai/v1/unknown")).isEmpty();
    }

    @Test
    @DisplayName("OpenAPI 文档由同一份条目生成，含路径参数与两种凭据形态")
    void specIsGeneratedFromSameEndpoints() {
        Map<String, Object> spec = registry.spec("/ai");
        assertThat(spec).containsEntry("openapi", "3.1.0");
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKeys("/api/ai/v1/kbs", "/api/ai/v1/kbs/{id}",
                "/api/ai/v1/workflows/webhook/{token}");
        @SuppressWarnings("unchecked")
        Map<String, Object> kbs = (Map<String, Object>) paths.get("/api/ai/v1/kbs");
        assertThat(kbs).containsKeys("get", "post");
        @SuppressWarnings("unchecked")
        Map<String, Object> byId = (Map<String, Object>) paths.get("/api/ai/v1/kbs/{id}");
        @SuppressWarnings("unchecked")
        Map<String, Object> getById = (Map<String, Object>) byId.get("get");
        assertThat((List<?>) getById.get("parameters")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(schemes).containsKeys("ApiKeyAuth", "BearerKey");
    }
}
