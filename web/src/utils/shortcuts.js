import { onBeforeUnmount, onMounted } from 'vue'

/** 平台判断只用于展示快捷键符号；业务功能永远同时响应 Ctrl 与 ⌘。 */
const isMac = () => /Mac|iPhone|iPad|iPod/i.test(typeof navigator === 'undefined' ? '' : (navigator.platform || ''))

export function saveKeyLabel () {
  return isMac() ? '⌘S' : 'Ctrl+S'
}

export function isSaveHotkey (e) {
  return Boolean(e && (e.ctrlKey || e.metaKey) && !e.altKey &&
    (e.key === 's' || e.key === 'S'))
}

/**
 * 在编辑器所在页面挂保存快捷键。
 * active 是当前编辑器是否可见/可保存的判定；不可见时不抢浏览器的 Ctrl+S。
 */
export function useSaveShortcut (active, handler) {
  const onKey = e => {
    if (typeof active === 'function' && !active()) return
    if (!isSaveHotkey(e)) return
    e.preventDefault()
    handler()
  }
  onMounted(() => window.addEventListener('keydown', onKey))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
  return onKey
}
