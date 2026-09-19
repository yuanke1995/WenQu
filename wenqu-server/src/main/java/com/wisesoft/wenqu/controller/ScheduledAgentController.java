package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.ScheduledAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户 Agent 定时任务路由，逐端点对齐参考实现 {@code server/routers/scheduled_agent_router.py}。
 *
 * <p>前缀 {@code /api/scheduled-tasks}（参考实现 {@code prefix="/scheduled-tasks"}，本工程统一
 * 挂 {@code /api}）。五个端点：列表 / 创建 / 更新 / 立即运行 / 删除。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()} 取 uid，再经
 *       {@link UserRepository#getByUid} 装载用户实体（照 {@code AgentRunService} 既有先例；
 *       参考实现的该依赖本身即完成「认证 + 装载」两步）。</li>
 *   <li>{@code Depends(get_db)} → 服务层各自的事务边界（{@link ScheduledAgentService} 内部用
 *       自管事务模板），控制器不持有会话。</li>
 * </ul>
 *
 * <h3>请求校验：pydantic → 控制器层显式校验</h3>
 * <p>参考实现的三个请求模型都带 {@code model_config = ConfigDict(extra="forbid")}，字段约束分四类：
 * 必填（{@code Field(...)}）、长度上限（{@code max_length}）、长度下限（{@code min_length}）、
 * 正则（{@code pattern}）。本工程沿用既有控制器口径（{@link SkillController} /
 * {@link KnowledgeEvalController}）：在控制器层逐条显式校验并抛 <b>422</b>，{@code loc} 为
 * {@code ["body", <字段名>]}、{@code type} 用 pydantic 的错误码名，与 FastAPI 的响应形状对齐；
 * <b>不静默忽略</b>。
 *
 * <p>{@code extra="forbid"} 同样显式实现（多余键 → 422 {@code extra_forbidden}）——Jackson 默认
 * 忽略未知字段，若照默认则「多传字段」会静默通过，与参考实现语义相反。
 *
 * <p>{@link #updateJob} 额外照搬 {@code ScheduledAgentUpdate.reject_null_enabled}：显式传
 * {@code "enabled": null} → 422。该 validator 的存在是刻意的——服务层
 * {@code AgentRunService.truthy(null)} 会把 null 解释为 {@code false}，不拦就会「静默停用任务」。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/scheduled-tasks")
@RequiredArgsConstructor
@Tag(name = "scheduled-tasks", description = "用户 Agent 定时任务")
public class ScheduledAgentController {

    // ==================== pydantic 字段约束（逐字对齐） ====================

    /** {@code request_id: Field(..., min_length=8, max_length=64, pattern=r"^[A-Za-z0-9._:-]+$")}。 */
    private static final int REQUEST_ID_MIN_LENGTH = 8;
    private static final int REQUEST_ID_MAX_LENGTH = 64;
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    /** {@code name: Field(..., max_length=255)}。 */
    private static final int NAME_MAX_LENGTH = 255;

    /** {@code project_id: Field(..., max_length=64)}。 */
    private static final int PROJECT_ID_MAX_LENGTH = 64;

    /** {@code agent_slug: Field(..., max_length=64)}。 */
    private static final int AGENT_SLUG_MAX_LENGTH = 64;

    /** {@code prompt: Field(..., max_length=32_000)}。 */
    private static final int PROMPT_MAX_LENGTH = 32_000;

    /** {@code cron_expression: Field(..., max_length=100)}。 */
    private static final int CRON_EXPRESSION_MAX_LENGTH = 100;

    /** {@code timezone: Field(..., max_length=64)}。 */
    private static final int TIMEZONE_MAX_LENGTH = 64;

    /** {@code tool_approval_mode: Field("default", max_length=32)}。 */
    private static final int TOOL_APPROVAL_MODE_MAX_LENGTH = 32;
    private static final String DEFAULT_TOOL_APPROVAL_MODE = "default";

    /** {@code model_spec: Field(None, max_length=512)}。 */
    private static final int MODEL_SPEC_MAX_LENGTH = 512;

    /** {@code ScheduledAgentCreate} 的字段全集（{@code extra="forbid"} 的白名单）。 */
    private static final Set<String> CREATE_FIELDS = Set.of(
            "request_id",
            "name",
            "project_id",
            "agent_slug",
            "prompt",
            "cron_expression",
            "timezone",
            "tool_approval_mode",
            "model_spec",
            "enabled");

    /** {@code ScheduledAgentUpdate} 的字段全集（无 {@code request_id}：更新不改请求 ID）。 */
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "name",
            "project_id",
            "agent_slug",
            "prompt",
            "cron_expression",
            "timezone",
            "tool_approval_mode",
            "model_spec",
            "enabled");

    /** {@code ScheduledAgentRunNow} 的字段全集。 */
    private static final Set<String> RUN_NOW_FIELDS = Set.of("request_id");

    private final ScheduledAgentService scheduledAgentService;
    private final UserRepository userRepository;

    // ==================== 端点（5） ====================

    /** 列出当前用户的定时 Agent（对应 {@code list_jobs}）。 */
    @Operation(summary = "列出当前用户的定时 Agent")
    @GetMapping
    public Map<String, Object> listJobs() {
        User user = requireCurrentUser();
        return scheduledAgentService.listScheduledJobs(user);
    }

    /** 创建一个用户自有的定时 Agent（对应 {@code create_job}）。 */
    @Operation(summary = "创建定时 Agent", description = "body 见 ScheduledAgentCreate；extra 字段报 422")
    @PostMapping
    public Map<String, Object> createJob(@RequestBody(required = false) Map<String, Object> body) {
        User user = requireCurrentUser();
        requireBody(body);
        rejectUnknownFields(body, CREATE_FIELDS);

        // pydantic 的 model_dump() 是「全字段」快照：未传的键也带默认值进入服务层。
        String requestId = requireString(body, "request_id", REQUEST_ID_MAX_LENGTH);
        requireRequestIdShape(requestId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", requestId);
        data.put("name", requireString(body, "name", NAME_MAX_LENGTH));
        data.put("project_id", requireString(body, "project_id", PROJECT_ID_MAX_LENGTH));
        data.put("agent_slug", requireString(body, "agent_slug", AGENT_SLUG_MAX_LENGTH));
        data.put("prompt", requireString(body, "prompt", PROMPT_MAX_LENGTH));
        data.put("cron_expression", requireString(body, "cron_expression", CRON_EXPRESSION_MAX_LENGTH));
        data.put("timezone", requireString(body, "timezone", TIMEZONE_MAX_LENGTH));
        data.put("tool_approval_mode",
                optionalString(body, "tool_approval_mode", TOOL_APPROVAL_MODE_MAX_LENGTH,
                        DEFAULT_TOOL_APPROVAL_MODE));
        data.put("model_spec", optionalString(body, "model_spec", MODEL_SPEC_MAX_LENGTH, null));
        data.put("enabled", optionalBoolean(body, "enabled", Boolean.TRUE));

        return scheduledAgentService.createScheduledJob(user, data);
    }

    /** 更新定时 Agent（对应 {@code update_job}）。 */
    @Operation(summary = "更新定时 Agent",
            description = "body 见 ScheduledAgentUpdate（exclude_unset 语义）；enabled 不得为 null（422）")
    @PatchMapping("/{job_id}")
    public Map<String, Object> updateJob(
            @PathVariable("job_id") String jobId,
            @RequestBody(required = false) Map<String, Object> body) {
        User user = requireCurrentUser();
        requireBody(body);
        rejectUnknownFields(body, UPDATE_FIELDS);

        // pydantic 的 model_dump(exclude_unset=True)：仅显式传入的键进入服务层。
        Map<String, Object> data = new LinkedHashMap<>();
        if (body.containsKey("name")) {
            data.put("name", requireString(body, "name", NAME_MAX_LENGTH));
        }
        if (body.containsKey("project_id")) {
            data.put("project_id", requireString(body, "project_id", PROJECT_ID_MAX_LENGTH));
        }
        if (body.containsKey("agent_slug")) {
            data.put("agent_slug", requireString(body, "agent_slug", AGENT_SLUG_MAX_LENGTH));
        }
        if (body.containsKey("prompt")) {
            data.put("prompt", requireString(body, "prompt", PROMPT_MAX_LENGTH));
        }
        if (body.containsKey("cron_expression")) {
            data.put("cron_expression", requireString(body, "cron_expression", CRON_EXPRESSION_MAX_LENGTH));
        }
        if (body.containsKey("timezone")) {
            data.put("timezone", requireString(body, "timezone", TIMEZONE_MAX_LENGTH));
        }
        if (body.containsKey("tool_approval_mode")) {
            data.put("tool_approval_mode",
                    optionalString(body, "tool_approval_mode", TOOL_APPROVAL_MODE_MAX_LENGTH, null));
        }
        if (body.containsKey("model_spec")) {
            data.put("model_spec", optionalString(body, "model_spec", MODEL_SPEC_MAX_LENGTH, null));
        }
        if (body.containsKey("enabled")) {
            // 对应 reject_null_enabled（mode="before"）：显式 null 直接 422，绝不静默停用。
            if (body.get("enabled") == null) {
                throw validationError("enabled", "enabled 不得为 null");
            }
            data.put("enabled", optionalBoolean(body, "enabled", null));
        }

        Map<String, Object> result = scheduledAgentService.updateScheduledJob(jobId, user, data);
        if (result == null) {
            throw ApiHttpException.notFound("定时任务不存在");
        }
        return result;
    }

    /** 立即按任务快照创建一次独立 Conversation 和 AgentRun（对应 {@code run_now}）。 */
    @Operation(summary = "立即运行定时 Agent", description = "按稳定 request_id 幂等")
    @PostMapping("/{job_id}/run-now")
    public Map<String, Object> runNow(
            @PathVariable("job_id") String jobId,
            @RequestBody(required = false) Map<String, Object> body) {
        User user = requireCurrentUser();
        requireBody(body);
        rejectUnknownFields(body, RUN_NOW_FIELDS);

        String requestId = requireString(body, "request_id", REQUEST_ID_MAX_LENGTH);
        requireRequestIdShape(requestId);

        Map<String, Object> result = scheduledAgentService.runScheduledJobNow(jobId, requestId, user);
        if (result == null) {
            throw ApiHttpException.notFound("定时任务不存在");
        }
        return result;
    }

    /** 删除定时 Agent 定义（对应 {@code delete_job}）。 */
    @Operation(summary = "删除定时 Agent")
    @DeleteMapping("/{job_id}")
    public Map<String, Object> deleteJob(@PathVariable("job_id") String jobId) {
        User user = requireCurrentUser();
        if (!scheduledAgentService.deleteScheduledJob(jobId, user)) {
            throw ApiHttpException.notFound("定时任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", true);
        result.put("job_id", jobId);
        return result;
    }

    // ==================== 校验辅助（pydantic 等价） ====================

    /** 取当前用户实体（参考实现 {@code current_user: User = Depends(get_required_user)}）。 */
    private User requireCurrentUser() {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw ApiHttpException.notFound("用户不存在");
        }
        return user;
    }

    /**
     * 请求体必填校验。
     *
     * <p>参考实现这些端点的请求体形参都是必填的 pydantic 模型，完全不传请求体时 FastAPI 返回
     * 422 {@code Field required}；本工程用 {@code @RequestBody(required = false)} 接住再显式判空，
     * 与字段级校验共用同一份 422 响应体形状。
     */
    private static void requireBody(Map<String, Object> body) {
        if (body == null) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of("loc", List.of("body"), "msg", "Field required", "type", "missing")));
        }
    }

    /** {@code extra="forbid"}：多余字段 → 422（FastAPI 的 {@code extra_forbidden}）。 */
    private static void rejectUnknownFields(Map<String, Object> body, Set<String> allowed) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw ApiHttpException.objectDetail(
                        422,
                        "请求参数校验失败",
                        List.of(Map.of(
                                "loc", List.of("body", key),
                                "msg", "Extra inputs are not permitted",
                                "type", "extra_forbidden")));
            }
        }
    }

    /** 请求校验失败 → 422（{@code loc} 为 {@code ["body", <字段名>]}，与 FastAPI 一致）。 */
    private static ApiHttpException validationError(String field, String message) {
        return ApiHttpException.objectDetail(
                422,
                "请求参数校验失败",
                List.of(Map.of(
                        "loc", List.of("body", field),
                        "msg", message,
                        "type", "value_error")));
    }

    /** 对应 pydantic 的必填 {@code str} 字段（{@code Field(...)}）：缺失 → missing，类型不符 → string_type。 */
    private static String requireString(Map<String, Object> body, String field, int maxLength) {
        if (!body.containsKey(field) || body.get(field) == null) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of(
                            "loc", List.of("body", field),
                            "msg", "Field required",
                            "type", "missing")));
        }
        return checkString(body.get(field), field, maxLength);
    }

    /** 对应 pydantic 的 {@code str | None = 默认值} 字段。 */
    private static Object optionalString(
            Map<String, Object> body, String field, int maxLength, String defaultValue) {
        if (!body.containsKey(field)) {
            return defaultValue;
        }
        Object value = body.get(field);
        if (value == null) {
            return null;
        }
        return checkString(value, field, maxLength);
    }

    /** {@code str} 类型 + 长度上限（pydantic {@code string_type} / {@code string_too_long}）。 */
    private static String checkString(Object value, String field, int maxLength) {
        if (!(value instanceof String text)) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of(
                            "loc", List.of("body", field),
                            "msg", "Input should be a valid string",
                            "type", "string_type")));
        }
        if (text.length() > maxLength) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of(
                            "loc", List.of("body", field),
                            "msg", "String should have at most " + maxLength + " characters",
                            "type", "string_too_long")));
        }
        return text;
    }

    /**
     * {@code request_id} 的 {@code min_length} + {@code pattern} 约束。
     *
     * <p>长度下限与正则在 pydantic 里是两条独立错误，报错码分别是
     * {@code string_too_short} 与 {@code string_pattern_mismatch}，此处按同序校验。
     */
    private static void requireRequestIdShape(String value) {
        if (value.length() < REQUEST_ID_MIN_LENGTH) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of(
                            "loc", List.of("body", "request_id"),
                            "msg", "String should have at least " + REQUEST_ID_MIN_LENGTH + " characters",
                            "type", "string_too_short")));
        }
        if (!REQUEST_ID_PATTERN.matcher(value).matches()) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of(
                            "loc", List.of("body", "request_id"),
                            "msg", "String should match pattern '^[A-Za-z0-9._:-]+$'",
                            "type", "string_pattern_mismatch")));
        }
    }

    /**
     * 对应 pydantic 的 {@code bool} 字段（含其宽松解析），{@code defaultValue} 用于键缺失时。
     *
     * <p>宽松解析照搬 pydantic v2 的 {@code bool} 可接受字面量集合（true/false、1/0、
     * yes/no、on/off，大小写不敏感）；其余字面量报 {@code bool_parsing}。
     */
    private static Object optionalBoolean(Map<String, Object> body, String field, Boolean defaultValue) {
        if (!body.containsKey(field)) {
            return defaultValue;
        }
        Object value = body.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            int intValue = number.intValue();
            if (intValue == 1) {
                return Boolean.TRUE;
            }
            if (intValue == 0) {
                return Boolean.FALSE;
            }
        } else if (value instanceof String text) {
            String normalized = text.strip().toLowerCase();
            if (Set.of("true", "1", "yes", "on").contains(normalized)) {
                return Boolean.TRUE;
            }
            if (Set.of("false", "0", "no", "off").contains(normalized)) {
                return Boolean.FALSE;
            }
        }
        throw ApiHttpException.objectDetail(
                422,
                "请求参数校验失败",
                List.of(Map.of(
                        "loc", List.of("body", field),
                        "msg", "Input should be a valid boolean",
                        "type", "bool_parsing")));
    }
}
