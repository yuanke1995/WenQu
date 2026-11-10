/**
 * 知识库管理：client.kbs.*
 * 读操作（list/get/listDocuments）需 API Key 开通 kb.read；
 * 写操作（create/uploadDocument）需 kb.write（默认不放开，Key 编辑里显式勾选）。
 */

import type { WenquClient } from "./client.js";
import { WenquApiError, WenquError } from "./errors.js";
import type {
  CreateKbParams,
  DocumentFileInput,
  KbDocument,
  KbDocumentListResult,
  KbListResult,
  KnowledgeBase,
  PlatformEnvelope,
  UploadDocumentParams,
} from "./types.js";

const KBS_PATH = "/api/ai/v1/kbs";

/** 路径段转义：库 id 直接拼进 URL 前的防御 */
function enc(segment: string): string {
  return encodeURIComponent(segment);
}

/** 统一转 Blob：File/Blob 原样，字节与文本包一层 */
function toBlob(file: DocumentFileInput): Blob {
  if (file instanceof Blob) return file;
  if (file instanceof Uint8Array) return new Blob([file]);
  if (file instanceof ArrayBuffer) return new Blob([new Uint8Array(file)]);
  return new Blob([file]);
}

export class Kbs {
  constructor(private readonly client: WenquClient) {}

  /** 知识库列表（本人可见范围，含每库文档数） */
  async list(): Promise<KbListResult> {
    return this.client.envelopeRequest<KbListResult>(KBS_PATH);
  }

  /** 知识库详情（不可见按不存在处理，抛 404） */
  async get(kbId: string): Promise<KnowledgeBase> {
    if (!kbId) throw new WenquError("kbs.get: kbId 必填");
    return this.client.envelopeRequest<KnowledgeBase>(`${KBS_PATH}/${enc(kbId)}`);
  }

  /** 库内文档列表（解析进度轮询本接口：status 0 生效 / 1 弃用 / 2 解析中 / 3 失败） */
  async listDocuments(kbId: string): Promise<KbDocumentListResult> {
    if (!kbId) throw new WenquError("kbs.listDocuments: kbId 必填");
    return this.client.envelopeRequest<KbDocumentListResult>(`${KBS_PATH}/${enc(kbId)}/documents`);
  }

  /** 创建知识库（embeddingRef 必填：providerId/modelId） */
  async create(params: CreateKbParams): Promise<KnowledgeBase> {
    if (!params.name || !params.name.trim()) throw new WenquError("kbs.create: name 不能为空");
    if (!params.embeddingRef) {
      throw new WenquError("kbs.create: embeddingRef 必填（providerId/modelId，可用清单：models.listEmbeddings()）");
    }
    const body: Record<string, unknown> = { name: params.name, embeddingRef: params.embeddingRef };
    if (params.description != null) body.description = params.description;
    if (params.icon != null) body.icon = params.icon;
    if (params.queryParams != null) body.queryParams = params.queryParams;
    if (params.parseParams != null) body.parseParams = params.parseParams;
    return this.client.envelopeRequest<KnowledgeBase>(KBS_PATH, {
      method: "POST",
      body: JSON.stringify(body),
    });
  }

  /**
   * 上传文档（multipart 字段名 file）。同步返回文档行（status=2 解析中），
   * 解析/分块/向量化走后台队列，进度用 listDocuments 轮询。
   */
  async uploadDocument(kbId: string, params: UploadDocumentParams): Promise<KbDocument> {
    if (!kbId) throw new WenquError("kbs.uploadDocument: kbId 必填");
    if (params.file == null) throw new WenquError("kbs.uploadDocument: file 必填");
    const filename =
      params.filename ??
      (typeof File !== "undefined" && params.file instanceof File ? params.file.name : undefined);
    if (!filename) {
      throw new WenquError("kbs.uploadDocument: 非 File 输入必须提供 filename（multipart 文件名是后端格式闸的依据）");
    }
    const form = new FormData();
    form.append("file", toBlob(params.file), filename);
    if (params.description != null) form.append("description", params.description);
    // multipart 不手动设 Content-Type：fetch 自动生成带 boundary 的头
    const res = await this.client.rawRequest(`${KBS_PATH}/${enc(kbId)}/documents`, {
      method: "POST",
      body: form,
    });
    const body = (await res.json()) as PlatformEnvelope<KbDocument>;
    if (body && body.success === false) throw new WenquApiError(body.msg || "上传失败");
    return body.data as KbDocument;
  }
}
