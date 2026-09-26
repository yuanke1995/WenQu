package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 接口表（RBAC 鉴权数据源）。
 * <p>
 * 主体由启动期 {@code ApiEndpointScanner} 扫描 Spring MVC 端点自动登记（{@code builtin=1}，
 * 按 method+path 幂等 {@code INSERT IGNORE}）；也允许手工登记非 Spring 端点或修正名称/模块。
 * 拦截器按「角色 → 绑定接口」做 AntPathMatcher 匹配（path 支持占位符如 {@code /api/ai/agent/{id}}）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_api")
public class ApiEndpoint {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** HTTP 方法（大写；ALL=任意方法） */
    private String method;

    /** 接口路径（应用内路径，如 /api/ai/agent/{id}） */
    private String path;

    /** 接口名称（展示用；扫描登记时优先取 @Operation summary） */
    private String name;

    /** 所属模块（Controller 分组，可编辑） */
    private String module;

    /** 扫描登记: 1=启动期自动登记 */
    private Integer builtin;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
