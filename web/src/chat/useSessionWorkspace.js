// 会话工作区摘要与会话级权限档位：头部标识、就绪提示、档位名册、执行器刷新与目录打开。
import { ref, computed, watch } from 'vue'
import { resolveEffectivePermission, permissionLabelOf } from './permission.js'
import { isForeignWorkspace, workspaceSetupHintOf, workspaceTitleOf,
         permissionCodeOf, workspaceRefreshNotice, permissionPickPlan } from './workspaceInfo.js'

export const useSessionWorkspace = ctx => {
  const sessionWorkspace = ref(null)
  const permissionPresets = ref([])
  const sessionPermission = ref('')
  const permissionSaving = ref(false)
  const dangerOpen = ref(false)
  const dangerAck = ref(false)
  const dangerSaving = ref(false)
  const workspaceRefreshing = ref(false)

  const effectivePermission = computed(() => resolveEffectivePermission(
    permissionPresets.value, sessionPermission.value,
    ctx.agent() && ctx.agent().permissionPreset))
  const permissionLabel = computed(() => permissionLabelOf(permissionPresets.value, effectivePermission.value))
  const wsTitle = computed(() => workspaceTitleOf(sessionWorkspace.value))
  const foreignWorkspace = computed(() => isForeignWorkspace(sessionWorkspace.value, ctx.localExecutorId()))
  const workspaceSetupHint = computed(() => workspaceSetupHintOf(sessionWorkspace.value, foreignWorkspace.value))
  // 弹窗按名字现取当前清单里的那一条，停用/恢复后按钮状态跟着翻面。
  const skillDocEntry = computed(() => ctx.skillList().find(s => s.name === ctx.skillDoc()) || {})

  const loadPermissionPresets = async () => {
    try {
      const r = await ctx.api.getPermissionPresets()
      permissionPresets.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
    } catch (e) {
      permissionPresets.value = []
    }
  }
  const applyPermission = async code => {
    const sid = ctx.currentSessionId.value
    if (!sid) return false
    permissionSaving.value = true
    try {
      await ctx.api.setSessionPermission(sid, code)
      sessionPermission.value = code
      return true
    } catch (e) {
      ctx.notify.error('切换权限失败：' + (e.message || '请重试'))
      return false
    } finally {
      permissionSaving.value = false
    }
  }
  const pickPermission = async key => {
    if (permissionSaving.value) return
    const plan = permissionPickPlan(key, effectivePermission.value)
    if (plan.confirm) {
      dangerAck.value = false
      dangerOpen.value = true
      return
    }
    if (plan.apply) await applyPermission(plan.apply)
  }
  const confirmDanger = async () => {
    if (!dangerAck.value || dangerSaving.value) return
    dangerSaving.value = true
    try {
      const ok = await applyPermission('autonomous')
      if (ok) dangerOpen.value = false
    } finally {
      dangerSaving.value = false
    }
  }

  // 标识是增强信息：取不到不打扰聊天。
  const loadSessionWorkspace = async sid => {
    if (!sid) return
    try {
      const r = await ctx.api.getSessionWorkspace(sid)
      sessionWorkspace.value = (r && r.data) || null
    } catch (e) {
      sessionWorkspace.value = null
    }
  }
  watch(ctx.currentSessionId, async sid => {
    sessionWorkspace.value = null
    sessionPermission.value = ''
    await loadSessionWorkspace(sid)
  }, { immediate: true })
  // 「新建对话」领走的空会话 id 不变、归属却改了：sid 判据不会动，得跟一次工作区纪元。
  watch(ctx.workspaceEpoch, () => loadSessionWorkspace(ctx.currentSessionId.value))
  watch(sessionWorkspace, ws => { sessionPermission.value = permissionCodeOf(ws) }, { immediate: true })

  const goBindWorkspace = () => {
    const pid = sessionWorkspace.value && sessionWorkspace.value.projectId
    if (!pid) return
    ctx.router.push('/project/' + pid)
  }
  const openLocalFolder = async path => {
    const pid = sessionWorkspace.value && sessionWorkspace.value.projectId
    if (!pid) return
    try {
      await ctx.api.openProjectFolder(pid, path || '')
    } catch (e) {
      ctx.notify.warning(e.message || '打开失败：先确认那台电脑上的执行器在线')
    }
  }
  const refreshSessionWorkspace = async () => {
    const sid = ctx.currentSessionId.value
    if (!sid || workspaceRefreshing.value) return
    workspaceRefreshing.value = true
    try {
      const r = await ctx.api.getSessionWorkspace(sid)
      sessionWorkspace.value = (r && r.data) || null
      const notice = workspaceRefreshNotice(sessionWorkspace.value)
      if (notice.ok) ctx.notify.success(notice.text)
      else ctx.notify.warning(notice.text)
    } catch (e) {
      ctx.notify.warning('检查失败：' + (e.message || '请稍后再试'))
    } finally {
      workspaceRefreshing.value = false
    }
  }

  return {
    sessionWorkspace, permissionPresets, sessionPermission, permissionSaving,
    dangerOpen, dangerAck, dangerSaving, workspaceRefreshing,
    effectivePermission, permissionLabel, wsTitle, foreignWorkspace,
    workspaceSetupHint, skillDocEntry,
    loadPermissionPresets, applyPermission, pickPermission, confirmDanger,
    loadSessionWorkspace, goBindWorkspace, openLocalFolder, refreshSessionWorkspace
  }
}
