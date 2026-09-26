package com.wisesoft.ai.sandbox;

import java.util.List;

/**
 * 沙盒文件系统 backend 的 Java 端口（对应参考实现 backend.py 的
 * {@code ProvisionerSandboxBackend} 所暴露的 {@code ls} / {@code download_files} 契约）。
 *
 * <p>参考实现经沙盒 provisioner HTTP 接口落地这些操作；本工程已部署 provisioner，具体实现
 * 在搬运 backend.py 对应批次前由 {@link ProvisionerSandboxBackend} 统一抛
 * {@link SandboxProvisionerException}（能力差异，绝不静默成功）。下载逻辑见
 * {@link SandboxDownload}，仅依赖本接口，与具体 backend 实现解耦。
 */
public interface SandboxFsBackend {

    /** 列出目录条目（对应参考实现 {@code backend.ls(dir)} 的返回对象）。 */
    LsResult ls(String dir);

    /** 批量下载文件（对应参考实现 {@code backend.download_files([...])} 的返回列表）。 */
    List<DownloadResult> downloadFiles(List<String> paths);

    /** {@code ls} 结果：error 非空表示失败；entries 为目录条目列表。 */
    final class LsResult {
        public final String error;
        public final List<FsEntry> entries;

        public LsResult(String error, List<FsEntry> entries) {
            this.error = error;
            this.entries = entries;
        }

        public boolean hasError() {
            return error != null;
        }
    }

    /** 目录条目（对应参考实现 entry dict 的 path / is_dir / size）。 */
    final class FsEntry {
        public final String path;
        public final boolean isDir;
        public final Long size;

        public FsEntry(String path, boolean isDir, Long size) {
            this.path = path;
            this.isDir = isDir;
            this.size = size;
        }
    }

    /** 单文件下载结果（对应参考实现 download_files 返回列表的元素）。 */
    final class DownloadResult {
        public final String error;
        public final byte[] content;

        public DownloadResult(String error, byte[] content) {
            this.error = error;
            this.content = content;
        }

        public boolean hasError() {
            return error != null;
        }
    }
}
