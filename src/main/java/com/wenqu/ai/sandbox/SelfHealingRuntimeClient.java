package com.wenqu.ai.sandbox;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 自愈版 runtime 客户端：**沙盒容器被带外删除时，摘掉作用域缓存并重试一次**。
 *
 * <p><b>治的病</b>：容器被 {@code docker rm} / provisioner 重启 / 节点漂移删掉后，Java 侧连接缓存
 * 仍指着那个死 URL。正常空闲回收走 {@code provider.release} 会摘缓存，带外删除绕过了那条路，
 * 于是下一次调用就撞 {@code 404 sandbox not found}（或连不上），用户看到的是「工具报了个看不懂的错」。
 * 修法不是等 touch 的节流窗口过去，而是当场自愈。
 *
 * <p><b>只认两种信号</b>：{@code sandbox not found} 与连接层失败（connection refused / host
 * unreachable）。<b>刻意不把「文件不存在」当自愈信号</b>——runtime 对缺失文件的 404 与沙盒消失
 * 长得一样，重试只会白白重建一个容器、还把真实的「文件没有」掩盖成别的错。
 *
 * <p><b>只重试一次</b>：重建后仍失败就是真失败，照原样上抛（绝不静默成功）。
 */
@Slf4j
final class SelfHealingRuntimeClient implements SandboxRuntimeClient {

    /** 判定「这个作用域的沙盒没了」的错误特征（小写比对）。 */
    private static final String[] GONE_MARKERS = {
            "sandbox not found",
            "sandbox not exist",
            "connection refused",
            "no such host",
            "connect timed out",
    };

    private final ProvisionerSandboxProvider provider;
    private final String threadId;
    private final String uid;
    private final Supplier<SandboxRuntimeClient> rebuild;   // 重新走 provider.get(...)，拿新容器
    private volatile SandboxRuntimeClient delegate;

    SelfHealingRuntimeClient(ProvisionerSandboxProvider provider,
                             String threadId,
                             String uid,
                             SandboxRuntimeClient initial,
                             Supplier<SandboxRuntimeClient> rebuild) {
        this.provider = provider;
        this.threadId = threadId;
        this.uid = uid;
        this.delegate = initial;
        this.rebuild = rebuild;
    }

    private static boolean looksGone(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        for (String marker : GONE_MARKERS) {
            if (msg.contains(marker)) return true;
        }
        return false;
    }

    /** 一次调用 + 命中「沙盒没了」就自愈重试（只一次）。 */
    private <T> T call(String op, java.util.function.Function<SandboxRuntimeClient, T> action) {
        try {
            return action.apply(delegate);
        } catch (RuntimeException first) {
            if (!looksGone(first)) {
                throw first;
            }
            log.warn("[SANDBOX] 操作 {} 命中沙盒消失（{}），摘缓存重建后重试一次", op, first.getMessage());
            provider.invalidate(threadId, uid);
            SandboxRuntimeClient fresh = rebuild.get();     // 会重新 create/discover
            this.delegate = fresh;
            return action.apply(fresh);
        }
    }

    @Override
    public SandboxReadFileResult readFile(String file, Integer startLine, Integer endLine) {
        return call("readFile", c -> c.readFile(file, startLine, endLine));
    }

    @Override
    public SandboxWriteResult writeFile(String file, String content) {
        return call("writeFile", c -> c.writeFile(file, content));
    }

    @Override
    public SandboxWriteResult writeFile(String file, String content, String encoding) {
        return call("writeFile(enc)", c -> c.writeFile(file, content, encoding));
    }

    @Override
    public SandboxWriteResult strReplaceEditor(String command, String path, String oldStr, String newStr, String replaceMode) {
        return call("strReplaceEditor", c -> c.strReplaceEditor(command, path, oldStr, newStr, replaceMode));
    }

    @Override
    public SandboxListPathResult listPath(String path, boolean recursive, boolean includeSize) {
        return call("listPath", c -> c.listPath(path, recursive, includeSize));
    }

    @Override
    public SandboxFindFilesResult findFiles(String path, String glob) {
        return call("findFiles", c -> c.findFiles(path, glob));
    }

    @Override
    public Iterable<byte[]> downloadFile(String path, Duration timeout) {
        // 流式返回：无法在拿到结果后自愈（数据已流出），交给调用方上层处理
        return delegate.downloadFile(path, timeout);
    }

    @Override
    public SandboxWriteResult uploadFile(File source, String path) {
        return call("uploadFile", c -> c.uploadFile(source, path));
    }

    @Override
    public SandboxExecResult execCommand(String command, Duration timeout, boolean truncate) {
        return call("execCommand", c -> c.execCommand(command, timeout, truncate));
    }
}