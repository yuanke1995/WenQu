package com.wenqu.ai.common;

/** 上传冲突：conflict=exists（内容完全相同）/ newVersion（同名但内容不同，需确认覆盖）。 */
public class DuplicateUploadException extends BizException {

    private final String conflict;

    public DuplicateUploadException(String conflict, String message) {
        super(message);
        this.conflict = conflict;
    }

    public String conflict() {
        return conflict;
    }
}
