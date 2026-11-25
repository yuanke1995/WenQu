package com.wenqu.ai.agentx;

import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.session.AgentSession;

import java.nio.file.Path;

/**
 * 引擎里的纯文本/时间小工具（从 AgentscopeChatEngine 抽出）。
 *
 * <p>六个方法都不读引擎字段、不写任何状态：入参进、值出。留在引擎里只是因为它先被那里用到，
 * 却让一个 4600 行的类又多扛了 60 行与本轮控制流无关的代码。
 *
 * <p>刻意保留同名 private 薄委托在引擎里：原调用点一行不改，行为等价、可逐步回退。
 *
 * @author yuanke
 */
public final class AgentxTextUtil {

    private AgentxTextUtil() {
    }

    /** 取第一个非空串；全空返回 null。 */
    public static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 机器耗时：总时长扣掉等人的时间（等待可能还在进行中，用 waitBeganAt 补当前这段）。 */
    public static long machineMs(long startedAt, long humanWaitMs, boolean waitingOnHuman, long waitBeganAt) {
        long now = System.currentTimeMillis();
        long waiting = humanWaitMs + (waitingOnHuman && waitBeganAt > 0 ? now - waitBeganAt : 0);
        return now - startedAt - waiting;
    }

    /** 日志/审计里的问题摘要：空白折叠、超长截断。 */
    public static String briefQuestion(String q) {
        String s = q.replaceAll("\\s+", " ").trim();
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /** 本机执行器的工作区环境段（并入 system prompt，抑制框架自带的 /workspace 路径暗示）。 */
    public static String localEnvText(String root, String notice) {
        String note = notice == null ? "" : "\n- " + notice;
        return "\n## 执行环境（本机执行器，以此段为准）\n"
                + "文件与命令工具都在用户自己的电脑上执行，不在服务器容器里。"
                + "当前工作区根目录：" + root + "\n"
                + "- `execute` 的 pwd 就是这个目录，相对路径都从这里解析；路径含空格/括号时要加引号。\n"
                + "- 其它段落若把工作区写成 `/workspace` 或 `/app/data/agentscope`（服务器沙盒/框架状态目录），"
                + "那些路径在这台电脑上不存在，不要用 execute / read_file 去读。\n"
                + "- 交付产物用 presentArtifact（返回值即访问地址），不要在磁盘上找 artifacts 目录。\n"
                + "- 复合命令的退出码由最后一条子命令决定：不要用 2>/dev/null 掩盖失败；"
                + "可能不存在的目录单独探测或加 `|| true`。" + note + "\n";
    }

    /** 某轮的首个事件 seq：接流时从这条开始读，避免把上一轮的尾部事件算进来。 */
    public static long turnStartSeq(AgentSession session, String frameworkTurnId, long fallback) {
        if (frameworkTurnId == null || frameworkTurnId.isBlank()) return fallback;
        long first = Long.MAX_VALUE;
        try {
            for (SessionEvent e : session.log().scan(0, session.log().head().seq())) {
                if (frameworkTurnId.equals(e.turnId()) && e.seq() < first) first = e.seq();
            }
        } catch (Exception e) {
            return fallback;
        }
        return first == Long.MAX_VALUE ? fallback : first;
    }

    /** 路径归一：优先 realpath（解析符号链接），失败则回落绝对路径。 */
    public static String realRoot(String raw) {
        String p = raw == null ? "" : raw.trim();
        if (p.isEmpty()) return p;
        try {
            return Path.of(p).toRealPath().toString();
        } catch (Exception e) {
            return Path.of(p).toAbsolutePath().normalize().toString();
        }
    }

    /** 计划正文取值：plan_write 攒下的正文 → plan_exit 的 summary → 入参纯文本 → 兜底说明。 */
    public static String planTextOf(Object input, String planMarkdown) {
        if (planMarkdown != null && !planMarkdown.isBlank()) return planMarkdown.trim();
        String fallback;
        if (input instanceof java.util.Map<?, ?> in) {
            fallback = com.wenqu.ai.agentx.AgentxToolCardCodec.str(in.get("summary")).trim();
        } else {
            fallback = com.wenqu.ai.agentx.AgentxToolCardCodec.str(input).trim();
        }
        if (!fallback.isEmpty()) return fallback;
        return NO_PLAN_BODY;
    }

    /** 计划正文取不到时给用户的那句话（同时也是 announcePending 里「该记一条日志」的判据）。 */
    public static final String NO_PLAN_BODY = "（这一轮没有取到计划内容，可以直接重试，或把要求写得更具体一些）";

    /** 本轮的「有可见交互」判定：空来源/用户/团队成员为真，定时任务/工作流/评测/API Key 为 headless。 */
    public static boolean interactiveTurn(com.wenqu.ai.agent.chat.ChatCommand cmd) {
        String src = cmd == null ? null : cmd.getSource();
        return src == null || src.isBlank()
                || com.wenqu.ai.service.TurnSource.USER.equalsIgnoreCase(src)
                || com.wenqu.ai.service.TurnSource.TEAM.equalsIgnoreCase(src);
    }
}
