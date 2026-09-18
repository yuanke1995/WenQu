package com.wisesoft.wenqu.workspace;

/**
 * Workspace 文件边界的中立异常。
 *
 * <p>由参考实现的 workspace/errors.py 逐类翻译。
 */
public class WorkspaceErrors {

    private WorkspaceErrors() {}

    /** 受信任文件传输超过调用方声明的字节上限。 */
    public static class FileTransferLimitError extends IllegalArgumentException {
        public FileTransferLimitError(String message) {
            super(message);
        }
    }
}
