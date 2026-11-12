// 技能选用状态（输入框「+」菜单）：清单加载、本轮点名（最多 3 个）、头像底色。
// 只在内存里，不落存储；发送后由发送链路清空 pickedSkills。
import { ref } from 'vue'

export const SKILL_AVA_COLORS = ['#e8f1ff', '#fff3e0', '#e8f5e9', '#fdeaf3', '#ede7f6', '#e0f4f1']
export const MAX_PICKED_SKILLS = 3

export const createSkillPicker = ({ listApi, currentSid, notifyWarning = () => {} }) => {
  const skillList = ref([])      // 可用技能（内置 + 本人 + 本会话工作区的只读清单）
  const pickedSkills = ref([])   // 本轮选用的技能名（发送后清空）
  const skillDoc = ref('')       // 正在看正文的那条技能名（''=没打开）；存名字而不是条目快照，
                                 // 这样停用/恢复后清单重拉，弹窗看到的仍是活的那条
  const loadSkills = async () => {
    try {
      const r = await listApi(currentSid())
      skillList.value = (r && r.success && Array.isArray(r.data)) ? r.data : []
    } catch (e) { /* 接口不可用时静默：菜单只显示附件入口 */ }
  }
  const toggleSkill = name => {
    const i = pickedSkills.value.indexOf(name)
    if (i >= 0) pickedSkills.value.splice(i, 1)
    else {
      // 工作区那一路只给看、不给点名：它随会话走，钉进本轮会带出一个换会话就解析不出的名字
      const hit = skillList.value.find(s => s.name === name)
      if (hit && hit.selectable === false) return
      if (pickedSkills.value.length >= MAX_PICKED_SKILLS) { notifyWarning(`一次最多选用 ${MAX_PICKED_SKILLS} 个技能`); return }
      pickedSkills.value.push(name)
    }
  }
  const skillAvaStyle = name => {
    const i = skillList.value.findIndex(s => s.name === name)
    const color = SKILL_AVA_COLORS[(i < 0 ? name.length : i) % SKILL_AVA_COLORS.length]
    return { background: color }
  }
  return { skillList, pickedSkills, skillDoc, loadSkills, toggleSkill, skillAvaStyle }
}
