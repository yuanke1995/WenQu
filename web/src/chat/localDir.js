// 本机目录句柄的持久化（File System Access API）。
//
// 为什么单独一层：目录句柄可以存进 IndexedDB，刷新后取回来接着用；但"存得住"不等于"有权限"——
// 浏览器随时可能要求重新授权，而重新授权必须由用户手势触发。所以这里只负责存取与查权限，
// 要不要弹授权由调用方在点击事件里决定。

const DB_NAME = 'wenqu-local-dirs'
const STORE = 'dirs'

const openDb = () => new Promise((resolve, reject) => {
  if (!globalThis.indexedDB) return reject(new Error('当前浏览器不支持本机目录'))
  const req = indexedDB.open(DB_NAME, 1)
  req.onupgradeneeded = () => {
    if (!req.result.objectStoreNames.contains(STORE)) req.result.createObjectStore(STORE)
  }
  req.onsuccess = () => resolve(req.result)
  req.onerror = () => reject(req.error)
})

/** 按工作区 id 保存目录句柄（每个工作区一个目录） */
export const saveLocalDirHandle = async (workspaceId, handle) => {
  if (!workspaceId || !handle) return
  const db = await openDb()
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      tx.objectStore(STORE).put(handle, workspaceId)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } finally {
    db.close()
  }
}

export const loadLocalDirHandle = async workspaceId => {
  if (!workspaceId) return null
  let db
  try {
    db = await openDb()
  } catch (e) {
    return null
  }
  try {
    return await new Promise((resolve, reject) => {
      const req = db.transaction(STORE, 'readonly').objectStore(STORE).get(workspaceId)
      req.onsuccess = () => resolve(req.result || null)
      req.onerror = () => reject(req.error)
    })
  } catch (e) {
    return null
  } finally {
    db.close()
  }
}

/** 'granted' | 'prompt' | 'denied' | 'unsupported' */
export const queryDirPermission = async handle => {
  if (!handle || typeof handle.queryPermission !== 'function') return 'unsupported'
  try {
    return await handle.queryPermission({ mode: 'readwrite' })
  } catch (e) {
    return 'unsupported'
  }
}

/** 必须在用户点击的同一个事件里调用，否则浏览器直接拒（返回 'prompt' 但不再弹窗） */
export const requestDirPermission = async handle => {
  if (!handle || typeof handle.requestPermission !== 'function') return 'unsupported'
  try {
    return await handle.requestPermission({ mode: 'readwrite' })
  } catch (e) {
    return 'denied'
  }
}
