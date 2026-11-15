<template>
  <span v-if="items.length >= 2" ref="wrapEl" class="ao-wrap">
    <button type="button" class="ao-btn" :aria-expanded="open" title="本条回答的章节大纲" @click.stop="toggle">
      <UnorderedListOutlined />
      <span>大纲</span>
      <span class="ao-n">{{ items.length }}</span>
    </button>
    <div v-if="open" class="ao-pop" role="menu" @click.stop>
      <div class="ao-head"><span>本条回答</span><span class="ao-head-n">{{ items.length }} 节</span></div>
      <button v-for="(it, idx) in items" :key="it.srcIdx" type="button" class="ao-row"
              :class="{ on: it.srcIdx === activeIdx, h3: it.level >= 4 }" role="menuitem" @click="jump(it)">
        <span class="ao-no">{{ String(idx + 1).padStart(2, '0') }}</span>
        <span class="ao-txt" :title="it.text">{{ it.text }}</span>
      </button>
      <div class="ao-foot">点一节跳到该段；正文标题已可直接深链</div>
    </div>
  </span>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, nextTick } from 'vue'
import { UnorderedListOutlined } from '@ant-design/icons-vue'

const props = defineProps({
  /** 消息下标：用来定位同一条回答的 .md 容器（ChatPage 的正文带 data-msg-index） */
  msgIndex: { type: Number, required: true }
})

const wrapEl = ref(null)
const open = ref(false)
const items = ref([])
const activeIdx = ref(0)
let observer = null
let timer = null

/** 正文容器：优先从本组件的消息块里找，避免同名下标在浮层/预览里重复 */
const mdRoot = () => {
  const block = wrapEl.value && wrapEl.value.closest('.msg-block')
  return (block && block.querySelector('.md[data-msg-index]'))
    || document.querySelector(`.md[data-msg-index="${props.msgIndex}"]`)
}

/** 只收正文自己的章节标题：markdown 的 H1→H2、H2→H3、H3→H4 逐级降档，
 *  所以模型写的 ## / ### 实际落在 h3 / h4，三者都要收；过程块里的折叠详情不是章节 */
const collectEls = () => {
  const root = mdRoot()
  if (!root) return []
  return Array.from(root.querySelectorAll('h2, h3, h4'))
    .filter(el => !el.closest('.tl-turn-process'))
}

const refresh = () => {
  const els = collectEls()
  let raw = els.map((el, i) => ({
    text: (el.textContent || '').trim(), level: +el.tagName.slice(1), srcIdx: i
  })).filter(it => it.text)
  // 第一条是整条回答的标题（模型写 ## → 渲染成 h3），大纲只列它下面的章节；
  // 同一层级的并列标题不丢（例如两段并列的 ## 都属于正文）。
  if (raw.length > 1 && raw[0].level < raw[1].level) raw = raw.slice(1)
  items.value = raw
  els.forEach((el, i) => { if (!el.id) el.id = `ans-${props.msgIndex}-h${i + 1}` })
}

const scheduleRefresh = () => {
  clearTimeout(timer)
  timer = setTimeout(refresh, 260)
}

const activeOf = () => {
  const els = collectEls()
  let idx = 0
  els.forEach((el, i) => { if (el.getBoundingClientRect().top <= 120) idx = i })
  return idx
}

const toggle = () => {
  if (!open.value) {
    refresh()
    nextTick(() => { activeIdx.value = activeOf() })
  }
  open.value = !open.value
}

/** 正文所在的滚动容器：对话页是 .messages，分享页是 .sc-list */
const scrollerOf = el => {
  for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
    const oy = getComputedStyle(p).overflowY
    if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight) return p
  }
  return null
}

/** 吸顶回答头从滚动口顶端算起占掉的高度。头部贴的是滚动容器的内边距**之下**
 *  （.messages 有 20px 上内边距），不是滚动口顶端，所以留白要按「上内边距 + 头高」算；
 *  写死 64px 比它小，跳过去标题会被头压掉小半行。 */
const stickyInset = el => {
  const head = wrapEl.value && wrapEl.value.closest('.ans-head')
  if (!head) return 0
  const sc = scrollerOf(el)
  const padTop = sc ? (parseFloat(getComputedStyle(sc).paddingTop) || 0) : 0
  return padTop + head.getBoundingClientRect().height
}

const jump = it => {
  const els = collectEls()
  const el = els[it && typeof it.srcIdx === 'number' ? it.srcIdx : it]
  if (!el) return
  el.style.scrollMarginTop = Math.round(stickyInset(el) + 12) + 'px'
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeIdx.value = it && typeof it.srcIdx === 'number' ? it.srcIdx : it
  open.value = false
}

const onDocDown = e => {
  if (!open.value) return
  if (wrapEl.value && !wrapEl.value.contains(e.target)) open.value = false
}
const onKey = e => { if (e.key === 'Escape') open.value = false }

onMounted(() => {
  // 只观察正文 .md：观察整个 .msg-block 会把本组件自己弹出的面板也算成变更，
  // 形成「刷新 → 重渲染 → 再刷新」的 260ms 循环。
  const root = mdRoot()
  if (root && typeof MutationObserver !== 'undefined') {
    observer = new MutationObserver(scheduleRefresh)
    observer.observe(root, { subtree: true, childList: true, characterData: true })
  }
  refresh()
  document.addEventListener('pointerdown', onDocDown)
  document.addEventListener('keydown', onKey)
})

onBeforeUnmount(() => {
  if (observer) observer.disconnect()
  clearTimeout(timer)
  document.removeEventListener('pointerdown', onDocDown)
  document.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
.ao-wrap { position: relative; display: inline-flex; }
.ao-btn {
  height: 26px; padding: 0 7px; border: 1px solid transparent; border-radius: 7px;
  background: transparent; color: var(--app-text2); font: inherit; font-size: 11.5px;
  display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; cursor: pointer;
}
.ao-btn:hover, .ao-btn[aria-expanded="true"] { background: var(--app-panel-2); color: var(--app-text); }
.ao-btn :deep(svg) { width: 13px; height: 13px; }
.ao-n {
  min-width: 15px; height: 15px; padding: 0 4px; border-radius: 8px;
  background: var(--app-panel-3, #f2f4f7); font-size: 9.5px; line-height: 15px;
  font-variant-numeric: tabular-nums; text-align: center; color: var(--app-text3);
}
.ao-pop {
  position: absolute; top: calc(100% + 6px); right: 0; z-index: 30;
  width: 286px; max-height: 340px; overflow: auto;
  background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 10px; box-shadow: var(--app-shadow-lg, 0 12px 32px rgba(16, 24, 40, .12));
  padding: 5px;
}
.ao-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 8px 7px; font-size: 10.5px; color: var(--app-text3);
  border-bottom: .5px solid var(--app-border); margin-bottom: 4px;
}
.ao-head-n { font-variant-numeric: tabular-nums; }
.ao-row {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 6px 8px; border: 0; border-radius: 6px; background: transparent;
  color: var(--app-text2); font: inherit; font-size: 12px; text-align: left; cursor: pointer;
}
.ao-row:hover { background: var(--app-panel-2); color: var(--app-text); }
.ao-row.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
.ao-row.h3 { padding-left: 22px; }
.ao-no {
  flex: none; width: 18px; font-size: 9.5px; color: var(--app-text3);
  font-variant-numeric: tabular-nums;
}
.ao-row.on .ao-no { color: var(--app-accent); }
.ao-txt { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ao-foot {
  margin-top: 4px; padding: 6px 8px 4px; border-top: .5px solid var(--app-border);
  font-size: 9.5px; color: var(--app-text3);
}
@media (max-width: 768px) {
  .ao-pop { width: min(286px, calc(100vw - 40px)); }
  .ao-btn { min-height: 32px; }
}
</style>
