package com.wenqu.ai.ssh;

/**
 * SSH 操作失败：带稳定错误码（与 ExecutorRegistry.Result.code() 同一套口径）。
 *
 * @author yuanke
 */
public class SshOpException extends RuntimeException {

    private final String code;
    /** 这次失败是否像“连接断了”——调用方可据此决定是否重连一次。 */
    private final boolean disconnectLikely;

    public SshOpException(String code, String message) {
        this(code, message, false);
    }

    public SshOpException(String code, String message, boolean disconnectLikely) {
        super(message);
        this.code = code;
        this.disconnectLikely = disconnectLikely;
    }

    public SshOpException(String code, String message, Throwable cause, boolean disconnectLikely) {
        super(message, cause);
        this.code = code;
        this.disconnectLikely = disconnectLikely;
    }

    public String code() {
        return code;
    }

    public boolean disconnectLikely() {
        return disconnectLikely;
    }
}
