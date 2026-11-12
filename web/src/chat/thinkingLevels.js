// 思考档位状态：按模型的开关/强度记忆（localStorage）、可选项与最终下发参数。
// 悬浮面板、移动端模型面板与发送链路共用这一份口径；纯查表常量在 ./thinking.js。
import { ref, computed } from 'vue'
import { THINK_CAPS, REASONING_LEVELS, THINK_LEVEL_ON, levelLabel } from './thinking.js'

const KEY_THINK = 'ai_deep_think'
const KEY_LEVEL = 'ai_think_level'

const readMap = (storage, key) => {
  try {
    const raw = storage.getItem(key)
    const parsed = raw ? JSON.parse(raw) : {}
    return (parsed && typeof parsed === 'object') ? parsed : {}
  } catch (e) { return {} }
}

/**
 * @param modelIndexOf   () => ({ [ref]: 模型登记行 })
 * @param effectiveModelOf () => 当前生效的模型引用
 * @param isBusy         () => 是否有轮次在跑（跑动中禁改档位：改了也不影响已发出的这一轮）
 * @param storage        localStorage 等价物，测试可注入
 */
export const createThinkingLevels = ({ modelIndexOf, effectiveModelOf, isBusy = () => false, storage = globalThis.localStorage }) => {
  /** 按模型引用取思考能力 / 支持档位（悬浮面板与发送链路共用同一套口径） */
  const thinkCapsOf = ref => THINK_CAPS[modelIndexOf()[ref]?.thinking || 'switchable'] || THINK_CAPS.switchable
  const reasoningLevelsOf = ref => {
    const raw = modelIndexOf()[ref]?.reasoningLevels
    return Array.isArray(raw) ? raw : []
  }
  /** 思考开关的按模型记忆：{ref: 0|1}（可带 __default 作未单独选择时的默认）。 */
  const deepThinkMap = ref(readMap(storage, KEY_THINK))
  /** 等级选择的按模型记忆（与开关同一套 per-model 风格）。 */
  const levelMap = ref(readMap(storage, KEY_LEVEL))

  /** 某模型本轮是否思考：恒思考强制开；否则按模型记忆（等级下拉的「关闭思考」写 0，选档位/开启写 1） */
  const deepOnOf = ref => {
    if (thinkCapsOf(ref).locked) return true
    const explicit = deepThinkMap.value[ref]
    if (explicit !== undefined) return explicit === 1
    return deepThinkMap.value.__default === true
  }
  /** 生效模型本轮是否思考（状态栏展示 + 发送链路取值） */
  const deepThinkOn = computed(() => deepOnOf(effectiveModelOf()))

  /** 等级下拉可选项：恒思考锁定为开；可开关模型给「关闭思考」+ 其支持档位；
   *  未登记档位时给「开启思考 / 关闭思考」两项（强度不可选但思考可开，不留死角）。 */
  const levelOptionsOf = ref => {
    const caps = thinkCapsOf(ref)
    const levels = reasoningLevelsOf(ref)
    if (caps.locked) return [{ value: levels[0] || THINK_LEVEL_ON, label: '恒思考 · ' + levelLabel(levels[0]) }]
    const opts = [{ value: 'off', label: '关闭思考' }]
    if (levels.length) {
      for (const lv of REASONING_LEVELS) {
        if (levels.includes(lv.value)) opts.push({ value: lv.value, label: '思考 · ' + lv.label })
      }
    } else {
      // 模型未登记强度档位：只给开关，强度交网关默认
      opts.unshift({ value: THINK_LEVEL_ON, label: '开启思考' })
    }
    return opts
  }
  /** 某模型当前选中：off=不思考；档位/ON=思考；恒思考模型恒为开（其首个支持档位） */
  const currentLevelOf = ref => {
    const levels = reasoningLevelsOf(ref)
    if (thinkCapsOf(ref).locked) return levels[0] || THINK_LEVEL_ON
    if (!deepOnOf(ref)) return 'off'
    const explicit = levelMap.value[ref]
    if (explicit && levelOptionsOf(ref).some(o => o.value === explicit)) return explicit
    const modelDefault = modelIndexOf()[ref]?.defaultReasoningLevel
    if (modelDefault && levels.includes(modelDefault)) return modelDefault
    return levels[0] || THINK_LEVEL_ON
  }
  /** 写入某模型的思考强度（面板点档位按悬浮模型写；不传=生效模型）：
   *  等级与开关联动记忆，选档位/开启=开思考、选关闭=关思考，落 localStorage 立即生效 */
  const setThinkLevel = (v, modelRef) => {
    const target = modelRef || effectiveModelOf()
    if (isBusy() || thinkCapsOf(target).locked) return
    const on = v !== 'off'
    deepThinkMap.value = { ...deepThinkMap.value, [target]: on ? 1 : 0 }
    levelMap.value = { ...levelMap.value, [target]: v }
    try {
      storage.setItem(KEY_THINK, JSON.stringify(deepThinkMap.value))
      storage.setItem(KEY_LEVEL, JSON.stringify(levelMap.value))
    } catch (e) { /* 存储不可用忽略 */ }
  }
  /** 生效模型的当前档位 / 本轮下发给后端的思考强度（空串=不指定，后端回落模型登记默认档位） */
  const currentThinkLevel = computed(() => currentLevelOf(effectiveModelOf()))
  const reasoningLevelParam = computed(() => {
    if (!deepThinkOn.value) return ''
    const v = currentThinkLevel.value
    return (v && v !== 'off' && v !== THINK_LEVEL_ON) ? v : ''
  })

  return { thinkCapsOf, reasoningLevelsOf, deepThinkMap, levelMap, deepOnOf, deepThinkOn,
           levelOptionsOf, currentLevelOf, setThinkLevel, currentThinkLevel, reasoningLevelParam }
}
