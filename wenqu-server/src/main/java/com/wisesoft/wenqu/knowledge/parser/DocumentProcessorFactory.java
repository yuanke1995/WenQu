package com.wisesoft.wenqu.knowledge.parser;

import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档处理器工厂（knowledge/parser/factory.py 全量移植）。
 *
 * <p>必要替换：Python {@code import_module(capability.module_path)} 动态导入 →
 * 引擎构造器注册表（各引擎类由静态块注册，见 {@link #register}）。
 * 动态导入在 Java 侧的唯一作用是按 capability 找到类，注册表达成同一效果。
 */
@Slf4j
public final class DocumentProcessorFactory {

    /** 处理器实例缓存（参考实现模块级 _PROCESSOR_CACHE）。 */
    private static final Map<String, BaseDocumentProcessor> PROCESSOR_CACHE = new LinkedHashMap<>();

    /** 引擎构造器注册表（processorType → kwargs 构造器）。 */
    private static final Map<String, Function<Map<String, Object>, BaseDocumentProcessor>> ENGINE_REGISTRY =
            new LinkedHashMap<>();

    static {
        // 对应参考实现 import_module + getattr 的类装载；引擎类与本工厂同包静态可达
        RapidOCRParser.register();
        MinerUParser.register();
        MinerUOfficialParser.register();
        PPStructureV3Parser.register();
        DeepSeekOCRParser.register();
        PaddleOcrApiParser.register();
    }

    private DocumentProcessorFactory() {}

    /** 注册引擎构造器（对应参考实现的动态导入装载点）。 */
    public static void register(String processorType, Function<Map<String, Object>, BaseDocumentProcessor> factory) {
        ENGINE_REGISTRY.put(processorType, factory);
    }

    /** 生成不暴露初始化参数内容的稳定缓存键。 */
    static String buildCacheKey(String processorType, Map<String, Object> kwargs) {
        if (kwargs == null || kwargs.isEmpty()) {
            return processorType;
        }
        // 初始化参数可能包含数据库密钥；摘要既区分实例配置，也避免密钥出现在缓存键和调试输出中。
        String kwargsRepr = kwargs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + pythonRepr(entry.getValue()))
                .collect(Collectors.joining("|"));
        String digest = sha256Hex(kwargsRepr).substring(0, 16);
        return processorType + "|" + digest;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception exc) {
            throw new IllegalStateException(exc);
        }
    }

    /** Python repr 的最小子集（缓存键的确定性渲染用；字符串带引号、None→None、布尔首字母大写）。 */
    private static String pythonRepr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof String text) {
            return "'" + text + "'";
        }
        if (value instanceof Boolean bool) {
            return bool ? "True" : "False";
        }
        return String.valueOf(value);
    }

    /** 获取文档处理器实例（单例模式）。 */
    public static synchronized BaseDocumentProcessor getProcessor(String processorType, Map<String, Object> kwargs) {
        if (!ParserCapabilities.PARSER_CAPABILITIES.containsKey(processorType)) {
            throw new IllegalArgumentException(
                    "不支持的处理器类型: " + processorType + ". 支持的类型: "
                            + ParserCapabilities.PARSER_CAPABILITIES.keySet());
        }

        // 使用缓存避免重复创建
        Map<String, Object> effectiveKwargs = kwargs == null ? Map.of() : kwargs;
        String cacheKey = buildCacheKey(processorType, effectiveKwargs);
        if (!PROCESSOR_CACHE.containsKey(cacheKey)) {
            clearCache(processorType);
            Function<Map<String, Object>, BaseDocumentProcessor> loader = ENGINE_REGISTRY.get(processorType);
            if (loader == null) {
                throw new IllegalArgumentException("不支持的处理器类型: " + processorType + ". 支持的类型: "
                        + ParserCapabilities.PARSER_CAPABILITIES.keySet());
            }
            PROCESSOR_CACHE.put(cacheKey, loader.apply(effectiveKwargs));
            log.debug("创建文档处理器: {}", processorType);
        }

        return PROCESSOR_CACHE.get(cacheKey);
    }

    /** 使用指定处理器处理文件（便捷方法）。 */
    public static String processFile(
            String processorType,
            String filePath,
            Map<String, Object> params,
            Map<String, Object> processorKwargs) {
        BaseDocumentProcessor processor = getProcessor(processorType, processorKwargs);
        return processor.processFile(filePath, params);
    }

    /** 检查指定处理器的健康状态。 */
    public static Map<String, Object> checkHealth(String processorType, Map<String, Object> kwargs) {
        try {
            BaseDocumentProcessor processor = getProcessor(processorType, kwargs);
            return processor.checkHealth();
        } catch (Exception exc) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("error", String.valueOf(exc.getMessage()));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "error");
            result.put("message", "健康检查失败: " + exc.getMessage());
            result.put("details", details);
            return result;
        }
    }

    /** 清除全部处理器缓存，或只淘汰指定引擎的实例。 */
    public static synchronized void clearCache(String processorType) {
        if (processorType == null) {
            PROCESSOR_CACHE.clear();
            log.debug("文档处理器缓存已清除");
            return;
        }
        List<String> matchingKeys = new ArrayList<>();
        for (String cacheKey : PROCESSOR_CACHE.keySet()) {
            if (cacheKey.equals(processorType) || cacheKey.startsWith(processorType + "|")) {
                matchingKeys.add(cacheKey);
            }
        }
        for (String cacheKey : matchingKeys) {
            PROCESSOR_CACHE.remove(cacheKey);
        }
        log.debug("文档处理器缓存已清除: {}", processorType);
    }
}
