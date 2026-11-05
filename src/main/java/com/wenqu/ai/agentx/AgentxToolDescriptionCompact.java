package com.wenqu.ai.agentx;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 工具说明精简：每次模型调用前，把 {@code tools} 里每把工具的<b>使用说明</b>压到第一句再发出去。
 *
 * <p><b>为什么要这一刀</b>：工具说明是<b>每一轮都要重付的固定上下文</b>——它不进缓存、也不因本轮
 * 用不用这把工具而变，一轮几十把工具攒下来就是每次调用都交一遍的租金。而说明的结构几乎都是
 * 「第一句说这把工具干什么 ＋ 后面几段说什么时候用、注意什么」：后者对<b>已经拿到参数与工具名</b>的
 * 模型增益有限，代价却是每一轮重复付。
 *
 * <p><b>只改发出去的那一份文本</b>：挂点在 {@code onModelCall} 的 {@link ModelCallInput#tools()}，
 * 换的是请求里的 {@link ToolSchema#getDescription()}。<b>不碰注册表</b>——工具名、参数 schema、
 * 只读档位、权限判定（{@code ToolBase#checkPermissions} 那套危险路径闸）、执行体、显示名映射
 * 全都在原来的对象上，一个都不动。所以这把开关关掉即恢复原样，不存在「压完权限就松了」这种事。
 * <b>也不碰参数说明</b>：参数那一段是用得准不准的关键，压它是省小钱冒大险。
 *
 * <p><b>不硬截断</b>：只有「取到第一句、且首句本身就不长」才压。首句里没有句末标点、首句超过
 * {@link #MAX_COMPACT_CHARS}、或压完没省到字的，一律原样发——把一句到一半砍断，模型读到的是
 * 一条说不清自己能力的工具，那比多付几百字更亏。
 *
 * <p><b>开关按调用现读</b>（{@code chat.toolDescriptionCompact}）：会话实例是按 {@code (用户,会话,模型)}
 * 常驻的，middleware 只能在 build 期挂上（见 {@link FrameworkSessions} 的注释），所以挂载与开关解耦——
 * 每次调用问一次配置。设置页改完下一次模型调用就生效，不需要等实例换代。
 *
 * <p>容量面板的「工具声明」一段走 {@link #applyAll}，与这里发出去的是同一份口径，
 * 免得面板按完整说明估、真实请求按精简说明跑。
 */
public final class AgentxToolDescriptionCompact implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentxToolDescriptionCompact.class);

    /** 首句超过这个字数就不压了：说明「这一条工具本来就没有一句话版本」，砍断只会读到半句话。 */
    static final int MAX_COMPACT_CHARS = 160;

    private final BooleanSupplier enabled;

    /** 首轮生效时 announce 一次：这条通道改了模型看到的东西，不许静默。 */
    private volatile boolean announced;

    public AgentxToolDescriptionCompact(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (input == null || !on()) {
            return next.apply(input);
        }
        List<ToolSchema> compacted;
        try {
            compacted = compactList(input.tools());
        } catch (Exception e) {
            // 精简是纯优化，绝不是这一轮的前提：出错就按完整说明发，本轮照跑
            log.warn("[agentx] 工具说明精简失败，本轮按完整说明发送: {}", e.toString());
            return next.apply(input);
        }
        if (compacted == null) {
            return next.apply(input);
        }
        announce(compacted.size(), input.tools().size());
        return next.apply(new ModelCallInput(input.messages(), compacted, input.options(), input.model()));
    }

    /** 按当前开关给容量面板一份与真实请求同口径的工具声明（没开就原样返回）。 */
    public static List<ToolSchema> applyAll(List<ToolSchema> schemas, boolean enabled) {
        if (!enabled || schemas == null || schemas.isEmpty()) {
            return schemas;
        }
        try {
            List<ToolSchema> out = compactList(schemas);
            return out == null ? schemas : out;
        } catch (Exception e) {
            log.warn("[agentx] 工具声明估算精简失败，按完整说明估算: {}", e.toString());
            return schemas;
        }
    }

    private boolean on() {
        try {
            return enabled != null && enabled.getAsBoolean();
        } catch (Exception e) {
            return false;   // 读不到配置＝不动模型看到的东西
        }
    }

    private void announce(int compacted, int total) {
        if (announced) {
            return;
        }
        announced = true;
        log.info("[agentx] 工具说明精简已生效: 本轮 {} / {} 把工具的说明压到第一句（开关 chat.toolDescriptionCompact）",
                compacted, total);
    }

    /**
     * 逐条压；一条都没省下就返回 null（调用方原样发，不新建 {@link ModelCallInput}）。
     */
    private static List<ToolSchema> compactList(List<ToolSchema> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<ToolSchema> out = null;
        for (int i = 0; i < tools.size(); i++) {
            ToolSchema t = tools.get(i);
            if (t == null) {
                continue;
            }
            String compact = firstSentence(t.getDescription());
            if (compact == null) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(tools.size());
                for (int k = 0; k < i; k++) {
                    out.add(tools.get(k));
                }
            }
            out.add(ToolSchema.builder()
                    .name(t.getName())
                    .description(compact)
                    .parameters(t.getParameters())
                    .outputSchema(t.getOutputSchema())
                    .strict(t.getStrict())
                    .build());
        }
        return out;
    }

    /**
     * 取第一句：中文按 {@code 。！？} 断，拉丁按 {@code . ! ?} 且后面跟空白或到尾断。
     *
     * @return 压短后的说明；取不出「更短且完整的一句」时返回 null（调用方原样发）
     */
    static String firstSentence(String description) {
        if (description == null) {
            return null;
        }
        String text = description.strip();
        if (text.isEmpty()) {
            return null;
        }
        int cut = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？') {
                cut = i + 1;
                break;
            }
            if (c == '.' || c == '!' || c == '?') {
                char prev = i > 0 ? text.charAt(i - 1) : ' ';
                // 小数与版本号不算句末（3.5、v1.2、2026-11-06 这类）
                if (prev >= '0' && prev <= '9') {
                    continue;
                }
                char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
                if (next == ' ' || next == '\n' || next == '\t' || i + 1 == text.length()) {
                    cut = i + 1;
                    break;
                }
            }
        }
        if (cut <= 0) {
            return null;
        }
        String head = text.substring(0, cut).strip();
        // 没省到字（整条本来就一句）不动
        if (head.isEmpty() || head.length() >= text.length()) {
            return null;
        }
        return head.length() > MAX_COMPACT_CHARS ? null : head;
    }
}
