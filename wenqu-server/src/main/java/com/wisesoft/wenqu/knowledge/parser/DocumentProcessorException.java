package com.wisesoft.wenqu.knowledge.parser;

/**
 * 文档处理异常基类（knowledge/parser/base.py 的 DocumentProcessorException 全量移植）。
 *
 * <p>{@code message}/{@code service_name}/{@code status_code} 三字段与
 * {@code __str__} 的 {@code [{service_name}] {message}} 形态逐字保留。
 */
public class DocumentProcessorException extends RuntimeException {

    private final String message;
    private final String serviceName;
    private final String statusCode;

    public DocumentProcessorException(String message, String serviceName, String statusCode) {
        super(message);
        this.message = message;
        this.serviceName = serviceName;
        this.statusCode = statusCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getStatusCode() {
        return statusCode;
    }

    @Override
    public String getMessage() {
        if (serviceName != null) {
            return "[" + serviceName + "] " + message;
        }
        return message;
    }
}
