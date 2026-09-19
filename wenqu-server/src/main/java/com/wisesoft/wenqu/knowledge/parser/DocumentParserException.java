package com.wisesoft.wenqu.knowledge.parser;

/** 文档解析异常（knowledge/parser/base.py 的 DocumentParserException 全量移植）。 */
public class DocumentParserException extends DocumentProcessorException {

    public DocumentParserException(String message, String serviceName, String statusCode) {
        super(message, serviceName, statusCode);
    }
}
