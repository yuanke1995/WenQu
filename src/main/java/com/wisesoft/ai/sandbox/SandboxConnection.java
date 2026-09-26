package com.wisesoft.ai.sandbox;

import java.util.Objects;

/**
 * 沙盒连接（对应参考实现 provider.py 的 {@code SandboxConnection} dataclass，slots=True）。
 *
 * <p>字段逐字对齐：cache_key / thread_id / uid / sandbox_id / sandbox_url /
 * generation / workdir_path。
 */
public class SandboxConnection {

    private final String cacheKey;
    private final String threadId;
    private final String uid;
    private final String sandboxId;
    private String sandboxUrl;
    private String generation;
    private final String workdirPath;

    public SandboxConnection(
            String cacheKey,
            String threadId,
            String uid,
            String sandboxId,
            String sandboxUrl,
            String generation,
            String workdirPath) {
        this.cacheKey = cacheKey;
        this.threadId = threadId;
        this.uid = uid;
        this.sandboxId = sandboxId;
        this.sandboxUrl = sandboxUrl;
        this.generation = generation;
        this.workdirPath = workdirPath;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getUid() {
        return uid;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public String getSandboxUrl() {
        return sandboxUrl;
    }

    public void setSandboxUrl(String sandboxUrl) {
        this.sandboxUrl = sandboxUrl;
    }

    public String getGeneration() {
        return generation;
    }

    public void setGeneration(String generation) {
        this.generation = generation;
    }

    public String getWorkdirPath() {
        return workdirPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SandboxConnection that)) {
            return false;
        }
        return Objects.equals(cacheKey, that.cacheKey)
                && Objects.equals(threadId, that.threadId)
                && Objects.equals(uid, that.uid)
                && Objects.equals(sandboxId, that.sandboxId)
                && Objects.equals(sandboxUrl, that.sandboxUrl)
                && Objects.equals(generation, that.generation)
                && Objects.equals(workdirPath, that.workdirPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cacheKey, threadId, uid, sandboxId, sandboxUrl, generation, workdirPath);
    }

    @Override
    public String toString() {
        return "SandboxConnection{cacheKey=" + cacheKey + ", sandboxId=" + sandboxId
                + ", sandboxUrl=" + sandboxUrl + ", generation=" + generation + ", workdirPath=" + workdirPath + "}";
    }
}
