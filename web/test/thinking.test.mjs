import test from 'node:test'
import assert from 'node:assert/strict'
import { agentBadgeOf } from '../src/chat/agentBadge.js'
import { levelLabel, REASONING_LEVELS, THINK_CAPS, THINK_LEVEL_ON, thinkStrengthTextOf, thinkPanelFootOf, pillLevelBadgeOf, pillLevelTitleOf, chipLabelOf } from '../src/chat/thinking.js'
import { stepBriefOf } from '../src/chat/toolBrief.js'

test('归属徽标：id 优先，其次名称，找不到 null', () => {
  const agents = [{ id: 'a1', name: 'A' }, { id: 'a2', name: 'B' }]
  assert.equal(agentBadgeOf(agents, 'a1', 'B').name, 'A')
  assert.equal(agentBadgeOf(agents, 'missing', 'B').name, 'B')
  assert.equal(agentBadgeOf(agents, 'x', 'y'), null)
  assert.equal(agentBadgeOf(null, null, null), null)
})

test('思考档位：标签查表与默认', () => {
  assert.equal(levelLabel('xhigh'), '超高')
  assert.equal(levelLabel('unknown'), '默认强度')
  assert.equal(REASONING_LEVELS.length, 5)
  assert.equal(THINK_LEVEL_ON, '__on__')
  assert.equal(THINK_CAPS.always.locked, true)
})

test('stepBriefOf：工具名 + 关键参数；无 brief 只给名字', () => {
  assert.equal(stepBriefOf({ name: 'execute', args: '{"command":"ls"}' }), '执行命令 · ls')
  assert.equal(stepBriefOf({ name: 'unknown_tool' }), 'unknown_tool')
  assert.equal(stepBriefOf(null), '')
})

// ==================== 面板/胶囊文案 ====================

test('强度行文本：未开启/已开启/具体档位', () => {
  assert.equal(thinkStrengthTextOf(false, 'high'), '未开启')
  assert.equal(thinkStrengthTextOf(true, THINK_LEVEL_ON), '已开启')
  assert.equal(thinkStrengthTextOf(true, 'high'), '高')
})

test('面板底部提示：不支持/恒思考/可调三态', () => {
  assert.equal(thinkPanelFootOf({ visible: false }), '该模型不支持深度思考。')
  assert.match(thinkPanelFootOf({ visible: true, locked: true }), /始终深度思考/)
  assert.match(thinkPanelFootOf({ visible: true, locked: false }), /强度越高/)
  assert.equal(thinkPanelFootOf(null), '该模型不支持深度思考。')
})

test('胶囊标记：无模型/未登记/不支持/关闭都不挂，ON 挂「思」', () => {
  const base = { ref: 'v/m', modelInfo: {}, capsVisible: true, level: 'high' }
  assert.equal(pillLevelBadgeOf(base), '高')
  assert.equal(pillLevelBadgeOf({ ...base, ref: '' }), '')
  assert.equal(pillLevelBadgeOf({ ...base, modelInfo: undefined }), '')
  assert.equal(pillLevelBadgeOf({ ...base, capsVisible: false }), '')
  assert.equal(pillLevelBadgeOf({ ...base, level: 'off' }), '')
  assert.equal(pillLevelBadgeOf({ ...base, level: '' }), '')
  assert.equal(pillLevelBadgeOf({ ...base, level: THINK_LEVEL_ON }), '思')
})

test('胶囊悬浮说明：恒思考/已开启/具体档位，宽屏与触屏入口文案不同', () => {
  assert.equal(pillLevelTitleOf({ ref: 'v/m', badge: '', level: 'high', coarse: false }), '')
  assert.match(pillLevelTitleOf({ ref: 'v/m', badge: '高', locked: true, level: 'high', coarse: false }), /不可调/)
  assert.match(pillLevelTitleOf({ ref: 'v/m', badge: '思', locked: false, level: THINK_LEVEL_ON, coarse: false }), /悬浮下拉里的模型行/)
  assert.match(pillLevelTitleOf({ ref: 'v/m', badge: '高', locked: false, level: 'high', coarse: true }), /点旁边的「思考」/)
})

test('档位豆短标签', () => {
  assert.equal(chipLabelOf('off'), '关闭')
  assert.equal(chipLabelOf(THINK_LEVEL_ON), '开启')
  assert.equal(chipLabelOf('xhigh'), '超高')
})
