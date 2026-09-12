<template>
  <div class="basic-wrap">
    <a-form :label-col="{ span: 4 }" :wrapper-col="{ span: 14 }">
      <div v-for="card in cards" :key="card.key" class="b-card">
        <div class="b-title">{{ card.title }}</div>
        <div class="b-desc">{{ card.desc }}</div>
        <a-alert v-if="card.alert" :type="card.alert.type" show-icon style="margin-bottom:16px"
                 :message="card.alert.msg" />
        <SchemaField v-for="f in card.fields" :key="f.group + '.' + f.key"
                     :field="f" :form="form" :tips="TIPS" @change="onStrengthSelect">
          <template v-if="probeKey(f)" #extra>
            <a-button size="small" style="margin-left:8px" :loading="probeStates[probeKey(f)]?.loading"
                      @click="$emit('probe', probeKey(f))">测试连接</a-button>
            <span v-if="probeStates[probeKey(f)]?.result" class="probe-chip"
                  :class="probeStates[probeKey(f)].result.available ? 'probe-ok' : 'probe-bad'">
              {{ probeStates[probeKey(f)].result.available ? '可达' : '不可达' }} {{ probeStates[probeKey(f)].result.latencyMs }}ms
            </span>
          </template>
        </SchemaField>
      </div>

      <div class="b-more">
        其余 {{ expertHintCount }} 项为超时 / 重试 / 并发 / 权重等调优与排障参数，保持默认值即可。
        <a href="javascript:void(0)" @click="$emit('switch-expert')">打开专家模式</a>
      </div>
    </a-form>
  </div>
</template>

<script setup>
import { watch } from 'vue'
import SchemaField from '../components/SchemaField.vue'
import { FIELDS, TIPS } from '../configSchema'

const props = defineProps({
  form: { type: Object, required: true },
  probeStates: { type: Object, default: () => ({}) }
})
defineEmits(['probe', 'switch-expert'])

// 三档预设：数值以后端 ConfigService.defaults() 的当前默认值作为 balanced 基准
const PRESETS = {
  precision: { vectorTopK: 8, vecThreshold: 0.5, keywordLimit: 10, keywordMaxTerms: 4, titleBonus: 0.15, positionBonus: 0.05, sectionBonus: 0.02, maxContextHits: 5, dedupEnabled: true },
  balanced: { vectorTopK: 15, vecThreshold: 0.3, keywordLimit: 20, keywordMaxTerms: 6, titleBonus: 0.1, positionBonus: 0.03, sectionBonus: 0.01, maxContextHits: 8, dedupEnabled: true },
  recall: { vectorTopK: 30, vecThreshold: 0.15, keywordLimit: 40, keywordMaxTerms: 10, titleBonus: 0.05, positionBonus: 0.01, sectionBonus: 0, maxContextHits: 15, dedupEnabled: false }
}

const same = (a, b) => (typeof b === 'boolean' ? a === b : Number(a) === Number(b))
const matchPreset = () => {
  for (const name of Object.keys(PRESETS)) {
    const p = PRESETS[name]
    if (Object.entries(p).every(([k, v]) => {
      const cur = (k === 'maxContextHits' || k === 'dedupEnabled') ? props.form.context[k] : props.form.retrieval[k]
      return same(cur, v)
    })) return name
  }
  return 'custom'
}

const applyPreset = v => {
  const p = PRESETS[v]
  if (!p) return
  Object.assign(props.form.retrieval, {
    vectorTopK: p.vectorTopK, vecThreshold: p.vecThreshold, keywordLimit: p.keywordLimit,
    keywordMaxTerms: p.keywordMaxTerms, titleBonus: p.titleBonus, positionBonus: p.positionBonus,
    sectionBonus: p.sectionBonus
  })
  props.form.context.maxContextHits = p.maxContextHits
  props.form.context.dedupEnabled = p.dedupEnabled
  props.form.retrieval.strength = v
}

// 由 schema 取 L1 字段，按所属面板归入卡片；检索强度档位置于检索卡首位
const strengthField = FIELDS.find(f => f.key === 'strength')
const l1 = FIELDS.filter(f => f.tier === 1)
const cards = [
  { key: 'retrieval', title: '检索强度', desc: '决定「召回多少、卡得多严」。拿不准就选「均衡」。',
    fields: [strengthField, ...l1.filter(f => f.panel === 'retrieval')].filter(Boolean) },
  { key: 'chat', title: '问答模型',
    desc: '支持 DeepSeek / 智谱 GLM / 百炼 Qwen / Kimi / 豆包 / 混元 等 OpenAI 兼容端点，保存即生效。',
    fields: l1.filter(f => f.panel === 'chat') },
  { key: 'kb', title: '知识库',
    desc: '向量模型与分块参数决定检索质量的天花板，也是改动代价最大的两项。',
    alert: { type: 'warning', msg: '向量模型改动会触发全量重嵌入；分块大小 / 重叠改动需重新解析文档才生效。' },
    fields: l1.filter(f => ['embedding', 'chunk', 'vision'].includes(f.panel)) }
]

const PROBE_BY_KEY = { 'chat.baseUrl': 'chat', 'embedding.baseUrl': 'embedding' }
const probeKey = f => PROBE_BY_KEY[f.group + '.' + f.key] || ''

const expertHintCount = FIELDS.length - l1.length

// 下拉变更：仅处理检索强度档位
const onStrengthSelect = (field, v) => {
  if (field.key === 'strength') applyPreset(v)
}

// 手动改动任一受控数值 → 自动降级为自定义档
watch(
  () => [
    props.form.retrieval.vectorTopK, props.form.retrieval.vecThreshold, props.form.retrieval.keywordLimit,
    props.form.retrieval.keywordMaxTerms, props.form.retrieval.titleBonus, props.form.retrieval.positionBonus,
    props.form.retrieval.sectionBonus, props.form.context.maxContextHits, props.form.context.dedupEnabled
  ],
  () => {
    const cur = props.form.retrieval.strength
    if (cur && cur !== 'custom' && matchPreset() !== cur) props.form.retrieval.strength = 'custom'
  }
)
</script>

<style scoped>
.basic-wrap {
  padding-bottom: 8px;
}
.b-card {
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 12px;
  padding: 16px 20px 4px;
  margin-bottom: 16px;
}
.b-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 2px;
}
.b-desc {
  font-size: 12px;
  color: #8c8c8c;
  margin-bottom: 12px;
}
.b-more {
  font-size: 12px;
  color: #8c8c8c;
  padding: 4px 4px 0;
}
.probe-chip {
  display: inline-block;
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.probe-ok {
  background: #f6ffed;
  color: #389e0d;
}
.probe-bad {
  background: #fff2f0;
  color: #cf1322;
}
</style>
