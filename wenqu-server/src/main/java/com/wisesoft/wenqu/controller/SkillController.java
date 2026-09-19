package com.wisesoft.wenqu.controller;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.agents.ResolvedSkill;
import com.wisesoft.wenqu.agents.SkillRemoteInstall;
import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.UrlQuote;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.Skill;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.permissions.ResourcePermissions;
import com.wisesoft.wenqu.permissions.ShareableResource;
import com.wisesoft.wenqu.repositories.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Skills 管理路由，逐端点对齐参考实现 {@code server/routers/skill_router.py}
 * （参考实现声明两个 router：{@code skills = /system/skills} 与 {@code user_skills = /skills}，
 * 聚合后对外为 {@code /api/system/skills} 与 {@code /api/skills}）。
 *
 * <p><b>平台差异（必要替换，已标注）</b>：参考实现的两个 router 在同一个 Python 模块里，
 * 本工程照「一个模块 → 一个类」的约定放在同一个 {@code @RestController} 内，
 * 因此类级不写 {@code @RequestMapping}，每个方法声明完整路径
 * （Spring 不允许一个类挂两个前缀；若用 {@code @RequestMapping({"a","b"})} 会凭空
 * 产生 {@code /api/system/skills/accessible} 这类参考实现不存在的路径）。
 *
 * <p>26 个端点（与对拍前端 {@code web/src/apis/skill_api.js} 的 26 处调用一一对应）：
 * <pre>
 * GET    /api/skills                                   Skill 卡片列表（登录用户）
 * GET    /api/skills/accessible                        可访问 Skill 列表（登录用户）
 * POST   /api/skills/import/prepare                    上传 zip/SKILL.md 解析草稿（登录用户，multipart）
 * POST   /api/skills/remote/list                       远程来源技能清单（登录用户）
 * POST   /api/skills/remote/search                     远程技能搜索（登录用户）
 * POST   /api/skills/remote/prepare                    远程安装草稿（登录用户）
 * POST   /api/skills/install-drafts/{draft_id}/confirm 确认安装草稿（管理员）
 * POST   /api/skills/personal/install-drafts/{id}/confirm 确认个人安装草稿（登录用户）
 * GET    /api/skills/personal/{slug}/file              读个人 Skill 文本文件（登录用户）
 * DELETE /api/skills/personal/{slug}                   删除个人 Skill（登录用户）
 * DELETE /api/skills/install-drafts/{draft_id}         丢弃安装草稿（登录用户）
 * GET    /api/system/skills                            可见 Skill 管理列表（登录用户）
 * GET    /api/system/skills/dependency-options         Skill 依赖选项（登录用户）
 * GET    /api/system/skills/builtin                    内置 skill 列表（管理员）
 * POST   /api/system/skills/builtin/sync                同步内置 skill（管理员）
 * PUT    /api/system/skills/{slug}/share-config        更新共享范围（登录用户）
 * PUT    /api/system/skills/{slug}/enabled             启用开关（登录用户）
 * GET    /api/system/skills/{slug}/tree                目录树（登录用户）
 * GET    /api/system/skills/{slug}/file                读技能文件（登录用户）
 * POST   /api/system/skills/{slug}/file                新建文件/目录（登录用户）
 * PUT    /api/system/skills/{slug}/file                更新文件内容（登录用户）
 * PUT    /api/system/skills/{slug}/dependencies        更新依赖（登录用户）
 * DELETE /api/system/skills/{slug}/file                删除文件/目录（登录用户）
 * GET    /api/system/skills/{slug}/export              导出 zip（登录用户，二进制流）
 * DELETE /api/system/skills/{slug}                     删除技能（登录用户）
 * POST   /api/system/skills/delete-batch               批量删除（登录用户）
 * </pre>
 *
 * <p>响应契约照搬：成功体为参考实现的普通字典（<b>不是</b>本产品既有 {@code ResultJson}）——
 * {@code {"success": true, "data": ...}}；卡片/管理列表额外带 {@code allowed_access_levels}；
 * 草稿确认与批量删除额外带 {@code summary}；纯操作端点只返回 {@code {"success": true}}。
 * 失败体由 {@link ApiHttpException} → {@code {"detail": ...}}。
 *
 * <h3>状态码映射（照搬 {@code _raise_from_value_error}）</h3>
 * {@code ValueError} → 文案含「不存在」或「无权」则 404，否则 400；其余异常 → 500。
 * 参考实现未声明 {@code extra="forbid"} 的请求模型，故额外字段被静默忽略（pydantic v2 默认
 * {@code extra="ignore"}），本工程同样忽略；必填/类型/长度校验在方法内显式做并抛 422
 * （与 {@code KnowledgeEvalController}、{@code McpController} 的既有标注一致）：
 * <ul>
 *   <li><b>请求体缺失</b>：参考实现的请求体形参都是必填 Pydantic 模型 → 422；本工程用
 *       {@code @RequestBody(required=false)} 接住后由 {@link #requireBody} 显式判空并返回同款 422。
 *   <li><b>字段级</b>：缺失/类型不符 → 422「field required / Input should be a valid …」。
 *   <li><b>空串是合法值</b>：参考实现的 {@code str} 接受 {@code ""}，由服务层判定
 *       （{@code path 不能为空} / {@code source 不能为空} → <b>400</b>）；
 *       因此 {@code content: ""}（保存空文件）也必须放行。
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "skills", description = "Skill 管理（卡片、文件树、依赖、共享范围、导入导出）")
public class SkillController {

    /** {@code SkillBatchDeleteRequest.slugs} 的 {@code max_length=50} → 超限 422。 */
    private static final int BATCH_DELETE_MAX_ITEMS = 50;

    private final SkillService skillService;
    private final SkillRemoteInstall skillRemoteInstall;
    private final UserRepository userRepository;

    // =========================================================================
    // === user_skills（前缀 /api/skills） ===
    // =========================================================================

    /** Skill 卡片列表（管理页所需）。 */
    @Operation(summary = "Skill 卡片列表", description = "响应 {success,data,allowed_access_levels}")
    @GetMapping("/api/skills")
    public Map<String, Object> listSkillCardsRoute(
            @RequestParam(value = "refresh_personal", required = false) String refreshPersonal) {
        User currentUser = requireUser();
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (ResolvedSkill item : skillService.listSkillCardsForUser(currentUser)) {
                data.add(serializeResolvedSkillForUser(item, currentUser));
            }
            Map<String, Object> response = successWithData(data);
            response.put("allowed_access_levels", SkillService.getAllowedSkillAccessLevels(currentUser));
            return response;
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to list Skill cards: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取 Skill 列表失败");
        }
    }

    /** 当前用户可访问的 Skill 列表。 */
    @Operation(summary = "可访问 Skill 列表", description = "响应 {success,data}")
    @GetMapping("/api/skills/accessible")
    public Map<String, Object> listAccessibleSkillsRoute() {
        User currentUser = requireUser();
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (ResolvedSkill item : skillService.listAccessibleSkills(currentUser, true)) {
                data.add(serializeResolvedSkillForUser(item, currentUser));
            }
            return successWithData(data);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to list accessible skills: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取可访问 Skills 失败");
        }
    }

    /** 上传 zip / SKILL.md 解析为安装草稿。 */
    @Operation(summary = "解析上传 Skill", description = "multipart: file；响应 {success,data}")
    @PostMapping("/api/skills/import/prepare")
    public Map<String, Object> prepareSkillUploadRoute(@RequestParam("file") MultipartFile file) {
        User currentUser = requireUser();
        try {
            String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            JSONObject data = skillService.prepareSkillUpload(filename, file.getBytes(), currentUser);
            return successWithData(data);
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to prepare skill upload: {}", exc.getMessage());
            throw new ApiHttpException(500, "解析上传 Skill 失败");
        }
    }

    /** 远程来源技能清单。 */
    @Operation(summary = "远程技能清单", description = "请求 {source}；响应 {success,data}")
    @PostMapping("/api/skills/remote/list")
    public Map<String, Object> listRemoteSkillsRoute(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        requireUser();
        String source = requireString(body, "source");
        Map<String, Object> normalized = body == null ? Map.of() : body;
        try {
            return successWithData(skillRemoteInstall.listRemoteSkills(source));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to list remote skills from '{}': {}", normalized.get("source"), exc.getMessage());
            throw new ApiHttpException(500, "获取远程 skills 列表失败");
        }
    }

    /** 远程技能搜索。 */
    @Operation(summary = "搜索远程技能", description = "请求 {query}；响应 {success,data}")
    @PostMapping("/api/skills/remote/search")
    public Map<String, Object> searchRemoteSkillsRoute(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        requireUser();
        String query = requireString(body, "query");
        try {
            return successWithData(skillRemoteInstall.searchRemoteSkills(query));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to search remote skills with query '{}': {}", query, exc.getMessage());
            throw new ApiHttpException(500, "搜索远程 skills 失败");
        }
    }

    /** 远程安装草稿。 */
    @Operation(summary = "远程安装草稿", description = "请求 {source,skills}；响应 {success,data}")
    @PostMapping("/api/skills/remote/prepare")
    public Map<String, Object> prepareRemoteSkillsRoute(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        String source = requireString(body, "source");
        List<String> skills = requireStringList(body, "skills", true);
        try {
            JSONObject data = skillService.prepareRemoteSkillInstall(source, skills, currentUser);
            return successWithData(data);
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to prepare remote skills from '{}': {}", source, exc.getMessage());
            throw new ApiHttpException(500, "解析远程 Skills 失败");
        }
    }

    /** 确认安装草稿（管理员）。 */
    @Operation(summary = "确认安装草稿", description = "请求 {share_config,slugs}；响应 {success,data,summary}")
    @PostMapping("/api/skills/install-drafts/{draft_id}/confirm")
    public Map<String, Object> confirmSkillInstallDraftRoute(
            @PathVariable("draft_id") String draftId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireAdmin();
        JSONObject shareConfig = optionalObject(body, "share_config");
        List<String> slugs = optionalStringList(body, "slugs");
        try {
            List<JSONObject> results = skillService.confirmSkillInstallDraft(draftId, shareConfig, slugs, currentUser);
            Map<String, Object> response = successWithData(results);
            response.put("summary", summarizeResults(results));
            return response;
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to confirm skill install draft '{}': {}", draftId, exc.getMessage());
            throw new ApiHttpException(500, "确认安装 Skill 失败");
        }
    }

    /** 确认个人安装草稿。 */
    @Operation(summary = "确认个人安装草稿", description = "请求 {slugs}；响应 {success,data,summary}")
    @PostMapping("/api/skills/personal/install-drafts/{draft_id}/confirm")
    public Map<String, Object> confirmPersonalSkillInstallDraftRoute(
            @PathVariable("draft_id") String draftId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        List<String> slugs = optionalStringList(body, "slugs");
        try {
            List<JSONObject> results = skillService.confirmPersonalSkillInstallDraft(draftId, slugs, currentUser);
            Map<String, Object> response = successWithData(results);
            response.put("summary", summarizeResults(results));
            return response;
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to confirm personal Skill draft '{}': {}", draftId, exc.getMessage());
            throw new ApiHttpException(500, "确认安装个人 Skill 失败");
        }
    }

    /** 读个人 Skill 文本文件。 */
    @Operation(summary = "读个人 Skill 文件", description = "query: path；响应 {success,data}")
    @GetMapping("/api/skills/personal/{slug}/file")
    public Map<String, Object> readPersonalSkillFileRoute(
            @PathVariable("slug") String slug, @RequestParam("path") String path) {
        User currentUser = requireUser();
        try {
            return successWithData(
                    skillService.readPersonalSkillFile(String.valueOf(currentUser.getUid()), slug, path));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to read personal Skill file '{}/{}': {}", slug, path, exc.getMessage());
            throw new ApiHttpException(500, "读取个人 Skill 文件失败");
        }
    }

    /** 删除个人 Skill。 */
    @Operation(summary = "删除个人 Skill", description = "响应 {success:true}")
    @DeleteMapping("/api/skills/personal/{slug}")
    public Map<String, Object> deletePersonalSkillRoute(@PathVariable("slug") String slug) {
        User currentUser = requireUser();
        try {
            skillService.deletePersonalSkill(String.valueOf(currentUser.getUid()), slug);
            return successOnly();
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to delete personal Skill '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "删除个人 Skill 失败");
        }
    }

    /** 丢弃安装草稿。 */
    @Operation(summary = "丢弃安装草稿", description = "响应 {success:true}")
    @DeleteMapping("/api/skills/install-drafts/{draft_id}")
    public Map<String, Object> discardSkillInstallDraftRoute(@PathVariable("draft_id") String draftId) {
        User currentUser = requireUser();
        try {
            skillService.discardSkillInstallDraft(draftId, currentUser);
            return successOnly();
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to discard skill install draft '{}': {}", draftId, exc.getMessage());
            throw new ApiHttpException(500, "取消安装 Skill 失败");
        }
    }

    // =========================================================================
    // === skills（前缀 /api/system/skills） ===
    // =========================================================================

    /** 可见 Skill 管理列表。 */
    @Operation(summary = "可见 Skill 管理列表", description = "响应 {success,data,allowed_access_levels}")
    @GetMapping("/api/system/skills")
    public Map<String, Object> listSkillsRoute() {
        User currentUser = requireUser();
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (Skill item : skillService.listVisibleSkillsForManagement(currentUser)) {
                data.add(serializeSkillForUser(item, currentUser));
            }
            Map<String, Object> response = successWithData(data);
            response.put("allowed_access_levels", SkillService.getAllowedSkillAccessLevels(currentUser));
            return response;
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to list manageable skills: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取技能列表失败");
        }
    }

    /** Skill 依赖选项。 */
    @Operation(summary = "Skill 依赖选项", description = "query: slug；响应 {success,data}")
    @GetMapping("/api/system/skills/dependency-options")
    public Map<String, Object> getSkillDependencyOptionsRoute(
            @RequestParam(value = "slug", required = false) String slug) {
        User currentUser = requireUser();
        try {
            if (slug != null && !slug.isEmpty()) {
                skillService.getManageableSkillOrRaise(currentUser, slug);
            }
            return successWithData(skillService.getSkillDependencyOptions(currentUser, slug));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get skill dependency options: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取 skill 依赖选项失败");
        }
    }

    /** 内置 skill 列表（管理员）。 */
    @Operation(summary = "内置 skill 列表", description = "响应 {success,data}")
    @GetMapping("/api/system/skills/builtin")
    public Map<String, Object> listBuiltinSkillsRoute() {
        requireAdmin();
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (Skill item : skillService.listSkills()) {
                if ("builtin".equals(item.getSourceType())) {
                    data.add(SkillService.skillToDict(item));
                }
            }
            return successWithData(data);
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to list builtin skills: {}", exc.getMessage());
            throw new ApiHttpException(500, "获取内置 skill 列表失败");
        }
    }

    /** 同步内置 skill（管理员）。 */
    @Operation(summary = "同步内置 skill", description = "响应 {success,data}")
    @PostMapping("/api/system/skills/builtin/sync")
    public Map<String, Object> syncBuiltinSkillsRoute() {
        User currentUser = requireAdmin();
        try {
            List<Map<String, Object>> data = new ArrayList<>();
            for (Skill item : skillService.initBuiltinSkills(currentUser.getUid())) {
                data.add(SkillService.skillToDict(item));
            }
            return successWithData(data);
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to sync builtin skills: {}", exc.getMessage());
            throw new ApiHttpException(500, "同步内置 skill 失败");
        }
    }

    /** 更新共享范围。 */
    @Operation(summary = "更新 Skill 共享范围", description = "请求 {share_config}；响应 {success,data}")
    @PutMapping("/api/system/skills/{slug}/share-config")
    public Map<String, Object> updateSkillShareConfigRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        JSONObject shareConfig = optionalObject(body, "share_config");
        try {
            Skill item = skillService.updateSkillShareConfig(slug, shareConfig, currentUser);
            return successWithData(serializeSkillForUser(item, currentUser));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to update skill share config '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "更新 Skill 共享范围失败");
        }
    }

    /** 启用开关。 */
    @Operation(summary = "更新 Skill 启用状态", description = "请求 {enabled}；响应 {success,data}")
    @PutMapping("/api/system/skills/{slug}/enabled")
    public Map<String, Object> updateSkillEnabledRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        if (body == null || !body.containsKey("enabled")) {
            throw validationError("body", "enabled", "field required");
        }
        boolean enabled = requireBoolean(body, "enabled");
        try {
            Skill item = skillService.updateSkillEnabled(slug, enabled, currentUser);
            return successWithData(serializeSkillForUser(item, currentUser));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to update skill enabled '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "更新 Skill 启用状态失败");
        }
    }

    /** 目录树。 */
    @Operation(summary = "获取技能目录树", description = "响应 {success,data}")
    @GetMapping("/api/system/skills/{slug}/tree")
    public Map<String, Object> getSkillTreeRoute(@PathVariable("slug") String slug) {
        User currentUser = requireUser();
        try {
            return successWithData(skillService.getSkillTree(slug, currentUser));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get skill tree '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "获取技能目录树失败");
        }
    }

    /** 读技能文件。 */
    @Operation(summary = "读取技能文件", description = "query: path；响应 {success,data}")
    @GetMapping("/api/system/skills/{slug}/file")
    public Map<String, Object> getSkillFileRoute(
            @PathVariable("slug") String slug, @RequestParam("path") String path) {
        User currentUser = requireUser();
        try {
            return successWithData(skillService.readSkillFile(slug, path, currentUser));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to read skill file '{}/{}': {}", slug, path, exc.getMessage());
            throw new ApiHttpException(500, "读取技能文件失败");
        }
    }

    /** 新建文件/目录。 */
    @Operation(summary = "创建技能文件", description = "请求 {path,is_dir,content}；响应 {success:true}")
    @PostMapping("/api/system/skills/{slug}/file")
    public Map<String, Object> createSkillFileRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        String path = requireString(body, "path");
        boolean isDir = optionalBoolean(body, "is_dir", false);
        String content = optionalString(body, "content", "");
        try {
            skillService.createSkillNode(slug, path, isDir, content, currentUser.getUid(), currentUser);
            return successOnly();
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to create skill node '{}/{}': {}", slug, path, exc.getMessage());
            throw new ApiHttpException(500, "创建技能文件失败");
        }
    }

    /** 更新文件内容。 */
    @Operation(summary = "更新技能文件", description = "请求 {path,content}；响应 {success:true}")
    @PutMapping("/api/system/skills/{slug}/file")
    public Map<String, Object> updateSkillFileRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        String path = requireString(body, "path");
        String content = requireString(body, "content");
        try {
            skillService.updateSkillFile(slug, path, content, currentUser.getUid(), currentUser);
            return successOnly();
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to update skill file '{}/{}': {}", slug, path, exc.getMessage());
            throw new ApiHttpException(500, "更新技能文件失败");
        }
    }

    /** 更新依赖。 */
    @Operation(summary = "更新 skill 依赖", description = "请求 {tool_dependencies,mcp_dependencies,skill_dependencies}")
    @PutMapping("/api/system/skills/{slug}/dependencies")
    public Map<String, Object> updateSkillDependenciesRoute(
            @PathVariable("slug") String slug, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        List<String> toolDependencies = optionalStringListDefault(body, "tool_dependencies");
        List<String> mcpDependencies = optionalStringListDefault(body, "mcp_dependencies");
        List<String> skillDependencies = optionalStringListDefault(body, "skill_dependencies");
        try {
            Skill item = skillService.updateSkillDependencies(
                    slug, toolDependencies, mcpDependencies, skillDependencies, currentUser);
            return successWithData(serializeSkillForUser(item, currentUser));
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to update skill dependencies '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "更新 skill 依赖失败");
        }
    }

    /** 删除文件/目录。 */
    @Operation(summary = "删除技能文件", description = "query: path；响应 {success:true}")
    @DeleteMapping("/api/system/skills/{slug}/file")
    public Map<String, Object> deleteSkillFileRoute(
            @PathVariable("slug") String slug, @RequestParam("path") String path) {
        User currentUser = requireUser();
        try {
            skillService.deleteSkillNode(slug, path, currentUser);
            return successOnly();
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to delete skill file '{}/{}': {}", slug, path, exc.getMessage());
            throw new ApiHttpException(500, "删除技能文件失败");
        }
    }

    /** 导出 zip（二进制流）。 */
    @Operation(summary = "导出技能", description = "响应 application/zip 附件")
    @GetMapping("/api/system/skills/{slug}/export")
    public ResponseEntity<StreamingResponseBody> exportSkillRoute(@PathVariable("slug") String slug) {
        User currentUser = requireUser();
        SkillService.ExportResult exported;
        try {
            exported = skillService.exportSkillZip(slug, currentUser);
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to export skill '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "导出技能失败");
        }

        Path exportPath = Path.of(exported.path());
        StreamingResponseBody body = outputStream -> {
            try (InputStream input = Files.newInputStream(exportPath)) {
                input.transferTo(outputStream);
            } finally {
                // 对应参考实现 BackgroundTasks.add_task(_cleanup_export_file, export_path)
                try {
                    Files.deleteIfExists(exportPath);
                } catch (IOException cleanupError) {
                    log.warn("Failed to cleanup exported skill archive '{}': {}",
                            exportPath, cleanupError.getMessage());
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + UrlQuote.quote(exported.downloadName(), "/"))
                .body(body);
    }

    /** 删除技能。 */
    @Operation(summary = "删除技能", description = "响应 {success:true}")
    @DeleteMapping("/api/system/skills/{slug}")
    public Map<String, Object> deleteSkillRoute(@PathVariable("slug") String slug) {
        User currentUser = requireUser();
        try {
            skillService.deleteSkill(slug, currentUser);
            return successOnly();
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to delete skill '{}': {}", slug, exc.getMessage());
            throw new ApiHttpException(500, "删除技能失败");
        }
    }

    /** 批量删除。 */
    @Operation(summary = "批量删除技能", description = "请求 {slugs}（≤50）；响应 {success,data,summary}")
    @PostMapping("/api/system/skills/delete-batch")
    public Map<String, Object> deleteSkillsBatchRoute(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        User currentUser = requireUser();
        List<String> slugs = requireStringList(body, "slugs", true);
        if (slugs.size() > BATCH_DELETE_MAX_ITEMS) {
            throw validationError("body", "slugs", "List should have at most 50 items after validation");
        }
        try {
            List<JSONObject> results = skillService.deleteSkillsBatch(slugs, currentUser);
            Map<String, Object> response = successWithData(results);
            response.put("summary", summarizeResults(results));
            return response;
        } catch (IllegalArgumentException exc) {
            throw fromValueError(exc);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to delete skills batch: {}", exc.getMessage());
            throw new ApiHttpException(500, "批量删除技能失败");
        }
    }

    // =========================================================================
    // === 辅助（模块级函数 _raise_from_value_error / _summarize_results / _serialize_skill_for_user）
    // =========================================================================

    /** {@code _raise_from_value_error}：含「不存在」或「无权」→ 404，否则 400。 */
    private static ApiHttpException fromValueError(RuntimeException exc) {
        String message = exc.getMessage() == null ? "" : exc.getMessage();
        int statusCode = (message.contains("不存在") || message.contains("无权")) ? 404 : 400;
        return new ApiHttpException(statusCode, message);
    }

    /** {@code _summarize_results}。 */
    private static Map<String, Object> summarizeResults(List<JSONObject> results) {
        int success = 0;
        for (JSONObject item : results) {
            if (Boolean.TRUE.equals(item.getBoolean("success"))) {
                success++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", results.size());
        summary.put("success", success);
        summary.put("failed", results.size() - success);
        return summary;
    }

    /** {@code _serialize_skill_for_user}（数据库实体版本）。 */
    private static Map<String, Object> serializeSkillForUser(Skill item, User user) {
        Map<String, Object> data = new LinkedHashMap<>(SkillService.skillToDict(item));
        data.put("can_manage", SkillService.userCanManageSkill(user, item));
        data.put(
                "effective_permission",
                ResourcePermissions.resolveSkillPermission(PermissionSubject.of(user), ShareableResource.of(item))
                        .value());
        data.put("is_builtin", SkillService.isBuiltinSkill(item));
        return data;
    }

    /**
     * {@code _serialize_skill_for_user}（{@link ResolvedSkill} 版本）。
     *
     * <p>能力差异（已标注）：参考实现把 {@link ResolvedSkill} 与数据库实体都塞给同一个
     * {@code _serialize_skill_for_user}（Python 鸭子类型，靠 {@code item.to_dict()} 与
     * {@code user_can_manage_skill}/{@code is_builtin_skill} 对二者都成立）；
     * Java 静态类型下拆成两个重载，输出的键集合与判定口径完全一致。
     */
    private static Map<String, Object> serializeResolvedSkillForUser(ResolvedSkill item, User user) {
        Map<String, Object> data = new LinkedHashMap<>(item.toDict());
        data.put("can_manage", SkillService.userCanManageSkill(user, item));
        data.put(
                "effective_permission",
                ResourcePermissions.resolveSkillPermission(PermissionSubject.of(user), item).value());
        data.put("is_builtin", SkillService.isBuiltinSkill(item));
        return data;
    }

    /** 解析登录用户（对应 {@code Depends(get_required_user)}）。 */
    private User requireUser() {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** 解析管理员用户（对应 {@code Depends(get_admin_user)}）。 */
    private User requireAdmin() {
        String uid = AuthGuards.requireAdmin();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(401, "请登录后再访问", Map.of("WWW-Authenticate", "Bearer"));
        }
        return user;
    }

    /** {@code {"success": true, "data": ...}}。 */
    private static Map<String, Object> successWithData(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }

    /** {@code {"success": true}}。 */
    private static Map<String, Object> successOnly() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        return response;
    }

    /**
     * 请求体必填校验。
     *
     * <p>参考实现这些端点的请求体形参都是必填的 Pydantic 模型
     * （如 {@code payload: RemoteSkillSourceRequest}），<b>完全不传请求体</b>时 FastAPI 返回
     * 422「Field required」。本工程用 {@code @RequestBody(required = false)} 接住再显式判空，
     * 以便与字段级校验共用同一份 422 响应体（{@code loc} 为 {@code ["body"]}、{@code type=missing}），
     * 也避免依赖 Spring 在缺体时抛出的 {@code HttpMessageNotReadableException} 文案。
     */
    private static void requireBody(Map<String, Object> body) {
        if (body == null) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of("loc", List.of("body"), "msg", "Field required", "type", "missing")));
        }
    }

    /**
     * 请求校验失败 → 422（对应 pydantic 的请求体校验错误）。
     *
     * <p>{@code loc} 固定为 {@code ["body", <字段名>]}，与 FastAPI 对请求体字段的报错位置一致。
     */
    private static ApiHttpException validationError(String location, String field, String message) {
        return ApiHttpException.objectDetail(
                422,
                "请求参数校验失败",
                List.of(Map.of("loc", List.of(location, field), "msg", message, "type", "value_error")));
    }

    /**
     * 对应 pydantic 的必填 {@code str} 字段（{@code Field(...)}）。
     *
     * <p>缺失 → 422「field required」；类型不是字符串 → 422。
     * <b>空串是合法取值</b>：参考实现里 {@code str} 接受 {@code ""}，随后由服务层自行判定
     * （{@code path 不能为空} / {@code source 不能为空} → <b>400</b>；
     * {@code searchRemoteSkills("")} 直接返回空列表），故此处不得拦截空串，否则会
     * 把参考实现的 400 变成 422，且会让「保存空文件」（{@code content: ""}）被误拒。
     */
    private String requireString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            throw validationError("body", field, "field required");
        }
        if (value instanceof String text) {
            return text;
        }
        throw validationError("body", field, "Input should be a valid string");
    }

    /** 可选 {@code str | None} 字段：缺失/显式 null 取默认值；非字符串 → 422（pydantic v2 不做隐式转换）。 */
    private static String optionalString(Map<String, Object> body, String field, String defaultValue) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String text) {
            return text;
        }
        throw validationError("body", field, "Input should be a valid string");
    }

    /** 可选 {@code bool} 字段：缺失取默认值；无法解析为布尔 → 422。 */
    private static boolean optionalBoolean(Map<String, Object> body, String field, boolean defaultValue) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return defaultValue;
        }
        return parseBoolean(value, field);
    }

    private boolean requireBoolean(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return parseBoolean(value, field);
    }

    /** {@code bool} 解析（Boolean 直取；字符串仅接受 true/false，与 pydantic 的报错口径一致 → 422）。 */
    private static boolean parseBoolean(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw validationError("body", field, "Input should be a valid boolean");
    }

    private static JSONObject optionalObject(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject object) {
            return object;
        }
        if (value instanceof Map<?, ?> map) {
            JSONObject result = new JSONObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        throw validationError("body", field, "Input should be a valid dictionary");
    }

    private static List<String> optionalStringList(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        return toStringList(value, field);
    }

    private static List<String> optionalStringListDefault(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return new ArrayList<>();
        }
        return toStringList(value, field);
    }

    private List<String> requireStringList(Map<String, Object> body, String field, boolean required) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            if (required) {
                throw validationError("body", field, "field required");
            }
            return new ArrayList<>();
        }
        return toStringList(value, field);
    }

    private static List<String> toStringList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw validationError("body", field, "Input should be a valid list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw validationError("body", field, "Input should be a valid string");
            }
            result.add(text);
        }
        return result;
    }
}
