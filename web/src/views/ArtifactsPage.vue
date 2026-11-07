<template>
  <div class="app-page art-page">
    <div class="app-page-head art-head">
      <h1 class="app-page-title">我的产物</h1>
      <span :class="['head-hint-plain', 'art-head-hint', { 'art-selection-hint': selectionMode }]">
        {{ selectionMode ? `已选 ${selectedIds.length} 项` : '模型在回答里生成的可下载文件' }}
      </span>
      <div class="art-head-tools">
        <a-input ref="searchRef" v-model:value="filters.keyword" class="art-search"
                 placeholder="按文件名或说明搜索" allow-clear
                 @change="onKeywordInput" @press-enter="applyFilters">
          <template #prefix><search-outlined /></template>
        </a-input>
        <div v-if="!selectionMode" class="art-seg" role="group" aria-label="视图切换">
          <button type="button" :class="['art-seg-btn', { on: viewMode === 'list' }]"
                  title="列表视图" aria-label="列表视图" @click="setView('list')">
            <unordered-list-outlined />
          </button>
          <button type="button" :class="['art-seg-btn', { on: viewMode === 'grid' }]"
                  title="网格视图" aria-label="网格视图" @click="setView('grid')">
            <appstore-outlined />
          </button>
        </div>
        <button v-if="selectionMode" type="button" class="app-btn ghost small" @click="exitSelection">
          <close-outlined /> 退出选择
        </button>
        <button v-else type="button" class="app-btn ghost small" :disabled="loading" @click="load">
          <reload-outlined /> 刷新
        </button>
      </div>
    </div>

    <div class="app-page-body art-body">
      <!-- 概览：全局口径，不随筛选变化；点数字可直接筛 -->
      <div v-if="stats" class="art-stats">
        <button type="button" class="art-stat art-stat-btn" :class="{ on: !hasFilters }"
                title="查看全部产物" @click="resetAll">
          <span class="art-stat-v">{{ stats.count }}</span>
          <span class="art-stat-l">全部产物</span>
        </button>
        <div class="art-stat">
          <span class="art-stat-v">{{ fmtSize(stats.totalSize) }}</span>
          <span class="art-stat-l">占用空间</span>
        </div>
        <button type="button" class="art-stat art-stat-btn warn"
                :class="{ on: filters.expiring, disabled: !stats.expiring7 }"
                :disabled="!stats.expiring7"
                :title="stats.expiring7 ? '只看 7 天内将被清理的产物' : '没有即将清理的产物'"
                @click="toggleExpiring">
          <span class="art-stat-v">{{ stats.expiring7 }}</span>
          <span class="art-stat-l">7 天内清理</span>
        </button>
        <button type="button" class="art-stat art-stat-btn"
                :class="{ on: filters.timeRange === 'mtd', disabled: !stats.monthAdded }"
                :disabled="!stats.monthAdded"
                title="查看本月新增的产物"
                @click="selectMonth">
          <span class="art-stat-v">+{{ stats.monthAdded }}</span>
          <span class="art-stat-l">本月新增</span>
        </button>
      </div>

      <div class="art-filter">
        <div class="art-frow">
          <span class="art-f-label">工作区</span>
          <button type="button" :class="['art-chip', { on: !filters.projectId }]"
                  @click="selectProject('')">全部</button>
          <button v-for="p in realProjects" :key="p.id" type="button"
                  :class="['art-chip', { on: filters.projectId === p.id }]"
                  @click="selectProject(p.id)">
            <i class="art-dot art-dot-project"></i>{{ p.displayName || p.name || '未命名工作区' }}
          </button>
          <div class="art-spacer"></div>
          <label class="art-select" title="排序">
            <sort-ascending-outlined class="art-select-ico" />
            <select v-model="filters.sort" aria-label="排序" @change="onSortChange">
              <option value="time_desc">最新优先</option>
              <option value="time_asc">最早优先</option>
              <option value="size_desc">从大到小</option>
              <option value="size_asc">从小到大</option>
              <option value="name_asc">名称 A→Z</option>
            </select>
            <down-outlined class="art-select-ico" />
          </label>
          <label class="art-select" title="时间范围">
            <calendar-outlined class="art-select-ico" />
            <select v-model="filters.timeRange" aria-label="时间范围" @change="applyFilters">
              <option value="all">全部时间</option>
              <option value="7d">近 7 天</option>
              <option value="30d">近 30 天</option>
              <option value="90d">近 90 天</option>
              <option value="mtd">本月</option>
            </select>
            <down-outlined class="art-select-ico" />
          </label>
        </div>
        <div class="art-frow">
          <span class="art-f-label">类型</span>
          <button v-for="k in KIND_OPTIONS" :key="k.key" type="button"
                  :class="['art-chip', { on: filters.kind === k.key }]"
                  @click="selectKind(k.key)">
            <i v-if="k.key !== 'all'" class="art-dot" :class="'k-' + k.key"></i>{{ k.label }}
          </button>
          <div class="art-spacer"></div>
          <button type="button" class="art-chip art-chip-warn" :class="{ on: filters.expiring }"
                  @click="toggleExpiring">
            <clock-circle-outlined /> 仅看即将清理
          </button>
        </div>
      </div>

      <div class="art-result">
        <template v-if="selectionMode">
          <b>选择模式</b>
          <span class="art-result-hint">点行即可勾选，点「预览」仍会打开预览台</span>
        </template>
        <template v-else>
          <b>共 {{ resultCount }} 个产物</b>
          <span class="art-result-dot">·</span>
          <span class="art-result-size">{{ fmtSize(resultSize) }}</span>
        </template>
        <div v-if="!selectionMode" class="art-result-actions">
          <label class="art-select art-sort-mobile" title="排序">
            <sort-ascending-outlined class="art-select-ico" />
            <select v-model="filters.sort" aria-label="排序" @change="onSortChange">
              <option value="time_desc">最新优先</option>
              <option value="time_asc">最早优先</option>
              <option value="size_desc">从大到小</option>
              <option value="size_asc">从小到大</option>
              <option value="name_asc">名称 A→Z</option>
            </select>
            <down-outlined class="art-select-ico" />
          </label>
          <label class="art-select art-time-mobile" title="时间范围">
            <calendar-outlined class="art-select-ico" />
            <select v-model="filters.timeRange" aria-label="时间范围" @change="applyFilters">
              <option value="all">全部时间</option>
              <option value="7d">近 7 天</option>
              <option value="30d">近 30 天</option>
              <option value="90d">近 90 天</option>
              <option value="mtd">本月</option>
            </select>
            <down-outlined class="art-select-ico" />
          </label>
          <button type="button" class="art-link" @click="enterSelection">选择</button>
          <button v-if="hasFilters" type="button" class="art-link" @click="clearFilters">清除筛选</button>
        </div>
      </div>

      <div v-if="error && !rows.length" class="app-card art-empty art-error">
        <div class="art-empty-ico error"><exclamation-circle-outlined /></div>
        <p class="art-empty-title">产物列表加载失败</p>
        <p class="head-hint-plain art-empty-hint">{{ error }}</p>
        <button type="button" class="app-btn" @click="retry">重试</button>
      </div>

      <div v-else-if="loading && !rows.length" class="art-skeleton">
        <div v-for="i in 4" :key="i" class="art-sk-row">
          <span class="art-sk-square"></span>
          <div class="art-sk-lines"><i></i><i></i><i></i></div>
        </div>
      </div>

      <div v-else-if="!visibleRows.length" class="app-card art-empty">
        <div class="art-empty-ico"><component :is="emptyIcon" /></div>
        <p class="art-empty-title">{{ emptyTitle }}</p>
        <p class="head-hint-plain art-empty-hint">{{ emptyHint }}</p>
        <button v-if="hasFilters" type="button" class="app-btn" @click="clearFilters">清除筛选</button>
        <router-link v-else-if="!rows.length" to="/chat" class="app-btn art-empty-action">
          <plus-outlined /> 去新建对话
        </router-link>
      </div>

      <template v-else>
        <div v-if="error" class="art-error-bar">
          <exclamation-circle-outlined />
          <span>{{ error }}</span>
          <button type="button" class="art-link" @click="retry">重试</button>
        </div>

        <a-spin :spinning="loading">
          <!-- 列表视图：按工作区分组 -->
          <div v-if="viewMode === 'list'" class="art-list">
            <template v-for="g in groups" :key="g.key || '__unassigned__'">
              <div v-if="groups.length > 1" class="art-group">
                <span class="art-group-name">{{ g.title }}</span>
                <span class="art-group-count">{{ g.rows.length }}</span>
              </div>
              <div v-for="r in g.rows" :key="r.id"
                   :class="['art-row', { picked: selectedIds.includes(r.id), selecting: selectionMode }]"
                   @click="onRowClick(r)">
                <label class="art-check" @click.stop>
                  <a-checkbox :checked="selectedIds.includes(r.id)"
                              :aria-label="'选择 ' + (r.displayName || r.filename)"
                              @change="e => toggleSelect(r.id, e.target.checked)" />
                </label>
                <span class="art-type" :class="'k-' + kindOfRow(r)">
                  <component :is="kindIcon(r)" />
                </span>
                <div class="art-main">
                  <div class="art-line">
                    <template v-if="renameId === r.id">
                      <div class="art-rename" @click.stop>
                        <a-input :ref="setRenameInput" v-model:value="renameDraft" size="small"
                                 :maxlength="60" placeholder="显示名" @press-enter="saveRename(r)"
                                 @keydown.esc="cancelRename" />
                        <button type="button" class="art-rename-ok" :disabled="renameSaving"
                                title="保存" @click="saveRename(r)"><check-outlined /></button>
                        <button type="button" class="art-rename-no" title="取消" @click="cancelRename">
                          <close-outlined />
                        </button>
                      </div>
                    </template>
                    <template v-else>
                      <span class="art-name" :title="r.displayName || r.filename"
                            @dblclick.stop="startRename(r)">{{ r.displayName || baseName(r.filename) }}</span>
                      <span class="art-badge" :class="'k-' + kindOfRow(r)">{{ extOfRow(r) }}</span>
                    </template>
                  </div>
                  <div v-if="r.description" class="art-desc" :title="r.description">{{ r.description }}</div>
                  <div class="art-meta">
                    <span>{{ fmtSize(r.size) }}</span>
                    <i class="art-dotsep">·</i>
                    <span>{{ fmtTime(r.createTime) }}</span>
                    <i class="art-dotsep">·</i>
                    <span :class="['art-expire', { warn: expireSoon(r.expireTime) }]"
                          :title="r.expireTime ? '预计清理时间：' + fmtTime(r.expireTime) : '产物不会被自动清理'">
                      {{ fmtExpire(r.expireTime) }}
                    </span>
                    <template v-if="isRenamed(r)">
                      <i class="art-dotsep">·</i>
                      <span class="art-origin" :title="r.originalFilename || r.filename">
                        原名：{{ r.originalFilename || r.filename }}
                      </span>
                    </template>
                    <template v-if="r.sessionId">
                      <i class="art-dotsep">·</i>
                      <router-link v-if="r.sessionExists" class="art-src"
                                   :to="{ path: '/chat', query: { sid: r.sessionId } }"
                                   :title="'来源：' + sourceTitle(r)" @click.stop>
                        <link-outlined /><span>来源：{{ sourceTitle(r) }}</span><arrow-right-outlined class="art-src-arrow" />
                      </router-link>
                      <span v-else class="art-src gone" title="来源会话已删除">
                        <link-outlined /><span>来源会话已删除</span>
                      </span>
                    </template>
                  </div>
                </div>
                <div class="art-acts" @click.stop>
                  <a v-if="canPreview(r)" :href="resolveImg(r.url)" target="_blank" rel="noopener"
                     data-pv :data-pv-name="r.filename" :data-pv-ext="artifactExt(r)"
                     :data-pv-kind="previewKindOf(r)" :data-pv-size="r.size" :data-pv-id="r.id"
                     class="app-btn ghost small art-preview"
                     title="预览（按住 Ctrl 或中键开成新标签页）">
                    <eye-outlined /> 预览
                  </a>
                  <template v-if="!selectionMode">
                    <a :href="resolveImg(r.url)" :download="r.originalFilename || r.filename"
                       class="app-btn ghost small art-icon-act" title="下载">
                      <download-outlined />
                    </a>
                    <a-dropdown :trigger="['click']" placement="bottomRight">
                      <button type="button" class="app-btn ghost small art-icon-act" title="更多操作"
                              aria-label="更多操作"><more-outlined /></button>
                      <template #overlay>
                        <a-menu class="art-menu" @click="e => onMenuClick(e.key, r)">
                          <a-menu-item key="rename"><edit-outlined /> 重命名</a-menu-item>
                          <a-menu-item key="move"><folder-open-outlined /> 移动到工作区</a-menu-item>
                          <a-menu-item key="copy"><link-outlined /> 复制下载链接</a-menu-item>
                          <a-menu-divider />
                          <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </template>
                </div>
              </div>
            </template>
          </div>

          <!-- 网格视图：平铺卡片，图片用真实缩略图，其余用类型化迷你示意块 -->
          <div v-else class="art-cards">
            <div v-for="r in visibleRows" :key="r.id"
                 :class="['art-card', { picked: selectedIds.includes(r.id) }]"
                 @click="onCardClick(r)">
              <div class="art-thumb" :class="'k-' + kindOfRow(r)">
                <span class="art-thumb-badge" :class="'k-' + kindOfRow(r)">{{ extOfRow(r) }}</span>
                <a-checkbox :checked="selectedIds.includes(r.id)"
                            :class="['art-thumb-check', { on: selectionMode }]"
                            :aria-label="'选择 ' + (r.displayName || r.filename)"
                            @click.stop
                            @change="e => toggleSelect(r.id, e.target.checked)" />
                <img v-if="kindOfRow(r) === 'image'" class="art-thumb-img"
                     :src="resolveImg(r.url)" :alt="r.displayName || r.filename" @error="onImgError" />
                <div v-else class="art-mini" :class="'art-mini-' + kindOfRow(r)">
                  <template v-if="kindOfRow(r) === 'page'">
                    <div class="art-mini-bar"><i></i><i></i><i></i></div>
                    <div class="art-mini-line accent"></div>
                    <div class="art-mini-line"></div>
                    <div class="art-mini-line short"></div>
                  </template>
                  <template v-else-if="kindOfRow(r) === 'doc'">
                    <div class="art-mini-line accent"></div>
                    <div class="art-mini-line"></div>
                    <div class="art-mini-line"></div>
                    <div class="art-mini-line short"></div>
                  </template>
                  <template v-else-if="kindOfRow(r) === 'sheet'">
                    <div class="art-mini-table">
                      <i v-for="n in 9" :key="n" :class="{ head: n <= 3 }"></i>
                    </div>
                  </template>
                  <template v-else-if="kindOfRow(r) === 'code'">
                    <div class="art-mini-code">
                      <i v-for="n in 4" :key="n" :class="{ short: n === 2 || n === 4 }"></i>
                    </div>
                  </template>
                  <template v-else-if="kindOfRow(r) === 'archive'">
                    <div class="art-archive-stack"><i></i><i></i><i></i></div>
                  </template>
                  <template v-else>
                    <component :is="kindIcon(r)" class="art-mini-glyph" />
                  </template>
                </div>
                <div v-if="!selectionMode" class="art-card-acts" @click.stop>
                  <a v-if="canPreview(r)" :href="resolveImg(r.url)" target="_blank" rel="noopener"
                     data-pv :data-pv-name="r.filename" :data-pv-ext="artifactExt(r)"
                     :data-pv-kind="previewKindOf(r)" :data-pv-size="r.size" :data-pv-id="r.id"
                     class="art-card-act" title="预览"><eye-outlined /></a>
                  <a-dropdown :trigger="['click']" placement="bottomRight">
                    <button type="button" class="art-card-act" title="更多操作" aria-label="更多操作">
                      <more-outlined />
                    </button>
                    <template #overlay>
                      <a-menu class="art-menu" @click="e => onMenuClick(e.key, r)">
                        <a-menu-item key="rename"><edit-outlined /> 重命名</a-menu-item>
                        <a-menu-item key="move"><folder-open-outlined /> 移动到工作区</a-menu-item>
                        <a-menu-item key="copy"><link-outlined /> 复制下载链接</a-menu-item>
                        <a-menu-divider />
                        <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </div>
              </div>
              <div class="art-card-body">
                <div class="art-card-name" :title="r.displayName || r.filename">
                  {{ r.displayName || baseName(r.filename) }}
                </div>
                <div class="art-card-meta">
                  <span>{{ fmtSize(r.size) }}</span><span>·</span><span>{{ gridTime(r.createTime) }}</span>
                </div>
                <div v-if="r.sessionId" class="art-card-src" :class="{ gone: !r.sessionExists }"
                     :title="r.sessionExists ? '来源：' + sourceTitle(r) : '来源会话已删除'">
                  {{ r.sessionExists ? '来源：' + sourceTitle(r) : '来源会话已删除' }}
                </div>
              </div>
            </div>
          </div>
        </a-spin>

        <!-- 批量栏：选择模式且有选中项时出现；桌面在内容区底部，移动端贴屏幕底 -->
        <div v-if="selectionMode && selectedIds.length" class="art-bulk">
          <div class="art-bulk-sel">
            <a-checkbox :checked="allChecked" :indeterminate="someChecked" aria-label="全选当前结果"
                        @change="toggleAll" />
            <span>已选 {{ selectedIds.length }} 项</span>
            <span class="art-bulk-size">· {{ fmtSize(selectedSize) }}</span>
          </div>
          <div class="art-spacer"></div>
          <button type="button" class="app-btn ghost small art-bulk-select" @click="toggleAll">
            {{ allChecked ? '取消全选' : '全选' }}
          </button>
          <button type="button" class="app-btn ghost small art-bulk-download" :disabled="batchBusy"
                  @click="downloadSelected">
            <download-outlined /> {{ batchBusy ? '打包中…' : (selectedIds.length === 1 ? '下载' : '下载 ZIP') }}
          </button>
          <button type="button" class="app-btn ghost small" :disabled="batchBusy" @click="askBatchMove">
            <folder-open-outlined /> <span class="art-bulk-label">移动到工作区</span>
          </button>
          <button type="button" class="app-btn danger small" :disabled="batchBusy" @click="askBatchDelete">
            <delete-outlined /> <span class="art-bulk-label">删除</span>
          </button>
        </div>
      </template>

      <MoveToWorkspaceModal v-model:open="moveOpen" :projects="projects"
                            :current-id="moveTarget ? (moveTarget.projectId || '') : ''"
                            :title="moveTitle" :note="moveNote" :saving="moveSaving"
                            @moved="doMove" />
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  AppstoreOutlined, ArrowRightOutlined, CalendarOutlined, CheckOutlined, ClockCircleOutlined,
  CloseOutlined, CodeOutlined, DeleteOutlined, DownOutlined, DownloadOutlined, EditOutlined,
  ExclamationCircleOutlined, EyeOutlined, FileImageOutlined, FileOutlined, FileTextOutlined,
  FileZipOutlined, FolderOpenOutlined, GlobalOutlined, LinkOutlined, MoreOutlined, PlusOutlined,
  ReloadOutlined, SearchOutlined, SortAscendingOutlined, TableOutlined, UnorderedListOutlined
} from '@ant-design/icons-vue'
import {
  deleteArtifact, deleteArtifactsBatch, downloadArtifactsZip, getArtifactStats, listArtifacts,
  listProjects, moveArtifactProject, moveArtifactsBatch, renameArtifact
} from '../api'
import { onImgError, resolveImg } from '../utils/markdown'
import { artifactExt, canPreview, previewKindOf } from '../utils/artifactView'
import { projectOnThisComputer } from '../utils/localExecutor'
import { copyText } from '../utils/clipboard'
import MoveToWorkspaceModal from '../components/MoveToWorkspaceModal.vue'

// ==================== 类型分组与图标 ====================
// 判据以服务端下发的 kind 为准（扩展名清单只在后端 PreviewTypes 一处）；下面这张只是老接口的兜底。
const KIND_OPTIONS = [
  { key: 'all', label: '全部' },
  { key: 'page', label: '网页' },
  { key: 'doc', label: '文档' },
  { key: 'sheet', label: '表格' },
  { key: 'image', label: '图片' },
  { key: 'code', label: '代码' },
  { key: 'archive', label: '压缩包' },
  { key: 'other', label: '其他' }
]
const KIND_ICONS = {
  page: GlobalOutlined,
  doc: FileTextOutlined,
  sheet: TableOutlined,
  image: FileImageOutlined,
  code: CodeOutlined,
  archive: FileZipOutlined,
  other: FileOutlined
}
const KIND_BY_EXT = {
  html: 'page', htm: 'page',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', bmp: 'image', svg: 'image',
  md: 'doc', markdown: 'doc', txt: 'doc', log: 'doc', pdf: 'doc', doc: 'doc', docx: 'doc', ppt: 'doc', pptx: 'doc',
  csv: 'sheet', tsv: 'sheet', xlsx: 'sheet', xls: 'sheet', ods: 'sheet',
  json: 'code', yaml: 'code', yml: 'code', xml: 'code', toml: 'code', ini: 'code', conf: 'code',
  properties: 'code', py: 'code', js: 'code', mjs: 'code', ts: 'code', jsx: 'code', tsx: 'code',
  java: 'code', go: 'code', rs: 'code', sql: 'code', sh: 'code', bash: 'code', zsh: 'code',
  css: 'code', scss: 'code', vue: 'code',
  zip: 'archive', gz: 'archive', tar: 'archive', '7z': 'archive', rar: 'archive', bz2: 'archive'
}

const kindOfRow = r => {
  if (!r) return 'other'
  if (r.kind && KIND_ICONS[r.kind]) return r.kind
  return KIND_BY_EXT[artifactExt(r)] || 'other'
}
const kindIcon = r => KIND_ICONS[kindOfRow(r)] || FileOutlined
const extOfRow = r => String(r.ext || artifactExt(r) || '').toUpperCase() || 'FILE'
const baseName = name => {
  const s = String(name || '')
  const i = s.lastIndexOf('.')
  return i > 0 ? s.slice(0, i) : s
}
const isRenamed = r => !!r.displayName && r.displayName !== baseName(r.originalFilename || r.filename)
const sourceTitle = r => r.sessionTitle || '会话'

// ==================== 页面状态 ====================
const VIEW_KEY = 'artifacts.view'
const SORT_KEY = 'artifacts.sort'
const readView = () => {
  try { return localStorage.getItem(VIEW_KEY) === 'grid' ? 'grid' : 'list' } catch (e) { return 'list' }
}
const readSort = () => {
  try {
    const v = localStorage.getItem(SORT_KEY)
    return ['time_desc', 'time_asc', 'size_desc', 'size_asc', 'name_asc'].includes(v) ? v : 'time_desc'
  } catch (e) { return 'time_desc' }
}

const rows = ref([])
const projects = ref([])
const stats = ref(null)
const loading = ref(false)
const error = ref('')
const viewMode = ref(readView())
const selectionMode = ref(false)
const selectedIds = ref([])
const batchBusy = ref(false)
const searchRef = ref(null)
const filters = reactive({
  keyword: '',
  projectId: '',
  kind: 'all',
  timeRange: 'all',
  expiring: false,
  sort: readSort()
})

// 工作区容器：产物按工作区分组展示。空数组时退化成单列表（接口挂掉也不该让页面白屏）
const realProjects = computed(() => projects.value.filter(p =>
  p.selectionStatus !== 'implicit' && projectOnThisComputer(p)))
/** 别的电脑上的工作区：产物仍属账号，但这一页不列（否则同名工作区会出现两档分不清的 chip） */
const otherComputerIds = computed(() => new Set(
  projects.value.filter(p => p.selectionStatus !== 'implicit' && !projectOnThisComputer(p)).map(p => p.id)))
/** 当前要展示的产物：接口按账号返回，这里剔掉属于别的电脑工作区的那部分（勾选/全选也据此算） */
const visibleRows = computed(() => rows.value.filter(r => !otherComputerIds.value.has(r.projectId)))
/** implicit 兜底容器的 id：它的产物在分组时并入「会话」栏，不再单独出一个兜底容器分组 */
const implicitProjectId = computed(() => (projects.value.find(p => p.selectionStatus === 'implicit') || {}).id || '')

const hasFilters = computed(() => !!filters.keyword.trim() || !!filters.projectId
  || filters.kind !== 'all' || filters.timeRange !== 'all' || filters.expiring)
const resultCount = computed(() => visibleRows.value.length)
const resultSize = computed(() => visibleRows.value.reduce((n, r) => n + (Number(r.size) || 0), 0))

const selectedRows = computed(() => visibleRows.value.filter(r => selectedIds.value.includes(r.id)))
const selectedSize = computed(() => selectedRows.value.reduce((n, r) => n + (Number(r.size) || 0), 0))
const allChecked = computed(() => visibleRows.value.length > 0 && selectedIds.value.length === visibleRows.value.length)
const someChecked = computed(() => selectedIds.value.length > 0 && selectedIds.value.length < visibleRows.value.length)

const emptyTitle = computed(() => {
  if (hasFilters.value) return '没有匹配的产物'
  return rows.value.length ? '产物在别的电脑的工作区里' : '还没有产物'
})
const emptyHint = computed(() => {
  if (hasFilters.value) return '试试更短的关键词，或清除类型/时间筛选'
  return rows.value.length
    ? '「我的产物」只列这台电脑上的工作区，换到那台电脑后可见'
    : '在对话里让 AI 把结果整理成一份清单、表格或网页，生成的文件会自动出现在这里。'
})
const emptyIcon = computed(() => (hasFilters.value ? SearchOutlined : FileTextOutlined))

// ==================== 列表 / 分组 ====================
const groups = computed(() => {
  const byKey = new Map()
  for (const r of visibleRows.value) {
    const raw = r.projectId || ''
    const k = raw && raw === implicitProjectId.value ? '' : raw
    if (!byKey.has(k)) byKey.set(k, [])
    byKey.get(k).push(r)
  }
  const out = []
  for (const p of realProjects.value) {
    if (byKey.has(p.id)) out.push({ key: p.id, title: p.displayName || p.name || '未命名工作区', rows: byKey.get(p.id) })
  }
  // 接口里已经不存在的工作区（被删）单独兜住，不让这些产物的分组凭空消失
  for (const [k, list] of byKey) {
    if (!k) continue
    if (!projects.value.some(p => p.id === k)) out.push({ key: k, title: '已删除的工作区', rows: list })
  }
  if (byKey.has('')) out.push({ key: '', title: '会话', rows: byKey.get('') })
  return out
})

// ==================== 加载 ====================
let reqSeq = 0
const load = async () => {
  const seq = ++reqSeq
  loading.value = true
  error.value = ''
  try {
    const r = await listArtifacts({
      keyword: filters.keyword.trim(),
      projectId: filters.projectId,
      kind: filters.kind,
      timeRange: filters.timeRange,
      expiring: filters.expiring,
      sort: filters.sort
    })
    if (seq !== reqSeq) return
    rows.value = (r && r.data) || []
    // 勾选只保留当前列表里仍存在的产物（搜索/刷新后失效的勾选自动剔除）
    const ids = new Set(visibleRows.value.map(x => x.id))
    selectedIds.value = selectedIds.value.filter(id => ids.has(id))
  } catch (e) {
    if (seq !== reqSeq) return
    // 列表失败要说清；已有结果时保留上一次结果，由页内错误条提示重试
    error.value = e.message || '请刷新重试'
    if (!rows.value.length) rows.value = []
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}
const retry = () => { load(); loadStats() }

const loadStats = async () => {
  try {
    const r = await getArtifactStats()
    stats.value = (r && r.data) || null
  } catch (e) {
    // 统计是辅助信息，失败只隐藏概览，不影响列表
    stats.value = null
  }
}
const loadProjects = async () => {
  try {
    const r = await listProjects()
    projects.value = (r && r.data) || []
  } catch (e) {
    projects.value = []
  }
}

// ==================== 筛选 / 排序 / 视图 ====================
const applyFilters = () => {
  if (selectionMode.value) exitSelection()
  load()
}
const resetAll = () => {
  filters.keyword = ''
  filters.projectId = ''
  filters.kind = 'all'
  filters.timeRange = 'all'
  filters.expiring = false
  applyFilters()
}
const clearFilters = resetAll
const selectProject = id => {
  if (filters.projectId === id) return
  filters.projectId = id
  applyFilters()
}
const selectKind = k => {
  if (filters.kind === k) return
  filters.kind = k
  applyFilters()
}
const toggleExpiring = () => {
  filters.expiring = !filters.expiring
  applyFilters()
}
const selectMonth = () => {
  filters.timeRange = filters.timeRange === 'mtd' ? 'all' : 'mtd'
  applyFilters()
}
const onSortChange = () => {
  try { localStorage.setItem(SORT_KEY, filters.sort) } catch (e) { /* 隐私模式 */ }
  applyFilters()
}
const setView = mode => {
  if (viewMode.value === mode) return
  viewMode.value = mode
  try { localStorage.setItem(VIEW_KEY, mode) } catch (e) { /* 隐私模式 */ }
}
// 搜索防抖：300ms；清空立即恢复（不然用户以为产物少了）
let keywordTimer = null
const onKeywordInput = () => {
  clearTimeout(keywordTimer)
  if (!filters.keyword.trim()) { applyFilters(); return }
  keywordTimer = setTimeout(applyFilters, 300)
}

// ==================== 选择模式 ====================
const enterSelection = () => { selectionMode.value = true }
const exitSelection = () => {
  selectionMode.value = false
  selectedIds.value = []
}
const toggleSelect = (id, checked) => {
  if (!selectionMode.value) selectionMode.value = true
  selectedIds.value = checked
    ? [...selectedIds.value, id]
    : selectedIds.value.filter(x => x !== id)
}
const toggleAll = e => {
  // 两个入口共用：勾选框事件带 target.checked，底部「全选」按钮是普通点击事件
  const fromCheckbox = !!(e && e.target && typeof e.target.checked === 'boolean')
  const checked = fromCheckbox ? e.target.checked : !allChecked.value
  selectedIds.value = checked ? visibleRows.value.map(x => x.id) : []
}
const onRowClick = r => {
  if (!selectionMode.value) return
  toggleSelect(r.id, !selectedIds.value.includes(r.id))
}
const onCardClick = r => {
  if (!selectionMode.value) return
  toggleSelect(r.id, !selectedIds.value.includes(r.id))
}

// ==================== 重命名 ====================
const renameId = ref('')
const renameDraft = ref('')
const renameSaving = ref(false)
let renameInputEl = null
const setRenameInput = el => { renameInputEl = el }
const startRename = r => {
  renameId.value = r.id
  renameDraft.value = r.displayName || baseName(r.filename)
  nextTick(() => {
    const inst = renameInputEl
    inst?.focus?.()
    inst?.select?.()
  })
}
const cancelRename = () => { renameId.value = ''; renameDraft.value = '' }
const saveRename = async r => {
  if (renameSaving.value) return
  renameSaving.value = true
  try {
    await renameArtifact(r.id, renameDraft.value.trim())
    message.success(renameDraft.value.trim() ? '已重命名' : '已恢复原文件名')
    cancelRename()
    await Promise.all([load(), loadStats()])
  } catch (e) {
    message.error('重命名失败：' + (e.message || '请重试'))
  } finally {
    renameSaving.value = false
  }
}

// ==================== 更多操作 ====================
const onMenuClick = (key, r) => {
  if (key === 'rename') startRename(r)
  else if (key === 'move') askMove(r)
  else if (key === 'copy') copyLink(r)
  else if (key === 'delete') askDelete(r)
}
const copyLink = async r => {
  const href = resolveImg(r.url)
  const abs = href && href.startsWith('http') ? href : window.location.origin + href
  await copyText(abs, '已复制下载链接')
}

// ==================== 下载 ====================
const downloadOne = r => {
  const a = document.createElement('a')
  a.href = resolveImg(r.url)
  a.download = r.originalFilename || r.filename || 'download'
  document.body.appendChild(a)
  a.click()
  a.remove()
}
const downloadSelected = async () => {
  if (!selectedIds.value.length) return
  if (selectedIds.value.length === 1 && selectedRows.value.length === 1) {
    downloadOne(selectedRows.value[0])
    return
  }
  batchBusy.value = true
  try {
    await downloadArtifactsZip(selectedIds.value)
  } catch (e) {
    message.error('下载失败：' + (e.message || '请重试'))
  } finally {
    batchBusy.value = false
  }
}

// ==================== 删除 ====================
const askDelete = row => Modal.confirm({
  title: `删除「${row.displayName || row.filename}」？`,
  content: '将删除该产物及其文件，删除后不可恢复。',
  okText: '删除', okType: 'danger', cancelText: '取消',
  onOk: () => doDelete(row)
})
const doDelete = async row => {
  try {
    const r = await deleteArtifact(row.id)
    if (r && r.success) {
      message.success('已删除')
      selectedIds.value = selectedIds.value.filter(id => id !== row.id)
      await Promise.all([load(), loadStats()])
    } else {
      message.error((r && r.msg) || '删除失败')
    }
  } catch (e) {
    message.error('删除失败：' + (e.message || ''))
  }
}
const askBatchDelete = () => Modal.confirm({
  title: `删除选中的 ${selectedIds.value.length} 个产物？`,
  content: `共 ${fmtSize(selectedSize.value)}，将同时删除文件，删除后不可恢复。`,
  okText: '删除', okType: 'danger', cancelText: '取消',
  onOk: () => doBatchDelete()
})
const doBatchDelete = async () => {
  batchBusy.value = true
  try {
    const r = await deleteArtifactsBatch(selectedIds.value)
    if (r && r.success) {
      const d = (r && r.data) || {}
      const skipped = d.skipped || []
      if (skipped.length) message.warning(`已删除 ${d.deleted} 项，${skipped.length} 项未能删除（不存在或无权限）`)
      else message.success(`已删除 ${d.deleted} 项`)
      exitSelection()
      await Promise.all([load(), loadStats()])
    } else {
      message.error((r && r.msg) || '批量删除失败')
    }
  } catch (e) {
    message.error('批量删除失败：' + (e.message || ''))
  } finally {
    batchBusy.value = false
  }
}

// ==================== 移动到工作区 ====================
const moveOpen = ref(false)
const moveTarget = ref(null)
const moveIds = ref([])
const moveSaving = ref(false)
const moveTitle = computed(() => moveIds.value.length
  ? `移动 ${moveIds.value.length} 个产物到工作区` : '移动产物到工作区')
const moveNote = computed(() => moveIds.value.length
  ? `${moveIds.value.length} 个产物将一起移动；只改归属，文件本身不动。`
  : '移动只改归属，文件本身不动；已下载过的链接不受影响。')
const askMove = r => {
  moveTarget.value = r
  moveIds.value = []
  moveOpen.value = true
}
const askBatchMove = () => {
  if (!selectedIds.value.length) return
  moveTarget.value = null
  moveIds.value = [...selectedIds.value]
  moveOpen.value = true
}
const doMove = async projectId => {
  moveSaving.value = true
  try {
    if (moveIds.value.length) {
      const r = await moveArtifactsBatch(moveIds.value, projectId)
      const d = (r && r.data) || {}
      if (d.skipped && d.skipped.length) {
        message.warning(`已移动 ${d.moved} 项，${d.skipped.length} 项未能移动`)
      } else {
        message.success(`已移动 ${d.moved || moveIds.value.length} 项`)
      }
      moveIds.value = []
      exitSelection()
    } else {
      await moveArtifactProject(moveTarget.value.id, projectId)
      message.success(projectId ? '已移动到该工作区' : '已摘掉工作区归属')
      moveTarget.value = null
    }
    moveOpen.value = false
    await Promise.all([load(), loadStats()])
  } catch (e) {
    message.error('移动失败：' + (e.message || '请重试'))
  } finally {
    moveSaving.value = false
  }
}

// ==================== 格式化 ====================
const fmtSize = n => {
  const v = Number(n) || 0
  if (v >= 1024 * 1024 * 1024) return (v / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(2) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}
const fmtTime = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const gridTime = s => (s ? String(s).replace('T', ' ').slice(5, 10) : '')
const DAY_MS = 24 * 3600 * 1000
const expireDaysLeft = t => {
  if (!t) return null
  const exp = new Date(String(t).replace(' ', 'T')).getTime()
  if (Number.isNaN(exp)) return null
  return (exp - Date.now()) / DAY_MS
}
const fmtExpire = t => {
  const d = expireDaysLeft(t)
  if (d === null) return '永久保留'
  if (d <= 0) return '即将清理'
  if (d < 1) return '1 天内清理'
  return `${Math.ceil(d)} 天后清理`
}
const expireSoon = t => {
  const d = expireDaysLeft(t)
  return d !== null && d <= 3
}

// ==================== 键盘 ====================
const onKeydown = e => {
  const tag = e.target && e.target.tagName
  const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
    || (e.target && e.target.isContentEditable)
  if (e.key === '/' && !typing) {
    e.preventDefault()
    searchRef.value?.focus?.()
  } else if (e.key === 'Escape' && selectionMode.value) {
    exitSelection()
  }
}

onMounted(() => {
  loadProjects()
  loadStats()
  load()
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  clearTimeout(keywordTimer)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
/* 类型色：只在本页用，和 app.css 的基础令牌同一套色调 */
.art-page {
  --art-violet: #7c5cff;
  --art-violet-weak: #f1eeff;
  --art-sheet: #2f9e63;
  --art-sheet-weak: #e9f6ee;
  --art-image: #d98a12;
  --art-image-weak: #fdf3e2;
  --art-code: #66758a;
  --art-code-weak: #eef1f5;
  --art-archive: #d4552e;
  --art-archive-weak: #fdeeea;
  --art-other: #98a0aa;
  --art-other-weak: #f2f4f7;
}

.art-head { align-items: center; }
.art-head-tools { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.art-search { width: 230px; }
.art-search :deep(.ant-input-affix-wrapper) {
  height: 30px; border-radius: 7px; background: var(--app-panel-2);
}
.art-seg { display: flex; height: 30px; border: 1px solid var(--app-border); border-radius: 7px; overflow: hidden; }
.art-seg-btn {
  width: 32px; border: 0; background: var(--app-panel); color: var(--app-text3);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.art-seg-btn.on { background: var(--app-accent-weak); color: var(--app-accent); }
.art-seg-btn:hover { color: var(--app-accent); }

.art-body { padding: 14px 20px 18px; }

/* ============ 概览 ============ */
.art-stats {
  display: grid; grid-template-columns: repeat(4, 1fr);
  border: 1px solid var(--app-border); border-radius: var(--app-radius);
  overflow: hidden; background: var(--app-panel);
}
.art-stat {
  display: flex; flex-direction: column; gap: 1px; text-align: left;
  padding: 10px 14px; border: 0; border-left: 1px solid var(--app-border);
  background: var(--app-panel); color: var(--app-text); font-family: inherit;
}
.art-stat:first-child { border-left: 0; }
.art-stat-btn { cursor: pointer; transition: background .15s; }
.art-stat-btn:hover { background: var(--app-panel-2); }
.art-stat-btn.on { background: var(--app-accent-weak); box-shadow: inset 0 -2px 0 var(--app-accent); }
.art-stat-btn.warn .art-stat-v { color: var(--app-warn); }
.art-stat-btn.disabled { cursor: not-allowed; }
.art-stat-btn.disabled .art-stat-v,
.art-stat-btn.disabled .art-stat-l { opacity: .55; }
.art-stat-v {
  font-size: 19px; line-height: 1.2; font-weight: 700; letter-spacing: -.01em;
  font-variant-numeric: tabular-nums;
}
.art-stat-l { font-size: 10.5px; color: var(--app-text3); }

/* ============ 筛选 ============ */
.art-filter {
  margin-top: 12px; padding: 9px 11px; display: flex; flex-direction: column; gap: 8px;
  border: 1px solid var(--app-border); border-radius: var(--app-radius);
  background: var(--app-panel-2);
}
.art-frow { display: flex; align-items: center; gap: 6px; min-width: 0; }
.art-f-label { width: 38px; flex: none; font-size: 10.5px; color: var(--app-text3); }
.art-spacer { flex: 1; }
.art-chip {
  height: 23px; padding: 0 9px; border-radius: 999px; cursor: pointer;
  display: inline-flex; align-items: center; gap: 4px; white-space: nowrap;
  border: 1px solid var(--app-border); background: var(--app-panel);
  color: var(--app-text2); font-size: 11px; font-family: inherit;
}
.art-chip:hover { border-color: var(--app-accent); color: var(--app-accent); }
.art-chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 600; }
.art-chip-warn { color: var(--app-warn); }
.art-chip-warn.on { background: var(--app-warn-weak); border-color: var(--app-warn-border); color: var(--app-warn-text); }
.art-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--art-other); flex: none; }
.art-dot.k-page { background: var(--app-accent); }
.art-dot.k-doc { background: var(--art-violet); }
.art-dot.k-sheet { background: var(--art-sheet); }
.art-dot.k-image { background: var(--art-image); }
.art-dot.k-code { background: var(--art-code); }
.art-dot.k-archive { background: var(--art-archive); }
.art-dot.k-other { background: var(--art-other); }
.art-dot-project { background: var(--app-accent); }
.art-select {
  display: inline-flex; align-items: center; gap: 4px; height: 25px; padding: 0 7px;
  border: 1px solid var(--app-border); border-radius: 6px;
  background: var(--app-panel); color: var(--app-text2); font-size: 11px; white-space: nowrap;
}
.art-select select {
  appearance: none; border: 0; outline: none; background: transparent; color: inherit;
  font: inherit; cursor: pointer; padding: 0 2px;
}
.art-select-ico { width: 12px; height: 12px; color: var(--app-text3); flex: none; }
.art-sort-mobile, .art-time-mobile { display: none; }

/* ============ 结果行 ============ */
.art-result { display: flex; align-items: baseline; gap: 7px; margin: 12px 2px 7px; min-height: 18px; }
.art-result b { font-size: 12px; color: var(--app-text); }
.art-result > span { font-size: 11px; color: var(--app-text3); }
.art-result-hint { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-result-actions { margin-left: auto; display: flex; align-items: center; gap: 10px; flex: none; }
.art-link {
  border: 0; background: transparent; padding: 0; cursor: pointer;
  color: var(--app-accent); font-size: 11px; font-family: inherit;
}
.art-link:hover { text-decoration: underline; }

/* ============ 列表 ============ */
.art-list { display: flex; flex-direction: column; gap: 7px; }
.art-group { display: flex; align-items: center; gap: 7px; margin: 6px 2px 4px; font-size: 10.5px; color: var(--app-text3); }
.art-group-name { font-size: 11px; font-weight: 600; color: var(--app-text2); }
.art-group-count {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 9.5px; color: var(--app-accent); background: var(--app-accent-weak);
  border-radius: 6px; padding: 0 5px;
}
.art-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; border: 1px solid var(--app-border); border-radius: var(--app-radius);
  background: var(--app-panel);
  transition: border-color .15s, background .15s;
}
.art-row:hover { border-color: var(--app-border-strong); background: var(--app-panel-2); }
.art-row.picked { border-color: var(--app-accent-border); background: var(--app-info-weak); }
.art-row.selecting { cursor: pointer; }
.art-check { width: 24px; display: inline-flex; align-items: center; flex: none; }
.art-check :deep(.ant-checkbox-wrapper) { display: inline-flex; align-items: center; }
.art-type {
  width: 34px; height: 34px; border-radius: 9px; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
}
.art-type :deep(svg), .art-type .anticon { font-size: 17px; }
.art-type.k-page { background: var(--app-accent-weak); color: var(--app-accent); }
.art-type.k-doc { background: var(--art-violet-weak); color: var(--art-violet); }
.art-type.k-sheet { background: var(--art-sheet-weak); color: var(--art-sheet); }
.art-type.k-image { background: var(--art-image-weak); color: var(--art-image); }
.art-type.k-code { background: var(--art-code-weak); color: var(--art-code); }
.art-type.k-archive { background: var(--art-archive-weak); color: var(--art-archive); }
.art-type.k-other { background: var(--art-other-weak); color: var(--art-other); }
.art-main { flex: 1; min-width: 0; }
.art-line { display: flex; align-items: center; gap: 7px; min-width: 0; }
.art-name {
  font-size: 13px; font-weight: 600; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.art-chip :deep(.anticon), .art-chip svg { font-size: 12px; }
.art-badge {
  flex: none; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 9px; font-weight: 700; letter-spacing: .04em; line-height: 1;
  padding: 2px 5px; border-radius: 4px;
}
.art-badge.k-page { color: var(--app-accent); background: var(--app-accent-weak); }
.art-badge.k-doc { color: var(--art-violet); background: var(--art-violet-weak); }
.art-badge.k-sheet { color: var(--art-sheet); background: var(--art-sheet-weak); }
.art-badge.k-image { color: var(--art-image); background: var(--art-image-weak); }
.art-badge.k-code { color: var(--art-code); background: var(--art-code-weak); }
.art-badge.k-archive { color: var(--art-archive); background: var(--art-archive-weak); }
.art-badge.k-other { color: var(--art-other); background: var(--art-other-weak); }
.art-desc {
  margin-top: 2px; font-size: 11.5px; color: var(--app-text2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 元信息整项换行，绝不项内断行（避免「45.9 / KB」那种碎排） */
.art-meta {
  margin-top: 3px; display: flex; flex-wrap: wrap; gap: 0 5px;
  font-size: 10.5px; color: var(--app-text3); min-width: 0;
}
.art-meta > span, .art-meta > a { flex: none; white-space: nowrap; }
.art-dotsep { opacity: .55; font-style: normal; }
.art-expire.warn { color: var(--app-warn); font-weight: 600; }
.art-origin { max-width: 230px; overflow: hidden; text-overflow: ellipsis; }
.art-src {
  display: inline-flex; align-items: center; gap: 3px; max-width: 240px;
  color: var(--app-accent); text-decoration: none;
}
.art-src span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-src .anticon { font-size: 10px; }
.art-src-arrow { font-size: 10px; opacity: .75; }
.art-src.gone { color: var(--app-text3); }
.art-acts { display: flex; align-items: center; gap: 6px; flex: none; }
.app-btn.small { min-height: 28px; padding: 0 9px; font-size: 11px; border-radius: 7px; }
.art-icon-act { width: 28px; padding: 0; justify-content: center; }
.art-preview { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.art-preview:hover { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }

/* ============ 行内重命名 ============ */
.art-rename { display: inline-flex; align-items: center; gap: 4px; min-width: 0; }
.art-rename :deep(.ant-input) { width: 250px; height: 26px; font-size: 12px; }
.art-rename-ok, .art-rename-no {
  width: 26px; height: 26px; border-radius: 6px; display: inline-flex;
  align-items: center; justify-content: center; cursor: pointer; flex: none;
}
.art-rename-ok { border: 0; background: var(--app-accent); color: #fff; }
.art-rename-ok:disabled { opacity: .6; cursor: not-allowed; }
.art-rename-no { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2); }

/* ============ 网格 ============ */
.art-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.art-card {
  border: 1px solid var(--app-border); border-radius: var(--app-radius);
  overflow: hidden; background: var(--app-panel); position: relative;
  transition: border-color .15s, box-shadow .15s;
}
.art-card:hover { border-color: var(--app-border-strong); box-shadow: var(--app-shadow-sm); }
.art-card.picked { border-color: var(--app-accent-border); box-shadow: 0 0 0 2px var(--app-accent-weak); }
.art-card.selecting { cursor: pointer; }
.art-thumb { height: 92px; position: relative; border-bottom: 1px solid var(--app-border); overflow: hidden; }
.art-thumb.k-page { background: linear-gradient(135deg, var(--app-accent-weak), var(--app-panel)); }
.art-thumb.k-doc { background: linear-gradient(135deg, var(--art-violet-weak), var(--app-panel)); }
.art-thumb.k-sheet { background: linear-gradient(135deg, var(--art-sheet-weak), var(--app-panel)); }
.art-thumb.k-image { background: linear-gradient(135deg, var(--art-image-weak), var(--app-panel)); }
.art-thumb.k-code { background: linear-gradient(135deg, var(--art-code-weak), var(--app-panel)); }
.art-thumb.k-archive { background: linear-gradient(135deg, var(--art-archive-weak), var(--app-panel)); }
.art-thumb.k-other { background: linear-gradient(135deg, var(--art-other-weak), var(--app-panel)); }
.art-thumb-badge {
  position: absolute; top: 8px; right: 8px; z-index: 2;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 9px; font-weight: 700; letter-spacing: .04em;
  padding: 1px 5px; border-radius: 4px;
}
.art-thumb-badge.k-page { color: var(--app-accent); background: var(--app-accent-weak); }
.art-thumb-badge.k-doc { color: var(--art-violet); background: var(--art-violet-weak); }
.art-thumb-badge.k-sheet { color: var(--art-sheet); background: var(--art-sheet-weak); }
.art-thumb-badge.k-image { color: var(--art-image); background: var(--art-image-weak); }
.art-thumb-badge.k-code { color: var(--art-code); background: var(--art-code-weak); }
.art-thumb-badge.k-archive { color: var(--art-archive); background: var(--art-archive-weak); }
.art-thumb-badge.k-other { color: var(--art-other); background: var(--art-other-weak); }
.art-thumb-check { position: absolute; top: 7px; left: 7px; z-index: 3; opacity: 0; transition: opacity .15s; }
.art-card:hover .art-thumb-check, .art-thumb-check.on { opacity: 1; }
.art-thumb-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.art-mini {
  position: absolute; inset: 18px 20px 12px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 6px;
  box-shadow: var(--app-shadow-sm); overflow: hidden;
}
.art-mini-bar { height: 12px; background: var(--app-panel-2); border-bottom: 1px solid var(--app-border); display: flex; align-items: center; gap: 3px; padding: 0 5px; }
.art-mini-bar i { width: 4px; height: 4px; border-radius: 50%; background: var(--app-border-strong); }
.art-mini-line { height: 3px; border-radius: 2px; background: var(--app-border); margin: 5px 8px 0; }
.art-mini-line.short { width: 54%; }
.art-mini-line.accent { background: var(--app-accent-border); }
.art-mini-table { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; margin: 5px 6px 0; background: var(--app-border); border: 1px solid var(--app-border); }
.art-mini-table i { height: 8px; background: var(--app-panel); }
.art-mini-table i.head { background: var(--art-sheet-weak); }
.art-mini-code { padding: 6px 8px; }
.art-mini-code i { display: block; height: 3px; border-radius: 2px; margin-bottom: 4px; background: var(--app-border); }
.art-mini-code i.short { width: 48%; background: var(--app-panel-2); }
.art-archive-stack { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); width: 46px; height: 30px; }
.art-archive-stack i { position: absolute; left: 0; right: 0; height: 8px; border: 1px solid var(--app-border); border-radius: 3px; background: var(--app-panel); }
.art-archive-stack i:nth-child(1) { top: 0; background: var(--art-archive-weak); }
.art-archive-stack i:nth-child(2) { top: 10px; }
.art-archive-stack i:nth-child(3) { top: 20px; }
.art-mini-glyph { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); font-size: 26px; color: var(--app-text3); }
.art-card-body { padding: 9px 10px 10px; }
.art-card-name { font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-card-meta { margin-top: 3px; display: flex; gap: 5px; font-size: 10px; color: var(--app-text3); }
.art-card-src { margin-top: 4px; font-size: 10px; color: var(--app-accent); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-card-src.gone { color: var(--app-text3); }
.art-card-acts { position: absolute; top: 8px; right: 8px; z-index: 4; display: flex; gap: 4px; opacity: 0; transition: opacity .15s; }
.art-card:hover .art-card-acts, .art-card:focus-within .art-card-acts { opacity: 1; }
/* 悬停时操作钮与类型徽标在同一角：让徽标先隐去，避免两层叠在一起 */
.art-card:hover .art-thumb-badge { opacity: 0; }
.art-thumb-badge { transition: opacity .15s; }
.art-card-act {
  width: 24px; height: 24px; border-radius: 6px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--app-border); background: rgba(255, 255, 255, .94); color: var(--app-text2); cursor: pointer;
  backdrop-filter: blur(4px);
}
.art-card-act:hover { color: var(--app-accent); border-color: var(--app-accent-border); }
.art-card-act.ant-btn { border: 0; }

/* ============ 批量栏 ============ */
.art-bulk {
  position: sticky; bottom: 0; z-index: 5;
  margin-top: 12px; padding: 9px 12px; display: flex; align-items: center; gap: 8px;
  border: 1px solid var(--app-accent-border); border-radius: var(--app-radius);
  background: var(--app-info-weak); box-shadow: 0 6px 18px rgba(46, 107, 230, .10);
}
.art-bulk-sel { display: flex; align-items: center; gap: 7px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.art-bulk-size { color: var(--app-text3); font-size: 11px; font-weight: 400; }
.art-bulk .app-btn.small { min-height: 28px; }
.art-bulk-download { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-panel); }

/* ============ 空态 / 错误 / 骨架 ============ */
.art-empty { text-align: center; padding: 28px 16px; }
.art-empty-ico {
  width: 46px; height: 46px; margin: 0 auto; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: var(--app-accent-weak); color: var(--app-accent); font-size: 22px;
}
.art-empty-ico.error { background: var(--app-danger-weak); color: var(--app-danger); }
.art-empty-title { margin: 8px 0 0; font-weight: 600; font-size: 13px; }
.art-empty-hint { margin-top: 3px; font-size: 11px; line-height: 1.6; white-space: normal; }
.art-empty .app-btn { margin-top: 12px; }
.art-empty-action { display: inline-flex; align-items: center; gap: 5px; }
.art-error-bar {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px; padding: 8px 10px;
  border: 1px solid var(--app-warn-border); border-radius: var(--app-radius-sm);
  background: var(--app-warn-weak); color: var(--app-warn-text); font-size: 12px;
}
.art-error-bar > span { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.art-error-bar .art-link { color: var(--app-warn-text); flex: none; }
.art-skeleton { display: flex; flex-direction: column; gap: 7px; }
.art-sk-row {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border: 1px solid var(--app-border); border-radius: var(--app-radius); background: var(--app-panel);
}
.art-sk-square { width: 34px; height: 34px; border-radius: 9px; flex: none; background: linear-gradient(90deg, var(--app-panel-2), var(--app-bg-layer-3), var(--app-panel-2)); background-size: 200% 100%; animation: art-shimmer 1.4s infinite; }
.art-sk-lines { flex: 1; min-width: 0; }
.art-sk-lines i { display: block; height: 8px; border-radius: 4px; background: linear-gradient(90deg, var(--app-panel-2), var(--app-bg-layer-3), var(--app-panel-2)); background-size: 200% 100%; animation: art-shimmer 1.4s infinite; }
.art-sk-lines i:nth-child(1) { width: 38%; }
.art-sk-lines i:nth-child(2) { width: 76%; margin-top: 6px; height: 6px; }
.art-sk-lines i:nth-child(3) { width: 58%; margin-top: 5px; height: 6px; }
@keyframes art-shimmer { to { background-position: -200% 0; } }

/* ============ 窄屏（手机）：/artifacts 在守卫白名单里，这里做真适配 ============ */
@media (max-width: 1024px) {
  .art-cards { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 768px) {
  /* 移动端外壳顶栏已经显示「我的产物」，页内不再重复标题；选择模式保留已选计数 */
  .art-head .app-page-title { display: none; }
  .art-head-hint:not(.art-selection-hint) { display: none; }
  .art-head { align-items: baseline; }
  .art-head-tools { display: flex; align-items: center; gap: 8px; width: 100%; margin-top: 2px; }
  .art-search { flex: 1; width: auto; min-width: 0; }
  .art-stats { display: flex; overflow-x: auto; white-space: nowrap; }
  .art-stat { min-width: 112px; flex: none; }
  .art-stat:first-child { border-left: 0; }
  .art-filter { padding: 8px 10px; }
  .art-frow { overflow-x: auto; white-space: nowrap; }
  .art-frow::-webkit-scrollbar { height: 0; }
  /* 排序/时间在手机上挪到结果行：筛选行只留 chips，避免右侧控件被裁一半 */
  .art-frow > .art-select { display: none; }
  .art-sort-mobile, .art-time-mobile { display: inline-flex; }
  .art-result { flex-wrap: wrap; row-gap: 6px; }
  .art-result-actions { gap: 6px; }
  .art-result-dot, .art-result-size { display: none; }
  .art-result-actions .art-select { height: 24px; padding: 0 5px; }
  .art-cards { grid-template-columns: repeat(2, 1fr); }
  .art-row { flex-wrap: wrap; row-gap: 0; column-gap: 8px; }
  /* 手机上保留类型图标：类型色是快速分辨网页/文档/图片的第一条线索 */
  .art-row .art-type { width: 28px; height: 28px; border-radius: 7px; }
  .art-row .art-type :deep(svg) { font-size: 14px; }
  .art-main { flex: 1 1 0; min-width: 0; }
  .art-acts { width: 100%; justify-content: flex-end; }
  .art-acts .app-btn.small { min-height: 44px; padding: 0 14px; }
  .art-acts .art-icon-act { width: 44px; padding: 0; }
  .art-check { width: 30px; min-height: 44px; }
  .art-row.selecting .art-acts { display: none; }
  .art-row.selecting .art-check { order: 3; margin-left: auto; }
  .art-rename :deep(.ant-input) { width: 42vw; }
  .art-bulk { flex-wrap: wrap; row-gap: 8px; padding: 8px 10px; }
  .art-bulk-sel { width: 100%; }
  .art-bulk .app-btn.small { min-height: 38px; }
  /* 手机上批量栏收成图标：勾选框已承担全选，移动/删除只留图标，避免两行按钮 */
  .art-bulk-select, .art-bulk-label { display: none; }
  .art-bulk .art-spacer { display: none; }
  .art-bulk-download { margin-left: auto; }
  .art-empty { padding: 24px 12px; }
}
@media (max-width: 520px) {
  .art-cards { grid-template-columns: 1fr; }
  .art-bulk { gap: 6px; }
  .art-bulk .app-btn.small { padding: 0 8px; }
  .art-origin { max-width: 140px; }
  .art-src { max-width: 180px; }
}
</style>
