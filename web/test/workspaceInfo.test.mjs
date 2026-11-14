import test from 'node:test'
import assert from 'node:assert/strict'
import { ref } from 'vue'
import { isForeignWorkspace, workspaceSetupHintOf, workspaceTitleOf,
         permissionCodeOf, workspaceRefreshNotice, permissionPickPlan } from '../src/chat/workspaceInfo.js'
import { useSessionWorkspace } from '../src/chat/useSessionWorkspace.js'

const tick = () => new Promise(r => setTimeout(r, 0))

test('异机判据：本机工作区配不同执行器才算异机', () => {
  assert.equal(isForeignWorkspace({ isLocal: true, executorId: 'pc-b' }, 'pc-a'), true)
  assert.equal(isForeignWorkspace({ isLocal: true, executorId: 'pc-a' }, 'pc-a'), false)
  assert.equal(isForeignWorkspace({ isLocal: true, executorId: 'pc-b' }, ''), false)
  assert.equal(isForeignWorkspace({ isLocal: false, executorId: 'pc-b' }, 'pc-a'), false)
  assert.equal(isForeignWorkspace(null, 'pc-a'), false)
})

test('就绪提示：隐式不提示；异机 > 未绑目录 > 执行器离线', () => {
  const base = { name: '甲', isLocal: true, roots: ['/x'], executorOnline: true }
  assert.equal(workspaceSetupHintOf(null, false), '')
  assert.equal(workspaceSetupHintOf({ ...base, implicit: true }, false), '')
  assert.match(workspaceSetupHintOf(base, true), /另一台电脑/)
  assert.match(workspaceSetupHintOf({ ...base, roots: [] }, false), /还没绑定本机文件夹/)
  assert.match(workspaceSetupHintOf({ ...base, executorOnline: false }, false), /执行器不在线/)
  assert.equal(workspaceSetupHintOf(base, false), '')
})

test('标题、权限恢复、刷新播报与选档分派', () => {
  assert.equal(workspaceTitleOf(null), '')
  assert.match(workspaceTitleOf({ name: '甲', archived: true }), /已归档/)
  assert.equal(permissionCodeOf({ permissionPreset: 'balanced' }), 'balanced')
  assert.equal(permissionCodeOf({}), '')
  assert.deepEqual(workspaceRefreshNotice({ executorOnline: true }), { ok: true, text: '执行器已连上' })
  assert.equal(workspaceRefreshNotice({ executorOnline: false }).ok, false)
  assert.deepEqual(permissionPickPlan('readonly', 'balanced'), { confirm: false, apply: 'readonly' })
  assert.deepEqual(permissionPickPlan('autonomous', 'balanced'), { confirm: true, apply: null })
  assert.deepEqual(permissionPickPlan('autonomous', 'autonomous'), { confirm: false, apply: null })
})

const ws = over => ({ projectId: 'p1', name: '工作区', roots: ['/x'], isLocal: true,
                      executorId: 'pc-a', executorOnline: true, permissionPreset: 'balanced', ...over })
const presets = [
  { code: 'readonly', label: '只读' },
  { code: 'balanced', label: '平衡', platformDefault: true },
  { code: 'autonomous', label: '完全' }
]

const makeCtx = (over = {}) => {
  const calls = []
  const notices = { success: [], warning: [], error: [] }
  const routes = []
  const ctx = {
    currentSessionId: ref('s1'),
    workspaceEpoch: ref(0),
    agent: () => ({ permissionPreset: '' }),
    skillList: () => [{ name: '技能甲' }],
    skillDoc: () => '技能甲',
    localExecutorId: () => 'pc-a',
    api: {
      getSessionWorkspace: async sid => { calls.push(['workspace', sid]); return { data: ws() } },
      getPermissionPresets: async () => ({ success: true, data: presets }),
      setSessionPermission: async (sid, code) => { calls.push(['perm', sid, code]) },
      openProjectFolder: async (pid, path) => { calls.push(['open', pid, path]) }
    },
    notify: {
      success: t => notices.success.push(t),
      warning: t => notices.warning.push(t),
      error: t => notices.error.push(t)
    },
    router: { push: p => routes.push(p) },
    ...over
  }
  return { ctx, calls, notices, routes }
}

test('会话工作区：立即加载、恢复档位、换会话与工作区纪元各自重拉', async () => {
  const { ctx, calls } = makeCtx()
  const w = useSessionWorkspace(ctx)
  await tick()
  await w.loadPermissionPresets()
  assert.equal(w.sessionWorkspace.value.name, '工作区')
  assert.equal(w.sessionPermission.value, 'balanced')
  assert.equal(w.effectivePermission.value, 'balanced')
  assert.equal(w.permissionLabel.value, '平衡')
  assert.match(w.wsTitle.value, /当前工作区：工作区/)
  assert.equal(w.foreignWorkspace.value, false)
  assert.equal(w.workspaceSetupHint.value, '')
  assert.deepEqual(w.skillDocEntry.value, { name: '技能甲' })

  ctx.currentSessionId.value = 's2'
  await tick()
  assert.deepEqual(calls.filter(c => c[0] === 'workspace').map(c => c[1]), ['s1', 's2'])
  ctx.workspaceEpoch.value++
  await tick()
  assert.equal(calls.filter(c => c[0] === 'workspace').length, 3)
})

test('会话工作区：取数失败置空；异机提示按本地执行器判定', async () => {
  const c = makeCtx({
    api: {
      getSessionWorkspace: async () => ({ data: ws({ executorId: 'pc-b' }) }),
      getPermissionPresets: async () => ({ success: true, data: presets })
    }
  })
  const w = useSessionWorkspace(c.ctx)
  await tick()
  assert.equal(w.foreignWorkspace.value, true)
  assert.match(w.workspaceSetupHint.value, /另一台电脑/)

  const failed = makeCtx({
    api: { getSessionWorkspace: async () => { throw new Error('网络错') },
           getPermissionPresets: async () => { throw new Error('网络错') } }
  })
  const f = useSessionWorkspace(failed.ctx)
  await tick()
  assert.equal(f.sessionWorkspace.value, null)
  await f.loadPermissionPresets()
  assert.deepEqual(f.permissionPresets.value, [])
})

test('档位：不再按平台上限回落；完全权限需勾选确认；失败复位保存态', async () => {
  const { ctx, calls, notices } = makeCtx({
    api: {
      getSessionWorkspace: async () => ({ data: ws() }),
      // 平台档位上限已退役：名册里的 available 不再参与钳制
      getPermissionPresets: async () => ({ success: true, data: presets.map(p =>
        p.code === 'autonomous' ? { ...p, available: false } : p) }),
      setSessionPermission: async (sid, code) => { calls.push(['perm', sid, code]) }
    }
  })
  const w = useSessionWorkspace(ctx)
  await tick()
  await w.loadPermissionPresets()
  w.sessionPermission.value = 'autonomous'
  assert.equal(w.effectivePermission.value, 'autonomous')   // 原样生效

  await w.pickPermission('readonly')
  assert.deepEqual(calls.at(-1), ['perm', 's1', 'readonly'])
  assert.equal(w.sessionPermission.value, 'readonly')

  await w.pickPermission('autonomous')
  assert.equal(w.dangerOpen.value, true)
  assert.equal(calls.filter(c => c[0] === 'perm').length, 1)
  await w.confirmDanger()
  assert.equal(w.dangerOpen.value, true)                  // 未勾选不写
  w.dangerAck.value = true
  await w.confirmDanger()
  assert.equal(calls.at(-1)[2], 'autonomous')
  assert.equal(w.dangerOpen.value, false)
  assert.equal(w.dangerSaving.value, false)

  const failing = makeCtx({
    api: { getSessionWorkspace: async () => ({ data: ws() }),
           getPermissionPresets: async () => ({ success: true, data: presets }),
           setSessionPermission: async () => { throw new Error('写失败') } }
  })
  const f = useSessionWorkspace(failing.ctx)
  await tick()
  const ok = await f.applyPermission('readonly')
  assert.equal(ok, false)
  assert.match(failing.notices.error.at(-1), /切换权限失败/)
  assert.equal(f.permissionSaving.value, false)
  assert.equal(notices.error.length, 0)
})

test('刷新与目录：连上/未连上/失败各有提示；跳转与打开按 projectId 走', async () => {
  const c = makeCtx()
  const w = useSessionWorkspace(c.ctx)
  await tick()
  await w.refreshSessionWorkspace()
  assert.match(c.notices.success.at(-1), /执行器已连上/)

  c.ctx.api.getSessionWorkspace = async () => ({ data: ws({ executorOnline: false }) })
  await w.refreshSessionWorkspace()
  assert.match(c.notices.warning.at(-1), /还没连上/)

  c.ctx.api.getSessionWorkspace = async () => { throw new Error('超时') }
  await w.refreshSessionWorkspace()
  assert.match(c.notices.warning.at(-1), /检查失败/)
  assert.equal(w.workspaceRefreshing.value, false)

  w.goBindWorkspace()
  assert.deepEqual(c.routes, ['/project/p1'])
  await w.openLocalFolder('src')
  assert.deepEqual(c.calls.at(-1), ['open', 'p1', 'src'])
  c.ctx.api.openProjectFolder = async () => { throw new Error('离线') }
  await w.openLocalFolder('')
  assert.match(c.notices.warning.at(-1), /离线/)
})
