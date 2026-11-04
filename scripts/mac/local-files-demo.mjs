#!/usr/bin/env node
/**
 * 本机文件挂载（沙盒 local_files）自检与预挂工具。
 *
 *   node scripts/mac/local-files-demo.mjs check            四条断言，不依赖模型，30 秒内出结果
 *   node scripts/mac/local-files-demo.mjs mount <会话id>    给某个会话预挂本机目录，之后直接在界面里问
 *
 * 前提：deploy/sandbox-provisioner/app.py 的补丁已在 provisioner 进程里生效，
 * 且 user-data/host-read-allow.txt（或 env SANDBOX_HOST_READ_ALLOW）里列了允许挂的宿主目录。
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'

const PROV = process.env.PROV_URL || 'http://127.0.0.1:8002'
const TOKEN = process.env.PROV_TOKEN || 'wenqu-local-dev-sandbox-provisioner-token'
const HOST_DIR = process.env.LOCAL_DEMO_DIR || `${os.homedir()}/wenqu-local-demo`
const ROOT = '/home/gem/user-data'
const LOCAL = `${ROOT}/local_files/${HOST_DIR.split('/').filter(Boolean).pop()}`

const call = async (method, url, body) => {
  const init = { method, headers: { Authorization: 'Bearer ' + TOKEN } }
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const r = await fetch(url, init)
  const text = await r.text()
  let parsed
  try { parsed = JSON.parse(text) } catch { parsed = text }
  return { status: r.status, body: parsed }
}

const sandboxCreate = (id, thread, uid, dirs) =>
  call('POST', `${PROV}/api/sandboxes`, {
    sandbox_id: id, thread_id: thread, uid, env: {}, inherit_env: true, host_read_dirs: dirs
  })

const read = (proxy, file) =>
  call('POST', `${proxy}/v1/file/read`, { file }).then(r => r.body?.data?.content ?? `«${JSON.stringify(r.body).slice(0, 200)}»`)

const exec = (proxy, command) =>
  call('POST', `${proxy}/v1/shell/exec`, { command }).then(r => r.body?.data?.output ?? JSON.stringify(r.body).slice(0, 200))

const liveCheckFile = `${HOST_DIR}/live-check.txt`

async function check () {
  const results = []
  const say = (name, ok, detail) => {
    results.push(ok)
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  → ' + detail : ''}`)
  }
  const id = crypto.createHash('sha256').update(`cap-probe:live-check-1`).digest('hex').slice(0, 12)

  fs.mkdirSync(HOST_DIR, { recursive: true })
  fs.writeFileSync(liveCheckFile, `宿主写入的校验码 A：LMN-${Date.now()}\n`)

  const created = await sandboxCreate(id, 'live-check-1', 'cap-probe', [HOST_DIR])
  if (created.status !== 200) {
    console.log('FAIL  建沙盒就失败了：', created.status, JSON.stringify(created.body).slice(0, 300))
    console.log('\n先确认 provisioner 已加载补丁（docker exec wenqu-sandbox-provisioner grep host_read_dirs /app/app.py）与白名单文件。')
    process.exit(1)
  }
  const proxy = created.body.sandbox_url
  console.log(`沙盒 ${id} 就绪\n`)

  // 断言 1：沙盒读得到宿主文件
  const first = await read(proxy, `${LOCAL}/live-check.txt`)
  say('1 沙盒能读到本机文件', first.includes('LMN-'), first.trim().slice(0, 60))

  // 断言 2：宿主改动即时可见 —— 这条才证明挂的是本机目录，不是拷了一份进沙盒
  const markerB = `LMN-B-${Date.now()}`
  fs.writeFileSync(liveCheckFile, `宿主改过之后：${markerB}\n`)
  const second = await read(proxy, `${LOCAL}/live-check.txt`)
  say('2 宿主改完沙盒立刻可见（非拷贝）', second.includes(markerB), second.trim().slice(0, 60))

  // 断言 3：沙盒写不进来（文件系统层挡，与权限档位无关）
  const wrote = await exec(proxy, `echo hack >> ${LOCAL}/live-check.txt 2>&1; head -c 40 ${LOCAL}/live-check.txt`)
  const stillClean = !fs.readFileSync(liveCheckFile, 'utf8').includes('hack')
  say('3 沙盒侧写入被拒且宿主文件未变', wrote.includes('Read-only file system') && stillClean, wrote.trim().slice(0, 70))

  // 断言 4：白名单外的宿主目录挂不进来
  const denied = await sandboxCreate(id, 'live-check-1', 'cap-probe', [`${os.homedir()}/.ssh`])
  say('4 白名单外路径被拒（400）', denied.status === 400, String(denied.body?.detail ?? denied.body).slice(0, 80))

  // 断言 5：不传该参数时行为不变（复用同一容器，且没有 local_files）
  const plain = await sandboxCreate(id, 'live-check-1', 'cap-probe', [])
  const listed = await call('POST', `${proxy}/v1/file/list`, { path: ROOT })
  const names = (listed.body?.data?.files || []).map(f => f.name)
  say('5 不带参数复用容器不报错', plain.status === 200 && plain.body?.sandbox_id === id, `根目录列出 ${names.join(',')}`)

  await call('DELETE', `${PROV}/api/sandboxes/${id}?expected_generation=${created.body.generation}`)
  fs.rmSync(liveCheckFile, { force: true })
  console.log(`\n探针沙盒已删，宿主临时文件已清。${results.every(Boolean) ? '五条全绿。' : '有红项，别当它通了。'}`)
}

async function mount (sessionId, uid = 'admin') {
  const id = crypto.createHash('sha256').update(`${uid}:${sessionId}`).digest('hex').slice(0, 12)
  const r = await sandboxCreate(id, sessionId, uid, [HOST_DIR])
  if (r.status !== 200) {
    console.log('预挂失败：', r.status, JSON.stringify(r.body).slice(0, 400))
    console.log('常见原因：该会话挂在「工作区」上（workdir 不同 → 身份不符），或白名单没放行。')
    process.exit(1)
  }
  console.log(`会话 ${sessionId}\n沙盒 ${id} → 已挂 ${HOST_DIR} 到 ${LOCAL}（只读）\n`)
  console.log('现在去界面里打开这个会话，让它 ls 那个目录并读文件。')
  console.log('注意：沙盒空闲 60 分钟会被回收，回收后重建就不带这个挂载了（后端还不认识这个参数）。')
}

const [cmd, arg, arg2] = process.argv.slice(2)
if (cmd === 'check') await check()
else if (cmd === 'mount') await mount(arg, arg2)
else console.log('用法：node scripts/mac/local-files-demo.mjs check | mount <会话id> [uid]')
