// 工具终态合并：done 汇总并进实时工具卡，保住对象引用与到达顺序，避免同一件事算两遍。

/** done 汇总的工具终态合并进现有数组（原地改，保住 timeline 里的对象引用与实时到达的顺序） */
export const mergeDoneToolCalls = (m, doneCalls) => {
  if (!m) return
  if (!Array.isArray(m.toolCalls)) m.toolCalls = []
  const list = m.toolCalls
  for (const d of doneCalls) {
    // 先找还挂在「进行中」的那一张：正常流是 start 帧建卡、终态帧就地改状态，done 这份只补全文。
    const live = [...list].reverse().find(x => x.name === d.name && x.status === 'start')
      // 找不到就认「同名、已终态、且这一份还没认领过」的那一张——实时帧早就把这张卡改成 done 了，
      // done 这份该把全文并到它身上。少了这一路，落库那份会被当成一次新调用 push 进去，同一件事
      // 在界面上算两遍：「执行了 N 个操作」和总览那半步的步数当场翻倍。
      // 正向扫：两份都是到达顺序，倒着配会把重复同名调用的入参/输出串到别的卡上。
      || list.find(x => x.name === d.name && !x._merged)
    if (live) {
      live._merged = true
      live.status = d.status || 'done'
      live.elapsedMs = d.elapsedMs || live.elapsedMs || 0
      // 起点跟着终态那份走：done 汇总是落库那一份的同源数据，实时帧万一漏了这张卡的重发，
      // 缺起点就等于这一张永远不进跨度；等待账同理，缺了就把人读卡的时间算进机器时间
      if (d.startedAtMs) live.startedAtMs = d.startedAtMs
      if (d.waitedMs) live.waitedMs = d.waitedMs
      if (d.args) live.args = d.args // done 汇总带全文（≤8KB），覆盖实时 200 字摘要
      if (d.error) live.error = d.error
      if (d.attempts != null) live.attempts = d.attempts
      if (d.result != null) live.result = d.result
      // 咨询卡（子智能体/取数助手）的终态一问一答：done 这份带全量 subChat（与落库同源），
      // 漏搬就是「实时帧丢了这张卡或没带 subChat，done 这份又没补」→ 当场看不见、刷新才长出结果
      if (d.subChat) live.subChat = d.subChat
      // 外溢留存标识必须跟着搬：实时流里的 tool_status 只是短摘要副本（不带 spillId），
      // 全文是 done 这份才有——漏搬的后果是「历史刷新后卡片有『查看完整输出』、当场没有」
      if (d.spillId) live.spillId = d.spillId
      if (d.argsSpillId) live.argsSpillId = d.argsSpillId
      // 扇出清单与运行结果只有终态那份才带全（实时进度帧只覆盖跑到那一刻）：漏搬的后果是
      // 「当场看得见、刷新后反而没了」，而 kind=timeout/infra 这种收尾漏掉就等于把失败显示成成功
      if (d.fanOut) live.fanOut = d.fanOut
      if (d.run) live.run = d.run
    } else {
      // 这张卡是 done 那份自己带来的（实时那帧丢了）：同样标成已认领，否则下一条同名汇总会
      // 并进它身上，把两次调用合成一张卡（翻倍的反方向错法）
      list.push({ ...d, _merged: true })
    }
  }
}
