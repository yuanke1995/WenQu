/**
 * 问渠对外 API（/api/ai/v1/**）的完整类型定义。
 * 与后端契约一一对应：OpenAI 兼容端点（chat/responses/embeddings/models）返回 OpenAI 形状；
 * 平台端点（retrieval/kbs/workflows）返回 {success,data,msg} 包络。
 */

// ==================== 平台包络 ====================

/** 平台统一响应包络（retrieval/kbs/workflows 用；SDK 内部消费，不对外暴露） */
export interface PlatformEnvelope<T> {
  success: boolean;
  data?: T;
  msg?: string;
}

/** OpenAI 风格错误体（chat/embeddings 等兼容端点用） */
export interface OpenAiErrorBody {
  error?: {
    message?: string;
    type?: string;
  };
}

// ==================== Chat Completions（OpenAI 兼容） ====================

export type ChatRole = "system" | "user" | "assistant" | "tool";

/** 会话消息。role=tool 时可带 tool_call_id 回传上一轮工具结果 */
export interface ChatMessage {
  role: ChatRole;
  content: string;
  tool_call_id?: string;
}

/** Function calling 声明的工具（只接受只读工具，副作用工具后端不暴露） */
export interface ChatTool {
  type: "function";
  function: {
    name: string;
    description?: string;
    parameters?: Record<string, unknown>;
  };
}

/** 模型发起的工具调用 */
export interface ChatToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string;
  };
}

export interface ChatCompletionUsage {
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
}

export interface ChatCompletionMessage {
  role: "assistant";
  /** finish_reason=tool_calls 时为 null，正文在 tool_calls 里 */
  content: string | null;
  tool_calls?: ChatToolCall[];
}

export interface ChatCompletionChoice {
  index: number;
  message: ChatCompletionMessage;
  finish_reason: string;
}

/** 非流式返回体（OpenAI chat.completion 形状）。model 是问渠智能体 id */
export interface ChatCompletion {
  id: string;
  object: "chat.completion";
  created: number;
  model: string;
  choices: ChatCompletionChoice[];
  usage?: ChatCompletionUsage;
}

export interface ChatCompletionChunkDelta {
  role?: "assistant";
  content?: string;
  tool_calls?: Array<ChatToolCall & { index?: number }>;
}

export interface ChatCompletionChunkChoice {
  index: number;
  delta: ChatCompletionChunkDelta;
  finish_reason?: string | null;
}

/**
 * 流式增量帧（OpenAI chat.completion.chunk 形状）。
 * usage 尾帧 choices 为空、usage 单独一帧；流中错误帧带 error 字段（SDK 会抛错而非透传）。
 */
export interface ChatCompletionChunk {
  id: string;
  object: "chat.completion.chunk";
  created: number;
  model: string;
  choices: ChatCompletionChunkChoice[];
  usage?: ChatCompletionUsage;
  error?: { message?: string; type?: string };
}

export interface ChatCreateParams {
  /** 问渠智能体 id（在问渠智能体页查看） */
  model: string;
  messages: ChatMessage[];
  tools?: ChatTool[];
  /** true 时 create 返回 AsyncIterable<ChatCompletionChunk> */
  stream?: boolean;
  /** 透传 stream_options.include_usage：true 时流尾帧带 usage（仅 stream=true 有意义） */
  includeUsage?: boolean;
}

// ==================== Responses（OpenAI 兼容） ====================

export interface ResponseCreateParams {
  /** 问渠智能体 id */
  model: string;
  /** 字符串或消息数组；最后一条 user 消息是本轮提问 */
  input: string | ChatMessage[];
  instructions?: string;
  /** true 时 create 返回 AsyncIterable<ResponseStreamEvent> */
  stream?: boolean;
}

export interface ResponseOutputContent {
  type: "output_text";
  text: string;
  annotations?: unknown[];
}

export interface ResponseOutputMessage {
  type: "message";
  id: string;
  role: "assistant";
  status: string;
  content: ResponseOutputContent[];
}

export interface ResponseUsage {
  input_tokens: number;
  output_tokens: number;
  total_tokens: number;
}

/** Responses 对象（非流式返回体与流式 completed/failed 事件内嵌同一形状） */
export interface WenquResponse {
  id: string;
  object: "response";
  created_at: number;
  model: string;
  status: string;
  output: ResponseOutputMessage[];
  usage?: ResponseUsage;
}

/** Responses 流式事件（按 type 判别；后端同时带同名 SSE event 行） */
export type ResponseStreamEvent =
  | { type: "response.created"; response: WenquResponse }
  | {
      type: "response.output_text.delta";
      item_id?: string;
      output_index?: number;
      content_index?: number;
      delta: string;
    }
  | { type: "response.completed"; response: WenquResponse }
  | { type: "response.failed"; response: WenquResponse };

// ==================== Embeddings（OpenAI 兼容） ====================

export interface EmbeddingCreateParams {
  /** 单条或批量文本（单批 ≤256 条、单条 ≤8192 token，超限后端 400） */
  input: string | string[];
  /** 向量模型引用 providerId/modelId（必填；可用清单见 models.listEmbeddings） */
  model: string;
  encoding_format?: "float" | "base64";
  dimensions?: number;
}

export interface EmbeddingItem {
  object: "embedding";
  index: number;
  /** float 格式为 number[]；base64 格式为编码串（float32 小端拼接后 base64） */
  embedding: number[] | string;
}

export interface EmbeddingUsage {
  prompt_tokens: number;
  total_tokens: number;
}

export interface EmbeddingCreateResponse {
  object: "list";
  data: EmbeddingItem[];
  model: string;
  usage: EmbeddingUsage;
  /** 向量维度（后端有返回时存在） */
  dimension?: number;
}

/** 向量模型清单：按供应商分组。字段按存在性取值，不假设恒在 */
export interface EmbeddingModelInfo {
  modelId?: string;
  /** 直接可作 embeddings.create 的 model 参数（providerId/modelId） */
  ref?: string;
  [key: string]: unknown;
}

export interface EmbeddingModelGroup {
  providerId?: string;
  providerName?: string;
  models?: EmbeddingModelInfo[];
  [key: string]: unknown;
}

export interface EmbeddingModelList {
  object: "list";
  data: EmbeddingModelGroup[];
}

// ==================== 知识检索 ====================

export interface RetrievalParams {
  /** 检索问题（≤8000 字） */
  query: string;
  /** 限定的知识库 id（空 = 本人可见全部库） */
  knowledgeBaseIds?: string[];
  /** 返回条数 1-50，默认 20 */
  topK?: number;
  /** 按融合分过滤（0-1） */
  minScore?: number;
}

/** 命中的知识块（来源文档/库/块序号） */
export interface RetrievedChunk {
  id: string;
  text: string;
  score: number;
  title?: string | null;
  titlePath?: string | null;
  docId?: string | null;
  kbId?: string | null;
  chunkIndex?: number | null;
  images: string[];
}

export interface RetrievalResult {
  chunks: RetrievedChunk[];
  total: number;
  /** 检索链路降级提示（如向量部分失败） */
  warnings?: string[];
}

// ==================== 知识库管理 ====================

/** 知识库对外精简视图 */
export interface KnowledgeBase {
  id: string;
  name: string;
  description?: string | null;
  /** 绑定的向量模型引用 providerId/modelId */
  embeddingRef?: string | null;
  docCount?: number | null;
  isDefault?: boolean | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface KbListResult {
  knowledgeBases: KnowledgeBase[];
  total: number;
}

/** 文档状态：0 生效 / 1 已弃用 / 2 解析中 / 3 解析失败 */
export type DocumentStatus = 0 | 1 | 2 | 3;

export interface KbDocument {
  id: string;
  kbId: string;
  fileName?: string | null;
  fileType?: string | null;
  status?: DocumentStatus | number | null;
  statusText?: string | null;
  chunkCount?: number | null;
  parseProgress?: number | null;
  parseDesc?: string | null;
  failReason?: string | null;
  fileSize?: number | null;
  description?: string | null;
  tags?: unknown;
  version?: number | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface KbDocumentListResult {
  documents: KbDocument[];
  total: number;
}

export interface CreateKbParams {
  name: string;
  description?: string;
  /** 必填：providerId/modelId（见 models.listEmbeddings） */
  embeddingRef: string;
  icon?: string;
  queryParams?: Record<string, unknown>;
  parseParams?: Record<string, unknown>;
}

/** 上传文件：浏览器 File / 通用 Blob / 字节 / 文本均可 */
export type DocumentFileInput = File | Blob | Uint8Array | ArrayBuffer | string;

export interface UploadDocumentParams {
  file: DocumentFileInput;
  /** 非 File/Blob 输入时必填文件名；File 输入缺省取 file.name */
  filename?: string;
  /** 文档描述（≤500 字） */
  description?: string;
}

// ==================== 工作流 ====================

export interface WorkflowRunParams {
  /** 开始节点入参（对应 DSL 里的 {{start.key}}） */
  inputs?: Record<string, unknown>;
}

/**
 * 运行记录。status=failed 时不抛错——调用方读 status/error/outputs 自行分流。
 * 常见 status：success / failed / running 等（以后端返回为准）。
 */
export interface WorkflowRun {
  id: string;
  status: string;
  /** 结束节点出参 */
  outputs?: Record<string, unknown> | null;
  /** 节点级输入输出与耗时（字段结构以后端为准，SDK 不裁剪） */
  nodeTraces?: Array<Record<string, unknown>> | null;
  durationMs?: number | null;
  error?: string | null;
}

// ==================== 原生问答运行时（深度集成） ====================

/** SSE 事件帧外壳：content 是字符串（结构化载荷为「字符串套 JSON」，SDK 已尽力解一层） */
export interface NativeFrame {
  /** 事件名（token/thinking/tool_status/artifact/ask_user/deep_plan_approval/done/error…，契约只增不删） */
  type: string;
  content?: string | null;
  sessionId?: string;
}

/** 发问入参（与问渠 ChatRequest 契约对齐，字段以后端为准） */
export interface NativeSendParams {
  question: string;
  /** 会话 id：留空由服务端新建，done 帧带回 */
  sessionId?: string;
  /** 智能体 id：不传走默认助手 */
  agentId?: string;
  /** 会话级模型覆盖（providerId/modelId） */
  model?: string;
  /** 图片 data URL 数组 */
  images?: string[];
  /** 附件引用（先 uploadAttachment 换 fileId） */
  attachments?: Array<{ fileId: string; name?: string; mime?: string }>;
  /** @ 引用：kb=检索收窄 / doc=内容前置 / agent=轮级委派 */
  mentions?: Array<{ type: "kb" | "doc" | "agent"; id: string; name?: string }>;
  /** # 历史引用 */
  historyRefs?: Array<{ messageId: string }>;
  /** 本轮启用技能名 */
  skills?: string[];
  deepThink?: boolean;
  planMode?: boolean;
  /** 重新计划所基于的旧计划批准 id */
  planBaseId?: string;
  reasoningLevel?: "low" | "medium" | "high" | "xhigh" | "max";
  regenerate?: boolean;
  replaceMessageId?: string;
  editMessageId?: string;
  /** 插话续发标记：这句是上一轮跑到一半时补的方向 */
  fromSteer?: boolean;
  /** 调用变量（业务上下文注入本轮，≤32 项） */
  variables?: Record<string, string>;
}

/** tool_status 事件整包（驱动进度行；args/result 是 JSON 串，用前自行 parse） */
export interface ToolStatusEvent {
  name?: string;
  label?: string;
  hint?: string;
  status?: string;
  elapsedMs?: number;
  args?: string;
  result?: string;
  [k: string]: unknown;
}

/** artifact 事件：deliver_artifact 的实时产物推送（url 是问渠签名地址） */
export interface ArtifactEvent {
  id?: string;
  url: string;
  filename?: string;
  ext?: string;
  size?: number;
  description?: string;
}

/** ask_user 卡片（人在回路·提问） */
export interface AskUserCard {
  askId: string;
  questions: Array<{ topic?: string; question: string; options?: string[]; multiSelect?: boolean }>;
  timeoutMs?: number;
  remainingMs?: number;
}

/** approval_required 卡片（人在回路·工具执行审批） */
export interface ApprovalCard {
  approvalId: string;
  toolName?: string;
  args?: string;
  reason?: string;
  timeoutMs?: number;
  remainingMs?: number;
  [k: string]: unknown;
}

/** deep_plan_approval 卡片（人在回路·执行计划） */
export interface PlanApprovalCard {
  planApprovalId: string;
  /** 计划正文（步骤清单，结构以后端为准） */
  plan?: unknown;
  timeoutMs?: number;
  remainingMs?: number;
  [k: string]: unknown;
}

/** resume 的首帧快照（已产出正文/卡片/时间线；running=false 表示没有轮在跑） */
export interface SnapshotPayload {
  running?: boolean;
  content?: string;
  [k: string]: unknown;
}

/** done 载荷关键字段（其余字段随功能演进增补，按存在性取值） */
export interface DonePayload {
  sources?: Array<Record<string, unknown>>;
  related?: string[];
  messageId?: string;
  turnId?: string;
  /** 帧上的会话 id 由 SDK 合入顶层（done 载荷本体不带） */
  sessionId?: string;
  finalContent?: string;
  finalImages?: string[];
  toolCalls?: Array<Record<string, unknown>>;
  artifacts?: Array<Record<string, unknown>>;
  agentId?: string;
  agentName?: string;
  [k: string]: unknown;
}

/** 流式事件回调：全部帧经 onEvent 透出，已知类型另给专用回调；未识别类型不报错（契约只增不删） */
export interface NativeStreamHandlers {
  /** 每一帧先到这里（含 done/error），做兜底渲染或日志 */
  onEvent?: (frame: NativeFrame) => void;
  onText?: (delta: string) => void;
  onThinking?: (delta: string) => void;
  /** 人话一行（tool_status 的 label/name/hint），适合状态条展示 */
  onStatus?: (line: string) => void;
  onTool?: (evt: ToolStatusEvent) => void;
  onArtifact?: (evt: ArtifactEvent) => void;
  onAskUser?: (card: AskUserCard) => void;
  onApprovalRequired?: (card: ApprovalCard) => void;
  /** plan 事件：整份计划（结构以后端为准） */
  onPlan?: (plan: unknown) => void;
  /** deep_plan_delta 事件：计划流式增量 */
  onPlanDelta?: (delta: string) => void;
  onPlanApproval?: (card: PlanApprovalCard) => void;
  /** deep_plan_cancelled / deep_plan_superseded */
  onPlanTerminal?: (type: "deep_plan_cancelled" | "deep_plan_superseded", payload: unknown) => void;
  onTodo?: (payload: unknown) => void;
  onRetrieved?: (payload: unknown) => void;
  onUsage?: (payload: unknown) => void;
  onWarn?: (text: string) => void;
  onImage?: (payload: unknown) => void;
  onGoalState?: (payload: unknown) => void;
  onSnapshot?: (snap: SnapshotPayload) => void;
  onDone?: (payload: DonePayload) => void;
  onError?: (message: string) => void;
  /** 本地 abort 断流（服务端按断线宽限后台续跑，之后可 resume 接回） */
  onAbort?: () => void;
}

/** /chat/attachment 返回 */
export interface UploadAttachmentResult {
  fileId: string;
  name?: string;
  mime?: string;
  size?: number;
}
