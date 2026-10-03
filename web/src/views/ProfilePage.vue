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
          <p class="pf-hint">
            昵称显示在侧边栏与成员列表，仅作展示、不用于登录。
            登录账号不可修改。
          </p>
          <div class="pf-row">
            <a-input v-model:value="nickForm.username" :maxlength="100" allow-clear style="width:320px"
                     placeholder="显示昵称，如 张三" @pressEnter="saveNickname" />
            <button class="app-btn" :disabled="nickSaving || !nickForm.username.trim()" @click="saveNickname">保存</button>
          </div>
          <p class="pf-sub-hint">改完侧边栏立即生效；管理员仍可在「成员管理」中调整。</p>
        </div>

        <!-- 个人默认模型面板（聊天面板同时承载「回答偏好」与「模型默认」个人覆盖项，保存按钮统一提交） -->
        <div v-else-if="current === 'chat' || current === 'vision'" class="app-card pf-card">
          <h2 class="app-card-title">{{ panelMeta.title }}</h2>
          <p class="pf-hint">{{ panelMeta.hint }}</p>
          <div class="pf-row">
            <!-- :key 按面板重挂载：聊天/视觉各用自己的实例，避免共享实例残留上一面板的列表与内部状态；
                 用组件声明的 v-model（而非 v-model:value——那是透传到根 a-select 的偶然生效路径） -->
            <ModelSelect :key="current" v-model="pref[panelMeta.field]" :type="current"
                         inherit-label="不设默认" :width="360" :disabled="loading" />
          </div>
          <p class="pf-sub-hint">{{ panelMeta.tail }}</p>

          <!-- 回答偏好与模型默认（仅聊天面板）：个人覆盖系统设置的体验参数与模型引用，仅对本人生效 -->
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
              <p v-else-if="!prefLoading && !modelFields.length" class="pf-sub-hint">暂无可个人覆盖的配置项。</p>
            </a-spin>

            <!-- 模型默认（重排/记忆向量/图谱兜底/问答对生成）：模型都归登记人，无全局槽位——
                 只能选自己登记的模型，仅对本人问答与本人资源生效 -->
            <template v-if="modelFields.length">
              <a-divider style="margin:16px 0 12px" />
              <p class="pf-hint">
                模型默认：重排 / 记忆向量 / 图谱兜底 / 问答对生成等能力所用的模型。模型都归登记人，
                这里只能选你<strong>自己</strong>登记的模型，且只对你本人的问答与资源生效；未设置时
                相关能力各自降级或回落（如重排回落到本地服务）。
              </p>
              <a-form layout="vertical" style="max-width:560px">
                <SchemaField v-for="f in modelFields" :key="f.path" :field="f" :form="prefForm" :tips="prefTips" />
              </a-form>
            </template>

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

        <!-- 向量模型仍无个人默认：向量空间与知识库索引一一对应、归知识库绑定；
             重排/记忆向量/图谱兜底/问答对生成已改为个人设置项（见聊天面板「模型默认」） -->

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
        <div v-else-if="current === 'memory'" class="app-card pf-card">
          <h2 class="app-card-title">我的长期记忆</h2>
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
          <div class="pf-row" style="margin-bottom:12px">
            <a-input v-model:value="memDraft" :maxlength="500" allow-clear style="flex:1"
                     placeholder="手动添加一条记忆，如：我负责 XX 系统的运维" @pressEnter="addMemory" />
            <button class="app-btn" :disabled="memSaving || !memDraft.trim()" @click="addMemory">添加</button>
          </div>
          <a-spin :spinning="memLoading">
            <div v-if="!memories.length" class="pf-sub-hint" style="padding:12px 0">
              还没有记忆。多聊几轮，或手动添加一条。
            </div>
            <div v-for="m in memories" :key="m.id" class="mem-item">
              <template v-if="memEditing === m.id">
                <a-input v-model:value="memEditDraft" :maxlength="500" size="small" @pressEnter="saveMemEdit(m)" />
                <button class="app-link-btn" :disabled="memSaving" @click="saveMemEdit(m)">保存</button>
                <button class="app-link-btn" @click="memEditing = ''">取消</button>
              </template>
              <template v-else>
                <span class="mem-content" :title="m.content">{{ m.content }}</span>
                <span class="mem-tag" :class="'mem-' + m.category">{{ categoryLabel(m.category) }}</span>
                <span v-if="m.source === 'auto'" class="mem-tag mem-src" :title="'来自会话 ' + (m.sourceSessionId || '')">自动</span>
                <span class="mem-meta">用过 {{ m.hitCount || 0 }} 次</span>
                <button class="app-link-btn" @click="startMemEdit(m)">编辑</button>
                <a-popconfirm title="删除后不会再被提起，确定？" @confirm="removeMemory(m)">
                  <button class="app-link-btn danger">删除</button>
                </a-popconfirm>
              </template>
            </div>
          </a-spin>
          <p class="pf-sub-hint">
            记忆只属于你自己，只注入你本人的对话（公开分享页不会携带）；已达上限时自动提取会暂停，删掉几条即可恢复。
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { clearAuth, ensureAuth } from '../utils/auth'
import { refreshSetupGuide } from '../utils/setupGuide'
import { changePasswordApi, getUserPreference, setUserPreference, updateMyProfile,
         getUserSettings, saveUserSettings,
         listMyMemories, addMyMemory, updateMyMemory, deleteMyMemory } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import SchemaField from '../components/SchemaField.vue'

const router = useRouter()
const route = useRoute()

const navs = [
  { key: 'profile', label: '个人资料' },
  { key: 'chat', label: '聊天模型与偏好' },
  { key: 'vision', label: '视觉模型' },
  { key: 'memory', label: '长期记忆' },
  { key: 'security', label: '账号安全' }
]
// ?panel= 深链（配置引导建议项「去配置」直达对应面板）；未知值回落个人资料
const PANEL_KEYS = navs.map(n => n.key)
const current = ref(PANEL_KEYS.includes(route.query.panel) ? route.query.panel : 'profile')
watch(() => route.query.panel, v => { if (PANEL_KEYS.includes(v)) current.value = v })

const PANELS = {
  chat: {
    field: 'defaultModel', title: '聊天模型与偏好',
    hint: '个人默认聊天模型：智能体未指定、会话未手动选择时使用；下方「回答偏好」可按需覆盖系统全局值。',
    tail: '清空（选「不设默认」）后每次对话需手动选择模型。'
  },
  vision: {
    field: 'defaultVisionModel', title: '视觉模型',
    hint: '对话中上传图片的理解走此模型；留空则不识别图片内容（仅展示）。须选「视觉」类型（通用视觉模型）；「OCR 专用」类型只用于文档解析，不能用于图片理解。',
    tail: '文档入库时的图片描述使用知识库绑定的视觉模型（知识库 → 编辑 → 解析参数）。'
  }
}
const panelMeta = computed(() => PANELS[current.value] || PANELS.chat)

// 个人默认（全量保存：任一面板保存都提交当前值；向量/重排无个人默认，归知识库绑定/检索设置）
const loading = ref(false)
const saving = ref(false)
const pref = ref({ defaultModel: '', defaultVisionModel: '' })

const load = async () => {
  loading.value = true
  try {
    const r = await getUserPreference()
    const d = (r && r.data) || {}
    pref.value = {
      defaultModel: d.defaultModel || '',
      defaultVisionModel: d.defaultVisionModel || ''
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

// ---- 回答偏好/模型默认：个人覆盖（c_ai_user_config；schema 驱动渲染，仅 personal 字段） ----
// 表单预填「生效值」（个人值 > 全局值 > schema 默认值）：用户看到的就是当前生效值；
// 保存时只提交与预填不同的项（未改动的继续跟随全局，避免把全局值"复制"成个人值）。
const prefLoading = ref(false)
const prefFields = ref([])
const prefTips = ref({})
const prefForm = ref({})
const prefInitial = ref({})
const prefPersonalKeys = ref([])

// 分组渲染：体验类字段归「回答偏好」，模型引用（+重排开关）归「模型默认」
// 重排开关与模型同组：同一意图（开重排 + 选模型），拆两处会出现"配了模型找不到开关"
const MODEL_GROUP_EXTRA = new Set(['retrieval.rerank.enabled'])
const answerFields = computed(() => prefFields.value.filter(
  f => f.type !== 'model' && !MODEL_GROUP_EXTRA.has(f.path)))
const modelFields = computed(() => prefFields.value.filter(
  f => f.type === 'model' || MODEL_GROUP_EXTRA.has(f.path)))

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
    const r = await addMyMemory(c)
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
const removeMemory = async m => {
  try {
    const r = await deleteMyMemory(m.id)
    if (r && r.success !== false) await loadMemories()
    else message.error(r?.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

onMounted(() => {
  load()
  loadMemories()
  loadPrefs()
  ensureAuth().then(me => { nickForm.value.username = me.username || '' })
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
.pf-sub-hint { font-size: 11px; color: var(--app-text3); margin: 10px 0 0; }
.pf-row { display: flex; align-items: center; gap: 10px; }
.pf-err { color: var(--app-danger); font-size: 12px; margin: 0 0 8px; }
/* 长期记忆列表 */
.mem-item { display: flex; align-items: center; gap: 8px; padding: 8px 0; border-bottom: 1px dashed var(--app-border); }
.mem-item:last-child { border-bottom: none; }
.mem-content { flex: 1; min-width: 0; font-size: 13px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mem-tag { flex: none; font-size: 11px; padding: 1px 6px; border-radius: 4px; background: var(--app-accent-weak); color: var(--app-accent); }
.mem-src { background: var(--app-panel-2); color: var(--app-text3); }
.mem-meta { flex: none; font-size: 11px; color: var(--app-text3); }
/* 自动提炼开关行：左说明右开关，与手动添加行之间留分隔 */
.mem-auto-row { padding: 10px 12px; margin-bottom: 14px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); }
.mem-auto-text { flex: 1; min-width: 0; }
.mem-auto-title { font-size: 13px; color: var(--app-text); font-weight: 500; margin-bottom: 2px; }
.mem-auto-row .pf-sub-hint { margin: 0; }
</style>
