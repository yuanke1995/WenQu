package com.wisesoft.ai.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token 计数工具（jtokkit = tiktoken 的纯 Java 实现，BPE 词表计数），
 * 用于上下文预算控制与网关 usage 缺失时的用量回落估算。
 * <p>
 * 计数口径：cl100k_base（GPT-3.5/4 词表，生态事实标准）。对 OpenAI 系模型是精确计数；
 * 对 DeepSeek/GLM/Qwen 等自带分词器的模型是近似——但 BPE 词表远比按字符启发式接近真实值。
 * 选 cl100k 而非更新的 o200k，是因为它对中文约 1 token/字，普遍高于国产分词器的合并效率：
 * 高估是安全方向（预算少塞不超窗），o200k 的词级合并更贴国产词表但存在低估域、风险方向反了。
 * <p>
 * 两条边界：
 * - 网关侧 prompt 还含 chat template / 工具 schema 等额外 token，本计数不含；超窗余量由
 *   上下文预算的安全系数（ModelRegistryService.CONTEXT_SAFETY_FACTOR）统一承担，这里只负责数准；
 * - 真实用量以网关返回的 usage 为准（realPromptTokens/realOutputTokens），本计数仅在其缺失时回落。
 *
 * @author yuanke
 */
public final class TokenCounter {

    /** cl100k_base 编码（Encoding 线程安全；注册表首次调用时载入词表，之后常驻） */
    private static final Encoding ENCODING =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private TokenCounter() {
    }

    /**
     * 计数文本的 token 数（cl100k_base BPE 计数，不加估算余量——
     * 超窗防护由上下文预算的安全系数承担，这里只负责数准）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ENCODING.countTokens(text);
    }
}
