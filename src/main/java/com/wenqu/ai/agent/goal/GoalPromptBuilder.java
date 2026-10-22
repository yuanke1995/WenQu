package com.wenqu.ai.agent.goal;

import com.wenqu.ai.model.Goal;
import com.wenqu.ai.service.GoalCopy;
import org.springframework.stereotype.Component;

/**
 * 目标续跑提示词的唯一出口。
 *
 * <p>与旧版 {@link GoalCopy#roundPrompt} 的区别：旧版每轮只把 objective 重新贴一遍，
 * 进度全靠聊天历史；这里把 {@link GoalContinuation} 的权威视图（阶段/计划/事实/产物/失败尝试/
 * 缺口/预算）一起带上，模型不需要从几十条历史里猜「做到哪了」。
 *
 * <p>提示词是<b>模型侧</b>文案，允许出现 goal_note / goal_submit 这类工具名；
 * 用户可见文案的纪律仍归 {@link GoalCopy}（不写内部机制、不写沙盒路径）。
 *
 * @author yuanke
 */
@Component
public class GoalPromptBuilder {

    /**
     * 一轮续跑/首轮的完整输入。
     *
     * @param goal       目标
     * @param c          续跑上下文
     * @param round      第几轮（从 1 起）
     * @param maxRounds  轮次硬上限
     * @param budgetNote 预算提醒（为空则不写）
     * @param recovery   是否崩溃恢复后的第一轮
     */
    public String roundPrompt(Goal goal, GoalContinuation c, int round, int maxRounds,
                              String budgetNote, boolean recovery) {
        StringBuilder b = new StringBuilder(2048);
        b.append("<goal_round>\n");
        b.append("目标：").append(goal.getObjective()).append('\n');
        b.append("目标版本：").append(goal.getObjectiveVersion() == null ? 0 : goal.getObjectiveVersion())
                .append('\n');
        b.append("轮次：").append(round).append('/').append(maxRounds).append('\n');
        if (recovery) {
            b.append("注意：上一轮执行中断（进程/租约），本轮先核对工作区与外部系统的真实状态，"
                    + "只补没做完的部分，不要重复已经生效的写操作。\n");
        }
        String view = c == null ? "" : c.render();
        if (!view.isBlank()) {
            b.append("\n<当前任务状态>\n").append(view).append("</当前任务状态>\n");
        }
        if (budgetNote != null && !budgetNote.isBlank()) {
            b.append("\n<预算提醒>").append(budgetNote).append("</预算提醒>\n");
        }
        if (c != null && !c.getArtifacts().isEmpty()) {
            b.append("\n注意：上一轮已经产出并登记了交付物（")
                    .append(String.join("、", c.getArtifacts()))
                    .append("）。如果目标要求已经满足，本轮先核对后直接调用 goal_submit（evidence 引用这些产物），"
                            + "不要重复生成；如果还差内容，只补缺口。\n");
        }

        b.append("""
                
                推进要求：
                1. 先核对工作区、工具结果和任务状态里已经落地的事实，再决定这一轮做什么；不要只凭之前的叙述判断进度。
                2. 这一轮要有实际进展。把新确认的事实、计划变化、产物用 goal_note 记录下来——跨轮续跑只认这份状态，不认聊天历史。
                3. 全部目标真正完成时，调用 goal_submit 提交完成申请：summary 写交付说明，evidence 必须给出可核验的证据。平台会按证据核验，**不会仅凭一句话算完成**。
                   evidence 是 JSON 数组，元素形如：
                   [{"kind":"artifact","ref":"报告.pdf","note":"最终交付物"},
                    {"kind":"file","ref":"workspace/out/result.md","note":"生成的文件"},
                    {"kind":"command","ref":"npm test","exitCode":0,"note":"测试通过"}]
                   kind 可用 artifact/file/command/test/url/text；artifact 与 file 优先。
                4. 只完成一部分时不要调用 goal_submit 的完成分支；在 goal_note 里写清 remaining/gaps，系统会自动开始下一轮。
                5. 如果遇到靠你自己无法解决的阻塞（缺权限、缺资料、外部依赖不可用），调用 goal_submit 并把 blocked 设为 true，blockedReason 写具体原因。
                6. 不要为了等用户拍板而停下，也不要向用户确认是否继续——按你的判断推进。
                7. 如果 goal_note/goal_submit 工具不可用，才退回到在回答最后单独一行写「%s」或「%s具体原因」；平台仍会走同样的证据核验，不会把这句话直接当成完成。
                """.formatted(GoalCopy.DONE_MARKER, GoalCopy.BLOCKED_MARKER));
        b.append("</goal_round>");
        return b.toString();
    }

    /**
     * 完成检查轮：上一轮已经交付产物并声称完成，但没有 goal_submit。
     *
     * <p>这一轮<b>禁止做新工作</b>，只回答「完成 / 未完成」。它是控制面的收口动作，不是
     * 又一整轮执行——目标要求已经由 prompt 说明清楚，这里只解决「模型忘了交申请」。
     */
    public String completionCheckPrompt(Goal goal, GoalContinuation c, int round, int maxRounds) {
        StringBuilder b = new StringBuilder(1024);
        b.append("<goal_completion_check>\n");
        b.append("目标：").append(goal.getObjective()).append('\n');
        b.append("轮次：").append(round).append('/').append(maxRounds).append("（完成检查轮）\n");
        if (c != null && !c.getArtifacts().isEmpty()) {
            b.append("上一轮已产出并登记的交付物：\n");
            for (String a : c.getArtifacts()) b.append("  - ").append(a).append('\n');
        }
        if (c != null && c.getLastSummary() != null && !c.getLastSummary().isBlank()) {
            b.append("上一轮说明：").append(c.getLastSummary()).append('\n');
        }
        b.append("""
                这一轮只做完成检查，不要做新工作：不要写文件、不要执行命令、不要搜索、不要重复生成交付物。
                平台在工具闸上只放行 read_file / grep_files / glob_files / list_files 与 goal_submit / goal_note；
                其它工具调用会被直接拒绝，不要尝试。
                只允许二选一：
                1. 目标已经真正完成：调用 goal_submit。summary 写交付说明；evidence 至少包含上一轮产物的
                   artifact 引用（JSON 数组，如 [{"kind":"artifact","ref":"文件名","note":"最终交付物"}]）。
                2. 目标还没有完成：调用 goal_note，remaining 用 JSON 字符串数组写清还差什么（例如 ["补第 3 章","核对来源"]），
                   不要调用 goal_submit。
                不要用一句“已完成”代替工具调用；平台只认工具提交。
                """);
        b.append("</goal_completion_check>");
        return b.toString();
    }

    /**
     * 用户插话那一轮的短上下文：让模型记得当前目标，但不把完整任务状态塞进用户对话。
     * 完整状态只在续跑轮给；这里给目标与收尾工具提示即可。
     */
    public String contextNote(Goal goal) {
        if (goal == null) return "";
        return "\n\n【当前目标】" + goal.getObjective()
                + "\n（这是你在本会话里持续推进的目标。完成时用 goal_submit 提交证据；"
                + "遇到解决不了的阻塞用 goal_submit 的 blocked 分支。不需要向用户确认是否继续。）";
    }
}
