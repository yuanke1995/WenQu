import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse } from '@vue/compiler-sfc'

// BrowserPanel 由 ChatPage 用 v-show="railTab === 'browser'" 控制显隐。
// v-show 只能落到组件的单根 DOM 元素上；一旦模板出现多根（例如把 Teleport
// 放在根 div 外面），过程/来源/文件 tab 下仍会漏出「助手看到 / 自己浏览」。
const readRoots = file => {
  const { descriptor, errors } = parse(readFileSync(file, 'utf8'))
  assert.deepEqual(errors, [], `${file} SFC 解析失败`)
  assert.ok(descriptor.template, `${file} 缺少 template`)
  const roots = descriptor.template.ast.children.filter(n => n.type === 1) // ELEMENT
  return roots.map(n => n.tag)
}

const collectTags = (file, tag) => {
  const { descriptor } = parse(readFileSync(file, 'utf8'))
  const found = []
  const walk = node => {
    if (node.type === 1 && node.tag === tag) found.push(node)
    for (const child of node.children || []) walk(child)
  }
  for (const root of descriptor.template.ast.children) walk(root)
  return found
}

test('BrowserPanel 保持单根，ChatPage 的 v-show 才能隐藏整个浏览面板', () => {
  assert.deepEqual(readRoots('src/components/BrowserPanel.vue'), ['div'])
  assert.match(readFileSync('src/views/ChatPage.vue', 'utf8'),
    /<BrowserPanel\s+v-show="railTab === 'browser'"/)
})

test('BrowserPanel 的全屏浮层仍在组件内，Teleport 没有变成第二个根节点', () => {
  assert.equal(collectTags('src/components/BrowserPanel.vue', 'Teleport').length, 1)
})

test('BrowserEmbed 保持单根，BrowserPanel 内的模式切换 v-show 才能生效', () => {
  assert.deepEqual(readRoots('src/components/BrowserEmbed.vue'), ['div'])
})
