<!-- 个人设置。长期记忆面板在 P5 收口时补回：框架仍负责写入（MEMORY.md + 日账），
     这里只提供「看得见、改得动、删得掉」的个人治理入口；日账只读，MEMORY.md 可编辑/清空。 -->
<template>
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title">个人设置</h1>
    </div>
    <div class="pf-body">
      <nav ref="pfNavEl" class="pf-nav">
        <button v-for="n in navs" :key="n.key" class="pf-nav-item"
                :class="{ active: current === n.key }" @click="current = n.key">
          {{ n.label }}
        </button>
      </nav>
      <section class="pf-content">
        <!-- 个人资料（自助：仅昵称，登录账号不可改） -->
        <div v-if="current === 'profile'" class="app-card pf-card">
          <h2 class="app-card-title">个人资料</h2>
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
              <a-input v-model:value="nickForm.username" :maxlength="100" allow-clear class="pf-nick-input"
                       placeholder="显示昵称，如 张三" @pressEnter="saveNickname" />
              <button class="app-btn" :disabled="nickSaving || !nickForm.username.trim()" @click="saveNickname">保存</button>
            </div>
          </div>
        </div>

        <!-- 个人默认模型 + 自动提炼记忆开关（保存按钮统一提交默认模型；记忆开关即时生效不参与保存） -->
        <div v-else-if="current === 'chat'" class="app-card pf-card">
          <h2 class="app-card-title">{{ panelMeta.title }}</h2>
          <p class="pf-hint">{{ panelMeta.hint }}</p>
          <div class="pf-row">
            <!-- 用组件声明的 v-model（而非 v-model:value——那是透传到根 a-select 的偶然生效路径） -->
            <ModelSelect v-model="pref[panelMeta.field]" type="chat"
                         :issue="current === 'chat' ? defaultModelIssue : ''"
                         inherit-label="不设默认" :width="isNarrow ? '100%' : 360" :disabled="loading" />
          </div>
          <p class="pf-sub-hint">{{ panelMeta.tail }}</p>

          <!-- 长期记忆的生成闸（即时生效，不参与下方「保存」）：只关「问答后自动提炼」这一步，
               已沉淀的记忆在后续问答里照常被提起。关掉后下一轮起才停（正在跑的那一轮不受影响）。 -->
          <div class="mem-auto-row" style="display:flex;align-items:flex-start;gap:12px">
            <div class="mem-auto-text">
              <div class="mem-auto-title">自动提炼记忆</div>
              <p class="pf-sub-hint">关掉后问答不再往长期记忆里添新条目；已经记下的仍会在后续问答中被提起。</p>
            </div>
            <a-switch :checked="memAutoEnabled" :loading="memAutoSaving" @change="toggleMemAuto" />
          </div>

          <!-- 回答偏好（原「相关建议条数 / 空态示例问题 / 默认助手 / 停用的技能 / 工具执行上限」五项
               个人覆盖）已整体下线：连同它们在系统设置里的全局那一份一起删干净，不再有两处口径。 -->

          <div class="pf-row" style="margin-top:14px">
            <button class="app-btn" :disabled="saving" @click="save">保存</button>
          </div>
        </div>

        <!-- 我的记忆（P5 收口）：框架负责写，这里负责让用户看得见、改得动、删得掉 -->
        <div v-else-if="current === 'memory'" class="app-card pf-card mem-card">
          <h2 class="app-card-title">
            我的记忆
            <span v-if="memOverview.daily && memOverview.daily.length" class="mem-count">
              {{ memOverview.daily.length }} 天日账
            </span>
          </h2>
          <p class="pf-hint">
            助手会在问答结束后把值得长期记住的信息写进 <code>MEMORY.md</code>，日账则是每天追加的原始记录。
            这里的内容只有你自己能看到；日账是只读的，删除后无法恢复。
          </p>
          <a-spin :spinning="memLoading">
            <div v-if="memOverview.over" class="mem-warn">
              当前约 {{ memOverview.tokens }} token，超过硬上限 {{ memOverview.maxTokens }} token；
              超出部分不会进入模型上下文。
              <button class="app-link-btn" @click="onCompactMemory">按上限裁剪</button>
            </div>

            <div class="mem-toolbar">
              <button class="app-btn" :disabled="memEditing || memSaving" @click="startMemEdit">编辑 MEMORY.md</button>
              <button class="app-btn ghost" :disabled="!memOverview.content" @click="downloadMemory">下载</button>
              <button class="app-btn ghost mem-danger"
                      :disabled="memClearing || (!memOverview.content && !(memOverview.daily && memOverview.daily.length))"
                      @click="onClearMemory">清空全部记忆</button>
              <span class="mem-meta">
                {{ memOverview.size || 0 }} 字符 · 约 {{ memOverview.tokens || 0 }} token
                / 上限 {{ memOverview.maxTokens || 0 }}
              </span>
            </div>

            <template v-if="memEditing">
              <a-textarea v-model:value="memEditText" :rows="16"
                          show-count class="mem-edit-area" placeholder="MEMORY.md 内容（Markdown）" />
              <div class="mem-edit-foot">
                <span class="mem-edit-count">{{ memEditText.length }} / {{ memOverview.maxChars || 0 }} 字符</span>
                <button class="app-btn" :disabled="memSaving" @click="saveMemory">
                  {{ memSaving ? '保存中…' : '保存' }}
                </button>
                <button class="app-btn ghost" :disabled="memSaving" @click="cancelMemEdit">取消</button>
              </div>
            </template>
            <pre v-else class="mem-md">{{ memOverview.content || '（还没有沉淀任何记忆。问答几轮后，助手会把值得记住的内容写到这里。）' }}</pre>

            <div class="mem-days">
              <div class="mem-days-head">日账</div>
              <div v-if="!memOverview.daily || !memOverview.daily.length" class="pf-sub-hint">还没有日账。</div>
              <div v-for="d in (memOverview.daily || [])" :key="d.date" class="mem-day-row">
                <span class="mem-day-date">{{ d.date }}</span>
                <span class="mem-meta">{{ fmtSize(d.size) }}</span>
                <div class="mem-day-acts">
                  <button class="app-link-btn" @click="viewDaily(d.date)">查看</button>
                  <button class="app-link-btn danger" @click="onDeleteDaily(d.date)">删除</button>
                </div>
              </div>
            </div>
            <p class="pf-sub-hint">
              最后合并：{{ memOverview.lastConsolidatedAt ? fmtTime(memOverview.lastConsolidatedAt) : '尚未合并' }}
              <template v-if="memOverview.lastConsolidatedAt">（后台大约每 30 分钟合并一次）</template>
            </p>
          </a-spin>

          <a-modal v-model:open="memViewOpen" :title="memViewTitle" :footer="null"
                   :width="isNarrow ? '92%' : 720">
            <pre class="mem-md">{{ memViewText }}</pre>
          </a-modal>
        </div>

        <!-- 模型能力均无个人层槽位：向量空间与知识库索引一一对应、归知识库绑定；
             重排（开关+模型）同归知识库/智能体检索设置绑定；
             问答对生成与 GraphRAG 抽取均已回落库主默认聊天模型（上方「个人默认聊天模型」） -->

        <!-- 账号安全 -->
        <div v-else-if="current === 'security'" class="app-card pf-card">
          <h2 class="app-card-title">修改密码</h2>
          <a-form layout="vertical" class="pf-form">
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
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { clearAuth, ensureAuth } from '../utils/auth'
import { refreshSetupGuide } from '../utils/setupGuide'
import { copyText } from '../utils/clipboard'
import { shareUrlOf } from './shareSession'
import { markSessionShared } from './store'
import { changePasswordApi, getUserPreference, setUserPreference, updateMyProfile, uploadAvatarApi,
         listMySessionShares, stopShareBySession, purgeShareRecord, enableSessionShare,
         getMemoryOverview, getMemoryContent, saveMemoryMd, compactMemory, clearMyMemory,
         deleteMemoryDaily } from '../api'
import ModelSelect from '../components/ModelSelect.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { isNarrow } from '../h5/mobile'

const router = useRouter()
const route = useRoute()

const navs = [
  { key: 'profile', label: '个人资料' },
  { key: 'chat', label: '聊天模型与偏好' },
  { key: 'memory', label: '我的记忆' },
  { key: 'shares', label: '分享管理' },
  { key: 'security', label: '账号安全' }
]
// ?panel= 深链（配置引导建议项「去配置」直达对应面板）；未知值回落个人资料
const PANEL_KEYS = navs.map(n => n.key)
const current = ref(PANEL_KEYS.includes(route.query.panel) ? route.query.panel : 'profile')
watch(() => route.query.panel, v => { if (PANEL_KEYS.includes(v)) current.value = v })
// 窄屏 Tab 是顶部横滑条：激活项必须滚进可视区（点相邻项浏览器自己看得见，
// 但 ?panel= 深链直达最后一个 Tab 时条停在起点，激活项藏在屏外像「点了没反应」）
const pfNavEl = ref(null)
watch(current, () => nextTick(() => {
  pfNavEl.value?.querySelector('.pf-nav-item.active')
    ?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
}))

const PANELS = {
  chat: {
    field: 'defaultModel', title: '聊天模型与偏好',
    hint: '个人默认聊天模型：智能体未指定、会话未手动选择时使用。知识库的问答对生成、GraphRAG 抽取跟随「库主」的个人默认聊天模型——自己的库烧你这份默认，别人的库烧库主的（GraphRAG 抽取优先用库级绑定的抽取模型，未绑定才回落库主默认）。',
    tail: '清空（选「不设默认」）后每次对话需手动选择模型。对话中上传的图片能否被理解取决于所选模型：支持读图的模型原图直发；不支持的本轮图片仅作展示、不参与回答（可换支持读图的模型重试）。文档入库的图片描述使用知识库绑定的视觉模型。'
  }
}
const panelMeta = computed(() => PANELS[current.value] || PANELS.chat)

// 个人默认（保存提交当前值；向量/重排/视觉无个人默认，归知识库绑定/检索设置/模型能力位）
const loading = ref(false)
const saving = ref(false)
const pref = ref({ defaultModel: '' })
// 个人默认模型此刻能不能用（后端判：供应商被停用、模型被移出列表）。不参与保存，只驱动界面
const defaultModelIssue = ref('')

const load = async () => {
  loading.value = true
  try {
    const r = await getUserPreference()
    const d = (r && r.data) || {}
    pref.value = {
      defaultModel: d.defaultModel || ''
    }
    defaultModelIssue.value = d.defaultModelIssue || ''
    // 用户级记忆开关（后端口径：null 视为开）
    memAutoEnabled.value = d.memoryEnabled !== false
  } catch (e) { /* 拉取失败保持空（未设默认） */ }
  finally { loading.value = false }
}

const save = async () => {
  saving.value = true
  try {
    await setUserPreference(pref.value)
    message.success('已保存')
    await load()
    refreshSetupGuide(true)  // 个人设置改了默认模型 → 引导 tag/卡片立即对账
  } catch (e) {
    message.error(e.message || '保存失败')
    await load() // 回落服务端状态，避免本地与服务端不一致
  } finally { saving.value = false }
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

// ---- 我的记忆（个人设置 → 我的记忆） ----
// 数据源是后端 user-data/agentscope/{uid}/MEMORY.md 与 memory/*.md；后端按登录 uid 解析，
// 前端不传用户。日账只读，MEMORY.md 可编辑/清空；清空与会自动写入的后台任务互斥由后端标记实例失效。
const memLoading = ref(false)
const memSaving = ref(false)
const memClearing = ref(false)
const memEditing = ref(false)
const memEditText = ref('')
const memViewOpen = ref(false)
const memViewTitle = ref('')
const memViewText = ref('')
const memOverview = ref({
  content: '', size: 0, tokens: 0, maxTokens: 0, maxChars: 0, over: false,
  daily: [], lastConsolidatedAt: ''
})

const fmtSize = n => {
  const v = Number(n) || 0
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}

const loadMemory = async () => {
  memLoading.value = true
  try {
    const r = await getMemoryOverview()
    memOverview.value = (r && r.data) || memOverview.value
  } catch (e) {
    message.error(e.message || '记忆加载失败')
  } finally {
    memLoading.value = false
  }
}

const startMemEdit = () => {
  memEditText.value = memOverview.value.content || ''
  memEditing.value = true
}
const cancelMemEdit = () => {
  memEditing.value = false
  memEditText.value = ''
}
const saveMemory = async () => {
  memSaving.value = true
  try {
    const r = await saveMemoryMd(memEditText.value)
    memOverview.value = (r && r.data) || memOverview.value
    memEditing.value = false
    message.success('记忆已保存')
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    memSaving.value = false
  }
}
const onCompactMemory = async () => {
  memLoading.value = true
  try {
    const r = await compactMemory()
    memOverview.value = (r && r.data) || memOverview.value
    message.success('已按上限裁剪')
  } catch (e) {
    message.error(e.message || '裁剪失败')
  } finally {
    memLoading.value = false
  }
}
const onClearMemory = () => {
  Modal.confirm({
    title: '清空全部长期记忆？',
    content: '会删除 MEMORY.md、全部日账与归档，无法恢复。已经开始的问答不受影响，但之后的问答不会再记得这些内容。',
    okText: '清空', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      memClearing.value = true
      try {
        const r = await clearMyMemory()
        memOverview.value = (r && r.data) || memOverview.value
        memEditing.value = false
        message.success('已清空长期记忆')
      } catch (e) {
        message.error(e.message || '清空失败')
      } finally {
        memClearing.value = false
      }
    }
  })
}
const viewDaily = async date => {
  try {
    const r = await getMemoryContent('memory/' + date + '.md')
    memViewTitle.value = '日账 ' + date
    memViewText.value = (r && r.data && r.data.content) || ''
    memViewOpen.value = true
  } catch (e) {
    message.error(e.message || '读取日账失败')
  }
}
const onDeleteDaily = date => {
  Modal.confirm({
    title: '删除 ' + date + ' 的日账？',
    content: '只删除这一天的原始记录，不会回改 MEMORY.md；删除后无法恢复。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteMemoryDaily(date)
        memOverview.value = (r && r.data) || memOverview.value
        message.success('日账已删除')
      } catch (e) {
        message.error(e.message || '删除失败')
      }
    }
  })
}
const downloadMemory = () => {
  const text = memOverview.value.content || ''
  if (!text) return
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'MEMORY.md'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
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
        if (r && r.success !== false) {
          message.success('已停止分享')
          // 同步侧栏行内图标：本页是「全部」的视角，操作对象同样在侧栏列表里，
          // 不同步的话从本页停掉分享，回对话页看到的还是分享中的图标
          markSessionShared(s.sessionId, false)
          await loadShares()
        } else message.error(r?.msg || '停止失败')
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
      // 换新/重开后该会话的分享标记必然为真，同步侧栏行内图标
      markSessionShared(s.sessionId, true)
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
// 记忆同理——自动提炼是在后台往库里加条目的，切回面板必须重拉，否则看到的是进页那一刻的旧快照
watch(current, v => {
  if (v === 'shares') {
    focusSid.value = route.query.sid || ''
    loadShares()
  }
  if (v === 'memory') loadMemory()
})

onMounted(() => {
  load()
  ensureAuth().then(me => { nickForm.value.username = me.username || ''; avatarPreview.value = me.avatar || '' })
  // 深链直达（?panel=xxx）时 watch 不会触发，首拉在这里补齐
  if (current.value === 'shares') loadShares()
  if (current.value === 'memory') loadMemory()
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
/* 昵称输入框：PC 定宽 280（与保存按钮并排）；窄屏改全宽（见下方 768 块） */
.pf-nick-input { width: 280px; }
/* 表单最大宽度：PC 上限避免长行难读；窄屏取消（见下方 768 块） */
.pf-form { max-width: 360px; }
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
/* 批量操作栏（与文档页 / 通知中心 batch-bar 同款）：有勾选才出现，动作收敛在这里 */
.mem-batch {
  display: flex; align-items: center; gap: 6px 10px; flex-wrap: wrap;
  margin-bottom: 10px; padding: 8px 12px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); border-radius: 8px;
  font-size: 12px; color: var(--app-accent);
}
.mem-batch-count { font-weight: 600; }
.mem-danger { color: var(--app-danger); }
.mem-danger:hover:not(:disabled) { background: var(--app-danger-weak); }
/* 记忆列表：每条一张软卡片，内容完整换行（不再单行截断省略） */
.mem-list { display: flex; flex-direction: column; gap: 8px; }
.mem-item {
  display: flex; align-items: flex-start; gap: 8px;
  border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px;
}
.mem-item.sel { border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.mem-main { flex: 1; min-width: 0; }
/* 勾选命中区：撑满「行左边界 → 正文前」整列、整行高（antd wrapper 是 label，@click.stop 只盖内层 16px，
   所以靠负外边距吃掉行内边距与 flex gap 来放大，而不是靠 stop）。
   内层 .ant-checkbox 自带 align-self:center，拉高后会居中下沉 → 改回 flex-start；
   上内边距 12.5px = 行上内边距 10px + 2.5px 首行对齐偏移，勾选框与正文首行齐平 */
.mem-item :deep(.mem-check) { align-self: stretch; align-items: flex-start; padding: 12.5px 8px 2.5px 13px; margin: -10px -8px -10px -13px; }
.mem-item :deep(.mem-check .ant-checkbox) { align-self: flex-start; }
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

/* ---- 我的记忆（P5） ---- */
.mem-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.mem-warn {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-bottom: 12px; padding: 9px 12px; border-radius: 8px;
  background: var(--app-warn-weak); color: var(--app-warn-text);
  border: 1px solid var(--app-warn-border); font-size: 12px; line-height: 1.5;
}
.mem-warn .app-link-btn { color: var(--app-warn-text); text-decoration: underline; }
.mem-md {
  margin: 0; padding: 12px 14px; max-height: 360px; overflow: auto;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px; line-height: 1.65;
  color: var(--app-text); white-space: pre-wrap; word-break: break-word;
}
.mem-edit-area { width: 100%; font-family: var(--app-font-mono, ui-monospace, monospace); font-size: 12px; }
.mem-days { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--app-border); }
.mem-days-head { font-size: 12px; font-weight: 500; color: var(--app-text); margin-bottom: 6px; }
.mem-day-row {
  display: flex; align-items: center; gap: 10px; padding: 7px 0;
  border-bottom: 1px dashed var(--app-border);
}
.mem-day-row:last-child { border-bottom: none; }
.mem-day-date { font-size: 12px; color: var(--app-text2); font-family: var(--app-font-mono, ui-monospace, monospace); }
.mem-day-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; }

/* ==================== 窄屏（手机）适配 ====================
   这一页是手机上的白名单页（改昵称/头像/密码是刚需），窄屏必须真能用：
   左列 Tab 竖排 + 定宽卡片 + 并排表单在 412px 上会把内容挤到破版
   （实测「清除头像」探出视口被裁、「保存」两字压成竖排、emoji 每格 13px 点不中）。
   这里把结构改成移动端设置页的常规形态：顶部横滑 Tab + 全宽卡片 + 换行表单。 */
@media (max-width: 768px) {
  /* 页头大标题与移动顶栏重复（顶栏已随路由显示「个人设置」），隐藏省一截首屏；
     scoped 只作用于本组件，不影响 /help 等同样用 .app-page-head 的页面 */
  .app-page-head { display: none; }

  /* Tab：左列竖排 → 顶部横滑胶囊（竖排会吃掉 1/3 宽度） */
  .pf-body { flex-direction: column; }
  .pf-nav {
    width: auto; flex-direction: row; gap: 6px;
    border-right: none; border-bottom: 1px solid var(--app-border);
    padding: 8px 10px; overflow-x: auto; overflow-y: hidden;
    scrollbar-width: none; -webkit-overflow-scrolling: touch;
  }
  .pf-nav::-webkit-scrollbar { display: none; }
  .pf-nav-item {
    flex: none; white-space: nowrap; min-height: 36px; padding: 8px 14px;
    border: 1px solid var(--app-border); border-radius: 999px; font-size: 13px;
    display: inline-flex; align-items: center; touch-action: manipulation;
  }
  .pf-nav-item.active { border-color: var(--app-accent-border); color: var(--app-accent); }

  /* 内容区与卡片：全宽（640/720 上限在 412px 上只会让右侧留白不齐） */
  .pf-content { padding: 12px 12px calc(24px + var(--sab, 0px)); }
  .pf-card, .mem-card, .shm-card { max-width: none; padding: 14px; }

  /* 表单行换行：昵称输入 + 保存按钮并排会挤压（保存两字被压成竖排） */
  .pf-row { flex-wrap: wrap; }
  .pf-nick-input { width: 100%; }
  .pf-form { max-width: none; }

  /* 头像区纵向居中：72px 头像 + 按钮行 + 说明横向放不下（「清除头像」被挤出视口） */
  .pf-avatar-block { flex-direction: column; align-items: center; gap: 12px; }
  .pf-avatar-side { align-items: center; padding-top: 0; }
  .pf-op-row { flex-wrap: wrap; justify-content: center; }
  .pf-avatar-side .pf-sub-hint { text-align: center; }

  /* emoji 网格 8 列：15 列时每格仅 13px 宽，手指点不中（860 块的 10 列同样偏小） */
  .pf-emoji-pick { grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 4px; }
  .pf-emoji-opt { font-size: 20px; }

  /* 触控热区下限 */
  .pf-nav-item, .pf-emoji-opt, .app-btn { min-height: 34px; }
  /* 记忆页的添加行：输入框占满整行（原 min-width 200 与分类框并排会溢出） */
  .mem-add-row { gap: 8px; }
  .mem-add-input { min-width: 100%; }
  .mem-add-cat { flex: 1; width: auto; }
  /* 分享管理：操作按钮行换行 */
  .shm-acts { flex-wrap: wrap; }
  /* 我的记忆：按钮行换行，统计信息独占一行 */
  .mem-toolbar { gap: 6px; }
  .mem-toolbar .mem-meta { width: 100%; }
  .mem-md { max-height: 300px; }
}
</style>
