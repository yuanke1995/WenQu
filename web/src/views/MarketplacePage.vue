<template>
  <!-- 市场（技能市场 v1）：发现 → 一键安装；自己的技能可上架。
       核心语义「安装即复制」：装完就是自己的独立技能，作者再改版/下架都不影响已装副本。
       页面骨架对齐成员/权限管理页：页头（说明 + 搜索 + 上架入口）+ 页签条 + 卡片网格 + 详情弹窗。 -->
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">市场</h1>
      <span class="head-hint-plain" title="安装即复制：装完就是自己的独立技能，与市场条目再无关联">
        装同事的好技能，或把自己的好技能分享出去
      </span>
      <div class="head-r">
        <a-input v-model:value="keyword" class="head-search" size="small" allow-clear
                 placeholder="搜索名称/简介/标签" @press-enter="load" @change="onKeywordClear">
          <template #prefix><search-outlined class="head-search-ic" /></template>
        </a-input>
        <a-tooltip title="刷新列表">
          <button class="app-icon-btn" aria-label="刷新市场列表" :disabled="loading" @click="load"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn" @click="publishOpen = true"><cloud-upload-outlined /> 上架我的技能</button>
      </div>
    </div>

    <!-- 页签形态对齐成员/权限管理页：a-tabs 只当导航条，内容在下方按 tab 渲染。
         计数由后端按同一筛选条件给出（两个页签各自可见，不必点进去才知道有没有东西）；
         页签状态落 URL(?tab=mine)，刷新/回退/分享可还原 -->
    <a-tabs v-model:active-key="tab" class="mk-tabs">
      <a-tab-pane key="all">
        <template #tab><span class="mk-tab">全部<i v-if="counts" class="mk-badge">{{ counts.all }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="mine">
        <template #tab><span class="mk-tab">我的上架<i v-if="counts" class="mk-badge">{{ counts.mine }}</i></span></template>
      </a-tab-pane>
    </a-tabs>

    <div class="app-page-body">
      <a-spin v-if="loading && !items.length" style="display:block;margin:48px auto" />

      <div v-else-if="!items.length" class="app-card key-empty">
        <div class="key-empty-title">{{ tab === 'mine' ? '你还没有上架过物品' : (keyword ? '没有匹配的物品' : '市场还是空的') }}</div>
        <div class="key-empty-desc">
          {{ tab === 'mine'
            ? '把自己的技能上架，同事就能一键安装。技能里固化的做法（步骤、格式、禁忌）往往正是别人缺的那块。'
            : (keyword ? '没有名称、简介或标签包含「' + keyword + '」的物品。' : '第一个上架的人就是你：把「这类问题该怎么做」固化成技能分享出来。') }}
        </div>
        <div class="key-empty-ops">
          <button class="app-btn" @click="publishOpen = true"><cloud-upload-outlined /> 上架我的技能</button>
          <button v-if="keyword" class="app-btn ghost small" @click="keyword = ''; load()">清除搜索</button>
        </div>
      </div>

      <div v-else class="mk-grid">
        <div v-for="it in items" :key="it.id" class="app-card mk-card">
          <div class="mk-card-head">
            <span class="mk-card-name" :title="it.name">{{ it.name }}</span>
            <span class="app-pill key-tag" :class="it.itemType === 'skill' ? 'type' : ''">{{ typeLabel(it.itemType) }}</span>
            <span v-if="it.mine && !it.online" class="app-pill warn key-tag">已下架</span>
            <span v-if="it.hasUpdate" class="app-pill warn key-tag">有更新</span>
            <span v-else-if="it.installed" class="app-pill ok key-tag">已安装</span>
          </div>
          <div class="mk-card-desc" :title="it.summary || ''">{{ it.summary || '（无简介）' }}</div>
          <div v-if="it.tags && it.tags.length" class="mk-card-tags">
            <button v-for="t in it.tags" :key="t" class="mk-tag" type="button" @click="searchTag(t)">{{ t }}</button>
          </div>
          <div class="mk-card-meta">
            <span class="mk-author" :title="it.authorUid">{{ it.authorName }}</span>
            <span class="mk-ver">{{ fmtVersion(it.version) }}</span>
            <span v-if="it.installCount">{{ it.installCount }} 次安装</span>
            <span v-if="it.updateTime">{{ fmtDate(it.updateTime) }}</span>
          </div>
          <div class="mk-card-foot">
            <button class="app-link-btn" @click="openDetail(it)">详情</button>
            <template v-if="!it.mine">
              <button v-if="it.hasUpdate" class="app-link-btn accent" :disabled="busy(it.id)" @click="install(it, true)">
                {{ busy(it.id) ? '安装中…' : '更新' }}
              </button>
              <button v-else-if="!it.installed" class="app-link-btn accent" :disabled="busy(it.id)" @click="install(it, false)">
                {{ busy(it.id) ? '安装中…' : '安装' }}
              </button>
              <!-- 已装且无更新：不给操作链接（重装收在详情弹窗），用文案确认状态即可 -->
              <span v-else class="mk-uptodate">已是最新</span>
            </template>
            <template v-else>
              <button class="app-link-btn" @click="toggleOnline(it)">{{ it.online ? '下架' : '重新上架' }}</button>
              <button class="app-link-btn danger" @click="removeItem(it)">删除</button>
            </template>
          </div>
        </div>
      </div>

      <p class="mk-foot-hint">
        安装即复制：装完就是自己的独立技能，作者改版/下架/删除都不影响已装副本。
        重新上架后内容若有变化，装过的人会自动看到「有更新」，点「更新」覆盖重装一份。
      </p>
    </div>

    <!-- 详情 -->
    <a-modal v-model:open="detailOpen" :title="'物品：' + (detail.name || '')" :footer="null" :width="820" destroy-on-close>
      <a-spin v-if="detailLoading" style="display:block;margin:36px auto" />
      <template v-else>
        <div class="mk-view-meta">
          <span class="mk-author">{{ detail.authorName }}</span>
          <span class="mk-ver">{{ fmtVersion(detail.version) }}</span>
          <span v-if="detail.installCount">{{ detail.installCount }} 次安装</span>
          <span v-if="detail.updateTime">更新于 {{ fmtDate(detail.updateTime) }}</span>
          <span v-if="detail.mine && !detail.online" class="app-pill warn key-tag">已下架</span>
          <span v-else-if="detail.hasUpdate" class="app-pill warn key-tag">有更新</span>
          <span v-else-if="detail.installed" class="app-pill ok key-tag">已安装</span>
        </div>
        <div v-if="detail.tags && detail.tags.length" class="mk-card-tags" style="margin-bottom:8px">
          <button v-for="t in detail.tags" :key="t" class="mk-tag" type="button" @click="searchTag(t)">{{ t }}</button>
        </div>
        <div class="mk-view-tabs">
          <button class="app-link-btn" :class="{ on: detailTab === 'desc' }" @click="detailTab = 'desc'">介绍</button>
          <button class="app-link-btn" :class="{ on: detailTab === 'skill' }" @click="detailTab = 'skill'">SKILL.md</button>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-if="detailTab === 'desc'" class="md mk-view-body"
             v-html="renderMd(detail.description || detail.summary || '（无介绍）')" />
        <template v-else>
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div v-if="skillRender" class="md mk-view-body" v-html="skillRender" />
          <pre v-else class="mk-view-raw">{{ detail.skillMd || '（快照里读不到 SKILL.md，可下载物品包查看）' }}</pre>
        </template>

        <div class="key-modal-foot">
          <button class="app-btn ghost" @click="download(detail)">下载物品包</button>
          <!-- 作者也能装：上架后自己装一遍验证是常事（后端本就放行） -->
          <button v-if="detail.online" class="app-btn" :disabled="busy(detail.id)"
                  @click="install(detail, !!detail.installed)">
            {{ busy(detail.id) ? '安装中…' : (detail.hasUpdate ? '更新' : (detail.installed ? '覆盖重装' : '安装')) }}
          </button>
          <button class="app-btn ghost" @click="detailOpen = false">关闭</button>
        </div>
      </template>
    </a-modal>

    <!-- 上架（与技能页共用弹窗组件） -->
    <MarketPublishModal v-model:open="publishOpen" @published="onPublished" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { CloudUploadOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import {
  deleteMarketItem, downloadMarketItemZip, getMarketItem, installMarketItem,
  listMarketItems, setMarketItemStatus
} from '../api'
import { renderMd } from '../utils/markdown'
import MarketPublishModal from '../components/MarketPublishModal.vue'

const TYPE_LABEL = { skill: '技能' }
const TABS = ['all', 'mine']

const route = useRoute()
const router = useRouter()

const items = ref([])
const loading = ref(false)
const keyword = ref('')
/** 两个页签的条数（后端按当前筛选条件算），首次加载前为 null，页签上不占位 */
const counts = ref(null)
const installing = ref({})
const publishOpen = ref(false)

const tab = computed({
  get: () => (TABS.includes(route.query.tab) ? route.query.tab : 'all'),
  set: v => router.replace({ path: '/market', query: v === 'all' ? {} : { tab: v } })
})

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({})
const detailTab = ref('desc')

const busy = id => !!installing.value[id]
const typeLabel = t => TYPE_LABEL[t] || t || '物品'
const skillRender = computed(() => {
  // SKILL.md 以 frontmatter 开头，直接渲染 markdown 会把 --- 块吞成分隔线：
  // 与技能详情页同口径预览正文，原文切到 pre 视图看
  const raw = detail.value.skillMd || ''
  const body = raw.replace(/^---\n[\s\S]*?\n---\n?/, '').trim()
  return body ? renderMd(body) : ''
})

const fmtDate = v => {
  try { return new Date(v).toLocaleDateString() } catch (e) { return '' }
}
/** 版本号展示：统一 v 前缀（裸数字夹在 meta 行里读不出来是版本）；已带 v 的不重复加 */
const fmtVersion = v => {
  const s = (v || '').trim()
  if (!s) return ''
  return /^v/i.test(s) ? s : 'v' + s
}
const errText = e => (e && e.message) || String(e)

const load = async () => {
  loading.value = true
  try {
    const r = await listMarketItems({
      keyword: keyword.value.trim(),
      mine: tab.value === 'mine'
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    items.value = Array.isArray(r.data?.items) ? r.data.items : []
    counts.value = r.data?.counts || null
  } catch (e) {
    message.error('加载市场失败：' + errText(e))
  } finally {
    loading.value = false
  }
}
watch(tab, load)
/** a-input allow-clear 的清空叉不触发 press-enter，这里监听 change 值变空时立即重拉 */
const onKeywordClear = e => {
  const v = e && e.target && e.target.value
  if (v === '' && keyword.value === '') load()
}
const searchTag = t => {
  keyword.value = t
  // 切页签会由 watch(tab) 重拉，已在「全部」时才自己拉，避免一次点击发两个请求
  if (tab.value !== 'all') tab.value = 'all'
  else load()
}
/** 上架成功的提示写的是「市场 → 我的上架」，这里就落在那一页，别让用户自己找 */
const onPublished = () => {
  if (tab.value !== 'mine') tab.value = 'mine'
  else load()
}

// ---------------- 安装 / 更新 ----------------
/** 覆盖类安装（更新/覆盖重装）先确认：整包替换会丢掉装之后本地改过的内容与旧附属文件 */
const install = (it, overwrite) => {
  if (overwrite) {
    Modal.confirm({
      title: it.hasUpdate ? '更新「' + it.name + '」？' : '覆盖重装「' + it.name + '」？',
      content: '将整包替换你已安装的同名技能：装之后本地改动过的内容、以及旧包里的 references/scripts 会丢失。',
      okText: it.hasUpdate ? '更新' : '覆盖重装',
      cancelText: '取消',
      onOk: () => doInstall(it, true)
    })
    return
  }
  doInstall(it, false)
}
const doInstall = (it, overwrite) => {
  installing.value = { ...installing.value, [it.id]: true }
  installMarketItem(it.id, !!overwrite)
    .then(r => {
      if (!r || !r.success) throw new Error((r && r.msg) || '安装失败')
      message.success((it.hasUpdate ? '已更新技能「' : '已安装技能「') + (r.data?.skillName || it.name) + '」')
      detailOpen.value = false
      load()
    })
    .catch(e => {
      const msg = errText(e)
      // 首装撞上同名（非市场来源的同名技能）：确认后覆盖重装
      if (msg.includes('同名')) {
        Modal.confirm({
          title: '覆盖安装？',
          content: '你已经有一个同名技能。覆盖会整包替换它（旧包里的 references/scripts 会丢失）。',
          okText: '覆盖安装',
          cancelText: '取消',
          onOk: () => doInstall(it, true)
        })
      } else {
        message.error('安装失败：' + msg)
      }
    })
    .finally(() => { installing.value = { ...installing.value, [it.id]: false } })
}

// ---------------- 我的上架：上下架 / 删除 ----------------
const toggleOnline = async it => {
  try {
    const r = await setMarketItemStatus(it.id, !it.online)
    if (!r || !r.success) throw new Error((r && r.msg) || '操作失败')
    message.success(it.online ? '已下架（列表不再展示，已装副本不受影响）' : '已重新上架')
    load()
  } catch (e) {
    message.error('操作失败：' + errText(e))
  }
}
const removeItem = it => {
  Modal.confirm({
    title: '删除「' + it.name + '」？',
    content: '条目与安装记录一起删除；已经装进同事技能目录的副本动不了（也不该动）。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteMarketItem(it.id)
        if (!r || !r.success) throw new Error((r && r.msg) || '删除失败')
        message.success('已删除')
        load()
      } catch (e) {
        message.error('删除失败：' + errText(e))
      }
    }
  })
}

// ---------------- 详情 / 下载 ----------------
const openDetail = async it => {
  detailOpen.value = true
  detailLoading.value = true
  detailTab.value = 'desc'
  detail.value = { ...it }
  try {
    const r = await getMarketItem(it.id)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    detail.value = r.data || it
  } catch (e) {
    message.error('读取详情失败：' + errText(e))
  } finally {
    detailLoading.value = false
  }
}
const download = async it => {
  try {
    await downloadMarketItemZip(it.id, it.name)
  } catch (e) {
    message.error('下载失败：' + errText(e))
  }
}

onMounted(load)
</script>

<style scoped>
.head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.head-search { width: 210px; }
.head-search-ic { color: var(--app-text3); font-size: 12px; }
.key-empty { text-align: center; padding: 40px 20px; }
.key-empty-title { font-weight: 600; margin-bottom: 6px; }
.key-empty-desc { font-size: 13px; color: var(--app-text3); line-height: 1.7; max-width: 560px; margin: 0 auto 14px; }
.key-empty-ops { display: flex; justify-content: center; gap: 8px; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 18px; }

/* 页签条：与成员/权限管理页同形态（a-tabs 只当导航，内容区在下方按 tab 渲染） */
.mk-tabs { flex: none; background: var(--app-panel); }
.mk-tabs :deep(.ant-tabs-nav) { margin: 0; padding: 0 20px; background: var(--app-panel); }
.mk-tabs :deep(.ant-tabs-nav::before) { border-color: var(--app-border); }
.mk-tabs :deep(.ant-tabs-tab) { font-size: 13px; padding: 10px 0; }
.mk-tabs :deep(.ant-tabs-tab-active) { font-weight: 600; }
.mk-tab { display: inline-flex; align-items: center; }
.mk-badge { font-style: normal; font-size: 11px; line-height: 16px; margin-left: 6px; padding: 0 6px;
  border-radius: 999px; background: var(--app-accent-weak); color: var(--app-text3); }
.mk-tabs :deep(.ant-tabs-tab-active) .mk-badge { background: var(--app-accent); color: #fff; }

.mk-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; margin-bottom: 18px; }
.mk-card:hover { border-color: var(--app-accent-border); }
.mk-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.mk-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mk-card-head .app-pill.type { background: var(--app-panel-2); color: var(--app-text2); }
.mk-card-desc { font-size: 12px; color: var(--app-text3); line-height: 1.6; margin: 6px 0 6px; height: 38px; overflow: hidden; }
.mk-card-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.mk-tag { border: 0; background: var(--app-panel-2); color: var(--app-text2); border-radius: 999px;
  padding: 1px 8px; font-size: 11px; line-height: 18px; cursor: pointer; }
.mk-tag:hover { color: var(--app-accent); }
.mk-card-meta { display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--app-text3); margin-bottom: 8px; }
.mk-author { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mk-ver { background: var(--app-panel-2); border-radius: 999px; padding: 0 7px; line-height: 17px; color: var(--app-text2); font-size: 10px; }
.mk-uptodate { font-size: 11px; color: var(--app-text3); }
.mk-card-foot { display: flex; align-items: center; justify-content: flex-end; gap: 2px; border-top: 1px dashed var(--app-border); padding-top: 4px; }
.app-link-btn.accent { color: var(--app-accent); font-weight: 600; }
.app-link-btn:disabled { opacity: .55; cursor: not-allowed; }
.mk-foot-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: 4px 0 0; }

.mk-view-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 12px; color: var(--app-text3); margin-bottom: 8px; }
.mk-view-tabs { display: flex; gap: 14px; border-bottom: 1px solid var(--app-border); padding-bottom: 6px; margin-bottom: 8px; }
.mk-view-tabs .app-link-btn { color: var(--app-text3); }
.mk-view-tabs .app-link-btn.on { color: var(--app-accent); font-weight: 600; }
.mk-view-body { max-height: 52vh; overflow-y: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px; }
.mk-view-raw { max-height: 52vh; overflow: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px;
  background: var(--app-panel-2); font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; margin: 0; }
</style>
