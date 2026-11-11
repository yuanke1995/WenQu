package com.wenqu.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下游集成方「身份链路」的继承回归：dynamic-form 的 CopilotWenquProxyController 把
 * {@code /api/copilot/q/**} 转发成问渠 {@code /api/ai/**}，浏览器不带 Key、由代理注入
 * {@code X-Api-Key}。因此代理白名单里的每一条路径都必须仍在问渠的 Key 白名单内、
 * 且不在拒绝名单内——否则面板拿到的是 1c 硬边界的 403，而不是问答结果。
 *
 * <p>路由取自 {@code dynamic-form/com.wisesoft.form.web/.../CopilotWenquProxyController}
 * 的 {@code isAllowedIdentityPath}；前缀型规则在这里取一个具体实例验证。
 * 这条测试是「开放契约改造不能打断下游」的护栏，不是兼容层。</p>
 */
class IdentityLinkInheritanceTest {

    private record Route(String method, String subPath) {
    }

    private static final List<Route> DTBD_FORWARD_ROUTES = List.of(
            new Route("POST", "chat"),
            new Route("POST", "chat/stop"),
            new Route("POST", "chat/steer"),
            new Route("POST", "chat/attachment"),
            new Route("POST", "ask-user/ask-1/answer"),
            new Route("POST", "plan-approval/plan-1/decide"),
            new Route("POST", "tool-approval/tool-1/decide"),
            new Route("GET", "chat/run"),
            new Route("GET", "chat/resume"),
            new Route("GET", "ask-user/pending"),
            new Route("GET", "plan-approval/pending"),
            new Route("GET", "tool-approval/pending"),
            new Route("GET", "tool-output/tool-1"),
            new Route("GET", "session/session-1"),
            new Route("GET", "session/session-1/events"),
            new Route("GET", "artifact/artifact-1/content"),
            new Route("GET", "config/public"),
            new Route("GET", "agent/available"));

    @Test
    @DisplayName("dtbd 身份链路转发的每条路径在 API Key 通道下仍可达")
    void forwardedPathsRemainReachableForApiKey() throws Exception {
        Method denied = SecurityConfig.class.getDeclaredMethod(
                "isKeyDeniedEndpoint", String.class, String.class);
        denied.setAccessible(true);
        for (Route r : DTBD_FORWARD_ROUTES) {
            String path = "/api/ai/" + r.subPath();
            assertThat(SecurityConfig.isPublicUserEndpoint(r.method(), path))
                    .as("%s %s 必须仍在 Key 白名单内（下游代理只带 X-Api-Key）", r.method(), path)
                    .isTrue();
            assertThat((Boolean) denied.invoke(null, r.method(), path))
                    .as("%s %s 不能落在 Key 拒绝名单内", r.method(), path)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("v1 新增端点不会挤占下游身份链路（命名空间互不干扰）")
    void v1NamespaceDoesNotShadowIdentityLink() {
        // 契约端点是公开文档，不属于 dtbd 转发的 /api/ai/** 原生链路
        assertThat(SecurityConfig.isPublicUserEndpoint("GET", "/api/ai/v1/openapi.json"))
                .as("v1 端点由开放契约注册表判定，不进 isPublicUserEndpoint")
                .isFalse();
        assertThat(SecurityConfig.isPublicUserEndpoint("POST", "/api/ai/chat")).isTrue();
    }
}
