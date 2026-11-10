<template>
  <!--
    产物首屏缩略：只负责「这一块里画什么」，尺寸由外层给（单件卡 112×84、多件 hero 128×82）。
    - image：真图铺满，失败退回类型徽标；
    - page：可见且拿到槽位才挂 iframe（375 逻辑宽后缩放），3s 未加载完成退回徽标；
    - md/table/text：可见才拉原文，取前几行；表单形 JSON 画迷你表格骨架；
    - 其它类型与分享页文本：类型徽标。
    缩略只是示意，真内容交给预览台；点击由外层锚点接管（iframe 不吃指针）。
  -->
  <div ref="root" class="ath" :class="['tone-' + tone, { 'is-blank': !showImage && !showFrame && !showForm && !showLines && !textLoading && !pagePending }]">
    <img v-if="showImage" class="ath-img" :src="hrefOf(file)" :alt="file.filename || ''"
         loading="lazy" referrerpolicy="no-referrer" @error="imgFailed = true" />

    <div v-else-if="pagePending" class="ath-page">
      <div v-if="!frameLoaded" class="ath-skel-page" aria-hidden="true">
        <div class="sp-bar"><i /><i /><i /></div>
        <div class="sp-body"><i class="sp-hero" /><i class="sp-l" /><i class="sp-l w60" /></div>
      </div>
      <iframe v-if="showFrame" class="ath-frame" :style="{ transform: 'scale(' + frameScale + ')' }"
              :src="hrefOf(file)" sandbox="allow-scripts" loading="lazy" scrolling="no"
              referrerpolicy="no-referrer" tabindex="-1" aria-hidden="true"
              @load="onFrameLoad" />
    </div>

    <div v-else-if="showForm" class="ath-form" aria-hidden="true">
      <i class="af-title" />
      <div v-for="r in 4" :key="r" class="af-row">
        <i v-for="c in 3" :key="c" />
      </div>
    </div>

    <div v-else-if="showLines" class="ath-lines" aria-hidden="true">
      <div v-for="(l, i) in textState.lines" :key="i" :class="['ath-line', { head: l.head }]">{{ l.text }}</div>
    </div>

    <div v-else-if="textLoading" class="ath-skel-lines" aria-hidden="true"><i /><i /><i /></div>

    <div v-else class="ath-fallback">
      <component :is="fallbackIcon" class="ath-fallback-ic" />
      <em>{{ extLabel }}</em>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  FileTextOutlined, FileImageOutlined, FileOutlined, GlobalOutlined, TableOutlined, CodeOutlined
} from '@ant-design/icons-vue'
import { previewKindOf, artifactExt, PV } from '../utils/artifactView'
import {
  loadThumbText, setFrameVisible, isFrameActive, onFrameSlots
} from '../utils/artifactThumb'

const props = defineProps({
  /** 产物（登录态：id/url/filename/preview；分享页：seq/filename/preview） */
  file: { type: Object, required: true },
  /** 产物地址解析：登录态 resolveImg，分享页按 token 现换 */
  hrefOf: { type: Function, default: f => (f && f.url) || '' },
  /**
   * 分享页：只给类型徽标。
   * 分享产物的地址是 token 下载端点（Content-Disposition: attachment + octet-stream），
   * 拿去给 img/iframe 只会触发下载、不会内联渲染；要做分享页缩略必须先改后端出流口径，
   * 不在本轮「零后端改动」范围内。
   */
  shared: { type: Boolean, default: false },
  /** H5：不在手机上跑网页 iframe */
  mobile: { type: Boolean, default: false }
})

const TEXT_KINDS = new Set([PV.MD, PV.TABLE, PV.TEXT])
const CODE_EXTS = new Set([
  'json', 'yaml', 'yml', 'xml', 'toml', 'ini', 'conf', 'properties',
  'py', 'js', 'mjs', 'ts', 'jsx', 'tsx', 'java', 'go', 'rs', 'sql', 'sh', 'bash', 'zsh', 'css', 'scss', 'vue'
])

const root = ref(null)
const imgFailed = ref(false)
const visible = ref(false)
const frameActive = ref(false)
const frameLoaded = ref(false)
const frameFailed = ref(false)
const frameScale = ref(0.28)
const textState = ref(null)
let io = null
let ro = null
let offSlots = null
let frameTimer = 0
let textStarted = false

const kind = computed(() => previewKindOf(props.file))
const ext = computed(() => artifactExt(props.file))
const key = computed(() => String((props.file && (props.file.id || props.file.url || props.file.filename)) || ''))
const extLabel = computed(() => (ext.value ? ext.value.toUpperCase() : 'FILE'))

const tone = computed(() => {
  const k = kind.value
  if (k === PV.PAGE) return 'page'
  if (k === PV.IMAGE) return 'image'
  if (k === PV.TABLE) return 'sheet'
  if (CODE_EXTS.has(ext.value)) return 'code'
  if (k === PV.MD || k === PV.TEXT || k === PV.PDF) return 'doc'
  return 'other'
})

const showImage = computed(() => !props.shared && kind.value === PV.IMAGE && !imgFailed.value)
const canPage = computed(() => !props.shared && kind.value === PV.PAGE && !props.mobile && !frameFailed.value)
const pagePending = computed(() => canPage.value)
const showFrame = computed(() => canPage.value && frameActive.value)
const isText = computed(() => TEXT_KINDS.has(kind.value) && !props.shared)
const textLoading = computed(() => isText.value && !textState.value)
const showForm = computed(() => !!(textState.value && textState.value.form))
const showLines = computed(() => !!(textState.value && textState.value.lines && textState.value.lines.length && !textState.value.form))

const fallbackIcon = computed(() => {
  switch (tone.value) {
    case 'page': return GlobalOutlined
    case 'image': return FileImageOutlined
    case 'sheet': return TableOutlined
    case 'code': return CodeOutlined
    case 'doc': return FileTextOutlined
    default: return FileOutlined
  }
})

const startText = () => {
  if (textStarted || !isText.value || !props.file || !props.file.id) return
  textStarted = true
  loadThumbText(props.file.id).then(st => { textState.value = st })
}

const measureFrame = () => {
  const w = root.value ? root.value.clientWidth : 0
  if (w) frameScale.value = w / 375
}

const clearFrameTimer = () => {
  if (frameTimer) { clearTimeout(frameTimer); frameTimer = 0 }
}

const onFrameLoad = () => {
  frameLoaded.value = true
  clearFrameTimer()
}

const syncSlots = () => { frameActive.value = isFrameActive(key.value) }

const applyVisibility = () => {
  if (canPage.value) setFrameVisible(key.value, visible.value)
  if (visible.value) startText()
}

watch(visible, applyVisibility)
watch(canPage, () => { syncSlots(); applyVisibility() })
watch(frameActive, active => {
  clearFrameTimer()
  if (!active) return
  frameLoaded.value = false
  frameFailed.value = false
  // 同一屏被别的卡挤掉槽位时，iframe 会卸载；重新拿到槽位再给一次 3s
  frameTimer = setTimeout(() => {
    if (!frameLoaded.value) {
      frameFailed.value = true
      setFrameVisible(key.value, false)
    }
  }, 3000)
})
// 只认「同一份产物」的键：交付包每次重算都会新建文件对象，按对象身份监听会被流式 token
// 触发成每帧重置（缩略不停闪）。id/url/filename 都不变时不动。
watch(key, (next, prev) => {
  if (prev === next) return
  if (prev) setFrameVisible(prev, false)
  imgFailed.value = false
  frameFailed.value = false
  frameLoaded.value = false
  frameScale.value = 0.28
  textState.value = null
  textStarted = false
  syncSlots()
  if (visible.value) applyVisibility()
})

onMounted(() => {
  offSlots = onFrameSlots(syncSlots)
  syncSlots()
  measureFrame()
  if (typeof ResizeObserver !== 'undefined' && root.value) {
    ro = new ResizeObserver(measureFrame)
    ro.observe(root.value)
  }
  if (typeof IntersectionObserver === 'undefined') {
    visible.value = true
    return
  }
  io = new IntersectionObserver(entries => {
    visible.value = !!(entries[0] && entries[0].isIntersecting)
  }, { rootMargin: '120px 0px' })
  if (root.value) io.observe(root.value)
})

onBeforeUnmount(() => {
  if (io) io.disconnect()
  if (ro) ro.disconnect()
  if (offSlots) offSlots()
  clearFrameTimer()
  if (key.value) setFrameVisible(key.value, false)
})
</script>

<style scoped>
.ath {
  width: 100%; height: 100%;
  position: relative; overflow: hidden;
  background: var(--app-panel-3, #f7f8fa);
}
.ath-img { width: 100%; height: 100%; object-fit: cover; display: block; }

/* 网页：骨架打底，iframe 按 375 逻辑宽渲染后缩放 */
.ath-page { position: absolute; inset: 0; overflow: hidden; }
.ath-frame {
  width: 375px; height: 520px; border: 0; background: #fff;
  transform-origin: 0 0; pointer-events: none;
}
.ath-skel-page { position: absolute; inset: 0; background: var(--app-panel); }
.sp-bar {
  height: 12px; display: flex; align-items: center; gap: 3px; padding: 0 5px;
  background: var(--app-panel-2, #fafbfc); border-bottom: 1px solid var(--app-border, #e6e9ed);
}
.sp-bar i { width: 4px; height: 4px; border-radius: 50%; background: var(--app-border-strong, #d8dde3); }
.sp-body { padding: 8px; }
.sp-body i { display: block; border-radius: 2px; margin-bottom: 5px; background: var(--app-panel-2, #fafbfc); border: 1px solid var(--app-border, #e6e9ed); }
.sp-hero { height: 16px; width: 54%; background: var(--app-accent-weak, #eef2f7); border-color: var(--app-accent-border, #bcd0f7); }
.sp-l { height: 4px; }
.sp-l.w60 { width: 60%; }

/* 表单骨架：结构示意，不渲染真实字段 */
.ath-form { padding: 8px 7px; height: 100%; }
.af-title {
  display: block; height: 5px; width: 46%; margin: 0 auto 6px;
  background: var(--app-accent-weak, #eef2f7); border: 1px solid var(--app-accent-border, #bcd0f7);
  border-radius: 2px;
}
.af-row { display: flex; gap: 3px; margin-bottom: 4px; }
.af-row i {
  flex: 1; height: 9px; border-radius: 2px;
  background: var(--app-panel); border: 1px solid var(--app-border, #e6e9ed);
}

/* 文本前几行：这个尺寸只求能分辨内容，不求读全 */
.ath-lines { padding: 7px 8px; height: 100%; overflow: hidden; }
.ath-line {
  font-size: 8px; line-height: 1.5; color: var(--app-text2, #5f6570);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ath-line.head { color: var(--app-text, #1f2329); font-weight: 600; }

.ath-skel-lines { padding: 10px 9px; }
.ath-skel-lines i {
  display: block; height: 5px; border-radius: 2px; margin-bottom: 6px;
  background: var(--app-panel-2, #fafbfc); border: 1px solid var(--app-border, #e6e9ed);
}
.ath-skel-lines i:nth-child(2) { width: 78%; }
.ath-skel-lines i:nth-child(3) { width: 52%; }

.ath-fallback {
  height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px;
  color: var(--app-text3, #98a0aa);
}
.ath-fallback-ic { font-size: 17px; }
.ath-fallback em { font-family: var(--app-mono, ui-monospace, Menlo, monospace); font-size: 8.5px; font-style: normal; letter-spacing: .04em; }

/* 类型底色：与「我的产物」网格同一套语义色，浅到不抢标题 */
.ath.tone-page { background: linear-gradient(135deg, var(--app-accent-weak, #eef2f7), var(--app-panel)); }
.ath.tone-image { background: linear-gradient(135deg, var(--app-panel-2, #fafbfc), var(--app-panel)); }
.ath.tone-sheet { background: linear-gradient(135deg, var(--app-ok-weak, #eaf5ec), var(--app-panel)); }
.ath.tone-code { background: linear-gradient(135deg, var(--app-panel-2, #fafbfc), var(--app-panel)); }
.ath.tone-doc { background: linear-gradient(135deg, var(--app-panel-2, #fafbfc), var(--app-panel)); }
.ath.tone-other { background: linear-gradient(135deg, var(--app-panel-2, #fafbfc), var(--app-panel)); }
</style>
