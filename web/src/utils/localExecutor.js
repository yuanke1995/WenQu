/**
 * 「这台电脑」是哪台执行器。
 *
 * 工作区绑在某一台执行器上，但同一个账号可以在多台电脑上登录：服务端按账号返回全部工作区，
 * 页面得自己知道当前这台电脑的设备 id，才能只列它名下的工作区、不把别的电脑的目录当本机用。
 *
 * 本地开发态由 dev-with-agent.mjs 从 ~/.wenqu/executor.json 读出并注入 VITE_LOCAL_EXECUTOR_ID；
 * 线上页面没有那份令牌文件，在工作区栏的「这台电脑」里选一次，之后一直记在这个浏览器里。
 */
const KEY = 'wenqu.localExecutorId'

export function localExecutorId () {
  try {
    const saved = localStorage.getItem(KEY)
    if (saved) return saved
  } catch (e) { /* 隐私模式：退回启动脚本注入的默认值 */ }
  return import.meta.env.VITE_LOCAL_EXECUTOR_ID || ''
}

export function setLocalExecutorId (id) {
  try {
    if (id) localStorage.setItem(KEY, id)
    else localStorage.removeItem(KEY)
  } catch (e) { /* 隐私模式：不阻断切换，只是下次打开要重新选 */ }
}

/** 工作区是否属于这台电脑。没绑执行器的老工作区不读写任何电脑的文件，一律保留。 */
export function projectOnThisComputer (p) {
  const id = localExecutorId()
  if (!id || !p) return true
  return !p.isLocal || !p.executorId || p.executorId === id
}
