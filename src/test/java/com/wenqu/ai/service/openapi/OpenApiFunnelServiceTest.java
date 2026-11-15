package com.wenqu.ai.service.openapi;

import com.wenqu.ai.mapper.OpenApiCallStatMapper;
import com.wenqu.ai.service.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入漏斗口径：签发 / 首调 / 7 日留存 / 成功率。
 *
 * <p>这些比率是「看板说了算」的数字，算错了没人会发现，只会让人误判接入情况。
 * 分母的选取（谁算「够得着 7 天」、未认证调用算不算、匿名行不进按 Key 分表）都钉在这里。</p>
 */
class OpenApiFunnelServiceTest {

    private final OpenApiCallStatMapper mapper = mock(OpenApiCallStatMapper.class);
    private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
    private final OpenApiFunnelService service = new OpenApiFunnelService(mapper, apiKeyService);

    private static Map<String, Object> key(String id, int daysAgo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("createTime", LocalDateTime.now().minusDays(daysAgo));
        return m;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("首调率只算窗口内签发的 Key；留存只把「活过 7 天」的 Key 计入分母")
    @SuppressWarnings("unchecked")
    void funnelRatios() {
        when(apiKeyService.list()).thenReturn(new ArrayList<>(List.of(
                key("k-old8", 8),      // 8 天前签发，昨天（=签发后第 7 天）成功调用过 → 首调 + 留存
                key("k-old20", 20),    // 20 天前签发，从未成功调用 → 进留存分母、不进首调分子
                key("k-new0", 0))));   // 今天签发，还没调用 → 只进签发量

        when(mapper.daily(anyList(), any())).thenReturn(List.of());
        when(mapper.statuses(anyList(), any())).thenReturn(List.of(
                row("s", 200, "calls", 10L),
                row("s", 403, "calls", 6L),
                row("s", 401, "calls", 2L),
                row("s", 429, "calls", 1L),
                row("s", 500, "calls", 1L)));
        when(mapper.reasons(anyList(), any(), anyBoolean())).thenReturn(List.of(
                row("r", "scope_denied:runs.read", "calls", 6L)));
        when(mapper.endpoints(anyList(), any())).thenReturn(List.of());
        when(mapper.keyDaily(anyList(), any())).thenReturn(List.of(
                // 8 天前那把：今天的成功调用 + 昨天的成功调用（今天-1 = created+7，落在留存窗内）
                row("k", "k-old8", "d", LocalDate.now(), "calls", 1L, "ok", 1L),
                row("k", "k-old8", "d", LocalDate.now().minusDays(1), "calls", 3L, "ok", 3L)));

        Map<String, Object> out = service.insights(30);

        Map<String, Object> keys = (Map<String, Object>) out.get("keySummary");
        assertEquals(3, keys.get("total"));
        assertEquals(3, keys.get("issued"), "三把都是窗口内签发");
        assertEquals(1, keys.get("firstCall"), "只有 k-old8 成功调用过");
        assertEquals(0.3333, keys.get("firstCallRate"));
        assertEquals(2, keys.get("retentionEligible"), "今天签发的还没到观察窗，不进分母");
        assertEquals(1, keys.get("retention"));
        assertEquals(0.5, keys.get("retentionRate"));

        Map<String, Object> calls = (Map<String, Object>) out.get("calls");
        assertEquals(20L, calls.get("total"));
        assertEquals(10L, calls.get("ok"));
        assertEquals(10L, calls.get("failed"));
        assertEquals(0.5, calls.get("successRate"));
        assertEquals(2L, calls.get("unauthorized"));
        assertEquals(6L, calls.get("forbidden"));
        assertEquals(1L, calls.get("rateLimited"));
        assertEquals(1L, calls.get("serverError"));

        // 归因要能说清「缺哪一个 scope」，并给出下一步
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) out.get("reasons");
        assertEquals("scope_denied:runs.read", reasons.get(0).get("reason"));
        assertEquals("scope", reasons.get(0).get("kind"));
        assertTrue(String.valueOf(reasons.get(0).get("label")).contains("runs.read"));
        assertEquals(0.3, (Double) reasons.get(0).get("share"), 1e-9);
    }

    @Test
    @DisplayName("一把 Key 都没有：不查台账，直接给空结构（未认证尝试也不展示）")
    void emptyWhenNoKeys() {
        when(apiKeyService.list()).thenReturn(new ArrayList<>());

        Map<String, Object> out = service.insights(30);

        verify(mapper, never()).daily(anyList(), any());
        assertEquals(List.of(), out.get("daily"));
        assertEquals(0L, ((Map<String, Object>) out.get("calls")).get("total"));
        assertNull(((Map<String, Object>) out.get("calls")).get("successRate"), "没有调用时比率是「无数据」而不是 0");
    }

    @Test
    @DisplayName("逐日补零：窗口内每一天都有一根柱子，没调用的日子计 0")
    @SuppressWarnings("unchecked")
    void zeroFillsDailySeries() {
        when(apiKeyService.list()).thenReturn(new ArrayList<>(List.of(key("k1", 40))));
        when(mapper.daily(anyList(), any())).thenReturn(List.of(row("d", LocalDate.now(), "calls", 2L, "ok", 1L)));
        when(mapper.statuses(anyList(), any())).thenReturn(List.of(row("s", 200, "calls", 1L), row("s", 403, "calls", 1L)));
        when(mapper.reasons(anyList(), any(), anyBoolean())).thenReturn(List.of());
        when(mapper.endpoints(anyList(), any())).thenReturn(List.of());
        when(mapper.keyDaily(anyList(), any())).thenReturn(List.of());

        List<Map<String, Object>> daily = (List<Map<String, Object>>) service.insights(7).get("daily");

        assertEquals(7, daily.size(), "7 天窗口就是 7 个点，不因为只有一天有数据就缩成 1 个");
        assertEquals(0L, daily.get(0).get("calls"));
        assertEquals(2L, daily.get(6).get("calls"));
        assertEquals(1L, daily.get(6).get("failed"));
    }

    @Test
    @DisplayName("窗口夹在 1–90 天之间，非法参数退回 30 天")
    void clampsWindow() {
        when(apiKeyService.list()).thenReturn(new ArrayList<>());
        assertEquals(90, service.insights(365).get("days"));
        assertEquals(30, service.insights(-5).get("days"), "负数按「没传」处理，退回默认 30 天");
        assertEquals(30, service.insights(0).get("days"));
    }
}
