package com.wisesoft.ai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.ApiEndpointMapper;
import com.wisesoft.ai.model.ApiEndpoint;
import com.wisesoft.ai.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 启动期接口扫描：把 Spring MVC 端点自动登记进 c_ai_api（RBAC 鉴权数据源）。
 * <p>
 * 动机：接口权限要「真拦截」，前提是接口清单真实完整——手填 30+ 端点既繁琐又必然漂移。
 * 扫描 {@link RequestMappingHandlerMapping} 的全部 HandlerMethod：
 * <ul>
 *   <li>仅登记 {@code /api/} 前缀的端点（拦截器 guarded 空间）；</li>
 *   <li>幂等：按 (method, path) 唯一键 {@code INSERT IGNORE}，新端点自动出现、已登记的不动
 *       （保留管理员改过的名称/模块）；</li>
 *   <li>名称优先取 {@code @Operation#summary}（Swagger 注解），缺失回落「Controller#方法」；</li>
 *   <li>模块取 Controller 类名（去 Controller 后缀），管理员可后续手工归类。</li>
 * </ul>
 * 登记完成后 {@link RoleService#invalidateApiPatterns()} 失效鉴权缓存。
 * 扫描失败仅告警不阻塞启动（fail-safe：鉴权按库内既有数据执行）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiEndpointScanner implements ApplicationRunner {

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final ApiEndpointMapper apiEndpointMapper;
    private final RoleService roleService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            scan();
        } catch (Exception e) {
            log.warn("[ApiScan] 接口扫描失败（不阻塞启动，鉴权按库内既有数据执行）: {}", e.getMessage());
        }
    }

    void scan() {
        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
        int added = 0, total = 0;
        // 幂等参照：(method, path) → id
        Map<String, String> existing = new ConcurrentHashMap<>();
        for (ApiEndpoint a : apiEndpointMapper.selectList(null)) {
            existing.put(a.getMethod().toUpperCase() + " " + a.getPath(), a.getId());
        }

        List<ApiEndpoint> toInsert = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMethods.entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod hm = e.getValue();

            Set<String> patterns = extractPatterns(info);
            Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                    info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (pattern == null || !pattern.startsWith("/api/")) continue;
                List<String> ms = methods.isEmpty() ? List.of("ALL")
                        : methods.stream().map(Enum::name).sorted().toList();
                for (String m : ms) {
                    total++;
                    String key = m + " " + pattern;
                    if (existing.containsKey(key)) continue;
                    ApiEndpoint api = new ApiEndpoint();
                    api.setId(UUID.randomUUID().toString().replace("-", ""));
                    api.setMethod(m);
                    api.setPath(pattern);
                    api.setName(resolveName(hm));
                    api.setModule(resolveModule(hm));
                    api.setBuiltin(1);
                    toInsert.add(api);
                    existing.put(key, api.getId());
                }
            }
        }
        for (ApiEndpoint api : toInsert) {
            try {
                apiEndpointMapper.insert(api);
                added++;
            } catch (Exception ex) {
                // 唯一键冲突等：忽略单条，保证其余登记成功
                log.debug("[ApiScan] 登记跳过 {} {}: {}", api.getMethod(), api.getPath(), ex.getMessage());
            }
        }
        if (added > 0) roleService.invalidateApiPatterns();
        log.info("[ApiScan] 接口扫描完成：命中 {} 个端点，新登记 {} 个", total, added);
    }

    /** 兼容 Spring 5/6：PathPattern（默认）与 AntPathMatcher 两种条件 */
    @SuppressWarnings("deprecation")
    private static Set<String> extractPatterns(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        PatternsRequestCondition pc = info.getPatternsCondition();
        return pc == null ? Set.of() : pc.getPatterns();
    }

    /** 接口名：@Operation summary 优先（AnnotatedElementUtils 兼容组合注解），缺失回落 Class#method */
    private static String resolveName(HandlerMethod hm) {
        try {
            Method m = hm.getMethod();
            Operation op = AnnotatedElementUtils.findMergedAnnotation(m, Operation.class);
            if (op != null && op.summary() != null && !op.summary().isBlank()) return op.summary().trim();
            return hm.getBeanType().getSimpleName() + "#" + m.getName();
        } catch (Exception e) {
            return hm.getBeanType().getSimpleName();
        }
    }

    /** 模块：Controller 类名去 Controller 后缀（管理员可在接口页手工归类中文模块名） */
    private static String resolveModule(HandlerMethod hm) {
        String n = hm.getBeanType().getSimpleName();
        return n.endsWith("Controller") ? n.substring(0, n.length() - "Controller".length()) : n;
    }
}
