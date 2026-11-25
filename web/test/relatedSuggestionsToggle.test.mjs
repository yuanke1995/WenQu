import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// 「接下来可以」推荐追问的个人开关：字段定义 + 后端读点 + 三处界面接线（PC / 移动 / 个人设置）。
// 纯静态对账（与 chatPageAssembly 同思路）：这几处一旦被改回「总是显示」，测试直接失败。
const read = p => readFileSync(p, 'utf8')

test('config-schema：接下来可以开关是个人开关、默认开', () => {
  const schema = JSON.parse(read('../src/main/resources/config-schema.json'))
  const f = schema.fields.find(x => x.backendKey === 'ui.showRelatedSuggestions')
  assert.ok(f, '缺少 ui.showRelatedSuggestions 字段')
  assert.equal(f.personal, true, '必须是 personal 字段（个人设置可改）')
  assert.equal(f.personalOnly, true, 'personalOnly：无全局槽位，不进管理端设置页')
  assert.equal(f.type, 'switch')
  assert.equal(f.def, true, '默认开')
})

test('后端读点：公开运行时配置按登录用户读回个人开关', () => {
  const src = read('../src/main/java/com/wenqu/ai/controller/ConfigController.java')
  assert.match(src, /"ui\.showRelatedSuggestions"/)
  assert.match(src, /ui\.put\("showRelatedSuggestions"/)
})

test('引擎：从公开运行时配置读入 showRelatedSuggestions 并对外暴露', () => {
  const src = read('src/chat/useChatEngine.js')
  // ref 声明仍在引擎；读公开运行时配置那一步随 ready() 抽进了 useEngineBootstrap。
  const boot = read('src/chat/useEngineBootstrap.js')
  assert.match(src, /const showRelatedSuggestions = ref\(true\)/)
  assert.match(boot, /ui\?\.showRelatedSuggestions !== false/)
  assert.match(src, /debugEntryVisible, debugDisplayVisible, showRelatedSuggestions,/)
})

test('PC：ChatPage 的「接下来可以」按 showRelatedSuggestions 渲染', () => {
  // 消息流在 ChatPage 拆分后落在子组件，上下文经 store 注入（前缀可选）。
  const list = read('src/views/chatpage/ChatMessageList.vue')
  assert.match(list, /m === store\.panelAi && store\.showRelatedSuggestions && m\.related/)
  const src = read('src/views/ChatPage.vue')
  assert.match(src, /debugEntryVisible, debugDisplayVisible, showRelatedSuggestions,/)
})

test('移动端：MobileMsgRow 受 showRelated 控制，MobileChatPage 传值', () => {
  const row = read('src/h5/MobileMsgRow.vue')
  assert.match(row, /showRelated: \{ type: Boolean, default: true \}/)
  assert.match(row, /lastRound && showRelated && m\.related/)
  const page = read('src/h5/MobileChatPage.vue')
  assert.match(page, /:show-related="showRelatedSuggestions"/)
  assert.match(page, /debugDisplayVisible, showRelatedSuggestions,/)
})

test('个人设置：对话显示分组 + 默认开感知的开关提交', () => {
  const src = read('src/views/ProfilePage.vue')
  assert.match(src, /title: '对话显示'/)
  assert.match(src, /f\.panel !== 'ui'/)
  // 默认开的开关关掉是有效改动，必须落库（不能沿用「false 等于默认就跳过」）
  assert.match(src, /const defVal = f\.def === true \? 'true' : 'false'/)
})

test('后端生成：关掉即不注入推荐提示词、并进实例指纹、也不落库', () => {
  // 三处读点随重构分散到协作类（行为等价，语义未变）：
  //  · 提示词注入 → AgentxPromptContext（先算 relatedSuggestions 布尔再决定挂 RelatedPrompt）
  //  · 实例指纹   → AgentxAgentBinding.bindingOf
  //  · 落库过滤   → AgentxTurnFinisher
  const prompt = read('../src/main/java/com/wenqu/ai/agentx/AgentxPromptContext.java')
  const binding = read('../src/main/java/com/wenqu/ai/agentx/AgentxAgentBinding.java')
  const finisher = read('../src/main/java/com/wenqu/ai/agentx/AgentxTurnFinisher.java')
  // 提示词按用户开关决定挂不挂 RelatedPrompt
  assert.match(prompt, /relatedSuggestions\s*\?\s*com\.wenqu\.ai\.agent\.chat\.RelatedPrompt\.line\(\)/)
  // 开关并进常驻实例指纹：拨动后下一轮重建才生效
  assert.match(binding, /key = key \+ "\|rel=" \+ relatedSuggestionsEnabled\(cmd\.getUserId\(\)\)/)
  // 关掉时不把 related 写进消息（即使模型偶发吐了也不会存）
  assert.match(finisher, /relatedChips\.isEmpty\(\) \|\| !agentBinding\.relatedSuggestionsEnabled\(c\.cmd\.getUserId\(\)\)/)
})
