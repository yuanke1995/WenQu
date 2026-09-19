package com.wisesoft.wenqu.models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置模型供应商定义。
 *
 * <p>由参考实现 models/providers/builtin.py 的 {@code BUILTIN_PROVIDERS} 逐条翻译：
 * 条目顺序、provider_id、display_name、base_url、embedding/rerank_base_url、
 * api_key_env、capabilities、models / embedding / rerank 端点，以及 enabled_models
 * 的每一项（id/type/display_name，embedding 另带 dimension 与 batch_size）完全一致。
 *
 * <p>注：参考实现中被注释掉的两个模板（Anthropic、Google Gemini）本清单同样不包含，
 * 与参考实现的实际生效清单保持一致。
 *
 * <p>平台差异（必要替换）：Python 的 {@code list[dict]} 字面量 → Java 的有序表构建；
 * 键的插入顺序与参考实现逐一对应（{@code ensure_builtin_model_providers_in_db} 会按此顺序取值）。
 */
public final class BuiltinProviders {

    /** 内置模型供应商模板清单。 */
    public static final List<Map<String, Object>> BUILTIN_PROVIDERS = List.of(
            provider(map -> {
                map.put("provider_id", "fluxionai");
                map.put("display_name", "Fluxion AI");
                map.put("base_url", "https://fluxionai.space/v1");
                map.put("api_key_env", "FLUXIONAI_API_KEY");
                map.put("models_endpoint", "https://fluxionai.space/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "openai");
                map.put("display_name", "OpenAI");
                map.put("base_url", "https://api.openai.com/v1");
                map.put("api_key_env", "OPENAI_API_KEY");
                map.put("models_endpoint", "https://api.openai.com/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "deepseek");
                map.put("display_name", "DeepSeek");
                map.put("base_url", "https://api.deepseek.com");
                map.put("api_key_env", "DEEPSEEK_API_KEY");
                map.put("models_endpoint", "https://api.deepseek.com/models");
            }),
            provider(map -> {
                map.put("provider_id", "alibaba-cn");
                map.put("display_name", "DashScope");
                map.put("base_url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
                map.put(
                        "embedding_base_url",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");
                map.put(
                        "rerank_base_url",
                        "https://dashscope.aliyuncs.com/compatible-api/v1/reranks");
                map.put("api_key_env", "DASHSCOPE_API_KEY");
                map.put("capabilities", List.of("chat", "embedding", "rerank"));
                map.put(
                        "models_endpoint",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/models");
                map.put(
                        "enabled_models",
                        List.of(
                                model(
                                        "text-embedding-v4",
                                        "embedding",
                                        "text-embedding-v4",
                                        1024,
                                        null),
                                model("qwen3-rerank", "rerank", "qwen3-rerank", null, null)));
            }),
            provider(map -> {
                map.put("provider_id", "alibaba");
                map.put("display_name", "DashScope (International)");
                map.put("base_url", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1");
                map.put("api_key_env", "DASHSCOPE_API_KEY");
                map.put(
                        "models_endpoint",
                        "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "alibaba-coding-plan-cn");
                map.put("display_name", "Aliyun Coding Plan");
                map.put("base_url", "https://coding.dashscope.aliyuncs.com/v1");
                map.put("api_key_env", "DASHSCOPE_API_KEY");
                map.put("models_endpoint", "https://coding.dashscope.aliyuncs.com/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "alibaba-coding-plan");
                map.put("display_name", "Aliyun Coding Plan (International)");
                map.put("base_url", "https://coding-intl.dashscope.aliyuncs.com/v1");
                map.put("api_key_env", "DASHSCOPE_API_KEY");
                map.put("models_endpoint", "https://coding-intl.dashscope.aliyuncs.com/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "zhipuai");
                map.put("display_name", "Zhipu (BigModel)");
                map.put("base_url", "https://open.bigmodel.cn/api/paas/v4");
                map.put("api_key_env", "ZHIPUAI_API_KEY");
                map.put("models_endpoint", "https://open.bigmodel.cn/api/paas/v4/models");
            }),
            provider(map -> {
                map.put("provider_id", "zhipuai-coding-plan");
                map.put("display_name", "Zhipu Coding Plan (BigModel)");
                map.put("base_url", "https://open.bigmodel.cn/api/coding/paas/v4");
                map.put("api_key_env", "ZHIPUAI_API_KEY");
                map.put("models_endpoint", "https://open.bigmodel.cn/api/coding/paas/v4/models");
            }),
            provider(map -> {
                map.put("provider_id", "zai");
                map.put("display_name", "Zhipu (Z.AI)");
                map.put("base_url", "https://api.z.ai/api/paas/v4");
                map.put("api_key_env", "ZAI_API_KEY");
                map.put("models_endpoint", "https://api.z.ai/api/paas/v4/models");
            }),
            provider(map -> {
                map.put("provider_id", "zai-coding-plan");
                map.put("display_name", "Zhipu Coding Plan (Z.AI)");
                map.put("base_url", "https://api.z.ai/api/coding/paas/v4");
                map.put("api_key_env", "ZAI_API_KEY");
                map.put("models_endpoint", "https://api.z.ai/api/coding/paas/v4/models");
            }),
            provider(map -> {
                map.put("provider_id", "xiaomi-token-plan-cn");
                map.put("display_name", "XiaomiMiMo Token Plan");
                map.put("base_url", "https://token-plan-cn.xiaomimimo.com/v1");
                map.put("api_key_env", "XIAOMI_MIMO_TOKEN_PLAN_API_KEY");
                map.put("models_endpoint", "https://token-plan-cn.xiaomimimo.com/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "xiaomi");
                map.put("display_name", "XiaomiMiMo");
                map.put("base_url", "https://api.xiaomimimo.com/v1");
                map.put("api_key_env", "XIAOMI_MIMO_API_KEY");
                map.put("models_endpoint", "https://api.xiaomimimo.com/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "kimi-for-coding");
                map.put("display_name", "Kimi Code");
                map.put("base_url", "https://api.kimi.com/coding/v1");
                map.put("api_key_env", "KIMI_CODE_API_KEY");
                map.put("models_endpoint", "https://api.kimi.com/coding/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "moonshotai-cn");
                map.put("display_name", "Moonshot");
                map.put("base_url", "https://api.moonshot.cn/v1");
                map.put("api_key_env", "MOONSHOT_API_KEY");
                map.put("models_endpoint", "https://api.moonshot.cn/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "moonshotai");
                map.put("display_name", "Moonshot (International)");
                map.put("base_url", "https://api.moonshot.ai/v1");
                map.put("api_key_env", "MOONSHOT_API_KEY");
                map.put("models_endpoint", "https://api.moonshot.ai/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "minimax-cn");
                map.put("display_name", "MiniMax");
                map.put("base_url", "https://api.minimaxi.com/v1");
                map.put("api_key_env", "MINIMAX_API_KEY");
                map.put("models_endpoint", "https://api.minimaxi.com/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "minimax");
                map.put("display_name", "MiniMax (International)");
                map.put("base_url", "https://api.minimax.io/v1");
                map.put("api_key_env", "MINIMAX_API_KEY");
                map.put("models_endpoint", "https://api.minimax.io/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "openrouter");
                map.put("display_name", "OpenRouter");
                map.put("base_url", "https://openrouter.ai/api/v1");
                map.put("api_key_env", "OPENROUTER_API_KEY");
                map.put("capabilities", List.of("chat", "embedding"));
                map.put("embedding_base_url", "https://openrouter.ai/api/v1/embeddings");
                map.put("models_endpoint", "https://openrouter.ai/api/v1/models");
                map.put(
                        "embedding_models_endpoint",
                        "https://openrouter.ai/api/v1/embeddings/models");
            }),
            provider(map -> {
                map.put("provider_id", "modelscope");
                map.put("display_name", "ModelScope");
                map.put("base_url", "https://api-inference.modelscope.cn/v1");
                map.put("api_key_env", "MODELSCOPE_ACCESS_TOKEN");
                map.put("models_endpoint", "https://api-inference.modelscope.cn/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "opencode");
                map.put("display_name", "OpenCode");
                map.put("base_url", "https://opencode.ai/zen/v1");
                map.put("models_endpoint", "https://opencode.ai/zen/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "opencode-go");
                map.put("display_name", "OpenCode Go");
                map.put("base_url", "https://opencode.ai/zen/go/v1");
                map.put("models_endpoint", "https://opencode.ai/zen/go/v1/models");
            }),
            provider(map -> {
                map.put("provider_id", "siliconflow-cn");
                map.put("display_name", "SiliconFlow");
                map.put("base_url", "https://api.siliconflow.cn/v1");
                map.put("embedding_base_url", "https://api.siliconflow.cn/v1/embeddings");
                map.put("rerank_base_url", "https://api.siliconflow.cn/v1/rerank");
                map.put("api_key_env", "SILICONFLOW_API_KEY");
                map.put("capabilities", List.of("chat", "embedding", "rerank"));
                map.put("models_endpoint", "https://api.siliconflow.cn/v1/models?sub_type=chat");
                map.put(
                        "embedding_models_endpoint",
                        "https://api.siliconflow.cn/v1/models?sub_type=embedding");
                map.put(
                        "rerank_models_endpoint",
                        "https://api.siliconflow.cn/v1/models?sub_type=reranker");
                map.put(
                        "enabled_models",
                        List.of(
                                model(
                                        "deepseek-ai/DeepSeek-V4-Flash",
                                        "chat",
                                        "deepseek-ai/DeepSeek-V4-Flash",
                                        null,
                                        null),
                                model(
                                        "Pro/MiniMaxAI/MiniMax-M2.5",
                                        "chat",
                                        "Pro/MiniMaxAI/MiniMax-M2.5",
                                        null,
                                        null),
                                model("zai-org/GLM-5.2", "chat", "zai-org/GLM-5.2", null, null),
                                model("Pro/BAAI/bge-m3", "embedding", "Pro/BAAI/bge-m3", 1024, 40),
                                model("BAAI/bge-m3", "embedding", "BAAI/bge-m3", 1024, 40),
                                model(
                                        "Qwen/Qwen3-Embedding-0.6B",
                                        "embedding",
                                        "Qwen/Qwen3-Embedding-0.6B",
                                        1024,
                                        40),
                                model(
                                        "Pro/BAAI/bge-reranker-v2-m3",
                                        "rerank",
                                        "Pro/BAAI/bge-reranker-v2-m3",
                                        null,
                                        null),
                                model(
                                        "BAAI/bge-reranker-v2-m3",
                                        "rerank",
                                        "BAAI/bge-reranker-v2-m3",
                                        null,
                                        null)));
            }),
            provider(map -> {
                map.put("provider_id", "siliconflow");
                map.put("display_name", "SiliconFlow (International)");
                map.put("base_url", "https://api.siliconflow.com/v1");
                map.put("embedding_base_url", "https://api.siliconflow.com/v1/embeddings");
                map.put("rerank_base_url", "https://api.siliconflow.com/v1/rerank");
                map.put("api_key_env", "SILICONFLOW_GLOBAL_API_KEY");
                map.put("capabilities", List.of("chat", "embedding", "rerank"));
                map.put("models_endpoint", "https://api.siliconflow.com/v1/models?sub_type=chat");
                map.put(
                        "embedding_models_endpoint",
                        "https://api.siliconflow.com/v1/models?sub_type=embedding");
                map.put(
                        "rerank_models_endpoint",
                        "https://api.siliconflow.com/v1/models?sub_type=reranker");
            }));

    private BuiltinProviders() {}

    private interface EntryBuilder {
        void accept(Map<String, Object> map);
    }

    private static Map<String, Object> provider(EntryBuilder builder) {
        Map<String, Object> map = new LinkedHashMap<>();
        builder.accept(map);
        return map;
    }

    /**
     * 构建 enabled_models 的单项。
     *
     * <p>键顺序与参考实现一致：id / type / display_name /[dimension] /[batch_size]
     * （dimension 与 batch_size 仅在模板里显式给出时才出现）。
     */
    private static Map<String, Object> model(
            String id, String type, String displayName, Integer dimension, Integer batchSize) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type);
        map.put("display_name", displayName);
        if (dimension != null) {
            map.put("dimension", dimension);
        }
        if (batchSize != null) {
            map.put("batch_size", batchSize);
        }
        return map;
    }
}
