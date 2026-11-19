package com.wenqu.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一浏览器工具的三路分派：本机有头/无头、云端、离线明确失败。
 * 关注点：同一个 browserOpen 在不同执行位置下路由正确、headless 参数随模式传递、
 * 快照只在读页面工具后记录。
 */
class BrowserToolsTest {

    private BrowserModeResolver mode;
    private LocalBrowserTools local;
    private McpClientService mcp;
    private BrowserSnapshotStore snapshots;
    private BrowserTools tools;

    private static final String UID = "u1";
    private static final String SID = "s1";

    private ToolContext ctx() {
        return new ToolContext(Map.of(
                PresentArtifactTool.CTX_USER_ID, UID,
                PresentArtifactTool.CTX_SESSION_ID, SID));
    }

    @BeforeEach
    void setUp() {
        mode = mock(BrowserModeResolver.class);
        local = mock(LocalBrowserTools.class);
        mcp = mock(McpClientService.class);
        snapshots = mock(BrowserSnapshotStore.class);
        tools = new BrowserTools(mode, local, mcp, snapshots);
    }

    @Test
    @DisplayName("本机有头：走执行器、headless=false、记快照")
    void localHeaded() {
        when(mode.resolve(UID)).thenReturn(BrowserModeResolver.Mode.LOCAL_HEADED);
        when(local.call(any(), eq("browser_navigate"), anyMap(), anyBoolean())).thenReturn("# T\nurl");
        when(local.call(any(), eq("browser_snapshot"), anyMap(), anyBoolean())).thenReturn("# T\nbody");

        String out = tools.browserOpen("https://example.com", ctx());

        assertThat(out).contains("body");
        verify(local).call(any(), eq("browser_navigate"), anyMap(), eq(false));
        verify(mcp, never()).callBrowserTool(anyString(), anyString(), anyMap());
        verify(snapshots).record(eq(UID), eq(SID), eq("https://example.com"), any(), any(), eq("local-headed"));
    }

    @Test
    @DisplayName("本机无头：headless=true 透传给执行器")
    void localHeadless() {
        when(mode.resolve(UID)).thenReturn(BrowserModeResolver.Mode.LOCAL_HEADLESS);
        when(local.call(any(), anyString(), anyMap(), anyBoolean())).thenReturn("ok");

        tools.browserSnapshot(ctx());

        verify(local).call(any(), eq("browser_snapshot"), anyMap(), eq(true));
        verify(snapshots).record(eq(UID), eq(SID), eq(""), any(), any(), eq("local-headless"));
    }

    @Test
    @DisplayName("云端：直调平台 MCP，不碰执行器")
    void cloud() {
        when(mode.resolve(UID)).thenReturn(BrowserModeResolver.Mode.CLOUD);
        when(mcp.callBrowserTool(UID, "browser_navigate", Map.of("url", "https://x.test"))).thenReturn("# X");
        when(mcp.callBrowserTool(UID, "browser_snapshot", Map.of())).thenReturn("# X\nbody");

        String out = tools.browserOpen("https://x.test", ctx());

        assertThat(out).contains("body");
        verify(local, never()).call(any(), anyString(), anyMap(), anyBoolean());
        verify(mcp).callBrowserTool(UID, "browser_navigate", Map.of("url", "https://x.test"));
        verify(snapshots).record(eq(UID), eq(SID), eq("https://x.test"), any(), any(), eq("cloud"));
    }

    @Test
    @DisplayName("离线且要求本机：明确失败，不悄悄换云端、不记快照")
    void offlineFailLoud() {
        when(mode.resolve(UID)).thenReturn(BrowserModeResolver.Mode.OFFLINE);

        String out = tools.browserOpen("http://localhost:8080", ctx());

        assertThat(out).contains("不可用").contains("本机执行器");
        verify(local, never()).call(any(), anyString(), anyMap(), anyBoolean());
        verify(mcp, never()).callBrowserTool(anyString(), anyString(), anyMap());
        verify(snapshots, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("点击/填写交互不重复记快照")
    void clickDoesNotRecord() {
        when(mode.resolve(UID)).thenReturn(BrowserModeResolver.Mode.LOCAL_HEADED);
        when(local.call(any(), anyString(), anyMap(), anyBoolean())).thenReturn("clicked");

        tools.browserClick("按钮", "e12", ctx());
        tools.browserType("输入框", "e5", "hi", ctx());

        verify(snapshots, never()).record(any(), any(), any(), any(), any(), any());
    }
}
