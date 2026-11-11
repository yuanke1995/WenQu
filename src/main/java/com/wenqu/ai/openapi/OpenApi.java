package com.wenqu.ai.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 {@code /api/ai/v1/**} 端点是「对外开放契约」的一部分。
 *
 * <p>路径与 HTTP 方法<b>不在这里写</b>：注册表启动期直接读 Spring 的
 * {@code RequestMappingHandlerMapping}，注解只描述契约里 Spring 表达不了的那几件事
 * （能力范围、摘要、是否允许 API Key 访问）。路径只有一份真源，注解和路由不可能漂移。</p>
 *
 * <p>未标注的 v1 端点在启动期直接失败（见 {@link OpenApiRegistry}），因此新增 v1 端点
 * 不会「悄悄自动对 API Key 开放」——默认拒绝，标注才可达。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpenApi {

    /** 调用该端点所需的 API Key scope；{@code keyAccessible=true} 时必填且必须是 {@link OpenApiScopes#ALL} 中的值 */
    String scope() default "";

    /** 契约摘要（进 OpenAPI 的 operation.summary） */
    String summary() default "";

    /** 是否允许持 API Key 的身份访问；false = 走别的通道（如文档端点、webhook 令牌） */
    boolean keyAccessible() default true;
}
