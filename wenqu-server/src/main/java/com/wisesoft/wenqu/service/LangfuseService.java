package com.wisesoft.wenqu.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Langfuse tracing 辅助。
 *
 * <p>由参考实现的 services/langfuse_service.py 逐函数翻译：trace 元数据/标签/运行上下文构造、
 * trace 信息摘要、用户反馈评分提交、trace URL 惰性解析、刷新。
 *
 * <p>能力差异（SDK 缺位，注明）：
 * <ul>
 *   <li>参考实现 import langfuse（可缺省依赖），缺包时 {@code Langfuse = None}，全部客户端
 *       调用路径自动短路（is_langfuse_enabled 返回 False、client 为 None、评分提交返回
 *       False、URL 解析返回 None）。本工程的 Java 依赖中无 Langfuse SDK，运行形态与
 *       「未安装 langfuse 包的参考实现」完全一致：纯逻辑函数全部生效，客户端函数按同一
 *       短路路径返回。
 *   <li>若后续接入 Langfuse（REST 或 SDK），只需替换 {@link #getLangfuseClient()} 的返回并
 *       在 create_score / create_trace_id / get_trace_url 三个调用点补齐实现，其余逻辑不动。
 *   <li>产品命名：source 元数据、标签前缀与 score_id 前缀均使用本产品命名；
 *       默认源站 {@code https://cloud.langfuse.com} 照搬。
 * </ul>
 */
@Service
public class LangfuseService {

    private static final Logger log = LoggerFactory.getLogger(LangfuseService.class);

    private static final Set<String> FALSE_VALUES = Set.of("0", "false", "no", "off");
    private static final String DEFAULT_LANGFUSE_BASE_URL = "https://cloud.langfuse.com";

    /** Langfuse 客户端占位：SDK 缺位时恒为 null（参考实现 Langfuse = None 的等价状态）。 */
    private static final Object LANGFUSE_CLIENT = null;

    /** 一次 Agent 运行的 tracing 上下文（callbacks/metadata/tags/trace_id）。 */
    public record LangfuseRunContext(
            List<Object> callbacks, Map<String, Object> metadata, List<String> tags, String traceId) {

        public static LangfuseRunContext empty(Map<String, Object> metadata, List<String> tags) {
            return new LangfuseRunContext(new ArrayList<>(), metadata, tags, null);
        }
    }

    /** 是否启用 Langfuse：显式关闭 > SDK 可用性 > 公私钥配置；SDK 缺位时恒为 False。 */
    public boolean isLangfuseEnabled() {
        String enabledRaw = envOrNull("LANGFUSE_ENABLED");
        if (enabledRaw == null) {
            enabledRaw = "true";
        }
        if (FALSE_VALUES.contains(enabledRaw.strip().toLowerCase())) {
            return false;
        }

        // 参考实现此处检查 Langfuse/CallbackHandler 是否可 import；本工程无 SDK，恒为 False
        if (LANGFUSE_CLIENT == null) {
            return false;
        }

        return envOrNull("LANGFUSE_PUBLIC_KEY") != null && !envOrNull("LANGFUSE_PUBLIC_KEY").isEmpty()
                && envOrNull("LANGFUSE_SECRET_KEY") != null && !envOrNull("LANGFUSE_SECRET_KEY").isEmpty();
    }

    /**
     * 获取 Langfuse 客户端（参考实现 lru_cache(maxsize=1)）；未启用时返回 null。
     *
     * <p>SDK 缺位：恒返回 null，与参考实现未安装 langfuse 包时一致。
     */
    public Object getLangfuseClient() {
        if (!isLangfuseEnabled()) {
            return null;
        }
        // SDK 接入点：new Langfuse(publicKey, secretKey, host)，初始化失败时告警并返回 null
        return null;
    }

    /** 构造 trace 元数据（键集与顺序照搬）。 */
    public Map<String, Object> buildTraceMetadata(
            String userId,
            String threadId,
            String agentId,
            String requestId,
            String operation,
            String backendId,
            String messageType,
            String username,
            String loginUserId,
            Object departmentId,
            Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("langfuse_user_id", userId);
        metadata.put("langfuse_session_id", threadId);
        metadata.put("request_id", requestId);
        metadata.put("thread_id", threadId);
        metadata.put("agent_id", agentId);
        metadata.put("operation", operation);
        metadata.put("source", "wenqu");
        metadata.put("feature", "chat");

        if (backendId != null && !backendId.isEmpty()) {
            metadata.put("backend_id", backendId);
        }
        if (messageType != null && !messageType.isEmpty()) {
            metadata.put("message_type", messageType);
        }
        if (username != null && !username.isEmpty()) {
            metadata.put("username", username);
        }
        if (loginUserId != null && !loginUserId.isEmpty()) {
            metadata.put("login_user_id", loginUserId);
        }
        if (departmentId != null) {
            metadata.put("department_id", String.valueOf(departmentId));
        }
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        return metadata;
    }

    /** 构造 trace 标签。 */
    public List<String> buildTraceTags(String agentId, String operation, String messageType, List<String> extraTags) {
        List<String> tags = new ArrayList<>();
        tags.add("wenqu");
        tags.add("chat");
        tags.add(operation);
        tags.add("agent:" + agentId);
        if (messageType != null && !messageType.isEmpty()) {
            tags.add("message_type:" + messageType);
        }
        for (String tag : extraTags == null ? List.<String>of() : extraTags) {
            if (tag != null && !tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }

    /** 组装运行上下文；无客户端时只带 metadata/tags（与参考实现短路分支一致）。 */
    public LangfuseRunContext buildRunContext(
            String userId,
            String threadId,
            String agentId,
            String requestId,
            String operation,
            String backendId,
            String messageType,
            String username,
            String loginUserId,
            Object departmentId,
            Map<String, Object> extraMetadata,
            List<String> extraTags) {
        Map<String, Object> metadata = buildTraceMetadata(
                userId,
                threadId,
                agentId,
                requestId,
                operation,
                backendId,
                messageType,
                username,
                loginUserId,
                departmentId,
                extraMetadata);
        List<String> tags = buildTraceTags(agentId, operation, messageType, extraTags);

        Object client = getLangfuseClient();
        if (client == null) {
            return LangfuseRunContext.empty(metadata, tags);
        }

        // SDK 接入点：trace_id = client.create_trace_id(seed=request_id)；
        // handler = CallbackHandler(trace_context={"trace_id": trace_id})
        String traceId = null;
        List<Object> callbacks = new ArrayList<>();
        return new LangfuseRunContext(callbacks, metadata, tags, traceId);
    }

    /** 提取 trace 摘要信息；无 trace_id 时返回空 Map。 */
    public Map<String, Object> getTraceInfo(LangfuseRunContext runContext) {
        if (runContext == null) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> metadata = runContext.metadata() == null ? new LinkedHashMap<>() : runContext.metadata();
        String traceId = runContext.traceId();
        if ((traceId == null || traceId.isEmpty()) && runContext.callbacks() != null && !runContext.callbacks().isEmpty()) {
            // SDK 接入点：trace_id = getattr(callbacks[0], "last_trace_id", None)
            traceId = null;
        }

        if (traceId == null || traceId.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> traceInfo = new LinkedHashMap<>();
        traceInfo.put("langfuse_trace_id", traceId);
        traceInfo.put("langfuse_user_id", metadata.get("langfuse_user_id"));
        traceInfo.put("langfuse_session_id", metadata.get("langfuse_session_id"));

        // 不在请求关键路径上取 trace_url：Langfuse 解析 project id 需要远程 API 调用，
        // 基址慢或不可达时会带来可感延迟。确需 URL 时用 getTraceUrlById 异步补写消息元数据。
        return traceInfo;
    }

    /** 提交用户反馈评分；客户端缺位或失败返回 False（保留本地反馈）。 */
    public boolean submitUserFeedbackScore(
            String traceId,
            int feedbackId,
            int messageId,
            int conversationId,
            String uid,
            String rating,
            String reason) {
        Object client = getLangfuseClient();
        if (client == null) {
            return false;
        }

        int value = "like".equals(rating) ? 1 : 0;
        try {
            // SDK 接入点：client.create_score(trace_id=..., score_id=f"wenqu-message-feedback-{feedbackId}",
            //   name="user-feedback", value=value, data_type="BOOLEAN", comment=reason, metadata={...})
            throw new UnsupportedOperationException("Langfuse SDK 未接入");
        } catch (Exception exc) {
            log.warn("提交 Langfuse 用户反馈评分失败，将保留本地反馈: {}", exc.getMessage());
            return false;
        }
    }

    /** 解析 URL 的 (scheme, hostname, port) 三元组，非法输入返回 null（与参考实现 _http_origin 对齐）。 */
    private static String httpOrigin(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI parsed = URI.create(url.strip());
            String scheme = parsed.getScheme();
            String hostname = parsed.getHost();
            if (hostname == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return null;
            }
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            int port = parsed.getPort() > 0 ? parsed.getPort() : defaultPort;
            return scheme + "\n" + hostname.toLowerCase(java.util.Locale.ROOT) + "\n" + port;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 按 trace ID 惰性解析已配置 Langfuse 源站的页面 URL；失败/越权源站返回 null。
     *
     * <p>SDK 缺位：客户端为 null，恒返回 null（参考实现短路分支）。
     */
    public String getTraceUrlById(String traceId, double timeoutSeconds) {
        String normalized = traceId == null ? "" : String.valueOf(traceId).strip();
        if (normalized.isEmpty()) {
            return null;
        }

        Object client = getLangfuseClient();
        if (client == null) {
            return null;
        }
        // SDK 接入点以下逻辑照搬参考实现：
        // trace_url = await asyncio.wait_for(asyncio.to_thread(client.get_trace_url, trace_id=...), timeout)
        // 失败告警返回 None；非字符串返回 None；
        // trace_url 的源站与 LANGFUSE_BASE_URL（缺省 https://cloud.langfuse.com）不一致时拒绝并告警。
        return null;
    }

    /** 刷新 Langfuse 事件；客户端缺位时为空操作。 */
    public void flushLangfuse() {
        Object client = getLangfuseClient();
        if (client == null) {
            return;
        }
        try {
            // SDK 接入点：client.flush()
        } catch (Exception exc) {
            log.warn("刷新 Langfuse 事件失败: {}", exc.getMessage());
        }
    }

    private static String envOrNull(String name) {
        return System.getenv(name);
    }
}
