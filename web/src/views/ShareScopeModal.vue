<template>
  <a-modal :open="open" :title="'共享范围 · ' + resourceLabel" :width="600" :confirm-loading="saving"
           :ok-button-props="{ disabled: invalid }" ok-text="保存" cancel-text="取消"
           @update:open="v => emit('update:open', v)" @ok="save">
    <a-alert v-if="violation" type="warning" show-icon style="margin-bottom:12px"
             message="管理权限范围大于读取权限，请调整后再保存。" />

    <!-- 读取权限 -->
    <div class="scope-section">
      <div class="scope-head">
        <span class="scope-title">读取权限</span>
        <span class="scope-note">谁可以{{ readVerb }}此{{ resourceLabel }}</span>
      </div>
      <div class="scope-cards">
        <div v-for="o in SHARE_LEVELS" :key="o.value" class="scope-card"
             :class="{ active: form.read.level === o.value }" @click="form.read.level = o.value">
          <component :is="o.icon" class="scope-card-ic" />
          <div class="scope-card-txt">
            <div class="scope-card-title">{{ o.title }}</div>
            <div class="scope-card-desc">{{ o.desc }}</div>
          </div>
          <a-popover v-if="form.read.level === o.value && hasPicker(o.value)" trigger="click"
                     placement="bottomLeft" overlay-class-name="scope-pop">
            <template #content>
              <div class="sel-panel">
                <div class="sel-head">
                  <span class="sel-title">可选{{ o.value === 'department' ? '部门' : '用户' }}</span>
                  <span class="sel-sub">已选 {{ pickCount('read') }}</span>
                </div>
                <a-input v-model:value="readSearch" size="small" allow-clear
                         :placeholder="o.value === 'department' ? '搜索部门' : '搜索用户'" />
                <div class="sel-list">
                  <div v-for="p in filteredOptions('read')" :key="p.value" class="sel-item"
                       @click="togglePick('read', p.value)">
                    <a-checkbox :checked="isPicked('read', p.value)" style="pointer-events:none" />
                    <span class="sel-label">{{ p.label }}</span>
                  </div>
                  <div v-if="!filteredOptions('read').length" class="sel-empty">无可选项</div>
                </div>
              </div>
            </template>
            <button class="scope-count" @click.stop><user-add-outlined /><span>{{ pickCount('read') }}</span></button>
          </a-popover>
        </div>
      </div>
    </div>

    <!-- 共享管理权限（可整体关闭） -->
    <div class="scope-section">
      <div class="scope-head">
        <span class="scope-title">共享管理权限</span>
        <span class="scope-note">可编辑 / 删除 / 改共享范围（含读取）</span>
        <a-switch v-model:checked="form.manageOn" size="small" class="scope-switch"
                  :disabled="form.read.level === 'private'" />
      </div>
      <template v-if="form.manageOn">
        <div class="scope-cards">
          <div v-for="o in SHARE_LEVELS" :key="o.value" class="scope-card"
               :class="{ active: form.manage.level === o.value }" @click="form.manage.level = o.value">
            <component :is="o.icon" class="scope-card-ic" />
            <div class="scope-card-txt">
              <div class="scope-card-title">{{ o.title }}</div>
              <div class="scope-card-desc">{{ o.desc }}</div>
            </div>
            <a-popover v-if="form.manage.level === o.value && hasPicker(o.value)" trigger="click"
                       placement="bottomLeft" overlay-class-name="scope-pop">
              <template #content>
                <div class="sel-panel">
                  <div class="sel-head">
                    <span class="sel-title">可选{{ o.value === 'department' ? '部门' : '用户' }}</span>
                    <span class="sel-sub">已选 {{ pickCount('manage') }}</span>
                  </div>
                  <a-input v-model:value="manageSearch" size="small" allow-clear
                           :placeholder="o.value === 'department' ? '搜索部门' : '搜索用户'" />
                  <div class="sel-list">
                    <div v-for="p in filteredOptions('manage')" :key="p.value" class="sel-item"
                         @click="togglePick('manage', p.value)">
                      <a-checkbox :checked="isPicked('manage', p.value)" style="pointer-events:none" />
                      <span class="sel-label">{{ p.label }}</span>
                    </div>
                    <div v-if="!filteredOptions('manage').length" class="sel-empty">无可选项</div>
                  </div>
                </div>
              </template>
              <button class="scope-count" @click.stop><user-add-outlined /><span>{{ pickCount('manage') }}</span></button>
            </a-popover>
          </div>
        </div>
      </template>
      <div v-else class="scope-off">已关闭：除你与管理员级外，无人可管理此{{ resourceLabel }}。</div>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { LockOutlined, GlobalOutlined, ApartmentOutlined, UserOutlined, UserAddOutlined } from '@ant-design/icons-vue'
import { listDepartments, listUsers } from '../api'

const props = defineProps({
  /** 弹窗开关（配合 v-model:open） */
  open: { type: Boolean, default: false },
  /** 资源名，用于文案：文档 / 智能体 / API Key */
  resourceLabel: { type: String, default: '资源' },
  /** 读取动作描述：文档=查看并检索、智能体=使用、API Key=查看 */
  readVerb: { type: String, default: '查看' },
  /** 当前 share_config 原文（空 = 未配置 = 仅自己，谁建归谁） */
  shareConfig: { type: String, default: '' },
  /** 保存回调：async (json) => ResultJson；由调用方决定打到哪个接口 */
  saveFn: { type: Function, required: true }
})
const emit = defineEmits(['update:open', 'saved'])

const SHARE_LEVELS = [
  { value: 'private', title: '仅自己', desc: '不共享：只有你与管理员级可见（默认）', icon: LockOutlined },
  { value: 'global', title: '全局共享', desc: '所有用户都可以访问', icon: GlobalOutlined },
  { value: 'department', title: '部门共享', desc: '选中的部门可访问', icon: ApartmentOutlined },
  { value: 'user', title: '指定人', desc: '选中的用户可以访问', icon: UserOutlined }
]
// 仅 private / global 两档没有成员选择器
const hasPicker = v => v !== 'private' && v !== 'global'

const saving = ref(false)
const readSearch = ref('')
const manageSearch = ref('')
const form = ref({
  read: { level: 'private', departmentIds: [], userUids: [] },
  manageOn: false,
  manage: { level: 'global', departmentIds: [], userUids: [] }
})
const departments = ref([])
const users = ref([])
let optionsLoaded = false

const scope = sec => (sec === 'read' ? form.value.read : form.value.manage)
const pickArr = sec => {
  const s = scope(sec)
  return s.level === 'department' ? s.departmentIds : s.userUids
}
const pickCount = sec => pickArr(sec).length
const pickOptions = sec => (scope(sec).level === 'department' ? departments : users).value
const pickSearch = sec => (sec === 'read' ? readSearch.value : manageSearch.value)
function filteredOptions (sec) {
  const k = pickSearch(sec).trim().toLowerCase()
  const list = pickOptions(sec)
  return k ? list.filter(o => String(o.label).toLowerCase().includes(k)) : list
}
const isPicked = (sec, val) => pickArr(sec).includes(val)
function togglePick (sec, val) {
  const arr = pickArr(sec)
  const i = arr.indexOf(val)
  if (i >= 0) arr.splice(i, 1)
  else arr.push(val)
}

/** 越权判定：管理范围是否超出读取范围（须与后端 validateShareConfig 语义一致） */
const violation = computed(() => {
  const f = form.value
  if (f.read.level === 'private' || !f.manageOn) return false
  const rank = { global: 0, department: 1, user: 2 }
  const r = f.read.level
  const m = f.manage.level
  if (r === 'global') return false
  if (m === 'global') return true
  if (rank[r] > rank[m]) return true
  if (r === 'department' && m === 'user') return true
  if (r === m && r === 'department') return !f.manage.departmentIds.every(id => f.read.departmentIds.includes(id))
  if (r === m && r === 'user') return !f.manage.userUids.every(u => f.read.userUids.includes(u))
  return false
})
const invalid = computed(() => {
  const f = form.value
  if (!['global', 'private'].includes(f.read.level) && !pickCount('read')) return true
  if (f.manageOn && !['global', 'private'].includes(f.manage.level) && !pickCount('manage')) return true
  return violation.value
})

function parseScope (s) {
  return {
    level: (s && s.access_level) || 'global',
    departmentIds: (s && Array.isArray(s.department_ids)) ? s.department_ids.slice() : [],
    userUids: (s && Array.isArray(s.user_uids)) ? s.user_uids.slice() : []
  }
}
const privateScope = () => ({ level: 'private', departmentIds: [], userUids: [] })

function init () {
  let cfg = null
  if (props.shareConfig && String(props.shareConfig).trim()) {
    try { cfg = JSON.parse(props.shareConfig) } catch (e) { cfg = null }
  }
  const present = cfg !== null
  const r = (cfg && cfg.read_scope) || null
  const m = (cfg && cfg.manage_scope) || null
  form.value = {
    // 无 share_config = 仅自己（谁建归谁）；有配置但未声明 read_scope = 后端语义「不命中任何人」，
    // 表单按仅自己近似呈现（补一次保存即落为标准私有）
    read: (present && r) ? parseScope(r) : privateScope(),
    // 有配置但未声明 manage_scope = 显式关闭管理（仅自己与管理员级）→ 开关关闭
    manageOn: present ? Boolean(m) : false,
    manage: parseScope(m || r)
  }
  readSearch.value = ''
  manageSearch.value = ''
  loadOptions()
}

async function loadOptions () {
  if (optionsLoaded) return
  try {
    const [depts, us] = await Promise.all([listDepartments(), listUsers()])
    departments.value = ((depts && depts.data) || []).map(x => ({ label: x.name, value: x.id }))
    users.value = ((us && us.data) || []).map(x => ({ label: (x.username || x.uid) + '（' + x.uid + '）', value: x.uid }))
    optionsLoaded = true
  } catch (e) { /* 下拉加载失败不阻断：仍可正常保存 */ }
}

function scopeToJson (s) {
  const o = { access_level: s.level }
  if (s.level === 'department') o.department_ids = s.departmentIds
  if (s.level === 'user') o.user_uids = s.userUids
  return o
}
function buildJson (f) {
  // 仅自己 = 未配置 → 空串（后端语义：空=私有，谁建归谁）；
  // 显式选择的全局/部门/指定人都必须落库——全局不再等价于无配置（无配置已是私有）
  if (f.read.level === 'private') return ''
  const cfg = { version: 2, read_scope: scopeToJson(f.read) }
  if (f.manageOn) cfg.manage_scope = scopeToJson(f.manage)
  return JSON.stringify(cfg)
}

async function save () {
  if (invalid.value) {
    message.warning(violation.value ? '管理范围不能宽于读取范围' : '请补齐未选择的部门 / 用户')
    return
  }
  saving.value = true
  try {
    const r = await props.saveFn(buildJson(form.value))
    if (r && r.success) {
      message.success('共享范围已保存')
      emit('update:open', false)
      emit('saved')
    } else {
      message.error((r && r.msg) || '保存失败')
    }
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

watch(() => props.open, v => { if (v) init() })
</script>

<style scoped>
.scope-section { margin-bottom: 14px; }
.scope-section:last-child { margin-bottom: 0; }
.scope-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.scope-title { font-size: 13px; font-weight: 500; color: var(--app-text); }
.scope-note { font-size: 12px; color: var(--app-text3); }
.scope-switch { margin-left: auto; }
.scope-cards { display: flex; flex-direction: column; gap: 8px; }
.scope-card {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px;
  border: 1px solid var(--app-border); border-radius: 8px; cursor: pointer;
  background: var(--app-panel); transition: border-color .15s, background .15s;
}
.scope-card:hover { border-color: var(--app-accent); }
.scope-card.active { border-color: var(--app-accent); background: var(--app-accent-weak); }
.scope-card-ic { font-size: 16px; color: var(--app-text3); flex: none; }
.scope-card.active .scope-card-ic { color: var(--app-accent); }
.scope-card-txt { min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.scope-card-title { font-size: 13px; color: var(--app-text); white-space: nowrap; }
.scope-card-desc { font-size: 12px; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.scope-count {
  margin-left: auto; flex: none; display: inline-flex; align-items: center; gap: 4px;
  height: 24px; padding: 0 10px; font-size: 12px; color: var(--app-text2);
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 6px; cursor: pointer;
}
.scope-count:hover { border-color: var(--app-accent); }
.scope-off { font-size: 12px; color: var(--app-text3); padding: 9px 12px; background: var(--app-bg); border-radius: 8px; }
.sel-panel { width: 240px; }
.sel-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 8px; }
.sel-title { font-size: 12px; font-weight: 500; color: var(--app-text); }
.sel-sub { font-size: 11px; color: var(--app-text3); }
.sel-list { max-height: 220px; overflow-y: auto; margin-top: 8px; }
.sel-item { display: flex; align-items: center; gap: 8px; padding: 5px 6px; border-radius: 6px; cursor: pointer; }
.sel-item:hover { background: var(--app-accent-weak); }
.sel-label { font-size: 12px; color: var(--app-text2); }
.sel-empty { font-size: 12px; color: var(--app-text3); padding: 12px 0; text-align: center; }
</style>
