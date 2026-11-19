<template>
  <div class="be-root">
    <!-- 地址栏：接受 http/https，不带 scheme 补 https；拒绝 file/data/blob/脚本/内嵌凭据/自身 origin -->
    <div class="be-bar">
      <button type="button" class="be-nav" title="后退" :disabled="!canBack || unknown" @click="back">‹</button>
      <button type="button" class="be-nav" title="前进" :disabled="!canForward || unknown" @click="forward">›</button>
      <button type="button" class="be-nav" title="刷新" @click="reload">⟳</button>
      <input v-model="input" class="be-url" type="text" inputmode="url" autocomplete="off" spellcheck="false"
             placeholder="输入网址，回车打开（如 example.com）"
             @keydown.enter.prevent="go" />
      <button type="button" class="be-go" :disabled="!input.trim()" @click="go">前往</button>
    </div>

    <!-- 状态行：sandbox 开关（临时，不持久化）+ 系统浏览器逃生口 -->
    <div class="be-sub">
      <label class="be-sandbox" title="关闭 sandbox 可打开更多站点，但页面将能下载/弹窗/导航顶层应用，仅本次有效">
        <input type="checkbox" :checked="sandboxed" @change="sandboxed = $event.target.checked" />
        <span>沙箱隔离</span>
      </label>
      <span class="be-state" :class="{ bad: loadState === 'error' || blocked }">{{ stateText }}</span>
      <button type="button" class="be-ext" :disabled="!currentUrl" @click="openExternal">在系统浏览器打开 ↗</button>
    </div>
    <p v-if="!sandboxed" class="be-warn">⚠ 已临时关闭沙箱：页面可下载、弹窗或导航当前应用，刷新后恢复。</p>
    <p v-if="blocked" class="be-blocked">
      {{ blockReason }}
      <button v-if="currentUrl" type="button" class="be-link" @click="openExternal">在系统浏览器打开 ↗</button>
    </p>

    <!-- iframe 载体：同源策略下跨域页面读不到 URL，导航历史由本组件维护 -->
    <div class="be-stage">
      <iframe v-if="currentUrl" ref="frame" class="be-frame" :src="currentUrl" :sandbox="sandboxAttr"
              referrerpolicy="no-referrer" title="侧栏浏览"
              @load="onLoad" @error="onError"></iframe>
      <div v-else class="be-empty">
        <div class="be-empty-ic">🌐</div>
        <p>输入网址在侧栏里浏览</p>
        <p class="be-empty-tip">部分站点禁止被嵌入，此时请用「在系统浏览器打开」</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const input = ref('')
const history = ref([])
const hIndex = ref(-1)
const sandboxed = ref(true)
const loadState = ref('idle')   // idle | loading | ok | error
const blocked = ref(false)
const blockReason = ref('')      // 区分「地址被拒」与「站点拒绝嵌入」，别混用同一句文案
const unknown = ref(false)      // 页面内部导航后 URL 不可读
let expectedLoads = 0

const currentUrl = computed(() => hIndex.value >= 0 ? history.value[hIndex.value] : '')
const canBack = computed(() => hIndex.value > 0)
const canForward = computed(() => hIndex.value < history.value.length - 1)
const sandboxAttr = computed(() => sandboxed.value
  ? 'allow-scripts allow-forms allow-same-origin allow-popups allow-popups-to-escape-sandbox'
  : undefined)
const stateText = computed(() => {
  if (blocked.value) return '无法嵌入'
  if (loadState.value === 'loading') return '加载中…'
  if (loadState.value === 'error') return '加载失败'
  if (unknown.value) return '页面已跳转（地址不可读）'
  if (loadState.value === 'ok') return '已加载'
  return ''
})

// URL 校验：对齐 DSH 的协议策略——只收 http/https，补全 scheme，拒绝内嵌凭据与自身 origin
const resolveUrl = raw => {
  let s = String(raw || '').trim()
  if (!s) return { err: '请输入网址' }
  if (/^(javascript|data|blob|file|vbscript):/i.test(s)) return { err: '只支持 http/https 网页地址' }
  if (/^https?:\/\/[^/?#@]*@/i.test(s)) return { err: '地址里不能带账号密码' }
  if (!/^https?:\/\//i.test(s)) s = 'https://' + s
  try {
    const u = new URL(s)
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return { err: '只支持 http/https 网页地址' }
    if (u.origin === window.location.origin) {
      return { err: '这是问渠自己的地址，不能嵌进问渠自己。要开本会话请在系统浏览器打开，或输入别的网址。' }
    }
    u.hash = ''
    return { url: u.href }
  } catch (e) { return { err: '地址格式不对，请检查' } }
}

const startLoad = url => {
  unknown.value = false
  blocked.value = false
  blockReason.value = ''
  loadState.value = 'loading'
  expectedLoads++
}

const go = () => {
  const r = resolveUrl(input.value)
  if (r.err) { loadState.value = 'error'; blocked.value = true; blockReason.value = r.err; return }
  const url = r.url
  input.value = url
  // 从当前位置截断（后退后前往 = 丢弃后面的历史），去重连续相同 URL
  const next = history.value.slice(0, hIndex.value + 1)
  if (next[next.length - 1] !== url) next.push(url)
  history.value = next
  hIndex.value = next.length - 1
  startLoad(url)
}

const back = () => {
  if (!canBack.value) return
  hIndex.value--
  input.value = currentUrl.value
  startLoad(currentUrl.value)
}

const forward = () => {
  if (!canForward.value) return
  hIndex.value++
  input.value = currentUrl.value
  startLoad(currentUrl.value)
}

const reload = () => {
  if (!currentUrl.value) return
  // 页面内部导航后地址不可读：刷新回到最后一个受控 URL（对齐 DSH 行为）
  if (unknown.value) input.value = currentUrl.value
  startLoad(currentUrl.value)
}

const onLoad = () => {
  // 受控导航的 load 才知道对应哪个 URL；页面内部跳转的 load 一律进 unknown 状态
  if (expectedLoads > 0) {
    expectedLoads--
    blocked.value = false
    loadState.value = 'ok'
    // 浏览器对 X-Frame-Options 拒绝的 load：iframe 停在 about:blank / chrome-error，
    // 且跨域内容不可读——这里做 best-effort 判断，不假装能 100% 识别。
    try {
      const el = document.querySelector('.be-frame')
      const href = el && el.contentWindow ? el.contentWindow.location.href : ''
      if (!href || href === 'about:blank' || href.startsWith('chrome-error://')) {
        blocked.value = true
        blockReason.value = '这个站点禁止被嵌入（X-Frame-Options / CSP），页面无法在这里显示。'
      }
    } catch (e) { /* 跨域成功加载：读不到 URL 是常态，不判失败 */ }
  } else {
    unknown.value = true
    loadState.value = 'ok'
  }
}

const onError = () => {
  loadState.value = 'error'
  blocked.value = true
  blockReason.value = '页面加载失败，可能站点不可达或禁止嵌入。'
}

const openExternal = () => {
  const r = resolveUrl(input.value)
  const url = currentUrl.value || r.url
  if (url) window.open(url, '_blank', 'noopener')
}
</script>

<style scoped>
.be-root { display: flex; flex-direction: column; gap: 8px; }
.be-bar { display: flex; align-items: center; gap: 4px; }
.be-nav { flex: none; width: 24px; height: 26px; border: 1px solid var(--app-border); border-radius: 6px;
  background: var(--app-panel); color: var(--app-text2); font: inherit; font-size: 15px; line-height: 1; cursor: pointer; }
.be-nav:hover:not(:disabled) { border-color: var(--app-accent); color: var(--app-accent); }
.be-nav:disabled { opacity: .4; cursor: not-allowed; }
.be-url { flex: 1; min-width: 0; height: 26px; padding: 0 8px; border: 1px solid var(--app-border); border-radius: 6px;
  background: var(--app-panel); color: var(--app-text); font: inherit; font-size: 11.5px; }
.be-url:focus { outline: none; border-color: var(--app-accent); }
.be-go { flex: none; height: 26px; padding: 0 9px; border: 1px solid var(--app-accent); border-radius: 6px;
  background: var(--app-accent); color: #fff; font: inherit; font-size: 11px; cursor: pointer; }
.be-go:disabled { opacity: .5; cursor: not-allowed; }
.be-sub { display: flex; align-items: center; gap: 8px; font-size: 10.5px; color: var(--app-text3); }
.be-sandbox { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; white-space: nowrap; }
.be-sandbox input { margin: 0; width: 12px; height: 12px; }
.be-state { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.be-state.bad { color: var(--app-warn-text, #b26a00); }
.be-ext { flex: none; background: transparent; border: 1px solid var(--app-border); border-radius: 6px;
  padding: 3px 8px; font: inherit; font-size: 10.5px; color: var(--app-accent); cursor: pointer; }
.be-ext:hover:not(:disabled) { border-color: var(--app-accent); }
.be-ext:disabled { opacity: .5; cursor: not-allowed; }
.be-warn, .be-blocked { margin: 0; padding: 7px 9px; border-radius: 7px; font-size: 10.5px; line-height: 1.6;
  background: var(--app-warn-weak, #fff7e6); color: var(--app-warn-text, #b26a00); border: 1px solid var(--app-warn-border, #ffd591); }
.be-link { background: transparent; border: 0; padding: 0; font: inherit; color: var(--app-accent); cursor: pointer; text-decoration: underline; }
.be-stage { flex: 1; min-height: 320px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; background: var(--app-panel); }
.be-frame { width: 100%; height: 100%; min-height: 320px; border: 0; display: block; }
.be-empty { min-height: 320px; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 6px; padding: 20px; text-align: center; color: var(--app-text3); }
.be-empty-ic { font-size: 30px; opacity: .6; }
.be-empty p { margin: 0; font-size: 11.5px; }
.be-empty-tip { font-size: 10px; opacity: .8; }
</style>
