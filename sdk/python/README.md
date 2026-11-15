# wenqu-ai — 问渠 WenQu Python SDK

问渠（AI 智能体工作台）的对外 Python SDK：几行代码调用问渠的**问答、向量、知识库检索、知识库管理与工作流**能力。运行时依赖仅 `requests`。

## 安装

```bash
cd sdk/python
pip install -e .
```

不想安装也可以，直接在代码里注入源码路径：

```python
import sys
sys.path.insert(0, "/path/to/WenQu/sdk/python/src")
from wenqu import WenquClient
```

要求 Python ≥ 3.9。

## 构造客户端

```python
from wenqu import WenquClient

client = WenquClient(
    base_url="http://host:8090/ai",   # 后端 context-path 是 /ai，请连同 /ai 一起传入
    api_key="sk-xxx",                  # 对外 API Key，随请求以 X-Api-Key 头下发
)
```

`timeout` 默认 60 秒（连接与读取共用）。客户端可复用（内部是 `requests.Session` 连接池），不再使用时 `client.close()`，或用 `with WenquClient(...) as client:` 自动释放。

## 快速上手

### 问答（Chat Completions，OpenAI 兼容）

`model` 填问渠**智能体 id**。

```python
resp = client.chat.completions.create(
    model="<智能体id>",
    messages=[{"role": "user", "content": "用一句话介绍问渠"}],
)
print(resp["choices"][0]["message"]["content"])
```

### 向量化（Embeddings）

`model` 必填，形如 `providerId/modelId`（清单见下方 `list_embeddings`）。单批 ≤256 条、单条 ≤8192 token。

```python
resp = client.embeddings.create(input="要向量化的文本", model="dashscope/text-embedding-v3")
vec = resp["data"][0]["embedding"]

# 批量 + 指定维度
resp = client.embeddings.create(
    input=["文本一", "文本二"], model="dashscope/text-embedding-v3", dimensions=512
)

# 可用向量模型清单（ref 可直接作 model 参数）
listing = client.models.list_embeddings()   # {"object": "list", "data": [按供应商分组...]}
for group in listing["data"]:
    for m in group.get("models", []):
        print(m["ref"])
```

### 知识库检索

```python
data = client.retrieval.search(
    "问渠如何配置重排",
    knowledge_base_ids=["kb-xxx"],   # 可选，缺省 = 该身份可见的全部库
    top_k=10,                         # 1-50，默认 20
    min_score=0.5,                    # 可选，按融合分过滤
)
for chunk in data["chunks"]:
    print(chunk["score"], chunk["title"], chunk["text"])
if data.get("warnings"):              # 降级提示，可选字段
    print("warnings:", data["warnings"])
```

### 知识库管理

```python
# 读（kb.read）
kbs = client.kbs.list()["knowledgeBases"]
kb = client.kbs.get(kbs[0]["id"])
docs = client.kbs.list_documents(kbs[0]["id"])["documents"]
# 文档 status：0 生效 / 1 弃用 / 2 解析中 / 3 解析失败（进度看 parseProgress，失败看 failReason）

# 写（kb.write，默认不放开，需在 API Key 编辑里显式勾选）
new_kb = client.kbs.create(name="我的库", embedding_ref="dashscope/text-embedding-v3", description="…")
doc = client.kbs.upload_document(new_kb["id"], "/path/to/手册.pdf")            # 传路径
doc = client.kbs.upload_document(new_kb["id"], open("/path/to/a.md", "rb"))    # 或传文件对象
# 同步返回 status=2（解析中），解析走后台队列，轮询 list_documents 看进度
```

### 工作流

```python
run = client.workflows.run("<工作流id>", inputs={"question": "你好"})   # 需 workflow.run，跑已发布版本
print(run["status"], run["outputs"], run["durationMs"])
run2 = client.workflows.get_run("<工作流id>", run["id"])                 # 按 runId 复查
```

## 流式

`stream=True` 返回迭代器，逐条产出 dict，收到 `data: [DONE]` 自然结束：

```python
for chunk in client.chat.completions.create(
    model="<智能体id>", messages=[{"role": "user", "content": "讲个故事"}], stream=True
):
    delta = chunk["choices"][0]["delta"].get("content", "")
    print(delta, end="", flush=True)
    if chunk.get("usage"):   # 尾帧带 usage
        print("\nusage:", chunk["usage"])
```

Responses API 同样支持流式，产出 `response.created / response.output_text.delta / response.completed / response.failed` 事件（dict 内 `type` 即事件名）：

```python
for event in client.responses.create(model="<智能体id>", input="讲个故事", stream=True):
    if event["type"] == "response.output_text.delta":
        print(event["delta"], end="", flush=True)
```

## 错误处理

所有异常继承 `WenquError`：

| 异常 | 触发条件 |
|------|----------|
| `WenquAuthError` | HTTP 401/403：Key 缺失、无效或 scope 不足 |
| `WenquRateLimitError` | HTTP 429：限频，应退避后重试 |
| `WenquAPIError` | 其余非 2xx，或包络 `success=false` |

```python
from wenqu import WenquClient, WenquRateLimitError, WenquAuthError, WenquAPIError

try:
    client.retrieval.search("问题")
except WenquRateLimitError as e:
    ...  # 退避重试：e.status_code == 429
except WenquAuthError as e:
    ...  # 检查 Key 与 scope
except WenquAPIError as e:
    print(e.status_code, e.message, e.error_type)
```

后端存在两种错误体形状（OpenAI 风格 `{"error":{"message","type"}}` 与平台包络 `{"success":false,"msg":...}`），SDK 已统一解析进 `message`/`error_type`/`payload`，调用方无需区分。

## API Key scope 说明

| scope | 覆盖能力 |
|-------|----------|
| `chat` | Chat Completions、Responses |
| `embedding` | `embeddings.create`、`models.list_embeddings` |
| `kb.read` | `retrieval.search`、`kbs.list/get/list_documents` |
| `kb.write` | `kbs.create`、`kbs.upload_document`（**默认不放开**，需在 Key 编辑里显式勾选） |
| `workflow.run` | `workflows.run/get_run` |

scope 不足时后端返回 403，SDK 抛 `WenquAuthError`。

## LangChain 适配（可选）

```bash
pip install -e ".[langchain]"   # 或直接 pip install langchain-core
```

```python
from wenqu import WenquClient
from wenqu.langchain_integration import WenquEmbeddings, WenquChat

client = WenquClient(base_url="http://host:8090/ai", api_key="sk-xxx")

embeddings = WenquEmbeddings(client, model="dashscope/text-embedding-v3")
vecs = embeddings.embed_documents(["文本一", "文本二"])

llm = WenquChat(client, model="<智能体id>")
reply = llm.invoke("用一句话介绍问渠")
print(reply.content)
```

未安装 `langchain-core` 时导入 `wenqu.langchain_integration` 会抛出带安装指引的 `ImportError`；SDK 核心功能不受影响。

## v1 run 原语（`client.runs`）

正式契约的「一次运行」。需要给 Key 显式勾选 `runs.write` / `runs.read`（**不在默认集**）。
与 `client.native`（JS SDK 的深度集成协议）不同，runs 的事件名在 SSE `event:` 行、
每帧带 `id:`（seq），断线用 `Last-Event-ID` 补增量。

```python
run = client.runs.create("智能体id", "整理这份资料", idempotency_key="order-12345")

summary = client.runs.stream(
    run["runId"],
    on_token=lambda delta: print(delta, end="", flush=True),
    on_approval_required=lambda card: client.runs.approve(
        run["runId"], card["approvalId"], "approve"),
    on_fallback=lambda detail: print("回放过期，已降级重拉", detail["status"]),
)
# summary["status"]: succeeded / failed / stopped / resume_expired
```

`stream()` 自动按 `id:` 记 seq，终态前断线带 `Last-Event-ID` 重连（默认 3 次）、只补增量并去重；
收到 `run.error(code=resume_expired)` 时自动 `get(run_id)` 降级，通过 `on_fallback` 交回状态。
需要逐帧自行处理的场景用 `client.runs.iter_events(run_id)`（生成器，产出 `RunEvent(id, event, data)`）。

冻结事件：`run.started / token / tool.status / plan.updated / approval.required /
artifact.created / approval.timed_out / run.done / run.error`。未知事件仍交给 `on_event`。

其余端点：`get(run_id)`、`artifacts(run_id)`、`approve(run_id, approval_id, decision, **fields)`、
`steer(run_id, text)`、`stop(run_id)`。

> 当前版本 `0.1.0`，按项目排期**尚未发布 PyPI**；仓库内用 `pip install -e sdk/python` 即可使用。

## 运行测试

测试基于本地 mock 服务（`tests/mock_server.py`，随机端口起停），无需后端：

```bash
cd sdk/python
pip install -e .          # 或测试自行注入 src 路径，二者皆可
python3 -m unittest discover -s tests -v
```

## 目录结构

```
sdk/python/
├── pyproject.toml
├── README.md
├── src/wenqu/
│   ├── __init__.py               # 导出 WenquClient 与异常
│   ├── client.py                 # Session、鉴权头、超时、错误映射
│   ├── chat.py                   # chat.completions / responses
│   ├── embeddings.py             # embeddings / models
│   ├── retrieval.py              # 知识检索
│   ├── kb.py                     # 知识库管理
│   ├── workflow.py               # 工作流触发
│   ├── sse.py                    # SSE 流解析（含 id: 字段）
│   ├── runs.py                   # v1 run 原语：7 端点 + 自动续传
│   └── langchain_integration.py  # LangChain 可选适配
└── tests/
    ├── mock_server.py
    └── test_*.py
```
