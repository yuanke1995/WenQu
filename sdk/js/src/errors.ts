/**
 * 错误体系：后端两种错误体（OpenAI 风格 {error:{message,type}} 与平台包络 {success:false,msg}）
 * 统一映射为 WenquError 子类，调用方只需按类型/状态码分流。
 */

export interface WenquErrorInfo {
  /** HTTP 状态码（网络层失败等非 HTTP 错误时缺省） */
  status?: number;
  /** OpenAI 风格错误类型（invalid_request_error / rate_limit_error 等；平台包络错误无此字段） */
  type?: string;
  /** v1 统一错误体的机器可读码（与 type 同源） */
  code?: string;
  /** 全链路请求号：报错时把它带给运维即可定位那一笔 */
  requestId?: string;
}

/** 所有问渠 SDK 错误的基类 */
export class WenquError extends Error {
  readonly status?: number;
  readonly type?: string;
  readonly code?: string;
  readonly requestId?: string;

  constructor(message: string, info?: WenquErrorInfo) {
    super(message);
    this.name = "WenquError";
    this.status = info?.status;
    this.type = info?.type;
    this.code = info?.code;
    this.requestId = info?.requestId;
  }
}

/** API 调用失败（非 2xx 或包络 success=false 的通用错误） */
export class WenquApiError extends WenquError {
  constructor(message: string, info?: WenquErrorInfo) {
    super(message, info);
    this.name = "WenquApiError";
  }
}

/** 鉴权失败（401/403）：API Key 无效、缺 scope 或资源不可见 */
export class WenquAuthError extends WenquApiError {
  constructor(message: string, info?: WenquErrorInfo) {
    super(message, info);
    this.name = "WenquAuthError";
  }
}

/** 限频（429）：按调用身份限频，稍后重试 */
export class WenquRateLimitError extends WenquApiError {
  constructor(message: string, info?: WenquErrorInfo) {
    super(message, info);
    this.name = "WenquRateLimitError";
  }
}

/**
 * 从状态码 + 响应体文本构造错误：两种错误体都能解析出 message。
 * 解析优先级：OpenAI 风格 error.message/type → 包络 msg → 顶层 message → 原始文本截断。
 */
export function errorFromStatus(status: number, rawText: string): WenquError {
  let message = `请求失败（HTTP ${status}）`;
  let type: string | undefined;
  let code: string | undefined;
  let requestId: string | undefined;
  let parsed: unknown;
  try {
    parsed = JSON.parse(rawText);
  } catch {
    parsed = undefined;
  }
  if (parsed && typeof parsed === "object") {
    const obj = parsed as Record<string, unknown>;
    const openAiError = obj.error as Record<string, unknown> | undefined;
    if (openAiError && typeof openAiError.message === "string" && openAiError.message) {
      message = openAiError.message;
      if (typeof openAiError.type === "string") type = openAiError.type;
      if (typeof openAiError.code === "string") code = openAiError.code;
      if (typeof openAiError.request_id === "string") requestId = openAiError.request_id;
    } else if (typeof obj.msg === "string" && obj.msg) {
      // 平台包络 {success:false, msg}
      message = obj.msg;
    } else if (typeof obj.message === "string" && obj.message) {
      message = obj.message;
    }
  } else if (rawText) {
    message = rawText.slice(0, 500);
  }
  const info: WenquErrorInfo = { status, type, code, requestId };
  if (status === 401 || status === 403) return new WenquAuthError(message, info);
  if (status === 429) return new WenquRateLimitError(message, info);
  return new WenquApiError(message, info);
}

/** 读取失败响应的错误体并构造映射错误 */
export async function errorFromResponse(res: Response): Promise<WenquError> {
  let text = "";
  try {
    text = await res.text();
  } catch {
    // 错误体不可读时按状态码给兜底文案
  }
  return errorFromStatus(res.status, text);
}
