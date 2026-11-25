// 目标模式入口（从 useChatEngine 抽出）：startGoal（建行即开跑）与 goalAct（目标动作）。
// 依赖经入参注入；goalBarVisible 与 goalAct 互相引用，两侧都以惰性 getter/箭头传入，
// 调用时机在装配完成之后，不触发 TDZ。
import { reactive } from 'vue'
import { goalAct as goalActImpl } from './goalActions.js'
import { getSessionGoalApi } from './chatTransport.js'

export const useGoalEntry = ({
  currentSessionId, messages, hooks, currentAgentId, sessionGoal, goalsMap, setGoal,
  goalRunning, goalBarVisible, watchGoalTurn, startGoalApi, goalActionApi, markSessionActive, notify,
}) => {
  // ==================== 目标模式（Goal Mode） ====================
  // 对齐 DSH：用户写一句目标，系统自己跨轮往下推进，做完了就停——没有验收标准、没有人工确认、
  // 没有逐条核验与倒计时。用户在目标条上只能做三件事：改目标原文、停止/继续、删除。
  // 目标是会话级的一份（横跨多轮），不是某一轮的东西；终态记录进右栏面板，条子只在
  // RUNNING/PAUSED 两档露出（做完了/删掉了都不是「还等你处理」）。

  /**
   * 目标入口（工具条按钮 /goal 命令共用这一出口）：建行即开跑。
   * 目标原文就是模型这一侧唯一要看到的东西——不传验收标准、不传启动策略。
   */
  async function startGoal (objective) {
    const sid = currentSessionId.value
    if (!sid) return null
    const obj = String(objective || '').trim()
    if (!obj) { notify.warning('先写一句这件事要做到什么'); return null }
    // 用户这句话先落地再发包：建目标是一次同步重活（建行 + 起首轮，冷启动要几秒），
    // 等 POST 回来再 push 的话，输入框已被 startGoalNow 清空、聊天区空空如也，用户以为这句话
    // 根本没发出去。与普通发送同口径（send() 也是先 push 气泡再开流）：goalCommand 等 goalId
    // 回来补上，失败再把这条 optimistic 气泡撤掉（startGoalNow 会把原文还回输入框）。
    // goalPending 补的是那几秒的空窗：两壳的身份胶囊与「撤动作键」都按 goalCommand 判，
    // 只等 goalId 的话这条回声在响应回来前长得跟普通提问一模一样，编辑重发能把目标指令重跑一轮。
    const echo = reactive({ role: 'user', content: obj, goalCommand: '', goalPending: true,
                            images: [], attachments: [], attachData: [],
                            skills: [], mentions: [], historyRefs: [], deepThink: false,
                            time: Date.now(), messageId: null })
    messages.value.push(echo)
    // 与普通提问同一落点：补尾随留白再贴底（=本轮问题置顶）。这里不能用 scrollSoft——它只跟着
    // 「还在贴底」走，用户上翻过就什么都不做，目标这句话既不留白也不归位，要等助手气泡接上才跳
    hooks.scrollForce?.()
    // 失败摘除：只发生在「服务端没写库」与「写库了但响应丢了」两种情形，后者由 catch 里的
    // 回读兜底先判掉，剩下的确实是没建成——气泡撤掉后界面回到发送前的样子
    const dropEcho = () => {
      const i = messages.value.indexOf(echo)
      if (i >= 0) messages.value.splice(i, 1)
    }
    try {
      const r = await startGoalApi(sid, obj, currentAgentId.value || '')
      if (r && r.success === false) { dropEcho(); notify.warning(r.msg || '目标创建失败'); return null }
      if (r && r.data) {
        setGoal(sid, { ...r.data })
        // 建目标即开跑：首轮由调度器起（一拍 2 秒）+ 实例冷启动装配，POST 回来的这一刻总线上
        // 通常还探不到。走 watchGoalTurn 而不是直接 attachRunningTurn：先挂占位气泡（「正在溯源」
        // 当场出现），探测到那一轮登记上总线再接流；接不上由 2.5s 轮询继续探（同 attachRunningTurn
        // 那条路径），不许让聊天区在那几秒里空着。
        if (goalRunning.value) watchGoalTurn(sid)
        // 对话里那一行回声服务端同一条已落库（echoGoalCommand），刷新/切会话读的是库里那份；
        // 这里只补命令标注。目标横跨多轮，状态在摘要条与详情面，但「什么时候定了什么目标」
        // 得能在对话里读出来
        echo.goalCommand = r.data.goalId || ''
        echo.goalPending = !echo.goalCommand
        // 侧栏要立刻看得见这条会话：目标会话在第一条回答落地之前 message_count 一直是 0，而会话列表
        // 隐藏空会话——用户在工作区里定了目标，回头看那个工作区读到的是「暂无会话」。
        // 标题用目标原文：这条会话讲的就是这件事，比「新对话」认得出来。
        markSessionActive(sid, obj)
        return r.data
      }
      dropEcho()
      return null
    } catch (e) {
      // 响应丢了不等于没建成：超时/5xx 时服务端可能已经写库并起了首轮（kickContinuation 抛在
      // 提交之后就是这种）。这时候撤气泡 + 还回原文，用户会重发一遍，把刚建好的目标重开一遍。
      const created = await recoverCreatedGoal(sid, obj, echo)
      if (created) return created
      dropEcho()
      notify.error(e.message || '目标创建失败')
      return null
    }
  }

  /**
   * 提交失败后的回读确认：按 objective + 创建时间（2 分钟内）认领刚建成的目标。
   * 认到就按成功结算（补 goalCommand、让侧栏立刻可见），否则返回 null 交由调用方撤气泡报错。
   */
  async function recoverCreatedGoal (sid, obj, echo) {
    const g = await getSessionGoalApi(sid).catch(() => null)
    const created = g && g.success && g.data && g.data.goalId
      && String(g.data.objective || '').trim() === obj
      && Date.now() - new Date(g.data.createdAt).getTime() < 120000
    if (!created) return null
    setGoal(sid, { ...g.data })
    echo.goalCommand = g.data.goalId || ''
    echo.goalPending = !echo.goalCommand
    markSessionActive(sid, obj)
    notify.warning('响应中断，但已从服务端确认目标创建成功')
    return g.data
  }

  // 目标动作在 ./goalActions.js（actions 层），依赖经 ctx 注入。
  const goalAct = (action, extra) => goalActImpl(action, extra, {
    goal: () => sessionGoal.value, goalOf: sid => goalsMap.value[sid], setGoal,
    currentSid: () => currentSessionId.value, action: goalActionApi,
    watchGoalTurn, barVisible: () => goalBarVisible.value, notify
  })

  return { startGoal, goalAct }
}
