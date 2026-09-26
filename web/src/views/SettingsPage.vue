<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">系统设置</h3>
      <span v-if="dirtyCount" class="dirty-hint">有 {{ dirtyCount }} 项已修改未保存</span>
      <button v-else class="head-hint-plain">修改后点右侧保存生效，悬停参数旁 ? 查看说明</button>
      <!-- 基础模式只显示常用项（模型服务 + 回答行为）；细粒度调参收进高级模式，避免设置页膨胀 -->
      <label class="adv-toggle" title="默认只显示常用配置；开启后显示全部调优参数">
        <a-switch v-model:checked="advMode" size="small" />
        <span>高级设置</span>
      </label>
      <button class="app-btn" style="margin-left:auto" :disabled="!dirtyCount" :class="{ dis: !dirtyCount }" @click="save">
        <save-outlined /> 保存配置{{ dirtyCount ? `（${dirtyCount} 项改动）` : '' }}
      </button>
    </div>

    <div v-if="!schemaLoaded" class="app-card" style="margin:16px 20px">
      <span class="head-hint-plain">正在加载配置字段定义…</span>
    </div>
    <div v-else class="set-body">
      <!-- 左侧分组导航（基础模式只列含常用项的分组） -->
      <nav class="set-nav">
        <span v-for="p in navPanels" :key="p.key" class="set-nav-item" :class="{ active: current === p.key }" @click="current = p.key">
          {{ groupLabel(p.key) }}
        </span>
      </nav>

      <!-- 右侧：当前分组表单 -->
      <section class="set-content">
        <a-spin :spinning="loading">
          <div class="set-panel-head">
            <h3 class="app-page-title">{{ currentPanel?.title }}</h3>
            <span v-if="!advMode && hiddenHere > 0" class="adv-hidden-hint">
              已隐藏 {{ hiddenHere }} 项高级配置（右上角「高级设置」可查看）
            </span>
            <button v-if="!NO_RESET.includes(current)" class="app-btn ghost" :disabled="resettingKey === current" @click="onResetGroup(current)">
              恢复本组默认
            </button>
          </div>

          <a-alert v-for="(al, ai) in (PANEL_ALERTS[current] || [])" :key="ai" :type="al.type" show-icon
                   style="margin-bottom:12px" :message="al.msg" />

          <div class="app-card set-card">
            <a-form :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" @submit.prevent>
              <template v-for="(blk, i) in blocksOf(current, !advMode)" :key="i">
                <div v-if="blk.type === 'sub'" class="cfg-sub">{{ blk.title }}</div>

                <!-- 常规字段（SchemaField 全量复用：类型控件/条件显隐/参数说明）。
                     技能与 MCP 的内容已迁到「智能体」页的个人 Tab，这里只剩技能的两个上下文预算参数 -->
                <template v-else-if="blk.type === 'field'">
                  <SchemaField :field="blk.field" :form="form" :tips="TIPS" @change="onFieldChange">
                    <template v-if="probeKey(blk.field)" #extra>
                      <button class="app-btn ghost small probe-btn" :disabled="probeStates[probeKey(blk.field)].loading" @click="doProbe(probeKey(blk.field))">
                        {{ probeStates[probeKey(blk.field)].loading ? '测试中…' : '测试连接' }}
                      </button>
                      <a-tooltip v-if="probeStates[probeKey(blk.field)].result" :title="probeStates[probeKey(blk.field)].result.detail">
                        <span class="probe-chip" :class="probeStates[probeKey(blk.field)].result.available ? 'ok' : 'bad'">
                          {{ probeStates[probeKey(blk.field)].result.available ? '可达' : '不可达' }} {{ probeStates[probeKey(blk.field)].result.latencyMs }}ms
                        </span>
                      </a-tooltip>
                    </template>
                  </SchemaField>
                </template>
              </template>

              <!-- API Key 管理（6.5）：签发 / 列表 / 停用 / 删除 -->
              <template v-if="current === 'apiKey'">
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

                <a-table v-else :data-source="filteredKeys" size="small" row-key="id" :pagination="false">
                  <a-table-column title="名称" key="name" ellipsis>
                    <template #default="{ record }">
                      <span class="key-name-wrap">
                        <span class="key-name">{{ record.name || '未命名' }}</span>
                        <span v-if="record.expired" class="app-pill err key-tag">已过期</span>
                      </span>
                    </template>
                  </a-table-column>
                  <a-table-column title="Key" key="prefix" width="170">
                    <template #default="{ record }"><span class="key-prefix">{{ record.keyPrefix }}…</span></template>
                  </a-table-column>
                  <a-table-column title="状态" key="status" width="80">
                    <template #default="{ record }">
                      <a-tooltip :title="record.expired ? '已过期，不可启用' : (record.disabled ? '已停用，点击启用' : '生效中，点击停用')">
                        <a-switch size="small" :checked="!record.disabled" :disabled="!!record.expired"
                                  :loading="keyTogglingId === record.id" @change="toggleKey(record)" />
                      </a-tooltip>
                    </template>
                  </a-table-column>
                  <a-table-column title="最近使用" key="lastUsed" width="150">
                    <template #default="{ record }">
                      <span :class="{ 'key-dim': !record.lastUsedAt }">{{ record.lastUsedAt ? fmtTs(record.lastUsedAt) : '从未使用' }}</span>
                    </template>
                  </a-table-column>
                  <a-table-column title="创建时间" key="created" width="150">
                    <template #default="{ record }"><span class="key-dim">{{ fmtTs(record.createTime) }}</span></template>
                  </a-table-column>
                  <a-table-column title="有效期" key="expire" width="110">
                    <template #default="{ record }"><span class="key-dim">{{ record.expireAt ? fmtDate(record.expireAt) : '长期' }}</span></template>
                  </a-table-column>
                  <a-table-column title="操作" key="act" width="150">
                    <template #default="{ record }">
                      <button class="app-link-btn" @click="openShareKey(record)">共享</button>
                      <button class="app-link-btn" @click="openRenameKey(record)">改名</button>
                      <a-popconfirm title="删除该 Key？调用方将立即失效" ok-text="删除" cancel-text="取消" @confirm="delKey(record.id)">
                        <button class="app-link-btn danger">删除</button>
                      </a-popconfirm>
                    </template>
                  </a-table-column>
                </a-table>
                <!-- 如何使用：拿到 Key 之后怎么调，比堆一段说明文字有用 -->
                <div class="key-usage">
                  <div class="fold-head" @click="usageOpen = !usageOpen">
                    <span class="fold-caret">{{ usageOpen ? '▾' : '▸' }}</span> 如何使用
                    <span class="key-dim">（调用方式与权限边界）</span>
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
                      <li>会话归属由服务端判定：未带登录令牌的调用共享 anonymous 兼容池，建议调用方各自登录或按 Key 隔离使用</li>
                      <li>权限仅限问答链路（问答 / 会话 / 反馈 / 引用溯源），管理端点一律拒绝</li>
                      <li>不再使用建议「停用」而非删除：停用可保留审计线索，删除记录即消失</li>
                    </ul>
                  </div>
                </div>

                <!-- 创建弹窗：两步式（表单 → 明文仅此一次展示） -->
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
                    </a-form>
                    <div class="key-modal-foot">
                      <button class="app-btn ghost" @click="closeCreateKey">取消</button>
                      <button class="app-btn" :disabled="keyCreating" @click="submitCreateKey">{{ keyCreating ? '创建中…' : '创建' }}</button>
                    </div>
                  </template>
                  <template v-else>
                    <div class="key-done-warn">
                      请立即复制保存——明文只显示这一次（服务端只存哈希，关闭后无法再查看）
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

                <!-- 改名弹窗 -->
                <a-modal v-model:open="renameOpen" title="重命名 API Key" :footer="null" :width="460">
                  <a-form layout="vertical">
                    <a-form-item label="用途名称" required>
                      <a-input v-model:value="renameForm.name" placeholder="如：报表系统集成"
                               :maxlength="200" @press-enter="submitRenameKey" />
                    </a-form-item>
                  </a-form>
                  <div class="key-modal-foot">
                    <button class="app-btn ghost" @click="renameOpen = false">取消</button>
                    <button class="app-btn" @click="submitRenameKey">保存</button>
                  </div>
                </a-modal>

                <!-- 共享范围（公共组件：与文档 / 智能体同一套两区表单） -->
                <ShareScopeModal v-model:open="keyShareVisible" resource-label="API Key" read-verb="查看"
                                 :share-config="keyShareTarget.shareConfig" :save-fn="saveKeyShareFn" @saved="loadKeys" />
              </template>

              <!-- 技能（Skills）与 MCP 的管理界面已迁到「智能体」页的个人 Tab（每人管自己的），
                   此处不再有自定义面板 -->

            </a-form>
          </div>
        </a-spin>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SaveOutlined, QuestionCircleOutlined, CopyOutlined, CheckOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getConfig, getConfigSchema, saveConfig, resetConfig, checkKeywordEngine,
         probeConnectivity,
         listApiKeys, createApiKey, setApiKeyDisabled, deleteApiKey, renameApiKey, updateApiKeyShare } from '../api'
import ShareScopeModal from './ShareScopeModal.vue'
import SchemaField from '../components/SchemaField.vue'
import { FIELDS, PANELS, TIPS, blocksOf, buildDefaultForm, readForm, writeForm, corePanels, hiddenFieldCount, applyServerSchema } from '../configSchema'

// 分组导航（沿用旧版锚点短名）
const NAV_LABELS = {
  chat: '智能问答模型', vision: '视觉模型', chunk: '文档解析', embedding: '向量模型', retrieval: '检索设置',
  context: '上下文控制', deepReasoning: '深度思考', tool: '工具调用',
  ratelimit: '接口限流', maintenance: '定时维护', apiKey: 'API Key 管理', skills: '技能（预算）',
  agent: '并行检索'
}
const groupLabel = key => NAV_LABELS[key] || key
const current = ref('chat')
const currentPanel = computed(() => PANELS.find(p => p.key === current.value))

// 高级设置模式（默认关，持久化）：关=只显示核心项（模型服务 + 回答行为），开=显示全部调优参数。
// 对标成熟产品的设置体系——全局设置保持精简，细粒度参数按需展开，避免设置页被 180+ 项淹没。
const advMode = ref(localStorage.getItem('app_adv_settings') === '1')
const navPanels = computed(() => advMode.value ? PANELS : corePanels())
const hiddenHere = computed(() => advMode.value ? 0 : hiddenFieldCount(current.value))
watch(advMode, v => {
  localStorage.setItem('app_adv_settings', v ? '1' : '0')
  // 关掉高级模式时，若当前分组已不在导航中（纯高级分组），切到第一个核心分组，避免停在空白页
  if (!v && !navPanels.value.some(p => p.key === current.value)) {
    current.value = navPanels.value[0]?.key || current.value
  }
})

// 无「恢复本组默认」的分组（API Key 由数据库管理；技能的预算项恢复默认意义不大且与个人技能无关）
const NO_RESET = ['embedding', 'maintenance', 'apiKey']

// 分组顶部说明（与旧版文案一致）
const PANEL_ALERTS = {
  chat: [{ type: 'info', msg: '问答模型支持跨厂商热切换：修改网关地址/API Key/模型名保存即生效免重启，API Key 以 RSA 加密入库。' }],
  vision: [{ type: 'info', msg: '视觉模型用于文档图片与用户图片的描述识别；关闭后图片仅展示、内容不进检索与引用。' }],
  chunk: [{ type: 'info', msg: '上传大小上限保存即生效；分块/图片上限只对重新解析/新上传文档生效，超限按保护策略截断入库。' }],
  embedding: [{ type: 'warning', msg: '向量模型热切换说明：不同模型的向量数学上不可迁移。保存时会先探测新配置并校验维度，通过后自动重建索引并后台全量重嵌入。重嵌入期间向量检索自动降级关键词路，服务不中断。' }],
  retrieval: [
    { type: 'info', msg: '融合分 = 向量权重×向量相似度 + 关键词权重×命中率。保存后立即生效。' },
    { type: 'info', msg: '重排：OpenAI 兼容 /v1/rerank 服务。未启动或不可用时自动回退融合分排序，不影响正常问答。' }
  ],
  context: [{ type: 'info', msg: '预算 = min(模型窗口×安全系数−输出限制, 成本上限)，知识块按相关度降序累积填充，超出自动裁剪。保存后立即生效。' }],
  deepReasoning: [{ type: 'info', msg: '深度思考：AI 先流式展示思维链，思考末尾输出检索计划（精化 query + 子问题）多路并行检索合并后回答。失败自动降级。' }],
  ratelimit: [{ type: 'info', msg: 'Redis 固定窗口计数，按用户（匿名按 IP）限频，超限返回 429。限频设为 0 表示不限流；Redis 不可用时自动放行。' }],
  maintenance: [{ type: 'info', msg: '后台定时任务参数，保存即生效。周期填 ≤0 表示暂停该任务；清理类任务只删超期数据。' }],
  apiKey: [{ type: 'info', msg: '给外部系统发放调用问答能力的密钥：调用方在请求头带 X-Api-Key 即可（免平台 token）。Key 权限固定为问答链路，管理端点一律拒绝。' }],
  // 技能与 MCP 的内容已迁到「智能体」页的个人 Tab（每人管自己的），这里只剩上下文预算参数
  skills: [{ type: 'info', msg: '技能内容与启停由每个人在「智能体 → 技能 Skills」里自己管理（内置技能随版本分发，可各自停用）。这里只保留两项预算参数：技能清单注入系统提示的字符上限、单个技能全文读取的字符上限——防止技能过多或过长吃掉上下文预算。' }],
  agent: [{ type: 'info', msg: '并行检索：把一个问题拆成多个检索视角并行执行（各自检索 + 提炼要点）再汇总，改善复杂问题"召回不全"。若当前智能体已配置子智能体，则改为委派子智能体执行——各按自己的知识库范围与角色视角检索。基于 Spring AI Alibaba 的 StateGraph 编排，失败会自动降级为原有单路检索，不影响问答可用性。' }],
}

const loading = ref(false)
const saving = ref(false)
// 字段定义是否已从后端到达：定义到位前不渲染表单（blocksOf/currentPanel 都依赖字段数组，
// 空定义下渲染会直接抛错）。容器本身仍是 configSchema.js 里的常量引用，填充后即可用。
const schemaLoaded = ref(false)

// ==================== 表单状态与回填 ====================
// 初始为空壳：字段定义来自后端 ⇒ 只有拿到定义后才能 buildDefaultForm()；
// keyword 预置空对象是给下方的探测结果清理 watch 兜底（避免加载期取属性报错）
const form = ref({ keyword: {} })

const fetchAndFill = async () => {
  loading.value = true
  try {
    // 定义先行：渲染字段、构建表单默认值、以及后续提交校验提示都基于它
    const rs = await getConfigSchema()
    if (!rs || !rs.success || !rs.data) throw new Error((rs && rs.msg) || '配置字段定义加载失败')
    applyServerSchema(rs.data)
    form.value = buildDefaultForm()
    schemaLoaded.value = true
    const r = await getConfig()
    if (r.success && r.data) {
      const d = r.data
      for (const f of FIELDS) {
        const raw = d[f.group]?.[f.submitKey || f.key]?.value
        let v
        if (raw === undefined || raw === null || raw === '') {
          v = f.type === 'switch' ? false : (f.type === 'number' ? f.def : (f.def ?? ''))
        } else if (f.type === 'number') v = Number(raw)
        else if (f.type === 'switch') v = raw === 'true' || raw === true
        else v = raw
        if (f.factor) v = Math.round(Number(v) / f.factor)
        writeForm(form.value, f.path, v)
      }
      initialPayload.value = buildPayload()
    }
  } catch (e) { message.error(e.message || '加载配置失败') }
  finally { loading.value = false }
}

// ==================== 提交载荷与脏检测（与旧版同一管线） ====================
const buildPayload = () => {
  const out = {}
  for (const f of FIELDS) {
    let v = readForm(form.value, f.path)
    if (typeof v === 'string') {
      v = v.trim()
      if (/apikey/i.test(f.key) && v.startsWith('****')) continue
    } else if (typeof v === 'number' || typeof v === 'boolean') {
      v = String(v)
    } else if (v === undefined || v === null) {
      continue
    }
    if (f.factor) v = String(Math.round(Number(v) * f.factor))
    ;(out[f.group] = out[f.group] || {})[f.submitKey || f.key] = v
  }
  return out
}
const initialPayload = ref(null)
const dirtyPayload = computed(() => {
  const base = initialPayload.value
  if (!base) return {}
  const cur = buildPayload()
  const out = {}
  for (const g of Object.keys(cur)) {
    const cb = base[g] || {}
    const diff = {}
    for (const k of Object.keys(cur[g] || {})) {
      if (JSON.stringify(cur[g][k]) !== JSON.stringify(cb[k])) diff[k] = cur[g][k]
    }
    if (Object.keys(diff).length) out[g] = diff
  }
  return out
})
const dirtyCount = computed(() =>
  Object.values(dirtyPayload.value).reduce((n, g) => n + Object.keys(g).length, 0))

const save = async () => {
  const payload = dirtyPayload.value
  if (!Object.keys(payload).length) { message.info('没有需要保存的改动'); return }
  saving.value = true
  try {
    const r = await saveConfig(payload)
    if (r.success) {
      const n = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      if (n === 0) message.warning('没有可保存的配置项（后端未识别提交的键），请检查后重试')
      else { message.success(`配置已保存并生效（更新 ${n} 项）`); initialPayload.value = buildPayload() }
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

// ==================== 恢复本组默认 ====================
const resettingKey = ref('')
const doResetGroup = async (key, label) => {
  resettingKey.value = key
  try {
    const r = await resetConfig([key])
    if (r.success) {
      const cnt = r.data && typeof r.data === 'object' ? Object.keys(r.data).length : 0
      message.success(`「${label}」已恢复默认（${cnt} 项）`)
      await fetchAndFill()
    } else message.error(r.msg || '恢复失败')
  } catch (e) { message.error(e.message || '恢复失败') }
  finally { resettingKey.value = '' }
}
const onResetGroup = key => {
  const label = groupLabel(key)
  Modal.confirm({
    title: `恢复「${label}」为默认值？`,
    content: '该组当前的自定义值会被覆盖为出厂默认（模型 API Key 与向量模型组不受影响）。',
    okText: '恢复', cancelText: '取消',
    onOk: () => doResetGroup(key, label)
  })
}

// ==================== 测试连接（先测后存） ====================
// 模型网关的连通性测试已随 baseUrl/API Key 迁至「模型供应商」页（先测后存）；
// 这里保留关键词引擎探测。
const probeStates = ref({
  keyword: { loading: false, result: null }
})
const probeLabels = { keyword: '关键词引擎' }
const PROBE_BY_KEY = {
  'keyword.baseUrl': 'keyword'
}
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''

const doProbe = async group => {
  const s = probeStates.value[group]
  if (!s || s.loading) return
  const f = form.value
  const payload = { group }
  if (group === 'keyword') {
    Object.assign(payload, { baseUrl: f.keyword.baseUrl, apiKey: f.keyword.apiKey })
  }
  s.loading = true
  s.result = null
  try {
    const r = await probeConnectivity(payload)
    const d = r?.data || {}
    s.result = { available: !!d.available, latencyMs: d.latencyMs ?? 0, detail: d.detail || (r?.msg || '（无详情）') }
    if (!s.result.available) message.error(`${probeLabels[group]}不可达：${s.result.detail}`)
  } catch (e) {
    s.result = { available: false, latencyMs: 0, detail: e.message || '请求失败' }
    message.error(`${probeLabels[group]}探测失败：${s.result.detail}`)
  } finally { s.loading = false }
}

// 被探测项改动后清空旧探测结果
watch(
  () => [form.value.keyword?.baseUrl, form.value.keyword?.apiKey],
  () => {
    for (const k of Object.keys(probeStates.value)) probeStates.value[k].result = null
  }
)

// ==================== 枚举变更校验 ====================
const onFieldChange = (field, v) => {
  if (field.group === 'keyword' && field.key === 'engine') onKeywordEngineChange(v)
}
const onKeywordEngineChange = async val => {
  if (val !== 'meilisearch') return
  try {
    const r = await checkKeywordEngine()
    if (r.success && r.data?.available) {
      message.success('Meilisearch 服务正常。保存后请执行全量重建（/api/ai/search-index/reindex）再提问')
    } else {
      form.value.keyword.engine = 'mysql'
      message.error('Meilisearch 不可用：请先启动服务，或检查服务地址')
    }
  } catch (e) {
    form.value.keyword.engine = 'mysql'
    message.error('Meilisearch 校验失败：' + (e.message || '服务不可用'))
  }
}

// ==================== 重嵌入状态 ====================
// ==================== API Key 管理（6.5） ====================
const keys = ref([])
const keysLoading = ref(false)
const keyKeyword = ref('')
const keyTogglingId = ref('')
const keyCreating = ref(false)
const usageOpen = ref(false)
const copiedSample = ref('')
const copiedKey = ref(false)
// 创建走两步式弹窗：form（填名称/有效期）→ done（明文仅此一次展示）
const createOpen = ref(false)
const createStep = ref('form')
const createForm = ref({ name: '', expireMode: 'never', expireDate: null })
const createdKey = ref({ name: '', apiKey: '' })
const renameOpen = ref(false)
const renameForm = ref({ id: '', name: '' })

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
const fmtDate = s => (s ? String(s).slice(0, 10) : '长期')

const loadKeys = async () => {
  keysLoading.value = true
  try {
    const r = await listApiKeys()
    if (r.success && Array.isArray(r.data)) keys.value = r.data
  } catch (e) { /* 拉取失败不打扰，保留上次列表 */ }
  finally { keysLoading.value = false }
}
const copyText = async t => {
  try { await navigator.clipboard.writeText(t); return true } catch (e) { return false }
}
/** 有效期选择 → 提交给后端的 expireAt（yyyy-MM-dd；空串=长期） */
const expirePayload = () => {
  const m = createForm.value.expireMode
  if (m === 'never') return ''
  const ymd = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  if (m === 'custom') {
    const d = createForm.value.expireDate
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
  -d '{"question":"如何创建评分组件？"}'`
})
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
    const r = await createApiKey({ name: createForm.value.name.trim(), expireAt: expirePayload() })
    if (r.success) {
      createdKey.value = { name: r.data?.name || createForm.value.name.trim(), apiKey: r.data?.apiKey || '' }
      createStep.value = 'done'
      copiedKey.value = await copyText(createdKey.value.apiKey)
    } else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { keyCreating.value = false }
}
const copyCreatedKey = async () => {
  const ok = await copyText(createdKey.value.apiKey)
  copiedKey.value = ok
  if (!ok) message.warning('浏览器未授权剪贴板，请手动选中复制')
}
const copySample = async (k, text) => {
  const ok = await copyText(text)
  copiedSample.value = ok ? k : ''
  if (!ok) message.warning('浏览器未授权剪贴板，请手动选中复制')
}
const openRenameKey = rec => {
  renameForm.value = { id: rec.id, name: rec.name || '' }
  renameOpen.value = true
}

// ==================== API Key 共享范围（弹窗为公共组件 ShareScopeModal） ====================
const keyShareVisible = ref(false)
const keyShareTarget = ref({ id: '', shareConfig: '' })
const saveKeyShareFn = json => updateApiKeyShare(keyShareTarget.value.id, json)
const openShareKey = rec => {
  keyShareTarget.value = { id: rec.id, shareConfig: rec.shareConfig || '' }
  keyShareVisible.value = true
}
const submitRenameKey = async () => {
  const name = renameForm.value.name.trim()
  if (!name) { message.warning('名称不能为空'); return }
  try {
    const r = await renameApiKey(renameForm.value.id, name)
    if (r.success) { message.success('已改名'); renameOpen.value = false; loadKeys() }
    else message.error(r.msg || '改名失败')
  } catch (e) { message.error(e.message || '改名失败') }
}
const toggleKey = async rec => {
  if (keyTogglingId.value) return
  keyTogglingId.value = rec.id
  try {
    const r = await setApiKeyDisabled(rec.id, !rec.disabled)
    if (r.success) { message.success(rec.disabled ? '已启用' : '已停用'); loadKeys() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
  finally { keyTogglingId.value = '' }
}
const delKey = async id => {
  try {
    const r = await deleteApiKey(id)
    if (r.success) { message.success('已删除'); loadKeys() }
    else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// 切到 API Key 面板时自动拉一次最新列表（数据可能在别处改过）
watch(current, k => {
  if (k === 'apiKey') loadKeys()
})

onMounted(fetchAndFill)
</script>

<style scoped>
.dirty-hint { font-size: 12px; color: #a3691b; background: #faf3e6; border-radius: 6px; padding: 3px 10px; }
/* .head-hint-plain 已提到 app.css 作为全局共用样式（供应商/知识库/技能/MCP 页也用它） */
/* 高级设置开关 + 隐藏项提示 */
.adv-toggle {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text3);
  cursor: pointer; user-select: none; margin-left: 10px;
}
.adv-toggle:hover { color: var(--app-accent); }
.adv-hidden-hint {
  font-size: 11px; color: var(--app-text3); background: var(--app-accent-weak);
  border-radius: 999px; padding: 3px 10px;
}
.app-btn.dis { background: #c6d4f2; cursor: not-allowed; }
.set-body { flex: 1; min-height: 0; display: flex; }
.set-nav {
  width: 150px; flex: none; border-right: 1px solid var(--app-border); background: var(--app-panel);
  padding: 10px 8px; display: flex; flex-direction: column; gap: 2px; overflow-y: auto;
}
.set-nav-item { padding: 7px 10px; border-radius: 8px; font-size: 12px; color: var(--app-text2); cursor: pointer; }
.set-nav-item:hover { background: var(--app-accent-weak); }
.set-nav-item.active { background: var(--app-accent-weak); color: var(--app-text); font-weight: 500; }
.set-content { flex: 1; min-width: 0; overflow-y: auto; padding: 14px 20px 24px; }
.set-panel-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.set-card { padding: 18px 20px 6px; }
.cfg-sub { font-size: 12px; font-weight: 500; color: var(--app-text3); margin: 14px 0 2px; padding-bottom: 4px; border-bottom: 1px dashed var(--app-border); }
.tip-icon { color: var(--app-text3); font-size: 12px; cursor: help; }
.app-btn.small { padding: 3px 10px; font-size: 11px; border-radius: 6px; margin-left: 10px; }
.app-btn.small + .app-btn.small { margin-left: 8px; }
.probe-btn { margin-left: 8px; }
.probe-chip { margin-left: 8px; font-size: 11px; border-radius: 999px; padding: 3px 9px; cursor: help; }
.probe-chip.ok { color: var(--app-ok); background: #eaf5ec; }
.probe-chip.bad { color: var(--app-danger); background: #fbecea; }
/* API Key 管理（6.5） */
.key-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.key-bar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.key-bar-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; flex: none; }
.key-stat { font-size: 12px; color: var(--app-text2); }
.key-stat b { color: var(--app-text); font-weight: 600; }
.key-dim { color: var(--app-text3); font-size: 12px; }
/* 搜索框（三面板统一肩部工具栏用） */
.res-search { width: 220px; }
.res-search :deep(.ant-input-affix-wrapper) { border-radius: 8px; }
.res-search :deep(.ant-input-prefix) { margin-right: 6px; }
.res-search-ic { color: var(--app-text3); font-size: 12px; }
/* 名称与状态标签同一行：inline-flex 垂直居中（inline-block 的基线对齐会让标签高低不齐） */
.key-name-wrap { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; }
.key-name { font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.key-tag { font-size: 11px; flex: none; line-height: 18px; }
.key-prefix { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: var(--app-text2); }
/* 空态 */
.key-empty { text-align: center; padding: 36px 20px; border: 1px dashed var(--app-border); border-radius: 8px; }
.key-empty-title { font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.key-empty-desc { font-size: 12px; color: var(--app-text3); margin-bottom: 14px; line-height: 1.7; }
/* 如何使用 */
.key-usage { margin-top: 16px; border-top: 1px solid var(--app-border); padding-top: 10px; }
/* 通用折叠头（如何使用 / 高级编辑 / 技能设置） */
.res-fold { margin-top: 14px; border-top: 1px solid var(--app-border); padding-top: 10px; }
.fold-head { font-size: 12px; font-weight: 500; cursor: pointer; user-select: none; }
.fold-head:hover { color: var(--app-accent); }
.fold-caret { display: inline-block; width: 12px; color: var(--app-text3); }
.key-usage-body { padding: 10px 0 0 12px; }
.key-usage-label { font-size: 12px; color: var(--app-text2); margin-bottom: 6px; }
.key-code { position: relative; background: #f6f7f9; border: 1px solid var(--app-border); border-radius: 6px; padding: 10px 34px 10px 12px; }
.key-code code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-all; color: var(--app-text); }
.key-copy {
  position: absolute; top: 6px; right: 6px; width: 24px; height: 24px; border: none; border-radius: 5px;
  background: transparent; color: var(--app-text3); cursor: pointer; font-size: 13px;
  display: inline-flex; align-items: center; justify-content: center;
}
.key-copy:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.key-copy-ok { color: var(--app-ok); }
.key-usage-list { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: var(--app-text2); line-height: 1.9; }
.key-usage-list code { background: #f2f3f5; padding: 1px 5px; border-radius: 4px; font-size: 11px; }
/* 弹窗内 */
.key-modal-foot { display: flex; justify-content: flex-end; gap: 8px; margin-top: 18px; }
.key-done-warn {
  background: #fff7e6; border: 1px solid #ffd591; color: #d46b08;
  border-radius: 6px; padding: 8px 10px; font-size: 12px; margin-bottom: 12px; line-height: 1.6;
}
.key-done-meta { font-size: 12px; color: var(--app-text2); margin-bottom: 8px; }
.key-done-box {
  position: relative; background: #f6f7f9; border: 1px solid var(--app-border); border-radius: 6px;
  padding: 12px 36px 12px 12px;
}
.key-done-box code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; word-break: break-all; color: var(--app-text); }
</style>
