package com.wenqu.ai.controller;

import com.wenqu.ai.service.SessionProcService;
import com.wenqu.ai.util.RequestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由与载荷：清单/计数/停止三个口子的映射、缺参与 400 的口径。
 * 授权点由 {@code ApiEndpointScanner} 在启动期收集，这里只管路由层。
 */
class SessionProcControllerTest {

    private SessionProcService procService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        procService = mock(SessionProcService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SessionProcController(procService)).build();
        RequestUser.set("u1", null, "user");
    }

    @AfterEach
    void tearDown() {
        RequestUser.clear();
    }

    @Test
    @DisplayName("GET 清单：sessionId 透传，两路 sides 原样返回")
    void listRoutes() throws Exception {
        when(procService.list("s1", "u1")).thenReturn(Map.of(
                "available", true,
                "procs", List.of(Map.of("side", "local", "tag", "wq1.s1.aaaaaaaa")),
                "sides", Map.of("local", Map.of("available", true))));
        mvc.perform(get("/api/ai/session/procs").param("sessionId", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.procs[0].side").value("local"))
                .andExpect(jsonPath("$.data.sides.local.available").value(true));
    }

    @Test
    @DisplayName("kill：side 与 tag 原样转交服务层")
    void killRoutes() throws Exception {
        when(procService.kill("s1", "u1", "local", "wq1.s1.aaaaaaaa"))
                .thenReturn(Map.of("found", true, "killed", 1, "remaining", 0));
        mvc.perform(post("/api/ai/session/procs/kill")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"side\":\"local\",\"tag\":\"wq1.s1.aaaaaaaa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.killed").value(1));
        verify(procService).kill("s1", "u1", "local", "wq1.s1.aaaaaaaa");
    }

    @Test
    @DisplayName("kill 缺参：400，不进服务层")
    void killMissingParams() throws Exception {
        mvc.perform(post("/api/ai/session/procs/kill")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(400));
        verify(procService, org.mockito.Mockito.never()).kill(eq("s1"), eq("u1"), eq(""), eq(""));
    }

    @Test
    @DisplayName("killAll：只带会话与来源")
    void killAllRoutes() throws Exception {
        when(procService.killAll("s1", "u1", "sandbox"))
                .thenReturn(Map.of("found", true, "killed", 2, "remaining", 0));
        mvc.perform(post("/api/ai/session/procs/killAll")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"side\":\"sandbox\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.killed").value(2));
    }

    @Test
    @DisplayName("GET 侧栏计数：直接回 sessions/partial")
    void liveRoutes() throws Exception {
        when(procService.live("u1")).thenReturn(Map.of("sessions", Map.of("s1", 3), "partial", false));
        mvc.perform(get("/api/ai/session/procs/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions.s1").value(3))
                .andExpect(jsonPath("$.data.partial").value(false));
    }
}
