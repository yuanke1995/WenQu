<template>
  <!-- 「+」菜单 sheet：顶部一块「本轮模式」（计划开关 / 定为目标），下面技能 / 引用(@) / 历史(#) / 附件
       四个分页。PC 上 @ 与 # 由输入框敲字符唤起（桌面习惯），计划走 /plan、目标走 /goal；移动端这些
       键盘路径都没有，这些入口统一收进这里。选中项与计划开启态以 chip 形式回显在输入框上方
       （由主壳渲染），本 sheet 只管挑选。 -->
  <BottomSheet :open="open" :title="TABS.find(t => t.key === tab).title" max-height="78dvh" @close="$emit('close')">
    <div class="as">
      <!-- ==================== 本轮模式 ====================
           计划是一颗开关（只管下一轮要不要先出计划，开启态由主壳那颗 chip 回显；发出去即消费，
           chip 跟着输入框里的其它本轮内容一起消失）；目标是一行动作
           （点了就把输入框那句交出去建目标开跑，没有正文就在主壳里提示先写一句）。工具条上不放它们——
           四颗药丸固定要 360px，360px 屏会把送出键挤出输入卡。 -->
      <div class="as-mode">
        <button class="as-row" type="button" @click="setPlanMode(!planModeOn)">
          <ordered-list-outlined class="as-ic" />
          <span class="as-main">
            <span class="as-name">计划模式</span>
            <span class="as-desc">这一轮先出计划，你批准后才开始回答（发出去就取消）</span>
          </span>
          <span class="as-sw" :class="{ on: planModeOn }" aria-hidden="true" />
        </button>
        <button v-if="goalModeOn && (!sessionGoal || goalTerminal)" class="as-row" type="button" @click="$emit('goal')">
          <aim-outlined class="as-ic" />
          <span class="as-main">
            <span class="as-name">定为目标</span>
            <span class="as-desc">把输入框这句交出去开跑；助手会自己往下推进，随时可以在上面那条停掉</span>
          </span>
        </button>
      </div>

      <div class="as-tabs">
        <button v-for="t in TABS" :key="t.key" class="as-tab" :class="{ on: tab === t.key }" type="button" @click="switchTab(t.key)">
          {{ t.label }}<span v-if="t.count()" class="as-tab-n">{{ t.count() }}</span>
        </button>
      </div>

      <!-- ==================== 技能 ==================== -->
      <template v-if="tab === 'skill'">
        <div class="as-hint">选用的技能对本轮生效，下一轮自动取消（一次最多 3 个）；点「正文」看这条到底写了什么</div>
        <div v-if="!skillList.length" class="as-empty">暂无可用技能</div>
        <div v-for="s in skillList" :key="s.name" class="as-row" :class="{ on: pickedSkills.includes(s.name) }">
          <button class="as-hit" type="button" :disabled="s.selectable === false" @click="toggleSkill(s.name)">
            <span class="as-ava" :style="skillAvaStyle(s.name)">{{ (s.name || '技').slice(0, 1) }}</span>
            <span class="as-main">
              <span class="as-name">{{ s.name }}
                <span v-if="s.disabled" class="as-flag off">已停用</span>
                <span v-else-if="s.source === 'workspace'" class="as-flag">工作区·只读</span>
              </span>
              <span v-if="s.description" class="as-desc">{{ s.description }}</span>
            </span>
            <check-outlined v-if="pickedSkills.includes(s.name)" class="as-check" />
          </button>
          <button class="as-doc" type="button" @click="skillDoc = s.name">正文</button>
        </div>
      </template>

      <!-- ==================== 引用(@) ==================== -->
      <template v-else-if="tab === 'ref'">
        <div class="as-subtabs">
          <button class="as-subtab" :class="{ on: mentionTab === 'kb' }" type="button" @click="switchMentionTab('kb')">知识库</button>
          <button class="as-subtab" :class="{ on: mentionTab === 'doc' }" type="button" @click="switchMentionTab('doc')">文档</button>
          <button class="as-subtab" :class="{ on: mentionTab === 'agent' }" type="button" @click="switchMentionTab('agent')">智能体</button>
        </div>
        <input v-model="mentionQuery" class="as-filter" type="search" placeholder="筛选…" />
        <div v-if="mentionLoading" class="as-empty">加载中…</div>
        <template v-else>
          <button v-for="k in (mentionTab === 'kb' ? mentionKbFiltered : [])" :key="'kb' + k.id" class="as-row" :class="{ on: isMentioned('kb', k.id) }"
                  type="button" @click="toggleMention('kb', k)">
            <database-outlined class="as-ic" />
            <span class="as-main"><span class="as-name">{{ k.name }}</span><span v-if="k.desc" class="as-desc">{{ k.desc }}</span></span>
            <check-outlined v-if="isMentioned('kb', k.id)" class="as-check" />
          </button>
          <button v-for="d in (mentionTab === 'doc' ? mentionDocFiltered : [])" :key="'doc' + d.id" class="as-row" :class="{ on: isMentioned('doc', d.id) }"
                  type="button" @click="toggleMention('doc', d)">
            <file-text-outlined class="as-ic" />
            <span class="as-main"><span class="as-name">{{ d.fileName }}</span><span class="as-desc">{{ mentionDocStatus(d) }}</span></span>
            <check-outlined v-if="isMentioned('doc', d.id)" class="as-check" />
          </button>
          <button v-for="a in (mentionTab === 'agent' ? mentionAgentFiltered : [])" :key="'ag' + a.id" class="as-row" :class="{ on: isMentioned('agent', a.id) }"
                  type="button" @click="toggleMention('agent', a)">
            <robot-outlined class="as-ic" />
            <span class="as-main"><span class="as-name">{{ a.name }}</span><span v-if="a.desc" class="as-desc">{{ a.desc }}</span></span>
            <check-outlined v-if="isMentioned('agent', a.id)" class="as-check" />
          </button>
          <div v-if="!listLen" class="as-empty">没有可引用的{{ mentionTab === 'kb' ? '知识库' : (mentionTab === 'doc' ? '文档' : '智能体') }}</div>
        </template>
        <div class="as-hint">引用只对本轮生效：知识库收窄检索范围，文档整篇带入上下文，智能体本轮强制咨询它</div>
      </template>

      <!-- ==================== 历史(#) ==================== -->
      <template v-else-if="tab === 'hist'">
        <div class="as-hint">勾选本会话中的历史问答，把它们作为本轮回答的上下文（最多 10 条）</div>
        <input v-model="histQuery" class="as-filter" type="search" placeholder="筛选…" />
        <button v-for="hm in histCandidates" :key="hm.messageId" class="as-row" :class="{ on: isHistPicked(hm.messageId) }"
                type="button" @click="toggleHistoryRef(hm)">
          <span class="as-role">{{ hm.role === 'user' ? '问' : '答' }}</span>
          <span class="as-main"><span class="as-name">{{ histItemTitle(hm) }}</span></span>
          <check-outlined v-if="isHistPicked(hm.messageId)" class="as-check" />
        </button>
        <div v-if="!histCandidates.length" class="as-empty">本会话还没有可引用的历史问答</div>
      </template>

      <!-- ==================== 附件 ==================== -->
      <template v-else>
        <div class="as-hint">支持 PDF / Word / Excel / PPT 与文本、代码文件；单个不超过 15MB（一次最多 5 个）</div>
        <button class="as-row" type="button" @click="fileInput && fileInput.click()">
          <paper-clip-outlined class="as-ic" />
          <span class="as-main"><span class="as-name">选择文件</span><span class="as-desc">上传后随本轮提问一起发送</span></span>
        </button>
        <button class="as-row" type="button" @click="imgInput && imgInput.click()">
          <picture-outlined class="as-ic" />
          <span class="as-main"><span class="as-name">选择图片</span><span class="as-desc">压缩后随本轮提问（支持多选，最多 5 张）</span></span>
        </button>
        <template v-if="pendingFiles.length">
          <div class="as-section">已选附件</div>
          <div v-for="(f, i) in pendingFiles" :key="f.name + i" class="as-row static">
            <paper-clip-outlined class="as-ic" />
            <span class="as-main">
              <span class="as-name">{{ f.name }}</span>
              <span class="as-desc">{{ f.uploading ? '上传中…' : (f.error ? f.error : fmtSize(f.size)) }}</span>
            </span>
            <button class="as-del" type="button" title="移除" @click.stop="removePendingFile(i)">×</button>
          </div>
        </template>
        <template v-if="pendingImages.length">
          <div class="as-section">已选图片</div>
          <div class="as-imgs">
            <span v-for="(p, i) in pendingImages" :key="i" class="as-img">
              <img :src="p.dataUrl" alt="待发送图片" />
              <button class="as-del" type="button" title="移除" @click.stop="removePendingImage(i)">×</button>
            </span>
          </div>
        </template>
        <input ref="fileInput" type="file" multiple style="display:none" @change="onFile" />
        <!-- accept 列出的这四个是能进 canvas 压缩通道、真能随问发送的格式（与个人设置页头像同口径）。 -->
        <input ref="imgInput" type="file" accept="image/png,image/jpeg,image/gif,image/webp" multiple style="display:none" @change="onImg" />
      </template>
    </div>

    <div class="as-foot">
      <button class="as-done" type="button" @click="$emit('close')">完成</button>
    </div>
  </BottomSheet>
</template>

<script setup>
import { computed, inject, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { CheckOutlined, DatabaseOutlined, FileTextOutlined, RobotOutlined, PaperClipOutlined, PictureOutlined,
  OrderedListOutlined, AimOutlined } from '@ant-design/icons-vue'
import BottomSheet from './BottomSheet.vue'
import { fmtSize, histItemTitle } from '../chat/projections'

const props = defineProps({ open: { type: Boolean, default: false } })
defineEmits(['close', 'goal'])

const engine = inject('wqChat')
const {
  skillList, pickedSkills, toggleSkill, skillAvaStyle, skillDoc,
  mentionTab, mentionQuery, mentionLoading, mentionKbFiltered, mentionDocFiltered, mentionAgentFiltered,
  mentionDocStatus, isMentioned, toggleMention, switchMentionTab, loadMentionCandidates, agentList, loadAgents,
  histPool, isHistPicked, toggleHistoryRef,
  pendingFiles, pendingImages, addFiles, removePendingFile, removePendingImage,
  pendingHistoryRefs, pendingMentions,
  // 本轮模式：计划开关与目标入口都吃引擎那份共享状态（ai_plan_mode 两端同源，见主壳 chip）。
  // 目标入口的显隐与 PC 同一条判据：这一条会话已有活目标时不再出现（换目标走条子上的编辑/删除），
  // 目标结束后可以再定一条新的。
  planModeOn, setPlanMode, goalModeOn, sessionGoal, goalTerminal
} = engine

// 历史引用候选：PC 侧在视图层（histQuery/histCandidates 未进引擎），移动壳按同一口径本地实现
//（筛选 + 最近在前 + 最多 60 条），数据源是引擎的 histPool（已落库、非流式、正文非空的消息）。
const histQuery = ref('')
const histQueryNorm = computed(() => histQuery.value.trim())
const histCandidates = computed(() => {
  const q = histQueryNorm.value.toLowerCase()
  const pool = histPool.value.filter(m => !q || String(m.content).toLowerCase().includes(q))
  return pool.slice(-60).reverse()
})

const tab = ref('skill')
const TABS = [
  { key: 'skill', label: '技能', title: '技能', count: () => pickedSkills.value.length },
  { key: 'ref', label: '引用', title: '引用资料', count: () => pendingMentions.value.length },
  { key: 'hist', label: '历史', title: '引用历史问答', count: () => pendingHistoryRefs.value.length },
  { key: 'file', label: '附件', title: '附件', count: () => pendingFiles.value.length + pendingImages.value.length }
]
const switchTab = k => {
  tab.value = k
  // 引用页首次进入才拉候选（与 PC 面板的懒加载同口径）；智能体候选复用下拉数据
  if (k === 'ref') {
    if (!mentionKbFiltered.value.length && !mentionDocFiltered.value.length) loadMentionCandidates()
    if (!agentList.value.length) loadAgents()
  }
}
watch(() => props.open, v => {
  if (!v) return
  // 打开即按已选内容跳到对应页（用户点「+」通常是要加东西，先给最常用的技能页）
  tab.value = 'skill'
})

const listLen = computed(() => mentionTab.value === 'kb' ? mentionKbFiltered.value.length
  : mentionTab.value === 'agent' ? mentionAgentFiltered.value.length : mentionDocFiltered.value.length)

// 附件选择：与主输入框同一条管线（图片压缩为 dataURL、文件先上传换 fileId）
const fileInput = ref(null)
const imgInput = ref(null)
const MAX_MB = 15
const onFile = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (files.some(f => f.size > MAX_MB * 1024 * 1024 && !f.type.startsWith('image/'))) { message.warning(`单个附件不能超过 ${MAX_MB}MB`); return }
  addFiles(files)
}
const onImg = e => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  addFiles(files)
}
</script>

<style scoped>
.as { display: flex; flex-direction: column; gap: 2px; }
/* 本轮模式：压在页签条上方、切页签不消失——它管的是「这一轮怎么答」，不是「加什么内容」，
   所以不给它一个页签（页签条那四条是并列的内容来源，混进一条状态开关会读成「选一种模式」）。 */
.as-mode { padding-bottom: 8px; border-bottom: 1px solid var(--app-border); }
.as-mode .as-desc { white-space: normal; display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; }
/* 开关：触屏没有 hover，开启态必须落在形状上——整行只换个底色说明不了「这是个可以拨的开关」 */
.as-sw { flex: none; width: 44px; height: 26px; border-radius: 999px; position: relative; background: var(--app-border); }
.as-sw::after { content: ''; position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: #fff; transition: transform .15s; }
.as-sw.on { background: var(--app-warn-text); }
.as-sw.on::after { transform: translateX(18px); }
.as-tabs { display: flex; gap: 6px; padding: 8px 0 8px; position: sticky; top: 0; background: var(--app-panel); z-index: 1; }
.as-tab {
  flex: 1; min-height: 38px; border: 1px solid var(--app-border); border-radius: 10px;
  background: var(--app-panel); color: var(--app-text2); font-size: 13px; touch-action: manipulation;
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
}
.as-tab.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); color: var(--app-accent); font-weight: 500; }
.as-tab-n { font-size: 11px; background: var(--app-accent); color: #fff; border-radius: 999px; padding: 0 5px; line-height: 16px; }
.as-subtabs { display: flex; gap: 6px; padding: 0 0 8px; }
.as-subtab { flex: 1; min-height: 34px; border: none; border-radius: 8px; background: var(--app-panel-2); color: var(--app-text2); font-size: 13px; touch-action: manipulation; }
.as-subtab.on { background: var(--app-accent-weak); color: var(--app-accent); font-weight: 500; }
.as-filter {
  width: 100%; box-sizing: border-box; min-height: 40px; padding: 8px 12px; margin-bottom: 6px;
  border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-panel-2);
  color: var(--app-text); font-size: 16px; outline: none;
}
.as-row {
  display: flex; align-items: center; gap: 10px; width: 100%; min-height: 48px;
  padding: 8px 10px; border: 1px solid transparent; border-radius: 10px;
  background: transparent; color: var(--app-text); text-align: left; touch-action: manipulation;
}
.as-row.on { background: var(--app-accent-weak); border-color: var(--app-accent-border); }
.as-row.static { cursor: default; }
/* 技能行：整行是「勾选/取消」的命中区，右侧另给一个「正文」入口（按钮不能嵌套，故行改为 div） */
.as-hit { flex: 1; min-width: 0; display: flex; align-items: center; gap: 10px; padding: 0; border: 0;
  background: transparent; color: inherit; font: inherit; text-align: left; touch-action: manipulation; }
.as-hit:disabled { cursor: default; }
.as-doc { flex: none; padding: 6px 4px; border: 0; background: transparent; color: var(--app-accent);
  font-size: 13px; touch-action: manipulation; }
.as-flag { margin-left: 6px; padding: 2px 6px; border-radius: 999px; font-size: 11px;
  background: var(--app-panel-2); color: var(--app-text3); }
.as-flag.off { background: var(--app-danger-weak); color: var(--app-danger-text); }
.as-ava { width: 30px; height: 30px; flex: none; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; font-size: 14px; color: var(--app-accent); }
.as-ic { color: var(--app-text3); flex: none; font-size: 16px; }
.as-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.as-name { font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.as-desc { font-size: 12px; color: var(--app-text3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.as-check { color: var(--app-accent); flex: none; }
.as-role { width: 26px; height: 26px; flex: none; border-radius: 50%; background: var(--app-panel-2); color: var(--app-text3); font-size: 12px; display: inline-flex; align-items: center; justify-content: center; }
.as-del { width: 34px; height: 34px; flex: none; border: none; background: transparent; color: var(--app-text3); font-size: 18px; touch-action: manipulation; }
.as-hint { font-size: 12px; color: var(--app-text3); padding: 4px 2px 8px; line-height: 1.6; }
.as-empty { padding: 20px 0; text-align: center; color: var(--app-text3); font-size: 13px; }
.as-section { font-size: 12px; color: var(--app-text3); padding: 10px 2px 2px; }
.as-imgs { display: flex; flex-wrap: wrap; gap: 8px; padding: 4px 2px; }
.as-img { position: relative; }
.as-img img { width: 72px; height: 72px; object-fit: cover; border-radius: 8px; display: block; }
.as-img .as-del { position: absolute; top: -8px; right: -8px; background: var(--app-panel); border-radius: 50%; width: 24px; height: 24px; font-size: 14px; box-shadow: var(--app-shadow-sm); }
.as-foot { position: sticky; bottom: 0; padding: 8px 0 0; background: var(--app-panel); }
.as-done { width: 100%; min-height: 44px; border: none; border-radius: 12px; background: var(--app-accent); color: #fff; font-size: 15px; touch-action: manipulation; }
</style>
