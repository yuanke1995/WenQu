package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用内嵌 Apache MINA sshd 做真远端（真实 JSch 客户端 + SFTP + exec），
 * 验证 SSH 执行器的 op 语义、路径围栏与指纹校验。
 */
class SshRemoteExecutorIT {

    @TempDir
    Path temp;

    private SshServer server;
    private SshConnectionManager manager;
    private SshHostService hostService;
    private SshHost host;

    @BeforeEach
    void startServer() throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        Path hostKey = temp.resolve("hostkey.ser");
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((u, p, s) -> "wenqu".equals(u) && "secret".equals(p));
        server.setFileSystemFactory(new NativeFileSystemFactory());
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setCommandFactory(commandFactory());
        server.start();

        host = new SshHost();
        host.setId("host-1");
        host.setUid("u1");
        host.setName("test");
        host.setHost("127.0.0.1");
        host.setPort(server.getPort());
        host.setUsername("wenqu");
        host.setAuthType(SshHost.AUTH_PASSWORD);
        host.setStatus(SshHost.STATUS_ACTIVE);
        host.setUpdateTime(java.time.LocalDateTime.now());

        manager = new SshConnectionManager();
        hostService = mock(SshHostService.class);
        when(hostService.byId("host-1")).thenReturn(host);
        when(hostService.credentialsOf(host)).thenReturn(creds(null));
    }

    @AfterEach
    void stopServer() throws IOException {
        if (manager != null) manager.shutdown();
        if (server != null) server.stop(true);
    }

    private SshCredentials creds(String pinned) {
        return new SshCredentials("host-1", "127.0.0.1", server.getPort(), "wenqu",
                SshHost.AUTH_PASSWORD, "secret", null, null, pinned);
    }

    private SshRemoteExecutor executor() {
        return new SshRemoteExecutor("host-1", hostService, manager, mock(SshAuditService.class));
    }

    private List<Map<String, Object>> roots() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("alias", "app");
        r.put("path", temp.toString());
        r.put("primary", true);
        return List.of(r);
    }

    private Map<String, Object> args(String path, Object... kv) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (path != null) a.put("path", path);
        a.put("roots", roots());
        a.put("policy", "workspace-write");
        for (int i = 0; i + 1 < kv.length; i += 2) a.put(String.valueOf(kv[i]), kv[i + 1]);
        return a;
    }

    @Test
    void execRunsCommandInPrimaryRoot() throws Exception {
        Files.writeString(temp.resolve("hello.txt"), "hi");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("command", "cat hello.txt && echo done");
        a.put("timeoutSeconds", 10);
        a.put("roots", roots());
        a.put("policy", "workspace-write");
        ExecutorRegistry.Result r = executor().execute("exec", a);
        assertThat(r.ok()).isTrue();
        assertThat(String.valueOf(r.data().get("output"))).contains("hi").contains("done");
        assertThat(((Number) r.data().get("exitCode")).intValue()).isZero();
    }

    @Test
    void listAndStatReadRealFiles() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "alpha");
        ExecutorRegistry.Result list = executor().execute("list", args(temp.toString()));
        assertThat(list.ok()).isTrue();
        List<?> entries = (List<?>) list.data().get("entries");
        assertThat(entries).anySatisfy(e -> assertThat(((Map<?, ?>) e).get("name")).isEqualTo("a.txt"));

        ExecutorRegistry.Result stat = executor().execute("stat", args(temp.resolve("a.txt").toString()));
        assertThat(stat.ok()).isTrue();
        assertThat(((Number) stat.data().get("size")).longValue()).isEqualTo(5L);
    }

    @Test
    void writeThenReadRoundTrips() throws Exception {
        Path f = temp.resolve("dir/b.txt");
        ExecutorRegistry.Result w = executor().execute("write", args(f.toString(), "content", "你好 wenqu"));
        assertThat(w.ok()).isTrue();
        assertThat(Files.readString(f)).isEqualTo("你好 wenqu");

        ExecutorRegistry.Result r = executor().execute("read", args(f.toString(), "offset", 0, "limit", 0));
        assertThat(r.ok()).isTrue();
        assertThat(String.valueOf(r.data().get("content"))).isEqualTo("你好 wenqu");
    }

    @Test
    void staleWriteIsRejectedWhenHashMismatch() throws Exception {
        Path f = temp.resolve("c.txt");
        Files.writeString(f, "old");
        ExecutorRegistry.Result w = executor().execute("write",
                args(f.toString(), "content", "new", "expectedHash", "deadbeef"));
        assertThat(w.ok()).isFalse();
        assertThat(w.code()).isEqualTo("E_STALE");
        assertThat(Files.readString(f)).isEqualTo("old");
    }

    @Test
    void pathOutsideRootsIsRejected() {
        ExecutorRegistry.Result r = executor().execute("read", args("/etc/passwd"));
        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isEqualTo("E_PATH_OUTSIDE");
    }

    @Test
    void hostKeyMismatchFailsClosed() {
        when(hostService.credentialsOf(host)).thenReturn(creds("SHA256:not-the-real-one"));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("command", "echo x");
        a.put("timeoutSeconds", 10);
        a.put("roots", roots());
        a.put("policy", "workspace-write");
        ExecutorRegistry.Result r = executor().execute("exec", a);
        assertThat(r.ok()).isFalse();
        assertThat(r.code()).isEqualTo("E_HOST_KEY_MISMATCH");
    }

    // ==================== 内嵌服务器的 exec 命令实现 ====================

    private static CommandFactory commandFactory() {
        return (ChannelSession channel, String command) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private OutputStream err;
            private ExitCallback callback;
            private Process process;

            @Override
            public void setInputStream(InputStream in) {
                this.in = in;
            }

            @Override
            public void setOutputStream(OutputStream out) {
                this.out = out;
            }

            @Override
            public void setErrorStream(OutputStream err) {
                this.err = err;
            }

            @Override
            public void setExitCallback(ExitCallback callback) {
                this.callback = callback;
            }

            @Override
            public void start(ChannelSession channel, Environment env) throws IOException {
                process = new ProcessBuilder("bash", "-c", command).start();
                Thread t = new Thread(() -> {
                    try {
                        pump(process.getInputStream(), out);
                        pump(process.getErrorStream(), err);
                        int code = process.waitFor();
                        callback.onExit(code);
                    } catch (Exception e) {
                        callback.onExit(127, e.getMessage());
                    }
                }, "mina-exec");
                t.setDaemon(true);
                t.start();
            }

            @Override
            public void destroy(ChannelSession channel) {
                if (process != null) process.destroyForcibly();
            }
        };
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }
}
