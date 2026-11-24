package com.wenqu.ai.agent.team;

import com.wenqu.ai.util.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队任务事件账本：框架 {@code LocalTeamClient} 只存任务的当前状态（无逐条事件），
 * 本类由 {@code WenquTeamClient} 在每次任务变更时投影一行，供面板/DAG 画布展示
 * 「创建 → 登记依赖 → 认领 → 完成/失败」的时间线。
 *
 * <p>只是读模型：真正的任务板（CAS、依赖判定、解锁）仍在框架里；这里不做任何状态计算，
 * 也不参与认领/完成判定，写失败只影响时间线，不影响任务本身。
 *
 * @author yuanke
 */
@Service
public class TeamTaskEventService {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public TeamTaskEventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String tenant() {
        String t = TenantContext.current();
        return (t == null || t.isBlank()) ? "default" : t;
    }

    /** 记一条任务事件。best-effort：异常吞掉（时间线少一行不能拖垮任务板）。 */
    public void record(String rootSessionId, String taskId, String kind, String detail) {
        if (rootSessionId == null || rootSessionId.isBlank() || taskId == null || kind == null) return;
        try {
            String d = detail == null ? null : (detail.length() > 600 ? detail.substring(0, 600) : detail);
            jdbc.update("INSERT INTO c_ai_team_task_event (tenant_id, root_session_id, task_id, kind, detail) "
                            + "VALUES (?,?,?,?,?)",
                    tenant(), rootSessionId, taskId, kind, d);
        } catch (Exception ignored) {
            // 账本非权威，写失败不影响任务
        }
    }

    /** 某任务的事件序列（旧→新），形状对齐前端 {kind,detail,at}。 */
    public List<Map<String, Object>> list(String rootSessionId, String taskId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rootSessionId == null || rootSessionId.isBlank() || taskId == null || taskId.isBlank()) return out;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT kind, detail, created_at FROM c_ai_team_task_event "
                            + "WHERE tenant_id=? AND root_session_id=? AND task_id=? ORDER BY id ASC",
                    tenant(), rootSessionId, taskId);
            for (Map<String, Object> r : rows) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("kind", r.get("kind"));
                one.put("detail", r.get("detail"));
                Object at = r.get("created_at");
                one.put("at", at instanceof LocalDateTime ldt ? ldt.format(AT)
                        : (at == null ? "" : String.valueOf(at)));
                out.add(one);
            }
        } catch (Exception ignored) {
            // 读失败按空时间线处理
        }
        return out;
    }
}
