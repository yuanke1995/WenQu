package com.wisesoft.wenqu.permissions;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 统一解析 Agent、Skill 与知识库的共享权限。
 *
 * <p>由参考实现的 permissions/resource_permission.py 逐函数翻译：
 * 共享范围规范化（global/department/user 三级、成员列表去重排序、越界拒绝）、
 * 管理范围必须含于读取范围、请求者命中范围判定、以及"所有权 → 共享范围 → 角色上限"
 * 三段式权限解析。
 *
 * <p>必要替换：Java 无 dataclass 与 frozenset，共享范围用 JSONObject 表示
 * （与参考实现的 dict 一一对应，键名照搬：access_level / department_ids / user_uids）。
 */
public final class ResourcePermissions {

    /** 默认共享范围：全局可见。 */
    public static final JSONObject DEFAULT_SCOPE = defaultScope();

    private static final Set<String> ACCESS_LEVELS = Set.of("global", "department", "user");

    private ResourcePermissions() {}

    private static JSONObject defaultScope() {
        JSONObject scope = new JSONObject();
        scope.put("access_level", "global");
        scope.put("department_ids", new JSONArray());
        scope.put("user_uids", new JSONArray());
        return scope;
    }

    /** 深拷贝一份默认共享范围（参考实现各处都用 {@code DEFAULT_SHARE_CONFIG.copy()}）。 */
    public static JSONObject newDefaultScope() {
        return DEFAULT_SCOPE.clone();
    }

    /** 规范化共享范围并校验其访问级别与成员列表。 */
    public static JSONObject normalizeScope(JSONObject scope) {
        if (scope == null) {
            return null;
        }
        String accessLevel = scope.getString("access_level");
        if (accessLevel == null || accessLevel.isEmpty()) {
            accessLevel = "global";
        }
        if (!ACCESS_LEVELS.contains(accessLevel)) {
            throw new IllegalArgumentException("无效的资源权限范围");
        }
        if ("global".equals(accessLevel)) {
            return newDefaultScope();
        }
        if ("department".equals(accessLevel)) {
            Set<Integer> departmentIds = new TreeSet<>();
            JSONArray raw = scope.getJSONArray("department_ids");
            if (raw != null) {
                for (Object value : raw) {
                    if (value != null) {
                        departmentIds.add(Integer.parseInt(String.valueOf(value)));
                    }
                }
            }
            if (departmentIds.isEmpty()) {
                throw new IllegalArgumentException("部门权限至少需要选择一个部门");
            }
            JSONObject normalized = new JSONObject();
            normalized.put("access_level", accessLevel);
            normalized.put("department_ids", new JSONArray(new java.util.ArrayList<>(departmentIds)));
            normalized.put("user_uids", new JSONArray());
            return normalized;
        }
        Set<String> userUids = new TreeSet<>();
        JSONArray raw = scope.getJSONArray("user_uids");
        if (raw != null) {
            for (Object value : raw) {
                String text = value == null ? "" : String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    userUids.add(text);
                }
            }
        }
        if (userUids.isEmpty()) {
            throw new IllegalArgumentException("指定用户权限至少需要选择一个用户");
        }
        JSONObject normalized = new JSONObject();
        normalized.put("access_level", accessLevel);
        normalized.put("department_ids", new JSONArray());
        normalized.put("user_uids", new JSONArray(new java.util.ArrayList<>(userUids)));
        return normalized;
    }

    /** 确保管理范围不会超出读取范围。 */
    public static void validateManageScope(JSONObject readScope, JSONObject manageScope) {
        if (readScope == null || manageScope == null || "global".equals(readScope.getString("access_level"))) {
            return;
        }
        String readLevel = readScope.getString("access_level");
        String manageLevel = manageScope.getString("access_level");
        if (!java.util.Objects.equals(manageLevel, readLevel)) {
            throw new IllegalArgumentException("管理范围必须包含在读取范围内");
        }
        if ("department".equals(readLevel)) {
            if (!idsOf(manageScope, "department_ids").containsAll(idsOf(readScope, "department_ids"))) {
                throw new IllegalArgumentException("管理范围必须包含在读取范围内");
            }
        } else if ("user".equals(readLevel)) {
            if (!stringsOf(manageScope, "user_uids").containsAll(stringsOf(readScope, "user_uids"))) {
                throw new IllegalArgumentException("管理范围必须包含在读取范围内");
            }
        }
    }

    private static Set<Integer> idsOf(JSONObject scope, String key) {
        Set<Integer> values = new LinkedHashSet<>();
        JSONArray raw = scope.getJSONArray(key);
        if (raw != null) {
            for (Object value : raw) {
                if (value != null) {
                    values.add(Integer.parseInt(String.valueOf(value)));
                }
            }
        }
        return values;
    }

    private static Set<String> stringsOf(JSONObject scope, String key) {
        Set<String> values = new LinkedHashSet<>();
        JSONArray raw = scope.getJSONArray(key);
        if (raw != null) {
            for (Object value : raw) {
                if (value != null) {
                    values.add(String.valueOf(value));
                }
            }
        }
        return values;
    }

    /** 规范化并校验 v2 共享配置。 */
    public static JSONObject normalizePermissionConfig(JSONObject shareConfig) {
        return normalizePermissionConfig(shareConfig, null, "当前用户无权使用该资源共享范围", false);
    }

    /** 规范化并校验 v2 共享配置（可限定允许的访问级别、可放宽越界校验）。 */
    public static JSONObject normalizePermissionConfig(
            JSONObject shareConfig,
            Collection<String> allowedAccessLevels,
            String unauthorizedAccessLevelMessage,
            boolean strict) {
        JSONObject config = shareConfig == null ? new JSONObject() : shareConfig;
        Object version = config.get("version");
        if (version instanceof Number number && number.intValue() == 2) {
            JSONObject readScope = normalizeScope(config.getJSONObject("read_scope"));
            JSONObject manageScope = normalizeScope(config.getJSONObject("manage_scope"));
            try {
                validateManageScope(readScope, manageScope);
            } catch (IllegalArgumentException exc) {
                if (strict) {
                    throw exc;
                }
                // 读取历史配置时保持原值；保存时由 strict 校验拒绝越界配置。
            }
            JSONObject normalized = new JSONObject();
            normalized.put("version", 2);
            normalized.put("read_scope", readScope);
            normalized.put("manage_scope", manageScope);
            if (allowedAccessLevels != null) {
                for (JSONObject scope : new JSONObject[] {readScope, manageScope}) {
                    if (scope != null && !allowedAccessLevels.contains(scope.getString("access_level"))) {
                        throw new IllegalArgumentException(unauthorizedAccessLevelMessage);
                    }
                }
            }
            return normalized;
        }
        throw new IllegalArgumentException("资源共享配置必须使用 version 2");
    }

    /** 判断用户是否命中一个共享范围。 */
    public static boolean scopeMatches(PermissionSubject user, JSONObject scope) {
        if (scope == null) {
            return false;
        }
        String accessLevel = scope.getString("access_level");
        if ("global".equals(accessLevel)) {
            return true;
        }
        if ("department".equals(accessLevel)) {
            Integer departmentId = user.departmentId();
            if (departmentId == null) {
                return false;
            }
            return idsOf(scope, "department_ids").contains(departmentId);
        }
        if ("user".equals(accessLevel)) {
            String uid = user.uid() == null ? "" : user.uid();
            return stringsOf(scope, "user_uids").contains(uid);
        }
        return false;
    }

    /** 解析资源所有权、共享范围和角色上限后的有效权限。 */
    public static ResourcePermission resolveResourcePermission(
            PermissionSubject user, ShareableResource resource, ResourcePermissionPolicy policy) {
        if ("superadmin".equals(user.role())) {
            return ResourcePermission.MANAGE;
        }
        JSONObject config = normalizePermissionConfig(resource.shareConfig(), null, "当前用户无权使用该资源共享范围", false);
        JSONObject shareConfig = config;

        ResourcePermission granted;
        if (java.util.Objects.equals(
                resource.createdBy() == null ? "" : resource.createdBy(), user.uid() == null ? "" : user.uid())) {
            return ResourcePermission.MANAGE;
        } else if (scopeMatches(user, shareConfig.getJSONObject("manage_scope"))
                && (shareConfig.getJSONObject("read_scope") == null
                        || scopeMatches(user, shareConfig.getJSONObject("read_scope")))) {
            granted = ResourcePermission.MANAGE;
        } else if (scopeMatches(user, shareConfig.getJSONObject("read_scope"))) {
            granted = ResourcePermission.READ;
        } else {
            granted = ResourcePermission.NONE;
        }

        return ResourcePermission.minimum(granted, policy.ceilingFor(user.role()));
    }

    /** 在权限不足时显式失败。 */
    public static void requireResourcePermission(ResourcePermission actual, ResourcePermission required) {
        if (actual.order() < required.order()) {
            throw new ResourcePermissionDeniedException(
                    "需要 " + required.value() + " 权限，当前为 " + actual.value());
        }
    }

    /** 解析知识库权限，普通用户最多只能获得只读权限。 */
    public static ResourcePermission resolveKnowledgeBasePermission(
            PermissionSubject user, ShareableResource resource) {
        return resolveResourcePermission(user, resource, ResourcePermissionPolicy.KNOWLEDGE_BASE);
    }

    /** 校验用户是否具备知识库所需权限，并返回实际权限。 */
    public static ResourcePermission requireKnowledgeBasePermission(
            PermissionSubject user, ShareableResource resource, ResourcePermission required) {
        ResourcePermission actual = resolveKnowledgeBasePermission(user, resource);
        requireResourcePermission(actual, required);
        return actual;
    }

    /** 解析 Agent 权限。 */
    public static ResourcePermission resolveAgentPermission(PermissionSubject user, ShareableResource resource) {
        return resolveResourcePermission(user, resource, ResourcePermissionPolicy.AGENT);
    }

    /** 解析 Skill 权限。 */
    public static ResourcePermission resolveSkillPermission(PermissionSubject user, ShareableResource resource) {
        if ("personal".equals(resource.sourceScope())) {
            if (java.util.Objects.equals(
                    resource.createdBy() == null ? "" : resource.createdBy(),
                    user.uid() == null ? "" : user.uid())) {
                return ResourcePermission.MANAGE;
            }
            return ResourcePermission.NONE;
        }
        return resolveResourcePermission(user, resource, ResourcePermissionPolicy.SKILL);
    }
}
