import test from 'node:test'
import assert from 'node:assert/strict'
import { isSaveHotkey, saveKeyLabel } from '../src/utils/shortcuts.js'

test('保存快捷键：Ctrl 与 ⌘ 都命中，Alt 组合不抢', () => {
  assert.equal(isSaveHotkey({ ctrlKey: true, key: 's' }), true)
  assert.equal(isSaveHotkey({ metaKey: true, key: 'S' }), true)
  assert.equal(isSaveHotkey({ altKey: true, ctrlKey: true, key: 's' }), false)
  assert.equal(isSaveHotkey({ key: 's' }), false)
  assert.equal(isSaveHotkey(null), false)
})

test('无浏览器平台信息时按 Ctrl+S 展示（Windows/Linux）', () => {
  assert.equal(saveKeyLabel(''), 'Ctrl+S')
  assert.equal(saveKeyLabel('Win32'), 'Ctrl+S')
  assert.equal(saveKeyLabel('Linux x86_64'), 'Ctrl+S')
  assert.equal(saveKeyLabel('MacIntel'), '⌘S')
})
