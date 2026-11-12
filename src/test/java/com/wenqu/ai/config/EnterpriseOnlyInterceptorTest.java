package com.wenqu.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseOnlyInterceptorTest {

    private final DeploymentGuard guard = mock(DeploymentGuard.class);
    private final EnterpriseOnlyInterceptor interceptor = new EnterpriseOnlyInterceptor(guard);

    @Test
    void standardBlocksEnterpriseEndpoint() throws Exception {
        when(guard.profile()).thenReturn("standard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response,
                handler("enterprise"));

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("企业级功能未启用");
    }

    @Test
    void enterpriseProfileAllowsEndpoint() throws Exception {
        when(guard.profile()).thenReturn("enterprise");

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("enterprise"))).isTrue();
    }

    @Test
    void standardProfileAllowsNormalEndpoint() throws Exception {
        when(guard.profile()).thenReturn("standard");

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("normal"))).isTrue();
    }

    private static HandlerMethod handler(String name) throws Exception {
        Method method = Probe.class.getMethod(name);
        return new HandlerMethod(new Probe(), method);
    }

    static class Probe {
        @EnterpriseOnly
        public void enterprise() {
        }

        public void normal() {
        }
    }
}
