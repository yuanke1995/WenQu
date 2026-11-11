<template>
  <!-- 技能（Skills）：把「这类问题该怎么做」固化成能力包，模型按需读取后照做。
       本页是「能力运营台」：统计条可下钻、筛选排序、卡面带使用数据、操作分级（开关 + ⋯ 菜单），
       详情走右侧抽屉（SkillDrawer）。内置只能停用，自己装的可以编辑/删除/下载/上架。 -->
  <div class="app-page">
    <!-- 无独立页头行：搜索/安装/新建并入统计条右侧，3 行压成 2 行；标题仅留给读屏 -->
    <h1 class="app-page-title sr-only">技能</h1>

    <div class="app-page-body">
      <a-spin v-if="loading && !skills.length" style="display:block;margin:48px auto" />

      <div v-else-if="!skills.length" class="app-card key-empty">
        <div class="key-empty-title">还没有技能</div>
        <div class="key-empty-desc">
          技能用来固化「这类问题该怎么做」——步骤、输出格式、禁忌。模型按需读取后照做，不必每次在提问里重复交代。
        </div>
        <div class="key-empty-ops">
          <button class="app-btn" @click="openCreate">新建第一个技能</button>
          <button class="app-btn ghost" @click="openInstall('zip')">上传 zip 安装</button>
          <button class="app-btn ghost" @click="openInstall('url')">从 URL 安装</button>
        </div>
      </div>

      <template v-else>
        <!-- 统计条：每个数字可点击下钻成筛选（效果图 SCENE 1 ①） -->
        <div class="statbar">
          <button class="stat" @click="setStatFilter('all')">
            <span class="n">{{ summary.total }}</span><span class="l">全部技能</span>
            <span class="jump">全部 <right-outlined /></span>
          </button>
          <button class="stat" @click="setStatFilter('active')">
            <span class="n ok">{{ summary.active }}</span><span class="l">生效中</span>
            <span class="jump">筛选 <right-outlined /></span>
          </button>
          <button class="stat" @click="setStatFilter('sortMost')">
            <span class="n">{{ summary.reads30dTotal }}</span><span class="l">30 天被模型读取</span>
            <span class="jump">按使用排序 <right-outlined /></span>
          </button>
          <button class="stat warn" @click="setStatFilter('zero')">
            <warning-outlined class="s-ic" />
            <span class="n">{{ summary.zeroCall }}</span><span class="l">零调用</span>
            <span class="jump">去处理 <right-outlined /></span>
          </button>
          <button class="stat warn" @click="setStatFilter('missing')">
            <warning-outlined class="s-ic" />
            <span class="n">{{ summary.missingDesc }}</span><span class="l">缺描述</span>
            <span class="jump">去处理 <right-outlined /></span>
          </button>
          <!-- 原页头操作组：搜索 + 安装/新建，收进统计条右侧 -->
          <div class="stat-acts">
            <a-tooltip title="技能用来固化「这类问题该怎么做」：步骤、输出格式、禁忌。模型先看清单，需要时再读正文">
              <info-circle-outlined class="stat-hint-ic" />
            </a-tooltip>
            <a-input v-model:value="keyword" class="head-search" size="small" allow-clear placeholder="搜索名称、描述或分类">
              <template #prefix><search-outlined class="head-search-ic" /></template>
            </a-input>
            <a-tooltip title="刷新列表">
              <button class="app-icon-btn" aria-label="刷新技能列表" :disabled="loading" @click="load"><reload-outlined /></button>
            </a-tooltip>
            <a-tooltip title="上传 zip 安装">
              <button class="app-icon-btn" aria-label="上传 zip 安装" @click="openInstall('zip')"><upload-outlined /></button>
            </a-tooltip>
            <a-tooltip title="从 URL 安装">
              <button class="app-icon-btn" aria-label="从 URL 安装" @click="openInstall('url')"><link-outlined /></button>
            </a-tooltip>
            <button class="app-btn" @click="openCreate"><plus-outlined /> 新建技能</button>
          </div>
        </div>

        <!-- 筛选行（效果图 SCENE 1 ②）：滚动到顶部时吸顶 -->
        <div class="sk-sticky">
        <div class="filterbar">
          <div class="seg">
            <button v-for="o in STATUS_OPTIONS" :key="o.value" class="seg-item"
                    :class="{ on: status === o.value }" @click="status = o.value">
              {{ o.label }} <span class="cnt" :class="{ warn: o.value === 'attention' && statusCounts.attention > 0 }">{{ statusCounts[o.value] }}</span>
            </button>
          </div>
          <button v-if="status === 'zero' || status === 'missing'" class="chip focus-chip" @click="status = 'all'">
            {{ status === 'zero' ? '零调用' : '缺描述' }} <close-outlined class="chip-x" />
          </button>
          <div class="chips">
            <button class="chip" :class="{ on: category === 'all' }" @click="category = 'all'">全部分类</button>
            <button v-for="c in categoryChips" :key="c.name" class="chip"
                    :class="{ on: category === c.name }" @click="category = c.name">{{ c.name }}</button>
          </div>
          <span class="fb-sp"></span>
          <a-select v-model:value="source" size="small" class="fb-sel" :options="SOURCE_OPTIONS" />
          <a-select v-model:value="sortBy" size="small" class="fb-sel" :options="SORT_OPTIONS" />
          <button class="app-btn ghost small" @click="toggleBatchMode">
            {{ batchMode ? '退出批量' : '批量操作' }}
          </button>
        </div>
        </div><!-- /sk-sticky -->

        <div v-if="!sorted.length" class="app-card key-empty">
          <div class="key-empty-title">没有匹配的技能</div>
          <div class="key-empty-desc">当前搜索与筛选条件下没有技能。</div>
          <button class="app-btn ghost small" @click="clearFilters">清除筛选</button>
        </div>

        <template v-else>
          <!-- 原按来源分组取消，来源降级为筛选维度；说明文案在选中对应来源时提示 -->
          <div class="skill-tip" v-if="source === 'builtin'">
            内置技能随版本分发、所有人可见：只能停用，不能修改或删除。
          </div>
          <div class="skill-tip" v-else-if="source === 'workspace'">
            工作区技能是助手在对话里沉淀到当前会话的，随会话走；换个会话就看不到了。
          </div>

          <div class="skill-grid">
            <div v-for="s in sorted" :key="s.source + ':' + s.name" class="app-card skill-card"
                 :class="{ off: s.disabled, zero: isZero(s), picked: isSelected(s), 'has-check': batchMode }"
                 @click="onCardClick(s)">
              <a-checkbox v-if="batchMode" class="card-check" :checked="isSelected(s)"
                          @click.stop @change="toggleSelect(s)" />
              <div class="skill-card-head">
                <span class="cat-ic" :class="'cat-' + catKey(s)"><component :is="catIcon(s)" /></span>
                <span class="skill-card-name" :title="s.name">{{ s.name }}</span>
                <span class="app-pill" :class="pillCls(s)">{{ pillText(s) }}</span>
              </div>
              <div class="skill-card-tags">
                <span class="skill-tag">{{ s.category || '通用' }}</span>
                <span class="skill-tag src">{{ sourceLabel(s.source) }}</span>
              </div>
              <div class="skill-card-desc" :class="{ miss: !s.description }" :title="s.description || ''">
                {{ descText(s) }}
              </div>
              <div class="skill-card-data" :class="{ zero: isZero(s) }">
                <span>30 天 <b>{{ s.reads30d || 0 }}</b> 次</span>
                <span class="dot">·</span>
                <span>{{ s.disabled ? '停用中' : (s.lastUsedAt ? '最近 ' + relTime(s.lastUsedAt) : '从未被读取') }}</span>
                <svg v-if="sparkPoints(s)" class="spark" :class="'cat-' + catKey(s)"
                     width="64" height="18" viewBox="0 0 64 18">
                  <polyline :points="sparkPoints(s)" fill="none" stroke="currentColor" stroke-width="1.6" />
                </svg>
              </div>
              <div class="skill-card-foot" @click.stop>
                <a-switch v-if="s.disableable" size="small" :checked="!s.disabled"
                          :loading="switching === s.name" @change="onSwitch(s, $event)" />
                <span v-else class="foot-dim">{{ s.disabled ? '已停用' : '生效中' }}</span>
                <button class="btn-view" @click="openDetail(s)"><line-chart-outlined /> 查看详情</button>
                <a-dropdown :trigger="['click']" placement="bottomRight">
                  <button class="dots-btn" aria-label="更多操作"><more-outlined /></button>
                  <template #overlay>
                    <a-menu @click="({ key }) => onCardMenu(key, s)">
                      <a-menu-item v-if="s.editable" key="edit"><edit-outlined /> 编辑</a-menu-item>
                      <a-menu-item key="copy"><copy-outlined /> 复制为新技能</a-menu-item>
                      <a-menu-item v-if="s.editable" key="versions"><history-outlined /> 版本历史</a-menu-item>
                      <a-menu-item v-if="s.deletable" key="download"><download-outlined /> 下载 zip</a-menu-item>
                      <a-menu-item v-if="s.editable" key="publish"><shop-outlined /> 上架到市场</a-menu-item>
                      <template v-if="s.deletable">
                        <a-menu-divider />
                        <a-menu-item key="remove" danger><delete-outlined /> 删除</a-menu-item>
                      </template>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </div>
          </div>

          <p class="skill-foot-hint">
            已停用的技能不会进模型清单，但本轮仍可在输入框里点名使用一次。
            助手在对话里沉淀的技能落在当前会话的沙盒工作区，不占「我的技能」。
          </p>
        </template>
      </template>
    </div>

    <!-- 批量操作条（选择模式底部浮出） -->
    <div v-if="batchMode" class="batch-bar">
      <span class="bb-n">已选 <b>{{ selected.length }}</b> 项</span>
      <button class="app-btn ghost small" :disabled="!selected.length || batching" @click="runBatch('disable')">停用</button>
      <button class="app-btn ghost small" :disabled="!selected.length || batching" @click="runBatch('enable')">启用</button>
      <a-popconfirm title="删除选中的技能？删除后不可恢复" ok-text="删除" cancel-text="取消"
                    :disabled="!selected.length || batching" @confirm="runBatch('delete')">
        <button class="app-btn danger small" :disabled="!selected.length || batching">删除</button>
      </a-popconfirm>
      <button class="app-link-btn" @click="exitBatch">取消</button>
    </div>

    <!-- 新建：模板画廊 + SKILL.md 实时预览（效果图 SCENE 3） -->
    <a-modal v-model:open="createOpen" title="新建技能" :footer="null" :width="1080"
             :style="{ maxWidth: 'calc(100vw - 32px)' }"
             :mask-closable="false" :body-style="{ padding: '0' }" destroy-on-close>
      <div class="mk">
        <div class="mk-rail">
          <div class="mk-rt">创建方式</div>
          <div class="mk-rail-item" :class="{ on: createMode === 'template' }" @click="createMode = 'template'">
            <appstore-outlined /> <span>从模板创建</span><span class="mk-badge">推荐</span>
          </div>
          <div class="mk-rail-item" :class="{ on: createMode === 'blank' }" @click="pickBlank">
            <file-outlined /> <span>空白 SKILL.md</span>
          </div>
          <div class="mk-rail-item" @click="goImportZip">
            <upload-outlined /> <span>导入 zip<small>去页头上传技能包</small></span>
          </div>
        </div>
        <div class="mk-mid">
          <template v-if="createMode === 'template'">
            <div class="mk-mt">选一个模板</div>
            <div class="mk-md">模板已预写步骤骨架与输出格式，创建后可直接改</div>
            <div class="mk-tpls">
              <div v-for="t in TEMPLATES" :key="t.key" class="mk-tpl" :class="{ sel: tplKey === t.key }"
                   @click="pickTemplate(t)">
                <span v-if="tplKey === t.key" class="mk-chk"><check-outlined /></span>
                <span class="cat-ic" :class="'cat-' + t.icon"><component :is="CAT_ICONS[t.icon]" /></span>
                <div class="n">{{ t.label }}</div>
                <div class="d">{{ t.desc }}</div>
                <div class="m">{{ t.meta }}</div>
              </div>
            </div>
          </template>
          <template v-else>
            <div class="mk-mt">空白 SKILL.md</div>
            <div class="mk-md">从零写正文：右侧展开「编辑正文」直接输入；步骤、输出格式、禁忌三段最实用。</div>
            <div class="mk-md">想要现成骨架？回左侧选一个模板，改两行就能用。</div>
          </template>
        </div>
        <div class="mk-prev">
          <div class="mk-prev-t">
            <file-text-outlined /> SKILL.md 实时预览
            <button class="app-link-btn mk-edit-body" @click="editBody = !editBody">{{ editBody ? '收起编辑' : '编辑正文' }}</button>
          </div>
          <pre v-if="!editBody" class="mk-code">{{ previewMd }}</pre>
          <a-textarea v-else v-model:value="createForm.content" :rows="12" class="key-code-area" />
          <div class="mk-fields">
            <div class="mk-field">
              <label>技能名（创建后不可改，请核对）</label>
              <a-input v-model:value="createForm.name" size="small" :maxlength="64"
                       placeholder="如：report-helper（中英文/数字/下划线/连字符，1~64 字符）" />
            </div>
            <div class="mk-field">
              <label>描述 —— 模型靠这句判断何时读取</label>
              <a-input v-model:value="createForm.description" size="small" :maxlength="1000"
                       placeholder="什么场景、出现哪些词时该用它，如「子表（一行对多条）的表单与大表分轮建规则」" />
              <div class="mk-hint" :class="descHint.cls">{{ descHint.text }}</div>
            </div>
            <div class="mk-field">
              <label>分类与图标</label>
              <div class="mk-icon-row">
                <span v-for="c in CAT_PICKS" :key="c.category" class="mk-icon-sel"
                      :class="['cat-' + c.key, { on: createForm.category === c.category }]"
                      :title="c.category" @click="createForm.category = c.category">
                  <component :is="CAT_ICONS[c.key]" />
                </span>
                <a-select v-model:value="createForm.category" size="small" class="mk-cat-sel" :options="CATEGORY_OPTIONS" />
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="key-modal-foot mk-foot">
        <button class="app-btn ghost" @click="createOpen = false">取消</button>
        <button class="app-btn" :disabled="creating" @click="submitCreate">{{ creating ? '创建中…' : '创建并生效' }}</button>
      </div>
    </a-modal>

    <!-- 安装：zip / URL -->
    <a-modal v-model:open="installOpen" title="安装技能" :footer="null" :width="720" :mask-closable="false" destroy-on-close>
      <a-tabs v-model:activeKey="installTab">
        <a-tab-pane key="zip" tab="上传 zip">
          <a-upload-dragger class="skill-drop" :before-upload="pickZip" :show-upload-list="false" accept=".zip" :disabled="installing">
            <p class="ant-upload-drag-icon"><inbox-outlined /></p>
            <p class="ant-upload-text">点击或拖拽 zip 到这里</p>
            <p class="ant-upload-hint">
              压缩包根目录或唯一外层目录里要有 SKILL.md；可带 references/、scripts/。
              上限 {{ limits.zipMB || 24 }}MB / {{ limits.files || 120 }} 个文件。
            </p>
          </a-upload-dragger>
          <div v-if="zipFile" class="skill-zip-picked">
            已选择：<b>{{ zipFile.name }}</b>（{{ fmtSize(zipFile.size) }}）
            <button class="app-link-btn" @click="zipFile = null">移除</button>
          </div>
          <a-form layout="vertical" style="margin-top:12px">
            <a-form-item label="技能名（可选）">
              <a-input v-model:value="installForm.name" placeholder="留空则取 SKILL.md frontmatter 里的 name" :maxlength="64" />
            </a-form-item>
            <a-checkbox v-model:checked="installForm.overwrite">同名覆盖（整包替换，旧包里的 references/scripts 会丢失）</a-checkbox>
          </a-form>
          <div class="key-modal-foot">
            <span v-if="uploadPct > 0 && uploadPct < 100" class="skill-upload-pct">上传中 {{ uploadPct }}%</span>
            <button class="app-btn ghost" @click="installOpen = false">取消</button>
            <button class="app-btn" :disabled="installing || !zipFile" @click="submitZip">
              {{ installing ? '安装中…' : '安装' }}
            </button>
          </div>
        </a-tab-pane>

        <a-tab-pane key="url" tab="从 URL 安装">
          <a-alert v-if="!remote.enabled" type="warning" show-icon style="margin-bottom:12px"
                   message="远程安装未开启"
                   description="需要管理员在「系统设置 → 工具调用 → 技能」里配置「允许远程安装的域名」（精确匹配，逗号分隔）。服务端仍会校验公网地址并限制体积。" />
          <a-alert v-else type="info" show-icon style="margin-bottom:12px"
                   :message="'只允许从以下域名安装：' + (remote.hosts || []).join('、')"
                   description="地址必须指向 SKILL.md 原文（GitHub 页面链接请换成 raw 链接），内容须含 name / description 的 frontmatter。" />
          <a-form layout="vertical">
            <a-form-item label="SKILL.md 地址" required>
              <a-input v-model:value="installForm.url" placeholder="https://raw.githubusercontent.com/org/repo/main/skills/demo/SKILL.md"
                       :disabled="!remote.enabled" />
            </a-form-item>
            <a-form-item label="技能名（可选）">
              <a-input v-model:value="installForm.name" placeholder="留空则取文件里 frontmatter 的 name" :maxlength="64"
                       :disabled="!remote.enabled" />
            </a-form-item>
            <a-checkbox v-model:checked="installForm.overwrite">同名覆盖</a-checkbox>
          </a-form>
          <div class="key-modal-foot">
            <button class="app-btn ghost" @click="installOpen = false">取消</button>
            <button class="app-btn" :disabled="installing || !remote.enabled" @click="submitUrl">
              {{ installing ? '安装中…' : '安装' }}
            </button>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-modal>

    <!-- 编辑 -->
    <a-modal :open="editOpen" :title="'编辑技能：' + editForm.name" :footer="null" :width="920"
             :mask-closable="false" destroy-on-close @cancel="tryCloseEdit">
      <a-form layout="vertical">
        <a-form-item label="描述" required>
          <a-input v-model:value="editForm.description" :maxlength="1000" show-count
                   placeholder="一句话说明什么场景用它——模型靠这句判断要不要读取" />
        </a-form-item>
        <a-form-item label="分类">
          <a-select v-model:value="editForm.category" :options="CATEGORY_OPTIONS" style="width:220px" />
        </a-form-item>
        <a-form-item label="SKILL.md 原文">
          <MdEditor v-model="editForm.content" editor-id="sk-edit-md" language="zh-CN"
                   :theme="mdTheme" :toolbars="MD_TOOLBARS" :no-prettier="true" :footers="[]"
                   class="sk-md-editor" @on-save="submitEdit" />
        </a-form-item>
      </a-form>
      <p class="skill-form-hint">正文、描述与分类可改；<b>技能名必须保持「{{ editForm.name }}」</b>，改名会打断引用。保存前旧版自动留快照，可在详情抽屉「版本」里回滚。⌘/Ctrl+S 直接保存。</p>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="tryCloseEdit">取消</button>
        <button class="app-btn" :disabled="saving" @click="submitEdit">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </a-modal>

    <!-- 详情抽屉（效果图 SCENE 2） -->
    <SkillDrawer v-model:open="drawerOpen" :skill="drawerSkill" :initial-tab="drawerTab"
                 @changed="load" @edit="openEdit" @copy="openCopy" @publish="openPublish"
                 @download="download" @remove="confirmDelete" />

    <!-- 上架到市场（与市场页共用弹窗） -->
    <MarketPublishModal v-model:open="publishOpen" :preset-name="publishName" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Modal, message } from 'ant-design-vue'
import {
  AppstoreOutlined, CheckOutlined, CloseOutlined, CodeOutlined, CopyOutlined, DeleteOutlined,
  DownloadOutlined, EditOutlined, FileOutlined, FileTextOutlined, HistoryOutlined, InboxOutlined, InfoCircleOutlined,
  LineChartOutlined, LinkOutlined, MoreOutlined, PlusOutlined, ReloadOutlined, RightOutlined,
  SearchOutlined, ShopOutlined, SkinOutlined, TableOutlined, ToolOutlined, UploadOutlined,
  WarningOutlined
} from '@ant-design/icons-vue'
import {
  batchSkills, createSkill, deleteSkill, downloadSkillZip, getSkillDetail, installSkillFromUrl,
  installSkillZip, listSkills, setSkillDisabled, updateSkill
} from '../api'
import MarketPublishModal from '../components/MarketPublishModal.vue'
import SkillDrawer from '../components/SkillDrawer.vue'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { themeState } from '../utils/theme'

const mdTheme = computed(() => (themeState.value === 'dark' ? 'dark' : 'light'))
// 工具栏只留基础排版与预览：与智能体页系统提示词编辑器同一套取舍（图片上传/公式会把大段
// 语法塞进 SKILL.md 纯文本契约，一律不要）；⌘S 由 on-save 接保存。
const MD_TOOLBARS = ['bold', 'underline', 'italic', '-', 'strikeThrough', 'quote', 'unorderedList', 'orderedList',
  '-', 'code', 'codeRow', 'table', '-', 'revoke', 'next', '=', 'preview', 'previewOnly', 'pageFullscreen', 'fullscreen']

const SOURCE_LABEL = { user: '我的技能', builtin: '内置技能', workspace: '工作区技能' }

// ---------------- 上架到市场 ----------------
const publishOpen = ref(false)
const publishName = ref('')
const openPublish = s => {
  publishName.value = s.name
  publishOpen.value = true
}

const skills = ref([])
const summary = ref({ total: 0, active: 0, reads30dTotal: 0, zeroCall: 0, missingDesc: 0 })
const remote = ref({ enabled: false, hosts: [] })
const limits = ref({})
const loading = ref(false)
const keyword = ref('')

// ---------------- 筛选与排序 ----------------
const STATUS_OPTIONS = [
  { value: 'all', label: '全部' },
  { value: 'active', label: '生效中' },
  { value: 'disabled', label: '已停用' },
  { value: 'attention', label: '需关注' }
]
const SOURCE_OPTIONS = [
  { value: 'all', label: '全部来源' },
  { value: 'user', label: '我的技能' },
  { value: 'builtin', label: '内置技能' },
  { value: 'workspace', label: '工作区技能' }
]
const SORT_OPTIONS = [
  { value: 'lastUsed', label: '最近使用' },
  { value: 'mostUsed', label: '使用最多' },
  { value: 'updated', label: '最近更新' },
  { value: 'name', label: '名称' }
]
const status = ref('all')
const category = ref('all')
const source = ref('all')
const sortBy = ref('lastUsed')

const isZero = s => !s.disabled && (s.reads30d || 0) === 0
const isAttention = s => isZero(s) || !s.description || s.platformDisabled

// 关键词/来源/分类先过滤（状态计数与分段共用），状态与排序最后
const baseList = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  let list = skills.value
  if (k) list = list.filter(s =>
    (s.name || '').toLowerCase().includes(k) ||
    (s.description || '').toLowerCase().includes(k) ||
    (s.category || '').toLowerCase().includes(k))
  if (source.value !== 'all') list = list.filter(s => s.source === source.value)
  if (category.value !== 'all') list = list.filter(s => (s.category || '通用') === category.value)
  return list
})
const statusCounts = computed(() => {
  const l = baseList.value
  return {
    all: l.length,
    active: l.filter(s => !s.disabled).length,
    disabled: l.filter(s => s.disabled).length,
    attention: l.filter(isAttention).length
  }
})
const categoryChips = computed(() => {
  const m = new Map()
  skills.value.forEach(s => {
    const c = s.category || '通用'
    m.set(c, (m.get(c) || 0) + 1)
  })
  return [...m.entries()].sort((a, b) => b[1] - a[1]).map(([name]) => ({ name }))
})
const sorted = computed(() => {
  let list = baseList.value
  const st = status.value
  if (st === 'active') list = list.filter(s => !s.disabled)
  else if (st === 'disabled') list = list.filter(s => s.disabled)
  else if (st === 'attention') list = list.filter(isAttention)
  else if (st === 'zero') list = list.filter(isZero)
  else if (st === 'missing') list = list.filter(s => !s.description)
  const arr = [...list]
  const by = sortBy.value
  if (by === 'lastUsed') arr.sort((a, b) => (b.lastUsedAt || 0) - (a.lastUsedAt || 0) || (b.reads30d || 0) - (a.reads30d || 0))
  else if (by === 'mostUsed') arr.sort((a, b) => (b.reads30d || 0) - (a.reads30d || 0) || (b.lastUsedAt || 0) - (a.lastUsedAt || 0))
  else if (by === 'updated') arr.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
  else arr.sort((a, b) => (a.name || '').localeCompare(b.name || '', 'zh'))
  return arr
})

const setStatFilter = f => {
  if (f === 'all') status.value = 'all'
  else if (f === 'active') status.value = 'active'
  else if (f === 'sortMost') sortBy.value = 'mostUsed'
  else if (f === 'zero') status.value = 'zero'
  else if (f === 'missing') status.value = 'missing'
}
const clearFilters = () => {
  keyword.value = ''
  status.value = 'all'
  category.value = 'all'
  source.value = 'all'
}

// ---------------- 批量操作 ----------------
const batchMode = ref(false)
const selected = ref([])
const batching = ref(false)
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const exitBatch = () => { batchMode.value = false; selected.value = [] }
const isSelected = s => selected.value.some(x => x.source === s.source && x.name === s.name)
const toggleSelect = s => {
  const i = selected.value.findIndex(x => x.source === s.source && x.name === s.name)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(s)
}
const runBatch = async action => {
  if (!selected.value.length) return
  batching.value = true
  try {
    const r = await batchSkills({ names: selected.value.map(s => s.name), action })
    if (!r || !r.success) throw new Error((r && r.msg) || '批量操作失败')
    const ok = r.data?.ok || 0
    const failed = r.data?.failed || 0
    const fails = (r.data?.results || []).filter(x => !x.ok)
    const verb = action === 'disable' ? '停用' : action === 'enable' ? '启用' : '删除'
    if (failed) message.warning(`批量${verb}：成功 ${ok}，失败 ${failed}：` + fails.slice(0, 3).map(f => f.name + (f.msg ? `（${f.msg}）` : '')).join('、'))
    else message.success(`已${verb} ${ok} 个技能`)
    selected.value = []
    await load()
  } catch (e) {
    message.error('批量操作失败：' + errText(e))
  } finally {
    batching.value = false
  }
}

// ---------------- 卡片展示辅助 ----------------
const CAT_ICONS = { form: TableOutlined, doc: FileTextOutlined, code: CodeOutlined, design: SkinOutlined, general: ToolOutlined }
const catKey = s => {
  const c = (s && s.category) || ''
  if (c.includes('表单') || c.includes('表格')) return 'form'
  if (c.includes('文档') || c.includes('写作')) return 'doc'
  if (c.includes('代码') || c.includes('审查')) return 'code'
  if (c.includes('设计') || c.includes('走查')) return 'design'
  return 'general'
}
const catIcon = s => CAT_ICONS[catKey(s)]
const pillCls = s => s.disabled ? (s.platformDisabled ? 'warn' : 'muted') : 'ok'
const pillText = s => s.disabled ? (s.platformDisabled ? '平台停用' : '我停用了') : '生效中'

/** 描述短（<40 字）时续显正文首段摘录，把卡面描述区填到至少两行；hover title 仍是完整描述 */
const descText = s => {
  const d = s.description || ''
  if (!d) return '（未填描述：模型不会主动读取它）'
  if (d.length >= 40 || !s.bodyExcerpt) return d
  return d + ' ' + s.bodyExcerpt
}

/** 7 天迷你趋势：series7d 旧→新；全 0 不画（返回空串） */
const sparkPoints = s => {
  const arr = Array.isArray(s.series7d) ? s.series7d : []
  if (!arr.length || !arr.some(v => v > 0)) return ''
  const max = Math.max(...arr, 1)
  return arr.map((v, i) => `${(i * 64 / (arr.length - 1)).toFixed(1)},${(16 - (v / max) * 12).toFixed(1)}`).join(' ')
}

const sourceLabel = s => SOURCE_LABEL[s] || s || '技能'
const fmtSize = n => {
  if (!n) return ''
  if (n >= 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + 'MB'
  if (n >= 1024) return Math.round(n / 1024) + 'KB'
  return n + 'B'
}
const relTime = ts => {
  if (!ts) return '从未'
  const m = Math.floor((Date.now() - ts) / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return m + ' 分钟前'
  const h = Math.floor(m / 60)
  if (h < 24) return h + ' 小时前'
  const d = Math.floor(h / 24)
  if (d < 30) return d + ' 天前'
  return Math.floor(d / 30) + ' 个月前'
}
const errText = e => (e && e.message) || String(e)

const load = async () => {
  loading.value = true
  try {
    const r = await listSkills()
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    skills.value = Array.isArray(r.data?.skills) ? r.data.skills : []
    summary.value = r.data?.summary || {
      total: skills.value.length,
      active: skills.value.filter(s => !s.disabled).length,
      reads30dTotal: skills.value.reduce((n, s) => n + (s.reads30d || 0), 0),
      zeroCall: skills.value.filter(isZero).length,
      missingDesc: skills.value.filter(s => !s.description).length
    }
    remote.value = r.data?.remoteInstall || { enabled: false, hosts: [] }
    limits.value = r.data?.limits || {}
    // 抽屉打开时同步刷新对应行（开关/编辑/批量后状态与统计跟着变）
    if (drawerOpen.value && drawerSkill.value) {
      const cur = skills.value.find(s => s.name === drawerSkill.value.name && s.source === drawerSkill.value.source)
      if (cur) drawerSkill.value = cur
    }
  } catch (e) {
    message.error('加载技能失败：' + errText(e))
  } finally {
    loading.value = false
  }
}

// ---------------- 详情抽屉 ----------------
const drawerOpen = ref(false)
const drawerSkill = ref(null)
const drawerTab = ref('content')
const openDetail = (s, t = 'content') => {
  drawerTab.value = t
  drawerSkill.value = s
  drawerOpen.value = true
}
const onCardClick = s => {
  if (batchMode.value) toggleSelect(s)
  else openDetail(s)
}

// ---------------- 新建（模板画廊 + 实时预览） ----------------
const CATEGORY_OPTIONS = ['表单表格', '文档写作', '代码审查', '设计走查', '检索问答', '回答规范', '通用']
  .map(v => ({ value: v, label: v }))
const CAT_PICKS = [
  { key: 'form', category: '表单表格' },
  { key: 'doc', category: '文档写作' },
  { key: 'code', category: '代码审查' },
  { key: 'design', category: '设计走查' },
  { key: 'general', category: '通用' }
]
const TEMPLATES = [
  { key: 'form', icon: 'form', label: '表单录入', category: '表单表格', desc: '字段清单、校验规则、子表分轮建', meta: '步骤骨架', body:
    '# 做法\n\n1. 先明确场景与字段清单：字段名、类型、必填、校验规则。\n2. 有子表（一行对多条）时：先建主表，第二轮再追加子表。\n3. 几十个字段的大表分轮建，每轮 ≤ 15 个字段。\n4. 输出：表格形态，字段说明列全。\n\n# 禁忌\n\n- 不要一轮塞进全部字段。\n- 不要猜字段类型；拿不准先问。' },
  { key: 'doc', icon: 'doc', label: '文档写作', category: '文档写作', desc: '场景→结构→再搭骨架的写法约定', meta: '步骤骨架', body:
    '# 做法\n\n1. 先明确读者与场景：给谁看、解决什么问题。\n2. 再定结构：章节提纲，每节一句话目标。\n3. 然后搭骨架：标题、段落、示例，再填内容。\n4. 输出：Markdown，标题层级不跳级。\n\n# 禁忌\n\n- 不要堆功能不写场景。\n- 接口文档不要缺请求/响应示例。' },
  { key: 'code', icon: 'code', label: '代码审查', category: '代码审查', desc: '安全、健壮性、可读性三清单', meta: '分栈清单', body:
    '# 做法\n\n按三张清单逐项核对，只报可操作、可复现的问题：\n\n1. 安全：注入、越权边界、密钥泄漏、未校验输入。\n2. 健壮：异常处理、空值与边界、并发与重试。\n3. 可读：命名、函数长度、重复逻辑、缺测试。\n\n# 输出格式\n\n- 按文件与行号列问题：现象、影响、建议修法。\n- 纯风格偏好不写成问题。' },
  { key: 'general', icon: 'general', label: '数据排查', category: '通用', desc: '状态→模块→报错路径定位法', meta: '步骤骨架', body:
    '# 做法\n\n1. 先确认现象：时间、人物、期望与实际的差。\n2. 沿状态定位：状态机、数据状态、缓存一致性。\n3. 沿模块定位：数据经过哪些服务/表/任务。\n4. 沿报错定位：日志、堆栈、上游调用链。\n5. 给结论：根因、影响面、修复动作。\n\n# 禁忌\n\n- 没证据不要先改线上数据。' },
  { key: 'design', icon: 'design', label: '设计走查', category: '设计走查', desc: '一致性、可达性、状态反馈核对', meta: '清单骨架', body:
    '# 做法\n\n评审设计稿按清单逐项核对：\n\n1. 布局一致性：间距、对齐、同类组件复用。\n2. 状态完整：默认/悬停/按下/禁用/加载/错误/空态。\n3. 可达性：对比度、点击热区、文字截断。\n4. 边界情况：超长文案、小屏、弱网。\n\n# 输出格式\n\n- 问题按严重度列：阻塞 / 建议改 / 可选。' },
  { key: 'blank', icon: 'general', label: '空白自定义', category: '通用', desc: '只给骨架，自由发挥', meta: '自由发挥', body:
    '# 做法\n\n1. \n2. \n\n# 输出格式\n\n\n\n# 禁忌\n\n' }
]

const createOpen = ref(false)
const creating = ref(false)
const createMode = ref('template')
const tplKey = ref('form')
const editBody = ref(false)
const createForm = ref({ name: '', description: '', category: '表单表格', content: '' })

const openCreate = () => {
  const t = TEMPLATES[0]
  createMode.value = 'template'
  tplKey.value = t.key
  editBody.value = false
  createForm.value = { name: '', description: '', category: t.category, content: t.body }
  createOpen.value = true
}
const pickTemplate = t => {
  tplKey.value = t.key
  const f = createForm.value
  f.content = t.body
  f.category = t.category
  if (!f.description.trim()) f.description = t.desc
}
const pickBlank = () => {
  createMode.value = 'blank'
  editBody.value = true
  const f = createForm.value
  if (!f.content.trim()) f.content = TEMPLATES[TEMPLATES.length - 1].body
}
const goImportZip = () => {
  createOpen.value = false
  openInstall('zip')
}
/** 复制为新技能：带原文/描述/分类进空白模式，名字加 -copy */
const openCopy = async s => {
  try {
    const r = await getSkillDetail(s.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    const d = r.data || {}
    createMode.value = 'blank'
    tplKey.value = ''
    editBody.value = true
    createForm.value = {
      name: (s.name + '-copy').slice(0, 64),
      description: d.description || '',
      category: d.category || s.category || '通用',
      content: d.content || ''
    }
    createOpen.value = true
  } catch (e) {
    message.error('读取技能失败：' + errText(e))
  }
}

const previewMd = computed(() => {
  const f = createForm.value
  const lines = ['---', 'name: ' + (f.name.trim() || '（未填）'),
    'description: ' + (f.description.trim() || '（未填——模型不会主动读取）')]
  if (f.category) lines.push('category: ' + f.category)
  lines.push('---', '', f.content || '（正文为空）')
  return lines.join('\n')
})
/** 触发词质量即时提示：含引号或场景词 ≥2 判「优」；太短/太单调警 */
const descHint = computed(() => {
  const d = (createForm.value.description || '').trim()
  if (!d) return { cls: '', text: '一句话说明什么场景用它；模型先看这句，决定要不要读正文。' }
  const quoted = /[「」『』“”"']/.test(d)
  const tokens = d.split(/[，,、；;/／\s。]+/).filter(t => t.length >= 2)
  if (quoted || tokens.length >= 2) return { cls: 'ok', text: `含 ${Math.max(tokens.length, 2)} 个场景触发词，判断质量：优` }
  if (d.length < 15) return { cls: 'warn', text: '描述太短，模型可能判断不出何时该读取它' }
  return { cls: 'warn', text: '建议用 / 或、分隔多个场景触发词，提高命中率' }
})

const submitCreate = async () => {
  const f = createForm.value
  if (!f.name.trim()) { message.warning('请填写技能名'); return }
  if (!f.description.trim()) { message.warning('请填写描述——模型靠它判断何时读取'); return }
  if (!f.content.trim()) { message.warning('请填写技能正文'); return }
  creating.value = true
  try {
    const r = await createSkill({
      name: f.name.trim(), description: f.description.trim(), content: f.content,
      overwrite: false, category: f.category || ''
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '创建失败')
    message.success('已创建技能「' + (r.data?.name || f.name) + '」')
    createOpen.value = false
    await load()
  } catch (e) {
    message.error('创建失败：' + errText(e))
  } finally {
    creating.value = false
  }
}

// ---------------- 安装 ----------------
const installOpen = ref(false)
const installTab = ref('zip')
const installing = ref(false)
const uploadPct = ref(0)
const zipFile = ref(null)
const installForm = ref({ url: '', name: '', overwrite: false })
const openInstall = tab => {
  installTab.value = tab || 'zip'
  installForm.value = { url: '', name: '', overwrite: false }
  zipFile.value = null
  uploadPct.value = 0
  installOpen.value = true
}
/** before-upload 返回 false：只接管文件，不走组件自带上传（安装按钮统一提交） */
const pickZip = file => {
  if (file.size > (limits.value.zipMB || 24) * 1024 * 1024) {
    message.warning('技能包超过 ' + (limits.value.zipMB || 24) + 'MB 上限')
    return false
  }
  zipFile.value = file
  return false
}
const submitZip = async () => {
  if (!zipFile.value) { message.warning('请选择 zip 文件'); return }
  installing.value = true
  uploadPct.value = 0
  try {
    const r = await installSkillZip(zipFile.value,
      { name: installForm.value.name.trim(), overwrite: installForm.value.overwrite },
      p => { uploadPct.value = p })
    if (!r || !r.success) throw new Error((r && r.msg) || '安装失败')
    message.success('已安装技能「' + (r.data?.name || '') + '」')
    installOpen.value = false
    await load()
  } catch (e) {
    message.error('安装失败：' + errText(e))
  } finally {
    installing.value = false
    uploadPct.value = 0
  }
}
const submitUrl = async () => {
  if (!installForm.value.url.trim()) { message.warning('请填写技能文件地址'); return }
  installing.value = true
  try {
    const r = await installSkillFromUrl({
      url: installForm.value.url.trim(),
      name: installForm.value.name.trim(),
      overwrite: installForm.value.overwrite
    })
    if (!r || !r.success) throw new Error((r && r.msg) || '安装失败')
    message.success('已安装技能「' + (r.data?.name || '') + '」')
    installOpen.value = false
    await load()
  } catch (e) {
    message.error('安装失败：' + errText(e))
  } finally {
    installing.value = false
  }
}

// ---------------- 编辑 ----------------
const editOpen = ref(false)
const saving = ref(false)
const editForm = ref({ name: '', description: '', category: '', content: '' })
// 打开时的基线快照：关窗前比对，有未保存改动就拦一道——蒙版已禁点，剩下的是 X/取消/离开
let editBaseline = ''
const openEdit = async s => {
  try {
    const r = await getSkillDetail(s.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '读取失败')
    editForm.value = {
      name: r.data.name,
      description: r.data.description || '',
      category: r.data.category || s.category || '通用',
      content: r.data.raw || r.data.content || ''
    }
    editBaseline = JSON.stringify(editForm.value)
    editOpen.value = true
  } catch (e) {
    message.error('读取技能失败：' + errText(e))
  }
}
const tryCloseEdit = () => {
  if (JSON.stringify(editForm.value) === editBaseline) { editOpen.value = false; return }
  Modal.confirm({
    title: '有未保存的修改',
    content: '关闭后这次的修改会丢失（未写入版本快照）。',
    okText: '放弃修改并关闭',
    okButtonProps: { danger: true },
    cancelText: '继续编辑',
    onOk: () => { editOpen.value = false }
  })
}
const submitEdit = async () => {
  const f = editForm.value
  if (!f.description.trim()) { message.warning('请填写描述'); return }
  if (!f.content.trim()) { message.warning('SKILL.md 不能为空'); return }
  saving.value = true
  try {
    const r = await updateSkill(f.name, { description: f.description.trim(), content: f.content, category: f.category || '' })
    if (!r || !r.success) throw new Error((r && r.msg) || '保存失败')
    message.success('已保存，下一轮生效')
    editBaseline = JSON.stringify(f)
    editOpen.value = false
    await load()
  } catch (e) {
    message.error('保存失败：' + errText(e))
  } finally {
    saving.value = false
  }
}

// ---------------- ⋯ 菜单 / 启停 / 删除 / 下载 ----------------
const onCardMenu = (key, s) => {
  if (key === 'edit') openEdit(s)
  else if (key === 'copy') openCopy(s)
  else if (key === 'versions') openDetail(s, 'versions')
  else if (key === 'download') download(s)
  else if (key === 'publish') openPublish(s)
  else if (key === 'remove') confirmDelete(s)
}
const switching = ref('')
const onSwitch = async (s, checked) => {
  switching.value = s.name
  try {
    const r = await setSkillDisabled(s.name, !checked)
    if (!r || !r.success) throw new Error((r && r.msg) || '操作失败')
    if (!checked) message.success(r.msg || '已停用')
    await load()
  } catch (e) {
    message.error('操作失败：' + errText(e))
  } finally {
    switching.value = ''
  }
}
const confirmDelete = s => {
  Modal.confirm({
    title: '删除技能「' + s.name + '」？',
    content: '删除后不可恢复（技能包连同附属文件一起删除）。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: () => remove(s)
  })
}
const remove = async s => {
  try {
    const r = await deleteSkill(s.name)
    if (!r || !r.success) throw new Error((r && r.msg) || '删除失败')
    message.success('已删除')
    if (drawerSkill.value && drawerSkill.value.name === s.name) drawerOpen.value = false
    await load()
  } catch (e) {
    message.error('删除失败：' + errText(e))
  }
}
const download = async s => {
  try {
    await downloadSkillZip(s.name)
  } catch (e) {
    message.error('下载失败：' + errText(e))
  }
}

onMounted(load)
</script>

<style scoped>
/* 类别五色（效果图基准）；暗色下弱底改透明度 */
.app-page {
  --c-form: #2e6be6;   --c-form-bg: #eaf0fd;
  --c-doc: #3b8f4e;    --c-doc-bg: #eaf5ec;
  --c-code: #7c5cd6;   --c-code-bg: #f0ecfb;
  --c-design: #c77c14; --c-design-bg: #faf3e6;
  --c-general: #0f8a8a;--c-general-bg: #e6f4f4;
}
html[data-theme='dark'] .app-page {
  --c-form-bg: rgba(46, 107, 230, .18);
  --c-doc-bg: rgba(59, 143, 78, .18);
  --c-code-bg: rgba(124, 92, 214, .2);
  --c-design-bg: rgba(199, 124, 20, .18);
  --c-general-bg: rgba(15, 138, 138, .18);
}

/* 统计条右侧操作组：原页头的搜索 + 安装/新建。安装两个压成图标按钮，
   保证 5 张统计卡 + 操作组在常规宽度下仍是一行 */
.stat-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.stat-hint-ic { color: var(--app-text3); font-size: 14px; cursor: help; }
.head-search { width: 210px; }
.head-search-ic { color: var(--app-text3); font-size: 12px; }
.key-empty { text-align: center; padding: 40px 20px; }
.key-empty-title { font-weight: 600; margin-bottom: 6px; }
.key-empty-desc { font-size: 13px; color: var(--app-text3); line-height: 1.7; max-width: 560px; margin: 0 auto 14px; }
.key-empty-ops { display: flex; justify-content: center; gap: 8px; }
.key-modal-foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-top: 18px; }
.key-code-area textarea { font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace); font-size: 12px; line-height: 1.7; }
/* SKILL.md 编辑器（md-editor-v3，与智能体页提示词编辑器同款）：高度贴合弹窗，圆角对齐卡片 */
.sk-md-editor { height: 440px; border-radius: 8px; overflow: hidden; --md-bk-color: var(--app-panel-2, #fff); }
.sk-md-editor.md-editor-dark { --md-bk-color: var(--app-panel-2, #1e1e1e); }

/* ---------------- 吸顶区（仅筛选行） ---------------- */
/* 统计条随页面滚走，筛选行滚到顶部时才吸顶（sticky 天然行为：未到达前留在原位）。
   body 有 16px padding-top，top:0 会留出一条缝让卡片从吸顶条上方露出来，
   故 top:-16px 让背景贴住滚动视口顶沿。吸顶条上方只留 8px 呼吸位：
   margin-top -8px 吃掉统计条 12px 下边距里的一半，未吸顶时视觉间距仍是 ~12px。 */
.sk-sticky { position: sticky; top: -16px; z-index: 6; background: var(--app-bg);
  margin: -8px -20px 12px; padding: 8px 20px 10px; }
.sk-sticky .filterbar { margin-bottom: 0; }

/* ---------------- 统计条 ---------------- */
.statbar { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.stat { flex: 1; min-width: 150px; background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 10px 14px; display: flex; align-items: center; gap: 10px; cursor: pointer; text-align: left;
  font-family: inherit; color: var(--app-text); }
.stat:hover { border-color: var(--app-accent-border); }
.stat .n { font-size: 19px; font-weight: 650; letter-spacing: .01em; }
.stat .n.ok { color: var(--app-ok); }
.stat .l { font-size: 12px; color: var(--app-text3); }
.stat .s-ic { color: var(--app-warn); font-size: 14px; flex: none; }
.stat.warn { border-color: var(--app-warn-border); background: #fffdf7; }
html[data-theme='dark'] .stat.warn { background: var(--app-warn-weak); }
.stat.warn .n { color: var(--app-warn-text); }
.stat .jump { margin-left: auto; font-size: 11px; color: var(--app-accent); display: inline-flex; align-items: center; gap: 2px; white-space: nowrap; }
.stat .jump :deep(svg) { font-size: 10px; }

/* ---------------- 筛选行 ---------------- */
.filterbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.seg { display: inline-flex; background: var(--app-bg-layer-3, #eceff3); border-radius: 7px; padding: 2px; }
.seg-item { height: 24px; padding: 0 12px; display: inline-flex; align-items: center; gap: 5px; border-radius: 5px;
  border: none; background: transparent; font-size: 12px; color: var(--app-text2); cursor: pointer; font-family: inherit; }
.seg-item.on { background: var(--app-panel); color: var(--app-text); font-weight: 600; box-shadow: var(--app-shadow-sm); }
.seg-item .cnt { font-size: 11px; color: var(--app-text3); }
.seg-item.on .cnt { color: var(--app-text2); }
.seg-item .cnt.warn { color: var(--app-warn-text); }
.chips { display: inline-flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.chip { height: 24px; padding: 0 11px; display: inline-flex; align-items: center; gap: 4px; border-radius: 999px;
  border: 1px solid var(--app-border-strong); background: var(--app-panel); font-size: 12px; color: var(--app-text2);
  cursor: pointer; font-family: inherit; }
.chip:hover { border-color: var(--app-accent-border); }
.chip.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 500; }
.focus-chip { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); }
.chip-x { font-size: 10px; }
.fb-sp { flex: 1; }
.fb-sel { min-width: 118px; }

/* ---------------- 卡片网格 ---------------- */
.skill-tip { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: -4px 0 10px; }
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; margin-bottom: 18px; }
.skill-card { position: relative; display: flex; flex-direction: column; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.skill-card:hover { border-color: var(--app-accent-border); box-shadow: var(--app-shadow); }
.skill-card.off { background: var(--app-panel-2); }
.skill-card.off .skill-card-name { color: var(--app-text3); font-weight: 500; }
.skill-card.zero { border-color: var(--app-warn-border); }
.skill-card.picked { border-color: var(--app-accent); box-shadow: 0 0 0 3px rgba(46, 107, 230, .12); }
.card-check { position: absolute; top: 10px; left: 10px; z-index: 2; }
.skill-card.has-check .skill-card-head { padding-left: 26px; }
.skill-card-head { display: flex; align-items: center; gap: 9px; min-width: 0; }
.cat-ic { width: 32px; height: 32px; border-radius: 8px; flex: none; display: inline-flex; align-items: center; justify-content: center; font-size: 15px; }
.cat-form { background: var(--c-form-bg); color: var(--c-form); }
.cat-doc { background: var(--c-doc-bg); color: var(--c-doc); }
.cat-code { background: var(--c-code-bg); color: var(--c-code); }
.cat-design { background: var(--c-design-bg); color: var(--c-design); }
.cat-general { background: var(--c-general-bg); color: var(--c-general); }
.skill-card-name { flex: 1; min-width: 0; font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-card-tags { display: flex; gap: 6px; margin-top: 9px; }
.skill-tag { font-size: 11px; color: var(--app-text2); background: var(--app-panel-2); border: 1px solid var(--app-border);
  border-radius: 4px; padding: 1px 7px; }
.skill-tag.src { color: var(--app-text3); }
/* 描述区恒占 3 行：卡片高度不再随描述长短浮动，数据行与底栏才有统一基线 */
.skill-card-desc { font-size: 12px; color: var(--app-text2); line-height: 1.6; margin-top: 7px;
  height: calc(1.6em * 3); flex: none; overflow: hidden;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }
.skill-card-desc.miss { color: var(--app-warn-text); }
/* 数据行恒定 18px：有迷你趋势图（svg 高 18）与没有时行高一致；下边距是底栏的最小间距 */
.skill-card-data { display: flex; align-items: center; gap: 8px; margin-top: 9px; margin-bottom: 10px;
  height: 18px; flex: none; font-size: 11px; color: var(--app-text3); }
.skill-card-data b { color: var(--app-text); font-weight: 600; }
.skill-card-data .spark { margin-left: auto; flex: none; }
.skill-card-data.zero b { color: var(--app-warn-text); }
.spark.cat-form { color: var(--c-form); }
.spark.cat-doc { color: var(--c-doc); }
.spark.cat-code { color: var(--c-code); }
.spark.cat-design { color: var(--c-design); }
.spark.cat-general { color: var(--c-general); }
/* 底栏恒贴卡片底部：grid 拉伸让同行卡片等高，auto 吃掉描述区留白，
   开关 / 查看详情 / ⋯ 在整行卡片里落在同一水平线上 */
.skill-card-foot { display: flex; align-items: center; gap: 8px; border-top: 1px solid var(--app-border);
  margin-top: auto; padding-top: 9px; flex: none; cursor: default; }
.foot-dim { font-size: 11px; color: var(--app-text3); }
.skill-card-foot > .ant-switch { flex: none; }
.btn-view { display: inline-flex; align-items: center; gap: 5px; height: 24px; padding: 0 10px; border-radius: 6px;
  border: 1px solid var(--app-border-strong); background: var(--app-panel); font-size: 12px; color: var(--app-text);
  cursor: pointer; font-family: inherit; margin-left: auto; }
.btn-view:hover { border-color: var(--app-accent-border); color: var(--app-accent); }
.btn-view :deep(svg) { font-size: 11px; color: var(--app-text2); }
.dots-btn { width: 24px; height: 24px; border-radius: 6px; border: 1px solid var(--app-border-strong); background: var(--app-panel);
  display: inline-flex; align-items: center; justify-content: center; color: var(--app-text2); cursor: pointer; font-size: 13px; flex: none; }
.dots-btn:hover { border-color: var(--app-accent-border); color: var(--app-accent); }
.skill-foot-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: 4px 0 0; }
.skill-form-hint { font-size: 12px; color: var(--app-text3); line-height: 1.7; margin: -6px 0 0; }

/* ---------------- 批量操作条 ---------------- */
.batch-bar { position: fixed; left: 50%; bottom: 22px; transform: translateX(-50%); z-index: 30;
  display: flex; align-items: center; gap: 10px; background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 10px; box-shadow: var(--app-shadow-lg); padding: 8px 16px; }
.bb-n { font-size: 12px; color: var(--app-text2); }
.bb-n b { color: var(--app-text); }

/* ---------------- 安装弹窗（沿用原样式） ---------------- */
.skill-zip-picked { font-size: 12px; color: var(--app-text2); margin-top: 10px; display: flex; align-items: center; gap: 8px; }
/* antd 给拖拽区写死 height 100%：在自动高度的 Tab 面板里，容器高先按「拖拽区=内容高」算出，
   再把 100% 解成这个值，于是拖拽区吃掉整块面板、下面的技能名与安装按钮被挤出弹窗。 */
.skill-drop :deep(.ant-upload-drag) { height: auto; }
.skill-upload-pct { margin-right: auto; font-size: 12px; color: var(--app-text3); }

/* ---------------- 新建弹窗（效果图 SCENE 3 三栏） ---------------- */
.mk { display: flex; height: min(560px, calc(100vh - 240px)); min-height: 420px; overflow: hidden; }
.mk-rail { width: 186px; flex: none; border-right: 1px solid var(--app-border); background: var(--app-panel-2); padding: 14px 10px; overflow-y: auto; }
.mk-rt { font-size: 11px; font-weight: 600; color: var(--app-text3); letter-spacing: .05em; padding: 0 8px 8px; }
.mk-rail-item { display: flex; align-items: center; gap: 9px; min-height: 38px; padding: 6px 10px; border-radius: 8px;
  font-size: 13px; color: var(--app-text2); margin-bottom: 2px; cursor: pointer; border: 1px solid transparent; }
.mk-rail-item :deep(svg) { color: var(--app-text3); flex: none; }
.mk-rail-item:hover { background: var(--app-panel); }
.mk-rail-item.on { background: var(--app-panel); color: var(--app-accent); font-weight: 600; border-color: var(--app-accent-border); }
.mk-rail-item.on :deep(svg) { color: var(--app-accent); }
.mk-rail-item small { display: block; font-size: 10px; color: var(--app-text3); font-weight: 400; }
.mk-badge { margin-left: auto; font-size: 10px; color: var(--app-accent); background: var(--app-accent-weak); border-radius: 4px; padding: 1px 6px; }
.mk-mid { flex: 0 1 430px; min-width: 300px; padding: 16px; overflow-y: auto; }
.mk-mt { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
.mk-md { font-size: 11px; color: var(--app-text3); margin-bottom: 12px; line-height: 1.7; }
.mk-tpls { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.mk-tpl { border: 1px solid var(--app-border); border-radius: 10px; padding: 12px; position: relative; background: var(--app-panel); cursor: pointer; }
.mk-tpl:hover { border-color: var(--app-accent-border); }
.mk-tpl.sel { border-color: var(--app-accent); box-shadow: 0 0 0 3px rgba(46, 107, 230, .1); }
.mk-tpl .cat-ic { width: 28px; height: 28px; border-radius: 7px; font-size: 14px; }
.mk-tpl .n { font-size: 12.5px; font-weight: 600; margin-top: 8px; }
.mk-tpl .d { font-size: 11px; color: var(--app-text3); margin-top: 3px; line-height: 1.55; }
.mk-tpl .m { font-size: 10.5px; color: var(--app-text3); margin-top: 6px; }
.mk-chk { position: absolute; top: 8px; right: 8px; width: 16px; height: 16px; border-radius: 50%; background: var(--app-accent);
  color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: 10px; }
.mk-prev { flex: 1 1 340px; min-width: 280px; border-left: 1px solid var(--app-border); background: var(--app-panel-2); padding: 16px;
  display: flex; flex-direction: column; overflow-y: auto; }
.mk-prev-t { font-size: 12px; font-weight: 600; color: var(--app-text2); margin-bottom: 8px; display: flex; align-items: center; gap: 6px; flex: none; }
.mk-prev-t :deep(svg) { color: var(--app-text3); font-weight: 400; }
.mk-edit-body { margin-left: auto; font-weight: 400; }
.mk-code { flex: 1; min-height: 120px; background: var(--app-code-bg); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 12px 14px; font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 11px; line-height: 1.75; color: var(--app-text); overflow: auto; white-space: pre-wrap; word-break: break-word; margin: 0; }
.mk-fields { flex: none; margin-top: 12px; display: flex; flex-direction: column; gap: 9px; }
.mk-field label { font-size: 11px; color: var(--app-text2); display: block; margin-bottom: 4px; }
.mk-hint { font-size: 10.5px; color: var(--app-text3); margin-top: 4px; line-height: 1.6; }
.mk-hint.ok { color: var(--app-ok); }
.mk-hint.warn { color: var(--app-warn-text); }
.mk-icon-row { display: flex; gap: 6px; align-items: center; }
.mk-icon-sel { width: 26px; height: 26px; border-radius: 7px; border: 1px solid var(--app-border); display: inline-flex;
  align-items: center; justify-content: center; background: var(--app-panel); cursor: pointer; font-size: 13px; color: var(--app-text3); }
.mk-icon-sel.on { border-color: var(--app-accent); box-shadow: 0 0 0 2px rgba(46, 107, 230, .12); }
.mk-cat-sel { margin-left: 6px; width: 150px; }
.mk-foot { padding: 0 20px 16px; margin-top: 0; }

@media (max-width: 1100px) {
  .mk { height: auto; flex-direction: column; }
  .mk-rail { width: auto; border-right: none; border-bottom: 1px solid var(--app-border); display: flex; gap: 6px; flex-wrap: wrap; }
  .mk-rt { display: none; }
  .mk-mid { width: auto; }
}
</style>
