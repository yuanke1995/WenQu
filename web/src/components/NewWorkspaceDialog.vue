<template>
  <a-modal :open="open" title="新建工作区" :footer="null" :width="560" @cancel="close">
    <div class="wq">
      <div class="wq-label">源文件夹</div>
      <div class="wq-drop" :class="{ filled: !!dir, busy: picking }" @click="pickDir">
        <template v-if="!dir">
          <FolderOpenOutlined class="wq-drop-ic" />
          <span>{{ picking ? '在那台电脑上选择中…' : '点击添加可读写文件夹' }}</span>
        </template>
        <template v-else>
          <FolderOpenOutlined class="wq-drop-ic" />
          <span class="wq-drop-path" :title="dir">{{ dir }}</span>
          <button type="button" class="app-link-btn" @click.stop="pickDir">更换</button>
        </template>
      </div>
      <div v-if="!executor" class="wq-hint">
        还没有在线的执行器
        <button type="button" class="app-link-btn" @click="$emit('open-pairing')">去授权这台电脑</button>
      </div>

      <div class="wq-label">工作区名称</div>
      <a-input v-model:value="name" :maxlength="60" placeholder="输入名称…" />

      <div class="wq-label">工作区图标</div>
      <div class="wq-icons">
        <button v-for="ic in ICONS" :key="ic.name" type="button" class="wq-icon"
                :class="{ on: icon === ic.name }" @click="icon = ic.name">
          <component :is="ic.cmp" />
        </button>
      </div>

      <div class="wq-label">工作区颜色</div>
      <div class="wq-colors">
        <button v-for="c in COLORS" :key="c" type="button" class="wq-color"
                :class="{ on: color === c }" :style="{ background: c }" @click="color = c" />
      </div>

      <div class="wq-foot">
        <button type="button" class="app-btn ghost" @click="close">取消</button>
        <button type="button" class="app-btn" :disabled="!dir || !name.trim() || creating" @click="create">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </div>
    </div>
  </a-modal>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  FolderOpenOutlined, FolderOutlined, CoffeeOutlined, ExperimentOutlined, RobotOutlined,
  AimOutlined, AppstoreOutlined, SettingOutlined, ThunderboltOutlined, BookOutlined,
  CodeOutlined, DatabaseOutlined, FileTextOutlined, StarOutlined
} from '@ant-design/icons-vue'
import { bindProjectExecutor, createProject, listExecutors, pickExecutorDir, updateProject } from '../api'

const props = defineProps({ open: { type: Boolean, default: false } })
const emit = defineEmits(['update:open', 'created', 'open-pairing'])

const ICONS = [
  { name: 'folder', cmp: FolderOutlined },
  { name: 'coffee', cmp: CoffeeOutlined },
  { name: 'experiment', cmp: ExperimentOutlined },
  { name: 'robot', cmp: RobotOutlined },
  { name: 'aim', cmp: AimOutlined },
  { name: 'appstore', cmp: AppstoreOutlined },
  { name: 'setting', cmp: SettingOutlined },
  { name: 'thunderbolt', cmp: ThunderboltOutlined },
  { name: 'book', cmp: BookOutlined },
  { name: 'code', cmp: CodeOutlined },
  { name: 'database', cmp: DatabaseOutlined },
  { name: 'file', cmp: FileTextOutlined },
  { name: 'star', cmp: StarOutlined }
]
const COLORS = [
  '#3f7f5f', '#2f6fed', '#f08a24', '#f0b429', '#e5484d', '#d6338c', '#7b4fd1',
  '#4f66d1', '#1f9ec9', '#2aa198', '#6b7280', '#111827', '#b39ddb', '#5cb85c'
]

const dir = ref('')
const name = ref('')
const icon = ref('folder')
const color = ref(COLORS[0])
const creating = ref(false)
const picking = ref(false)
const executor = ref(null)

async function loadDevice () {
  try {
    const r = await listExecutors()
    const online = ((r && r.data) || []).filter(d => d.online)
    executor.value = online[0] || null
  } catch (e) {
    executor.value = null
  }
}

async function pickDir () {
  if (!executor.value) {
    message.warning('还没有在线的执行器：先在那台电脑上启动 wenqu-agent')
    return
  }
  picking.value = true
  try {
    const r = await pickExecutorDir(executor.value.id)
    if (r && r.success === false) {
      const msg = String(r.msg || '')
      if (!/没有选择目录/.test(msg)) message.warning(msg || '没有选到目录')
      return
    }
    const picked = (r && r.data && r.data.path) || ''
    if (!picked) return
    dir.value = picked
    if (!name.value.trim()) name.value = picked.replace(/\/+$/, '').split('/').pop() || '本机工作区'
  } catch (e) {
    message.warning('没有选到目录：' + (e.message || ''))
  } finally {
    picking.value = false
  }
}

const close = () => emit('update:open', false)

async function create () {
  if (!dir.value || !name.value.trim() || !executor.value) return
  creating.value = true
  try {
    const created = await createProject(name.value.trim(), undefined, 'local')
    const id = (created && created.data && created.data.id) || ''
    if (!id) throw new Error('创建工作区没有返回 id')
    await bindProjectExecutor(id, executor.value.id, dir.value)
    await updateProject(id, { icon: icon.value, color: color.value })
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
  dir.value = ''
  name.value = ''
  icon.value = 'folder'
  color.value = COLORS[0]
  loadDevice()
})

onMounted(() => { if (props.open) loadDevice() })
</script>

<style scoped>
.wq { display: flex; flex-direction: column; gap: 8px; }
.wq-label { margin-top: 6px; font-size: 13px; font-weight: 600; color: var(--app-text1); }
.wq-drop { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 96px;
  border: 1px dashed var(--app-border, rgba(0,0,0,.2)); border-radius: 10px; cursor: pointer;
  color: var(--app-text2); font-size: 13px; padding: 12px; transition: border-color .15s, background .15s; }
.wq-drop:hover { border-color: var(--app-accent); background: var(--app-surface2, rgba(0,0,0,.02)); }
.wq-drop.filled { justify-content: flex-start; }
.wq-drop.busy { opacity: .7; pointer-events: none; }
.wq-drop-ic { font-size: 18px; color: var(--app-accent); }
.wq-drop-path { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--app-text1); }
.wq-hint { font-size: 12px; color: var(--app-text3); }
.wq-icons { display: flex; flex-wrap: wrap; gap: 8px; }
.wq-icon { width: 34px; height: 34px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid transparent; border-radius: 8px; background: var(--app-surface2, rgba(0,0,0,.04));
  cursor: pointer; color: var(--app-text2); font-size: 16px; }
.wq-icon.on { border-color: var(--app-accent); color: var(--app-accent); background: transparent; }
.wq-colors { display: flex; flex-wrap: wrap; gap: 10px; }
.wq-color { width: 22px; height: 22px; border-radius: 50%; border: 2px solid transparent; cursor: pointer; padding: 0; }
.wq-color.on { outline: 2px solid var(--app-text1); outline-offset: 2px; }
.wq-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; padding-top: 12px;
  border-top: 1px solid var(--app-border, rgba(0,0,0,.08)); }
</style>
