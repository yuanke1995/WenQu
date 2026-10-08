package com.wenqu.ai.model;

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

    /** 前端路由路径（如 /members）。tab 型可空（跳转由父页 ?tab= 承担）；group 型必须为空 */
    private String path;

    /**
     * 渲染位置：一条菜单记录不再假设只活在侧栏里，由该字段声明它在哪渲染。
     * <ul>
     *   <li>{@code sidebar} — 侧栏可点入口（历史默认值，存量行缺列时按此理解）；</li>
     *   <li>{@code tab} — 父页面内的 Tab 项（必须有 parentId；不出现在侧栏）；</li>
     *   <li>{@code group} — 侧栏分组标题（不可点、无 path，纯视觉归类）；</li>
     *   <li>{@code hidden} — 纯权限容器（接口归属/角色绑定的挂载点，任何 UI 不渲染）。</li>
     * </ul>
     * 权限语义四种完全一致：可见性仍由 visible + 角色绑定决定，渲染位置只管「画在哪」。
     */
    private String renderAs;

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
