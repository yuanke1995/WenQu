package com.wisesoft.wenqu.knowledge.parser;

/** OCR处理异常（knowledge/parser/base.py 的 OCRException 全量移植）。 */
public class OCRException extends DocumentProcessorException {

    public OCRException(String message, String serviceName, String statusCode) {
        super(message, serviceName, statusCode);
    }
}
