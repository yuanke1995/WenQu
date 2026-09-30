<template>
  <!-- 工作流画布编辑器（M2）：DSL 是唯一真源，画布只是编辑器——
       加载即还原图形（坐标读 DSL position），保存即序列化回 DSL（坐标写回 position）。
       连线校验：禁悬空/自环、start 无入边、end 无出边、非 loop 禁成环、条件出边必须挂已声明分支。 -->
  <div class="wf-editor">
    <div class="wf-toolbar">
      <button class="app-btn ghost small" @click="$emit('back')"><arrow-left-outlined /> 返回列表</button>
      <a-input v-model:value="name" class="wf-name" :maxlength="100" placeholder="工作流名称" />
      <span class="wf-save-hint">{{ dirty ? '有未保存改动' : '已保存' }}</span>
      <span class="flex-gap"></span>
      <button class="app-btn ghost small" :disabled="busy" @click="doValidate">校验</button>
      <button class="app-btn ghost small" :disabled="busy" @click="showHistory">运行历史</button>
      <button class="app-btn small" :disabled="busy || !workflowId" @click="openRun">
        <play-circle-outlined /> 运行
      </button>
      <button class="app-btn small" :disabled="busy" @click="doSave">保存</button>
    </div>

    <div class="wf-body">
      <!-- 左侧节点面板：拖拽到画布或点击添加（瀑布排布） -->
      <div class="wf-palette">
        <div class="wf-palette-title">节点</div>
        <div v-for="t in PALETTE" :key="t.type" class="wf-palette-item" :class="{ disabled: t.type === 'start' && hasStart }"
             draggable="true" @dragstart="onDragStart($event, t.type)" @click="addNodeAt(t.type)">
          <span class="wf-node-icon" :class="`icon-${t.type}`">{{ t.icon }}</span>
          <span class="wf-palette-name">{{ t.label }}</span>
        </div>
        <div class="wf-palette-hint">拖拽或点击添加；<br/>删除：选中后 Delete；<br/>「{{ TYPE_META.condition.label }}」连线时会让你选分支。</div>
      </div>

      <!-- 画布 -->
      <div class="wf-canvas-wrap" @drop="onDrop" @dragover.prevent>
        <VueFlow v-model:nodes="nodes" v-model:edges="edges"
                 :delete-key-code="['Backspace', 'Delete']"
                 :default-edge-options="{ markerEnd: MarkerType.ArrowClosed }"
                 :min-zoom="0.2" :max-zoom="2"
                 @connect="onConnect" @node-click="onNodeClick" @pane-click="closeDrawer">
          <Background :gap="16" />
          <template #node-wf="props">
            <div class="wf-node" :class="[`wf-${props.data.nodeType}`, props.data.run ? `run-${props.data.run.status}` : '']">
              <Handle v-if="props.data.nodeType !== 'start'" type="target" :position="Position.Left" />
              <Handle v-if="props.data.nodeType !== 'end'" type="source" :position="Position.Right" />
              <div class="wf-node-head">
                <span class="wf-node-icon" :class="`icon-${props.data.nodeType}`">{{ TYPE_META[props.data.nodeType]?.icon }}</span>
                <span class="wf-node-label">{{ props.data.label }}</span>
              </div>
              <div class="wf-node-sub">{{ subText(props.data) }}</div>
              <div v-if="props.data.run" class="wf-node-badge" :class="`badge-${props.data.run.status}`">
                {{ props.data.run.status === 'success' ? '✓' : '✕' }} {{ fmtMs(props.data.run.elapsedMs) }}
              </div>
            </div>
          </template>
        </VueFlow>
      </div>
    </div>

    <!-- 右侧抽屉：属性编辑 / 运行详情 -->
    <a-drawer v-model:open="drawer" :title="selected ? `${TYPE_META[selected.data.nodeType]?.label || ''} · ${selected.id}` : ''"
              width="430" destroy-on-close @close="closeDrawer">
      <a-tabs v-if="selected" v-model:activeKey="drawerTab">
        <a-tab-pane key="config" tab="属性">
          <div class="wf-form">
            <div class="wf-form-item"><label>节点 ID</label><span class="wf-form-static">{{ selected.id }}</span></div>

            <!-- start：入参列表 -->
            <template v-if="selected.data.nodeType === 'start'">
              <div class="wf-form-item"><label>入参定义</label></div>
              <div v-for="(p, i) in editConfig.inputs" :key="i" class="wf-rows">
                <a-input v-model:value="p.key" placeholder="参数名，如 question" />
                <a-checkbox v-model:checked="p.required">必填</a-checkbox>
                <button class="app-btn ghost small" @click="editConfig.inputs.splice(i, 1)">删</button>
              </div>
              <button class="app-btn ghost small" @click="editConfig.inputs.push({ key: '', required: true })">+ 添加入参</button>
              <div class="wf-hint">入参在点「运行」时填写；后续节点用 <code v-pre>{{start.参数名}}</code> 引用。</div>
            </template>

            <!-- end：出参映射 -->
            <template v-else-if="selected.data.nodeType === 'end'">
              <div class="wf-form-item"><label>出参映射</label></div>
              <div v-for="(o, i) in editConfig.outputs" :key="i" class="wf-rows">
                <a-input v-model:value="o.key" placeholder="输出名，如 answer" class="wf-row-key" />
                <a-input v-model:value="o.value" :placeholder="'{{llm.answer}}'" class="wf-row-value" />
                <button class="app-btn ghost small" @click="editConfig.outputs.splice(i, 1)">删</button>
              </div>
              <button class="app-btn ghost small" @click="editConfig.outputs.push({ key: '', value: '' })">+ 添加出参</button>
              <div class="wf-hint">值可写变量引用 <code v-pre>{{nodeId.key}}</code> 或固定文本。</div>
            </template>

            <!-- llm -->
            <template v-else-if="selected.data.nodeType === 'llm'">
              <div class="wf-form-item"><label>模型</label>
                <ModelSelect v-model="editConfig.modelRef" type="chat" width="100%" inherit-label="跟随个人默认模型" />
              </div>
              <div class="wf-form-item"><label>Prompt（支持 <code v-pre>{{nodeId.key}}</code> 引用）</label>
                <a-textarea v-model:value="editConfig.prompt" :rows="7"
                            :placeholder="'如：基于以下资料回答问题：{{retrieval.text}}\n\n问题：{{start.question}}'" />
              </div>
              <div class="wf-form-item"><label>温度（留空跟随全局）</label>
                <a-input-number v-model:value="editConfig.temperature" :min="0" :max="2" :step="0.1" style="width: 140px" />
              </div>
              <div class="wf-hint">输出键：<code>answer</code>（模型回答文本），后续节点用 <code>{{ LBB + selected.id + '.answer' + RBB }}</code> 引用。</div>
            </template>

            <!-- retrieval -->
            <template v-else-if="selected.data.nodeType === 'retrieval'">
              <div class="wf-form-item"><label>检索词（支持 <code v-pre>{{nodeId.key}}</code> 引用）</label>
                <a-input v-model:value="editConfig.query" :placeholder="'如：{{start.question}}'" />
              </div>
              <div class="wf-form-item"><label>知识库范围（不选 = 全部）</label>
                <a-select v-model:value="editConfig.kbIds" mode="multiple" style="width: 100%" allow-clear
                          placeholder="全部知识库" :options="kbOptions" />
              </div>
              <div class="wf-form-item"><label>topK（召回条数）</label>
                <a-input-number v-model:value="editConfig.topK" :min="1" :max="20" style="width: 140px" />
              </div>
              <div class="wf-hint">输出键：<code>chunks</code>（结构化命中）/ <code>text</code>（拼接文本，可直接拼 prompt）/ <code>count</code>。</div>
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
          </div>
          <div class="wf-drawer-actions">
            <button class="app-btn small" @click="applyConfig">应用到画布</button>
          </div>
        </a-tab-pane>

        <a-tab-pane key="run" tab="运行输出">
          <template v-if="nodeTrace">
            <div class="wf-trace-meta">
              <a-tag :color="nodeTrace.status === 'success' ? 'green' : 'red'">{{ nodeTrace.status === 'success' ? '成功' : '失败' }}</a-tag>
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
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ArrowLeftOutlined, PlayCircleOutlined } from '@ant-design/icons-vue'
import { VueFlow, useVueFlow, MarkerType, Handle, Position } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import ModelSelect from '../components/ModelSelect.vue'
import {
  getWorkflow, createWorkflow, updateWorkflow, validateWorkflowDsl,
  runWorkflow, listWorkflowRuns, getWorkflowRun, listKnowledgeBases
} from '../api'

const props = defineProps({
  workflowId: { type: String, default: '' }   // 空 = 新建
})
const emit = defineEmits(['back', 'saved'])

const { addEdges, removeEdges, updateNodeData, findNode, screenToFlowCoordinate, fitView } = useVueFlow()

// 模板里要展示「字面量大括号」时拼这两个常量——不能写 {{ '{{' }}：模板 tokenizer 不识别引号，
// 会在字符串内部的第一个 }} 处截断插值（实测编译报 Unterminated string constant）
const LBB = '{{'
const RBB = '}}'

// ---------- 类型元数据与面板 ----------
const TYPE_META = {
  start: { label: '开始', icon: '▶' },
  end: { label: '结束', icon: '■' },
  llm: { label: 'LLM 调用', icon: '✦' },
  retrieval: { label: '知识检索', icon: '⌕' },
  condition: { label: '条件分支', icon: '⑂' }
}
const PALETTE = ['start', 'end', 'llm', 'retrieval', 'condition'].map(t => ({ type: t, ...TYPE_META[t] }))

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
const runResult = ref(null)     // {status, outputs, error, traces}
const historyModal = ref(false)
const historyLoading = ref(false)
const history = ref([])
const runModal = ref(false)
const runInputs = reactive({})
const branchPick = ref(false)
const pendingConn = ref(null)
const pendingBranch = ref('')
const savedDsl = ref('')        // 上次保存的 DSL 文本（脏检测）

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

// ---------- DSL ↔ 画布 ----------
const BLANK_CONFIGS = {
  start: { inputs: [{ key: 'question', required: true }] },
  end: { outputs: [{ key: 'answer', value: '' }] },
  llm: { modelRef: '', prompt: '', temperature: null },
  retrieval: { query: '', kbIds: [], topK: 5 },
  condition: { branches: [{ key: 'ok', expr: '' }] }
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
function onNodeClick({ node }) {
  selectedId.value = node.id
  editConfig.value = JSON.parse(JSON.stringify(node.data.config || blankConfig(node.data.nodeType)))
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
  if (node.data.nodeType === 'condition') {
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
  const oldConfig = node.data.config || {}
  updateNodeData(node.id, { config: JSON.parse(JSON.stringify(editConfig.value)) })
  // 条件分支键变更后，悬空的出边摘除（连线时重新选分支）
  if (node.data.nodeType === 'condition') {
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
      traces: safeParse(run.nodeTraces) || []
    }
    await replayTraces()
    if (runResult.value.status === 'success') {
      message.success(`运行成功（${fmtMs(run.durationMs)}），点击节点看输入输出`)
    } else {
      message.error('运行失败：' + (runResult.value.error || '未知原因'))
    }
    drawer.value = true
    drawerTab.value = 'run'
  } catch (e) {
    message.error('运行失败：' + (e.message || ''))
  } finally {
    busy.value = false
  }
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
      traces: safeParse(run.nodeTraces) || []
    }
    historyModal.value = false
    for (const n of nodes.value) updateNodeData(n.id, { run: null })
    for (const t of runResult.value.traces) {
      if (findNode(t.nodeId)) updateNodeData(t.nodeId, { run: { status: t.status, elapsedMs: t.elapsedMs } })
    }
    drawer.value = true
    drawerTab.value = 'run'
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
    nextTick(() => fitView({ padding: 0.2 }))
    return
  }
  busy.value = true
  try {
    const r = await getWorkflow(workflowId.value)
    const row = r.data || {}
    name.value = row.name || ''
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
    default: return ''
  }
}

const fmtMs = ms => (ms == null ? '' : ms >= 1000 ? (ms / 1000).toFixed(1) + ' s' : ms + ' ms')
const runLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const runColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 19) : '—')

load()
</script>

<style scoped>
.wf-editor { display: flex; flex-direction: column; height: 100%; min-width: 0; }
.wf-toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; background: var(--app-panel);
  border-bottom: 1px solid var(--app-border); flex: none;
}
.wf-name { width: 220px; }
.wf-save-hint { font-size: 12px; color: var(--app-text3); }
.flex-gap { flex: 1; }
.wf-body { flex: 1; min-height: 0; display: flex; }

.wf-palette {
  width: 150px; flex: none; border-right: 1px solid var(--app-border);
  background: var(--app-panel); padding: 10px 8px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 6px;
}
.wf-palette-title { font-size: 12px; color: var(--app-text3); margin-bottom: 2px; }
.wf-palette-item {
  display: flex; align-items: center; gap: 8px;
  border: 1px solid var(--app-border); border-radius: 6px;
  padding: 8px 10px; cursor: grab; background: var(--app-bg, #fff); font-size: 13px;
  user-select: none;
}
.wf-palette-item:hover { border-color: var(--app-accent); color: var(--app-accent); }
.wf-palette-item.disabled { opacity: 0.4; cursor: not-allowed; }
.wf-palette-hint { font-size: 11px; color: var(--app-text3); line-height: 1.7; margin-top: 6px; }

.wf-canvas-wrap { flex: 1; min-width: 0; position: relative; background: var(--app-bg, #f5f6f8); }

/* 画布节点（slot 内容带本组件 scoped 属性，可直接命中） */
.wf-node {
  background: #fff; border: 1.5px solid var(--app-border); border-radius: 8px;
  padding: 8px 12px; min-width: 150px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  font-size: 13px; position: relative;
}
.wf-node-head { display: flex; align-items: center; gap: 7px; }
.wf-node-icon { font-size: 13px; }
.icon-start { color: #52c41a; }
.icon-end { color: #8c8c8c; }
.icon-llm { color: #7c5cff; }
.icon-retrieval { color: #1677ff; }
.icon-condition { color: #fa8c16; }
.wf-node-label { font-weight: 500; }
.wf-node-sub { font-size: 11px; color: var(--app-text3); margin-top: 3px; max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-node :deep(.vue-flow__handle) { width: 8px; height: 8px; background: var(--app-accent); border: none; }
.wf-node.run-success { border-color: #52c41a; box-shadow: 0 0 0 2px rgba(82, 196, 26, 0.15); }
.wf-node.run-failed { border-color: #ff4d4f; box-shadow: 0 0 0 2px rgba(255, 77, 79, 0.15); }
.wf-node-badge {
  position: absolute; top: -9px; right: -9px;
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 9px; color: #fff;
}
.badge-success { background: #52c41a; }
.badge-failed { background: #ff4d4f; }

/* 抽屉表单 */
.wf-form { display: flex; flex-direction: column; gap: 12px; }
.wf-form-item label { display: block; font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.wf-form-item { font-size: 13px; }
.wf-form-static { color: var(--app-text2); font-family: monospace; font-size: 12px; }
.wf-rows { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.wf-row-key { width: 110px; flex: none; }
.wf-row-value { flex: 1; }
.wf-branch { border: 1px dashed var(--app-border); border-radius: 6px; padding: 8px; margin-bottom: 8px; }
.wf-branch-radio { padding: 2px 0; }
.wf-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; }
.wf-hint code { background: var(--app-panel); padding: 1px 4px; border-radius: 3px; font-size: 11px; }
.wf-drawer-actions { margin-top: 16px; }
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
</style>
