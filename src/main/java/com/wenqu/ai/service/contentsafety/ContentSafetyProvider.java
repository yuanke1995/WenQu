package com.wenqu.ai.service.contentsafety;

/**
 * 内容安全外部检查服务（SPI）。
 * <p>
 * 与联网搜索同一套服务商无关的形态：实现类只管「把一段文本送去、拿回一个结论」，
 * 规则表、审计、流式截取都归 {@code ContentSafetyService}，各家服务（阿里云内容安全、
 * 腾讯天御、自建模型服务）都收敛到 {@link SafetyVerdict} 这一个结果模型。
 * <p>
 * 实现类以 Spring Bean 注册，{@code id()} 与配置项 {@code contentSafety.provider} 对应。
 * 内置规则（{@code c_ai_content_rule}）永远先跑，这一层是<b>追加</b>的第二道闸，不是替代。
 *
 * @author yuanke
 */
public interface ContentSafetyProvider {

    /** 服务标识（与配置 contentSafety.provider 取值一致；local 表示不接外部服务） */
    String id();

    /**
     * 检查一段文本。
     *
     * @param req 待检文本与归属
     * @return 结论（{@code null} 视作「未拦」）
     * @throws Exception 失败一律抛出——由 {@code ContentSafetyService} 记审计并保持内置规则的结论，
     *                   不在这里替调用方决定放行还是拦
     */
    SafetyVerdict check(SafetyRequest req) throws Exception;

    /** 一次检查的入参 */
    record SafetyRequest(String text, String stage, String userId, String sessionId,
                         String baseUrl, String apiKey, int timeoutMs) {
    }
}
