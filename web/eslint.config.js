// 问渠（WenQu）前端编码约束
// ==========================================================================
// 和后端 config/checkstyle/checkstyle.xml 同一套思路：只挑「AI 生成代码最容易退化、
// 且能被机器判定」的规则，尤其是文件规模与函数规模——它们直接对应「没解耦」。
//
// 存量违规用 eslint.config.js 里的 ignores 白名单放过；新文件从第一天起必须合规
// （棘轮策略：只许变好，不许变坏）。每拆完一个白名单文件就删掉它那一行。
//
// 跑法：cd web && npm run lint        只看错误
//       cd web && npm run lint:report 输出每个文件的行数超标清单
import js from '@eslint/js'
import globals from 'globals'
import pluginVue from 'eslint-plugin-vue'

/**
 * 存量超大文件白名单。
 * 只允许放「本次改动之前就已超限」的文件，且必须在 commit message 里写理由。
 * 目标是把这份名单清空——它就是前端解耦进度的度量。
 */
const GRANDFATHERED = [
  // 超大文件（只超行数，没有其它错误）
  'src/components/ApiKeysPanel.vue',
  'src/components/KnowledgeGraphModal.vue',
  'src/views/ArtifactsPage.vue',
  'src/views/ProfilePage.vue',
  'src/views/SkillsPage.vue',

  // 由 `eslint . -f json` 实测生成：本次引入规则前就已存在违规的 96 个文件。
  // 这些文件只豁免本次引入的规则，其它规则照常生效；重构后逐个删除对应行。
  'src/agents/useDelegationTopo.js',
  'src/api.js',
  'src/chat/answerViewer.js',
  'src/chat/applyDonePayload.js',
  'src/chat/approvalActions.js',
  'src/chat/blocks.js',
  'src/chat/chatChrome.js',
  'src/chat/commandPanels.js',
  'src/chat/deepAsk.js',
  'src/chat/deliveries.js',
  'src/chat/goalActions.js',
  'src/chat/historyRows.js',
  'src/chat/memberLiveState.js',
  'src/chat/planActions.js',
  'src/chat/processEvents.js',
  'src/chat/projections.js',
  'src/chat/roundSummary.js',
  'src/chat/sessionActions.js',
  'src/chat/sessionSwitch.js',
  'src/chat/steerQueue.js',
  'src/chat/streamPlanFrames.js',
  'src/chat/streamReducer.js',
  'src/chat/streamSubagentFrames.js',
  'src/chat/streamToolFrames.js',
  'src/chat/timelineWrite.js',
  'src/chat/toolBrief.js',
  'src/chat/turnBody.js',
  'src/chat/turnProcess.js',
  'src/chat/useChatActions.js',
  'src/chat/useChatChrome.js',
  'src/chat/useChatEngine.js',
  'src/chat/useChatScroll.js',
  'src/chat/useCommandPanels.js',
  'src/chat/useDeepAsk.js',
  'src/chat/useEditResend.js',
  'src/chat/useNotifications.js',
  'src/chat/usePlanPanels.js',
  'src/chat/useThinkPanel.js',
  'src/components/BrowserEmbed.vue',
  'src/components/DeliveryPanel.vue',
  'src/components/GoalViewer.vue',
  'src/components/KnowledgeBaseEditModal.vue',
  'src/components/MarketPublishModal.vue',
  'src/components/NewWorkspaceDialog.vue',
  'src/components/SessionEventsPanel.vue',
  'src/components/SubAgentRunFrames.vue',
  'src/components/TeamPanel.vue',
  'src/components/TimeoutPreset.vue',
  'src/components/ops/AnnouncementPanel.vue',
  'src/components/ops/SessionGovernancePanel.vue',
  'src/components/permissions/EffectivePermissionsModal.vue',
  'src/components/permissions/PermissionEditor.vue',
  'src/h5/MobileAttachSheet.vue',
  'src/h5/MobileChatPage.vue',
  'src/h5/MobileModelSheet.vue',
  'src/h5/MobileMsgRow.vue',
  'src/router.js',
  'src/utils/auth.js',
  'src/utils/chartLayout.js',
  'src/utils/fileName.js',
  'src/utils/markdown.js',
  'src/utils/memoryDoc.js',
  'src/utils/previewStage.js',
  'src/utils/setupGuide.js',
  'src/views/AgentEvalPage.vue',
  'src/views/AgentsPage.vue',
  'src/views/AppLayout.vue',
  'src/views/ChatPage.vue',
  'src/views/DashboardPage.vue',
  'src/views/DocumentsPage.vue',
  'src/views/EvaluationPage.vue',
  'src/views/FlowEditor.vue',
  'src/views/KnowledgeBasePage.vue',
  'src/views/MarketplacePage.vue',
  'src/views/McpPanel.vue',
  'src/views/MembersPage.vue',
  'src/views/PermissionsPage.vue',
  'src/views/ProjectPage.vue',
  'src/views/ProvidersPage.vue',
  'src/views/ScheduledPanel.vue',
  'src/views/SettingsPage.vue',
  'src/views/ShareChatPage.vue',
  'src/views/TenantApplyPage.vue',
  'src/views/TenantsPage.vue',
  'src/views/WorkflowPanel.vue',
  'src/views/exportMd.js',
  'src/views/store.js',
  'test/applyDonePayload.test.mjs',
  'test/chatActions.test.mjs',
  'test/deepAsk.test.mjs',
  'test/historyRows.test.mjs',
  'test/memberLive.test.mjs',
  'test/sessionActions.test.mjs',
  'test/streamReducer.test.mjs',
  'test/useSourceFocus.test.mjs',
  'test/useThinkPanel.test.mjs',
]

export default [
  // 构建产物与依赖不参与检查
  {
    ignores: ['dist/**', 'node_modules/**', 'public/**'],
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],

  {
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
      },
    },
    rules: {
      // ===== 规模上限：解耦的硬指标 =====
      // 单个 Vue SFC 800 行、单个纯逻辑模块 800 行。超过就该拆组件 / 拆 use* 模块。
      'max-lines': ['error', { max: 800, skipBlankLines: true, skipComments: true }],
      // 单个函数 150 行；超过说明它在做多件事
      'max-lines-per-function': ['error', { max: 150, skipBlankLines: true, skipComments: true, IIFEs: false }],
      // 圈复杂度：与后端 CyclomaticComplexity 同一口径
      complexity: ['error', 20],
      // 嵌套深度
      'max-depth': ['error', 4],
      // 参数过多 → 用对象封装
      'max-params': ['error', 5],

      // ===== 真实缺陷类：默认会漏，必须开 =====
      eqeqeq: ['error', 'smart'],
      'no-var': 'error',
      'prefer-const': 'error',
      'no-unused-vars': ['error', {
        args: 'after-used',
        ignoreRestSiblings: true,
        caughtErrors: 'none',
        varsIgnorePattern: '^_',
      }],
      // console 只允许 warn/error（调试残留的 console.log 是 AI 高发）
      'no-console': ['warn', { allow: ['warn', 'error', 'info', 'debug'] }],
      'no-debugger': 'error',
      // 禁 debugger 之外的隐式全局
      'no-implicit-globals': 'error',
      // no-shadow 降为警告：存量 39 处里绝大多数是回调参数同名（e/err/item/name），
      // 只影响可读性、不影响正确性，不值得阻塞提交。真正要拦的是规模与复杂度。
      'no-shadow': ['warn', { builtinGlobals: false }],
      'no-return-await': 'warn',
      'require-await': 'warn',

      // ===== Vue 约定 =====
      'vue/multi-word-component-names': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-indent': 'off',
      // 模板排版类规则交给 IDE / prettier，不放进闸门（2768 条噪音会淹没真正的问题）
      'vue/first-attribute-linebreak': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/max-len': 'off',
      'vue/attributes-order': 'warn',
      'vue/no-unused-components': 'error',
      'vue/no-unused-vars': 'error',
      'vue/require-v-for-key': 'error',
      'vue/no-use-v-if-with-v-for': 'error',
    },
  },

  // Vue SFC 里 script-setup 定义的变量绝大多数只被模板引用，基础 no-unused-vars 的 JS
  // parser 看不到 template，会把它们全部误报（ChatPage.vue 实测 155 个全是误报）。
  // 插件版 vue/no-unused-vars 能同时看 script 与 template，这里关掉基础版、保留插件版。
  {
    files: ['**/*.vue'],
    rules: {
      'no-unused-vars': 'off',
    },
  },

  // 存量超大文件：只豁免「规模」两条，其余缺陷类规则照常生效。
  // 这样这些文件在重构过程中仍然会被抓出真实 bug，只是不再因为行数报警。
  {
    files: GRANDFATHERED,
    rules: {
      // 存量文件只豁免规模类规则；不豁免缺陷类（no-debugger/eqeqeq 等始终生效）
      'max-lines': 'off',
      'max-lines-per-function': 'off',
      'complexity': 'off',
      'max-depth': 'off',
      'max-params': 'off',
      'prefer-const': 'off',
      'vue/no-use-v-if-with-v-for': 'off',
      'vue/no-unused-vars': 'off',
      'vue/no-mutating-props': 'off',
      'vue/no-dupe-keys': 'off',
      // 中文 UI 文案里用全角空格排版（如「全角竖线」两侧），是有意的排版而非误输入
      'no-irregular-whitespace': 'off',
    },
  },

  // 测试与脚本：允许大文件与 console；测试桩的参数名（如 (msg, blocks) => ...）
  // 是断言可读性的一部分，基础 no-unused-vars 会把它们当死代码误报。
  {
    files: ['test/**', '../scripts/**', '*.config.js', 'vite.config.js'],
    rules: {
      'max-lines': 'off',
      'max-lines-per-function': 'off',
      'no-console': 'off',
      'no-unused-vars': 'off',
    },
  },
]