package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.service.ProjectService;
import com.wenqu.ai.service.SandboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 聊天档 / 工作区档的判档（从 AgentscopeChatEngine 抽出）。
 *
 * <p>命中工作区意图词、或会话已粘在工作区档（建过实例/绑了项目/本机目录）就走工作区档；
 * 否则走聊天档。刻意「宁可多判工作区」：误判工作区只多付一次约 3 秒装配，误判聊天会让
 * 「跑/构建/改文件」失去能力。判据用 sessions（粘性）、sandboxService（本机目录）、projectService（绑定项目）。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxSandboxIntent {

    private final FrameworkSessions sessions;
    private final SandboxService sandboxService;
    private final ProjectService projectService;

    private static final Pattern SANDBOX_INTENT_EN = Pattern.compile(
            "(?i)\\b(file|folder|directory|project|code|script|run|execute|build|compile|install|deploy|debug|terminal|command|read|write|save|edit|export|package|zip|excel|csv|json|python|node|npm|bash|shell|git|docker)\\b");
    private static final Pattern SANDBOX_INTENT_EXT = Pattern.compile(
            "(?i)\\.(py|js|ts|java|html|css|json|csv|md|xlsx|zip|sh|sql)\\b");
    private static final String[] SANDBOX_INTENT_ZH = {
            "文件", "目录", "文件夹", "项目", "代码", "脚本", "运行", "执行", "构建", "编译",
            "安装", "部署", "调试", "终端", "命令行", "读取", "写入", "保存", "编辑", "修改",
            "导出", "打包", "压缩", "解压", "表格", "数据库"
    };

    public boolean wants(ChatCommand cmd, ExecutionPolicy policy) {
        String key = sessions.key(cmd);
        if (pinnedToWorkspace(key, cmd)) {
            log.info("[PROFILE] profile=workspace reason=sticky session={}", cmd.getSessionId());
            return true;
        }
        if (boundToProject(cmd)) {
            log.info("[PROFILE] profile=workspace reason=bound-project session={}", cmd.getSessionId());
            return true;
        }
        String q = cmd.getQuestion();
        if (q == null || q.isBlank()) {
            boolean sandbox = keyedDownstream(key, cmd);
            log.info("[PROFILE] profile={} reason=continuation session={}",
                    sandbox ? "workspace" : "chat", cmd.getSessionId());
            return sandbox;
        }
        String reason = intentReason(q);
        if (reason == null) {
            log.info("[PROFILE] profile=chat reason=no-intent session={} q={}",
                    cmd.getSessionId(), AgentxTextUtil.briefQuestion(q));
            return false;
        }
        log.info("[PROFILE] profile=workspace reason={} session={} q={}",
                reason, cmd.getSessionId(), AgentxTextUtil.briefQuestion(q));
        return true;
    }

    /** 粘性：已建过工作区档实例、或本机会话目录已 pin 的会话，不因下一句普通问题降回聊天档。 */
    private boolean pinnedToWorkspace(String key, ChatCommand cmd) {
        return sessions.hasHeld(key) && (sessions.sandboxAttached(key) || pinnedLocal(cmd));
    }

    /** 本机会话目录也算「已进入工作区档」（它没有 SandboxContext，sandboxAttached 恒 false）。 */
    private boolean pinnedLocal(ChatCommand cmd) {
        return sandboxService != null && sandboxService.pinnedLocalSessionWorkspace(cmd.getSessionId());
    }

    /** 绑了显式项目（非隐式选择，或直接工作区）→ 工作区档。查库失败按「未绑定」回落意图判档。 */
    private boolean boundToProject(ChatCommand cmd) {
        try {
            com.wenqu.ai.model.Project p = projectService.projectOfSession(cmd.getSessionId());
            return p != null && (!com.wenqu.ai.model.Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())
                    || projectService.isDirect(p));
        } catch (RuntimeException e) {
            log.debug("[PROFILE] 工作区解析失败，按问题意图判档 session={}: {}",
                    cmd.getSessionId(), e.toString());
            return false;
        }
    }

    /** 续跑轮（无正文）：建过实例的会话沿用当前档，否则默认工作区档。 */
    private boolean keyedDownstream(String key, ChatCommand cmd) {
        if (!sessions.hasHeld(key)) return true;
        return sessions.sandboxAttached(key) || pinnedLocal(cmd);
    }

    /** 意图词命中返回原因串（intent-en / intent-zh:词），否则 null。 */
    private String intentReason(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        if (SANDBOX_INTENT_EXT.matcher(q).find() || SANDBOX_INTENT_EN.matcher(lower).find()) {
            return "intent-en";
        }
        for (String word : SANDBOX_INTENT_ZH) {
            if (q.contains(word)) return "intent-zh:" + word;
        }
        return null;
    }
}
