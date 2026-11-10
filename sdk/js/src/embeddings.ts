/**
 * 向量能力（OpenAI 兼容）：
 * - client.embeddings.create：批量文本 → 向量（model 为 providerId/modelId，必填）
 * - client.models.listEmbeddings：当前身份可用的向量模型清单（按供应商分组）
 */

import type { WenquClient } from "./client.js";
import { WenquError } from "./errors.js";
import type { EmbeddingCreateParams, EmbeddingCreateResponse, EmbeddingModelList } from "./types.js";

const EMBEDDINGS_PATH = "/api/ai/v1/embeddings";
const EMBEDDING_MODELS_PATH = "/api/ai/v1/models/embeddings";

export class Embeddings {
  constructor(private readonly client: WenquClient) {}

  async create(params: EmbeddingCreateParams): Promise<EmbeddingCreateResponse> {
    if (!params.model) {
      throw new WenquError("embeddings.create: model 必填，为向量模型引用 providerId/modelId（可用清单：models.listEmbeddings()）");
    }
    const texts = typeof params.input === "string" ? [params.input] : params.input;
    if (!texts || texts.length === 0) throw new WenquError("embeddings.create: input 不能为空");
    const body: Record<string, unknown> = { input: params.input, model: params.model };
    if (params.encoding_format) body.encoding_format = params.encoding_format;
    if (params.dimensions != null) body.dimensions = params.dimensions;
    return this.client.jsonRequest<EmbeddingCreateResponse>(EMBEDDINGS_PATH, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }
}

export class Models {
  constructor(private readonly client: WenquClient) {}

  /**
   * 可用向量模型清单。data 是按供应商分组的数组，组内模型含 ref=providerId/modelId，
   * 可直接作 embeddings.create 的 model 参数。字段按存在性取值（不假设恒在）。
   */
  async listEmbeddings(): Promise<EmbeddingModelList> {
    return this.client.jsonRequest<EmbeddingModelList>(EMBEDDING_MODELS_PATH);
  }
}
