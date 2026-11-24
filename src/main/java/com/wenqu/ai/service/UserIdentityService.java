package com.wenqu.ai.service;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.model.User;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TenantContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 在<b>后台线程</b>上按某个真实用户执行一段逻辑（身份 + 租户）。
 *
 * <p>框架把工具/团队生命周期派到自己的线程上，{@link RequestUser} 与 {@link TenantContext} 都是
 * ThreadLocal，不重放就会回落 SYSTEM：智能体可读性、知识库可见范围与租户行过滤全部按「无身份」判，
 * 表现从「明明有权限却查不到」到「团队成员悄悄没绑上智能体」都有。本类把「按 uid 装身份 + 绑租户 +
 * 用完复原」收在一处，后台路径不必各写一遍。
 *
 * <p>只做身份重放，不做授权放大：装载的角色就是该用户自己的角色，读到的资源仍走同一套可见性判定。
 */
@Component
public class UserIdentityService {

    private final UserMapper userMapper;
    private final UserRoleService userRoleService;

    public UserIdentityService(UserMapper userMapper, UserRoleService userRoleService) {
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
    }

    /** 以指定用户身份执行；用户不存在/已失效时 fail-loud，不静默按 SYSTEM 跑。 */
    public <T> T asUser(String uid, Supplier<T> body) {
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) {
            throw new BizException(403, "缺少有效用户，无法执行该后台操作");
        }
        // 先绑目标用户的租户再读用户行：后台线程可能带着上一份租户，用户表查询会按它过滤，
        // 正常用户会被判成「查无此人」。这里刻意不认线程上已绑定的值（可能是上一位用户的），
        // 而是按 uid 重新解析；解析完再把线程原值还回去。
        String tenant = resolveTenantFor(uid);
        return TenantContext.as(tenant, () -> {
            RequestUser.Snapshot s = snapshot(uid);
            if (s == null) {
                throw new BizException(403, "用户不存在或已失效：无法以该身份执行后台操作");
            }
            return RequestUser.callAs(s, body);
        });
    }

    /** 无返回值版本。 */
    public void asUser(String uid, Runnable body) {
        asUser(uid, () -> {
            body.run();
            return null;
        });
    }

    /** 按 uid 解析租户，不采信线程上可能残留的绑定值；解析期间临时解绑，结束后复原。 */
    private String resolveTenantFor(String uid) {
        String previous = TenantContext.boundTenant();
        TenantContext.bind(null);
        try {
            return TenantContext.resolveFor(uid);
        } finally {
            TenantContext.bind(previous);
        }
    }

    /** 用户身份快照；SYSTEM/空 uid/查无此人返回 null。 */
    public RequestUser.Snapshot snapshot(String uid) {
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) return null;
        User u = userMapper.selectById(uid);
        if (u == null) return null;
        List<String> roles = userRoleService.rolesOf(u.getUid());
        String role = roles == null || roles.isEmpty() ? RequestUser.SYSTEM : roles.get(0);
        return new RequestUser.Snapshot(u.getUid(), u.getDepartmentId(), role, roles);
    }
}