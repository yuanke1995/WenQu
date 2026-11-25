// 本会话沙盒目录（两端同吃这一份）：取数、判据、折树只算一份，页面只管怎么摆。
// 从 useChatEngine 抽出：这块只关心「沙盒里有哪些文件、能不能取回成产物」，
// 与问答流、审批、目标无关。三个重拉时机（切会话/一轮收尾/判据翻转）在这里注册。
import { ref, computed, watch } from 'vue'

// Windows 路径归一：后端回的是宿主路径，树节点按 / 切分
const toPosix = p => String(p || '').replace(/\\/g, '/')

export const useSandboxFiles = ({ currentSessionId, loading, messages, notify,
                                  listApi, downloadApi, takeApi }) => {
  // ==================== 本会话沙盒目录（两端同吃这一份） ====================
  // 模型写进沙盒、却没交付成产物的文件也列在这里：工作区文件栏只读 projects/{工作区}，
  // 会话没挂工作区时那份文件躺在 sessions/{会话} 下，本栏按宿主目录读，容器空闲回收后照样列得出来。
  // 两端同吃这一份，「助手在沙盒里做了文件」在手机上也看得见。
  // 与任务清单同一刀：取数、判据、折树只算一份，页面只管怎么摆。
  const sandboxFiles = ref([])
  const sandboxTotal = ref(0)
  const sandboxErr = ref('')
  /** 本机执行器离线、当前列的是云端沙盒文件：界面要说明本机旧文件暂时看不到 */
  const sandboxLocalOffline = ref(false)
  const sandboxLoading = ref(false)
  /** 本轮已取回成产物的路径：按钮点过一次就改口，不邀请重复点（重复点会落出一条同名重复产物） */
  const sandboxTaken = ref(new Set())
  /** 被展开的目录。默认<b>全部收起</b>：这个工作区一上来就摊开上百个文件，右栏那点宽度全被目录行占满，
   *  真正要找的那份成品反而要往下翻。用户点开哪个记哪个（一轮收尾重拉文件列表时保留，不把人家刚点开的收回去），
   *  切会话才清空——换了份目录，旧路径没有指代对象。 */
  const sandboxOpened = ref(new Set())
  // 沙盒系工具名单：与后端 FrameworkToolLabels.FilesystemToolNames 同口径，另加 run_code（SANDBOX 族仅存的
  // 一把）与 deliver_artifact（把沙盒里做出来的文件存进会话）；ls 是老名，只为按裸名落库的历史兜底。
  const SANDBOX_TOOL_NAMES = new Set(['execute', 'run_code', 'read_file', 'write_file', 'edit_file',
                                      'grep_files', 'glob_files', 'list_files', 'ls', 'deliver_artifact'])
  /** 本会话是否真用过沙盒：沙盒目录要等模型第一次往里写东西才由 provisioner 建出来——没用过沙盒
   *  工具的会话（新用户第一问最典型）连本人 workspace 根都还没有，这时去拉文件列表只会撞上
   *  「沙盒可能部署在另一台机器上」的假故障。判据认「成功跑完的沙盒系工具调用」：
   *  被拒/失败的调用没建出任何目录，不算——只认 done 顺带把「审批被拒却报目录故障」也挡掉。
   *  会话目录里出现文件 ⟺ 本会话有过成功的沙盒系调用（附件、模板复制都落各自的目录，不进这里）。 */
  const sessionSandboxActive = computed(() => messages.value.some(m => m.role !== 'user'
    && Array.isArray(m.toolCalls)
    && m.toolCalls.some(t => t && t.status === 'done' && SANDBOX_TOOL_NAMES.has(t.name))))

  async function loadSandboxFiles () {
    const sid = currentSessionId.value
    sandboxTaken.value = new Set()
    if (!sid || !sessionSandboxActive.value) {
      sandboxFiles.value = []
      sandboxTotal.value = 0
      sandboxErr.value = ''
      sandboxLocalOffline.value = false
      sandboxLoading.value = false
      return
    }
    sandboxLoading.value = true
    sandboxErr.value = ''
    try {
      const r = await listApi(sid)
      // 迟到的结果不上屏：await 期间可能已切走会话、或判据翻假（切会话那一瞬 messages 还是
      // 上一个会话的，历史落位才揭出本会话没用过沙盒）——上屏就会把误报/旧文件盖进当前视图
      if (currentSessionId.value !== sid || !sessionSandboxActive.value) return
      sandboxLocalOffline.value = Boolean(r?.data?.localOffline)
      sandboxFiles.value = (r && r.data && r.data.files) || []
      // 条数认服务端给的总数，不认到手这一截：一屏只回最新 300 个，只报到手数量会把「没列全」说成「就这些」
      sandboxTotal.value = Number(r?.data?.total) || sandboxFiles.value.length
    } catch (e) {
      if (currentSessionId.value !== sid || !sessionSandboxActive.value) return
      // 失败必须说出来并给重试：静默成空列表，用户读到的是「我让它做的文件没了」
      sandboxFiles.value = []
      sandboxTotal.value = 0
      sandboxErr.value = e?.message || '沙盒目录读取失败'
      sandboxLocalOffline.value = false
    } finally {
      // 只收拾自己这个会话的 loading：切换后新一次加载正在飞，别把它的小字提示掐了
      if (currentSessionId.value === sid) sandboxLoading.value = false
    }
  }

  /** 相对路径折回目录树；目录行的个数/体积是**整棵子树**的，所以一个上百文件的构建目录先看计数就知道值不值得点 */
  const sandboxTree = computed(() => {
    const root = { name: '', path: '', dirs: new Map(), files: [], count: 0, size: 0, newest: 0 }
    for (const f of sandboxFiles.value) {
      const segs = toPosix(f.path).split('/').filter(Boolean)
      segs.pop()   // 末段是文件名，前面的才是目录
      let node = root
      let acc = ''
      for (const seg of segs) {
        acc = acc ? acc + '/' + seg : seg
        let d = node.dirs.get(seg)
        if (!d) {
          d = { name: seg, path: acc, dirs: new Map(), files: [], count: 0, size: 0, newest: 0 }
          node.dirs.set(seg, d)
        }
        node = d
      }
      node.files.push(f)
    }
    const roll = d => {
      for (const f of d.files) {
        d.count += 1
        d.size += Number(f.size) || 0
        d.newest = Math.max(d.newest, Number(f.mtime) || 0)
      }
      for (const sub of d.dirs.values()) {
        roll(sub)
        d.count += sub.count
        d.size += sub.size
        d.newest = Math.max(d.newest, sub.newest)
      }
      return d
    }
    return roll(root)
  })
  /** 树按收起状态摊平成可渲染的行。同层先目录后文件——文件夹跟文件按时间混排时，一个刚写进去的名字
   *  就把目录行挤到下面，脉络读不出来；组内仍按最新在前（整栏的读法仍是「刚做出来的在最上面」） */
  const sandboxRows = computed(() => {
    const out = []
    const walk = (node, depth) => {
      const kids = [
        ...[...node.dirs.values()].map(d => ({ dir: true, node: d, newest: d.newest })),
        ...node.files.map(f => ({ dir: false, node: f, newest: Number(f.mtime) || 0 })),
      ].sort((a, b) => (b.dir === a.dir ? 0 : b.dir ? 1 : -1) || b.newest - a.newest)
      for (const k of kids) {
        if (!k.dir) {
          out.push({ type: 'file', key: k.node.path, depth, file: k.node })
          continue
        }
        const open = sandboxOpened.value.has(k.node.path)
        out.push({ type: 'dir', key: 'd:' + k.node.path, depth, dir: k.node, open })
        if (open) walk(k.node, depth + 1)
      }
    }
    walk(sandboxTree.value, 0)
    return out
  })
  const sandboxCount = computed(() => sandboxTotal.value || sandboxFiles.value.length)
  const sandboxBytes = computed(() => sandboxTree.value.size)
  const sandboxRest = computed(() => Math.max(0, sandboxTotal.value - sandboxFiles.value.length))
  /** 体积文案要省着写：10 KB 以下留一位小数（1.4 KB 分得出大小），往上取整（146 KB 不需要那两位） */
  const sbSize = n => {
    const v = Number(n) || 0
    return v >= 1024 * 1024 ? (v / 1024 / 1024).toFixed(1) + ' MB'
      : v >= 10 * 1024 ? Math.round(v / 1024) + ' KB'
      : v >= 1024 ? (v / 1024).toFixed(1) + ' KB' : v + ' B'
  }
  const toggleSandboxDir = path => {
    const next = new Set(sandboxOpened.value)
    if (next.has(path)) next.delete(path)
    else next.add(path)
    sandboxOpened.value = next
  }
  async function sandboxDownload (f) {
    const sid = currentSessionId.value
    if (!sid) return
    try {
      await downloadApi(sid, f.path, f.name)
    } catch (e) {
      notify.warning(`下载失败：${e?.message || '沙盒目录读不到这个文件'}`)
    }
  }
  async function sandboxTake (f) {
    const sid = currentSessionId.value
    if (!sid) return
    try {
      await takeApi(sid, f.path)
      sandboxTaken.value = new Set([...sandboxTaken.value, f.path])
      notify.success('已存为产物，可在「我的产物」里查看')
    } catch (e) {
      notify.warning(`存为产物失败：${e?.message || '文件过大或该类型不支持作为产物'}`)
    }
  }

  // 三个重拉时机，与 PC 原行为逐字等价：切会话（顺带把展开态归零）、一轮收尾（交付物是那轮做出来的，
  // 不重拉就要刷新页面才看得见）、判据翻转（切会话那一瞬判据可能还是上一个会话的旧值）
  watch(currentSessionId, () => { sandboxOpened.value = new Set(); loadSandboxFiles() })
  watch(() => loading.value, (v, prev) => { if (prev && !v) loadSandboxFiles() })
  watch(sessionSandboxActive, () => { loadSandboxFiles() })

  return {
    sandboxFiles, sandboxTotal, sandboxErr, sandboxLocalOffline, sandboxLoading,
    sandboxTaken, sandboxOpened, sessionSandboxActive, loadSandboxFiles,
    sandboxDownload, sandboxTake, sandboxRows, sandboxCount, sandboxBytes, sandboxRest,
    sbSize, toggleSandboxDir,
  }
}
