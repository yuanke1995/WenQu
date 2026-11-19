package com.wenqu.ai.ssh;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 终端入场券：WebSocket 升级请求不能带自定义 Authorization 头（浏览器 API 限制），
 * 因此先用已登录的 HTTP 接口换一张 60 秒、一次性、绑定 {@code uid|hostId} 的票；
 * 票存在 Redis（多副本可校验），WS 握手时消费即销毁。
 *
 * @author yuanke
 */
@Service
public class SshTerminalTicketService {

    private static final String KEY = "wenqu:ssh:terminal:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public SshTerminalTicketService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(String uid, String hostId) {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String ticket = HexFormat.of().formatHex(raw);
        redis.opsForValue().set(KEY + ticket, uid + "|" + hostId, TTL);
        return ticket;
    }

    /** 消费一张票；返回 [uid, hostId]，无效返回 null。 */
    public String[] consume(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        String value;
        try {
            value = redis.opsForValue().getAndDelete(KEY + ticket);
        } catch (Exception e) {
            return null;
        }
        if (value == null) return null;
        int i = value.indexOf('|');
        if (i <= 0) return null;
        return new String[]{value.substring(0, i), value.substring(i + 1)};
    }
}
