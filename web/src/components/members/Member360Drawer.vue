<template>
  <a-drawer v-model:open="visible" :width="760" :closable="false" class="m360">
    <template #title>
      <div class="m360-head">
        <UserAvatar :avatar="member.avatar" :name="member.username || member.uid" :size="38" />
        <div class="m360-title">
          <div class="m360-name">
            {{ member.username || '（未命名）' }}
            <code class="m360-uid">{{ member.uid }}</code>
            <span class="app-pill" :class="member.status === 0 ? 'muted' : 'ok'">
              {{ member.status === 0 ? '已禁用' : '启用' }}
            </span>
          </div>
          <div class="m360-sub">
            <span>{{ deptName }}</span><span>·</span>
            <span>{{ tenantName }}</span><span>·</span>
            <span>{{ lastLoginText }}</span>
          </div>
        </div>
        <span style="flex:1"></span>
        <a-button type="text" size="small" @click="visible = false">关闭</a-button>
      </div>
    </template>

    <a-tabs v-model:active-key="tab" class="m360-tabs">
      <a-tab-pane key="identity" tab="身份与归属" />
      <a-tab-pane key="roles" tab="角色与授权" />
      <a-tab-pane key="auth" tab="认证与登录" />
      <a-tab-pane key="audit" tab="操作记录" />
    </a-tabs>

    <!-- 内容只在打开时渲染：关闭态渲染会带着空 member 跑一遍模板，白白报错 -->
    <div v-if="visible" class="m360-body">
      <!-- 身份与归属 -->
      <div v-show="tab === 'identity'" class="m360-grid">
        <section class="app-card m360-panel">
          <div class="app-card-title">身份与归属</div>
          <div class="m360-kv"><span>登录账号</span><b>{{ member.uid || '—' }}</b></div>
          <div class="m360-kv"><span>昵称</span><b>{{ member.username || '—' }}</b></div>
          <div class="m360-kv">
            <span>部门</span>
            <b>
              <button v-if="member.departmentId" class="app-link-btn" @click="$emit('goto-department', member.departmentId)">
                {{ deptName }} ›
              </button>
              <span v-else class="muted">未分配</span>
            </b>
          </div>
          <div class="m360-kv"><span>所属租户</span><b>{{ tenantName }}</b></div>
          <div class="m360-kv"><span>状态</span><b>{{ member.status === 0 ? '已禁用' : '启用' }}</b></div>
          <div class="m360-kv"><span>通知邮箱</span><b>{{ member.email || '—' }}</b></div>
        </section>
        <section class="app-card m360-panel">
          <div class="app-card-title">运行状态</div>
          <div class="m360-kv"><span>连续登录失败</span><b>{{ member.loginFailCount || 0 }} 次</b></div>
          <div class="m360-kv">
            <span>锁定至</span>
            <b :class="member.lockedUntil ? 'warn' : ''">{{ member.lockedUntil || '—' }}</b>
          </div>
          <div class="m360-kv"><span>会话代数</span><b>{{ member.tokenEpoch || 0 }}</b></div>
          <div class="m360-kv"><span>最近登录</span><b>{{ lastLoginText }}</b></div>
          <div class="m360-kv"><span>建档时间</span><b>{{ fmt(member.createTime) }}</b></div>
          <div class="m360-kv"><span>最近更新</span><b>{{ fmt(member.updateTime) }}</b></div>
        </section>
      </div>

      <!-- 角色与授权 -->
      <div v-show="tab === 'roles'" class="m360-panel-wrap">
        <section class="app-card m360-panel">
          <div class="app-card-title">
            角色与授权
            <span class="m360-tip">来源必须看见 · IdP 授予在成员页只读</span>
          </div>
          <a-spin :spinning="rolesLoading">
            <div v-if="rolesError" class="m360-err">
              角色信息读取失败：{{ rolesError }} · <button class="app-link-btn" @click="loadRoles">重试</button>
            </div>
            <div v-else-if="!roleRows.length" class="m360-empty">该成员没有角色行</div>
            <div v-else>
              <div v-for="r in roleRows" :key="r.code" class="m360-role">
                <span class="role-pill" :class="'r-' + r.code">{{ roleLabel(r.code) }}</span>
                <span class="m360-src" :class="{ lock: r.source === 'idp' }">
                  {{ r.source === 'idp' ? '组映射授予 · 不可改' : '手动授予' }}
                </span>
                <span style="flex:1"></span>
                <span class="m360-note">{{ r.note }}</span>
              </div>
            </div>
          </a-spin>
        </section>

        <section v-if="tempRoles.length" class="app-card m360-panel">
          <div class="app-card-title">
            <span class="m360-warn-ic">!</span>
            临时授权 {{ tempRoles.length }} 项 · 到期自动回收
            <span style="flex:1"></span>
            <button class="app-link-btn" @click="$emit('adjust-expiry', member)">调整到期</button>
          </div>
          <div v-for="r in tempRoles" :key="r.code" class="m360-role">
            <span class="role-pill" :class="'r-' + r.code">{{ roleLabel(r.code) }}</span>
            <span style="flex:1"></span>
            <span class="m360-note">{{ r.expireText }}</span>
          </div>
          <div class="form-tip">
            保存成员时原样带回，不会被写成永久——临时授权是唯一「会自己消失」的东西，写路径必须保住它的到期时刻。
          </div>
        </section>

        <section class="app-card m360-panel">
          <div class="app-card-title">有效权限</div>
          <a-spin :spinning="permLoading">
            <div v-if="permError" class="m360-err">
              有效权限读取失败：{{ permError }} · <button class="app-link-btn" @click="loadPerms">重试</button>
            </div>
            <div v-else-if="!permCodes.length" class="m360-empty">该成员没有可用授权点</div>
            <div v-else class="m360-perms">
              <span v-for="p in permCodes" :key="p" class="m360-perm" :title="p">
                {{ permNames[p] || p }}
              </span>
            </div>
          </a-spin>
        </section>
      </div>

      <!-- 认证与登录 -->
      <div v-show="tab === 'auth'" class="m360-panel-wrap">
        <section class="app-card m360-panel">
          <div class="app-card-title">
            认证与登录
            <span class="m360-tip">身份源列表 · 逐源可解绑</span>
          </div>
          <div v-if="!identities.length" class="m360-empty">该成员只有本地账号</div>
          <div v-for="(it, i) in identities" :key="i" class="m360-idp">
            <span class="app-pill plain">{{ providerName(it.providerId) }}</span>
            <code class="m360-ext">{{ it.externalId }}</code>
            <span style="flex:1"></span>
            <button class="app-link-btn danger" @click="unbind(it)">解绑</button>
          </div>
          <div class="m360-idp">
            <span class="app-pill plain">本地账号</span>
            <span class="m360-note">
              {{ localAccountText }}
              · 连续失败 {{ member.loginFailCount || 0 }} 次
              · 改密后使全部端会话失效
            </span>
            <span style="flex:1"></span>
            <button class="app-link-btn" @click="$emit('reset-password', member)">重置密码</button>
          </div>
        </section>
      </div>

      <!-- 操作记录 -->
      <div v-show="tab === 'audit'" class="m360-panel-wrap">
        <section class="app-card m360-panel">
          <div class="app-card-title">
            最近操作记录
            <span class="m360-tip">谁在什么时候改过他</span>
            <span style="flex:1"></span>
            <button class="app-link-btn" @click="$emit('open-audit', member.uid)">在审计页打开</button>
          </div>
          <a-spin :spinning="auditLoading">
            <div v-if="auditError" class="m360-err">
              操作记录读取失败：{{ auditError }} · <button class="app-link-btn" @click="loadAudit">重试</button>
            </div>
            <div v-else-if="!auditRows.length" class="m360-empty">没有查到该成员的历史变更</div>
            <div v-else>
              <div v-for="(a, i) in auditRows" :key="i" class="m360-audit">
                <span class="m360-audit-tm">{{ fmt(a.ts) }}</span>
                <span class="app-pill plain">{{ a.action }}</span>
                <span class="m360-audit-d">{{ a.detail }}</span>
                <span class="m360-audit-who">{{ a.actor }}</span>
              </div>
            </div>
          </a-spin>
        </section>
      </div>
    </div>

    <template #footer>
      <div class="m360-foot">
        <button class="app-btn" @click="$emit('edit', member)">编辑</button>
        <button class="app-btn ghost" @click="$emit('reset-password', member)">重置密码</button>
        <button class="app-btn ghost" @click="$emit('toggle-status', member)">
          {{ member.status === 0 ? '启用' : '禁用' }}
        </button>
        <span style="flex:1"></span>
        <button class="app-btn ghost" @click="$emit('more', member)">更多</button>
        <button class="app-btn ghost" @click="$emit('remove', member)">删除</button>
      </div>
    </template>
  </a-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getEffectivePermissions, getPermissionCatalog, listAuditEvents, unbindIdentity } from '../../api'
import UserAvatar from '../UserAvatar.vue'

/**
 * 成员 360 抽屉：一个成员的四段聚合面（身份与归属 / 角色与授权 / 认证与登录 / 操作记录）。
 * <p>只读聚合：写操作仍走成员页既有的编辑弹窗与接口，这里只发事件，不自己实现表单。
 * <p>四段各自懒加载且各自报错：某一段读不出来不影响其它段（不能整体阻塞）。
 */
const props = defineProps({
  open: { type: Boolean, default: false },
  /** 成员行（与 /user/page 的 items 口径一致） */
  member: { type: Object, default: () => ({}) },
  /** 部门清单（显示部门名） */
  departments: { type: Array, default: () => [] },
  /** 租户清单（显示租户名） */
  tenants: { type: Array, default: () => [] },
  /** 角色清单（显示角色名与管理员级） */
  roles: { type: Array, default: () => [] },
  /** 认证源清单（providerId → 可读名） */
  providers: { type: Array, default: () => [] },
  /** 临时授权到期清单（uid + 角色 + 到期时刻） */
  expiring: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:open', 'edit', 'reset-password', 'toggle-status', 'remove', 'more',
  'goto-department', 'adjust-expiry', 'open-audit'])

const visible = computed({
  get: () => props.open,
  set: v => emit('update:open', v)
})

const tab = ref('identity')

const rolesLoading = ref(false)
const rolesError = ref('')
const permLoading = ref(false)
const permError = ref('')
const auditLoading = ref(false)
const auditError = ref('')
const permCodes = ref([])
const permNames = ref({})
const auditRows = ref([])

/**
 * 授权点 code → 中文名（授权点目录是事实源：扫描器从 @Permission 注解登记）。
 * 拉不到就退回 code 本身——裸 code 至少还能查，编个假中文名更坏。
 * <p>管理员级通配标记（{@code (admin:all-allow)}）不在授权点目录里（它不是一个授权点），
 * 单独给它一句人话，否则界面上会出现一个谁也看不懂的括号串。
 */
const ADMIN_ALL_ALLOW = '(admin:all-allow)'
async function loadPermNames () {
  if (Object.keys(permNames.value).length) return
  try {
    const r = await getPermissionCatalog()
    const out = { [ADMIN_ALL_ALLOW]: '全部管理功能（管理员级）' }
    for (const p of ((r && r.data) || [])) {
      if (p && p.code) out[p.code] = p.name || p.code
    }
    permNames.value = out
  } catch (e) {
    permNames.value = { [ADMIN_ALL_ALLOW]: '全部管理功能（管理员级）' }
  }
}

const deptName = computed(() => {
  const d = props.departments.find(x => x.id === props.member.departmentId)
  return d ? d.name : '未分配'
})
const tenantName = computed(() => {
  const t = props.tenants.find(x => x.id === (props.member.tenantId || 'default'))
  return t ? t.name : (props.member.tenantId || '默认租户')
})
const lastLoginText = computed(() => fmt(props.member.lastLoginTime) || '从未登录')

const roleLabel = code => {
  const r = props.roles.find(x => x.code === code)
  return (r && r.name) || ({ superadmin: '超级管理员', admin: '管理员', user: '成员' }[code] || code)
}
const providerName = id => {
  const p = props.providers.find(x => x.id === id)
  return (p && p.name) || id
}

/** 身份源列表（外部身份绑定；空 = 只有本地账号） */
const identities = computed(() => (props.member && props.member.identities) || [])

/**
 * 本地账号状态说明。
 * <p>后端不下发 password_hash（它是 @JsonIgnore 的哈希，发出去等于交一把离线爆破的靶子），
 * 所以这里按「有身份绑定 ⇒ 只走 IdP 登录」反推，不去猜密码是否存在。
 */
const localAccountText = computed(() => {
  const hasIdp = identities.value.length > 0
  return hasIdp ? '未设本地密码（只走认证源登录）' : '可用本地账号登录'
})

const fmt = s => {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(0, 16)
}

/** 角色行：来源 + 到期说明（来源来自后端 roleSources，与编辑弹窗的置灰口径同源） */
const roleRows = computed(() => {
  const codes = (props.member.roles && props.member.roles.length) ? props.member.roles : ['user']
  const sources = props.member.roleSources || {}
  const expires = props.member.roleExpires || {}
  return codes.map(code => {
    const src = sources[code]
    const exp = expires[code]
    let note = ''
    if (exp) {
      const days = Math.ceil((new Date(String(exp).replace(' ', 'T')).getTime() - Date.now()) / 86400000)
      note = `${fmt(exp)} 到期（剩余 ${days} 天）`
    } else if (src === 'idp') {
      note = '来源：认证源组映射'
    } else {
      note = '永久'
    }
    return { code, source: src || 'manual', note }
  })
})

/** 临时授权：从临期清单里取该成员的行（只算带到期时刻的） */
const tempRoles = computed(() => {
  const uid = props.member.uid
  if (!uid) return []
  return (props.expiring || [])
    .filter(e => e.uid === uid && e.expireAt)
    .map(e => ({
      code: e.roleCode,
      expireText: `${fmt(e.expireAt)} 到期（剩余 ${Math.ceil((new Date(String(e.expireAt).replace(' ', 'T')).getTime() - Date.now()) / 86400000)} 天）`
    }))
})

async function loadRoles () {
  rolesLoading.value = true
  rolesError.value = ''
  try {
    // 角色集与来源已在行数据里（roleSources），这里只需把展示刷新一次
    await Promise.resolve()
  } catch (e) {
    rolesError.value = e.message || '读取失败'
  } finally {
    rolesLoading.value = false
  }
}

async function loadPerms () {
  const uid = props.member.uid
  if (!uid) { permCodes.value = []; return }
  permLoading.value = true
  permError.value = ''
  try {
    await loadPermNames()
    const r = await getEffectivePermissions(uid)
    permCodes.value = (r && r.data && r.data.permissions) || []
  } catch (e) {
    permError.value = e.message || '读取失败'
    permCodes.value = []
  } finally {
    permLoading.value = false
  }
}

async function loadAudit () {
  const uid = props.member.uid
  if (!uid) { auditRows.value = []; return }
  auditLoading.value = true
  auditError.value = ''
  try {
    // target=被操作对象（这个人）；不是 actor（谁做的）——问的是「谁改过他」
    const r = await listAuditEvents(`days=30&target=${encodeURIComponent(uid)}&page=1&size=10`)
    auditRows.value = ((r && r.data && r.data.items) || []).map(a => ({
      ts: a.ts,
      action: a.action,
      actor: a.actor_uid || '—',
      detail: auditDetailText(a)
    }))
  } catch (e) {
    auditError.value = e.message || '读取失败'
    auditRows.value = []
  } finally {
    auditLoading.value = false
  }
}

/**
 * 审计详情转人话。
 * <p>detail 落库时是键值 JSON（{"uid":"x","roles":["admin"]}），直接甩给界面等于让管理员读日志。
 * 这里按已知键翻成中文；认不出的键原样留着（别丢信息），但整体不展示原始 JSON 串。
 */
function auditDetailText (a) {
  const d = a.detail
  if (!d) return '—'
  let obj = null
  try {
    obj = typeof d === 'string' ? JSON.parse(d) : d
  } catch (e) {
    return String(d)
  }
  if (!obj || typeof obj !== 'object') return String(d)
  const parts = []
  if (obj.uid) parts.push(`对象 ${obj.uid}`)
  if (Array.isArray(obj.roles)) parts.push(`角色 ${obj.roles.join('、')}`)
  if (obj.expires) parts.push(`到期 ${obj.expires}`)
  if (obj.departmentId !== undefined) parts.push(`部门 ${obj.departmentId || '未分配'}`)
  if (obj.status !== undefined) parts.push(`状态 ${obj.status === 0 ? '禁用' : '启用'}`)
  if (obj.email) parts.push(`邮箱 ${obj.email}`)
  if (!parts.length) {
    return Object.keys(obj).map(k => `${k} ${obj[k]}`).join(' · ') || '—'
  }
  return parts.join(' · ')
}

async function unbind (it) {
  try {
    const r = await unbindIdentity(props.member.uid, it.providerId)
    if (r && r.success === false) { message.error(r.msg || '解绑失败'); return }
    message.success('已解绑')
    emit('update:open', true)
    // 解绑后要刷新身份列表：父组件重载成员行再传回来
    emit('reload')
  } catch (e) {
    message.error(e.message || '解绑失败')
  }
}

// 抽屉打开时按页签懒加载：首屏只要身份与角色，权限与审计等到点了再拉
watch(() => props.open, open => {
  if (!open) return
  tab.value = 'identity'
  loadRoles()
})
watch(tab, t => {
  if (t === 'roles' && !permCodes.value.length && !permError.value) loadPerms()
  if (t === 'audit' && !auditRows.value.length && !auditError.value) loadAudit()
})
watch(() => props.member.uid, () => {
  permCodes.value = []
  auditRows.value = []
  loadRoles()
})
</script>

<style scoped>
.m360-head { display: flex; align-items: center; gap: 11px; }
.m360-title { min-width: 0; }
.m360-name { display: flex; align-items: center; gap: 7px; font-size: 15px; font-weight: 600; }
.m360-uid { font-family: var(--app-mono, ui-monospace, monospace); font-size: 11px; font-weight: 400; color: var(--app-text3); }
.m360-sub { display: flex; align-items: center; gap: 7px; margin-top: 3px; font-size: 11px; color: var(--app-text3); }
.m360-body { padding-top: 4px; }
.m360-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.m360-panel-wrap { display: flex; flex-direction: column; gap: 10px; }
.m360-panel { padding: 12px 14px; }
.m360-panel .app-card-title { display: flex; align-items: center; gap: 7px; margin-bottom: 8px; }
.m360-tip { font-size: 10.5px; font-weight: 400; color: var(--app-text3); }
.m360-warn-ic { width: 15px; height: 15px; border-radius: 50%; background: var(--app-warn-weak); color: var(--app-warn-text);
  font-size: 10px; display: inline-flex; align-items: center; justify-content: center; }
.m360-kv { display: flex; align-items: center; font-size: 12px; padding: 5px 0; border-bottom: 1px dashed var(--app-border); }
.m360-kv:last-child { border-bottom: none; }
.m360-kv span { color: var(--app-text3); }
.m360-kv b { margin-left: auto; font-weight: 500; }
.m360-kv b.warn { color: var(--app-warn-text); }
.muted { color: var(--app-text3); font-weight: 400; }
.m360-role { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px dashed var(--app-border); font-size: 12px; }
.m360-role:last-child { border-bottom: none; }
.m360-src { font-size: 10.5px; color: var(--app-text3); background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 3px; padding: 1px 5px; }
.m360-src.lock { color: #7c5cd6; background: #f0ecfb; border-color: transparent; }
.m360-note { font-size: 11px; color: var(--app-text3); }
.m360-idp { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px dashed var(--app-border); font-size: 12px; }
.m360-idp:last-child { border-bottom: none; }
.m360-ext { font-family: var(--app-mono, ui-monospace, monospace); font-size: 11px; color: var(--app-text3); }
.m360-audit { display: flex; align-items: center; gap: 9px; padding: 6px 0; border-bottom: 1px dashed var(--app-border); font-size: 11.5px; }
.m360-audit:last-child { border-bottom: none; }
.m360-audit-tm { width: 92px; flex: none; color: var(--app-text3); font-size: 10.5px; }
.m360-audit-d { flex: 1; min-width: 0; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m360-audit-who { flex: none; color: var(--app-text3); font-size: 10.5px; }
.m360-perms { display: flex; flex-wrap: wrap; gap: 5px; }
.m360-perm { font-size: 10.5px; color: var(--app-text2); background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 4px; padding: 2px 6px; font-family: var(--app-mono, ui-monospace, monospace); }
.m360-err { padding: 14px 0; text-align: center; font-size: 12px; color: var(--app-danger); }
.m360-empty { padding: 22px 0; text-align: center; font-size: 12px; color: var(--app-text3); }
.m360-foot { display: flex; align-items: center; gap: 8px; }
.role-pill { display: inline-flex; align-items: center; font-size: 11px; line-height: 1; padding: 4px 8px; border-radius: 999px; }
.role-pill.r-superadmin { color: var(--app-accent); background: var(--app-accent-weak); }
.role-pill.r-admin { color: var(--app-ok); background: var(--app-ok-weak); }
.role-pill.r-user { color: var(--app-text2); background: var(--app-panel-2); }
</style>
