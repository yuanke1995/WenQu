<template>
  <a-modal v-model:open="visible" :width="720" :title="title" :footer="null" :mask-closable="false">
    <!-- 第一步：填目标 -->
    <template v-if="!result">
      <div class="bm-field">
        <label>{{ targetLabel }}</label>
        <a-select v-if="action === 'setDepartment'" v-model:value="departmentId" allow-clear
                  :options="deptOptions" placeholder="选择目标部门" style="width:100%" />
        <a-select v-else-if="action === 'addRoles'" v-model:value="roleCodes" mode="multiple"
                  :options="roleOptions" placeholder="选择要追加的角色" style="width:100%"
                  :disabled="disabledRoleCodes.length ? disabledRoleCodes : []" />
        <a-radio-group v-else-if="action === 'setStatus'" v-model:value="status" button-style="solid" size="small">
          <a-radio-button :value="1">启用</a-radio-button>
          <a-radio-button :value="0">禁用</a-radio-button>
        </a-radio-group>
      </div>

      <div v-if="action === 'setDepartment'" class="bm-field bm-inline">
        <label style="margin:0">覆盖策略</label>
        <a-radio-group v-model:value="onlyIfBlank" size="small">
          <a-radio :value="false">覆盖原部门</a-radio>
          <a-radio :value="true">仅填空白</a-radio>
        </a-radio-group>
        <span class="form-tip" style="margin:0">
          覆盖会改变该成员的数据可见范围（部门级共享）
        </span>
      </div>

      <div class="bm-sec">变更摘要</div>
      <div class="bm-diff">
        <div class="bm-diff-row bm-diff-head">
          <span>成员</span><span>{{ fromLabel }}</span><span></span><span>{{ toLabel }}</span>
        </div>
        <div v-for="d in diffRows" :key="d.uid" class="bm-diff-row">
          <span class="bm-who">{{ d.name }} <code>{{ d.uid }}</code></span>
          <span class="bm-from">{{ d.from }}</span>
          <span class="bm-arw">→</span>
          <span class="bm-to">{{ d.to }}</span>
        </div>
        <div v-if="!diffRows.length" class="bm-empty">没有可变更的成员</div>
      </div>

      <div class="bm-warn" v-if="idpHits.length">
        <span>!</span>
        <div>
          {{ idpHits.length }} 名成员持有 IdP 组映射授予的角色，批量追加不会覆盖它们（IdP 权威）。
          执行后这些成员会逐条给出结果，不会静默跳过。
        </div>
      </div>

      <div class="bm-foot">
        <span class="form-tip" style="margin:0">已选 {{ members.length }} 名成员 · 执行前先看会发生什么</span>
        <span style="flex:1"></span>
        <button class="app-btn ghost" @click="close">取消</button>
        <button class="app-btn" :disabled="!canSubmit" :loading="running" @click="run">确认执行</button>
      </div>
    </template>

    <!-- 第二步：逐条结果 -->
    <template v-else>
      <div class="bm-sec" style="margin-top:0">执行结果</div>
      <div class="bm-result">
        <div v-for="r in resultRows" :key="r.uid" class="bm-res" :class="r.ok ? 'ok' : 'err'">
          <span class="bm-res-ic">{{ r.ok ? '✓' : '×' }}</span>
          <span>{{ r.name }}</span>
          <span style="flex:1"></span>
          <span class="bm-res-txt">{{ r.ok ? '成功' : r.reason }}</span>
        </div>
      </div>
      <div class="bm-foot">
        <span class="form-tip" style="margin:0">
          成功 {{ result.succeeded.length }} 条 · 失败 {{ result.failed.length }} 条（失败项不影响其他成员）
        </span>
        <span style="flex:1"></span>
        <button class="app-btn ghost" @click="close">关闭</button>
        <button class="app-btn" @click="again">再操作一批</button>
      </div>
    </template>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { batchUsers } from '../../api'

/**
 * 批量操作弹窗（成员管理页批量条用）。
 * <p>两段式：填目标 → 看变更摘要 → 执行 → 看逐条结果。
 * <p>逐条结果不吞错：失败项带原因原样回显（IdP 角色不可改、最后一名管理员等）。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  /** 动作：setDepartment | addRoles | setStatus */
  action: { type: String, default: 'setDepartment' },
  /** 选中的成员行 */
  members: { type: Array, default: () => [] },
  /** 部门清单 */
  departments: { type: Array, default: () => [] },
  /** 角色清单 */
  roles: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:open', 'done'])

const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})

const departmentId = ref(undefined)
const roleCodes = ref([])
const status = ref(1)
const onlyIfBlank = ref(false)
const running = ref(false)
const result = ref(null)

const title = computed(() => ({
  setDepartment: '批量改部门',
  addRoles: '批量追加角色',
  setStatus: '批量启用 / 禁用'
}[props.action] || '批量操作'))

const targetLabel = computed(() => ({
  setDepartment: '目标部门',
  addRoles: '要追加的角色',
  setStatus: '目标状态'
}[props.action] || '目标'))

const fromLabel = computed(() => ({
  setDepartment: '原部门',
  addRoles: '现有角色',
  setStatus: '当前状态'
}[props.action] || '原'))

const toLabel = computed(() => ({
  setDepartment: '新部门',
  addRoles: '追加后',
  setStatus: '新状态'
}[props.action] || '新'))

const deptOptions = computed(() => props.departments.map(d => ({ label: d.name, value: d.id })))
const roleOptions = computed(() => props.roles.map(r => ({
  label: r.name || r.code,
  value: r.code,
  // IdP 授予的角色在成员页不可改：这里直接置灰，避免批量里执行了却无效
  disabled: idpRoleCodes.value.includes(r.code)
})))

/** 选中的成员里，由 IdP 组映射授予的角色编码（这些角色批量追加不动） */
const idpRoleCodes = computed(() => {
  const out = new Set()
  for (const m of props.members) {
    const src = m.roleSources || {}
    for (const code of Object.keys(src)) {
      if (src[code] === 'idp') out.add(code)
    }
  }
  return Array.from(out)
})
const disabledRoleCodes = computed(() => idpRoleCodes.value)

const deptName = id => {
  if (!id) return '未分配'
  const d = props.departments.find(x => x.id === id)
  return d ? d.name : id
}
const roleLabel = code => {
  const r = props.roles.find(x => x.code === code)
  return (r && r.name) || code
}

/** 变更摘要：逐条列出「从什么变成什么」 */
const diffRows = computed(() => props.members.map(m => {
  const name = m.username || m.uid
  if (props.action === 'setDepartment') {
    const cur = m.departmentId ? deptName(m.departmentId) : '未分配'
    const next = onlyIfBlank.value && cur !== '未分配' ? cur : deptName(departmentId.value)
    return { uid: m.uid, name, from: cur, to: next }
  }
  if (props.action === 'addRoles') {
    const cur = (m.roles && m.roles.length) ? m.roles.map(roleLabel).join('、') : '无'
    const add = (roleCodes.value || []).filter(c => !(m.roles || []).includes(c)).map(roleLabel)
    return { uid: m.uid, name, from: cur, to: add.length ? cur + ' + ' + add.join('、') : cur + '（无新增）' }
  }
  const cur = m.status === 0 ? '已禁用' : '启用'
  return { uid: m.uid, name, from: cur, to: status.value === 0 ? '已禁用' : '启用' }
}))

/** IdP 冲突预警：选中成员里持有 IdP 角色的那些 */
const idpHits = computed(() => props.members.filter(m => {
  const src = m.roleSources || {}
  return Object.values(src).some(v => v === 'idp')
}))

const canSubmit = computed(() => {
  if (!props.members.length) return false
  if (props.action === 'setDepartment') return departmentId.value !== undefined
  if (props.action === 'addRoles') return (roleCodes.value || []).length > 0
  return status.value === 0 || status.value === 1
})

const resultRows = computed(() => {
  if (!result.value) return []
  const failed = new Map((result.value.failed || []).map(f => [f.uid, f.reason]))
  const ok = new Set(result.value.succeeded || [])
  return props.members.map(m => ({
    uid: m.uid,
    name: m.username || m.uid,
    ok: ok.has(m.uid),
    reason: failed.get(m.uid) || '失败'
  }))
})

async function run () {
  running.value = true
  try {
    const body = { action: props.action, uids: props.members.map(m => m.uid) }
    if (props.action === 'setDepartment') {
      body.departmentId = departmentId.value || ''
      body.onlyIfBlank = !!onlyIfBlank.value
    } else if (props.action === 'addRoles') {
      body.roles = roleCodes.value || []
    } else {
      body.status = status.value
    }
    const r = await batchUsers(body)
    if (r && r.success === false) { message.error(r.msg || '批量操作失败'); return }
    result.value = (r && r.data) || { succeeded: [], failed: [] }
    emit('done')
  } catch (e) {
    message.error(e.message || '批量操作失败')
  } finally {
    running.value = false
  }
}

function close () {
  visible.value = false
}
function again () {
  result.value = null
  roleCodes.value = []
}

watch(() => props.open, open => {
  if (!open) return
  result.value = null
  departmentId.value = undefined
  roleCodes.value = []
  status.value = 1
  onlyIfBlank.value = false
})
</script>

<style scoped>
.bm-field { margin-bottom: 14px; }
.bm-field label { display: block; font-size: 11px; color: var(--app-text2); margin-bottom: 5px; }
.bm-inline { display: flex; align-items: center; gap: 12px; }
.bm-sec { font-size: 11px; font-weight: 600; color: var(--app-text3); letter-spacing: .04em; margin: 14px 0 6px; }
.bm-diff { border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.bm-diff-row { display: grid; grid-template-columns: 150px 1fr 20px 1fr; align-items: center; gap: 10px;
  padding: 8px 11px; border-bottom: 1px solid var(--app-border); font-size: 12px; }
.bm-diff-row:last-child { border-bottom: none; }
.bm-diff-head { background: var(--app-panel-2); color: var(--app-text3); font-size: 10.5px; }
.bm-who { color: var(--app-text); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bm-who code { font-size: 10px; color: var(--app-text3); font-weight: 400; margin-left: 4px; }
.bm-from { color: var(--app-text3); text-decoration: line-through; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bm-arw { color: var(--app-text3); text-align: center; }
.bm-to { color: var(--app-ok); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bm-empty { padding: 20px; text-align: center; font-size: 12px; color: var(--app-text3); }
.bm-warn { display: flex; align-items: flex-start; gap: 8px; margin-top: 12px; border: 1px solid var(--app-warn-border);
  background: var(--app-warn-weak); border-radius: 8px; padding: 8px 10px; font-size: 11.5px; color: var(--app-warn-text); line-height: 1.7; }
.bm-warn > span { width: 15px; height: 15px; border-radius: 50%; background: var(--app-warn); color: #fff;
  font-size: 10px; display: inline-flex; align-items: center; justify-content: center; flex: none; margin-top: 2px; }
.bm-foot { display: flex; align-items: center; gap: 8px; margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--app-border); }
.bm-result { border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 11px; }
.bm-res { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 12px;
  border-bottom: 1px dashed var(--app-border); }
.bm-res:last-child { border-bottom: none; }
.bm-res-ic { width: 15px; height: 15px; border-radius: 50%; color: #fff; font-size: 10px;
  display: inline-flex; align-items: center; justify-content: center; flex: none; }
.bm-res.ok .bm-res-ic { background: var(--app-ok); }
.bm-res.err .bm-res-ic { background: var(--app-danger); }
.bm-res-txt { font-size: 11.5px; }
.bm-res.ok .bm-res-txt { color: var(--app-ok); }
.bm-res.err .bm-res-txt { color: var(--app-danger-text); }
</style>
