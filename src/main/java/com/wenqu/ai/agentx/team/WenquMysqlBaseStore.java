package com.wenqu.ai.agentx.team;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 框架 {@code BaseStore} 的 MySQL 实现：让框架自带的 {@code LocalTeamClient}
 * （任务板 CAS / 邮箱 / 成员名册）能跨副本、跨重启持久，而不必重写一套 TeamClient。
 *
 * <p>这是「用框架」的接缝：框架只带了 {@code InMemoryStore}，其它一律要求宿主提供持久化。
 * 本类只做 KV + 版本 CAS，不含任何团队领域语义——任务板、依赖判定、认领与邮箱全在
 * {@code LocalTeamClient} 里（框架代码）。
 *
 * <p>namespace 用 '/' 连接（{@code teamNs/memberNs/taskNs/msgNs} 是固定段），租户信息由
 * namespace 携带，所以本表不走 MyBatis 租户拦截器，也不进 {@code GLOBAL_TABLES}。
 *
 * @author yuanke
 */
@Component
public class WenquMysqlBaseStore implements BaseStore {

    private final JdbcTemplate jdbc;

    public WenquMysqlBaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean supportsAtomicSessionStorage() {
        return true;
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        String ns = ns(namespace);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT item_key, version, value_json FROM c_ai_team_store WHERE namespace=? AND item_key=?",
                ns, key);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        long version = ((Number) row.get("version")).longValue();
        return new StoreItem(key, parse((String) row.get("value_json")), version);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        String ns = ns(namespace);
        String json = JSON.toJSONString(value);
        int updated = jdbc.update(
                "UPDATE c_ai_team_store SET value_json=?, version=version+1 WHERE namespace=? AND item_key=?",
                json, ns, key);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO c_ai_team_store (namespace, item_key, version, value_json) VALUES (?,?,?,?)",
                        ns, key, 1L, json);
            } catch (DuplicateKeyException race) {
                // 并发插入被抢先：退化为更新，语义等价（put 是无条件写入）
                jdbc.update(
                        "UPDATE c_ai_team_store SET value_json=?, version=version+1 WHERE namespace=? AND item_key=?",
                        json, ns, key);
            }
        }
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value,
                                long expectedVersion) {
        String ns = ns(namespace);
        String json = JSON.toJSONString(value);
        if (expectedVersion <= 0) {
            // 0 = create-if-absent
            try {
                return jdbc.update(
                        "INSERT INTO c_ai_team_store (namespace, item_key, version, value_json) VALUES (?,?,?,?)",
                        ns, key, 1L, json) == 1;
            } catch (DuplicateKeyException exists) {
                return false;
            }
        }
        return jdbc.update(
                "UPDATE c_ai_team_store SET value_json=?, version=version+1 "
                        + "WHERE namespace=? AND item_key=? AND version=?",
                json, ns, key, expectedVersion) == 1;
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        String ns = ns(namespace);
        int lim = limit <= 0 ? 100 : Math.min(limit, 1000);
        int off = Math.max(0, offset);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT item_key, version, value_json FROM c_ai_team_store "
                        + "WHERE namespace=? ORDER BY seq ASC LIMIT ? OFFSET ?",
                ns, lim, off);
        List<StoreItem> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(new StoreItem((String) row.get("item_key"),
                    parse((String) row.get("value_json")),
                    ((Number) row.get("version")).longValue()));
        }
        return out;
    }

    @Override
    public void delete(List<String> namespace, String key) {
        jdbc.update("DELETE FROM c_ai_team_store WHERE namespace=? AND item_key=?", ns(namespace), key);
    }

    private static String ns(List<String> namespace) {
        if (namespace == null || namespace.isEmpty()) return "";
        return String.join("/", namespace);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        JSONObject obj = JSON.parseObject(json);
        return obj == null ? new LinkedHashMap<>() : (Map<String, Object>) obj;
    }
}
