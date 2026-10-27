package com.wenqu.ai.service.contentsafety;

/**
 * 外部内容安全服务的结论（各家服务收敛成的统一结果模型）。
 *
 * @param blocked  是否要求拦下
 * @param category 命中类目（用于审计与统计，可为空）
 * @param reason   可读原因（进审计；为空时用默认文案）
 * @param raw      原始响应片段（排障用，落审计时截断）
 *
 * @author yuanke
 */
public record SafetyVerdict(boolean blocked, String category, String reason, String raw) {

    public static SafetyVerdict pass() {
        return new SafetyVerdict(false, null, null, null);
    }
}
