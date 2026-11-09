package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.AuditEventMapper;
import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.ModelInfoMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.AuditEvent;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新租户首登引导。
 *
 * <p>五项状态全部按真实数据计算，不做「点过就算完成」的本地标记：
 * 改密看审计（本人是否有过一次「修改密码」事件）、建部门/邀请成员/建知识库看本租户行数、
 * 绑定模型看平台是否有可用聊天模型。单租户部署不出现（前端按 tenant != default 判断）。
 *
 * @author yuanke
 */
@Service
@RequiredArgsConstructor
public class TenantOnboardingService {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ModelInfoMapper modelInfoMapper;
    private final AuditEventMapper auditEventMapper;

    public Map<String, Object> status(String uid) {
        String tenant = TenantContext.current();
        boolean required = tenant != null && !tenant.isBlank()
                && !TenantContext.DEFAULT_TENANT.equals(tenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("required", required);
        if (!required) {
            out.put("done", true);
            return out;
        }
        boolean passwordChanged = false;
        try {
            Long n = auditEventMapper.selectCount(new LambdaQueryWrapper<AuditEvent>()
                    .eq(AuditEvent::getActorUid, uid)
                    .eq(AuditEvent::getAction, "修改密码"));
            passwordChanged = n != null && n > 0;
        } catch (Exception ignored) {
        }
        long members = countSafely(() -> userMapper.selectCount(new LambdaQueryWrapper<User>()), 0);
        boolean dept = countSafely(() -> departmentMapper.selectCount(null), 0) > 0;
        boolean kb = countSafely(() -> knowledgeBaseMapper.selectCount(null), 0) > 0;
        boolean model = countSafely(() -> modelInfoMapper.selectCount(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getModelType, "chat")), 0) > 0;
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("password", passwordChanged);
        items.put("department", dept);
        items.put("member", members > 1);
        items.put("model", model);
        items.put("kb", kb);
        int done = (int) items.values().stream().filter(Boolean.TRUE::equals).count();
        out.put("items", items);
        out.put("done", done >= 5);
        out.put("completed", done);
        out.put("total", 5);
        return out;
    }

    private static long countSafely(java.util.function.Supplier<Long> q, long fallback) {
        try {
            Long n = q.get();
            return n == null ? fallback : n;
        } catch (Exception e) {
            return fallback;
        }
    }
}
