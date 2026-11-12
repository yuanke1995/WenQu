import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const engine = readFileSync('src/chat/useChatEngine.js', 'utf8')
const transport = readFileSync('src/chat/chatTransport.js', 'utf8')

test('transport 边界：引擎不再直连 api.js，统一经 chatTransport', () => {
  assert.equal(/from ['"]\.\.\/api['"]/.test(engine), false, 'useChatEngine 不得直接从 ../api 导入')
  assert.match(engine, /from '\.\/chatTransport\.js'/)
  assert.match(transport, /from '\.\.\/api\.js'/)
})

test('transport 面覆盖问答/审批/计划/目标/沙盒五组', () => {
  for (const name of ['sendQuestion', 'resumeChat', 'getChatRun', 'approveToolCall',
    'listPendingPlans', 'resolvePlanApproval', 'startGoalApi', 'goalActionApi',
    'listSandboxFiles', 'downloadArtifactsZip']) {
    assert.match(transport, new RegExp(`\\b${name}\\b`), name)
  }
})
