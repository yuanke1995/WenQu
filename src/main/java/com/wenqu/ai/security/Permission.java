package com.wenqu.ai.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 端点的授权点归属声明（RBAC 升级方案批次 A，见 design/plans/RBAC升级方案.md §2 / 附录 A）。
 * <p>
 * 授权判定从「角色 → HTTP 端点」升级为「角色 → 授权点 → 端点」：角色绑定的是业务语义
 * （{@code kb:read} / {@code document:delete}），端点经本注解归属到唯一授权点。
 * 换 URL 不改权限配置、同一动作跨端点只配一次、deny 可以按语义粒度下。
 * <p>
 * 使用规则（附录 A.1/A.5）：
 * <ul>
 *   <li><b>方法级优先</b>：标在 handler 方法上；整个控制器同属一个授权点时才允许标在类上作默认值
 *       （方法级注解覆盖类级默认）；</li>
 *   <li><b>唯一归属</b>：一个端点恰好属于一个授权点——同一方法多路径必须同属一个点，
 *       冲突在启动自检时升级为失败；</li>
 *   <li>命名 {@code {domain}:{action}}，action ∈ read/write/delete/publish/export/import/run/approve/manage/admin/self；
 *       粒度按「会被独立授权的边界」切，不按 verb 机械切；</li>
 *   <li>公开通道（游客分享/SCIM/登录引导/沙盒回调等）<b>不标</b>本注解——它们走显式公开端点集，
 *       标了反而会被误当受控端点。</li>
 * </ul>
 * 扫描器（{@code ApiEndpointScanner}）启动时读取注解写入 {@code c_ai_api.permission_code}，
 * 并把新出现的授权点自动登记进目录表 {@code c_ai_permission}。
 *
 * @author yuanke
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Permission {

    /** 授权点编码（{domain}:{action}；扫描器据此写入 c_ai_api.permission_code） */
    String value();
}
