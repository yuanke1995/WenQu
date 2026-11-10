/**
 * 知识检索：client.retrieval.search——混合检索直通，返回按融合分降序的知识块。
 * 平台包络端点（{success,data,msg}），SDK 解包后只把 data 交给调用方。
 */

import type { WenquClient } from "./client.js";
import { WenquError } from "./errors.js";
import type { RetrievalParams, RetrievalResult } from "./types.js";

const RETRIEVAL_PATH = "/api/ai/v1/retrieval";

export class Retrieval {
  constructor(private readonly client: WenquClient) {}

  async search(params: RetrievalParams): Promise<RetrievalResult> {
    if (!params.query || !params.query.trim()) throw new WenquError("retrieval.search: query 不能为空");
    const body: Record<string, unknown> = { query: params.query };
    if (params.knowledgeBaseIds) body.knowledgeBaseIds = params.knowledgeBaseIds;
    if (params.topK != null) body.topK = params.topK;
    if (params.minScore != null) body.minScore = params.minScore;
    return this.client.envelopeRequest<RetrievalResult>(RETRIEVAL_PATH, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }
}
