package com.wenqu.ai.openapi;

import com.wenqu.ai.config.SecurityConfig;
import com.wenqu.ai.mapper.OpenApiCallStatMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用台账落库口径：端点折叠成契约模板、归因取自现场标记、埋点失败不影响请求。
 *
 * <p>这三条都是「写错了不会报错、只会让看板说谎」的那种，必须由测试钉住：
 * 端点不折叠 → 热度榜被 runId 撑爆；归因取不到 → 所有 403 混成一行；抛异常 → 埋点把业务打挂。</p>
 */
class OpenApiCallRecorderTest {

    private final OpenApiCallStatMapper mapper = mock(OpenApiCallStatMapper.class);
    private final OpenApiRegistry registry = mock(OpenApiRegistry.class);
    private final OpenApiCallRecorder recorder = new OpenApiCallRecorder(mapper, registry);

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        return req;
    }

    @Test
    @DisplayName("已登记端点写契约模板：不同 runId 折叠成同一行")
    void foldsRegisteredEndpointToTemplate() {
        when(registry.match(eq("GET"), anyString())).thenReturn(Optional.of(
                new OpenApiRegistry.Endpoint("/api/ai/v1/runs/{id}", "GET", "runs.read", "查询 run", true, "getRunsById")));
        MockHttpServletRequest req = request("GET", "/api/ai/v1/runs/abc123");
        req.setAttribute(SecurityConfig.ATTR_API_KEY_ID, "key-1");
        OpenApiCallAttr.mark(OpenApiCallAttr.OK);

        recorder.record(req, 200);

        verify(mapper).bump(eq(LocalDate.now()), eq("key-1"), eq("GET /v1/runs/{id}"), eq(200),
                eq(OpenApiCallAttr.OK));
    }

    @Test
    @DisplayName("未登记端点写实际路径：那本身就是「调错了」的证据")
    void keepsActualPathWhenUnregistered() {
        when(registry.match(anyString(), anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest req = request("POST", "/api/ai/v1/nope");
        OpenApiCallAttr.mark(OpenApiCallAttr.ENDPOINT_NOT_REGISTERED);

        recorder.record(req, 403);

        verify(mapper).bump(eq(LocalDate.now()), eq(""), eq("POST /api/ai/v1/nope"), eq(403),
                eq(OpenApiCallAttr.ENDPOINT_NOT_REGISTERED));
    }

    @Test
    @DisplayName("没带凭据的 401 归因到 no_credential，不是笼统的 401")
    void explainsBareUnauthorized() {
        when(registry.match(anyString(), anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest req = request("GET", "/api/ai/v1/me");

        recorder.record(req, 401);

        verify(mapper).bump(any(), eq(""), anyString(), eq(401), eq(OpenApiCallAttr.NO_CREDENTIAL));
    }

    @Test
    @DisplayName("归因先到先得：Key 已判无效后，登录门禁补的 401 不覆盖根因")
    void keepsFirstReason() {
        MockHttpServletRequest req = request("GET", "/api/ai/v1/me");
        OpenApiCallAttr.mark(OpenApiCallAttr.KEY_EXPIRED);
        OpenApiCallAttr.mark("error_401");

        assertEquals(OpenApiCallAttr.KEY_EXPIRED, OpenApiCallAttr.peek(req));
    }

    @Test
    @DisplayName("台账写失败不抛给调用方：观测设施不能影响业务")
    void swallowFailures() {
        when(registry.match(anyString(), anyString())).thenReturn(Optional.empty());
        when(mapper.bump(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        MockHttpServletRequest req = request("GET", "/api/ai/v1/me");

        assertDoesNotThrow(() -> recorder.record(req, 200));
    }

    @Test
    @DisplayName("只采集 v1 全量 + 其余路径带 Key 的调用（漏斗的分母要含存量端点）")
    void filterScope() {
        OpenApiCallFilter filter = new OpenApiCallFilter(recorder);

        MockHttpServletRequest v1 = request("GET", "/api/ai/v1/me");
        assertFalse(filter.shouldNotFilter(v1), "v1 路径即使没凭据也要记（401 是漏斗的一段）");

        MockHttpServletRequest legacyNoKey = request("POST", "/api/ai/chat");
        assertTrue(filter.shouldNotFilter(legacyNoKey), "界面上的登录态调用不是接入调用");

        MockHttpServletRequest legacyKey = request("POST", "/api/ai/chat");
        legacyKey.addHeader("X-Api-Key", "sk-whatever");
        assertFalse(filter.shouldNotFilter(legacyKey), "带 Key 的存量端点属于接入");

        MockHttpServletRequest options = request("OPTIONS", "/api/ai/v1/me");
        assertTrue(filter.shouldNotFilter(options), "预检不是调用");
    }

    @Test
    @DisplayName("SSE 事件流按建立时计数：状态码 200 与端点都如实落账")
    void recordsStreamStart() throws Exception {
        when(registry.match(anyString(), anyString())).thenReturn(Optional.of(
                new OpenApiRegistry.Endpoint("/api/ai/v1/runs/{id}/events", "GET", "runs.read",
                        "事件流", true, "getRunsByIdEvents")));
        MockHttpServletRequest req = request("GET", "/api/ai/v1/runs/r1/events");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        OpenApiCallFilter filter = new OpenApiCallFilter(recorder);

        filter.doFilter(req, resp, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));

        verify(mapper).bump(any(), eq(""), eq("GET /v1/runs/{id}/events"), eq(200), eq(OpenApiCallAttr.OK));
    }

    @Test
    @DisplayName("OPTIONS 不落账")
    void skipsPreflight() throws Exception {
        MockHttpServletRequest req = request("OPTIONS", "/api/ai/v1/me");
        OpenApiCallFilter filter = new OpenApiCallFilter(recorder);

        filter.doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { });

        verify(mapper, never()).bump(any(), anyString(), anyString(), anyInt(), anyString());
    }
}
