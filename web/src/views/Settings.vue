<template>
  <div>
    <a-alert type="info" show-icon style="margin-bottom:16px"
             message="问答/视觉/向量三类模型均支持跨厂商热切换：修改网关地址/API Key/模型名（预设覆盖 DeepSeek、智谱GLM、百炼Qwen、Kimi、豆包、混元、千帆、MiniMax、SiliconFlow、Ollama 等 OpenAI 兼容端点），保存即生效免重启，API Key 以 RSA 加密入库。其中向量模型切换会先探测新配置（失败拒绝保存），通过后自动全量重嵌入并在下方展示进度。鼠标悬停参数名旁的 ? 可查看说明。" />

    <!-- 分区锚点：点击展开并平滑定位到对应配置分组 -->
    <div class="cfg-anchor">
      <template v-for="a in anchors" :key="a.key">
        <a :class="{ 'anchor-active': currentAnchor === a.key }" href="javascript:void(0)" @click="jumpTo(a.key)">{{ a.label }}</a>
      </template>
    </div>

    <!-- 未保存改动提示（差异感知：避免"以为保存了其实没有"） -->
    <div v-if="dirtyCount" class="dirty-tip">
      <warning-outlined style="color:#d48806" /> 有 {{ dirtyCount }} 组配置已修改未保存，点击右下角「保存配置（{{ dirtyCount }} 组改动）」生效
    </div>

    <a-spin :spinning="loading">
      <a-collapse v-model:activeKey="activeKeys" :bordered="false" class="cfg-collapse">

        <a-collapse-panel key="chat" header="智能问答模型" :id="'cfg-anchor-chat'">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='chat'" @click.stop="onResetGroup('chat')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <div class="cfg-sub">模型与连接（厂商预设自动填充地址与补全路径）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.chatModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.chat.model" placeholder="如 deepseek-chat / glm-4.5 / qwen-plus，与所选厂商一致" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.chatPreset" placement="top">厂商预设 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-select v-model:value="chatPreset" style="width:360px" :options="chatPresetOptions"
                        placeholder="选择厂商自动填充网关地址与补全路径" @change="onChatPresetChange" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.chatBaseUrl" placement="top">网关地址 Base URL <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.chat.baseUrl" style="width:420px"
                        placeholder="如 https://api.deepseek.com；…/v1、…/v4 等版本尾缀或完整端点也能自动识别" />
              <a-button size="small" style="margin-left:8px" :loading="probeStates.chat.loading"
                        @click="doProbe('chat')">测试连接</a-button>
              <a-tooltip v-if="probeStates.chat.result" :title="probeStates.chat.result.detail">
                <span class="probe-chip" :class="probeStates.chat.result.available ? 'probe-ok' : 'probe-bad'">
                  {{ probeStates.chat.result.available ? '可达' : '不可达' }} {{ probeStates.chat.result.latencyMs }}ms
                </span>
              </a-tooltip>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.chatCompletionsPath" placement="top">补全路径 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.chat.completionsPath" style="width:420px"
                        placeholder="默认 /v1/chat/completions；智谱 /v4、方舟 /v3、千帆 /v2（留空自动识别）" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.chatApiKey" placement="top">API Key <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-password v-model:value="form.chat.apiKey" style="width:420px"
                        placeholder="未修改时显示 ****掩码，无需重新输入（RSA 加密入库）" />
            </a-form-item>
            <div class="cfg-sub">回答行为与内容</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.temperature" placement="top">温度 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.temperature" :min="0" :max="2" :step="0.1" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.systemPrompt" placement="top">System Prompt <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-textarea v-model:value="form.chat.systemPrompt" :rows="4"
                          placeholder="AI 助手的角色与回答风格（引用/图片/追问规则由系统固定，不可修改）" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.suggestedQuestions" placement="top">推荐问题池 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-textarea v-model:value="form.chat.suggestedQuestions" :rows="4"
                          placeholder="每行一个问题，欢迎页展示前 8 条（数据看板热门问题也可一键加入）" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.citationCheck" placement="top">引用一致性自检 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.chat.citationCheckEnabled" />
              <span style="margin-left:12px;color:#999;font-size:12px">
                生成后校验每条 [N] 引用是否被引用内容支撑，剔除语义不符的引用并重编编号（增加一次校验调用延迟）
              </span>
            </a-form-item>
            <div class="cfg-sub">记忆与上下文</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.historyRounds" placement="top">多轮记忆轮数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.historyRounds" :min="0" :max="20" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.remainTokenFloor" placement="top">上下文保留下限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.remainTokenFloor" :min="0" :step="100" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.truncateFallbackChars" placement="top">截断兜底字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.truncateFallbackChars" :min="0" :step="50" style="width:200px" />
            </a-form-item>
            <div class="cfg-sub">并发与超时</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.pipelineThreads" placement="top">问答流水线线程 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.pipelineThreads" :min="2" :max="64" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">并发问答重活线程，保存即生效</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.streamRetryCount" placement="top">流式中断重试 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.streamRetryCount" :min="0" :max="5" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">未输出内容时自动重试次数，0=关闭</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.sseTimeoutMs" placement="top">回答超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chat.sseTimeoutMs" :min="60000" :step="30000" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">SSE 超时截断并提示，默认 300000</span>
            </a-form-item>
            <div class="cfg-sub">调试开关（排障用，生产建议仅开引用自检）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.retrievalDebugEnabled" placement="top"><span style="display:inline-flex;align-items:center;gap:4px">检索调试入口 <a-tag color="warning" size="small" style="margin-left:2px">调试</a-tag> <question-circle-outlined class="tip-icon" /></span></a-tooltip></template>
              <a-switch v-model:checked="form.chat.retrievalDebugEnabled" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.showDebugDegradations" placement="top"><span style="display:inline-flex;align-items:center;gap:4px">降级提示 <a-tag color="warning" size="small" style="margin-left:2px">调试</a-tag> <question-circle-outlined class="tip-icon" /></span></a-tooltip></template>
              <a-switch v-model:checked="form.chat.showDebugDegradations" />
              <span style="margin-left:12px;color:#999;font-size:12px">
                默认关闭：回答下方不显示任何降级提示（无命中/改写失败/图片剔除/缓存命中等）；调试排障时开启可见全部原因
              </span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.judgeEnabled" placement="top"><span style="display:inline-flex;align-items:center;gap:4px">体检 LLM 评判 <a-tag color="warning" size="small" style="margin-left:2px">调试</a-tag> <question-circle-outlined class="tip-icon" /></span></a-tooltip></template>
              <a-switch v-model:checked="form.eval.judgeEnabled" />
              <span style="margin-left:12px;color:#999;font-size:12px">
                自动体检时对每个 case 判"命中资料是否足以直接回答"，产出 judgeScore（每 case 一次调用，增加体检耗时）
              </span>
            </a-form-item>
          </a-form>
        </a-collapse-panel>

        <a-collapse-panel key="vision" :id="'cfg-anchor-vision'" header="视觉模型（图片识别）">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='vision'" @click.stop="onResetGroup('vision')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionEnabled" placement="top">启用图片描述 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.vision.enabled" />
              <span v-if="!form.vision.enabled" style="margin-left:12px;color:#cf1322;font-size:12px">
                ⚠ 关闭后文档图片/用户图片不生成描述：图片仅展示、内容不进入检索（RAG 对图片语义失效）
              </span>
            </a-form-item>
            <div class="cfg-sub">模型与连接（网关地址 / 密钥）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.vision.model" placeholder="如 qwen3-vl:2b" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionBaseUrl" placement="top">网关地址 Base URL <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.vision.baseUrl" style="width:420px"
                        placeholder="如 http://localhost:11434（Ollama）或 https://open.bigmodel.cn/api/paas" />
              <a-button size="small" style="margin-left:8px" :loading="probeStates.vision.loading"
                        @click="doProbe('vision')">测试连接</a-button>
              <a-tooltip v-if="probeStates.vision.result" :title="probeStates.vision.result.detail">
                <span class="probe-chip" :class="probeStates.vision.result.available ? 'probe-ok' : 'probe-bad'">
                  {{ probeStates.vision.result.available ? '可达' : '不可达' }} {{ probeStates.vision.result.latencyMs }}ms
                </span>
              </a-tooltip>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionApiKey" placement="top">API Key <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-password v-model:value="form.vision.apiKey" style="width:420px"
                        placeholder="未修改时显示 ****掩码（Ollama 无需 Key 可留空；RSA 加密入库）" />
            </a-form-item>
            <div class="cfg-sub">识别提示词与并发（文档图与用户传图分开控制）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionPrompt" placement="top">识别提示词 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-textarea v-model:value="form.vision.prompt" :rows="3"
                          placeholder="图片描述提示词（50字内描述界面/元素）" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.visionConcurrency" placement="top">图片描述并发 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.vision.concurrency" :min="1" :max="16" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.userImageConcurrency" placement="top">用户图片并发 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.vision.userImageConcurrency" :min="1" :max="16" style="width:200px" />
            </a-form-item>
          </a-form>
        </a-collapse-panel>

        <a-collapse-panel key="chunk" :id="'cfg-anchor-chunk'" header="文档解析（上传上限/分块/图片）">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='chunk'" @click.stop="onResetGroup('chunk')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <div class="cfg-sub">上传与单文档保护（超限截断入库）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.uploadMaxSize" placement="top">上传大小上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.upload.maxFileSizeMB" :min="1" :max="1024" :step="50" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">MB，保存即生效</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.maxChunks" placement="top">最大知识块数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.maxChunks" :min="0" :step="500" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">0=不限制</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.maxImages" placement="top">最多提取图片 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.maxImages" :min="0" :step="20" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">0=不限制</span>
            </a-form-item>
            <div class="cfg-sub">分块与解析行为（需重新解析/新上传文档生效）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.overlap" placement="top">分块重叠字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.overlap" :min="0" :step="20" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">0=关闭，需重解析生效</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.parseConcurrency" placement="top">解析并发数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.parse.concurrency" :min="1" :max="8" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.embedRetryCount" placement="top">向量化重试 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.parse.embedRetryCount" :min="0" :max="5" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">向量化批次失败自动重试次数，0=不重试</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.ocrMinText" placement="top">PDF 扫描件阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.parse.ocrMinText" :min="0" :step="5" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">页文本少于该长度触发 OCR</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.chunkStructural" placement="top">结构感知切分 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.chunk.structural" />
              <span style="margin-left:12px;color:#999;font-size:12px">标题/段落边界优先 + 章节路径注入，需重解析生效</span>
            </a-form-item>
            <a-form-item v-if="form.chunk.structural">
              <template #label><a-tooltip :title="tips.chunkStructuralRatio" placement="top">边界阈值比例 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.chunk.structuralRatio" :min="0.5" :max="1" :step="0.05" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">达到 maxSize×比例 时优先在段落边界断块</span>
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="上传大小上限保存即生效（新上传按新限制校验）；分块/图片上限对超大文档保护：知识块数超上限截断入库，图片数超上限不再提取描述。分块重叠与分块/图片上限均只对重新解析/新上传文档生效。" />
        </a-collapse-panel>

        <a-collapse-panel key="embedding" :id="'cfg-anchor-embedding'" header="向量模型（Embedding，切换需全量重嵌入）">
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <div class="cfg-sub">模型与连接（网关地址 / 向量化路径 / 密钥）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.embeddingModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.embedding.model" style="width:420px"
                        placeholder="如 text-embedding-v4 / embedding-3 / bge-m3，与所选厂商一致" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.embeddingBaseUrl" placement="top">网关地址 Base URL <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.embedding.baseUrl" style="width:420px"
                        placeholder="OpenAI 兼容网关；…/v1、…/v4 等版本尾缀自动识别" />
              <a-button size="small" style="margin-left:8px" :loading="probeStates.embedding.loading"
                        @click="doProbe('embedding')">测试连接</a-button>
              <a-tooltip v-if="probeStates.embedding.result" :title="probeStates.embedding.result.detail">
                <span class="probe-chip" :class="probeStates.embedding.result.available ? 'probe-ok' : 'probe-bad'">
                  {{ probeStates.embedding.result.available ? '可达' : '不可达' }} {{ probeStates.embedding.result.latencyMs }}ms
                </span>
              </a-tooltip>
              <span style="margin-left:8px;color:#999;font-size:12px">保存会触发全量重嵌入，建议先测连通</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.embeddingPath" placement="top">向量化路径 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.embedding.embeddingsPath" style="width:420px"
                        placeholder="默认 /v1/embeddings；智谱 /v4/embeddings、千帆 /v2/embeddings（留空自动识别）" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.embeddingApiKey" placement="top">API Key <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-password v-model:value="form.embedding.apiKey" style="width:420px"
                        placeholder="未修改时显示 ****掩码，无需重新输入（RSA 加密入库）" />
            </a-form-item>
            <div class="cfg-sub">索引状态与重嵌入（切换模型后自动触发）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.embeddingDimensions" placement="top">当前索引维度 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <span v-if="embeddingDimensions" style="color:#555">{{ embeddingDimensions }} 维</span>
              <span v-else style="color:#999">未记录（尚未切换过向量模型；首次重嵌入完成后自动记录）</span>
            </a-form-item>
            <a-form-item label="重嵌入状态">
              <div>
                <span v-if="reembed.status === 'running'" style="color:#1677ff">
                  进行中：{{ reembed.done }} / {{ reembed.total }} 块
                  <span v-if="reembed.failed" style="color:#cf1322">（失败 {{ reembed.failed }}）</span>
                </span>
                <span v-else-if="reembed.status === 'done'" style="color:#389e0d">
                  已完成：{{ reembed.done }} 块<span v-if="reembed.failed" style="color:#cf1322">（失败 {{ reembed.failed }}，可重试补齐）</span>
                </span>
                <span v-else-if="reembed.status === 'failed'" style="color:#cf1322">
                  失败：{{ reembed.error }}（已完成 {{ reembed.done }} 块，可重试）
                </span>
                <span v-else style="color:#999">未运行</span>
                <a-button size="small" style="margin-left:12px" :loading="reembedTriggering" @click="doTriggerReembed">
                  手动重嵌入
                </a-button>
                <a-button size="small" style="margin-left:8px" @click="refreshReembedStatus">刷新</a-button>
              </div>
              <!-- 维度变化 / 耗时 / 索引对账：任务跑过才有意义 -->
              <div v-if="reembed.status !== 'idle'" style="margin-top:6px;color:#999;font-size:12px;line-height:1.8">
                <span v-if="reembed.newDim">
                  维度：{{ reembed.oldDim || '未知' }} → {{ reembed.newDim }}
                  <span v-if="reembed.oldDim && reembed.oldDim !== reembed.newDim" style="color:#d46b08">（维度已变，索引 schema 已按新维度重建）</span>
                </span>
                <span v-if="reembedElapsed" style="margin-left:12px">耗时 {{ reembedElapsed }}</span>
                <!-- 对账：索引内实际块数少于成功写入数 = 有丢块（DROP 与并发解析撞车），需再跑一次补齐 -->
                <span v-if="reembed.indexed" style="margin-left:12px">
                  索引内 {{ reembed.indexed }} 块
                  <span v-if="reembed.status === 'done' && reembed.indexed < reembed.done" style="color:#cf1322">
                    ⚠ 少于成功写入 {{ reembed.done }} 块（疑与并发解析撞车丢块，建议解析空闲时再跑一次）
                  </span>
                </span>
              </div>
            </a-form-item>
          </a-form>
          <a-alert type="warning" show-icon style="margin:0 24px 16px"
                   message="向量模型热切换说明：不同模型的向量在数学上不可迁移（维度/语义空间均不同）。保存时会先探测新配置并校验维度合法（探测失败或维度非法一律拒绝保存，旧索引保持完整）；通过后自动按新维度重建向量索引并后台全量重嵌入（无需重新上传文档，MySQL 知识块不动）。任务开始即清空语义缓存——旧模型的问题向量已作废，留着可能命中语义无关的历史回答。重嵌入期间向量检索自动降级关键词路，服务不中断。完成后请核对上方「索引内块数」与成功写入块数是否一致，并抽查几个问题验证召回质量。" />
        </a-collapse-panel>

        <a-collapse-panel key="retrieval" :id="'cfg-anchor-retrieval'" header="检索设置（混合检索权重 + 重排 + 关键词引擎）">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='retrieval'" @click.stop="onResetGroup('retrieval')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <div class="cfg-sub">关键词引擎（类型与服务连接）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordEngine" placement="top">关键词引擎 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-select v-model:value="form.keyword.engine" style="width:220px" :options="[
                { value: 'mysql', label: 'mysql（LIKE，零依赖，库大时慢）' },
                { value: 'meilisearch', label: 'meilisearch（中文分词+相关度，推荐）' }
              ]" @change="onKeywordEngineChange" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordBaseUrl" placement="top">引擎服务地址 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.keyword.baseUrl" placeholder="http://localhost:7700" style="width:320px" />
              <a-button size="small" style="margin-left:8px" :loading="probeStates.keyword.loading"
                        @click="doProbe('keyword')">测试连接</a-button>
              <a-tooltip v-if="probeStates.keyword.result" :title="probeStates.keyword.result.detail">
                <span class="probe-chip" :class="probeStates.keyword.result.available ? 'probe-ok' : 'probe-bad'">
                  {{ probeStates.keyword.result.available ? '可达' : '不可达' }} {{ probeStates.keyword.result.latencyMs }}ms
                </span>
              </a-tooltip>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordApiKey" placement="top">引擎 Key <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-password v-model:value="form.keyword.apiKey" placeholder="Meilisearch master key（服务端未设置可留空）" style="width:320px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordTimeout" placement="top">引擎超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.keyword.timeoutMillis" :min="200" :step="100" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">超时自动降级 mysql</span>
            </a-form-item>
            <div class="cfg-sub">融合权重 · 阈值 · 改写回退</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.vectorWeight" placement="top">向量权重 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.vectorWeight" :min="0" :max="1" :step="0.05" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordWeight" placement="top">关键词权重 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.keywordWeight" :min="0" :max="1" :step="0.05" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.titleBonus" placement="top">标题命中奖励 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.titleBonus" :min="0" :max="1" :step="0.05" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.positionBonus" placement="top">位置奖励 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.positionBonus" :min="0" :max="0.5" :step="0.01" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">首块 / 前段奖励</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.vecThreshold" placement="top">向量阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.vecThreshold" :min="0" :max="1" :step="0.05" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">相似度归一化基准/下限</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.keywordLimit" placement="top">关键词召回上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.keywordLimit" :min="1" :step="5" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.retrievalTimeout" placement="top">检索超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.searchTimeoutMs" :min="500" :step="500" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">关键词/总检索超时</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rewriteTimeoutMs" placement="top">改写超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rewriteTimeoutMs" :min="1000" :step="500" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">查询改写超时，本地模型慢可调大</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rewriteFallbackMinHits" placement="top">改写回退-最小命中 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rewriteFallbackMinHits" :min="0" :step="1" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">改写后命中少于该值→回退原问重检；0=关</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rewriteFallbackWeakScore" placement="top">改写回退-弱分阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rewriteFallbackWeakScore" :min="0" :max="1" :step="0.05" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">改写后最高命中分低于该值→回退原问重检；0=关</span>
            </a-form-item>
            <div class="cfg-sub">关联扩散与引用识别</div>
            <!-- 知识块关联检索：引用 1-hop 扩散 + 父章节带出 -->
            <a-form-item>
              <template #label><a-tooltip :title="tips.refExpandEnabled" placement="top">关联扩散 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.refExpandEnabled" />
              <span style="margin-left:12px;color:#999;font-size:12px">命中块自动带出"被引用/父章节"关联块</span>
            </a-form-item>
            <a-form-item v-if="form.retrieval.refExpandEnabled">
              <template #label><a-tooltip :title="tips.refExpandMaxHits" placement="top">扩散块上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.refExpandMaxHits" :min="0" :max="10" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">0=仅父章节不带引用块</span>
            </a-form-item>
            <a-form-item v-if="form.retrieval.refExpandEnabled">
              <template #label><a-tooltip :title="tips.refExpandIncludeIncoming" placement="top">入边扩散 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.refExpandIncludeIncoming" />
              <span style="margin-left:12px;color:#999;font-size:12px">同时带出"引用本块的块"（默认关，易带低相关）</span>
            </a-form-item>
            <a-form-item v-if="form.retrieval.refExpandEnabled">
              <template #label><a-tooltip :title="tips.refExpandParentEnabled" placement="top">父章节带出 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.refExpandParentEnabled" />
              <span style="margin-left:12px;color:#999;font-size:12px">命中子章节时带父章节摘要（定义/总述）</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.refDetectEnabled" placement="top">引用识别 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.refDetectEnabled" />
              <span style="margin-left:12px;color:#999;font-size:12px">解析时识别"详见/参见X节"（改后需重解析）</span>
            </a-form-item>
            <a-form-item v-if="form.retrieval.refDetectEnabled">
              <template #label><a-tooltip :title="tips.refDetectMention" placement="top">提及识别 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.refDetectMention" />
              <span style="margin-left:12px;color:#999;font-size:12px">正文提到其他章节也算引用（如 4.1.2 所述/《数据字典》/XX章节）</span>
            </a-form-item>
            <div class="cfg-sub">重排服务（可选 reranker，需独立服务）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankEnabled" placement="top">启用重排 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.retrieval.rerank.enabled" :loading="rerankChecking" @change="onRerankEnabledChange" />
              <span style="margin-left:12px;color:#999;font-size:12px">开启前自动校验服务可用性</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankModel" placement="top">模型名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.retrieval.rerank.model" placeholder="BAAI/bge-reranker-v2-m3" style="width:320px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankBaseUrl" placement="top">服务地址 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.retrieval.rerank.baseUrl" placeholder="http://localhost:7997" style="width:320px" />
              <a-button size="small" style="margin-left:8px" :loading="probeStates.rerank.loading"
                        @click="doProbe('rerank')">测试连接</a-button>
              <a-tooltip v-if="probeStates.rerank.result" :title="probeStates.rerank.result.detail">
                <span class="probe-chip" :class="probeStates.rerank.result.available ? 'probe-ok' : 'probe-bad'">
                  {{ probeStates.rerank.result.available ? '可达' : '不可达' }} {{ probeStates.rerank.result.latencyMs }}ms
                </span>
              </a-tooltip>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankTimeout" placement="top">超时(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rerank.timeoutMillis" :min="1000" :step="1000" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankMinHits" placement="top">候选区间 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rerank.minHits" :min="1" style="width:90px" />
              <span style="margin:0 6px;color:#999">~</span>
              <a-input-number v-model:value="form.retrieval.rerank.maxHits" :min="2" style="width:90px" />
              <span style="margin-left:8px;color:#999;font-size:12px">候选数在此区间才重排</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rerankFailCooldown" placement="top">失败冷却(ms) <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.retrieval.rerank.failCooldownMs" :min="1000" :step="5000" style="width:200px" />
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="融合分 = 向量权重×向量相似度 + 关键词权重×命中率 + 标题命中奖励。保存后立即生效，可配合「检索调试」对比效果。" />
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="重排：OpenAI 兼容 /v1/rerank 服务（sentence-transformers CrossEncoder，bge-reranker-v2-m3）。未启动或不可用时自动回退融合分排序，不影响正常问答。" />
        </a-collapse-panel>

        <a-collapse-panel key="context" :id="'cfg-anchor-context'" header="上下文与长度控制">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='context'" @click.stop="onResetGroup('context')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <div class="cfg-sub">窗口与预算（决定单次请求上下文长度）</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.modelWindows" placement="top">模型窗口映射 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.context.modelWindows"
                       placeholder="模型名=token,逗号分隔，如 qwen3=131072" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.defaultWindow" placement="top">默认窗口 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.defaultWindowTokens" :min="1000" :step="1000" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.safetyFactor" placement="top">窗口安全系数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.safetyFactor" :min="0.1" :max="1" :step="0.05" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.costCap" placement="top">成本软上限 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.costCapTokens" :min="0" :step="500" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.maxOutput" placement="top">输出限制 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.maxOutputTokens" :min="100" :step="100" style="width:200px" />
            </a-form-item>
            <div class="cfg-sub">历史裁剪 · 命中片段与填充</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.historyMax" placement="top">历史注入上限 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.historyMaxTokens" :min="0" :step="100" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.historyPerMsg" placement="top">单条历史截断字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.historyPerMsgChars" :min="0" :step="20" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.snippetWindow" placement="top">命中片段窗口字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.snippetWindowChars" :min="0" :step="20" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.maxContextHits" placement="top">知识块填充上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.maxContextHits" :min="1" :max="30" style="width:200px" />
            </a-form-item>
            <div class="cfg-sub">信息增益去冗余</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.dedupEnabled" placement="top">信息增益去冗余 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.context.dedupEnabled" />
              <span style="margin-left:12px;color:#999;font-size:12px">
                跳过与已选块语义重复的候选块（同一操作被切成多块时只保留最高相关那块，防重复进上下文与表述不一致）
              </span>
            </a-form-item>
            <a-form-item v-if="form.context.dedupEnabled">
              <template #label><a-tooltip :title="tips.dedupThreshold" placement="top">重叠阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.dedupThreshold" :min="0.1" :max="0.9" :step="0.05" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">词元重叠比例达此值即判冗余（越高越宽松，越少剔除）</span>
            </a-form-item>
            <a-form-item v-if="form.context.dedupEnabled">
              <template #label><a-tooltip :title="tips.dedupPathThreshold" placement="top">同章节阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.context.dedupPathThreshold" :min="0.1" :max="0.9" :step="0.05" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">同章节路径的相邻切片重复率更高，用更低阈值判断冗余</span>
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出预算的块自动被裁；每块只取命中关键词±窗口片段。历史单条截断+总量限制，[图片N] 标记自动剥离避免编号冲突。保存后立即生效。" />
        </a-collapse-panel>

        <a-collapse-panel key="deepReasoning" :id="'cfg-anchor-deepReasoning'" header="深度思考设置">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='deepReasoning'" @click.stop="onResetGroup('deepReasoning')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <div class="cfg-sub">思考模式与引导</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drEnabled" placement="top">总开关 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.enabled" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMode" placement="top">思考模式 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-select v-model:value="form.deepReasoning.thinkingMode" style="width:220px" :options="[
                { value: 'model', label: 'model（透传 enable_thinking，从 reasoning_content 提取）' },
                { value: 'prompt', label: 'prompt（提示词引导输出到正文）' }
              ]" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drEnableThinking" placement="top">透传 enable_thinking <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.enableThinking" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drPrompt" placement="top">思考引导提示词 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-textarea v-model:value="form.deepReasoning.prompt" :rows="6"
                          placeholder="引导模型先深度思考、末尾输出 <search>精化query|子问题1|子问题2</search> 检索计划" />
            </a-form-item>
            <div class="cfg-sub">检索计划 · 多路与超时</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drSearchTag" placement="top">检索计划标签名 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input v-model:value="form.deepReasoning.searchTag" style="width:200px" placeholder="search" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMaxSub" placement="top">最大子问题数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.maxSubQueries" :min="0" :max="8" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMultiRetrieval" placement="top">多路并行检索 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.multiRetrieval" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drTimeout" placement="top">思考阶段超时 ms <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.timeoutMillis" :min="1000" :step="1000" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMaxTokens" placement="top">思考输出上限 token <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.maxThinkingTokens" :min="0" :step="100" style="width:200px" />
            </a-form-item>
            <div class="cfg-sub">思考增强 · 护栏与路由</div>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drMaxThinkingChars" placement="top">思考长度上限字符 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.maxThinkingChars" :min="0" :step="500" style="width:200px" />
              <span style="margin-left:12px;color:#999;font-size:12px">超限截断思考流并保留已想内容继续（0=不限制）</span>
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drInjectThinking" placement="top">思考链注入回答 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.injectThinking" />
              <span style="margin-left:12px;color:#999;font-size:12px">把推理过程（截断）作为参考注入生成 prompt，让思考作用于回答</span>
            </a-form-item>
            <a-form-item v-if="form.deepReasoning.injectThinking">
              <template #label><a-tooltip :title="tips.drInjectThinkingChars" placement="top">注入长度上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.injectThinkingMaxChars" :min="100" :step="100" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drInjectKeywords" placement="top">思考关键词增强检索 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.injectKeywords" />
              <span style="margin-left:12px;color:#999;font-size:12px">从思考全文提取词元补充检索（思考失败时也用于增强降级检索）</span>
            </a-form-item>
            <a-form-item v-if="form.deepReasoning.injectKeywords">
              <template #label><a-tooltip :title="tips.drInjectKeywordsMax" placement="top">增强词元数上限 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.deepReasoning.injectKeywordsMax" :min="1" :max="10" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.drAutoRoute" placement="top">自动路由 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.deepReasoning.autoRoute" />
              <span style="margin-left:12px;color:#999;font-size:12px">未手动开启时，长问/多条件/对比类问题自动启用深度思考</span>
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="深度思考：AI 先流式展示思维链（回答上方折叠面板，思考完成自动收起），思考末尾输出 <search> 检索计划（精化 query + 子问题），多路并行检索合并后回答；思考链与思考关键词参与最终检索/回答增强。默认 maxThinkingTokens=0 不设上限（qwen 思考模式设 max_tokens 会空输出）。失败自动降级（思考内容不白费，用于增强检索）。" />
        </a-collapse-panel>

        <a-collapse-panel key="semanticCache" :id="'cfg-anchor-semanticCache'" header="语义缓存（相似问题加速）">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='semanticCache'" @click.stop="onResetGroup('semanticCache')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <a-form-item>
              <template #label><a-tooltip :title="tips.scEnabled" placement="top">总开关 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.semanticCache.enabled" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.scThreshold" placement="top">相似度阈值 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.semanticCache.threshold" :min="0.8" :max="1" :step="0.01" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.scMaxEntries" placement="top">最大条数 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.semanticCache.maxEntries" :min="10" :step="50" style="width:200px" />
            </a-form-item>
            <a-form-item label="已缓存">
              <span>{{ cacheStats.count }} 条</span>
              <a-button size="small" style="margin-left:12px" :loading="cacheClearing" @click="doClearCache">清空缓存</a-button>
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="命中相似问题（≥阈值）时直接复用历史回答：省检索与 LLM 成本、秒级返回，回答下方会标注来源问题。知识库变更（解析/删除/回滚/启停用）时自动整体清空，不会用过期答案。" />
        </a-collapse-panel>

        <a-collapse-panel key="ratelimit" :id="'cfg-anchor-ratelimit'" header="接口限流（防滥用）">
          <template #extra><a-button size="small" type="text" class="reset-group-btn" :loading="resettingKey==='ratelimit'" @click.stop="onResetGroup('ratelimit')">恢复本组默认</a-button></template>
          <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
            <a-form-item>
              <template #label><a-tooltip :title="tips.rlEnabled" placement="top">总开关 <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-switch v-model:checked="form.ratelimit.enabled" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rlChat" placement="top">问答限频（次/分钟/用户） <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.ratelimit.chatPerMinute" :min="0" :step="5" style="width:200px" />
            </a-form-item>
            <a-form-item>
              <template #label><a-tooltip :title="tips.rlUpload" placement="top">上传限频（次/分钟/用户） <question-circle-outlined class="tip-icon" /></a-tooltip></template>
              <a-input-number v-model:value="form.ratelimit.uploadPerMinute" :min="0" :step="5" style="width:200px" />
            </a-form-item>
          </a-form>
          <a-alert type="info" show-icon style="margin:0 24px 16px"
                   message="Redis 固定窗口计数，按用户（网关未透传 X-User-Id 时按 IP）限频，超限返回 429 并提示等待秒数。限频设为 0 表示该接口不限流；Redis 不可用时自动放行，不影响正常使用。保存后立即生效。" />
        </a-collapse-panel>

      </a-collapse>

      <!-- 悬浮保存按钮：固定在右下角，无需滚动到底部 -->
      <div style="position:fixed; right:24px; bottom:24px; z-index:100; margin:0">
        <a-button type="primary" :loading="saving" :disabled="!dirtyCount" @click="save"
                  style="box-shadow:0 4px 12px rgba(0,0,0,0.18)">
          <template #icon><save-outlined /></template>
          {{ dirtyCount ? `保存配置（${dirtyCount} 组改动）` : '保存配置' }}
        </a-button>
      </div>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { QuestionCircleOutlined, SaveOutlined, WarningOutlined } from '@ant-design/icons-vue'
import { getConfig, saveConfig, resetConfig, checkRerank, checkKeywordEngine, getAnswerCacheStats, clearAnswerCache, getReembedStatus, triggerReembed, probeConnectivity } from '../api'

// 折叠面板：默认展开常用分组（chat / retrieval / context / deepReasoning），vision / embedding 收起
const activeKeys = ref(['chat', 'retrieval', 'context', 'deepReasoning'])

// 分区锚点：点击展开 + 平滑滚动定位
const anchors = [
  { key: 'chat', label: '智能问答' },
  { key: 'vision', label: '视觉模型' },
  { key: 'chunk', label: '文档解析' },
  { key: 'embedding', label: '向量模型' },
  { key: 'retrieval', label: '检索设置' },
  { key: 'context', label: '上下文控制' },
  { key: 'deepReasoning', label: '深度思考' },
  { key: 'semanticCache', label: '语义缓存' },
  { key: 'ratelimit', label: '接口限流' }
]
const currentAnchor = ref('')
let anchorObserver = null
const jumpTo = (key) => {
  currentAnchor.value = key
  // 展开该分组（若收起）
  if (!activeKeys.value.includes(key)) activeKeys.value = [...activeKeys.value, key]
  // 平滑滚动到分组
  requestAnimationFrame(() => {
    document.getElementById('cfg-anchor-' + key)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

// 参数说明（hover ? 查看"调整该参数会影响什么"）
const tips = {
  chatModel: '切换回答所用的底层大模型（命名与所选厂商一致，如 deepseek-chat / glm-4.5 / qwen-plus / moonshot-v1-8k / doubao-pro-32k）。不同模型的能力、速度、成本差异很大；更换后请同步确认下方「模型窗口映射」包含该模型，否则按默认窗口计算上下文预算。跨厂商切换后若深度思考报错（enable_thinking 仅 qwen3 系支持），请将「深度思考-思考模式」切为 prompt 或关闭透传。',
  chatPreset: '常见国产模型厂商预设：选择后自动填充网关地址与补全路径（API Key 与模型名需自行补齐）。覆盖 DeepSeek、智谱 GLM、阿里百炼 Qwen、Kimi、豆包（火山方舟）、腾讯混元、百度千帆、MiniMax、SiliconFlow（一个 Key 访问多个开源模型）与本地 Ollama，均为 OpenAI 兼容端点。',
  chatBaseUrl: 'OpenAI 兼容网关地址，不含补全路径。三种填法自动识别：① 网关根地址（如 https://api.deepseek.com）；② OpenAI SDK 风格版本尾缀（…/v1、…/compatible-mode/v1、智谱 …/v4、方舟 …/v3、千帆 …/v2，版本段自动移入补全路径）；③ 完整端点（以 /chat/completions 结尾）。修改保存后下一次回答即走新网关，免重启。',
  chatCompletionsPath: '对话补全路径，默认 /v1/chat/completions。非 /v1 网关需调整：智谱 GLM /v4/chat/completions、火山方舟 /v3/chat/completions、百度千帆 /v2/chat/completions；选择厂商预设会自动填充，手动填写带版本尾缀的 baseUrl 时也可留空自动识别。',
  chatApiKey: '所选厂商的 API Key（格式以厂商为准，讯飞星火为 key:secret 拼接形式）。以 RSA 加密后存储，页面上仅显示 ****掩码；未修改时无需重新输入。切换厂商后请换对应 Key，否则网关 401。',
  temperature: '控制回答的随机性（0~2）：越低回答越稳定、严谨、贴近资料原文（操作手册问答建议 0.2~0.4）；越高越有创造性，但也更容易偏离事实或编造内容。注意部分厂商范围更窄（如智谱 0~1），超出会报错。',
  systemPrompt: '定义 AI 的角色与回答风格，会注入每次问答的系统提示。改动立即影响所有回答的语气与行为；引用标注、配图、追问的硬性规则由系统固定，不可在此修改。',
  visionModel: '图片识别所用的多模态模型，影响文档截图、流程图的描述质量（描述越准，回答配图与检索召回越准）。换云端多模态（如 GLM-4V / qwen-vl-max / GPT-4o）需同步修改下方网关地址与 API Key。',
  visionBaseUrl: '视觉模型 OpenAI 兼容网关地址。本地 Ollama 用 http://localhost:11434；云端同对话模型厂商（…/v1、…/v4 等版本尾缀自动识别）。修改保存后下一次图片描述即走新网关，免重启。',
  visionApiKey: '视觉模型 API Key（RSA 加密存储，页面仅显示 ****掩码，未修改无需重输）。本地 Ollama 不校验密钥可留空；切换云端服务后请换对应 Key。',
  embeddingModel: '向量化所用模型，决定知识块与提问的语义表示。切换保存时会真实探测新配置（不可达/Key 错误将拒绝保存），通过后自动清空向量索引并后台全量重嵌入——不同模型向量不可迁移，这是必要步骤；期间检索降级关键词路。',
  embeddingBaseUrl: '向量模型 OpenAI 兼容网关地址（可与对话模型不同厂商，如对话用 DeepSeek、向量用百炼）。…/v1、…/v4 等版本尾缀自动识别。',
  embeddingApiKey: '向量模型 API Key（RSA 加密存储，页面仅显示 ****掩码，未修改无需重输）。注意：与对话模型的 Key 通常不同，切换厂商时务必同步更换。',
  embeddingPath: '向量化接口路径，默认 /v1/embeddings。智谱 /v4/embeddings、百度千帆 /v2/embeddings；baseUrl 填了版本尾缀时可留空自动识别。',
  embeddingDimensions: '当前向量索引的维度，由系统在全量重嵌入成功后自动记录，不可手工修改。切换向量模型时用它与新模型探测维度比对：维度变化说明索引 schema 必须重建（重嵌入会自动做）。显示"未记录"表示本库尚未跑过重嵌入，不影响使用。',
  visionPrompt: '图片描述的要求（如提取关键文字/界面元素、说明流程要点）。改动影响图片描述的内容倾向，进而影响检索与配图准确性。',
  visionConcurrency: '文档解析时图片描述的最大并发数。调高解析更快，但占用更多显存/推理资源（本地 Ollama 需设 OLLAMA_NUM_PARALLEL 才能并行）；调低更稳。',
  maxChunks: '单文档解析的最大知识块数（0=不限制）。超大文档超出部分截断不入库，防止 embedding 调用数万次导致解析失控。',
  maxImages: '单文档最多提取并描述的图片数（0=不限制）。图片爆炸的文档（上百张图）解析会非常慢，设上限可避免。',
  overlap: '分块重叠字符数：把上一块尾部 N 字拼入当前块的向量化文本，保留硬切/分块截断处的语义衔接。仅影响向量，不写入知识块正文、不影响增量复用（邻块变动不会连锁重嵌）。0=关闭。需重解析生效。',
  uploadMaxSize: '文档上传大小上限（MB）。保存即生效（新上传按新限制校验）；物理上限 1GB 由容器兜底，不可超过。',
  vectorWeight: '向量语义相似度在最终排序分中的占比。调高更侧重"意思相近"的匹配（适合口语化、换说法的提问）；过高可能引入字面无关但语义相近的块。',
  keywordWeight: '关键词精确命中在排序分中的占比。调高更侧重"字面命中"（适合操作手册中的专有名词、按钮名）；过高会漏掉语义相关但字面不同的内容。',
  titleBonus: '知识块标题命中关键词时的额外加分。调高更倾向返回标题相关的块；适合章节结构清晰的文档，但可能挤占正文命中的块。',
  rerankEnabled: '对混合检索候选再做一次精排（真交叉编码）。需先启动本地服务 scripts/win 或 scripts/mac 的 start_rerank_server（bge-reranker-v2-m3）；服务不可用时自动回退融合分排序。',
  rerankBaseUrl: '重排服务地址（OpenAI 兼容 /v1/rerank），默认本地 http://localhost:7997。',
  rerankModel: '重排模型名，与本地服务一致即可，默认 BAAI/bge-reranker-v2-m3。',
  rerankTimeout: '单次重排超时时间（毫秒），超过则回退融合分排序。建议 3000~8000。',
  modelWindows: '声明各模型的上下文窗口大小（token），格式"模型名=token"逗号分隔，按当前模型名的包含关系匹配。设置过大有超窗报错风险，过小会浪费模型能力。',
  defaultWindow: '当「模型窗口映射」未匹配到当前模型时使用的窗口大小兜底值。',
  safetyFactor: '上下文预算 = 窗口 × 安全系数 − 输出限制。系数越高单次可塞入更多知识块和历史，但越接近模型窗口上限；建议 0.6~0.8。',
  costCap: '单次请求输入 token 的硬上限（0=不限制）。用于控制成本：即使模型窗口很大，也最多塞这么多内容；设小会减少知识块数量、回答可能不完整。',
  maxOutput: '回答生成的最大 token 数。设太短回答会被截断；设太长增加成本与等待时间。',
  historyMax: '注入对话历史的 token 总上限。越大多轮上下文越完整（追问更准），但会挤压知识块的空间，且历史可能引入过时信息。',
  historyPerMsg: '每条历史消息保留的最大字符数，超出部分截断。控制历史占用的空间，保留最近轮次。',
  snippetWindow: '每个知识块只取"命中关键词 ± 该字符数"的片段送入上下文（0=整块塞入）。调大上下文信息更全但 token 消耗增大；调小更省 token 但可能丢失上下文导致理解偏差。',
  maxContextHits: '上下文最多塞入的知识块数量上限。调大可能引入相关度低的块稀释注意力；调小可能漏掉有价值的参考资料。',
  dedupEnabled: '信息增益去冗余：跳过与已选块语义重复的候选块（同一操作被切成多个知识块时，只保留最高相关的一块进上下文）。防止重复内容浪费预算、以及多块表述不一致导致模型自相矛盾。关闭后所有命中块按原顺序填充。',
  dedupThreshold: '候选块与任一已选块的词元重叠比例（Jaccard）达到此值即判定为冗余跳过。越高越宽松（剔除越少）；越低剪得越狠但可能误伤信息有增量的相关块。',
  dedupPathThreshold: '候选块与已选块处于同一章节路径（titlePath 互为前缀/相等）时使用的重叠阈值。同章节的相邻切片几乎总讲同一内容，此阈值通常设得比普通阈值更低（更容易剪）。',
  drEnabled: '深度思考总开关。关闭后即使前端开启"深度思考"开关也走普通回答流程（前端开关独立控制）。',
  drMode: '思考模式：model=通过 extraBody 透传 enable_thinking=true，从模型 reasoning_content 提取思维链（qwen3 系原生支持）；prompt=用提示词引导模型把思考输出到正文 content（兼容不支持思考参数的模型/网关）。',
  drEnableThinking: 'thinkingMode=model 时是否透传 enable_thinking=true。若网关静默忽略或返回异常，可关闭此项并切到 prompt 模式。',
  drPrompt: '思考阶段的引导提示词：要求模型先深度分析不直接作答，并在末尾输出 <search> 检索计划（精化 query | 子问题1 | 子问题2）。改坏可能导致检索计划提取失败（自动降级普通检索）。',
  drSearchTag: '检索计划包裹标签名，默认 search（即 <search>...</search>）。需与提示词中的标签一致。',
  drMaxSub: '从检索计划中最多取多少个子问题参与多路并行检索（不含精化 query）。越大召回越广但更慢、成本更高。',
  drMultiRetrieval: '是否多路并行检索（精化 query + 子问题分别检索后按最高分合并）。关闭则只用精化 query 单路检索（更快但召回面窄）。',
  drTimeout: '思考阶段最大等待时间(ms)。超时用已收集的思考内容降级为普通检索回答，不阻塞。',
  drMaxTokens: '思考输出的 token 上限（0=不设）。qwen3 思考模式下设 max_tokens 会导致空输出，默认 0；仅当思考过长需裁剪时设置。',
  drMaxThinkingChars: '思考流长度上限（字符，0=不限制）。超限自动中断思考流但保留已想内容继续提取检索计划，避免超长思维链刷爆上下文与 token。',
  drInjectThinking: '把深度思考的推理过程（截断到注入长度上限）作为参考注入最终回答的生成 prompt，让"想过的拆解与判断"直接作用于回答；明确以参考资料为准，思考仅辅助。',
  drInjectThinkingChars: '思考链注入回答的最大字符数。越长信息越全但占用生成预算；过短可能丢失关键推理。',
  drInjectKeywords: '从思考全文提取关键词元补充到检索 query（多路检索多一条增强路；深度思考失败时也用它增强降级检索，思考不白费）。提升"思考中提到的实体/限定词"的召回。',
  drInjectKeywordsMax: '思考关键词增强最多取多少个词元。越多召回越宽但可能引入噪声。',
  drAutoRoute: '未手动开启深度思考时，按问题特征自动判断：长问（≥25 字）或含多条件/对比/递进词（如果/当/对比/区别/以及/同时/多个）的问题自动启用深度思考。保守启发式，避免常见问题全量思考导致成本翻倍。',
  historyRounds: '问答时注入对话历史的轮数（多轮记忆）。调大更连贯但占上下文预算；0=不注入历史。',
  remainTokenFloor: '上下文预算保留下限（token）：扣除系统提示与问题后至少保留的量，低于则不再填充知识块。',
  truncateFallbackChars: '知识块超出预算时的截断兜底字符数（至少保留的字数）。',
  pipelineThreads: '问答流水线的并发线程数：图片识别、查询改写、检索、深度思考等"重活"在独立线程池执行，不占 Tomcat 请求线程（多用户并发问答时避免请求线程被打满）。调高可支撑更多并发用户，但占用更多 CPU/内存；队列满时新请求会快速返回"系统繁忙"。保存即生效。',
  streamRetryCount: '主 LLM 流式生成在"未输出任何内容"时中断的自动重试次数（0=关闭）。已输出内容后中断不重试（避免重复内容）；重试仍失败会在回答下方给出警示。',
  sseTimeoutMs: '问答 SSE 连接超时（毫秒）：超过后回答被截断，前端提示"回答超时已截断"。深度思考+长回答场景可调大，默认 300000（5 分钟）。',
  retrievalDebugEnabled: '检索调试入口开关（内部排障用）：开启后回答操作菜单显示「检索调试」，可分步查看关键词/向量/重排召回结果；面向用户的部署建议保持关闭。',
  suggestedQuestions: '欢迎页展示的推荐问题（新用户引导）。每行一条、最多 8 条；数据看板的热门问题可一键加入。改动保存后，用户下次进入问答页生效。',
  showDebugDegradations: '回答下方是否显示降级提示（无命中/查询改写失败/图片剔除/未标注引用/缓存命中等）。默认关：回答区不显示任何降级提示（排障信息仍写 [FAIL-LOUD] 日志）；调试排障时开启即可看到全部降级原因。',
  citationCheck: '引用语义一致性自检：回答生成后，把每条 [N] 引用的前文句子与其来源片段交给模型判断是否被直接支撑，剔除"编号存在但内容与该块无关"的引用并重编编号。提升引用可信度，代价是每轮回答多一次校验调用（约数秒延迟）。',
  judgeEnabled: '自动体检的 LLM 评判（调试度量）：体检时对每个 case 判断"当前检索的 top 命中资料是否足以直接回答该问题"，汇总为 judgeScore 写入体检报告，用于评估检索结果的实际可用性。每 case 一次模型调用，评估集大时体检耗时明显增加。',
  visionEnabled: '视觉模型总开关。关闭后：文档图片/用户图片都不生成描述——图片仅展示、内容不进检索与回答引用（RAG 对图片语义失效），一般不建议关闭。',
  parseConcurrency: '文档异步解析的并发数（同时解析几个文档）。调高多文档上传更快，但并发解析会同时占用 embedding/Ollama 资源；保存后对新任务生效。',
  embedRetryCount: '向量化批次失败时的自动重试次数（0=不重试）。重试仍失败则整个文档解析失败并回退/提示（fail-loud，绝不静默丢块）。',
  ocrMinText: 'PDF 页文本少于该长度判定为扫描件/图片型，触发 OCR 识别（0=总是 OCR）。调高更激进触发 OCR，调低更依赖 PDF 自带文本。',
  chunkStructural: '按文档结构切分：标题层级开新块、达到边界阈值在段落边界断块（避免从句子中间硬切）、章节标题路径注入块上下文。docx 生效；存量文档需重解析后才会按新规则重建知识块。',
  chunkStructuralRatio: '结构切分边界阈值（maxSize×比例）：块达到该长度时优先在段落边界断块。调低块更小更贴近边界但块数更多；调高更接近原 800 字硬切。',
  userImageConcurrency: '用户在对话中上传图片的识别并发数（区别于文档解析的图片描述并发）。',
  vecThreshold: '向量相似度归一化基准：低于该分的向量命中归一化为 0 分，也是向量检索的相似度下限。调高更严格（召回更少但更相关）。',
  keywordLimit: '关键词检索最多返回的知识块数（SQL LIMIT）。调高召回更全但更慢、融合分计算更重。',
  retrievalTimeout: '混合检索超时（ms）：关键词子检索与总检索的超时上限，超时降级返回已收集结果。',
  rewriteTimeoutMs: '查询改写超时（ms）：LLM 改写问题（多轮追问补全上下文）的等待上限，超时则用原问题检索并提示。本地模型响应慢时调大可减少改写降级，默认 5000。',
  rewriteFallbackMinHits: '改写跑偏回退-最小命中数：改写后的检索词召回的知识块数低于该值时，判定"改写跑偏"，自动丢弃改写结果、用原始问题重检一次（防止改写改错方向导致漏召回）。0=关闭该判据。默认 2。',
  rewriteFallbackWeakScore: '改写跑偏回退-弱分阈值：改写后的最高命中融合分低于该值时同样判定"改写跑偏"并回退原问题重检。0=关闭该判据。默认 0.2（回退后答案质量差、命中分整体偏低时可适当调高）。',
  refExpandEnabled: '知识块关联扩散总开关：命中块时自动带出"它引用的块"（详见/参见X节）与"父章节摘要"，让交叉引用内容的回答更完整。关闭后回到只检索直接命中块。',
  refExpandMaxHits: '关联扩散块的数量上限（0=只做父章节带出、不带引用块）。扩散块是可舍弃的增强，受数量与 token 双上限约束。',
  refExpandIncludeIncoming: '是否同时带出"引用了本块的块"（入边扩散）。默认关：入边常带出低相关块；出边（本块引用的）与父章节已覆盖主要场景。',
  refExpandParentEnabled: '父章节带出：命中子章节块时，自动带上父章节的标题+摘要（前200字，含定义/总述），解决子块上下文不完整。',
  refDetectEnabled: '解析时识别知识块中的交叉引用（详见/参见/见 X 节/「章节名」）并建立引用关系。改后需重新解析文档才生效。',
  refDetectMention: '同时识别正文中无动词的章节提及（如"如 4.1.2 所述""在《数据字典》中""报表设计模块"）。提及类只做精确匹配、单块最多 8 条引用，避免把高频话题词误建成引用边。',
  positionBonus: '知识块位置奖励：位于文档首块/前段的内容额外加分。适合"文档开头是摘要"的结构；对顺序无关的文档可调低。',
  rerankMinHits: '触发重排的候选数区间（下限~上限）：候选太少（无意义）或太多（超时风险）时跳过重排，直接用融合分排序。',
  rerankFailCooldown: '重排服务失败后的冷却时间(ms)：冷却期内不再探测/调用（避免每个请求都撞一次），冷却结束后自动恢复。',
  keywordEngine: '关键词召回引擎。mysql=MySQL LIKE（零依赖，知识块量大时全表扫描慢）；meilisearch=外部索引（中文分词+相关度打分）。切换到 meilisearch 时自动校验服务可用性（不可用则保存失败）并自动全量重建索引，无需手动操作。',
  keywordBaseUrl: 'Meilisearch 服务地址（如 http://localhost:7700，docker-compose 部署为容器内地址）。',
  keywordApiKey: 'Meilisearch master key（需与服务端 MEILI_MASTER_KEY 一致）。留空时回退读取环境变量 AI_MEILI_KEY；服务端已设置 key 而此处为空/错误，切换引擎时会校验失败并阻止保存。',
  keywordTimeout: '关键词引擎单次请求超时(ms)：关键词路是辅助召回，超时会自动降级回 MySQL LIKE，不建议设太大。',
  scEnabled: '语义缓存总开关：提问向量化后与历史问题比对，相似度达阈值直接复用历史回答（跳过检索与 LLM，秒级返回且省成本）。带图片的提问不走缓存。',
  scThreshold: '命中阈值（余弦相似度 0.8~1）。越高越保守（只有问法几乎一致才命中）；0.96 兼顾准确与命中率的推荐值。知识库变更时缓存自动整体失效。',
  scMaxEntries: '缓存条数上限，超出按时间淘汰最早的条目。每条存储一次问题向量化 + 完整回答。',
  rlEnabled: '接口限流总开关（Redis 固定窗口计数）。关闭后所有接口不限流；Redis 不可用时即使开启也会自动放行（限流是保护措施，不比业务先挂）。',
  rlChat: '每个用户每分钟最多发起的问答次数（0=不限流）。匿名请求（网关未透传 X-User-Id）按 IP 维度共享额度。用于防止滥用与成本失控。',
  rlUpload: '每个用户每分钟最多上传文档的次数（0=不限流）。批量上传按一次请求计。解析是重资源操作，限制上传频次可防止解析队列被打满。'
}

const loading = ref(false)
const saving = ref(false)
const cacheStats = ref({ count: 0 })
const cacheClearing = ref(false)
const doClearCache = async () => {
  cacheClearing.value = true
  try {
    const r = await clearAnswerCache()
    if (r.success) { cacheStats.value = { count: 0 }; message.success('答案缓存已清空') }
    else message.error(r.msg || '清空失败')
  } catch (e) { message.error(e.message || '清空失败') }
  finally { cacheClearing.value = false }
}
const rerankChecking = ref(false)
const keywordChecking = ref(false)

// 切换关键词引擎：切到 meilisearch 时先校验服务可用性，不可用则回滚并提示（服务地址需先保存生效）
const onKeywordEngineChange = async val => {
  if (val !== 'meilisearch') return
  keywordChecking.value = true
  try {
    const r = await checkKeywordEngine()
    if (r.success && r.data?.available) {
      message.success('Meilisearch 服务正常。保存后请执行全量重建（接口 /api/ai/search-index/reindex）再提问')
    } else {
      form.value.keyword.engine = 'mysql'
      message.error('Meilisearch 不可用：请先启动服务（docker compose up meilisearch 或本机二进制），或检查服务地址')
    }
  } catch (e) {
    form.value.keyword.engine = 'mysql'
    message.error('Meilisearch 校验失败：' + (e.message || '服务不可用'))
  } finally {
    keywordChecking.value = false
  }
}

// 启用重排开关：打开前先校验服务可用性，服务不正常阻止开启并回滚
const onRerankEnabledChange = async checked => {
  if (!checked) return            // 关闭无需校验
  rerankChecking.value = true
  try {
    const r = await checkRerank()
    if (r.success && r.data?.available) {
      message.success('重排服务正常，已启用')
    } else {
      form.value.retrieval.rerank.enabled = false
      message.error('重排服务不可用：请先启动本地服务（scripts/win 或 scripts/mac 的 start_rerank_server），或检查服务地址')
    }
  } catch (e) {
    form.value.retrieval.rerank.enabled = false
    message.error('重排服务校验失败：' + (e.message || '服务不可用'))
  } finally {
    rerankChecking.value = false
  }
}
// 厂商预设：主流国产模型 OpenAI 兼容端点（baseUrl 均为网关根地址，不含版本段；版本段在 completionsPath）
const chatPreset = ref('custom')
const chatPresets = {
  deepseek: { baseUrl: 'https://api.deepseek.com', completionsPath: '/v1/chat/completions' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas', completionsPath: '/v4/chat/completions' },
  dashscope: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode', completionsPath: '/v1/chat/completions' },
  moonshot: { baseUrl: 'https://api.moonshot.cn', completionsPath: '/v1/chat/completions' },
  ark: { baseUrl: 'https://ark.cn-beijing.volces.com/api', completionsPath: '/v3/chat/completions' },
  hunyuan: { baseUrl: 'https://api.hunyuan.cloud.tencent.com', completionsPath: '/v1/chat/completions' },
  qianfan: { baseUrl: 'https://qianfan.baidubce.com', completionsPath: '/v2/chat/completions' },
  minimax: { baseUrl: 'https://api.minimax.chat', completionsPath: '/v1/chat/completions' },
  siliconflow: { baseUrl: 'https://api.siliconflow.cn', completionsPath: '/v1/chat/completions' },
  ollama: { baseUrl: 'http://localhost:11434', completionsPath: '/v1/chat/completions' }
}
const chatPresetOptions = [
  { value: 'custom', label: '自定义 / 保持现状' },
  { value: 'deepseek', label: 'DeepSeek（api.deepseek.com）' },
  { value: 'zhipu', label: '智谱 GLM（open.bigmodel.cn）' },
  { value: 'dashscope', label: '阿里百炼 Qwen（dashscope）' },
  { value: 'moonshot', label: 'Kimi 月之暗面（moonshot）' },
  { value: 'ark', label: '豆包/火山方舟（volces.com）' },
  { value: 'hunyuan', label: '腾讯混元（hunyuan）' },
  { value: 'qianfan', label: '百度千帆 v2（qianfan）' },
  { value: 'minimax', label: 'MiniMax（minimax.chat）' },
  { value: 'siliconflow', label: 'SiliconFlow 硅基流动（多模型聚合）' },
  { value: 'ollama', label: '本地 Ollama（localhost:11434）' }
]
const onChatPresetChange = val => {
  const p = chatPresets[val]
  if (!p) return
  form.value.chat.baseUrl = p.baseUrl
  form.value.chat.completionsPath = p.completionsPath
  message.info('已填充网关地址与补全路径，请补齐 API Key 与模型名后保存')
}

const form = ref({ chat: { model: '', baseUrl: '', apiKey: '', completionsPath: '', temperature: 0.3, systemPrompt: '', suggestedQuestions: '', retrievalDebugEnabled: false, remainTokenFloor: 800, truncateFallbackChars: 200, historyRounds: 5, pipelineThreads: 8, streamRetryCount: 1, sseTimeoutMs: 300000, showDebugDegradations: false, citationCheckEnabled: true },
                    vision: { enabled: true, model: '', baseUrl: '', apiKey: '', prompt: '', concurrency: 4, userImageConcurrency: 2 },
                    embedding: { model: '', baseUrl: '', apiKey: '', embeddingsPath: '' },
                    chunk: { maxChunks: 3000, maxImages: 100, overlap: 100, structural: true, structuralRatio: 0.8 },
                    // 解析类参数后端 key 前缀是 parse.*（不是 chunk.*），必须独立分组提交，否则被白名单静默丢弃
                    parse: { concurrency: 2, ocrMinText: 20, embedRetryCount: 1 },
                    upload: { maxFileSizeMB: 200 },
                    retrieval: { vectorWeight: 0.6, keywordWeight: 0.4, titleBonus: 0.1,
                                 vecThreshold: 0.3, keywordLimit: 20, keywordTimeoutMs: 800, searchTimeoutMs: 8000, rewriteTimeoutMs: 5000,
                                 rewriteFallbackMinHits: 2, rewriteFallbackWeakScore: 0.2,
                                 refDetectEnabled: true, refDetectMention: true, refExpandEnabled: true, refExpandMaxHits: 3, refExpandIncludeIncoming: false,
                                 refExpandParentEnabled: true,
                                 positionBonus: 0.03, sectionBonus: 0.01, keywordMaxTerms: 6, keywordMaxTotal: 12,
                                 rerank: { enabled: false, baseUrl: 'http://localhost:7997',
                                           model: 'BAAI/bge-reranker-v2-m3', timeoutMillis: 5000,
                                           minHits: 6, maxHits: 15, failCooldownMs: 60000 } },
                    keyword: { engine: 'mysql', baseUrl: 'http://localhost:7700', apiKey: '', timeoutMillis: 1000 },
                    context: { modelWindows: '', defaultWindowTokens: 32768, safetyFactor: 0.7, costCapTokens: 8000,
                               maxOutputTokens: 2000, historyMaxTokens: 1200, historyPerMsgChars: 200,
                               snippetWindowChars: 150, maxContextHits: 8,
                               dedupEnabled: true, dedupThreshold: 0.45, dedupPathThreshold: 0.28 },
                    deepReasoning: { enabled: true, thinkingMode: 'model', enableThinking: true, prompt: '',
                                     searchTag: 'search', maxSubQueries: 3, multiRetrieval: true,
                                     timeoutMillis: 30000, maxThinkingTokens: 0,
                                     maxThinkingChars: 3000, injectThinking: true, injectThinkingMaxChars: 800,
                                     injectKeywords: true, injectKeywordsMax: 5, autoRoute: false },
                    ratelimit: { enabled: true, chatPerMinute: 10, uploadPerMinute: 10 },
                    semanticCache: { enabled: true, threshold: 0.96, maxEntries: 500 },
                    eval: { judgeEnabled: false } })

// ==================== 测试连接（模型网关 / 服务可达性） ====================
// 用表单里「尚未保存」的值探测，先测后存；与保存流程无关，不改配置、不落库。
// 注意：必须定义在 form 之后——watch 创建时会立即执行取值函数收集依赖，早于 form 初始化会抛 ReferenceError。
const probeStates = ref({
  chat: { loading: false, result: null },
  vision: { loading: false, result: null },
  embedding: { loading: false, result: null },
  rerank: { loading: false, result: null },
  keyword: { loading: false, result: null }
})
const probeLabels = { chat: '对话模型', vision: '视觉模型', embedding: '向量模型', rerank: '重排服务', keyword: '关键词引擎' }

const doProbe = async group => {
  const s = probeStates.value[group]
  if (!s || s.loading) return
  const f = form.value
  const payload = { group }
  if (group === 'chat') {
    Object.assign(payload, { baseUrl: f.chat.baseUrl, apiKey: f.chat.apiKey, model: f.chat.model, path: f.chat.completionsPath })
  } else if (group === 'vision') {
    Object.assign(payload, { baseUrl: f.vision.baseUrl, apiKey: f.vision.apiKey, model: f.vision.model })
  } else if (group === 'embedding') {
    Object.assign(payload, { baseUrl: f.embedding.baseUrl, apiKey: f.embedding.apiKey, model: f.embedding.model, path: f.embedding.embeddingsPath })
  } else if (group === 'rerank') {
    Object.assign(payload, { baseUrl: f.retrieval.rerank.baseUrl, model: f.retrieval.rerank.model })
  } else if (group === 'keyword') {
    Object.assign(payload, { baseUrl: f.keyword.baseUrl, apiKey: f.keyword.apiKey })
  }
  s.loading = true
  s.result = null
  try {
    const r = await probeConnectivity(payload)
    const d = r?.data || {}
    s.result = {
      available: !!d.available,
      latencyMs: d.latencyMs ?? 0,
      detail: d.detail || (r?.msg || '（无详情）')
    }
    // 失败时同时弹提示：详情可能较长，chip + tooltip 之外再给一次显性反馈
    if (!s.result.available) {
      message.error(`${probeLabels[group]}不可达：${s.result.detail}`)
    }
  } catch (e) {
    s.result = { available: false, latencyMs: 0, detail: e.message || '请求失败' }
    message.error(`${probeLabels[group]}探测失败：${s.result.detail}`)
  } finally {
    s.loading = false
  }
}

// 任一被探测的配置项改动后，清空已有的探测结果（避免"改了地址还显示旧的可达"）
watch(
  () => [
    form.value.chat.baseUrl, form.value.chat.completionsPath, form.value.chat.model, form.value.chat.apiKey,
    form.value.vision.baseUrl, form.value.vision.model, form.value.vision.apiKey,
    form.value.embedding.baseUrl, form.value.embedding.embeddingsPath, form.value.embedding.model, form.value.embedding.apiKey,
    form.value.retrieval.rerank.baseUrl, form.value.retrieval.rerank.model,
    form.value.keyword.baseUrl, form.value.keyword.apiKey
  ],
  () => {
    for (const k of Object.keys(probeStates.value)) probeStates.value[k].result = null
  }
)

// 当前向量索引维度（后端重嵌入成功后回写 embedding.dimensions，只读展示）
const embeddingDimensions = ref('')

// 向量模型全量重嵌入状态（切换后自动触发/手动重试；运行中轮询刷新）
const reembed = ref({ status: 'idle', total: 0, done: 0, failed: 0, error: null, oldDim: 0, newDim: 0, indexed: 0 })
const reembedTriggering = ref(false)
// 耗时：运行中按当前时间算（轮询驱动刷新），结束后按 endTime 定格
const reembedElapsed = computed(() => {
  const s = reembed.value
  if (!s.startTime) return ''
  const end = s.status === 'running' ? Date.now() : (s.endTime || 0)
  if (!end || end < s.startTime) return ''
  const sec = Math.round((end - s.startTime) / 1000)
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`
})
let reembedTimer = null
const refreshReembedStatus = async () => {
  try {
    const r = await getReembedStatus()
    if (r.success) reembed.value = r.data || { status: 'idle' }
    // 运行中每 3s 轮询，结束即停
    if (reembed.value.status === 'running') {
      if (!reembedTimer) reembedTimer = setInterval(refreshReembedStatus, 3000)
    } else if (reembedTimer) {
      clearInterval(reembedTimer); reembedTimer = null
      // 任务结束时后端已回写 embedding.dimensions，同步刷新只读维度展示
      if (reembed.value.newDim) embeddingDimensions.value = String(reembed.value.newDim)
    }
  } catch (e) { /* 状态查询失败静默（不影响配置页） */ }
}
const doTriggerReembed = async () => {
  reembedTriggering.value = true
  try {
    const r = await triggerReembed()
    if (r.success) { message.success('全量重嵌入任务已启动，期间检索自动降级关键词路'); refreshReembedStatus() }
    else message.error(r.msg || '触发失败')
  } catch (e) { message.error(e.message || '触发失败') }
  finally { reembedTriggering.value = false }
}

/** 拉取配置并回填表单与保存基线（进入页面 / 恢复本组默认后调用） */
const fetchAndFill = async () => {
  loading.value = true
  try {
    const r = await getConfig()
    if (r.success && r.data) {
      const d = r.data
      form.value.chat.model = d.chat?.model?.value || ''
      form.value.chat.baseUrl = d.chat?.baseUrl?.value || ''
      // 后端 snapshot 对 apiKey 脱敏（****后4位）；掩码仅展示，未修改则不提交
      form.value.chat.apiKey = d.chat?.apiKey?.value || ''
      form.value.chat.completionsPath = d.chat?.completionsPath?.value || ''
      form.value.chat.temperature = Number(d.chat?.temperature?.value ?? 0.3)
      form.value.chat.systemPrompt = d.chat?.systemPrompt?.value || ''
      form.value.chat.suggestedQuestions = d.chat?.suggestedQuestions?.value || ''
      form.value.chat.retrievalDebugEnabled = d.chat?.retrievalDebugEnabled?.value === 'true'
      form.value.chat.remainTokenFloor = Number(d.chat?.remainTokenFloor?.value ?? 800)
      form.value.chat.truncateFallbackChars = Number(d.chat?.truncateFallbackChars?.value ?? 200)
      form.value.chat.historyRounds = Number(d.chat?.historyRounds?.value ?? 5)
      form.value.chat.pipelineThreads = Number(d.chat?.pipelineThreads?.value ?? 8)
      form.value.chat.streamRetryCount = Number(d.chat?.streamRetryCount?.value ?? 1)
      form.value.chat.sseTimeoutMs = Number(d.chat?.sseTimeoutMs?.value ?? 300000)
      form.value.chat.showDebugDegradations = d.chat?.showDebugDegradations?.value === 'true'
      form.value.chat.citationCheckEnabled = d.chat?.citationCheckEnabled?.value !== 'false'
      form.value.eval.judgeEnabled = d.eval?.judgeEnabled?.value === 'true'
      form.value.vision.enabled = d.vision?.enabled?.value !== 'false'
      form.value.vision.model = d.vision?.model?.value || ''
      form.value.vision.baseUrl = d.vision?.baseUrl?.value || ''
      // 后端 snapshot 对 apiKey 脱敏（****后4位）；掩码仅展示，未修改则不提交
      form.value.vision.apiKey = d.vision?.apiKey?.value || ''
      form.value.vision.prompt = d.vision?.prompt?.value || ''
      form.value.vision.concurrency = Number(d.vision?.concurrency?.value ?? 4)
      form.value.vision.userImageConcurrency = Number(d.vision?.userImageConcurrency?.value ?? 2)
      const ck = d.chunk || {}
      form.value.chunk.maxChunks = Number(ck.maxChunks?.value ?? 3000)
      form.value.chunk.maxImages = Number(ck.maxImages?.value ?? 100)
      form.value.chunk.overlap = Number(ck.overlap?.value ?? 100)
      form.value.chunk.structural = ck.structural?.value !== 'false'
      form.value.chunk.structuralRatio = Number(ck.structuralRatio?.value ?? 0.8)
      // 解析类参数在后端 parse.* 分组（与 chunk.* 分开）
      const ps = d.parse || {}
      form.value.parse.concurrency = Number(ps.concurrency?.value ?? 2)
      form.value.parse.ocrMinText = Number(ps.ocrMinText?.value ?? 20)
      form.value.parse.embedRetryCount = Number(ps.embedRetryCount?.value ?? 1)
      const up = d.upload || {}
      form.value.upload.maxFileSizeMB = Math.round(Number(up.maxFileSize?.value ?? 209715200) / 1024 / 1024)
      form.value.retrieval.vectorWeight = Number(d.retrieval?.vectorWeight?.value ?? 0.6)
      form.value.retrieval.keywordWeight = Number(d.retrieval?.keywordWeight?.value ?? 0.4)
      form.value.retrieval.titleBonus = Number(d.retrieval?.titleBonus?.value ?? 0.1)
      form.value.retrieval.vecThreshold = Number(d.retrieval?.vecThreshold?.value ?? 0.3)
      form.value.retrieval.keywordLimit = Number(d.retrieval?.keywordLimit?.value ?? 20)
      form.value.retrieval.keywordTimeoutMs = Number(d.retrieval?.keywordTimeoutMs?.value ?? 800)
      form.value.retrieval.searchTimeoutMs = Number(d.retrieval?.searchTimeoutMs?.value ?? 8000)
      form.value.retrieval.rewriteTimeoutMs = Number(d.retrieval?.rewriteTimeoutMs?.value ?? 5000)
      form.value.retrieval.rewriteFallbackMinHits = Number(d.retrieval?.rewriteFallbackMinHits?.value ?? 2)
      form.value.retrieval.rewriteFallbackWeakScore = Number(d.retrieval?.rewriteFallbackWeakScore?.value ?? 0.2)
      form.value.retrieval.refDetectEnabled = d.retrieval?.refDetectEnabled?.value !== 'false'
      form.value.retrieval.refDetectMention = d.retrieval?.refDetectMention?.value !== 'false'
      form.value.retrieval.refExpandEnabled = d.retrieval?.refExpandEnabled?.value !== 'false'
      form.value.retrieval.refExpandMaxHits = Number(d.retrieval?.refExpandMaxHits?.value ?? 3)
      form.value.retrieval.refExpandIncludeIncoming = d.retrieval?.refExpandIncludeIncoming?.value === 'true'
      form.value.retrieval.refExpandParentEnabled = d.retrieval?.refExpandParentEnabled?.value !== 'false'
      form.value.retrieval.positionBonus = Number(d.retrieval?.positionBonus?.value ?? 0.03)
      form.value.retrieval.sectionBonus = Number(d.retrieval?.sectionBonus?.value ?? 0.01)
      form.value.retrieval.keywordMaxTerms = Number(d.retrieval?.keywordMaxTerms?.value ?? 6)
      form.value.retrieval.keywordMaxTotal = Number(d.retrieval?.keywordMaxTotal?.value ?? 12)
      // rerank 是独立分组（d.rerank），勿用 retrieval 组
      const rr = d.rerank || {}
      form.value.retrieval.rerank.enabled = rr.enabled?.value === 'true'
      form.value.retrieval.rerank.baseUrl = rr.baseUrl?.value || 'http://localhost:7997'
      form.value.retrieval.rerank.model = rr.model?.value || 'BAAI/bge-reranker-v2-m3'
      form.value.retrieval.rerank.timeoutMillis = Number(rr.timeoutMillis?.value ?? 5000)
      form.value.retrieval.rerank.minHits = Number(rr.minHits?.value ?? 6)
      form.value.retrieval.rerank.maxHits = Number(rr.maxHits?.value ?? 15)
      form.value.retrieval.rerank.failCooldownMs = Number(rr.failCooldownMs?.value ?? 60000)
      const kw = d.keyword || {}
      form.value.keyword.engine = kw.engine?.value || 'mysql'
      form.value.keyword.baseUrl = kw.baseUrl?.value || 'http://localhost:7700'
      form.value.keyword.apiKey = kw.apiKey?.value || ''
      form.value.keyword.timeoutMillis = Number(kw.timeoutMillis?.value ?? 1000)
      const ctx = d.context || {}
      form.value.context.modelWindows = ctx.modelWindows?.value || ''
      form.value.context.defaultWindowTokens = Number(ctx.defaultWindowTokens?.value ?? 32768)
      form.value.context.safetyFactor = Number(ctx.safetyFactor?.value ?? 0.7)
      form.value.context.costCapTokens = Number(ctx.costCapTokens?.value ?? 8000)
      form.value.context.maxOutputTokens = Number(ctx.maxOutputTokens?.value ?? 2000)
      form.value.context.historyMaxTokens = Number(ctx.historyMaxTokens?.value ?? 1200)
      form.value.context.historyPerMsgChars = Number(ctx.historyPerMsgChars?.value ?? 200)
      form.value.context.snippetWindowChars = Number(ctx.snippetWindowChars?.value ?? 150)
      form.value.context.maxContextHits = Number(ctx.maxContextHits?.value ?? 8)
      form.value.context.dedupEnabled = ctx.dedupEnabled?.value !== 'false'
      form.value.context.dedupThreshold = Number(ctx.dedupThreshold?.value ?? 0.45)
      form.value.context.dedupPathThreshold = Number(ctx.dedupPathThreshold?.value ?? 0.28)
      const dr = d.deepReasoning || {}
      form.value.deepReasoning.enabled = dr.enabled?.value === 'true'
      form.value.deepReasoning.thinkingMode = dr.thinkingMode?.value || 'model'
      form.value.deepReasoning.enableThinking = dr.enableThinking?.value === 'true'
      form.value.deepReasoning.prompt = dr.prompt?.value || ''
      form.value.deepReasoning.searchTag = dr.searchTag?.value || 'search'
      form.value.deepReasoning.maxSubQueries = Number(dr.maxSubQueries?.value ?? 3)
      form.value.deepReasoning.multiRetrieval = dr.multiRetrieval?.value === 'true'
      form.value.deepReasoning.timeoutMillis = Number(dr.timeoutMillis?.value ?? 30000)
      form.value.deepReasoning.maxThinkingTokens = Number(dr.maxThinkingTokens?.value ?? 0)
      form.value.deepReasoning.maxThinkingChars = Number(dr.maxThinkingChars?.value ?? 3000)
      form.value.deepReasoning.injectThinking = dr.injectThinking?.value !== 'false'
      form.value.deepReasoning.injectThinkingMaxChars = Number(dr.injectThinkingMaxChars?.value ?? 800)
      form.value.deepReasoning.injectKeywords = dr.injectKeywords?.value !== 'false'
      form.value.deepReasoning.injectKeywordsMax = Number(dr.injectKeywordsMax?.value ?? 5)
      form.value.deepReasoning.autoRoute = dr.autoRoute?.value === 'true'
      const rl = d.ratelimit || {}
      form.value.ratelimit.enabled = rl.enabled?.value !== 'false'
      form.value.ratelimit.chatPerMinute = Number(rl.chatPerMinute?.value ?? 10)
      form.value.ratelimit.uploadPerMinute = Number(rl.uploadPerMinute?.value ?? 10)
      const sc = d.semanticCache || {}
      form.value.semanticCache.enabled = sc.enabled?.value !== 'false'
      form.value.semanticCache.threshold = Number(sc.threshold?.value ?? 0.96)
      form.value.semanticCache.maxEntries = Number(sc.maxEntries?.value ?? 500)
      const em = d.embedding || {}
      form.value.embedding.model = em.model?.value || ''
      form.value.embedding.baseUrl = em.baseUrl?.value || ''
      form.value.embedding.apiKey = em.apiKey?.value || ''
      form.value.embedding.embeddingsPath = em.embeddingsPath?.value || ''
      // 只读：后端记录的当前索引维度（重嵌入成功后回写；空=尚未记录）
      embeddingDimensions.value = em.dimensions?.value || ''
      // 缓存统计（条数）异步刷新
      getAnswerCacheStats().then(r => { if (r.success) cacheStats.value = r.data }).catch(() => {})
      // 重嵌入状态（若后台仍在跑则自动开启轮询）
      refreshReembedStatus()
      // 保存差异基线：以"表单 → 载荷"同一管线产物为准（与后端掩码/数值归一一致）
      initialPayload.value = buildPayload()
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }
}

onMounted(async () => {
  await fetchAndFill()

  // 滚动高亮跟随：视口内最靠上的分组自动点亮对应锚点
  anchorObserver = new IntersectionObserver(entries => {
    entries.forEach(en => {
      if (en.isIntersecting) currentAnchor.value = en.target.id.replace('cfg-anchor-', '')
    })
  }, { rootMargin: '-20px 0px -70% 0px', threshold: 0 })
  anchors.forEach(a => {
    const el = document.getElementById('cfg-anchor-' + a.key)
    if (el) anchorObserver.observe(el)
  })
})

onUnmounted(() => {
  if (anchorObserver) { anchorObserver.disconnect(); anchorObserver = null }
  if (reembedTimer) { clearInterval(reembedTimer); reembedTimer = null }
})

/** 从当前表单组装提交载荷（与后端分组/掩码规则一致：掩码 apiKey 不提交） */
const buildPayload = () => ({
      chat: { model: form.value.chat.model?.trim(), baseUrl: form.value.chat.baseUrl?.trim(),
              // 掩码原样提交会覆盖真实 key：未修改（**** 开头）则不提交
              apiKey: form.value.chat.apiKey?.trim().startsWith('****') ? undefined : form.value.chat.apiKey?.trim(),
              completionsPath: form.value.chat.completionsPath?.trim(),
              temperature: String(form.value.chat.temperature),
              systemPrompt: form.value.chat.systemPrompt?.trim(),
              suggestedQuestions: form.value.chat.suggestedQuestions?.trim(),
              retrievalDebugEnabled: String(form.value.chat.retrievalDebugEnabled),
              remainTokenFloor: String(form.value.chat.remainTokenFloor),
              truncateFallbackChars: String(form.value.chat.truncateFallbackChars),
              historyRounds: String(form.value.chat.historyRounds),
              pipelineThreads: String(form.value.chat.pipelineThreads),
              streamRetryCount: String(form.value.chat.streamRetryCount),
              sseTimeoutMs: String(form.value.chat.sseTimeoutMs),
              showDebugDegradations: String(form.value.chat.showDebugDegradations),
              citationCheckEnabled: String(form.value.chat.citationCheckEnabled) },
      eval: { judgeEnabled: String(form.value.eval.judgeEnabled) },
      vision: { enabled: String(form.value.vision.enabled),
                model: form.value.vision.model?.trim(),
                baseUrl: form.value.vision.baseUrl?.trim(),
                // 掩码原样提交会覆盖真实 key：未修改（**** 开头）则不提交
                apiKey: form.value.vision.apiKey?.trim().startsWith('****') ? undefined : form.value.vision.apiKey?.trim(),
                prompt: form.value.vision.prompt?.trim(),
                concurrency: String(form.value.vision.concurrency),
                userImageConcurrency: String(form.value.vision.userImageConcurrency) },
      // 向量模型热切换：保存时后端先探测新配置，通过后自动触发全量重嵌入
      embedding: { model: form.value.embedding.model?.trim(),
                   baseUrl: form.value.embedding.baseUrl?.trim(),
                   apiKey: form.value.embedding.apiKey?.trim().startsWith('****') ? undefined : form.value.embedding.apiKey?.trim(),
                   embeddingsPath: form.value.embedding.embeddingsPath?.trim() },
      chunk: { maxChunks: String(form.value.chunk.maxChunks), maxImages: String(form.value.chunk.maxImages),
               overlap: String(form.value.chunk.overlap),
               structural: String(form.value.chunk.structural),
               structuralRatio: String(form.value.chunk.structuralRatio) },
      // parse 是独立分组（后端 key 前缀 parse.*），并发/OCR阈值/向量化重试都在这里，不可放进 chunk
      parse: { concurrency: String(form.value.parse.concurrency),
               ocrMinText: String(form.value.parse.ocrMinText),
               embedRetryCount: String(form.value.parse.embedRetryCount) },
      upload: { maxFileSize: String(form.value.upload.maxFileSizeMB * 1024 * 1024) },
      retrieval: { vectorWeight: String(form.value.retrieval.vectorWeight),
                   keywordWeight: String(form.value.retrieval.keywordWeight),
                   titleBonus: String(form.value.retrieval.titleBonus),
                   vecThreshold: String(form.value.retrieval.vecThreshold),
                   keywordLimit: String(form.value.retrieval.keywordLimit),
                   keywordTimeoutMs: String(form.value.retrieval.keywordTimeoutMs),
                   searchTimeoutMs: String(form.value.retrieval.searchTimeoutMs),
                   rewriteTimeoutMs: String(form.value.retrieval.rewriteTimeoutMs),
                   rewriteFallbackMinHits: String(form.value.retrieval.rewriteFallbackMinHits),
                   rewriteFallbackWeakScore: String(form.value.retrieval.rewriteFallbackWeakScore),
                   refDetectEnabled: String(form.value.retrieval.refDetectEnabled),
                   refDetectMention: String(form.value.retrieval.refDetectMention),
                   refExpandEnabled: String(form.value.retrieval.refExpandEnabled),
                   refExpandMaxHits: String(form.value.retrieval.refExpandMaxHits),
                   refExpandIncludeIncoming: String(form.value.retrieval.refExpandIncludeIncoming),
                   refExpandParentEnabled: String(form.value.retrieval.refExpandParentEnabled),
                   positionBonus: String(form.value.retrieval.positionBonus),
                   sectionBonus: String(form.value.retrieval.sectionBonus),
                   keywordMaxTerms: String(form.value.retrieval.keywordMaxTerms),
                   keywordMaxTotal: String(form.value.retrieval.keywordMaxTotal) },
      // rerank 是独立分组（后端 key 前缀 rerank.*），不可嵌套在 retrieval 下
      rerank: { enabled: String(form.value.retrieval.rerank.enabled),
                baseUrl: form.value.retrieval.rerank.baseUrl?.trim(),
                model: form.value.retrieval.rerank.model?.trim(),
                timeoutMillis: String(form.value.retrieval.rerank.timeoutMillis),
                minHits: String(form.value.retrieval.rerank.minHits),
                maxHits: String(form.value.retrieval.rerank.maxHits),
                failCooldownMs: String(form.value.retrieval.rerank.failCooldownMs) },
      keyword: { engine: form.value.keyword.engine,
                 baseUrl: form.value.keyword.baseUrl?.trim(),
                 // 后端 snapshot 对 apiKey 脱敏（****后4位），掩码原样提交会覆盖真实 key：未修改（**** 开头）则不提交
                 apiKey: form.value.keyword.apiKey?.trim().startsWith('****') ? undefined : form.value.keyword.apiKey?.trim(),
                 timeoutMillis: String(form.value.keyword.timeoutMillis) },
      context: { modelWindows: form.value.context.modelWindows?.trim(),
                 defaultWindowTokens: String(form.value.context.defaultWindowTokens),
                 safetyFactor: String(form.value.context.safetyFactor),
                 costCapTokens: String(form.value.context.costCapTokens),
                 maxOutputTokens: String(form.value.context.maxOutputTokens),
                 historyMaxTokens: String(form.value.context.historyMaxTokens),
                 historyPerMsgChars: String(form.value.context.historyPerMsgChars),
                 snippetWindowChars: String(form.value.context.snippetWindowChars),
                 maxContextHits: String(form.value.context.maxContextHits),
                 dedupEnabled: String(form.value.context.dedupEnabled),
                 dedupThreshold: String(form.value.context.dedupThreshold),
                 dedupPathThreshold: String(form.value.context.dedupPathThreshold) },
      deepReasoning: { enabled: String(form.value.deepReasoning.enabled),
                       thinkingMode: form.value.deepReasoning.thinkingMode,
                       enableThinking: String(form.value.deepReasoning.enableThinking),
                       prompt: form.value.deepReasoning.prompt,
                       searchTag: form.value.deepReasoning.searchTag?.trim(),
                       maxSubQueries: String(form.value.deepReasoning.maxSubQueries),
                       multiRetrieval: String(form.value.deepReasoning.multiRetrieval),
                       timeoutMillis: String(form.value.deepReasoning.timeoutMillis),
                       maxThinkingTokens: String(form.value.deepReasoning.maxThinkingTokens),
                       maxThinkingChars: String(form.value.deepReasoning.maxThinkingChars),
                       injectThinking: String(form.value.deepReasoning.injectThinking),
                       injectThinkingMaxChars: String(form.value.deepReasoning.injectThinkingMaxChars),
                       injectKeywords: String(form.value.deepReasoning.injectKeywords),
                       injectKeywordsMax: String(form.value.deepReasoning.injectKeywordsMax),
                       autoRoute: String(form.value.deepReasoning.autoRoute) },
      ratelimit: { enabled: String(form.value.ratelimit.enabled),
                   chatPerMinute: String(form.value.ratelimit.chatPerMinute),
                   uploadPerMinute: String(form.value.ratelimit.uploadPerMinute) },
      semanticCache: { enabled: String(form.value.semanticCache.enabled),
                       threshold: String(form.value.semanticCache.threshold),
                       maxEntries: String(form.value.semanticCache.maxEntries) }
    })

/** 加载完成时的基线载荷（保存差异判定用；掩码 apiKey 未改时载荷无该键，与基线一致不误报） */
const initialPayload = ref(null)
/** 有改动的分组列表（对比 form 当前载荷与基线；值经同一 buildPayload 管线归一，避免数值/空格伪差异） */
const dirtyGroups = computed(() => {
  const base = initialPayload.value
  if (!base) return []
  const cur = buildPayload()
  return Object.keys(base).filter(g => JSON.stringify(base[g]) !== JSON.stringify(cur[g]))
})
const dirtyCount = computed(() => dirtyGroups.value.length)

// ===== 恢复本组默认 =====
const resettingKey = ref('')
const groupLabel = key => (anchors.find(a => a.key === key) || {}).label || key
const doResetGroup = async (key, label) => {
  resettingKey.value = key
  try {
    const r = await resetConfig([key])
    if (r.success) {
      const cnt = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      message.success(`「${label}」已恢复默认（${cnt} 项）`)
      await fetchAndFill() // 回填 + 重建保存基线
    } else message.error(r.msg || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { resettingKey.value = '' }
}
const onResetGroup = key => {
  const label = groupLabel(key)
  Modal.confirm({
    title: `恢复「${label}」为默认值？`,
    content: '该组当前的自定义值会被覆盖为出厂默认（模型 API Key 与向量模型组不受影响）。',
    okText: '恢复',
    cancelText: '取消',
    onOk: () => doResetGroup(key, label)
  })
}

const save = async () => {
  const dirty = dirtyGroups.value
  if (!dirty.length) { message.info('没有需要保存的改动'); return }
  saving.value = true
  try {
    const r = await saveConfig(buildPayload())
    if (r.success) {
      const n = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      // N=0 即"假保存"哨兵：后端白名单未命中任何键时给出明确提示而非"已保存"误导
      if (n === 0) message.warning('没有可保存的配置项（后端未识别提交的键），请检查后重试')
      else { message.success(`配置已保存并生效（更新 ${n} 项）`); initialPayload.value = buildPayload() }
      // 若触发了向量模型切换，重嵌入任务已自动启动（状态轮询自动开启）
      refreshReembedStatus()
    }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}
</script>

<style scoped>
.tip-icon {
  color: #bbb;
  font-size: 12px;
  margin-left: 4px;
  cursor: help;
}
.tip-icon:hover {
  color: #1677ff;
}
/* 折叠面板：去掉卡片默认背景与边框，保持与页面一致的浅色观感 */
.cfg-collapse {
  background: transparent;
}
/* 分区锚点导航条：吸顶常驻（滚动时保持可见，随时可跳任意分组） */
.cfg-anchor {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 6px;
  margin-bottom: 14px;
  padding: 8px 12px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  position: sticky;
  top: 0;
  z-index: 10;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}
.cfg-anchor a {
  font-size: 13px;
  color: #555;
  padding: 3px 10px;
  border-radius: 12px;
  text-decoration: none;
  transition: all 0.2s;
}
.cfg-anchor a:hover {
  color: #1677ff;
  background: #e6f4ff;
}
.cfg-anchor a.anchor-active {
  color: #fff;
  background: #1677ff;
}
.cfg-collapse :deep(.ant-collapse-item) {
  background: #fff;
  border-radius: 8px;
  margin-bottom: 12px;
  border: 1px solid #f0f0f0;
}
.cfg-collapse :deep(.ant-collapse-header) {
  font-weight: 500;
}
.dirty-tip {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  color: #ad6800;
  border-radius: 6px;
  padding: 6px 12px;
  margin: 0 0 12px;
  font-size: 13px;
}
.cfg-sub {
  margin: 4px 0 10px;
  padding: 2px 0 2px 8px;
  border-left: 3px solid #1677ff;
  color: #4a5568;
  font-size: 12.5px;
  font-weight: 500;
  background: #f6f8fa;
  border-radius: 0 4px 4px 0;
}
.reset-group-btn {
  font-size: 12px;
  color: #8c8c8c;
  margin-right: 4px;
}
.reset-group-btn:hover {
  color: #d4380d !important;
}
/* 测试连接结果标记：紧凑小标签，hover 看详情 */
.probe-chip {
  margin-left: 8px;
  padding: 4px 6px;
  font-size: 12px;
  line-height: 1;
  border-radius: 3px;
  border: 1px solid transparent;
  cursor: default;
  white-space: nowrap;
}
.probe-ok {
  color: #389e0d;
  background: #f6ffed;
  border-color: #b7eb8f;
}
.probe-bad {
  color: #cf1322;
  background: #fff1f0;
  border-color: #ffa39e;
}
</style>
