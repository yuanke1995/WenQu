// 配置字段 schema —— 表单渲染 / 回填 / 提交的唯一数据源
// 自动生成：由 scripts/gen_config_schema.js 从 Settings.vue + ConfigService.java 提取，请勿手工编辑结构
// 字段含义：panel=所属折叠面板 section=面板内分节 group/key=后端分组与键 path=form 内路径 tier=1必需 2调优 3工程

export const TIPS = {
  "temperature": "控制回答的随机性（0~2）：越低回答越稳定、严谨、贴近资料原文（知识库问答建议 0.2~0.4）；越高越有创造性，但也更容易偏离事实或编造内容。注意部分厂商范围更窄（如智谱 0~1），超出会报错。",
  "systemPrompt": "定义 AI 的角色与回答风格，会注入每次问答的系统提示。改动立即影响所有回答的语气与行为；引用标注、配图、追问的硬性规则由系统固定，不可在此修改。",
  "visionPrompt": "图片描述的要求（如提取关键文字/界面元素、说明流程要点）。改动影响图片描述的内容倾向，进而影响检索与配图准确性。",
  "visionConcurrency": "文档解析时图片描述的最大并发数。调高解析更快，但占用更多显存/推理资源（本地 Ollama 需设 OLLAMA_NUM_PARALLEL 才能并行）；调低更稳。",
  "maxChunks": "单文档解析的最大知识块数（0=不限制）。超大文档超出部分截断不入库，防止 embedding 调用数万次导致解析失控。",
  "maxImages": "单文档最多提取并描述的图片数（0=不限制）。图片爆炸的文档（上百张图）解析会非常慢，设上限可避免。",
  "overlap": "分块重叠字符数：把上一块尾部 N 字拼入当前块的向量化文本，保留硬切/分块截断处的语义衔接。仅影响向量，不写入知识块正文、不影响增量复用（邻块变动不会连锁重嵌）。0=关闭。需重解析生效。",
  "uploadMaxSize": "文档上传大小上限（MB）。保存即生效（新上传按新限制校验）；物理上限 1GB 由容器兜底，不可超过。",
  "vectorWeight": "向量语义相似度在最终排序分中的占比。调高更侧重\"意思相近\"的匹配（适合口语化、换说法的提问）；过高可能引入字面无关但语义相近的块。",
  "keywordWeight": "关键词精确命中在排序分中的占比。调高更侧重\"字面命中\"（适合知识库资料中的专有名词、按钮名）；过高会漏掉语义相关但字面不同的内容。",
  "rerankEnabled": "重排总开关（对混合检索候选再做一次精排）。重排模型本身归各知识库的检索设置（未配置的库不做精排）；本地 reranker 服务见 scripts/win 或 scripts/mac 的 start_rerank_server。",
  "modelWindows": "声明各模型的上下文窗口大小（token），格式\"模型名=token\"逗号分隔，按当前模型名的包含关系匹配。设置过大有超窗报错风险，过小会浪费模型能力。",
  "defaultWindow": "当「模型窗口映射」未匹配到当前模型时使用的窗口大小兜底值。",
  "safetyFactor": "上下文预算 = 窗口 × 安全系数 − 输出限制。系数越高单次可塞入更多知识块和历史，但越接近模型窗口上限；建议 0.6~0.8。",
  "costCap": "单次请求输入 token 的硬上限（0=不限制）。用于控制成本：即使模型窗口很大，也最多塞这么多内容；设小会减少知识块数量、回答可能不完整。",
  "maxOutput": "回答生成的最大 token 数。设太短回答会被截断；设太长增加成本与等待时间。",
  "historyMax": "注入对话历史的 token 总上限。越大多轮上下文越完整（追问更准），但会挤压知识块的空间，且历史可能引入过时信息。",
  "historyPerMsg": "每条历史消息保留的最大字符数，超出部分截断。控制历史占用的空间，保留最近轮次。",
  "snippetWindow": "每个知识块只取\"命中关键词 ± 该字符数\"的片段送入上下文（0=整块塞入）。调大上下文信息更全但 token 消耗增大；调小更省 token 但可能丢失上下文导致理解偏差。",
  "maxContextHits": "上下文最多塞入的知识块数量上限。调大可能引入相关度低的块稀释注意力；调小可能漏掉有价值的参考资料。",
  "dedupEnabled": "信息增益去冗余：跳过与已选块语义重复的候选块（同一操作被切成多个知识块时，只保留最高相关的一块进上下文）。防止重复内容浪费预算、以及多块表述不一致导致模型自相矛盾。关闭后所有命中块按原顺序填充。",
  "dedupThreshold": "候选块与任一已选块的词元重叠比例（Jaccard）达到此值即判定为冗余跳过。越高越宽松（剔除越少）；越低剪得越狠但可能误伤信息有增量的相关块。",
  "dedupPathThreshold": "候选块与已选块处于同一章节路径（titlePath 互为前缀/相等）时使用的重叠阈值。同章节的相邻切片几乎总讲同一内容，此阈值通常设得比普通阈值更低（更容易剪）。",
  "drEnabled": "深度思考总开关。关闭后即使前端开启\"深度思考\"开关也走普通回答流程（前端开关独立控制）。",
  "drMode": "思考模式：model=通过 extraBody 透传 enable_thinking=true，从模型 reasoning_content 提取思维链（qwen3 系原生支持）；prompt=用提示词引导模型把思考输出到正文 content（兼容不支持思考参数的模型/网关）。",
  "drEnableThinking": "thinkingMode=model 时是否透传 enable_thinking=true。若网关静默忽略或返回异常，可关闭此项并切到 prompt 模式。",
  "drPrompt": "思考阶段的引导提示词：要求模型先深度分析不直接作答，并在末尾输出 <search> 检索计划（精化 query | 子问题1 | 子问题2）。改坏可能导致检索计划提取失败（自动降级普通检索）。",
  "drSearchTag": "检索计划包裹标签名，默认 search（即 <search>...</search>）。需与提示词中的标签一致。",
  "drMaxSub": "从检索计划中最多取多少个子问题参与多路并行检索（不含精化 query）。越大召回越广但更慢、成本更高。",
  "drMultiRetrieval": "是否多路并行检索（精化 query + 子问题分别检索后按最高分合并）。关闭则只用精化 query 单路检索（更快但召回面窄）。",
  "drTimeout": "思考阶段最大等待时间(ms)。超时用已收集的思考内容降级为普通检索回答，不阻塞。",
  "drMaxTokens": "思考输出的 token 上限（0=不设）。qwen3 思考模式下设 max_tokens 会导致空输出，默认 0；仅当思考过长需裁剪时设置。",
  "drMaxThinkingChars": "思考流长度上限（字符，0=不限制）。超限自动中断思考流但保留已想内容继续提取检索计划，避免超长思维链刷爆上下文与 token。",
  "drInjectThinking": "把深度思考的推理过程（截断到注入长度上限）作为参考注入最终回答的生成 prompt，让\"想过的拆解与判断\"直接作用于回答；明确以参考资料为准，思考仅辅助。",
  "drInjectThinkingChars": "思考链注入回答的最大字符数。越长信息越全但占用生成预算；过短可能丢失关键推理。",
  "drInjectKeywords": "从思考全文提取关键词元补充到检索 query（多路检索多一条增强路；深度思考失败时也用它增强降级检索，思考不白费）。提升\"思考中提到的实体/限定词\"的召回。",
  "drInjectKeywordsMax": "思考关键词增强最多取多少个词元。越多召回越宽但可能引入噪声。",
  "drAutoRoute": "未手动开启深度思考时，按问题特征自动判断：达到「字数上限」的长问，或命中「触发词」的问题自动启用深度思考；低于「字数下限」的问题一律不思考。保守启发式，避免常见问题全量思考导致成本翻倍。",
  "historyRounds": "问答时注入对话历史的轮数（多轮记忆）。调大更连贯但占上下文预算；0=不注入历史。",
  "remainTokenFloor": "上下文预算保留下限（token）：扣除系统提示与问题后至少保留的量，低于则不再填充知识块。",
  "truncateFallbackChars": "知识块超出预算时的截断兜底字符数（至少保留的字数）。",
  "pipelineThreads": "问答流水线的并发线程数：图片识别、查询改写、检索、深度思考等\"重活\"在独立线程池执行，不占 Tomcat 请求线程（多用户并发问答时避免请求线程被打满）。调高可支撑更多并发用户，但占用更多 CPU/内存；队列满时新请求会快速返回\"系统繁忙\"。保存即生效。",
  "streamRetryCount": "主 LLM 流式生成在\"未输出任何内容\"时中断的自动重试次数（0=关闭）。已输出内容后中断不重试（避免重复内容）；重试仍失败会在回答下方给出警示。",
  "sseTimeoutMs": "问答 SSE 连接超时（毫秒）：超过后回答被截断，前端提示\"回答超时已截断\"。深度思考+长回答场景可调大，默认 300000（5 分钟）。",
  "retrievalDebugEnabled": "检索调试入口开关（内部排障用）：开启后回答操作菜单显示「检索调试」，可分步查看关键词/向量/重排召回结果；面向用户的部署建议保持关闭。",
  "showDebugDegradations": "回答下方是否显示降级提示（无命中/查询改写失败/图片剔除/未标注引用/缓存命中等）。默认关：回答区不显示任何降级提示（排障信息仍写 [FAIL-LOUD] 日志）；调试排障时开启即可看到全部降级原因。",
  "citationCheck": "引用语义一致性自检：回答生成后，把每条 [N] 引用的前文句子与其来源片段交给模型判断是否被直接支撑，剔除\"编号存在但内容与该块无关\"的引用并重编编号。提升引用可信度，代价是每轮回答多一次校验调用（约数秒延迟）。",
  "judgeEnabled": "自动体检的 LLM 评判（调试度量）：体检时对每个 case 判断\"当前检索的 top 命中资料是否足以直接回答该问题\"，汇总为 judgeScore 写入体检报告，用于评估检索结果的实际可用性。每 case 一次模型调用，评估集大时体检耗时明显增加。",
  "visionEnabled": "视觉模型总开关。关闭后：文档图片/用户图片都不生成描述——图片仅展示、内容不进检索与回答引用（RAG 对图片语义失效），一般不建议关闭。",
  "parseConcurrency": "文档异步解析的并发数（同时解析几个文档）。调高多文档上传更快，但并发解析会同时占用 embedding/Ollama 资源；保存后对新任务生效。",
  "embedRetryCount": "向量化批次失败时的自动重试次数（0=不重试）。重试仍失败则整个文档解析失败并回退/提示（fail-loud，绝不静默丢块）。",
  "ocrMinText": "PDF 页文本少于该长度判定为扫描件/图片型，触发 OCR 识别（0=总是 OCR）。调高更激进触发 OCR，调低更依赖 PDF 自带文本。",
  "chunkStructural": "按文档结构切分：标题层级开新块、达到边界阈值在段落边界断块（避免从句子中间硬切）、章节标题路径注入块上下文。docx 生效；存量文档需重解析后才会按新规则重建知识块。",
  "chunkStructuralRatio": "结构切分边界阈值（maxSize×比例）：块达到该长度时优先在段落边界断块。调低块更小更贴近边界但块数更多；调高更接近原 800 字硬切。",
  "chunkHeadingDepth": "章节标题识别上限层级：解析时识别到第几级标题就按其断块，并把章节路径（如「第一章 > 第二节 > 条目名」）拼入知识块上下文。调大后更深层的条目级标题会独立成块、路径带上条目名，这类块更容易被「某某是什么、有哪些配置」类问题召回，但块数与解析成本随之上升；调小则只按大章节切块，同章节下的多个条目会合并在同一块。改动需重新解析文档生效。默认 4。",
  "userImageConcurrency": "用户在对话中上传图片的识别并发数（区别于文档解析的图片描述并发）。",
  "descCacheVersion": "图片描述缓存版本号：用于强制让旧描述失效。把版本号 +1 后重新解析文档，会忽略历史缓存、对全部图片重新调用视觉模型描述（换视觉模型或提示词后应 +1）。",
  "descCacheTtlDays": "图片描述缓存的有效期（天，0=永不过期）。同一张图片（按内容哈希）在该期限内复用已生成的描述，避免重复识别；到期后重新描述。",
  "vecThreshold": "向量相似度归一化基准：低于该分的向量命中归一化为 0 分，也是向量检索的相似度下限。调高更严格（召回更少但更相关）。",
  "vectorTopK": "向量检索最多取回的候选块数：用户说法与手册用词不一致时，调大能让语义相近但用词不同的块也进入候选（再经融合/重排决定最终入选）。调大更全但更慢。",
  "keywordLimit": "关键词检索最多返回的知识块数（SQL LIMIT）。调高召回更全但更慢、融合分计算更重。",
  "retrievalTimeout": "混合检索超时（ms）：关键词子检索与总检索的超时上限，超时降级返回已收集结果。",
  "rewriteTimeoutMs": "查询改写超时（ms）：LLM 改写问题（多轮追问补全上下文）的等待上限，超时则用原问题检索并提示。本地模型响应慢时调大可减少改写降级，默认 5000。",
  "keywordEngine": "关键词召回引擎。mysql=MySQL LIKE（零依赖，知识块量大时全表扫描慢）；meilisearch=外部索引（中文分词+相关度打分）。切换到 meilisearch 时自动校验服务可用性（不可用则保存失败）并自动全量重建索引，无需手动操作。",
  "keywordBaseUrl": "Meilisearch 服务地址（如 http://localhost:7700，docker-compose 部署为容器内地址）。",
  "keywordApiKey": "Meilisearch master key（需与服务端 MEILI_MASTER_KEY 一致）。留空时回退读取环境变量 AI_MEILI_KEY；服务端已设置 key 而此处为空/错误，切换引擎时会校验失败并阻止保存。",
  "keywordTimeout": "关键词引擎单次请求超时(ms)：关键词路是辅助召回，超时会自动降级回 MySQL LIKE，不建议设太大。",
  "rlEnabled": "接口限流总开关（Redis 固定窗口计数）。关闭后所有接口不限流；Redis 不可用时即使开启也会自动放行（限流是保护措施，不比业务先挂）。",
  "rlChat": "每个用户每分钟最多发起的问答次数（0=不限流）。未登录请求按 IP 维度共享额度。用于防止滥用与成本失控。",
  "rlUpload": "每个用户每分钟最多上传文档的次数（0=不限流）。批量上传按一次请求计。解析是重资源操作，限制上传频次可防止解析队列被打满。",
  "kwReconcileOnStartup": "关键词索引启动对账：启动时按 (id,contentHash) 与 MySQL 精确比对，自动补写/删除漂移文档。单实例部署建议开；多副本部署建议关，改为运维单点执行，避免多实例同时重建。",
  "kwReconcileIntervalMs": "关键词索引周期对账间隔（ms，≤0=暂停）。Meilisearch 与 MySQL 长期运行可能因异常写入产生漂移，周期对账可自动修复。默认 3600000（1 小时）。",
  "parseRecoverStuck": "文档解析启动自愈：把上次崩溃时残留的「解析中」状态文档复位为可重试。单实例部署建议开；多副本部署若多实例同时启动会重复复位，建议关。",
  "evalAutoIntervalMs": "自动体检的执行周期（ms，≤0=暂停）。按周期跑评估集并对比历史指标，指标跌幅超过「退化判定跌幅」即标记退化。默认 86400000（每天）。",
  "evalAutoThresholdPct": "退化判定阈值：某项指标相对上次体检的跌幅超过该百分比即判为退化并在报告中标出。调小更敏感（更早发现轻微退化，但噪声多）；调大更宽容。",
  "evalJudgeModel": "体检的 LLM 评判所用模型（留空则复用当前问答模型）。可指定更便宜的模型专做「资料是否足以回答」的判断，降低体检成本。",
  "chatImgCleanupInterval": "聊天上传图片的清理任务间隔（ms，≤0=暂停）。默认 86400000（每天）扫描一次超期图片并删除。",
  "chatImgRetention": "聊天上传图片的保留时长（ms）。超过该时长且无引用的聊天图片会被清理，防止磁盘无限增长；默认 604800000（7 天）。",
  "sessionCleanupInterval": "会话清理任务间隔（ms，≤0=暂停）。默认 86400000（每天）扫描并删除超期会话。",
  "sessionRetentionDays": "会话保留天数：最后一次活跃超过该天数的会话将被删除（含消息）。默认 30 天；调大可保留更久，代价是存储与查询变慢。",
  "keywordFailCooldown": "关键词引擎失败冷却（ms）：Meilisearch 调用失败（超时/鉴权错误/服务不可用）后，冷却期内不再探测与调用，关键词路直接走 MySQL LIKE 兜底，避免每个请求都撞一次失败。默认 60000。",
  "keywordMaxTerms": "关键词主词元数量上限：jieba 分词后取多少个主词元参与关键词召回。词元越多召回面越广但 SQL/索引查询更重；长问句被截断时可适当调大。默认 6。",
  "keywordMaxTotal": "关键词词元总数上限：主词元 + 由长词拆出的 2-gram/4-gram 子词元的总数。子词元用于「换说法/子串」场景的补充召回，过多会引入噪声。默认 12。",
  "keywordTimeoutMs": "MySQL 关键词兜底检索的超时（ms）。关键词路是辅助召回，超时即本次跳过关键词、仅用向量结果（不阻塞问答）。默认 800。",
  "chunkMaxSize": "单块最大字符数：决定分块粒度。调小检索更精准但块数/embedding 成本上升；调大上下文更完整但可能混入无关内容。改动需重新解析文档生效。默认 800。",
  "imagesMaxWidth": "图片压缩后的最长边像素（0=不压缩）。文档中提取的图片会等比缩放到该尺寸再入库与送视觉模型；调小显著省成本但界面截图细节可能看不清。默认 1280。",
  "imagesQuality": "JPEG 压缩质量（0~1）。越低体积越小、越省存储与带宽，但文字边缘易糊影响识别。默认 0.9。",
  "imagesAuthEnabled": "图片访问鉴权（HMAC 签名 URL）。开启后图片必须带有效签名才能访问，防止被直接盗链；生产环境建议开启。关闭则图片 URL 可直接访问。",
  "imagesAuthExpire": "签名 URL 有效期（秒）。超期后旧链接失效（页面刷新会按新签名重新加载）。过短会频繁失效，过长削弱防盗链效果。默认 3600。",
  "visionTimeout": "单张图片描述的读取超时（ms）。本地大模型出图慢可调大；注意该超时在客户端构建时读取，改动需重启后端生效。默认 30000。",
  "visionRetryCount": "单张图片描述失败后的自动重试次数。本地 Ollama 偶发超时/500，重试可显著降低\"图片无描述\"的降级率。0=不重试。",
  "visionThink": "视觉模型思考模式开关。qwen3 系默认开启思考（慢且输出不稳定）；关闭后可提速并让描述更稳定。默认关闭。",
  "visionKeepAlive": "Ollama 模型常驻时长（分钟）：避免每次识别都重新加载模型。0=不发送该参数（云端 OpenAI 兼容服务不支持该参数，须设 0）。默认 30。",
  "visionNumCtx": "Ollama num_ctx 上下文窗口。图片视觉 token 较多（1280px 约 1600~2500），Ollama 默认 4096 会截断导致描述不全；0=不设置。默认 16384。",
  "sessionMaxHistory": "会话保留的最近对话轮数上限。越大多轮上下文越完整，但占用 Redis 内存与注入预算。默认 10。",
  "sessionExpireMinutes": "会话缓存的过期时间（分钟）。超期后会话从缓存淘汰（数据库记录仍保留，按会话清理策略删除）。默认 30。",
  "imageFilterEnabled": "图片相关性校验开关。开启后按图片标记前文的关键词判断该图是否与问题相关，过滤掉无关配图；关闭则回答里只要命中图片就一并带出。",
  "imageFilterMinHits": "图片相关性校验的关键词命中数阈值：前文命中数 ≥ 该值才视为相关。调高更严格（可能误杀配图），调低更宽松。默认 1。",
  "imageFilterPreContextChars": "校验取图片标记之前多少字符作为判断前文。过短可能漏掉关键词，过长可能引入无关词。默认 100。",
  "drAutoRouteMinChars": "自动路由的问题字数下限：低于该字数的问题不触发深度思考（短问直接答）。默认 8。",
  "drAutoRouteLongChars": "自动路由的问题字数上限：达到该字数即触发深度思考。默认 25。",
  "drAutoRouteKeywords": "自动路由触发词（逗号分隔）：问句含任一触发词即触发深度思考，用于拦截多条件/对比/递进类中等长度问题。留空表示只按长度判断。",
  "maxImagesPerMessage": "单条消息最多携带的图片张数。超限直接拒绝，防止 base64 大载荷压垮解码与视觉处理。默认 9。",
  "maxImageMb": "单张图片的体积上限（MB，按原图计；base64 编码后约为 4/3 倍）。超限拒绝发送。默认 10。",
  "relatedCount": "回答末尾 <related> 相关追问的推荐条数，提示词里的示例会与该数量保持一致。默认 3。",
  "embedBatchSize": "向量化分批大小（每批嵌入的知识块条数）。需与 embedding 上游接口的单次请求上限匹配，调大可提速但可能被限流。默认 10。",
  "ocrDpi": "PDF 扫描页 OCR 渲染 DPI：越高小字越清晰、识别率越好，但内存与耗时随之增加。默认 200。",
  "rlWindowSeconds": "限流固定窗口的长度（秒）：窗口内按「次/分钟」配置的额度计数，窗口越长突发容纳越多。默认 60。",
  "docMetaTtlSeconds": "文档名本地缓存的有效期（秒）。多副本部署时，其它实例的改名/删除最多延迟一个 TTL 后自愈；单副本可调大，多副本调小可加快一致但增加回源查询。默认 600。",
  "toolEnabled": "工具调用（Function Calling）总开关：开启后把已注册的工具声明给大模型，由模型在回答过程中自主决定是否调用。默认关闭；各子工具需在下方单独开启才会实际暴露给模型。",
  "toolKnowledgeRetrieval": "知识库精确检索工具：开启后模型可在回答中自主发起检索（而非只使用系统预检索的资料），适合多跳追问、需要核实细节的场景。需总开关开启；默认关闭。",
  "toolKnowledgeRetrievalMaxHits": "精确检索工具单次返回的知识块上限（1~5）。调大单次信息更全但占用上下文预算；模型可能多次调用，注意累计开销。",
  "toolArtifactEnabled": "产物交付工具：开启后模型可按需生成 Markdown/CSV/JSON/HTML 等文件（如导出清单、对比表），以可下载卡片形式附在回答中并随会话持久化。需总开关开启；默认关闭。",
  "toolBuiltinEnabled": "内置高频工具：开启后模型可调用「算术表达式计算」「当前日期时间」「日期相差天数」三个内置工具。模型口算与「不知道今天几号」是两类常见硬伤——涉及金额合计、百分比、工期/有效期推算时交给工具算，比让模型自己算可靠得多。表达式求值由后端自实现解析（不引入脚本引擎，仅数字与 + - * / % ^ 括号，无代码执行能力）。需总开关开启；默认关闭。",
  // 技能总开关 / 技能目录 / 注入开关 / readSkill 工具开关已随「技能下沉为个人资产」移除：
  // 技能内容在「智能体 → 技能 Skills」里每人自己管理（谁装谁用、停用由本人控），这里只剩两项预算参数。
  "skillInjectMax": "技能清单注入系统提示的字符上限：每个人在「智能体 → 技能 Skills」里装的技能都会汇总进清单，超出部分只列到截断（不会挤占知识块与历史的预算）。",
  "skillMaxFile": "单个技能全文读取上限（字符）：技能内容过长时截断，防止一个技能吃掉整个上下文预算。",
  "agentEnabled": "并行检索总开关。开启后每轮问答先把问题拆成多个视角（全句语义 / 关键词精确 / 长问题子句），交给多个子代理并行检索、各自提炼要点，再汇总进上下文——多条件、跨章节的复杂问题召回更全。代价：首字延迟变长（多 2~4 路检索，开启提炼时再多 2~4 次短调用）。默认关闭；建议先小范围对比效果。若当前智能体已配置子智能体，则优先委派子智能体（各按自己的知识库范围与角色视角检索），此处作为未配置子智能体时的默认策略。",
  "agentSubAgents": "子代理数量：每个子代理负责一个检索视角。2 个覆盖大多数问题；3~4 适合多条件/多主题的长问题。数量越多召回越全但越慢。",
  "agentTopK": "每个子代理取回的命中块数上限（跨代理自动去重，重复块只占一个引用编号）。",
  "agentDigest": "是否用模型把每个子代理的命中提炼成 2~3 条要点再汇总：开启后进主链路的资料更精炼（不会把多路原始片段都塞进上下文），但要多花 2~4 次模型调用；关闭则只做并行检索合并（零额外成本）。",
  "agentAutoRoute": "主智能体挂了多个子智能体时，先由模型判断「这个问题该咨询谁」，只并行咨询选中的助手。好处：避免把无关角色（如问表单操作却去查法律）也跑一遍，省掉多余检索与要点提炼开销，编排卡片也不会被 0 命中的角色占满。代价：判定本身多一次模型调用（约 1~2 秒）。关闭则每轮全部并行。",
  "agentRouteTimeout": "挑选助手的最长等待时间。超时、调用失败或结果无法解析时，自动回退为「全部候选都咨询」——宁可多跑也不漏掉能力，不影响正常问答。",
  "agentAutoDispatch": "对话页选「自动派遣」时的总开关：每轮消息由当轮生效模型按各智能体的名称+描述挑选最合适的角色（失败回落默认智能体）。关闭后「自动派遣」等同使用默认智能体。",
  // mcpEnabled / mcpServers 已移除：MCP Server 由每个用户在「智能体 → MCP 外部工具」里自己登记
}

export const PANELS = [
  { key: "chat", title: "智能问答模型", sections: ["模型与连接（厂商预设自动填充地址与补全路径）","回答行为与内容","记忆与上下文","并发与超时","消息图片限制（防 base64 洪峰压垮解码/视觉处理）","调试开关（排障用，生产建议仅开引用自检）"] },
  { key: "vision", title: "图片描述（视觉；模型归各知识库解析设置）", sections: ["总开关","识别提示词与并发（文档图与用户传图分开控制）","调用参数（超时 / 重试 / Ollama 推理）","图片描述缓存（改版本号/有效期后需重解析生效）","图片相关性校验（按图片标记前文关键词过滤无关配图）"] },
  { key: "chunk", title: "文档解析默认模板（新建库预填；各库可在知识库设置覆盖）", sections: ["上传与单文档保护（超限截断入库）","图片处理与访问鉴权（需重解析/新上传生效）","分块与解析行为（需重新解析/新上传文档生效；知识库级参数仅对其后解析生效）"] },
  { key: "retrieval", title: "检索设置（混合检索权重 + 重排 + 关键词引擎）", sections: ["关键词引擎（类型与服务连接）","融合权重 · 阈值 · 改写回退","查询改写（把问句改写为检索关键词，提升召回）","意图分类（问候/闲聊/知识库无关话题跳过检索直接对话）","关联扩散与引用识别","重排服务（总开关；重排模型归各知识库检索设置）"] },
  { key: "context", title: "上下文与长度控制", sections: ["窗口与预算（决定单次请求上下文长度）","历史裁剪 · 命中片段与填充","信息增益去冗余","@ 引用（输入框手动指定参考资料）"] },
  { key: "deepReasoning", title: "深度思考设置", sections: ["思考模式与引导","检索计划 · 多路与超时","思考增强 · 护栏与路由"] },
  { key: "tool", title: "工具调用（Function Calling）", sections: ["总开关","子工具（需总开关开启）"] },
  { key: "apiKey", title: "API Key 管理（对外开放问答能力）", sections: [] },
  // 技能内容与 MCP Server 已迁到「智能体」页的个人 Tab；这里只剩技能的上下文预算参数
  { key: "skills", title: "技能（Skills）预算参数（技能内容归个人）", sections: ["上下文预算"] },
  // 改名说明：子智能体已迁到「智能体」页面按角色委派，这里保留的是"没配子智能体时"的默认并行策略
  { key: "agent", title: "并行检索（未配置子智能体时的默认策略）", sections: ["总开关与并行度"] },
  { key: "ratelimit", title: "接口限流（防滥用）", sections: [] },
  { key: "maintenance", title: "定时维护（索引对账 / 自动体检 / 清理）", sections: ["启动自愈与索引对账","自动体检（检索质量回归）","聊天图片清理","会话参数与清理","文档名缓存（多副本一致性）"] },
]

export const FIELDS = [
  { panel: "chat", section: 1, group: "chat", key: "temperature", path: "chat.temperature", label: "温度", type: "number", tips: "temperature", def: 0.3, min: 0, max: 2, step: 0.1, width: 200, tier: 1 },
  { panel: "chat", section: 1, group: "chat", key: "systemPrompt", path: "chat.systemPrompt", label: "System Prompt", type: "textarea", tips: "systemPrompt", def: "", rows: 4, ph: "AI 助手的角色与回答风格（引用/图片/追问规则由系统固定，不可修改）", tier: 1 },
  { panel: "chat", section: 1, group: "chat", key: "citationCheckEnabled", path: "chat.citationCheckEnabled", label: "引用一致性自检", type: "switch", tips: "citationCheck", def: true, note: "生成后校验每条 [N] 引用是否被引用内容支撑，剔除语义不符的引用并重编编号（增加一次校验调用延迟）", tier: 2 },
  { panel: "chat", section: 1, group: "retrieval", key: "relatedCount", path: "retrieval.relatedCount", label: "相关追问条数", type: "number", tips: "relatedCount", def: 3, min: 1, max: 8, width: 200, note: "回答末尾 <related> 推荐的用户可能追问数", tier: 2 },
  { panel: "chat", section: 2, group: "chat", key: "historyRounds", path: "chat.historyRounds", label: "多轮记忆轮数", type: "number", tips: "historyRounds", def: 5, min: 0, max: 20, width: 200, core: true, tier: 1 },
  { panel: "chat", section: 2, group: "chat", key: "remainTokenFloor", path: "chat.remainTokenFloor", label: "上下文保留下限", type: "number", tips: "remainTokenFloor", def: 800, min: 0, step: 100, width: 200, tier: 2 },
  { panel: "chat", section: 2, group: "chat", key: "truncateFallbackChars", path: "chat.truncateFallbackChars", label: "截断兜底字符", type: "number", tips: "truncateFallbackChars", def: 200, min: 0, step: 50, width: 200, tier: 3 },
  { panel: "chat", section: 3, group: "chat", key: "pipelineThreads", path: "chat.pipelineThreads", label: "问答流水线线程", type: "number", tips: "pipelineThreads", def: 8, min: 2, max: 64, width: 200, note: "并发问答重活线程，保存即生效", tier: 3 },
  { panel: "chat", section: 3, group: "chat", key: "streamRetryCount", path: "chat.streamRetryCount", label: "流式中断重试", type: "number", tips: "streamRetryCount", def: 1, min: 0, max: 5, width: 200, note: "未输出内容时自动重试次数，0=关闭", tier: 3 },
  { panel: "chat", section: 3, group: "chat", key: "sseTimeoutMs", path: "chat.sseTimeoutMs", label: "回答超时(ms)", type: "number", tips: "sseTimeoutMs", def: 300000, min: 60000, step: 30000, width: 200, note: "SSE 超时截断并提示，默认 300000", tier: 3 },
  { panel: "chat", section: 4, group: "chat", key: "maxImagesPerMessage", path: "chat.maxImagesPerMessage", label: "单条消息图片上限", type: "number", tips: "maxImagesPerMessage", def: 9, min: 1, max: 20, width: 200, tier: 3 },
  { panel: "chat", section: 4, group: "chat", key: "maxImageMb", path: "chat.maxImageMb", label: "单张图片上限(MB)", type: "number", tips: "maxImageMb", def: 10, min: 1, max: 50, width: 200, tier: 3 },
  { panel: "chat", section: 5, group: "chat", key: "retrievalDebugEnabled", path: "chat.retrievalDebugEnabled", label: "检索调试入口", type: "switch", tips: "retrievalDebugEnabled", def: false, debug: true, tier: 3 },
  { panel: "chat", section: 5, group: "chat", key: "showDebugDegradations", path: "chat.showDebugDegradations", label: "降级提示", type: "switch", tips: "showDebugDegradations", def: false, note: "默认关闭：回答下方不显示任何降级提示（无命中/改写失败/图片剔除/缓存命中等）；调试排障时开启可见全部原因", debug: true, tier: 3 },
  { panel: "vision", section: -1, group: "vision", key: "enabled", path: "vision.enabled", label: "启用图片描述", type: "switch", tips: "visionEnabled", def: true, tier: 1 },
  // vision.model / embedding.model / retrieval.rerank.model 已退役：模型归属到使用者（知识库绑定），见对应管理页
  { panel: "vision", section: 1, group: "vision", key: "prompt", path: "vision.prompt", label: "识别提示词", type: "textarea", tips: "visionPrompt", def: "", rows: 3, ph: "图片描述提示词（50字内描述界面/元素）", tier: 2 },
  { panel: "vision", section: 1, group: "vision", key: "concurrency", path: "vision.concurrency", label: "图片描述并发", type: "number", tips: "visionConcurrency", def: 4, min: 1, max: 16, width: 200, tier: 3 },
  { panel: "vision", section: 1, group: "vision", key: "userImageConcurrency", path: "vision.userImageConcurrency", label: "用户图片并发", type: "number", tips: "userImageConcurrency", def: 2, min: 1, max: 16, width: 200, tier: 3 },
  { panel: "vision", section: 2, group: "vision", key: "timeoutMillis", path: "vision.timeoutMillis", label: "描述超时(ms)", type: "number", tips: "visionTimeout", def: 30000, min: 1000, step: 5000, width: 200, note: "单张图片描述的读取超时（客户端启动时构建，改动需重启生效）", tier: 3 },
  { panel: "vision", section: 2, group: "vision", key: "retryCount", path: "vision.retryCount", label: "失败重试次数", type: "number", tips: "visionRetryCount", def: 1, min: 0, max: 5, width: 200, note: "本地模型偶发超时/500，重试可显著降低降级率", tier: 3 },
  { panel: "vision", section: 2, group: "vision", key: "think", path: "vision.think", label: "开启思考模式", type: "switch", tips: "visionThink", def: false, note: "qwen3 系视觉模型默认思考；关闭可提速且输出更稳定", tier: 3 },
  { panel: "vision", section: 2, group: "vision", key: "keepAliveMinutes", path: "vision.keepAliveMinutes", label: "模型常驻(分钟)", type: "number", tips: "visionKeepAlive", def: 30, min: 0, step: 5, width: 200, note: "仅 Ollama；0=不发送该参数（云端服务须设 0）", tier: 3 },
  { panel: "vision", section: 2, group: "vision", key: "numCtx", path: "vision.numCtx", label: "num_ctx", type: "number", tips: "visionNumCtx", def: 16384, min: 0, step: 1024, width: 200, note: "仅 Ollama；0=不设置（默认 4096 会截断大图视觉 token）", tier: 3 },
  { panel: "vision", section: 3, group: "vision", key: "descCacheVersion", path: "vision.descCacheVersion", label: "描述缓存版本", type: "text", tips: "descCacheVersion", def: "1", width: 200, ph: "如 1", note: "版本号 +1 → 忽略旧描述缓存，重解析时全量重新描述", tier: 3 },
  { panel: "vision", section: 3, group: "vision", key: "descCacheTtlDays", path: "vision.descCacheTtlDays", label: "描述缓存有效期", type: "number", tips: "descCacheTtlDays", def: 180, min: 0, step: 30, width: 200, note: "天，0=不过期", tier: 3 },
  { panel: "chunk", section: 0, group: "upload", key: "maxFileSizeMB", path: "upload.maxFileSizeMB", label: "上传大小上限", type: "number", tips: "uploadMaxSize", def: 200, min: 1, max: 1024, step: 50, width: 200, note: "MB，保存即生效", submitKey: "maxFileSize", factor: 1048576, tier: 2 },
  { panel: "chunk", section: 0, group: "chunk", key: "maxChunks", path: "chunk.maxChunks", label: "最大知识块数", type: "number", tips: "maxChunks", def: 3000, min: 0, step: 500, width: 200, note: "0=不限制", tier: 2 },
  { panel: "chunk", section: 0, group: "chunk", key: "maxImages", path: "chunk.maxImages", label: "最多提取图片", type: "number", tips: "maxImages", def: 100, min: 0, step: 20, width: 200, note: "0=不限制", tier: 2 },
  { panel: "chunk", section: 1, group: "images", key: "maxWidth", path: "images.maxWidth", label: "压缩最长边(px)", type: "number", tips: "imagesMaxWidth", def: 1280, min: 0, step: 160, width: 200, note: "0=不压缩；调小省成本但可能看不清界面细节", tier: 3 },
  { panel: "chunk", section: 1, group: "images", key: "quality", path: "images.quality", label: "JPEG 质量(0~1)", type: "number", tips: "imagesQuality", def: 0.9, min: 0.1, max: 1, step: 0.05, width: 200, tier: 3 },
  { panel: "chunk", section: 1, group: "images", key: "authEnabled", path: "images.authEnabled", label: "图片访问鉴权", type: "switch", tips: "imagesAuthEnabled", def: false, note: "开启后图片 URL 需 HMAC 签名，防止被直接盗链（生产建议开）", core: true, tier: 3 },
  { panel: "chunk", section: 1, group: "images", key: "authExpireSeconds", path: "images.authExpireSeconds", label: "签名有效期(秒)", type: "number", tips: "imagesAuthExpire", def: 3600, min: 60, step: 600, width: 200, note: "超期后旧链接失效，页面刷新会自动重新签名", vif: "images.authEnabled", tier: 3 },
  { panel: "chunk", section: 2, group: "chunk", key: "maxSize", path: "chunk.maxSize", label: "分块最大字符数", type: "number", tips: "chunkMaxSize", def: 800, min: 200, step: 100, width: 200, note: "单块上限，决定检索粒度；改后需重解析生效", core: true, tier: 1 },
  { panel: "chunk", section: 2, group: "chunk", key: "overlap", path: "chunk.overlap", label: "分块重叠字符", type: "number", tips: "overlap", def: 100, min: 0, step: 20, width: 200, note: "0=关闭，需重解析生效", tier: 1 },
  { panel: "chunk", section: 2, group: "chunk", key: "structural", path: "chunk.structural", label: "结构感知切分", type: "switch", tips: "chunkStructural", def: true, note: "标题/段落边界优先 + 章节路径注入，需重解析生效", tier: 2 },
  { panel: "chunk", section: 2, group: "chunk", key: "structuralRatio", path: "chunk.structuralRatio", label: "边界阈值比例", type: "number", tips: "chunkStructuralRatio", def: 0.8, min: 0.5, max: 1, step: 0.05, width: 200, note: "达到 maxSize×比例 时优先在段落边界断块", vif: "chunk.structural", tier: 2 },
  { panel: "chunk", section: 2, group: "chunk", key: "headingDepth", path: "chunk.headingDepth", label: "标题识别层级", type: "number", tips: "chunkHeadingDepth", def: 4, min: 1, max: 6, step: 1, width: 200, note: "章节路径识别到几级标题；改后需重解析生效", vif: "chunk.structural", tier: 2 },
  { panel: "retrieval", section: 0, group: "keyword", key: "engine", path: "keyword.engine", label: "关键词引擎", type: "select", tips: "keywordEngine", def: "mysql", width: 220, options: [{"value":"mysql","label":"mysql（LIKE，零依赖，库大时慢）"},{"value":"meilisearch","label":"meilisearch（中文分词+相关度，推荐）"}], tier: 2 },
  { panel: "retrieval", section: 0, group: "keyword", key: "baseUrl", path: "keyword.baseUrl", label: "引擎服务地址", type: "text", tips: "keywordBaseUrl", def: "http://localhost:7700", width: 320, ph: "http://localhost:7700", tier: 2 },
  { panel: "retrieval", section: 0, group: "keyword", key: "apiKey", path: "keyword.apiKey", label: "引擎 Key", type: "password", tips: "keywordApiKey", def: "", width: 320, ph: "Meilisearch master key（服务端未设置可留空）", tier: 2 },
  { panel: "retrieval", section: 1, group: "retrieval", key: "vectorWeight", path: "retrieval.vectorWeight", label: "向量权重", type: "number", tips: "vectorWeight", def: 0.6, min: 0, max: 1, step: 0.05, width: 200, core: true, tier: 2 },
  { panel: "retrieval", section: 1, group: "retrieval", key: "keywordWeight", path: "retrieval.keywordWeight", label: "关键词权重", type: "number", tips: "keywordWeight", def: 0.4, min: 0, max: 1, step: 0.05, width: 200, core: true, tier: 2 },
  { panel: "retrieval", section: 1, group: "retrieval", key: "vectorTopK", path: "retrieval.vectorTopK", label: "向量召回上限", type: "number", tips: "vectorTopK", def: 15, min: 1, max: 100, step: 5, width: 200, note: "向量路候选块数，调大更易召回生僻表述", core: true, tier: 1 },
  { panel: "retrieval", section: 1, group: "retrieval", key: "vecThreshold", path: "retrieval.vecThreshold", label: "向量阈值", type: "number", tips: "vecThreshold", def: 0.3, min: 0, max: 1, step: 0.05, width: 200, note: "相似度归一化基准/下限", core: true, tier: 1 },
  { panel: "retrieval", section: 1, group: "retrieval", key: "keywordLimit", path: "retrieval.keywordLimit", label: "关键词召回上限", type: "number", tips: "keywordLimit", def: 20, min: 1, step: 5, width: 200, tier: 2 },
  { panel: "retrieval", section: 1, group: "retrieval", key: "searchTimeoutMs", path: "retrieval.searchTimeoutMs", label: "检索超时(ms)", type: "number", tips: "retrievalTimeout", def: 8000, min: 500, step: 500, width: 200, note: "混合检索总超时", tier: 2 },
  { panel: "retrieval", section: 5, group: "rerank", key: "enabled", path: "retrieval.rerank.enabled", label: "启用重排", type: "switch", tips: "rerankEnabled", def: false, note: "开启前自动校验服务可用性", tier: 2 },
  { panel: "context", section: 0, group: "context", key: "modelWindows", path: "context.modelWindows", label: "模型窗口映射", type: "text", tips: "modelWindows", def: "", ph: "模型名=token,逗号分隔，如 qwen3=131072", tier: 3 },
  { panel: "context", section: 0, group: "context", key: "defaultWindowTokens", path: "context.defaultWindowTokens", label: "默认窗口 token", type: "number", tips: "defaultWindow", def: 32768, min: 1000, step: 1000, width: 200, tier: 3 },
  { panel: "context", section: 0, group: "context", key: "safetyFactor", path: "context.safetyFactor", label: "窗口安全系数", type: "number", tips: "safetyFactor", def: 0.7, min: 0.1, max: 1, step: 0.05, width: 200, tier: 3 },
  { panel: "context", section: 0, group: "context", key: "costCapTokens", path: "context.costCapTokens", label: "成本软上限 token", type: "number", tips: "costCap", def: 8000, min: 0, step: 500, width: 200, tier: 2 },
  { panel: "context", section: 0, group: "context", key: "maxOutputTokens", path: "context.maxOutputTokens", label: "输出限制 token", type: "number", tips: "maxOutput", def: 2000, min: 100, step: 100, width: 200, tier: 3 },
  { panel: "context", section: 1, group: "context", key: "historyMaxTokens", path: "context.historyMaxTokens", label: "历史注入上限 token", type: "number", tips: "historyMax", def: 1200, min: 0, step: 100, width: 200, tier: 3 },
  { panel: "context", section: 1, group: "context", key: "historyPerMsgChars", path: "context.historyPerMsgChars", label: "单条历史截断字符", type: "number", tips: "historyPerMsg", def: 200, min: 0, step: 20, width: 200, tier: 3 },
  { panel: "context", section: 1, group: "context", key: "snippetWindowChars", path: "context.snippetWindowChars", label: "命中片段窗口字符", type: "number", tips: "snippetWindow", def: 150, min: 0, step: 20, width: 200, tier: 3 },
  { panel: "context", section: 1, group: "context", key: "maxContextHits", path: "context.maxContextHits", label: "知识块填充上限", type: "number", tips: "maxContextHits", def: 8, min: 1, max: 30, width: 200, tier: 2 },
  { panel: "context", section: 2, group: "context", key: "dedupEnabled", path: "context.dedupEnabled", label: "信息增益去冗余", type: "switch", tips: "dedupEnabled", def: true, note: "跳过与已选块语义重复的候选块（同一操作被切成多块时只保留最高相关那块，防重复进上下文与表述不一致）", tier: 2 },
  { panel: "context", section: 2, group: "context", key: "dedupThreshold", path: "context.dedupThreshold", label: "重叠阈值", type: "number", tips: "dedupThreshold", def: 0.45, min: 0.1, max: 0.9, step: 0.05, width: 200, note: "词元重叠比例达此值即判冗余（越高越宽松，越少剔除）", vif: "context.dedupEnabled", tier: 3 },
  { panel: "context", section: 2, group: "context", key: "dedupPathThreshold", path: "context.dedupPathThreshold", label: "同章节阈值", type: "number", tips: "dedupPathThreshold", def: 0.28, min: 0.1, max: 0.9, step: 0.05, width: 200, note: "同章节路径的相邻切片重复率更高，用更低阈值判断冗余", vif: "context.dedupEnabled", tier: 3 },
  { panel: "deepReasoning", section: 0, group: "deepReasoning", key: "enabled", path: "deepReasoning.enabled", label: "总开关", type: "switch", tips: "drEnabled", def: true, core: true, tier: 2 },
  { panel: "deepReasoning", section: 2, group: "deepReasoning", key: "autoRoute", path: "deepReasoning.autoRoute", label: "自动路由", type: "switch", tips: "drAutoRoute", def: false, note: "未手动开启时，长问/多条件/对比类问题自动启用深度思考", core: true, tier: 2 },
  { panel: "tool", section: 0, group: "tool", key: "enabled", path: "tool.enabled", label: "总开关", type: "switch", tips: "toolEnabled", def: false, note: "开启后模型可调用已注册的 @Tool 工具（默认关）", core: true, tier: 2 },
  { panel: "tool", section: 1, group: "tool", key: "knowledgeRetrieval.enabled", path: "tool.knowledgeRetrieval.enabled", label: "知识库精确检索工具", type: "switch", tips: "toolKnowledgeRetrieval", def: false, note: "模型回答中可自主发起检索补充资料（默认关）", vif: "tool.enabled", tier: 2 },
  { panel: "tool", section: 1, group: "tool", key: "knowledgeRetrieval.maxHits", path: "tool.knowledgeRetrieval.maxHits", label: "单次命中块上限", type: "number", tips: "toolKnowledgeRetrievalMaxHits", def: 5, min: 1, max: 5, width: 200, note: "工具单次返回的知识块数上限（1~5）", vif: "tool.enabled && tool.knowledgeRetrieval.enabled", tier: 3 },
  { panel: "tool", section: 1, group: "tool", key: "artifact.enabled", path: "tool.artifact.enabled", label: "产物交付工具", type: "switch", tips: "toolArtifactEnabled", def: false, note: "模型可生成 Markdown/CSV/JSON/HTML 文件并以可下载卡片附在回答中（默认关）", vif: "tool.enabled", tier: 2 },
  { panel: "tool", section: 1, group: "tool", key: "builtin.enabled", path: "tool.builtin.enabled", label: "内置高频工具（计算/时间）", type: "switch", tips: "toolBuiltinEnabled", def: false, note: "算术表达式计算、当前日期时间、日期相差天数——模型口算与「今天几号」的硬伤交给工具（默认关）", vif: "tool.enabled", tier: 2 },
  { panel: "ratelimit", section: -1, group: "ratelimit", key: "enabled", path: "ratelimit.enabled", label: "总开关", type: "switch", tips: "rlEnabled", def: true, core: true, tier: 2 },
  { panel: "maintenance", section: 2, group: "images", key: "chatCleanupIntervalMs", path: "images.chatCleanupIntervalMs", label: "清理任务间隔(ms)", type: "number", tips: "chatImgCleanupInterval", def: 86400000, min: 0, step: 3600000, width: 200, note: "≤0 = 暂停清理，默认 86400000（每天）", tier: 3 },
  { panel: "maintenance", section: 2, group: "images", key: "chatRetentionMillis", path: "images.chatRetentionMillis", label: "图片保留时长(ms)", type: "number", tips: "chatImgRetention", def: 604800000, min: 0, step: 86400000, width: 220, note: "默认 604800000（7 天），超期清理聊天上传图", tier: 3 },
  { panel: "skills", section: 0, group: "skill", key: "injectMaxChars", path: "skill.injectMaxChars", label: "清单字符上限", type: "number", tips: "skillInjectMax", def: 1200, min: 200, step: 100, width: 200, tier: 3 },
  { panel: "skills", section: 0, group: "skill", key: "maxFileChars", path: "skill.maxFileChars", label: "技能读取上限", type: "number", tips: "skillMaxFile", def: 20000, min: 500, step: 1000, width: 200, note: "单个技能全文读取字符上限，超出截断", tier: 3 },
  { panel: "agent", section: 0, group: "agent", key: "enabled", path: "agent.enabled", label: "总开关", type: "switch", tips: "agentEnabled", def: false, core: true, note: "开启后每轮问答先并行多视角检索再汇总（默认关，会增加首字延迟）", tier: 2 },
  { panel: "agent", section: 0, group: "agent", key: "subAgents", path: "agent.subAgents", label: "子代理数量", type: "number", tips: "agentSubAgents", def: 2, min: 2, max: 4, step: 1, width: 200, note: "2~4：越多召回越全，并发检索与提炼调用也越多", vif: "agent.enabled", tier: 2 },
  { panel: "agent", section: 0, group: "agent", key: "topKPerAgent", path: "agent.topKPerAgent", label: "每代理取块数", type: "number", tips: "agentTopK", def: 3, min: 1, max: 10, step: 1, width: 200, note: "每个子代理取回命中块上限（跨代理自动去重）", vif: "agent.enabled", tier: 3 },
  { panel: "agent", section: 0, group: "agent", key: "digestEnabled", path: "agent.digestEnabled", label: "要点提炼", type: "switch", tips: "agentDigest", def: true, note: "开=每个子代理用模型提炼 2~3 条要点再汇总；关=只并行检索合并（零额外调用）", vif: "agent.enabled", tier: 2 },
  { panel: "agent", section: 0, group: "agent", key: "autoDispatch", path: "agent.autoDispatch", label: "自动派遣", type: "switch", tips: "agentAutoDispatch", def: true, note: "对话页「自动派遣」模式的总开关：按名称+描述路由智能体；关闭后回落默认智能体", tier: 2 },
  { panel: "agent", section: 0, group: "agent", key: "autoRoute", path: "agent.autoRoute", label: "按需委派", type: "switch", tips: "agentAutoRoute", def: true, note: "开=主模型先从候选助手挑出相关的、只咨询选中的（省开销、卡片无无关角色）；关=每轮全部并行", vif: "agent.enabled", tier: 2 },
  { panel: "agent", section: 0, group: "agent", key: "routeTimeoutMs", path: "agent.routeTimeoutMs", label: "路由超时(ms)", type: "number", tips: "agentRouteTimeout", def: 8000, min: 1000, max: 20000, step: 500, width: 200, note: "挑选助手的最长等待；超时/失败自动回退为全部候选（不影响问答）", vif: "agent.enabled", tier: 3 },
]

/**
 * 基础模式显示的核心配置项（对标同类产品的设置体系：全局设置只保留「模型服务 + 回答行为」，
 * 细粒度调参——检索权重/分块参数/上下文预算/维护任务/功能总开关等——收进「高级设置」）。
 *
 * 判断依据：普通用户/管理员真正必须决定的只有「用哪个模型、连哪个网关、密钥是什么」，
 * 以及最能影响体感的少数回答行为项。其余都有合理默认值，需要时再进高级模式调。
 * 新增配置项默认进高级（不进此清单即隐藏），避免设置页再次膨胀。
 */
export const CORE_PATHS = new Set([
  // 主回答模型（会话覆盖 > 个人默认；网关/密钥在「模型供应商」页管理）
  'chat.temperature', 'chat.systemPrompt',
  // 视觉总开关（视觉模型归各知识库解析设置）
  'vision.enabled',
  // 重排总开关（重排模型归各知识库检索设置）
  'retrieval.rerank.enabled',
  // 自动派遣（对话页智能体路由）
  'agent.autoDispatch',
])

/** 字段是否属核心（基础模式可见）；path 缺省时按 group.key 拼 */
export function isCoreField (f) {
  const p = f.path || (f.group + '.' + f.key)
  return CORE_PATHS.has(p)
}

/** 基础模式下可见的面板（含至少一个核心字段的面板） */
export function corePanels () {
  return PANELS.filter(p => FIELDS.some(f => f.panel === p.key && !f.groupedUnder && isCoreField(f)))
}

/** 某面板在基础模式下被隐藏的字段数（用于提示"还有 N 项高级配置"） */
export function hiddenFieldCount (panel) {
  return FIELDS.filter(f => f.panel === panel && !f.groupedUnder && !isCoreField(f)).length
}

/**
 * 按面板取渲染块：分节标题与字段按顺序交织，自定义块由调用方插入。
 * @param coreOnly true=基础模式，只渲染核心字段（该节全被隐藏时不输出标题）
 */
export function blocksOf (panel, coreOnly = false) {
  const p = PANELS.find(x => x.key === panel)
  const blocks = []
  const keep = f => !coreOnly || isCoreField(f)
  for (let i = 0; i < p.sections.length; i++) {
    const fs = FIELDS.filter(f => f.panel === panel && f.section === i && !f.groupedUnder && keep(f))
    if (!fs.length) continue
    blocks.push({ type: 'sub', title: p.sections[i] })
    for (const f of fs) blocks.push({ type: 'field', field: f })
  }
  for (const f of FIELDS.filter(f => f.panel === panel && f.section === -1 && !f.groupedUnder && keep(f))) blocks.unshift({ type: 'field', field: f })
  return blocks
}

/** 由 schema 生成 form 默认值对象（替代手写 form） */
export function buildDefaultForm () {
  const form = {}
  const set = (path, v) => {
    const seg = path.split('.')
    let o = form
    for (let i = 0; i < seg.length - 1; i++) { o[seg[i]] = o[seg[i]] || {}; o = o[seg[i]] }
    o[seg[seg.length - 1]] = v
  }
  for (const f of FIELDS) set(f.path || (f.group + '.' + f.key), f.def)
  return form
}

/** 读取 form 内嵌套路径，如 readForm(form, 'retrieval.rerank.enabled') */
export function readForm (obj, path) {
  return path.split('.').reduce((a, k) => (a == null ? a : a[k]), obj)
}

/** 写入 form 内嵌套路径（中间层级自动创建） */
export function writeForm (obj, path, value) {
  const seg = path.split('.')
  let t = obj
  for (let i = 0; i < seg.length - 1; i++) {
    if (t[seg[i]] == null) t[seg[i]] = {}
    t = t[seg[i]]
  }
  t[seg[seg.length - 1]] = value
}

/** 条件显示：vif 形如 "vision.enabled" 或 "a.b && c.d" */
export function isVisible (field, form) {
  if (!field.vif) return true
  for (const cond of field.vif.split('&&').map(s => s.trim())) {
    const seg = cond.split('.')
    let v = form
    for (const s of seg) { if (v == null) return false; v = v[s] }
    if (!v) return false
  }
  return true
}
