package com.wenqu.ai.config;

/**
 * 配置键常量：读取点不再散落魔法字符串。
 *
 * <p>当前先收口历史上直接带默认值的隐藏键（不进设置页）；新增读取点应优先加到这里。
 * 键值仍以 {@code config-schema.json} / {@code ConfigService.defaults()} 为登记源，
 * {@code ConfigContractTest} 会校验每个常量对应的键确实已登记。
 */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    public static final String CHAT_MAX_ATTACHMENTS_PER_MESSAGE = "chat.maxAttachmentsPerMessage";
    public static final String CHAT_MAX_ATTACHMENT_MB = "chat.maxAttachmentMb";
    public static final String CHAT_MAX_MENTIONS_PER_MESSAGE = "chat.maxMentionsPerMessage";
    public static final String CHAT_MAX_HISTORY_REFS_PER_MESSAGE = "chat.maxHistoryRefsPerMessage";
    public static final String CHAT_ATTACHMENT_MAX_CHARS = "chat.attachmentMaxChars";
    public static final String CHAT_ATTACHMENT_TOTAL_CHARS = "chat.attachmentTotalChars";

    public static final String CHUNK_MAX_IMAGES = "chunk.maxImages";
    public static final String CHUNK_MAX_CHUNKS = "chunk.maxChunks";

    public static final String PARSE_CHILD_SIZE = "parse.childSize";
    public static final String PARSE_OCR_DPI = "parse.ocrDpi";
    public static final String PARSE_OCR_MIN_TEXT = "parse.ocrMinText";

    public static final String GRAPHRAG_CHUNK_VIEW_MAX_CHARS = "graphrag.chunkViewMaxChars";

    public static final String KEYWORD_TIMEOUT_MILLIS = "keyword.timeoutMillis";
    public static final String KEYWORD_FAIL_COOLDOWN_MS = "keyword.failCooldownMs";

    public static final String RERANK_TIMEOUT_MILLIS = "rerank.timeoutMillis";
    public static final String RERANK_FAIL_COOLDOWN_MS = "rerank.failCooldownMs";

    public static final String RETRIEVAL_KEYWORD_MAX_TERMS = "retrieval.keywordMaxTerms";
    public static final String RETRIEVAL_KEYWORD_MAX_TOTAL = "retrieval.keywordMaxTotal";
    public static final String RETRIEVAL_KEYWORD_TIMEOUT_MS = "retrieval.keywordTimeoutMs";

    public static final String TENANT_AUTO_APPROVE_ENABLED = "tenant.autoApprove.enabled";
}
