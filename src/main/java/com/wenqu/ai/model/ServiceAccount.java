package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务账号（批次 C3，方案 design/plans/rbac-upgrade-plan.md §3 批次 C3）：
 * 「非人类身份」——其它系统长期调用管理面 / 内部服务互调用的独立账号。
 * <ul>
 *   <li>凭据：{@code X-Service-Token} 头带明文密钥，库中只存 SHA-256（泄露即吊销重建，不解密回显）；</li>
 *   <li>权限：角色绑定复用 {@code c_ai_user_role}（uid = {@code svc:{id}}，granted_by='service'）——
 *       授权点判定、deny、TTL/广播失效、成员反查全套机制原样生效，不另起炉灶；</li>
 *   <li>边界：不在 {@code c_ai_user} 表里，登录链路（JWT/密码）天然进不来，不能登录网页；</li>
 *   <li>范围：{@code /api/ai/v1/workflows}、{@code /ai/mcp} 的 API Key 通道与沙盒回调凭据各自独立，本表不替代。</li>
 * </ul>
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_service_account")
public class ServiceAccount {

    /** 主键（UUID）；角色绑定行用 {@code svc:{id}} 作为 uid 挂 c_ai_user_role */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 归属租户（建号时创建人的租户）。
     * <p>表本身不参与行级过滤（令牌校验发生在租户还没装载的那一刻），但角色绑定落在
     * {@code c_ai_user_role} 那张<b>按租户过滤</b>的表里——所以令牌校验后必须按这一列把租户上下文装上，
     * 否则非默认租户建出来的服务账号读自己的绑定时查不到行，等于零角色。
     * 界面上的归属守门也看它：清单按它过滤，改/删/轮换跨租户一律拒。
     */
    private String tenantId;

    /** 名称（展示与审计可读） */
    private String name;

    /** 说明（用途/负责人，便于交接） */
    private String description;

    /** 令牌 SHA-256（hex，64 位；明文只在创建时返回一次） */
    private String tokenHash;

    /** 状态: 1=启用, 0=停用（停用即全部调用 401） */
    private Integer status;

    /** 到期时刻（NULL=永久；与角色 expire_at 同口径，到期自动回收） */
    private LocalDateTime expireAt;

    /** 创建人（交接线索） */
    private String createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
