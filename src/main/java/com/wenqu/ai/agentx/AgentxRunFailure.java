package com.wenqu.ai.agentx;

import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ModelQuotaGuard;
import com.wenqu.ai.service.UsageQuotaService;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 把框架留下的失败痕迹说成用户能行动的一句话（从 AgentscopeChatEngine 抽出）。
 * 零字段依赖的纯映射：断流 / 配额 / 限流 / 超时判死 / 连不上 / 通用，各说各的可执行动作。
 * 判据与手写引擎那一组同源（402 在两条运行时里必须长成同一句话）。
 *
 * @author yuanke
 */
@Slf4j
public final class AgentxRunFailure {

    private AgentxRunFailure() {
    }

    /** 「流出过正文、但上游没给末块就把连接收了」的内部指纹。 */
    public static final String UPSTREAM_STREAM_CUT = "wenqu:upstream-stream-cut";

    public static String emit(WenquSourcePool pool, RunChannel channel, String raw) {
        // 断流自己说一句：把它塞进上面那套「额度／限流／连不上」的分类会得到一句通用的
        // 「AI 回复失败，请稍后重试」——可那一轮其实<b>说出了半截话</b>，用户要的是「这段不完整」，
        // 不是「重试就好」。
        if (UPSTREAM_STREAM_CUT.equals(raw)) {
            String msg = "模型这一轮中途断流，上面这段可能不完整：上游没有给出结束标记就收了连接。可以发一句「继续」让它接着说";
            pool.registrar().degrade("upstreamStreamCut", msg);
            channel.emit(RunEventType.ERROR, msg);
            log.info("[agentx] 本轮断流收束: session 侧已提示不完整");
            return msg;
        }
        // 自家配额闸那句已经点名的规则、用了多少、什么时候恢复——比通用那句「额度不足去充值」准，比通用那句「额度不足去充值」准，
        // 原样交给用户（它本来就是写给人看的中文）。落到下面的通用分类会把「找管理员放宽月度预算」
        // 说成「去模型供应商充值」，指错方向。
        if (raw.startsWith(com.wenqu.ai.service.UsageQuotaService.REFUSAL_PREFIX)) {
            pool.registrar().degrade("tenantQuotaExhausted", raw);
            channel.emit(RunEventType.ERROR, raw);
            log.info("[agentx] 本轮被配额闸拦下: {}", raw);
            return raw;
        }
        boolean quota = com.wenqu.ai.service.ModelQuotaGuard.isQuotaText(raw);
        boolean limited = !quota && com.wenqu.ai.service.ModelQuotaGuard.isRateLimitedText(raw);
        // 静默判死那条（ModelUtils 的 timeout 包成 ModelException「Model request timeout after PT60S」，
        // 重试烧完再包成「Retries exhausted: 2/2」）必须单独说一句：通用那句「请稍后重试」不知道重试
        // 也是白重试——上游已经按设置的时长判死过两轮。指出的动作是「等一会再问」或「换个响应快的
        // 模型」，不是原地反复按发送。只认 timeout 这个词的话，Retries exhausted 那一支照样落进通用文案。
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (!quota && !limited && (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("retries exhausted"))) {
            String msg = "模型服务没有响应：上游长时间不回内容，已按「模型无响应上限」判死并重试过仍无结果。"
                    + "稍后再问，或在「智能体 → 模型供应商」换一个响应快的模型";
            pool.registrar().degrade("modelUnresponsive", msg);
            channel.emit(RunEventType.ERROR, msg);
            log.info("[agentx] 本轮上游无响应收束: 原文={}", raw.length() > 200 ? raw.substring(0, 200) : raw);
            return msg;
        }
        boolean unreachable = !quota && !limited
                && raw.toLowerCase(java.util.Locale.ROOT).contains("connect");
        String msg = quota ? "模型服务额度不足：请到「模型供应商」充值，或改用其他模型"
                : limited ? "模型服务限流，请稍后重试"
                : unreachable ? "无法连接 AI 服务，请检查网络或 API 地址"
                : "AI 回复失败，请稍后重试";
        pool.registrar().degrade(quota ? "modelQuotaExhausted" : "streamError", "模型输出中断：" + msg);
        channel.emit(RunEventType.ERROR, msg);
        log.info("[agentx] 本轮失败收束: {} 原文={}", msg, raw.length() > 200 ? raw.substring(0, 200) : raw);
        return msg;
    }
}
