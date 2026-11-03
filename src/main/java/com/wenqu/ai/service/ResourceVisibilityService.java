package com.wenqu.ai.service;

import org.springframework.stereotype.Service;

/**
 * 资源可见性 / 权限解析器（谁建归谁）。
 * <p>
 * 2026-11-02 移除共享范围（share_config 已随功能删除）：资源一律私有——仅创建者可见可管；
 * 管理员级（含 superadmin）同样没有全量短路（无归属的历史数据已迁给 admin）。
 * <p>
 * 官方内置资源的「登录即可读」不经本类：手册库（builtin=1）走 {@link #canReadBuiltinKb}，
 * 内置智能体（isBuiltin=1）走 {@code AgentService.readable} 的内置豁免；
 * 管理侧入口（列表/编辑/删块）由各调用点按「内置资源仅管理员级」另行拦截。
 *
 * @author yuanke
 */
@Service
public class ResourceVisibilityService {

    /** 权限等级（ordinal 顺序用于比较大小） */
    public enum Permission { NONE, READ, MANAGE }

    /** 当前操作者抽象（从登录态 RequestUser 派生） */
    public record Principal(String uid, String departmentId, String role) {
        public static final Principal ANONYMOUS = new Principal("anonymous", null, "user");
        public boolean superadmin() { return "superadmin".equals(role); }
    }

    /**
     * 解析用户的<b>有效权限</b>：创建者 → MANAGE（短路）；其余一律 NONE（资源私有，谁建归谁）。
     */
    public Permission resolve(Principal p, String createdBy) {
        if (p == null) p = Principal.ANONYMOUS;
        if (createdBy != null && !createdBy.isBlank() && createdBy.equals(p.uid())) {
            return Permission.MANAGE; // 创建者短路
        }
        return Permission.NONE;
    }

    /** 当前用户是否能读取该资源（仅创建者；其余不可见） */
    public boolean canRead(Principal p, String createdBy) {
        return resolve(p, createdBy).ordinal() >= Permission.READ.ordinal();
    }

    /** 当前用户是否能管理该资源（仅创建者） */
    public boolean canManage(Principal p, String createdBy) {
        return resolve(p, createdBy) == Permission.MANAGE;
    }

    /**
     * 知识库<b>消费侧</b>可读判定（检索/对话/MCP 等登录后的消费入口）：
     * 官方内置库（builtin=1，如问渠使用手册）登录即可读——手册是随版本分发的系统资源，
     * 不是任何人的私有资产；其余库谁建归谁。
     * <p>管理侧入口（知识库页/文档管理/知识块管理）不经本方法：
     * 那里按「内置库仅管理员级可见」另行拦截（各控制器已有判定），两处口径刻意不同。
     */
    public boolean canReadBuiltinKb(Principal p, Integer builtin, String createdBy) {
        if (builtin != null && builtin == 1) return true;
        return canRead(p, createdBy);
    }
}
