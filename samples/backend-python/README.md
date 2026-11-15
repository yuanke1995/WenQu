# backend-python · 检索 + 工作流触发

FastAPI + `wenqu-ai`（Python SDK，仓库内 `-e` 安装，无需 PyPI）。

```bash
cd samples/backend-python
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
set -a && . ../.env && set +a
uvicorn app:app --port 3012
```

```bash
curl -s 'http://127.0.0.1:3012/search?q=%E9%97%AE%E6%B8%A0&top_k=5'
curl -s -X POST http://127.0.0.1:3012/workflow -H 'Content-Type: application/json' -d '{"inputs":{}}'
curl -s http://127.0.0.1:3012/health
```

## 两个必须预置的东西

- **检索要有内容**：`kb.read` 之外，还得有已登记的向量模型供应商和至少一个有文档的知识库。
  空库返回 `chunks: []`，这不是故障；`seed/` 不创建这些（见 `samples/README.md`）。
- **工作流必须已发布**：`POST /v1/workflows/{id}/run` 只跑已发布版本，含人工审核节点也会被拒。
  建/发布工作流只有登录态内部端点（`POST /api/ai/workflow/{id}/publish`），
  不在 v1 契约里，所以 `WENQU_WORKFLOW_ID` 要人工填。本地库里当前全是 `draft`，
  因此**这一半在本仓库本地环境无法实机跑通**，代码按真实返回形状写，不伪造成功响应。

## 失败路径

- Key 没开 `kb.read` → 403「该 API Key 未开通「kb.read」能力…」。
- `q` 空或超 8000 字 → 400 `invalid_request_error`。
- 工作流是 draft → 400「工作流「X」尚未发布：API 触发只跑已发布版本（请先在画布发布）」。
- 运行失败**不抛 500**：返回 `status=failed` 的 run 对象，读 `error` 字段即可。
