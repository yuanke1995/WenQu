/**
 * SSE 解析：基于 fetch 返回的 ReadableStream，按空行（\n\n）分帧，
 * 提取 data: 与 event: 字段。供 chat/responses 流式复用，零依赖、浏览器与 Node ≥18 双端可用。
 */

export interface SseEvent {
  /** SSE event: 行（chat/completions 流不带事件名，responses 流带） */
  event?: string;
  /** 多行 data: 以 \n 拼接后的载荷 */
  data: string;
}

/** 统一换行符：CRLF/CR → LF，简化后续分帧逻辑 */
function normalizeNewlines(text: string): string {
  return text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

/** 解析单个 SSE 帧；无 data 行的帧（纯注释/心跳）返回 null */
function parseFrame(frame: string): SseEvent | null {
  let event: string | undefined;
  const dataLines: string[] = [];
  for (const line of frame.split("\n")) {
    if (line.startsWith(":")) continue; // SSE 注释行
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).replace(/^ /, ""));
    } else if (line.startsWith("event:")) {
      event = line.slice(6).replace(/^ /, "");
    }
  }
  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join("\n") };
}

/**
 * 把响应体流转成 SSE 事件序列。
 * 终止条件：流自然结束（responses 流以 response.completed 收尾、无 [DONE]），
 * 或调用方 break 出 for-await（finally 里 cancel 释放连接）。
 * [DONE] 哨兵帧原样透传（data === "[DONE]"），由消费方决定终止。
 */
export async function* sseEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<SseEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) buffer += normalizeNewlines(decoder.decode(value, { stream: true }));
      let idx: number;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const frame = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const ev = parseFrame(frame);
        if (ev) yield ev;
      }
    }
    // 流结束时冲刷解码器与最后一段未闭合的帧（部分服务端结尾不带空行）
    buffer += normalizeNewlines(decoder.decode());
    const rest = buffer.trim();
    if (rest) {
      const ev = parseFrame(rest);
      if (ev) yield ev;
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      // 流已结束时 cancel 可能报错，忽略
    }
  }
}
