import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { basename, join } from 'node:path'

// 类型检查棘轮：checkJs 是全局关的，靠文件头 `// @ts-check` 逐个开启。
// 没有这条守卫，谁把某个文件头的 @ts-check 一删，它就从 CI 里静默退出类型检查——
// 表面上还是绿的，覆盖面却悄悄缩了。这里只许变多、不许变少。

const BASELINE = 141
const MUST_STAY_TAGGED = [
  'streamAnswerHandlers.js',   // 2026-11 拆分事故的现场：回调表装配
  'streamBuffer.js',
  'streamMerge.js',
  'applyDonePayload.js',
  'streamReducer.js',
  'streamToolFrames.js',
  'timelineWrite.js',
  'useChatEngine.js',
  'turnDoneCloseout.js',
  'turnReconnect.js',
  'sessionSwitch.js',
]

const walk = dir => {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else if (full.endsWith('.js')) out.push(full)
  }
  return out
}

const files = walk('src')
const tagged = new Set(
  files.filter(f => readFileSync(f, 'utf8').startsWith('// @ts-check')).map(f => basename(f))   // Windows 下分隔符是 \，不能按 '/' 切
)

test(`带 @ts-check 的源文件不少于 ${BASELINE} 个（棘轮：只许增加）`, () => {
  assert.ok(tagged.size >= BASELINE, `当前只有 ${tagged.size} 个文件开了 @ts-check，低于基线 ${BASELINE}`)
})

test('事故现场与核心流式模块必须一直留在类型检查里', () => {
  const missing = MUST_STAY_TAGGED.filter(f => !tagged.has(f))
  assert.deepEqual(missing, [], `这些文件丢了文件头 // @ts-check：${missing.join(', ')}`)
})

test('src/chat 下的每个模块都必须开 @ts-check（2026-11 起整目录收口）', () => {
  // 生成文件（*.generated.js）由生成器整份覆盖，标不了 @ts-check，按扩展名豁免
  const untagged = readdirSync('src/chat')
    .filter(f => f.endsWith('.js') && !f.endsWith('.generated.js'))
    .filter(f => !readFileSync(join('src/chat', f), 'utf8').startsWith('// @ts-check'))
  assert.deepEqual(untagged, [], `这些新/旧模块没有开类型检查：${untagged.join(', ')}`)
})