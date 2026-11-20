package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.executor.ExecutorService;
import com.wenqu.ai.service.PresentArtifactTool;
import com.wenqu.ai.util.RequestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通会话里可直接调用的 SSH 工具：连接走<b>用户本机的 OpenSSH</b>，不在服务端存凭据。
 *
 * <p>与「ssh_alias 工作区」的区别：那一形态把 SSH 绑在工作区后端上，必须先建工作区才有终端；
 * 这把工具把同一条链路（本机 {@code wenqu-agent} → 本机 {@code ssh <alias>}）做成聊天能力，
 * 用户在任意会话里说「ssh wenqu 上去看看」就能用。
 *
 * <p>实现上刻意复用 {@link SshAliasVirtualExecutor}（经 {@code ssh-alias:} 虚拟执行器 id）：
 * 它负责包 wrapper、注入租户/用户、超时和审计，后端不碰密钥。
 *
 * @author yuanke
 */
@Service
public class SshChatTool {

    private static final Logger log = LoggerFactory.getLogger(SshChatTool.class);

    /** 与本机 agent 的别名校验保持同一套字符集（wenqu-agent.mjs: SSH_ALIAS_RE）。 */
    private static final String ALIAS_RE = "^[A-Za-z0-9._@-]+$";

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    /** 与 SshAliasVirtualExecutor#timeoutOf 同口径：外层超时不能比内层先到。 */
    private static final int ALIAS_LOOKUP_TIMEOUT_MS = 15_000;

    private final ExecutorService executorService;
    private final ExecutorRegistry registry;

    public SshChatTool(ExecutorService executorService, ExecutorRegistry registry) {
        this.executorService = executorService;
        this.registry = registry;
    }

    @Tool(name = "ssh_exec", description =
            "通过用户本机的 OpenSSH 连接远端主机并执行命令，用户说「ssh <别名> 上去看看／查一下／执行…」时用它。"
            + "别名是用户本机 ~/.ssh/config（Windows: %USERPROFILE%\\.ssh\\config）里的 Host 名（例如 wenqu），服务端不保存任何密钥；"
            + "命令在远端执行，返回原始输出和退出码。别名不确定时，失败信息里会列出这台电脑读到的可用别名。"
            + "只在用户明确要求连某台远端机器时使用；不要拿它执行用户没要求的写操作。")
    public Map<String, Object> sshExec(
            @ToolParam(description = "本机 OpenSSH 的 Host 别名，如 wenqu") String alias,
            @ToolParam(description = "要在远端执行的 shell 命令，如 hostname; uptime; df -h /") String command,
            @ToolParam(description = "超时秒数（1~600，默认 60）；仅用户要求长任务时才加大", required = false)
            Integer timeoutSeconds,
            ToolContext toolContext) {

        String a = alias == null ? "" : alias.trim();
        String cmd = command == null ? "" : command.trim();
        if (a.isEmpty() || !a.matches(ALIAS_RE)) {
            return fail("E_INVALID", "SSH 别名不合法：" + a + "（只支持字母、数字、点、下划线、@ 和连字符）");
        }
        if (cmd.isEmpty()) {
            return fail("E_INVALID", "远端命令不能为空");
        }
        int seconds = timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS));

        String uid = resolveUid(toolContext);
        if (uid == null || uid.isBlank() || RequestUser.SYSTEM.equals(uid)) {
            return fail("E_UNAUTHENTICATED", "拿不到当前用户，无法找到你的本机执行器");
        }
        String deviceId = pickExecutor(uid, a);
        if (deviceId == null) {
            List<String> online = new ArrayList<>();
            Set<String> aliases = new LinkedHashSet<>();
            for (Map<String, Object> row : executorService.list(uid)) {
                String id = String.valueOf(row.get("id"));
                if (!registry.online(id)) continue;
                online.add(id);
                aliases.addAll(aliasesOf(id));
            }
            if (online.isEmpty()) {
                return fail("E_EXECUTOR_OFFLINE", "本机执行器不在线：先在这台电脑上启动 wenqu-agent 再试");
            }
            return fail("E_ALIAS_NOT_FOUND", "本机 ~/.ssh/config（Windows: %USERPROFILE%\\.ssh\\config）里没有 Host 别名「" + a + "」。"
                    + "读到的别名：" + (aliases.isEmpty() ? "(空)" : String.join("、", aliases)));
        }

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", cmd);
        args.put("timeoutSeconds", seconds);
        // agent 侧只把 read-only 档当禁改判据；这里没有工作区根，cwd 固定为远端 $HOME。
        args.put("policy", "workspace-write");

        String virtualId = SshAliasIds.virtualId(deviceId, a);
        int outerTimeoutMs = Math.max(30_000, (seconds + 30) * 1000);
        ExecutorRegistry.Result r;
        try {
            r = registry.call(virtualId, "exec", args, outerTimeoutMs);
        } catch (Exception e) {
            log.warn("[SSH-TOOL] 调用失败 alias={}: {}", a, e.toString());
            return fail("E_IO", "SSH 调用失败：" + safeMessage(e));
        }
        if (!r.ok()) {
            return fail(r.code() == null ? "E_IO" : r.code(), r.message() == null ? "SSH 执行失败" : r.message());
        }
        Map<String, Object> data = r.data() == null ? Map.of() : r.data();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("alias", a);
        out.put("output", data.getOrDefault("output", ""));
        out.put("exitCode", data.get("exitCode"));
        out.put("truncated", Boolean.TRUE.equals(data.get("truncated")));
        return out;
    }

    /** 挑一台「在线且 ~/.ssh/config 里真有这个别名」的执行器；没有就返回 null。 */
    private String pickExecutor(String uid, String alias) {
        for (Map<String, Object> row : executorService.list(uid)) {
            String id = String.valueOf(row.get("id"));
            if (!registry.online(id)) continue;
            if (aliasesOf(id).stream().anyMatch(alias::equalsIgnoreCase)) return id;
        }
        return null;
    }

    private Set<String> aliasesOf(String deviceId) {
        Set<String> out = new LinkedHashSet<>();
        try {
            ExecutorRegistry.Result r = registry.call(deviceId, "sshAliases", Map.of(), ALIAS_LOOKUP_TIMEOUT_MS);
            if (r.ok() && r.data() != null && r.data().get("aliases") instanceof Collection<?> list) {
                for (Object o : list) {
                    if (o != null) out.add(String.valueOf(o));
                }
            }
        } catch (Exception e) {
            log.debug("[SSH-TOOL] 读取执行器别名失败 device={}: {}", deviceId, e.toString());
        }
        return out;
    }

    /**
     * 取当前用户：优先用工具上下文里透传的 uid——框架在弹性线程上跑工具，ThreadLocal 会回落成 SYSTEM，
     * 只有 {@code ToolContext} 里那份快照是跨线程安全的（与 PresentArtifactTool 同一口径）。
     */
    private static String resolveUid(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object v = toolContext.getContext().get(PresentArtifactTool.CTX_USER_ID);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return RequestUser.uid();
    }

    private static Map<String, Object> fail(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", err);
        return out;
    }

    private static String safeMessage(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
