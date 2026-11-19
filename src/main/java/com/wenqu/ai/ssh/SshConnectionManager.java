package com.wenqu.ai.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * SSH 连接池：按主机缓存 JSch {@link Session}，保活、断线重连、并发 channel。
 *
 * <p><b>为什么按主机缓存 Session 而不是每次新建</b>：SSH 握手（尤其 RSA/键盘交互认证）在公网上
 * 常是 200ms~1s 级；一次文件工具调用可能连续做 stat/list/read 三次，每次都握手会把「看一眼目录」
 * 拖成数秒。Session 内可并发开多个 channel（exec/sftp/shell），SFTP channel 每次操作单独开关即可。
 *
 * <p><b>失效与重连</b>：{@code updateTime} 变化（凭据/地址被改）或 {@link #invalidate(String)}
 * 会让旧 Session 作废；执行期遇到断链只重连一次——写操作不自动重放（非幂等）。
 *
 * <p><b>指纹</b>：连接建立后立即取 {@code session.getHostKey()} 计算 {@code SHA256:...}；
 * 已固定指纹不一致时断开并抛 {@link HostKeyMismatchException}；未固定则调用方（HostService）决定是否 TOFU 记录。
 *
 * @author yuanke
 */
@Service
public class SshConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(SshConnectionManager.class);

    /** 连接超时：公网 + 弱网留足，但也不能让一次工具调用挂太久。 */
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int SOCKET_TIMEOUT_MS = 60_000;
    private static final int KEEPALIVE_MS = 30_000;
    private static final int MAX_OUTPUT_BYTES = 1_000_000;

    /** 执行超时后的兜底线程池：channel 不响应时由它负责 disconnect。 */
    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ssh-io");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Pooled> pool = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** 连接结果信息（测试连接 / 审计用）。 */
    public record ConnectInfo(String serverVersion, String fingerprint, String algorithm) {
    }

    /** 一次 exec 的结果。{@code exitCode=null} 表示命令没跑完（超时/断链）。 */
    public record ExecResult(String output, Integer exitCode, boolean truncated) {
    }

    /** 测试连接结果：banner/指纹/算法 + 远端 home 与 os。 */
    public record TestResult(String serverVersion, String fingerprint, String algorithm,
                             String home, String os) {
    }

    private static final class Pooled {
        final Session session;
        final String version;

        Pooled(Session session, String version) {
            this.session = session;
            this.version = version;
        }
    }

    private static String versionOf(SshHost host) {
        return host.getUpdateTime() == null ? "" : host.getUpdateTime().toString();
    }

    private Object lockFor(String hostId) {
        return locks.computeIfAbsent(hostId, k -> new Object());
    }

    private Session session(SshHost host, SshCredentials creds) throws JSchException {
        String version = versionOf(host);
        Pooled pooled = pool.get(host.getId());
        if (pooled != null && version.equals(pooled.version) && pooled.session.isConnected()) {
            return pooled.session;
        }
        synchronized (lockFor(host.getId())) {
            pooled = pool.get(host.getId());
            if (pooled != null && version.equals(pooled.version) && pooled.session.isConnected()) {
                return pooled.session;
            }
            if (pooled != null) {
                try {
                    pooled.session.disconnect();
                } catch (Exception ignore) {
                }
                pool.remove(host.getId(), pooled);
            }
            Session fresh = connect(host, creds, CONNECT_TIMEOUT_MS);
            pool.put(host.getId(), new Pooled(fresh, version));
            return fresh;
        }
    }

    /**
     * 新建一条 SSH 连接并做指纹校验。
     *
     * @throws HostKeyMismatchException 已固定指纹与本次不一致
     * @throws JSchException            网络/认证失败
     */
    private Session connect(SshHost host, SshCredentials creds, int timeoutMs) throws JSchException {
        JSch jsch = new JSch();
        try {
            if (creds.keyAuth()) {
                byte[] key = creds.privateKey() == null ? null : creds.privateKey().getBytes(StandardCharsets.UTF_8);
                byte[] pass = creds.passphrase() == null || creds.passphrase().isEmpty()
                        ? null : creds.passphrase().getBytes(StandardCharsets.UTF_8);
                if (key == null || key.length == 0) {
                    throw new JSchException("私钥为空：请重新填写私钥");
                }
                jsch.addIdentity("wenqu-" + host.getId(), key, null, pass);
            }
        } catch (JSchException e) {
            throw e;
        } catch (Exception e) {
            throw new JSchException("加载私钥失败：" + e.getMessage(), e);
        }
        Session session = jsch.getSession(creds.username(), creds.host(), creds.port());
        if (creds.passwordAuth()) {
            session.setPassword(creds.password() == null ? "" : creds.password());
        }
        session.setConfig("StrictHostKeyChecking", "no"); // 指纹由下面手工严格校验
        session.setConfig("PreferredAuthentications",
                creds.keyAuth() ? "publickey" : "password,keyboard-interactive,publickey");
        session.setServerAliveInterval(KEEPALIVE_MS);
        session.setServerAliveCountMax(3);
        session.setTimeout(SOCKET_TIMEOUT_MS);
        try {
            session.connect(timeoutMs);
        } catch (JSchException e) {
            throw e;
        }
        try {
            HostKey hk = session.getHostKey();
            String fingerprint = fingerprint(hk.getKey());
            String pinned = creds.pinnedFingerprint();
            if (pinned != null && !pinned.isBlank() && !constantTimeEquals(pinned, fingerprint)) {
                session.disconnect();
                throw new HostKeyMismatchException("SSH 主机指纹与已保存的不一致（可能是中间人攻击或主机重装）："
                        + "已保存=" + pinned + " 本次=" + fingerprint);
            }
            return session;
        } catch (RuntimeException e) {
            if (!(e instanceof HostKeyMismatchException)) {
                try {
                    session.disconnect();
                } catch (Exception ignore) {
                }
            }
            throw e;
        }
    }

    /** 计算 SHA256 指纹（OpenSSH 风格：{@code SHA256:base64url-no-padding}）。JSch 的 getKey() 是 base64 的公钥 blob。 */
    public static String fingerprint(String keyBase64) {
        byte[] keyBlob;
        try {
            keyBlob = Base64.getDecoder().decode(keyBase64 == null ? "" : keyBase64);
        } catch (IllegalArgumentException e) {
            keyBlob = (keyBase64 == null ? "" : keyBase64).getBytes(StandardCharsets.UTF_8);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(keyBlob);
            String b64 = Base64.getEncoder().withoutPadding().encodeToString(digest);
            return "SHA256:" + b64;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 测试连接 ====================

    /**
     * 测试连接：独立建连（不污染池），返回 banner/指纹 + home/os，最后一定断开。
     * 调用方据此展示指纹供用户核对、并把指纹 TOFU 落库。
     */
    public TestResult test(SshHost host, SshCredentials creds, int timeoutMs) {
        Session session = null;
        try {
            session = connect(host, creds, timeoutMs);
            HostKey hk = session.getHostKey();
            String fingerprint = fingerprint(hk.getKey());
            String algorithm = hk.getType();
            String version = session.getServerVersion();
            String home = execOn(session, "printf %s \"$HOME\"", 5_000).output().strip();
            String os = execOn(session, "uname -s 2>/dev/null || echo unknown", 5_000).output().strip();
            return new TestResult(version, fingerprint, algorithm, home, os);
        } catch (JSchException e) {
            throw translate(e);
        } finally {
            if (session != null) {
                try {
                    session.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    // ==================== 执行 / SFTP / Shell ====================

    public ExecResult exec(SshHost host, SshCredentials creds, String command, int timeoutMs) {
        Session session = sessionOrThrow(host, creds);
        return execOn(session, command, timeoutMs);
    }

    private Session sessionOrThrow(SshHost host, SshCredentials creds) {
        try {
            return session(host, creds);
        } catch (JSchException e) {
            throw translate(e);
        }
    }

    /** 在给定 session 上跑一条命令；超时会 disconnect channel 并返回 exitCode=null。 */
    public ExecResult execOn(Session session, String command, int timeoutMs) {
        Future<ExecResult> future = ioPool.submit(() -> doExec(session, command, timeoutMs));
        try {
            return future.get(Math.max(1_000, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new ExecResult("", null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult("", null, false);
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new SshOpException("E_IO", "SSH 命令执行失败：" + e.getMessage());
        }
    }

    private ExecResult doExec(Session session, String command, int timeoutMs) {
        ChannelExec channel = null;
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        boolean[] truncated = {false};
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(err);
            InputStream in = channel.getInputStream();
            channel.connect(Math.min(timeoutMs, 15_000));
            byte[] buf = new byte[8192];
            long deadline = System.currentTimeMillis() + timeoutMs;
            int n;
            while ((n = in.read(buf)) != -1) {
                appendCapped(kept, buf, n, truncated);
                if (System.currentTimeMillis() > deadline) {
                    return new ExecResult(kept.toString(StandardCharsets.UTF_8), null, true);
                }
            }
            // 等 channel 关闭以拿到退出码
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Integer exit = channel.isClosed() ? channel.getExitStatus() : null;
            String out = kept.toString(StandardCharsets.UTF_8);
            if (err.size() > 0) {
                out = out + (out.isEmpty() ? "" : "\n") + err.toString(StandardCharsets.UTF_8);
            }
            return new ExecResult(out, exit, truncated[0]);
        } catch (JSchException e) {
            throw translate(e);
        } catch (Exception e) {
            throw new SshOpException("E_IO", "SSH 命令执行失败：" + e.getMessage());
        } finally {
            if (channel != null) {
                try {
                    channel.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static void appendCapped(ByteArrayOutputStream out, byte[] buf, int n, boolean[] truncated) {
        int room = MAX_OUTPUT_BYTES - out.size();
        if (room <= 0) {
            truncated[0] = true;
            return;
        }
        if (n > room) {
            out.write(buf, 0, room);
            truncated[0] = true;
        } else {
            out.write(buf, 0, n);
        }
    }

    /** 在池化 session 上开一个 SFTP channel 执行回调；断链重连一次。 */
    public <T> T withSftp(SshHost host, SshCredentials creds, Function<ChannelSftp, T> fn) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Session session = sessionOrThrow(host, creds);
            ChannelSftp sftp = null;
            try {
                sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect(CONNECT_TIMEOUT_MS);
                return fn.apply(sftp);
            } catch (JSchException e) {
                last = translate(e);
                if (attempt == 0) invalidate(host.getId());
            } catch (SshOpException e) {
                last = e;
                if (attempt == 0 && e.disconnectLikely()) invalidate(host.getId());
                else throw e;
            } finally {
                if (sftp != null) {
                    try {
                        sftp.disconnect();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        throw last == null ? new SshOpException("E_IO", "SFTP 通道不可用") : last;
    }

    /** 打开交互式 PTY shell（调用方负责读写与关闭）。 */
    public ShellHandle openShell(SshHost host, SshCredentials creds, int cols, int rows) {
        Session session = sessionOrThrow(host, creds);
        try {
            ChannelShell channel = (ChannelShell) session.openChannel("shell");
            channel.setPtyType("xterm-256color");
            channel.setPtySize(Math.max(20, cols), Math.max(5, rows), 0, 0);
            InputStream out = channel.getInputStream();
            OutputStream in = channel.getOutputStream();
            channel.connect(CONNECT_TIMEOUT_MS);
            return new ShellHandle(channel, out, in);
        } catch (JSchException e) {
            throw translate(e);
        } catch (Exception e) {
            throw new SshOpException("E_IO", "SSH 终端通道打开失败：" + e.getMessage());
        }
    }

    /** 一条交互式 shell 的句柄。 */
    public static final class ShellHandle implements AutoCloseable {
        private final ChannelShell channel;
        private final InputStream in;
        private final OutputStream out;

        ShellHandle(ChannelShell channel, InputStream in, OutputStream out) {
            this.channel = channel;
            this.in = in;
            this.out = out;
        }

        public InputStream input() {
            return in;
        }

        public OutputStream output() {
            return out;
        }

        public boolean alive() {
            return channel.isConnected();
        }

        public void resize(int cols, int rows) {
            try {
                channel.setPtySize(Math.max(20, cols), Math.max(5, rows), 0, 0);
            } catch (Exception ignore) {
            }
        }

        @Override
        public void close() {
            try {
                out.close();
            } catch (Exception ignore) {
            }
            try {
                channel.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    // ==================== 失效 / 关闭 ====================

    public void invalidate(String hostId) {
        Pooled pooled = pool.remove(hostId);
        if (pooled != null) {
            try {
                pooled.session.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    public boolean isConnected(String hostId) {
        Pooled p = pool.get(hostId);
        return p != null && p.session.isConnected();
    }

    /** SFTP 错误 → 稳定错误码。 */
    public static SshOpException translate(com.jcraft.jsch.SftpException e) {
        int id = e.id;
        if (id == com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            return new SshOpException("E_NOT_FOUND", "远端文件不存在：" + e.getMessage());
        }
        if (id == com.jcraft.jsch.ChannelSftp.SSH_FX_PERMISSION_DENIED) {
            return new SshOpException("E_PERMISSION", "远端权限不足：" + e.getMessage());
        }
        return new SshOpException("E_IO", "SFTP 错误：" + e.getMessage());
    }

    public static SshOpException translate(JSchException e) {        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("auth fail") || lower.contains("auth cancel")
                || lower.contains("userauth fail") || lower.contains("permission denied")) {
            return new SshOpException("E_PERMISSION", "SSH 认证失败：用户名/密码或私钥不正确（" + msg + "）");
        }
        if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("connection refused") || lower.contains("unknownhost")
                || lower.contains("no route to host") || lower.contains("network is unreachable")
                || lower.contains("connection reset") || lower.contains("broken pipe")) {
            return new SshOpException("E_EXECUTOR_OFFLINE", "SSH 主机连不上：检查地址/端口/防火墙（" + msg + "）", true);
        }
        return new SshOpException("E_IO", "SSH 连接失败：" + msg);
    }

    @PreDestroy
    public void shutdown() {
        for (String id : pool.keySet()) invalidate(id);
        ioPool.shutdownNow();
    }
}
