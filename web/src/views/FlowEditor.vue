<template>
  <!-- 工作流画布编辑器（M2）：DSL 是唯一真源，画布只是编辑器——
       加载即还原图形（坐标读 DSL position），保存即序列化回 DSL（坐标写回 position）。
       连线校验：禁悬空/自环、start 无入边、end 无出边、非 loop 禁成环、条件出边必须挂已声明分支。 -->
  <div class="wf-editor">
    <div class="wf-toolbar">
      <button class="app-btn ghost small wf-tb-back" @click="$emit('back')"><arrow-left-outlined /> 返回</button>
      <a-input v-model:value="name" class="wf-name" :maxlength="100" placeholder="工作流名称" @change="dirty = true" />
      <div class="wf-tb-status">
        <span class="wf-dot" :class="dirty ? 'dot-dirty' : 'dot-saved'" />
        <span class="wf-save-hint">{{ dirty ? '有未保存改动' : '已保存' }}</span>
        <span class="wf-tb-sep" />
        <a-tag v-if="publishedVersion != null" color="green" class="wf-ver">已发布 v{{ publishedVersion }}</a-tag>
        <a-tag v-else-if="workflowId" class="wf-ver">草稿</a-tag>
      </div>
      <span class="flex-gap"></span>
      <div class="wf-tb-actions">
        <button class="app-btn ghost small" :disabled="busy" @click="doValidate">校验</button>
        <button class="app-btn ghost small" :disabled="busy" @click="showHistory">运行历史</button>
        <button class="app-btn small" :disabled="busy || !workflowId" @click="openRun">
          <play-circle-outlined /> 运行
        </button>
        <button class="app-btn small" :disabled="busy" @click="doSave">保存</button>
        <!-- M4：发布把当前草稿冻结成一个版本（API 触发 / 智能体绑定跑的是已发布版本，草稿继续可改） -->
        <button class="app-btn primary small" :disabled="busy || !workflowId" @click="doPublish">
          <cloud-upload-outlined /> {{ publishedVersion != null ? '发布新版本' : '发布' }}
        </button>
      </div>
    </div>

    <div class="wf-body">
      <!-- 左侧节点面板：按用途分组，拖拽到画布或点击添加（瀑布排布） -->
      <div class="wf-palette">
        <div class="wf-palette-title">节点</div>
        <template v-for="g in PALETTE_GROUPS" :key="g.title">
          <div class="wf-palette-group">{{ g.title }}</div>
          <div v-for="t in g.types" :key="t" class="wf-palette-item" :class="{ disabled: t === 'start' && hasStart }"
               :title="TYPE_META[t].brief"
               draggable="true" @dragstart="onDragStart($event, t)" @click="addNodeAt(t)">
            <span class="wf-node-ico" :style="{ background: tint(colorOf(t)), color: colorOf(t) }">
              <span class="wf-node-icon">{{ TYPE_META[t].icon }}</span>
            </span>
            <span class="wf-palette-name">{{ TYPE_META[t].label }}</span>
          </div>
        </template>
        <div class="wf-palette-hint">拖拽或点击添加；删除选中后按 Delete；右键节点 / 连线 / 画布有快捷菜单。</div>
      </div>

      <!-- 画布（网格吸附 16px：节点落点整齐；右下角小地图 + 缩放控件） -->
      <div class="wf-canvas-wrap" @drop="onDrop" @dragover.prevent>
        <VueFlow v-model:nodes="nodes" v-model:edges="edges"
                 :delete-key-code="['Backspace', 'Delete']"
                 :default-edge-options="EDGE_OPTIONS"
                 :snap-to-grid="true" :snap-grid="[16, 16]"
                 :min-zoom="0.2" :max-zoom="2"
                 @connect="onConnect" @node-click="onNodeClick" @pane-click="closeDrawer"
                 @nodes-change="onNodesChange" @edges-change="onEdgesChange"
                 @node-context-menu="onNodeContextMenu" @edge-context-menu="onEdgeContextMenu"
                 @pane-context-menu="onPaneContextMenu">
          <Background :gap="16" pattern-color="#d6dae2" :size="1.2" />
          <MiniMap class="wf-minimap" :node-color="minimapColor" pannable zoomable />
          <Controls class="wf-controls" :show-interactive="false" />
          <template #node-wf="props">
            <div class="wf-node" :style="{ '--nc': colorOf(props.data.nodeType) }"
                 :class="[`wf-${props.data.nodeType}`, props.data.run ? `run-${props.data.run.status}` : '']">
              <Handle v-if="props.data.nodeType !== 'start'" type="target" :position="Position.Left" />
              <Handle v-if="props.data.nodeType !== 'end'" type="source" :position="Position.Right" />
              <div class="wf-node-head">
                <span class="wf-node-ico" :style="{ background: tint(colorOf(props.data.nodeType)), color: colorOf(props.data.nodeType) }">
                  <span class="wf-node-icon">{{ TYPE_META[props.data.nodeType]?.icon }}</span>
                </span>
                <div class="wf-node-titles">
                  <span class="wf-node-label">{{ props.data.label }}</span>
                  <!-- 节点 id 直接可见：变量引用 {{id.key}} 要用到它，不用回抽屉里翻 -->
                  <span class="wf-node-id">{{ props.id }}</span>
                </div>
              </div>
              <div class="wf-node-sub">{{ subText(props.data) }}</div>
              <div v-if="props.data.run" class="wf-node-badge" :class="`badge-${props.data.run.status}`">
                {{ props.data.run.status === 'success' ? '✓' : props.data.run.status === 'waiting' ? '✋' : '✕' }} {{ fmtMs(props.data.run.elapsedMs) }}
              </div>
            </div>
          </template>
        </VueFlow>
      </div>
    </div>

    <!-- 右侧抽屉：属性编辑 / 运行详情 -->
    <a-drawer v-model:open="drawer" :title="selected ? `${TYPE_META[selected.data.nodeType]?.label || ''} · ${selected.id}` : ''"
              width="460" destroy-on-close @close="closeDrawer">
      <a-tabs v-if="selected" v-model:activeKey="drawerTab">
        <a-tab-pane key="config" tab="属性">
          <div class="wf-form">
            <!-- 节点说明卡：类型色图标 + 一句话作用 + 输出/入参键（下游引用什么一眼看到，不必猜） -->
            <div class="wf-node-brief" :style="{ '--nc': colorOf(selected.data.nodeType) }">
              <span class="wf-node-ico" :style="{ background: tint(colorOf(selected.data.nodeType)), color: colorOf(selected.data.nodeType) }">
                <span class="wf-node-icon">{{ TYPE_META[selected.data.nodeType]?.icon }}</span>
              </span>
              <div class="wf-brief-body">
                <div class="wf-brief-title">
                  {{ TYPE_META[selected.data.nodeType]?.label }}
                  <span class="wf-node-id">{{ selected.id }}</span>
                </div>
                <div class="wf-brief-desc">{{ TYPE_META[selected.data.nodeType]?.brief }}</div>
                <div v-if="briefOut.length" class="wf-brief-out">
                  <span class="wf-out-label">{{ selected.data.nodeType === 'start' ? '入参' : '输出' }}</span>
                  <code v-for="k in briefOut" :key="k">{{ k }}</code>
                </div>
              </div>
            </div>

            <!-- start：入参列表 -->
            <template v-if="selected.data.nodeType === 'start'">
              <div class="wf-form-item"><label>入参定义</label></div>
              <div v-for="(p, i) in editConfig.inputs" :key="i" class="wf-rows">
                <a-input v-model:value="p.key" placeholder="参数名，如 question" class="wf-row-grow" />
                <!-- 必填：checkbox 文字禁换行（行内空间紧张时"必填"会被压成竖排） -->
                <a-checkbox v-model:checked="p.required" class="wf-req-cb" title="运行时此入参必填">必填</a-checkbox>
                <button class="app-btn ghost small wf-row-del" @click="editConfig.inputs.splice(i, 1)">删</button>
              </div>
              <button class="app-btn ghost small" @click="editConfig.inputs.push({ key: '', required: true })">+ 添加入参</button>
              <div class="wf-hint">入参在点「运行」时填写；后续节点用 <code v-pre>{{start.参数名}}</code> 引用。</div>
            </template>

            <!-- end：出参映射 -->
            <template v-else-if="selected.data.nodeType === 'end'">
              <div class="wf-form-item"><label>出参映射</label></div>
              <div v-for="(o, i) in editConfig.outputs" :key="i" class="wf-rows">
                <a-input v-model:value="o.key" placeholder="输出名，如 answer" class="wf-row-key" />
                <a-input v-model:value="o.value" class="wf-row-value"
                         :placeholder="LBB + 'llm.answer' + RBB + ' 或固定文本'" />
                <button class="app-btn ghost small wf-row-del" @click="editConfig.outputs.splice(i, 1)">删</button>
              </div>
              <button class="app-btn ghost small" @click="editConfig.outputs.push({ key: '', value: '' })">+ 添加出参</button>
              <div class="wf-hint">值可写变量引用 <code v-pre>{{nodeId.key}}</code> 或固定文本；对话型绑定取名为 <code>answer</code> 的出参作回答。</div>
            </template>

            <!-- llm -->
            <template v-else-if="selected.data.nodeType === 'llm'">
              <div class="wf-form-item"><label>模型</label>
                <ModelSelect v-model="editConfig.modelRef" type="chat" width="100%" inherit-label="跟随个人默认模型" />
              </div>
              <div class="wf-form-item"><label>Prompt（支持 <code v-pre>{{nodeId.key}}</code> 引用，可直接选）</label>
                <a-textarea v-model:value="editConfig.prompt" :rows="7"
                            :placeholder="'如：基于以下资料回答问题：{{retrieval.text}}\n\n问题：{{start.question}}'" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('prompt', v, '\n')" />
              </div>
              <div class="wf-form-item"><label>温度（留空跟随全局）</label>
                <a-input-number v-model:value="editConfig.temperature" :min="0" :max="2" :step="0.1" style="width: 140px" />
              </div>
              <div class="wf-form-item"><label>超时（秒，留空默认 600；到时按节点失败终止）</label>
                <a-input-number v-model:value="editConfig.timeoutSeconds" :min="1" :max="3600" style="width: 140px" />
              </div>
              <div class="wf-hint">后续节点用 <code>{{ LBB + selected.id + '.answer' + RBB }}</code> 引用本节点的回答。</div>
            </template>

            <!-- retrieval -->
            <template v-else-if="selected.data.nodeType === 'retrieval'">
              <div class="wf-form-item"><label>检索词（支持 <code v-pre>{{nodeId.key}}</code> 引用，可直接选）</label>
                <a-input v-model:value="editConfig.query" :placeholder="'如：{{start.question}}'" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('query', v, ' ')" />
              </div>
              <div class="wf-form-item"><label>知识库范围（不选 = 全部）</label>
                <a-select v-model:value="editConfig.kbIds" mode="multiple" style="width: 100%" allow-clear
                          placeholder="全部知识库" :options="kbOptions" />
              </div>
              <div class="wf-form-item"><label>topK（召回条数）</label>
                <a-input-number v-model:value="editConfig.topK" :min="1" :max="20" style="width: 140px" />
              </div>
              <div class="wf-form-item"><label>低分过滤 minScore（留空跟随全局检索设置；0=不过滤）</label>
                <a-input-number v-model:value="editConfig.minScore" :min="0" :max="2" :step="0.05" style="width: 140px" />
                <div class="wf-hint">排序分（重排分，未启用重排时为融合分）低于它的块不进结果也不占 topK 名额。</div>
              </div>
              <div class="wf-hint"><code>text</code> 是可直接拼进 prompt 的拼接文本；<code>chunks</code> 保留结构（标题/路径/得分），<code>count</code> 是命中数。</div>
            </template>

            <!-- condition -->
            <template v-else-if="selected.data.nodeType === 'condition'">
              <div class="wf-form-item"><label>分支（按声明序求值，首个命中生效）</label></div>
              <div v-for="(b, i) in editConfig.branches" :key="i" class="wf-branch">
                <div class="wf-rows">
                  <a-input v-model:value="b.key" placeholder="分支键，如 ok" class="wf-row-key" />
                  <button class="app-btn ghost small" @click="removeBranch(i)">删</button>
                </div>
                <a-input v-if="b.key !== 'else'" v-model:value="b.expr"
                         :placeholder="'表达式，如 {{llm.answer}}.length() > 0'" />
                <div v-else class="wf-hint" style="margin-top:4px">else 为兜底分支，无需表达式。</div>
              </div>
              <button class="app-btn ghost small" @click="editConfig.branches.push({ key: '', expr: '' })">+ 添加分支</button>
              <div class="wf-hint">
                表达式为 SpEL 安全子集：引用写 <code v-pre>{{nodeId.key}}</code>；可用
                <code>.length()</code>、<code>.contains('…')</code>、<code>.isEmpty()</code>、比较与 and/or/not（not 取反要加括号）。
                每个分支需连一条出边（连线时选分支键）。
              </div>
            </template>

            <!-- http -->
            <template v-else-if="selected.data.nodeType === 'http'">
              <div class="wf-form-item"><label>请求方法</label>
                <a-select v-model:value="editConfig.method" style="width: 140px"
                          :options="['GET', 'POST', 'PUT', 'DELETE'].map(m => ({ value: m, label: m }))" />
              </div>
              <div class="wf-form-item"><label>URL（仅公网 http/https，重定向逐跳校验）</label>
                <a-input v-model:value="editConfig.url" :placeholder="'https://api.example.com/data?q={{start.question}}'" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('url', v, '')" />
              </div>
              <div class="wf-form-item"><label>请求头</label></div>
              <div v-for="(h, i) in editConfig.headers" :key="i" class="wf-rows">
                <a-input v-model:value="h.key" placeholder="Header" class="wf-row-key" />
                <a-input v-model:value="h.value" placeholder="值（支持引用）" class="wf-row-value" />
                <button class="app-btn ghost small" @click="editConfig.headers.splice(i, 1)">删</button>
              </div>
              <button class="app-btn ghost small" @click="editConfig.headers.push({ key: '', value: '' })">+ 添加请求头</button>
              <div v-if="editConfig.method !== 'GET'" class="wf-form-item" style="margin-top:8px"><label>请求体（支持引用）</label>
                <a-textarea v-model:value="editConfig.body" :rows="4" placeholder="原始请求体，如 JSON" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('body', v, '\n')" />
              </div>
              <div class="wf-form-item"><label>超时（ms，1s~60s）</label>
                <a-input-number v-model:value="editConfig.timeoutMs" :min="1000" :max="60000" :step="1000" style="width: 140px" />
              </div>
              <div class="wf-hint">响应体进 state 时截断 2 万字符（trace 同步截断）。</div>
            </template>

            <!-- code -->
            <template v-else-if="selected.data.nodeType === 'code'">
              <div class="wf-form-item"><label>语言</label>
                <a-select v-model:value="editConfig.language" style="width: 140px"
                          :options="[{ value: 'python', label: 'Python' }, { value: 'node', label: 'Node' }]" />
              </div>
              <div class="wf-form-item"><label>代码（原样执行，不做变量渲染）</label>
                <a-textarea v-model:value="editConfig.code" :rows="10"
                            :placeholder='"# Python 示例\nvalue = \"处理结果\"\nprint(value)"' />
              </div>
              <div class="wf-form-item"><label>超时（秒，1~300）</label>
                <a-input-number v-model:value="editConfig.timeoutSeconds" :min="1" :max="300" style="width: 140px" />
              </div>
              <div class="wf-hint">
                依赖沙盒（设置页「沙盒工具」开关）；非 0 退出码视为节点失败，stdout 在 <code>output</code> 里。
              </div>
            </template>

            <!-- subagent -->
            <template v-else-if="selected.data.nodeType === 'subagent'">
              <div class="wf-form-item"><label>智能体（可见性按运行发起人）</label>
                <a-select v-model:value="editConfig.agentId" style="width: 100%" show-search option-filter-prop="label"
                          placeholder="选择要委派的智能体" :options="agentOptions" />
              </div>
              <div class="wf-form-item"><label>指令（支持 <code v-pre>{{nodeId.key}}</code> 引用，可直接选）</label>
                <a-textarea v-model:value="editConfig.prompt" :rows="6"
                            :placeholder="'如：请基于你的知识库回答：{{start.question}}'" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('prompt', v, '\n')" />
              </div>
              <div class="wf-form-item"><label>模型（留空走发起人个人默认）</label>
                <ModelSelect v-model="editConfig.modelRef" type="chat" width="100%" inherit-label="跟随个人默认模型" />
              </div>
              <div class="wf-hint">复用该智能体完整问答管线（知识库范围/工具/技能原样生效），回答全文在 <code>answer</code>。</div>
            </template>

            <!-- approval -->
            <template v-else-if="selected.data.nodeType === 'approval'">
              <div class="wf-form-item"><label>审批提示（给人看的内容，支持引用）</label>
                <a-textarea v-model:value="editConfig.prompt" :rows="5"
                            :placeholder="'如：以下回答即将交付，请确认：\n{{llm.answer}}'" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('prompt', v, '\n')" />
              </div>
              <div class="wf-form-item"><label>超时（秒，超时按拒绝终止本轮运行）</label>
                <a-input-number v-model:value="editConfig.timeoutSeconds" :min="30" :max="86400" style="width: 160px" />
              </div>
              <div class="wf-hint">
                分支固定为 <code>approve</code> / <code>reject</code>，各连一条出边（批准走 approve、拒绝走 reject）。
                运行到此挂起，在运行详情的审批卡上裁决后从断点续跑（已完成节点不重复执行）。
              </div>
            </template>

            <!-- loop -->
            <template v-else-if="selected.data.nodeType === 'loop'">
              <div class="wf-form-item"><label>最大迭代次数（防回跳失控）</label>
                <a-input-number v-model:value="editConfig.maxLoops" :min="1" :max="100" style="width: 140px" />
              </div>
              <div class="wf-form-item"><label>分支（按声明序求值；某分支的出边指回前面的节点即构成循环）</label></div>
              <div v-for="(b, i) in editConfig.branches" :key="i" class="wf-branch">
                <div class="wf-rows">
                  <a-input v-model:value="b.key" placeholder="分支键，如 retry" class="wf-row-key" />
                  <button class="app-btn ghost small" @click="removeBranch(i)">删</button>
                </div>
                <a-input v-if="b.key !== 'else'" v-model:value="b.expr"
                         :placeholder="'表达式，如 {{llm.answer}}.contains(\'DONE\') == false'" />
                <div v-else class="wf-hint" style="margin-top:4px">else 为兜底分支，无需表达式。</div>
              </div>
              <button class="app-btn ghost small" @click="editConfig.branches.push({ key: '', expr: '' })">+ 添加分支</button>
              <div class="wf-hint">
                表达式语法同条件分支；超过最大迭代次数即失败终止（当前轮次可见于 trace 的 loopCount）。
              </div>
            </template>

            <!-- template -->
            <template v-else-if="selected.data.nodeType === 'template'">
              <div class="wf-form-item"><label>模板（聚合多路上游输出拼 prompt）</label>
                <a-textarea v-model:value="editConfig.template" :rows="8"
                            :placeholder="'视角一要点：{{subagent_1.answer}}\n\n视角二要点：{{subagent_2.answer}}\n\n请综合以上视角回答：{{start.question}}'" />
                <a-select v-if="refGroups.length" class="wf-ref-pick" size="small" :value="null"
                          :options="refGroups" show-search option-filter-prop="label"
                          placeholder="+ 插入上游引用（选节点 · 输出键）" @change="v => insertRef('template', v, '\n')" />
              </div>
              <div class="wf-hint">渲染结果在 <code>text</code>，多视角汇总后接 LLM 或直接作答都行。</div>
            </template>
          </div>
          <div class="wf-drawer-actions">
            <button class="app-btn small" @click="applyConfig">应用到画布</button>
          </div>
        </a-tab-pane>

        <a-tab-pane key="run" tab="运行输出">
          <!-- 人工审核审批卡：run 挂起在 waiting_approval 时出现，裁决即续跑 -->
          <div v-if="runResult?.status === 'waiting_approval' && approvalInfo" class="wf-approval-card">
            <div class="wf-approval-head"><span class="wf-node-ico" style="background: rgba(250, 140, 22, 0.12); color: #d4380d"><span class="wf-node-icon">✋</span></span> 等待人工审核</div>
            <div class="wf-approval-prompt">{{ approvalInfo.prompt || approvalInfo.requestArgs }}</div>
            <div class="wf-approval-meta">
              <span v-if="approvalInfo.createdAt">挂起于 {{ fmtTime(approvalInfo.createdAt) }}</span>
              <span>超时按拒绝终止本轮</span>
            </div>
            <div class="wf-approval-actions">
              <button class="app-btn small" :disabled="busy" @click="doApprove(true)">✓ 批准（approve）</button>
              <button class="app-btn danger small" :disabled="busy" @click="doApprove(false)">✕ 拒绝（reject）</button>
            </div>
          </div>
          <template v-if="nodeTrace">
            <div class="wf-trace-meta">
              <a-tag :color="traceTagColor(nodeTrace.status)">{{ traceTagColor(nodeTrace.status) === 'green' ? '成功' : traceTagColor(nodeTrace.status) === 'red' ? '失败' : '等待审核' }}</a-tag>
              <span>耗时 {{ fmtMs(nodeTrace.elapsedMs) }}</span>
              <span v-if="nodeTrace.promptTokens != null">入 {{ nodeTrace.promptTokens }} tok</span>
              <span v-if="nodeTrace.completionTokens != null">出 {{ nodeTrace.completionTokens }} tok</span>
            </div>
            <div v-if="nodeTrace.error" class="wf-trace-error">{{ nodeTrace.error }}</div>
            <div v-if="nodeTrace.input && Object.keys(nodeTrace.input).length" class="wf-trace-block">
              <div class="wf-trace-label">输入</div>
              <pre class="wf-trace-pre">{{ JSON.stringify(nodeTrace.input, null, 2) }}</pre>
            </div>
            <div v-if="nodeTrace.output" class="wf-trace-block">
              <div class="wf-trace-label">输出</div>
              <pre class="wf-trace-pre">{{ JSON.stringify(nodeTrace.output, null, 2) }}</pre>
            </div>
          </template>
          <div v-else class="wf-hint">还没有运行记录——点工具栏「运行」试一次。</div>
        </a-tab-pane>
      </a-tabs>
    </a-drawer>

    <!-- 条件出边选分支 -->
    <a-modal v-model:open="branchPick" title="选择分支" ok-text="连上" cancel-text="取消" @ok="confirmBranch" @cancel="pendingConn = null">
      <div class="wf-hint" style="margin-bottom:8px">「{{ pendingNodeLabel }}」到目标的连线挂在哪个分支上？</div>
      <a-radio-group v-model:value="pendingBranch" style="width:100%">
        <div v-for="b in pendingBranchOptions" :key="b.key" class="wf-branch-radio">
          <a-radio :value="b.key" :disabled="b.used">{{ b.key }}{{ b.used ? '（已连线）' : '' }}</a-radio>
        </div>
      </a-radio-group>
    </a-modal>

    <!-- 运行入参 -->
    <a-modal v-model:open="runModal" title="运行工作流" ok-text="运行" cancel-text="取消"
             :confirm-loading="busy" @ok="doRun">
      <div v-if="startInputs.length" class="wf-run-form">
        <div v-for="p in startInputs" :key="p.key" class="wf-form-item">
          <label>{{ p.key }}<span v-if="p.required" class="wf-req">*</span></label>
          <a-input v-model:value="runInputs[p.key]" placeholder="该入参的值" />
        </div>
      </div>
      <div v-else class="wf-hint">开始节点未定义入参，直接运行。</div>
    </a-modal>

    <!-- 运行历史 -->
    <a-modal v-model:open="historyModal" title="运行历史" :footer="null" width="720px">
      <a-spin :spinning="historyLoading">
        <div v-if="!history.length" class="wf-hint">还没有运行记录</div>
        <div v-else class="wf-history">
          <div v-for="r in history" :key="r.id" class="wf-history-item" @click="openRunDetail(r)">
            <div class="wf-history-head">
              <a-tag :color="runColor(r.status)">{{ runLabel(r.status) }}</a-tag>
              <span class="wf-history-meta">{{ r.triggerType === 'manual' ? '手动' : r.triggerType }}</span>
              <span class="wf-history-meta">{{ fmtTime(r.startedAt) }}</span>
              <span class="wf-history-meta">{{ r.durationMs != null ? fmtMs(r.durationMs) : '' }}</span>
              <a class="wf-history-link">查看节点 trace →</a>
            </div>
            <div v-if="r.error" class="wf-trace-error">{{ r.error }}</div>
          </div>
        </div>
      </a-spin>
    </a-modal>

    <!-- 右键菜单：节点 / 连线 / 画布空白，Teleport 到 body（fixed 定位不受祖先 transform 影响） -->
    <Teleport to="body">
      <div v-if="ctxMenu.open" ref="ctxMenuEl" class="wf-ctx-menu"
           :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }" @contextmenu.prevent>
        <!-- 节点右键 -->
        <template v-if="ctxMenu.kind === 'node'">
          <div class="wf-ctx-item" @click="ctxOpenProps"><span class="wf-ctx-ico">⚙</span>打开属性</div>
          <div class="wf-ctx-item" :class="{ disabled: ctxNodeIsStart }" @click="ctxCopyNode">
            <span class="wf-ctx-ico">⧉</span>复制节点
          </div>
          <div class="wf-ctx-sep" />
          <div class="wf-ctx-item" @click="ctxDeleteNode">
            <span class="wf-ctx-ico">✕</span>删除节点<span class="wf-ctx-key">Del</span>
          </div>
        </template>
        <!-- 连线右键 -->
        <template v-else-if="ctxMenu.kind === 'edge'">
          <div class="wf-ctx-item" @click="ctxDeleteEdge">
            <span class="wf-ctx-ico">✕</span>删除连线<span class="wf-ctx-key">Del</span>
          </div>
        </template>
        <!-- 画布空白右键 -->
        <template v-else>
          <div class="wf-ctx-item has-sub"><span class="wf-ctx-ico">✚</span>添加节点<span class="wf-ctx-arrow">▸</span>
            <div class="wf-ctx-sub">
              <div v-for="t in PALETTE" :key="t.type" class="wf-ctx-item"
                   :class="{ disabled: t.type === 'start' && hasStart }" @click="ctxAddNode(t.type)">
                <span class="wf-ctx-ico">{{ t.icon }}</span>{{ t.label }}
              </div>
            </div>
          </div>
          <div v-if="nodeClipboard" class="wf-ctx-item" @click="ctxPaste"><span class="wf-ctx-ico">⎘</span>粘贴节点</div>
          <div class="wf-ctx-sep" />
          <div class="wf-ctx-item" @click="ctxSelectAll"><span class="wf-ctx-ico">☐</span>全选节点</div>
          <div class="wf-ctx-sep" />
          <div class="wf-ctx-item" @click="ctxFitView"><span class="wf-ctx-ico">⤢</span>适应视图</div>
          <div class="wf-ctx-item" @click="ctxZoomIn"><span class="wf-ctx-ico">⊕</span>放大</div>
          <div class="wf-ctx-item" @click="ctxZoomOut"><span class="wf-ctx-ico">⊖</span>缩小</div>
          <div class="wf-ctx-item" @click="ctxResetZoom"><span class="wf-ctx-ico">◎</span>重置缩放</div>
        </template>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, watch, onBeforeUnmount } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ArrowLeftOutlined, PlayCircleOutlined, CloudUploadOutlined } from '@ant-design/icons-vue'
import { VueFlow, useVueFlow, MarkerType, Handle, Position } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/minimap/dist/style.css'
import '@vue-flow/controls/dist/style.css'
import ModelSelect from '../components/ModelSelect.vue'
import {
  getWorkflow, createWorkflow, updateWorkflow, validateWorkflowDsl,
  runWorkflow, listWorkflowRuns, getWorkflowRun, getWorkflowPendingApproval,
  listKnowledgeBases, listAvailableAgents, resolveWorkflowApproval, publishWorkflow
} from '../api'

const props = defineProps({
  workflowId: { type: String, default: '' }   // 空 = 新建
})
const emit = defineEmits(['back', 'saved'])

const { addEdges, removeEdges, updateNodeData, findNode, screenToFlowCoordinate, fitView, zoomIn, zoomOut, zoomTo, addSelectedNodes } = useVueFlow()

// 模板里要展示「字面量大括号」时拼这两个常量——不能写 {{ '{{' }}：模板 tokenizer 不识别引号，
// 会在字符串内部的第一个 }} 处截断插值（实测编译报 Unterminated string constant）
const LBB = '{{'
const RBB = '}}'

/**
 * 连线默认样式：只给箭头，不给 style——style 会被 vue-flow 写成 path 上的<b>内联样式</b>，
 * 内联优先级高于 CSS 类，选中/悬停的加粗提色就永远赢不了它（线宽与颜色统一走 CSS）。
 */
const EDGE_OPTIONS = { markerEnd: MarkerType.ArrowClosed }
/** 小地图节点取色：与画布节点同色（未配置类型回落灰） */
const minimapColor = n => colorOf(n?.data?.nodeType)

// ---------- 类型元数据与面板 ----------
// color：节点/面板/抽屉统一的类型色（11 类全给色——此前只有 5 类有图标色，新加的 6 类在画布上分不出类型）
const TYPE_META = {
  start: { label: '开始', icon: '▶', color: '#52c41a', brief: '工作流入口，声明本轮可用的入参', out: ['入参键'] },
  end: { label: '结束', icon: '■', color: '#8c8c8c', brief: '工作流出口，映射最终出参', out: [] },
  llm: { label: 'LLM 调用', icon: '✦', color: '#7c5cff', brief: '把 prompt 发给对话模型，拿回答', out: ['answer'] },
  retrieval: { label: '知识检索', icon: '⌕', color: '#1677ff', brief: '按检索词在知识库里召回片段', out: ['chunks', 'text', 'count'] },
  condition: { label: '条件分支', icon: '⑂', color: '#fa8c16', brief: '按表达式命中分支，走不同路径', out: ['route'] },
  http: { label: 'HTTP 请求', icon: '⇄', color: '#13c2c2', brief: '调公网接口取数据（逐跳 SSRF 校验）', out: ['status', 'body', 'contentType'] },
  code: { label: '代码执行', icon: '⌨', color: '#eb2f96', brief: '在沙盒容器里跑 Python / Node', out: ['output', 'exitCode'] },
  subagent: { label: '子智能体', icon: '＠', color: '#2f54eb', brief: '委派某个智能体按它自己的配置作答', out: ['answer'] },
  approval: { label: '人工审核', icon: '✋', color: '#d4380d', brief: '挂起等人拍板，批准/拒绝各走一支', out: ['route'] },
  loop: { label: '循环', icon: '↻', color: '#d4b106', brief: '按分支回跳重跑，最多 maxLoops 轮', out: ['route', 'loopCount'] },
  template: { label: '模板转换', icon: '✎', color: '#08979c', brief: '把多路上游输出拼成一段文本', out: ['text'] }
}
const PALETTE = ['start', 'end', 'llm', 'retrieval', 'condition', 'http', 'code', 'subagent', 'approval', 'loop', 'template']
  .map(t => ({ type: t, ...TYPE_META[t] }))
/** 左侧节点面板分组（11 类平铺太散，按用途分组更好找） */
const PALETTE_GROUPS = [
  { title: '流程', types: ['start', 'end'] },
  { title: '模型', types: ['llm', 'subagent'] },
  { title: '数据', types: ['retrieval', 'http', 'code', 'template'] },
  { title: '控制', types: ['condition', 'approval', 'loop'] }
]
/** 类型色（未知类型回落灰） */
const colorOf = t => (TYPE_META[t]?.color) || '#8c8c8c'
/** #rrggbb → rgba(...)（图标底衬用，避免依赖 color-mix 的浏览器支持） */
function tint(hex, alpha = 0.12) {
  const h = String(hex || '').replace('#', '')
  if (h.length !== 6) return 'transparent'
  const n = parseInt(h, 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`
}

// ---------- 状态 ----------
const nodes = ref([])
const edges = ref([])
const name = ref('')
const workflowId = ref(props.workflowId)
const dirty = ref(false)
const busy = ref(false)
const drawer = ref(false)
const drawerTab = ref('config')
const selectedId = ref('')
const editConfig = ref({})
const kbOptions = ref([])
const agentOptions = ref([])
const runResult = ref(null)     // {status, outputs, error, traces, runId}
const approvalInfo = ref(null)  // waiting_approval 时的待审批信息（prompt/timeoutSeconds）
const historyModal = ref(false)
const historyLoading = ref(false)
const history = ref([])
const runModal = ref(false)
const runInputs = reactive({})
const branchPick = ref(false)
const pendingConn = ref(null)
const pendingBranch = ref('')
const savedDsl = ref('')        // 上次保存的 DSL 文本（脏检测）
const publishedVersion = ref(null)  // M4：当前发布版本号（null=未发布）

const selected = computed(() => (selectedId.value && findNode(selectedId.value)) || null)
const hasStart = computed(() => nodes.value.some(n => n.data.nodeType === 'start'))
const startInputs = computed(() => {
  const s = nodes.value.find(n => n.data.nodeType === 'start')
  return (s && s.data.config.inputs) || []
})
const nodeTrace = computed(() => {
  if (!selectedId.value || !runResult.value) return null
  const list = runResult.value.traces.filter(t => t.nodeId === selectedId.value)
  return list.length ? list[list.length - 1] : null
})
const pendingBranchOptions = computed(() => {
  const c = pendingConn.value
  if (!c) return []
  const node = findNode(c.source)
  const declared = (node && node.data.config.branches) || []
  const wired = new Set(edges.value.filter(e => e.source === c.source).map(e => e.data && e.data.branch))
  return declared.map(b => ({ key: b.key, used: wired.has(b.key) }))
})
const pendingNodeLabel = computed(() => {
  const c = pendingConn.value
  const node = c && findNode(c.source)
  return node ? node.data.label : ''
})

/** 抽屉说明卡的输出/入参键：start 动态取入参声明，其余取类型注册表 */
const briefOut = computed(() => {
  if (!selected.value) return []
  const t = selected.value.data.nodeType
  if (t === 'start') return (((editConfig.value || {}).inputs) || []).map(i => i && i.key).filter(Boolean)
  return TYPE_META[t]?.out || []
})

// ---------- M4+：变量引用选择器（上游节点 id + 输出键，选了就插进输入框，不用手打 {{}}） ----------
/** 各类型节点的输出键（与引擎各节点体实际写入 state 的键一一对应；start 动态取入参声明） */
const OUTPUT_KEYS = {
  llm: ['answer'],
  retrieval: ['chunks', 'text', 'count'],
  condition: ['route'],
  http: ['status', 'body', 'contentType'],
  code: ['output', 'exitCode'],
  subagent: ['answer'],
  approval: ['route'],
  loop: ['route', 'loopCount'],
  template: ['text']
}

/** 某节点的全部上游祖先（沿入边反向 BFS；loop 回跳成环由 seen 去重兜住）。
 *  引擎按 state 取值——凡在本节点之前执行过的节点输出都引用得到，不限于直接上一跳。 */
function upstreamOf(id) {
  const parents = new Map()
  for (const e of edges.value) {
    if (!e.target || !e.source) continue
    if (!parents.has(e.target)) parents.set(e.target, new Set())
    parents.get(e.target).add(e.source)
  }
  const seen = new Set()
  const stack = [...(parents.get(id) || [])]
  while (stack.length) {
    const cur = stack.pop()
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const p of parents.get(cur) || []) if (!seen.has(p)) stack.push(p)
  }
  return [...seen]
}

/** 引用选择器的分组选项：按上游节点分组，叶子 = {{nodeId.key}} */
const refGroups = computed(() => {
  if (!selectedId.value) return []
  const groups = []
  for (const nid of upstreamOf(selectedId.value)) {
    const node = findNode(nid)
    if (!node) continue
    const t = node.data.nodeType
    let keys = []
    if (t === 'start') {
      keys = (((node.data.config || {}).inputs) || []).map(i => i && i.key).filter(Boolean)
    } else {
      keys = OUTPUT_KEYS[t] || []
    }
    if (!keys.length) continue
    groups.push({
      label: `${TYPE_META[t]?.label || t} · ${nid}`,
      options: keys.map(k => ({ value: `{{${nid}.${k}}}`, label: k }))
    })
  }
  return groups
})

/** 把选中的引用追加到指定字段（已有内容则换行后续写；检索词这类短字段直接空格续写） */
function insertRef(field, refText, sep = ' ') {
  if (!refText) return
  const cur = editConfig.value[field] || ''
  editConfig.value[field] = cur.trimEnd() ? cur.trimEnd() + sep + refText : refText
  dirty.value = true
}

// ---------- DSL ↔ 画布 ----------
const BLANK_CONFIGS = {
  start: { inputs: [{ key: 'question', required: true }] },
  end: { outputs: [{ key: 'answer', value: '' }] },
  llm: { modelRef: '', prompt: '', temperature: null, timeoutSeconds: null },
  retrieval: { query: '', kbIds: [], topK: 5, minScore: null },
  condition: { branches: [{ key: 'ok', expr: '' }] },
  http: { method: 'GET', url: '', headers: [], body: '', timeoutMs: 15000 },
  code: { language: 'python', code: '', timeoutSeconds: 60 },
  subagent: { agentId: '', prompt: '', modelRef: '' },
  approval: { prompt: '', timeoutSeconds: 120, branches: [{ key: 'approve' }, { key: 'reject' }] },
  loop: { maxLoops: 5, branches: [{ key: 'retry', expr: '' }, { key: 'done', expr: '' }] },
  template: { template: '' }
}
const blankConfig = t => JSON.parse(JSON.stringify(BLANK_CONFIGS[t] || {}))

function loadGraph(dsl) {
  nodes.value = (dsl.nodes || []).map(n => {
    const config = JSON.parse(JSON.stringify(n.config || {}))
    // 后端 end 出参存的是对象 {key:模板}；画布用行数组编辑——加载时对象 → 数组
    if (n.type === 'end' && config.outputs && !Array.isArray(config.outputs)) {
      config.outputs = Object.entries(config.outputs).map(([key, value]) => ({ key, value }))
    }
    return {
      id: n.id, type: 'wf',
      position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
      data: { nodeType: n.type, label: TYPE_META[n.type]?.label || n.type, config, run: null }
    }
  })
  edges.value = (dsl.edges || []).map((e, i) => ({
    id: `e_${e.from}_${e.branch || 'd'}_${e.to}_${i}`,
    source: e.from, target: e.to,
    label: e.branch || undefined,
    data: e.branch ? { branch: e.branch } : {},
    markerEnd: MarkerType.ArrowClosed
  }))
}

function toDsl() {
  return {
    version: 1,
    nodes: nodes.value.map(n => {
      const config = JSON.parse(JSON.stringify(n.data.config || {}))
      // 保存时 end 出参数组行 → 对象（与后端 endBody 的 Map 口径一致；空键丢弃）
      if (n.data.nodeType === 'end' && Array.isArray(config.outputs)) {
        const obj = {}
        for (const o of config.outputs) {
          if (o.key && o.key.trim()) obj[o.key.trim()] = o.value == null ? '' : o.value
        }
        config.outputs = obj
      }
      return {
        id: n.id, type: n.data.nodeType,
        position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
        config
      }
    }),
    edges: edges.value.map(e => ({
      from: e.source, to: e.target,
      ...(e.data && e.data.branch ? { branch: e.data.branch } : {})
    }))
  }
}

// ---------- 节点增删 ----------
const nextNum = ref(1)

function genId(nodeType) {
  let id
  do { id = `${nodeType}_${nextNum.value++}` } while (findNode(id))
  return id
}

function waterfallPos() {
  if (!nodes.value.length) return { x: 80, y: 160 }
  let last = nodes.value[0]
  for (const n of nodes.value) if (n.position.x > last.position.x) last = n
  return { x: last.position.x + 240, y: last.position.y }
}

function addNodeAt(nodeType, at) {
  if (nodeType === 'start' && hasStart.value) { message.info('开始节点只能有一个'); return }
  const pos = at || waterfallPos()
  const id = genId(nodeType)
  nodes.value.push({
    id, type: 'wf', position: pos,
    data: { nodeType, label: TYPE_META[nodeType].label, config: blankConfig(nodeType), run: null }
  })
  dirty.value = true
  return id
}

const onDragStart = (e, nodeType) => e.dataTransfer.setData('application/wf-type', nodeType)

function onDrop(e) {
  const nodeType = e.dataTransfer.getData('application/wf-type')
  if (!nodeType) return
  const p = screenToFlowCoordinate({ x: e.clientX, y: e.clientY })
  addNodeAt(nodeType, { x: p.x - 60, y: p.y - 20 })
}

// ---------- 右键菜单（节点 / 连线 / 画布空白） ----------
const ctxMenu = reactive({ open: false, x: 0, y: 0, kind: 'pane', nodeId: '', edgeId: '' })
const ctxMenuEl = ref(null)
const nodeClipboard = ref(null)   // 复制的节点 { nodeType, config }
const ctxNodeIsStart = computed(() => findNode(ctxMenu.nodeId)?.data.nodeType === 'start')

function openCtxMenu(kind, x, y, ids = {}) {
  ctxMenu.open = true
  ctxMenu.kind = kind
  ctxMenu.nodeId = ids.nodeId || ''
  ctxMenu.edgeId = ids.edgeId || ''
  ctxMenu.x = x
  ctxMenu.y = y
  nextTick(clampCtxMenu)   // 渲染后按实际尺寸收进视口
}

function clampCtxMenu() {
  const el = ctxMenuEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  // 画布菜单的「添加节点」有二级飞出（~150px），右侧多预留一份
  const reserve = ctxMenu.kind === 'pane' ? 160 : 12
  ctxMenu.x = Math.max(8, Math.min(ctxMenu.x, window.innerWidth - r.width - reserve - 8))
  ctxMenu.y = Math.max(8, Math.min(ctxMenu.y, window.innerHeight - r.height - 8))
}

function closeCtxMenu() {
  ctxMenu.open = false
}

// vue-flow 的 node/edge contextmenu 不阻断冒泡，pane 会再发一次——stopPropagation 防菜单被画布项覆盖
function onNodeContextMenu({ event, node }) {
  event.preventDefault()
  event.stopPropagation()
  openCtxMenu('node', event.clientX, event.clientY, { nodeId: node.id })
}

function onEdgeContextMenu({ event, edge }) {
  event.preventDefault()
  event.stopPropagation()
  openCtxMenu('edge', event.clientX, event.clientY, { edgeId: edge.id })
}

function onPaneContextMenu(event) {
  event.preventDefault()
  openCtxMenu('pane', event.clientX, event.clientY)
}

// 菜单打开期间：点外部 / Esc / 窗口缩放即关
watch(() => ctxMenu.open, open => {
  if (open) {
    window.addEventListener('mousedown', onGlobalMousedown, true)
    window.addEventListener('keydown', onCtxKeydown, true)
    window.addEventListener('resize', closeCtxMenu)
  } else {
    window.removeEventListener('mousedown', onGlobalMousedown, true)
    window.removeEventListener('keydown', onCtxKeydown, true)
    window.removeEventListener('resize', closeCtxMenu)
  }
})

function onGlobalMousedown(e) {
  if (ctxMenuEl.value && !ctxMenuEl.value.contains(e.target)) closeCtxMenu()
}

function onCtxKeydown(e) {
  if (e.key === 'Escape') closeCtxMenu()
}

onBeforeUnmount(closeCtxMenu)   // 卸载时置 open=false，watcher 即刻摘掉全局监听

function ctxOpenProps() {
  const id = ctxMenu.nodeId
  closeCtxMenu()
  selectNode(id)
  drawerTab.value = 'config'
  drawer.value = true
}

function ctxCopyNode() {
  const node = findNode(ctxMenu.nodeId)
  if (!node) return
  if (node.data.nodeType === 'start') { message.info('开始节点只能有一个'); return }
  nodeClipboard.value = { nodeType: node.data.nodeType, config: JSON.parse(JSON.stringify(node.data.config || {})) }
  pasteNode(nodeClipboard.value, node.position.x + 40, node.position.y + 40)
  closeCtxMenu()
}

function pasteNode(src, x, y) {
  const id = genId(src.nodeType)
  nodes.value.push({
    id, type: 'wf', position: { x, y },
    data: { nodeType: src.nodeType, label: TYPE_META[src.nodeType].label, config: JSON.parse(JSON.stringify(src.config || {})), run: null }
  })
  dirty.value = true
  return id
}

function ctxPaste() {
  const src = nodeClipboard.value
  if (!src) return
  const p = screenToFlowCoordinate({ x: ctxMenu.x, y: ctxMenu.y })
  pasteNode(src, p.x - 60, p.y - 20)
  closeCtxMenu()
}

function ctxDeleteNode() {
  const id = ctxMenu.nodeId
  closeCtxMenu()
  nodes.value = nodes.value.filter(n => n.id !== id)
  edges.value = edges.value.filter(e => e.source !== id && e.target !== id)
  if (selectedId.value === id) closeDrawer()
  dirty.value = true
}

function ctxDeleteEdge() {
  const id = ctxMenu.edgeId
  closeCtxMenu()
  edges.value = edges.value.filter(e => e.id !== id)
  dirty.value = true
}

function ctxAddNode(nodeType) {
  const p = screenToFlowCoordinate({ x: ctxMenu.x, y: ctxMenu.y })
  addNodeAt(nodeType, { x: p.x - 60, y: p.y - 20 })
  closeCtxMenu()
}

function ctxSelectAll() {
  addSelectedNodes(nodes.value)
  closeCtxMenu()
}

function ctxFitView() { closeCtxMenu(); fitView({ padding: 0.2, duration: 200 }) }
function ctxZoomIn() { closeCtxMenu(); zoomIn({ duration: 200 }) }
function ctxZoomOut() { closeCtxMenu(); zoomOut({ duration: 200 }) }
function ctxResetZoom() { closeCtxMenu(); zoomTo(1, { duration: 200 }) }

// Delete/Backspace 键删节点/连线走 vue-flow 内部删除，不经过上面的右键处理——
// 从 change 流里认出 remove 补脏标记（否则删完仍显示「已保存」，一刷新改动全丢）
function onNodesChange(changes) {
  if (changes.some(c => c.type === 'remove')) dirty.value = true
}

function onEdgesChange(changes) {
  if (changes.some(c => c.type === 'remove')) dirty.value = true
}

// ---------- 连线校验 ----------
function createsCycle(source, target) {
  // 加边 source→target 成环 ⇔ target 已可达 source（沿现有边 DFS）
  const adj = new Map()
  for (const e of edges.value) {
    if (!adj.has(e.source)) adj.set(e.source, [])
    adj.get(e.source).push(e.target)
  }
  const seen = new Set([target])
  const stack = [target]
  while (stack.length) {
    const cur = stack.pop()
    if (cur === source) return true
    for (const next of adj.get(cur) || []) if (!seen.has(next)) { seen.add(next); stack.push(next) }
  }
  return false
}

function onConnect(conn) {
  const { source, target } = conn
  if (!source || !target || source === target) return
  const src = findNode(source)
  const dst = findNode(target)
  if (!src || !dst) return
  if (dst.data.nodeType === 'start') { message.warning('开始节点不能作为连线的终点'); return }
  if (src.data.nodeType === 'end') { message.warning('结束节点不能作为连线的起点'); return }
  if (src.data.nodeType === 'condition') {
    if (!src.data.config.branches?.length) { message.warning('先在属性里为条件节点声明分支'); return }
    const wired = new Set(edges.value.filter(e => e.source === source).map(e => e.data && e.data.branch))
    if (src.data.config.branches.every(b => wired.has(b.key))) { message.warning('该条件节点所有分支都已连线'); return }
    pendingConn.value = { ...conn }
    pendingBranch.value = ''
    branchPick.value = true
    return
  }
  if (createsCycle(source, target)) { message.warning('不能成环（循环节点开放前不允许回跳）'); return }
  addEdges([{ ...conn, id: `e_${source}_${Date.now()}`, markerEnd: MarkerType.ArrowClosed, data: {} }])
  dirty.value = true
}

function confirmBranch() {
  const c = pendingConn.value
  if (!c) return
  if (!pendingBranch.value) { message.warning('请选择分支'); return }
  branchPick.value = false
  addEdges([{
    ...c, id: `e_${c.source}_${pendingBranch.value}_${Date.now()}`,
    label: pendingBranch.value, markerEnd: MarkerType.ArrowClosed,
    data: { branch: pendingBranch.value }
  }])
  pendingConn.value = null
  dirty.value = true
}

// ---------- 属性抽屉 ----------
function selectNode(id) {
  const node = findNode(id)
  if (!node) return
  selectedId.value = id
  // editConfig 同步跟随选中节点——否则程序化聚焦后切到属性页点「应用」会把旧配置写进新节点
  editConfig.value = JSON.parse(JSON.stringify(node.data.config || blankConfig(node.data.nodeType)))
}

function onNodeClick({ node }) {
  selectNode(node.id)
  drawerTab.value = nodeTrace.value ? 'run' : 'config'
  drawer.value = true
}

function closeDrawer() {
  drawer.value = false
  selectedId.value = ''
}

function applyConfig() {
  const node = selected.value
  if (!node) return
  // 轻量前端校验（完整校验在保存时走后端 dry-run）
  if (node.data.nodeType === 'start') {
    for (const p of editConfig.value.inputs || []) {
      if (!p.key.trim()) { message.warning('入参名不能为空'); return }
    }
  }
  if (['condition', 'loop'].includes(node.data.nodeType)) {
    const keys = (editConfig.value.branches || []).map(b => b.key.trim())
    if (keys.some(k => !k)) { message.warning('分支键不能为空'); return }
    if (new Set(keys).size !== keys.length) { message.warning('分支键不能重复'); return }
  }
  if (node.data.nodeType === 'llm' && !String(editConfig.value.prompt || '').trim()) {
    message.warning('Prompt 不能为空'); return
  }
  if (node.data.nodeType === 'retrieval' && !String(editConfig.value.query || '').trim()) {
    message.warning('检索词不能为空'); return
  }
  if (node.data.nodeType === 'http' && !String(editConfig.value.url || '').trim()) {
    message.warning('URL 不能为空'); return
  }
  if (node.data.nodeType === 'code' && !String(editConfig.value.code || '').trim()) {
    message.warning('代码不能为空'); return
  }
  if (node.data.nodeType === 'subagent') {
    if (!editConfig.value.agentId) { message.warning('请选择要委派的智能体'); return }
    if (!String(editConfig.value.prompt || '').trim()) { message.warning('指令不能为空'); return }
  }
  if (node.data.nodeType === 'approval' && !String(editConfig.value.prompt || '').trim()) {
    message.warning('审批提示不能为空'); return
  }
  if (node.data.nodeType === 'template' && !String(editConfig.value.template || '').trim()) {
    message.warning('模板不能为空'); return
  }
  const oldConfig = node.data.config || {}
  updateNodeData(node.id, { config: JSON.parse(JSON.stringify(editConfig.value)) })
  // 路由类分支键变更后，悬空的出边摘除（连线时重新选分支）
  if (['condition', 'loop'].includes(node.data.nodeType)) {
    const keys = new Set((editConfig.value.branches || []).map(b => b.key))
    const dangling = edges.value.filter(e => e.source === node.id && e.data && e.data.branch && !keys.has(e.data.branch))
    if (dangling.length) removeEdges(dangling)
  }
  dirty.value = true
  message.success('已应用到画布（保存后才写入工作流）')
  void oldConfig
}

function removeBranch(i) {
  editConfig.value.branches.splice(i, 1)
}

// ---------- 校验 / 保存 ----------
async function doValidate() {
  busy.value = true
  try {
    const r = await validateWorkflowDsl(JSON.stringify(toDsl()))
    const d = r.data || {}
    if (d.errors?.length) {
      Modal.warning({ title: `校验未通过（${d.errors.length} 项）`, content: d.errors.join('；'), width: 560 })
    } else if (d.compiled) {
      message.success('校验通过，图可编译')
    } else {
      message.warning('结构通过，但编译未过：' + (d.compileError || '未知原因'))
    }
  } catch (e) {
    message.error('校验失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
}

async function doSave() {
  if (!name.value.trim()) { message.warning('请先填写工作流名称'); return }
  busy.value = true
  try {
    const dslText = JSON.stringify(toDsl())
    const v = await validateWorkflowDsl(dslText)
    const d = v.data || {}
    if (d.errors?.length) {
      Modal.warning({ title: `保存被拦下：校验未通过（${d.errors.length} 项）`, content: d.errors.join('；'), width: 560 })
      return
    }
    if (!d.compiled) {
      message.warning('结构通过但编译未过（' + (d.compileError || '未知') + '）；仍可保存，运行时会再校')
    }
    if (workflowId.value) {
      await updateWorkflow(workflowId.value, { name: name.value.trim(), dsl: dslText })
    } else {
      const r = await createWorkflow({ name: name.value.trim(), dsl: dslText })
      workflowId.value = r.data.id
    }
    savedDsl.value = dslText
    dirty.value = false
    message.success('已保存')
    emit('saved')
  } catch (e) {
    message.error('保存失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
}

// ---------- M4：发布（把当前草稿冻结成一个版本） ----------
/**
 * 发布前必须先保存：发布的是库里的草稿 DSL，不是画布内存里的图——
 * 有未保存改动就直接拦下（否则"我发布了但跑的不是我看到的图"）。
 */
async function doPublish() {
  if (!workflowId.value) { message.warning('请先保存工作流，再发布'); return }
  if (dirty.value) { message.warning('有未保存改动：请先保存，再发布'); return }
  busy.value = true
  try {
    const r = await publishWorkflow(workflowId.value, '')
    const d = r.data || {}
    publishedVersion.value = d.version
    message.success(`已发布 v${d.version}（API 触发与智能体绑定将使用这一版）`)
    emit('saved')
  } catch (e) {
    message.error('发布失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
}

// ---------- 运行（同步调试 + trace 回放染色） ----------
function openRun() {
  for (const k of Object.keys(runInputs)) delete runInputs[k]
  runModal.value = true
}

async function doRun() {
  // 必填入参前端先拦一层
  for (const p of startInputs.value) {
    if (p.required && !(runInputs[p.key] || '').trim()) { message.warning(`请填写入参 ${p.key}`); return }
  }
  busy.value = true
  runModal.value = false
  // 清上一轮染色
  for (const n of nodes.value) if (n.data.run) updateNodeData(n.id, { run: null })
  try {
    const r = await runWorkflow(workflowId.value, { ...runInputs })
    const run = r.data || {}
    runResult.value = {
      status: run.status,
      outputs: safeParse(run.outputs),
      error: run.error,
      traces: safeParse(run.nodeTraces) || [],
      runId: run.id
    }
    await replayTraces()
    if (runResult.value.status === 'success') {
      message.success(`运行成功（${fmtMs(run.durationMs)}），点击节点看输入输出`)
    } else if (runResult.value.status === 'waiting_approval') {
      // 人工审核挂起：加载审批卡内容（prompt/超时），在抽屉里裁决
      try {
        const ar = await getWorkflowPendingApproval(workflowId.value, run.id)
        approvalInfo.value = ar.data
      } catch (e) { approvalInfo.value = null }
      message.info('运行已挂起：等待人工审核')
    } else {
      message.error('运行失败：' + (runResult.value.error || '未知原因'))
    }
    // 抽屉 body 依赖选中节点（v-if="selected"）：先聚焦关键节点再开，否则弹空壳
    const focusId = pickFocusNodeId()
    if (focusId) selectNode(focusId)
    drawerTab.value = 'run'
    drawer.value = !!focusId
  } catch (e) {
    message.error('运行失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
}

/** 审批裁决：批准/拒绝后后端按快照恢复续跑，返回终态 run——直接替换 runResult 重放染色 */
async function doApprove(approved) {
  if (!runResult.value?.runId) return
  busy.value = true
  try {
    const r = await resolveWorkflowApproval(workflowId.value, runResult.value.runId, approved)
    const run = r.data || {}
    runResult.value = {
      status: run.status,
      outputs: safeParse(run.outputs),
      error: run.error,
      traces: safeParse(run.nodeTraces) || [],
      runId: run.id
    }
    approvalInfo.value = null
    await replayTraces()
    if (runResult.value.status === 'success') {
      message.success(`已${approved ? '批准' : '拒绝'}，续跑完成（${fmtMs(run.durationMs)}）`)
    } else {
      message.error('续跑失败：' + (runResult.value.error || '未知原因'))
    }
  } catch (e) {
    message.error('审批失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
}

/** 运行结束后抽屉自动聚焦的节点：失败→失败节点、挂起→审批节点、成功→end 节点（没有就最后一条 trace） */
function pickFocusNodeId() {
  const traces = runResult.value?.traces || []
  if (!traces.length) return ''
  const lastOf = s => { for (let i = traces.length - 1; i >= 0; i--) if (traces[i].status === s) return traces[i]; return null }
  const t = (runResult.value.status === 'failed' && lastOf('failed'))
    || (runResult.value.status === 'waiting_approval' && lastOf('waiting'))
    || [...traces].reverse().find(x => findNode(x.nodeId)?.data.nodeType === 'end')
    || traces[traces.length - 1]
  return (t && findNode(t.nodeId)) ? t.nodeId : ''
}

async function replayTraces() {
  const traces = runResult.value.traces
  for (const t of traces) {
    if (!findNode(t.nodeId)) continue   // 运行快照里的节点可能已被删
    updateNodeData(t.nodeId, { run: { status: t.status, elapsedMs: t.elapsedMs } })
    await nextTick()
    await new Promise(res => setTimeout(res, 120))   // 逐节点点亮，跑完即全量定格
  }
}

function showHistory() {
  historyModal.value = true
  historyLoading.value = true
  history.value = []
  listWorkflowRuns(workflowId.value)
    .then(r => { history.value = r.data || [] })
    .catch(e => message.error('运行历史加载失败：' + (e.message || '')))
    .finally(() => { historyLoading.value = false })
}

async function openRunDetail(row) {
  try {
    const r = await getWorkflowRun(workflowId.value, row.id)
    const run = r.data || {}
    runResult.value = {
      status: run.status, outputs: safeParse(run.outputs), error: run.error,
      traces: safeParse(run.nodeTraces) || [], runId: run.id
    }
    approvalInfo.value = null
    if (run.status === 'waiting_approval') {
      try {
        const ar = await getWorkflowPendingApproval(workflowId.value, run.id)
        approvalInfo.value = ar.data
      } catch (e) { approvalInfo.value = null }
    }
    historyModal.value = false
    for (const n of nodes.value) updateNodeData(n.id, { run: null })
    for (const t of runResult.value.traces) {
      if (findNode(t.nodeId)) updateNodeData(t.nodeId, { run: { status: t.status, elapsedMs: t.elapsedMs } })
    }
    const focusId = pickFocusNodeId()
    if (focusId) selectNode(focusId)
    drawerTab.value = 'run'
    drawer.value = !!focusId
  } catch (e) {
    message.error('运行详情加载失败：' + (e.message || ''))
  }
}

// ---------- 加载 ----------
async function load() {
  if (!workflowId.value) {
    // 新建画布：默认 start→end 骨架（入参 question / 出参 answer），开箱即改
    name.value = ''
    nodes.value = [
      { id: 'start', type: 'wf', position: { x: 80, y: 160 }, data: { nodeType: 'start', label: TYPE_META.start.label, config: blankConfig('start'), run: null } },
      { id: 'end', type: 'wf', position: { x: 420, y: 160 }, data: { nodeType: 'end', label: TYPE_META.end.label, config: blankConfig('end'), run: null } }
    ]
    edges.value = [{ id: 'e_start_end', source: 'start', target: 'end', markerEnd: MarkerType.ArrowClosed, data: {} }]
    dirty.value = true   // 新建画布尚未落库，如实显示「有未保存改动」
    nextTick(() => fitView({ padding: 0.2 }))
    return
  }
  busy.value = true
  try {
    const r = await getWorkflow(workflowId.value)
    const row = r.data || {}
    name.value = row.name || ''
    publishedVersion.value = row.publishedVersion == null ? null : row.publishedVersion
    const dsl = safeParse(row.dsl) || { nodes: [], edges: [] }
    loadGraph(dsl)
    savedDsl.value = row.dsl || ''
    dirty.value = false
    nextTick(() => fitView({ padding: 0.2 }))
  } catch (e) {
    message.error('工作流加载失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
}

loadKnowledgeBases()
async function loadKnowledgeBases() {
  try {
    const r = await listKnowledgeBases()
    kbOptions.value = ((r.data || [])).map(k => ({ value: k.id, label: k.name || k.id }))
  } catch (e) { /* 检索节点库列表拿不到就留空（不影响其他功能） */ }
}

loadAgents()
async function loadAgents() {
  try {
    const r = await listAvailableAgents()
    agentOptions.value = ((r.data || [])).map(a => ({
      value: a.id || a.agentId,
      label: a.name || a.id || a.agentId
    })).filter(o => o.value)
  } catch (e) { /* 拿不到就留空：子智能体节点无候选（不影响其他功能） */ }
}

// ---------- 工具 ----------
function safeParse(s) {
  if (!s) return null
  try { return JSON.parse(s) } catch (e) { return null }
}

function subText(data) {
  const c = data.config || {}
  switch (data.nodeType) {
    case 'start': return `${(c.inputs || []).length} 个入参`
    case 'end': return `${(c.outputs || []).length} 个出参`
    case 'llm': return c.prompt ? String(c.prompt).slice(0, 24) : '未配置 Prompt'
    case 'retrieval': return c.query ? `检索 ${String(c.query).slice(0, 18)}` : '未配置检索词'
    case 'condition': return `${(c.branches || []).length} 个分支`
    case 'http': return `${c.method || 'GET'} ${String(c.url || '').slice(0, 20)}`
    case 'code': return `${c.language === 'node' ? 'Node' : 'Python'} · ${c.timeoutSeconds || 60}s`
    case 'subagent': return c.agentId ? `委派 ${String(c.agentId).slice(0, 16)}` : '未选择智能体'
    case 'approval': return c.prompt ? String(c.prompt).slice(0, 20) : '等待人工确认'
    case 'loop': return `最多 ${(c.maxLoops || 5)} 轮`
    case 'template': return c.template ? String(c.template).slice(0, 22) : '未配置模板'
    default: return ''
  }
}

const fmtMs = ms => (ms == null ? '' : ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms')
const traceTagColor = s => ({ success: 'green', failed: 'red', waiting: 'orange' }[s] || 'red')
const runLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const runColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 19) : '—')

load()
</script>

<style scoped>
.wf-editor { display: flex; flex-direction: column; height: 100%; min-width: 0; }
.wf-toolbar {
  display: flex; align-items: center; gap: 10px; flex-wrap: nowrap;
  padding: 9px 14px; background: var(--app-panel);
  border-bottom: 1px solid var(--app-border); flex: none;
}
.wf-tb-back { flex: none; }
.wf-name { width: 200px; flex: none; }
.wf-tb-status { display: flex; align-items: center; gap: 8px; flex: none; }
.wf-tb-sep { width: 1px; height: 14px; background: var(--app-border); }
/* 状态点：比纯文字更快读出"有没有未保存改动" */
.wf-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.dot-saved { background: #52c41a; }
.dot-dirty { background: #fa8c16; }
.wf-tb-actions { display: flex; align-items: center; gap: 6px; flex: none; margin-left: 4px; }
.wf-save-hint { font-size: 12px; color: var(--app-text3); white-space: nowrap; }
.wf-ver { margin: 0; }
.flex-gap { flex: 1; }
.wf-body { flex: 1; min-height: 0; display: flex; }

.wf-palette {
  width: 168px; flex: none; border-right: 1px solid var(--app-border);
  background: var(--app-panel); padding: 10px 8px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 5px;
}
.wf-palette-title { font-size: 12px; color: var(--app-text3); margin-bottom: 2px; font-weight: 500; }
.wf-palette-group {
  font-size: 11px; color: var(--app-text3); opacity: .85;
  margin: 8px 0 2px 2px; letter-spacing: .5px;
}
.wf-palette-group:first-of-type { margin-top: 2px; }
.wf-palette-item {
  display: flex; align-items: center; gap: 8px;
  border: 1px solid var(--app-border); border-radius: 7px;
  padding: 6px 8px; cursor: grab; background: var(--app-bg, #fff); font-size: 13px;
  user-select: none; transition: border-color .15s, box-shadow .15s;
}
.wf-palette-item:hover { border-color: var(--app-accent); box-shadow: 0 1px 4px rgba(0, 0, 0, .06); }
.wf-palette-item.disabled { opacity: 0.4; cursor: not-allowed; }
.wf-palette-hint { font-size: 11px; color: var(--app-text3); line-height: 1.7; margin-top: 8px; }

.wf-canvas-wrap { flex: 1; min-width: 0; position: relative; background: var(--app-bg, #f5f6f8); }
/* 连线：默认细灰；悬停加深加粗；选中（点击）用主题色明显加粗 + 光晕——
   注意线宽/颜色必须走 CSS（内联 style 会压死选中态，见 EDGE_OPTIONS 注释） */
.wf-canvas-wrap :deep(.vue-flow__edge-path) { stroke: #b3bac6; stroke-width: 1.8; transition: stroke .12s, stroke-width .12s; }
.wf-canvas-wrap :deep(.vue-flow__edge:hover .vue-flow__edge-path) { stroke: #7f8a9e; stroke-width: 2.2; cursor: pointer; }
.wf-canvas-wrap :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--app-accent); stroke-width: 2.8;
  filter: drop-shadow(0 0 3px rgba(51, 112, 255, 0.45));
}
/* 分支标签：选中时标签底色跟着提色，和线一体 */
.wf-canvas-wrap :deep(.vue-flow__edge.selected .vue-flow__edge-text) { fill: var(--app-accent); font-weight: 600; }
.wf-canvas-wrap :deep(.vue-flow__edge.selected .vue-flow__edge-textbg) { fill: rgba(51, 112, 255, 0.08); }
.wf-canvas-wrap :deep(.vue-flow__connection-path) { stroke: var(--app-accent); stroke-width: 2.2; }
.wf-canvas-wrap :deep(.vue-flow__edge-text) { font-size: 11px; }
.wf-canvas-wrap :deep(.vue-flow__edge-textbg) { fill: #fff; }
/* 小地图与缩放控件 */
.wf-minimap {
  background: var(--app-bg, #fff); border: 1px solid var(--app-border);
  border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0, 0, 0, .08);
}
.wf-controls { display: flex; gap: 2px; }
.wf-controls :deep(.vue-flow__controls-button) {
  background: var(--app-bg, #fff); border-bottom: 1px solid var(--app-border);
  border-radius: 6px; width: 26px; height: 26px;
}
.wf-controls :deep(.vue-flow__controls-button svg) { fill: var(--app-text2); }

/* 画布节点（slot 内容带本组件 scoped 属性，可直接命中） */
.wf-node {
  background: #fff; border: 1.5px solid var(--app-border); border-radius: 10px;
  border-left: 3px solid var(--nc, var(--app-border));
  padding: 8px 12px; min-width: 168px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  font-size: 13px; position: relative; transition: box-shadow .15s, border-color .15s;
}
.wf-node:hover { box-shadow: 0 3px 10px rgba(0, 0, 0, 0.10); }
/* 选中态由 vue-flow 挂在父节点上：用 :deep 命中 */
.wf-canvas-wrap :deep(.vue-flow__node.selected) .wf-node {
  border-color: var(--nc, var(--app-accent));
  box-shadow: 0 0 0 3px rgba(51, 112, 255, 0.14);
}
.wf-node-head { display: flex; align-items: center; gap: 8px; }
/* 图标统一为「圆角方块底衬 + 类型色符号」：11 类各有色，扫读分得清 */
.wf-node-ico {
  width: 22px; height: 22px; border-radius: 6px; flex: none;
  display: inline-flex; align-items: center; justify-content: center; font-size: 12px; line-height: 1;
}
.wf-node-icon { font-size: 12px; }
.wf-node-titles { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.wf-node-label { font-weight: 500; line-height: 1.3; }
.wf-node-id { font-size: 11px; color: var(--app-text3); font-family: ui-monospace, Menlo, monospace; line-height: 1.2; }
.wf-node-sub {
  font-size: 11px; color: var(--app-text3); margin-top: 5px; max-width: 190px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.wf-node :deep(.vue-flow__handle) { width: 8px; height: 8px; background: var(--app-accent); border: none; }
.wf-node.run-success { border-color: #52c41a; box-shadow: 0 0 0 2px rgba(82, 196, 26, 0.15); }
.wf-node.run-failed { border-color: #ff4d4f; box-shadow: 0 0 0 2px rgba(255, 77, 79, 0.15); }
.wf-node.run-waiting { border-color: #fa8c16; box-shadow: 0 0 0 2px rgba(250, 140, 22, 0.2); }
.wf-node-badge {
  position: absolute; top: -9px; right: -9px;
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 9px; color: #fff;
}
.badge-success { background: #52c41a; }
.badge-failed { background: #ff4d4f; }
.badge-waiting { background: #fa8c16; }

/* 人工审核审批卡 */
.wf-approval-card {
  border: 1.5px solid #fa8c16; border-radius: 8px; padding: 12px 14px;
  background: #fff7e6; margin-bottom: 14px;
}
.wf-approval-head { font-weight: 500; font-size: 13px; margin-bottom: 6px; }
.wf-approval-prompt { font-size: 13px; white-space: pre-wrap; word-break: break-word; margin-bottom: 8px; }
.wf-approval-meta { font-size: 12px; color: var(--app-text3); display: flex; gap: 12px; margin-bottom: 10px; }
.wf-approval-actions { display: flex; gap: 10px; }

/* 抽屉表单 */
.wf-form { display: flex; flex-direction: column; gap: 14px; }
.wf-form-item label { display: block; font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.wf-form-item { font-size: 13px; }
.wf-form-static { color: var(--app-text2); font-family: monospace; font-size: 12px; }
/* 节点说明卡：类型色贯穿（左边条 + 图标底衬），输出键 chip 化 */
.wf-node-brief {
  display: flex; gap: 10px; align-items: flex-start;
  border: 1px solid var(--app-border); border-left: 3px solid var(--nc, var(--app-border));
  border-radius: 8px; padding: 10px 12px; background: var(--app-bg, #fafafa);
}
.wf-brief-body { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.wf-brief-title { font-weight: 500; font-size: 13px; display: flex; align-items: center; gap: 8px; }
.wf-brief-desc { font-size: 12px; color: var(--app-text2); line-height: 1.6; }
.wf-brief-out { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 2px; }
.wf-out-label { font-size: 11px; color: var(--app-text3); }
.wf-brief-out code {
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 4px;
  padding: 1px 6px; font-size: 11px; font-family: ui-monospace, Menlo, monospace;
}
.wf-rows { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.wf-row-key { width: 110px; flex: none; }
.wf-row-value { flex: 1; min-width: 0; }
.wf-row-grow { flex: 1; min-width: 0; }
/* 行内「必填」勾选：文字禁换行（默认会被 flex 压成竖排两字），勾选框与文字都不缩 */
.wf-req-cb { flex: none; white-space: nowrap; }
.wf-req-cb :deep(.ant-checkbox + span) { white-space: nowrap; padding-inline-start: 4px; padding-inline-end: 0; }
.wf-row-del { flex: none; }
.wf-branch { border: 1px dashed var(--app-border); border-radius: 6px; padding: 8px; margin-bottom: 8px; }
.wf-branch-radio { padding: 2px 0; }
.wf-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; }
/* 变量引用选择器：贴在输入框下方，窄条不抢输入框的视觉主位 */
.wf-ref-pick { margin-top: 6px; width: 100%; }
.wf-hint code { background: var(--app-panel); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
.wf-drawer-actions { margin-top: 16px; display: flex; justify-content: flex-end; }
.wf-req { color: #ff4d4f; margin-left: 2px; }

/* 运行输出 */
.wf-trace-meta { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--app-text3); margin-bottom: 8px; }
.wf-trace-error { font-size: 13px; color: var(--app-danger, #d4380d); margin: 6px 0; word-break: break-all; }
.wf-trace-block { margin-top: 8px; }
.wf-trace-label { font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.wf-trace-pre {
  background: var(--app-panel); border-radius: 6px; padding: 8px 10px;
  font-size: 12px; max-height: 260px; overflow: auto; white-space: pre-wrap; word-break: break-all;
  margin: 0;
}

/* 运行历史 */
.wf-history { display: flex; flex-direction: column; gap: 8px; }
.wf-history-item { border: 1px solid var(--app-border); border-radius: 6px; padding: 8px 12px; cursor: pointer; }
.wf-history-item:hover { border-color: var(--app-accent); }
.wf-history-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.wf-history-meta { font-size: 12px; color: var(--app-text3); }
.wf-history-link { margin-left: auto; font-size: 12px; color: var(--app-accent); }

/* 右键菜单（Teleport 到 body，scoped 属性随元素带出，样式照常命中） */
.wf-ctx-menu {
  position: fixed; z-index: 1030; min-width: 168px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.13);
  padding: 4px; font-size: 13px; color: var(--app-text);
  user-select: none;
}
.wf-ctx-item {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px; border-radius: 6px; cursor: pointer; white-space: nowrap;
}
.wf-ctx-item:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.wf-ctx-item.disabled { opacity: 0.4; cursor: not-allowed; }
.wf-ctx-item.disabled:hover { background: transparent; color: inherit; }
.wf-ctx-ico { width: 16px; text-align: center; font-size: 12px; flex: none; }
.wf-ctx-key { margin-left: 14px; font-size: 11px; color: var(--app-text3); }
.wf-ctx-arrow { margin-left: 14px; font-size: 11px; color: var(--app-text3); }
.wf-ctx-sep { height: 1px; background: var(--app-border); margin: 4px 6px; }
.wf-ctx-item.has-sub { position: relative; }
.wf-ctx-sub {
  display: none; position: absolute; left: 100%; top: -5px; min-width: 150px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.13);
  padding: 4px; max-height: 330px; overflow-y: auto;
}
.wf-ctx-item.has-sub:hover .wf-ctx-sub { display: block; }
.wf-ctx-sub .wf-ctx-item:hover { background: var(--app-accent-weak); color: var(--app-accent); }
</style>
