package com.wenqu.ai.controller;

import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.AuthService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 当前身份与权限（/auth/me）。
 * 从 ChatController 拆出：每个页面加载都要调它，与问答链路没有共享状态。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "身份与菜单", description = "当前登录身份、权限、租户与可见菜单树")
public class ChatIdentityController {

private final com.wenqu.ai.config.DeploymentGuard deploymentGuard;
private final com.wenqu.ai.service.TenantService tenantService;
private final AdminGuard adminGuard;
private final AuthService authService;
private final com.wenqu.ai.service.MenuService menuService;

    /**
     * 当前身份与权限（普通用户问答 UI 据此隐藏/显示管理入口；白名单端点，无需管理员即可调用）。
     * 响应追加 {@code menus}（当前角色可见的侧边栏菜单树，前端动态渲染导航）；
     * {@code admin} 按管理员级角色判定（含自定义 admin_flag=1 角色）。
     */
    @Operation(summary = "当前身份与权限", description = "返回当前登录用户（uid/username/role/departmentId）、是否管理员（admin）"
            + "与可见菜单树（menus，按角色绑定下发，前端侧边栏数据源）")
    @GetMapping("/auth/me")
    public ResultJson authMe(HttpServletRequest httpRequest) {
        Map<String, Object> me = authService.currentUser(RequestUser.uid(), adminGuard.isAdmin(httpRequest));
        me.put("menus", menuService.menusForUser(RequestUser.uid()));
        // 租户：随身份一起给，界面据此说一句「现在看的是哪个租户的数据」。
        // 刻意不另开 /tenant/mine 端点——那会多一处要给所有角色授权的面，而它说的还是同一件事
        String tenant = com.wenqu.ai.util.TenantContext.current();
        if (tenant == null || tenant.isBlank()) tenant = com.wenqu.ai.util.TenantContext.DEFAULT_TENANT;
        me.put("tenant", tenant);
        me.put("tenantName", tenantService.nameOf(tenant));
        me.put("profile", deploymentGuard.profile());
        // 平台级与否随身份一起下发：界面上「批准开通申请」这类入口要按它决定出不出现，
        // 让人点了才知道没权限是最差的一种提示
        me.put("superadmin", com.wenqu.ai.util.RequestUser.superadmin());
        return ResultJson.ok(me);
    }
}
