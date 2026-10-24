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

        <!-- 个人默认聊天模型（选完即存，没有「保存」这一步；「自动提炼记忆」开关在「我的记忆」面板） -->
        <div v-else-if="current === 'chat'" class="app-card pf-card">
          <h2 class="app-card-title">{{ panelMeta.title }}</h2>
          <p class="pf-hint">{{ panelMeta.hint }}</p>
          <div class="pf-row">
            <!-- 用组件声明的 v-model（而非 v-model:value——那是透传到根 a-select 的偶然生效路径） -->
            <ModelSelect v-model="pref[panelMeta.field]" type="chat"
                         :issue="current === 'chat' ? defaultModelIssue : ''"
                         inherit-label="不设默认" :width="isNarrow ? '100%' : 360"
                         :disabled="loading || prefSaving" @change="saveDefaultModel" />
            <span v-if="prefSaving" class="pf-sub-hint pf-saving-tip">生效中…</span>
          </div>
          <p class="pf-sub-hint">{{ panelMeta.tail }}</p>

          <!-- 回答偏好（原「相关建议条数 / 空态示例问题 / 默认助手 / 停用的技能 / 工具执行上限」五项
               个人覆盖）已整体下线：连同它们在系统设置里的全局那一份一起删干净，不再有两处口径。 -->
        </div>

        <!-- 我的记忆（P5 收口）：框架负责写，这里负责让用户看得见、改得动、删得掉；
             「自动提炼记忆」生成闸也从聊天面板挪到了这里——开关与沉淀内容同面板，边看边管。
             阅读与编辑都按「分类 → 条目」结构化呈现（parse/serialize 见 utils/memoryDoc.js），
             不要求用户懂 Markdown；原文编辑作为高级项保留，兼容手写/异常格式的内容。 -->
        <div v-else-if="current === 'memory'" class="app-card pf-card mem-card">
          <h2 class="app-card-title">
            我的记忆
            <span v-if="memOverview.daily && memOverview.daily.length" class="mem-count">
              {{ memOverview.daily.length }} 天日账
            </span>
          </h2>
          <p class="pf-hint">
            助手会在问答结束后把值得长期记住的信息整理进下面的「长期记忆」，日账则是每天追加的原始记录。
            这里的内容只有你自己能看到；日账是只读的，删除后无法恢复。
          </p>

          <!-- 长期记忆的生成闸（即时生效，不参与任何保存）：只关「问答后自动提炼」这一步，
               已沉淀的记忆在后续问答里照常被提起。关掉后下一轮起才停（正在跑的那一轮不受影响）。 -->
          <div class="mem-auto-row" style="display:flex;align-items:flex-start;gap:12px">
            <div class="mem-auto-text">
              <div class="mem-auto-title">自动提炼记忆</div>
              <p class="pf-sub-hint">关掉后问答不再往长期记忆里添新条目；已经记下的仍会在后续问答中被提起。</p>
            </div>
            <a-switch :checked="memAutoEnabled" :loading="memAutoSaving" @change="toggleMemAuto" />
          </div>

          <a-spin :spinning="memLoading">
            <div v-if="memOverview.over" class="mem-warn">
              当前约 {{ memOverview.tokens }} token，超过硬上限 {{ memOverview.maxTokens }} token；
              超出部分不会进入模型上下文。
              <button class="app-link-btn" @click="onCompactMemory">按上限裁剪</button>
            </div>

            <div class="mem-toolbar">
              <button class="app-btn" :disabled="memEditing || memSaving" @click="startMemEdit">编辑</button>
              <button class="app-btn ghost" :disabled="!memOverview.content" @click="downloadMemory">下载</button>
              <button class="app-btn ghost mem-danger"
                      :disabled="memClearing || (!memOverview.content && !(memOverview.daily && memOverview.daily.length))"
                      @click="onClearMemory">清除全部</button>
              <span class="mem-meta">
                {{ memOverview.size || 0 }} 字符 · 约 {{ memOverview.tokens || 0 }} token
                / 上限 {{ memOverview.maxTokens || 0 }}
              </span>
            </div>

            <!-- 阅读态：按分类结构化呈现（用户不需要懂 Markdown） -->
            <div v-if="!memEditing" class="mem-view">
              <template v-if="memDocView.sections.length">
                <div v-for="sec in memDocView.sections" :key="sec.id" class="mem-sec">
                  <div class="mem-sec-title">{{ sec.title }}</div>
                  <template v-for="r in secRows(sec)" :key="r.e.id">
                    <div v-if="r.kind === 'sub'" class="mem-sub-title">{{ r.e.title }}</div>
                    <div v-else class="mem-line" :class="{ raw: r.e.kind === 'raw', indent: r.indent }">
                      <span v-if="r.e.kind === 'item'" class="mem-dot">•</span>
                      <span class="mem-line-text">{{ r.e.text }}</span>
                    </div>
                  </template>
                </div>
              </template>
              <p v-else class="mem-view-empty">（还没有沉淀任何记忆。问答几轮后，助手会把值得记住的内容写到这里。）</p>
            </div>

            <!-- 编辑态：结构化编辑（默认）+ 原文编辑（高级）。改动只在本地，保存后才落盘 -->
            <template v-if="memEditing">
              <p class="pf-sub-hint mem-edit-hint">
                按「分类 → 条目」编辑：点条目文字即可修改；删错了也不要紧，点「取消」可放弃本次全部改动，保存后才会生效。
              </p>
              <div v-if="!memRawMode" class="mem-edit">
                <div v-for="sec in memDoc.sections" :key="sec.id" class="mem-sec">
                  <div class="mem-sec-head">
                    <template v-if="sec._renaming">
                      <a-input v-model:value="sec._draft" size="small" class="mem-sec-input"
                               placeholder="分类名，如 用户偏好" @pressEnter="commitSecRename(sec)" />
                      <button class="app-link-btn" @click="commitSecRename(sec)">确定</button>
                      <button class="app-link-btn" @click="sec._renaming = false">取消</button>
                    </template>
                    <template v-else>
                      <span class="mem-sec-title" title="点击重命名" @click="startSecRename(sec)">{{ sec.title || '未命名分类' }}</span>
                      <div class="mem-sec-acts">
                        <button class="app-link-btn" @click="addEntry(sec)">添加一条</button>
                        <button class="app-link-btn danger" @click="delSection(sec)">删除分类</button>
                      </div>
                    </template>
                  </div>
                  <template v-for="r in secRows(sec)" :key="r.e.id">
                    <div v-if="r.kind === 'sub'" class="mem-sub-head">
                      <template v-if="r.e._renaming">
                        <a-input v-model:value="r.e._draft" size="small" class="mem-sec-input"
                                 placeholder="小节名" @pressEnter="commitSubRename(r.e)" />
                        <button class="app-link-btn" @click="commitSubRename(r.e)">确定</button>
                        <button class="app-link-btn" @click="r.e._renaming = false">取消</button>
                      </template>
                      <template v-else>
                        <span class="mem-sub-title" title="点击重命名" @click="startSubRename(r.e)">{{ r.e.title || '未命名小节' }}</span>
                        <div class="mem-sec-acts">
                          <button class="app-link-btn" @click="addSubItem(r.e)">添加一条</button>
                          <button class="app-link-btn danger" @click="delSub(r.e)">删除小节</button>
                        </div>
                      </template>
                    </div>
                    <div v-else class="mem-line" :class="{ editing: memEditId === r.e.id, raw: r.e.kind === 'raw', indent: r.indent }">
                      <span v-if="r.e.kind === 'item'" class="mem-dot">•</span>
                      <template v-if="memEditId === r.e.id">
                        <a-textarea v-model:value="memEditDraft" class="mem-line-input"
                                    :auto-size="{ minRows: 1, maxRows: 8 }" />
                        <div class="mem-line-acts">
                          <button class="app-link-btn" @click="commitEntryEdit">确定</button>
                          <button class="app-link-btn" @click="memEditId = ''">取消</button>
                        </div>
                      </template>
                      <template v-else>
                        <span class="mem-line-text" @click="startEntryEdit(r.e)">{{ r.e.text }}</span>
                        <div class="mem-line-acts">
                          <button class="app-link-btn" @click="startEntryEdit(r.e)">编辑</button>
                          <button class="app-link-btn danger" @click="removeEntryById(r.e.id)">删除</button>
                        </div>
                      </template>
                    </div>
                  </template>
                </div>
                <button class="app-btn ghost mem-add-sec" @click="addSection">+ 添加分类</button>
              </div>
              <a-textarea v-else v-model:value="memEditText" :rows="16"
                          show-count class="mem-edit-area" placeholder="MEMORY.md 内容（Markdown）" />
              <div class="mem-edit-foot">
                <span class="mem-edit-count" :class="{ over: memEditOver }">
                  {{ memEditLen }} / {{ memOverview.maxChars || 0 }} 字符<template v-if="memEditOver">，已超上限</template>
                </span>
                <button class="app-link-btn" @click="toggleMemRaw">
                  {{ memRawMode ? '切换为可视化编辑' : '切换为原文编辑' }}
                </button>
                <button class="app-btn" :disabled="memSaving || memEditOver" @click="saveMemory">
                  {{ memSaving ? '保存中…' : '保存' }}
                </button>
                <button class="app-btn ghost" :disabled="memSaving" @click="cancelMemEdit">取消</button>
              </div>
            </template>

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
import { parseMemoryDoc, serializeMemoryDoc, nextEntryId } from '../utils/memoryDoc'
import { shareUrlOf } from './shareSession'
import { markSessionShared, resetSessionStore } from './store'
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
  { key: 'chat', label: '默认模型' },
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
    field: 'defaultModel', title: '默认模型',
    hint: '会话未选定模型时，用这个模型回答；会话已选定模型时沿用会话的选择。选择后立即生效，没有单独的保存按钮。',
    tail: '清空（选「不设默认」）后，每次对话需手动选择模型。对话上传的图片能否被理解，取决于模型是否支持读图（不支持就换个能读图的模型重试）；入库文档的图片描述用知识库绑定的视觉模型。'
  }
}
const panelMeta = computed(() => PANELS[current.value] || PANELS.chat)

// 个人默认（选完即存，setUserPreference 为部分更新：缺省字段不动；向量/重排/视觉无个人默认，归知识库绑定/检索设置/模型能力位）
const loading = ref(false)
const prefSaving = ref(false)
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

const saveDefaultModel = async v => {
  prefSaving.value = true
  try {
    await setUserPreference({ defaultModel: v || '' })
    message.success(v ? '已设为默认模型' : '已清除默认模型')
  } catch (e) {
    message.error(e.message || '设置失败')
  } finally {
    // 成败都回读服务端：成功要刷新「这个模型还能不能用」的判定，失败要把选择器退回真正生效的那一个
    await load()
    prefSaving.value = false
    refreshSetupGuide(true)  // 个人默认模型变了 → 引导 tag/卡片立即对账
  }
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
      resetSessionStore()
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
// 阅读与编辑都不直接暴露 Markdown：overview.content 经 utils/memoryDoc 解析成「分类 → 条目」，
// 保存时再序列化回同一格式（round-trip 保真，见该文件的注释）；原文编辑仅作高级项保留。
const memLoading = ref(false)
const memSaving = ref(false)
const memClearing = ref(false)
// 阅读态：直接从 overview.content 解析（内容被后台任务更新时这里跟着变）
const memDocView = computed(() => parseMemoryDoc(memOverview.value.content || ''))
const memEditing = ref(false)
// 原文编辑（高级项）：内容没有 ## 小节结构时直接进原文模式，结构化模式无从下手
const memRawMode = ref(false)
const memDoc = ref({ header: '', sections: [] })
const memEditText = ref('')
const memEditId = ref('')   // 正在编辑的条目 id（含小节内条目）
const memEditDraft = ref('')
const memEditLen = computed(() =>
  memRawMode.value ? (memEditText.value || '').length : serializeMemoryDoc(memDoc.value).length)
const memEditOver = computed(() => memEditLen.value > (memOverview.value.maxChars || 0))
// 进入条目编辑时把焦点放进输入框：点文字即改，不用再点一次文本框
watch(memEditId, async id => {
  if (!id) return
  await nextTick()
  const el = document.querySelector('.mem-line.editing .mem-line-input textarea')
  if (el && el.focus) el.focus()
})
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

// 把小节的 entries 摊平为渲染行：子题头 + 其条目（带缩进标记），阅读态与编辑态共用
const secRows = sec => {
  const rows = []
  for (const e of sec.entries || []) {
    if (e.kind === 'sub') {
      rows.push({ kind: 'sub', e })
      for (const it of e.items || []) rows.push({ kind: 'item', e: it, indent: true })
    } else {
      rows.push({ kind: 'item', e, indent: false })
    }
  }
  return rows
}

const startMemEdit = () => {
  const content = memOverview.value.content || ''
  memDoc.value = parseMemoryDoc(content)
  memEditId.value = ''
  if (content.trim() && !memDoc.value.sections.length) {
    // 没有 ## 小节结构（手写/异常内容）：结构化模式无从下手，直接进原文编辑
    memRawMode.value = true
    memEditText.value = content
  } else {
    memRawMode.value = false
  }
  memEditing.value = true
}
const cancelMemEdit = () => {
  memEditing.value = false
  memRawMode.value = false
  memEditText.value = ''
  memEditId.value = ''
}
// 原文 ↔ 可视化：双向切换时互转内容，转不过去（没有小节结构）就留在原地并提示
const toggleMemRaw = () => {
  if (memRawMode.value) {
    const doc = parseMemoryDoc(memEditText.value)
    if (memEditText.value.trim() && !doc.sections.length) {
      message.warning('当前内容没有可分的小节，已保留在原文编辑')
      return
    }
    memDoc.value = doc
    memRawMode.value = false
  } else {
    memEditText.value = serializeMemoryDoc(memDoc.value)
    memRawMode.value = true
  }
}
const saveMemory = async () => {
  const text = memRawMode.value ? memEditText.value : serializeMemoryDoc(memDoc.value)
  const maxChars = memOverview.value.maxChars || 0
  if (maxChars && text.length > maxChars) {
    message.error('内容超过上限（' + text.length + ' / ' + maxChars + ' 字符），请精简后再保存')
    return
  }
  memSaving.value = true
  try {
    const r = await saveMemoryMd(text)
    memOverview.value = (r && r.data) || memOverview.value
    memEditing.value = false
    message.success('记忆已保存')
  } catch (e) {
    message.error(e.message || '保存失败')
  } finally {
    memSaving.value = false
  }
}

// 条目行定位：小节内的条目挂在 sub.items 上，其余挂在 sec.entries 上
const findEntryOwner = id => {
  for (const sec of memDoc.value.sections) {
    for (const e of sec.entries) {
      if (e.id === id) return { list: sec.entries, entry: e }
      if (e.kind === 'sub') {
        const it = (e.items || []).find(x => x.id === id)
        if (it) return { list: e.items, entry: it }
      }
    }
  }
  return null
}
const startEntryEdit = entry => { memEditId.value = entry.id; memEditDraft.value = entry.text }
const commitEntryEdit = () => {
  const o = findEntryOwner(memEditId.value)
  if (o) {
    const t = (memEditDraft.value || '').replace(/[ \t]+$/g, '')
    if (t.trim()) o.entry.text = t
    else o.list.splice(o.list.indexOf(o.entry), 1) // 清空文本 = 删除该条
  }
  memEditId.value = ''
}
const removeEntryById = id => {
  const o = findEntryOwner(id)
  if (o) o.list.splice(o.list.indexOf(o.entry), 1)
}
const addEntry = sec => {
  // 插到第一个子题之前：直接条目是小节的开头部分，加到尾部会跑到子题底下
  const e = { id: nextEntryId(), kind: 'item', text: '' }
  const idx = (sec.entries || []).findIndex(x => x.kind === 'sub')
  if (idx >= 0) sec.entries.splice(idx, 0, e); else sec.entries.push(e)
  startEntryEdit(e)
}
const addSubItem = sub => {
  const it = { id: nextEntryId(), kind: 'item', text: '' }
  if (!sub.items) sub.items = []
  sub.items.push(it)
  startEntryEdit(it)
}

// 分类 / 小节：重命名（点标题）、新增（底部按钮）、删除（有条目时确认）
const startSecRename = sec => { sec._renaming = true; sec._draft = sec.title }
const commitSecRename = sec => { sec.title = (sec._draft || '').trim(); sec._renaming = false }
const startSubRename = sub => { sub._renaming = true; sub._draft = sub.title }
const commitSubRename = sub => { sub.title = (sub._draft || '').trim(); sub._renaming = false }
const addSection = () => {
  memDoc.value.sections.push({ id: nextEntryId(), title: '', entries: [], _renaming: true, _draft: '' })
}
const delSection = sec => {
  const drop = () => {
    const i = memDoc.value.sections.indexOf(sec)
    if (i >= 0) memDoc.value.sections.splice(i, 1)
  }
  if ((sec.entries || []).length) {
    Modal.confirm({
      title: '删除分类「' + (sec.title || '未命名分类') + '」？',
      content: '分类下的所有条目会一并删除。取消编辑可放弃本次全部改动。',
      okText: '删除', okType: 'danger', cancelText: '取消', onOk: drop
    })
  } else drop()
}
const delSub = sub => {
  const sec = memDoc.value.sections.find(s => (s.entries || []).includes(sub))
  const drop = () => {
    if (!sec) return
    const i = sec.entries.indexOf(sub)
    if (i >= 0) sec.entries.splice(i, 1)
  }
  if ((sub.items || []).length) {
    Modal.confirm({
      title: '删除小节「' + (sub.title || '未命名小节') + '」？',
      content: '小节下的所有条目会一并删除。取消编辑可放弃本次全部改动。',
      okText: '删除', okType: 'danger', cancelText: '取消', onOk: drop
    })
  } else drop()
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
    content: '会删除下面整理的全部记忆和所有日账，无法恢复。已经开始的问答不受影响，但之后的问答不会再记得这些内容。',
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
// 「自动提炼记忆」开关也一起重拉：它过去只在进页时读过一次，值在别处变过（另一个页签、探针改完还原、
// 请求超时但其实已落库）就永远显示旧值，看着像「它自己关了」
watch(current, v => {
  if (v === 'shares') {
    focusSid.value = route.query.sid || ''
    loadShares()
  }
  if (v === 'memory') { loadMemory(); load() }
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
/* 即时保存的进行中提示：选择器在 PUT 期间只是变灰，不写一句看不出「在等什么」 */
.pf-saving-tip { margin: 0; white-space: nowrap; }
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
/* 危险动作（清除全部 / 行内删除） */
.mem-danger { color: var(--app-danger); }
.mem-danger:hover:not(:disabled) { background: var(--app-danger-weak); }

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
.mem-meta { flex: none; font-size: 11px; color: var(--app-text3); }
/* 编辑态：文本域 + 底部按钮行（计数靠左、按钮靠右，互不重叠） */
.mem-edit-foot { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-top: 8px; }
.mem-edit-count { margin-right: auto; font-size: 11px; color: var(--app-text3); }
/* 空态 */
.mem-empty { text-align: center; padding: 26px 0 18px; }
.mem-empty-ico { font-size: 26px; line-height: 1; }
.mem-empty-title { font-size: 13px; color: var(--app-text2); margin: 8px 0 2px; }
.mem-empty .pf-sub-hint { margin: 0; }
/* 自动提炼开关行：左说明右开关（位于「我的记忆」面板顶部） */
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
/* 阅读态：结构化呈现（分类 + 条目，不要求用户懂 Markdown） */
.mem-view {
  padding: 12px 14px; max-height: 480px; overflow: auto;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
}
.mem-view-empty { margin: 0; font-size: 12px; color: var(--app-text3); }
.mem-sec + .mem-sec { margin-top: 14px; }
.mem-sec-title { font-size: 13px; font-weight: 600; color: var(--app-text); }
.mem-sub-title { margin: 10px 0 2px; font-size: 12px; font-weight: 500; color: var(--app-text2); }
.mem-line { display: flex; align-items: flex-start; gap: 8px; padding: 3px 0; font-size: 12px; line-height: 1.65; color: var(--app-text); }
.mem-line.indent { padding-left: 16px; }
.mem-dot { flex: none; color: var(--app-text3); }
.mem-line-text { min-width: 0; white-space: pre-wrap; word-break: break-word; }
/* 原文段（非 '- ' 行，如裁剪说明）：等宽 + 弱化，表明不是普通条目 */
.mem-line.raw .mem-line-text { font-family: var(--app-font-mono, ui-monospace, monospace); color: var(--app-text2); }
/* 编辑态：结构化编辑 */
.mem-edit { padding: 4px 14px 12px; border: 1px solid var(--app-border); border-radius: 8px; }
.mem-edit .mem-sec { margin-top: 12px; }
.mem-edit .mem-sec:first-child { margin-top: 8px; }
.mem-edit-hint { margin: 0 0 8px; }
.mem-sec-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mem-sec-head .mem-sec-title { cursor: pointer; border-radius: 4px; }
.mem-sec-head .mem-sec-title:hover { color: var(--app-accent); }
.mem-sec-input { max-width: 280px; }
.mem-sec-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; }
.mem-edit .mem-line { padding: 2px 0; }
.mem-edit .mem-line-text { cursor: pointer; border-radius: 4px; }
.mem-edit .mem-line-text:hover { background: var(--app-accent-weak); }
/* 行内动作：默认隐藏，hover 到该行或正在编辑时才出现（触屏无 hover，见 768 块常显） */
.mem-line-acts { margin-left: auto; display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; opacity: 0; transition: opacity .15s; }
.mem-line:hover .mem-line-acts, .mem-line.editing .mem-line-acts { opacity: 1; }
.mem-line-input { flex: 1; min-width: 0; }
.mem-add-sec { margin-top: 12px; }
.mem-edit-count.over { color: var(--app-danger); }
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
  /* 分享管理：一行两栏（左正文 + 右操作）在手机上必然破版——操作区是 flex:none
     且按内容定宽（五个链接钮约 300px），336px 的行里正文只剩 2px，实测标题一字一行
     竖排、链接框只剩一个「h」。窄屏改成纵向：正文占满整行，操作另起一行左对齐。 */
  .shm-item { flex-direction: column; align-items: stretch; gap: 8px; }
  .shm-acts { max-width: none; justify-content: flex-start; row-gap: 2px; column-gap: 14px;
    border-top: 1px dashed var(--app-border); padding-top: 6px; }
  /* 链接式小按钮原本 18px 高，手指点不准（本页其它可点元素已统一 34px 下限） */
  .shm-acts .app-link-btn { min-height: 34px; padding: 6px 2px; }
  /* 我的记忆：按钮行换行，统计信息独占一行 */
  .mem-toolbar { gap: 6px; }
  .mem-toolbar .mem-meta { width: 100%; }
  .mem-md { max-height: 300px; }
  /* 触屏没有 hover：结构化编辑的行内「编辑/删除」常显 */
  .mem-line-acts { opacity: 1; }
}
</style>
