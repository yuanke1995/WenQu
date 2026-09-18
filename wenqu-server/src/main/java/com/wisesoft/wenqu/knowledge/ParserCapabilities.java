package com.wisesoft.wenqu.knowledge;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档格式与 OCR 处理器能力声明。
 *
 * <p>由参考实现的 knowledge/parser/capabilities.py 逐条翻译：扩展名集合、
 * PARSER_CAPABILITIES 注册表（service_name/display_name/supported_extensions/
 * module_path/class_name 逐字照搬）、按扩展名查询引擎。
 *
 * <p>说明：module_path/class_name 是参考实现 Python 解析器的动态装配坐标；
 * <p>必要替换：module_path 的包前缀按本产品命名（参考实现使用其自身包坐标）；
 * Java 解析器引擎尚未照搬，本类先落能力声明数据面，供设置项校验、附件解析
 * 方案枚举与 ocr_service（照搬时）消费。
 */
public final class ParserCapabilities {

    public static final List<String> TEXT_FILE_EXTENSIONS = List.of(".txt", ".md");
    public static final List<String> OFFICE_FILE_EXTENSIONS = List.of(".docx", ".pptx", ".xls", ".xlsx");
    public static final List<String> HTML_FILE_EXTENSIONS = List.of(".html", ".htm");
    public static final List<String> IMAGE_FILE_EXTENSIONS =
            List.of(".jpg", ".jpeg", ".png", ".bmp", ".tiff", ".tif", ".webp");
    public static final List<String> PDF_FILE_EXTENSIONS = List.of(".pdf");
    public static final Set<String> OCR_FILE_EXTENSIONS =
            Set.of(concat(PDF_FILE_EXTENSIONS, IMAGE_FILE_EXTENSIONS));

    public static final List<String> SUPPORTED_FILE_EXTENSIONS = List.of(
            ".txt", ".md",
            ".docx", ".pptx", ".xls", ".xlsx",
            ".html", ".htm",
            ".json", ".csv",
            ".pdf",
            ".jpg", ".jpeg", ".png", ".bmp", ".tiff", ".tif", ".webp",
            ".zip");

    private static final List<String> STANDARD_OCR_EXTENSIONS =
            List.of(".pdf", ".jpg", ".jpeg", ".png", ".bmp", ".tiff", ".tif");
    private static final List<String> MINERU_OFFICIAL_EXTENSIONS =
            List.of(".pdf", ".docx", ".pptx", ".png", ".jpg", ".jpeg");
    private static final List<String> DEEPSEEK_OCR_EXTENSIONS =
            List.of(".pdf", ".png", ".jpg", ".jpeg", ".bmp", ".webp");

    /** 描述一个 OCR 处理器的装配位置和输入格式。 */
    public record ParserCapability(
            String serviceName, String displayName, List<String> supportedExtensions,
            String modulePath, String className) {}

    /** PARSER_CAPABILITIES 注册表（键序即注册顺序）。 */
    public static final Map<String, ParserCapability> PARSER_CAPABILITIES;

    static {
        Map<String, ParserCapability> capabilities = new LinkedHashMap<>();
        capabilities.put("rapid_ocr", new ParserCapability(
                "rapid_ocr", "RapidOCR (ONNX)", STANDARD_OCR_EXTENSIONS,
                "wenqu.knowledge.parser.rapid_ocr", "RapidOCRParser"));
        capabilities.put("mineru_ocr", new ParserCapability(
                "mineru_ocr", "MinerU OCR", STANDARD_OCR_EXTENSIONS,
                "wenqu.knowledge.parser.mineru", "MinerUParser"));
        capabilities.put("mineru_official", new ParserCapability(
                "mineru_official", "MinerU Official API", MINERU_OFFICIAL_EXTENSIONS,
                "wenqu.knowledge.parser.mineru_official", "MinerUOfficialParser"));
        capabilities.put("pp_structure_v3_ocr", new ParserCapability(
                "pp_structure_v3_ocr", "PP-Structure-V3", STANDARD_OCR_EXTENSIONS,
                "wenqu.knowledge.parser.pp_structure_v3", "PPStructureV3Parser"));
        capabilities.put("deepseek_ocr", new ParserCapability(
                "deepseek_ocr", "DeepSeek OCR", DEEPSEEK_OCR_EXTENSIONS,
                "wenqu.knowledge.parser.deepseek_ocr", "DeepSeekOCRParser"));
        capabilities.put("paddleocr_vl_1_6", new ParserCapability(
                "paddleocr_vl_1_6", "PaddleOCR-VL-1.6", STANDARD_OCR_EXTENSIONS,
                "wenqu.knowledge.parser.paddleocr_api", "PaddleOCRVLParser"));
        capabilities.put("paddleocr_pp_ocrv6", new ParserCapability(
                "paddleocr_pp_ocrv6", "PP-OCRv6", STANDARD_OCR_EXTENSIONS,
                "wenqu.knowledge.parser.paddleocr_api", "PaddleOCRPPOCRv6Parser"));
        PARSER_CAPABILITIES = Collections.unmodifiableMap(capabilities);

        // 向 OptionsService 的 ocr_engine 校验端口注册真实引擎清单（缺省空集的解除点）
        com.wisesoft.wenqu.config.OptionsService.registerOcrEngineIds(ParserCapabilities::getOcrEngineIds);
    }

    private ParserCapabilities() {}

    /** 返回指定 OCR 处理器的轻量能力声明。 */
    public static ParserCapability getParserCapability(String engineId) {
        ParserCapability capability = PARSER_CAPABILITIES.get(engineId);
        if (capability == null) {
            throw new IllegalArgumentException("不支持的 OCR 引擎: " + engineId);
        }
        return capability;
    }

    /** 返回按注册顺序排列的 OCR 处理器标识。 */
    public static List<String> getOcrEngineIds() {
        return List.copyOf(PARSER_CAPABILITIES.keySet());
    }

    /** 返回能处理指定扩展名的 OCR 处理器。 */
    public static List<String> getOcrEnginesForExtension(String extension) {
        String normalized = extension == null ? "null" : extension.toLowerCase();
        if (!normalized.startsWith(".")) {
            normalized = "." + normalized;
        }
        List<String> result = new java.util.ArrayList<>();
        for (Map.Entry<String, ParserCapability> entry : PARSER_CAPABILITIES.entrySet()) {
            if (entry.getValue().supportedExtensions().contains(normalized)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** 判断文件名是否属于统一解析器支持的输入格式。 */
    public static boolean isSupportedFileExtension(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        String suffix = dot >= 0 ? name.substring(dot).toLowerCase() : "";
        return SUPPORTED_FILE_EXTENSIONS.contains(suffix);
    }

    private static String[] concat(List<String> first, List<String> second) {
        String[] all = new String[first.size() + second.size()];
        int i = 0;
        for (String value : first) {
            all[i++] = value;
        }
        for (String value : second) {
            all[i++] = value;
        }
        return all;
    }
}
