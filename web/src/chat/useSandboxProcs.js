// @ts-check
// 会话后台进程（两端同吃这一份）：工作区会话跑在本机执行器，普通会话跑在服务器沙盒，
// 同一条会话两边都可能背着进程，所以合成一张清单、不按来源拆卡。
// 真源各自在源头（执行器 ps / 沙盒 /proc），服务端不留台账——重启后仍照得到实况。
// 从 useChatEngine 抽出，依赖通过入参注入；生命周期钩子留在主文件（与其它逻辑共用 onUnmounted）。
import { ref, computed, watch } from 'vue'
import { Modal } from 'ant-design-vue'

export const useSandboxProcs = ({ currentSessionId, loading, messages,
                                  notify, listApi, killApi, killAllApi, refreshBadges }) => {

  // ==================== 会话后台进程（两端同吃这一份） ====================
  // 助手「把写好的前后端跑起来自己看效果」时起的是 npm run dev 这类**不会自己退**的进程。
  // 两个来源合成一张清单：工作区会话跑在本机执行器（进程在你电脑上），会话栏会话跑在服务器沙盒；
  // 同一条会话在执行器离线的那几轮会退回沙盒，两边都可能背着进程，所以这里不按来源拆两张卡。
  // 真源各自在源头：执行器读自己的进程登记 + ps 实况，沙盒读容器 /proc，服务端不留台账——
  // 执行器重启、后端重启都照得到；进程自己退了/被回收，清单自然清空。
  const procs = ref([])
  /** 来源状态：local/sandbox 各自 {applicable,available,reason,message}。读不到与没有进程是两件事 */
  const procSides = ref({})
  const procErr = ref('')
  const procLoading = ref(false)
  /** 本会话点过回答里的「在沙盒中运行」：那条路没有工具调用记录，光靠 sessionSandboxActive 判不出用过沙盒 */
  const sandboxRanOnce = ref(false)
  /** 能起进程的工具：execute（本机/沙盒命令）与 run_code（沙盒脚本）。
   *  只读写文件的会话没得扫，别为它们去问执行器（那会让「执行器离线」在纯读文件的会话上白弹一句）。 */
  const PROC_TOOL_NAMES = new Set(['execute', 'run_code'])
  const sessionProcActive = computed(() => messages.value.some(m => m.role !== 'user'
    && Array.isArray(m.toolCalls)
    && m.toolCalls.some(t => t && t.status === 'done' && PROC_TOOL_NAMES.has(t.name))))
  /** 要不要扫：本会话跑过命令/脚本（或点过回答里的「在沙盒中运行」）。没用过就没得扫。
   *  这条与「卡要不要露脸」必须分开——判据里若掺进 procs 是否为空，空清单会让这里短路，
   *  于是下一次扫永远发不出去，起了真进程也再也不会显示。 */
  const procScanActive = computed(() => sessionProcActive.value || sandboxRanOnce.value)
  /** 来源给的提醒（离线/版本旧/读取失败）：卡上要看得见，绝不静默成「没有进程」 */
  const procNotices = computed(() => {
    const out = []
    const sides = procSides.value || {}
    for (const side of ['local', 'sandbox']) {
      const s = sides[side]
      if (!s || !s.message) continue
      if (s.available && s.reason !== 'executor-too-old') continue
      out.push({ side, reason: s.reason || 'error', message: s.message })
    }
    return out
  })
  /** 卡只在<b>扫到了东西</b>或<b>有提醒/失败</b>时露脸：一条都没扫到就整张不显示。
   *  常驻一句「后台进程 · 没有」等于把「我查过了，没事」当成用户要看的东西，
   *  而这在用过命令的会话里是绝大多数情况。读不到 ≠ 没有，所以那种情形照显示，且给重试。 */
    const procCardVisible = computed(() => procScanActive.value
    && (procs.value.length > 0 || procNotices.value.length > 0 || !!procErr.value))
  /** 正在停的那一棵：只让这一行的按钮变灰变字，别把整张卡按成 loading（用户还在看其余几行） */
  const killingTag = ref('')
  /** 全部停止进行中：整卡按钮置灰，避免连点 */
  const killingAll = ref(false)
  /** 秒针（1s）：「跑了多久」按 startedAt 现算，不是每次重扫——不然数字停在「17 秒」看着像整张卡死了 */
  const nowTick = ref(Date.now())
  async function loadSandboxProcs () {
    const sid = currentSessionId.value
    if (!sid || !procScanActive.value) {
      procs.value = []
      procSides.value = {}
      procErr.value = ''
      procLoading.value = false
      return
    }
    procLoading.value = true
    procErr.value = ''
    try {
      const r = await listApi(sid)
      if (currentSessionId.value !== sid || !procScanActive.value) return
      procs.value = (r && r.data && r.data.procs) || []
      procSides.value = (r && r.data && r.data.sides) || {}
    } catch (e) {
      if (currentSessionId.value !== sid || !procScanActive.value) return
      // 「读不到进程表」和「没有后台进程」是两件事，静默成空列表会把一个正占着端口的服务读成不存在
      procs.value = []
      procSides.value = {}
      procErr.value = e?.message || '读不到会话里的进程'
    } finally {
      if (currentSessionId.value === sid) {
        procLoading.value = false
        ensureProcTimer()   // 有在跑的就挂秒针，全停完了就把定时器摘掉
      }
    }
  }
  /** 跑了多久：这一栏答的是「忘了关的那个跑了多久」，分钟以上就不必再报秒 */
  function procAge (p) {
    const s = p.startedAt ? Math.max(0, Math.floor((nowTick.value - p.startedAt) / 1000)) : (Number(p.elapsedSec) || 0)
    if (s < 60) return `${s} 秒`
    const m = Math.floor(s / 60)
    if (m < 60) return `${m} 分钟`
    const h = Math.floor(m / 60)
    return `${h} 小时 ${m % 60} 分`
  }
  /** 秒针（1s）与实况重扫（30s）共用一个定时器：只在卡上有进程时挂着，空卡不白问执行器 */
  let procTimer = null
  function ensureProcTimer () {
    const need = procs.value.length > 0
    if (!need && procTimer) { clearInterval(procTimer); procTimer = null; return }
    if (need && !procTimer) {
      let n = 0
      procTimer = setInterval(() => {
        nowTick.value = Date.now()
        // 定时重扫：进程在别处被杀掉、或自己退出了，这一行要自己消失，而不是挂着个「在跑」骗人
        if (++n % 30 === 0 && !killingTag.value && !killingAll.value && !procLoading.value) loadSandboxProcs()
      }, 1000)
    }
  }
  async function killProc (p) {
    const sid = currentSessionId.value
    if (!sid || killingTag.value || killingAll.value) return
    killingTag.value = p.tag
    try {
      const r = await killApi(sid, p.side || 'sandbox', p.tag)
      const killed = Number(r?.data?.killed) || 0
      const remaining = Number(r?.data?.remaining) || 0
      // 三种结果分开说：停掉了几个 / 有停不下来的 / 它本来就自己退了——最后那种不能报「已停止」，
      // 用户会以为是自己这一刀救回来的
      if (remaining > 0) notify.error(`有 ${remaining} 个进程停不下来（可能卡在不可中断的读写上），可再刷新确认`)
      else if (killed > 0) notify.success(`已停掉 ${killed} 个进程，占用的端口随之释放`)
      else notify.info('这个进程已经自己退出了')
    } catch (e) {
      notify.error(e?.message || '停止失败')
    } finally {
      killingTag.value = ''
      await loadSandboxProcs()
      // 停完顺手喊侧栏重扫：那一行的「还背着进程」不该再等下一轮轮询才灭
      refreshBadges()
    }
  }
  /** 停掉本会话起的全部进程树（按当前清单里出现过的来源分别停） */
  async function killAllProcs () {
    const sid = currentSessionId.value
    if (!sid || killingTag.value || killingAll.value) return
    const sides = [...new Set(procs.value.map(p => p.side || 'sandbox'))]
    if (!sides.length) return
    const total = procs.value.length
    Modal.confirm({
      title: '停止本会话全部后台进程？',
      content: `会停掉 ${total} 棵进程树并释放端口${sides.includes('local') ? '（本机执行器上的进程）' : ''}`
        + `${sides.includes('sandbox') && sides.includes('local') ? '、' : ''}${sides.includes('sandbox') ? '（沙盒里只有本会话起的）' : ''}。`,
      okText: '全部停止',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        killingAll.value = true
        let killed = 0
        let remaining = 0
        let failed = 0
        for (const side of sides) {
          try {
            const r = await killAllApi(sid, side)
            killed += Number(r?.data?.killed) || 0
            remaining += Number(r?.data?.remaining) || 0
          } catch {
            failed++
          }
        }
        killingAll.value = false
        if (failed && !killed && !remaining) notify.error('全部停止失败，可刷新后重试')
        else if (remaining > 0) notify.error(`已停 ${killed} 个，还有 ${remaining} 个停不下来`)
        else if (killed > 0) notify.success(`已停掉 ${killed} 个进程，占用的端口随之释放`)
        else notify.info('这些进程已经自己退出了')
        await loadSandboxProcs()
        refreshBadges()
      }
    })
  }
  // 重扫时机与沙盒文件同一批：一轮跑完（占端口的服务恰恰是在这一轮里起的）、判据翻转、切会话。
  // 「点过运行按钮」是这一会话的事，切走就作废。
  watch(() => loading.value, (v, prev) => { if (prev && !v) { loadSandboxProcs(); refreshBadges() } })
  watch(sessionProcActive, () => { loadSandboxProcs() })
  watch(currentSessionId, () => { sandboxRanOnce.value = false; loadSandboxProcs() })

  /** 组件卸载时清掉进程轮询定时器（主文件的 onUnmounted 调用） */
  const stopProcTimer = () => { if (procTimer) { clearInterval(procTimer); procTimer = null } }

  return {
    procs, procSides, procNotices, procErr, procLoading, procCardVisible, procScanActive,
    killingTag, killingAll, procAge, sandboxRanOnce, sessionProcActive,
    loadSandboxProcs, killProc, killAllProcs, stopProcTimer,
  }
}
