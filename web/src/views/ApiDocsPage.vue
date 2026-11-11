<!--
  对接文档（在线）：API Key 对外集成的完整手册 —— 怎么用 / 有什么能力 / 接哪些。
  内容全部对应后端真实实现（SecurityConfig 白名单∩拒绝名单、ChatController、
  WorkflowApiController、McpServerService 平台级工具集），不写接口上不存在的能力。
  示例地址统一走 publicUrl（本地连线上后端时按 VITE_PROXY_TARGET 推导服务器地址，未设置回落当前 origin，
  与 ApiKeysPanel 同源）：本地起 web 时，文档里的地址必须是真的服务器地址，不能固化成 localhost:5800。
-->
<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">对接文档</h3>
      <span class="head-hint-plain">外部系统接入说明：问答 / 工作流 / MCP</span>
    </div>
    <div class="app-page-body">
      <div class="doc-body">
        <!-- 左栏：章节锚点导航（滚动跟随高亮） -->
        <aside class="doc-nav">
          <button v-for="s in sections" :key="s.id" class="doc-nav-item"
                  :class="{ active: activeSection === s.id }" @click="goSection(s.id)">
            {{ s.label }}
          </button>
          <div class="doc-nav-foot">
            <button class="app-link-btn" @click="router.push('/profile?panel=apikeys')">去创建 / 管理 API Key</button>
          </div>
        </aside>

        <!-- 右栏：正文 -->
        <article class="doc-article">
          <!-- ==================== 1. 概览 ==================== -->
          <section :id="sections[0].id" class="doc-sec">
            <h2>概览：能接什么</h2>
            <p class="doc-lead">
              拿一把 <code>API Key</code>，外部系统就能以<dfn>签发人本人</dfn>的身份使用问渠的问答链路，不需要平台登录令牌。
              对接面共三块，凭据同源（都是个人设置里自签的 Key）：
            </p>
            <div class="doc-cards">
              <div class="doc-card">
                <div class="doc-card-t">问答接口（HTTP + SSE）</div>
                <p>流式问答、多轮会话、附件上传、引用溯源、回答反馈。适合业务系统内嵌"问一下"、批量提问、把回答写回自己的库。</p>
                <code :title="baseUrl + '/api/ai/chat'">POST {{ baseUrl }}/api/ai/chat</code>
              </div>
              <div class="doc-card">
                <div class="doc-card-t">工作流触发（开放 API）</div>
                <p>同步触发已发布的工作流并拿回结构化出参，或事后按 runId 补查运行结果。适合把固定流程当 RPC 调。</p>
                <code :title="baseUrl + '/api/ai/v1/workflows/{id}/run'">POST {{ baseUrl }}/api/ai/v1/workflows/&#123;id&#125;/run</code>
              </div>
              <div class="doc-card">
                <div class="doc-card-t">MCP 入口（平台级）</div>
                <p>一个地址暴露整套元工具（提问 / 检索 / 列库 / 列智能体），供 Claude、Cursor 等 MCP 客户端直接挂载。</p>
                <code :title="mcpUrl">{{ mcpUrl }}</code>
              </div>
              <div class="doc-card">
                <div class="doc-card-t">Embeddings（OpenAI 兼容）</div>
                <p>外部项目借用问渠登记的向量模型做向量化，SDK 零改造切换 endpoint；按 embedding scope 授权、单独记账。</p>
                <code :title="baseUrl + '/api/ai/v1/embeddings'">POST {{ baseUrl }}/api/ai/v1/embeddings</code>
              </div>
              <div class="doc-card">
                <div class="doc-card-t">知识库 API（检索 / 管理）</div>
                <p>把问渠当检索引擎：直接拿混合检索结果做自己的 RAG；建库、上传文档走 kb.write 授权，可见性与网页一致。</p>
                <code :title="baseUrl + '/api/ai/v1/retrieval'">POST {{ baseUrl }}/api/ai/v1/retrieval</code>
              </div>
              <div class="doc-card">
                <div class="doc-card-t">前端嵌入（挂件 / iframe）</div>
                <p>两行 script 在业务页面挂一个浮动聊天入口，或 iframe 直嵌分享页；凭据是分享 token，不接触 API Key。</p>
                <code>WenquChat.init(&#123;token: '…'&#125;)</code>
              </div>
            </div>
            <div class="doc-note">
              准备工作只有一步：在 <b>个人设置 → API 密钥</b> 创建一把 Key（用途名称 + 有效期 + 能力范围 scope）。Key 恒以签发人本人身份发问——开放的是你自己的智能体、知识库与额度；给哪个系统对接，就用那个系统归属的账号签发。
              <p style="margin:8px 0 0">机器可读契约：<code>{{ baseUrl }}/api/ai/v1/openapi.json</code>（OpenAPI 3.1，与服务端鉴权判定同源）。</p>
            </div>
          </section>

          <!-- ==================== 2. 认证与通用约定 ==================== -->
          <section :id="sections[1].id" class="doc-sec">
            <h2>认证与通用约定</h2>
            <h3>地址拼法</h3>
            <p>后端 context-path 为 <code>/ai</code>，完整地址 = <code>&#123;服务地址&#125;/ai + 接口路径</code>。本页示例都按当前访问域名动态拼好：</p>
            <div class="doc-code"><code>{{ baseUrl }}/api/ai/...</code>
              <button class="doc-copy" :title="copied === 'base' ? '已复制' : '复制'" @click="copy('base', baseUrl + '/api/ai/...')">
                <check-outlined v-if="copied === 'base'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <h3>认证头</h3>
            <p>所有 Key 可调的接口都在请求头带 <code>X-Api-Key</code>（MCP 入口除外，它用 <code>Authorization: Bearer</code>）：</p>
            <div class="doc-code"><code>X-Api-Key: sk-xxxxxxxx</code>
              <button class="doc-copy" :title="copied === 'header' ? '已复制' : '复制'" @click="copy('header', 'X-Api-Key: sk-xxxxxxxx')">
                <check-outlined v-if="copied === 'header'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <ul>
              <li>身份恒为<b>签发人本人</b>：不存在代表账号选择，编辑 Key 也不能改绑。可用 <code>GET /api/ai/auth/me</code> 自检当前身份。</li>
              <li>额度、限频、配额都记在签发人名下；Key 停用 / 过期 / 删除后立即失效。</li>
              <li>明文由服务端加密保管，列表点「眼睛」随时回显；怀疑泄露就停用重签。</li>
            </ul>
            <h3>响应包络</h3>
            <p>普通 JSON 接口统一返回：</p>
            <div class="doc-code"><pre class="doc-pre">{ "success": true, "data": … }        // 成功
{ "success": false, "msg": "原因" }   // 业务失败（HTTP 400/403/429 等）</pre></div>
            <p>SSE 接口（<code>/chat</code>）不同：同步校验失败直接返回 HTTP 4xx JSON；流已建立后的错误以 <code>error</code> 事件下发。</p>
          </section>

          <!-- ==================== 3. 问答接口 ==================== -->
          <section :id="sections[2].id" class="doc-sec">
            <h2>问答接口（核心）</h2>
            <p><code>POST {{ baseUrl }}/api/ai/chat</code> · 返回 <code>text/event-stream</code>（SSE 流式）</p>
            <h3>快速示例</h3>
            <div class="doc-code"><pre class="doc-pre">{{ curlChat }}</pre>
              <button class="doc-copy" :title="copied === 'curlChat' ? '已复制' : '复制'" @click="copy('curlChat', curlChat)">
                <check-outlined v-if="copied === 'curlChat'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <h3>请求体字段</h3>
            <table class="doc-table">
              <thead><tr><th style="width:150px">字段</th><th style="width:70px">必填</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td><code>question</code></td><td>是</td><td>问题文本，最长 8000 字</td></tr>
                <tr><td><code>sessionId</code></td><td>否</td><td>续聊传上一次的会话 ID；不传则服务端自动新建。建议先调 <code>POST /api/ai/session/new</code> 拿 <code>sessionId</code>，后续每轮带同一个即多轮上下文</td></tr>
                <tr><td><code>agentId</code></td><td>否</td><td>指定智能体（用 <code>GET /api/ai/agent/available</code> 拉可用清单）；不传用默认助手</td></tr>
                <tr><td><code>model</code></td><td>否</td><td>模型引用（providerId/modelId）；不传沿用个人默认</td></tr>
                <tr><td><code>deepThink</code></td><td>否</td><td>true = 深度思考（先 thinking 流后正文）</td></tr>
                <tr><td><code>planMode</code></td><td>否</td><td>true = 计划模式（先出执行计划，批准后再回答；外部集成一般不用）</td></tr>
                <tr><td><code>reasoningLevel</code></td><td>否</td><td>思考强度 low / medium / high / xhigh / max（须模型登记支持）</td></tr>
                <tr><td><code>images</code></td><td>否</td><td>图片 data URL 数组（≤9 张，单张 ≤10MB），需模型支持读图</td></tr>
                <tr><td><code>attachments</code></td><td>否</td><td>文档附件 <code>[&#123;fileId,name,mime&#125;]</code>（≤5 个）；fileId 来自 <code>POST /api/ai/chat/attachment</code></td></tr>
                <tr><td><code>mentions</code></td><td>否</td><td>@ 引用 <code>[&#123;type,id&#125;]</code>：type = kb（检索收窄）/ doc（内容强制前置）/ agent（本轮委派），≤10 条，服务端校验可见性</td></tr>
                <tr><td><code>historyRefs</code></td><td>否</td><td># 历史引用 <code>[&#123;messageId&#125;]</code>：把本会话历史消息前置进本轮上下文</td></tr>
                <tr><td><code>skills</code></td><td>否</td><td>本轮启用技能名数组</td></tr>
              </tbody>
            </table>
            <h3>SSE 事件流</h3>
            <table class="doc-table">
              <thead><tr><th style="width:150px">事件</th><th>含义</th></tr></thead>
              <tbody>
                <tr><td><code>thinking</code> / <code>thinking_done</code></td><td>深度思考增量 / 思考结束（开了 deepThink 才有）</td></tr>
                <tr><td><code>token</code></td><td>正文文本增量（拼接即完整回答）</td></tr>
                <tr><td><code>image</code></td><td>本轮产出的图片 URL 列表</td></tr>
                <tr><td><code>tool_status</code> / <code>tool_output</code></td><td>工具执行过程（状态变化 / 输出），展示用</td></tr>
                <tr><td><code>plan</code> / <code>todo</code> / <code>retrieved</code> / <code>usage</code> / <code>warn</code></td><td>过程状态事件（计划 / 任务清单 / 检索命中 / 用量 / 降级提示）</td></tr>
                <tr><td><code>done</code></td><td>收尾：引用来源、相关推荐、消息 ID、思考全文、最终内容等（见下）</td></tr>
                <tr><td><code>error</code></td><td>本轮失败原因（流已建立后的错误走这里，不是 HTTP 错误码）</td></tr>
              </tbody>
            </table>
            <h3><code>done</code> 事件关键字段</h3>
            <div class="doc-code"><pre class="doc-pre">{{ doneSample }}</pre>
              <button class="doc-copy" :title="copied === 'done' ? '已复制' : '复制'" @click="copy('done', doneSample)">
                <check-outlined v-if="copied === 'done'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <ul>
              <li><code>sources</code>：引用来源（知识块），块 id 可再调 <code>GET /api/ai/knowledge/&#123;knowledgeId&#125;</code> 做引用溯源拿全文</li>
              <li><code>messageId</code>：本轮回答的消息 ID，提交反馈、# 历史引用都靠它</li>
              <li><code>finalContent</code> / <code>finalImages</code>：权威全文 / 最终图片（与 token 拼接结果一致，以此为准）</li>
              <li>字段随功能演进而增补，解析时按存在性取值，不要假设字段恒在</li>
            </ul>
            <h3>相关端点</h3>
            <ul>
              <li><code>POST /api/ai/chat/attachment</code>：multipart 传 <code>file</code>，返回 <code>&#123;fileId,name,mime,size&#125;</code>（上传与问答同限频）</li>
              <li><code>POST /api/ai/chat/stop</code>：停止本轮（仅本轮发起人可停）</li>
              <li><code>POST /api/ai/feedback</code>：body <code>&#123;messageId, rating(1|0), feedbackText?&#125;</code>，只能评价本人会话内的消息</li>
              <li><code>GET /api/ai/session/&#123;id&#125;</code> / <code>GET /api/ai/session/&#123;id&#125;/events</code>：读会话详情与历史事件（只读自用会话）</li>
            </ul>
          </section>

          <!-- ==================== 4. 能力清单 ==================== -->
          <section :id="sections[3].id" class="doc-sec">
            <h2>能力清单：Key 能接哪些接口</h2>
            <p>Key 的可用面 = <b>问答链路白名单 ∩ 拒绝名单之外</b>。下表是集成方常用端点（前缀均为 <code>{{ baseUrl }}/api/ai</code>）：</p>
            <table class="doc-table">
              <thead><tr><th style="width:100px">分类</th><th style="width:300px">端点</th><th>用途</th></tr></thead>
              <tbody>
                <tr><td rowspan="3">问答</td><td><code>POST /chat</code></td><td>流式问答（SSE），核心入口</td></tr>
                <tr><td><code>POST /chat/attachment</code></td><td>上传问答附件换 fileId</td></tr>
                <tr><td><code>POST /chat/stop</code> · <code>/chat/steer</code></td><td>停止本轮 / 运行中插话</td></tr>
                <tr><td rowspan="4">会话</td><td><code>POST /session/new</code></td><td>新建会话，返回 <code>&#123;sessionId&#125;</code></td></tr>
                <tr><td><code>GET /sessions</code></td><td>会话列表（keyword / cursor / size 分页）</td></tr>
                <tr><td><code>GET /session/&#123;id&#125;</code></td><td>会话详情与消息历史</td></tr>
                <tr><td><code>PUT /session/&#123;id&#125;/pin | favorite | rename</code></td><td>置顶 / 收藏 / 重命名</td></tr>
                <tr><td>溯源与反馈</td><td><code>GET /knowledge/&#123;knowledgeId&#125;</code> · <code>POST /feedback</code></td><td>引用块全文溯源 / 👍👎 反馈</td></tr>
                <tr><td>元信息</td><td><code>GET /agent/available</code> · <code>GET /auth/me</code> · <code>GET /config/public</code></td><td>可用智能体清单 / 身份自检 / 公开运行时配置</td></tr>
                <tr><td>工作流开放 API</td><td><code>POST /v1/workflows/&#123;id&#125;/run</code> · <code>GET /v1/workflows/&#123;id&#125;/run/&#123;runId&#125;</code></td><td>触发已发布工作流 / 补查运行结果（见下节）</td></tr>
                <tr><td rowspan="2">向量与知识库</td><td><code>POST /v1/embeddings</code> · <code>GET /v1/models/embeddings</code></td><td>OpenAI 兼容向量化 / 可用向量模型清单（见 Embeddings 一节）</td></tr>
                <tr><td><code>POST /v1/retrieval</code> · <code>/v1/kbs…</code></td><td>知识检索（只读）/ 知识库管理（写需 kb.write）（见知识库 API 一节）</td></tr>
                <tr><td>MCP 入口</td><td><code>{{ mcpUrl }}</code></td><td>平台级 MCP（凭据是 Bearer，见 MCP 一节）</td></tr>
              </tbody>
            </table>
            <div class="doc-note">
              这张表是"问答链路"的口径：白名单里新增端点会自动对 Key 开放，但<b>能力配置面</b>（技能 / MCP 配置 / 市场等）被拒绝名单挡住——见「权限与 scope」。
            </div>
          </section>

          <!-- ==================== 5. 工作流触发 ==================== -->
          <section :id="sections[4].id" class="doc-sec">
            <h2>工作流触发（开放 API）</h2>
            <p>外部系统持 Key 触发<b>已发布</b>的工作流。路径挂在 <code>/v1/**</code> 下，与内部管理端点分开，便于网关按前缀对外暴露。</p>
            <div class="doc-code"><pre class="doc-pre">{{ curlWorkflow }}</pre>
              <button class="doc-copy" :title="copied === 'curlWf' ? '已复制' : '复制'" @click="copy('curlWf', curlWorkflow)">
                <check-outlined v-if="copied === 'curlWf'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <ul>
              <li>body 为 <code>&#123;"inputs": &#123;…&#125;&#125;</code>（开始节点入参，对应 <code>&#123;&#123;start.key&#123;&#125;&#125;</code>），可整体省略</li>
              <li><b>同步返回</b> run 全量信息：<code>status / outputs（结束节点出参）/ nodeTraces（节点级输入输出与耗时）/ durationMs</code></li>
              <li>失败不抛 500：返回 <code>status=failed</code> 的 run，看 <code>error</code> 与 nodeTraces 定位失败节点</li>
              <li>异步核对：<code>GET /v1/workflows/&#123;id&#125;/run/&#123;runId&#125;</code> 按 runId 补查</li>
              <li>约束：工作流对签发人身份<b>可见</b>才可触发（不可见一律 404，不泄露存在性）；未发布 → 400；含人工审核节点 → 400（外部调用方没有审批交互面）</li>
            </ul>
          </section>

          <!-- ==================== 6. Embeddings ==================== -->
          <section :id="sections[5].id" class="doc-sec">
            <h2>Embeddings（OpenAI 兼容）</h2>
            <p>外部项目用任意 OpenAI SDK 把 base_url 指到问渠，即可借用问渠登记的向量模型做向量化。
              <code>model</code> 为向量模型引用 <code>providerId/modelId</code>（必填——问渠向量按知识库绑定，无全局默认）。</p>
            <div class="doc-code"><pre class="doc-pre">{{ curlEmbeddings }}</pre>
              <button class="doc-copy" :title="copied === 'curlEmb' ? '已复制' : '复制'" @click="copy('curlEmb', curlEmbeddings)">
                <check-outlined v-if="copied === 'curlEmb'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <table class="doc-table">
              <thead><tr><th style="width:150px">字段</th><th style="width:70px">必填</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td><code>input</code></td><td>是</td><td>字符串或字符串数组；单批 ≤256 条、单条 ≤8192 token（超限 400）</td></tr>
                <tr><td><code>model</code></td><td>是</td><td>向量模型引用 <code>providerId/modelId</code>；可用清单：<code>GET /v1/models/embeddings</code></td></tr>
                <tr><td><code>encoding_format</code></td><td>否</td><td><code>float</code>（默认）或 <code>base64</code>（float32 小端打包）</td></tr>
                <tr><td><code>dimensions</code></td><td>否</td><td>向维度裁剪（仅模型支持时生效）</td></tr>
              </tbody>
            </table>
            <ul>
              <li>响应为 OpenAI 标准形状：<code>&#123;object:"list", data:[&#123;object:"embedding", index, embedding&#125;], model, usage&#125;</code>，SDK 零改造切换 endpoint</li>
              <li>需要 Key 开通 <b>embedding</b> scope（默认集内含）；用量按 <code>kind=embedding</code> 单独记账，与聊天分开统计</li>
              <li>限频：默认 60 请求/分钟（每请求可带 256 条文本）；供应商额度不足会返回可行动的报错文案</li>
            </ul>
          </section>

          <!-- ==================== 7. 知识库 API ==================== -->
          <section :id="sections[6].id" class="doc-sec">
            <h2>知识库 API（检索与管理）</h2>
            <p>把问渠当检索引擎用：外部系统可以直接拿混合检索结果做自己的 RAG，也可以程序化维护知识库内容。
              可见性与网页端完全一致——签发人看不到的库与文档，从这里同样拿不到。</p>
            <h3>检索（只读，kb.read）</h3>
            <div class="doc-code"><pre class="doc-pre">{{ curlRetrieval }}</pre>
              <button class="doc-copy" :title="copied === 'curlRt' ? '已复制' : '复制'" @click="copy('curlRt', curlRetrieval)">
                <check-outlined v-if="copied === 'curlRt'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <ul>
              <li>body：<code>query</code>（必填 ≤8000 字）、<code>knowledgeBaseIds</code>（可选，空=本人可见全部库）、<code>topK</code>（1-50，默认 20）、<code>minScore</code>（按融合分过滤）</li>
              <li>返回 <code>&#123;chunks:[&#123;id,text,score,title,titlePath,docId,kbId,chunkIndex,images&#125;], total, warnings?&#125;</code>；不执行重排，链路降级会在 <code>warnings</code> 如实说明</li>
            </ul>
            <h3>库管理（读 kb.read / 写 kb.write）</h3>
            <table class="doc-table">
              <thead><tr><th style="width:300px">端点</th><th style="width:90px">scope</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td><code>GET /v1/kbs</code></td><td>kb.read</td><td>可见库列表（含文档数）</td></tr>
                <tr><td><code>GET /v1/kbs/&#123;id&#125;</code></td><td>kb.read</td><td>库详情（精简视图）</td></tr>
                <tr><td><code>GET /v1/kbs/&#123;id&#125;/documents</code></td><td>kb.read</td><td>库内文档列表（状态 0 生效 / 1 弃用 / 2 解析中 / 3 失败，含进度）</td></tr>
                <tr><td><code>POST /v1/kbs</code></td><td>kb.write</td><td>建库：name + embeddingRef（必填，向量模型引用）</td></tr>
                <tr><td><code>POST /v1/kbs/&#123;id&#125;/documents</code></td><td>kb.write</td><td>multipart 传 <code>file</code>；同步返回文档行，解析走后台队列，轮询上面的列表看进度</td></tr>
              </tbody>
            </table>
            <div class="doc-code"><pre class="doc-pre">{{ curlKbUpload }}</pre>
              <button class="doc-copy" :title="copied === 'curlUp' ? '已复制' : '复制'" @click="copy('curlUp', curlKbUpload)">
                <check-outlined v-if="copied === 'curlUp'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <div class="doc-note">
              <b>kb.write 默认不放开</b>：签发 Key 时不勾选就没有写库能力（403 会点名缺哪个 scope）。
              上传格式与大小闸与网页一致（docx/pdf/xlsx/ppt/pptx/txt/md/csv）。
            </div>
          </section>

          <!-- ==================== 8. 前端嵌入 ==================== -->
          <section :id="sections[7].id" class="doc-sec">
            <h2>前端嵌入（挂件 / iframe）</h2>
            <p>把智能体嵌进业务页面有两条路，凭据都是<b>分享 token</b>（智能体「公开发布」得到），不接触 API Key：</p>
            <h3>方式一：浮动聊天挂件（推荐）</h3>
            <div class="doc-code"><pre class="doc-pre">{{ embedSnippet }}</pre>
              <button class="doc-copy" :title="copied === 'embSn' ? '已复制' : '复制'" @click="copy('embSn', embedSnippet)">
                <check-outlined v-if="copied === 'embSn'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <ul>
              <li>加载器约 3KB、零依赖；浮动按钮点击才懒加载对话面板（宿主不为一个按钮付流量）</li>
              <li>选项：<code>token</code>（必填）/ <code>title</code> / <code>color</code>（品牌色，hex）/ <code>placeholder</code> / <code>position</code>（bottom-right | bottom-left）/ <code>height</code>·<code>width</code> / <code>noTool</code>·<code>compact</code>·<code>autoOpen</code></li>
              <li>程序化控制：<code>WenquChat.init()</code> 返回 <code>&#123;open, close, toggle, destroy&#125;</code></li>
            </ul>
            <h3>方式二：iframe 直嵌</h3>
            <div class="doc-code"><pre class="doc-pre">{{ iframeSnippet }}</pre>
              <button class="doc-copy" :title="copied === 'ifSn' ? '已复制' : '复制'" @click="copy('ifSn', iframeSnippet)">
                <check-outlined v-if="copied === 'ifSn'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <p>嵌入参数（query，仅 <code>embed=1</code> 时生效）：</p>
            <table class="doc-table">
              <thead><tr><th style="width:150px">参数</th><th>效果</th></tr></thead>
              <tbody>
                <tr><td><code>no-tool=1</code></td><td>隐藏输入区工具条（附件/图片入口），纯问答挂件</td></tr>
                <tr><td><code>compact=1</code></td><td>更紧的内边距，适合小尺寸容器</td></tr>
                <tr><td><code>color=#rrggbb</code></td><td>品牌色覆盖（只接受合法 hex，脏值忽略）</td></tr>
                <tr><td><code>placeholder=…</code></td><td>输入框占位提示（≤50 字）</td></tr>
                <tr><td><code>autoOpen=1</code></td><td>加载后自动聚焦输入框</td></tr>
              </tbody>
            </table>
            <div class="doc-note">游客额度闸照常生效：每条分享有每日轮次上限、每访客每日轮次上限与每日 token 上限，超限返回 429。</div>
          </section>

          <!-- ==================== 9. MCP 接入 ==================== -->
          <section :id="sections[8].id" class="doc-sec">
            <h2>MCP 接入</h2>
            <p>平台级 MCP 入口把问渠能力暴露为标准 MCP Server（Streamable HTTP），Claude / Cursor 等客户端粘上就能用。凭据不是 <code>X-Api-Key</code>，而是 <code>Authorization: Bearer</code> 头。</p>
            <div class="doc-usage-label">接入点地址</div>
            <div class="doc-code"><code>{{ mcpUrl }}</code>
              <button class="doc-copy" :title="copied === 'mcpUrl' ? '已复制' : '复制'" @click="copy('mcpUrl', mcpUrl)">
                <check-outlined v-if="copied === 'mcpUrl'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <div class="doc-usage-label">mcp.json（粘进 Cursor 的 .cursor/mcp.json 或 Claude Desktop 配置）</div>
            <div class="doc-code"><pre class="doc-pre">{{ mcpJson }}</pre>
              <button class="doc-copy" :title="copied === 'mcpJson' ? '已复制' : '复制'" @click="copy('mcpJson', mcpJson)">
                <check-outlined v-if="copied === 'mcpJson'" class="doc-copy-ok" /><copy-outlined v-else />
              </button>
            </div>
            <h3>暴露的工具（固定元工具集）</h3>
            <table class="doc-table">
              <thead><tr><th style="width:260px">工具</th><th>说明</th></tr></thead>
              <tbody>
                <tr><td><code>wenqu_ask</code></td><td>跑一次完整问答拿完整回答。参数：<code>question</code>（必填）、<code>agentId</code>（可选）、<code>sessionId</code>（可选，传入续接上下文）</td></tr>
                <tr><td><code>wenqu_search_knowledge</code></td><td>在可见知识库里检索片段。参数：<code>query</code>（必填）、<code>topK</code>（1~5，默认 5）、<code>kbId</code>（可选限定单库）</td></tr>
                <tr><td><code>wenqu_list_knowledge_bases</code></td><td>列出调用者可见的知识库 id 与名称</td></tr>
                <tr><td><code>wenqu_list_agents</code></td><td>列出可用智能体 id / 名称 / 描述（<code>wenqu_ask</code> 的 agentId 从这里取）</td></tr>
              </tbody>
            </table>
            <h3>启用条件（缺一不可）</h3>
            <ul>
              <li>该 Key 在个人设置里<b>打开了「MCP」开关</b>（授权 / 收回这个入口是每个 Key 独立的一列）</li>
              <li>管理员在「系统设置 → MCP 服务（双向）」开启<b>「对外提供 MCP 端点」</b>总开关</li>
              <li>对外域名访问（非 localhost）时，还须把前端域名填进「MCP 服务（双向）→ 允许的 Origin」，否则浏览器型客户端被 403 拒绝（Claude / Cursor 这类本地进程不带 Origin，不受影响）</li>
              <li>可见范围与 Key 创建者在网页上看到的完全一致</li>
            </ul>
            <div class="doc-note">
              想让某一个智能体单独对外，用它的「公开发布 → MCP 端点」：<code>{{ baseUrl }}/mcp/&#123;token&#125;</code>。那是另一个地址，凭据是分享 token 而不是 API Key。
            </div>
          </section>

          <!-- ==================== 10. 权限与 scope ==================== -->
          <section :id="sections[9].id" class="doc-sec">
            <h2>权限与 scope：Key 能碰什么、不能碰什么</h2>
            <h3>能力范围（scope）</h3>
            <p>每把 Key 有一份能力范围，签发时勾选、之后可在「个人设置 → API 密钥」编辑。
              scope 只约束 Key 通道（登录态不受约束）；收紧<b>立即生效</b>——缺对应 scope 的调用返回 403 并点名缺哪一项。</p>
            <table class="doc-table">
              <thead><tr><th style="width:130px">scope</th><th style="width:110px">默认开通</th><th>覆盖能力</th></tr></thead>
              <tbody>
                <tr><td><code>chat</code></td><td>是</td><td>问答（/chat、OpenAI 兼容端点、Responses）</td></tr>
                <tr><td><code>embedding</code></td><td>是</td><td>向量化（/v1/embeddings）</td></tr>
                <tr><td><code>kb.read</code></td><td>是</td><td>知识库检索与只读（/v1/retrieval、/v1/kbs 读侧）</td></tr>
                <tr><td><code>kb.write</code></td><td><b>否</b></td><td>建库与上传文档（/v1/kbs 写侧）——只在确实需要时勾选</td></tr>
                <tr><td><code>workflow.run</code></td><td>是</td><td>触发已发布工作流（/v1/workflows）</td></tr>
              </tbody>
            </table>
            <h3>硬性拒绝的端点</h3>
            <p>Key 只开放<b>问答链路</b>。以下是服务端硬性拒绝（HTTP 403）的能力配置面，带 Key 调用会当场得到明确报错，不会静默走到别的鉴权层：</p>
            <table class="doc-table">
              <thead><tr><th style="width:320px">端点前缀</th><th>为什么不给</th></tr></thead>
              <tbody>
                <tr><td><code>/api/ai/api-key/**</code></td><td>Key 管理——否则泄露的 Key 能给自己签发续命、改绑定换身份</td></tr>
                <tr><td><code>/api/ai/user/**</code></td><td>改密 / 资料 / 偏好 / 个人设置——一把贴在配置文件里的密钥不该拥有账号自服务能力</td></tr>
                <tr><td><code>/api/ai/auth/**</code></td><td>登录鉴权，凭据语义不同（唯一例外：<code>GET /auth/me</code> 只读身份自检）</td></tr>
                <tr><td><code>/api/ai/skill/**</code> · <code>/api/ai/mcp/**</code> · <code>/api/ai/market/**</code> · <code>/api/ai/scheduled/**</code> · <code>/api/ai/credential/**</code></td><td>能力配置面：技能 / MCP 配置 / 市场 / 定时任务 / 工作流凭据的增删改——这些在界面上做，不从 Key 走</td></tr>
                <tr><td><code>/api/ai/session-shares/**</code></td><td>分享管理</td></tr>
                <tr><td><code>DELETE /api/ai/session(s)…</code></td><td>会话可以建、可以读、可以问，但不允许 Key 删历史（保留审计线索）</td></tr>
                <tr><td><code>/api/ai/agent/&#123;id&#125;/publish · /share</code></td><td>智能体的发布 / 分享配置</td></tr>
              </tbody>
            </table>
            <p>此外，白名单之外的一切端点对 Key 一律 403（即使代表账号在界面上是管理员——Key 的 API 面恒为问答白名单，不受代表账号角色影响）。</p>
            <h3>集成方守则</h3>
            <ul>
              <li>一把 Key 一个用途，别多系统共用；不用了先「停用」而不是删除——停用保留审计线索，删除记录即消失</li>
              <li>Key 等于签发人的问答身份与额度，按敏感凭据保管（配置文件 / 密钥管理服务），不要进前端代码或日志</li>
              <li>轮询调用注意限频：问答默认 10 次/分钟（管理员可调），超限返回 429</li>
            </ul>
          </section>

          <!-- ==================== 11. 错误码 ==================== -->
          <section :id="sections[10].id" class="doc-sec">
            <h2>错误码与限频</h2>
            <table class="doc-table">
              <thead><tr><th style="width:110px">状态码</th><th>含义与处理</th></tr></thead>
              <tbody>
                <tr><td><code>400</code></td><td>参数或业务校验失败（超字数 / 附件过期 / 引用不可见 / 工作流未发布…），<code>msg</code> 有明确原因</td></tr>
                <tr><td><code>401</code></td><td>未认证：没带凭据或登录门禁拦下。检查 <code>X-Api-Key</code> 是否带对</td></tr>
                <tr><td><code>403</code></td><td>三种可能：Key 无效；端点在拒绝名单（文案会点名"Key 只开放问答链路"）；端点不在问答白名单。都改不了——换界面对应的操作路径</td></tr>
                <tr><td><code>404</code></td><td>资源不存在或<b>不在你可见范围</b>（工作流 / 会话等按不存在处理，不泄露存在性）</td></tr>
                <tr><td><code>429</code></td><td>限频：问答默认 10 次/分钟、上传同桶限频，窗口 60 秒。退避重试或联系管理员调 <code>ratelimit.chatPerMinute</code></td></tr>
              </tbody>
            </table>
            <p>额度维度：每轮问答计入签发人的用量与配额，配额到顶会在请求入口直接拒绝并说明恢复时间。</p>
          </section>

          <footer class="doc-foot">接口行为以服务端实现为准；本页与后端同步维护（安全白名单：SecurityConfig · 问答：ChatController · 工作流开放 API：WorkflowApiController · 向量：OpenAiEmbeddingsController · 知识库对外：KbApiController · MCP：McpServerService）</footer>
        </article>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { publicUrl } from '../utils/publicUrl'
import { CheckOutlined, CopyOutlined } from '@ant-design/icons-vue'
import { writeClipboard } from '../utils/clipboard'

const router = useRouter()

const sections = [
  { id: 'doc-overview', label: '概览：能接什么' },
  { id: 'doc-auth', label: '认证与通用约定' },
  { id: 'doc-chat', label: '问答接口（SSE）' },
  { id: 'doc-endpoints', label: '能力清单' },
  { id: 'doc-workflow', label: '工作流触发' },
  { id: 'doc-embeddings', label: 'Embeddings' },
  { id: 'doc-kb', label: '知识库 API' },
  { id: 'doc-embed-widget', label: '前端嵌入' },
  { id: 'doc-mcp', label: 'MCP 接入' },
  { id: 'doc-boundary', label: '权限与 scope' },
  { id: 'doc-errors', label: '错误码与限频' }
]

/** 地址动态拼法与 ApiKeysPanel 同源：基址走 publicUrl（按 VITE_PROXY_TARGET 推导，未设置回落当前 origin） */
const baseUrl = computed(() => publicUrl('/ai'))
const mcpUrl = computed(() => publicUrl('/ai/mcp'))
const mcpJson = computed(() => JSON.stringify({
  mcpServers: { wenqu: { url: mcpUrl.value, headers: { Authorization: 'Bearer sk-你的Key' } } }
}, null, 2))

const curlChat = computed(() => `curl -N -X POST ${baseUrl.value}/api/ai/chat \\
  -H "Content-Type: application/json" \\
  -H "X-Api-Key: sk-你的Key" \\
  -d '{"question":"如何创建评分组件？","agentId":"<智能体id>"}'`)
const curlWorkflow = computed(() => `curl -X POST ${baseUrl.value}/api/ai/v1/workflows/<工作流id>/run \\
  -H "Content-Type: application/json" \\
  -H "X-Api-Key: sk-你的Key" \\
  -d '{"inputs":{"city":"杭州"}}'`)
const curlEmbeddings = computed(() => `curl -X POST ${baseUrl.value}/api/ai/v1/embeddings \\
  -H "Content-Type: application/json" \\
  -H "X-Api-Key: sk-你的Key" \\
  -d '{"input":["你好，世界"],"model":"<providerId>/<modelId>"}'`)
const curlRetrieval = computed(() => `curl -X POST ${baseUrl.value}/api/ai/v1/retrieval \\
  -H "Content-Type: application/json" \\
  -H "X-Api-Key: sk-你的Key" \\
  -d '{"query":"报销流程","knowledgeBaseIds":["<kbId>"],"topK":5}'`)
const curlKbUpload = computed(() => `curl -X POST ${baseUrl.value}/api/ai/v1/kbs/<kbId>/documents \\
  -H "X-Api-Key: sk-你的Key" \\
  -F "file=@员工手册.docx"`)
// SFC 的 script 段以字面量 </scr+ipt> 收尾，示例代码里要出现它只能拼接，不能写死（也无需反斜杠转义——模板字面量里 \/ 不是合法转义）
const S_CLOSE = '</' + 'script>'
const embedSnippet = computed(() => `<script src="${publicUrl('/wenqu-embed.js')}">${S_CLOSE}
<script>
  WenquChat.init({
    token: '<分享token>',        // 公开发布链接 /s/ 后面那段
    title: '在线咨询',
    color: '#3b82f6',
    placeholder: '问我任何问题',
    position: 'bottom-right'    // 或 bottom-left
  })
${S_CLOSE}`)
const iframeSnippet = computed(() => `<iframe src="${publicUrl('/s')}/<分享token>?embed=1&color=%233b82f6&placeholder=%E4%BD%A0%E6%83%B3%E9%97%AE%E4%BB%80%E4%B9%88"
        width="420" height="640" style="border:0;border-radius:12px"></iframe>`)
const doneSample = `event: done
data: {
  "sources": [...],        // 引用来源（知识块，id 可用于引用溯源）
  "related": [...],        // 相关推荐问题
  "messageId": "...",      // 本轮回答消息 ID（反馈 / 历史引用用）
  "turnId": "...",         // 本轮身份
  "finalContent": "...",   // 权威全文（与 token 拼接一致，以此为准）
  "finalImages": [...],
  "toolCalls": [...],
  "artifacts": [...],
  "agentId": "...", "agentName": "..."
}`

const copied = ref('')
const copy = async (k, text) => {
  copied.value = (await writeClipboard(text)) ? k : ''
}

// 章节滚动跟随：进视口比例最高的章节高亮（与 HelpCenter 的选中形态一致）
const activeSection = ref(sections[0].id)
let observer = null
onMounted(() => {
  observer = new IntersectionObserver(entries => {
    entries.forEach(en => {
      if (en.isIntersecting) activeSection.value = en.target.id
    })
  }, { rootMargin: '-15% 0px -70% 0px' })
  sections.forEach(s => {
    const el = document.getElementById(s.id)
    if (el) observer.observe(el)
  })
})
onBeforeUnmount(() => observer && observer.disconnect())
const goSection = id => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
</script>

<style scoped>
/* 布局：左锚点导航 + 右正文（与 HelpCenter 同形态，代码块样式沿用 API 面板的 key-code 形态） */
.doc-body { display: grid; grid-template-columns: 220px 1fr; gap: 14px; align-items: start; }
.doc-nav {
  position: sticky; top: 12px;
  display: flex; flex-direction: column; gap: 2px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  padding: 10px; max-height: calc(100vh - 120px); overflow-y: auto;
}
.doc-nav-item {
  display: block; width: 100%; text-align: left;
  background: transparent; border: none; border-radius: 8px; cursor: pointer;
  color: var(--app-text2); font-size: 13px; padding: 7px 9px;
}
.doc-nav-item:hover { background: var(--app-panel-2); color: var(--app-text); }
.doc-nav-item.active { background: var(--app-accent-weak, var(--app-panel-2)); color: var(--app-accent); font-weight: 500; }
.doc-nav-foot { margin-top: 8px; padding: 8px 9px 2px; border-top: 1px solid var(--app-border); font-size: 12px; }

.doc-article {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  padding: 22px 26px; min-width: 0; max-width: 920px; scroll-behavior: smooth;
}
.doc-sec { padding-bottom: 8px; margin-bottom: 18px; border-bottom: 1px solid var(--app-border); scroll-margin-top: 12px; }
.doc-sec:last-of-type { border-bottom: none; }
.doc-sec h2 { font-size: 18px; margin: 6px 0 12px; color: var(--app-text); }
.doc-sec h3 { font-size: 14px; margin: 16px 0 8px; color: var(--app-text); }
.doc-sec p, .doc-sec li { font-size: 13px; color: var(--app-text2); line-height: 1.9; }
.doc-lead { margin-top: 0; }
.doc-sec dfn { font-style: normal; color: var(--app-text); font-weight: 500; }
.doc-sec code {
  background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 4px;
  padding: 1px 5px; font-size: 12px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--app-text); word-break: break-all;
}
.doc-sec ul { padding-left: 20px; margin: 8px 0; }

/* 三张能力卡：flex 纵排让底部代码条压到卡片底沿——三张卡文字行数不同，代码条也齐着。
   轨道必须 minmax(0,1fr)：1fr 的默认最小宽度是内容 min-content，卡片里 nowrap 的长 URL
   会把整行撑出文章面板（截图里右缘溢出就是它），压到 0 后由卡片内部 ellipsis 截断。 */
.doc-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 12px 0; }
.doc-card {
  border: 1px solid var(--app-border); border-radius: 8px; padding: 12px 14px; background: var(--app-panel-2);
  display: flex; flex-direction: column; min-width: 0;
}
.doc-card-t { font-size: 13px; font-weight: 600; color: var(--app-text); margin-bottom: 6px; }
.doc-card p { font-size: 12px; line-height: 1.7; margin: 0 0 8px; }
.doc-card code { display: block; margin-top: auto; font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: default; }

/* 提示条 */
.doc-note {
  background: var(--app-accent-weak, var(--app-panel-2)); border-radius: 8px;
  padding: 10px 12px; font-size: 12.5px; color: var(--app-text2); line-height: 1.8; margin: 12px 0;
}
.doc-note b { color: var(--app-text); }

/* 代码块 + 复制按钮（形态与 ApiKeysPanel 的 key-code 一致） */
.doc-code {
  position: relative; background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 8px; padding: 10px 36px 10px 12px; margin: 8px 0 12px; overflow-x: auto;
}
.doc-code > code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text); border: none; background: transparent; padding: 0; }
.doc-pre {
  margin: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
  line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text);
}
.doc-copy {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border: none; border-radius: 5px;
  background: transparent; color: var(--app-text3); cursor: pointer; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
}
.doc-copy:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.doc-copy-ok { color: var(--app-ok); }
.doc-usage-label { font-size: 12px; color: var(--app-text2); margin: 12px 0 4px; }

/* 表格 */
.doc-table { border-collapse: collapse; width: 100%; margin: 10px 0 14px; font-size: 12.5px; }
.doc-table th, .doc-table td { border: 1px solid var(--app-border); padding: 7px 10px; text-align: left; vertical-align: top; line-height: 1.7; }
.doc-table th { background: var(--app-panel-2); color: var(--app-text); font-weight: 500; }
.doc-table td { color: var(--app-text2); }
.doc-table td code { font-size: 11.5px; }

.doc-foot { margin-top: 6px; font-size: 11.5px; color: var(--app-text3); }

/* 窄屏：导航收为顶部横排 */
@media (max-width: 900px) {
  .doc-body { grid-template-columns: 1fr; }
  .doc-nav { position: static; flex-direction: row; flex-wrap: wrap; max-height: none; }
  .doc-nav-foot { border-top: none; margin: 0; padding: 0 0 0 6px; }
  .doc-cards { grid-template-columns: 1fr; }
}
</style>
