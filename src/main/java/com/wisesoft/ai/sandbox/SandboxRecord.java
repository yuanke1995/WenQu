package com.wisesoft.ai.sandbox;

import java.util.Objects;

/**
 * 沙盒记录（对应参考实现 provisioner_client.py 的 {@code SandboxRecord} dataclass，slots=True）。
 *
 * <p>字段逐字对齐：sandbox_id / sandbox_url / status / generation / workdir_path。
 */
public class SandboxRecord {

    private final String sandboxId;
    private final String sandboxUrl;
    private final String status;
    private final String generation;
    private final String workdirPath;

    public SandboxRecord(
            String sandboxId, String sandboxUrl, String status, String generation, String workdirPath) {
        this.sandboxId = sandboxId;
        this.sandboxUrl = sandboxUrl;
        this.status = status;
        this.generation = generation;
        this.workdirPath = workdirPath;
    }

    public String getSandboxId() {
        return sandboxId;
    }

    public String getSandboxUrl() {
        return sandboxUrl;
    }

    public String getStatus() {
        return status;
    }

    public String getGeneration() {
        return generation;
    }

    public String getWorkdirPath() {
        return workdirPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SandboxRecord that)) {
            return false;
        }
        return Objects.equals(sandboxId, that.sandboxId)
                && Objects.equals(sandboxUrl, that.sandboxUrl)
                && Objects.equals(status, that.status)
                && Objects.equals(generation, that.generation)
                && Objects.equals(workdirPath, that.workdirPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sandboxId, sandboxUrl, status, generation, workdirPath);
    }

    @Override
    public String toString() {
        return "SandboxRecord{sandboxId=" + sandboxId + ", sandboxUrl=" + sandboxUrl
                + ", status=" + status + ", generation=" + generation
                + ", workdirPath=" + workdirPath + "}";
    }
}
