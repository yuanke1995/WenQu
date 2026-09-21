package com.wisesoft.wenqu.permissions;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.RequestUser;
import com.wisesoft.wenqu.common.SpringContext;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseDetail;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库领域权限校验的路由层适配，逐函数对齐参考实现 {@code server/utils/knowledge_permissions.py}。
 *
 * <p>两条语义与参考实现一致：
 * <ul>
 *   <li>先加载知识库，不存在 → 404 {@code 知识库 {kb_id} 不存在}；</li>
 *   <li>再校验资源权限（复用 {@link ResourcePermissions#requireKnowledgeBasePermission}），
 *       不足 → 403 {@code 无权操作该知识库}（参考实现的 ResourcePermissionDenied 不再外泄内部描述）。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：参考实现依赖注入 {@code Depends(get_admin_user)}，Java 侧改为
 * {@link AuthGuards#requireAdmin()} 前置调用；知识库运行时单例 {@code knowledge_base}
 * → {@link SpringContext#bean(Class)} 取容器内 {@link KnowledgeBaseManager}。
 */
public final class KnowledgePermissions {

    private KnowledgePermissions() {
    }

    /** 加载知识库并校验当前用户的有效资源权限（对应 ensure_knowledge_base_permission）。 */
    public static KnowledgeBaseDetail ensureKnowledgeBasePermission(String kbId, ResourcePermission required) {
        KnowledgeBaseManager manager = SpringContext.bean(KnowledgeBaseManager.class);
        // 参考实现 get_database_info(kb_id) 的 include_files 默认 False —— 权限判定不需要文件列表
        KnowledgeBaseDetail dbInfo = manager.getDatabaseInfo(kbId, false);
        if (dbInfo == null) {
            throw new ApiHttpException(404, "知识库 " + kbId + " 不存在");
        }
        try {
            ResourcePermissions.requireKnowledgeBasePermission(currentSubject(), shareable(dbInfo), required);
        } catch (ResourcePermissionDeniedException error) {
            throw new ApiHttpException(403, "无权操作该知识库");
        }
        return dbInfo;
    }

    /** 解析当前用户对指定知识库的有效权限（对应 {@code resolve_knowledge_base_permission(current_user, database)}）。 */
    public static ResourcePermission resolveKnowledgeBasePermission(KnowledgeBaseDetail detail) {
        return ResourcePermissions.resolveKnowledgeBasePermission(currentSubject(), shareable(detail));
    }

    /**
     * 校验管理员对指定知识库的读取权限（对应 require_knowledge_base_read：先 get_admin_user 再判读权限）。
     *
     * @return 当前用户 uid
     */
    public static String requireKnowledgeBaseRead(String kbId) {
        String uid = AuthGuards.requireAdmin();
        ensureKnowledgeBasePermission(kbId, ResourcePermission.READ);
        return uid;
    }

    /**
     * 校验管理员对指定知识库的管理权限（对应 require_knowledge_base_manage）。
     *
     * @return 当前用户 uid
     */
    public static String requireKnowledgeBaseManage(String kbId) {
        String uid = AuthGuards.requireAdmin();
        ensureKnowledgeBasePermission(kbId, ResourcePermission.MANAGE);
        return uid;
    }

    /** 当前请求者的权限主体视图（role/uid/department_id）。 */
    private static PermissionSubject currentSubject() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("uid", RequestUser.uid());
        source.put("role", RequestUser.role());
        source.put("department_id", RequestUser.departmentId());
        return PermissionSubject.ofMap(source);
    }

    /** 资源权限判定所需的共享配置视图（字段包装方式与 KnowledgeBaseManager 现有解析保持一致）。 */
    private static ShareableResource shareable(KnowledgeBaseDetail detail) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("created_by", detail.createdBy());
        source.put("share_config", new com.alibaba.fastjson2.JSONObject(detail.shareConfig()));
        return ShareableResource.ofMap(source);
    }
}
