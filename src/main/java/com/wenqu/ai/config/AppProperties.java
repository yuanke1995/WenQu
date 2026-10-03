package com.wenqu.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 应用自定义配置
 *
 * @author yuanke
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-app")
public class AppProperties {

    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Keyword keyword = new Keyword();
    private Session session = new Session();
    private Images images = new Images();
    private Vision vision = new Vision();
    private Ratelimit ratelimit = new Ratelimit();

    private DeepReasoning deepReasoning = new DeepReasoning();

    private Context context = new Context();

    private Auth auth = new Auth();

    /** 主回答 System Prompt 角色段（DB 可编辑覆盖，保存即生效；此处为兜底默认值） */
    private String systemPrompt = "你是\"问渠\"（WenQu），智能体工作台的 AI 助手。"
            + "你可以依据检索到的知识库资料回答问题，也能直接处理日常问答与创作；"
            + "本轮若提供了工具（如计算器、日期、联网搜索、沙盒命令执行、文件读写、交付文件产物等），"
            + "应优先用工具获取真实结果，不要心算或虚构结果。"
            + "回答应准确、简洁：有参考资料时依据资料作答，不编造不存在的内容；"
            + "资料未覆盖时如实说明没有找到依据，并给出可行的下一步建议（换个问法、补充资料，"
            + "或转由对应的专业助手回答），不要臆测，也不要仅因话题超出某个文档范围就拒答。";

    @Data
    public static class Chunk {
        /** 分块最大字符数 */
        private int maxSize = 800;
        /** 分块重叠字符数 */
        private int overlap = 100;
        /** 单文档解析的最大知识块数（0=不限制；防止超大文档 embedding 调用数万次） */
        private int maxChunks = 3000;
        /** 单文档最多提取图片数（0=不限制；防止图片爆炸导致视觉描述数小时） */
        private int maxImages = 100;
        /** 结构感知切分：标题/段落边界优先断块 + 章节标题路径注入（docx 生效，需重解析） */
        private boolean structural = true;
        /** 结构切分边界阈值比例（达到 maxSize×该比例时优先在段落边界断块） */
        private double structuralRatio = 0.8;
        /** 章节标题识别上限层级（1~6）：调大后更深层的小节/条目标题独立成块并进章节路径（改后需重解析生效） */
        private int headingDepth = 4;
    }

    /**
     * 关键词召回引擎：mysql（LIKE，零依赖但全表扫描）/ meilisearch（外部索引，中文分词 + BM25 相关度）。
     * 引擎/地址/超时可经设置页动态调整；apiKey 只从 env/yml 读取，不落 c_ai_config（避免密钥明文入库）。
     */
    @Data
    public static class Keyword {
        /** 召回引擎：mysql | meilisearch（默认 mysql，切换后需先 reindex 建索引） */
        private String engine = "mysql";
        /** Meilisearch 服务地址 */
        private String baseUrl = "http://localhost:7700";
        /** Meilisearch master key（仅 env/yml 配置，不入 DB） */
        private String apiKey = "";
        /** 索引名 */
        private String index = "ai-doc-chunks";
        /** 单次请求超时(ms)：关键词路是辅助召回，超时即降级，不宜过大 */
        private int timeoutMillis = 1000;
    }

    @Data
    public static class Retrieval {
        /** 混合检索：向量相似度权重（0~1） */
        private double vectorWeight = 0.6;
        /** 混合检索：关键词命中率权重（0~1） */
        private double keywordWeight = 0.4;
        /** 重排（独立 reranker 服务，OpenAI 兼容 /v1/rerank；Ollama 无 rerank 能力，勿配 Ollama 地址） */
        private Rerank rerank = new Rerank();
    }

    @Data
    public static class Rerank {
        /** 是否启用重排（需先启动本地 reranker 服务：scripts/win|mac/start_rerank_server.*） */
        private boolean enabled = false;
        /** reranker 服务 base-url（OpenAI 兼容，POST /v1/rerank） */
        private String baseUrl = "http://localhost:7997";
        /** rerank 模型名 */
        private String model = "BAAI/bge-reranker-v2-m3";
        /** 单次重排超时(ms) */
        private int timeoutMillis = 5000;
    }

    @Data
    public static class Session {
        /** 保留最近对话轮数 */
        private int maxHistory = 10;
        /** 会话过期时间（分钟） */
        private int expireMinutes = 30;
    }

    @Data
    public static class Ratelimit {
        /** 接口限流总开关（Redis 固定窗口，按用户/IP；Redis 不可用自动放行） */
        private boolean enabled = true;
        /** 问答限频：次/分钟/用户（0=不限） */
        private int chatPerMinute = 10;
        /** 上传限频：次/分钟/用户（0=不限） */
        private int uploadPerMinute = 10;
    }

    @Data
    public static class Images {
        /** 图片存储根目录，默认 ./data（相对应用工作目录） */
        private String dir = "data";
        /** 图片最长边像素，超过则等比缩小（0=不压缩） */
        private int maxWidth = 1280;
        /** JPEG 压缩质量（0~1） */
        private float quality = 0.9f;
        /** 图片 URL 访问前缀（含 context-path /ai） */
        private String urlPrefix = "/ai/images";
        /** 图片访问鉴权开关（HMAC 签名 URL，生产开启） */
        private boolean authEnabled = false;
        /** 签名 URL 有效期（秒） */
        private long authExpireSeconds = 3600;
        /** 回答中 [图片N] 标记与图片描述的相关性校验（LLM 偶发错配兜底） */
        private ImageFilter imageFilter = new ImageFilter();
    }

    @Data
    public static class ImageFilter {
        /** 是否启用图片相关性校验 */
        private boolean enabled = true;
        /** 关键词命中数阈值（≥1 即相关，保守防误杀） */
        private int minHits = 1;
        /** 校验取标记前文的最大字符数 */
        private int preContextChars = 100;
    }

    @Data
    public static class Vision {
        /** 是否启用图片描述（关闭则只提取图片不调模型；设置页 vision.enabled 可改，保存即生效） */
        private boolean enabled = true;
        /** 单张图片描述超时(ms)（RestClient 构建期读取，需重启生效） */
        private int timeoutMillis = 180000;
        /** 图片描述并发度（设置页 vision.concurrency 可改，保存即生效） */
        private int concurrency = 2;
        /** 单张图片失败重试次数（syncProperties 回写，设置页保存即生效） */
        private int retryCount = 1;
        /** 描述 prompt（defaults() 种子值；运行时读 vision.prompt，设置页保存即生效） */
        private String prompt = "请简要描述这张图片的内容，如果是界面截图请提取关键文字和界面元素，如果是流程图请说明流程要点，50字以内。";
    }

    /**
     * 深度思考（生产级）：
     * 阶段1 思考流式输出思维链（enable_thinking 透传 / 提示词引导双模式）
     * → 阶段2 从思考文本提取 <search> 检索计划（精化 query + 子问题）
     * → 阶段3 多路并行检索合并 → 复用现有上下文构建与回答流
     */
    @Data
    public static class DeepReasoning {
        /** 思考模式：model=extraBody 透传 enable_thinking 从 reasoning_content 提取；prompt=提示词引导输出到 content */
        private String thinkingMode = "model";
        /** 是否透传 enable_thinking=true（thinkingMode=model 时生效） */
        private boolean enableThinking = true;
        /** 思考引导 prompt（要求先分析不答答案，末尾输出 <search> 检索计划） */
        private String prompt = "你是一个严谨的分析助手。请只输出对用户问题的深度思考过程，不要直接给出最终答案。"
                + "要求：1) 先拆解问题关键点，分析可能的知识来源与回答方向；2) 思考要条理清晰、覆盖全面；"
                + "3) 思考结束后，在最后单独一行输出检索计划，严格按格式：\n"
                + "<search>精化后的检索query|子问题1|子问题2</search>\n"
                + "第一个是用于检索知识库的精化查询短语，| 分隔的子问题是需要分别检索的子问题（最多3个）。";
        /** 检索计划标签名（<search>/</search>） */
        private String searchTag = "search";
        /** 最大子问题数（不含精化 query） */
        private int maxSubQueries = 3;
        /** 多路并行检索开关 */
        private boolean multiRetrieval = true;
        /** 思考阶段超时(ms)，超时用已有内容降级 */
        private int timeoutMillis = 30000;
        /** 思考输出上限 token（0=不设，规避 qwen 思考模式 max_tokens 空输出） */
        private int maxThinkingTokens = 0;
        /** 思考流长度上限（字符，0=不限制）：超限中断思考流并保留已收集内容，避免刷爆上下文/token */
        private int maxThinkingChars = 3000;
        /** 思考链注入最终回答（把推理过程截断后作为参考注入生成 prompt，让"想过的"作用于"答"） */
        private boolean injectThinking = true;
        /** 思考链注入回答的长度上限（字符） */
        private int injectThinkingMaxChars = 800;
        /** 思考关键词增强检索（从思考全文提取词元补充到检索 query，提升召回） */
        private boolean injectKeywords = true;
        /** 思考关键词增强的词元数上限 */
        private int injectKeywordsMax = 5;
    }

    /**
     * 上下文与长度控制（价值驱动填充）：
     * 预算 = 模型窗口 × 安全系数 − 最大输出；块按相关度降序累积填充，历史按预算裁剪。
     * 窗口/最大输出按模型声明（c_ai_model.context_window / max_output，模型管理页维护）；
     * 窗口没有全局兜底——对话类模型登记时强制声明，未声明的存量行运行时 fail-loud 降级提醒。
     * 安全系数为平台固定策略（ModelRegistryService.CONTEXT_SAFETY_FACTOR），不再作为配置项。
     */
    @Data
    public static class Context {
        /** 历史压缩开关：开启后全部历史始终在场（近期原样 + 更早滚动摘要），不再按轮数/字符截断 */
        private boolean historyCompress = true;
        /** 压缩触发比例：摘要+原样历史估算超过 检索预算×该比例 时滚动压缩（0.1~0.9） */
        private double compressRatio = 0.5;
        /** 知识块命中片段窗口（字符，命中关键词前后各取 N 字；0=整块塞入） */
        private int snippetWindowChars = 150;
        /** 上下文填充的最大块数（兜底上限，防候选极多时预算失控） */
        private int maxContextHits = 8;
    }

    /** 本地登录鉴权（JWT + PBKDF2，纯 JDK 实现，不引入第三方依赖） */
    @Data
    public static class Auth {
        /** JWT 签名密钥（env AI_JWT_SECRET）。留空则开发期自动生成随机值并告警（重启后原令牌失效） */
        private String jwtSecret = "";
        /** 令牌有效期（小时，默认 7 天） */
        private int tokenTtlHours = 168;
        /**
         * 是否要求登录：true（默认）＝除公开端点外必须持有效令牌；
         * false＝放行未登录请求（身份为 anonymous，仅能访问公开端点与历史兼容池）。
         */
        private boolean requireLogin = true;
        /** 令牌签发者 */
        private String issuer = "wenqu";
        /** 令牌受众 */
        private String audience = "wenqu-api";
        /** 登录失败锁定阈值（连续失败次数，0=不锁定） */
        private int maxLoginFailures = 5;
        /** 锁定时长（分钟） */
        private int lockMinutes = 15;
        /** 初始化管理员时要求的最小密码长度 */
        private int minPasswordLength = 6;
    }
}