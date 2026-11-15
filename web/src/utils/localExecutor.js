/**
 * 「这台电脑」是哪台执行器。
 *
 * 工作区绑在某一台执行器上，但同一个账号可以在多台电脑上登录：服务端按账号返回全部工作区，
 * 页面得自己知道当前这台电脑的设备 id，才能只列它名下的工作区、不把别的电脑的目录当本机用。
 *
 * 一台电脑可以绑多个账号（同一个执行器进程持有多个令牌），所以设备 id 必须按账号分存：
 * 键是「全局键 + 当前登录 uid」。用旧版单一全局键会让换账号登录后带着上一个人的设备 id。
 *
 * 本地开发态由 dev-with-agent.mjs 从 ~/.wenqu/executor.json 读出并按 uid 注入 VITE_LOCAL_EXECUTORS；
 * 线上页面没有那份令牌文件，在工作区栏的「这台电脑」里选一次，之后一直记在这个浏览器里。
 */
import { uidSync } from './auth'

const KEY = 'wenqu.localExecutorId'

const keyFor = uid => (uid ? `${KEY}.${uid}` : KEY)

function injected (uid) {
  if (uid) {
    const raw = import.meta.env.VITE_LOCAL_EXECUTORS
    if (raw) {
      try {
        const map = JSON.parse(raw)
        if (map && map[uid]) return String(map[uid])
      } catch (e) { /* 环境变量写坏时退回单账号口径 */ }
    }
  }
  return import.meta.env.VITE_LOCAL_EXECUTOR_ID || ''
}

export function localExecutorId () {
  const uid = uidSync()
  try {
    const saved = localStorage.getItem(keyFor(uid))
    if (saved) return saved
    // 旧版本的全局键只迁给升级后第一个读到它的账号，迁完即删：
    // 否则设备被撤销清空后，这里会把它重新捡回来，和「重新选一次」来回打架。
    const legacy = uid ? localStorage.getItem(KEY) : ''
    if (legacy) {
      localStorage.setItem(keyFor(uid), legacy)
      localStorage.removeItem(KEY)
      return legacy
    }
  } catch (e) { /* 隐私模式：退回启动脚本注入的默认值 */ }
  return injected(uid)
}

export function setLocalExecutorId (id) {
  const uid = uidSync()
  try {
    if (id) localStorage.setItem(keyFor(uid), id)
    else localStorage.removeItem(keyFor(uid))
  } catch (e) { /* 隐私模式：不阻断切换，只是下次打开要重新选 */ }
}

/** 工作区是否属于这台电脑。没绑执行器的老工作区不读写任何电脑的文件，一律保留。 */
export function projectOnThisComputer (p) {
  const id = localExecutorId()
  if (!id || !p) return true
  return !p.isLocal || !p.executorId || p.executorId === id
}
