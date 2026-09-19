package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：附加元数据与全局工具实例收集。
 *
 * <p>由参考实现的 agents/toolkits/registry.py 逐段翻译：ToolExtraMetadata、
 * 全局元数据注册表、全局工具实例清单。
 *
 * <p>能力差异（显式标注，非遗漏）：参考实现经 {@code @tool} 装饰器（langchain tool
 * 的扩展）在模块导入期自动收集工具实例与元数据；Java 无装饰器机制，工具执行体属于
 * agents 运行时引擎（尚未照搬）。本类保留注册表的全部数据面：
 * {@link #register(ToolDefinition, ToolExtraMetadata)} 供引擎/工具集照搬时调用，
 * {@link ToolDefinition} 承载 langchain tool 的 name/description/args_schema 面。
 */
public final class ToolkitsRegistry {

    /** 附加元数据（用装饰器注册）。 */
    public static final class ToolExtraMetadata {

        public String category = "";  // 分类: buildin, knowledge, mysql, subagents, debug
        public List<String> tags = new ArrayList<>();
        public String displayName = "";  // 显示名称（给人看的名字）
        public String icon = "";
        public String configGuide = "";  // 配置说明（给人看的使用前配置提示）

        public ToolExtraMetadata() {}

        public ToolExtraMetadata(String category, List<String> tags, String displayName, String icon, String configGuide) {
            this.category = category == null ? "" : category;
            this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
            this.displayName = displayName == null ? "" : displayName;
            this.icon = icon == null ? "" : icon;
            this.configGuide = configGuide == null ? "" : configGuide;
        }
    }

    /**
     * langchain tool 对象的最小面（name/description/args_schema）。
     *
     * <p>args_schema 以 JSON schema 形式承载（对应 pydantic {@code .schema()} 产物）。
     */
    public interface ToolDefinition {

        /** 工具名（langchain tool_obj.name）。 */
        String getName();

        /** 工具描述（langchain tool_obj.description）。 */
        String getDescription();

        /** 参数 schema（dict 或 pydantic model 的 schema()，此处统一为 Map）。 */
        Map<String, Object> getArgsSchema();

        /**
         * 工具对象自身的 metadata（langchain tool 的 {@code tool_obj.metadata}）。
         *
         * <p>平台差异：参考实现用 {@code getattr(tool_obj, "metadata", {}) or {}} 动态取用，
         * Java 无此机制，故在接口上显式给出默认实现（缺省空表），语义等价。
         */
        default Map<String, Object> getMetadata() {
            return Map.of();
        }
    }

    /** 全局注册表: tool_name -> ToolExtraMetadata。 */
    private static final Map<String, ToolExtraMetadata> EXTRA_REGISTRY = new LinkedHashMap<>();

    /** 全局工具实例列表（由 @tool 装饰器自动收集）。 */
    private static final List<ToolDefinition> ALL_TOOL_INSTANCES = new ArrayList<>();

    private ToolkitsRegistry() {}

    /** 获取工具附加元数据。 */
    public static ToolExtraMetadata getExtraMetadata(String toolName) {
        return EXTRA_REGISTRY.get(toolName);
    }

    /** 获取所有附加元数据。 */
    public static Map<String, ToolExtraMetadata> getAllExtraMetadata() {
        return new LinkedHashMap<>(EXTRA_REGISTRY);
    }

    /** 获取所有工具实例（由 @tool 装饰器自动收集）。 */
    public static List<ToolDefinition> getAllToolInstances() {
        return new ArrayList<>(ALL_TOOL_INSTANCES);
    }

    /**
     * 注册工具（对应 {@code @tool} 装饰器体内动作：应用 langchain 装饰器后注册元数据、
     * 设置 handle_tool_error 并收集实例）。
     */
    public static void register(ToolDefinition tool, ToolExtraMetadata metadata) {
        EXTRA_REGISTRY.put(tool.getName(), new ToolExtraMetadata(
                metadata.category, metadata.tags, metadata.displayName, metadata.icon, metadata.configGuide));
        ALL_TOOL_INSTANCES.add(tool);
    }
}
