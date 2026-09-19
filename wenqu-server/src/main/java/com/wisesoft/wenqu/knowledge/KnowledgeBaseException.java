package com.wisesoft.wenqu.knowledge;

/**
 * 知识库统一异常基类。
 *
 * <p>由参考实现的 knowledge/base.py 中 KnowledgeBaseException / KBNotFoundError /
 * KBNameConflictError 逐项翻译。Java 侧同属一个文件内以静态嵌套类承载，层级关系一致。
 */
public class KnowledgeBaseException extends RuntimeException {

    public KnowledgeBaseException(String message) {
        super(message);
    }

    public KnowledgeBaseException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 知识库不存在错误。 */
    public static class KBNotFoundError extends KnowledgeBaseException {
        public KBNotFoundError(String message) {
            super(message);
        }

        public KBNotFoundError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 知识库名称冲突错误。 */
    public static class KBNameConflictError extends KnowledgeBaseException {
        public KBNameConflictError(String message) {
            super(message);
        }
    }
}
