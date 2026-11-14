import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveEffectivePermission, permissionLabelOf } from '../src/chat/permission.js'

const presets = [
  { code: 'read', label: '只读' },
  { code: 'write', label: '可写', platformDefault: true },
  { code: 'full', label: '完全' }
]

test('优先级：会话覆盖 > 智能体默认 > 平台默认', () => {
  assert.equal(resolveEffectivePermission(presets, 'read', 'write'), 'read')
  assert.equal(resolveEffectivePermission(presets, '', 'read'), 'read')
  assert.equal(resolveEffectivePermission(presets, '', ''), 'write')
})

// 平台档位上限已退役（2026-11-14）：名册里的档位不再做 available 钳制，选过/智能体档原样生效
test('档位不再按平台上限回落：不可用码原样返回', () => {
  const list = presets.map(p => p.code === 'full' ? { ...p, available: false } : p)
  assert.equal(resolveEffectivePermission(list, 'full', ''), 'full')
  assert.equal(resolveEffectivePermission(list, '', 'full'), 'full')
})

test('档位不在名册里也原样返回', () => {
  assert.equal(resolveEffectivePermission(presets, 'gone', ''), 'gone')
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
