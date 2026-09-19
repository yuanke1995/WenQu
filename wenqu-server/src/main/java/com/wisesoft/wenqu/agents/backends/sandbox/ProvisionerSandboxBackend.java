package com.wisesoft.wenqu.agents.backends.sandbox;

import java.util.List;

/**
 * 沙盒 backend（对应参考实现 backends/sandbox/backend.py 的 {@code ProvisionerSandboxBackend}）。
 *
 * <p><b>能力差异（显式标注）</b>：参考实现继承 deepagents 的沙盒 backend 基类，并经沙盒
 * provisioner HTTP 接口实现 {@code ls} / {@code download_files} / {@code write_file} 等文件
 * 系统操作。本项目未照搬 deepagents 框架，也未部署沙盒 provisioner（见 dify / notion /
 * skill_remote_install / install_skill / download_kb_file 同一口径），故此处先承载构造签名
 * 与 {@link SandboxFsBackend} 接口契约，具体文件系统操作在搬运 backend.py 对应批次前统一抛
 * {@link SandboxProvisionerException}——<b>绝不静默成功</b>。
 */
public class ProvisionerSandboxBackend implements SandboxFsBackend {

    private final String threadId;
    private final String uid;
    private final String workdirPath;
    private final boolean createIfMissing;

    public ProvisionerSandboxBackend(String threadId, String uid, String workdirPath, boolean createIfMissing) {
        this.threadId = threadId;
        this.uid = uid;
        this.workdirPath = workdirPath;
        this.createIfMissing = createIfMissing;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getUid() {
        return uid;
    }

    public String getWorkdirPath() {
        return workdirPath;
    }

    public boolean isCreateIfMissing() {
        return createIfMissing;
    }

    @Override
    public LsResult ls(String dir) {
        throw new SandboxProvisionerException(
                "沙盒 backend 未部署：ProvisionerSandboxBackend.ls 不可用"
                        + "（deepagents 框架未照搬 / provisioner 未配置）");
    }

    @Override
    public List<DownloadResult> downloadFiles(List<String> paths) {
        throw new SandboxProvisionerException(
                "沙盒 backend 未部署：ProvisionerSandboxBackend.downloadFiles 不可用"
                        + "（deepagents 框架未照搬 / provisioner 未配置）");
    }
}
