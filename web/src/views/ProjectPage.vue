<template>
  <div class="app-page">
    <div class="app-page-head pj-head">
      <component :is="workspaceIconOf(form.icon, true)"
                 class="pj-head-ic" :style="form.color ? { color: form.color } : null" />
      <template v-if="!nameEditing">
        <h1 class="app-page-title">
          <button class="pj-title-btn" type="button" title="点击改名" @click="startRename">{{ displayName }}</button>
        </h1>
        <span v-if="isArchived" class="app-pill muted">已归档</span>
      </template>
      <template v-else>
        <a-input ref="nameInput" v-model:value="nameDraft" class="pj-name-input" :maxlength="60"
                 placeholder="工作区名称" @press-enter="saveName" @keydown.esc="cancelRename" />
        <button class="app-btn" :disabled="savingName || !nameDraft.trim()" @click="saveName">
          {{ savingName ? '保存中…' : '保存' }}
        </button>
        <button class="app-btn ghost" @click="cancelRename">取消</button>
      </template>

      <a-popover v-model:open="lookOpen" trigger="click" placement="bottomLeft"
                 :overlay-style="{ width: '308px' }">
        <template #content>
          <div class="pj-look">
            <div class="pj-look-title">工作区外观</div>
            <div class="pj-icons">
              <button v-for="ic in WORKSPACE_ICONS" :key="ic.name" type="button" class="pj-icon"
                      :class="{ on: form.icon === ic.name }" :title="ic.label"
                      @click="saveLook({ icon: ic.name })">
                <component :is="workspaceIconOf(ic.name, true)" />
              </button>
            </div>
            <div class="pj-colors">
              <button v-for="c in WORKSPACE_COLORS" :key="c" type="button" class="pj-color"
                      :class="{ on: form.color === c }" :style="{ background: c }"
                      :title="c" @click="saveLook({ color: c })">
                <check-outlined v-if="form.color === c" />
              </button>
            </div>
          </div>
        </template>
        <button class="pj-icon-btn" type="button" title="外观：图标与颜色">
          <bg-colors-outlined />
        </button>
      </a-popover>

      <div class="pj-iso-inline">
        <safety-outlined class="pj-iso-shield" />
        <span class="pj-iso-label">工作区隔离</span>
        <span class="pj-state" :class="{ on: form.memoryIsolated === 1 }">
          {{ form.memoryIsolated === 1 ? '已开启' : '未开启' }}
        </span>
        <a-tooltip title="开启后，这个工作区里的对话不带入也不写入个人记忆（新开轮次生效）。上下文只来自目录里的文件和这里的工作区记忆，两边互不干扰。">
          <question-circle-outlined class="pj-help" />
        </a-tooltip>
        <a-switch size="small" :checked="form.memoryIsolated === 1" :loading="isoSaving" @change="saveMemoryIsolated" />
      </div>

      <div class="pj-head-actions">
        <button v-if="inlinePreview" class="pj-icon-btn" type="button"
                :title="sideCollapsed ? '展开右侧目录' : '收起右侧目录'" @click="toggleSideCollapsed">
          <menu-unfold-outlined v-if="sideCollapsed" />
          <menu-fold-outlined v-else />
        </button>
        <button class="app-btn" :disabled="isArchived"
                :title="isArchived ? '工作区已归档，恢复后才能新建对话' : ''" @click="goChat">
          <plus-outlined /> 新建对话
        </button>
      </div>
    </div>

    <div class="app-page-body pj-body">
      <div class="pj-wrap" ref="pjWrapEl" :class="{ 'pj-single': !inlinePreview }">
        <a-skeleton v-if="firstLoad" class="pj-skeleton" active :paragraph="{ rows: 6 }" />

        <template v-else>
          <!-- 预览：桌面两栏时占左侧主区。放不下两栏（窄屏/窗口窄）时收掉，点击文件仍走「新标签 / 下载」 -->
          <section v-if="inlinePreview" class="app-card pj-card pj-pv-card">
            <div v-if="previewEntry" class="pj-pv-head">
              <file-text-outlined class="pj-pv-ic" />
              <span class="pj-pv-name" :title="previewEntry.name">{{ previewEntry.name }}</span>
              <span class="pj-pv-meta">{{ fmtSize(previewEntry.size) }} · {{ fmtAgo(previewEntry.mtime) }}</span>
              <span class="pj-spacer"></span>
              <span v-if="previewEditable && !previewLoading && !previewError" class="pj-pv-acts">
                <span v-if="previewDirty" class="pj-pv-dirty">未保存</span>
                <template v-if="previewEditing">
                  <button class="pj-text-btn" type="button" :disabled="previewSaving || !previewDirty"
                          @click="saveEdit">{{ previewSaving ? '保存中…' : '保存' }}</button>
                  <button class="pj-text-btn" type="button" :disabled="previewSaving" @click="cancelEdit">取消</button>
                </template>
                <button v-else class="pj-text-btn" type="button" @click="startEdit">编辑</button>
              </span>
            </div>
            <div class="pj-pv-body">
              <div v-if="!previewEntry" class="pj-pv-empty">
                <file-text-outlined class="pj-pv-empty-ic" />
                <div>从右侧目录选一个文件，预览会出现在这里</div>
                <div class="pj-pv-sub">图片、PDF、Markdown、代码直接看；其他类型可下载</div>
              </div>
              <a-spin v-else :spinning="previewLoading">
                <div v-if="previewEditing" class="pj-pv-editor">
                  <pre ref="previewEditHlEl" class="pj-pv-edit-hl" aria-hidden="true"><code class="hljs" v-html="previewDraftHtml"></code></pre>
                  <textarea ref="previewEditEl" v-model="previewDraft" class="pj-pv-edit"
                            spellcheck="false" wrap="off" @scroll="syncEditScroll"></textarea>
                </div>
                <div v-else-if="previewError" class="pj-pv-none">
                  {{ previewError }}
                  <button class="pj-text-btn" type="button" @click="selectFile(previewEntry)">重试</button>
                </div>
                <div v-else-if="previewKind === PV.IMAGE" class="pj-pv-img-wrap">
                  <img class="pj-pv-img" :src="previewUrl" :alt="previewEntry.name" />
                </div>
                <iframe v-else-if="previewKind === PV.PAGE || previewKind === PV.PDF"
                        class="pj-pv-frame" :src="previewUrl" :title="previewEntry.name"></iframe>
                <div v-else-if="previewKind === PV.MD" class="pj-pv-md">
                  <div class="md" v-html="previewHtml"></div>
                </div>
                <pre v-else-if="previewKind === PV.TEXT || previewKind === PV.TABLE" class="pj-pv-text"><code class="hljs" v-html="previewCodeHtml"></code></pre>
                <div v-else class="pj-pv-none">
                  <p>这个类型暂不支持预览</p>
                  <button class="app-btn" type="button" @click="downloadPreview">下载文件</button>
                </div>
              </a-spin>
              <div v-if="previewEntry && previewTruncated && !previewError" class="pj-pv-note">
                文件较大，只读回前一段；完整内容请下载查看。
              </div>
            </div>
          </section>

          <div v-if="showSide" class="pj-side">
          <!-- 目录：源文件夹 + 当前文件夹内容（只读） -->
          <section class="app-card pj-card pj-dir-card">
            <div class="pj-card-head">
              <h2 class="pj-card-title">工作区目录</h2>
              <span class="pj-spacer"></span>
              <button class="pj-text-btn" type="button" :disabled="rootPicking || rootBusy" @click="addRoot">
                {{ rootPicking ? '选择中…' : '＋ 添加文件夹' }}
              </button>
            </div>

            <div class="pj-roots">
              <div v-for="r in form.roots" :key="r.path" class="pj-root"
                   :class="{ on: activeRoot && activeRoot.alias === r.alias }">
                <button class="pj-root-main" type="button" :title="'查看「' + r.alias + '」的目录内容'" @click="selectRoot(r)">
                  <folder-filled class="pj-root-ic" />
                  <span class="pj-root-name">
                    {{ r.alias }}<span v-if="r.primary" class="pj-mini-pill" title="主目录：cwd 与相对路径的基准">主要</span>
                  </span>
                </button>
                <button class="pj-root-path" type="button" title="点击复制完整路径" @click="copyRootPath(r)">{{ r.path }}</button>
                <span class="pj-root-acts">
                  <button class="pj-text-btn" type="button" @click="openRoot(r)">打开</button>
                  <button class="pj-text-btn danger" type="button"
                          :disabled="rootBusy || form.roots.length <= 1" @click="removeRoot(r)">移除</button>
                </span>
              </div>

              <div v-if="!form.roots.length" class="pj-empty">
                还没有绑定本机目录。
                <button class="pj-text-btn" type="button" @click="addRoot">添加文件夹</button>
              </div>
              <div v-if="!executorId" class="pj-exec-pick">
                <a-select size="small" style="min-width: 200px" :value="executorPick"
                          :options="executorOptions" placeholder="先选择一台在线的电脑" @change="chooseExecutor" />
              </div>
            </div>

            <div class="pj-divider"></div>

            <div class="pj-filebar">
              <span class="pj-filebar-title">文件</span>
              <span class="pj-filebar-count">{{ fileTotal }} 项</span>
              <span class="pj-spacer"></span>
              <a-input v-model:value="fileKw" size="small" allow-clear class="pj-search"
                       placeholder="搜索当前文件夹" />
            </div>

            <div v-if="crumbs.length > 1" class="pj-crumbs">
              <template v-for="(c, i) in crumbs" :key="c.path">
                <span v-if="i" class="pj-crumb-sep">/</span>
                <button class="pj-crumb" :class="{ last: i === crumbs.length - 1 }"
                        type="button" :disabled="i === crumbs.length - 1" @click="openDir(c.path)">{{ c.label }}</button>
              </template>
            </div>

            <div class="pj-files">
            <a-spin :spinning="fileLoading">
              <div v-if="fileError" class="pj-empty err">
                {{ fileError }}
                <button class="pj-text-btn" type="button" @click="loadFiles">重试</button>
              </div>
              <div v-else-if="!form.roots.length" class="pj-empty">绑定文件夹后，这里会列出目录内容。</div>
              <div v-else-if="!visibleFiles.length" class="pj-empty">
                {{ fileKw ? '没有匹配的文件' : '这个文件夹是空的' }}
              </div>
              <div v-else class="pj-file-list">
                <div class="pj-file-row pj-file-head">
                  <span></span>
                  <span>名称</span>
                  <span class="pj-col-size">大小</span>
                  <span class="pj-col-time">时间</span>
                </div>
                <button v-for="e in visibleFiles" :key="e.path" class="pj-file-row" type="button"
                        :class="{ on: previewEntry && previewEntry.path === e.path }"
                        :title="e.kind === 'dir' ? '打开文件夹' : (e.viewUrl || previewKindOf(e) !== PV.NONE ? '预览' : '下载')"
                        @click="onFileClick(e)">
                  <folder-filled v-if="e.kind === 'dir'" class="pj-dir-ic" />
                  <file-text-outlined v-else class="pj-file-ic" />
                  <span class="pj-file-name">{{ e.name }}</span>
                  <span class="pj-col-size">{{ e.kind === 'dir' ? '—' : fmtSize(e.size) }}</span>
                  <span class="pj-col-time">{{ e.kind === 'dir' ? '—' : fmtAgo(e.mtime) }}</span>
                </button>
              </div>
              <div v-if="fileTruncated" class="pj-note pj-list-note">
                这一层只列了前 {{ fileEntries.length }} 项，共 {{ fileTotal }} 项。
              </div>
            </a-spin>
            </div>
          </section>

          <!-- 事项清单（C2）：记下 → 去聊 → 勾掉；进入工作区先看见还欠着的事 -->
          <section class="app-card pj-card pj-item-card">
            <div class="pj-card-head">
              <h2 class="pj-card-title">事项
                <span v-if="openItems.length" class="pj-mini-pill">{{ openItems.length }}</span>
              </h2>
              <span class="pj-spacer"></span>
              <button class="pj-text-btn" type="button" :disabled="itemBusy"
                      @click="itemAdding = !itemAdding; itemDraft = ''">
                {{ itemAdding ? '取消' : '＋ 记一条' }}
              </button>
            </div>
            <div class="pj-item-body">
              <div v-if="itemAdding" class="pj-item-add">
                <a-input v-model:value="itemDraft" size="small" :maxlength="200"
                         placeholder="这个工作区接下来要做什么？" @press-enter="addItem" />
                <button class="app-btn" type="button" :disabled="!itemDraft.trim() || itemBusy"
                        @click="addItem">{{ itemBusy ? '添加中…' : '添加' }}</button>
              </div>
              <a-spin :spinning="itemLoading">
                <div v-if="itemError" class="pj-empty err">
                  {{ itemError }}
                  <button class="pj-text-btn" type="button" @click="loadItems">重试</button>
                </div>
                <div v-else-if="!items.length" class="pj-empty">
                  还没有事项。把「接下来要做什么」记在这里，点「去聊」直接带进对话。
                </div>
                <div v-else class="pj-item-list">
                  <div v-for="it in items" :key="it.id" class="pj-item-row"
                       :class="{ done: it.status === 'done' }">
                    <button class="pj-item-check" type="button"
                            :title="it.status === 'done' ? '标记未完成' : '标记完成'"
                            @click="toggleItem(it)">
                      <check-outlined v-if="it.status === 'done'" />
                      <span v-else class="pj-item-dot"></span>
                    </button>
                    <div class="pj-item-main">
                      <span class="pj-item-title">{{ it.title }}</span>
                      <span v-if="it.note" class="pj-item-note">{{ it.note }}</span>
                    </div>
                    <button v-if="it.status !== 'done'" class="pj-text-btn" type="button"
                            @click="chatItem(it)">去聊</button>
                    <button class="pj-item-x" type="button" title="删除事项"
                            @click="removeItem(it)">×</button>
                  </div>
                </div>
              </a-spin>
            </div>
          </section>

          <!-- 记忆 -->
          <section class="app-card pj-card pj-mem-card">
            <div class="pj-card-head">
              <h2 class="pj-card-title">工作区记忆</h2>
              <span class="pj-mem-path">.wenqu/MEMORY.md</span>
              <span class="pj-spacer"></span>
              <button class="app-btn" type="button" @click="openMemory">{{ wmContent ? '编辑' : '添加' }}</button>
            </div>
            <div class="pj-mem-body">
            <a-spin :spinning="wmLoading">
              <div v-if="wmError" class="pj-empty err">
                读取失败：{{ wmError }}
                <button class="pj-text-btn" type="button" @click="loadMemory">重试</button>
              </div>
              <pre v-else-if="wmContent" class="pj-mem-text">{{ wmContent }}</pre>
              <div v-else class="pj-empty">还没有工作区记忆。</div>
            </a-spin>
            </div>
          </section>
          </div>
        </template>
      </div>
    </div>

    <a-modal v-model:open="wmOpen" title="编辑工作区记忆" :footer="null"
             :width="mobileShell ? '92vw' : 560" @cancel="wmOpen = false">
      <a-spin :spinning="wmLoading">
        <a-textarea v-model:value="wmDraft" :rows="14" :disabled="wmSaving"
                    placeholder="记录这个工作区的稳定事实：约定、决策、结构、坑、偏好…" />
        <div class="pj-modal-foot">
          <button class="app-btn ghost" type="button" @click="wmOpen = false">取消</button>
          <button class="app-btn" type="button" :disabled="wmSaving" @click="saveMemory">
            {{ wmSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  PlusOutlined, FolderFilled, FileTextOutlined, QuestionCircleOutlined,
  SafetyOutlined, BgColorsOutlined, CheckOutlined, MenuFoldOutlined, MenuUnfoldOutlined
} from '@ant-design/icons-vue'
import hljs from 'highlight.js/lib/common'
import {
  getProject, updateProject, bindProjectExecutor, pickExecutorDir, openProjectFolder,
  listExecutors, listProjectFiles, getWorkspaceMemory, saveWorkspaceMemory,
  listProjectItems, createProjectItem, updateProjectItem, deleteProjectItem,
  downloadProjectFile, setNewSessionProject, projectFileContent, saveProjectFileContent
} from '../api'
import { sessionStore, loadSessions as refreshSidebarSessions } from './store'
import { WORKSPACE_ICONS, WORKSPACE_COLORS, workspaceIconOf } from '../utils/workspaceIcons'
import { copyText } from '../utils/clipboard'
import { resolveImg, renderMd } from '../utils/markdown'
import { previewKindOf, artifactExt, PV } from '../utils/artifactView'
import { mobileShell, isNarrow } from '../h5/mobile'

const route = useRoute()
const router = useRouter()
const id = computed(() => String(route.params.id || ''))

const loading = ref(true)
const loadedOnce = ref(false)
const form = ref({ name: '', status: 'active', memoryIsolated: 0, roots: [], icon: '', color: '' })
const isArchived = computed(() => form.value.status === 'archived')
const displayName = computed(() => form.value.name || '未命名工作区')
const firstLoad = computed(() => loading.value && !loadedOnce.value)

// ---------- 执行器与源文件夹 ----------
const executorId = ref('')
const executors = ref([])
const executorPick = ref(undefined)
const rootPicking = ref(false)
const rootBusy = ref(false)
const executorOptions = computed(() => executors.value
  .filter(e => e.online)
  .map(e => ({ value: e.id, label: `${e.name || e.id}（在线）` })))

const loadExecutors = async () => {
  try {
    const r = await listExecutors()
    executors.value = (r && r.data) || []
  } catch (e) {
    executors.value = []
  }
}

const baseNameOf = p => {
  const s = String(p || '').replace(/[/\\]+$/, '')
  const i = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'))
  return (i >= 0 ? s.slice(i + 1) : s) || '文件夹'
}

/** 目录改完即存：服务端返回整份工作区，根别名以它为准（重名会被它去重） */
const bindRoots = async (executor, roots) => {
  const r = await bindProjectExecutor(id.value, executor, roots.map(x => ({ path: x.path, alias: x.alias })))
  const p = (r && r.data) || {}
  if (Array.isArray(p.roots)) form.value.roots = p.roots.map(x => ({ ...x }))
  executorId.value = p.executorId || executor
  return p
}

const ensureFileDir = () => {
  const roots = form.value.roots
  if (!roots.length) { fileDir.value = ''; return }
  const ok = roots.some(x => fileDir.value === x.alias || fileDir.value.startsWith(x.alias + '/'))
  if (!ok) {
    const primary = roots.find(x => x.primary) || roots[0]
    fileDir.value = primary ? primary.alias : ''
  }
}

const addRoot = async () => {
  if (rootPicking.value || rootBusy.value) return
  const ex = executorId.value || executorPick.value
  if (!ex) { message.warning('先选择一台在线的电脑'); return }
  rootPicking.value = true
  try {
    const r = await pickExecutorDir(ex)
    if (r && r.success === false) {
      const msg = String(r.msg || '')
      if (!/没有选择目录/.test(msg)) message.warning(msg || '没有选到目录')
      return
    }
    const picked = (r && r.data && r.data.path) || ''
    if (!picked) return
    if (form.value.roots.some(x => x.path === picked)) { message.info('这个文件夹已经添加过了'); return }
    rootBusy.value = true
    await bindRoots(ex, [...form.value.roots, { path: picked, alias: baseNameOf(picked) }])
    const added = form.value.roots.find(x => x.path === picked)
    if (added) fileDir.value = added.alias
    message.success('已添加文件夹')
    await loadFiles()
  } catch (e) {
    const msg = String(e.message || '')
    // 用户在系统选择框里点了取消：不是失败，静默返回
    if (/没有选择目录|E_CANCELLED/.test(msg)) return
    message.error('添加失败：' + (msg || '请重试'))
  } finally {
    rootPicking.value = false
    rootBusy.value = false
  }
}

const removeRoot = async root => {
  if (rootBusy.value || rootPicking.value) return
  if (form.value.roots.length <= 1) { message.warning('至少保留一个文件夹'); return }
  if (!executorId.value) { message.warning('这个工作区还没有绑定执行器'); return }
  rootBusy.value = true
  try {
    await bindRoots(executorId.value, form.value.roots.filter(x => x.path !== root.path))
    ensureFileDir()
    message.success('已移除')
    await loadFiles()
  } catch (e) {
    message.error('移除失败：' + (e.message || '请重试'))
  } finally {
    rootBusy.value = false
  }
}

const chooseExecutor = async v => {
  executorPick.value = v
  if (!form.value.roots.length) return
  rootBusy.value = true
  try {
    await bindRoots(v, form.value.roots)
    message.success('已绑定这台电脑')
    await loadFiles()
  } catch (e) {
    message.error('绑定失败：' + (e.message || '请重试'))
  } finally {
    rootBusy.value = false
  }
}

const openRoot = async root => {
  try {
    await openProjectFolder(id.value, root.alias)
  } catch (e) {
    message.warning(e.message || '打开失败：先确认那台电脑上的执行器在线')
  }
}

const copyRootPath = root => copyText(root.path, '路径已复制')

// ---------- 目录内容（只读） ----------
const fileDir = ref('')
const fileEntries = ref([])
const fileTotal = ref(0)
const fileTruncated = ref(false)
const fileError = ref('')
const fileKw = ref('')
const fileLoading = ref(false)
let filesSeq = 0

// ---------- 文件预览（桌面左栏内嵌；放不下两栏时点击文件仍走新标签/下载） ----------
// 是否摆两栏按内容区实测宽度判：侧栏可折叠（232/56px）、窗口可缩放，只看视口会算错
const pjWrapEl = ref(null)
const pjWrapW = ref(Math.max(0, (typeof window !== 'undefined' ? window.innerWidth : 0) - 232))
const inlinePreview = computed(() => !isNarrow.value && pjWrapW.value >= 880)
let pjRO = null
// 右栏收起：按本机记忆，刷新后保持；只有摆得下两栏时才有这个动作
const SIDE_COLLAPSE_KEY = 'app_pj_side_collapsed'
const sideCollapsed = ref((() => {
  try { return localStorage.getItem(SIDE_COLLAPSE_KEY) === '1' } catch (e) { return false }
})())
const toggleSideCollapsed = () => {
  sideCollapsed.value = !sideCollapsed.value
  try { localStorage.setItem(SIDE_COLLAPSE_KEY, sideCollapsed.value ? '1' : '0') } catch (e) { /* 忽略 */ }
}
const showSide = computed(() => !inlinePreview.value || !sideCollapsed.value)
const previewEntry = ref(null)
const previewText = ref('')
const previewTruncated = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
let previewSeq = 0
const previewKind = computed(() => previewKindOf(previewEntry.value))
const previewUrl = computed(() => (previewEntry.value && previewEntry.value.viewUrl) ? resolveImg(previewEntry.value.viewUrl) : '')
const previewHtml = computed(() => (previewKind.value === PV.MD ? renderMd(previewText.value) : ''))
const previewEditing = ref(false)
const previewSaving = ref(false)
const previewDraft = ref('')
const previewEditEl = ref(null)
const previewEditHlEl = ref(null)
const previewEditable = computed(() =>
  (previewKind.value === PV.MD || previewKind.value === PV.TEXT || previewKind.value === PV.TABLE)
  && !previewTruncated.value)
const previewDirty = computed(() => previewEditing.value && previewDraft.value !== previewText.value)
// 代码按扩展名挂 highlight.js 语法；映射不到就按纯文本转义
const CODE_LANG = {
  java: 'java', js: 'javascript', mjs: 'javascript', jsx: 'javascript', ts: 'typescript', tsx: 'typescript',
  json: 'json', xml: 'xml', html: 'xml', htm: 'xml', vue: 'xml',
  yaml: 'yaml', yml: 'yaml', css: 'css', scss: 'scss', sql: 'sql', py: 'python', go: 'go', rs: 'rust',
  sh: 'bash', bash: 'bash', zsh: 'bash', toml: 'ini', ini: 'ini', conf: 'ini', properties: 'ini'
}
const escapeHtml = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const highlightText = (text, lang) => {
  if (lang && hljs.getLanguage(lang)) {
    try { return hljs.highlight(text, { language: lang, ignoreIllegals: true }).value } catch (e) { /* 落空走转义 */ }
  }
  return escapeHtml(text)
}
const previewCodeHtml = computed(() =>
  highlightText(previewText.value, CODE_LANG[artifactExt(previewEntry.value)] || ''))
// 编辑态同样要有格式：高亮层垫在透明 textarea 下面，随输入与滚动同步
const previewDraftHtml = computed(() => {
  const lang = previewKind.value === PV.MD ? 'markdown' : (CODE_LANG[artifactExt(previewEntry.value)] || '')
  return highlightText(previewDraft.value, lang)
})
const syncEditScroll = e => {
  const hl = previewEditHlEl.value
  if (!hl) return
  hl.scrollTop = e.target.scrollTop
  hl.scrollLeft = e.target.scrollLeft
}
const clearPreview = () => {
  previewSeq++
  previewEntry.value = null
  previewText.value = ''
  previewTruncated.value = false
  previewLoading.value = false
  previewError.value = ''
  previewEditing.value = false
  previewDraft.value = ''
  previewSaving.value = false
}

const activeRoot = computed(() => {
  const alias = String(fileDir.value || '').split('/')[0]
  return form.value.roots.find(r => r.alias === alias)
    || form.value.roots.find(r => r.primary)
    || form.value.roots[0]
    || null
})

const crumbs = computed(() => {
  const segs = String(fileDir.value || '').split('/').filter(Boolean)
  const out = []
  let acc = ''
  for (const s of segs) {
    acc = acc ? acc + '/' + s : s
    out.push({ label: s, path: acc })
  }
  return out
})

const visibleFiles = computed(() => {
  const q = fileKw.value.trim().toLowerCase()
  if (!q) return fileEntries.value
  return fileEntries.value.filter(e => String(e.name || '').toLowerCase().includes(q))
})

const loadFiles = async () => {
  const seq = ++filesSeq
  if (!form.value.roots.length || !fileDir.value) {
    fileEntries.value = []
    fileTotal.value = 0
    fileTruncated.value = false
    fileError.value = ''
    return
  }
  fileLoading.value = true
  try {
    const r = await listProjectFiles(id.value, fileDir.value)
    if (seq !== filesSeq) return
    const d = (r && r.data) || {}
    fileEntries.value = Array.isArray(d.entries) ? d.entries : []
    fileTotal.value = d.total || 0
    fileTruncated.value = !!d.truncated
    fileError.value = ''
  } catch (e) {
    if (seq !== filesSeq) return
    fileEntries.value = []
    fileTotal.value = 0
    fileTruncated.value = false
    fileError.value = e.message || '目录读取失败'
  } finally {
    if (seq === filesSeq) fileLoading.value = false
  }
}

const selectRoot = root => {
  if (fileDir.value === root.alias) return
  if (previewEditing.value) { message.warning('正在编辑，先保存或取消'); return }
  fileDir.value = root.alias
  fileKw.value = ''
  clearPreview()
  loadFiles()
}

const openDir = path => {
  if (path === fileDir.value) return
  if (previewEditing.value) { message.warning('正在编辑，先保存或取消'); return }
  fileDir.value = path
  fileKw.value = ''
  clearPreview()
  loadFiles()
}

const selectFile = async e => {
  if (!e) return
  const seq = ++previewSeq
  previewEntry.value = e
  previewText.value = ''
  previewTruncated.value = false
  previewError.value = ''
  previewLoading.value = false
  previewEditing.value = false
  previewDraft.value = ''
  previewSaving.value = false
  const kind = previewKindOf(e)
  // 页面/图片/PDF 直接把签名地址交给 iframe/img，不必再取原文
  if (kind !== PV.MD && kind !== PV.TEXT && kind !== PV.TABLE) return
  previewLoading.value = true
  try {
    const r = await projectFileContent(id.value, e.path)
    if (seq !== previewSeq) return
    const d = (r && r.data) || {}
    previewText.value = String(d.content || '')
    previewTruncated.value = !!d.truncated
  } catch (err) {
    if (seq !== previewSeq) return
    previewError.value = err.message || '读取失败'
  } finally {
    if (seq === previewSeq) previewLoading.value = false
  }
}

const startEdit = async () => {
  if (!previewEditable.value || previewLoading.value) return
  previewDraft.value = previewText.value
  previewEditing.value = true
  await nextTick()
  if (previewEditEl.value) previewEditEl.value.focus()
  if (previewEditHlEl.value) { previewEditHlEl.value.scrollTop = 0; previewEditHlEl.value.scrollLeft = 0 }
}

const cancelEdit = () => {
  if (!previewDirty.value) { previewEditing.value = false; return }
  Modal.confirm({
    title: '放弃未保存的修改？',
    content: '这次编辑的内容不会保留。',
    okText: '放弃',
    cancelText: '继续编辑',
    onOk: () => { previewEditing.value = false }
  })
}

const saveEdit = async () => {
  const e = previewEntry.value
  if (!e || previewSaving.value) return
  if (!previewDirty.value) { previewEditing.value = false; return }
  previewSaving.value = true
  try {
    await saveProjectFileContent(id.value, e.path, previewDraft.value)
    previewText.value = previewDraft.value
    previewEditing.value = false
    message.success('已保存')
    const path = e.path
    await loadFiles()
    const fresh = fileEntries.value.find(x => x.path === path)
    if (fresh && previewEntry.value && previewEntry.value.path === path) previewEntry.value = fresh
  } catch (err) {
    message.error('保存失败：' + (err.message || '请重试'))
  } finally {
    previewSaving.value = false
  }
}

const downloadPreview = async () => {
  const e = previewEntry.value
  if (!e) return
  try {
    await downloadProjectFile(id.value, e.path, e.name, fileDir.value)
  } catch (err) {
    message.error('下载失败：' + (err.message || '请重试'))
  }
}

const onFileClick = async e => {
  if (e.kind === 'dir') { openDir(e.path); return }
  if (inlinePreview.value) {
    if (previewEditing.value) { message.warning('正在编辑，先保存或取消'); return }
    selectFile(e)
    return
  }
  if (e.viewUrl) {
    const url = resolveImg(e.viewUrl)
    if (url) window.open(url, '_blank', 'noopener')
    return
  }
  try {
    await downloadProjectFile(id.value, e.path, e.name, fileDir.value)
  } catch (err) {
    message.error('下载失败：' + (err.message || '请重试'))
  }
}

const fmtSize = n => {
  const v = Number(n) || 0
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(2) + ' MB'
  if (v >= 1024) return (v / 1024).toFixed(1) + ' KB'
  return v + ' B'
}

const fmtTime = ms => {
  if (!ms) return '—'
  const d = new Date(Number(ms))
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const fmtAgo = ms => {
  const v = Number(ms)
  if (!v) return '—'
  const d = Date.now() - v
  if (d < 0) return fmtTime(v)
  if (d < 60e3) return '刚刚'
  if (d < 3600e3) return Math.floor(d / 60e3) + ' 分钟前'
  if (d < 86400e3) return Math.floor(d / 3600e3) + ' 小时前'
  if (d < 7 * 86400e3) return Math.floor(d / 86400e3) + ' 天前'
  return fmtTime(v)
}

// ---------- 隔离 ----------
const isoSaving = ref(false)
const saveMemoryIsolated = async v => {
  const next = v === true ? 1 : 0
  const prev = form.value.memoryIsolated
  form.value.memoryIsolated = next
  isoSaving.value = true
  try {
    await updateProject(id.value, { memoryIsolated: next })
    message.success(next ? '已开启：这个工作区的对话不带入也不写入个人记忆' : '已关闭：对话照常使用个人记忆')
  } catch (e) {
    form.value.memoryIsolated = prev
    message.error('保存失败：' + (e.message || '请重试'))
  } finally {
    isoSaving.value = false
  }
}

// ---------- 事项清单（C2） ----------
const items = ref([])
const itemLoading = ref(false)
const itemError = ref('')
const itemDraft = ref('')
const itemAdding = ref(false)
const itemBusy = ref(false)
const openItems = computed(() => items.value.filter(i => i.status !== 'done'))

const loadItems = async () => {
  itemLoading.value = true
  try {
    const r = await listProjectItems(id.value)
    items.value = (r && r.data) || []
    itemError.value = ''
  } catch (e) {
    items.value = []
    itemError.value = e.message || '读取失败'
  } finally {
    itemLoading.value = false
  }
}

const addItem = async () => {
  const title = itemDraft.value.trim()
  if (!title || itemBusy.value) return
  itemBusy.value = true
  try {
    const r = await createProjectItem(id.value, { title })
    if (r && r.data) items.value.push(r.data)
    itemDraft.value = ''
    itemAdding.value = false
  } catch (e) {
    message.error('添加失败：' + (e.message || '请重试'))
  } finally {
    itemBusy.value = false
  }
}

/** 勾选只改用户明确点的这一条；不按对话内容自动推断状态（避免「编造进度」） */
const toggleItem = async it => {
  if (itemBusy.value) return
  const next = it.status === 'done' ? 'open' : 'done'
  itemBusy.value = true
  try {
    const r = await updateProjectItem(id.value, it.id, { status: next })
    if (r && r.data) Object.assign(it, r.data)
  } catch (e) {
    message.error('更新失败：' + (e.message || '请重试'))
  } finally {
    itemBusy.value = false
  }
}

const removeItem = async it => {
  itemBusy.value = true
  try {
    await deleteProjectItem(id.value, it.id)
    items.value = items.value.filter(x => x.id !== it.id)
  } catch (e) {
    message.error('删除失败：' + (e.message || '请重试'))
  } finally {
    itemBusy.value = false
  }
}

/** 去聊：有关联会话就接回那条；否则在本工作区新建一条，ask 只预填输入框、不自动发送 */
const chatItem = it => {
  const ask = it.title
  const bind = { itemId: it.id, itemProjectId: id.value }
  if (it.sessionId) {
    router.push({ path: '/chat', query: { sid: it.sessionId, ask, ...bind } })
    return
  }
  setNewSessionProject(id.value)
  sessionStore.newChatTick++
  router.push({ path: '/chat', query: { ask, ...bind } })
}

// ---------- 记忆 ----------
const wmOpen = ref(false)
const wmLoading = ref(false)
const wmSaving = ref(false)
const wmContent = ref('')
const wmDraft = ref('')
const wmError = ref('')

const loadMemory = async () => {
  wmLoading.value = true
  try {
    const r = await getWorkspaceMemory(id.value)
    wmContent.value = (r && r.data && r.data.content) || ''
    wmError.value = ''
  } catch (e) {
    wmContent.value = ''
    wmError.value = e.message || '读取失败'
  } finally {
    wmLoading.value = false
  }
}

const openMemory = async () => {
  wmOpen.value = true
  wmDraft.value = wmContent.value
  await loadMemory()
  wmDraft.value = wmContent.value
}

const saveMemory = async () => {
  wmSaving.value = true
  try {
    await saveWorkspaceMemory(id.value, wmDraft.value)
    wmContent.value = wmDraft.value
    wmOpen.value = false
    message.success('已保存工作区记忆')
  } catch (e) {
    message.error('保存失败：' + (e.message || '请重试'))
  } finally {
    wmSaving.value = false
  }
}

// ---------- 页头：改名 / 外观 / 新对话 ----------
const nameEditing = ref(false)
const nameDraft = ref('')
const nameInput = ref(null)
const savingName = ref(false)

const startRename = () => {
  nameDraft.value = form.value.name || ''
  nameEditing.value = true
  nextTick(() => nameInput.value && nameInput.value.focus({ cursor: 'all' }))
}

const cancelRename = () => { nameEditing.value = false }

const saveName = async () => {
  const name = nameDraft.value.trim()
  if (!name) { message.warning('工作区名不能为空'); return }
  savingName.value = true
  try {
    await updateProject(id.value, { name })
    form.value.name = name
    nameEditing.value = false
    message.success('名字已更新')
    refreshSidebarSessions(undefined, { refreshProjects: true }).catch(() => {})
  } catch (e) {
    message.error('保存失败：' + (e.message || '请重试'))
  } finally {
    savingName.value = false
  }
}

const lookOpen = ref(false)
const saveLook = async patch => {
  const next = {
    icon: patch.icon !== undefined ? patch.icon : form.value.icon,
    color: patch.color !== undefined ? patch.color : form.value.color
  }
  const prev = { icon: form.value.icon, color: form.value.color }
  form.value.icon = next.icon
  form.value.color = next.color
  try {
    await updateProject(id.value, next)
  } catch (e) {
    form.value.icon = prev.icon
    form.value.color = prev.color
    message.error('外观保存失败：' + (e.message || '请重试'))
    return
  }
  refreshSidebarSessions(undefined, { refreshProjects: true }).catch(() => {})
}

const goChat = () => {
  setNewSessionProject(id.value)
  sessionStore.newChatTick++
  router.push('/chat')
}

// ---------- 生命周期 ----------
const load = async () => {
  loading.value = true
  try {
    const r = await getProject(id.value)
    const p = (r && r.data) || {}
    if (p.isImplicit) {
      router.replace('/chat')
      return
    }
    form.value = {
      name: p.name || '',
      status: p.status || 'active',
      memoryIsolated: p.memoryIsolated || 0,
      roots: Array.isArray(p.roots) ? p.roots.map(x => ({ ...x })) : [],
      icon: p.icon || '',
      color: p.color || ''
    }
    executorId.value = p.executorId || ''
    executorPick.value = undefined
    ensureFileDir()
    loadedOnce.value = true
    await Promise.all([loadFiles(), loadMemory(), loadItems()])
  } catch (e) {
    message.error('工作区加载失败：' + (e.message || '请重试'))
  } finally {
    loading.value = false
  }
}

watch(id, () => {
  loadedOnce.value = false
  nameEditing.value = false
  lookOpen.value = false
  fileDir.value = ''
  fileEntries.value = []
  fileKw.value = ''
  fileTotal.value = 0
  fileError.value = ''
  clearPreview()
  wmContent.value = ''
  wmError.value = ''
  items.value = []
  itemError.value = ''
  itemAdding.value = false
  load()
})

onMounted(() => {
  if (pjWrapEl.value && typeof ResizeObserver !== 'undefined') {
    pjRO = new ResizeObserver(es => {
      const w = es[0] && es[0].contentRect ? es[0].contentRect.width : 0
      if (w) pjWrapW.value = Math.round(w)
    })
    pjRO.observe(pjWrapEl.value)
  }
  loadExecutors()
  load()
})

onUnmounted(() => {
  if (pjRO) pjRO.disconnect()
  pjRO = null
})
</script>

<style scoped>
/* ---------- 页头 ---------- */
.pj-head { flex-wrap: wrap; }
.pj-head-ic { width: 20px; height: 20px; flex: none; color: var(--app-ok); }
.pj-name-input { width: 240px; height: 28px; }
/* 页名即改名入口：外观保持标题原样，悬停才提示可点 */
.pj-title-btn {
  border: 0; background: none; padding: 0; font: inherit; color: inherit;
  cursor: pointer; text-align: left;
}
.pj-title-btn:hover { text-decoration: underline; }
.pj-icon-btn {
  width: 26px; height: 26px; flex: none; display: grid; place-items: center;
  border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel);
  color: var(--app-text2); cursor: pointer;
}
.pj-icon-btn:hover { color: var(--app-accent); border-color: var(--app-accent-border); }
.pj-head-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.pj-iso-inline {
  display: flex; align-items: center; gap: 8px; flex: none;
  margin-left: 4px; padding-left: 12px; border-left: 1px solid var(--app-border);
}
.pj-iso-shield { color: var(--app-ok); font-size: 15px; }
.pj-iso-label { font-size: 12px; color: var(--app-text2); white-space: nowrap; }

/* ---------- 外观弹层 ---------- */
.pj-look-title { font-size: 12.5px; font-weight: 600; margin-bottom: 8px; }
.pj-icons { display: grid; grid-template-columns: repeat(7, 1fr); gap: 5px; }
.pj-icon {
  height: 32px; border: 1px solid var(--app-border); border-radius: 7px;
  background: var(--app-panel); display: grid; place-items: center;
  color: var(--app-text2); font-size: 15px; cursor: pointer;
}
.pj-icon:hover { color: var(--app-accent); border-color: var(--app-accent-border); }
.pj-icon.on { color: var(--app-accent); border-color: var(--app-accent); background: var(--app-accent-weak); }
.pj-colors { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.pj-color {
  width: 20px; height: 20px; border: 0; border-radius: 50%; cursor: pointer;
  display: grid; place-items: center; color: #fff; font-size: 11px;
}

/* ---------- 内容区：固定高度，列表与记忆各自内部滚动 ---------- */
.pj-body { padding: 14px 20px 16px; display: flex; min-height: 0; overflow: hidden; }
/* 桌面两栏：左预览（自适应宽）+ 右目录/记忆（384px）；窄屏改单列，见文件末尾媒体查询 */
.pj-wrap { width: 100%; height: 100%; min-height: 0; display: flex; gap: 12px; }
.pj-skeleton { flex: 1; }
.pj-side { width: 384px; flex: none; min-height: 0; display: flex; flex-direction: column; gap: 12px; }
.pj-card { padding: 0; }
.pj-dir-card { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.pj-files { flex: 1; min-height: 0; overflow: auto; }
.pj-mem-card { flex: 0 1 auto; max-height: 34%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.pj-mem-body { flex: 1; min-height: 0; overflow: auto; }
.pj-card-head { display: flex; align-items: center; gap: 8px; padding: 12px 16px; }
.pj-card-title { margin: 0; font-size: 13.5px; font-weight: 600; }
.pj-spacer { flex: 1; }
.pj-text-btn {
  border: 0; background: none; padding: 0; font: inherit; font-size: 12px;
  color: var(--app-accent); cursor: pointer; white-space: nowrap;
}
.pj-text-btn:hover { text-decoration: underline; }
.pj-text-btn:disabled { color: var(--app-text3); cursor: not-allowed; text-decoration: none; }
.pj-text-btn.danger { color: var(--app-text3); }
.pj-text-btn.danger:hover { color: var(--app-danger-text); }
.pj-note { font-size: 11.5px; color: var(--app-text3); }
.pj-empty { padding: 14px 16px; font-size: 12px; color: var(--app-text3); }
.pj-empty.err { color: var(--app-danger-text); }

/* ---------- 隔离 ---------- */
.pj-state {
  font-size: 11px; font-weight: 400; line-height: 1; padding: 3px 8px;
  border-radius: 999px; background: #f1f3f5; color: var(--app-text3);
}
.pj-state.on { background: var(--app-ok-weak); color: var(--app-ok); }
.pj-help { font-size: 13px; color: var(--app-text3); cursor: help; }
.pj-help:hover { color: var(--app-accent); }

/* ---------- 目录 ---------- */
.pj-roots { padding: 0 8px; }
.pj-root { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px; }
.pj-root:hover { background: var(--app-panel-2); }
.pj-root.on { background: var(--app-accent-weak); }
.pj-root-main {
  display: inline-flex; align-items: center; gap: 6px; flex: none;
  border: 0; background: none; padding: 0; font: inherit; cursor: pointer; color: var(--app-text);
}
.pj-root-ic { color: #f0ad2d; font-size: 16px; }
.pj-root-name { font-size: 12px; font-weight: 600; display: inline-flex; align-items: center; gap: 5px; }
.pj-mini-pill {
  font-size: 10px; font-weight: 400; line-height: 15px; padding: 0 5px;
  border: 1px solid var(--app-border); border-radius: 999px; color: var(--app-text3);
}
.pj-root-path {
  flex: 1; min-width: 0; border: 0; background: none; padding: 0; text-align: left;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px;
  color: var(--app-text3); cursor: copy; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pj-root-path:hover { color: var(--app-text2); }
.pj-root-acts { display: flex; gap: 10px; flex: none; }
.pj-exec-pick { padding: 2px 8px 8px; }
.pj-divider { height: 1px; background: var(--app-border); margin: 4px 16px 0; }

/* ---------- 文件 ---------- */
.pj-filebar { display: flex; align-items: center; gap: 8px; padding: 10px 16px 6px; }
.pj-filebar-title { font-size: 12px; font-weight: 500; color: var(--app-text2); }
.pj-filebar-count { font-size: 11.5px; color: var(--app-text3); }
.pj-search { width: 170px; }
.pj-crumbs { display: flex; align-items: center; gap: 4px; padding: 0 16px 6px; flex-wrap: wrap; }
.pj-crumb {
  border: 0; background: none; padding: 0; font: inherit; font-size: 11.5px;
  color: var(--app-accent); cursor: pointer;
}
.pj-crumb:hover { text-decoration: underline; }
.pj-crumb.last { color: var(--app-text2); cursor: default; text-decoration: none; }
.pj-crumb-sep { font-size: 11px; color: var(--app-text3); }
.pj-file-list { padding: 0 8px 10px; }
.pj-file-row {
  display: grid; grid-template-columns: 20px minmax(0, 1fr) 90px 90px;
  align-items: center; gap: 8px; width: 100%; padding: 6px 8px;
  border: 0; border-radius: 6px; background: none; font: inherit; font-size: 12px;
  color: var(--app-text); text-align: left; cursor: pointer;
}
.pj-file-row:hover { background: var(--app-panel-2); }
.pj-file-head {
  position: sticky; top: 0; z-index: 1;
  background: var(--app-panel);
  font-size: 11px; color: var(--app-text3); cursor: default; padding-top: 2px; padding-bottom: 4px;
}
.pj-file-head:hover { background: none; }
.pj-dir-ic { color: #f0ad2d; font-size: 15px; }
.pj-file-ic { color: var(--app-text3); font-size: 15px; }
.pj-file-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pj-col-size, .pj-col-time { font-size: 11.5px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.pj-col-time { text-align: right; }
.pj-list-note { padding: 0 16px 10px; }
.pj-file-row.on { background: var(--app-accent-weak); }

/* ---------- 预览（桌面左栏） ---------- */
.pj-pv-card { flex: 1; min-width: 0; display: flex; flex-direction: column; overflow: hidden; }
.pj-pv-head {
  display: flex; align-items: center; gap: 8px; flex: none;
  padding: 10px 16px; min-height: 44px; border-bottom: 1px solid var(--app-border);
}
.pj-pv-ic { color: var(--app-text3); font-size: 15px; flex: none; }
.pj-pv-name { font-size: 12.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pj-pv-meta { font-size: 11px; color: var(--app-text3); white-space: nowrap; flex: none; }
.pj-pv-acts { display: flex; align-items: center; gap: 12px; flex: none; }
.pj-pv-dirty { font-size: 11px; color: var(--app-warn); }
/* 编辑态：高亮层垫在透明 textarea 下面，两层字号/行高/内边距必须完全一致才能对齐 */
.pj-pv-editor { position: relative; height: 100%; }
.pj-pv-edit-hl,
.pj-pv-edit {
  position: absolute; inset: 0; margin: 0; padding: 16px 20px; border: 0; outline: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.75;
  white-space: pre; tab-size: 2; letter-spacing: normal;
}
.pj-pv-edit-hl { overflow: hidden; color: var(--app-text); pointer-events: none; }
.pj-pv-edit-hl code.hljs { display: block; padding: 0; background: transparent; overflow: visible; }
.pj-pv-edit {
  width: 100%; height: 100%; resize: none; overflow: auto;
  background: transparent; color: transparent; caret-color: var(--app-text);
}
.pj-pv-body { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.pj-pv-body :deep(.ant-spin-nested-loading) { flex: 1; min-height: 0; }
.pj-pv-body :deep(.ant-spin-container) { height: 100%; }
.pj-pv-empty, .pj-pv-none {
  height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; padding: 16px; color: var(--app-text3); font-size: 12px; text-align: center;
}
.pj-pv-empty-ic { font-size: 30px; opacity: .5; }
.pj-pv-sub { font-size: 11px; color: var(--app-text3); }
.pj-pv-img-wrap {
  height: 100%; display: flex; align-items: center; justify-content: center;
  padding: 16px; background: var(--app-panel-2);
}
.pj-pv-img { max-width: 100%; max-height: 100%; object-fit: contain; border-radius: 6px; }
.pj-pv-frame { width: 100%; height: 100%; border: 0; display: block; background: #fff; }
.pj-pv-md { height: 100%; overflow: auto; padding: 18px 24px; }
.pj-pv-text {
  height: 100%; overflow: auto; margin: 0; padding: 16px 20px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.75;
  color: var(--app-text); white-space: pre; tab-size: 2;
}
/* highlight.js 自带底色与内边距，嵌在我们的 pre 里会多一圈：只留语法着色 */
.pj-pv-text code.hljs { display: block; padding: 0; background: transparent; overflow: visible; }
.pj-pv-note {
  flex: none; padding: 8px 16px; border-top: 1px solid var(--app-border);
  font-size: 11.5px; color: var(--app-warn-text); background: var(--app-warn-weak);
}

/* ---------- 事项清单 ---------- */
.pj-item-card { margin-top: 12px; }
.pj-item-body { padding: 0 16px 12px; }
.pj-item-add { display: flex; gap: 8px; margin-bottom: 8px; }
.pj-item-add .app-btn { flex: none; height: 28px; padding: 0 12px; }
.pj-item-list { display: flex; flex-direction: column; gap: 2px; }
.pj-item-row {
  display: flex; align-items: center; gap: 8px; padding: 5px 4px;
  border-radius: 6px;
}
.pj-item-row:hover { background: var(--app-panel-2); }
.pj-item-check {
  flex: none; width: 18px; height: 18px; display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--app-border); border-radius: 50%; background: none; cursor: pointer;
  color: var(--app-accent); padding: 0;
}
.pj-item-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--app-text3); opacity: .55; }
.pj-item-row.done .pj-item-check { border-color: var(--app-accent); background: var(--app-accent-weak); }
.pj-item-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 1px; }
.pj-item-title { font-size: 12.5px; color: var(--app-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pj-item-row.done .pj-item-title { color: var(--app-text3); text-decoration: line-through; }
.pj-item-note { font-size: 11.5px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pj-item-x {
  flex: none; width: 22px; height: 22px; border: 0; background: none; cursor: pointer;
  color: var(--app-text3); font-size: 15px; line-height: 1; border-radius: 5px;
}
.pj-item-x:hover { color: var(--app-danger); background: var(--app-panel-2); }

/* ---------- 记忆 ---------- */
.pj-mem-path {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10.5px;
  padding: 1px 6px; border: 1px solid var(--app-border); border-radius: 5px;
  background: var(--app-panel-2); color: var(--app-text3);
}
.pj-mem-text {
  margin: 0 16px 12px; padding: 10px 12px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  font-family: inherit; font-size: 12px; line-height: 1.8;
  color: var(--app-text); white-space: pre-wrap; word-break: break-word;
}
.pj-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }

/* 两栏态：右栏 384px，路径换到第二行，文件行收紧、时间列不折行 */
.pj-wrap:not(.pj-single) .pj-side .pj-root { flex-wrap: wrap; }
.pj-wrap:not(.pj-single) .pj-side .pj-root-acts { order: 2; margin-left: auto; }
.pj-wrap:not(.pj-single) .pj-side .pj-root-path { order: 3; flex-basis: 100%; padding-left: 22px; }
.pj-wrap:not(.pj-single) .pj-side .pj-file-row { grid-template-columns: 18px minmax(0, 1fr) 54px 96px; gap: 6px; padding: 5px 8px; }
.pj-wrap:not(.pj-single) .pj-side .pj-col-time { white-space: nowrap; }

/* 单列态（窄屏或内容区放不下两栏）：目录+记忆占满整宽 */
.pj-wrap.pj-single { flex-direction: column; }
.pj-wrap.pj-single .pj-side { width: auto; flex: 1; min-height: 0; }

@media (max-width: 720px) {
  .pj-iso-inline { flex: 1 1 100%; margin-left: 0; padding-left: 0; border-left: 0; }
  .pj-head-actions { margin-left: 0; width: 100%; }
  .pj-card-head { flex-wrap: wrap; }
  .pj-root { flex-wrap: wrap; }
  .pj-root-path { flex-basis: calc(100% - 24px); }
  .pj-filebar { flex-wrap: wrap; }
  .pj-filebar .pj-spacer { display: none; }
  .pj-search { width: 100%; }
  .pj-file-row { grid-template-columns: 20px minmax(0, 1fr) 70px; }
  .pj-col-time { display: none; }
}
</style>
