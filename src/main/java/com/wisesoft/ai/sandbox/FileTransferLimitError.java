package com.wisesoft.ai.sandbox;

/**
 * 文件传输超限（对应参考实现 yuxi.workspace.errors.FileTransferLimitError；参考单测以
 * {@code ValueError} 捕获，因其语义为取值越界，本工程以其独立异常形态保留）。
 */
public class FileTransferLimitError extends RuntimeException {
    public FileTransferLimitError(String message) {
        super(message);
    }
}
