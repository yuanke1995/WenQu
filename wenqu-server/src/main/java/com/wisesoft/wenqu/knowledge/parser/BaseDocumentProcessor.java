package com.wisesoft.wenqu.knowledge.parser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文档处理器基类（knowledge/parser/base.py 的 BaseDocumentProcessor 全量移植）。
 *
 * <p>Python 的 {@code ClassVar} 类属性 → 子类构造器内赋值的保护字段（Java 无类级可变属性），
 * 各引擎构造器从 {@code ParserCapabilities.getParserCapability(...)} 取值填充，与参考一致。
 */
public abstract class BaseDocumentProcessor {

    protected String serviceName = "";
    protected String displayName = "";
    protected List<String> supportedExtensions = List.of();

    /** 处理文件并返回提取的文本。 */
    public abstract String processFile(String filePath, Map<String, Object> params);

    /** 检查服务健康状态，返回 status/message/details 结构。 */
    public abstract Map<String, Object> checkHealth();

    /** 返回 parser 声明的稳定服务标识。 */
    public String getServiceName() {
        return serviceName;
    }

    /** 检查是否支持指定的文件类型（fileExtension 含点，如 ".pdf"）。 */
    public boolean supportsFileType(String fileExtension) {
        return supportedExtensions.contains(fileExtension.toLowerCase(Locale.ROOT));
    }

    /** 返回类声明的支持文件扩展名。 */
    public List<String> getSupportedExtensions() {
        return List.copyOf(supportedExtensions);
    }
}
