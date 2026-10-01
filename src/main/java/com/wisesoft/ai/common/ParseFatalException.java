package com.wisesoft.ai.common;

import lombok.Getter;

/**
 * 解析任务的终态错误：格式/内容类问题，重试多少次都不会成功，任务直接转 dead。
 * <p>
 * 与一般异常区分开是为了让解析队列能**精确分流**：
 * 超时/IO/5xx/内存溢出这类偶发错误走指数退避重试，魔数不符/空文档/文档过大这类确定性错误不重试。
 * 不分类的话，队列只能把所有失败都判成终态（用户反复点重解析也过不去）或都重试（白耗算力、刷屏）。
 *
 * @author yuanke
 */
@Getter
public class ParseFatalException extends RuntimeException {

    public ParseFatalException(String message) {
        super(message);
    }

    public ParseFatalException(String message, Throwable cause) {
        super(message, cause);
    }
}
