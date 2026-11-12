import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveEffectivePermission, permissionLabelOf } from '../src/chat/permission.js'

const presets = [
  { code: 'read', label: '只读', available: true },
  { code: 'write', label: '可写', available: true, platformDefault: true },
  { code: 'full', label: '完全', available: false }
]

test('优先级：会话覆盖 > 智能体默认 > 平台默认', () => {
  assert.equal(resolveEffectivePermission(presets, 'read', 'write'), 'read')
  assert.equal(resolveEffectivePermission(presets, '', 'read'), 'read')
  assert.equal(resolveEffectivePermission(presets, '', ''), 'write')
})

test('不可用档位按平台上限回落', () => {
  assert.equal(resolveEffectivePermission(presets, 'full', ''), 'write')
})

test('不可用档位且平台无可用项时保留原码', () => {
  const none = [{ code: 'full', label: '完全', available: false }]
  assert.equal(resolveEffectivePermission(none, 'full', ''), 'full')
})

test('空名册/空码：返回空串', () => {
  assert.equal(resolveEffectivePermission([], '', ''), '')
  assert.equal(resolveEffectivePermission(null, undefined, undefined), '')
})

test('档位文案：命中取 label，未知原样返回', () => {
  assert.equal(permissionLabelOf(presets, 'read'), '只读')
  assert.equal(permissionLabelOf(presets, 'gone'), 'gone')
  assert.equal(permissionLabelOf(null, 'x'), 'x')
})
