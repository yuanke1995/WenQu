/**
 * 回答流的三件共享合并语义：起表基准（turn_start）、配图全量替换（image）、终态载荷（done）。
 * <p>主聊天页与分享页此前各写一份，且已经分叉：分享页的 image 帧还拿着「非法帧清空」的旧语义
 * （主页面早已改成不动状态），done 的用时也没有机器耗时回退——同一条流在两块屏幕上对不出同一份账。
 * 本文件是这三件的唯一实现；页面只保留各自特有的支路（时间线重建、blocks、toolCalls 合并、
 * 计划卡、插话结算、评价与游客过滤），那些不在这条线上收敛。
 * <p>刻意零依赖：合并语义要能被 node --test 直接钉成行为表（web/test/streamMerge.test.mjs），
 * 不因为一个用时回退把整个 projections 拖进测试链路。
 */

/** 收尾那一刻写回气泡的「机器时间」那一份：让 turnMs + 等人时间 等于本地钟量到的整轮墙钟。
 *  <b>只给旧服务端用</b>（done 不带 turnWallMs）：那时服务端只有机器耗时，照抄会让跳着的数字
 *  在答完那一刻变小；新服务端一律以 turnWallMs 为准，不再与本地钟对账。
 *  没有本地起表点（历史消息）时原样返回服务端那份，不拿库里的创建时刻凑。 */
const settledTurnMs = (serverMs, waitMs, startedAt, settledAt) => {
  const machine = Math.max(0, Number(serverMs) || 0)
  const wait = Math.max(0, Number(waitMs) || 0)
  const a = Number(startedAt) || 0
  const b = Number(settledAt) || 0
  const wall = a > 0 && b > a ? b - a : 0
  return Math.max(machine, wall - wait)
}

/**
 * turn_start：把秒表锚到服务端收到提问那一刻（时长形式下发，绝对时刻不进两端钟差）。
 * 基准只认第一份（外壳那帧最先到）：引擎开轮后的第二帧同一基准、只是 prepMs 更准，
 * 按它重锚会把这一帧在路上的时间从读数里扣掉，秒表往回跳。
 * @param alignTick 秒针相位对齐回调（两页各有一份实现，形状相同），没有就跳过对齐
 */
export function applyTurnStart (msg, payload, alignTick) {
  let p = null
  try { p = typeof payload === 'string' ? JSON.parse(payload) : payload } catch (e) { return }
  if (!p || typeof p.wallMs !== 'number') return
  if (!msg.turnClockFromServer) {
    msg.time = Date.now() - p.wallMs
    msg.turnClockFromServer = true
    if (typeof alignTick === 'function') alignTick(msg.time)
  }
  if (typeof p.prepMs === 'number') msg.turnPrepMs = p.prepMs
}

/**
 * image 帧：配图清单只有「同源全量替换」一种变更方式——后端 image 帧恒发本轮全量数组。
 * 解析失败或形状不对时保持已有清单，不用一份可疑数据把已下发的图清空。
 */
export function applyImageFrame (msg, payload) {
  try {
    const parsed = typeof payload === 'string' ? JSON.parse(payload) : payload
    if (Array.isArray(parsed)) msg.images = parsed
  } catch (e) { /* 非法帧不动状态：已下发的图不因解析失败而消失 */ }
}

/**
 * done 终态载荷的公共字段合并：正文/配图/来源/消息 ID/模型/思考/整轮用时。
 * 返回「定稿正文相对流式全文是否被改写过」——优先吃后端标志（BodyReconcile 一处裁决随 done
 * 宣告），旧服务端没有该键才回退现场比对；调用方据此决定时间线 text 段是否失效。
 * 页面特有支路（timeline 重建、blocks、toolCalls 原地合并、plan/tokens/steers）不在这里。
 */
export function applyDoneCore (msg, p) {
  const bodyRewritten = typeof p.bodyRewritten === 'boolean'
    ? p.bodyRewritten
    : (typeof p.finalContent === 'string' && p.finalContent !== '' && p.finalContent !== msg.content)
  // 最终正文与流式累积不一致（引用自检重建/related 清理等改写了正文）：以 done 下发的落库版为准，
  // 否则正文里的 [N]/[图片N] 与权威清单的下标会错位
  if (typeof p.finalContent === 'string' && p.finalContent !== '' && p.finalContent !== msg.content) {
    msg.content = p.finalContent
  }
  // 权威清单与流式 image 帧、落库同源（定稿不改写配图清单），只做一次对齐
  if (Array.isArray(p.finalImages)) msg.images = p.finalImages
  if (Array.isArray(p.sources)) msg.sources = p.sources
  if (p.messageId) msg.messageId = p.messageId
  // 生效模型以后端权威解析为准（请求未带覆盖时后端回落个人默认，前端预填值在此校正）
  if (typeof p.model === 'string' && p.model) msg.model = p.model
  if (p.thinking) msg.thinking = p.thinking
  // 整轮用时只认服务端墙钟（从收到提问起表，含开轮前准备与人在回路），与落库 tokens.turnWallMs
  // 同一个数，刷新前后必须一模一样；不与本地钟取大（多出的只是一次网络往返），
  // 服务端没有这个键（旧版）时退回「机器耗时 + 等人」口径
  if (typeof p.humanWaitMs === 'number') msg.humanWaitMs = p.humanWaitMs
  if (typeof p.prepMs === 'number') msg.turnPrepMs = p.prepMs
  if (typeof p.turnWallMs === 'number') {
    msg.turnMs = p.turnWallMs
  } else if (typeof p.elapsedMs === 'number') {
    msg.turnMs = settledTurnMs(p.elapsedMs, msg.humanWaitMs, msg.time, Date.now())
  }
  return bodyRewritten
}
