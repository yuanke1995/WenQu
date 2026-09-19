package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.wisesoft.wenqu.agents.BaseContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * 通用的 Context 相关中间件（对应参考实现 {@code agents/middlewares/context.py}）。
 *
 * <p>参考实现用两个装饰器承载同一职责：
 * <ul>
 *   <li>{@code @dynamic_prompt} 的 {@code context_aware_prompt(request)} —— 从 runtime context
 *       动态生成系统提示词（返回 {@code request.runtime.context.system_prompt}）；</li>
 *   <li>{@code @wrap_model_call} 的 {@code context_based_model(request, handler)} ——
 *       从 runtime context 动态选择模型。</li>
 * </ul>
 *
 * <p>本类把两者合为一个 {@link ModelInterceptor}（框架的 {@code interceptModel} 即
 * {@code wrap_model_call} 的等价物），在一次拦截里先换提示词、再换模型，顺序与参考实现一致。
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ol>
 *   <li><b>模型替换无对应物</b>：参考实现的 {@code request.override(model=model)} 依赖
 *       LangChain {@code ModelRequest.model} 字段，而 Java 框架的 {@link ModelRequest}
 *       <b>没有 model 字段</b>（只有 systemMessage/messages/options/tools/dynamicToolCallbacks/
 *       toolDescriptions/context）。故本类<b>只做提示词注入</b>，模型选择落在
 *       {@code resolveChatModelSpec}/{@code loadChatModel} 的调用方（构图时选模型），
 *       此处保留同名扩展点 {@link #resolveModelSpec(BaseContext)} 供上游接线。</li>
 *   <li><b>无 systemMessage 时新建</b>：参考实现的 {@code dynamic_prompt} 直接产出提示词字符串；
 *       本类在 {@code request.getSystemMessage() == null} 时新建 {@link SystemMessage}，
 *       非空时<b>整条替换</b>（与 {@code dynamic_prompt} 的"动态提示词即系统提示词"语义一致，
 *       而非追加）。</li>
 *   <li><b>logger.debug 的截断字段</b>：参考实现打印 {@code request.messages[-1].content[:200]}；
 *       本类按同一 200 字符截断，但取最后一条<b>有文本</b>的消息（框架消息的文本读取统一走
 *       {@code getText()}）。</li>
 * </ol>
 */
public class ContextAwareInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ContextAwareInterceptor.class);

    /** 与参考实现 {@code request.messages[-1].content[:200]} 的 200 一致。 */
    private static final int LOG_CONTENT_LIMIT = 200;

    /** runtime context 在本工程里由构图方以该键放进 {@link ModelRequest#getContext()}。 */
    public static final String CONTEXT_KEY = "context";

    @Override
    public String getName() {
        return "context_aware";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        BaseContext context = contextOf(request);
        if (context == null) {
            return handler.call(request);
        }

        String systemPrompt = context.getString("system_prompt");
        ModelRequest updated = request;
        if (systemPrompt != null) {
            updated = ModelRequest.builder(request)
                    .systemMessage(new SystemMessage(systemPrompt))
                    .build();
        }

        String modelSpec = resolveModelSpec(context);
        if (log.isDebugEnabled()) {
            log.debug("Using model {} for request {}", modelSpec, lastMessagePreview(updated));
        }
        return handler.call(updated);
    }

    /**
     * 从 runtime context 解析模型规格。
     *
     * <p>能力差异（见类注释 1）：Java 框架的 {@link ModelRequest} 无 model 字段，
     * 无法在拦截器内替换模型实例。此方法保留参考实现
     * {@code resolve_chat_model_spec(request.runtime.context.model)} 的<b>读取口径</b>
     * （取 {@code context.model}，空串视为未指定），供构图方在构建 {@code GraphFactory}
     * 时据此选模型；本类自身不执行替换。
     */
    protected String resolveModelSpec(BaseContext context) {
        String spec = context.getString("model");
        return spec == null || spec.isEmpty() ? null : spec;
    }

    /** 取 {@link ModelRequest#getContext()} 里的运行时上下文。 */
    protected BaseContext contextOf(ModelRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object value = raw == null ? null : raw.get(CONTEXT_KEY);
        return value instanceof BaseContext context ? context : null;
    }

    /** 对应参考实现的 {@code request.messages[-1].content[:200]}。 */
    private static String lastMessagePreview(ModelRequest request) {
        List<org.springframework.ai.chat.messages.Message> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            String text = messages.get(i).getText();
            if (text != null && !text.isEmpty()) {
                return text.length() <= LOG_CONTENT_LIMIT ? text : text.substring(0, LOG_CONTENT_LIMIT);
            }
        }
        return "";
    }

    /** 供构图方构造 {@link ModelRequest#getContext()} 的便捷方法（键名与 {@link #CONTEXT_KEY} 一致）。 */
    public static Map<String, Object> contextMap(BaseContext context) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(CONTEXT_KEY, context);
        return map;
    }
}
