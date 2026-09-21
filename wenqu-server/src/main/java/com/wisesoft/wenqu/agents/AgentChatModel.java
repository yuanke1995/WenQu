package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.service.ModelSelectors;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

/**
 * Agent 运行时聊天模型加载（对应参考实现 {@code yuxi/models/chat.py} 的
 * {@code resolve_chat_model_spec} + {@code load_chat_model}）。
 *
 * <p>逐参对齐：
 * <table border="1">
 *   <caption>参考实现 → 本工程</caption>
 *   <tr><th>参考实现</th><th>本工程</th></tr>
 *   <tr><td>{@code resolve_chat_model_spec(model_spec, *, fallback)}</td>
 *       <td>{@link #resolveChatModelSpec(String, String)}（逐字：非空 strip 优先、都空则
 *           抛「model spec 不能为空」）</td></tr>
 *   <tr><td>{@code load_chat_model(fully_specified_name, *, session_id)}</td>
 *       <td>{@link #loadChatModel(String, String)}：按 spec 取 {@code ModelInfo}，用<b>该模型
 *           所属供应商行</b>的 {@code base_url / api_key / model_id} 构造客户端</td></tr>
 *   <tr><td>{@code BaseChatModel}（langchain，按 spec 实例化）</td>
 *       <td>{@link ModelSelectors#buildChatModel(String)} → spring-ai
 *           {@link OpenAiChatModel}</td></tr>
 * </table>
 *
 * <h3>2026-09-21 修订：改成按 spec 走供应商行（原先标注的能力差异已消）</h3>
 * <p>此前本类注入 {@code DynamicOpenAiChatModel}：那个 bean 的网关三要素恒取自
 * {@code c_ai_config.chat.*}（设置页全局配置），spec 只贡献 model 名，于是「前端在模型供应商页
 * 换了模型/端点，对话请求仍然打那个旧的全局网关」。现改为
 * {@link ModelSelectors#buildChatModel(String)}：<b>spec 所属 provider 行的地址与密钥生效</b>。
 * 随之移除 {@code ModelBoundChatModel} 包装层 —— 新客户端的 {@code getDefaultOptions()}
 * 本身就是带 model 名的 {@code OpenAiChatOptions}（实现 {@code ToolCallingChatOptions}），
 * 满足框架 {@code AgentLlmNode#buildChatOptions} 的类型检查，不会再被重建丢字段。
 *
 * <p>失败语义对齐参考实现：spec 为空 / 未被 {@code enabled_models} 收录 / 不是 chat 模型 /
 * provider_type 不支持，一律抛 {@link IllegalArgumentException}（参考实现同为
 * {@code Unknown model spec}），不做任何全局配置回退。
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>{@code model_cache} 多模型注册表不对位</b>：参考实现每次按 spec 现造模型对象。本工程
 *       在 {@link ModelSelectors#buildChatModel(String)} 内按「spec + 三要素指纹」缓存实例：
 *       同一 spec 且配置未变时复用，供应商改地址/密钥后指纹变化自动换新 —— 语义等价，只是避免
 *       每次构图都新建一个 WebClient。</li>
 *   <li><b>{@code session_id} 未使用</b>：参考实现用它给 OpenCode 请求绑定稳定会话路由；
 *       本工程的模型调用不做该路由，参数仅为对齐调用面而保留。</li>
 * </ol>
 */
@Service
public class AgentChatModel {

    private final ModelSelectors modelSelectors;

    public AgentChatModel(ModelSelectors modelSelectors) {
        this.modelSelectors = modelSelectors;
    }

    /**
     * 解析空模型配置（对应 {@code resolve_chat_model_spec}），不吞掉已配置但无效的模型值。
     *
     * <p>仅处理模型为空时的优先级：请求/配置值 → 调用方 fallback → 都为空则抛错
     * （不再回退任何全局默认模型）。
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
     * <p>模型 spec 形如 {@code provider_id:model_id}：baseUrl / apiKey / model 三要素全部由
     * 该 spec 对应的供应商行决定，本类不再切分 model_id。
     *
     * @param fullySpecifiedName 模型 spec（对应 {@code fully_specified_name}）
     * @param sessionId 会话 id（见类注释能力差异 2：保留参数、当前未使用）
     */
    public ChatModel loadChatModel(String fullySpecifiedName, String sessionId) {
        String spec = resolveChatModelSpec(fullySpecifiedName, null);
        return modelSelectors.buildChatModel(spec);
    }
}
