<template>
  <!-- 市场：发现 → 一键安装；技能 / 应用模板（智能体）/ 工作流 / MCP 服务。
       核心语义「安装即复制」：技能=复制进本人目录（同名可整包替换）；应用模板/工作流=每次安装都 fork 一份新副本。 -->
  <div class="app-page">
    <div class="app-page-head">
      <h1 class="app-page-title sr-only">市场</h1>
      <span class="head-hint-plain">装同事分享的技能、应用模板、工作流与 MCP 服务</span>
      <div class="head-r">
        <a-input v-model:value="keyword" class="head-search" size="small" allow-clear
                 placeholder="搜索名称/简介/标签" @press-enter="applySearch" @change="onKeywordChange">
          <template #prefix><search-outlined class="head-search-ic" /></template>
        </a-input>
        <a-tooltip title="刷新列表">
          <button class="app-icon-btn" aria-label="刷新市场列表" :disabled="loading" @click="refresh"><reload-outlined /></button>
        </a-tooltip>
        <!-- MCP 页签没有「我的上架」发布链路：普通用户只浏览/安装，管理员维护目录条目 -->
        <button v-if="tab !== 'mcp'" class="app-btn" @click="publishOpen = true"><cloud-upload-outlined /> 上架</button>
        <button v-else-if="isAdmin" class="app-btn" @click="openMcpModal()"><plus-outlined /> 新增 MCP 服务</button>
      </div>
    </div>

    <!-- 页签形态对齐成员/权限管理页：a-tabs 只当导航条，内容在下方按 tab 渲染。
         类型页签各读 counts.byType[类型]，「我的上架」读跨类型的 counts.mine；
         页签状态落 URL(?tab=mine)，刷新/回退/分享可还原 -->
    <a-tabs v-model:active-key="tab" class="mk-tabs" :class="{ 'is-searching': searching && !drill }">
      <a-tab-pane v-for="t in TYPE_TABS" :key="t.key">
        <template #tab><span class="mk-tab">{{ t.label }}<i v-if="counts" class="mk-badge">{{ counts.byType[t.key] }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="installed">
        <template #tab><span class="mk-tab">我安装的<i v-if="counts" class="mk-badge">{{ counts.installed }}</i></span></template>
      </a-tab-pane>
      <a-tab-pane key="mine">
        <template #tab><span class="mk-tab">我的上架<i v-if="counts" class="mk-badge">{{ counts.mine }}</i></span></template>
      </a-tab-pane>
    </a-tabs>

    <div class="app-page-body">
      <a-spin v-if="loading && !items.length" style="display:block;margin:48px auto" />

      <template v-else>
        <!-- 工具行：普通浏览/我的上架；搜索与「我安装的」隐藏（后者只看已装清单） -->
        <div v-if="!searching && tab !== 'installed'" class="mk-toolbar">
          <a-select v-model:value="sort" size="small" class="mk-sort" :options="SORT_OPTIONS"
                    aria-label="排序方式" @change="onSortChange" />
          <div class="mk-chip-row">
            <button type="button" class="mk-chip" :class="{ on: !tag }" @click="pickTag('')">全部</button>
            <button v-for="t in tagOptions" :key="t" type="button" class="mk-chip"
                    :class="{ on: tag === t }" @click="pickTag(t)">{{ t }}</button>
          </div>
        </div>
        <!-- 我安装的：汇总条 + 批量更新入口（数量与侧栏红点同源） -->
        <div v-if="tab === 'installed' && !searching" class="mk-summary">
          <span class="mk-summary-text">
            已装 <b>{{ counts?.installed || 0 }}</b> 个内容，其中 <b>{{ counts?.updates || 0 }}</b> 个可以更新
          </span>
          <button class="app-btn small" :disabled="!counts?.updates" @click="openBatch">
            <reload-outlined /> 批量更新
          </button>
        </div>
        <div v-else-if="searching" class="mk-search-hint">
          <template v-if="drill">在「{{ tabLabel(tab) }}」中找到 <b>{{ total }}</b> 条与「{{ appliedKeyword }}」相关的内容</template>
          <template v-else>在 {{ TYPE_TABS.length }} 个类型中找到 <b>{{ total }}</b> 条与「{{ appliedKeyword }}」相关的内容</template>
        </div>

        <!-- 空态 -->
        <div v-if="!items.length" class="app-card key-empty">
          <template v-if="searching">
            <div class="key-empty-title">没有匹配的内容</div>
            <div class="key-empty-desc">没有名称、简介或标签包含「{{ appliedKeyword }}」的内容。</div>
            <div class="key-empty-ops">
              <button class="app-btn ghost small" @click="clearSearch">清除搜索</button>
            </div>
          </template>
          <template v-else-if="tag">
            <div class="key-empty-title">没有带「{{ tag }}」标签的内容</div>
            <div class="key-empty-desc">清除标签可以查看这个页签的全部内容。</div>
            <div class="key-empty-ops">
              <button class="app-btn ghost small" @click="pickTag('')">清除标签</button>
            </div>
          </template>
          <template v-else-if="tab === 'installed'">
            <div class="key-empty-title">还没有安装过内容</div>
            <div class="key-empty-desc">装过的技能、应用模板、工作流与 MCP 服务都会出现在这里，更新也会第一时间提示。</div>
            <div class="key-empty-ops">
              <button class="app-btn" @click="goTab(firstBrowseTab)">去逛逛</button>
            </div>
          </template>
          <template v-else-if="tab === 'mine'">
            <div class="key-empty-title">你还没有上架过内容</div>
            <div class="key-empty-desc">把自己的好东西上架，同事就能一键安装：固化的做法、配好的智能体、画好的流程都值得分享。</div>
            <div class="key-empty-ops">
              <button class="app-btn" @click="publishOpen = true"><cloud-upload-outlined /> 上架</button>
            </div>
          </template>
          <template v-else>
            <div class="key-empty-title">这个页签还是空的</div>
            <div class="key-empty-desc">
              <template v-if="otherTypeCount">其他页签还有 {{ otherTypeCount }} 件内容</template>
              <template v-else>第一个上架的人就是你。</template>
            </div>
            <div class="key-empty-ops">
              <button v-if="firstOtherTab" class="app-btn ghost small" @click="goTab(firstOtherTab.key)">
                去{{ firstOtherTab.label }}页签
              </button>
              <button v-if="tab !== 'mcp'" class="app-btn" @click="publishOpen = true"><cloud-upload-outlined /> 上架</button>
              <button v-else-if="isAdmin" class="app-btn" @click="openMcpModal()"><plus-outlined /> 新增 MCP 服务</button>
            </div>
          </template>
        </div>

        <!-- 全局搜索：按类型分组的紧凑行 -->
        <template v-else-if="searching && !drill">
          <div v-for="g in searchGroups" :key="g.key" class="mk-res-group">
            <div class="mk-res-head">
              <span class="t">{{ g.label }}</span><span class="n">{{ g.items.length }}</span>
              <button type="button" class="mk-res-more" @click="drillTo(g.key)">在{{ g.label }}页签查看 →</button>
            </div>
            <div v-for="it in g.items" :key="it.id" class="mk-res-row">
              <span class="mk-type-icon" :class="it.itemType"><component :is="typeIcon(it.itemType)" /></span>
              <div class="mk-res-main">
                <div class="mk-res-name">
                  <span class="txt">{{ it.name }}</span>
                  <span v-if="it.authorUid === 'system'" class="app-pill plain key-tag mk-official">平台官方</span>
                  <span v-if="stateOf(it)" class="app-pill key-tag" :class="stateOf(it).cls">{{ stateOf(it).label }}</span>
                </div>
                <div class="mk-res-desc">{{ it.summary || '暂无简介' }}</div>
              </div>
              <div class="mk-res-meta">
                <span v-if="versionLabel(it)" class="mk-ver" :class="{ delta: it.hasUpdate }">{{ versionLabel(it) }}</span>
                <span class="mk-author">{{ it.authorName }}</span>
                <span v-if="it.installCount">{{ it.installCount }} 次安装</span>
              </div>
              <div class="mk-res-act">
                <button class="app-link-btn" @click="openDetail(it)">详情</button>
                <template v-if="actionOf(it)">
                  <span v-if="actionOf(it).text" class="mk-note">{{ actionOf(it).label }}</span>
                  <button v-else class="app-link-btn accent" :disabled="actionOf(it).disabled"
                          @click="actionOf(it).run()">{{ actionOf(it).label }}</button>
                </template>
              </div>
            </div>
          </div>
          <div v-if="hasMore" class="mk-load-more">
            <button class="app-btn ghost small" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? '加载中…' : `加载更多（还有 ${total - items.length} 个）` }}
            </button>
          </div>
        </template>

        <!-- 卡片网格 -->
        <template v-else>
          <div class="mk-grid">
            <div v-for="it in items" :key="it.id" class="app-card mk-card">
              <div class="mk-card-head">
                <span class="mk-type-icon" :class="it.itemType"><component :is="typeIcon(it.itemType)" /></span>
                <span class="mk-card-name" :title="it.name">{{ it.name }}</span>
                <span v-if="it.authorUid === 'system'" class="app-pill plain key-tag mk-official">平台官方</span>
                <span v-if="it.itemType === 'mcp' && it.requiresAuth" class="app-pill warn plain key-tag">需认证</span>
                <span v-if="stateOf(it)" class="app-pill key-tag" :class="stateOf(it).cls">{{ stateOf(it).label }}</span>
              </div>
              <div class="mk-card-desc" :title="it.summary || ''">{{ it.summary || '暂无简介' }}</div>
              <!-- MCP：目录只下发地址与传输类型（等宽字体一眼可辨），不含任何凭据 -->
              <div v-if="it.itemType === 'mcp' && it.url" class="mk-url-row">
                <span class="mk-transport">{{ it.mcpType || 'streamable' }}</span>
                <span class="mk-url" :title="it.url">{{ it.url }}</span>
              </div>
              <div v-if="it.tags && it.tags.length" class="mk-card-tags">
                <button v-for="t in it.tags" :key="t" class="mk-tag" type="button" @click="pickTag(t)">{{ t }}</button>
              </div>
              <div class="mk-card-meta">
                <span class="mk-author" :title="it.authorUid">{{ it.authorName }}</span>
                <span v-if="versionLabel(it)" class="mk-ver" :class="{ delta: it.hasUpdate }">{{ versionLabel(it) }}</span>
                <span v-if="it.installCount">{{ it.installCount }} 次安装</span>
                <span v-if="it.updateTime">{{ fmtDate(it.updateTime) }}</span>
              </div>
              <!-- 我的上架：作者数据（近 30 天安装趋势 + 最近安装人），没有流水就不占位 -->
              <div v-if="tab === 'mine' && statsOf(it)" class="mk-stats">
                <div class="mk-stats-line">
                  <span>近 30 天 <b>{{ statsOf(it).count30 }}</b> 次安装</span>
                  <span class="mk-spark">
                    <i v-for="(p, i) in statsOf(it).series" :key="i"
                       :style="{ height: barHeight(p.count, statsOf(it)) }" />
                  </span>
                </div>
                <div v-if="statsOf(it).recent && statsOf(it).recent.length" class="mk-installers">
                  <span v-for="p in statsOf(it).recent" :key="p.uid" class="mk-ava"
                        :title="p.name">{{ initialOf(p.name) }}</span>
                </div>
              </div>
              <div class="mk-card-foot">
                <button class="app-link-btn" @click="openDetail(it)">详情</button>
                <!-- MCP 目录：管理员=编辑/下架；用户按四态分流（已装>同名>超限>可装）。
                     「我安装的」只做使用者视角，不暴露目录管理动作 -->
                <template v-if="it.itemType === 'mcp' && isAdmin && tab !== 'installed'">
                  <button class="app-link-btn" @click="openMcpModal(it)">编辑</button>
                  <button class="app-link-btn" @click="toggleOnline(it)">{{ it.online ? '下架' : '重新上架' }}</button>
                </template>
                <template v-else-if="it.mine && tab !== 'installed'">
                  <button class="app-link-btn" @click="openMeta(it)">编辑信息</button>
                  <button class="app-link-btn" @click="toggleOnline(it)">{{ it.online ? '下架' : '重新上架' }}</button>
                  <button class="app-link-btn danger" @click="removeItem(it)">删除</button>
                </template>
                <template v-else-if="actionOf(it)">
                  <span v-if="actionOf(it).text" class="mk-note">{{ actionOf(it).label }}</span>
                  <button v-else class="app-link-btn accent" :disabled="actionOf(it).disabled"
                          @click="actionOf(it).run()">{{ actionOf(it).label }}</button>
                </template>
              </div>
            </div>
          </div>
          <div v-if="hasMore" class="mk-load-more">
            <button class="app-btn ghost small" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? '加载中…' : `加载更多（还有 ${total - items.length} 个）` }}
            </button>
          </div>
          <p v-if="tab === 'mcp'" class="mk-foot-hint">
            安装后出现在「智能体 → MCP 外部工具」；需要认证的服务请在那里补充请求头。每人最多 {{ mcpQuota.max }} 个。
          </p>
        </template>
      </template>
    </div>

    <!-- 详情 -->
    <a-modal v-model:open="detailOpen" :title="detail.name || ''" :footer="null" :width="820" destroy-on-close>
      <a-spin v-if="detailLoading" style="display:block;margin:36px auto" />
      <template v-else>
        <div class="mk-view-meta">
          <span class="mk-author">{{ detail.authorName }}</span>
          <span class="mk-ver">{{ fmtVersion(detail.version) }}</span>
          <span v-if="detail.installCount">{{ detail.installCount }} 次安装</span>
          <span v-if="detail.updateTime">更新于 {{ fmtDate(detail.updateTime) }}</span>
          <span v-if="stateOf(detail)" class="app-pill key-tag" :class="stateOf(detail).cls">{{ stateOf(detail).label }}</span>
        </div>
        <!-- MCP：地址与传输类型常驻可见（装前可核对装的是什么） -->
        <div v-if="detail.itemType === 'mcp' && detail.url" class="mk-url-row" style="margin:0 0 8px">
          <span class="mk-transport">{{ detail.mcpType || 'streamable' }}</span>
          <span class="mk-url" :title="detail.url">{{ detail.url }}</span>
        </div>
        <div v-if="detail.tags && detail.tags.length" class="mk-card-tags" style="margin-bottom:8px">
          <button v-for="t in detail.tags" :key="t" class="mk-tag" type="button"
                  @click="pickTag(t); detailOpen = false">{{ t }}</button>
        </div>
        <div class="mk-view-tabs">
          <button class="app-link-btn" :class="{ on: detailTab === 'desc' }" @click="detailTab = 'desc'">介绍</button>
          <button class="app-link-btn" :class="{ on: detailTab === 'content' }" @click="detailTab = 'content'">内容</button>
          <button class="app-link-btn" :class="{ on: detailTab === 'versions' }" @click="detailTab = 'versions'">版本历史</button>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-if="detailTab === 'desc'" class="md mk-view-body"
             v-html="renderMd(detail.description || detail.summary || '暂无介绍')" />
        <!-- 内容页签：按类型给结构化预览（技能正文/能力摘要/只读流程/参数表），原始配置折叠可查 -->
        <MarketItemContentView v-else-if="detailTab === 'content'" :item="detail" :quota="mcpQuota" />
        <div v-else class="mk-versions">
          <div v-if="detailVersionsLoading" class="mk-versions-empty">加载中…</div>
          <div v-else-if="detailVersionsError" class="mk-versions-empty">版本历史读取失败。</div>
          <div v-else-if="!detailVersions.length" class="mk-versions-empty">还没有版本记录。</div>
          <div v-for="(v, i) in detailVersions" :key="i" class="mk-version">
            <div class="mk-version-head">
              <span class="mk-version-no">{{ fmtVersion(v.version) }}</span>
              <span class="mk-version-date">{{ fmtDate(v.createTime) }}</span>
            </div>
            <div class="mk-version-note" :class="{ muted: !v.releaseNote }">
              {{ v.releaseNote || '作者没有填写这次更新说明' }}
            </div>
          </div>
        </div>

        <!-- MCP：认证提醒常驻可见（不随页签切换消失） -->
        <div v-if="detail.itemType === 'mcp' && detail.requiresAuth" class="mk-auth-note">
          该服务需要认证请求头。这里只提供地址与传输类型，不含任何凭据；
          安装后请在「智能体 → MCP 外部工具」编辑该服务，补充请求头凭据。
        </div>

        <div class="key-modal-foot">
          <button v-if="detail.itemType !== 'mcp' && detail.online" class="app-btn ghost" @click="download(detail)">下载物品包</button>
          <!-- MCP：按四态分流（与卡片同口径）；非 MCP 走原有安装流（作者也能装：上架后自己装一遍验证是常事） -->
          <template v-if="detail.itemType === 'mcp' && detail.online">
            <button v-if="detail.mcpState === 'installed'" class="app-btn ghost" @click="goMcpPanel">去 MCP 页</button>
            <button v-else-if="detail.mcpState === 'name_conflict'" class="app-btn ghost" @click="goMcpPanel">去 MCP 页处理</button>
            <button v-else-if="detail.mcpState === 'quota_full'" class="app-btn" disabled>已达上限</button>
            <button v-else-if="detail.mcpState !== 'broken'" class="app-btn" :disabled="busy(detail.id)" @click="doInstall(detail, false)">
              {{ busy(detail.id) ? '安装中…' : '安装到我的 MCP' }}
            </button>
          </template>
          <button v-else-if="detail.itemType !== 'mcp' && detail.online" class="app-btn" :disabled="busy(detail.id)"
                  @click="onDetailPrimary(detail)">
            {{ busy(detail.id) ? '安装中…' : installLabel(detail) }}
          </button>
          <button class="app-btn ghost" @click="detailOpen = false">关闭</button>
        </div>
      </template>
    </a-modal>

    <!-- 上架（与技能页共用弹窗组件） -->
    <MarketPublishModal v-model:open="publishOpen" @published="onPublished" />
    <!-- 更新说明 / 批量更新 / 编辑信息 -->
    <MarketUpdateModal v-model:open="updateOpen" :item="updateItem" :versions="updateVersions"
                       :loading="updateLoading" @confirm="onUpdateConfirm" />
    <MarketBatchUpdateModal v-model:open="batchOpen" :items="updatesList" @done="onBatchDone" />
    <MarketMetaModal v-model:open="metaOpen" :item="metaItem" @saved="onMetaSaved" />
    <!-- MCP 目录条目（仅管理员；新增与编辑共用） -->
    <MarketMcpPublishModal v-model:open="mcpModalOpen" :edit-item="mcpEditItem" @published="refresh" />
  </div>
</template>

<script setup>
import { computed, h, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ApiOutlined, BookOutlined, CloudUploadOutlined, PartitionOutlined, PlusOutlined,
  ReloadOutlined, RobotOutlined, SearchOutlined
} from '@ant-design/icons-vue'
import {
  deleteMarketItem, downloadMarketItem, getMarketItem, getMarketItemVersions,
  getMyMarketStats, installMarketItem, listMarketInstalled, listMarketItems,
  listMarketUpdates, setMarketItemStatus
} from '../api'
import { renderMd } from '../utils/markdown'
import { ensureAuth } from '../utils/auth'
import { refreshMarketUpdates } from '../utils/marketUpdates'
import MarketPublishModal from '../components/MarketPublishModal.vue'
import MarketMcpPublishModal from '../components/MarketMcpPublishModal.vue'
import MarketUpdateModal from '../components/MarketUpdateModal.vue'
import MarketBatchUpdateModal from '../components/MarketBatchUpdateModal.vue'
import MarketMetaModal from '../components/MarketMetaModal.vue'
import MarketItemContentView from '../components/MarketItemContentView.vue'

const TYPE_LABEL = { skill: '技能', agent: '应用模板', workflow: '工作流', mcp: 'MCP 服务' }
const TYPE_ICON = { skill: BookOutlined, agent: RobotOutlined, workflow: PartitionOutlined, mcp: ApiOutlined }
/** 类型页签（物品类型过滤）+ 「我安装的」+ 跨类型的「我的上架」 */
const TYPE_TABS = [
  { key: 'skill', label: '技能' },
  { key: 'agent', label: '应用模板' },
  { key: 'workflow', label: '工作流' },
  { key: 'mcp', label: 'MCP 服务' }
]
const TABS = [...TYPE_TABS.map(t => t.key), 'installed', 'mine']
const SORT_OPTIONS = [
  { value: 'update_time', label: '最新' },
  { value: 'install_count', label: '最多安装' }
]
const PAGE_SIZE = 24
const SEARCH_PAGE_SIZE = 60
const TAB_KEY = 'market_tab'

const route = useRoute()
const router = useRouter()

const items = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const keyword = ref('')
/** 已生效的搜索词：只有回车/清空才改变结果，输入过程中不打断当前列表 */
const appliedKeyword = ref('')
/** 搜索时是否钻到某个页签（false=跨类型分组结果） */
const drill = ref(false)
const sort = ref('update_time')
const tag = ref('')
/** 标签 chips 的候选池：当前页签已加载条目里出现过的标签（切页签重置） */
const tagPool = ref([])
const page = ref(1)
const total = ref(0)
/** 页签条数（后端 counts：byType 逐类型 + 跨类型 mine，均按当前关键词/标签算），首次加载前为 null 不占位 */
const counts = ref(null)
const installing = ref({})
const publishOpen = ref(false)
/** 我安装的：可更新清单（批量更新弹窗数据）/ 更新说明弹窗 / 编辑信息 */
const updatesList = ref([])
const batchOpen = ref(false)
const updateOpen = ref(false)
const updateItem = ref({})
const updateVersions = ref({})
const updateLoading = ref(false)
const metaOpen = ref(false)
const metaItem = ref({})
/** 我的上架：安装趋势与最近安装人（/market/mine/stats，按条目 id 索引） */
const statsByItem = ref({})
/** 首次挂载的页签决定走完再让 watch 接管，避免重复请求 */
const inited = ref(false)

const tab = computed({
  get: () => (TABS.includes(route.query.tab) ? route.query.tab : 'skill'),
  set: v => {
    try { localStorage.setItem(TAB_KEY, v) } catch (e) { /* 隐私模式忽略 */ }
    router.replace({ path: '/market', query: v === 'skill' ? {} : { tab: v } })
  }
})

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref({})
const detailTab = ref('desc')
const detailVersions = ref([])
const detailVersionsLoading = ref(false)
const detailVersionsError = ref(false)

const busy = id => !!installing.value[id]
const typeIcon = t => TYPE_ICON[t] || BookOutlined
const tabLabel = t => (t === 'mine' ? '我的上架' : (t === 'installed' ? '我安装的' : (TYPE_LABEL[t] || '内容')))
const searching = computed(() => appliedKeyword.value !== '')

// ---------------- MCP 目录（第 4 页签） ----------------
/** 管理员才看得到目录维护入口；ensureAuth 命中模块级缓存，不额外发请求 */
const isAdmin = ref(false)
/** 安装四态（与后端 mcpState 同口径；文案与优先级见 MarketService.enrichMcpViews） */
const MCP_STATES = {
  installed: { label: '已在 MCP', cls: 'ok' },
  name_conflict: { label: '同名已存在', cls: 'warn' },
  quota_full: { label: '已达上限', cls: 'warn' },
  broken: { label: '目录信息异常', cls: 'warn' }
}
/** MCP 个人配额（counts.mcpQuota 后端随列表下发，与四态判定同源） */
const mcpQuota = computed(() => counts.value?.mcpQuota || { used: 0, max: 10 })
const mcpModalOpen = ref(false)
const mcpEditItem = ref(null)
const openMcpModal = it => {
  mcpEditItem.value = it || null
  mcpModalOpen.value = true
}
/** 「去 MCP 页」统一落智能体中心的 MCP 外部工具页签 */
const goMcpPanel = () => router.push({ path: '/agents', query: { tab: 'mcp' } })

/** fork 类物品（应用模板/工作流）的卡片主操作文案：装新版 > 安装 > 再装一份 */
const copyLabel = it => (it.hasUpdate ? '装新版' : (it.installed ? '再装一份' : '安装'))
/** 详情弹窗安装按钮文案（技能有覆盖语义，fork 类没有） */
const installLabel = it => it.itemType === 'skill'
  ? (it.hasUpdate ? '更新' : (it.installed ? '覆盖重装' : '安装'))
  : copyLabel(it)

const fmtDate = v => {
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
/** 版本号展示：统一 v 前缀（裸数字夹在 meta 行里读不出来是版本）；已带 v 的不重复加 */
const fmtVersion = v => {
  const s = (v || '').trim()
  if (!s) return ''
  return /^v/i.test(s) ? s : 'v' + s
}
/** 卡片元信息里的版本：已装看已装版本，有更新看区间，未装看市场版本 */
const versionLabel = it => {
  const cur = fmtVersion(it.version)
  const ins = fmtVersion(it.installedVersion)
  if (it.hasUpdate) return ins ? `已装 ${ins} → ${cur}` : cur
  if (it.installed) return ins ? `已装 ${ins}` : ''
  return cur
}
/** 卡片状态：只出现一次；MCP 的更新语义不可用，因此不展示「有更新」 */
const stateOf = it => {
  if (!it || !it.itemType) return null
  if (it.itemType === 'mcp') {
    if (it.online === false) return { label: '已下架', cls: 'warn' }
    const meta = MCP_STATES[it.mcpState]
    return meta ? { label: meta.label, cls: meta.cls } : null
  }
  // 我安装的：下架优先，其次有更新/已是最新（与浏览页签的「已安装」区分）
  if (tab.value === 'installed') {
    if (it.online === false) return { label: '作者已下架', cls: 'warn' }
    if (it.hasUpdate) return { label: '有更新', cls: 'update' }
    return { label: '已是最新', cls: 'ok' }
  }
  if (it.mine) {
    if (it.online === false) return { label: '已下架', cls: 'warn' }
    if (tab.value === 'mine') return { label: '已上架', cls: 'ok' }
  }
  if (it.hasUpdate) return { label: '有更新', cls: 'update' }
  if (it.installed) return { label: '已安装', cls: 'ok' }
  return null
}
/** 非作者的唯一主操作；返回 {label, run, disabled} 或 {label, text:true} */
const actionOf = it => {
  if (!it) return null
  // 我的上架/浏览页签里的自有条目走管理动作；「我安装的」里自装的内容仍要能看更新说明
  if (it.mine && tab.value !== 'installed') return null
  if (it.itemType === 'mcp') {
    if (it.online === false) return null
    if (it.mcpState === 'installed') return { label: '去 MCP 页', run: goMcpPanel }
    if (it.mcpState === 'name_conflict') return { label: '去 MCP 页处理', run: goMcpPanel }
    if (it.mcpState === 'quota_full') return { label: `已达上限（${mcpQuota.value.used}/${mcpQuota.value.max}）`, text: true }
    if (it.mcpState === 'broken') return { label: '目录信息异常', text: true }
    return { label: busy(it.id) ? '安装中…' : '安装', run: () => doInstall(it, false), disabled: busy(it.id) }
  }
  // 已下架：装过的人可以回看详情，但不能更新/再装
  if (it.online === false) return null
  // 有更新统一先看说明：技能是覆盖，应用模板/工作流是新增副本，按钮文案相同、后果不同
  if (it.hasUpdate) return { label: '更新说明', run: () => openUpdate(it) }
  if (it.itemType === 'skill') {
    if (!it.installed) return { label: busy(it.id) ? '安装中…' : '安装', run: () => install(it, false), disabled: busy(it.id) }
    return null
  }
  if (tab.value === 'installed') return null
  return { label: busy(it.id) ? '安装中…' : copyLabel(it), run: () => installCopy(it), disabled: busy(it.id) }
}

const errText = e => (e && e.message) || String(e)

// ---------------- 列表加载 ----------------
const collectTags = list => {
  const freq = new Map()
  for (const it of list) {
    for (const t of (it.tags || [])) {
      if (!t) continue
      freq.set(t, (freq.get(t) || 0) + 1)
    }
  }
  return [...freq.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], 'zh-CN'))
    .map(([t]) => t)
    .slice(0, 12)
}
const buildParams = () => {
  const p = { page: page.value, pageSize: searching.value ? SEARCH_PAGE_SIZE : PAGE_SIZE, sort: sort.value }
  if (searching.value) {
    p.keyword = appliedKeyword.value
    if (drill.value) {
      if (tab.value === 'mine') p.mine = true
      else p.type = tab.value
    }
  } else if (tab.value === 'mine') {
    p.mine = true
  } else {
    p.type = tab.value
  }
  if (!searching.value && tag.value) p.tag = tag.value
  return p
}
/** 列表请求序号：切页签/改条件时丢弃先发后到的旧响应，避免旧数据覆盖新页签 */
let loadSeq = 0
const fetchPage = async (reset = true) => {
  const seq = ++loadSeq
  if (reset) loading.value = true
  else loadingMore.value = true
  try {
    // 「我安装的」走独立端点（安装记录 join 现存条目）；全局搜索时仍走跨类型列表
    const installedView = tab.value === 'installed' && (!searching.value || drill.value)
    const r = installedView
      ? await listMarketInstalled({
        keyword: drill.value ? appliedKeyword.value : '',
        page: page.value,
        pageSize: searching.value ? SEARCH_PAGE_SIZE : PAGE_SIZE
      })
      : await listMarketItems(buildParams())
    if (seq !== loadSeq) return
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    const list = Array.isArray(r.data?.items) ? r.data.items : []
    items.value = reset ? list : items.value.concat(list)
    total.value = Number(r.data?.total || 0)
    counts.value = r.data?.counts || null
    if (reset && !searching.value && !tag.value && tab.value !== 'installed') {
      tagPool.value = collectTags(list)
    }
    if (tab.value === 'mine' && !searching.value) loadStats()
    if (tab.value === 'installed' && !searching.value) refreshMarketUpdates()
  } catch (e) {
    if (seq !== loadSeq) return
    message.error('加载市场失败：' + errText(e))
  } finally {
    if (seq === loadSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
}
const reload = (clear = true) => {
  if (clear) items.value = []
  page.value = 1
  fetchPage(true)
}
const loadMore = () => {
  if (loadingMore.value || !hasMore.value) return
  page.value += 1
  fetchPage(false)
}
const refresh = () => reload(false)
const hasMore = computed(() => items.value.length < total.value)

const applySearch = () => {
  appliedKeyword.value = keyword.value.trim()
  drill.value = tab.value === 'mine' || tab.value === 'installed'
  tag.value = ''
  reload(true)
}
const onKeywordChange = e => {
  // allow-clear 与手动删空都走这里：清空立即恢复当前页签列表
  const v = (e && e.target && e.target.value) || ''
  if (v !== '') return
  keyword.value = ''
  if (!appliedKeyword.value) return
  appliedKeyword.value = ''
  drill.value = false
  reload(true)
}
const clearSearch = () => {
  keyword.value = ''
  appliedKeyword.value = ''
  drill.value = false
  reload(true)
}
const pickTag = t => {
  const next = t === tag.value ? '' : (t || '')
  tag.value = next
  keyword.value = ''
  appliedKeyword.value = ''
  drill.value = false
  reload(true)
}
const onSortChange = () => reload(true)
const drillTo = key => {
  drill.value = true
  if (tab.value === key) reload(true)
  else tab.value = key
}
const goTab = key => { tab.value = key }

const tagOptions = computed(() => tagPool.value)
const otherTypeCount = computed(() => {
  const byType = counts.value?.byType || {}
  return TYPE_TABS
    .filter(t => t.key !== tab.value)
    .reduce((sum, t) => sum + Number(byType[t.key] || 0), 0)
})
const firstOtherTab = computed(() => TYPE_TABS
  .find(t => t.key !== tab.value && Number(counts.value?.byType?.[t.key] || 0) > 0))
const firstBrowseTab = computed(() => TYPE_TABS
  .find(t => Number(counts.value?.byType?.[t.key] || 0) > 0)?.key || 'skill')
const searchGroups = computed(() => TYPE_TABS
  .map(t => ({ ...t, items: items.value.filter(it => it.itemType === t.key) }))
  .filter(g => g.items.length))

// ---------------- 作者数据（我的上架） ----------------
const loadStats = async () => {
  try {
    const r = await getMyMarketStats(30)
    if (r && r.success) statsByItem.value = r.data?.byItem || {}
  } catch (e) { /* 作者数据是增强：拉不到不打断列表 */ }
}
const statsOf = it => statsByItem.value[it.id] || null
/** 30 根柱子的相对高度（2-16px）：没有数据的日期留最矮一根，不画成 0 */
const barHeight = (count, s) => {
  const series = (s && s.series) || []
  const max = Math.max(1, ...series.map(p => Number(p.count) || 0))
  return Math.max(2, Math.round((Number(count) || 0) / max * 16)) + 'px'
}
const initialOf = name => {
  const s = (name || '').trim()
  return s ? s.slice(0, 1).toUpperCase() : '?'
}

// ---------------- 更新说明 / 批量更新 / 编辑信息 ----------------
const openUpdate = async it => {
  updateItem.value = it
  updateVersions.value = {}
  updateOpen.value = true
  updateLoading.value = true
  try {
    const r = await getMarketItemVersions(it.id)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    updateVersions.value = r.data || {}
  } catch (e) {
    message.error('读取更新说明失败：' + errText(e))
    updateVersions.value = { items: [], newItems: [], matched: false }
  } finally {
    updateLoading.value = false
  }
}
/** 更新说明弹窗确认：技能=覆盖更新，应用模板/工作流=安装新副本 */
const onUpdateConfirm = it => {
  updateOpen.value = false
  doInstall(it, it.itemType === 'skill')
}
const openBatch = async () => {
  try {
    const r = await listMarketUpdates()
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    const list = r.data?.items || []
    if (!list.length) { message.info('当前没有可更新的内容'); return }
    updatesList.value = list
    batchOpen.value = true
  } catch (e) {
    message.error('读取可更新清单失败：' + errText(e))
  }
}
const onBatchDone = () => {
  refresh()
  refreshMarketUpdates()
}
const openMeta = async it => {
  try {
    const r = await getMarketItem(it.id)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    metaItem.value = r.data || it
    metaOpen.value = true
  } catch (e) {
    message.error('读取详情失败：' + errText(e))
  }
}
const onMetaSaved = () => refresh()

watch(tab, () => {
  if (!inited.value) return
  drill.value = !!appliedKeyword.value
  tag.value = ''
  tagPool.value = []
  reload(true)
})

const tabFromRoute = () => (TABS.includes(route.query.tab) ? route.query.tab : '')
const storedTab = () => {
  try {
    const v = localStorage.getItem(TAB_KEY)
    return TABS.includes(v) ? v : ''
  } catch (e) { return '' }
}

onMounted(async () => {
  // 管理员标记只影响目录维护入口的显隐；失败按非管理员处理（后端仍兜底鉴权）
  ensureAuth().then(info => { isAdmin.value = !!info?.admin }).catch(() => { /* 登录态由路由守卫负责 */ })
  refreshMarketUpdates()

  const fromRoute = tabFromRoute()
  if (fromRoute) {
    inited.value = true
    fetchPage(true)
    return
  }
  const stored = storedTab()
  if (stored) {
    tab.value = stored
    await nextTick() // 让上面的页签变化先被 watch 消费掉（inited 仍为 false），避免重复请求
    inited.value = true
    fetchPage(true)
    return
  }
  // 首次进入：落第一个有内容的类型页签（计数失败就保留默认「技能」）
  try {
    const r = await listMarketItems({ page: 1, pageSize: 1 })
    const first = TYPE_TABS.find(t => Number(r?.data?.counts?.byType?.[t.key] || 0) > 0)
    if (first) tab.value = first.key
  } catch (e) { /* 降级到默认页签，不阻塞页面 */ }
  await nextTick()
  inited.value = true
  fetchPage(true)
})

// ---------------- 安装 / 更新 ----------------
/** 覆盖类安装（更新/覆盖重装）先确认：整包替换会丢掉装之后本地改过的内容与旧附属文件（仅技能有覆盖语义） */
const install = (it, overwrite) => {
  if (overwrite) {
    Modal.confirm({
      title: it.hasUpdate ? '更新「' + it.name + '」？' : '覆盖重装「' + it.name + '」？',
      content: '将整包替换你装好的同名技能：装好后改过的内容，以及旧版本附带的参考文件与脚本会丢失。',
      okText: it.hasUpdate ? '更新' : '覆盖重装',
      cancelText: '取消',
      onOk: () => doInstall(it, true)
    })
    return
  }
  doInstall(it, false)
}
/** fork 类物品（应用模板/工作流）：每次安装都是新副本，没有覆盖语义、不用确认 */
const installCopy = it => doInstall(it, false)

/** 安装成功后的按类型文案（返回的实体名由后端给：skillName / workflowName / agentName / mcpName） */
const installedName = (it, data) =>
  data?.skillName || data?.workflowName || data?.agentName || data?.mcpName || it.name
const installedVerb = (it, data) => {
  if (it.itemType === 'mcp') return '已接入 MCP 服务「'
  if (it.itemType === 'workflow') return data?.workflowId ? '已复制为你的工作流「' : '已安装「'
  if (it.itemType === 'agent') return data?.agentId ? '已复制为你的智能体「' : '已安装「'
  return it.hasUpdate ? '已更新技能「' : '已安装技能「'
}
/** fork 裁剪告警：列表逐条呈现（知识库/MCP/技能引用没保住的说明，装完必须让人知道差了什么） */
const showWarnings = (name, warnings) => {
  const list = (warnings || []).filter(Boolean)
  if (!list.length) return
  Modal.info({
    title: '已安装「' + name + '」，注意以下调整',
    width: 560,
    content: h('ul', { style: 'padding-left:18px;margin:8px 0 0;line-height:1.9;' },
      list.map(w => h('li', w))),
    okText: '知道了'
  })
}

const doInstall = (it, overwrite) => {
  installing.value = { ...installing.value, [it.id]: true }
  installMarketItem(it.id, !!overwrite)
    .then(r => {
      if (!r || !r.success) throw new Error((r && r.msg) || '安装失败')
      const name = installedName(it, r.data)
      message.success(installedVerb(it, r.data) + name + '」')
      detailOpen.value = false
      refresh()
      refreshMarketUpdates()
      showWarnings(name, r.data?.warnings)
    })
    .catch(e => {
      const msg = errText(e)
      // 首装撞上同名（非市场来源的同名技能）：确认后覆盖重装（仅技能类型会走到）
      if (it.itemType === 'skill' && msg.includes('同名')) {
        Modal.confirm({
          title: '覆盖安装？',
          content: '你已经有一个同名技能。覆盖会整包替换它（旧版本附带的参考文件与脚本会丢失）。',
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
    message.success(it.online ? '已下架' : '已重新上架')
    refresh()
    refreshMarketUpdates()
  } catch (e) {
    message.error('操作失败：' + errText(e))
  }
}
const removeItem = it => {
  Modal.confirm({
    title: '删除「' + it.name + '」？',
    content: '这条内容与安装记录一起删除；已经装进同事目录的副本不受影响。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteMarketItem(it.id)
        if (!r || !r.success) throw new Error((r && r.msg) || '删除失败')
        message.success('已删除')
        refresh()
        refreshMarketUpdates()
      } catch (e) {
        message.error('删除失败：' + errText(e))
      }
    }
  })
}

// ---------------- 详情 / 下载 ----------------
let detailSeq = 0
const openDetail = async it => {
  const seq = ++detailSeq
  detailOpen.value = true
  detailLoading.value = true
  detailTab.value = 'desc'
  detail.value = { ...it }
  detailVersions.value = []
  detailVersionsLoading.value = true
  detailVersionsError.value = false
  // 版本历史与详情并行拉：切页签时不用再等一次
  getMarketItemVersions(it.id)
    .then(r => {
      if (seq !== detailSeq) return
      if (r && r.success) detailVersions.value = r.data?.items || []
      else detailVersionsError.value = true
    })
    .catch(() => { if (seq === detailSeq) detailVersionsError.value = true })
    .finally(() => { if (seq === detailSeq) detailVersionsLoading.value = false })
  try {
    const r = await getMarketItem(it.id)
    if (seq !== detailSeq) return
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    detail.value = r.data || it
  } catch (e) {
    if (seq === detailSeq) message.error('读取详情失败：' + errText(e))
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}
/** 详情主操作：有更新先看说明；首次安装按类型走复制/安装 */
const onDetailPrimary = it => {
  if (it.hasUpdate) {
    detailOpen.value = false
    openUpdate(it)
    return
  }
  if (it.itemType === 'skill') install(it, !!it.installed)
  else installCopy(it)
}

const download = async it => {
  try {
    await downloadMarketItem(it.id, it.name, it.itemType)
  } catch (e) {
    message.error('下载失败：' + errText(e))
  }
}

/** 上架成功的提示写的是「市场 → 我的上架」，这里就落在那一页，别让用户自己找 */
const onPublished = () => {
  // 发布完成直接落到「我的上架」：清掉搜索/标签，避免新条目被过滤条件挡住
  keyword.value = ''
  appliedKeyword.value = ''
  drill.value = false
  tag.value = ''
  if (tab.value !== 'mine') tab.value = 'mine'
  else reload(true)
}
</script>

<style scoped>
.head-r { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.head-search { width: 300px; }
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
/* 全局搜索时结果跨类型，页签不参与筛选：隐藏选中态，避免「MCP 高亮却显示工作流结果」的误导 */
.mk-tabs.is-searching :deep(.ant-tabs-tab-active) .mk-tab { color: var(--app-text); font-weight: 400; }
.mk-tabs.is-searching :deep(.ant-tabs-tab-active) .mk-badge { background: var(--app-accent-weak); color: var(--app-text3); }
.mk-tabs.is-searching :deep(.ant-tabs-ink-bar) { display: none; }
.mk-badge { font-style: normal; font-size: 11px; line-height: 16px; margin-left: 6px; padding: 0 6px;
  border-radius: 999px; background: var(--app-accent-weak); color: var(--app-text3); }
.mk-tabs :deep(.ant-tabs-tab-active) .mk-badge { background: var(--app-accent); color: #fff; }

/* 工具行：统一 8px 间隔，控件 24-26px，避免输入框底边被裁切 */
.mk-toolbar { display: flex; align-items: center; gap: 8px; padding-bottom: 12px; }
.mk-sort { width: 116px; flex: none; }
.mk-chip-row { display: flex; gap: 6px; flex: 1; min-width: 0; overflow: hidden; }
.mk-chip { height: 24px; display: inline-flex; align-items: center; padding: 0 10px; flex: none;
  border: 1px solid var(--app-border); border-radius: 999px; background: var(--app-panel);
  font-size: 11px; color: var(--app-text2); cursor: pointer; white-space: nowrap; }
.mk-chip:hover { color: var(--app-accent); border-color: var(--app-accent-border); }
.mk-chip.on { border-color: var(--app-accent-border); background: var(--app-accent-weak);
  color: var(--app-accent); font-weight: 500; }
.mk-search-hint { padding-bottom: 10px; font-size: 12px; color: var(--app-text3); }
.mk-search-hint b { color: var(--app-text); font-weight: 600; }
/* 我安装的：汇总条（数量与侧栏红点同源） */
.mk-summary { display: flex; align-items: center; gap: 12px; padding: 10px 14px; margin-bottom: 12px;
  background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); border-radius: 8px; }
.mk-summary-text { font-size: 13px; color: var(--app-text); }
.mk-summary-text b { color: var(--app-accent); font-weight: 600; }
/* 我的上架：安装趋势 + 最近安装人 */
.mk-stats { margin-bottom: 8px; }
.mk-stats-line { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--app-text3); }
.mk-stats-line b { color: var(--app-text2); font-weight: 600; }
.mk-spark { display: inline-flex; align-items: flex-end; gap: 2px; height: 16px; flex: 1; min-width: 0; overflow: hidden; }
.mk-spark i { width: 3px; flex: none; border-radius: 1px; background: var(--app-accent); opacity: .75; }
.mk-installers { display: flex; gap: 4px; margin-top: 6px; }
.mk-ava { width: 18px; height: 18px; border-radius: 50%; background: var(--app-panel-2); color: var(--app-text2);
  font-size: 10px; display: inline-flex; align-items: center; justify-content: center; }

/* 卡片网格：1600 宽三列，窄屏两列/单列 */
.mk-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 18px; }
@media (max-width: 1360px) { .mk-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 980px) { .mk-grid { grid-template-columns: minmax(0, 1fr); } }
.mk-card { display: flex; flex-direction: column; min-height: 172px; padding: 13px 15px 12px; }
.mk-card:hover { border-color: var(--app-accent-border); }
.mk-card-head { display: flex; align-items: center; gap: 6px; min-width: 0; }
.mk-type-icon { flex: none; width: 18px; height: 18px; border-radius: 4px; display: inline-flex;
  align-items: center; justify-content: center; font-size: 11px; }
.mk-type-icon.skill { background: var(--app-panel-2); color: var(--app-text2); }
.mk-type-icon.agent { background: var(--app-ok-weak); color: var(--app-ok); }
.mk-type-icon.workflow { background: var(--app-warn-weak); color: var(--app-warn-text); }
.mk-type-icon.mcp { background: #f3e8ff; color: #7c3aed; }
html[data-theme='dark'] .mk-type-icon.mcp { background: #2a2140; color: #b69cff; }
.mk-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mk-card-desc { font-size: 12px; color: var(--app-text3); line-height: 19px; margin: 7px 0; height: 38px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.mk-card-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.mk-tag { border: 0; background: var(--app-panel-2); color: var(--app-text2); border-radius: 999px;
  padding: 1px 8px; font-size: 11px; line-height: 18px; cursor: pointer; }
.mk-tag:hover { color: var(--app-accent); }
.mk-card-meta { display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--app-text3);
  margin-bottom: 8px; white-space: nowrap; overflow: hidden; }
.mk-author { max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mk-ver { background: var(--app-panel-2); border-radius: 999px; padding: 0 7px; line-height: 17px;
  color: var(--app-text2); font-size: 10px; flex: none; }
.mk-ver.delta { background: var(--app-accent-weak); color: var(--app-accent); }
.mk-note { font-size: 11px; color: var(--app-text3); }
/* MCP 目录条目：官方描边徽标（区别于普通 pill 的实心底） */
.mk-official { border: 1px solid var(--app-accent); color: var(--app-accent); background: transparent; }
/* MCP 地址行：传输类型等宽 pill + 等宽地址（一眼可辨装的是什么） */
.mk-url-row { display: flex; align-items: center; gap: 7px; min-width: 0; margin-bottom: 6px; }
.mk-transport { flex: none; font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 10.5px; line-height: 17px; padding: 0 6px; border-radius: 3px;
  background: var(--app-panel-2); color: var(--app-text3); }
.mk-url { min-width: 0; flex: 1; font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 11px; color: var(--app-text3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* MCP 详情弹窗：认证提醒常驻条 */
.mk-auth-note { margin-top: 10px; padding: 9px 11px; border: 1px solid #f0dfb6; background: #faf3e6;
  border-radius: 8px; font-size: 12px; line-height: 1.7; color: #a3691b; }
html[data-theme='dark'] .mk-auth-note { border-color: rgba(240, 223, 182, .25); background: rgba(250, 243, 230, .08); }
.mk-card-foot { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: auto;
  border-top: 1px dashed var(--app-border); padding-top: 6px; }
.app-link-btn.accent { color: var(--app-accent); font-weight: 600; }
.app-link-btn:disabled { opacity: .55; cursor: not-allowed; }
.mk-foot-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: 4px 0 0; }
.mk-load-more { display: flex; justify-content: center; margin: 2px 0 6px; }

/* 全局搜索结果：按类型分组的紧凑行 */
.mk-res-group { margin-bottom: 14px; }
.mk-res-head { display: flex; align-items: center; gap: 8px; padding: 0 2px 7px; }
.mk-res-head .t { font-size: 12px; font-weight: 600; color: var(--app-text); }
.mk-res-head .n { font-size: 11px; color: var(--app-text3); }
.mk-res-more { margin-left: auto; border: none; background: transparent; padding: 0;
  font-size: 11.5px; color: var(--app-accent); cursor: pointer; }
.mk-res-row { display: flex; align-items: center; gap: 10px; min-height: 56px; padding: 8px 12px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px; margin-bottom: 6px; }
.mk-res-main { flex: 1; min-width: 0; }
.mk-res-name { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500; color: var(--app-text); }
.mk-res-name .txt { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mk-res-desc { margin-top: 2px; font-size: 11.5px; color: var(--app-text3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mk-res-meta { flex: none; display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--app-text3); }
.mk-res-act { flex: none; display: flex; align-items: center; gap: 10px; }

.mk-view-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 12px; color: var(--app-text3); margin-bottom: 8px; }
.mk-view-tabs { display: flex; gap: 14px; border-bottom: 1px solid var(--app-border); padding-bottom: 6px; margin-bottom: 8px; }
.mk-view-tabs .app-link-btn { position: relative; color: var(--app-text3); padding-bottom: 4px; }
.mk-view-tabs .app-link-btn.on { color: var(--app-accent); font-weight: 600; }
.mk-view-tabs .app-link-btn.on::after { content: ''; position: absolute; left: 0; right: 0; bottom: -7px;
  height: 2px; border-radius: 1px; background: var(--app-accent); }
.mk-view-body { max-height: 52vh; overflow-y: auto; border: 1px solid var(--app-border); border-radius: 6px; padding: 12px 14px; }
.mk-versions { border: 1px solid var(--app-border); border-radius: 8px; max-height: 52vh; overflow-y: auto;
  padding: 2px 12px; }
.mk-versions-empty { padding: 18px; text-align: center; font-size: 12px; color: var(--app-text3); }
.mk-version { padding: 9px 0; border-bottom: 1px dashed var(--app-border); }
.mk-version:last-child { border-bottom: 0; }
.mk-version-head { display: flex; align-items: baseline; gap: 8px; }
.mk-version-no { font-size: 12px; font-weight: 600; color: var(--app-text); }
.mk-version-date { font-size: 11px; color: var(--app-text3); }
.mk-version-note { margin-top: 3px; font-size: 12px; line-height: 1.7; color: var(--app-text2); white-space: pre-wrap; }
.mk-version-note.muted { color: var(--app-text3); }
</style>
