<template>
  <a-modal :open="open" title="新建工作区" :footer="null" :width="560" @cancel="close">
    <div class="wq">
      <div class="wq-label">从现有工作区复制（可选）</div>
      <a-select v-model:value="templateId" allow-clear :options="templateOptions" style="width:100%"
                placeholder="不复制：空白工作区" />
      <div class="wq-hint">复制事项清单与工作区记忆骨架；不复制文件、会话与产物。</div>

      <div class="wq-label wq-label-row">
        <span>源文件夹</span>
        <button type="button" class="app-link-btn" :disabled="picking || !executor || !executor.online" @click="pickDir">
          <plus-outlined /> 添加
        </button>
      </div>
      <div class="wq-dirs">
        <div v-for="(d, i) in dirs" :key="d.path" class="wq-dir">
          <FolderOpenOutlined class="wq-dir-ic" />
          <span class="wq-dir-name" :title="d.path">{{ d.name }}</span>
          <span v-if="i === 0" class="wq-primary">主要</span>
          <button v-else type="button" class="app-link-btn wq-mini" @click="makePrimary(i)">设为主要</button>
          <button type="button" class="wq-remove" title="移除" @click="removeDir(i)">×</button>
        </div>
        <button v-if="!dirs.length" type="button" class="wq-drop" :class="{ busy: picking }" @click="pickDir">
          <FolderOpenOutlined class="wq-drop-ic" />
          <span>{{ picking ? '在那台电脑上选择中…' : '点击添加可读写文件夹（可多个）' }}</span>
        </button>
        <div v-else-if="picking" class="wq-hint">在那台电脑上选择中…</div>
      </div>
      <div v-if="!executorId || !executor" class="wq-hint">
        还没指定这台电脑
        <button type="button" class="app-link-btn" @click="goPickDevice">选择这台电脑</button>
      </div>
      <div v-else-if="!executor.online" class="wq-hint">
        「{{ executor.name }}」的执行器不在线：去那台电脑的终端确认 wenqu-agent 正在运行
      </div>
      <div v-else class="wq-hint">文件夹会绑定到「{{ executor.name }}」（这台电脑）</div>

      <div class="wq-label">工作区名称</div>
      <a-input v-model:value="name" :maxlength="60" placeholder="输入名称…" />

      <div class="wq-label">工作区图标</div>
      <div class="wq-icons">
        <button v-for="ic in ICONS" :key="ic.name" type="button" class="wq-icon"
                :class="{ on: icon === ic.name }" @click="icon = ic.name">
          <component :is="workspaceIconOf(ic.name, true)" />
        </button>
      </div>

      <div class="wq-label">工作区颜色</div>
      <div class="wq-colors">
        <button v-for="c in COLORS" :key="c" type="button" class="wq-color"
                :class="{ on: color === c }" :style="{ background: c }" :title="c" @click="color = c">
          <check-outlined v-if="color === c" class="wq-color-check" />
        </button>
      </div>

      <div class="wq-foot">
        <button type="button" class="app-btn ghost" @click="close">取消</button>
        <button type="button" class="app-btn" :disabled="!canCreate || creating" @click="create">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined, FolderOpenOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { WORKSPACE_ICONS, WORKSPACE_COLORS, workspaceIconOf } from '../utils/workspaceIcons'
import { localExecutorId } from '../utils/localExecutor'
import { createProject, getProject, listExecutors, listProjects, pickExecutorDir, updateProject,
         getWorkspaceMemory, saveWorkspaceMemory } from '../api'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['update:open', 'created', 'pick-device'])

const ICONS = WORKSPACE_ICONS
const COLORS = WORKSPACE_COLORS
const devices = ref([])
const executorId = ref('')
const dirs = ref([])
const name = ref('')
const icon = ref('folder')
const color = ref(COLORS[0])
const creating = ref(false)
const picking = ref(false)
const templateId = ref(undefined)
const templates = ref([])
const templateOptions = computed(() => templates.value.map(p => ({
  value: p.id, label: p.displayName || p.name || '未命名工作区'
})))

// 工作区固定建在这台电脑上：设备 id 只从「这台电脑」读，不再让用户挑别的电脑。
// 设备清单只用于显示本机名字与在线状态（离线设备也要列，否则状态没法说清）。
const executor = computed(() => devices.value.find(d => d.id === executorId.value) || null)
const canCreate = computed(() => dirs.value.length > 0 && name.value.trim()
  && !!executorId.value && !!executor.value && !!executor.value.online)
const goPickDevice = () => {
  emit('update:open', false)
  emit('pick-device')
}

const baseName = p => {
  const s = String(p || '').replace(/[/\\]+$/, '')
  const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'))
  return (i >= 0 ? s.slice(i + 1) : s) || '文件夹'
}

async function loadDevice () {
  try {
    const r = await listExecutors()
    devices.value = (r && r.data) || []
  } catch (e) {
    devices.value = []
  }
}

async function loadTemplates () {
  try {
    const r = await listProjects()
    templates.value = ((r && r.data) || []).filter(p => !p.isImplicit
      && (!p.executorId || p.executorId === localExecutorId()))
  } catch (e) {
    templates.value = []
  }
}

/** 选模板：沿用源工作区的目录与执行器，避免用户为「复制设置」再选一遍同一批文件夹 */
watch(templateId, async pid => {
  if (!pid) return
  try {
    const r = await getProject(pid)
    const p = (r && r.data) || {}
    executorId.value = p.executorId || executorId.value
    dirs.value = (p.roots || []).map(x => ({ path: x.path, name: x.alias || baseName(x.path) }))
    if (!name.value.trim()) name.value = (p.name || '工作区') + ' 副本'
  } catch (e) {
    message.warning('模板工作区读取失败：' + (e.message || '请重试'))
  }
})

/** 每次打开都重读一次：用户可能刚在「这台电脑」里换过设备，localStorage 不是响应式的 */
function refreshLocalDevice () {
  executorId.value = localExecutorId()
  loadDevice()
}

async function pickDir () {
  if (!executorId.value || !executor.value || !executor.value.online) {
    message.warning('这台电脑的执行器不在线：先在终端启动 wenqu-agent（侧栏「这台电脑」可查看状态）')
    return
  }
  picking.value = true
  try {
    const r = await pickExecutorDir(executorId.value)
    if (r && r.success === false) {
      const msg = String(r.msg || '')
      if (!/没有选择目录/.test(msg)) message.warning(msg || '没有选到目录')
      return
    }
    const picked = (r && r.data && r.data.path) || ''
    if (!picked) return
    if (dirs.value.some(d => d.path === picked)) {
      message.info('这个文件夹已经添加过了')
      return
    }
    dirs.value.push({ path: picked, name: baseName(picked) })
    if (!name.value.trim()) name.value = baseName(picked)
  } catch (e) {
    message.warning('没有选到目录：' + (e.message || ''))
  } finally {
    picking.value = false
  }
}

function removeDir (i) {
  dirs.value.splice(i, 1)
}
function makePrimary (i) {
  if (i <= 0) return
  const [d] = dirs.value.splice(i, 1)
  dirs.value.unshift(d)
}

const close = () => emit('update:open', false)

async function create () {
  if (!canCreate.value) return
  creating.value = true
  try {
    const roots = dirs.value.map(d => ({ path: d.path, alias: d.name }))
    const created = await createProject(name.value.trim(), executorId.value, roots, templateId.value)
    const id = (created && created.data && created.data.id) || ''
    if (!id) throw new Error('创建工作区没有返回 id')
    await updateProject(id, { icon: icon.value, color: color.value })
    // 模板复制只给记忆骨架：源工作区的具体结论可能已过期，不该原样继承
    if (templateId.value) {
      try {
        await saveWorkspaceMemory(id, '# 工作区记忆\n\n> 记录这个工作区的稳定事实：约定、决策、结构、坑、偏好。\n')
      } catch (e) { /* 执行器离线时不挡创建 */ }
    }
    emit('created', id)
    close()
  } catch (e) {
    message.error('创建失败：' + (e.message || ''))
  } finally {
    creating.value = false
  }
}

watch(() => props.open, open => {
  if (!open) return
  dirs.value = []
  name.value = ''
  icon.value = 'folder'
  color.value = COLORS[0]
  templateId.value = undefined
  refreshLocalDevice()
  loadTemplates()
})

onMounted(() => { if (props.open) refreshLocalDevice() })
</script>

<style scoped>
.wq { display: flex; flex-direction: column; gap: 8px; }
.wq-label { margin-top: 6px; font-size: 13px; font-weight: 600; color: var(--app-text); }
.wq-label-row { display: flex; align-items: center; justify-content: space-between; }
.wq-dirs { display: flex; flex-direction: column; gap: 6px; }
.wq-dir { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 10px;
  border: 1px solid var(--app-border, rgba(0,0,0,.08)); border-radius: 8px;
  background: var(--app-surface2, rgba(0,0,0,.02)); }
.wq-dir-ic { color: var(--app-accent); }
.wq-dir-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 13px; color: var(--app-text); }
.wq-primary { font-size: 11px; line-height: 18px; padding: 0 6px; border-radius: 4px;
  color: var(--app-accent); background: color-mix(in srgb, var(--app-accent) 12%, transparent); }
.wq-mini { font-size: 11px; }
.wq-remove { border: 0; background: none; cursor: pointer; color: var(--app-text3); font-size: 16px; line-height: 1; }
.wq-drop { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 78px;
  border: 1px dashed var(--app-border, rgba(0,0,0,.2)); border-radius: 10px; cursor: pointer;
  color: var(--app-text2); font-size: 13px; padding: 12px; background: none; }
.wq-drop:hover { border-color: var(--app-accent); background: var(--app-surface2, rgba(0,0,0,.02)); }
.wq-drop.busy { opacity: .7; pointer-events: none; }
.wq-drop-ic { font-size: 18px; color: var(--app-accent); }
.wq-hint { font-size: 12px; color: var(--app-text3); display: flex; align-items: center; gap: 6px; }
.wq-icons { display: flex; flex-wrap: wrap; gap: 8px; }
.wq-icon { width: 34px; height: 34px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid transparent; border-radius: 8px; background: var(--app-surface2, rgba(0,0,0,.04));
  cursor: pointer; color: var(--app-text2); font-size: 16px; }
.wq-icon.on { border-color: var(--app-accent); color: var(--app-accent); background: transparent; }
.wq-colors { display: flex; flex-wrap: wrap; gap: 10px; }
/* 选中态不能只靠 outline：这里曾写成 var(--app-text1)（主题里没有这个变量），
   整条 outline 简写在计算期作废、环一个都看不见，点颜色像是没反应。
   现在以对勾为主、取 --app-text 的描边为辅，暗色块上也认得出来 */
.wq-color { width: 22px; height: 22px; border-radius: 50%; border: 2px solid transparent; cursor: pointer; padding: 0;
  display: inline-flex; align-items: center; justify-content: center; }
.wq-color.on { outline: 2px solid var(--app-text); outline-offset: 2px; }
.wq-color-check { color: #fff; font-size: 12px; line-height: 1; pointer-events: none;
  filter: drop-shadow(0 0 1px rgba(0,0,0,.55)); }
.wq-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; padding-top: 12px;
  border-top: 1px solid var(--app-border, rgba(0,0,0,.08)); }
</style>
