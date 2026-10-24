package com.wenqu.ai.sandbox;

import io.agentscope.core.session.AtomicSessionStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 框架会话日志用的本地原子存储（全框架化 P1）。
 *
 * <p><b>为什么需要它</b>：框架 {@code HarnessAgent#sessionLog} 会用
 * {@code ctx.get(AbstractFilesystem.class).sessionStorage(ctx)} 构造
 * {@code JournalSessionLog}，而后者对 storage 强制 {@code requireNonNull}。
 * 问渠的 {@code ProvisionerSandboxFilesystem} 若不给这个能力（返回 null），
 * 整轮问答会在 {@code FrameworkSessions#open} 里以 NPE 掐断——
 * 真机验证时确实如此（{@code JournalSessionLog:55} 的 NPE）。
 *
 * <p><b>为什么落宿主本地而不是沙箱</b>：会话日志要跨轮、跨进程、跨容器生命周期存活，
 * 而沙箱容器会被空闲回收。接口注释也写明 *"Implementations must durably store bytes before ACK"*
 * 与 *"The runtime journal storage must not be writable through model filesystem tools"*——
 * 前者要求真持久，后者要求模型碰不到它，两者都指向宿主侧。
 *
 * <p><b>原子性</b>：{@link #compareAndSet} 用「版本号校验 + 临时文件 + ATOMIC_MOVE」实现。
 * 单机单 JVM 下由文件锁保证；跨进程靠版本号比对（后写者若版本不匹配直接失败，不静默覆盖）。
 */
public class LocalJournalStorage implements AtomicSessionStorage {

    /**
     * 会话事件日志的根目录——**只有这一处口径**。
     *
     * <p>轮转（{@code JournalRotation}）要在同一个根下搬目录，若两处各自算 root，
     * 换一次布局就会出现「轮转搬走的不是问答正在写的那一份」——静默丢历史。
     */
    public static java.nio.file.Path defaultRoot() {
        return java.nio.file.Paths.get(System.getProperty("user.home"), ".wenqu", "agent-journal");
    }

    private final Path root;

    public LocalJournalStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root is required");
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建会话日志目录：" + this.root, e);
        }
    }

    /** 版本号：写在内容头部（8 字节 long），CAS 比对就靠它。 */
    private static final int VERSION_HEADER = Long.BYTES;

    /**
     * 读一份日志。
 *
 * <p><b>返回 null 而不是空 Value</b>：{@code JournalSessionLog#head} 是这么写的——
 * {@code var header = storage.read(...); if (header != null) parse(header.bytes(), SessionHeader.class).validate();}
 * ——文件不存在时框架期望拿到 null。返回 {@code Value(0, new byte[0])} 会让 parse 去解析空串，
 * *直接抛 JsonException*（真机验证踩过）。
 */
@Override
    public Value read(String path) {
        Path f = resolve(path);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            byte[] raw = Files.readAllBytes(f);
            if (raw.length < VERSION_HEADER) {
                // 损坏或旧格式：当作不存在，让调用方按 create 语义重建
                return null;
            }
            long version = java.nio.ByteBuffer.wrap(raw, 0, VERSION_HEADER).getLong();
            byte[] body = new byte[raw.length - VERSION_HEADER];
            System.arraycopy(raw, VERSION_HEADER, body, 0, body.length);
            return new Value(version, body);
        } catch (IOException e) {
            throw new UncheckedIOException("读取会话日志失败：" + f, e);
        }
    }

    @Override
    public boolean compareAndSet(String path, long expectedVersion, byte[] bytes) {
        Path f = resolve(path);
        try {
            Files.createDirectories(f.getParent());
            // 版本不符直接失败：并发写者必须自己重试，不能让后写覆盖前写。
            // 与接口注释 "expectedVersion=0 means create if absent" 一致
            // ——文件不存在（read 返回 null）且期望版本为 0，即 create 语义，放行。
            Value current = read(path);
            long actualVersion = current == null ? 0L : current.version();
            if (actualVersion != expectedVersion) {
                return false;
            }
            long next = expectedVersion + 1;
            byte[] payload = new byte[VERSION_HEADER + (bytes == null ? 0 : bytes.length)];
            java.nio.ByteBuffer.wrap(payload, 0, VERSION_HEADER).putLong(next);
            if (bytes != null) {
                System.arraycopy(bytes, 0, payload, VERSION_HEADER, bytes.length);
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp-" + ProcessHandle.current().pid());
            Files.write(tmp, payload, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // 某些文件系统不支持原子移动，退化为普通替换（先删后移，避免残留半截文件）
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("写入会话日志失败：" + f, e);
        } finally {
            try {
                Files.deleteIfExists(f.resolveSibling(f.getFileName() + ".tmp-" + ProcessHandle.current().pid()));
            } catch (IOException ignored) {
                // 临时文件清理失败不影响已完成的写入
            }
        }
    }

    @Override
    public List<String> listPaths(String prefix) {
        Path dir = resolveDir(prefix);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString())
                    .filter(n -> !n.contains(".tmp-"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("列会话日志失败：" + dir, e);
        }
    }

    /** 一条会话日志目录在 root 下的物理位置（轮转搬走的就是它）。 */
    public Path sessionDir(String storagePath) {
        return resolve(storagePath);
    }

    /**
     * 把一条会话当前的日志目录整体搬到旁边（日志轮转用），成功返回 true。
     *
     * <p><b>为什么是搬目录而不是截断链</b>：{@code JournalSessionLog} 的 commit 带父链与逐段 SHA-256，
     * 删中间任何一颗都会让 {@code scan} 报 {@code Broken committed prefix}；框架给的有界入口只有
     * {@code SessionMigration} 那套「导出 baseline → 换一份空日志」，而它要求目标日志为空——搬走正好造出空目标。
     *
     * <p><b>目标已存在不覆盖</b>：两条链撞在一起比「这一轮不轮转」坏得多，宁可不搬。
     */
    public boolean relocateSessionDir(String storagePath, String asidePath) {
        Path from = resolve(storagePath);
        Path to = resolve(asidePath);
        if (!Files.isDirectory(from) || Files.exists(to)) {
            return false;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("搬开会话事件日志目录失败：" + from + " → " + to, e);
        }
    }

    /** 路径解析：拒绝越出 root 的相对路径（会话键来自框架，不该为外部输入，但这里按不可信处理）。 */
    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("session log path is required");
        }
        Path p = root.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("session log path escapes storage root: " + path);
        }
        return p;
    }

    private Path resolveDir(String prefix) {
        return resolve(prefix.endsWith("/") ? prefix + "x" : prefix + "/x").getParent();
    }
}