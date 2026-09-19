package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import com.wisesoft.wenqu.knowledge.parser.DocumentProcessorFactory;
import com.wisesoft.wenqu.models.ModelProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OCR 方法选择、运行时配置和健康检测（services/ocr_service.py 全量移植）。
 *
 * <p>必要替换：{@code pg_manager.get_async_session_context()}（无 db 句柄时自建会话）→
 * 本工程统一走 Spring 事务/Bean 调用，配置解析直接由 {@link OptionsService} 承担；
 * {@code asyncio.gather} 并发健康检查 → 顺序执行（本工程无事件循环，检查结果一致）。
 */
@Slf4j
@Service
public class OcrService {

    private final OptionsService optionsService;
    private final ModelProviderService modelProviderService;

    public OcrService(OptionsService optionsService, ModelProviderService modelProviderService) {
        this.optionsService = optionsService;
        this.modelProviderService = modelProviderService;
    }

    /** 引擎选项视图（设置页引擎列表）。 */
    public Map<String, Object> getOcrOptions() {
        Map<String, Object> options = optionsService.get(OptionsService.SYSTEM_OPTIONS);
        List<Map<String, Object>> engines = new ArrayList<>();
        for (Map.Entry<String, ParserCapabilities.ParserCapability> entry :
                ParserCapabilities.PARSER_CAPABILITIES.entrySet()) {
            ParserCapabilities.ParserCapability capability = entry.getValue();
            Map<String, Object> engine = new LinkedHashMap<>();
            engine.put("engine_id", entry.getKey());
            engine.put("service_name", capability.serviceName());
            engine.put("display_name", capability.displayName());
            engine.put("supported_extensions", List.copyOf(capability.supportedExtensions()));
            engines.add(engine);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("default_engine", options.get("default_ocr_engine"));
        result.put("engines", engines);
        return result;
    }

    /** 解析最终 OCR 引擎 ID（显式参数优先，缺省用默认引擎；disable 合法）。 */
    public static String resolveOcrEngineId(String engineId, String defaultEngine) {
        String resolved = (engineId == null || engineId.isEmpty() ? defaultEngine : engineId).strip();
        if (resolved.isEmpty()) {
            resolved = defaultEngine;
        }
        if ("disable".equals(resolved)) {
            return resolved;
        }
        if (!ParserCapabilities.PARSER_CAPABILITIES.containsKey(resolved)) {
            throw new IllegalArgumentException("不支持的 OCR 引擎: " + resolved);
        }
        return resolved;
    }

    /** 解析 OCR 任务参数：确定引擎并构造处理器参数（_ocr_processor_kwargs）。 */
    public Map<String, Object> resolveOcrTaskParams(Map<String, Object> params) {
        Map<String, Object> resolved = new LinkedHashMap<>(params == null ? Map.of() : params);
        Object configuredEngine = resolved.get("ocr_engine");
        String defaultEngine;
        if (configuredEngine == null) {
            defaultEngine = String.valueOf(optionsService.get(OptionsService.SYSTEM_OPTIONS).get("default_ocr_engine"));
        } else {
            defaultEngine = String.valueOf(configuredEngine);
        }
        String engineId = resolveOcrEngineId(
                configuredEngine == null ? null : String.valueOf(configuredEngine), defaultEngine);
        resolved.put("ocr_engine", engineId);
        resolved.remove("ocr_engine_config");

        Map<String, Object> kwargs;
        if ("disable".equals(engineId)) {
            kwargs = new LinkedHashMap<>();
        } else {
            kwargs = buildProcessorKwargs(engineId);
        }
        resolved.put("_ocr_processor_kwargs", kwargs);
        return resolved;
    }

    /**
     * 使用当前运行时配置将文件解析为 Markdown。业务代码唯一应调用的文档解析入口。
     *
     * <p>负责区分应用层配置解析和底层文件转换：OCR 文件先确定引擎，再从 Options/
     * 环境变量/模型供应商解析构造参数；普通文件参数原样交给统一解析器。
     * 底层 parser 只接收准备好的 {@code ocr_engine} 与 {@code _ocr_processor_kwargs}。
     */
    public String parseDocument(String source, Map<String, Object> params) {
        Map<String, Object> resolvedParams = params;
        String suffix = com.wisesoft.wenqu.common.PosixPathLite
                .suffixOf(source.split("\\?", 2)[0])
                .toLowerCase();
        if (ParserCapabilities.OCR_FILE_EXTENSIONS.contains(suffix)) {
            resolvedParams = resolveOcrTaskParams(params);
            String engineId = String.valueOf(resolvedParams.get("ocr_engine"));
            if (!"disable".equals(engineId)
                    && !ParserCapabilities.getParserCapability(engineId).supportedExtensions().contains(suffix)) {
                throw new IllegalArgumentException("OCR 引擎 " + engineId + " 不支持文件类型 " + suffix);
            }
        }

        return com.wisesoft.wenqu.knowledge.UnifiedParser.parseResolvedDocument(source, resolvedParams);
    }

    /** 使用当前有效配置并行检查所有 OCR 方法。 */
    public Map<String, Object> checkAllOcrHealth() {
        List<String> configured = new ArrayList<>();
        Map<String, Object> results = new LinkedHashMap<>();
        for (String engineId : ParserCapabilities.PARSER_CAPABILITIES.keySet()) {
            try {
                buildProcessorKwargs(engineId);
                configured.add(engineId);
            } catch (Exception exc) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("status", "error");
                failure.put("message", String.valueOf(exc.getMessage()));
                failure.put("details", new LinkedHashMap<>());
                results.put(engineId, failure);
            }
        }

        for (String engineId : configured) {
            Map<String, Object> kwargs;
            try {
                kwargs = buildProcessorKwargs(engineId);
            } catch (Exception exc) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("status", "error");
                failure.put("message", String.valueOf(exc.getMessage()));
                failure.put("details", new LinkedHashMap<>());
                results.put(engineId, failure);
                continue;
            }
            results.put(engineId, DocumentProcessorFactory.checkHealth(engineId, kwargs));
        }
        return results;
    }

    /** 按引擎解析处理器构造参数（Options → 环境变量 → 模型供应商）。 */
    private Map<String, Object> buildProcessorKwargs(String engineId) {
        Map<String, Object> kwargs = new LinkedHashMap<>();
        switch (engineId) {
            case "mineru_ocr" -> {
                Map<String, Object> opts = optionsService.get(OptionsService.MINERU_OCR_HOST_OPTS);
                Object serverUrl = opts.get("server_url");
                if (serverUrl != null && !String.valueOf(serverUrl).isEmpty()) {
                    kwargs.put("server_url", serverUrl);
                }
            }
            case "mineru_official" -> {
                Map<String, Object> opts = optionsService.get(OptionsService.MINERU_OFFICIAL_API_OPTS);
                Object apiKey = opts.get("api_key");
                if (apiKey != null && !String.valueOf(apiKey).isEmpty()) {
                    kwargs.put("api_key", apiKey);
                }
            }
            case "pp_structure_v3_ocr" -> {
                Map<String, Object> opts = optionsService.get(OptionsService.PP_STRUCTURE_V3_OCR_HOST_OPTS);
                Object serverUrl = opts.get("server_url");
                if (serverUrl != null && !String.valueOf(serverUrl).isEmpty()) {
                    kwargs.put("server_url", serverUrl);
                }
            }
            case "deepseek_ocr" -> {
                ModelProvider provider = modelProviderService.getModelProviderById("siliconflow-cn");
                String apiKey = provider != null && Boolean.TRUE.equals(provider.getIsEnabled())
                        ? ModelProviderService.resolveApiKey(provider)
                        : null;
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new IllegalArgumentException("siliconflow-cn 模型供应商凭证不可用");
                }
                kwargs.put("api_key", apiKey);
                kwargs.put("api_url", stripTrailingSlash(provider.getBaseUrl()) + "/chat/completions");
            }
            case "paddleocr_vl_1_6", "paddleocr_pp_ocrv6" -> {
                Map<String, Object> opts = optionsService.get(OptionsService.PADDLEOCR_API_OPTS);
                for (Map.Entry<String, Object> entry : opts.entrySet()) {
                    if (entry.getValue() != null) {
                        kwargs.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            default -> {
                // 其余引擎无构造参数（参考实现 return {}）
            }
        }
        return kwargs;
    }

    private static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
