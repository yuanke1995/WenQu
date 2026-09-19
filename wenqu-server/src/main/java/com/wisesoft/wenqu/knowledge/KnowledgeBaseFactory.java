package com.wisesoft.wenqu.knowledge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 知识库工厂：按 kb_type 注册与创建知识库实例。
 *
 * <p>由参考实现的 knowledge/factory.py 逐方法翻译（register / create / get_available_types /
 * get_kb_class / is_type_supported）。
 *
 * <p>必要替换：参考实现以「类对象」注册并反射实例化；本工程的执行器是无状态 Spring 单例，
 * 故注册的是「kb_type → 描述 + 实例供应器」，{@code create} 返回供应器产物（等价于其
 * {@code kb_class(work_dir)} 的实例化结果）。工作目录在参考实现用于落盘中间产物，
 * 本工程统一走对象存储，故不承载 work_dir 参数。
 */
public final class KnowledgeBaseFactory {

    /** 一种知识库类型的注册信息。 */
    public record KbTypeInfo(
            String kbType,
            String name,
            String description,
            boolean requiresEmbeddingModel,
            boolean supportsDocuments,
            Map<String, Object> createParams,
            Supplier<Object> instanceSupplier) {}

    private static final Map<String, KbTypeInfo> KB_TYPES = new LinkedHashMap<>();

    private KnowledgeBaseFactory() {}

    /** 注册知识库类型；kb_type 为空或已注册时拒绝（与参考实现的校验口径一致）。 */
    public static synchronized void register(KbTypeInfo info) {
        if (info == null) {
            throw new IllegalArgumentException("Knowledge base type info must not be null");
        }
        if (info.kbType() == null || info.kbType().isEmpty()) {
            throw new IllegalArgumentException("Knowledge base class must define kb_type");
        }
        KB_TYPES.put(info.kbType(), info);
    }

    /** 创建知识库实例；未知类型抛 KBNotFoundError（与参考实现一致）。 */
    public static Object create(String kbType) {
        KbTypeInfo info = KB_TYPES.get(kbType);
        if (info == null) {
            throw new KnowledgeBaseException.KBNotFoundError(
                    "Unknown knowledge base type: " + kbType + ". Available types: " + KB_TYPES.keySet());
        }
        return info.instanceSupplier().get();
    }

    /** 所有可用知识库类型的描述信息。 */
    public static Map<String, Map<String, Object>> getAvailableTypes() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, KbTypeInfo> entry : KB_TYPES.entrySet()) {
            KbTypeInfo info = entry.getValue();
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("name", info.name());
            description.put("description", info.description());
            description.put("requires_embedding_model", info.requiresEmbeddingModel());
            description.put("supports_documents", info.supportsDocuments());
            description.put("create_params", info.createParams() == null ? new LinkedHashMap<>() : info.createParams());
            result.put(entry.getKey(), description);
        }
        return result;
    }

    /** 取指定类型的注册信息；未知类型抛 KBNotFoundError。 */
    public static KbTypeInfo getKbClass(String kbType) {
        KbTypeInfo info = KB_TYPES.get(kbType);
        if (info == null) {
            throw new KnowledgeBaseException.KBNotFoundError(
                    "Unknown knowledge base type: " + kbType + ". Available types: " + KB_TYPES.keySet());
        }
        return info;
    }

    /** 是否支持指定知识库类型。 */
    public static boolean isTypeSupported(String kbType) {
        return kbType != null && KB_TYPES.containsKey(kbType);
    }

    /** 清空注册表（仅供测试/初始化重置）。 */
    static synchronized void reset() {
        KB_TYPES.clear();
    }
}
