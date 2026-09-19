package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.service.DynamicOpenAiChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

/**
 * Agent 运行时聊天模型加载（对应参考实现 {@code yuxi/models/chat.py} 的
 * {@code resolve_chat_model_spec} + {@code load_chat_model} 中被构图路径用到的那一面）。
 *
 * <p>逐参对齐：
 * <table border="1">
 *   <caption>参考实现 → 本工程</caption>
 *   <tr><th>参考实现</th><th>本工程</th></tr>
 *   <tr><td>{@code resolve_chat_model_spec(model_spec, *, fallback)}</td>
 *       <td>{@link #resolveChatModelSpec(String, String)}（逐字：非空 strip 优先、都空则
 *           抛「model spec 不能为空」）</td></tr>
 *   <tr><td>{@code load_chat_model(fully_specified_name, *, session_id)}</td>
 *       <td>{@link #loadChatModel(String, String)}</td></tr>
 *   <tr><td>{@code BaseChatModel}（langchain）</td>
 *       <td>{@link ChatModel}（spring-ai）；实现体为
 *           {@link DynamicOpenAiChatModel}（跨厂商热切换，读 {@code c_ai_config.chat.*}）</td></tr>
 * </table>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>{@code model_cache} 多模型注册表不对位</b>：参考实现按 {@code fully_specified_name}
 *       在 {@code model_cache} 中查 {@code ModelInfo}，查不到抛
 *       {@code Unknown model spec: '…'}，查到后校验 {@code model_type == "chat"}。
 *       本工程的 chat 模型是<b>单一热切换实例</b>（{@link DynamicOpenAiChatModel} 自身从
 *       {@code c_ai_config} 读 baseUrl/apiKey/model），没有「按 spec 选模型」这一步，
 *       故本方法只做 {@link #resolveChatModelSpec} 的非空校验——<b>不</b>抛
 *       {@code Unknown model spec}，也不做 type 校验。模型名本身由配置侧保证。</li>
 *   <li><b>{@code session_id} 未使用</b>：参考实现用它给 OpenCode 请求绑定稳定会话路由；
 *       本工程的模型调用不做该路由，参数仅为对齐调用面而保留。</li>
 * </ol>
 */
@Service
public class AgentChatModel {

    private final DynamicOpenAiChatModel chatModel;

    public AgentChatModel(DynamicOpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 解析空模型配置（对应 {@code resolve_chat_model_spec}），不吞掉已配置但无效的模型值。
     *
     * <p>仅处理模型为空时的优先级：请求/配置值 → 调用方 fallback → 都为空则抛错。
     *
     * @param modelSpec 请求或配置里的模型值
     * @param fallback 调用方兜底值（无则传 {@code null}）
     * @return strip 后的非空模型值
     * @throws IllegalArgumentException 两个候选都为空（对应参考实现 {@code ValueError}）
     */
    public static String resolveChatModelSpec(String modelSpec, String fallback) {
        for (String candidate : new String[] {modelSpec, fallback}) {
            if (candidate != null && !candidate.strip().isEmpty()) {
                return candidate.strip();
            }
        }
        throw new IllegalArgumentException("model spec 不能为空");
    }

    /**
     * 取本次 Run 使用的聊天模型（对应 {@code load_chat_model} 的调用面）。
     *
     * @param fullySpecifiedName 模型值（对应 {@code fully_specified_name}）
     * @param sessionId 会话 id（见类注释能力差异 2：保留参数、当前未使用）
     */
    public ChatModel loadChatModel(String fullySpecifiedName, String sessionId) {
        resolveChatModelSpec(fullySpecifiedName, null);
        return chatModel;
    }
}
