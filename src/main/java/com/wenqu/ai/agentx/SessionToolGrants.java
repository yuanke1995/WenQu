package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.model.ToolApproval;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「本次会话都允许」的根会话作用域登记处。
 *
 * <p>用户看到的「会话」是那条根会话（对话页的那一条），而实际执行往往发生在它派出去的分支/团队成员
 * 临时会话里。放行如果挂在执行会话上，换一个子轮就重新问人——这正是线上「同一把 MCP 工具一轮问 13 次」的
 * 直接原因。本类把放行按 <b>uid + 根会话 id</b> 存一份，父轮、分支、续问、团队成员读同一份。
 *
 * <p><b>为什么落 Redis 而不是轮槽位</b>：子轮可能在另一个副本上建实例，进程内的
 * {@code AgentxTurnSlot} 看不见审批在哪个副本点的。Redis 是框架会话状态本来就依赖的硬依赖，
 * 多副本读写同一份才是「本会话」的语义；TTL 到期或用户撤销即失效，失败方向仍是重新问人。
 *
 * <p><b>为什么按工具名而不是整轮放行</b>：放行「执行命令」不该顺带把「写文件」也放过去。按名登记、
 * 按名判定，与审批卡的粒度一致。不同智能体在同一根会话下用同名工具时同样受益——用户点的是
 * 「这个工具在本次对话里别再问」，不是「这个智能体」。
 */
@Slf4j
@Component
public class SessionToolGrants {

    private static final String PREFIX = "wenqu:toolgrant:";

    private final StringRedisTemplate redis;
    private final SessionService sessionService;
    private final ConfigService configService;

    public SessionToolGrants(StringRedisTemplate redis, SessionService sessionService, ConfigService configService) {
        this.redis = redis;
        this.sessionService = sessionService;
        this.configService = configService;
    }

    /** 本轮执行侧应读的根会话作用域键；解析不到根时回落到本轮会话自身。 */
    public String scopeKeyOf(ChatCommand cmd) {
        if (cmd == null || cmd.getSessionId() == null || cmd.getSessionId().isBlank()) return null;
        String uid = uidOf(cmd.getUserId());
        String root = firstNonBlank(cmd.getRootSessionId(), cmd.getTeamRootSessionId(),
                rootOf(cmd.getSessionId()), cmd.getParentSessionId(), cmd.getSessionId());
        return root == null ? null : uid + "|" + root;
    }

    /**
     * 审批行对应的根会话作用域键。审批行已经落了父会话（团队成员的父会话就是团队根），
     * 优先用会话行上的 root_session_id 取到<b>最顶层</b>的根，避免嵌套派遣只认到中间一层。
     */
    public String scopeKeyForApproval(ToolApproval row) {
        if (row == null) return null;
        String root = rootSessionIdForApproval(row);
        return root == null ? null : uidOf(row.getUserId()) + "|" + root;
    }

    /** 审批行归属的最顶层会话 id（团队/嵌套派遣按会话行上的 root_session_id 取根）。 */
    public String rootSessionIdForApproval(ToolApproval row) {
        if (row == null || row.getSessionId() == null || row.getSessionId().isBlank()) return null;
        return firstNonBlank(rootOf(row.getSessionId()), row.getParentSessionId(), row.getSessionId());
    }

    /** 根会话本身的作用域键（供会话删除/用户清理时主动清）。 */
    public String scopeKeyForRoot(String userId, String rootSessionId) {
        if (rootSessionId == null || rootSessionId.isBlank()) return null;
        return uidOf(userId) + "|" + rootSessionId;
    }

    /** 登记一次会话级放行；Redis 写失败只留痕（审批本身已送进框架，下一次调用会重新问人，方向安全）。 */
    public void grant(String scopeKey, String toolName) {
        if (scopeKey == null || scopeKey.isBlank() || toolName == null || toolName.isBlank()) return;
        String key = key(scopeKey);
        try {
            redis.opsForSet().add(key, toolName);
            redis.expire(key, ttl());
            log.info("[GRANT] 本会话放行: scope={} tool={}", scopeKey, toolName);
        } catch (RuntimeException e) {
            log.warn("[FAIL-LOUD] 会话级放行写入 Redis 失败（下一次调用会重新询问） scope={} tool={}: {}",
                    scopeKey, toolName, e.toString());
        }
    }

    public boolean allowed(String scopeKey, String toolName) {
        if (scopeKey == null || scopeKey.isBlank() || toolName == null || toolName.isBlank()) return false;
        try {
            return Boolean.TRUE.equals(redis.opsForSet().isMember(key(scopeKey), toolName));
        } catch (RuntimeException e) {
            // 读失败按未放行处理：多问一次，不会多放一次
            log.warn("[FAIL-LOUD] 会话级放行读取失败（按未放行处理） scope={} tool={}: {}",
                    scopeKey, toolName, e.toString());
            return false;
        }
    }

    /** 当前根会话已放行的工具（会话头部/撤销入口用；顺序稳定）。 */
    public Set<String> list(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) return Set.of();
        try {
            Set<String> members = redis.opsForSet().members(key(scopeKey));
            return members == null ? Set.of() : new LinkedHashSet<>(members);
        } catch (RuntimeException e) {
            log.warn("[FAIL-LOUD] 会话级放行清单读取失败 scope={}: {}", scopeKey, e.toString());
            return Set.of();
        }
    }

    public void revoke(String scopeKey, String toolName) {
        if (scopeKey == null || scopeKey.isBlank() || toolName == null || toolName.isBlank()) return;
        try {
            redis.opsForSet().remove(key(scopeKey), toolName);
            log.info("[GRANT] 撤销本会话放行: scope={} tool={}", scopeKey, toolName);
        } catch (RuntimeException e) {
            log.warn("[FAIL-LOUD] 会话级放行撤销失败 scope={} tool={}: {}", scopeKey, toolName, e.toString());
        }
    }

    /** 会话的权限档位被用户显式改掉：之前「本会话都允许」的工具放行一并作废，按新档位重新问。 */
    public void clearForSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String root = rootOf(sessionId);
        clear(scopeKeyForRoot(userId, root == null ? sessionId : root));
    }

    public void clear(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) return;
        try {
            redis.delete(key(scopeKey));
        } catch (RuntimeException e) {
            log.warn("[FAIL-LOUD] 会话级放行清理失败 scope={}: {}", scopeKey, e.toString());
        }
    }

    /**
     * 取一条会话最顶层的根会话 id。
     * <p>先看会话行自己的 {@code root_session_id}（团队成员会直接落根），没有再沿 {@code parent_session_id}
     * 向上走。走到「没有父会话」的那条会话时，如果它就是起点（一个没有父链的临时会话），返回 null，
     * 由调用方回落到本轮自己的 parent/sessionId——不能把临时子会话自己当成根，那正是放行丢失的老 bug。
     */
    private String rootOf(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String start = sessionId;
        String cur = sessionId;
        try {
            for (int i = 0; i < 6; i++) {
                Session s = sessionService.sessionById(cur);
                if (s == null) return null;
                String root = s.getRootSessionId();
                if (root != null && !root.isBlank()) return root.trim();
                String parent = s.getParentSessionId();
                if (parent == null || parent.isBlank() || parent.equals(cur)) {
                    // 起点的临时会话没有父链：交回调用方用 cmd.parentSessionId/sessionId 兜住；
                    // 向上走过至少一层才到顶时，这个顶才是根。
                    return cur.equals(start) ? null : cur;
                }
                cur = parent;
            }
            return null;
        } catch (RuntimeException e) {
            log.debug("[GRANT] 根会话解析失败 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private Duration ttl() {
        int hours = configService.getInt("chat.toolGrantTtlHours", 168);
        return Duration.ofHours(Math.max(1, hours));
    }

    private static String key(String scopeKey) {
        return PREFIX + scopeKey;
    }

    private static String uidOf(String uid) {
        return uid == null || uid.isBlank() ? "guest" : uid;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}