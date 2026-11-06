package com.wenqu.ai.agentx;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 前缀稳定器：把框架每轮拼进 system prompt 的每会话 ID 换成稳定占位。
 *
 * <p><b>为什么需要这一刀</b>：AgentScope 的 WorkspaceContextMiddleware 会把
 * {@code AgentStateStore ID: <sessionId>} 与 {@code Sandbox root: /workspace (container id: <sandboxId>)}
 * 写进 system prompt。每个新会话的这两个 id 都不同，而供应商的 prefix cache 是整段前缀匹配——
 * 从这一行之后（实测约 2~3.5k tokens 处）整段 16.8k 前缀全部失效，缓存只中 0~3.5k。
 * 同前缀连发时能中 14k，说明缓存本身正常，坏在「每会话必变」的 id。
 *
 * <p><b>只改模型看到的文本</b>：挂点在 {@code onModelCall}，替换 system 消息里的这两个 id；
 * 注册表、会话状态、工具执行、RuntimeContext 全部不动。会话工具（SessionSearchTool 等）读的是
 * RuntimeContext 的 sessionId，不依赖提示词里的这行；真机若发现依赖，关掉本中间件即恢复原样。
 *
 * <p><b>只处理纯文本 system 消息</b>：含非文本块（图片等）的消息原样放行，避免破坏结构。
 */
public final class PrefixStabilizerMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PrefixStabilizerMiddleware.class);

    private final java.util.function.BooleanSupplier enabled;

    public PrefixStabilizerMiddleware() {
        this(() -> true);
    }

    public PrefixStabilizerMiddleware(java.util.function.BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    /** AgentStateStore ID: <id> */
    private static final Pattern SESSION_ID =
            Pattern.compile("(?i)(AgentStateStore ID:\\s*)\\S+");

    /** Sandbox root: /workspace (container id: <id>) */
    private static final Pattern CONTAINER_ID =
            Pattern.compile("(?i)(container id:\\s*)[^\\s)]+");

    static {
        // 启动自检：不依赖框架当前是否注入这两个 id，先用样例串把替换规则钉住。
        // 必须放在两个 Pattern 之后——静态初始化按文本顺序执行，放前面会拿到 null。
        try {
            String sample = "AgentStateStore ID: 0123456789abcdef0123456789abcdef\n"
                    + "Sandbox root: /workspace (container id: abcdef123456)\n";
            String out = stableText(sample);
            if (out.contains("<session>") && out.contains("<container>")) {
                log.info("[PROFILE] 前缀稳定器自检通过");
            } else {
                log.error("[PROFILE] 前缀稳定器自检失败，未识别预期 id: {}", out);
            }
        } catch (Exception e) {
            log.warn("[PROFILE] 前缀稳定器自检异常: {}", e.toString());
        }
    }

    private volatile boolean announced;
    private volatile boolean checked;

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (input == null || input.messages() == null || input.messages().isEmpty() || !on()) {
            return next.apply(input);
        }
        checkOnce(input.messages());
        List<Msg> stabilized = stabilize(input.messages());
        if (stabilized == null) {
            return next.apply(input);
        }
        announce();
        return next.apply(new ModelCallInput(
                stabilized, input.tools(), input.options(), input.model()));
    }

    /** 首次调用登记一次：system prompt 里到底有没有那两个动态 id，排障时不用再猜。 */
    private void checkOnce(List<Msg> messages) {
        if (checked) {
            return;
        }
        checked = true;
        boolean system = false;
        boolean sessionId = false;
        boolean containerId = false;
        for (Msg m : messages) {
            if (m == null || m.getRole() != MsgRole.SYSTEM) {
                continue;
            }
            system = true;
            String text = m.getTextContent();
            if (text == null) {
                continue;
            }
            sessionId |= SESSION_ID.matcher(text).find();
            containerId |= CONTAINER_ID.matcher(text).find();
        }
        log.info("[PROFILE] 前缀稳定器检查: system={} sessionId={} containerId={}",
                system, sessionId, containerId);
    }

    /** 没有任何替换时返回 null，避免每轮新建一份消息列表。 */
    private static List<Msg> stabilize(List<Msg> messages) {
        List<Msg> out = null;
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (m == null || m.getRole() != MsgRole.SYSTEM) {
                continue;
            }
            String text = m.getTextContent();
            if (text == null || text.isEmpty() || !allText(m.getContent())) {
                continue;
            }
            String stable = stableText(text);
            if (stable.equals(text)) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(messages);
            }
            Msg.Builder b = SystemMessage.builder()
                    .content(TextBlock.builder().text(stable).build());
            if (m.getId() != null) b.id(m.getId());
            if (m.getName() != null) b.name(m.getName());
            if (m.getMetadata() != null) b.metadata(m.getMetadata());
            if (m.getTimestamp() != null) b.timestamp(m.getTimestamp());
            out.set(i, b.build());
        }
        return out;
    }

    static String stableText(String text) {
        String stable = SESSION_ID.matcher(text).replaceAll("$1<session>");
        return CONTAINER_ID.matcher(stable).replaceAll("$1<container>");
    }

    private boolean on() {
        try {
            return enabled != null && enabled.getAsBoolean();
        } catch (Exception e) {
            return false;   // 读不到配置＝不动模型看到的文本
        }
    }

    private static boolean allText(List<ContentBlock> blocks) {
        if (blocks == null) {
            return true;
        }
        for (ContentBlock block : blocks) {
            if (!(block instanceof TextBlock)) {
                return false;
            }
        }
        return true;
    }

    private void announce() {
        if (announced) {
            return;
        }
        announced = true;
        log.info("[PROFILE] 前缀稳定器已生效：system prompt 中的会话 id / 容器 id 已替换为稳定占位");
    }
}
