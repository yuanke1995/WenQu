package com.wenqu.ai.openapi;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 机器可读的开放契约：{@code GET /api/ai/v1/openapi.json}。
 *
 * <p>由 {@link OpenApiRegistry} 实时生成，路径/方法/scope 与实际鉴权判定同源，
 * 不再依赖 springdoc 全量扫描（那个会把 500+ 内部端点一起暴露，生产默认关闭）。
 * 本端点不走 API Key 通道：契约文档本身公开可读，集成方不需要先有 Key 才能读契约。</p>
 */
@RestController
@RequiredArgsConstructor
public class OpenApiSpecController {

    private final OpenApiRegistry registry;

    @OpenApi(summary = "开放 API 契约（OpenAPI 3.1）")
    @GetMapping("/api/ai/v1/openapi.json")
    public Map<String, Object> spec(HttpServletRequest request) {
        return registry.spec(request.getContextPath());
    }
}
