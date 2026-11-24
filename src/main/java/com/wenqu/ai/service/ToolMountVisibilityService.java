package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AgentMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.Workflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工具装配期的资源可见性复核（工作流工具 / HTTP 工具共用）。
 * <p>为什么要独立一层：{@code WorkflowToolService}/{@code HttpToolService} 以前直接
 * {@code mapper.selectById} 就算「配置存在」，但市场安装时的一次性裁剪只是 UX——装完之后
 * 归属、角色范围、启停都会变，运行期必须按<b>本轮触发者</b>重算。
 * <p>口径与消费路径一致：创建者 ∪ 角色数据范围只读；内置/平台预置/平台共享智能体豁免；
 * 游客分享的 token 已是授权，身份按发布者装载，不套登录户口径。
 * <p>刻意不注入 {@code AgentService}：它已经注入 HTTP/工作流工具服务，直接互注会成环。
 * 这里复用它的静态判据与 {@link PlatformAgentLookup} 只读取口。
 */
@Service
@RequiredArgsConstructor
public class ToolMountVisibilityService {

    private final AgentMapper agentMapper;
    private final PlatformAgentLookup platformAgentLookup;
    private final ResourceVisibilityService visibility;
    private final UserMapper userMapper;
    private final UserRoleService userRoleService;

    /** mount 期重新取智能体：平台固定 id 兜底、停用拒绝、游客沿用 token 授权。 */
    public Agent agentForMount(String agentId, String userId, boolean guest) {
        if (agentId == null || agentId.isBlank()) return null;
        Agent a = agentMapper.selectById(agentId);
        if (a == null && PlatformAgentLookup.isShared(agentId)) a = platformAgentLookup.find(agentId);
        if (a == null || AgentService.isDisabled(a)) return null;
        if (guest) return a;   // token 即授权，见 AgentShareService.resolveGuest
        if (Integer.valueOf(1).equals(a.getIsBuiltin())
                || AgentService.isPlatformPreset(a)
                || AgentService.isPlatformSharedAgent(a)) return a;
        return visibility.canRead(principalOf(userId), a.getCreatedBy()) ? a : null;
    }

    /** 工作流工具引用的运行期可读判据：共享 READ 通过，严格 owner 相等不通过。 */
    public boolean canReadWorkflow(String userId, Workflow workflow) {
        if (workflow == null) return false;
        if (workflow.getUid() == null || workflow.getUid().isBlank()) return true; // 历史无归属行按可用处理
        return visibility.canRead(principalOf(userId), workflow.getUid());
    }

    /** 与 WorkflowToolService.principalOf 同源：uid + 部门 + 角色，不依赖 ThreadLocal。 */
    private ResourceVisibilityService.Principal principalOf(String uid) {
        try {
            var p = WorkflowService.Principal.ofUser(uid, userMapper, userRoleService);
            return new ResourceVisibilityService.Principal(p.uid(), p.departmentId(), p.role());
        } catch (Exception e) {
            return new ResourceVisibilityService.Principal(uid, null, null);
        }
    }
}
