// @ts-check
// 成员页的「直播态」气泡（state 层）：把 /team/member/{sid}/live 的帧拼成一条可渲染的消息对象。
//
// 为什么不是直接复用主问答的 streamAnswer：那条函数的一整套副作用（建气泡、登记 chatStreams、
// 断线重挂、收尾落库）都绑在「这一页发起了这一轮」上。成员轮不是本页发起的——它是框架唤醒在后台
// 起跑的一轮，本页只是个观众。观众需要的只有三件事：把帧按主问答同一套回调拼成一条消息、
// 收尾时按历史重载、中途能按那张审批卡。
//
// 所以这里只借两样东西：帧回调名（CHAT_EVENT_CB，生成表）与拼装用的纯函数
// （applyToolStatus / 审批卡 / 检索 / 技能 / 产物…）——这两样都是主问答在用的同一份实现，
// 不另写一套「什么时候显示什么」。
import { reactive } from 'vue'
import {
  applyApprovalCardFrame, applyRetrievedFrame, applySkillHitFrame, applyUsageFrame,
  applyTodoFrame, applyAgentDelegated
} from './streamInfoFrames.js'
import { applyToolStatus } from './streamToolFrames.js'
import { applyRunCodeProgress } from './streamInfoFrames.js'
import { createStreamBuffer } from './streamBuffer.js'
import { attachAskCard } from './askReducer.js'
import { extendTimelineText, extendTimelineProcess, pushTimelineTool } from './timelineWrite.js'

/** 直播气泡的初始形状：与主问答 fresh 那份对齐（同一批字段名，渲染器不用分支） */
const freshLiveMsg = (sid, name) => reactive({
  role: 'ai', content: '', images: [], sources: [], related: [], degradations: [], warnMsg: '',
  loading: true, retrying: false, live: true, liveSessionId: sid, liveMemberName: name || '',
  thinking: '', thinkLoading: false, processText: '',
  time: Date.now(), turnClockFromServer: false, turnMs: 0, humanWaitMs: 0,
  artifacts: [], toolCalls: [], skillHits: [], plan: null, planCard: null, timeline: [],
  errorCard: null, model: '', goalWaiting: false, agentId: '', agentName: name || '',
  approvals: [], retrieved: null, tokensPreview: null, _turnOpen: true
})

/**
 * @param {object} ctx 注入项（含下面这些回调）
 * @param ctx.appendMessage (msg) => void   直播气泡进视图（未在列表里才追加）
 * @param ctx.reload        (sid, why) => void 收尾：按历史重载那一条会话（落库那份是权威）
 * @param ctx.onScroll      () => void      跟随滚动
 * @param ctx.setTodos      (sid, payload) => void
 */
export const createMemberLiveState = ctx => {
  let msg = null
  let buf = null
  let sid = ''

  const scroll = () => { try { ctx.onScroll() } catch (e) { /* 视图未就绪 */ } }

  const reset = (sessionId, memberName) => {
    sid = sessionId
    msg = freshLiveMsg(sessionId, memberName)
    buf = createStreamBuffer({
      msg,
      extendText: (m, from, to) => extendTimelineText(m, from, to),
      extendProcess: (m, from, to) => extendTimelineProcess(m, from, to),
      scroll,
      schedule: setTimeout,
      throttleMs: 120
    })
    return msg
  }

  /** 快照：这一轮在进页面之前已经跑出来的那一段，按积压顺序重放一遍 */
  /**
   * 快照到达：第一次进来时按这一条会话建直播态；之后只是确认「这一轮还没收尾」。
   * 积压帧已含全部 token，这里不补正文。
   *
   * @param {{done?: boolean, frames?: any[]}} snap frames 由接流层按顺序逐帧派发给 onFrame，这里不重复消费
   * @param sid2  这条直播对应的会话 id（接流层从订阅结果里给）
   */
  const applySnapshot = (snap, sid2) => {
    if (!msg) reset(sid2 || sid, '')
    if (!snap || snap.done) return
    msg.loading = true
    ctx.appendMessage(msg)
  }

  /**
   * 一帧：回调名与主问答同一张表。没认出来的帧静默跳过——
   * 直播是「看得见」，不是「解析全部」；真正完整的过程在收尾后的历史与账本里。
   */
  const onFrame = (cb, content) => {
    if (!msg) return
    try {
      switch (cb) {
        case 'onToken': buf.appendToken(content); msg.thinkLoading = false; scroll(); break
        case 'onProcess': buf.appendProcess(content); break
        // 思考：成员轮的帧与主问答同形；这里按纯文本累积，不写时间线思考段——
        // 直播态气泡只活到收尾，收尾后由历史那份带全量时间线的消息接管
        case 'onThinking': {
          // 思考帧是增量：先 flush 节流中的正文，避免思考落到正文之前（与主问答 appendThinking 同口径）
          buf.flushNow(false)
          msg.thinkLoading = true
          msg.thinking = (msg.thinking || '') + content
          scroll()
          break
        }
        case 'onThinkingDone': msg.thinkLoading = false; break
        case 'onToolStatus':
          applyToolStatus(msg, content, {
            thinkRoundEnd: () => { msg.thinkLoading = false },
            ensureTick: () => {},
            liveScroll: scroll,
            isDispatchTool: () => false,
            pushTimelineTool: (m, card) => pushTimelineTool(m, card),
            retireAskCard: () => {}
          })
          break
        case 'onApprovalRequired': applyApprovalCardFrame(msg, content, scroll); break
        // 提问卡：成员会向人发问，这条卡在成员页必须能就地作答——否则观众只能干看着它卡住。
        // 卡片挂在同一个直播态气泡上，作答面板复用主问答那份（pendingAsk 取的就是最近一条带 ask 的消息）。
        case 'onAskUser': {
          const j = parse(content)
          if (j) { attachAskCard(msg, j, j.timeoutMs); scroll() }
          break
        }
        case 'onRetrieved': applyRetrievedFrame(msg, content, scroll); break
        case 'onSkillHit': applySkillHitFrame(msg, content, scroll); break
        case 'onUsage': applyUsageFrame(msg, content); break
        case 'onTodo': applyTodoFrame(sid, content, ctx.setTodos); break
        case 'onAgentDelegated': applyAgentDelegated(msg, content, scroll); break
        case 'onRunCodeProgress': applyRunCodeProgress(msg, content); break
        case 'onWarn': msg.warnMsg = content; scroll(); break
        case 'onArtifact': {
          const a = parse(content)
          if (a && (a.name || a.f) ) msg.artifacts = [...(msg.artifacts || []), a]
          break
        }
        case 'onPlan': msg.plan = content; scroll(); break
        case 'onSteer': msg.warnMsg = content; scroll(); break
        case 'onCompactState': break
        default: break
      }
    } catch (e) {
      // 单帧渲染失败不拖死整条直播：这一帧丢了，后面照样收
      if (typeof console !== 'undefined') console.warn('[MEMBER-LIVE] 帧处理失败', cb, e && e.message)
    }
  }

  /** 收尾：气泡交给历史（落库那份是权威），本层状态清空 */
  const finish = (why) => {
    const done = msg
    msg = null
    buf = null
    if (done) { done.loading = false; done.thinkLoading = false }
    // 轮结束：重载那条会话，让落库的回答/工具卡/审批卡落到正式气泡上（直播态是过渡）
    if (sid && ctx.reload) ctx.reload(sid, why || 'live-done')
  }

  return {
    applySnapshot,
    onFrame,
    finish,
    /** 只读视图：当前直播态气泡（收到的帧都拼在它上面）。生产代码不读它——它们是给测试看的观测口。 */
    current: () => msg
  }
}

const parse = v => {
  if (v && typeof v === 'object') return v
  if (typeof v !== 'string') return null
  try { return JSON.parse(v) } catch (e) { return null }
}