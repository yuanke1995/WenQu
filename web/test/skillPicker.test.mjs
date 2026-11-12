import test from 'node:test'
import assert from 'node:assert/strict'
import { createSkillPicker, SKILL_AVA_COLORS, MAX_PICKED_SKILLS } from '../src/chat/skillPicker.js'

const setup = (api = async () => ({ success: true, data: [] }), sid = 's1') => {
  const warns = []
  const p = createSkillPicker({ listApi: api, currentSid: () => sid, notifyWarning: m => warns.push(m) })
  return { p, warns }
}

test('加载清单：成功取 data，失败/异常回落空数组', async () => {
  const ok = setup(async () => ({ success: true, data: [{ name: 'a' }] }))
  await ok.p.loadSkills()
  assert.deepEqual(ok.p.skillList.value, [{ name: 'a' }])

  const bad = setup(async () => ({ success: false, data: [{ name: 'b' }] }))
  await bad.p.loadSkills()
  assert.deepEqual(bad.p.skillList.value, [])

  const boom = setup(async () => { throw new Error('net') })
  await boom.p.loadSkills()
  assert.deepEqual(boom.p.skillList.value, [])
})

test('点名切换：加入/移除；最多 3 个', () => {
  const { p, warns } = setup()
  p.toggleSkill('a')
  p.toggleSkill('b')
  assert.deepEqual(p.pickedSkills.value, ['a', 'b'])
  p.toggleSkill('a')
  assert.deepEqual(p.pickedSkills.value, ['b'])
  p.toggleSkill('c')
  p.toggleSkill('d')
  assert.equal(p.pickedSkills.value.length, MAX_PICKED_SKILLS)
  p.toggleSkill('e')
  assert.equal(warns.length, 1)
  assert.match(warns[0], /3 个技能/)
})

test('不可点名的技能（工作区只读）加不进来', async () => {
  const { p } = setup(async () => ({ success: true, data: [{ name: 'ws', selectable: false }] }))
  await p.loadSkills()
  p.toggleSkill('ws')
  assert.deepEqual(p.pickedSkills.value, [])
})

test('头像底色按清单下标取，未知项按名字长度兜底', async () => {
  const { p } = setup(async () => ({ success: true, data: [{ name: 'a' }, { name: 'b' }] }))
  await p.loadSkills()
  assert.equal(p.skillAvaStyle('a').background, SKILL_AVA_COLORS[0])
  assert.equal(p.skillAvaStyle('b').background, SKILL_AVA_COLORS[1])
  assert.equal(p.skillAvaStyle('zz').background, SKILL_AVA_COLORS[2 % SKILL_AVA_COLORS.length])
})

test('skillDoc 只是当前查看的名字，切换不影响已点名的技能', () => {
  const { p } = setup()
  p.toggleSkill('a')
  p.skillDoc.value = 'a'
  assert.deepEqual(p.pickedSkills.value, ['a'])
})
