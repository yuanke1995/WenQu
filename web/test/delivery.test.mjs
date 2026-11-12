import test from 'node:test'
import assert from 'node:assert/strict'
import { deliveryTagOf, deliveryEntryOf, deliverySubjectOf, deliveryOf, deliveryCardOf } from '../src/chat/delivery.js'

test('交付包：文件角色分类', () => {
  assert.equal(deliveryTagOf('index.html'), '页面')
  assert.equal(deliveryTagOf('robots.txt'), '站点文件')
  assert.equal(deliveryTagOf('README.md'), '文档')
  assert.equal(deliveryTagOf('data.csv'), '文件')
})

test('交付包：入口优先级 index.html > README > 任一 html > 第一件', () => {
  const files = [{ filename: 'a.md' }, { filename: 'b.html' }, { filename: 'README.md' }]
  assert.equal(deliveryEntryOf(files).filename, 'README.md')
  assert.equal(deliveryEntryOf([{ filename: 'b.html' }, { filename: 'a.md' }]).filename, 'b.html')
  assert.equal(deliveryEntryOf([{ filename: 'a.md' }]).filename, 'a.md')
  assert.equal(deliveryEntryOf([]), null)
})

test('交付包：主语去掉指令词与后半句', () => {
  assert.equal(deliverySubjectOf('做一个问渠官网，要有首屏和案例'), '问渠官网')
  assert.equal(deliverySubjectOf('帮我写一个季度总结'), '季度总结')
  assert.equal(deliverySubjectOf(''), '')
})

test('交付包：分组、入口标记、体量与单件成卡', () => {
  const d = deliveryOf([
    { filename: 'index.html', url: '/a', size: 2048 },
    { filename: 'about.html', url: '/b', size: 1024 },
    { filename: 'README.md', url: '/c', size: 0 },
    { filename: 'robots.txt', url: '/d', size: 0 }
  ], { name: '官网 · 交付' })
  assert.equal(d.name, '官网 · 交付')
  assert.equal(d.fileCount, 4)
  assert.equal(d.sizeLabel, '3.0 KB')
  assert.equal(d.entry.filename, 'index.html')
  assert.equal(d.entry.tag, '入口')
  assert.equal(d.pageCount, 1)
  assert.equal(d.siteCount, 1)
  assert.deepEqual(d.groups.map(g => g.key), ['entry', 'pages', 'docs', 'site'])
  assert.equal(deliveryCardOf(d), d)
})

test('交付包：同名文件重复交付只留最后一次，入口不指旧版本', () => {
  const d = deliveryOf([
    { filename: 'index.html', url: '/old', size: 1024 },
    { filename: 'about.html', url: '/about', size: 512 },
    { filename: 'index.html', url: '/new', size: 2048 }
  ], { name: '官网 · 交付' })
  assert.equal(d.fileCount, 2)
  assert.equal(d.files.filter(f => f.filename === 'index.html').length, 1)
  assert.equal(d.entry.url, '/new')
  assert.equal(d.sizeLabel, '2.5 KB')
})

test('交付包：无有效产物返回 null；seq 条目也算有效', () => {
  assert.equal(deliveryOf([]), null)
  assert.equal(deliveryOf([{ filename: 'x.md' }]), null)
  assert.equal(deliveryOf([{ filename: 'x.md', seq: 1 }]).fileCount, 1)
  assert.equal(deliveryCardOf(null), null)
})
