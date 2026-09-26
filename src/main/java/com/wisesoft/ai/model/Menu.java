package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 菜单表（侧边栏数据源，按角色绑定下发）。
 * <p>
 * 内置菜单使用固定 id（如 {@code menu-chat}），自定义菜单由 MyBatis-Plus 生成 UUID
 * （{@code ASSIGN_UUID} 仅在 id 为空时自动填充，显式 id 原样保留）。
 * {@code visible=0} 的菜单不进侧边栏，但仍可作为权限归属（预留）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_menu")
public class Menu {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 父菜单ID（空=顶级） */
    private String parentId;

    /** 菜单名称 */
    private String name;

    /** 图标名（@ant-design/icons-vue 组件名，如 SettingOutlined；空=默认图标） */
    private String icon;

    /** 前端路由路径（如 /members） */
    private String path;

    /** 排序（小在前） */
    private Integer sortOrder;

    /** 是否显示: 1=显示, 0=隐藏 */
    private Integer visible;

    /** 内置菜单: 1=预置不可删除 */
    private Integer builtin;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
