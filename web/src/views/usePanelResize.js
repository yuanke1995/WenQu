// 右栏宽度拖拽（从 ChatPage.vue 抽出）：宽屏两栏此消彼长，拖动只改 --rp-w（作用在 .chat2 上）。
// 上限 = 视口宽的 2/3（同时消息区至少留 360px），下限 280。宽度存 localStorage，刷新保留。
// 自洽的一块：只碰 window/document/localStorage 与 .chat2 的 DOM ref，不依赖页面其它状态。
import { ref, watch, onMounted } from 'vue'

const RP_W_KEY = 'wenqu.rpWidth'
const RP_MIN = 280
const CHAT_MIN = 360

export const usePanelResize = () => {
  const chat2Ref = ref(null)
  const rpMax = () => Math.max(RP_MIN, Math.min(window.innerWidth * 2 / 3, window.innerWidth - CHAT_MIN))
  const rpWidth = ref(Math.max(RP_MIN, Math.min(rpMax(), Number(localStorage.getItem(RP_W_KEY)) || 420)))

  const applyRpWidth = () => chat2Ref.value?.style.setProperty('--rp-w', rpWidth.value + 'px')

  watch(rpWidth, v => { localStorage.setItem(RP_W_KEY, String(v)); applyRpWidth() })
  onMounted(() => {
    rpWidth.value = Math.max(RP_MIN, Math.min(rpMax(), rpWidth.value))
    applyRpWidth()
  })

  const startPanelResize = e => {
    const startX = e.clientX, startW = rpWidth.value
    const onMove = ev => rpWidth.value = Math.max(RP_MIN, Math.min(rpMax(), startW - (ev.clientX - startX)))
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
    }
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  return { chat2Ref, rpWidth, startPanelResize }
}
