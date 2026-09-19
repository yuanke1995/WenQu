package com.wisesoft.wenqu.agents;

import java.util.Map;

/**
 * 对应 LangChain / pydantic 消息的 {@code model_dump()} 能力（能力差异，显式标注）。
 *
 * <p>参考实现的 {@code base._json_safe} 在兜底分支里用 {@code hasattr(value, "model_dump")}
 * 判定"是不是可序列化的模型对象"。Java 侧没有 pydantic，也没有 duck typing，故把该判定
 * 收敛为一个显式接口：实现本接口即等价于"有 model_dump"。
 *
 * <p>本工程已有 {@link HumanMessage#rawMessage()}（参考实现里 {@code raw_message()} 与
 * {@code model_dump()} 在消息类上返回同一形状），照搬时统一由 {@link #modelDump()} 承担。
 */
public interface ModelDumpable {

    /** 对应 pydantic {@code model_dump()} / LangChain 消息的序列化字典。 */
    Map<String, Object> modelDump();
}
