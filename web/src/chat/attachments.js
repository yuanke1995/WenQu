// 附件重发凭据（fileId）的存活判定层。
//
// 为什么要有这一层：fileId 随消息落库、历史回放能把它带回来之后，「带得回号」和「文件还在」就成了
// 两件事——上传件按 chat.uploadRetentionHours（默认 24h）被周期清理。只有问过服务端才分得开这两种，
// 而分错的代价不对称：把还活着的说成失效，用户会对着一个能用的文件白重传一次；把已清理的说成活着，
// 发送时后端 fail-loud 报「附件不存在或已过期」，不会静默丢内容。
//
// 缓存按 fileId 记账、只增不清：fileId 是一次性凭据（每次上传都是新号），本页签内不会被复用。
import { checkChatAttachmentsLive } from '../api'

const live = new Map()        // fileId -> boolean
const inflight = new Map()    // 同批 fileId 的在途请求，避免并发重复问

/** 上传成功即算存活：不必再问自己刚送上来的文件（编辑卡打开时也就少了那一次多余请求） */
export const markLive = fileId => { if (fileId) live.set(fileId, true) }

/** 一批 fileId 中仍在盘上的那部分：命中缓存的直接答，未问过的合并成一次请求。
 *  请求失败按抛出交给调用方决定，这里不静默当成「还在」——那是把网络故障读成文件可用。 */
export async function probeLive (fileIds) {
  const ids = [...new Set((fileIds || []).filter(Boolean))]
  const ans = new Set()
  const unknown = []
  for (const id of ids) {
    if (live.has(id)) { if (live.get(id)) ans.add(id) } else if (!unknown.includes(id)) unknown.push(id)
  }
  for (let i = 0; i < unknown.length; i += 50) {
    const batch = unknown.slice(i, i + 50)
    const key = batch.join(',')
    let p = inflight.get(key)
    if (!p) {
      p = checkChatAttachmentsLive(batch)
        .then(r => new Set(((r && r.data && r.data.live) || []).filter(Boolean)))
        .finally(() => inflight.delete(key))
      inflight.set(key, p)
    }
    const got = await p
    for (const id of batch) {
      live.set(id, got.has(id))
      if (got.has(id)) ans.add(id)
    }
  }
  return ans
}

/** 一组附件（{name,mime,size,fileId}）按存活拆成 {ok,dead}。
 *  没有 fileId 的一律算 dead：那条消息是在凭据开始落库之前发的，当时就没记号，本来也带不走。 */
export async function splitAtts (atts) {
  const list = Array.isArray(atts) ? atts : []
  const withId = list.filter(a => a && a.fileId)
  const ok = withId.length ? await probeLive(withId.map(a => a.fileId)) : new Set()
  const out = { ok: [], dead: [] }
  for (const a of list) (a && a.fileId && ok.has(a.fileId) ? out.ok : out.dead).push(a)
  return out
}
