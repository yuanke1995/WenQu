package com.wenqu.ai.agent.runcode;

/**
 * 子调用被本轮限制挡下（超上限 / 本轮已叫停 / 工具不认识）。
 * <p>
 * 单独一个类型而不是复用 {@code BizException}：这条异常要跨 HTTP 回到容器里，
 * 语义必须是<b>结构化错误字段</b>而不是"网关 500"——DeepSeek Harness 那条「失败是字段不是 reject」
 * 的口径在这里同样成立，脚本要能看懂"为什么这次没跑成"并继续（比如少取一份资料后如实说明）。
 *
 * @author yuanke
 */
public class RunLimitException extends RuntimeException {

    public RunLimitException(String message) {
        super(message);
    }
}
