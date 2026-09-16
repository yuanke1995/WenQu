package com.wisesoft.ai.config;

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
public class AiAppProperties {

    private Chunk chunk = new Chunk();
    private Retrieval retrieval = new Retrieval();
    private Keyword keyword = new Keyword();
    private Session session = new Session();
    private Images images = new Images();
    private Vision vision = new Vision();
    private Ratelimit ratelimit = new Ratelimit();

    private QueryRewrite queryRewrite = new QueryRewrite();

    private Intent intent = new Intent();

    private DeepReasoning deepReasoning = new DeepReasoning();

    private Context context = new Context();

    /** 主回答 System Prompt 角色段（DB 可编辑覆盖，保存即生效；此处为兜底默认值） */
    private String systemPrompt = "你是\"问渠\"（WenQu），一个基于操作手册知识库回答系统使用问题的AI助手。"
            + "回答应准确、简洁，优先依据参考资料，不要编造不存在的内容。";

    /** 内部信任 token（平台网关代理调用时携带；必须通过环境变量配置，无默认值） */
    private String trustedToken;

    /**
     * 管理员用户白名单（逗号分隔 X-User-Id；"*" = 所有请求均为管理员，单机自用模式）。
     * 权限模型：普通用户仅开放问答链路（chat/会话/反馈/引用溯源/公开配置），
     * 文档管理、系统配置、评估、看板、索引运维等管理端点仅管理员可访问。
     */
    private String adminUsers = "";

    /** 管理员访问口令（请求头 X-Admin-Token 携带；与 admin-users 任一命中即视为管理员）。
     * 适合无网关直连/本地部署（配合前端 VITE_ADMIN_TOKEN）。留空则仅按 admin-users 判定。 */
    private String adminToken = "";

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
        /** 混合检索：标题命中额外奖励（0~1，加在融合分上） */
        private double titleBonus = 0.1;
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
        /** anonymous 历史兼容池是否对具名用户共享可见（存量升级兼容；false 时仅 anonymous 调用方可访问，收紧越权面） */
        private boolean anonymousShared = true;
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
        /** 图片描述模型（全模态，已实测返回标准 OpenAI 格式；求快可换 qwen3-omni-flash） */
        private String model = "";
        /** 视觉模型 base-url（与 chat 同网关） */
        private String baseUrl = "";
        /** 视觉模型 API Key */
        private String apiKey = "";
        /** 是否启用图片描述（关闭则只提取图片不调模型） */
        private boolean enabled = true;
        /** 单张图片描述超时(ms) */
        private int timeoutMillis = 30000;
        /** 图片描述并发度（本地 Ollama 需设置 OLLAMA_NUM_PARALLEL 才能并行推理） */
        private int concurrency = 4;
        /** 单张图片失败重试次数（Ollama 偶发 500/超时，重试可显著降低降级率） */
        private int retryCount = 1;
        /** Ollama keep_alive 保持模型常驻(分钟)，0=不发送（云端服务不支持此参数需设 0） */
        private int keepAliveMinutes = 30;
        /** Ollama 上下文窗口 num_ctx：1280px 识别图视觉 token 约 1600-2500，默认 4096 会截断；0=不设置 */
        private int numCtx = 16384;
        /**
         * 关闭思考模式（qwen3 系列默认思考，关闭后提速且输出稳定）。
         * 注意：max_tokens 在该思考模型下会导致空输出，本项目不发送 max_tokens
         */
        private boolean think = false;
        /** 描述 prompt */
        private String prompt = "请简要描述这张图片的内容，如果是界面截图请提取关键文字和界面元素，如果是流程图请说明流程要点，50字以内。";
    }

    @Data
    public static class QueryRewrite {
        /** 是否启用查询改写 */
        private boolean enabled = true;
        /** 改写超时时间(ms) */
        private int timeoutMillis = 5000;
        /** 改写 prompt（单轮对话） */
        private String prompt = "请将用户问题改写为一个更精准的检索关键词或短语，用于检索操作手册知识库。"
                + "要求：1) 只输出改写后的文本，不要解释；2) 保留核心动作和对象，去除疑问语气；"
                + "3) 如果是简单问题（如'有哪些功能'）可原样返回。";
        /** 多轮对话参与改写的最近轮数 */
        private int historyRounds = 2;
        /** 改写 prompt（多轮对话，其中 %s 会被替换为对话历史） */
        private String promptMultiTurn = "以下是一段对话历史。请根据上下文，将最后一条用户消息改写为一个独立、精准的检索关键词或短语，用于检索操作手册知识库。"
                + "要求：1) 只输出改写后的文本，不要解释；2) 如果最后一条消息是追问（如'那删除呢'），结合历史补全为完整问题；"
                + "3) 保留核心动作和对象。\n\n对话历史：\n%s";
    }

    /**
     * 意图分类：纯文本消息先经 LLM 判断「闲聊/知识库无关」（chat）还是「可能需要检索知识库」（doc），
     * chat 跳过改写/思考/检索/引用直达对话，doc 走完整 RAG 链路。
     * 失败/超时/输出无法识别一律降级为 doc（fail-safe，最坏等于现状）。
     */
    @Data
    public static class Intent {
        /** 是否启用意图分类（默认关闭，AI_INTENT_CLASSIFY_ENABLED 可开） */
        private boolean enabled = false;
        /** 分类超时时间(ms)：分类只输出一个单词，超时不宜过大，超时按 doc 处理 */
        private int timeoutMillis = 3000;
        /** 分类用模型（留空回落 chat.model，可配更小更快的模型） */
        private String model = "";
        /** 分类 prompt（要求只输出 chat 或 doc 单词；拿不准输出 doc） */
        private String prompt = "你是意图分类器，判断用户消息是否需要检索产品操作手册知识库。只输出一个单词：chat 或 doc，不要任何解释。\n"
                + "输出 chat：问候/寒暄/致谢/告别（你好、hi、在吗、谢谢、再见）、闲聊，"
                + "以及与产品文档知识库无关的一切话题（天气、新闻、常识问答、其他产品等）。\n"
                + "输出 doc：凡是可能需要查操作手册/功能说明/配置方法/操作步骤/报错处理的问题，"
                + "以及提及本产品、系统、表单、组件、功能等内容的消息。\n"
                + "拿不准时一律输出 doc。";
        /** chat 分支（闲聊/知识库无关）回答规则：拼在角色段之后，DB 可编辑保存即生效 */
        private String chatPrompt = "当前用户消息不涉及产品文档内容（问候、寒暄或与知识库无关的话题）。"
                + "请自然友好地用简短篇幅回应，不要引用任何资料。"
                + "若用户询问与产品无关的问题，可简要回应，并说明你是\"问渠\"、擅长回答产品使用/配置/操作类问题，引导用户提问相关内容。"
                + "不要编造文档内容，不要输出 [N] 或 [图片N] 标记。";
    }

    /**
     * 深度思考（生产级）：
     * 阶段1 思考流式输出思维链（enable_thinking 透传 / 提示词引导双模式）
     * → 阶段2 从思考文本提取 <search> 检索计划（精化 query + 子问题）
     * → 阶段3 多路并行检索合并 → 复用现有上下文构建与回答流
     */
    @Data
    public static class DeepReasoning {
        /** 总开关（前端开关关闭时不进本流程） */
        private boolean enabled = true;
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
        /** 自动路由：未手动开启深度思考时，按问题特征（长度/多条件/对比）自动判断是否需要思考 */
        private boolean autoRoute = false;
    }

    /**
     * 上下文与长度控制（价值驱动填充）：
     * 预算 = min(模型窗口 × 安全系数 − 预留输出, 成本软上限)；块按相关度降序累积填充，历史按预算裁剪
     */
    @Data
    public static class Context {
        /** 模型上下文窗口映射（格式 "模型名子串=token,模型名子串=token"，按当前 chat.model 子串匹配；未匹配用默认值） */
        private String modelWindows = "qwen-plus=131072,qwen3=131072,qwen-max=32768,deepseek=65536,default=32768";
        /** 未匹配到模型时的默认窗口（token） */
        private int defaultWindowTokens = 32768;
        /** 窗口安全系数（0~1，预留余量防超窗） */
        private double safetyFactor = 0.7;
        /** 成本软上限（token，0=不限制）：即使模型窗口很大，单次请求输入也不超过此值，防止账单失控 */
        private int costCapTokens = 8000;
        /** 输出限制 maxTokens（同时从窗口预算中预留） */
        private int maxOutputTokens = 2000;
        /** 注入对话历史的 token 上限（超出按"保留用户问题优先"裁剪） */
        private int historyMaxTokens = 1200;
        /** 单条历史消息截断字符数 */
        private int historyPerMsgChars = 200;
        /** 知识块命中片段窗口（字符，命中关键词前后各取 N 字；0=整块塞入） */
        private int snippetWindowChars = 150;
        /** 上下文填充的最大块数（兜底上限，防候选极多时预算失控） */
        private int maxContextHits = 8;
    }
}