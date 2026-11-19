package com.wenqu.ai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 浏览器执行位置解析：两套浏览器硬互斥的唯一判据。
 *
 * 关注点：内置（builtin）掩盖执行器在线、headless 只在真走本机时生效、
 * 离线回落云端（历史行为）、空 uid fail-closed。
 */
class BrowserModeResolverTest {

    private LocalBrowserTools local;
    private UserConfigService config;
    private BrowserModeResolver resolver;

    private static final String UID = "u1";

    @BeforeEach
    void setUp() {
        local = mock(LocalBrowserTools.class);
        config = mock(UserConfigService.class);
        resolver = new BrowserModeResolver(local, config);
    }

    private void location(String v) {
        when(config.personalValue(UID, BrowserModeResolver.LOCATION_KEY)).thenReturn(v);
    }

    private void headless(String v) {
        when(config.personalValue(UID, BrowserModeResolver.HEADLESS_KEY)).thenReturn(v);
    }

    private void online(String id) {
        when(local.onlineExecutor(UID)).thenReturn(id);
    }

    @Test
    @DisplayName("默认（空值）+ 执行器在线 → 本机有头")
    void defaultOnline() {
        location("");
        online("ex1");
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.LOCAL_HEADED);
        assertThat(resolver.local(UID)).isTrue();
        assertThat(resolver.cloud(UID)).isFalse();
    }

    @Test
    @DisplayName("选内置浏览器：执行器在线也走云端，本机不挂")
    void builtinWinsOverOnlineExecutor() {
        location("builtin");
        online("ex1");
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.CLOUD);
        assertThat(resolver.cloud(UID)).isTrue();
        assertThat(resolver.local(UID)).isFalse();
    }

    @Test
    @DisplayName("内置值大小写不敏感")
    void builtinCaseInsensitive() {
        location("BuIlTiN");
        online("ex1");
        assertThat(resolver.builtin(UID)).isTrue();
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.CLOUD);
    }

    @Test
    @DisplayName("本机 + headless=true → 本机无头；内置时 headless 无效")
    void headlessOnlyOnLocal() {
        location("");
        headless("true");
        online("ex1");
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.LOCAL_HEADLESS);
        assertThat(resolver.headless(UID)).isTrue();

        location("builtin");
        online("ex1");
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.CLOUD);
        assertThat(resolver.headless(UID)).isFalse();
    }

    @Test
    @DisplayName("本机模式但执行器离线 → OFFLINE（明确失败，不回落云端）")
    void localButOffline() {
        location("local");
        when(local.onlineExecutor(UID)).thenReturn(null);
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.OFFLINE);
        assertThat(resolver.local(UID)).isFalse();
        assertThat(resolver.cloud(UID)).isFalse();
    }

    @Test
    @DisplayName("默认空值 + 执行器离线 → 回落云端（历史承诺）")
    void defaultOfflineFallsBackToCloud() {
        location("");
        when(local.onlineExecutor(UID)).thenReturn(null);
        assertThat(resolver.resolve(UID)).isEqualTo(BrowserModeResolver.Mode.CLOUD);
    }

    @Test
    @DisplayName("空 uid fail-closed 到非内置（离线时按默认回落云端，不碰本机）")
    void blankUid() {
        assertThat(resolver.builtin(null)).isFalse();
        assertThat(resolver.builtin("  ")).isFalse();
        assertThat(resolver.resolve(null)).isEqualTo(BrowserModeResolver.Mode.CLOUD);
        assertThat(resolver.local(null)).isFalse();
    }
}
