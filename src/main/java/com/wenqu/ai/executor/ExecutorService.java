package com.wenqu.ai.executor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 执行器设备的登记与令牌校验（令牌只存 SHA-256）。 */
@Service
public class ExecutorService {

    private final ExecutorRegistry registry;
    private final JdbcTemplate jdbc;

    public ExecutorService(ExecutorRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 签发设备令牌（只回一次）；同名设备不重复创建。 */
    public ExecutorRegistry.Paired register(String uid, String name, String os) {
        ExecutorRegistry.Paired paired = registry.register(uid, name, os);
        jdbc.update("insert into c_ai_executor (id, uid, name, token_hash, os) values (?,?,?,?,?)",
                paired.device().id(), uid, paired.device().name(), sha256(paired.token()), os);
        return paired;
    }

    /** 校验设备令牌并确保它在进程内登记表里（进程重启后靠这条恢复）。 */
    public ExecutorRegistry.Device authenticate(String token) {
        return authenticate(token, true);
    }

    /**
     * @param touchLastSeen 是否刷新库里的 last_seen；ack/result 这类高频回传不需要，
     *                      在线状态由 poll/heartbeat 维护，少一次每调用必做的 DB 写。
     */
    public ExecutorRegistry.Device authenticate(String token, boolean touchLastSeen) {
        if (token == null || token.isBlank()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, uid, name, os from c_ai_executor where token_hash = ? and revoked = 0", sha256(token));
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        ExecutorRegistry.Device device = new ExecutorRegistry.Device(
                String.valueOf(r.get("id")), String.valueOf(r.get("uid")),
                r.get("name") == null ? "" : String.valueOf(r.get("name")),
                r.get("os") == null ? "" : String.valueOf(r.get("os")));
        registry.restore(device);
        if (touchLastSeen) {
            jdbc.update("update c_ai_executor set last_seen = now() where id = ?", device.id());
        }
        return device;
    }

    public List<Map<String, Object>> list(String uid) {
        return jdbc.queryForList(
                "select id, name, os, last_seen, revoked from c_ai_executor where uid = ? and revoked = 0 order by create_time desc", uid);
    }

    /** 这台执行器是不是这个用户的（绑工作区前必须过这一关）。 */
    public boolean belongsTo(String uid, String executorId) {
        if (uid == null || executorId == null || executorId.isBlank()) return false;
        Integer n = jdbc.queryForObject(
                "select count(*) from c_ai_executor where id = ? and uid = ? and revoked = 0",
                Integer.class, executorId, uid);
        return n != null && n > 0;
    }

    public void revoke(String uid, String id) {
        jdbc.update("update c_ai_executor set revoked = 1 where id = ? and uid = ?", id, uid);
        registry.unregister(id);
    }
}
