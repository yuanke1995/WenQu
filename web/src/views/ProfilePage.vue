<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">个人设置</h1>
    </div>
    <div class="pf-body">
      <nav class="pf-nav">
        <button v-for="n in navs" :key="n.key" class="pf-nav-item"
                :class="{ active: current === n.key }" @click="current = n.key">
          {{ n.label }}
        </button>
      </nav>
      <section class="pf-content">
        <!-- 个人资料（自助：仅昵称，登录账号不可改） -->
        <div v-if="current === 'profile'" class="app-card pf-card">
          <h2 class="app-card-title">个人资料</h2>
          <p class="pf-hint pf-hint-top">
            昵称与头像显示在侧边栏和成员列表中，仅作展示、不用于登录；登录账号不可修改。
          </p>

          <!-- 头像：上传图片（≤2MB）或选一个 emoji；留空=回落昵称首字。改动后侧栏/成员列表立即同步 -->
          <div class="pf-avatar-block">
            <div class="pf-avatar-frame">
              <UserAvatar :avatar="avatarPreview" :name="nickForm.username" :size="72" />
            </div>
            <div class="pf-avatar-side">
              <div class="pf-op-row">
                <label class="app-btn pf-upload" :class="{ busy: avatarSaving }">
                  {{ avatarSaving ? '处理中…' : '上传图片' }}
                  <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden
                         :disabled="avatarSaving" @change="onAvatarFile" />
                </label>
                <button class="app-btn ghost" :disabled="!avatarPreview || avatarSaving" @click="clearAvatar">清除头像</button>
              </div>
              <p class="pf-sub-hint">支持 PNG / JPEG / GIF / WebP，单张不超过 2MB，上传后立即生效。</p>
            </div>
          </div>

          <!-- 图标与上传互斥（后设覆盖前设）；两者都为空时显示昵称首字 -->
          <div class="pf-field">
            <div class="pf-field-head">
              <span class="pf-label">或选择一个图标</span>
              <span class="pf-sub-hint">点选即生效；留空则显示昵称首字</span>
            </div>
            <div class="pf-emoji-pick">
              <button v-for="e in AVATAR_EMOJIS" :key="e" class="pf-emoji-opt" type="button"
                      :class="{ on: avatarPreview === e }" :disabled="avatarSaving"
                      :aria-label="'使用图标 ' + e" @click="pickEmoji(e)">{{ e }}</button>
            </div>
          </div>

          <div class="pf-field">
            <div class="pf-field-head"><span class="pf-label">昵称</span></div>
            <div class="pf-row">
              <a-input v-model:value="nickForm.username" :maxlength="100" allow-clear style="width:280px"
                       placeholder="显示昵称，如 张三" @pressEnter="saveNickname" />
              <button class="app-btn" :disabled="nickSaving || !nickForm.username.trim()" @click="saveNickname">保存</button>
            </div>
            <p class="pf-sub-hint">改完侧边栏立即生效；管理员仍可在「成员管理」中调整。</p>
          </div>
        </div>

        <!-- 个人默认模型面板（承载「回答偏好」个人覆盖项，保存按钮统一提交） -->
        <div v-else-if="current === 'chat'" class="app-card pf-card">
          <h2 class="app-card-title">{{ panelMeta.title }}</h2>
          <p class="pf-hint">{{ panelMeta.hint }}</p>
          <div class="pf-row">
            <!-- 用组件声明的 v-model（而非 v-model:value——那是透传到根 a-select 的偶然生效路径） -->
            <ModelSelect v-model="pref[panelMeta.field]" type="chat"
                         inherit-label="不设默认" :width="360" :disabled="loading" />
          </div>
          <p class="pf-sub-hint">{{ panelMeta.tail }}</p>

          <!-- 回答偏好（仅聊天面板）：个人覆盖系统设置的体验参数，仅对本人生效 -->
          <template v-if="current === 'chat'">
            <a-divider style="margin:16px 0 12px" />
            <p class="pf-hint">
              回答偏好默认取「系统设置」的全局值；修改后仅对你<strong>自己</strong>的问答生效（跨设备同步），
              只有被修改过的项才记为你的个人设置，其余继续跟随全局。
            </p>
            <a-spin :spinning="prefLoading">
              <a-form v-if="answerFields.length" layout="vertical" style="max-width:560px">
                <SchemaField v-for="f in answerFields" :key="f.path" :field="f" :form="prefForm" :tips="prefTips" />
              </a-form>
              <p v-else-if="!prefLoading" class="pf-sub-hint">暂无可个人覆盖的配置项。</p>
            </a-spin>

            <p v-if="prefPersonalKeys.length" class="pf-sub-hint">
              当前已设个人值：{{ prefPersonalKeys.join('、') }}
              <button class="app-link-btn" :disabled="saving" @click="clearPrefs">全部清除个人设置</button>
            </p>
          </template>

          <div class="pf-row" style="margin-top:14px">
            <button class="app-btn" :disabled="saving" @click="save">保存</button>
            <button v-if="current === 'chat' && prefDirty" class="app-link-btn" :disabled="saving" @click="loadPrefs">还原修改</button>
          </div>
        </div>

        <!-- 模型能力均无个人层槽位：向量空间与知识库索引一一对应、归知识库绑定；
             重排（开关+模型）同归知识库/智能体检索设置绑定；
             问答对生成与 GraphRAG 抽取均已回落库主默认聊天模型（上方「个人默认聊天模型」） -->

        <!-- 账号安全 -->
        <div v-else-if="current === 'security'" class="app-card pf-card">
          <h2 class="app-card-title">修改密码</h2>
          <a-form layout="vertical" style="max-width:360px">
            <a-form-item label="当前密码" required>
              <a-input-password v-model:value="pwdForm.oldPassword" placeholder="请输入当前密码" @pressEnter="submitPwd" />
            </a-form-item>
            <a-form-item label="新密码" required>
              <a-input-password v-model:value="pwdForm.newPassword" placeholder="至少 6 位" />
            </a-form-item>
            <a-form-item label="确认新密码" required>
              <a-input-password v-model:value="pwdForm.confirm" placeholder="再次输入新密码" @pressEnter="submitPwd" />
            </a-form-item>
            <p v-if="pwdError" class="pf-err">{{ pwdError }}</p>
            <button class="app-btn" :disabled="pwdSaving" @click="submitPwd">修改密码</button>
            <span class="pf-sub-hint" style="margin-left:10px">修改成功后需重新登录</span>
          </a-form>
        </div>

        <!-- 长期记忆（跨会话个性化） -->
        <div v-else-if="current === 'memory'" class="app-card pf-card mem-card">
          <h2 class="app-card-title">
            我的长期记忆
            <span v-if="memories.length" class="mem-count">{{ memories.length }} 条</span>
          </h2>
          <p class="pf-hint">
            开启时，系统会在每轮问答后自动提炼值得长期记住的信息（偏好、项目背景、明确要求记住的事），
            并在你之后的对话中自动带上。这里可以查看、修改、删除——删掉的就永远不会再被提起。
          </p>
          <!-- 用户级开关：仅控制「自动生成」，已存记忆的注入不受影响（逐条删除即可） -->
          <div class="pf-row mem-auto-row">
            <div class="mem-auto-text">
              <div class="mem-auto-title">自动提炼记忆</div>
              <div class="pf-sub-hint">关闭后不再从你的对话中自动生成新记忆；已存的记忆仍会注入，可逐条删除。</div>
            </div>
            <a-switch :checked="memAutoEnabled" :loading="memAutoSaving" @change="toggleMemAuto" />
          </div>
          <div class="mem-add-row">
            <a-input v-model:value="memDraft" :maxlength="500" allow-clear class="mem-add-input"
                     placeholder="手动添加一条记忆，如：我负责 XX 系统的运维" @pressEnter="addMemory" />
            <a-select v-model:value="memDraftCat" class="mem-add-cat" :options="CAT_OPTIONS" />
            <button class="app-btn" :disabled="memSaving || !memDraft.trim()" @click="addMemory">添加</button>
          </div>
          <a-spin :spinning="memLoading">
            <div v-if="!memories.length" class="mem-empty">
              <div class="mem-empty-ico">💡</div>
              <p class="mem-empty-title">还没有记忆</p>
              <p class="pf-sub-hint">多聊几轮让系统自动提炼，或在上方手动添加一条。</p>
            </div>
            <div v-else class="mem-list">
              <div v-for="m in memories" :key="m.id" class="mem-item">
                <!-- 编辑态：文本域独占卡片，计数与按钮同排（antd show-count 渲染在文本域下方，会与按钮行重叠） -->
                <template v-if="memEditing === m.id">
                  <a-textarea v-model:value="memEditDraft" :maxlength="500"
                              :auto-size="{ minRows: 2, maxRows: 8 }" />
                  <div class="mem-edit-foot">
                    <span class="mem-edit-count">{{ memEditDraft.length }} / 500</span>
                    <button class="app-btn ghost" :disabled="memSaving" @click="memEditing = ''">取消</button>
                    <button class="app-btn" :disabled="memSaving || !memEditDraft.trim()" @click="saveMemEdit(m)">保存</button>
                  </div>
                </template>
                <template v-else>
                  <div class="mem-content">{{ m.content }}</div>
                  <div class="mem-foot">
                    <span class="mem-tag" :class="'mem-cat-' + (m.category || 'fact')">{{ categoryLabel(m.category) }}</span>
                    <span v-if="m.source === 'auto'" class="mem-tag mem-src"
                          :title="'来自会话 ' + (m.sourceSessionId || '')">自动</span>
                    <span class="mem-meta">用过 {{ m.hitCount || 0 }} 次</span>
                    <span class="mem-acts">
                      <button class="app-link-btn" @click="startMemEdit(m)">编辑</button>
                      <button class="app-link-btn danger" @click="confirmRemoveMemory(m)">删除</button>
                    </span>
                  </div>
                </template>
              </div>
            </div>
          </a-spin>
          <p class="pf-sub-hint">
            记忆只属于你自己，只注入你本人的对话（公开分享页不会携带）；已达上限时自动提取会暂停，删掉几条即可恢复。
          </p>
        </div>

        <!-- 分享管理（会话只读分享）：链接发出后就散在各处，这里是统一的回看与停用入口。
             会话内分享面板管"这一条"，这里管"全部"——单条面板无法回答"我一共发出过哪些"。 -->
        <div v-else-if="current === 'shares'" class="app-card pf-card shm-card">
          <h2 class="app-card-title">
            分享管理
            <span v-if="shareOnCount" class="mem-count">{{ shareOnCount }} 条生效中</span>
          </h2>
          <p class="pf-hint">
            会话分享生成的是<strong>只读链接</strong>：拿到链接的人可以查看这段对话，不能继续提问。
            链接展示的是会话<strong>最新内容而非快照</strong>——分享后继续在这段对话里聊到敏感内容，链接页也会跟着变。
            停止分享后链接立即失效，记录保留到清理周期结束（与会话保留期一致），也可以随时手动清除。
          </p>
          <a-spin :spinning="shareLoading">
            <div v-if="!shares.length" class="mem-empty">
              <div class="mem-empty-ico">🔗</div>
              <p class="mem-empty-title">还没有分享过任何会话</p>
              <p class="pf-sub-hint">在对话页顶栏点「分享这段对话」即可生成只读链接。</p>
            </div>
            <div v-else class="shm-list">
              <div v-for="s in shares" :key="s.sessionId" class="shm-item"
                   :class="{ off: !s.enabled, focus: focusSid === s.sessionId }">
                <div class="shm-main">
                  <div class="shm-title">
                    {{ s.title }}
                    <span v-if="s.orphaned" class="shm-tag warn">会话已删除</span>
                    <span v-else-if="s.enabled" class="shm-tag on">生效中</span>
                    <span v-else class="shm-tag">已停止</span>
                  </div>
                  <div class="shm-meta">
                    分享于 {{ fmtTime(s.createTime) }}
                    <template v-if="s.visitCount">
                      · 已被访问 {{ s.visitCount }} 次<template v-if="s.lastVisitAt">，最近 {{ fmtTime(s.lastVisitAt) }}</template>
                    </template>
                    <template v-else> · 尚未被访问</template>
                  </div>
                  <!-- 已停止的链接不再展示地址栏：给一个打不开的链接不如明确说"已停止" -->
                  <div v-if="s.enabled" class="shm-link">
                    <span class="shm-url">{{ shareUrlOf(s.token) }}</span>
                  </div>
                  <div v-if="s.orphaned" class="shm-note">这条链接已打不开（会话已删除）。停用后超保留期自动清理，也可在此立即清除。</div>
                </div>
                <div class="shm-acts">
                  <template v-if="s.enabled">
                    <button class="app-link-btn" @click="copyShare(s)">复制链接</button>
                    <a class="app-link-btn" :href="shareUrlOf(s.token)" target="_blank" rel="noopener">预览</a>
                    <button class="app-link-btn" @click="regenShare(s)">换新链接</button>
                    <button class="app-link-btn" @click="goSharedSession(s)">查看会话</button>
                    <button class="app-link-btn danger" @click="stopShare(s)">停止分享</button>
                  </template>
                  <template v-else>
                    <span class="shm-off-note">已停止</span>
                    <button v-if="!s.orphaned" class="app-link-btn" @click="restartShare(s)">重新开启</button>
                    <!-- 清除记录：已停用才给。悬空行没有信息价值（标题已退化、内容不可访问），
                         是堆积面板的主要来源，需要一个立即清掉的出口而不必等 30 天保留期 -->
                    <button class="app-link-btn danger" @click="purgeRecord(s)">清除记录</button>
                  </template>
                </div>
              </div>
            </div>
          </a-spin>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { clearAuth, ensureAuth } from '../utils/auth'
import { refreshSetupGuide } from '../utils/setupGuide'
import { copyText } from '../utils/clipboard'
import { shareUrlOf } from './shareSession'
import { changePasswordApi, getUserPreference, setUserPreference, updateMyProfile, uploadAvatarApi,
         getUserSettings, saveUserSettings,
         listMyMemories, addMyMemory, updateMyMemory, deleteMyMemory,
         listMySessionShares, stopShareBySession, purgeShareRecord, enableSessionShare } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import SchemaField from '../components/SchemaField.vue'
import UserAvatar from '../components/UserAvatar.vue'

const router = useRouter()
const route = useRoute()

const navs = [
  { key: 'profile', label: '个人资料' },
  { key: 'chat', label: '聊天模型与偏好' },
  { key: 'memory', label: '长期记忆' },
  { key: 'shares', label: '分享管理' },
  { key: 'security', label: '账号安全' }
]
// ?panel= 深链（配置引导建议项「去配置」直达对应面板）；未知值回落个人资料
const PANEL_KEYS = navs.map(n => n.key)
const current = ref(PANEL_KEYS.includes(route.query.panel) ? route.query.panel : 'profile')
watch(() => route.query.panel, v => { if (PANEL_KEYS.includes(v)) current.value = v })

const PANELS = {
  chat: {
    field: 'defaultModel', title: '聊天模型与偏好',
    hint: '个人默认聊天模型：智能体未指定、会话未手动选择时使用。知识库的问答对生成、GraphRAG 抽取跟随「库主」的个人默认聊天模型——自己的库烧你这份默认，别人的库烧库主的（GraphRAG 抽取优先用库级绑定的抽取模型，未绑定才回落库主默认）。下方「回答偏好」可按需覆盖系统全局值。',
    tail: '清空（选「不设默认」）后每次对话需手动选择模型。对话中上传的图片能否被理解取决于所选模型：支持读图的模型原图直发；不支持的本轮图片仅作展示、不参与回答（可换支持读图的模型重试）。文档入库的图片描述使用知识库绑定的视觉模型。'
  }
}
const panelMeta = computed(() => PANELS[current.value] || PANELS.chat)

// 个人默认（保存提交当前值；向量/重排/视觉无个人默认，归知识库绑定/检索设置/模型能力位）
const loading = ref(false)
const saving = ref(false)
const pref = ref({ defaultModel: '' })

const load = async () => {
  loading.value = true
  try {
    const r = await getUserPreference()
    const d = (r && r.data) || {}
    pref.value = {
      defaultModel: d.defaultModel || ''
    }
    // 用户级记忆开关（后端口径：null 视为开）
    memAutoEnabled.value = d.memoryEnabled !== false
  } catch (e) { /* 拉取失败保持空（未设默认） */ }
  finally { loading.value = false }
}

const save = async () => {
  saving.value = true
  try {
    // 个人默认模型（原有全量提交口径）
    await setUserPreference(pref.value)
    // 对话偏好（仅聊天面板）：只提交与预填不同的项，未改动的继续跟随全局
    if (current.value === 'chat') {
      const payload = changedPrefPayload()
      if (Object.keys(payload).length) {
        const r = await saveUserSettings(payload)
        if (r && r.success === false) { message.error(r.msg || '保存失败'); return }
      }
    }
    message.success('已保存')
    await load()
    if (current.value === 'chat') await loadPrefs()
    refreshSetupGuide(true)  // 个人设置改了默认模型 → 引导 tag/卡片立即对账
  } catch (e) {
    message.error(e.message || '保存失败')
    await load() // 回落服务端状态，避免本地与服务端不一致
  } finally { saving.value = false }
}

// ---- 回答偏好：个人覆盖（c_ai_user_config；schema 驱动渲染，仅 personal 字段） ----
// 表单预填「生效值」（个人值 > 全局值 > schema 默认值）：用户看到的就是当前生效值；
// 保存时只提交与预填不同的项（未改动的继续跟随全局，避免把全局值"复制"成个人值）。
const prefLoading = ref(false)
const prefFields = ref([])
const prefTips = ref({})
const prefForm = ref({})
const prefInitial = ref({})
const prefPersonalKeys = ref([])

const answerFields = computed(() => prefFields.value.filter(f => f.type !== 'model'))

const setByPath = (obj, path, v) => {
  const seg = path.split('.')
  let t = obj
  for (let i = 0; i < seg.length - 1; i++) { t[seg[i]] = t[seg[i]] || {}; t = t[seg[i]] }
  t[seg[seg.length - 1]] = v
}
const getByPath = (obj, path) => path.split('.').reduce((a, k) => (a == null ? a : a[k]), obj)
/** 生效值 → 控件值：switch 用布尔、number 用数字、文本原样（空=控件空） */
const toControl = (field, raw) => {
  const v = raw == null ? '' : String(raw)
  if (field.type === 'switch') return v === 'true'
  if (field.type === 'number' || field.type === 'range') return v === '' ? null : Number(v)
  return v
}
/** 控件值 → 提交值：全部归一为字符串（后端按字符串存/校验，与设置页同口径） */
const toSubmit = (field, v) => {
  if (field.type === 'switch') return v ? 'true' : 'false'
  if (v == null) return ''
  return String(v).trim()
}
// 数字控件 null 与空串等价（：只比较"是否被改过"，null/'' 归一避免误判脏）
const norm = v => (v == null ? '' : String(v))

const loadPrefs = async () => {
  prefLoading.value = true
  try {
    const r = await getUserSettings()
    const d = (r && r.data) || {}
    const fields = d.fields || []
    const values = d.values || {}
    const globals = d.globals || {}
    const form = {}
    const init = {}
    const labels = []
    for (const f of fields) {
      const personal = values[f.path]
      if (personal !== undefined && personal !== '') labels.push(f.label)
      const effective = (personal !== undefined && personal !== '') ? personal
        : (globals[f.path] !== undefined && globals[f.path] !== '' ? globals[f.path] : f.def)
      const ctrl = toControl(f, effective)
      setByPath(form, f.path, ctrl)
      setByPath(init, f.path, ctrl)
    }
    prefFields.value = fields
    prefTips.value = d.tips || {}
    prefForm.value = form
    prefInitial.value = init
    prefPersonalKeys.value = labels
  } catch (e) { /* 拉取失败保持空面板 */ }
  finally { prefLoading.value = false }
}
const prefDirty = computed(() => {
  for (const f of prefFields.value) {
    if (norm(getByPath(prefForm.value, f.path)) !== norm(getByPath(prefInitial.value, f.path))) return true
  }
  return false
})
/** 与预填不同的项 → 提交载荷（统一转字符串；未被改动的项不提交，继续跟随全局） */
const changedPrefPayload = () => {
  const payload = {}
  for (const f of prefFields.value) {
    const cur = getByPath(prefForm.value, f.path)
    if (norm(cur) !== norm(getByPath(prefInitial.value, f.path))) {
      payload[f.path] = toSubmit(f, cur)
    }
  }
  return payload
}
/** 全部清除个人设置：清空本人所有个人覆盖（后端按空串=删行；清空后回落全局默认/本地服务） */
const clearPrefs = async () => {
  const payload = {}
  for (const f of prefFields.value) payload[f.path] = ''
  saving.value = true
  try {
    const r = await saveUserSettings(payload)
    if (r && r.success === false) { message.error(r.msg || '操作失败'); return }
    message.success('已全部清除个人设置')
    await loadPrefs()
  } catch (e) { message.error(e.message || '操作失败') }
  finally { saving.value = false }
}

// ---- 账号安全：修改密码（成功后强制重新登录） ----
const pwdSaving = ref(false)
const pwdError = ref('')
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' })
const submitPwd = async () => {
  const f = pwdForm.value
  pwdError.value = ''
  if (!f.oldPassword) { pwdError.value = '请输入当前密码'; return }
  if (!f.newPassword || f.newPassword.length < 6) { pwdError.value = '新密码至少 6 位'; return }
  if (f.newPassword !== f.confirm) { pwdError.value = '两次输入的新密码不一致'; return }
  pwdSaving.value = true
  try {
    const r = await changePasswordApi(f.oldPassword, f.newPassword)
    if (r && r.success) {
      message.success('密码已修改，请重新登录')
      clearAuth()
      router.push('/login')
    } else {
      message.error((r && r.msg) || '修改失败')
    }
  } catch (e) { message.error(e.message || '修改失败') }
  finally { pwdSaving.value = false }
}

// ---- 个人资料：昵称（自助修改；保存后刷新身份缓存，侧栏名立即同步） ----
const nickSaving = ref(false)
const nickForm = ref({ username: '' })
const saveNickname = async () => {
  const name = nickForm.value.username.trim()
  if (!name) return
  nickSaving.value = true
  try {
    const r = await updateMyProfile(name)
    if (r && r.success !== false) {
      message.success('昵称已保存')
      await ensureAuth(true) // 刷新 /auth/me 缓存 → AppLayout 的 computed userName 立即跟着变
    } else message.error(r?.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { nickSaving.value = false }
}

// ---- 个人资料：头像（emoji 选择 / 图片上传 / 清除；与昵称同源 updateMyProfile，改动后同步身份缓存） ----
const avatarPreview = ref('')
const avatarSaving = ref(false)
// emoji 可选集（比智能体图标集更生活化，便于个人辨识）
const AVATAR_EMOJIS = ['🤖', '🧠', '💡', '📚', '⚖️', '📊', '✍️', '🔍', '🛠️', '💬',
  '🎯', '🧭', '📝', '🌐', '⚡', '🌟', '🍀', '🔥', '🌈', '🐱',
  '🐶', '🦊', '🐼', '🌸', '⚓', '🎨', '🚀', '💎', '☕', '🍎']
const pickEmoji = async (e) => {
  if (avatarSaving.value) return
  avatarSaving.value = true
  try {
    const r = await updateMyProfile(nickForm.value.username, e)
    if (r && r.success !== false) { avatarPreview.value = e; await ensureAuth(true); message.success('头像已更新') }
    else message.error(r?.msg || '保存失败')
  } catch (err) { message.error(err.message || '保存失败') }
  finally { avatarSaving.value = false }
}
const onAvatarFile = async (ev) => {
  const file = ev.target.files && ev.target.files[0]
  ev.target.value = '' // 允许重复选同一文件
  if (!file) return
  avatarSaving.value = true
  try {
    const r = await uploadAvatarApi(file)
    const url = r && r.data && r.data.url
    if (url) { avatarPreview.value = url; await ensureAuth(true); message.success('头像已上传') }
    else message.error('上传失败')
  } catch (err) { message.error(err.message || '上传失败') }
  finally { avatarSaving.value = false }
}
const clearAvatar = async () => {
  if (avatarSaving.value) return
  avatarSaving.value = true
  try {
    const r = await updateMyProfile(nickForm.value.username, '')
    if (r && r.success !== false) { avatarPreview.value = ''; await ensureAuth(true); message.success('已清除头像') }
    else message.error(r?.msg || '操作失败')
  } catch (err) { message.error(err.message || '操作失败') }
  finally { avatarSaving.value = false }
}

// ---- 长期记忆：用户级自动提炼开关（仅关生成，不关注入） ----
const memAutoEnabled = ref(true)
const memAutoSaving = ref(false)
const toggleMemAuto = async checked => {
  memAutoSaving.value = true
  try {
    const r = await setUserPreference({ memoryEnabled: checked })
    if (r && r.success !== false) {
      memAutoEnabled.value = checked
      message.success(checked ? '已开启自动提炼记忆' : '已关闭自动提炼记忆')
    } else { message.error(r?.msg || '保存失败'); await load() }
  } catch (e) {
    message.error(e.message || '保存失败')
    await load() // 回落服务端状态，避免开关与服务端不一致
  } finally { memAutoSaving.value = false }
}

// ---- 长期记忆：列表 / 手动添加 / 编辑 / 删除（注入与自动提取在后端完成） ----
const memories = ref([])
const memLoading = ref(false)
const memSaving = ref(false)
const memDraft = ref('')
const memEditing = ref('')
const memEditDraft = ref('')
const CATEGORY_LABELS = { fact: '事实', instruction: '约定', project: '项目' }
const categoryLabel = c => CATEGORY_LABELS[c] || '事实'
// 手动添加可选分类（后端 addManual 本就支持 category，此前前端没暴露）
const CAT_OPTIONS = Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label }))
const memDraftCat = ref('fact')

const loadMemories = async () => {
  memLoading.value = true
  try {
    const r = await listMyMemories()
    memories.value = (r && r.data) || []
  } catch (e) { /* 静默：列表加载失败不阻塞其他面板 */ }
  finally { memLoading.value = false }
}
const addMemory = async () => {
  const c = memDraft.value.trim()
  if (!c) return
  memSaving.value = true
  try {
    const r = await addMyMemory(c, memDraftCat.value)
    if (r && r.success !== false) { memDraft.value = ''; await loadMemories() }
    else message.error(r?.msg || '添加失败')
  } catch (e) { message.error(e.message || '添加失败') }
  finally { memSaving.value = false }
}
const startMemEdit = m => { memEditing.value = m.id; memEditDraft.value = m.content }
const saveMemEdit = async m => {
  const c = memEditDraft.value.trim()
  if (!c) return
  memSaving.value = true
  try {
    const r = await updateMyMemory(m.id, c)
    if (r && r.success !== false) { memEditing.value = ''; await loadMemories() }
    else message.error(r?.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { memSaving.value = false }
}
/** 删除确认：Modal.confirm（a-popconfirm 点击浮层在 iab 内嵌页不弹，全站统一用 Modal） */
const confirmRemoveMemory = m => {
  Modal.confirm({
    title: '删除这条记忆？',
    content: '「' + m.content.slice(0, 40) + (m.content.length > 40 ? '…' : '') + '」删除后不会再被提起。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => removeMemory(m)
  })
}
const removeMemory = async m => {
  try {
    const r = await deleteMyMemory(m.id)
    if (r && r.success !== false) await loadMemories()
    else message.error(r?.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ---- 分享管理（会话只读链接的统一回看与停用） ----
// 与会话内分享面板（views/shareSession.js）的关系：那边管"这一条"，这里管"全部"。
// 链接拼装复用 shareUrlOf —— 协议只有一处定义，两边各拼一次迟早漂移。
const shares = ref([])
const shareLoading = ref(false)
const shareBusy = ref('')
// 深链定位：侧栏点分享标记进来时高亮并滚到对应那一条（?sid=）
const focusSid = ref('')

const shareOnCount = computed(() => shares.value.filter(s => s.enabled).length)

const fmtTime = v => {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const loadShares = async () => {
  shareLoading.value = true
  try {
    const r = await listMySessionShares()
    shares.value = (r && r.data) || []
    // 深链定位的行滚进视野：从侧栏标记点进来时直接看到"就是这条"
    if (focusSid.value) {
      await new Promise(resolve => setTimeout(resolve, 60))
      const el = document.querySelector('.shm-item.focus')
      if (el && el.scrollIntoView) el.scrollIntoView({ block: 'center' })
    }
  } catch (e) { message.error(e.message || '分享列表加载失败') }
  finally { shareLoading.value = false }
}

const copyShare = s => copyText(shareUrlOf(s.token), '链接已复制')
const goSharedSession = s => router.push({ path: '/chat', query: { sid: s.sessionId } })

const stopShare = s => {
  Modal.confirm({
    title: '停止分享这条链接？',
    content: '停止后所有已发出的链接立即失效，拿到链接的人会看到"链接已失效"。可以随时重新开启，但会生成新链接。',
    okText: '停止分享', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await stopShareBySession(s.sessionId)
        if (r && r.success !== false) { message.success('已停止分享'); await loadShares() }
        else message.error(r?.msg || '停止失败')
      } catch (e) { message.error(e.message || '停止失败') }
    }
  })
}

/** 换新链接 / 重新开启：同一个后端动作（换新 token 使旧链接立即失效），文案按当前状态区分 */
const regenShare = s => {
  Modal.confirm({
    title: '换一个新链接？',
    content: '此前发出的所有链接会立即失效，新链接需重新分发。',
    okText: '换新链接', cancelText: '取消',
    onOk: () => restartShare(s, '已生成新链接，旧链接立即失效')
  })
}
const restartShare = async (s, okMsg = '已重新开启分享') => {
  if (shareBusy.value) return
  shareBusy.value = s.sessionId
  try {
    const r = await enableSessionShare(s.sessionId)
    if (r && r.success !== false) {
      message.success(okMsg)
      await loadShares()
      focusSid.value = s.sessionId
    } else message.error(r?.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { shareBusy.value = '' }
}

/** 清除记录：物理删除一条已停止的分享记录（与"停止分享"不同，那个只让链接失效） */
const purgeRecord = s => {
  Modal.confirm({
    title: '清除这条分享记录？',
    content: '记录会从分享管理里移除，访问次数等统计一并消失，无法恢复。会话本身不受影响。' +
      (s.orphaned ? '（该会话已删除，此记录已无用途。）' : ''),
    okText: '清除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await purgeShareRecord(s.sessionId)
        if (r && r.success !== false) { message.success('记录已清除'); await loadShares() }
        else message.error(r?.msg || '清除失败')
      } catch (e) { message.error(e.message || '清除失败') }
    }
  })
}

// 面板切换时才拉列表：其余面板不碰这个接口（个人设置是常驻页，每次进都拉会白跑一次）
watch(current, v => {
  if (v === 'shares') {
    focusSid.value = route.query.sid || ''
    loadShares()
  }
})

onMounted(() => {
  load()
  loadMemories()
  loadPrefs()
  ensureAuth().then(me => { nickForm.value.username = me.username || ''; avatarPreview.value = me.avatar || '' })
  // 直接以 ?panel=shares 深链进入（侧栏入口）时，watch(current) 不会触发——初始化归位前手动拉一次
  if (current.value === 'shares') loadShares()
})
</script>

<style scoped>
.pf-body { flex: 1; min-height: 0; display: flex; }
.pf-nav {
  width: 150px; flex: none; border-right: 1px solid var(--app-border); background: var(--app-panel);
  padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto;
}
.pf-nav-item { padding: 7px 10px; border-radius: 8px; font-size: 12px; color: var(--app-text2); cursor: pointer; border: none; background: transparent; text-align: left; }
.pf-nav-item:hover { background: var(--app-accent-weak); }
.pf-nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }
.pf-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.pf-card { max-width: 640px; padding: 18px 20px; }
.pf-hint { font-size: 12px; color: var(--app-text2); margin: 0 0 12px; }
.pf-hint-top { margin-bottom: 18px; line-height: 1.65; }
.pf-sub-hint { font-size: 11px; color: var(--app-text3); margin: 10px 0 0; }
.pf-row { display: flex; align-items: center; gap: 10px; }
.pf-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }

/* 头像区：左预览 + 右侧「按钮行 / 说明行」两行，不再把按钮和长说明挤成一条横带 */
.pf-avatar-block { display: flex; align-items: flex-start; gap: 18px; }
/* 描边环：上传的图片（多为白底照片）在白卡片上也有边界；用 shadow 不占布局尺寸 */
.pf-avatar-frame {
  flex: none; display: inline-flex; border-radius: 50%;
  box-shadow: 0 0 0 1px var(--app-border), 0 1px 2px rgba(0, 0, 0, .05);
}
.pf-avatar-side { min-width: 0; display: flex; flex-direction: column; gap: 6px; padding-top: 6px; }
.pf-op-row { display: flex; align-items: center; gap: 8px; }
.pf-avatar-side .pf-sub-hint { margin: 0; }
.pf-upload { cursor: pointer; min-width: 86px; justify-content: center; }
.pf-upload.busy { opacity: .65; pointer-events: none; }
/* ghost 按钮 disabled 时全局样式不降透明度，这里补上，避免「清除头像」看着仍可点 */
.pf-avatar-side .app-btn.ghost:disabled { opacity: .45; color: var(--app-text3); }

/* 分区：图标区 / 昵称区各自成块，用分隔线分层级（原先三块平铺无区分） */
.pf-field { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--app-border); }
.pf-field-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.pf-label { font-size: 12px; font-weight: 500; color: var(--app-text); }
.pf-field-head .pf-sub-hint { margin: 0; }

/* 图标集：15 列定宽网格（30 个正好两行、不留残行）；去掉逐格边框与白底，只靠 hover / 选中环表达状态 */
.pf-emoji-pick { display: grid; grid-template-columns: repeat(15, minmax(0, 1fr)); gap: 6px; }
.pf-emoji-opt {
  aspect-ratio: 1 / 1; min-width: 0; padding: 0; font-size: 18px; line-height: 1;
  border: none; border-radius: 9px; background: transparent; cursor: pointer; color: inherit;
  display: inline-flex; align-items: center; justify-content: center;
  transition: background .15s, box-shadow .15s, transform .12s;
}
.pf-emoji-opt:hover { background: var(--app-accent-weak); }
.pf-emoji-opt:active { transform: scale(.92); }
.pf-emoji-opt.on { background: var(--app-accent-weak); box-shadow: inset 0 0 0 1.5px var(--app-accent); }
.pf-emoji-opt:disabled { cursor: not-allowed; opacity: .5; }
.pf-emoji-opt.on:disabled { opacity: 1; }
/* 窄屏（扣掉左侧导航后内容区收窄）降到 10 列，30 个仍是整行 */
@media (max-width: 860px) {
  .pf-emoji-pick { grid-template-columns: repeat(10, minmax(0, 1fr)); }
}
/* 长期记忆：卡片比其他面板略宽（长句记忆要完整换行展示，640 略挤） */
.mem-card { max-width: 720px; }
/* 标题旁条数胶囊 */
.mem-count { font-size: 11px; font-weight: 400; line-height: 1; color: var(--app-text3); background: var(--app-panel-2); padding: 3px 8px; border-radius: 999px; }
/* 手动添加行：输入 + 分类 + 按钮；窄屏允许换行不挤压 */
.mem-add-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.mem-add-input { flex: 1; min-width: 200px; }
.mem-add-cat { width: 92px; flex: none; }
/* 记忆列表：每条一张软卡片，内容完整换行（不再单行截断省略） */
.mem-list { display: flex; flex-direction: column; gap: 8px; }
.mem-item { border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; }
.mem-content { font-size: 13px; line-height: 1.65; color: var(--app-text); white-space: pre-wrap; word-break: break-word; }
/* 元信息行：分类/来源/使用度靠左，编辑删除靠右——独立一行，任何宽度都不变形 */
.mem-foot { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.mem-tag { flex: none; font-size: 11px; line-height: 1; padding: 3px 7px; border-radius: 4px; white-space: nowrap; background: var(--app-panel-2); color: var(--app-text3); }
.mem-cat-project { background: var(--app-accent-weak); color: var(--app-accent); }
.mem-cat-instruction { background: #faf3e6; color: #a3691b; }

/* ---- 分享管理 ---- */
.shm-card { max-width: 760px; }
.shm-list { display: flex; flex-direction: column; gap: 8px; }
.shm-item {
  border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px;
  display: flex; align-items: flex-start; gap: 12px;
}
/* 已停止：整条压暗，让"生效中"的几条在长列表里一眼可辨（这是本页要回答的首要问题） */
.shm-item.off { opacity: .68; }
/* 深链定位：从侧栏分享标记点进来时短暂标一下"就是这条" */
.shm-item.focus { border-color: var(--app-accent); background: var(--app-accent-weak); }
.shm-main { flex: 1; min-width: 0; }
.shm-title {
  font-size: 13px; color: var(--app-text); font-weight: 500;
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.shm-tag {
  font-size: 11px; line-height: 1; padding: 3px 6px; border-radius: 4px;
  background: var(--app-panel-2); color: var(--app-text3); font-weight: 400; white-space: nowrap;
}
.shm-tag.on { background: #e8f5ee; color: #1a7f4b; }
.shm-tag.warn { background: #fdeceb; color: #b0322f; }
.shm-meta { font-size: 11px; color: var(--app-text3); margin-top: 4px; }
.shm-link { margin-top: 6px; }
/* 链接单行截断：URL 很长，全量换行会把这一行撑得很高 */
.shm-url {
  display: block; font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 11px;
  color: var(--app-text3); background: var(--app-bg); border: 1px solid var(--app-border);
  border-radius: 4px; padding: 4px 6px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.shm-note { font-size: 11px; color: var(--app-text3); margin-top: 6px; }
.shm-acts {
  flex: none; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  justify-content: flex-end; max-width: 320px;
}
.shm-off-note { font-size: 11px; color: var(--app-text3); }
.mem-src { background: transparent; border: 1px solid var(--app-border); padding: 2px 6px; }
.mem-meta { flex: none; font-size: 11px; color: var(--app-text3); }
.mem-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
/* 编辑态：文本域 + 底部按钮行（计数靠左、按钮靠右，互不重叠） */
.mem-edit-foot { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 8px; }
.mem-edit-count { margin-right: auto; font-size: 11px; color: var(--app-text3); }
/* 空态 */
.mem-empty { text-align: center; padding: 26px 0 18px; }
.mem-empty-ico { font-size: 26px; line-height: 1; }
.mem-empty-title { font-size: 13px; color: var(--app-text2); margin: 8px 0 2px; }
.mem-empty .pf-sub-hint { margin: 0; }
/* 自动提炼开关行：左说明右开关，与手动添加行之间留分隔 */
.mem-auto-row { padding: 10px 12px; margin-bottom: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); }
.mem-auto-text { flex: 1; min-width: 0; }
.mem-auto-title { font-size: 13px; color: var(--app-text); font-weight: 500; margin-bottom: 2px; }
.mem-auto-row .pf-sub-hint { margin: 0; }
</style>
