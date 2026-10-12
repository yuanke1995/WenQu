<template>
  <!-- 只在真有留存时出现：卡片里的预览已经说明「以上为预览」，这句是它唯一的续读入口 -->
  <button class="spill-btn" type="button" :disabled="loading" :title="'读回这条工具调用的完整' + kind + '原文'"
          @click="load">
    <download-outlined /> 查看完整{{ kind }}<template v-if="loading">（读取中…）</template>
  </button>

  <a-modal v-model:open="shown" :title="title" width="min(920px, 94vw)" :footer="null" class="spill-modal">
    <div class="spill-bar">
      <input v-model="q" class="spill-q" type="text" :placeholder="searchHint" @keyup.enter="runSearch" />
      <button v-if="!local" class="spill-go" type="button" :disabled="searching || !q.trim()" @click="runSearch">
        {{ searching ? '搜索中…' : '在整份里搜' }}
      </button>
      <span class="spill-meta">{{ metaText }}</span>
    </div>

    <!-- 整份检索的命中清单：点一行跳到那一行（原文超过一段时才有这个，一段读得完就直接本地筛） -->
    <div v-if="hits.length" class="spill-hits">
      <div class="spill-hits-head">
        命中 {{ hits.length }}<template v-if="searchTotal > hits.length">（共 {{ searchTotal }} 处<template v-if="searchLimited">，只列前 {{ hits.length }} 处</template>）</template>
        · 点一行跳过去
      </div>
      <button v-for="h in hits" :key="h.line" class="spill-hit" type="button" @click="jumpTo(h)">
        <span class="spill-hit-ln">第 {{ h.line }} 行</span><span class="spill-hit-tx">{{ h.text }}</span>
      </button>
    </div>
    <div v-else-if="searched" class="spill-none">这份原文里没有「{{ q.trim() }}」——整份都扫过了，不是只筛了眼前这一段。</div>

    <!-- 一段读得完的（多数情况）：整份在前端按行筛，跟以前一样 -->
    <pre v-if="local && bodyText" class="spill-body">{{ bodyText }}</pre>
    <div v-else-if="local && raw" class="spill-err">没有匹配行（这份原文里没有「{{ q.trim() }}」）。</div>

    <!-- 超出一次读回上限：按行窗口翻，命中行高亮 -->
    <div v-else-if="!local && winLines.length" class="spill-body spill-lines">
      <div v-for="(row, i) in winLines" :key="win.fromLine + i" class="spill-line"
           :class="{ hit: win.fromLine + i === hitLine }">
        <span class="spill-ln">{{ win.fromLine + i }}</span><span class="spill-tx">{{ row }}</span>
      </div>
    </div>
    <div v-else-if="!local && !err" class="spill-err">{{ loading ? '正在读回…' : '这一段的行没读回来（可能已经翻过原文末尾）。' }}</div>

    <div v-if="!local && winLines.length" class="spill-pager">
      <button type="button" :disabled="loading || win.fromLine <= 1" @click="move(-1)">← 上 {{ winSize }} 行</button>
      <span>第 {{ win.fromLine }}–{{ win.toLine }} 行 / 共 {{ win.totalLines }} 行</span>
      <button type="button" :disabled="loading || win.toLine >= win.totalLines" @click="move(1)">下 {{ winSize }} 行 →</button>
    </div>

    <div v-if="err" class="spill-err">{{ err }}
      <button class="spill-go" type="button" @click="retry">重试</button>
    </div>
    <div v-else-if="!raw && !winLines.length && !loading && shown" class="spill-err">这份原文是空的（工具没有回显内容）。</div>
  </a-modal>
</template>

<script setup>
/**
 * 工具大输出的「查看完整输出/入参」：卡片里只放了预览，其余整份存在服务端磁盘上（见 ToolSpillService），
 * 点这里按需读回。
 * <p>两种读法按原文大小自动选，为的是「一句话找得到」：
 * <ul>
 *   <li>一次就读得完（多数）→ 在手里这份上按行筛，不打第二趟网络；</li>
 *   <li>读不完（超过一段 512K 字）→ 改按<b>行窗口</b>读，关键字交服务端扫整份，
 *       命中给行号，点一下跳到那一行（前端自己筛只筛得到已读回那段，报错在第 280 万字处就永远筛不到）。</li>
 * </ul>
 * 读回失败要出声并给重试按钮——留个空窗会被读成「我这条没留住」。
 */
import { computed, ref } from 'vue'
import { getToolOutput, getToolOutputLines, searchToolOutput } from '../api'

const props = defineProps({
  id: { type: String, default: '' },
  tool: { type: String, default: '' },
  kind: { type: String, default: '输出' }   // 输出 | 入参（写文件那种超长内容也留得住）
})

const shown = ref(false)
const loading = ref(false)
const err = ref('')
const q = ref('')
const raw = ref('')
const totalChars = ref(0)
const outOfBand = ref(false)   // 原文本身在落盘时就只留了前段
const local = ref(true)        // true=整份在手（前端筛）；false=按行窗口读

const win = ref({ fromLine: 0, toLine: 0, totalLines: 0 })
const winLines = computed(() => (win.value.content ? win.value.content.split('\n') : []))
const winSize = computed(() => Math.max(1, win.value.toLine - win.value.fromLine + 1))
const hitLine = ref(0)

const hits = ref([])
const searchTotal = ref(0)
const searchLimited = ref(false)
const searching = ref(false)
const searched = ref(false)

const title = computed(() => (props.tool ? `完整${props.kind}：${props.tool}` : `完整${props.kind}`))
const searchHint = computed(() => local.value
  ? '在这份原文里筛关键字（按行）…'
  : '关键字在整份原文里搜（服务端扫全部行，回车即搜）…')

const lines = computed(() => {
  const t = raw.value
  if (!t) return []
  const kw = q.value.trim().toLowerCase()
  const all = t.split('\n')
  if (!kw) return all
  return all.filter(l => l.toLowerCase().includes(kw))
})

const bodyText = computed(() => {
  if (!raw.value) return ''
  if (!q.value.trim()) return lines.value.join('\n')
  return lines.value.length ? `匹配 ${lines.value.length} 行\n\n${lines.value.join('\n')}` : ''
})

const metaText = computed(() => {
  if (err.value) return ''
  if (!local.value) {
    if (!win.value.totalLines) return ''
    const parts = [`共 ${win.value.totalLines.toLocaleString()} 行 · ${Number(totalChars.value || 0).toLocaleString()} 字`]
    if (searched.value && searchTotal.value) parts.push(`搜出 ${searchTotal.value} 处`)
    if (outOfBand.value) parts.push('原文过大，服务端只留存了前 4M 字')
    return parts.join(' · ')
  }
  if (!raw.value) return ''
  const parts = [`共 ${totalChars.value.toLocaleString()} 字`]
  if (q.value.trim()) parts.push(lines.value.length ? `筛出 ${lines.value.length} 行` : '没有匹配行')
  if (outOfBand.value) parts.push('原文过大，服务端只留存了前 4M 字')
  return parts.join(' · ')
})

function fail (msg) {
  err.value = msg
}

async function loadWindow (line) {
  loading.value = true
  err.value = ''
  try {
    const r = await getToolOutputLines(props.id, line)
    const d = r && r.data
    if (!d) { fail('这一段的原文读不回来了（可能已超过保留期被清理）'); return }
    win.value = d
    totalChars.value = Number(d.chars) || totalChars.value
  } catch (e) {
    fail('读取失败：' + (e?.message || '网络异常'))
  } finally {
    loading.value = false
  }
}

async function load () {
  if (!props.id) { err.value = '这条记录没有可读回的原文标识'; shown.value = true; return }
  shown.value = true
  if (raw.value || winLines.value.length || err.value) return    // 读过一次就用手里这份，反复点开不该重复打接口
  loading.value = true
  err.value = ''
  try {
    const r = await getToolOutput(props.id)
    const d = (r && r.data) || null
    if (!d) { err.value = '这份原文读不回来了（可能已超过保留期被清理）'; return }
    totalChars.value = Number(d.chars) || 0
    outOfBand.value = !!d.outOfBand
    if (d.more) {
      // 一次读回装不下：改成按行窗口读，找词交给服务端（只筛手里这段会筛不到后面的行）
      local.value = false
      await loadWindow(1)
      if (!win.value.totalLines && !err.value) fail('这段原文的行窗口没读回来')
    } else {
      raw.value = d.content || ''
    }
  } catch (e) {
    err.value = '读取失败：' + (e?.message || '网络异常')
  } finally {
    loading.value = false
  }
}

async function runSearch () {
  const kw = q.value.trim()
  if (!kw) { hits.value = []; searched.value = false; searchTotal.value = 0; return }
  if (local.value) { searched.value = true; return }   // 整份在手，前端按行筛就够（computed 已经在筛）
  searching.value = true
  searched.value = true
  err.value = ''
  try {
    const r = await searchToolOutput(props.id, kw)
    const d = r && r.data
    if (!d) { fail('这份原文里没搜到「' + kw + '」（也可能已被清理或不属于你）'); hits.value = []; return }
    hits.value = d.matches || []
    searchTotal.value = Number(d.total) || 0
    searchLimited.value = !!d.limited
  } catch (e) {
    fail('搜索失败：' + (e?.message || '网络异常'))
    hits.value = []
  } finally {
    searching.value = false
  }
}

function jumpTo (h) {
  hitLine.value = h.line
  loadWindow(h.line)
}

function move (dir) {
  hitLine.value = 0
  const size = winSize.value
  const next = dir < 0 ? win.value.fromLine - size : win.value.toLine + 1
  if (next < 1 || next > win.value.totalLines) return
  loadWindow(next)
}

function retry () {
  err.value = ''
  if (local.value || !winLines.value.length) load()
  else loadWindow(win.value.fromLine || 1)
}
</script>

<style scoped>
/* 安静的一行小字按钮：卡片正文已经在讲执行结果，续读入口不该抢版面 */
.spill-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 2px 8px;
  border: 1px solid var(--app-border, #d9d9d9);
  border-radius: 6px;
  background: transparent;
  color: var(--app-text-secondary, #666);
  font-size: 12px;
  cursor: pointer;
}
.spill-btn:hover { color: var(--app-accent, #1677ff); border-color: var(--app-accent, #1677ff); }
.spill-btn:disabled { opacity: .6; cursor: default; }
.spill-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.spill-q {
  flex: 1 1 240px;
  padding: 5px 9px;
  border: 1px solid var(--app-border, #d9d9d9);
  border-radius: 6px;
  background: var(--app-bg-elevated, #fff);
  color: var(--app-text, #222);
  font-size: 13px;
}
.spill-go {
  padding: 4px 10px;
  border: 1px solid var(--app-border, #d9d9d9);
  border-radius: 6px;
  background: var(--app-bg-elevated, #fff);
  color: var(--app-text-secondary, #666);
  font-size: 12px;
  cursor: pointer;
}
.spill-go:hover { color: var(--app-accent, #1677ff); border-color: var(--app-accent, #1677ff); }
.spill-go:disabled { opacity: .6; cursor: default; }
.spill-meta { font-size: 12px; color: var(--app-text-secondary, #888); }
.spill-body {
  max-height: 58vh;
  overflow: auto;
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--app-bg-input, #f6f7f9);
  color: var(--app-text, #222);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.spill-lines { white-space: normal; padding: 6px 0; }
.spill-line { display: flex; gap: 8px; padding: 1px 10px; }
.spill-line.hit { background: var(--app-warn-weak); }
.spill-ln {
  flex: 0 0 auto;
  min-width: 5ch;
  text-align: right;
  color: var(--app-text3);
  font-variant-numeric: tabular-nums;
  user-select: none;
}
.spill-tx { flex: 1 1 auto; white-space: pre-wrap; word-break: break-word; }
.spill-hits { max-height: 22vh; overflow: auto; margin-bottom: 8px; border: 1px solid var(--app-border); border-radius: 8px; }
.spill-hits-head { padding: 6px 10px; font-size: 12px; color: var(--app-text-secondary, #888); }
.spill-hit {
  display: flex;
  gap: 8px;
  width: 100%;
  padding: 4px 10px;
  border: 0;
  border-top: 1px solid var(--app-border);
  background: transparent;
  color: var(--app-text, #222);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.spill-hit:hover { background: var(--app-bg-input, #f6f7f9); }
.spill-hit-ln { flex: 0 0 auto; color: var(--app-accent, #1677ff); font-variant-numeric: tabular-nums; }
.spill-hit-tx { flex: 1 1 auto; overflow: hidden; color: var(--app-text-secondary, #666); white-space: nowrap; text-overflow: ellipsis; }
.spill-none { margin-bottom: 8px; font-size: 12px; color: var(--app-text-secondary, #888); }
.spill-pager { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; font-size: 12px; color: var(--app-text-secondary, #888); }
.spill-pager button {
  padding: 3px 10px;
  border: 1px solid var(--app-border, #d9d9d9);
  border-radius: 6px;
  background: var(--app-bg-elevated, #fff);
  color: var(--app-text-secondary, #666);
  font-size: 12px;
  cursor: pointer;
}
.spill-pager button:hover:disabled { cursor: default; }
.spill-pager button:not(:disabled):hover { color: var(--app-accent, #1677ff); border-color: var(--app-accent, #1677ff); }
.spill-err { font-size: 13px; color: var(--app-danger, #d4380d); padding: 8px 2px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
