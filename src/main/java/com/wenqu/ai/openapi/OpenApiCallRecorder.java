package com.wenqu.ai.openapi;

import com.wenqu.ai.config.SecurityConfig;
import com.wenqu.ai.mapper.OpenApiCallStatMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 调用台账落库（B5 接入漏斗）：把一次外部调用折叠成「按日聚合的一行」。
 *
 * <p><b>永不干扰业务</b>：埋点失败只写 debug 日志。台账是观测设施，不能因为它自己写不进去
 * 就让集成方的请求失败——这条比「数据完整」更重要，缺一行只是看板少一次计数。</p>
 *
 * <p><b>端点写契约模板而不是实际路径</b>：{@code GET /v1/runs/{runId}} 才能把成百上千个 runId
 * 收成一类端点；否则热度榜会被「一个个不同的 runId」占满，看不到到底哪类能力在被使用。</p>
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiCallRecorder {

    /** 表里 endpoint 列宽（V23）：超长截断，避免脏路径把写入顶掉 */
    private static final int ENDPOINT_MAX = 160;

    private final OpenApiCallStatMapper mapper;
    private final OpenApiRegistry registry;

    /**
     * 记一次调用。
     *
     * @param req    当前请求（归因标记、Key id 都挂在它上面）
     * @param status 响应状态码；事件流按「连接已建立」记 2xx（流内错误看 run 事件，不看台账）
     */
    public void record(HttpServletRequest req, int status) {
        try {
            String method = req.getMethod();
            String path = pathOf(req);
            String keyId = attr(req, SecurityConfig.ATTR_API_KEY_ID);
            mapper.bump(LocalDate.now(),
                    keyId == null ? "" : keyId,
                    endpointOf(method, path),
                    status,
                    reasonOf(req, status));
        } catch (Exception e) {
            // 台账写失败不改判请求结果：这里只留一行 debug，连 warn 都不该刷（外部高频调用时会刷屏）
            log.debug("[OPENAPI-STAT] 台账写入失败（不影响请求）: {}", e.getMessage());
        }
    }

    /** 端点标识：登记过的写契约模板，没登记的写实际路径（那本身就是「调错了」的证据） */
    private String endpointOf(String method, String path) {
        Optional<OpenApiRegistry.Endpoint> hit = registry.match(method, path);
        String ep = hit.map(e -> e.method() + " " + stripPrefix(e.path()))
                .orElseGet(() -> method + " " + path);
        return ep.length() > ENDPOINT_MAX ? ep.substring(0, ENDPOINT_MAX) : ep;
    }

    private static String stripPrefix(String contractPath) {
        return contractPath.startsWith(OpenApiRegistry.PREFIX)
                ? "/v1/" + contractPath.substring(OpenApiRegistry.PREFIX.length())
                : contractPath;
    }

    /**
     * 归因：优先采用链路里标记的现场原因（Key 校验失败 / 缺 scope / 端点未登记 / 拒绝名单），
     * 没标记的按状态码兜底——兜底只分得出「成功 / 限流 / 其他」，说不出细节，这正是现场标记存在的理由。
     */
    private static String reasonOf(HttpServletRequest req, int status) {
        String marked = OpenApiCallAttr.peek(req);
        if (marked != null && !marked.isBlank()) return marked;
        if (status >= 200 && status < 400) return OpenApiCallAttr.OK;
        if (status == 401) return OpenApiCallAttr.NO_CREDENTIAL;
        if (status == 429) return OpenApiCallAttr.RATE_LIMITED;
        return "error_" + status;
    }

    private static String pathOf(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    private static String attr(HttpServletRequest req, String name) {
        Object v = req.getAttribute(name);
        return v == null ? null : String.valueOf(v);
    }
}
