package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具审批模式与敏感工具拦截定义。
 *
 * <p>由参考实现的 agents/tool_approval.py 逐函数翻译：模式归一化、敏感 backend 工具清单、
 * 按 Project 豁免当前目录写入的审批谓词。
 *
 * <p>能力差异（显式标注，非遗漏）：参考实现构造 LangChain 的
 * {@code HumanInTheLoopMiddleware}；Java 侧无该中间件（agents 运行时引擎尚未照搬），
 * {@link #createToolApprovalInterruptOn} 返回与 {@code interrupt_on} 相同的数据形状，
 * 供后续引擎照搬时直接消费；{@code mode == "always_trust"} 时同样返回 null。
 */
public final class ToolApproval {

    public static final String DEFAULT_TOOL_APPROVAL_MODE = "default";
    public static final Set<String> TOOL_APPROVAL_MODES = Set.of("default", "always_trust");
    /** 默认审批模式下需要拦截或对子 Agent 隐藏的敏感 backend 工具。 */
    public static final Set<String> SENSITIVE_BACKEND_TOOLS = Set.of("write_file", "edit_file", "execute");

    private static final List<String> ALLOWED_DECISIONS = Arrays.asList("approve", "reject");

    private ToolApproval() {}

    /** 归一化审批模式；非法值抛 IllegalArgumentException（对应参考实现 ValueError）。 */
    public static String normalizeToolApprovalMode(Object value) {
        Object mode = value instanceof String text ? text.strip() : value;
        if (mode == null || !TOOL_APPROVAL_MODES.contains(String.valueOf(mode))) {
            throw new IllegalArgumentException("不支持的 tool_approval_mode: " + value);
        }
        return String.valueOf(mode);
    }

    /**
     * 按审批模式与当前 Project 构造敏感工具审批（返回 {@code interrupt_on} 数据形状）。
     *
     * @return {@code always_trust} 时为 null；否则形如
     *         {@code {write_file: {allowed_decisions, when}, edit_file: {...}, execute: {allowed_decisions}}}
     */
    public static Map<String, Map<String, Object>> createToolApprovalInterruptOn(
            String mode, String currentProjectPath) {
        if ("always_trust".equals(mode)) {
            return null;
        }

        java.util.function.Predicate<Map<Object, Object>> writeRequiresApproval =
                projectWriteRequiresApproval(currentProjectPath == null ? "" : currentProjectPath);
        Map<String, Map<String, Object>> interruptOn = new LinkedHashMap<>();
        Map<String, Object> writeFileSpec = new LinkedHashMap<>();
        writeFileSpec.put("allowed_decisions", ALLOWED_DECISIONS);
        writeFileSpec.put("when", writeRequiresApproval);
        interruptOn.put("write_file", writeFileSpec);

        Map<String, Object> editFileSpec = new LinkedHashMap<>();
        editFileSpec.put("allowed_decisions", ALLOWED_DECISIONS);
        editFileSpec.put("when", writeRequiresApproval);
        interruptOn.put("edit_file", editFileSpec);

        Map<String, Object> executeSpec = new LinkedHashMap<>();
        executeSpec.put("allowed_decisions", ALLOWED_DECISIONS);
        interruptOn.put("execute", executeSpec);
        return interruptOn;
    }

    /** 创建仅豁免当前 Project 写入的审批谓词。 */
    static java.util.function.Predicate<Map<Object, Object>> projectWriteRequiresApproval(
            String currentProjectPath) {
        PosixPath projectRoot = normalizeRuntimePath(currentProjectPath);
        return request -> {
            // 非法路径或 Project 外路径继续请求人工确认
            Object args = request == null ? null : request.get("args");
            Object filePathRaw = args instanceof Map<?, ?> argsMap ? argsMap.get("file_path") : null;
            PosixPath filePath = normalizeRuntimePath(filePathRaw);
            if (projectRoot == null || filePath == null) {
                return true;
            }
            return !filePath.equals(projectRoot) && !filePath.isUnder(projectRoot);
        };
    }

    /** 将审批参数收敛为无跳转的 Sandbox 绝对路径（PurePosixPath 语义）。 */
    static PosixPath normalizeRuntimePath(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String raw = ((String) value).strip();
        PosixPath path = PosixPath.parse(raw);
        if (raw.isEmpty()
                || !path.isAbsolute()
                || path.partsContains("..")
                || raw.contains("\\")
                || raw.contains("://")) {
            return null;
        }
        return path;
    }

    /**
     * PurePosixPath 的最小移植（仅审批逻辑所需面）：分割符折叠、绝对路径判断、
     * parts（首段为 "/"）、parents 链与路径相等比较。
     */
    public static final class PosixPath {

        private final boolean absolute;
        private final List<String> segments;
        private final String normalized;

        private PosixPath(boolean absolute, List<String> segments) {
            this.absolute = absolute;
            this.segments = segments;
            this.normalized = absolute ? "/" + String.join("/", segments) : String.join("/", segments);
        }

        static PosixPath parse(String value) {
            String text = value == null ? "" : value;
            boolean absolute = text.startsWith("/");
            List<String> segments = new ArrayList<>();
            for (String part : text.split("/")) {
                if (!part.isEmpty()) {
                    segments.add(part);
                }
            }
            return new PosixPath(absolute, segments);
        }

        boolean isAbsolute() {
            return absolute;
        }

        /** parts 语义：绝对路径首段为 "/"，其后为各级目录/文件名。 */
        List<String> parts() {
            List<String> parts = new ArrayList<>();
            if (absolute) {
                parts.add("/");
            }
            parts.addAll(segments);
            return parts;
        }

        boolean partsContains(String value) {
            return parts().contains(value);
        }

        /** 是否位于 ancestor 之下（对应 {@code ancestor in path.parents}）。 */
        boolean isUnder(PosixPath ancestor) {
            if (!ancestor.absolute || !absolute || segments.size() <= ancestor.segments.size()) {
                return false;
            }
            return segments.subList(0, ancestor.segments.size()).equals(ancestor.segments);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PosixPath && ((PosixPath) other).normalized.equals(normalized);
        }

        @Override
        public int hashCode() {
            return normalized.hashCode();
        }

        @Override
        public String toString() {
            return normalized;
        }
    }
}
