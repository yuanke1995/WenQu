<template>
  <div class="app-page ev-page">
    <div class="app-page-head">
      <h3 class="app-page-title">检索评估</h3>
      <span class="head-stat">评估集 · 调参实验 · 答案质量 · 自动体检</span>
      <span class="head-set">
        <span class="app-pill" :class="caseCount ? 'ok' : 'warn'">{{ caseCount }} 条用例</span>
        <span v-if="setInfo?.generatedAt" class="set-time">v{{ setInfo.version }} · {{ fmtTime(setInfo.generatedAt) }}</span>
      </span>
      <div class="head-acts">
        <span class="auto-pill" :class="autoTone">{{ autoText }}</span>
        <a-select v-model:value="scopeKbId" size="small" class="scope-sel" :options="scopeOptions" @change="reloadAll" />
        <button class="app-btn ghost" :disabled="!latestRunId" @click="copyRunLink">复制链接</button>
        <button class="app-btn ghost" :disabled="!latestRunId" @click="exportLatest">导出</button>
        <button class="app-btn" :disabled="checking" @click="doHealth">
          <thunderbolt-outlined />{{ checking ? '体检中…' : '立即体检' }}
        </button>
      </div>
    </div>

    <div class="app-page-body">
      <div class="ev-tabs">
        <button v-for="t in tabs" :key="t.key" class="ev-tab" :class="{ on: activeTab === t.key }"
                @click="switchTab(t.key)">
          {{ t.label }}
          <span v-if="t.badge" class="ev-tab-badge" :class="t.badgeTone">{{ t.badge }}</span>
        </button>
      </div>

      <a-spin :spinning="loading.overview && !overview">
        <!-- ==================== 总览 ==================== -->
        <div v-show="activeTab === 'overview'" class="ev-pane">
          <div v-if="!caseCount" class="app-card ev-empty">
            <div class="ev-empty-ico">⌁</div>
            <div class="ev-empty-title">还没有可用的评估集</div>
            <p>从历史问答的真实引用回放生成 25 条左右的评估集，先完成一次体检。<br>
              弱监督标签只代表「当时引用了什么」，后续可逐条人工确认。</p>
            <div class="ev-empty-acts">
              <a-input-number v-model:value="generateCount" :min="5" :max="500" style="width:100px" />
              <span class="muted">条用例</span>
              <button class="app-btn" :disabled="generating || !canWrite" @click="doGenerate(true)">
                {{ generating ? '生成中…' : '生成评估集' }}
              </button>
              <button class="app-btn ghost" @click="switchTab('cases')">先看看用例</button>
            </div>
          </div>

          <template v-else>
            <div v-if="overview?.paramsStale" class="ev-banner warn">
              <span class="tag warn">参数已变更</span>
              <span>线上参数在上次体检后改过，当前红绿灯读的是旧测量。建议重新体检后再对比。</span>
              <button class="app-link-btn" @click="doHealth">重新体检</button>
            </div>
            <div v-if="overview?.apply" class="ev-banner" :class="applyTone">
              <span class="tag" :class="applyTagTone">{{ applyLabel }}</span>
              <span>
                灰度应用「{{ overview.apply.groupName }}」· 观察至 {{ fmtTime(overview.apply.deadline) }}
                <template v-if="overview.apply.message">· {{ overview.apply.message }}</template>
              </span>
              <button v-if="overview.apply.status === 'observing' && canWrite" class="app-link-btn" @click="doRollback">手动回滚</button>
            </div>

            <div class="ev-kpi">
              <div class="app-card ev-health">
                <div class="ev-card-h">检索健康度 <span class="card-sub">4 项加权</span></div>
                <div class="ev-health-main">
                  <svg viewBox="0 0 80 80" class="ev-ring">
                    <circle cx="40" cy="40" r="31" fill="none" stroke="var(--app-border)" stroke-width="7" />
                    <circle cx="40" cy="40" r="31" fill="none" :stroke="healthColor" stroke-width="7"
                            stroke-linecap="round" :stroke-dasharray="`${healthDash} 195`" transform="rotate(-90 40 40)" />
                  </svg>
                  <div>
                    <div class="ev-health-score" :style="{ color: healthColor }">{{ health?.score ?? '—' }}<span> / 100</span></div>
                    <div class="ev-health-line">
                      <span class="app-pill" :class="healthPillTone">{{ health?.label || '待体检' }}</span>
                      <span v-if="health?.delta != null" class="ev-delta" :class="health.delta >= 0 ? 'up' : 'down'">
                        {{ health.delta >= 0 ? '▲' : '▼' }} {{ Math.abs(health.delta).toFixed(1) }}
                      </span>
                    </div>
                    <div class="ev-muted">{{ lastRunText }}</div>
                  </div>
                </div>
                <div class="ev-health-parts">
                  <span>recall@5 <b>{{ fmtMetric(metrics['recall@5'], 2) }}</b></span>
                  <span>MRR <b>{{ fmtMetric(metrics.MRR, 2) }}</b></span>
                  <span>命中 <b>{{ fmtPct(metrics.hitRate) }}</b></span>
                  <span>评估集 <b>{{ overview?.qualityScore ?? '—' }}</b></span>
                </div>
              </div>

              <div v-for="m in kpiCards" :key="m.key" class="app-card ev-metric">
                <div class="ev-metric-label">{{ m.label }}</div>
                <div class="ev-metric-num">{{ m.value }}<i v-if="m.unit">{{ m.unit }}</i></div>
                <div class="ev-metric-foot">
                  <span class="ev-delta" :class="m.deltaClass">{{ m.deltaText }}</span>
                  <span class="ev-muted">{{ m.hint }}</span>
                </div>
              </div>
            </div>

            <div class="ev-grid-2">
              <div class="app-card">
                <div class="ev-card-h">质量趋势
                  <span class="card-sub">最近 {{ overview?.trend?.length || 0 }} 次运行</span>
                </div>
                <EvalTrendChart v-if="overview?.trend?.length" :rows="overview.trend" :series="overviewSeries" height="230px" />
                <a-empty v-else :image="false" description="还没有运行记录，点右上「立即体检」开始" />
              </div>

              <div class="app-card">
                <div class="ev-card-h">评估集质量 <span class="card-sub">这把尺子有多可信</span></div>
                <div class="ev-quality">
                  <div class="ev-quality-score" :class="qualityTone">{{ overview?.qualityScore ?? 0 }}</div>
                  <div class="ev-quality-main">
                    <div class="ev-quality-bar"><i :style="{ width: (overview?.qualityScore || 0) + '%' }" :class="qualityTone"></i></div>
                    <div class="ev-muted">人工确认 {{ overview?.verifiedCount || 0 }} · 弱监督 {{ overview?.weakCount || 0 }} · 失效块 {{ overview?.staleExpected || 0 }}</div>
                  </div>
                </div>
                <div class="ev-quality-rows">
                  <div><span>有期望标签</span><b>{{ overview?.withExpectedCount || 0 }} / {{ caseCount }}</b></div>
                  <div><span>待人工确认</span><b>{{ overview?.weakCount || 0 }}</b></div>
                  <div><span>期望块失效</span><b :class="{ danger: (overview?.staleExpected || 0) > 0 }">{{ overview?.staleExpected || 0 }}</b></div>
                </div>
                <div class="ev-card-foot">
                  <button class="app-link-btn" @click="openCases('unverified')">去校验用例</button>
                  <button class="app-link-btn" @click="openCases('stale')">修复失效标签</button>
                </div>
              </div>
            </div>

            <div class="ev-grid-2">
              <div class="app-card">
                <div class="ev-card-h">需要处理 <span class="card-sub">失败优先</span>
                  <button class="app-link-btn" style="margin-left:auto" @click="openCases('miss')">查看全部</button>
                </div>
                <div v-if="issueRows.length" class="ev-issues">
                  <div v-for="r in issueRows" :key="r.key" class="ev-issue-row">
                    <span class="tag" :class="r.tone">{{ r.tag }}</span>
                    <div class="ev-issue-main">
                      <div class="ev-issue-q">{{ r.question }}</div>
                      <div class="ev-muted">{{ r.meta }}</div>
                    </div>
                    <button class="app-link-btn" @click="openCaseByQuestion(r.question)">看题目</button>
                    <button v-if="r.caseId" class="app-link-btn" @click="addToExperiment(r.caseId)">加入调参</button>
                  </div>
                </div>
                <a-empty v-else :image="false" description="本次体检没有发现明显落空题" />
              </div>

              <div class="app-card">
                <div class="ev-card-h">最近运行 <span class="card-sub">手动与自动同列</span>
                  <button class="app-link-btn" style="margin-left:auto" @click="switchTab('history')">全部运行</button>
                </div>
                <div v-if="recentRuns.length" class="ev-recent">
                  <div v-for="r in recentRuns" :key="r.runId" class="ev-recent-row" @click="openRunRow(r)">
                    <span class="ev-muted">{{ fmtTime(r.time) }}</span>
                    <span class="tag" :class="r.trigger === 'scheduled' ? 'grey' : 'blue'">{{ r.trigger === 'scheduled' ? '自动' : '手动' }}</span>
                    <span class="ev-recent-main">{{ r.bestGroupName || '当前配置' }}</span>
                    <span class="ev-recent-num">{{ fmtMetric(r.recall5, 2) }}</span>
                    <span class="app-pill" :class="runPillTone(r.status)">{{ runStatusText(r.status) }}</span>
                  </div>
                </div>
                <a-empty v-else :image="false" description="还没有运行记录" />
              </div>
            </div>
          </template>
        </div>

        <!-- ==================== 评估集 ==================== -->
        <div v-show="activeTab === 'cases'" class="ev-pane">
          <div class="app-card">
            <div class="ev-card-h">
              评估集 <span class="card-sub">{{ caseState.set ? `v${caseState.set.version} · ${caseCount} 条` : '暂无' }}</span>
              <span class="ev-sp"></span>
              <button v-if="canWrite" class="app-link-btn" @click="openImport">导入</button>
              <button class="app-link-btn" @click="doExportCases">导出</button>
              <button v-if="canWrite" class="app-link-btn" @click="openCase(null)">新建用例</button>
            </div>
            <div class="ev-set-strip" v-if="caseState.set">
              <span>生成于 {{ fmtTime(caseState.set.generatedAt) }}</span><span>·</span>
              <span>来源 {{ setSourceText }}</span><span>·</span>
              <span>质量分 <b>{{ overview?.qualityScore ?? '—' }}</b></span>
              <span class="ev-sp"></span>
              <span v-if="selectedCaseIds.length">已选 {{ selectedCaseIds.length }} 条
                <button class="app-link-btn" @click="runSelectedCases">运行所选</button>
              </span>
            </div>

            <div class="ev-toolbar">
              <button v-for="f in caseFilters" :key="f.key" class="ev-chip" :class="{ on: caseState.filter === f.key }"
                      @click="setCaseFilter(f.key)">
                {{ f.label }} <b>{{ f.count ?? 0 }}</b>
              </button>
              <a-input v-model:value="caseState.keyword" size="small" allow-clear placeholder="搜索问题 / 期望知识块…"
                       class="ev-search" @press-enter="loadCases(1)" @change="onCaseSearchChange" />
              <span class="ev-sp"></span>
              <button v-if="canWrite" class="app-btn ghost" :disabled="generating" @click="doGenerate(false)">
                {{ generating ? '生成中…' : '重新生成评估集' }}
              </button>
            </div>

            <a-table size="small" row-key="id" :data-source="caseState.rows" :columns="caseColumns"
                     :pagination="false" :loading="loading.cases"
                     :row-selection="{ selectedRowKeys: selectedCaseIds, onChange: onSelectCases, preserveSelectedRowKeys: true }"
                     :locale="{ emptyText: caseState.set ? '没有符合条件的用例' : '还没有评估集，请先生成' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'question'">
                  <div class="ev-case-q">{{ record.question }}</div>
                  <div v-if="record.tags?.length" class="ev-case-tags">
                    <a-tag v-for="t in record.tags" :key="t" style="margin-right:4px">{{ t }}</a-tag>
                  </div>
                </template>
                <template v-else-if="column.key === 'expected'">
                  <div class="ev-expect-tags">
                    <a-tag v-for="t in (record.expectedTitles || []).slice(0, 3)" :key="t.id"
                           :color="t.missing ? 'red' : 'default'" style="margin-right:4px;margin-bottom:2px">
                      {{ t.title }}
                    </a-tag>
                    <span v-if="(record.expectedTitles || []).length > 3" class="ev-muted">+{{ record.expectedTitles.length - 3 }}</span>
                  </div>
                </template>
                <template v-else-if="column.key === 'source'">
                  <span class="tag" :class="sourceTone(record.source)">{{ sourceText(record.source) }}</span>
                </template>
                <template v-else-if="column.key === 'quality'">
                  <span class="tag" :class="qualityTagTone(record.quality)">{{ qualityText(record.quality) }}</span>
                </template>
                <template v-else-if="column.key === 'last'">
                  <span v-if="record.lastRecall5 == null" class="ev-muted">—</span>
                  <span v-else :class="record.lastRecall5 === 0 ? 'ev-danger' : (record.lastRecall5 < 0.5 ? 'ev-warn' : 'ev-ok')">
                    {{ fmtMetric(record.lastRecall5, 2) }}
                  </span>
                </template>
                <template v-else-if="column.key === 'ops'">
                  <button v-if="canWrite" class="app-link-btn" @click="openCase(record)">编辑</button>
                  <button v-if="canWrite" class="app-link-btn danger" @click="removeCase(record)">删除</button>
                  <button class="app-link-btn" @click="runOneCase(record)">运行</button>
                </template>
              </template>
            </a-table>

            <div class="ev-pager">
              <span class="ev-muted">共 {{ caseState.total }} 条</span>
              <a-pagination size="small" :current="caseState.page" :page-size="caseState.size" :total="caseState.total"
                            :show-size-changer="false" @change="loadCases" />
            </div>
          </div>
        </div>

        <!-- ==================== 调参实验 ==================== -->
        <div v-show="activeTab === 'lab'" class="ev-pane">
          <div class="app-card">
            <div class="ev-exp-bar">
              <span class="ev-label">用例范围</span>
              <span class="ev-seg">
                <button :class="{ on: experiment.scope === 'full' }" @click="experiment.scope = 'full'">全量 {{ caseCount }}</button>
                <button :class="{ on: experiment.scope === 'miss' }" @click="experiment.scope = 'miss'">仅落空 {{ overview?.issues?.miss?.length || 0 }}</button>
                <button :class="{ on: experiment.scope === 'selected' }" @click="experiment.scope = 'selected'">选中 {{ selectedCaseIds.length }}</button>
              </span>
              <span class="ev-label">K 值</span>
              <span class="ev-kchips">
                <button v-for="k in [5, 10, 20]" :key="k" class="ev-chip" :class="{ on: experiment.kList.includes(k) }"
                        @click="toggleK(k)">{{ k }}</button>
              </span>
              <span class="ev-label">充分性评判</span>
              <a-switch v-model:checked="experiment.includeJudge" size="small" />
              <span class="ev-sp"></span>
              <span class="ev-muted">{{ experimentEstimate }}</span>
            </div>

            <div class="ev-group-grid">
              <div v-for="(g, gi) in groups" :key="gi" class="ev-group" :class="{ base: gi === 0, off: g.enabled === false }">
                <div class="ev-group-h">
                  <a-input v-model:value="g.name" size="small" class="ev-group-name" :bordered="false" />
                  <span class="tag" :class="gi === 0 ? 'blue' : 'grey'">{{ gi === 0 ? '基线' : '挑战 ' + gi }}</span>
                  <a-dropdown :trigger="['click']">
                    <button class="ev-icon-btn">···</button>
                    <template #overlay>
                      <a-menu @click="({ key }) => groupMenu(key, gi)">
                        <a-menu-item key="copy">复制</a-menu-item>
                        <a-menu-item key="toggle">{{ g.enabled === false ? '启用' : '停用' }}</a-menu-item>
                        <a-menu-item v-if="gi > 0" key="delete" danger>删除</a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </div>
                <div class="ev-group-mode">
                  <a-select v-model:value="g.mode" size="small" style="width:100%" :options="[
                    { value: 'normal', label: '单路 normal' },
                    { value: 'multi', label: '多路 multi' }
                  ]" />
                </div>
                <div class="ev-params">
                  <label>向量权重 <a-input-number v-model:value="g.vectorWeight" size="small" :step="0.05" :placeholder="cur('retrieval.vectorWeight')" /></label>
                  <label>关键词权重 <a-input-number v-model:value="g.keywordWeight" size="small" :step="0.05" :placeholder="cur('retrieval.keywordWeight')" /></label>
                  <label>向量阈值 <a-input-number v-model:value="g.vecThreshold" size="small" :step="0.05" :placeholder="cur('retrieval.vecThreshold')" /></label>
                  <label>关键词上限 <a-input-number v-model:value="g.keywordLimit" size="small" :min="1" :placeholder="cur('retrieval.keywordLimit')" /></label>
                  <label>召回条数 <a-input-number v-model:value="g.topK" size="small" :min="1" :placeholder="cur('retrieval.vectorTopK')" /></label>
                  <label>低置信策略
                    <a-select v-model:value="g.lowConfidencePolicy" size="small" allow-clear :placeholder="cur('retrieval.lowConfidencePolicy')" :options="[
                      { value: 'top1', label: 'top1 保底' }, { value: 'off', label: 'off 严格门' }
                    ]" />
                  </label>
                  <label>保底分 <a-input-number v-model:value="g.top1Floor" size="small" :step="0.01" :placeholder="cur('retrieval.top1Floor')" /></label>
                  <label class="ev-param-switch">重排
                    <a-switch size="small" :checked="g.rerankEnabled == null ? true : false"
                              @change="v => g.rerankEnabled = v ? null : false" />
                    <span v-if="g.rerankEnabled == null" class="ev-muted">跟随（库绑模型才重排）</span>
                    <span v-else class="ev-muted">强制关</span>
                  </label>
                </div>
                <div class="ev-group-diff" :class="{ none: gi === 0 }">{{ groupDiff(g, gi) }}</div>
              </div>
              <button class="ev-group-add" :disabled="groups.length >= 8" @click="addGroup('blank')">
                <span class="ev-plus">＋</span>
                <span>添加参数组</span>
                <span class="ev-muted">关键词优先 / 向量优先 / 向量+重排 / 多路</span>
                <span class="ev-muted">最多 8 组</span>
              </button>
            </div>

            <div class="ev-run-row">
              <button v-if="!experiment.running" class="app-btn" :disabled="!canWrite" @click="runExperiment">
                <thunderbolt-outlined /> 运行实验
              </button>
              <button v-else class="app-btn ghost" @click="stopExperiment">取消运行</button>
              <span class="ev-muted">上次运行 {{ lastRunText }}</span>
              <span v-if="experiment.running" class="ev-progress">
                <span class="ev-progress-track"><i :style="{ width: progressPct + '%' }"></i></span>
                {{ experiment.progress.done || 0 }} / {{ experiment.progress.total || 0 }}
              </span>
            </div>

            <template v-if="experiment.detail">
              <div v-if="experiment.detail.run?.warning" class="ev-banner warn">
                <span class="tag warn">运行提示</span><span>{{ experiment.detail.run.warning }}</span>
              </div>
              <div v-if="experiment.detail.run?.error" class="ev-banner danger">
                <span class="tag err">运行失败</span><span>{{ experiment.detail.run.error }}</span>
              </div>
              <div v-if="experiment.detail.run?.timedOutGroups?.length" class="ev-banner warn">
                <span class="tag warn">超时组</span><span>{{ experiment.detail.run.timedOutGroups.join('、') }}</span>
              </div>
              <div v-if="experiment.detail.run?.staleExpected" class="ev-banner">
                <span class="tag grey">失效标签</span>
                <span>{{ experiment.detail.run.staleExpected }} 个期望块已失效，已从本轮指标中剔除。</span>
                <button class="app-link-btn" @click="openCases('stale')">去修复</button>
              </div>

              <div v-if="conclusion" class="ev-conclusion">
                <div>{{ conclusion }}</div>
                <button v-if="bestGroup && bestGroup.name !== firstGroup?.name && canWrite" class="app-btn"
                        @click="applyGroup(bestGroup)">应用最佳组</button>
              </div>

              <a-table size="small" row-key="name" :data-source="experiment.detail.groups || []"
                       :columns="resultColumns" :pagination="false" :locale="{ emptyText: '没有组结果' }">
                <template #bodyCell="{ column, record, index }">
                  <template v-if="column.key === 'name'">
                    <span :class="{ 'ev-ok': index === bestIndex }">{{ record.name }}</span>
                    <span v-if="index === bestIndex" class="tag ok" style="margin-left:6px">最佳</span>
                  </template>
                  <template v-else-if="column.key === 'mode'">
                    <span class="tag grey">{{ record.mode }}</span>
                  </template>
                  <template v-else-if="column.key === 'delta'">
                    <span v-if="index === 0" class="ev-muted">—</span>
                    <span v-else :class="deltaOf(record) > 0 ? 'ev-ok' : (deltaOf(record) < 0 ? 'ev-danger' : 'ev-muted')">
                      {{ deltaOf(record) > 0 ? '+' : '' }}{{ deltaOf(record).toFixed(2) }}
                    </span>
                  </template>
                  <template v-else-if="column.key === 'judgeScore'">
                    <span>{{ record.judgeScore == null ? '—' : fmtPct(record.judgeScore) }}</span>
                  </template>
                  <template v-else-if="column.key === 'ops'">
                    <button v-if="index > 0 && canWrite" class="app-link-btn" @click="applyGroup(record)">应用此组</button>
                    <button class="app-link-btn" @click="loadExperimentResults(record.name)">明细</button>
                  </template>
                  <template v-else>
                    <span>{{ column.metric ? fmtMetric(record.metrics?.[column.metric], 3) : '—' }}</span>
                    <div v-if="column.ci && record.ci?.[column.metric]?.length" class="ev-ci">
                      [{{ record.ci[column.metric][0] }}, {{ record.ci[column.metric][1] }}]
                    </div>
                  </template>
                </template>
              </a-table>

              <div class="ev-exp-foot">
                <span>逐题明细：</span>
                <a-select v-model:value="experiment.group" size="small" style="width:180px"
                          :options="(experiment.detail.groups || []).map(g => ({ value: g.name, label: g.name }))"
                          @change="loadExperimentResults" />
                <span class="ev-muted">按失败优先排序，展示前 {{ experimentResults.length }} 条</span>
                <span class="ev-sp"></span>
                <button class="app-link-btn" @click="exportCurrentRun">导出报告</button>
              </div>

              <div class="ev-case-results">
                <div v-for="r in experimentResults" :key="r.id" class="ev-case-result">
                  <span class="tag" :class="r.firstRank ? (r.recallAtK?.['recall@5'] < 0.5 ? 'warn' : 'ok') : 'err'">
                    {{ r.firstRank ? '#' + r.firstRank : '落空' }}
                  </span>
                  <div class="ev-case-result-main">
                    <div class="ev-case-q">{{ r.question }}</div>
                    <div class="ev-muted">
                      期望 {{ r.expectedCount }} 块 · 命中 {{ r.hitCount }} · recall@5 {{ fmtMetric(r.recallAtK?.['recall@5'], 2) }} · MRR {{ fmtMetric(r.mrr, 2) }}
                      <template v-if="r.judgeCovered != null"> · 充分性 {{ r.judgeCovered ? '足以回答' : '不足' }}</template>
                    </div>
                  </div>
                  <div class="ev-case-hits">
                    <a-tag v-for="h in (r.hits || []).slice(0, 3)" :key="h.knowledgeId"
                           :color="(r.expectedIds || []).includes(h.knowledgeId) ? 'green' : 'default'">
                      {{ h.title || h.knowledgeId }}
                    </a-tag>
                  </div>
                </div>
                <a-empty v-if="!experimentResults.length" :image="false" description="该组还没有逐题结果" />
              </div>

              <div v-if="activeSegments.length" class="app-card ev-segments">
                <div class="ev-card-h">分群指标 <span class="card-sub">看哪个库/哪类问题在退化</span></div>
                <a-collapse ghost :bordered="false">
                  <a-collapse-panel key="tag" header="按标签">
                    <div v-for="s in activeSegmentsByTag" :key="s.segment" class="ev-seg-row">
                      <span>{{ s.segment }}</span><span class="ev-muted">{{ s.caseCount }} 条</span>
                      <b>recall@5 {{ fmtMetric(s.metrics?.['recall@5'], 2) }}</b>
                      <b>MRR {{ fmtMetric(s.metrics?.MRR, 2) }}</b>
                    </div>
                  </a-collapse-panel>
                  <a-collapse-panel key="kb" header="按知识库">
                    <div v-for="s in activeSegmentsByKb" :key="s.segment" class="ev-seg-row">
                      <span>{{ s.segment }}</span><span class="ev-muted">{{ s.caseCount }} 条</span>
                      <b>recall@5 {{ fmtMetric(s.metrics?.['recall@5'], 2) }}</b>
                      <b>MRR {{ fmtMetric(s.metrics?.MRR, 2) }}</b>
                    </div>
                  </a-collapse-panel>
                  <a-collapse-panel key="docType" header="按文档类型">
                    <div v-for="s in activeSegmentsByDocType" :key="s.segment" class="ev-seg-row">
                      <span>{{ s.segment }}</span><span class="ev-muted">{{ s.caseCount }} 条</span>
                      <b>recall@5 {{ fmtMetric(s.metrics?.['recall@5'], 2) }}</b>
                      <b>MRR {{ fmtMetric(s.metrics?.MRR, 2) }}</b>
                    </div>
                  </a-collapse-panel>
                </a-collapse>
              </div>
            </template>
            <a-empty v-else :image="false" description="配置参数组后运行实验，结果会落库并出现在历史趋势里" />
          </div>
        </div>

        <!-- ==================== 答案质量 ==================== -->
        <div v-show="activeTab === 'answer'" class="ev-pane">
          <div class="app-card">
            <div class="ev-card-h">答案质量评测
              <span class="card-sub">逐条检索 → 生成回答 → 评判忠实度 / 相关性；每条 2 次模型调用</span>
            </div>
            <div class="ev-exp-bar">
              <span class="ev-label">用例来源</span>
              <span class="ev-seg">
                <button :class="{ on: answer.source === 'miss' }" @click="answer.source = 'miss'">仅检索落空 {{ overview?.issues?.miss?.length || 0 }}</button>
                <button :class="{ on: answer.source === 'low' }" @click="answer.source = 'low'">低相关 {{ overview?.issues?.low?.length || 0 }}</button>
                <button :class="{ on: answer.source === 'selected' }" @click="answer.source = 'selected'">选中 {{ selectedCaseIds.length }}</button>
                <button :class="{ on: answer.source === 'all' }" @click="answer.source = 'all'">全量 {{ caseCount }}</button>
              </span>
              <span class="ev-label">数量</span>
              <a-input-number v-model:value="answer.count" size="small" :min="1" :max="50" style="width:70px" />
              <span class="ev-muted">上限 50</span>
              <span class="ev-sp"></span>
              <span class="ev-muted">评判模型 {{ overview?.judgeModel || '未配置' }} · 预计 {{ answer.count * 2 }} 次调用</span>
              <button class="app-btn" :disabled="answer.running || !canWrite" @click="startAnswer">
                {{ answer.running ? '评测中…' : '运行答案评测' }}
              </button>
            </div>
            <div v-if="answer.running" class="ev-progress" style="margin-top:8px">
              <span class="ev-progress-track"><i :style="{ width: answerPct + '%' }"></i></span>
              {{ answer.progress.done }} / {{ answer.progress.total }}
            </div>

            <a-alert v-if="answer.report?.skippedReason" type="warning" show-icon style="margin-top:10px"
                     :message="answer.report.skippedReason" />
            <template v-if="answer.report && !answer.report.skippedReason">
              <div class="ev-score-grid">
                <div class="ev-score-box"><div class="ev-muted">忠实度均分</div><div class="ev-score-num" :class="scoreTone(answer.report.avgFaithfulness)">{{ fmtPct(answer.report.avgFaithfulness) }}</div></div>
                <div class="ev-score-box"><div class="ev-muted">相关性均分</div><div class="ev-score-num" :class="scoreTone(answer.report.avgRelevance)">{{ fmtPct(answer.report.avgRelevance) }}</div></div>
                <div class="ev-score-box"><div class="ev-muted">用例数</div><div class="ev-score-num dim">{{ answer.report.caseCount }}</div><div class="ev-muted">{{ answer.report.runTime }}</div></div>
                <div class="ev-score-box"><div class="ev-muted">耗时</div><div class="ev-score-num dim">{{ answer.report.elapsedMs }}ms</div><div class="ev-muted">{{ answer.report.answerModel }}</div></div>
              </div>
              <a-table size="small" row-key="id" :data-source="answer.report.cases || []" :columns="answerColumns"
                       :pagination="{ pageSize: 10, size: 'small', hideOnSinglePage: true, showTotal: t => `共 ${t} 条` }"
                       style="margin-top:10px">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'question'"><span class="ev-case-q">{{ record.question }}</span></template>
                  <template v-else-if="column.key === 'evidence'">{{ record.evidenceCount }} 块</template>
                  <template v-else-if="column.key === 'faithfulness'"><span :class="scoreTone(record.faithfulness)">{{ fmtPct(record.faithfulness) }}</span></template>
                  <template v-else-if="column.key === 'relevance'"><span :class="scoreTone(record.relevance)">{{ fmtPct(record.relevance) }}</span></template>
                  <template v-else-if="column.key === 'issue'">{{ record.error || record.issue || '—' }}</template>
                </template>
                <template #expandedRowRender="{ record }">
                  <div class="ev-answer-body">{{ record.answer || (record.error ? '（执行失败，未生成回答）' : '（无回答）') }}</div>
                </template>
              </a-table>
            </template>
            <div v-if="answer.history.length" class="ev-history-chips">
              <span>历史评测</span>
              <span v-for="(h, i) in answer.history" :key="i">{{ fmtTime(h.runTime) }}：忠实 {{ fmtPct(h.avgFaithfulness) }} / 相关 {{ fmtPct(h.avgRelevance) }}（{{ h.caseCount }} 条）</span>
            </div>
          </div>
        </div>

        <!-- ==================== 历史趋势 ==================== -->
        <div v-show="activeTab === 'history'" class="ev-pane">
          <div class="app-card">
            <div class="ev-card-h">运行历史
              <span class="card-sub">每次运行绑定评估集版本与参数指纹</span>
              <span class="ev-sp"></span>
              <span class="ev-seg">
                <button v-for="m in historyMetrics" :key="m.key" :class="{ on: historyMetric === m.key }"
                        @click="historyMetric = m.key">{{ m.label }}</button>
              </span>
            </div>
            <EvalTrendChart v-if="historyChartRows.length" :rows="historyChartRows" :series="historySeries"
                            :threshold="historyMetric === 'recall5' ? 0.75 : null" height="220px" />
            <a-empty v-else :image="false" description="还没有运行记录" />

            <a-table size="small" row-key="id" :data-source="history.rows" :columns="historyColumns"
                     :pagination="false" :loading="loading.history" :locale="{ emptyText: '还没有运行记录' }">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'time'">{{ fmtTime(record.createTime) }}</template>
                <template v-else-if="column.key === 'trigger'">
                  <span class="tag" :class="record.triggerKind === 'scheduled' ? 'grey' : 'blue'">{{ record.triggerKind === 'scheduled' ? '自动' : '手动' }}</span>
                </template>
                <template v-else-if="column.key === 'set'">v{{ record.setVersion ?? '—' }}</template>
                <template v-else-if="column.key === 'group'">{{ record.bestGroupName || '当前配置' }}</template>
                <template v-else-if="column.key === 'recall5'">{{ fmtMetric(record.metrics?.['recall@5'], 3) }}</template>
                <template v-else-if="column.key === 'mrr'">{{ fmtMetric(record.metrics?.MRR, 3) }}</template>
                <template v-else-if="column.key === 'hit'">{{ fmtPct(record.metrics?.hitRate) }}</template>
                <template v-else-if="column.key === 'status'">
                  <span class="app-pill" :class="runPillTone(record.status)">{{ runStatusText(record.status) }}</span>
                  <span v-if="record.baseline" class="tag blue" style="margin-left:4px">基线</span>
                </template>
                <template v-else-if="column.key === 'ops'">
                  <button class="app-link-btn" @click="openRunRow(record)">查看</button>
                  <button v-if="canWrite && (record.status === 'succeeded' || record.status === 'partial')" class="app-link-btn" @click="markBaseline(record)">设基线</button>
                  <button class="app-link-btn" @click="compareRun(record)">对比</button>
                  <button class="app-link-btn" @click="exportRun(record)">导出</button>
                </template>
              </template>
            </a-table>
            <div class="ev-pager">
              <span class="ev-muted">共 {{ history.total }} 次运行 · 保留 90 天</span>
              <a-pagination size="small" :current="history.page" :page-size="history.size" :total="history.total"
                            :show-size-changer="false" @change="loadHistory" />
            </div>
          </div>
        </div>
      </a-spin>
    </div>

    <!-- ==================== 用例编辑 ==================== -->
    <a-modal v-model:open="caseModal.open" :title="caseModal.editing ? '编辑用例' : '新建用例'"
             width="720px" :confirm-loading="caseModal.saving" @ok="saveCase">
      <div class="ev-form">
        <div class="ev-form-row"><label>问题</label>
          <a-textarea v-model:value="caseModal.form.question" :rows="3" placeholder="用户会怎么问？" />
        </div>
        <div class="ev-form-row"><label>期望知识块</label>
          <a-select v-model:value="caseModal.form.expectedKnowledgeIds" mode="multiple" style="width:100%"
                    show-search :filter-option="false" :options="caseModal.options"
                    placeholder="搜索知识块标题，选中即作为期望命中" @search="searchChunks" />
          <div class="ev-muted">只选真正能回答该问题的知识块；标签错误会让 recall 失真。</div>
        </div>
        <div class="ev-form-row"><label>标签</label>
          <a-select v-model:value="caseModal.form.tags" mode="tags" style="width:100%" placeholder="如：Java / 权限 / 报销（分群指标用）" />
        </div>
        <div class="ev-form-row"><label>备注</label>
          <a-input v-model:value="caseModal.form.note" placeholder="考察点 / 人工修正说明" />
        </div>
        <div class="ev-form-row inline">
          <a-checkbox v-model:checked="caseModal.form.humanVerified">人工确认标签</a-checkbox>
          <a-checkbox v-model:checked="caseModal.form.enabled">启用该用例</a-checkbox>
        </div>
      </div>
    </a-modal>

    <!-- ==================== 导入 ==================== -->
    <a-modal v-model:open="importModal" title="导入用例" width="640px" @ok="doImport">
      <a-textarea v-model:value="importText" :rows="12" placeholder='粘贴 JSON：[{"question":"…","expectedKnowledgeIds":["…"]}] 或 {"cases":[…]}' />
      <div class="ev-muted" style="margin-top:6px">同一问题会跳过；期望块会自动剔除不存在的 ID。</div>
    </a-modal>

    <!-- ==================== 运行详情 ==================== -->
    <a-modal v-model:open="runModal.open" title="运行详情" width="860px" :footer="null">
      <a-spin :spinning="runModal.loading">
        <template v-if="runModal.detail">
          <div class="ev-run-head">
            <span class="app-pill" :class="runPillTone(runModal.detail.run.status)">{{ runStatusText(runModal.detail.run.status) }}</span>
            <span class="ev-muted">{{ fmtTime(runModal.detail.run.createTime) }} · {{ runModal.detail.run.triggerKind === 'scheduled' ? '自动体检' : '手动实验' }}</span>
            <span class="ev-sp"></span>
            <button class="app-btn ghost" @click="exportRun(runModal.detail.run)">导出</button>
            <button v-if="canWrite && (runModal.detail.run.status === 'succeeded' || runModal.detail.run.status === 'partial')"
                    class="app-btn ghost" @click="markBaseline(runModal.detail.run)">设为基线</button>
          </div>
          <div v-if="runModal.detail.run.warning" class="ev-banner warn"><span class="tag warn">提示</span><span>{{ runModal.detail.run.warning }}</span></div>
          <div v-if="runModal.detail.run.error" class="ev-banner danger"><span class="tag err">失败</span><span>{{ runModal.detail.run.error }}</span></div>
          <a-table size="small" row-key="name" :data-source="runModal.detail.groups || []" :columns="resultColumns"
                   :pagination="false">
            <template #bodyCell="{ column, record, index }">
              <template v-if="column.key === 'name'">{{ record.name }}</template>
              <template v-else-if="column.key === 'mode'"><span class="tag grey">{{ record.mode }}</span></template>
              <template v-else-if="column.key === 'delta'">—</template>
              <template v-else-if="column.key === 'ops'"><span class="ev-muted">—</span></template>
              <template v-else-if="column.metric">{{ fmtMetric(record.metrics?.[column.metric], 3) }}</template>
              <template v-else>{{ record.judgeScore == null ? '—' : fmtPct(record.judgeScore) }}</template>
            </template>
          </a-table>
          <div class="ev-muted" style="margin-top:8px">逐题结果请到实验页或历史运行的「明细」查看。</div>
        </template>
      </a-spin>
    </a-modal>

    <!-- ==================== 对比 ==================== -->
    <a-modal v-model:open="compareModal.open" title="两次运行对比" width="820px" :footer="null">
      <a-spin :spinning="compareModal.loading">
        <template v-if="compareModal.data">
          <div class="ev-conclusion">{{ compareModal.data.conclusion }}</div>
          <div v-if="compareModal.data.setChanged" class="ev-banner warn">
            <span class="tag warn">评估集不同</span><span>两次运行基于不同评估集版本，逐题对比仅供参考。</span>
          </div>
          <div class="ev-compare-counts">
            <span class="tag ok">改善 {{ compareModal.data.counts?.better || 0 }}</span>
            <span class="tag err">退化 {{ compareModal.data.counts?.worse || 0 }}</span>
            <span class="tag grey">持平 {{ compareModal.data.counts?.flat || 0 }}</span>
          </div>
          <a-table size="small" row-key="question" :data-source="compareModal.data.rows || []" :columns="compareColumns"
                   :pagination="{ pageSize: 10, size: 'small', hideOnSinglePage: true }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'aRank'">{{ record.aRank ? '#' + record.aRank : '未命中' }}</template>
              <template v-else-if="column.key === 'bRank'">{{ record.bRank ? '#' + record.bRank : '未命中' }}</template>
              <template v-else-if="column.key === 'aRecall5'">{{ fmtMetric(record.aRecall5, 2) }}</template>
              <template v-else-if="column.key === 'bRecall5'">{{ fmtMetric(record.bRecall5, 2) }}</template>
              <template v-else-if="column.key === 'verdict'">
                <span class="tag" :class="record.verdict === 'better' ? 'ok' : record.verdict === 'worse' ? 'err' : 'grey'">
                  {{ record.verdict === 'better' ? '改善' : record.verdict === 'worse' ? '退化' : '持平' }}
                </span>
              </template>
            </template>
          </a-table>
        </template>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ThunderboltOutlined } from '@ant-design/icons-vue'
import EvalTrendChart from './eval/EvalTrendChart.vue'
import {
  getEvalOverview, getEvalCases, createEvalCase, updateEvalCase, deleteEvalCase,
  importEvalCases, exportEvalCases, startEvalRun, listEvalRuns, getEvalRun,
  getEvalRunResults, cancelEvalRun, setEvalBaseline, compareEvalRuns, exportEvalRun,
  applyEvalGroup, rollbackEvalApply, evalGenerate, runAnswerEval, answerEvalReport,
  getConfig, listKnowledgeBases, searchKnowledge, getAuthMe, runEvalAutoCheck
} from '../api'

const activeTab = ref('overview')
const canWrite = ref(true)
const loading = reactive({ overview: false, cases: false, history: false })
const overview = ref(null)
const generating = ref(false)
const checking = ref(false)
const generateCount = ref(100)
const scopeKbId = ref('')
const kbs = ref([])
const curCfg = ref({})

const caseState = reactive({ rows: [], total: 0, page: 1, size: 20, filter: 'all', keyword: '', counts: {}, set: null })
const selectedCaseIds = ref([])

const groups = ref([defaultGroup()])
const experiment = reactive({
  scope: 'full', kList: [5, 10, 20], includeJudge: false,
  running: false, runId: null, detail: null,
  progress: { done: 0, total: 0 },
  group: null, results: [], resultsTotal: 0
})
const history = reactive({ rows: [], total: 0, page: 1, size: 20 })
const historyMetric = ref('recall5')
const answer = reactive({
  source: 'miss', count: 10, running: false,
  progress: { done: 0, total: 0 }, report: null, history: []
})

const caseModal = reactive({
  open: false, editing: null, saving: false, options: [],
  form: { question: '', expectedKnowledgeIds: [], tags: [], note: '', humanVerified: false, enabled: true }
})
const importModal = ref(false)
const importText = ref('')
const runModal = reactive({ open: false, loading: false, detail: null })
const compareModal = reactive({ open: false, loading: false, data: null })

let runTimer = null
let answerTimer = null

// ==================== 基础 ====================
function defaultGroup (preset = {}) {
  return {
    name: '当前配置', mode: 'normal',
    vectorWeight: null, keywordWeight: null, vecThreshold: null,
    keywordLimit: null, topK: null, rerankEnabled: null,
    lowConfidencePolicy: null, top1Floor: null, enabled: true,
    ...preset
  }
}
const PRESETS = {
  kw: { name: '关键词优先', vectorWeight: 0.3, keywordWeight: 0.7 },
  vec: { name: '向量优先', vectorWeight: 0.7, keywordWeight: 0.3 },
  rerank: { name: '向量+重排', vectorWeight: 0.7, keywordWeight: 0.3, rerankEnabled: null },
  multi: { name: '多路模拟', mode: 'multi' },
  low: { name: '低置信保底', lowConfidencePolicy: 'top1' },
  blank: {}
}
function addGroup (preset) {
  if (groups.value.length >= 8) { message.warning('参数组最多 8 组'); return }
  const p = PRESETS[preset] || {}
  groups.value.push(defaultGroup({ ...p, name: p.name || `对比组${groups.value.length}` }))
}
function groupMenu (key, gi) {
  if (key === 'copy') {
    if (groups.value.length >= 8) { message.warning('参数组最多 8 组'); return }
    groups.value.push({ ...groups.value[gi], name: `${groups.value[gi].name} 副本` })
  } else if (key === 'toggle') {
    groups.value[gi].enabled = groups.value[gi].enabled === false
  } else if (key === 'delete' && gi > 0) {
    groups.value.splice(gi, 1)
  }
}
function toggleK (k) {
  const i = experiment.kList.indexOf(k)
  if (i >= 0) { if (experiment.kList.length > 1) experiment.kList.splice(i, 1) } else experiment.kList.push(k)
  experiment.kList.sort((a, b) => a - b)
}
const cur = k => curCfg.value[k] ?? ''
function flattenConfig (d) {
  const flat = {}
  for (const [g, keys] of Object.entries(d || {})) {
    for (const [k, meta] of Object.entries(keys || {})) {
      if (meta && typeof meta === 'object' && 'value' in meta) flat[`${g}.${k}`] = meta.value
    }
  }
  return flat
}
async function loadConfig () {
  try {
    const r = await getConfig()
    if (r.success) curCfg.value = flattenConfig(r.data)
  } catch (e) { /* 占位为空不影响功能 */ }
}
async function loadAuth () {
  try {
    const r = await getAuthMe()
    if (r.success) canWrite.value = !!r.data?.admin
  } catch (e) { /* 保持默认 */ }
}
async function loadKbs () {
  try {
    const r = await listKnowledgeBases()
    if (r.success) kbs.value = r.data || []
  } catch (e) { /* 忽略 */ }
}

// ==================== 计算属性 ====================
const setInfo = computed(() => overview.value?.set || caseState.set)
const caseCount = computed(() => overview.value?.caseCount ?? caseState.total ?? 0)
const metrics = computed(() => overview.value?.lastRun?.metrics || overview.value?.lastAutoReport?.metrics || {})
const baselineMetrics = computed(() => overview.value?.baselineRun?.metrics || {})
const latestRunId = computed(() => overview.value?.lastRun?.id || overview.value?.lastRun?.runId)
const lastRunText = computed(() => {
  const r = overview.value?.lastRun
  if (!r) return '还没有运行记录'
  return `${fmtTime(r.createTime)} · ${r.triggerKind === 'scheduled' ? '自动体检' : '手动实验'}`
})
const autoTone = computed(() => {
  const s = overview.value?.lastAutoReport?.status
  if (s === 'decline' || s === 'error') return 'err'
  if (!s || s === 'empty') return 'warn'
  return 'ok'
})
const autoText = computed(() => {
  const s = overview.value?.lastAutoReport?.status
  if (s === 'decline') return '自动体检：下滑'
  if (s === 'error') return '自动体检：异常'
  if (!s || s === 'empty') return '自动体检：待运行'
  return '自动体检：正常'
})
const health = computed(() => {
  const r5 = num(metrics.value['recall@5'])
  const mrr = num(metrics.value.MRR)
  const hit = num(metrics.value.hitRate)
  if (r5 == null && mrr == null && hit == null) return null
  const q = num(overview.value?.qualityScore) ?? 80
  const score = Math.round(100 * (0.4 * (r5 ?? 0) + 0.25 * (mrr ?? 0) + 0.25 * (hit ?? 0) + 0.10 * (q / 100)))
  const label = score >= 85 ? '良好' : score >= 70 ? '一般' : '需关注'
  const delta = baselineMetrics.value['recall@5'] != null && metrics.value['recall@5'] != null
    ? (metrics.value['recall@5'] - baselineMetrics.value['recall@5']) * 100 : null
  return { score, label, delta }
})
const healthDash = computed(() => ((health.value?.score || 0) / 100) * 194.8)
const healthColor = computed(() => (health.value?.score ?? 0) >= 85 ? 'var(--app-ok)' : (health.value?.score ?? 0) >= 70 ? 'var(--app-warn)' : 'var(--app-danger)')
const healthPillTone = computed(() => (health.value?.score ?? 0) >= 85 ? 'ok' : (health.value?.score ?? 0) >= 70 ? 'warn' : 'err')
const qualityTone = computed(() => (overview.value?.qualityScore ?? 0) >= 80 ? 'ok' : (overview.value?.qualityScore ?? 0) >= 60 ? 'warn' : 'err')
const kpiCards = computed(() => {
  const r5 = num(metrics.value['recall@5'])
  const mrr = num(metrics.value.MRR)
  const hit = num(metrics.value.hitRate)
  const judge = overview.value?.lastRun?.groups?.[0]?.judgeScore ?? overview.value?.lastAutoReport?.judgeScore
  return [
    { key: 'r5', label: 'recall@5', value: r5 == null ? '—' : r5.toFixed(2), hint: '期望块排进前 5 的比例', delta: deltaOfMetric('recall@5') },
    { key: 'mrr', label: 'MRR', value: mrr == null ? '—' : mrr.toFixed(2), hint: '首个期望块排名倒数', delta: deltaOfMetric('MRR') },
    { key: 'hit', label: '命中率', value: fmtPct(hit), hint: '没有完全落空的题占比', delta: deltaOfMetric('hitRate') },
    { key: 'judge', label: '答案充分率', value: judge == null ? '—' : fmtPct(judge), hint: '资料足以直接回答', delta: null }
  ].map(m => ({
    ...m,
    deltaText: m.delta == null ? '暂无上期基线' : `较基线 ${m.delta > 0 ? '+' : ''}${m.delta.toFixed(3)}`,
    deltaClass: m.delta == null ? 'flat' : (m.delta > 0 ? 'up' : m.delta < 0 ? 'down' : 'flat')
  }))
})
function deltaOfMetric (key) {
  const a = num(metrics.value[key])
  const b = num(baselineMetrics.value[key])
  if (a == null || b == null) return null
  return a - b
}
const overviewSeries = [
  { key: 'recall5', label: 'recall@5', color: '#2e6be6' },
  { key: 'mrr', label: 'MRR', color: '#8fb3ef' },
  { key: 'hitRate', label: '命中率', color: '#c9a25a' }
]
const recentRuns = computed(() => (overview.value?.trend || []).slice(-5).reverse())
const issueRows = computed(() => {
  const issues = overview.value?.issues || {}
  const rows = []
  for (const x of (issues.miss || []).slice(0, 3)) {
    rows.push({ key: 'miss-' + x.caseId, tag: '落空', tone: 'err', question: x.question, caseId: x.caseId, meta: `期望 ${x.expectedCount} 块 · 命中 0 · recall@5 0` })
  }
  for (const x of (issues.low || []).slice(0, 2)) {
    rows.push({ key: 'low-' + x.caseId, tag: '偏低', tone: 'warn', question: x.question, caseId: x.caseId, meta: `recall@5 ${fmtMetric(x.recall5, 2)} · 首个命中 ${x.firstRank ? '#' + x.firstRank : '未命中'}` })
  }
  for (const x of (issues.stale || []).slice(0, 2)) {
    rows.push({ key: 'stale-' + x.id, tag: '失效', tone: 'grey', question: x.question, caseId: x.id, meta: '期望块已被删除/重解析，需修复标签' })
  }
  return rows.slice(0, 6)
})
const tabs = computed(() => [
  { key: 'overview', label: '总览' },
  { key: 'cases', label: '评估集', badge: caseCount.value || '', badgeTone: 'blue' },
  { key: 'lab', label: '调参实验', badge: groups.value.length, badgeTone: 'blue' },
  { key: 'answer', label: '答案质量' },
  { key: 'history', label: '历史趋势' }
])
const scopeOptions = computed(() => [
  { value: '', label: '全部知识库' },
  ...kbs.value.map(k => ({ value: k.id, label: k.name || k.id }))
])
const setSourceText = computed(() => {
  const s = setInfo.value?.source
  return { generated: '历史问答回放', import: '文件导入', manual: '手工' }[s] || s || '—'
})

// ==================== 加载 ====================
async function loadOverview () {
  loading.overview = true
  try {
    const r = await getEvalOverview(scopeKbId.value)
    if (r.success) overview.value = r.data
  } catch (e) { message.error(e.message || '加载总览失败') }
  finally { loading.overview = false }
}
async function loadCases (page = caseState.page) {
  loading.cases = true
  try {
    const r = await getEvalCases({
      setId: setInfo.value?.id,
      filter: caseState.filter,
      keyword: caseState.keyword,
      page,
      size: caseState.size
    })
    if (r.success) {
      caseState.rows = r.data.rows || []
      caseState.total = r.data.total || 0
      caseState.page = r.data.page || page
      caseState.counts = r.data.counts || {}
      caseState.set = r.data.set || null
    }
  } catch (e) { message.error(e.message || '加载用例失败') }
  finally { loading.cases = false }
}
async function loadHistory (page = 1) {
  loading.history = true
  try {
    const r = await listEvalRuns(page, history.size, setInfo.value?.id)
    if (r.success) {
      history.rows = r.data.rows || []
      history.total = r.data.total || 0
      history.page = r.data.page || page
    }
  } catch (e) { message.error(e.message || '加载历史失败') }
  finally { loading.history = false }
}
async function reloadAll () {
  await loadOverview()
  await loadCases(1)
  await loadHistory(1)
}
async function scrollToTab (key) {
  activeTab.value = key
  if (key === 'cases') await loadCases(caseState.page)
  if (key === 'history') await loadHistory(history.page)
  if (key === 'answer') await loadAnswerStatus()
}
function switchTab (key) { scrollToTab(key) }

// ==================== 总览动作 ====================
async function doHealth () {
  if (!canWrite.value) return
  checking.value = true
  try {
    const r = await runEvalAutoCheck()
    if (r.success) {
      message.success('体检完成')
      await reloadAll()
    } else message.error(r.msg || '体检失败')
  } catch (e) { message.error(e.message || '体检失败') }
  finally { checking.value = false }
}
async function doGenerate (direct) {
  if (!canWrite.value) return
  const run = async () => {
    generating.value = true
    try {
      const r = await evalGenerate(generateCount.value, scopeKbId.value)
      if (r.success) {
        message.success(`评估集已生成：${r.data?.caseCount ?? r.data?.generated ?? 0} 条`)
        await reloadAll()
      } else message.error(r.msg || '生成失败')
    } catch (e) { message.error(e.message || '生成失败') }
    finally { generating.value = false }
  }
  if (direct) { await run(); return }
  Modal.confirm({
    title: '重新生成评估集？',
    content: '会基于最近历史问答生成一个新版本；旧版本与历史运行仍保留，不会被覆盖。',
    okText: '生成', cancelText: '取消', onOk: run
  })
}
async function doRollback () {
  Modal.confirm({
    title: '回滚灰度应用？',
    content: '将把参数恢复到应用前快照。',
    okText: '回滚', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await rollbackEvalApply()
        if (r.success) { message.success('已回滚'); await loadOverview() }
        else message.error(r.msg || '回滚失败')
      } catch (e) { message.error(e.message || '回滚失败') }
    }
  })
}
function openCases (filter) {
  caseState.filter = filter
  caseState.page = 1
  activeTab.value = 'cases'
  loadCases(1)
}
function openCaseByQuestion (question) {
  caseState.keyword = question
  openCases('all')
}
function addToExperiment (caseId) {
  if (!selectedCaseIds.value.includes(caseId)) selectedCaseIds.value.push(caseId)
  experiment.scope = 'selected'
  switchTab('lab')
}

// ==================== 用例 ====================
const caseFilters = computed(() => {
  const c = caseState.counts || {}
  return [
    { key: 'all', label: '全部', count: c.all || 0 },
    { key: 'miss', label: '完全落空', count: c.miss || 0 },
    { key: 'low', label: '低相关', count: c.low || 0 },
    { key: 'stale', label: '期望失效', count: c.stale || 0 },
    { key: 'unverified', label: '待确认', count: c.unverified || 0 },
    { key: 'verified', label: '已确认', count: c.verified || 0 },
    { key: 'disabled', label: '已停用', count: c.disabled || 0 }
  ]
})
const caseColumns = [
  { title: '问题', key: 'question', ellipsis: true },
  { title: '期望知识块（标题）', key: 'expected', width: 330 },
  { title: '来源', key: 'source', width: 82 },
  { title: '质量', key: 'quality', width: 82 },
  { title: '最近 recall@5', key: 'last', width: 110 },
  { title: '操作', key: 'ops', width: 130 }
]
function setCaseFilter (key) { caseState.filter = key; loadCases(1) }
function onCaseSearchChange () { clearTimeout(onCaseSearchChange.t); onCaseSearchChange.t = setTimeout(() => loadCases(1), 350) }
function onSelectCases (keys) { selectedCaseIds.value = keys }
function sourceText (s) {
  return { session: '历史引用', feedback: '差评回流', manual: '手工', import: '导入' }[s] || (s || '—')
}
function sourceTone (s) {
  return { session: 'grey', feedback: 'err', manual: 'blue', import: 'grey' }[s] || 'grey'
}
function qualityText (q) {
  return { verified: '已确认', unverified: '待确认', stale: '失效' }[q] || q || '—'
}
function qualityTagTone (q) {
  return { verified: 'ok', unverified: 'warn', stale: 'err' }[q] || 'grey'
}
function openCase (record) {
  caseModal.editing = record
  caseModal.options = (record?.expectedTitles || []).map(t => ({ value: t.id, label: t.title }))
  caseModal.form = record
    ? {
        question: record.question || '',
        expectedKnowledgeIds: record.expectedKnowledgeIds || [],
        tags: record.tags || [],
        note: record.note || '',
        humanVerified: record.humanVerified === 1,
        enabled: record.enabled !== 0
      }
    : { question: '', expectedKnowledgeIds: [], tags: [], note: '', humanVerified: false, enabled: true }
  caseModal.open = true
}
async function searchChunks (kw) {
  if (!kw || kw.length < 1) return
  try {
    const r = await searchKnowledge(kw)
    if (r.success) {
      const found = (r.data || []).map(x => ({ value: x.id, label: x.title || x.snippet || x.id }))
      const map = new Map(caseModal.options.map(o => [o.value, o]))
      found.forEach(o => map.set(o.value, o))
      caseModal.options = [...map.values()]
    }
  } catch (e) { /* 搜索失败不阻塞编辑 */ }
}
async function saveCase () {
  if (!caseModal.form.question?.trim()) { message.warning('问题不能为空'); return }
  caseModal.saving = true
  try {
    const body = {
      question: caseModal.form.question.trim(),
      expectedKnowledgeIds: caseModal.form.expectedKnowledgeIds,
      tags: caseModal.form.tags,
      note: caseModal.form.note,
      humanVerified: caseModal.form.humanVerified ? 1 : 0,
      enabled: caseModal.form.enabled ? 1 : 0
    }
    const r = caseModal.editing
      ? await updateEvalCase(caseModal.editing.id, body)
      : await createEvalCase(body)
    if (r.success) {
      message.success('已保存')
      caseModal.open = false
      await loadCases()
      await loadOverview()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { caseModal.saving = false }
}
function removeCase (record) {
  Modal.confirm({
    title: '删除该用例？',
    content: record.question,
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteEvalCase(record.id)
        if (r.success) { message.success('已删除'); await loadCases(); await loadOverview() }
        else message.error(r.msg || '删除失败')
      } catch (e) { message.error(e.message || '删除失败') }
    }
  })
}
function openImport () { importText.value = ''; importModal.value = true }
async function doImport () {
  try {
    const parsed = JSON.parse(importText.value)
    const cases = Array.isArray(parsed) ? parsed : (parsed.cases || [])
    if (!cases.length) { message.warning('没有可导入的用例'); return }
    const r = await importEvalCases(cases)
    if (r.success) {
      message.success(`导入完成：新增 ${r.data.added}，跳过 ${r.data.skipped}`)
      importModal.value = false
      await loadCases(1)
      await loadOverview()
    } else message.error(r.msg || '导入失败')
  } catch (e) { message.error('JSON 解析失败：' + (e.message || '')) }
}
async function doExportCases () {
  try {
    const r = await exportEvalCases(setInfo.value?.id)
    if (r.success) download(`retrieval-eval-v${r.data.version || 1}.json`, JSON.stringify(r.data, null, 2), 'application/json')
    else message.error(r.msg || '导出失败')
  } catch (e) { message.error(e.message || '导出失败') }
}
function runSelectedCases () {
  if (!selectedCaseIds.value.length) { message.warning('先勾选用例'); return }
  experiment.scope = 'selected'
  switchTab('lab')
}
function runOneCase (record) {
  if (!selectedCaseIds.value.includes(record.id)) selectedCaseIds.value.push(record.id)
  experiment.scope = 'selected'
  switchTab('lab')
}

// ==================== 参数组 ====================
function groupDiff (g, gi) {
  if (gi === 0) return '跟随线上配置 · 基线不可删除'
  const diff = []
  const add = (label, key, fullKey) => {
    if (g[key] == null) return
    const before = cur(fullKey)
    if (String(before) !== String(g[key])) diff.push(`${label} ${before || '—'} → ${g[key]}`)
  }
  add('向量', 'vectorWeight', 'retrieval.vectorWeight')
  add('关键词', 'keywordWeight', 'retrieval.keywordWeight')
  add('阈值', 'vecThreshold', 'retrieval.vecThreshold')
  add('关键词上限', 'keywordLimit', 'retrieval.keywordLimit')
  add('召回', 'topK', 'retrieval.vectorTopK')
  if (g.rerankEnabled === false) diff.push('重排 跟随 → 强制关')
  if (g.lowConfidencePolicy != null && String(g.lowConfidencePolicy) !== cur('retrieval.lowConfidencePolicy')) diff.push(`低置信 ${cur('retrieval.lowConfidencePolicy') || '—'} → ${g.lowConfidencePolicy}`)
  if (g.top1Floor != null && String(g.top1Floor) !== cur('retrieval.top1Floor')) diff.push(`保底分 ${cur('retrieval.top1Floor') || '—'} → ${g.top1Floor}`)
  return diff.length ? `较基线：${diff.join(' · ')}` : '与当前配置一致'
}
const experimentEstimate = computed(() => {
  const count = experiment.scope === 'selected' ? selectedCaseIds.value.length
    : experiment.scope === 'miss' ? (overview.value?.issues?.miss?.length || 0) : caseCount.value
  const gs = groups.value.filter(g => g.enabled !== false).length
  return `${gs} 组 × ${count} 条 · 参数覆盖仅本次实验`
})
async function runExperiment () {
  const active = groups.value.filter(g => g.enabled !== false)
  if (!active.length) { message.warning('至少启用一个参数组'); return }
  let caseIds = null
  if (experiment.scope === 'selected') {
    if (!selectedCaseIds.value.length) { message.warning('还没有勾选用例'); return }
    caseIds = selectedCaseIds.value
  } else if (experiment.scope === 'miss') {
    caseIds = (overview.value?.issues?.miss || []).map(x => x.caseId).filter(Boolean)
    if (!caseIds.length) { message.warning('没有落空题，改为全量运行'); caseIds = null }
  }
  try {
    const r = await startEvalRun({
      setId: setInfo.value?.id,
      caseIds,
      groups: active.map(g => ({
        name: g.name, mode: g.mode,
        vectorWeight: g.vectorWeight, keywordWeight: g.keywordWeight,
        vecThreshold: g.vecThreshold, keywordLimit: g.keywordLimit, topK: g.topK,
        rerankEnabled: g.rerankEnabled === false ? false : null,
        lowConfidencePolicy: g.lowConfidencePolicy, top1Floor: g.top1Floor
      })),
      kList: experiment.kList,
      includeJudge: experiment.includeJudge
    })
    if (r.success) {
      experiment.runId = r.data.runId
      experiment.detail = null
      experiment.results = []
      experiment.group = null
      experiment.running = true
      experiment.progress = { done: 0, total: r.data.total || 0 }
      startRunPoll()
    } else message.error(r.msg || '启动失败')
  } catch (e) { message.error(e.message || '启动失败') }
}
function startRunPoll () {
  stopRunPoll()
  const tick = async () => {
    if (!experiment.runId) return
    try {
      const r = await getEvalRun(experiment.runId)
      if (r.success) {
        experiment.detail = r.data
        experiment.progress = r.data.progress || experiment.progress
        experiment.running = !!r.data.progress?.running || r.data.run?.status === 'running'
        if (!experiment.group && r.data.groups?.length) experiment.group = r.data.groups[0].name
        if (!experiment.running) {
          stopRunPoll()
          if (experiment.group) await loadExperimentResults(experiment.group)
          await loadOverview()
          await loadHistory(1)
          message.success('实验完成')
        }
      }
    } catch (e) { /* 下次轮询再试 */ }
  }
  tick()
  runTimer = setInterval(tick, 1500)
}
function stopRunPoll () { if (runTimer) { clearInterval(runTimer); runTimer = null } }
async function stopExperiment () {
  if (!experiment.runId) return
  try {
    const r = await cancelEvalRun(experiment.runId)
    if (r.success) message.info('已请求取消')
  } catch (e) { message.error(e.message || '取消失败') }
}
async function loadExperimentResults (group) {
  if (!experiment.runId || !group) return
  experiment.group = group
  try {
    const r = await getEvalRunResults(experiment.runId, group, 1, 200)
    if (r.success) {
      experiment.results = r.data.rows || []
      experiment.resultsTotal = r.data.total || 0
    }
  } catch (e) { message.error(e.message || '加载明细失败') }
}
const progressPct = computed(() => experiment.progress.total ? Math.round(experiment.progress.done / experiment.progress.total * 100) : 0)
const firstGroup = computed(() => experiment.detail?.groups?.[0] || null)
const bestGroup = computed(() => {
  const gs = experiment.detail?.groups || []
  return gs.slice().sort((a, b) => num(b.metrics?.['recall@5'] ?? 0) - num(a.metrics?.['recall@5'] ?? 0))[0] || null
})
const bestIndex = computed(() => {
  const gs = experiment.detail?.groups || []
  const b = bestGroup.value
  return b ? gs.findIndex(g => g.name === b.name) : -1
})
const resultColumns = computed(() => {
  const ks = experiment.kList || [5, 10, 20]
  const cols = [
    { title: '参数组', key: 'name', width: 150 },
    { title: '模式', key: 'mode', width: 82 }
  ]
  ks.forEach(k => cols.push({ title: `recall@${k}`, key: `recall@${k}`, metric: `recall@${k}`, ci: true, width: 92 }))
  cols.push(
    { title: 'MRR', key: 'MRR', metric: 'MRR', ci: true, width: 84 },
    { title: '命中率', key: 'hitRate', metric: 'hitRate', ci: false, width: 84 },
    { title: '充分率', key: 'judgeScore', width: 80 },
    { title: '较基线', key: 'delta', width: 78 },
    { title: '操作', key: 'ops', width: 120 }
  )
  return cols
})
function deltaOf (group) {
  const base = firstGroup.value
  if (!base) return 0
  const k = `recall@${experiment.kList[0] || 5}`
  return num(group.metrics?.[k] ?? 0) - num(base.metrics?.[k] ?? 0)
}
const conclusion = computed(() => {
  const gs = experiment.detail?.groups || []
  if (gs.length < 2) return ''
  const base = gs[0]
  const k = `recall@${experiment.kList[0] || 5}`
  const better = gs.slice(1).filter(g =>
    num(g.metrics?.[k] ?? 0) >= num(base.metrics?.[k] ?? 0) &&
    num(g.metrics?.MRR ?? 0) >= num(base.metrics?.MRR ?? 0) &&
    num(g.metrics?.hitRate ?? 0) >= num(base.metrics?.hitRate ?? 0) &&
    (num(g.metrics?.[k] ?? 0) > num(base.metrics?.[k] ?? 0) || num(g.metrics?.MRR ?? 0) > num(base.metrics?.MRR ?? 0))
  )
  if (!better.length) return `暂无参数组全面优于「${base.name}」。可以看逐题明细定位落空题，或再试其他预设。`
  const d = (num(better[0].metrics?.[k] ?? 0) - num(base.metrics?.[k] ?? 0)).toFixed(2)
  return `「${better.map(g => g.name).join('、')}」各项指标不差于基线且至少一项更优（recall@${experiment.kList[0] || 5} +${d}）。建议先看逐题明细确认没有个别题被挤掉，再应用。`
})
const experimentResults = computed(() => {
  const rows = (experiment.results || []).slice()
  rows.sort((a, b) => {
    const ra = a.firstRank || 0
    const rb = b.firstRank || 0
    if (ra === 0 && rb !== 0) return -1
    if (rb === 0 && ra !== 0) return 1
    return num(a.recallAtK?.['recall@5'] ?? 0) - num(b.recallAtK?.['recall@5'] ?? 0)
  })
  return rows.slice(0, 80)
})
const activeSegments = computed(() => experiment.detail?.segments?.[experiment.group] || null)
const activeSegmentsByTag = computed(() => activeSegments.value?.byTag || [])
const activeSegmentsByKb = computed(() => activeSegments.value?.byKb || [])
const activeSegmentsByDocType = computed(() => activeSegments.value?.byDocType || [])

function applyGroup (group) {
  Modal.confirm({
    title: `应用「${group.name}」到线上配置？`,
    content: '参数会立即生效，并进入 24 小时观察期；若下一次体检核心指标明显下滑，会自动回滚并通知管理员。',
    okText: '应用并观察', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await applyEvalGroup(experiment.runId, group.name)
        if (r.success) {
          message.success('已应用，进入观察期')
          await loadOverview()
        } else message.error(r.msg || '应用失败')
      } catch (e) { message.error(e.message || '应用失败') }
    }
  })
}

// ==================== 历史 ====================
const historyMetrics = [
  { key: 'recall5', label: 'recall@5' },
  { key: 'mrr', label: 'MRR' },
  { key: 'hitRate', label: '命中率' }
]
const historySeries = computed(() => {
  const map = {
    recall5: { key: 'recall5', label: 'recall@5', color: '#2e6be6' },
    mrr: { key: 'mrr', label: 'MRR', color: '#8fb3ef' },
    hitRate: { key: 'hitRate', label: '命中率', color: '#c9a25a' }
  }
  return [map[historyMetric.value] || map.recall5]
})
const historyChartRows = computed(() => (history.rows || []).slice().reverse().map(r => ({
  time: r.createTime,
  recall5: r.metrics?.['recall@5'],
  mrr: r.metrics?.MRR,
  hitRate: r.metrics?.hitRate
})))
const historyColumns = [
  { title: '时间', key: 'time', width: 140 },
  { title: '触发', key: 'trigger', width: 72 },
  { title: '评估集', key: 'set', width: 72 },
  { title: '最佳组', key: 'group', width: 130 },
  { title: 'recall@5', key: 'recall5', width: 90 },
  { title: 'MRR', key: 'mrr', width: 78 },
  { title: '命中率', key: 'hit', width: 78 },
  { title: '状态', key: 'status', width: 130 },
  { title: '操作', key: 'ops', width: 190 }
]
function rowBaseline (row) {
  return setInfo.value?.baselineRunId && setInfo.value.baselineRunId !== row.id ? setInfo.value.baselineRunId : null
}
async function openRunRow (row) {
  const id = row.id || row.runId
  if (!id) return
  runModal.open = true
  runModal.loading = true
  runModal.detail = null
  try {
    const r = await getEvalRun(id)
    if (r.success) runModal.detail = r.data
  } catch (e) { message.error(e.message || '加载失败') }
  finally { runModal.loading = false }
}
async function markBaseline (row) {
  const id = row.id || row.runId
  if (!id) return
  try {
    const r = await setEvalBaseline(id)
    if (r.success) { message.success('已设为基线'); await loadHistory(history.page); await loadOverview() }
    else message.error(r.msg || '设置失败')
  } catch (e) { message.error(e.message || '设置失败') }
}
async function compareRun (row) {
  const baseId = rowBaseline(row)
  if (!baseId) { message.info('先对一次运行点「设基线」，再对比'); return }
  compareModal.open = true
  compareModal.loading = true
  compareModal.data = null
  try {
    const r = await compareEvalRuns(row.id, baseId)
    if (r.success) compareModal.data = r.data
    else message.error(r.msg || '对比失败')
  } catch (e) { message.error(e.message || '对比失败') }
  finally { compareModal.loading = false }
}
const compareColumns = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '基线排名', key: 'aRank', width: 90, customRender: ({ text, record }) => record.aRank ? '#' + record.aRank : '未命中' },
  { title: '本次排名', key: 'bRank', width: 90, customRender: ({ text, record }) => record.bRank ? '#' + record.bRank : '未命中' },
  { title: '基线 recall@5', key: 'aRecall5', width: 110 },
  { title: '本次 recall@5', key: 'bRecall5', width: 110 },
  { title: '结论', key: 'verdict', width: 80 }
]
async function exportRun (row) {
  const id = row?.id || row?.runId || experiment.runId
  if (!id) { message.info('没有可导出的运行'); return }
  try {
    const r = await exportEvalRun(id, 'csv')
    if (r.success) download(r.data.filename, r.data.content, 'text/csv;charset=utf-8')
    else message.error(r.msg || '导出失败')
  } catch (e) { message.error(e.message || '导出失败') }
}
function exportCurrentRun () { exportRun({ id: experiment.runId }) }
async function copyRunLink () {
  const id = experiment.runId || latestRunId.value
  if (!id) { message.info('还没有运行记录'); return }
  const url = `${location.origin}${location.pathname}?run=${id}`
  try {
    await navigator.clipboard.writeText(url)
    message.success('运行链接已复制')
  } catch (e) {
    message.info(url)
  }
}
async function exportLatest () {
  if (experiment.runId) return exportRun({ id: experiment.runId })
  const latest = history.rows.find(r => r.status !== 'running') || overview.value?.lastRun
  if (!latest) { message.info('还没有运行记录'); return }
  await exportRun(latest)
}

// ==================== 答案质量 ====================
const answerColumns = [
  { title: '问题', key: 'question', ellipsis: true },
  { title: '证据', key: 'evidence', width: 70 },
  { title: '忠实度', key: 'faithfulness', width: 80 },
  { title: '相关性', key: 'relevance', width: 80 },
  { title: '问题说明', key: 'issue', ellipsis: true }
]
const answerPct = computed(() => answer.progress.total ? Math.round(answer.progress.done / answer.progress.total * 100) : 0)
async function loadAnswerStatus () {
  try {
    const r = await answerEvalReport()
    if (r.success) {
      answer.report = r.data?.report || null
      answer.history = r.data?.history || []
      answer.running = !!r.data?.running
      if (r.data?.progress) answer.progress = r.data.progress
      if (answer.running) startAnswerPoll()
    }
  } catch (e) { /* 接口未部署时静默 */ }
}
async function startAnswer () {
  let caseIds = null
  if (answer.source === 'miss') caseIds = (overview.value?.issues?.miss || []).map(x => x.caseId).filter(Boolean)
  else if (answer.source === 'low') caseIds = (overview.value?.issues?.low || []).map(x => x.caseId).filter(Boolean)
  else if (answer.source === 'selected') caseIds = selectedCaseIds.value
  if (answer.source !== 'all' && !caseIds?.length) { message.warning('当前来源没有可用题目'); return }
  try {
    const r = await runAnswerEval(answer.count, caseIds)
    if (r.success) {
      answer.running = true
      answer.progress = { done: 0, total: Math.min(answer.count, caseIds?.length || answer.count) }
      startAnswerPoll()
    } else message.error(r.msg || '启动失败')
  } catch (e) { message.error(e.message || '启动失败') }
}
function startAnswerPoll () {
  stopAnswerPoll()
  answerTimer = setInterval(async () => {
    try {
      const r = await answerEvalReport()
      if (!r.success) return
      if (r.data?.progress) answer.progress = r.data.progress
      answer.report = r.data?.report || answer.report
      answer.history = r.data?.history || answer.history
      answer.running = !!r.data?.running
      if (!answer.running) {
        stopAnswerPoll()
        message.success('答案评测完成')
        await loadOverview()
      }
    } catch (e) { /* 下次再试 */ }
  }, 3000)
}
function stopAnswerPoll () { if (answerTimer) { clearInterval(answerTimer); answerTimer = null } }

// ==================== 工具 ====================
function num (v) {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
function fmtMetric (v, digits = 2) {
  const n = num(v)
  return n == null ? '—' : n.toFixed(digits)
}
function fmtPct (v) {
  const n = num(v)
  return n == null ? '—' : Math.round(n * 100) + '%'
}
function fmtTime (t) {
  if (!t) return '—'
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}
function scoreTone (v) {
  const n = num(v)
  return n == null ? 'dim' : n >= 0.8 ? 'ok' : n >= 0.6 ? 'warn' : 'err'
}
function runStatusText (s) {
  return {
    running: '运行中', succeeded: '正常', partial: '部分超时', failed: '失败', cancelled: '已取消'
  }[s] || s || '—'
}
function runPillTone (s) {
  return { succeeded: 'ok', partial: 'warn', running: 'blue', failed: 'err', cancelled: 'grey' }[s] || 'grey'
}

const applyTone = computed(() => {
  const s = overview.value?.apply?.status
  return s === 'rolledBack' ? 'danger' : s === 'confirmed' ? 'ok' : 'warn'
})
const applyTagTone = computed(() => {
  const s = overview.value?.apply?.status
  return s === 'rolledBack' ? 'err' : s === 'confirmed' ? 'ok' : 'warn'
})
const applyLabel = computed(() => {
  const s = overview.value?.apply?.status
  return { observing: '灰度观察中', confirmed: '观察通过', rolledBack: '已自动回滚' }[s] || '灰度应用'
})
function download (filename, content, type) {
  const blob = new Blob([content], { type: type || 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

// ==================== 生命周期 ====================
onMounted(async () => {
  await Promise.all([loadConfig(), loadAuth(), loadKbs()])
  await loadOverview()
  await loadCases(1)
  await loadHistory(1)
  loadAnswerStatus()
  const sharedRun = new URLSearchParams(location.search).get('run')
  if (sharedRun) openRunRow({ id: sharedRun })
})
onUnmounted(() => { stopRunPoll(); stopAnswerPoll() })
</script>

<style scoped>
.ev-page .head-stat { font-size: 12px; color: var(--app-text3); }
.ev-page .head-set { display: inline-flex; align-items: center; gap: 8px; }
.ev-page .set-time { font-size: 11px; color: var(--app-text3); }
.ev-page .head-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ev-page .scope-sel { min-width: 132px; }
.auto-pill { font-size: 11px; padding: 3px 10px; border-radius: 999px; white-space: nowrap; }
.auto-pill.ok { color: var(--app-ok); background: var(--app-ok-weak); }
.auto-pill.warn { color: var(--app-warn-text); background: var(--app-warn-weak); }
.auto-pill.err { color: var(--app-danger); background: var(--app-danger-weak); }

.ev-tabs { display: flex; gap: 20px; border-bottom: 1px solid var(--app-border); margin-bottom: 14px; overflow-x: auto; }
.ev-tab { border: none; background: none; padding: 9px 1px; font-family: var(--font); font-size: 13px; color: var(--app-text2); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; white-space: nowrap; display: inline-flex; align-items: center; gap: 5px; }
.ev-tab:hover { color: var(--app-accent); }
.ev-tab.on { color: var(--app-accent); font-weight: 600; border-bottom-color: var(--app-accent); }
.ev-tab-badge { font-size: 10px; line-height: 15px; padding: 0 5px; border-radius: 999px; background: var(--app-accent-weak); color: var(--app-accent); }
.ev-tab-badge { background: var(--app-accent-weak); color: var(--app-accent); }

.ev-pane { animation: ev-fade .18s ease; }
@keyframes ev-fade { from { opacity: .4; transform: translateY(2px); } to { opacity: 1; transform: none; } }
.ev-sp { flex: 1; }
.ev-muted { font-size: 11px; color: var(--app-text3); }
.ev-ok { color: var(--app-ok); }
.ev-warn { color: var(--app-warn-text); }
.ev-danger { color: var(--app-danger); }

.ev-empty { text-align: center; padding: 56px 20px; }
.ev-empty-ico { font-size: 30px; color: var(--app-accent); margin-bottom: 8px; }
.ev-empty-title { font-size: 17px; font-weight: 600; margin-bottom: 8px; }
.ev-empty p { font-size: 12.5px; color: var(--app-text2); line-height: 1.9; margin-bottom: 16px; }
.ev-empty-acts { display: flex; align-items: center; justify-content: center; gap: 8px; flex-wrap: wrap; }

.ev-banner { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; padding: 8px 12px; border-radius: 8px; border: 1px solid var(--app-border); background: var(--app-panel-2); font-size: 12px; color: var(--app-text2); }
.ev-banner.warn { border-color: var(--app-warn-border); background: var(--app-warn-weak); color: var(--app-warn-text); }
.ev-banner.danger { border-color: var(--app-danger-border); background: var(--app-danger-weak); color: var(--app-danger-text); }
.ev-banner.ok { border-color: var(--app-ok-border, #cbe6d1); background: var(--app-ok-weak); color: var(--app-ok); }
.ev-banner .app-link-btn { margin-left: auto; }

.ev-kpi { display: grid; grid-template-columns: 280px repeat(4, minmax(0, 1fr)); gap: 10px; }
.ev-health { position: relative; }
.ev-card-h { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; margin-bottom: 10px; }
.ev-health-main { display: flex; align-items: center; gap: 12px; }
.ev-ring { width: 72px; height: 72px; flex: none; }
.ev-health-score { font-size: 25px; font-weight: 600; line-height: 1.1; letter-spacing: -.02em; }
.ev-health-score span { font-size: 11px; color: var(--app-text3); font-weight: 400; }
.ev-health-line { display: flex; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
.ev-health-parts { display: flex; flex-wrap: wrap; gap: 3px 10px; margin-top: 9px; padding-top: 8px; border-top: 1px dashed var(--app-border); font-size: 10.5px; color: var(--app-text3); }
.ev-health-parts b { color: var(--app-text2); }
.ev-metric { display: flex; flex-direction: column; justify-content: space-between; min-height: 118px; }
.ev-metric-label { font-size: 11px; color: var(--app-text3); }
.ev-metric-num { font-size: 24px; font-weight: 600; letter-spacing: -.02em; margin-top: 3px; }
.ev-metric-num i { font-size: 12px; color: var(--app-text3); font-style: normal; font-weight: 400; }
.ev-metric-foot { display: flex; flex-direction: column; gap: 2px; margin-top: 8px; }
.ev-delta { font-size: 10.5px; font-weight: 600; }
.ev-delta.up { color: var(--app-ok); }
.ev-delta.down { color: var(--app-danger); }
.ev-delta.flat { color: var(--app-text3); font-weight: 400; }

.ev-grid-2 { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); gap: 10px; margin-top: 10px; }
.ev-quality { display: flex; align-items: center; gap: 14px; }
.ev-quality-score { font-size: 32px; font-weight: 600; line-height: 1; }
.ev-quality-score.ok { color: var(--app-ok); }
.ev-quality-score.warn { color: var(--app-warn); }
.ev-quality-score.err { color: var(--app-danger); }
.ev-quality-main { flex: 1; min-width: 0; }
.ev-quality-bar { height: 8px; border-radius: 4px; background: var(--app-bg-layer-3, #f2f4f7); overflow: hidden; margin-bottom: 5px; }
.ev-quality-bar i { display: block; height: 100%; border-radius: 4px; background: var(--app-accent); }
.ev-quality-bar i.ok { background: var(--app-ok); }
.ev-quality-bar i.warn { background: var(--app-warn); }
.ev-quality-bar i.err { background: var(--app-danger); }
.ev-quality-rows { margin-top: 10px; display: flex; flex-direction: column; gap: 5px; font-size: 11.5px; color: var(--app-text2); }
.ev-quality-rows div { display: flex; justify-content: space-between; border-bottom: 1px dashed var(--app-border); padding-bottom: 4px; }
.ev-quality-rows b { font-weight: 600; }
.ev-card-foot { display: flex; gap: 12px; margin-top: 9px; }

.ev-issues { display: flex; flex-direction: column; }
.ev-issue-row { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px dashed var(--app-border); }
.ev-issue-row:last-child { border-bottom: none; }
.ev-issue-main { flex: 1; min-width: 0; }
.ev-issue-q { font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ev-recent { display: flex; flex-direction: column; }
.ev-recent-row { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px dashed var(--app-border); cursor: pointer; }
.ev-recent-row:last-child { border-bottom: none; }
.ev-recent-row:hover { background: var(--app-panel-2); }
.ev-recent-main { flex: 1; min-width: 0; font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ev-recent-num { font-size: 12px; font-weight: 600; }

.ev-set-strip { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; font-size: 11px; color: var(--app-text3); padding: 7px 10px; margin-bottom: 9px; border: 1px dashed var(--app-border); border-radius: 6px; background: var(--app-panel-2); }
.ev-set-strip b { color: var(--app-text2); }
.ev-toolbar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 9px; }
.ev-chip { font-family: var(--font); font-size: 11px; color: var(--app-text2); background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 999px; padding: 2px 9px; cursor: pointer; white-space: nowrap; }
.ev-chip b { font-weight: 600; color: var(--app-text); margin-left: 2px; }
.ev-chip.on { color: var(--app-accent); border-color: var(--app-accent-border); background: var(--app-accent-weak); font-weight: 600; }
.ev-search { width: 230px; }
.ev-case-q { font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ev-case-tags { margin-top: 3px; }
.ev-expect-tags { display: flex; flex-wrap: wrap; gap: 3px; }
.ev-pager { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }

.ev-exp-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 9px 10px; border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2); margin-bottom: 10px; }
.ev-label { font-size: 11px; color: var(--app-text3); }
.ev-seg { display: inline-flex; border: 1px solid var(--app-border); border-radius: 6px; overflow: hidden; background: var(--app-panel); }
.ev-seg button { border: none; background: none; font-family: var(--font); font-size: 11px; color: var(--app-text2); padding: 3px 9px; cursor: pointer; border-left: 1px solid var(--app-border); }
.ev-seg button:first-child { border-left: none; }
.ev-seg button.on { background: var(--app-accent); color: #fff; font-weight: 600; }
.ev-kchips { display: inline-flex; gap: 4px; }
.ev-group-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 9px; }
.ev-group { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); padding: 10px; display: flex; flex-direction: column; min-height: 210px; }
.ev-group.base { border-color: var(--app-accent-border); background: #fbfdff; }
.ev-group.off { opacity: .55; }
.ev-group-h { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.ev-group-name { font-weight: 600; padding-left: 0; }
.ev-icon-btn { margin-left: auto; border: none; background: none; color: var(--app-text3); cursor: pointer; font-size: 14px; }
.ev-group-mode { margin-bottom: 7px; }
.ev-params { display: grid; grid-template-columns: 1fr 1fr; gap: 5px 8px; font-size: 10.5px; color: var(--app-text3); }
.ev-params label { display: flex; flex-direction: column; gap: 2px; }
.ev-params :deep(.ant-input-number), .ev-params :deep(.ant-select) { width: 100%; }
.ev-param-switch { flex-direction: row; align-items: center; gap: 4px; white-space: nowrap; }
.ev-group-diff { margin-top: 8px; padding: 5px 7px; border-radius: 5px; font-size: 10px; line-height: 1.5; background: var(--app-warn-weak); color: var(--app-warn-text); border: 1px solid var(--app-warn-border); }
.ev-group-diff.none { background: var(--app-panel-2); color: var(--app-text3); border-color: var(--app-border); }
.ev-group-add { border: 1px dashed var(--app-border); border-radius: 8px; background: transparent; min-height: 210px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; color: var(--app-text3); font-size: 11px; cursor: pointer; font-family: var(--font); }
.ev-group-add:hover { border-color: var(--app-accent-border); color: var(--app-accent); background: var(--app-accent-weak); }
.ev-group-add:disabled { cursor: not-allowed; opacity: .5; }
.ev-plus { font-size: 20px; line-height: 1; }
.ev-run-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 10px; }
.ev-progress { display: inline-flex; align-items: center; gap: 7px; font-size: 11px; color: var(--app-text2); }
.ev-progress-track { width: 150px; height: 6px; border-radius: 3px; background: var(--app-bg-layer-3, #f2f4f7); overflow: hidden; }
.ev-progress-track i { display: block; height: 100%; background: var(--app-accent); border-radius: 3px; transition: width .2s; }
.ev-conclusion { display: flex; align-items: center; gap: 10px; padding: 9px 12px; margin: 10px 0; border: 1px solid var(--app-accent-border); background: #f6f9ff; border-radius: 8px; font-size: 12px; color: var(--app-text2); }
.ev-conclusion > div { flex: 1; }
.ev-ci { font-size: 9.5px; color: var(--app-text3); margin-top: 1px; }
.ev-exp-foot { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-top: 10px; font-size: 11px; color: var(--app-text2); }
.ev-case-results { margin-top: 8px; border: 1px solid var(--app-border); border-radius: 8px; overflow: hidden; }
.ev-case-result { display: flex; align-items: center; gap: 9px; padding: 7px 10px; border-bottom: 1px dashed var(--app-border); }
.ev-case-result:last-child { border-bottom: none; }
.ev-case-result-main { flex: 1; min-width: 0; }
.ev-case-hits { display: flex; gap: 4px; flex-wrap: wrap; max-width: 360px; justify-content: flex-end; }
.ev-segments { margin-top: 10px; }
.ev-seg-row { display: flex; align-items: center; gap: 12px; padding: 4px 0; font-size: 12px; border-bottom: 1px dashed var(--app-border); }
.ev-seg-row:last-child { border-bottom: none; }
.ev-seg-row b { margin-left: auto; }
.ev-seg-row b + b { margin-left: 6px; }

.ev-score-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 10px; }
.ev-score-box { border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; background: var(--app-panel); }
.ev-score-num { font-size: 25px; font-weight: 600; line-height: 1.2; margin-top: 2px; }
.ev-score-num.ok { color: var(--app-ok); }
.ev-score-num.warn { color: var(--app-warn); }
.ev-score-num.err { color: var(--app-danger); }
.ev-score-num.dim { font-size: 20px; color: var(--app-text2); }
.ev-answer-body { font-size: 12px; line-height: 1.8; white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto; color: var(--app-text2); }
.ev-history-chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 10px; font-size: 10.5px; color: var(--app-text3); }
.ev-history-chips span { border: 1px solid var(--app-border); border-radius: 999px; padding: 2px 8px; background: var(--app-panel-2); }

.ev-form { display: flex; flex-direction: column; gap: 12px; }
.ev-form-row { display: flex; flex-direction: column; gap: 5px; }
.ev-form-row label { font-size: 12px; color: var(--app-text2); }
.ev-form-row.inline { flex-direction: row; gap: 18px; }
.ev-run-head { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; margin-bottom: 10px; }
.ev-compare-counts { display: flex; gap: 6px; margin: 8px 0; }

@media (max-width: 1180px) {
  .ev-kpi { grid-template-columns: 1fr 1fr 1fr; }
  .ev-kpi .ev-health { grid-column: span 3; }
  .ev-group-grid { grid-template-columns: 1fr 1fr; }
  .ev-grid-2 { grid-template-columns: 1fr; }
}
@media (max-width: 860px) {
  .ev-kpi { grid-template-columns: 1fr 1fr; }
  .ev-kpi .ev-health { grid-column: span 2; }
  .ev-group-grid { grid-template-columns: 1fr; }
  .ev-score-grid { grid-template-columns: 1fr 1fr; }
  .ev-toolbar .ev-search { width: 100%; }
}
</style>
