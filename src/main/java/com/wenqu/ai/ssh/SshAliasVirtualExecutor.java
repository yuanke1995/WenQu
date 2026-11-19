package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「本机 OpenSSH 别名」虚拟执行器：把标准 op 协议转发给用户本机执行器的 {@code ssh} op。
 *
 * <p>真实连接发生在用户电脑上（{@code wenqu-agent} 调用本机 {@code ssh}，读 {@code ~/.ssh/config}），
 * 问渠后端只做协议转发，不接触私钥/密码。数据形状与本地执行器 op 一致，因此
 * {@code ExecutorFilesystem}/{@code ExecutorFiles} 不需要为它写第二套逻辑。
 *
 * @author yuanke
 */
public class SshAliasVirtualExecutor implements ExecutorRegistry.VirtualExecutor {

    private final String deviceId;
    private final String alias;
    private final ExecutorRegistry registry;
    private final SshAuditService audit;

    public SshAliasVirtualExecutor(String deviceId, String alias, ExecutorRegistry registry,
                                   SshAuditService audit) {
        this.deviceId = deviceId;
        this.alias = alias;
        this.registry = registry;
        this.audit = audit;
    }

    @Override
    public boolean online() {
        return registry != null && deviceId != null && !deviceId.isBlank() && registry.online(deviceId);
    }

    @Override
    public ExecutorRegistry.Result execute(String op, Map<String, Object> args) {
        if (!online()) {
            return ExecutorRegistry.Result.fail("E_EXECUTOR_OFFLINE", "本机执行器不在线：先在对应电脑上启动 wenqu-agent");
        }
        long timeout = timeoutOf(op, args);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("alias", alias);
        wrapper.put("op", op);
        wrapper.put("args", args == null ? Map.of() : args);
        // 顶层 policy 仅供执行器做只读档预检（真正的路径收束在 args.roots + policy 里）
        if (args != null && args.get("policy") != null) wrapper.put("policy", args.get("policy"));
        long began = System.currentTimeMillis();
        ExecutorRegistry.Result r = registry.call(deviceId, "ssh", wrapper, timeout);
        if (audit != null) {
            String uid = str(args == null ? null : args.get("uid"));
            if (uid.isBlank()) uid = str(args == null ? null : args.get("_uid"));
            String tenant = str(args == null ? null : args.get("_tenantId"));
            Object p = args == null ? null : args.get("path");
            audit.record(tenant.isBlank() ? null : tenant, uid, "ssh_alias", deviceId, null, null,
                    str(args == null ? null : args.get("sessionId")),
                    str(args == null ? null : args.get("turnId")),
                    op, p == null ? null : String.valueOf(p), r.ok(), r.code(),
                    System.currentTimeMillis() - began, 0, 0);
        }
        return r;
    }

    private static long timeoutOf(String op, Map<String, Object> args) {
        if ("exec".equals(op) && args != null && args.get("timeoutSeconds") instanceof Number n) {
            return Math.max(30_000L, (n.longValue() + 30L) * 1000L);
        }
        if ("tree".equals(op) || "grep".equals(op) || "find".equals(op)) return 180_000L;
        return 120_000L;
    }

    @Override
    public void close() {
        // 无本进程连接可关：SSH 连接在用户电脑上，由 ControlMaster/ControlPersist 管理。
    }

    public String deviceId() {
        return deviceId;
    }

    public String alias() {
        return alias;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
