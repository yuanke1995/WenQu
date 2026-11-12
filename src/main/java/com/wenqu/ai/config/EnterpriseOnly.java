package com.wenqu.ai.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 企业档专属端点：{@code wenqu.deployment.profile=standard} 时直接 404。
 *
 * <p>只标明确的企业能力（多租户管理、SCIM、服务账号、审计等），不标登录、邀请、
 * 开通申请、首启引导这些标准档也要用的通道。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EnterpriseOnly {
}
