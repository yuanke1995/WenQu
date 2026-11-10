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
