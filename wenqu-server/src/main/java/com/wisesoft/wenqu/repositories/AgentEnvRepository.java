package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.AgentEnv;
import com.wisesoft.wenqu.repository.port.AgentEnvMapper;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 智能体环境变量仓储。
 *
 * <p>由参考实现的 repositories/agent_env_repository.py 逐方法翻译：内容未变时复用旧时间戳，
 * 否则整集合原子写入（唯一键为 uid）。
 *
 * <p>必要替换：参考实现用 PostgreSQL 的 {@code ON CONFLICT (uid) DO UPDATE}，MySQL 用
 * {@code ON DUPLICATE KEY UPDATE}（uid 上有唯一键）。两者都是单条原子语句，语义一致。
 */
@Repository
public class AgentEnvRepository {

    private final AgentEnvMapper agentEnvMapper;
    private final JdbcTemplate jdbc;

    public AgentEnvRepository(AgentEnvMapper agentEnvMapper, JdbcTemplate jdbc) {
        this.agentEnvMapper = agentEnvMapper;
        this.jdbc = jdbc;
    }

    /** 写入结果：环境变量与生效的更新时间。 */
    public record AgentEnvWriteResult(JSONObject env, LocalDateTime updatedAt) {}

    /** 读取指定用户的环境变量。 */
    public AgentEnv getByUid(String uid) {
        return agentEnvMapper.selectOne(new LambdaQueryWrapper<AgentEnv>().eq(AgentEnv::getUid, uid));
    }

    /** 内容未变时复用旧时间，否则原子写入完整环境变量集合。 */
    public AgentEnvWriteResult upsert(String uid, JSONObject env, LocalDateTime updatedAt) {
        AgentEnv current = getByUid(uid);
        JSONObject currentEnv = current == null ? new JSONObject() : RepoValues.parseObject(current.getEnv());
        if (current != null && currentEnv.equals(env)) {
            return new AgentEnvWriteResult(currentEnv, current.getUpdatedAt());
        }

        jdbc.update(
                "INSERT INTO agent_envs (uid, env, created_at, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE env = VALUES(env), updated_at = VALUES(updated_at)",
                uid,
                JSON.toJSONString(env),
                DateTimeUtils.utcNowNaive(),
                updatedAt);
        return new AgentEnvWriteResult(env, updatedAt);
    }

    /** 便于调用方传入 Map 形式的写入签名（键值同为字符串）。 */
    public AgentEnvWriteResult upsert(String uid, Map<String, String> env, LocalDateTime updatedAt) {
        JSONObject json = new JSONObject();
        if (env != null) {
            json.putAll(env);
        }
        return upsert(uid, json, updatedAt);
    }
}
