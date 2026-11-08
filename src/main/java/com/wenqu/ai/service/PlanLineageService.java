package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.hitl.HitlDecisionService;
import com.wenqu.ai.model.ToolApproval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 计划血缘（B+）：用户批准后再要求「重新计划」时，把上一版已批准计划与该轮执行进度拼进本轮提示词，
 * 并给新计划记录留下「基于哪一版」的字段。
 *
 * <p>为什么必须服务端拼：计划模式的关键上下文是模型这一轮看到的用户消息。只把旧计划留在历史里，
 * 长会话被压缩、换设备或历史裁剪之后就会丢；拼进本轮消息则每一轮都成立。进度同理——会话表只留
 * 最新一份 todo，必须按 {@code turn_id} 从事件账本取那一轮的终态快照。
 *
 * <p>这里刻意只做「读 + 拼」，不推进任何状态：计划审批仍走原链路，血缘只是让模型知道从哪儿接着改。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanLineageService {

    /** 旧计划正文上限：计划卡本身通常一两千字，超过这个量级就该截断，别把整轮上下文都吃掉 */
    private static final int PLAN_MAX_CHARS = 6000;

    private final HitlDecisionService hitl;
    private final SessionEventService sessionEvents;

    /** 解析结果：血缘标识 + 旧计划那一轮的执行统计 + 可直接拼进 modelInput 的上下文段。 */
    public record Resolved(String planBaseId, int version, int done, int total, int failed, String promptNote) {
    }

    /**
     * 按旧计划批准 ID 取血缘上下文。
     * <p>归属校验是硬条件：记录必须属于当前会话、当前用户、且已批准。任一不满足返回 null
     * （调用方按「没有血缘」继续本轮，不报错——旧卡可能来自一次被清理的会话，重新计划本身仍应可用）。
     */
    public Resolved resolve(String sessionId, String userId, String planBaseId) {
        if (sessionId == null || sessionId.isBlank() || planBaseId == null || planBaseId.isBlank()) return null;
        ToolApproval rec = hitl.findApproval(planBaseId.trim());
        if (rec == null || !"planApproval".equals(rec.getToolName())) return null;
        if (!sessionId.equals(rec.getSessionId())) return null;
        if (rec.getUserId() != null && !rec.getUserId().isBlank() && !rec.getUserId().equals(userId)) return null;
        // 旧计划必须是「批准过」的：待批准/被拒/已作废的版本不能成为重新计划的基线
        if (!"APPROVED".equals(rec.getStatus())) return null;

        String plan = planTextOf(rec);
        if (plan.isBlank()) return null;
        int version = hitl.planVersionOf(rec.getId());
        TodoStat stat = todoStatOf(rec);
        String note = promptNote(plan, version, stat);
        log.info("[PLAN] 重新计划带上一版血缘: base={} v{} 进度={}/{} 失败={} session={}",
                rec.getId(), version, stat.done(), stat.total(), stat.failed(), sessionId);
        return new Resolved(rec.getId(), version, stat.done(), stat.total(), stat.failed(), note);
    }

    /** 批准稿优先（用户可能编辑过）；没有编辑稿才回退模型原稿。 */
    private String planTextOf(ToolApproval rec) {
        String approved = rec.getAnswer() == null ? "" : rec.getAnswer().trim();
        String plan = approved.isEmpty() ? hitl.parsePlanDraft(rec.getRequestArgs()) : approved;
        plan = plan == null ? "" : plan.trim();
        if (plan.length() > PLAN_MAX_CHARS) {
            plan = plan.substring(0, PLAN_MAX_CHARS) + "\n…（计划过长，已截断）";
        }
        return plan;
    }

    /** 旧计划那一轮的最后一份清单统计；读不到就按「没有清单」处理，绝不臆测已完成步骤。 */
    private TodoStat todoStatOf(ToolApproval rec) {
        String turnId = rec.getTurnId();
        if (turnId == null || turnId.isBlank()) return new TodoStat(0, 0, 0, java.util.List.of());
        String doc = sessionEvents.latestTodoOfTurn(rec.getSessionId(), rec.getUserId(), turnId);
        if (doc == null || doc.isBlank()) return new TodoStat(0, 0, 0, java.util.List.of());
        try {
            JSONObject json = JSON.parseObject(doc);
            JSONArray items = json == null ? null : json.getJSONArray("items");
            if (items == null || items.isEmpty()) return new TodoStat(0, 0, 0, java.util.List.of());
            int done = 0;
            int failed = 0;
            java.util.List<String> lines = new java.util.ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                JSONObject it = items.getJSONObject(i);
                String status = it == null ? "pending" : String.valueOf(it.getString("status"));
                String content = it == null ? "" : String.valueOf(it.getString("content"));
                if ("done".equals(status)) done++;
                if ("failed".equals(status)) failed++;
                String mark = "done".equals(status) ? "- [x] "
                        : "doing".equals(status) ? "- [>] "
                        : "failed".equals(status) ? "- [!] " : "- [ ] ";
                lines.add(mark + content);
            }
            return new TodoStat(done, items.size(), failed, lines);
        } catch (Exception e) {
            log.warn("[PLAN] 解析旧计划执行清单失败 base={}: {}", rec.getId(), e.getMessage());
            return new TodoStat(0, 0, 0, java.util.List.of());
        }
    }

    private String promptNote(String plan, int version, TodoStat stat) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【本次是在上一版计划基础上的重新计划】\n");
        sb.append("上一版计划（v").append(version).append("，已批准");
        if (stat.total() > 0) {
            sb.append("；那一轮执行进度：已完成 ").append(stat.done())
                    .append(" / ").append(stat.total());
            if (stat.failed() > 0) sb.append("，失败 ").append(stat.failed());
        }
        sb.append("）：\n");
        sb.append(plan).append('\n');
        if (stat.total() > 0) {
            sb.append("上一版执行清单：\n");
            for (String line : stat.lines()) sb.append(line).append('\n');
            sb.append("说明：已完成项代表成果已经产生，不要重复执行，也不要声称它们还没做；");
            sb.append("进行中项按用户的新意见决定继续或改写；失败项不会自动重试，若仍需完成请写进新计划。\n");
        } else {
            sb.append("说明：上一轮没有留下可读的任务清单，只能依据计划正文，不要臆测哪些步骤已经完成。\n");
        }
        sb.append("请输出一份完整的新计划（不是修改说明），供用户重新确认；");
        sb.append("在保留已完成成果的前提下重排还没做的部分，并落实用户本轮的新意见。\n");
        return sb.toString();
    }

    private record TodoStat(int done, int total, int failed, java.util.List<String> lines) {
    }
}
