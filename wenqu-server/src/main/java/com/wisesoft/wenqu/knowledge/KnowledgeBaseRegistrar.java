package com.wisesoft.wenqu.knowledge;

import org.springframework.context.annotation.Configuration;

/**
 * 知识库类型注册（对应参考实现的 knowledge/runtime.py）。
 *
 * <p>参考实现在模块导入时执行三次注册：
 * {@code KnowledgeBaseFactory.register(MilvusKB / DifyKB / NotionKB)}，随后创建
 * {@code KnowledgeBaseManager} 单例。本工程把 manager 门面与 executor 收敛为容器 bean
 * {@link KnowledgeBaseRuntime}，故此处只承担「注册 kb_type → 实例供应器」这一职责，
 * 由 Spring 在启动时完成（等价于 runtime.py 的模块级注册副作用）。
 *
 * <p>能力差异（如实标注，不谎称已支持）：
 * <ul>
 *   <li>{@code milvus} 已注册，供应器返回 {@link KnowledgeBaseRuntime}（本工程的向量知识库执行器）。
 *   <li>{@code dify} / {@code notion}（外部只读检索连接器，对应 ReadOnlyConnectors）：
 *       <b>参数面已移植完成</b>——{@link KnowledgeBaseTypeParams} 已按参考实现逐字承载二者的
 *       {@code get_create_params_config} / {@code validate_additional_params} /
 *       {@code get_query_params_config}，{@link ReadOnlyConnectors} 也已承载只读能力判定与报错文案。
 *       但二者的 {@code aquery}（真实检索实现）依赖 Dify / Notion 外部 HTTP 接口，尚未移植，
 *       因此<b>不注册</b>：未注册时 {@link KnowledgeBaseFactory#isTypeSupported} 返回 false，
 *       {@link KnowledgeBaseRuntime#getKbConfig} 会以 KBNotFoundError 明确拒绝——不会静默
 *       当成 milvus 处理，也不会放行一个「可创建但检不了」的知识库。检索实现移植后在此补两行注册即可。
 * </ul>
 */
@Configuration
public class KnowledgeBaseRegistrar {

    public KnowledgeBaseRegistrar(KnowledgeBaseRuntime knowledgeBaseRuntime) {
        KnowledgeBaseFactory.register(new KnowledgeBaseFactory.KbTypeInfo(
                "milvus",
                "Milvus",
                "基于 Milvus 的生产级向量知识库，适合高性能部署",
                true,
                true,
                new java.util.LinkedHashMap<>(KnowledgeBaseTypeParams.createParamsConfig("milvus")),
                () -> knowledgeBaseRuntime));
    }
}
