<template>
  <!-- 会话列表（移动壳的「抽屉」）：底部 sheet 形态。
       数据与 PC 侧栏同一份 store（sessionStore）+ 同一组接口（pin/favorite/rename/delete），
       分组口径与 AppLayout 一致（置顶/今天/7 天内/更早），但用扁平列表 + 行内操作条呈现。 -->
  <BottomSheet :open="open" title="会话" :subtitle="total ? total + ' 个会话' : ''" max-height="82dvh" @close="$emit('close')">
    <div class="ss">
      <div class="ss-search">
        <search-outlined class="ss-search-ic" />
        <input
          v-model="kw"
          class="ss-search-input"
          type="search"
          placeholder="搜索会话…"
          enterkeyhint="search"
          @input="onInput"
        />
        <button v-if="kw" class="ss-search-clear" type="button" title="清除" @click="clearKw">×</button>
      </div>

      <button class="ss-new" type="button" @click="$emit('new-chat')">
        <plus-outlined /> 新建对话
      </button>

      <div v-if="sessionStore.loading && !sessionStore.list.length" class="ss-empty">加载中…</div>
      <div v-else-if="!groups.length" class="ss-empty">{{ kw ? '没有匹配的会话' : '还没有会话' }}</div>

      <template v-for="g in groups" :key="g.label">
        <div class="ss-group">{{ g.label }}<span class="ss-group-n">{{ g.items.length }}</span></div>
        <div v-for="s in g.items" :key="s.id" class="ss-row" :class="{ cur: s.id === currentId }">
          <button class="ss-main" type="button" @click="$emit('select', s.id)">
            <span class="ss-title">{{ s.title || '新对话' }}</span>
            <span class="ss-meta">
              <star-filled v-if="s.isFavorite === 1" class="ss-fav" />
              {{ sessTimeText(s) }}<template v-if="s.messageCount"> · {{ s.messageCount }} 条</template>
            </span>
          </button>
          <button class="ss-more" type="button" title="会话操作" @click.stop="openMenu(s)"><more-outlined /></button>
        </div>
      </template>

      <button v-if="sessionStore.hasMore" class="ss-more-load" type="button" :disabled="sessionStore.loadingMore" @click="loadMoreSessions()">
        {{ sessionStore.loadingMore ? '加载中…' : '查看更多' }}
      </button>
    </div>

    <!-- 壳外入口（对称 PC 侧栏 foot）：手机上聊天以外的页面只从这里进。
         不塞进顶栏：个人设置/帮助/主题/退出全是低频动作，挤在顶栏只会稀释「新建/查找」的位置；
         通知是高频动作，单列在顶栏铃铛（带未读角标）。 -->
    <div v-if="!menuFor" class="ss-foot">
      <button class="ss-foot-btn" type="button" @click="$emit('profile')"><user-outlined />个人设置</button>
      <button class="ss-foot-btn" type="button" @click="$emit('help')"><question-circle-outlined />帮助中心</button>
      <button class="ss-foot-btn" type="button" @click="$emit('artifacts')"><file-text-outlined />我的产物</button>
      <button class="ss-foot-btn" type="button" @click="onToggleTheme">
        <bulb-outlined />{{ themeState === 'dark' ? '切换亮色主题' : '切换暗色主题' }}
      </button>
      <button class="ss-foot-btn danger" type="button" @click="doLogout"><logout-outlined />退出登录</button>
      <!-- 安装到桌面（PWA）：Android 走 beforeinstallprompt，iOS 走图文指引（见 h5/pwa.js）；
           应用内浏览器（微信等）与已安装时不显示 -->
      <button v-if="installVisible" class="ss-foot-btn install" type="button" @click="onInstall">
        <mobile-outlined />安装到桌面
      </button>
    </div>

    <!-- 行操作（底部二级 sheet 太重，这里用行内操作条） -->
    <div v-if="menuFor" class="ss-menu">
      <div class="ss-menu-title">{{ menuFor.title || '新对话' }}</div>
      <div class="ss-menu-actions">
        <button class="ss-menu-btn" type="button" @click="doPin"><pushpin-outlined />{{ menuFor.isPinned === 1 ? '取消置顶' : '置顶' }}</button>
        <button class="ss-menu-btn" type="button" @click="doFav"><star-outlined />{{ menuFor.isFavorite === 1 ? '取消收藏' : '收藏' }}</button>
        <button class="ss-menu-btn" type="button" @click="startRename"><edit-outlined />重命名</button>
        <button class="ss-menu-btn" type="button" @click="doExport"><download-outlined />导出 Markdown</button>
        <button class="ss-menu-btn danger" type="button" @click="doDelete"><delete-outlined />删除</button>
      </div>
      <div v-if="renaming" class="ss-rename">
        <input v-model="renameText" class="ss-rename-input" type="text" placeholder="会话名称" @keydown.enter="confirmRename" />
        <button class="ss-menu-btn" type="button" @click="confirmRename">保存</button>
        <button class="ss-menu-btn" type="button" @click="renaming = false">取消</button>
      </div>
      <button class="ss-menu-close" type="button" @click="menuFor = null">收起</button>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, h, inject, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined, PlusOutlined, MoreOutlined, PushpinOutlined, StarOutlined, StarFilled, EditOutlined, DeleteOutlined, DownloadOutlined,
         UserOutlined, QuestionCircleOutlined, BulbOutlined, LogoutOutlined, MobileOutlined, FileTextOutlined } from '@ant-design/icons-vue'
import { installable, installEntryVisible, promptInstall } from './pwa'
import BottomSheet from './BottomSheet.vue'
import { sessionStore, loadSessions, loadMoreSessions } from '../views/store'
import { pinSession, favoriteSession, renameSessionApi, logoutApi } from '../api'
import { clearAuth } from '../utils/auth'
import { themeState, toggleTheme } from '../utils/theme'
import { exportSessionMarkdown } from '../views/exportMd'

// 引擎实例（provide 自移动壳）：删除会话复用其 handleDeleteSession（含当前会话被删后的落点）
const engine = inject('wqChat')

const props = defineProps({
  open: { type: Boolean, default: false },
  currentId: { type: String, default: '' }
})
const emit = defineEmits(['close', 'select', 'new-chat', 'changed', 'profile', 'help', 'artifacts'])

// 壳外入口：个人设置/帮助是页面跳转（emit 给移动壳，与 select/new-chat 同路径），
// 主题与退出是本 sheet 自持的动作（与会话行操作同一层级）
const router = useRouter()
const onToggleTheme = () => toggleTheme()

// 安装到桌面：能弹浏览器安装提示就弹；iOS 没有该能力，给「分享 → 添加到主屏幕」图文步骤
// （computed 而非一次性求值：beforeinstallprompt 在挂载后到达时入口要能自己出现）
const installVisible = computed(() => installEntryVisible())
const onInstall = async () => {
  if (installable.value) {
    const fired = await promptInstall()
    if (fired) message.success('已弹出安装提示，按提示完成即可')
    return
  }
  Modal.info({
    title: '添加到主屏幕',
    okText: '知道了',
    content: h('div', { style: 'line-height:1.9' }, [
      h('div', 'Safari 不支持一键安装，按下面三步添加：'),
      h('div', '① 点底部「分享」按钮'),
      h('div', '② 选「添加到主屏幕」'),
      h('div', '③ 点右上角「添加」')
    ])
  })
}
// 退出登录：令牌无状态，清本地令牌并回登录页（与 PC 侧栏同口径）。
// 移动端多一步确认：这个入口贴着会话列表，误触后要重新输密码，代价比多点一下高。
const doLogout = () => {
  Modal.confirm({
    title: '退出登录',
    content: '退出后需要重新登录才能继续使用。',
    okText: '退出', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try { await logoutApi() } catch (e) { /* 忽略：服务端不维护会话 */ }
      clearAuth()
      message.success('已退出登录')
      router.replace('/login')
    }
  })
}

const kw = ref('')
let searchTimer = null
const onInput = () => {
  clearTimeout(searchTimer)
  // 300ms 防抖（与 PC 侧栏同口径）：移动端输入法组合期也会连续触发 input
  searchTimer = setTimeout(() => { loadSessions(kw.value).catch(() => {}) }, 300)
}
const clearKw = () => { kw.value = ''; loadSessions('').catch(() => {}) }
watch(() => props.open, v => { if (v) { kw.value = sessionStore.keyword || '' } })

// 时间字段稳健解析 + 分桶（与 AppLayout 的 sessTime/groupedSessions 同口径）
const sessTime = v => {
  if (!v) return null
  if (typeof v === 'number') return new Date(v)
  if (typeof v === 'string') { const d = new Date(v); return isNaN(d.getTime()) ? null : d }
  if (Array.isArray(v)) return new Date(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, v[5] || 0)
  if (typeof v === 'object' && v.year) return new Date(v.year, (v.monthValue || 1) - 1, v.dayOfMonth || 1, v.hour || 0, v.minute || 0, v.second || 0)
  return null
}
const relText = d => {
  if (!d) return ''
  const now = new Date()
  const t0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const t = d.getTime()
  if (t >= t0) return '今天'
  if (t >= t0 - 86400000) return '昨天'
  if (t >= t0 - 6 * 86400000) return '本周'
  return `${d.getMonth() + 1}/${d.getDate()}`
}
const sessTimeText = s => relText(sessTime(s.updateTime))

const total = computed(() => sessionStore.total || sessionStore.list.length)
const groups = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const weekStart = startOfToday - 6 * 86400000
  const out = [
    { label: '置顶', items: [] }, { label: '今天', items: [] }, { label: '7 天内', items: [] }, { label: '更早', items: [] }
  ]
  for (const s of sessionStore.list) {
    if (s.isPinned === 1) { out[0].items.push(s); continue }
    const d = sessTime(s.updateTime)
    const t = d ? d.getTime() : 0
    if (t >= startOfToday) out[1].items.push(s)
    else if (t >= weekStart) out[2].items.push(s)
    else out[3].items.push(s)
  }
  return out.filter(g => g.items.length)
})

const menuFor = ref(null)
const renaming = ref(false)
const renameText = ref('')
const openMenu = s => { menuFor.value = s; renaming.value = false; renameText.value = s.title || '' }
const doPin = async () => {
  try { await pinSession(menuFor.value.id, menuFor.value.isPinned !== 1); await loadSessions(); emit('changed') } catch (e) { message.error(e.message || '操作失败') }
}
const doFav = async () => {
  try { await favoriteSession(menuFor.value.id, menuFor.value.isFavorite !== 1); await loadSessions(); emit('changed') } catch (e) { message.error(e.message || '操作失败') }
}
/** 导出 Markdown（与 PC 侧栏会话菜单同口径；无需先打开会话）。
 *  这里只给「下载」一条路：存/复制双通道的主场在「状态与来源」sheet（已打开的会话），
 *  列表这个入口属于顺手补充，再套一层二选一反而比直接下载更绕。 */
const doExport = () => {
  const s = menuFor.value
  if (s) exportSessionMarkdown(s.id, s.title || 'AI对话')
  menuFor.value = null
}
const confirmRename = async () => {
  const t = renameText.value.trim()
  if (!t) { message.warning('名称不能为空'); return }
  try {
    await renameSessionApi(menuFor.value.id, t)
    renaming.value = false
    await loadSessions()
    emit('changed')
  } catch (e) { message.error(e.message || '重命名失败') }
}
const doDelete = () => {
  const s = menuFor.value
  // 用 Modal.confirm 而非 a-popconfirm：移动端浮层点击在 iab/触屏上更可靠（且本页无 hover 语义）
  Modal.confirm({
    title: '删除会话',
    content: `确定删除「${s.title || '新对话'}」？删除后不可恢复。`,
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      menuFor.value = null
      // 走引擎的删除（当前会话被删时它会自动落到最近会话/新建并同步 URL，与 PC 侧栏同一路径）
      await engine.handleDeleteSession(s.id).catch(() => {})
      emit('changed', s.id)
    }
  })
}
</script>

<style scoped>
.ss { display: flex; flex-direction: column; gap: 6px; }
.ss-search { display: flex; align-items: center; gap: 8px; background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 10px; padding: 0 10px; }
.ss-search-ic { color: var(--app-text3); flex: none; }
.ss-search-input { flex: 1; min-width: 0; border: none; background: transparent; font-size: 16px; padding: 10px 0; color: var(--app-text); outline: none; }
.ss-search-clear { width: 30px; height: 30px; border: none; background: transparent; color: var(--app-text3); font-size: 18px; touch-action: manipulation; }
.ss-new {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  min-height: 42px; border: 1px dashed var(--app-border-strong); border-radius: 10px;
  background: transparent; color: var(--app-accent); font-size: 14px; touch-action: manipulation;
}
.ss-group { font-size: 12px; color: var(--app-text3); padding: 10px 2px 2px; display: flex; gap: 6px; align-items: center; }
.ss-group-n { opacity: .7; }
.ss-row { display: flex; align-items: stretch; gap: 2px; border-radius: 10px; }
.ss-row.cur { background: var(--app-accent-weak); }
.ss-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; align-items: flex-start; padding: 10px; border: none; background: transparent; text-align: left; touch-action: manipulation; }
.ss-title { font-size: 14px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.ss-meta { font-size: 12px; color: var(--app-text3); display: flex; align-items: center; gap: 4px; }
.ss-fav { color: var(--app-warn); font-size: 11px; }
.ss-more { width: 40px; border: none; background: transparent; color: var(--app-text3); font-size: 16px; touch-action: manipulation; }
.ss-empty { padding: 24px 0; text-align: center; color: var(--app-text3); font-size: 13px; }
.ss-more-load { min-height: 40px; border: none; background: transparent; color: var(--app-accent); font-size: 13px; touch-action: manipulation; }

/* 壳外入口：sticky 贴底——会话列表很长时也不必滚到底才能退出登录（PC 侧栏 foot 同理） */
.ss-foot {
  position: sticky; bottom: 0; margin-top: 12px; padding-top: 10px;
  background: var(--app-panel); border-top: 1px solid var(--app-border);
  display: grid; grid-template-columns: 1fr 1fr; gap: 8px;
}
.ss-foot-btn {
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  min-height: 44px; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel); color: var(--app-text2); font-size: 13px; touch-action: manipulation;
}
.ss-foot-btn:active { background: var(--app-accent-weak); color: var(--app-accent); }
/* 危险动作独占整行：五个入口时它落单，占左列会与上面的成对按钮不对齐 */
.ss-foot-btn.danger { grid-column: 1 / -1; color: var(--app-danger); border-color: var(--app-danger-border); }
/* 安装入口占整行：它不是与应用内动作并列的一项，而是"把应用装到桌面"这条独立路径 */
.ss-foot-btn.install { grid-column: 1 / -1; color: var(--app-accent); border-color: var(--app-accent-border); }
.ss-foot-btn.danger:active { background: var(--app-danger-weak); color: var(--app-danger); }

.ss-menu { position: sticky; bottom: 0; margin-top: 10px; background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 12px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; box-shadow: var(--app-shadow); }
.ss-menu-title { font-size: 13px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ss-menu-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.ss-menu-btn { display: inline-flex; align-items: center; gap: 5px; min-height: 38px; padding: 0 12px; border: 1px solid var(--app-border); border-radius: 9px; background: var(--app-panel); color: var(--app-text2); font-size: 13px; touch-action: manipulation; }
.ss-menu-btn.danger { color: var(--app-danger); border-color: var(--app-danger-border); }
.ss-rename { display: flex; gap: 8px; align-items: center; }
.ss-rename-input { flex: 1; min-width: 0; border: 1px solid var(--app-border); border-radius: 8px; padding: 9px 10px; font-size: 16px; background: var(--app-panel); color: var(--app-text); }
.ss-menu-close { border: none; background: transparent; color: var(--app-text3); font-size: 12px; min-height: 30px; touch-action: manipulation; }
</style>
