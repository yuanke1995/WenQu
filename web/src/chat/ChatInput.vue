<script setup>
/**
 * 聊天输入区（主对话页与公开分享页共用）。
 *
 * <p><b>为什么抽出来</b>：卡片外壳、正文与 chip 同流、工具条左右分栏、拖拽落点、发送/停止键
 * 这套东西原先只长在 ChatPage 里，分享页要用就得再写一份——而它是用户天天盯着的那块 UI，
 * 抄一份的后果不是多几十行，是两页慢慢长得不一样（按钮圆角、图标、悬停色、窄屏收法）。
 * 现在样式与布局只有这一份，改一次两页同时生效。
 *
 * <p><b>边界：本组件只管「怎么摆」，不管「有什么」</b>。页面专属的东西全部走插槽，
 * 所以主对话页的智能体胶囊、目标入口、模型选择、思考开关、插话队列、+ 面板、
 * @ 引用面板都不需要本组件知道：
 * <ul>
 *   <li>{@code #above}      —— 卡片之外的同一列（插话队列）</li>
 *   <li>默认插槽            —— 卡片内、正文之上（+ 面板 / @ 面板 / 斜杠面板）</li>
 *   <li>{@code #left-lead}  —— 工具条左端、附件键**之前**（智能体胶囊）</li>
 *   <li>{@code #left}       —— 工具条左端、附件键**之后**（目标）</li>
 *   <li>{@code #right}      —— 工具条右端、发送键**之前**（模型 / 思考 / 用量）</li>
 * </ul>
 *
 * <p><b>为什么附件键由组件画、却把「点了干什么」交给页面</b>：主对话页点「+」是展开面板
 * （上传只是面板里的一行），分享页点「+」直接开文件选择器。按钮长得一样、行为不同，
 * 正好是一个 emit + 一个 {@link #openPicker} 暴露方法的边界。
 *
 * <p><b>列宽与滚动条补偿</b>：用页面定义的 {@code --chat-col}（居中列宽）与 {@code --chat-sbw}
 * （滚动条槽宽），两边都给默认值，所以分享页不定义这两个变量也拿得到同样的 860px 列宽。
 */
import { computed, ref } from 'vue'
import { PlusOutlined, ArrowUpOutlined, PauseCircleOutlined } from '@ant-design/icons-vue'
import Composer from './Composer.vue'

const props = defineProps({
  /** 正文（不含 chip） */
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  /** 富占位（透传给 Composer）：命令词 + 说明；目标/计划这类“回车即执行”的入口用 */
  hintCmd: { type: String, default: '' },
  hintRest: { type: String, default: '' },
  /** chip 描述解析（透传给 Composer）：key → {kind,name,sub,...} */
  resolve: { type: Function, required: true },
  /** 生成中：发送键变停止键 */
  sending: { type: Boolean, default: false },
  /** 生成中且输入框有内容 = 可「插话」，此时发送键仍是发送态（与主对话页同一条判据） */
  steerable: { type: Boolean, default: false },
  /** 发送键可用性（有内容且不在上传中） */
  canSend: { type: Boolean, default: false },
  /** 是否显示附件键（能力没开就整颗不出现，而不是点了报错） */
  canAttach: { type: Boolean, default: true },
  /** 附件键的悬停说明 */
  attachTitle: { type: String, default: '添加：上传文件图片' },
  /** 系统选择器的接收类型（如 'image/*,.pdf'）：不传则不限，由取材层按 MIME 分流 */
  accept: { type: String, default: '' },
  /** 附件键高亮（主对话页：面板开着或已挂技能时点亮） */
  attachOn: { type: Boolean, default: false },
  /** 停止键上膛态：里面显示 esc 字样（两段式 Esc 停止） */
  stopArmed: { type: Boolean, default: false },
  /**
   * 用 {@code #replace} 插槽顶替整张卡片。
   * <p>主对话页在「挂起提问卡 / 待批计划卡」时是拿另一块面板<b>顶替</b>输入框的（v-if / v-else-if 三选一）。
   * 那两块面板要继续吃本组件的卡片样式（圆角/边框/聚焦光圈），就必须渲染在本组件树内——
   * 所以用插槽顶替，而不是让页面自己在外面再包一层。
   * <p>为什么用 prop 而不是「插槽有没有传」来判断：插槽上挂 v-if 时编译器仍会生成插槽函数，
   * 组件只认得出「有没有这个插槽」，认不出「它这次渲染出来是不是空的」——那样回退分支永远不生效。
   */
  replaceBox: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'send', 'stop', 'attach', 'files',
  'paste', 'keydown', 'input', 'click', 'focus', 'blur', 'chip-click', 'update:keys', 'ready'])

const composerRef = ref(null)
const fileInput = ref(null)
const boxRef = ref(null)
/** 手动拖拽高度（px）。null = 自适应（editor 自身 6 行上限 + 滚动）。
 *  赋值后卡片切到固定高度、editor 改为 flex 撑满并在内部滚动，让多行输入有更大的编辑区。 */
const boxHeight = ref(null)
const onResizeStart = e => {
  if (e.button !== 0) return
  const box = boxRef.value
  if (!box) return
  const startY = e.clientY
  const startH = box.getBoundingClientRect().height
  // 最小高度：容纳一行正文 + 工具条 + 上下内边距；最大高度：屏高的 6 成，避免盖住消息区
  const minH = 92
  const maxH = Math.max(minH, window.innerHeight * 0.6)
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'ns-resize'
  const move = ev => {
    const dy = ev.clientY - startY
    // 抓手在卡片上沿、卡片底边固定：往下拖 = 上沿跟着往下 = 变矮，所以是 startH - dy
    boxHeight.value = Math.min(maxH, Math.max(minH, startH - dy))
  }
  const up = () => {
    document.body.style.userSelect = ''
    document.body.style.cursor = ''
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}
/** 双击抓手还原为自适应高度 */
const resetHeight = () => { boxHeight.value = null }
const boxStyle = computed(() => boxHeight.value != null ? { height: boxHeight.value + 'px' } : null)
const dragOver = ref(false)
/** 拖拽进入/离开会在子元素之间反复触发（dragleave 先于下一个 dragenter），用计数抵消，
 *  否则鼠标划过子元素时提示会闪 */
let dragDepth = 0

/** 只有真的拖着文件才亮提示：拖选中的文字、拖 input 里的 chip 也会触发 dragenter，
 *  那时候弹「松开以添加图片或附件」是答非所问（与主对话页同一条判据） */
const onDragEnter = e => {
  if (!Array.from(e.dataTransfer?.types || []).includes('Files')) return
  dragDepth++
  dragOver.value = true
}
const onDragLeave = () => { if (--dragDepth <= 0) { dragDepth = 0; dragOver.value = false } }
const onDrop = e => {
  dragDepth = 0
  dragOver.value = false
  const files = Array.from(e.dataTransfer?.files || [])
  if (files.length) emit('files', files)
}
const onPicked = e => {
  const files = Array.from(e.target.files || [])
  // 同一个文件连选两次也要能触发 change：清空值必须在读取之后
  e.target.value = ''
  if (files.length) emit('files', files)
}
/** 打开系统文件选择器（页面点「+」要直接选文件时调它；走面板的页面不用） */
const openPicker = () => fileInput.value?.click()
// Composer 的命令式接口原样透出：父级要往编辑区里插 chip / 重画 / 聚焦都还得走它。
// rootEl 必须是 getter（而不是拍平成值）：父级 Esc 处理要拿它跟 document.activeElement 比，
// 一次性取值会在 Composer 重新挂载后指向旧节点
defineExpose({
  get rootEl () { return composerRef.value?.rootEl },
  openPicker,
  focus: () => composerRef.value?.focus(),
  blurOut: () => composerRef.value?.blurOut(),
  isFocused: () => composerRef.value?.isFocused(),
  serialize: () => composerRef.value?.serialize(),
  insertText: t => composerRef.value?.insertText(t),
  setText: t => composerRef.value?.setText(t),
  clear: () => composerRef.value?.clear(),
  sync: keys => composerRef.value?.sync(keys),
  repaint: () => composerRef.value?.repaint(),
  afterEdit: () => composerRef.value?.afterEdit(),
  domKeys: () => composerRef.value?.domKeys(),
  caretIndex: () => composerRef.value?.caretIndex(),
  setCaret: i => composerRef.value?.setCaret(i),
  replaceRange: (a, b, t) => composerRef.value?.replaceRange(a, b, t)
})

/** 生成中框里有字 → 这一点是「插话」；没字 → 才是停止本轮（与主对话页同一条判据） */
const showStop = computed(() => props.sending && !(props.steerable && props.canSend))
</script>

<template>
  <div class="ci" @dragenter.prevent="onDragEnter" @dragover.prevent @dragleave.prevent="onDragLeave" @drop.prevent="onDrop">
    <div v-if="dragOver" class="ci-drop">松开以添加图片或附件</div>
    <slot name="above" />
    <!-- 顶替态（挂起提问卡 / 待批计划卡）：页面给整块面板，卡片样式仍由本组件提供 -->
    <slot v-if="replaceBox" name="replace" />
    <div v-else class="ci-box" ref="boxRef" :class="{ 'ci-resized': boxHeight != null }" :style="boxStyle">
      <!-- 顶部拖拽热区：不画图标，光标到卡片上沿变 ns-resize 即是入口；按住向上拖放大、向下拖缩小（上沿跟着鼠标走）；双击还原自适应。窄屏隐藏（触屏不需要拖拽） -->
      <div class="ci-grip" @mousedown.prevent="onResizeStart" @dblclick="resetHeight"
           title="拖动调整输入框高度 · 双击还原"></div>
      <slot />
      <div class="ci-composer-wrap">
        <Composer ref="composerRef" :model-value="modelValue" :resolve="resolve" :placeholder="placeholder"
                  :hint-cmd="hintCmd" :hint-rest="hintRest"
                  @update:model-value="v => emit('update:modelValue', v)"
                  @update:keys="k => emit('update:keys', k)"
                  @keydown="e => emit('keydown', e)" @paste="e => emit('paste', e)"
                  @input="e => emit('input', e)" @click="e => emit('click', e)"
                  @focus="e => emit('focus', e)" @blur="e => emit('blur', e)"
                  @chip-click="k => emit('chip-click', k)"
                  @ready="() => emit('ready')" />
      </div>
      <div class="ci-toolbar">
        <div class="ci-left">
          <slot name="left-lead" />
          <button v-if="canAttach" class="app-icon-btn ci-attach" :class="{ 'ci-btn-on': attachOn }"
                  :title="attachTitle" @click="emit('attach')">
            <plus-outlined />
          </button>
          <slot name="left" />
        </div>
        <div class="ci-right">
          <slot name="right" />
          <button v-if="sending && canSend && steerable" class="ci-send"
                  title="插话（Enter 同效：先排到输入框上方 · Ctrl/⌘+Enter 立刻插进本轮）" @click="emit('send')">
            <arrow-up-outlined />
          </button>
          <button v-else-if="showStop" class="ci-send stop" :title="stopArmed ? '再按一次 Esc 停止生成' : '点击停止生成'" @click="emit('stop')">
            <span v-if="stopArmed" class="ci-esc">esc</span>
            <pause-circle-outlined v-else />
          </button>
          <button v-else class="ci-send" title="发送" :disabled="!canSend" @click="emit('send')">
            <arrow-up-outlined />
          </button>
        </div>
      </div>
    </div>
    <!-- 卡片之下的同列内容（如分享页的「内容由 AI 生成」页脚）：必须在同一个内边距列里，
         页面若自己在外面包一层带 padding 的容器，居中基准就和卡片错开了 -->
    <slot name="below" />
    <input ref="fileInput" class="ci-file" type="file" multiple :accept="accept" @click.stop @change="onPicked" />
  </div>
</template>

<style scoped>
/* 外层：只管那一圈内边距与拖拽落点。
   右内边距由三段相加：
     ① 32px 基础内边距；② --chat-sbw 滚动条槽宽——消息区是滚动容器、这里不是，两侧可用宽度
     天然差一个槽宽，不补则居中基准错开半个滚动条，肉眼能看出两条竖边不齐（主对话页实测写入）；
     ③ --ci-right-extra 页面额外让位（主对话页 ≤1200 要给右下角的全局帮助 FAB 让出角落，
     否则 FAB 压住发送键一侧）。后两个都由页面定义，分享页不定义即为 0。 */
.ci {
  position: relative; flex: none;
  padding: 10px calc(32px + var(--chat-sbw, 0px) + var(--ci-right-extra, 0px)) 14px 32px;
}
.ci-drop {
  position: absolute; inset: 6px calc(32px + var(--chat-sbw, 0px) + var(--ci-right-extra, 0px)) 6px 32px;
  z-index: 6; pointer-events: none;
  background: rgba(46,107,230,.06); border: 2px dashed var(--app-accent); border-radius: 14px;
  display: flex; align-items: center; justify-content: center;
  color: var(--app-accent); font-size: 14px; font-weight: 500;
}
/* 卡片：文本在上、工具条在下。
   同时写普通选择器与 :deep()：默认卡片由本组件自己渲染（普通选择器命中），
   而 #replace 插槽里的面板是父组件编译的、只有父级的 data-v 标记——它们在本组件根节点之下，
   所以 :deep() 编出来的 `[data-v-本组件] .ci-box` 正好命中它们。两页的卡片样式因此只有这一份。 */
.ci-box,
.ci :deep(.ci-box) {
  position: relative; max-width: var(--chat-col, 860px); margin: 0 auto;
  border: 1px solid var(--app-border); border-radius: 16px; background: var(--app-panel);
  padding: 10px 12px 8px;
  display: flex; flex-direction: column;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 8px 20px -10px rgba(16, 24, 40, .10);
  transition: border-color .2s, box-shadow .2s;
}
/* 顶部拖拽热区：不画任何图标（用户要求去掉常驻/悬浮横条），只留卡片上沿一条 10px 的
   隐形热区——光标到这变 ns-resize 即可发现，按住拖动改高、双击还原。窄屏隐藏（触屏不需要） */
.ci-grip {
  position: absolute; top: -4px; left: 0; right: 0; height: 10px; z-index: 5;
  cursor: ns-resize;
}
.ci-grip:hover::before { background: var(--app-accent); }
/* 编辑区包裹层：自适应时按内容撑高；手动拖高时 flex 撑满，editor 内部滚动 */
.ci-composer-wrap { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; }
.ci-composer-wrap > .composer,
.ci-composer-wrap :deep(.composer) { flex: 1 1 auto; min-height: 0; height: 100%; }
/* 手动高度态：撤掉 editor 的 6 行上限，改为撑满并内部滚动 */
.ci-resized .composer-edit,
.ci-resized :deep(.composer-edit) { max-height: none; flex: 1 1 auto; }
.ci-box:focus-within,
.ci :deep(.ci-box:focus-within) {
  border-color: var(--app-accent);
  box-shadow: 0 1px 2px rgba(16, 24, 40, .04), 0 10px 26px -10px rgba(46, 107, 230, .30);
}
.ci-file { display: none; }
.ci-toolbar { display: flex; align-items: center; gap: 6px; margin-top: 6px; }
.ci-left { display: flex; align-items: center; gap: 2px; min-width: 0; }
.ci-right { margin-left: auto; display: flex; align-items: center; gap: 2px; }
/* 附件键点亮态（面板开着 / 已挂材料）：与 app-icon-btn 的悬停态同色，但常驻 */
.ci-btn-on { color: var(--app-accent) !important; background: var(--app-accent-weak) !important; }
.ci-send {
  width: 30px; height: 30px; border-radius: 50%; border: none; flex: none;
  background: var(--app-accent); color: #fff; font-size: 15px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; transition: background .2s;
}
.ci-send:hover:not(:disabled) { background: var(--app-accent-hover); }
/* 禁用态：浅蓝底 + 白图标对比度太低，图标几乎看不见，看着像按钮坏了。
   改灰底灰图标 —— 禁用就该「退后」而不是「变淡」 */
.ci-send:disabled { background: var(--app-border); color: var(--app-text3); cursor: not-allowed; }
/* 停止态：弱化危险色，不做实心红圆 */
.ci-send.stop { background: var(--app-danger-weak); color: var(--app-danger-text); border: 1px solid var(--app-danger-border); }
.ci-send.stop:hover:not(:disabled) { background: var(--app-danger-border); }
.ci-esc { font-size: 10px; font-weight: 600; line-height: 1; letter-spacing: .5px; }

/* 窄屏：内边距从 32px 收到 10px（与 .chat-head 的横向节奏一致），工具条不换行
   （换行会把按钮里的文字压成竖排），触控热区放大。
   右内边距仍带滚动条槽：简写会把它一起抹掉，所以用长写补回来，否则窄屏又不对齐。
   注意这里**不再叠加 --ci-right-extra**：≤768 时页面已收起右下角的全局 FAB（主对话页的历史口径） */
@media (max-width: 768px) {
  .ci { padding: 8px calc(10px + var(--chat-sbw, 0px)) calc(10px + var(--sab, 0px)) 10px; }
  .ci-drop { inset: 6px calc(10px + var(--chat-sbw, 0px)) calc(6px + var(--sab, 0px)) 10px; }
  .ci-box,
  .ci :deep(.ci-box) { padding: 6px 6px 6px 10px; border-radius: 12px; }
  /* 触屏无拖拽：隐藏抓手，卡片回归自适应 */
  .ci-grip { display: none; }
  .ci-toolbar { flex-wrap: nowrap; gap: 2px; }
  .ci-left, .ci-right { min-width: 0; }
  .ci-right { margin-left: auto; }
  /* 触摸热区：antd 的 28~32px 控件在手指上偏小。发送键给到 44px —— 仓库里
     分享页与移动壳的触控标准都是 44（check:share / check:mshell 按 44 断言），
     主对话页窄屏原为 34px，此处一并对齐到同一口径 */
  .ci-toolbar .ant-btn { min-width: 34px; min-height: 34px; }
  .ci-toolbar .app-icon-btn { min-width: 36px; min-height: 36px; }
  .ci-send { width: 44px; height: 44px; font-size: 16px; }
}
</style>
