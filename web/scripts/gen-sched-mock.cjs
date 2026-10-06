// 生成「真实数据」mock：maintenance 的 49 字段 schema + 17 个任务快照。
// 从 src/main/resources/config-schema.json 与 ScheduleCenter.java 直接解析，
// 不手抄——手抄的字段数/relatedKeys 与真实后端对不上，量出来的滚动高度就没意义。
const fs = require('fs')
const path = require('path')
const ROOT = path.resolve(__dirname, '..', '..')

const schema = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/main/resources/config-schema.json'), 'utf8'))
const maintFields = schema.fields.filter(f => f.panel === 'maintenance')
const maintPanel = schema.panels.find(p => p.key === 'maintenance')

const src = fs.readFileSync(path.join(ROOT, 'src/main/java/com/wenqu/ai/schedule/ScheduleCenter.java'), 'utf8')
const regRe = /register\(\s*"([^"]+)",\s*\n?\s*"[^"]*",\s*\n?\s*(null|"[a-z][\w.]*")/g
const registered = []
let m
while ((m = regRe.exec(src)) !== null) {
  registered.push({ name: m[1], configKey: m[2] === 'null' ? null : m[2].replace(/"/g, '') })
}
const tbl = src.match(/TASK_PARAMS = Map\.ofEntries\(([\s\S]*?)\n {4}\);/)
const taskParams = {}
if (tbl) {
  const entryRe = /Map\.entry\("([^"]+)",\s*List\.of\(([^)]*)\)\)/g
  let e
  while ((e = entryRe.exec(tbl[1])) !== null) {
    const keys = (e[2].match(/"[a-z][\w.]+"/g) || []).map(s => s.replace(/"/g, ''))
    taskParams[e[1]] = keys
  }
}

const now = 1700000000000
const tasks = registered.map((r, i) => ({
  name: r.name,
  desc: '任务说明文字（悬浮任务名可见）',
  configKey: r.configKey,
  editable: r.configKey != null,
  pauseRisk: null,
  intervalMs: 86400000,
  paused: false,
  running: false,
  lastFinishedAt: now - 60000,
  lastSuccess: i % 3 !== 0,
  lastDurationMs: 40 + i,
  lastError: null,
  successCount: i,
  failCount: 0,
  nextDueAt: now + 3600000,
  now,
  relatedKeys: taskParams[r.name] || []
}))

fs.writeFileSync('/tmp/s.json', JSON.stringify({
  version: 1, panels: [maintPanel], tips: schema.tips || {},
  corePaths: schema.corePaths || [], editable: schema.editable || [], fields: maintFields
}))
fs.writeFileSync('/tmp/t.json', JSON.stringify(tasks))
console.log(`mock: fields=${maintFields.length} tasks=${tasks.length} relatedKeys=${tasks.reduce((n, t) => n + t.relatedKeys.length, 0)}`)
