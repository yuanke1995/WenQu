package com.wenqu.ai.memory;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.service.ConfigService;

import java.nio.file.Path;

/**
 * 长期记忆的磁盘路径口径：{@code chat.agentscopeStateDir/{uid}}。
 *
 * <p>框架的 {@code WorkspaceManager} 按 {@code RuntimeContext.userId} 把 {@code MEMORY.md} 与
 * {@code memory/*.md} 解析到该目录（见 {@code SandboxService#memoryRouted}）。个人记忆面板、
 * 账号删除清理与引擎侧的硬上限必须共用这一份口径，否则「面板看到的」与「模型读到的」会分叉。
 *
 * <p>uid 只允许字母/数字/{@code _@.-}，与 {@code OrgService.UID_PATTERN} 同一口径；
 * 所有对外路径都先做白名单校验，避免通过 {@code ../} 读到别人的记忆。
 */
public final class MemoryPaths {

    /** 与 AgentscopeChatEngine / SandboxService 同一键同一默认值。 */
    public static final String STATE_DIR_KEY = "chat.agentscopeStateDir";
    public static final String STATE_DIR_DEFAULT = "user-data/agentscope";

    public static final String MEMORY_MD = "MEMORY.md";
    public static final String MEMORY_DIR = "memory";

    private static final String UID_PATTERN = "[A-Za-z0-9_@.\\-]+";
    private static final String DAILY_PATTERN = "memory/\\d{4}-\\d{2}-\\d{2}\\.md";

    private MemoryPaths() {
    }

    /** 状态根目录（框架 workspace 根）。 */
    public static Path stateDir(ConfigService configService) {
        String raw = configService == null ? null : configService.get(STATE_DIR_KEY);
        if (raw == null || raw.isBlank()) raw = STATE_DIR_DEFAULT;
        return Path.of(raw);
    }

    /** 某个用户的记忆根：{@code stateDir/{uid}}。 */
    public static Path root(ConfigService configService, String uid) {
        Path base = stateDir(configService);
        Path root = base.resolve(requireUid(uid)).normalize();
        if (!root.startsWith(base.normalize())) {
            throw new BizException("非法的用户目录");
        }
        return root;
    }

    public static String requireUid(String uid) {
        String u = uid == null ? "" : uid.trim();
        if (u.isEmpty() || "anonymous".equalsIgnoreCase(u)) {
            throw new BizException(401, "未登录");
        }
        if (!u.matches(UID_PATTERN)) {
            throw new BizException("登录账号含非法字符（仅允许字母/数字/_@.-）");
        }
        return u;
    }

    /** 是否是 {@code MEMORY.md}（路由后端收到的路径可能带前导斜杠）。 */
    public static boolean isMemoryMd(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/');
        return MEMORY_MD.equals(p) || p.endsWith("/" + MEMORY_MD);
    }

    /**
     * 面板可读路径白名单：只允许 {@code MEMORY.md} 与 {@code memory/YYYY-MM-DD.md}。
     * 日账是 append-only 的原始记录，面板只读；{@code .consolidation_state} / {@code archive/}
     * 不对外暴露（前者是内部游标，后者是超期归档）。
     */
    public static Path resolveContent(ConfigService configService, String uid, String relativePath) {
        Path root = root(configService, uid);
        String raw = relativePath == null ? "" : relativePath.replace('\\', '/').trim();
        while (raw.startsWith("/")) raw = raw.substring(1);
        if (raw.isEmpty()) throw new BizException("路径不能为空");
        if (MEMORY_MD.equals(raw)) return root.resolve(MEMORY_MD);
        if (raw.matches(DAILY_PATTERN)) {
            Path target = root.resolve(raw).normalize();
            if (!target.startsWith(root)) throw new BizException("非法的记忆路径");
            return target;
        }
        throw new BizException("不支持的记忆文件");
    }
}
