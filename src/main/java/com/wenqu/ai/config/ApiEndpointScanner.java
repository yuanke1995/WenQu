package com.wenqu.ai.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.ApiEndpointMapper;
import com.wenqu.ai.mapper.MenuApiMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.model.ApiEndpoint;
import com.wenqu.ai.model.Menu;
import com.wenqu.ai.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
 *   <li>模块取 Controller 类名（去 Controller 后缀）并按 {@link #MODULE_ZH} 对照汉化，
 *       管理员可后续手工归类；启动时同步把存量英文模块键归一成中文（仅展示字段，不参与鉴权）；</li>
 *   <li>菜单归属：按 Controller 对照表（{@link #DEFAULT_MENUS}）写入默认归属（多归属，供权限页分组与勾选联动）；
 *       新登记接口自动归属；零归属的内置接口启动时幂等补齐（已有任一归属则不动，尊重管理员调整）。
 *       归属不参与鉴权，管理员可在权限页任意调整。</li>
 * </ul>
 * 登记完成后 {@link RoleService#invalidateApiPatterns()} 失效鉴权缓存。
 * 扫描失败仅告警不阻塞启动（fail-safe：鉴权按库内既有数据执行）。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class ApiEndpointScanner implements ApplicationRunner {

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final ApiEndpointMapper apiEndpointMapper;
    private final MenuMapper menuMapper;
    private final MenuApiMapper menuApiMapper;
    private final RoleService roleService;

    /**
     * 模块汉化对照：Controller 类名（去 Controller 后缀）→ 中文模块名。
     * 权限页接口列表/筛选、角色接口权限树全按该字段展示与分组；鉴权只认 (method, path)，改名无副作用。
     * 未收录的控制器回落英文类名（管理员可在接口页手工改）。
     */
    private static final Map<String, String> MODULE_ZH = Map.ofEntries(
            Map.entry("Agent", "智能体"),
            Map.entry("ApiEndpoint", "接口管理"),
            Map.entry("ApiKey", "API 密钥"),
            Map.entry("Artifact", "产物"),
            Map.entry("Auth", "登录认证"),
            Map.entry("Chat", "对话"),
            Map.entry("Config", "系统配置"),
            Map.entry("Department", "部门"),
            Map.entry("DescCache", "图片描述缓存"),
            Map.entry("Document", "文档"),
            Map.entry("Evaluation", "检索评估"),
            Map.entry("Graph", "知识图谱"),
            Map.entry("KnowledgeBase", "知识库"),
            Map.entry("Knowledge", "知识块"),
            Map.entry("Mcp", "MCP 工具"),
            Map.entry("Memory", "长期记忆"),
            Map.entry("Menu", "菜单"),
            Map.entry("Ocr", "解析引擎"),
            Map.entry("Oidc", "单点登录"),
            Map.entry("Provider", "模型供应商"),
            Map.entry("Qa", "问答看板"),
            Map.entry("RetrievalDebug", "检索调试"),
            Map.entry("Role", "角色"),
            Map.entry("Sandbox", "沙盒"),
            Map.entry("Schedule", "任务调度"),
            Map.entry("ScheduledJob", "定时任务"),
            Map.entry("SearchIndex", "搜索索引"),
            Map.entry("Share", "公开分享"),
            Map.entry("Skill", "技能"),
            Map.entry("ToolInventory", "工具清单"),
            Map.entry("Trace", "执行追踪"),
            Map.entry("User", "用户"),
            Map.entry("Workflow", "工作流"),
            Map.entry("WorkflowApi", "工作流开放接口"));

    /**
     * 默认菜单归属对照：Controller 类名（去 Controller 后缀）→ 内置菜单 id 列表（支持多归属，
     * 如部门接口同时用于「成员管理」与「权限管理」）。菜单 id 见 {@code RbacSeedRunner.BUILTIN_MENUS}。
     * <p>仅作启动期登记的默认值，管理员可在权限页调整；归属只决定权限配置页的分组展示与勾选联动，
     * 不参与鉴权。未收录的 Controller（登录/通知/个人资产等）不归属，接口进「其他接口」分组。</p>
     */
    private static final Map<String, List<String>> DEFAULT_MENUS = Map.ofEntries(
            Map.entry("Agent", List.of("menu-agents")),
            Map.entry("ApiEndpoint", List.of("menu-permissions")),
            Map.entry("ApiKey", List.of("menu-settings")),
            Map.entry("Artifact", List.of("menu-artifacts")),
            Map.entry("Chat", List.of("menu-chat")),
            Map.entry("Config", List.of("menu-settings")),
            Map.entry("Credential", List.of("menu-agents")),
            Map.entry("Department", List.of("menu-members", "menu-permissions")),
            Map.entry("Document", List.of("menu-knowledge")),
            Map.entry("Evaluation", List.of("menu-evaluation")),
            Map.entry("Graph", List.of("menu-knowledge")),
            Map.entry("Knowledge", List.of("menu-knowledge")),
            Map.entry("KnowledgeBase", List.of("menu-knowledge")),
            Map.entry("Mcp", List.of("menu-agents")),
            Map.entry("Menu", List.of("menu-permissions")),
            Map.entry("Ocr", List.of("menu-settings")),
            Map.entry("Provider", List.of("menu-agents")),
            Map.entry("Qa", List.of("menu-dashboard")),
            Map.entry("RetrievalDebug", List.of("menu-evaluation")),
            Map.entry("Role", List.of("menu-permissions")),
            Map.entry("Schedule", List.of("menu-agents")),
            Map.entry("ScheduledJob", List.of("menu-agents")),
            Map.entry("SearchIndex", List.of("menu-settings")),
            Map.entry("Share", List.of("menu-agents")),
            Map.entry("Skill", List.of("menu-agents")),
            Map.entry("ToolInventory", List.of("menu-settings")),
            Map.entry("Trace", List.of("menu-dashboard")),
            Map.entry("User", List.of("menu-members")),
            Map.entry("Workflow", List.of("menu-agents")));

    /**
     * 显式构造而非 {@code @RequiredArgsConstructor}：
     * 容器里 {@code RequestMappingHandlerMapping} 不止一个（actuator 的 {@code controllerEndpointHandlerMapping}
     * 也是它的子类），必须按 bean 名限定。否则只能靠编译期 {@code -parameters} 保留形参名做按名兜底解析，
     * 一旦手工 javac 忘了加该参数（pom 里是 {@code <parameters>true</parameters>}），启动即歧义失败。
     */
    public ApiEndpointScanner(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping requestMappingHandlerMapping,
                              ApiEndpointMapper apiEndpointMapper,
                              MenuMapper menuMapper,
                              MenuApiMapper menuApiMapper,
                              RoleService roleService) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
        this.apiEndpointMapper = apiEndpointMapper;
        this.menuMapper = menuMapper;
        this.menuApiMapper = menuApiMapper;
        this.roleService = roleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            scan();
        } catch (Exception e) {
            log.warn("[ApiScan] 接口扫描失败（不阻塞启动，鉴权按库内既有数据执行）: {}", e.getMessage());
        }
    }

    void scan() {
        // 存量模块名归一：历史上登记的是英文类名键，按对照表改名（管理员已手工归类的值不会命中，保持不动）
        int renamed = 0;
        for (Map.Entry<String, String> e : MODULE_ZH.entrySet()) {
            renamed += apiEndpointMapper.renameModule(e.getKey(), e.getValue());
        }
        if (renamed > 0) log.info("[ApiScan] 模块名汉化：{} 个接口的模块改为中文", renamed);

        // 默认菜单归属的前提数据：现存菜单 id（退役/删除的菜单不可再建归属）与已有归属的接口 id（幂等判定）
        Set<String> validMenuIds = menuMapper.selectList(null).stream().map(Menu::getId).collect(Collectors.toSet());
        Set<String> boundApiIds = new HashSet<>(menuApiMapper.boundApiIds());

        Map<RequestMappingInfo, HandlerMethod> handlerMethods = requestMappingHandlerMapping.getHandlerMethods();
        int added = 0, total = 0;
        // 幂等参照：(method, path) → 已登记接口
        Map<String, ApiEndpoint> existing = new ConcurrentHashMap<>();
        for (ApiEndpoint a : apiEndpointMapper.selectList(null)) {
            existing.put(a.getMethod().toUpperCase() + " " + a.getPath(), a);
        }

        List<ApiEndpoint> toInsert = new ArrayList<>();
        // 默认菜单归属：新登记接口 → 插入成功后绑定；零归属内置接口 → 循环结束后幂等补齐
        Map<String, List<String>> newApiMenus = new HashMap<>();
        Map<String, List<String>> backfillMenus = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMethods.entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod hm = e.getValue();
            List<String> defMenus = resolveDefaultMenus(hm, validMenuIds);

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
                    ApiEndpoint existed = existing.get(key);
                    if (existed != null) {
                        // 幂等补齐：内置接口、当前零归属、命中默认对照 → 待绑定（已有任一归属则不动，尊重管理员调整）
                        if (!defMenus.isEmpty() && isBuiltin(existed) && !boundApiIds.contains(existed.getId())) {
                            backfillMenus.putIfAbsent(existed.getId(), defMenus);
                        }
                        continue;
                    }
                    ApiEndpoint api = new ApiEndpoint();
                    api.setId(UUID.randomUUID().toString().replace("-", ""));
                    api.setMethod(m);
                    api.setPath(pattern);
                    api.setName(resolveName(hm));
                    api.setModule(resolveModule(hm));
                    api.setBuiltin(1);
                    toInsert.add(api);
                    existing.put(key, api);
                    if (!defMenus.isEmpty()) newApiMenus.put(api.getId(), defMenus);
                }
            }
        }
        int bound = 0;
        for (ApiEndpoint api : toInsert) {
            try {
                apiEndpointMapper.insert(api);
                added++;
                for (String menuId : newApiMenus.getOrDefault(api.getId(), List.of())) {
                    bound += menuApiMapper.bind(menuId, api.getId());
                }
            } catch (Exception ex) {
                // 唯一键冲突等：忽略单条，保证其余登记成功
                log.debug("[ApiScan] 登记跳过 {} {}: {}", api.getMethod(), api.getPath(), ex.getMessage());
            }
        }
        int backfilled = 0;
        for (Map.Entry<String, List<String>> e : backfillMenus.entrySet()) {
            for (String menuId : e.getValue()) {
                backfilled += menuApiMapper.bind(menuId, e.getKey());
            }
        }
        if (added > 0) roleService.invalidateApiPatterns();
        if (backfilled > 0) log.info("[ApiScan] 默认菜单归属补齐：{} 个存量接口（此前零归属）", backfillMenus.size());
        log.info("[ApiScan] 接口扫描完成：命中 {} 个端点，新登记 {} 个，默认菜单归属 {} 条", total, added, bound);
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

    /** 模块：Controller 类名去 Controller 后缀，按 {@link #MODULE_ZH} 汉化（未收录回落英文键，管理员可在接口页手工归类） */
    private static String resolveModule(HandlerMethod hm) {
        String key = controllerKey(hm);
        return MODULE_ZH.getOrDefault(key, key);
    }

    /** 默认菜单归属：Controller 对照表 ∩ 现存菜单（未收录或菜单已退役 → 空，接口显示在「其他接口」分组） */
    private static List<String> resolveDefaultMenus(HandlerMethod hm, Set<String> validMenuIds) {
        List<String> menus = DEFAULT_MENUS.getOrDefault(controllerKey(hm), List.of());
        if (menus.isEmpty()) return List.of();
        return menus.stream().filter(validMenuIds::contains).toList();
    }

    /** Controller 键：类名去 Controller 后缀（模块汉化与菜单归属对照共用） */
    private static String controllerKey(HandlerMethod hm) {
        String n = hm.getBeanType().getSimpleName();
        return n.endsWith("Controller") ? n.substring(0, n.length() - "Controller".length()) : n;
    }

    private static boolean isBuiltin(ApiEndpoint api) {
        return api.getBuiltin() != null && api.getBuiltin() == 1;
    }
}
