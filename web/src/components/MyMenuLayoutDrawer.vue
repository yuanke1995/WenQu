<template>
  <a-drawer v-model:open="open" title="我的侧栏布局" :width="480" destroy-on-close @close="close">
    <a-alert type="info" show-icon style="margin-bottom:12px"
             message="只影响你自己的侧栏，其他同事不受影响" />

    <div class="ml-bar">
      <span class="ml-count">显示 {{ shown }} / 共 {{ rows.length }}</span>
      <span style="flex:1"></span>
      <button class="app-link-btn" @click="setAll(true)">全部显示</button>
      <button class="app-link-btn" @click="setAll(false)">全部隐藏</button>
      <a-popconfirm title="恢复到系统默认顺序，并把隐藏项全部显示出来，确定？"
                    ok-text="恢复" cancel-text="取消" @confirm="resetDefault">
        <button class="app-link-btn danger" style="margin-left:10px">恢复默认</button>
      </a-popconfirm>
    </div>
    <p class="ml-tip">拖动行可调整顺序（上下移动调整精细位置）；「新建对话」是固定入口，不在此列表。</p>

    <a-spin :spinning="loading">
      <div v-if="rows.length" class="ml-list">
        <!-- 原生 HTML5 拖拽：零依赖。整行可拖，但开关/箭头按钮需 stopPropagation，
             否则拨开关的手势会被父行的 dragstart 吃掉（点击时浏览器会先触发 dragstart）。
             刻意不做「拖到别的父节点下」：跨级会改菜单树结构，而树结构参与 RBAC 授权判定，
             把结构调整混进「调整口味」的手势里太易误操作 —— 跨级请用编辑菜单弹窗的父级选择。 -->
        <div v-for="(r, i) in rows" :key="r.id"
             class="ml-row"
             :class="{ off: r.hidden, 'is-drag': dragIndex === i, 'is-over': overIndex === i && dragIndex !== null && dragIndex !== i }"
             draggable="true"
             @dragstart="onDragStart(i, $event)"
             @dragover.prevent="onDragOver(i, $event)"
             @drop.prevent="onDrop(i, $event)"
             @dragend="onDragEnd"
             @dragleave="onDragLeave(i)">
          <span class="ml-grip" aria-hidden="true">⋮⋮</span>
          <component :is="iconOf(r.icon)" class="ml-ic" />
          <span class="ml-name" :title="r.name">{{ r.name }}</span>
          <span class="ml-ops" @mousedown.stop @click.stop>
            <button class="app-icon-btn ml-mv" title="上移" :disabled="i === 0" @click="move(i, -1)">
              <arrow-up-outlined />
            </button>
            <button class="app-icon-btn ml-mv" title="下移" :disabled="i === rows.length - 1" @click="move(i, 1)">
              <arrow-down-outlined />
            </button>
            <a-switch :checked="!r.hidden" size="small" @change="v => r.hidden = !v" />
          </span>
        </div>
      </div>
      <div v-else-if="!loading" class="ml-empty">暂无可调整的菜单</div>
    </a-spin>

    <template #footer>
      <button class="app-btn" style="margin-right:8px" @click="close">取消</button>
      <button class="app-btn primary" :disabled="saving || !dirty" @click="save">
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </template>
  </a-drawer>
</template>

<script setup>
/**
 * 我的侧栏布局（纯个人偏好）
 * ---------------------------------------------------------------------------
 * 位置：入口在 AppLayout 侧栏底部用户区（齿轮），**不放权限管理页** —— 布局是个人口味，
 * 让管理员去改别人的侧栏没有意义。
 *
 * 与「角色权限」的关系（关键，别混淆）：
 *   - 谁能**看到**哪些菜单 = RBAC（c_ai_role_menu，管理员配置，fail-closed）；
 *   - 谁能**用**哪些接口 = RBAC（c_ai_role_api）；
 *   - 我把看到的菜单**怎么排、藏哪几个** = 本组件（c_ai_user.menu_pref，只影响本人）。
 *   本组件拿到的列表来自 /auth/me（已按角色过滤），所以这里最多只能「重排/隐藏自己已有的」，
 *   天然无法越权看到未授权菜单；后端保存时还会再用白名单过滤一次（丢弃陌生 id）。
 *
 * 草稿设计：打开时深拷贝，改动只落草稿，取消即丢弃；保存才发请求。
 */
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined,
  FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined,
  ArrowUpOutlined, ArrowDownOutlined
} from '@ant-design/icons-vue'
import { getMyMenuLayout, saveMyMenuLayout } from '../api'

const props = defineProps({
  open: Boolean,
  /** /auth/me 下发的可见菜单（顶层平铺，已按角色过滤、已生效个人偏好） */
  menus: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'saved'])

const ICONS = {
  MessageOutlined, RobotOutlined, DatabaseOutlined, TeamOutlined, BarChartOutlined,
  ExperimentOutlined, SafetyOutlined, SettingOutlined, AppstoreOutlined,
  FolderOutlined, UserOutlined, FileOutlined, FileTextOutlined
}
const iconOf = n => ICONS[n] || FileOutlined

const open = ref(false)
const loading = ref(false)
const saving = ref(false)
/** 草稿行：{ id, name, icon, hidden } */
const rows = ref([])
/** 打开时的原始态，用于算脏 */
const base = ref('')

const shown = computed(() => rows.value.filter(r => !r.hidden).length)
const sig = list => list.map(r => `${r.id}:${r.hidden ? 0 : 1}`).join('|')
const dirty = computed(() => sig(rows.value) !== base.value)

watch(() => props.open, async v => {
  if (!v) return
  loading.value = true
  try {
    // 拉一次服务端偏好，与当前可见菜单取交集：
    // 偏好里的 id 可能已失效（菜单被删/角色变更），此处自然丢弃
    const r = await getMyMenuLayout()
    const hidden = new Set((r && r.data && r.data.hidden) || [])
    rows.value = (props.menus || []).map(m => ({
      id: m.id, name: m.name, icon: m.icon, hidden: hidden.has(m.id)
    }))
    base.value = sig(rows.value)
  } catch (e) {
    message.error(e.message || '读取个人布局失败')
    rows.value = []
    base.value = ''
  } finally { loading.value = false }
})

function move (i, dir) {
  const j = i + dir
  if (j < 0 || j >= rows.value.length) return
  const list = rows.value
  const t = list[i]; list[i] = list[j]; list[j] = t
}
function setAll (v) { rows.value.forEach(r => { r.hidden = !v }) }

/* ==================== 拖拽排序（仅同级内重排） ====================
 * 原生 HTML5 DnD，不用拖拽库。三个必须处理的点：
 * 1. dragIndex 存的是「当前下标」而非原始下标 —— 拖动中我们每越过一行就 swap 一次，
 *    源行本身会随数组变动而移动，固定原下标会算错位置。
 * 2. dragover 必须 preventDefault，否则浏览器拒绝 drop（无 drop 事件）。
 * 3. 拨开关/点箭头会连带触发父行 dragstart（mousedown 即启动拖拽），
 *    故 .ml-ops 上 @mousedown.stop 掐断起点。
 */
const dragIndex = ref(null)
const overIndex = ref(null)

function onDragStart (i, ev) {
  dragIndex.value = i
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move'
    // Firefox 要求设置数据才会真正启动拖拽；纯文本 payload 即可（不会真的用它）
    try { ev.dataTransfer.setData('text/plain', String(i)) } catch (e) { /* IE 兼容路径，忽略 */ }
  }
}
function onDragOver (i, ev) {
  if (dragIndex.value === null) return
  ev.dataTransfer.dropEffect = 'move'
  if (overIndex.value !== i) overIndex.value = i
  // 悬停越过中线即换位：拖动项实时让位，视觉上「跟着手走」
  const r = ev.currentTarget.getBoundingClientRect()
  const crossed = ev.clientY > r.top + r.height / 2
  const from = dragIndex.value
  const to = crossed ? i + 1 : i
  if (to !== from && to >= 0 && to < rows.value.length) {
    splice(from, to)
    dragIndex.value = to
    overIndex.value = to
  }
}
function onDrop (i) {
  if (dragIndex.value !== null && dragIndex.value !== i) {
    splice(dragIndex.value, i)
  }
  onDragEnd()
}
function onDragLeave (i) {
  if (overIndex.value === i) overIndex.value = null
}
function onDragEnd () {
  dragIndex.value = null
  overIndex.value = null
}
/** 把 from 位置的项挪到 to 位置（to 会被该项占据，其余顺移） */
function splice (from, to) {
  const list = rows.value
  const [item] = list.splice(from, 1)
  list.splice(to, 0, item)
}

async function save () {
  saving.value = true
  try {
    // order 只传「可见的」+ hidden 传隐藏的；两者互斥表达，隐藏项留在 order 里也无妨
    const res = await saveMyMenuLayout({
      order: rows.value.map(r => r.id),
      hidden: rows.value.filter(r => r.hidden).map(r => r.id)
    })
    if (res && res.success) { message.success('已保存'); emit('saved'); emit('close') }
    else message.error((res && res.msg) || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

/** 恢复默认：服务端把列置 null，前端按 props.menus 原序重置草稿并关闭 */
async function resetDefault () {
  saving.value = true
  try {
    const res = await saveMyMenuLayout({ reset: true })
    if (res && res.success) {
      message.success('已恢复默认')
      rows.value = (props.menus || []).map(m => ({ id: m.id, name: m.name, icon: m.icon, hidden: false }))
      base.value = sig(rows.value)
      emit('saved')
      emit('close')
    } else message.error((res && res.msg) || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { saving.value = false }
}

const close = () => emit('close')
</script>

<style scoped>
.ml-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.ml-count { font-size: 12px; color: var(--app-text3); }
.ml-tip { margin: 0 0 10px; font-size: 12px; color: var(--app-text3); }
.ml-list { display: flex; flex-direction: column; gap: 2px; }
.ml-row { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; border: 1px solid var(--app-border); background: var(--app-panel, var(--app-panel-2)); cursor: grab; }
/* 隐藏项压低存在感（开关状态本身已明示，这里只做视觉弱化） */
.ml-row.off { opacity: .55; }
/* 拖动中的源行：半透明让位；目标行：描边提示落点。二者同时存在才看得出「谁在动、要落哪」 */
.ml-row.is-drag { opacity: .35; cursor: grabbing; }
.ml-row.is-over { border-color: var(--app-accent); box-shadow: inset 0 0 0 1px var(--app-accent); }
.ml-grip { flex: none; font-size: 12px; line-height: 1; color: var(--app-text3); letter-spacing: -2px; }
.ml-ic { flex: none; font-size: 14px; color: var(--app-text2); }
.ml-name { flex: 1 1 auto; min-width: 0; font-size: 13px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ml-ops { flex: none; display: flex; align-items: center; gap: 4px; }
.ml-mv { font-size: 11px; padding: 3px 5px; }
.ml-mv:disabled { opacity: .3; cursor: not-allowed; }
.ml-empty { padding: 24px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
</style>
