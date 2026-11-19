package com.wenqu.ai.ssh;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.SshHostMapper;
import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.service.ConfigCryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SSH 主机服务：凭据托管、归属校验、TOFU 指纹、测试连接、CRUD。
 *
 * <p><b>凭据不变量</b>：
 * <ul>
 *   <li>写：明文只在这里出现，立刻 {@link ConfigCryptoService#encrypt} 后落库；</li>
 *   <li>读：{@link #credentialsOf} 是唯一解密出口，只给 {@link SshConnectionManager} / 执行器用；</li>
 *   <li>出参：{@link #toDto} 只回 {@code hasPassword/hasPrivateKey/hasPassphrase}，永不回密文或明文。</li>
 * </ul>
 *
 * <p><b>指纹</b>：新建时可不填；第一次“测试连接”/第一次实际连接前由 {@link #ensurePinned} 用
 * {@code manager.test()} 取回并落库（TOFU）。已固定后任何不一致一律 {@link HostKeyMismatchException}。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SshHostService {

    private static final int TEST_TIMEOUT_MS = 15_000;

    private final SshHostMapper mapper;
    private final ConfigCryptoService crypto;
    private final SshConnectionManager manager;
    /** 惰性取：避免 SshHostService ↔ ExecutorRegistry 的构造器环（provider 反向依赖本类）。 */
    private final org.springframework.beans.factory.ObjectProvider<ExecutorRegistry> executorRegistryProvider;
    private final JdbcTemplate jdbc;

    public SshHostService(SshHostMapper mapper, ConfigCryptoService crypto,
                          SshConnectionManager manager,
                          org.springframework.beans.factory.ObjectProvider<ExecutorRegistry> executorRegistryProvider,
                          JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.crypto = crypto;
        this.manager = manager;
        this.executorRegistryProvider = executorRegistryProvider;
        this.jdbc = jdbc;
    }

    private ExecutorRegistry registry() {
        return executorRegistryProvider.getIfAvailable();
    }

    /** 新建/更新的入参（明文凭据只在服务内部流转）。 */
    public record Input(String name, String host, Integer port, String username, String authType,
                        String password, String privateKey, String passphrase,
                        String hostKeyFingerprint) {
    }

    // ==================== 查询 ====================

    public List<SshHost> list(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<SshHost>()
                .eq(SshHost::getUid, uid)
                .orderByDesc(SshHost::getCreateTime));
    }

    public SshHost ownedOrNull(String uid, String id) {
        if (uid == null || uid.isBlank() || id == null || id.isBlank()) return null;
        SshHost h = mapper.selectById(id);
        return h != null && uid.equals(h.getUid()) ? h : null;
    }

    public SshHost owned(String uid, String id) {
        SshHost h = ownedOrNull(uid, id);
        if (h == null) throw new IllegalArgumentException("SSH 主机不存在或不属于你");
        return h;
    }

    public boolean belongsTo(String uid, String hostId) {
        return ownedOrNull(uid, hostId) != null;
    }

    /**
     * 系统侧按 id 取（虚拟执行器分发用，可能运行在没有租户上下文的工具线程）。
     * <p>按平台视图查：SSH 主机 id 是随机标识、只从「本人工作区」链路下发，不存在跨租户越权；
     * 而工具线程没有租户 ThreadLocal 时，普通查询会被租户闸 fail-closed 拒掉。
     */
    public SshHost byId(String id) {
        if (id == null || id.isBlank()) return null;
        return com.wenqu.ai.util.TenantContext.asPlatform(() -> mapper.selectById(id));
    }

    // ==================== 写 ====================

    public SshHost create(String uid, Input in) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("请先登录");
        validate(in, true);
        SshHost h = new SshHost();
        h.setId("ssh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        h.setUid(uid);
        apply(h, in, true);
        h.setStatus(SshHost.STATUS_ACTIVE);
        h.setDeleted(0);
        h.setCreateTime(LocalDateTime.now());
        h.setUpdateTime(LocalDateTime.now());
        mapper.insert(h);
        log.info("[SSH] 新建主机 uid={} id={} target={}", uid, h.getId(), in.username() + "@" + in.host() + ":" + port(in));
        return h;
    }

    public SshHost update(String uid, String id, Input in) {
        SshHost h = owned(uid, id);
        validate(in, false);
        String oldHost = h.getHost();
        Integer oldPort = h.getPort();
        apply(h, in, false);
        // 地址/端口变了：主机指纹对应的可能已不是同一台机器，强制重新 TOFU（除非本次显式给了新指纹）
        boolean endpointChanged = !eq(oldHost, h.getHost()) || !eq(oldPort, h.getPort());
        if (endpointChanged && (in.hostKeyFingerprint() == null || in.hostKeyFingerprint().isBlank())) {
            h.setHostKeyFingerprint(null);
            h.setHostKeyAlgorithm(null);
        }
        h.setUpdateTime(LocalDateTime.now());
        mapper.updateById(h);
        ExecutorRegistry registry = registry();
        if (registry != null) registry.invalidateVirtual(SshHost.executorIdOf(id));
        log.info("[SSH] 更新主机 uid={} id={} endpointChanged={}", uid, id, endpointChanged);
        return h;
    }

    public void delete(String uid, String id) {
        SshHost h = owned(uid, id);
        Integer used = jdbc.queryForObject(
                "select count(*) from c_ai_project where ssh_host_id = ? and deleted = 0", Integer.class, id);
        if (used != null && used > 0) {
            throw new IllegalStateException("有 " + used + " 个工作区正在使用这台 SSH 主机：先解绑或删除那些工作区");
        }
        mapper.deleteById(h.getId());
        manager.invalidate(id);
        ExecutorRegistry registry = registry();
        if (registry != null) registry.invalidateVirtual(SshHost.executorIdOf(id));
        log.info("[SSH] 删除主机 uid={} id={}", uid, id);
    }

    private void apply(SshHost h, Input in, boolean creating) {
        h.setName(trim(in.name()));
        h.setHost(trim(in.host()));
        h.setPort(in.port() == null ? 22 : in.port());
        h.setUsername(trim(in.username()));
        h.setAuthType(in.authType() == null || in.authType().isBlank()
                ? SshHost.AUTH_PASSWORD : in.authType().trim());
        // 密文列：本次给了明文才覆盖；编辑时留空=不改
        if (in.password() != null && !in.password().isEmpty()) {
            h.setPasswordCipher(crypto.encrypt(in.password()));
        }
        if (in.privateKey() != null && !in.privateKey().isBlank()) {
            h.setPrivateKeyCipher(crypto.encrypt(in.privateKey()));
        }
        if (in.passphrase() != null && !in.passphrase().isEmpty()) {
            h.setPassphraseCipher(crypto.encrypt(in.passphrase()));
        }
        if (SshHost.AUTH_PASSWORD.equals(h.getAuthType())) {
            h.setPrivateKeyCipher(null);
            h.setPassphraseCipher(null);
        } else if (SshHost.AUTH_PRIVATE_KEY.equals(h.getAuthType())) {
            h.setPasswordCipher(null);
        }
        if (in.hostKeyFingerprint() != null && !in.hostKeyFingerprint().isBlank()) {
            h.setHostKeyFingerprint(in.hostKeyFingerprint().trim());
        }
    }

    private static void validate(Input in, boolean creating) {
        if (in == null) throw new IllegalArgumentException("缺少主机参数");
        if (isBlank(in.host())) throw new IllegalArgumentException("主机地址不能为空");
        if (isBlank(in.username())) throw new IllegalArgumentException("登录用户不能为空");
        if (in.port() != null && (in.port() < 1 || in.port() > 65535)) {
            throw new IllegalArgumentException("端口必须在 1~65535 之间");
        }
        String auth = in.authType() == null || in.authType().isBlank() ? SshHost.AUTH_PASSWORD : in.authType().trim();
        if (!SshHost.AUTH_PASSWORD.equals(auth) && !SshHost.AUTH_PRIVATE_KEY.equals(auth)) {
            throw new IllegalArgumentException("认证方式只能是 password 或 private_key");
        }
        if (creating) {
            if (SshHost.AUTH_PASSWORD.equals(auth) && (in.password() == null || in.password().isEmpty())) {
                throw new IllegalArgumentException("密码认证必须填写密码");
            }
            if (SshHost.AUTH_PRIVATE_KEY.equals(auth) && (in.privateKey() == null || in.privateKey().isBlank())) {
                throw new IllegalArgumentException("私钥认证必须粘贴私钥内容");
            }
        }
        // 编辑时若切换认证方式却没带新凭据，会在连接时明确报错，不在保存阶段猜
    }

    // ==================== 凭据 / 指纹 ====================

    /** 唯一解密出口。 */
    public SshCredentials credentialsOf(SshHost h) {
        String password = SshHost.AUTH_PASSWORD.equals(h.getAuthType()) ? crypto.decrypt(h.getPasswordCipher()) : null;
        String key = SshHost.AUTH_PRIVATE_KEY.equals(h.getAuthType()) ? crypto.decrypt(h.getPrivateKeyCipher()) : null;
        String pass = crypto.decrypt(h.getPassphraseCipher());
        return new SshCredentials(h.getId(), h.getHost(), h.getPort() == null ? 22 : h.getPort(),
                h.getUsername(), h.getAuthType(), password, key, pass, h.getHostKeyFingerprint());
    }

    /**
     * 确保主机指纹已固定：为空时实际连一次取回并落库（TOFU）。返回连接信息。
     *
     * @throws SshOpException 连不上/认证失败/指纹不一致
     */
    public SshConnectionManager.TestResult ensurePinned(SshHost h) {
        SshCredentials creds = credentialsOf(h);
        SshConnectionManager.TestResult r = manager.test(h, creds, TEST_TIMEOUT_MS);
        if (h.getHostKeyFingerprint() == null || h.getHostKeyFingerprint().isBlank()) {
            pin(h.getId(), r.fingerprint(), r.algorithm());
            h.setHostKeyFingerprint(r.fingerprint());
            h.setHostKeyAlgorithm(r.algorithm());
            log.info("[SSH] 首次连接已固定主机指纹 id={} fingerprint={}", h.getId(), r.fingerprint());
        }
        return r;
    }

    /** 测试连接（已存主机）：成功会更新指纹（仅当为空）与最近探测时间/结果。 */
    public Map<String, Object> test(String uid, String id) {
        SshHost h = owned(uid, id);
        try {
            SshConnectionManager.TestResult r = ensurePinned(h);
            touchProbe(h.getId(), true, null);
            return resultDto(r);
        } catch (SshOpException e) {
            touchProbe(h.getId(), false, e.getMessage());
            throw e;
        }
    }

    /** 用临时参数测试（新建前拨测）：不落库、不固定指纹，只把指纹回给前端核对。 */
    public Map<String, Object> testAdhoc(Input in) {
        validate(in, true);
        SshHost tmp = new SshHost();
        tmp.setId("adhoc");
        tmp.setHost(trim(in.host()));
        tmp.setPort(in.port() == null ? 22 : in.port());
        tmp.setUsername(trim(in.username()));
        tmp.setAuthType(in.authType() == null || in.authType().isBlank() ? SshHost.AUTH_PASSWORD : in.authType().trim());
        SshCredentials creds = new SshCredentials("adhoc", tmp.getHost(), tmp.getPort(), tmp.getUsername(),
                tmp.getAuthType(), in.password(), in.privateKey(), in.passphrase(),
                in.hostKeyFingerprint());
        SshConnectionManager.TestResult r = manager.test(tmp, creds, TEST_TIMEOUT_MS);
        return resultDto(r);
    }

    private static Map<String, Object> resultDto(SshConnectionManager.TestResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverVersion", r.serverVersion());
        m.put("fingerprint", r.fingerprint());
        m.put("algorithm", r.algorithm());
        m.put("home", r.home());
        m.put("os", r.os());
        return m;
    }

    private void pin(String id, String fingerprint, String algorithm) {
        jdbc.update("update c_ai_ssh_host set host_key_fingerprint = ?, host_key_algorithm = ? where id = ?",
                fingerprint, algorithm, id);
    }

    public void touchProbe(String id, boolean ok, String error) {
        try {
            jdbc.update("update c_ai_ssh_host set last_probe_at = now(), last_probe_ok = ?, last_error = ? where id = ?",
                    ok ? 1 : 0, error == null ? null : SshShell.oneLine(error), id);
        } catch (Exception e) {
            log.debug("[SSH] 探测结果落库失败 id={}: {}", id, e.getMessage());
        }
    }

    // ==================== 远端目录浏览（新建工作区选目录） ====================

    /** 远端单层目录浏览：path 为空 = 登录用户 home；只列目录。 */
    public Map<String, Object> browse(String uid, String id, String path) {
        SshHost h = owned(uid, id);
        SshCredentials creds = credentialsOf(h);
        String target = path == null ? "" : path.trim();
        if (target.isBlank()) {
            target = manager.exec(h, creds, "printf %s \"$HOME\"", 8_000).output().strip();
            if (target.isBlank()) target = "/";
        }
        final String dir = target;
        List<Map<String, Object>> dirs = manager.withSftp(h, creds, s -> {
            try {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object o : s.ls(dir)) {
                    if (!(o instanceof com.jcraft.jsch.ChannelSftp.LsEntry e)) continue;
                    String name = e.getFilename();
                    if (".".equals(name) || "..".equals(name)) continue;
                    if (!e.getAttrs().isDir()) continue;
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("name", name);
                    one.put("path", (dir.endsWith("/") ? dir : dir + "/") + name);
                    out.add(one);
                }
                out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
                return out;
            } catch (com.jcraft.jsch.SftpException e) {
                throw SshConnectionManager.translate(e);
            }
        });
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", dir);
        m.put("parent", parent(dir));
        m.put("dirs", dirs);
        return m;
    }

    /** 远端逐级建目录（已存在视为成功）；用于目录选择器“新建文件夹”。 */
    public void mkdir(String uid, String id, String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("远端路径必须是绝对路径");
        }
        SshHost h = owned(uid, id);
        SshCredentials creds = credentialsOf(h);
        String clean = path.replaceAll("/+$", "");
        manager.withSftp(h, creds, s -> {
            try {
                String cur = "";
                for (String seg : clean.split("/")) {
                    if (seg.isEmpty()) continue;
                    cur = cur + "/" + seg;
                    try {
                        if (!s.stat(cur).isDir()) throw new IllegalArgumentException("同名文件已存在：" + cur);
                    } catch (com.jcraft.jsch.SftpException e) {
                        if (e.id == com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                            s.mkdir(cur);
                        } else {
                            throw e;
                        }
                    }
                }
                return null;
            } catch (com.jcraft.jsch.SftpException e) {
                throw SshConnectionManager.translate(e);
            }
        });
    }

    private static String parent(String path) {
        if (path == null) return "/";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    // ==================== 出参 ====================

    public Map<String, Object> toDto(SshHost h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("name", h.getName());
        m.put("host", h.getHost());
        m.put("port", h.getPort());
        m.put("username", h.getUsername());
        m.put("authType", h.getAuthType());
        m.put("hasPassword", h.getPasswordCipher() != null && !h.getPasswordCipher().isBlank());
        m.put("hasPrivateKey", h.getPrivateKeyCipher() != null && !h.getPrivateKeyCipher().isBlank());
        m.put("hasPassphrase", h.getPassphraseCipher() != null && !h.getPassphraseCipher().isBlank());
        m.put("hostKeyFingerprint", h.getHostKeyFingerprint() == null ? "" : h.getHostKeyFingerprint());
        m.put("hostKeyAlgorithm", h.getHostKeyAlgorithm() == null ? "" : h.getHostKeyAlgorithm());
        m.put("status", h.getStatus());
        // online 的语义是「这台主机此刻可用」：状态 active 且最近一次探测不是失败（null=从未探测，按可用）。
        boolean reachable = h.getLastProbeOk() == null || h.getLastProbeOk() != 0;
        m.put("online", SshHost.STATUS_ACTIVE.equals(h.getStatus()) && reachable);
        m.put("lastProbeAt", h.getLastProbeAt() == null ? null : h.getLastProbeAt().toString());
        m.put("lastProbeOk", h.getLastProbeOk());
        m.put("lastError", h.getLastError() == null ? "" : h.getLastError());
        m.put("createTime", h.getCreateTime() == null ? null : h.getCreateTime().toString());
        return m;
    }

    public List<Map<String, Object>> listDtos(String uid) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SshHost h : list(uid)) out.add(toDto(h));
        return out;
    }

    private static int port(Input in) {
        return in.port() == null ? 22 : in.port();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
