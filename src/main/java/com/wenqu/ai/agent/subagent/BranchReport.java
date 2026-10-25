package com.wenqu.ai.agent.subagent;

import java.util.List;
import java.util.Map;

/**
 * 一条全循环分支跑完的回报。
 * <p>
 * 刻意<b>不带</b>子轮会话 id：分支的临时会话在执行器内就已经收养产物并删除，回报里再放一个
 * 已不存在的会话 id 只会诱使后来者去查它。
 * <p>
 * 编号语义要留意：{@code answer} 里的 {@code [N]} 与 {@code [图片N]} 仍是<b>子轮自己的编号</b>，
 * 引用与图片的真身在 {@code sources}/{@code images} 里。父轮装配时按这两份表把它们续编成
 * 父轮编号（见 ChatService 的回报段处理）——直接拼进父轮上下文是错的，两套编号会互相指错资料。
 *
 * @param status        done | failed | timeout（失败分支照样在回报段占一行，让主模型知道这一支没结果，
 *                      与「要点提炼」的失败占位同一口径）
 * @param answer        子轮回答正文（子轮编号原样保留）
 * @param sources       子轮引用来源：ref / knowledgeId / docId / title / snippet / images（原始 URL，已剥签名）/ score
 * @param images        子轮图片编号表（下标 0 = {@code [图片1]}，原始 URL）
 * @param toolSteps     子轮实际执行的工具步数（编排视图用它说明"这一支真跑了活"）
 * @param steps         逐步执行记录（干活视角）：{n=工具名, l=中文名, ms=单步耗时, d=被挡下, s=start|done|error}，
 *                      上限 {@link #MAX_STEPS} 步；超出的尾部丢弃（计数 toolSteps 仍如实）
 * @param artifactFiles 子轮交付的产物文件名（已收养进父会话，此处只作展示）
 * @param artifacts     产物明细（干活视角）：{f=文件名, u=url, s=大小, e=类型}，与 artifactFiles 同源更全
 * @param elapsedMs     本支耗时
 * @param error         失败/超时文案（可为 null）
 * @author yuanke
 */
public record BranchReport(String status, String answer, List<Map<String, Object>> sources, List<String> images,
                           int toolSteps, List<Map<String, Object>> steps, List<String> artifactFiles,
                           List<Map<String, Object>> artifacts, long elapsedMs, String error) {

    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String TIMEOUT = "timeout";

    /** 逐步记录的条数上限：干活视角要的是"干了什么"，不是流水账全文 */
    public static final int MAX_STEPS = 20;

    /** 组装一份失败回报（超时/异常两条路径共用，保证失败分支也有名字与耗时；已跑到的步骤如实带上——
     *  「跑到第几步断的」正是失败排查要看的） */
    public static BranchReport failed(String status, String error, long elapsedMs, List<Map<String, Object>> steps) {
        return new BranchReport(status, "", List.of(), List.of(), 0,
                steps == null ? List.of() : List.copyOf(steps), List.of(), List.of(), elapsedMs, error);
    }

    /** 这一支有没有可用作答的回报 */
    public boolean usable() {
        return DONE.equals(status) && answer != null && !answer.isBlank();
    }

    public String answerOrEmpty() {
        return answer == null ? "" : answer;
    }

    public List<Map<String, Object>> sourcesOrEmpty() {
        return sources == null ? List.of() : sources;
    }

    public List<String> imagesOrEmpty() {
        return images == null ? List.of() : images;
    }

    public List<String> artifactFilesOrEmpty() {
        return artifactFiles == null ? List.of() : artifactFiles;
    }

    public List<Map<String, Object>> stepsOrEmpty() {
        return steps == null ? List.of() : steps;
    }

    public List<Map<String, Object>> artifactsOrEmpty() {
        return artifacts == null ? List.of() : artifacts;
    }
}
