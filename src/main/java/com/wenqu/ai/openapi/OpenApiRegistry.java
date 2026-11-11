package com.wenqu.ai.openapi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开放契约注册表：{@code /api/ai/v1/**} 的唯一真源。
 *
 * <p>启动期扫描 Spring 的 handler mapping，把带 {@link OpenApi} 的方法收集成条目。
 * 鉴权边界（SecurityConfig）、CORS 允许方法、scope 名册、OpenAPI 文档全部从这里派生，
 * 不再各自维护一份端点清单。</p>
 *
 * <p><b>启动期 fail-loud</b>：v1 命名空间里出现未标注的路由、重复的 method+path、
 * 非法 scope、或名单里有从未被任何端点使用的 scope，直接启动失败。这样「新增 v1 端点」
 * 必须显式声明它对集成方的可见性，不可能悄悄自动对 API Key 开放。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiRegistry {

    public static final String PREFIX = "/api/ai/v1/";

    private final ApplicationContext context;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private volatile List<Endpoint> endpoints = List.of();

    /** 一条开放契约：路径是 Spring 路由模板（含 {id}），method 为大写 HTTP 方法 */
    public record Endpoint(String path, String method, String scope, String summary,
                           boolean keyAccessible, String operationId) {
    }

    private volatile boolean initialized;

    /** 上下文刷新完成后扫描：此时 Spring MVC 的 handler mapping 已经装配完毕 */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        if (initialized) return;
        initialized = true;
        // 按名称取：actuator 在场时容器里还有第二个同类型 bean（controllerEndpointHandlerMapping），
        // 按类型取会 NoUniqueBeanDefinitionException 直接炸启动（实机启动抓到的，MockMvc 测试不启全上下文抓不到）
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        List<Endpoint> found = new ArrayList<>();
        List<String> unannotated = new ArrayList<>();
        Set<String> routeKeys = new LinkedHashSet<>();

        for (Map.Entry<RequestMappingInfo, org.springframework.web.method.HandlerMethod> e
                : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            var handler = e.getValue();
            Set<String> paths = patternsOf(info);
            if (paths.isEmpty()) continue;
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            OpenApi ann = handler.getMethodAnnotation(OpenApi.class);
            for (String p : paths) {
                if (!p.startsWith(PREFIX)) continue;
                if (ann == null) {
                    unannotated.add(p);
                    continue;
                }
                if (methods.isEmpty()) {
                    throw new IllegalStateException("[OPENAPI] " + p + " 未限定 HTTP 方法，无法登记契约");
                }
                String scope = ann.scope();
                // scope 留空 + keyAccessible=true = 任意有效 Key 可读（契约文档这类无需能力授权的面）
                if (!scope.isBlank() && !OpenApiScopes.ALL.contains(scope)) {
                    throw new IllegalStateException("[OPENAPI] " + p + " 的 scope 非法：" + scope);
                }
                if (!ann.keyAccessible() && !scope.isBlank()) {
                    throw new IllegalStateException("[OPENAPI] " + p + " 不对 Key 开放却声明了 scope：" + scope);
                }
                for (RequestMethod m : methods) {
                    String key = m.name() + " " + p;
                    if (!routeKeys.add(key)) {
                        throw new IllegalStateException("[OPENAPI] 路由重复登记：" + key);
                    }
                    found.add(new Endpoint(p, m.name(), scope, ann.summary(), ann.keyAccessible(),
                            operationId(m.name(), p)));
                }
            }
        }
        if (!unannotated.isEmpty()) {
            throw new IllegalStateException("[OPENAPI] v1 命名空间存在未标注 @OpenApi 的路由，"
                    + "默认拒绝对集成方开放，请显式声明：" + unannotated);
        }
        // 名单对账：每个 scope 都必须真有端点在用，避免签发页出现勾了也没用的选项
        for (String s : OpenApiScopes.ALL) {
            if (found.stream().noneMatch(ep -> ep.keyAccessible() && s.equals(ep.scope()))) {
                throw new IllegalStateException("[OPENAPI] scope 「" + s + "」没有任何开放端点使用，请核对名单");
            }
        }
        // 具体路径优先（段数多、字面量多的排前面），避免模板互相抢匹配
        found.sort(Comparator.comparingInt((Endpoint ep) -> ep.path().length()).reversed());
        this.endpoints = List.copyOf(found);
        log.info("[OPENAPI] 开放契约登记完成：{} 个端点，scope {}", found.size(), scopes());
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        PathPatternsRequestCondition pp = info.getPathPatternsCondition();
        if (pp != null && !pp.getPatternValues().isEmpty()) return pp.getPatternValues();
        // 兼容 ant_path_matcher 策略（Boot 3 默认 PathPatternParser，此分支只是兜住显式切换的部署）
        var legacy = info.getPatternsCondition();
        return legacy == null ? Set.of() : legacy.getPatterns();
    }

    private static String operationId(String method, String path) {
        StringBuilder sb = new StringBuilder(method.toLowerCase());
        for (String seg : path.substring(PREFIX.length()).split("/")) {
            if (seg.isBlank()) continue;
            if (seg.startsWith("{") && seg.endsWith("}")) {
                sb.append("By").append(cap(seg.substring(1, seg.length() - 1)));
            } else {
                sb.append(cap(seg.replace('-', '_')));
            }
        }
        return sb.toString();
    }

    private static String cap(String s) {
        if (s.isBlank()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** 当前登记的开放端点（不可变） */
    public List<Endpoint> all() {
        return endpoints;
    }

    /** Key 通道的放行判定：只有标注了 keyAccessible 的 method+path 才可达，其余默认拒绝 */
    public Optional<Endpoint> match(String method, String path) {
        if (method == null || path == null || !path.startsWith(PREFIX)) return Optional.empty();
        return endpoints.stream()
                .filter(ep -> ep.keyAccessible() && ep.method().equalsIgnoreCase(method)
                        && matcher.match(ep.path(), path))
                .findFirst();
    }

    /** 某条路径上登记的 HTTP 方法（CORS 预检据此回 Allow-Methods，GET 端点不再被漏掉） */
    public Set<String> methodsFor(String path) {
        Set<String> out = new LinkedHashSet<>();
        if (path == null) return out;
        for (Endpoint ep : endpoints) {
            if (matcher.match(ep.path(), path)) out.add(ep.method());
        }
        return out;
    }

    /** 实际被端点使用的 scope 名册 */
    public List<String> scopes() {
        return OpenApiScopes.ALL.stream()
                .filter(s -> endpoints.stream().anyMatch(ep -> ep.keyAccessible() && s.equals(ep.scope())))
                .toList();
    }

    /** 每个 scope 覆盖哪些端点（签发页「这个勾选项能调什么」的可核对数据源） */
    public Map<String, List<String>> scopeEndpoints() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String s : OpenApiScopes.ALL) {
            out.put(s, endpoints.stream()
                    .filter(ep -> ep.keyAccessible() && s.equals(ep.scope()))
                    .map(ep -> ep.method() + " " + ep.path())
                    .toList());
        }
        return out;
    }

    /** 生成 OpenAPI 3.1 文档（只含 v1，字段不编造：请求体 schema 未声明即不写死） */
    public Map<String, Object> spec(String contextPath) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.1.0");
        root.put("info", Map.of("title", "问渠开放 API", "version", "1.0.0",
                "description", "由服务端开放契约注册表实时生成；路径、方法、scope 与鉴权判定同源"));
        root.put("servers", List.of(Map.of("url", contextPath == null ? "" : contextPath)));
        root.put("security", List.of(Map.of("ApiKeyAuth", List.of()), Map.of("BearerKey", List.of())));

        Map<String, Object> paths = new LinkedHashMap<>();
        for (Endpoint ep : endpoints) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) paths.computeIfAbsent(
                    ep.path(), k -> new LinkedHashMap<String, Object>());
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("operationId", ep.operationId());
            op.put("summary", ep.summary());
            if (!ep.keyAccessible()) {
                op.put("security", List.of());
                op.put("description", "不走 API Key 通道（令牌即凭据的入站 webhook）");
            } else {
                op.put("description", ep.scope().isBlank()
                        ? "任意有效 API Key 可读（无需额外 scope）"
                        : "API Key 需开通 scope: " + ep.scope());
            }
            List<Map<String, Object>> params = pathParams(ep.path());
            if (!params.isEmpty()) op.put("parameters", params);
            if (!"GET".equals(ep.method())) {
                op.put("requestBody", Map.of("required", true, "content",
                        Map.of("application/json", Map.of("schema", Map.of("type", "object")))));
            }
            op.put("responses", Map.of(
                    "200", Map.of("description", "成功"),
                    "default", Map.of("description", "错误", "content", Map.of("application/json",
                            Map.of("schema", Map.of("$ref", "#/components/schemas/Error"))))));
            item.put(ep.method().toLowerCase(), op);
        }
        root.put("paths", paths);
        root.put("components", Map.of(
                "securitySchemes", Map.of(
                        "ApiKeyAuth", Map.of("type", "apiKey", "in", "header", "name", "X-Api-Key"),
                        "BearerKey", Map.of("type", "http", "scheme", "bearer",
                                "description", "Bearer sk-…（OpenAI SDK 默认鉴权头）")),
                "schemas", Map.of("Error", Map.of("type", "object", "properties", Map.of(
                        "error", Map.of("type", "object", "properties", Map.of(
                                "message", Map.of("type", "string"),
                                "type", Map.of("type", "string"),
                                "code", Map.of("type", "string"),
                                "request_id", Map.of("type", "string"))))))));
        return root;
    }

    private static final Pattern PATH_VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private static List<Map<String, Object>> pathParams(String path) {
        List<Map<String, Object>> out = new ArrayList<>();
        Matcher m = PATH_VAR.matcher(path);
        while (m.find()) {
            out.add(Map.of("name", m.group(1), "in", "path", "required", true,
                    "schema", Map.of("type", "string")));
        }
        return out;
    }
}
