<template>
  <div class="akp">
    <!-- 工具栏：搜索 + 概览统计 + 主操作 -->
    <div class="key-bar">
      <div class="key-bar-left">
        <a-input v-model:value="keyKeyword" placeholder="搜索名称 / Key 前缀" allow-clear size="small" class="res-search">
          <template #prefix><search-outlined class="res-search-ic" /></template>
        </a-input>
        <span class="key-stat">
          共 <b>{{ keys.length }}</b> 个 Key · 生效中 <b>{{ activeKeyCount }}</b>
          <span v-if="lastUsedKey" class="key-dim">· 最近使用 {{ fmtTs(lastUsedKey.lastUsedAt) }}</span>
        </span>
      </div>
      <div class="key-bar-actions">
        <a-tooltip title="刷新列表">
          <button class="app-icon-btn" aria-label="刷新 Key 列表" :disabled="keysLoading" @click="loadKeys"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn small" @click="openCreateKey">＋ 创建 API Key</button>
      </div>
    </div>

    <!-- 空态：没有 Key 时给引导；有 Key 但搜索无匹配时提示清除搜索 -->
    <div v-if="!keys.length" class="key-empty">
      <div class="key-empty-title">还没有 API Key</div>
      <div class="key-empty-desc">
        创建后，外部系统在请求头带 <code>X-Api-Key</code> 即可调用问答接口，无需平台 token。
      </div>
      <button class="app-btn small" @click="openCreateKey">创建第一个 API Key</button>
    </div>
    <div v-else-if="!filteredKeys.length" class="key-empty">
      <div class="key-empty-title">没有匹配的 Key</div>
      <div class="key-empty-desc">没有名称或前缀包含「{{ keyKeyword }}」的 Key。</div>
      <button class="app-btn ghost small" @click="keyKeyword = ''">清除搜索</button>
    </div>

    <a-table v-else :data-source="filteredKeys" :columns="keyCols" size="small" row-key="id"
             :pagination="false" table-layout="fixed" :scroll="{ x: 946 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <span class="key-name-wrap">
            <span class="key-name">{{ record.name || '未命名' }}</span>
            <span v-if="record.expired" class="app-pill err key-tag">已过期</span>
          </span>
        </template>
        <template v-else-if="column.key === 'prefix'">
          <span class="key-cell">
            <code class="key-prefix" :class="{ plain: !!revealed[record.id] }"
                  :title="revealed[record.id] || ''">{{ revealed[record.id] || (record.keyPrefix + '••••••••') }}</code>
            <button class="key-mini" :disabled="revealingId === record.id"
                    :title="revealed[record.id] ? '隐藏明文' : '显示明文'" @click="toggleReveal(record)">
              <eye-invisible-outlined v-if="revealed[record.id]" /><eye-outlined v-else />
            </button>
            <button class="key-mini" :disabled="revealingId === record.id" title="复制明文" @click="copyKey(record)">
              <check-outlined v-if="copiedKeyId === record.id" class="key-copy-ok" /><copy-outlined v-else />
            </button>
          </span>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tooltip :title="record.expired ? '已过期，不可启用' : (record.disabled ? '已停用，点击启用' : '生效中，点击停用')">
            <a-switch size="small" :checked="!record.disabled" :disabled="!!record.expired"
                      :loading="keyTogglingId === record.id" @change="toggleKey(record)" />
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'mcp'">
          <a-tooltip :title="record.mcpEnabled ? '可访问平台级 MCP 入口 /ai/mcp' : '未授权 MCP 入口（点击授权）'">
            <a-switch size="small" :checked="!!record.mcpEnabled"
                      :loading="keyMcpTogglingId === record.id" @change="toggleKeyMcp(record)" />
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'lastUsed'">
          <span :class="{ 'key-dim': !record.lastUsedAt }">{{ record.lastUsedAt ? fmtTs(record.lastUsedAt) : '从未使用' }}</span>
        </template>
        <template v-else-if="column.key === 'usage30d'">
          <span class="key-dim" :title="'近 30 天：' + (record.usage30dCalls || 0) + ' 次调用 / ' + (record.usage30dTokens || 0) + ' tokens（含 OpenAI 兼容端点）'">
            {{ (record.usage30dCalls || 0) }} 次 · {{ fmtTokens(record.usage30dTokens || 0) }}
          </span>
        </template>
        <template v-else-if="column.key === 'created'">
          <span class="key-dim">{{ fmtDate(record.createTime) }}</span>
        </template>
        <template v-else-if="column.key === 'expire'">
          <span class="key-dim">{{ record.expireAt ? fmtDate(record.expireAt) : '长期' }}</span>
        </template>
        <template v-else-if="column.key === 'act'">
          <button class="app-link-btn" @click="openEditKey(record)">编辑</button>
          <a-popconfirm title="删除该 Key？调用方将立即失效" ok-text="删除" cancel-text="取消" @confirm="delKey(record.id)">
            <button class="app-link-btn danger">删除</button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>
    <!-- 如何使用：拿到 Key 之后怎么调，比堆一段说明文字有用 -->
    <div class="key-usage">
      <div class="fold-head" @click="usageOpen = !usageOpen">
        <span class="fold-caret">{{ usageOpen ? '▾' : '▸' }}</span> 如何使用
        <span class="key-dim">（调用方式与权限边界）</span>
        <a class="fold-head-link" @click.stop="router.push('/docs')">完整对接文档 →</a>
      </div>
      <div v-if="usageOpen" class="key-usage-body">
        <div class="key-usage-label">调用问答接口（curl 示例）</div>
        <div class="key-code">
          <code>{{ curlSample }}</code>
          <button class="key-copy" :title="copiedSample === 'curl' ? '已复制' : '复制'"
                  @click="copySample('curl', curlSample)">
            <check-outlined v-if="copiedSample === 'curl'" class="key-copy-ok" /><copy-outlined v-else />
          </button>
        </div>
        <ul class="key-usage-list">
          <li><code>X-Api-Key: sk-…</code> 作为访问凭据，无需平台登录令牌</li>
          <li>Key 恒以<b>签发人本人</b>的身份发问：开放的是你自己的智能体、知识库与额度，不存在指定别人</li>
          <li>明文在服务端加密保管：列表点「眼睛」随时回显、点「复制」直接拷贝；怀疑泄露就停用重签</li>
          <li>权限仅限问答链路（问答 / 会话 / 反馈 / 引用溯源 / 附件上传等）：白名单之外对 Key 一律 403，
            改密、技能与 MCP 配置、共享管理、删会话这些端点都不从 Key 走</li>
          <li>问的是哪一个智能体，由请求体里的 <code>agentId</code> 决定（不传则用代表账号的默认助手）；
            可用清单用 <code>GET /api/ai/agent/available</code> 拉</li>
          <li>打开「MCP」开关后，该 Key 还可作为平台级 MCP 入口 <code>/ai/mcp</code> 的凭据
            （请求头带 <code>Authorization: Bearer sk-…</code>），供 Claude / Cursor 等客户端调用：检索知识库、提问、列出可见库与智能体</li>
          <li>不再使用建议「停用」而非删除：停用可保留审计线索，删除记录即消失</li>
        </ul>

        <!-- 平台级 MCP 接入点：开关只是授权，这里给的是"粘上就能用"的地址与配置 -->
        <div class="mcp-entry">
          <div class="key-usage-label">MCP 接入点（平台级 · 一个地址调用整套能力）</div>
          <div class="key-code">
            <code>{{ mcpPlatformUrl }}</code>
            <button class="key-copy" :title="copiedSample === 'mcpUrl' ? '已复制' : '复制'"
                    @click="copySample('mcpUrl', mcpPlatformUrl)">
              <check-outlined v-if="copiedSample === 'mcpUrl'" class="key-copy-ok" /><copy-outlined v-else />
            </button>
          </div>
          <div class="key-usage-label">mcp.json（粘贴到 Cursor 的 .cursor/mcp.json 或 Claude Desktop 配置）</div>
          <div class="key-code">
            <pre class="key-code-pre">{{ mcpPlatformJson }}</pre>
            <button class="key-copy" :title="copiedSample === 'mcpJson' ? '已复制' : '复制'"
                    @click="copySample('mcpJson', mcpPlatformJson)">
              <check-outlined v-if="copiedSample === 'mcpJson'" class="key-copy-ok" /><copy-outlined v-else />
            </button>
          </div>
          <ul class="key-usage-list">
            <li>需要<strong>该 Key 打开上方「MCP」开关</strong>，且管理员在「系统设置 → MCP 服务（双向）」开启「对外提供 MCP 端点」——总开关同时约束平台级入口与每个智能体自己的端点</li>
            <li>对外域名访问（不是 localhost）时，还须把前端域名填进「MCP 服务（双向）→ 允许的 Origin」，否则浏览器型客户端会被 403 拒绝（Claude / Cursor 这类本地进程客户端不带 Origin，不受影响）</li>
            <li>可见范围与 Key 创建者在网页上看到的完全一致</li>
            <li>想让某一个智能体单独对外，改用它的「公开发布 → MCP 端点」标签页，那是另一个地址（<code>/ai/mcp/{token}</code>），凭据是分享 token 而不是 Key</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- MCP 调用审计（仅管理员）：入站=外部客户端调平台端点，出站=平台调用户接入的外部工具 -->
    <div v-if="isAdmin" class="key-usage">
      <div class="fold-head" @click="toggleAudit">
        <span class="fold-caret">{{ auditOpen ? '▾' : '▸' }}</span> MCP 调用审计
        <span class="key-dim">（入站：外部客户端调用平台能力；出站：模型调用你接入的外部工具）</span>
      </div>
      <div v-if="auditOpen" class="key-usage-body">
        <div class="audit-summary">
          <template v-if="auditSummary">
            <span>累计调用 <b>{{ auditSummary.total }}</b></span>
            <span>成功 <b class="ok">{{ auditSummary.success }}</b></span>
            <span>失败 <b class="bad">{{ auditSummary.failed }}</b></span>
            <span v-if="auditTopTool">最常调用 <b>{{ auditTopTool.toolName }}</b>（{{ auditTopTool.cnt }} 次）</span>
          </template>
          <span v-else class="key-dim">暂无汇总</span>
        </div>
        <div class="audit-filter">
          <a-select v-model:value="auditFilter.channel" size="small" style="width: 130px"
                    allow-clear placeholder="全部渠道" @change="loadAudit(true)">
            <a-select-option value="agent">智能体端点</a-select-option>
            <a-select-option value="platform">平台级入口</a-select-option>
            <a-select-option value="outbound">出站（外部工具）</a-select-option>
          </a-select>
          <a-select v-model:value="auditFilter.success" size="small" style="width: 100px"
                    allow-clear placeholder="全部结果" @change="loadAudit(true)">
            <a-select-option :value="1">成功</a-select-option>
            <a-select-option :value="0">失败</a-select-option>
          </a-select>
          <a-input v-model:value="auditFilter.tool" size="small" style="width: 180px"
                   placeholder="工具名（模糊）" allow-clear @press-enter="loadAudit(true)" />
          <button class="app-btn small" :disabled="auditLoading" @click="loadAudit(true)">查询</button>
          <a-tooltip title="刷新">
            <button class="app-icon-btn" aria-label="刷新审计记录" :disabled="auditLoading" @click="loadAudit(true)">
              <reload-outlined />
            </button>
          </a-tooltip>
        </div>
        <a-table :data-source="auditRows" :columns="auditCols" size="small" row-key="id" :pagination="false"
                 :loading="auditLoading" table-layout="fixed" :scroll="{ x: 908 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'time'">
              <span class="key-dim">{{ fmtTs(record.createdAt) }}</span>
            </template>
            <template v-else-if="column.key === 'channel'">
              <span class="audit-chip" :class="record.channel">{{ auditChannelText(record.channel) }}</span>
            </template>
            <template v-else-if="column.key === 'tool'">
              <code class="audit-mono">{{ record.toolName }}</code>
            </template>
            <template v-else-if="column.key === 'cred'">
              <code class="audit-mono">{{ record.credentialRef || '—' }}</code>
            </template>
            <template v-else-if="column.key === 'owner'">{{ record.ownerUid || '—' }}</template>
            <template v-else-if="column.key === 'ip'">{{ record.callerIp || '—' }}</template>
            <template v-else-if="column.key === 'dur'">{{ record.durationMs }}ms</template>
            <template v-else-if="column.key === 'ok'">
              <a-tooltip v-if="!record.success && record.errorMsg" :title="record.errorMsg">
                <span class="audit-chip fail">失败</span>
              </a-tooltip>
              <span v-else class="audit-chip pass">成功</span>
            </template>
          </template>
        </a-table>
        <div class="audit-pager">
          <span class="key-dim">共 {{ auditTotal }} 条</span>
          <a-pagination size="small" :current="auditPage" :page-size="auditSize" :total="auditTotal"
                        :show-size-changer="false" @change="onAuditPage" />
        </div>
      </div>
    </div>

    <!-- 创建弹窗：两步式（表单 → 明文展示，列表眼睛可随时回显） -->
    <a-modal v-model:open="createOpen" :title="createStep === 'form' ? '创建 API Key' : 'Key 已创建'"
             :footer="null" :width="580" :mask-closable="false" @cancel="closeCreateKey">
      <template v-if="createStep === 'form'">
        <a-form layout="vertical">
          <a-form-item label="用途名称" required>
            <a-input v-model:value="createForm.name" placeholder="如：报表系统集成 / 运维脚本 / 定时巡检"
                     :maxlength="200" @press-enter="submitCreateKey" />
          </a-form-item>
          <a-form-item label="有效期">
            <a-radio-group v-model:value="createForm.expireMode" size="small" button-style="solid">
              <a-radio-button value="never">长期有效</a-radio-button>
              <a-radio-button value="30">30 天</a-radio-button>
              <a-radio-button value="90">90 天</a-radio-button>
              <a-radio-button value="custom">自定义</a-radio-button>
            </a-radio-group>
            <a-date-picker v-if="createForm.expireMode === 'custom'" v-model:value="createForm.expireDate"
                           style="margin-top:8px" placeholder="选择到期日期"
                           :disabled-date="d => d && d.valueOf() < Date.now() - 86400000" />
          </a-form-item>
          <a-form-item label="发问身份">
            <div class="key-self-bind"><code>{{ selfUid }}</code>（本人）</div>
            <div class="key-field-hint">
              Key 恒以签发人本人身份发问：开放的是你自己的智能体、知识库与额度，不存在指定别人。
              对接系统要用的 Key，就用对接时归属的那个账号签发。
            </div>
          </a-form-item>
        </a-form>
        <div class="key-modal-foot">
          <button class="app-btn ghost" @click="closeCreateKey">取消</button>
          <button class="app-btn" :disabled="keyCreating" @click="submitCreateKey">{{ keyCreating ? '创建中…' : '创建' }}</button>
        </div>
      </template>
      <template v-else>
        <div class="key-done-warn">
          可先复制保存；之后也能随时在列表点「眼睛」回显（明文由服务端加密保管）
        </div>
        <div class="key-done-meta">用途：<b>{{ createdKey.name }}</b></div>
        <div class="key-done-box">
          <code>{{ createdKey.apiKey }}</code>
          <button class="key-copy" :title="copiedKey ? '已复制' : '复制'" @click="copyCreatedKey">
            <check-outlined v-if="copiedKey" class="key-copy-ok" /><copy-outlined v-else />
          </button>
        </div>
        <div class="key-modal-foot">
          <button class="app-btn" @click="closeCreateKey">我已保存，关闭</button>
        </div>
      </template>
    </a-modal>

    <!-- 编辑弹窗：名称与有效期都能改（Key 本身与发问身份改不了——恒为签发人本人） -->
    <a-modal v-model:open="editOpen" title="编辑 API Key" :footer="null" :width="460">
      <a-form layout="vertical">
        <a-form-item label="用途名称" required>
          <a-input v-model:value="editForm.name" placeholder="如：报表系统集成"
                   :maxlength="200" @press-enter="submitEditKey" />
        </a-form-item>
        <a-form-item label="有效期">
          <a-radio-group v-model:value="editForm.expireMode" size="small" button-style="solid">
            <a-radio-button value="never">长期有效</a-radio-button>
            <a-radio-button value="30">30 天</a-radio-button>
            <a-radio-button value="90">90 天</a-radio-button>
            <a-radio-button value="custom">选日期</a-radio-button>
          </a-radio-group>
          <a-date-picker v-if="editForm.expireMode === 'custom'" v-model:value="editForm.expireDate"
                         style="margin-top:8px" placeholder="选择到期日期"
                         :disabled-date="d => d && d.valueOf() < Date.now() - 86400000" />
          <div class="key-field-hint">「30 天 / 90 天」从今天起算；到期后该 Key 立即不可调用，改回长期即恢复</div>
        </a-form-item>
      </a-form>
      <div class="key-modal-foot">
        <button class="app-btn ghost" @click="editOpen = false">取消</button>
        <button class="app-btn" @click="submitEditKey">保存</button>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
/*
 * API 密钥面板（个人资产）：原在系统设置（SettingsPage）里、仅管理员可达；2026-11-02 下放自服务
 * 后整块搬进个人设置，普通用户与管理员共用同一套规则：
 *   ① 发问身份恒为签发人本人（服务端强制，Key 不存在指定/改绑别人）；
 *   ② 明文由服务端加密保管（JWE），列表「眼睛」随时回显、「复制」直接拷贝；
 *      存量 Key（回显上线前签发）无密文，眼睛会明确提示重签。
 *   ③ MCP 调用审计：管理视角的折叠块，仅管理员渲染。
 * 列表按归属过滤（谁建归谁）：只返回本人签发的 Key，管理员也没有全量短路，
 * 所以同一份组件不需要分叉，也不需要一列「代表账号」（恒等于本人）。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { CheckOutlined, CopyOutlined, EyeInvisibleOutlined, EyeOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { listApiKeys, createApiKey, setApiKeyDisabled, setApiKeyMcp, deleteApiKey,
         updateApiKey, revealApiKey, getMcpAuditLogs, getMcpAuditSummary } from '../api'
import { authUser } from '../utils/auth'
import { writeClipboard } from '../utils/clipboard'
import { publicUrl } from '../utils/publicUrl'

const keys = ref([])
const keysLoading = ref(false)
// 列宽只能走 :columns + table-layout="fixed"：写成 <a-table-column width="..."> 不进 colgroup，
// 浏览器按内容排宽——点「眼睛」回显 48 字明文时 Key 列被撑到 500px，其余列挤成竖排两行字，行高翻三倍。
// scroll.x 必须 ≥ 固定列宽总和（850）+ 名称列保底（96）：定宽列之和超过 scroll.x 时，
// fixed 布局会把没写宽度的名称列压成 0px（列整个消失），滚动条行程也对不上实际内容宽。
// 列宽按内容实测收紧（Key 密文态约 200px）：留太宽会在 Key 列里空出一大片，
// 总宽超过卡片宽度还会平白多出横向滚动条。
// 「代表账号」列不展示：服务端把 actAsUid 恒写成签发人，列表又只返回本人建的 Key，每行都是同一个 uid。
const keyCols = [
  { title: '名称', key: 'name', ellipsis: true },
  { title: 'Key', key: 'prefix', width: 230 },
  { title: '启用', key: 'status', width: 50 },
  { title: 'MCP 入口', key: 'mcp', width: 76 },
  { title: '最近使用', key: 'lastUsed', width: 128 },
  { title: '近30天用量', key: 'usage30d', width: 110 },
  { title: '创建时间', key: 'created', width: 86 },
  { title: '有效期', key: 'expire', width: 86 },
  { title: '操作', key: 'act', width: 84 }
]
const auditCols = [
  { title: '时间', key: 'time', width: 118 },
  { title: '渠道', key: 'channel', width: 100 },
  { title: '工具', key: 'tool', width: 170, ellipsis: true },
  { title: '凭据', key: 'cred', width: 150, ellipsis: true },
  { title: '归属', key: 'owner', width: 110, ellipsis: true },
  { title: '调用者 IP', key: 'ip', width: 110, ellipsis: true },
  { title: '耗时', key: 'dur', width: 70 },
  { title: '结果', key: 'ok', width: 80 }
]
const keyKeyword = ref('')
const keyTogglingId = ref('')
const keyMcpTogglingId = ref('')
const keyCreating = ref(false)
const usageOpen = ref(false)
const copiedSample = ref('')
const copiedKey = ref(false)

/** 管理员判据与 /auth/me 缓存同源（ensureAuth 在路由进入前已拉好）——只管审计折叠块的显隐 */
const isAdmin = computed(() => Boolean(authUser.value && authUser.value.admin))
const selfUid = computed(() => (authUser.value && authUser.value.user) || '')
const router = useRouter()

/** 明文回显：id → 明文（点眼睛拉一次后记忆，再点隐藏只藏显示不动请求）；revealingId 是请求中的行 */
const revealed = ref({})
/** 点复制取到的明文：只缓存不显示——行不会被 48 字明文撑开换行，再点一次还能同步写剪贴板（保住用户手势） */
const plainCache = ref({})
const revealingId = ref('')
const copiedKeyId = ref('')
const fetchPlain = async rec => {
  revealingId.value = rec.id
  try {
    const r = await revealApiKey(rec.id)
    if (r.success && r.data?.apiKey) return r.data.apiKey
    message.error(r.msg || '明文回显失败')
    return ''
  } catch (e) {
    message.error(e.message || '明文回显失败')
    return ''
  } finally { revealingId.value = '' }
}
const ensureRevealed = async rec => {
  if (revealed.value[rec.id]) return revealed.value[rec.id]
  const plain = await fetchPlain(rec)
  if (plain) revealed.value = { ...revealed.value, [rec.id]: plain }
  return plain
}
const toggleReveal = async rec => {
  if (revealed.value[rec.id]) {
    const next = { ...revealed.value }
    delete next[rec.id]
    revealed.value = next
    return
  }
  await ensureRevealed(rec)
}
const copyKey = rec => {
  // 明文已在手时同步发起写入：await 过一轮网络就把用户手势丢了，Safari 那类浏览器会直接拒绝剪贴板写入
  const cached = revealed.value[rec.id] || plainCache.value[rec.id]
  if (cached) return writePlain(rec, cached, true)
  fetchPlain(rec).then(plain => {
    if (!plain) return
    plainCache.value = { ...plainCache.value, [rec.id]: plain }
    writePlain(rec, plain, false)
  })
}
const writePlain = async (rec, plain, hadPlain) => {
  const ok = await writeClipboard(plain)
  copiedKeyId.value = ok ? rec.id : ''
  if (!ok) message.warning(hadPlain ? '复制失败：请检查浏览器的剪贴板权限' : '明文已取出，再点一次复制图标')
}

const activeKeyCount = computed(() => keys.value.filter(k => !k.disabled && !k.expired).length)
const filteredKeys = computed(() => {
  const kw = keyKeyword.value.trim().toLowerCase()
  if (!kw) return keys.value
  return keys.value.filter(k =>
    String(k.name || '').toLowerCase().includes(kw) || String(k.keyPrefix || '').toLowerCase().includes(kw))
})
const lastUsedKey = computed(() => keys.value
    .filter(k => k.lastUsedAt)
    .sort((a, b) => String(b.lastUsedAt).localeCompare(String(a.lastUsedAt)))[0] || null)
const fmtTs = s => (s ? String(s).replace('T', ' ').slice(0, 16) : '—')
const fmtTokens = n => (n >= 1000000 ? (n / 1000000).toFixed(1) + 'M'
  : n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n))

const fmtDate = s => (s ? String(s).slice(0, 10) : '长期')

const loadKeys = async () => {
  keysLoading.value = true
  try {
    const r = await listApiKeys()
    if (r.success && Array.isArray(r.data)) keys.value = r.data
  } catch (e) { /* 拉取失败不打扰，保留上次列表 */ }
  finally { keysLoading.value = false }
}
/** 有效期选择 → 提交给后端的 expireAt（yyyy-MM-dd；空串=长期）。创建与编辑两个弹窗共用 */
const expirePayload = form => {
  const m = form.expireMode
  if (m === 'never') return ''
  const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  if (m === 'custom') {
    const d = form.expireDate
    if (!d) return ''
    return d.format ? d.format('YYYY-MM-DD') : ymd(new Date(d))
  }
  const d = new Date()
  d.setDate(d.getDate() + Number(m))
  return ymd(d)
}
/** 示例随第一个 Key 的前缀变化，让用户一眼看到"在哪儿带" */
const curlSample = computed(() => {
  const k = keys.value[0]?.keyPrefix || 'sk-你的Key'
  return `curl -X POST http://<你的服务地址>/ai/api/ai/chat \\
  -H "Content-Type: application/json" \\
  -H "X-Api-Key: ${k}..." \\
  -d '{"question":"如何创建评分组件？","agentId":"<智能体id>"}'`
})

/**
 * 平台级 MCP 接入点。基址走 publicUrl（本地连线上后端时按 VITE_PROXY_TARGET 推导服务器地址）：
 * 本地起 web 调试时地址也要能被外部客户端真实访问，不能固化成 vite 的 5800。
 *
 * 凭据头用真 Key 前缀 + "..."：完整明文在列表「眼睛」里随时可回显，这里不可能拿到，
 * 用前缀是为了让粘贴的人一眼知道该换成什么，而不是留一句"你的 Key"让人猜位置。
 */
const mcpPlatformUrl = computed(() => publicUrl('/ai/mcp'))
const mcpPlatformJson = computed(() => {
  const k = keys.value.find(x => !x.disabled && !x.expired)?.keyPrefix || 'sk-你的Key'
  return JSON.stringify({
    mcpServers: {
      wenqu: { url: mcpPlatformUrl.value, headers: { Authorization: `Bearer ${k}...` } }
    }
  }, null, 2)
})
// 创建走两步式弹窗：form（填名称/有效期）→ done（明文展示，之后列表眼睛随时可回显）
const createOpen = ref(false)
const createStep = ref('form')
const createForm = ref({ name: '', expireMode: 'never', expireDate: null })
const createdKey = ref({ name: '', apiKey: '' })
const editOpen = ref(false)
const editForm = ref({ id: '', name: '', expireMode: 'never', expireDate: null })

const openCreateKey = () => {
  createForm.value = { name: '', expireMode: 'never', expireDate: null }
  createdKey.value = { name: '', apiKey: '' }
  copiedKey.value = false
  createStep.value = 'form'
  createOpen.value = true
}
const closeCreateKey = () => {
  createOpen.value = false
  if (createStep.value === 'done') loadKeys()
}
const submitCreateKey = async () => {
  // 空名不静默兜底成「未命名 Key」——直接提示，避免签出一堆分不清用途的 Key
  if (!createForm.value.name.trim()) { message.warning('请先填写用途名称'); return }
  if (createForm.value.expireMode === 'custom' && !createForm.value.expireDate) { message.warning('请选择到期日期'); return }
  keyCreating.value = true
  try {
    // 恒绑签发人：不传 actAsUid（传了别人的 uid 后端也会拒）
    const body = { name: createForm.value.name.trim(), expireAt: expirePayload(createForm.value) }
    const r = await createApiKey(body)
    if (r.success) {
      createdKey.value = { name: r.data?.name || createForm.value.name.trim(), apiKey: r.data?.apiKey || '' }
      createStep.value = 'done'
      copiedKey.value = await writeClipboard(createdKey.value.apiKey)
    } else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { keyCreating.value = false }
}
const copyCreatedKey = async () => {
  const ok = await writeClipboard(createdKey.value.apiKey)
  copiedKey.value = ok
  if (!ok) message.warning('浏览器未授权剪贴板，请手动选中复制')
}
const copySample = async (k, text) => {
  const ok = await writeClipboard(text)
  copiedSample.value = ok ? k : ''
  if (!ok) message.warning('浏览器未授权剪贴板，请手动选中复制')
}
const openEditKey = rec => {
  // 有效期回填：有到期日 → 「选日期」并显示那一天（已过期的也显示，改到未来或切长期才恢复可用）；无 → 长期有效
  const exp = rec.expireAt ? dayjs(String(rec.expireAt).slice(0, 10)) : null
  editForm.value = { id: rec.id, name: rec.name || '', expireMode: exp ? 'custom' : 'never', expireDate: exp }
  editOpen.value = true
}
const submitEditKey = async () => {
  const name = editForm.value.name.trim()
  if (!name) { message.warning('名称不能为空'); return }
  if (editForm.value.expireMode === 'custom' && !editForm.value.expireDate) { message.warning('请选择到期日期'); return }
  try {
    // 发问身份恒为签发人，编辑不涉及（服务端不收也不认）
    const body = { name, expireAt: expirePayload(editForm.value) }
    const r = await updateApiKey(editForm.value.id, body)
    if (r.success) { message.success('已保存'); editOpen.value = false; loadKeys() }
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
}

// ==================== 启停 / MCP 授权 / 删除 ====================
const toggleKey = async rec => {
  if (keyTogglingId.value) return
  keyTogglingId.value = rec.id
  try {
    const r = await setApiKeyDisabled(rec.id, !rec.disabled)
    if (r.success) loadKeys()
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { keyTogglingId.value = '' }
}
/** MCP 列：授权 / 收回该 Key 访问平台级 MCP 入口（/ai/mcp）的资格（与「停用」是两件事） */
const toggleKeyMcp = async rec => {
  if (keyMcpTogglingId.value) return
  keyMcpTogglingId.value = rec.id
  try {
    const r = await setApiKeyMcp(rec.id, !rec.mcpEnabled)
    if (r.success) loadKeys()
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { keyMcpTogglingId.value = '' }
}
const delKey = async id => {
  try {
    const r = await deleteApiKey(id)
    if (r.success) { message.success('已删除'); loadKeys() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== MCP 调用审计（仅管理员，后端 requireAdmin 兜底） ====================
const auditOpen = ref(false)
const auditLoading = ref(false)
const auditRows = ref([])
const auditTotal = ref(0)
const auditPage = ref(1)
const auditSize = 20
const auditSummary = ref(null)
const auditFilter = ref({ channel: null, success: null, tool: '' })
/** 渠道中文名：入站两类 + 出站（平台调用户接入的外部 MCP 工具） */
const auditChannelText = c =>
  c === 'agent' ? '智能体端点' : (c === 'outbound' ? '出站工具' : (c === 'platform' ? '平台级' : (c || '—')))
const auditTopTool = computed(() => {
  const list = auditSummary.value?.byTool || []
  return list.length ? list[0] : null
})

/** 首次展开时懒加载（带汇总）；resetPage=true 表示筛选变化回第一页 */
const loadAudit = async (resetPage = false) => {
  if (resetPage) auditPage.value = 1
  auditLoading.value = true
  try {
    // 明细与汇总分开请求：任何一个 403/失败都不能连累另一个（Promise.all 会整体 throw）
    const r = await getMcpAuditLogs({ ...auditFilter.value, page: auditPage.value, size: auditSize })
    if (r.success && r.data) {
      auditRows.value = r.data.rows || []
      auditTotal.value = r.data.total || 0
    } else message.error(r.msg || '审计明细加载失败')
    if (!auditSummary.value) {
      const s = await getMcpAuditSummary()
      if (s.success && s.data) auditSummary.value = s.data
    }
  } catch (e) { message.error(e.message || '审计数据加载失败') }
  finally { auditLoading.value = false }
}
const toggleAudit = () => {
  auditOpen.value = !auditOpen.value
  if (auditOpen.value && !auditSummary.value && !auditLoading.value) loadAudit(true)
}
const onAuditPage = p => { auditPage.value = p; loadAudit(false) }

// 面板随页签挂载（v-if 控制），挂载即拉一次最新列表（数据可能在别处改过）
onMounted(loadKeys)
</script>

<style scoped>
/* 自 SettingsPage 迁出（2026-11-02）：键值面板专属样式随组件走；app-* 全局类来自 app.css */
/* 窄窗口下 Key 表要横向滚动：macOS 悬浮式滚动条会直接画在最后一行上，
   给滚动容器留底部空间，滚动条落在留白里，行内容不再被拦腰盖住 */
.akp :deep(.ant-table-content),
.akp :deep(.ant-table-body) { padding-bottom: 10px; }
.key-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.key-bar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.key-bar-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; flex: none; }
.key-stat { font-size: 12px; color: var(--app-text2); }
.key-stat b { color: var(--app-text); font-weight: 600; }
.key-dim { color: var(--app-text3); font-size: 12px; }
/* 搜索框（与设置页其它面板同一形态） */
.res-search { width: 220px; }
.res-search :deep(.ant-input-affix-wrapper) { border-radius: 8px; }
.res-search :deep(.ant-input-prefix) { margin-right: 6px; }
.res-search-ic { color: var(--app-text3); font-size: 12px; }
.app-btn.small { padding: 3px 10px; font-size: 11px; border-radius: 6px; margin-left: 10px; }
.app-btn.small + .app-btn.small { margin-left: 8px; }
/* 名称与状态标签同一行：inline-flex 垂直居中（inline-block 的基线对齐会让标签高低不齐） */
.key-name-wrap { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; }
.key-name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-prefix { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 22px; color: var(--app-text2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 明文整串都要看得见：不截断，在格内换行（48 字约 345px，列宽 230 装不下一行） */
.key-prefix.plain { color: var(--app-text); white-space: normal; word-break: break-all; overflow: visible; text-overflow: clip; }
/* Key 单元格：密文态 + 眼睛/复制（回显是敏感动作，图标做小、悬停才有着色） */
/* flex 0 1 auto + min-width:0：code 不撑满整列（否则图标被推到列尾、中间空一大片），
   明文超长时又能缩到内容以下换行，而不是把整列顶宽 */
.key-cell { display: flex; align-items: flex-start; gap: 2px; min-width: 0; }
.key-cell .key-prefix { flex: 0 1 auto; min-width: 0; }
.key-mini {
  border: none; background: transparent; color: var(--app-text3); cursor: pointer;
  width: 22px; height: 22px; border-radius: 5px; flex: none; padding: 0;
  display: inline-flex; align-items: center; justify-content: center; font-size: 12px;
}
.key-mini:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.key-mini:disabled { opacity: .5; cursor: wait; }
/* 空态 */
.key-empty { text-align: center; padding: 36px 20px; border: 1px dashed var(--app-border); border-radius: 8px; }
.key-empty-title { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.key-empty-desc { font-size: 12px; color: var(--app-text3); margin-bottom: 14px; line-height: 1.7; }
/* 如何使用 */
.key-usage { margin-top: 16px; border-top: 1px solid var(--app-border); padding-top: 10px; }
.fold-head { font-size: 12px; font-weight: 500; cursor: pointer; user-select: none; }
.fold-head:hover { color: var(--app-accent); }
.fold-caret { display: inline-block; width: 12px; color: var(--app-text3); }
/* 完整对接文档入口：折叠头右侧的次级链接，点它不翻折叠块 */
.fold-head-link { float: right; font-weight: 400; font-size: 12px; color: var(--app-accent); cursor: pointer; }
.key-usage-body { padding: 10px 0 0 12px; }
.key-usage-label { font-size: 12px; color: var(--app-text2); margin-bottom: 6px; }
/* 平台级 MCP 接入点：同一张卡里第二、三个 label 之间要留出间距，否则两块代码框贴成一片 */
.mcp-entry { margin-top: 14px; }
.mcp-entry .key-usage-label + .key-code + .key-usage-label { margin-top: 12px; }
/* mcp.json 是多行 pre：.key-code 那套 code 选择器不管 pre，这里补上，并给右上角复制按钮让位 */
.key-code-pre {
  margin: 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
  line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text);
}
.key-code { position: relative; background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 6px; padding: 10px 34px 10px 12px; }
.key-code code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text); }
.key-copy {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border: none; border-radius: 5px;
  background: transparent; color: var(--app-text3); cursor: pointer; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
}
.key-copy:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.key-copy-ok { color: var(--app-ok); }
.key-usage-list { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: var(--app-text2); line-height: 1.9; }
.key-usage-list code { background: var(--app-panel-2); padding: 1px 5px; border-radius: 4px; font-size: 11px; }
/* 弹窗内 */
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.key-field-hint { margin-top: 6px; font-size: 12px; color: var(--app-text3); line-height: 1.6; }
.key-self-bind { padding: 5px 10px; border: 1px solid var(--app-border); border-radius: 6px; background: var(--app-panel-2); font-size: 12px; color: var(--app-text2); }
.key-self-bind code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--app-text); }
.key-done-warn {
  background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); color: var(--app-warn-text);
  border-radius: 6px; padding: 8px 10px; font-size: 12px; margin-bottom: 12px; line-height: 1.6;
}
.key-done-meta { font-size: 12px; color: var(--app-text2); margin-bottom: 8px; }
.key-done-box {
  position: relative; background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 6px;
  padding: 12px 36px 12px 12px;
}
.key-done-box code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; word-break: break-all; color: var(--app-text); }
/* MCP 调用审计 */
.audit-summary { display: flex; gap: 16px; font-size: 12px; color: var(--app-text2); margin-bottom: 10px; flex-wrap: wrap; }
.audit-summary b { font-weight: 600; color: var(--app-text); }
.audit-summary b.ok { color: var(--app-ok); }
.audit-summary b.bad { color: var(--app-danger); }
.audit-filter { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.audit-chip { font-size: 11px; border-radius: 999px; padding: 2px 8px; white-space: nowrap; }
.audit-chip.agent { color: var(--app-accent); background: var(--app-accent-weak); }
.audit-chip.platform { color: var(--app-warn-text); background: var(--app-warn-weak); }
.audit-chip.outbound { color: var(--app-text2); background: var(--app-panel-2); }
.audit-chip.pass { color: var(--app-ok); background: var(--app-ok-weak); }
.audit-chip.fail { color: var(--app-danger); background: var(--app-danger-weak); }
.audit-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
.audit-pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; justify-content: flex-end; }
</style>
