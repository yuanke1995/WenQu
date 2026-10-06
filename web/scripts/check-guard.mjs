// 验证窄屏页面守卫的白名单判断（纯逻辑，不渲染、不起服务）。
// 背景：初版把「白名单判断」放在父组件的 v-if 上、守卫组件无条件渲染引导卡，
//       导致 /chat 也被拦。此脚本从真实组件源码里抽出白名单常量与判断式来跑用例，
//       防止再次出现「逻辑算对了但没接上」的情况。
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const file = join(fileURLToPath(new URL('..', import.meta.url)), 'src/h5/DesktopOnlyGuard.vue')
const src = readFileSync(file, 'utf8')

// 从真实源码抽常量与判断式 —— 不复制粘贴，确保测的是代码本身。
// 先剥掉行注释（常量数组里有 // 说明文字，否则 JSON.parse 会失败）
const stripComment = s => s.replace(/\/\/[^\n]*/g, '')
const grab = re => {
  const m = src.match(re)
  if (!m) throw new Error('源码里没找到：' + re)
  const cleaned = stripComment(m[1]).replace(/'/g, '"').replace(/,(\s*])/, '$1')
  return JSON.parse(cleaned)
}
const ALLOW = grab(/const ALLOW = (\[[^\]]*\])/)
const ALLOW_PREFIX = grab(/const ALLOW_PREFIX = (\[[^\]]*\])/)
const usesAllowed = /<slot\s+v-else\s*\/>/.test(src)

// 与组件内 allowed 同式
const allowed = p => ALLOW.includes(p) || ALLOW_PREFIX.some(x => p.startsWith(x + '/'))

const cases = [
  ['/chat', true, '对话主入口（用户登录后的落地页，必须放行）'],
  ['/profile', true, '个人设置（改密码）'],
  ['/help', true, '帮助中心'],
  ['/artifacts', true, '我的产物（窄屏已适配：卡片列表 + 44px 热区 + Modal.confirm）'],
  ['/knowledge', true, '知识库（只读浏览：库卡片 + 文档列表窄屏重排）'],
  ['/knowledge/abc/docs', true, '知识库-文档管理（前缀放行，只读浏览）'],
  ['/settings', false, '系统设置'],
  ['/members', false, '成员管理'],
  ['/permissions', false, '权限管理'],
  ['/dashboard', false, '数据看板'],
  ['/stats', false, '使用统计'],
  ['/evaluation', false, '检索评估'],
  ['/agents', false, '智能体工作台'],
  ['/agents?tab=workflow', false, '智能体-工作流 tab']
]

let bad = 0
console.log(`白名单 ALLOW = ${JSON.stringify(ALLOW)}`)
console.log(`前缀表 ALLOW_PREFIX = ${JSON.stringify(ALLOW_PREFIX)}`)
console.log(`模板是否用了 v-else 透出 slot：${usesAllowed ? '是' : '否 ← 修复前就是这种（slot 被塞进引导卡里）'}`)
console.log('')
for (const [path, want, label] of cases) {
  const got = allowed(path)
  const ok = got === want
  if (!ok) bad++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${path.padEnd(24)} ${label.padEnd(40)} 期望${want ? '放行' : '引导卡'} 实际${got ? '放行' : '引导卡'}`)
}
if (!usesAllowed) { bad++; console.log('\nFAIL  守卫组件未用 v-else 透出 slot：白名单页面也会显示引导卡') }
console.log(bad ? `\n${bad} 项不符` : '\n全部通过')
process.exit(bad ? 1 : 0)
