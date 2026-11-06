/**
 * 把现网某一轮的落库行导入**本地**库，供无头浏览器用同一份前端构建渲染真模型输出。
 *
 * 场景：无头浏览器是全新 profile，拿不到现网登录态；线上静态又与本地 `web/dist` 逐字节相同
 * （见 `docs/chat-blocks-rollout.md` §3.3），所以把行搬回本地渲染，结论对线上成立。
 * 只写本地 `wenqu_ai`；不改任何现网数据。
 *
 * 用法：
 *   node scripts/loadtest/replay-prod-row.mjs /tmp/prod-rows.tsv <现网会话 id> [本地 uid]
 * 输出：本地会话 id（`replay-<现网 sid 前 12 位>`），拿去开 `/chat?sid=…`。
 * 导出现网行的只读 SQL 见 `check-prod-rows.mjs` 底部注释（注意 `--default-character-set=utf8mb4`）。
 */
import { openSync, readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

const COLS = ['id', 'session_id', 'role', 'create_time', 'end_reason', 'turn_id',
  'content', 'thinking', 'process_text', 'timeline', 'tool_calls', 'artifacts', 'blocks', 'tokens']
const un = (s) => s.replace(/\\(.)/g, (_, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : c === 'r' ? '\r' : c === '0' ? '\0' : c))

const [file, sessionId, uid = 'yuanke'] = process.argv.slice(2)
if (!file || !sessionId) {
  console.error('用法：node scripts/loadtest/replay-prod-row.mjs <导出的 TSV> <现网会话 id> [本地 uid]')
  process.exit(2)
}

const rows = readFileSync(file, 'utf8').split('\n').filter(Boolean)
  .map((l) => Object.fromEntries(COLS.map((c, i) => [c, un(l.split('\t')[i] ?? '')])))
  .filter((r) => r.session_id === sessionId)
if (!rows.length) throw new Error('这份导出里没有该会话的行')

/** 值一律走 UNHEX：blocks/tool_calls 里引号、反斜杠、换行都不需要再转义。 */
const h = (s) => (s === '' || s == null ? 'NULL' : `_utf8mb4 X'${Buffer.from(s, 'utf8').toString('hex')}'`)
const localId = 'replay-' + sessionId.slice(0, 12)
const sql = [
  `DELETE FROM c_ai_message WHERE session_id='${localId}';`,
  `DELETE FROM c_ai_session WHERE id='${localId}';`,
  `INSERT INTO c_ai_session (id,user_id,title,message_count,create_time,update_time,deleted,tenant_id)
   VALUES ('${localId}','${uid}','现网真轮回放',${rows.length},NOW(),NOW(),0,'default');`,
]
rows.forEach((r, i) => {
  sql.push(`INSERT INTO c_ai_message
    (id,session_id,role,content,thinking,artifacts,tool_calls,timeline,process_text,tokens,sequence,create_time,deleted,blocks,turn_id,end_reason,tenant_id)
    VALUES ('${localId}-${i}','${localId}','${r.role}',${h(r.content)},${h(r.thinking)},${h(r.artifacts)},${h(r.tool_calls)},
      ${h(r.timeline)},${h(r.process_text)},${h(r.tokens)},${i + 1},'${r.create_time}',0,${h(r.blocks)},${h(r.turn_id)},${h(r.end_reason)},'default');`)
})
writeFileSync(`/tmp/${localId}.sql`, sql.join('\n'))
// 用文件描述符喂 stdin：200KB+ 的整段 SQL 走管道时 mysql 客户端会停在那里等退出（实测卡死）。
execFileSync('mysql', ['-uroot', '-h127.0.0.1', 'wenqu_ai', '--default-character-set=utf8mb4'], {
  stdio: [openSync(`/tmp/${localId}.sql`, 'r'), 'inherit', 'inherit'],
})
console.log(localId)