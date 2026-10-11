package com.wenqu.ai.agent.runcode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性运行凭据登记处（P3b）。
 * <p>
 * 沙盒里的脚本要回调平台工具，凭的是什么？三种可能里只有第三种能用：
 * <ol>
 *   <li>用户的登录令牌——绝不能进容器：那等于把"这个人"整份交给一段模型现写的代码，
 *       而容器是别人会话也可能复用的环境；</li>
 *   <li>provisioner 的服务令牌——方向反了，那是平台访问容器的凭据，不是容器访问平台的；</li>
 *   <li>每次 {@code run_code} 现发一枚、只活这一次运行、只能调本轮已挂上的工具——
 *       越权面就是"这轮本来就能做的事"，且运行结束即失效。</li>
 * </ol>
 * 与免登录分享（{@code /api/ai/share/{token}}）、产物签名 URL 是同一套思路：<b>短命凭据即鉴权</b>，
 * 不给容器装任何长期身份。
 * <p>
 * 内存表而不落库：凭据的作用域就是一次正在跑的工具调用，落库只会多一个"重启后仍然有效"的洞。
 * TTL 由调用方给（脚本运行上限 + 富余），到点即视为无效；每次发放顺带清一次过期项。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class RunTokenRegistry {

    /**
     * 一次运行的登记。
     *
     * @param sessionId 发起这一轮问答的会话（日志与"这一轮已叫停"判定的归属）
     * @param turnId    本轮 id（同一轮里多次 run_code 各发一枚，靠它归账）
     * @param uid       本轮用户（子调用的可见性与个人资产按他判，凭据本身不带身份）
     * @param bridge    子调用入口（本轮闭包，见 {@link RunCodeBridge}）
     * @param expiresAt 失效时刻（epoch millis）
     */
    public record Handle(String sessionId, String turnId, String uid, RunCodeBridge bridge, long expiresAt) {
        boolean liveAt(long now) {
            return now < expiresAt;
        }

        boolean live() {
            return liveAt(System.currentTimeMillis());
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Handle> live = new ConcurrentHashMap<>();

    /**
     * 发放一枚运行凭据。
     * <p>随机 32 字节（不是 sessionId 派生、也不是自增 id）：可枚举的凭据等于给容器留一条
     * "猜到别人的运行就能调别人的工具"的路径，而这条路径跨网络。
     */
    public String issue(String sessionId, String turnId, String uid, RunCodeBridge bridge, long ttlMs) {
        purgeExpired();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        live.put(token, new Handle(sessionId, turnId, uid, bridge,
                System.currentTimeMillis() + Math.max(5_000L, ttlMs)));
        return token;
    }

    /**
     * 凭据换回这一轮（无效/过期返回 null，调用方按 401 处理）。
     * <p>过期即摘除：不留"先返回 null 再等清扫"的空档，被拒的凭据不该还在表里。
     */
    public Handle resolve(String token) {
        if (token == null || token.isBlank()) return null;
        Handle h = live.get(token);
        if (h == null) return null;
        if (!h.live()) {
            live.remove(token);
            return null;
        }
        return h;
    }

    /** 运行结束主动撤销（幂等：重复调用、或本就没发出去时静默返回 false） */
    public boolean revoke(String token) {
        return token != null && live.remove(token) != null;
    }

    /** 本轮退场时把还挂着的全部撤掉（防止异常路径漏 revoke 后凭据活到 TTL 到点） */
    public int revokeByTurn(String turnId) {
        if (turnId == null) return 0;
        int n = 0;
        for (Map.Entry<String, Handle> e : live.entrySet()) {
            if (turnId.equals(e.getValue().turnId()) && live.remove(e.getKey()) != null) n++;
        }
        if (n > 0) log.info("[P3B] 本轮退场撤销 {} 枚未用完的运行凭据 turn={}", n, turnId);
        return n;
    }

    /** 当前有效凭据数（运行状态面板与冒烟验证用） */
    public int activeCount() {
        purgeExpired();
        return live.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        live.entrySet().removeIf(e -> !e.getValue().liveAt(now));
    }
}
