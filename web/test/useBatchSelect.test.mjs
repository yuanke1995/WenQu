import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { useBatchSelect, reportBatchResult } from '../src/utils/useBatchSelect.js'

// 勾选态此前在 6 个运营页各抄一份（智能体 / MCP / 服务商 / 工作流 / 定时任务 / 技能），
// 这里锁住抽出的那份的唯一行为，防止某页再改回各写一套。

const setup = (ids = [1, 2, 3], opts) => {
  const selectableIds = ref(ids.map(id => ({ id })))
  const c = useBatchSelect(selectableIds, opts)
  return { c, selectableIds }
}

test('批量模式默认关闭，退出即清空勾选', () => {
  const { c } = setup()
  assert.equal(c.batchMode.value, false)
  c.toggleSelect(1)
  assert.deepEqual(c.selected.value, [1])
  c.toggleBatchMode()
  assert.equal(c.batchMode.value, true)
  assert.deepEqual(c.selected.value, [1], '进入批量不应清空')
  c.toggleBatchMode()
  assert.equal(c.batchMode.value, false)
  assert.deepEqual(c.selected.value, [], '退出批量必须清空')
})

test('exitBatch 与再次点批量管理等价', () => {
  const { c } = setup()
  c.toggleBatchMode()
  c.toggleSelect(2)
  c.exitBatch()
  assert.equal(c.batchMode.value, false)
  assert.deepEqual(c.selected.value, [])
})

test('toggleSelect 幂等往返', () => {
  const { c } = setup()
  c.toggleSelect(2)
  c.toggleSelect(3)
  assert.deepEqual(c.selected.value, [2, 3])
  c.toggleSelect(2)
  assert.deepEqual(c.selected.value, [3])
})

test('allChecked / someChecked / toggleAll 三态', () => {
  const { c } = setup([1, 2, 3])
  assert.equal(c.allChecked.value, false)
  assert.equal(c.someChecked.value, false)
  c.toggleSelect(1)
  assert.equal(c.allChecked.value, false)
  assert.equal(c.someChecked.value, true, '选了一部分 → indeterminate')
  c.toggleAll()
  assert.deepEqual(c.selected.value, [1, 2, 3])
  assert.equal(c.allChecked.value, true)
  assert.equal(c.someChecked.value, false)
  c.toggleAll()
  assert.deepEqual(c.selected.value, [], '全选时再点全选 = 清空')
})

test('可勾选集合为空时 allChecked 为 false（空列表不应显示已全选）', () => {
  const { c } = setup([])
  assert.equal(c.allChecked.value, false)
  c.toggleAll()
  assert.deepEqual(c.selected.value, [])
})

test('keyOf 支持复合键（技能以 来源+名称 为身份）', () => {
  const items = ref([{ source: 'user', name: 'a' }, { source: 'builtin', name: 'a' }])
  const c = useBatchSelect(items, { keyOf: s => `${s.source}::${s.name}` })
  c.toggleSelect('user::a')
  assert.deepEqual(c.selected.value, ['user::a'], '同名不同来源是两条')
  assert.equal(c.allChecked.value, false)
  c.toggleSelect('builtin::a')
  assert.equal(c.allChecked.value, true)
})

test('reportBatchResult：全成功一条 message', () => {
  const calls = []
  const ui = { message: { success: t => calls.push(['success', t]) }, Modal: { warning: t => calls.push(['warning', t]) } }
  reportBatchResult({ data: { succeeded: [1, 2], failed: [] } }, '删除', '个智能体', ui)
  assert.deepEqual(calls, [['success', '已删除 2 个智能体']])
})

test('reportBatchResult：有失败逐条弹 Modal，不静默吞', () => {
  const calls = []
  const ui = { message: { success: t => calls.push(['success', t]) }, Modal: { warning: t => calls.push(['warning', t]) } }
  reportBatchResult({ data: { succeeded: [1], failed: [{ name: '甲', error: '内置不可删' }, { id: 9, error: '无权限' }] } }, '删除', '个智能体', ui)
  assert.equal(calls.length, 1)
  const [kind, payload] = calls[0]
  assert.equal(kind, 'warning')
  assert.equal(payload.title, '删除完成：成功 1 个，失败 2 个')
  assert.match(payload.content, /「甲」：内置不可删/)
  assert.match(payload.content, /「9」：无权限/, '没有 name 时回落到 id')
})