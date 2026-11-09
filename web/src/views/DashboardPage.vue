<template>
  <div class="app-page">
    <div class="app-page-head">
      <h3 class="app-page-title">数据看板</h3>
      <span class="head-stat">问答质量、执行记录与知识回流闭环</span>
      <div class="head-acts">
        <span class="data-ts" :title="fmtTime(summary.dataThrough)">数据截至 {{ fmtClock(summary.dataThrough) }}</span>
        <a-segmented v-model:value="range" size="small" :options="rangeOptions" @change="onRangeChange" />
        <a-checkbox v-model:checked="compare">对比上期</a-checkbox>
        <a-dropdown :trigger="['click']">
          <button class="app-btn ghost small">视图</button>
          <template #overlay>
            <div class="view-menu">
              <div class="vm-title">已保存视图</div>
              <div v-if="!savedViews.length" class="vm-empty">还没有保存的视图</div>
              <div v-for="v in savedViews" :key="v.id" class="vm-row" @click="applyView(v)">
                <span class="vm-name">{{ v.name }}</span>
                <span class="vm-del" title="删除视图" @click.stop="removeView(v.id)">×</span>
              </div>
              <div class="vm-sep"></div>
              <div class="vm-row vm-save" @click="openSaveView">＋ 保存当前视图</div>
            </div>
          </template>
        </a-dropdown>
        <button class="app-btn ghost small" @click="layoutEdit = !layoutEdit">{{ layoutEdit ? '完成布局' : '布局' }}</button>
        <a-checkbox v-model:checked="autoRefresh">自动刷新</a-checkbox>
        <button class="app-btn ghost small" @click="copyLink">复制链接</button>
        <button class="app-btn ghost small" @click="exportCsv">导出</button>
        <button class="app-btn ghost small" :disabled="overviewLoading" @click="reloadAll">刷新</button>
      </div>
    </div>

    <div class="app-page-body">
      <!-- 阈值预警横幅（P2）：只在真的越界时出现，可忽略本次 -->
      <div v-if="alerts.triggered && !alertsClosed" class="app-card alert-banner">
        <span class="alert-dot" />
        <div class="alert-main">
          <b>近 7 天有 {{ alerts.items.length }} 项指标越界</b>
          <span v-for="it in alerts.items" :key="it.key" class="alert-item" :class="it.level">{{ it.title }}</span>
        </div>
        <button class="app-link-btn" @click="goAlert">去处理</button>
        <button class="app-link-btn" @click="alertsClosed = true">忽略本次</button>
      </div>

      <a-tabs v-model:activeKey="tab" @change="onTabChange">
        <!-- ============================================================ 总览 ============================================================ -->
        <a-tab-pane key="overview" tab="总览">
          <a-spin :spinning="overviewLoading">
            <div v-if="layoutEdit" class="layout-bar">
              布局模式：用 ↑ ↓ 调整卡片顺序，顺序会自动保存
              <button class="app-link-btn" @click="resetLayout">恢复默认</button>
            </div>
            <div class="overview-sortable">
            <div class="sort-block" :style="{ order: orderOf('kpi') }">
              <div v-if="layoutEdit" class="sort-ctrls">
                <button class="sort-btn" :disabled="orderOf('kpi') === 0" @click="moveBlock('kpi', -1)">↑</button>
                <button class="sort-btn" :disabled="orderOf('kpi') === blockOrder.length - 1" @click="moveBlock('kpi', 1)">↓</button>
              </div>
            <div class="kpi-row">
              <div class="app-card health-card">
                <div class="app-card-title">问答健康度
                  <a-popover placement="bottomLeft" trigger="click">
                    <template #content>
                      <div class="health-doc">
                        <div class="hd-title">健康度口径</div>
                        <div>命中率 40%：100 − 无命中率</div>
                        <div>引用率 20%：有引用标注占比</div>
                        <div>满意率 30%：👍 / 全部反馈（无反馈按中性 88 计）</div>
                        <div>体检 10%：正常 100 / 下滑 45 / 未跑 80</div>
                        <div class="hd-foot">≥80 良好 · 60–79 需关注 · &lt;60 风险</div>
                      </div>
                    </template>
                    <span class="card-sub" style="cursor:pointer">{{ rangeLabel }} · 4 项加权 <i class="hd-i">?</i></span>
                  </a-popover>
                </div>
                <div class="health-body">
                  <a-progress type="circle" :percent="health.score || 0" :size="76" :stroke-width="7"
                              :stroke-color="healthColor" :format="() => String(health.score || 0)" />
                  <div class="health-side">
                    <span class="app-pill" :class="healthPill">{{ healthText }}</span>
                    <div v-if="compare && healthDelta !== null" class="health-delta" :class="healthDelta >= 0 ? 'up' : 'down'">
                      {{ healthDelta >= 0 ? '▲ +' : '▼ ' }}{{ healthDelta }} 较上期
                    </div>
                    <div class="health-cap">满分 100</div>
                  </div>
                </div>
                <div class="health-parts">
                  <span>命中 <b>{{ health.parts?.hitRate ?? '—' }}%</b></span>
                  <span>引用 <b>{{ health.parts?.citationRate ?? '—' }}%</b></span>
                  <span>满意 <b>{{ health.parts?.likeRate ?? '—' }}{{ health.parts?.likeRate != null ? '%' : '' }}</b></span>
                  <span>体检 <b>{{ checkupLabel }}</b></span>
                </div>
              </div>

              <div v-for="m in metrics" :key="m.key" class="app-card metric-card">
                <div class="metric-label">{{ m.label }}</div>
                <div class="metric-main">
                  <div class="metric-num">{{ m.value }}<i v-if="m.unit" class="metric-unit">{{ m.unit }}</i></div>
                  <span v-if="compare && m.deltaText" class="metric-delta" :class="m.deltaClass">{{ m.deltaText }}</span>
                </div>
                <div class="metric-foot">
                  <svg class="spark" viewBox="0 0 100 30" preserveAspectRatio="none">
                    <defs>
                      <linearGradient :id="'sg-' + m.key" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0" :stop-color="m.color" stop-opacity=".2" />
                        <stop offset="1" :stop-color="m.color" stop-opacity="0" />
                      </linearGradient>
                    </defs>
                    <path :d="m.area" :fill="'url(#sg-' + m.key + ')'" />
                    <path :d="m.line" fill="none" :stroke="m.color" stroke-width="1.6" stroke-linejoin="round" />
                  </svg>
                  <span class="metric-hint">{{ m.hint }}</span>
                </div>
              </div>
            </div>
            </div>

            <div class="sort-block" :style="{ order: orderOf('charts') }">
              <div v-if="layoutEdit" class="sort-ctrls">
                <button class="sort-btn" :disabled="orderOf('charts') === 0" @click="moveBlock('charts', -1)">↑</button>
                <button class="sort-btn" :disabled="orderOf('charts') === blockOrder.length - 1" @click="moveBlock('charts', 1)">↓</button>
              </div>
            <div class="chart-row">
              <div class="app-card">
                <div class="app-card-title">问答趋势
                  <div class="legend">
                    <span><i style="background:var(--app-accent)" />问答量</span>
                    <span><i style="background:var(--app-ok)" />有引用</span>
                    <span><i style="background:var(--app-warn)" />无命中</span>
                  </div>
                  <span class="card-sub" style="margin-left:auto">{{ rangeLabel }}</span>
                </div>
                <div ref="trendEl" class="echart trend" />
              </div>

              <div class="app-card">
                <div class="app-card-title">命中构成 <span class="card-sub">按问答条数</span></div>
                <div class="donut-wrap">
                  <div ref="donutEl" class="echart donut" />
                  <div class="donut-legend">
                    <div class="dl-row"><i class="dl-dot" style="background:var(--app-accent)" />
                      <div><div class="dl-t">有引用命中</div><div class="dl-s">{{ fmtInt(citedCount) }} 条</div></div>
                      <b>{{ citationPct }}%</b>
                    </div>
                    <div class="dl-row"><i class="dl-dot" style="background:#e0a44a" />
                      <div><div class="dl-t">无命中</div><div class="dl-s">{{ fmtInt(noHitCount) }} 条</div></div>
                      <b>{{ summary.noHitRate || 0 }}%</b>
                    </div>
                  </div>
                </div>
                <div class="sat-block">
                  <div class="sat-head"><span>反馈满意度</span><b>{{ fb.likeRate == null ? '暂无反馈' : fb.likeRate + '%' }}</b></div>
                  <div class="sat-bar"><i :style="{ width: (fb.likeRate || 0) + '%' }" /><em /></div>
                  <div class="sat-foot"><span>👍 {{ fb.likes || 0 }}</span><span>👎 {{ fb.dislikes || 0 }}</span></div>
                </div>
              </div>
            </div>
            </div>

            <div class="sort-block" :style="{ order: orderOf('tops') }">
              <div v-if="layoutEdit" class="sort-ctrls">
                <button class="sort-btn" :disabled="orderOf('tops') === 0" @click="moveBlock('tops', -1)">↑</button>
                <button class="sort-btn" :disabled="orderOf('tops') === blockOrder.length - 1" @click="moveBlock('tops', 1)">↓</button>
              </div>
            <div class="two-col">
              <div class="app-card">
                <div class="app-card-title">热门问题 TOP10 <span class="card-sub">点击下钻到执行记录</span></div>
                <div v-if="(summary.topQuestions || []).length" class="rank-list">
                  <div v-for="(q, i) in summary.topQuestions" :key="q.question" class="rank-row" @click="drill(q.question)">
                    <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                    <span class="rank-q" :title="q.question">{{ q.question }}</span>
                    <div class="rank-bar"><i :style="{ width: barWidth(q.count, topMax) }" /></div>
                    <span class="rank-c">{{ q.count }}</span>
                  </div>
                </div>
                <a-empty v-else :image="false" description="该时间范围内暂无问答" />
              </div>

              <div class="app-card">
                <div class="app-card-title">无命中问题 TOP10 <span class="card-sub">建议补充知识库</span>
                  <button class="app-link-btn" style="margin-left:auto" @click="showBadTraces">查看差评执行</button>
                </div>
                <div v-if="(summary.noHitQuestions || []).length" class="rank-list">
                  <div v-for="(q, i) in summary.noHitQuestions" :key="q.question" class="rank-row has-act"
                       @click="drill(q.question)">
                    <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
                    <span class="rank-q" :title="q.question">{{ q.question }}</span>
                    <div class="rank-bar"><i class="warm" :style="{ width: barWidth(q.count, noHitMax) }" /></div>
                    <span class="rank-c">{{ q.count }}</span>
                    <button class="mini-link" @click.stop="openAdd({ question: q.question })">入库</button>
                  </div>
                </div>
                <a-empty v-else :image="false" description="该时间范围内没有无命中问题" />
              </div>
            </div>
            </div>

            <div class="sort-block" :style="{ order: orderOf('checkup') }">
              <div v-if="layoutEdit" class="sort-ctrls">
                <button class="sort-btn" :disabled="orderOf('checkup') === 0" @click="moveBlock('checkup', -1)">↑</button>
                <button class="sort-btn" :disabled="orderOf('checkup') === blockOrder.length - 1" @click="moveBlock('checkup', 1)">↓</button>
              </div>
            <!-- 体检条：正常时只占一行，详情按需展开 -->
            <div class="app-card checkup-strip">
              <span class="checkup-dot" :class="checkupDot" />
              <b>检索质量 · {{ checkupLabel }}</b>
              <span class="checkup-meta">
                {{ checkup ? (checkup.caseCount ? '评估集 ' + checkup.caseCount + ' 题 · ' : '')
                  + fmtTime(checkup.runTime) : '暂无体检记录 —— 生成评估集后次日自动跑，或点右侧「立即体检」' }}
              </span>
              <template v-if="checkMetrics.length">
                <span v-for="row in checkMetrics.slice(0, 3)" :key="row.name" class="chip">
                  {{ row.short }} <b>{{ row.value }}</b>
                  <span v-if="row.delta != null" :class="row.delta < 0 ? 'down' : (row.delta > 0 ? 'up' : 'flat')">
                    {{ row.delta > 0 ? '▲+' : (row.delta < 0 ? '▼' : '') }}{{ row.delta }}%
                  </span>
                </span>
              </template>
              <div class="checkup-acts">
                <button v-if="checkMetrics.length" class="app-link-btn" @click="checkupOpen = !checkupOpen">
                  {{ checkupOpen ? '收起' : '详情' }}
                </button>
                <button class="app-btn ghost small" :disabled="checkLoading" @click="doAutoCheck">立即体检</button>
              </div>
            </div>
            <a-alert v-if="checkup?.paramsStale" type="warning" show-icon style="margin-top:8px"
                     message="这份指标是在旧参数下测的" :description="paramsDriftText" />
            <div v-if="checkupOpen && checkMetrics.length" class="app-card">
              <a-table :data-source="checkMetrics" :columns="checkCols" size="small" row-key="name"
                       :pagination="false" :locale="{ emptyText: '暂无指标' }" />
            </div>
            </div>

            <div class="sort-block" :style="{ order: orderOf('actions') }">
              <div v-if="layoutEdit" class="sort-ctrls">
                <button class="sort-btn" :disabled="orderOf('actions') === 0" @click="moveBlock('actions', -1)">↑</button>
                <button class="sort-btn" :disabled="orderOf('actions') === blockOrder.length - 1" @click="moveBlock('actions', 1)">↓</button>
              </div>
            <!-- 待办闭环：差评 / 缺口 / 待标注三源合一 -->
            <div class="app-card">
              <div class="app-card-title aq-title">
                待办闭环 <span class="card-sub">差评 / 知识缺口 / 待标注合并，处置后就地减少</span>
                <div class="aq-progress">
                  <div class="aq-track"><i :style="{ width: closedPct + '%' }" /></div>
                  <span>本周已闭环 <b>{{ counts.closedThisWeek || 0 }}</b> / {{ (counts.closedThisWeek || 0) + (counts.total || 0) }}</span>
                </div>
              </div>
              <div class="aq-chips">
                <button v-for="c in aqChips" :key="c.key" class="aq-chip" :class="{ on: aqFilter === c.key }"
                        @click="aqFilter = c.key">{{ c.label }} {{ c.count }}</button>
              </div>
              <div v-if="filteredActions.length">
                <div v-for="(row, i) in filteredActions" :key="row.type + '-' + i" class="aq-row">
                  <span class="src" :class="row.type">{{ srcLabel(row.type) }}</span>
                  <div class="aq-main">
                    <div class="aq-q" :title="row.question">{{ row.question }}</div>
                    <div class="aq-meta">{{ row.meta }}<template v-if="row.at"> · {{ fmtAgo(row.at) }}</template></div>
                  </div>
                  <div class="aq-acts">
                    <template v-if="row.type === 'bad'">
                      <a-tooltip :title="(row.knowledgeIds || []).length ? '加入评估集（问题→引用过的知识块）' : '该轮无引用块，无法加入评估集'">
                        <button class="app-link-btn" :disabled="!(row.knowledgeIds || []).length" @click="addToEval(row)">加入评估集</button>
                      </a-tooltip>
                      <button class="app-link-btn" @click="openAdd({ question: row.question, answer: row.answer })">补知识块</button>
                    </template>
                    <template v-else-if="row.type === 'gap'">
                      <button class="app-link-btn" @click="openAdd({ question: row.question })">入库</button>
                      <button class="app-link-btn" @click="drill(row.question)">看执行</button>
                    </template>
                    <template v-else>
                      <button class="app-link-btn"
                              @click="openLabel({ id: row.sampleId, qaLogId: row.qaLogId, question: row.question, answerSummary: row.answerSummary })">标注回流</button>
                      <button class="app-link-btn danger" @click="doDismiss({ id: row.sampleId })">忽略</button>
                    </template>
                  </div>
                </div>
              </div>
              <a-empty v-else :image="false" :description="counts.total ? '当前筛选下没有待处理项' : '这个时间范围内没有待处理项'" />
            </div>
            </div>
            </div>
          </a-spin>
        </a-tab-pane>

        <!-- ============================================================ 执行记录 ============================================================ -->
        <a-tab-pane key="trace" tab="执行记录">
          <div class="stat-strip">
            <div v-for="s in traceCards" :key="s.key" class="app-card mini-stat">
              <div class="metric-label">{{ s.label }}</div>
              <div class="metric-main">
                <div class="metric-num">{{ s.value }}<i v-if="s.unit" class="metric-unit">{{ s.unit }}</i></div>
                <span class="metric-hint">{{ s.hint }}</span>
              </div>
            </div>
          </div>

          <div class="app-card" style="margin-top:10px">
            <div class="app-card-title">每日执行量
              <span class="card-sub">{{ traceDaysLabel }}</span>
              <div class="legend">
                <span><i style="background:var(--app-accent)" />对话</span>
                <span><i style="background:#bcd0f7" />工作流</span>
                <span><i style="background:var(--app-ok)" />成功率</span>
              </div>
            </div>
            <div ref="traceEl" class="echart trace-chart" />
          </div>

          <div class="app-card" style="margin-top:10px">
            <div class="trace-filters">
              <a-select v-model:value="traceKind" style="width:110px" :options="[{ value: 'chat', label: '对话' }, { value: 'workflow', label: '工作流' }]" @change="loadTraces" />
              <a-select v-model:value="traceDays" style="width:120px"
                        :options="[{ value: 1, label: '近 24 小时' }, { value: 7, label: '近 7 天' }, { value: 30, label: '近 30 天' }, { value: 90, label: '近 90 天' }, { value: 0, label: '全部' }]"
                        @change="onTraceDaysChange" />
              <template v-if="traceKind === 'chat'">
                <a-select v-model:value="traceHasCitation" style="width:120px" allow-clear placeholder="引用"
                          :options="[{ value: 1, label: '有引用' }, { value: 0, label: '无引用' }]" @change="loadTraces" />
                <a-select v-model:value="traceRating" style="width:120px" allow-clear placeholder="评分"
                          :options="[{ value: 1, label: '👍 好评' }, { value: 0, label: '👎 差评' }]" @change="loadTraces" />
              </template>
              <a-input-search v-model:value="traceKeyword" placeholder="关键词（问题/入参）" style="width:220px"
                              allow-clear @search="loadTraces" />
              <button class="app-btn ghost small" style="margin-left:auto" @click="loadTraces">查询</button>
            </div>

            <div v-if="drillKeyword" class="drill-chip">
              <span>来自看板下钻：<b>{{ drillKeyword }}</b></span>
              <button class="app-link-btn" @click="clearDrill">清除</button>
            </div>

            <a-table :data-source="traceRows" :columns="traceCols" size="small" row-key="id"
                     :loading="traceLoading" :pagination="tracePagination" :locale="{ emptyText: '暂无执行记录' }"
                     :custom-row="record => ({ onClick: () => openTraceDetail(record) })"
                     @change="onTraceTableChange">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'statusCol'">
                  <template v-if="record.kind === 'workflow'">
                    <a-tag :color="wfStatusColor(record.status)">{{ wfStatusLabel(record.status) }}</a-tag>
                    <a-tag v-if="record.version != null" color="blue">v{{ record.version }}</a-tag>
                  </template>
                  <template v-else>
                    <span class="wf-run-dot" :class="record.hasCitation === 1 ? 'rd-success' : 'rd-failed'" />
                    <span class="trace-ct">{{ record.hasCitation === 1 ? '有引用' : '无引用' }}</span>
                    <span v-if="record.rating === 1" title="好评">👍</span>
                    <span v-else-if="record.rating === 0" title="差评">👎</span>
                  </template>
                </template>
                <template v-if="column.key === 'elapsedMs'">
                  <div class="dur-cell">
                    <div class="dur-track"><i :style="{ width: durWidth(record.elapsedMs) }" /></div>
                    <span>{{ record.elapsedMs != null ? record.elapsedMs + ' ms' : '—' }}</span>
                  </div>
                </template>
                <template v-if="column.key === 'action'">
                  <button class="app-link-btn" @click.stop="openTraceDetail(record)">详情</button>
                </template>
              </template>
            </a-table>
            <div class="table-hint">点击「详情」查看问题、回答、引用来源、工具调用、Token 与反馈。</div>
          </div>
        </a-tab-pane>

        <!-- ============================================================ 采样标注 ============================================================ -->
        <a-tab-pane key="pool">
          <template #tab>
            <span class="tab-label">采样标注<em v-if="counts.pool" class="tab-badge">{{ counts.pool }}</em></span>
          </template>
          <div class="app-card">
            <div class="app-card-title">采样闭环漏斗 <span class="card-sub">入池 → 处置 → 回流评测集</span>
              <button class="app-btn ghost small" style="margin-left:auto" :disabled="sampling" @click="doSample">立即采样</button>
            </div>
            <div class="funnel">
              <template v-for="(s, i) in funnel" :key="s.label">
                <div class="f-stage">
                  <div class="f-num">{{ s.value }}</div>
                  <div class="f-label">{{ s.label }}</div>
                  <div class="f-pct">{{ s.pct }}%</div>
                </div>
                <div v-if="i < funnel.length - 1" class="f-arrow">→</div>
              </template>
            </div>
            <div class="table-hint">
              入池 {{ poolStats.pending + poolStats.labeled + poolStats.dismissed }} 条，已处置
              {{ poolStats.labeled + poolStats.dismissed }} 条（已忽略 {{ poolStats.dismissed || 0 }} 条）；
              待标注 {{ poolStats.pending || 0 }} 条。最近入池：{{ fmtTime(poolStats.lastSampledAt) }}
            </div>
          </div>

          <div class="app-card" style="margin-top:10px">
            <div class="app-card-title">样本队列
              <a-segmented v-model:value="poolStatus" size="small" style="margin-left:auto"
                           :options="[{ value: 'pending', label: '待标注' }, { value: 'labeled', label: '已回流' }, { value: 'dismissed', label: '已忽略' }]"
                           @change="loadPool" />
            </div>
            <div v-if="poolRows.length" class="sample-grid">
              <div v-for="s in poolRows" :key="s.id" class="sample-card">
                <div class="sample-top">
                  <span class="src" :class="srcClass(s.source)">{{ srcText(s.source) }}</span>
                  <span class="sample-q" :title="s.question">{{ s.question || '（关联问答已删除）' }}</span>
                  <span class="sample-time">{{ fmtAgo(s.createdAt) }}</span>
                </div>
                <div class="sample-a">{{ s.answerSummary || '（该轮没有回答摘要）' }}</div>
                <div class="sample-foot">
                  <span class="sample-note">{{ s.note || '' }}</span>
                  <div class="sample-acts">
                    <template v-if="s.status === 'pending'">
                      <button class="app-link-btn" @click="openLabel(s)">标注回流</button>
                      <button class="app-link-btn danger" @click="doDismiss(s)">忽略</button>
                    </template>
                    <span v-else class="app-pill" :class="s.status === 'labeled' ? 'ok' : 'muted'">
                      {{ s.status === 'labeled' ? '已回流' : '已忽略' }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
            <a-empty v-else :image="false" description="池子里没有该状态的样本" />
            <div class="pool-pager">
              <button class="app-btn ghost small" :disabled="poolPage <= 1" @click="loadPool(poolPage - 1)">上一页</button>
              <span>第 {{ poolPage }} 页 · 共 {{ poolTotal }} 条</span>
              <button class="app-btn ghost small" :disabled="poolPage * POOL_SIZE >= poolTotal" @click="loadPool(poolPage + 1)">下一页</button>
            </div>
          </div>
        </a-tab-pane>

        <!-- ============================================================ 费用与配额 ============================================================ -->
        <a-tab-pane key="cost" tab="费用与配额">
          <div class="app-card">
            <div class="app-card-title">预算水位 <span class="card-sub">按台账现算 · 阈值 80%</span>
              <a-segmented v-model:value="costRange" size="small" style="margin-left:auto"
                           :options="[{ value: 7, label: '近 7 日' }, { value: 30, label: '近 30 日' }]" @change="loadCost" />
              <button class="app-link-btn" @click="openQuota()">新建配额</button>
            </div>
            <div v-if="budgetRows.length">
              <div v-for="b in budgetRows" :key="b.id" class="budget-row">
                <div class="budget-name">
                  <div class="b-name">{{ b.name }}</div>
                  <div class="b-sub">{{ b.sub }}</div>
                </div>
                <div class="budget-track">
                  <div class="budget-fill" :class="b.tone" :style="{ width: Math.min(100, b.pct) + '%' }" />
                  <span class="budget-threshold" />
                </div>
                <div class="budget-meta">
                  <b>{{ b.used }}</b> / {{ b.limit }}
                  <span v-if="b.state" class="b-state" :class="b.tone">{{ b.state }}</span>
                </div>
              </div>
            </div>
            <a-empty v-else :image="false" description="还没设任何上限 —— 当前不限额" />
          </div>

          <div class="cost-grid">
            <div class="app-card">
              <div class="app-card-title">每日费用 <span class="card-sub">{{ costRangeLabel }}</span>
                <span class="card-sub" style="margin-left:auto">未计价 tokens {{ fmtTokensCompact(cost.unpricedTokens) }}</span>
              </div>
              <div ref="costEl" class="echart cost-chart" />
            </div>
            <div class="app-card">
              <div class="app-card-title">按模型 <span class="card-sub">费用占比</span></div>
              <div class="donut-wrap">
                <div ref="modelEl" class="echart model-donut" />
                <div class="donut-legend">
                  <div v-for="(m, i) in modelRows.slice(0, 4)" :key="m.model" class="dl-row">
                    <i class="dl-dot" :style="{ background: modelColor(i) }" />
                    <div><div class="dl-t">{{ m.label || m.model }}</div><div class="dl-s">{{ m.cost != null ? fmtCost(m.cost) : '未计价' }}</div></div>
                    <b>{{ modelPct(m) }}%</b>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="cost-grid2">
            <div class="app-card">
              <div class="app-card-title">按用户 <span class="card-sub">费用</span></div>
              <div v-for="u in (cost.byUser || []).slice(0, 6)" :key="u.uid" class="user-bar">
                <span class="ub-name" :title="u.name">{{ u.name }}</span>
                <div class="ub-track"><i :style="{ width: userBarWidth(u) }" /></div>
                <span class="ub-val">{{ u.cost != null ? fmtCost(u.cost) : '未计价' }}</span>
              </div>
              <a-empty v-if="!(cost.byUser || []).length" :image="false" description="该时间范围内没有用量" />
            </div>
            <div class="app-card">
              <div class="app-card-title">配额明细 <span class="card-sub">按台账现算</span>
                <button class="app-link-btn" style="margin-left:auto" @click="openQuota()">新建</button>
              </div>
              <a-table :data-source="quotas" :columns="quotaCols" size="small" row-key="id"
                       :loading="quotaLoading" :pagination="false"
                       :locale="{ emptyText: '还没设任何上限——当前不限额' }">
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'tokenLimit'">
                    {{ Number(record.tokenLimit) > 0 ? fmtTokensCompact(record.tokenLimit) : '—' }}
                  </template>
                  <template v-else-if="column.key === 'costLimit'">
                    {{ record.costLimit != null ? fmtCost(record.costLimit) : '—' }}
                  </template>
                  <template v-else-if="column.key === 'used'">
                    <div>{{ fmtTokensCompact(record.usedTokens) }}<template v-if="record.costLimit != null"> · {{ fmtCost(record.usedCost) }}</template></div>
                    <div class="metric-sub">
                      {{ record.tokenPercent != null ? record.tokenPercent + '%' : '' }}<template v-if="record.tokenPercent != null && record.costPercent != null"> / </template>{{ record.costPercent != null ? record.costPercent + '%' : '' }}
                    </div>
                  </template>
                  <template v-else-if="column.key === 'state'">
                    <a-tag v-if="record.breach" :color="Number(record.enforce) === 1 ? 'red' : 'orange'">
                      {{ record.breach === 'token' ? 'token 到顶' : '费用到顶' }}·{{ Number(record.enforce) === 1 ? '已拦下' : '只告警' }}
                    </a-tag>
                    <a-tag v-else-if="Number(record.tokenPercent || 0) >= 80 || Number(record.costPercent || 0) >= 80" color="gold">接近上限</a-tag>
                    <span v-else class="metric-sub">正常</span>
                  </template>
                  <template v-else-if="column.key === 'op'">
                    <button class="app-link-btn" @click="openQuota(record)">编辑</button>
                    <button class="app-link-btn" style="margin-left:8px" @click="removeQuota(record)">删除</button>
                  </template>
                </template>
              </a-table>
              <div class="table-hint">到顶后拦的是用户主动发起的调用（对话 / 子智能体 / 工作流）；历史压缩、记忆提炼这类辅助调用不拦。</div>
            </div>
          </div>

          <!-- 明细表降为第二层：需要精确值时展开 -->
          <div class="app-card" style="margin-top:10px">
            <a-collapse ghost>
              <a-collapse-panel key="detail" header="费用明细（按用户 / 按模型 / 按部门 / 租户）">
                <div v-if="unpricedList.length || Number(cost.unregisteredTokens) > 0" class="unpriced-box">
                  <div class="unpriced-title">未计价模型
                    <span class="card-sub">{{ unpricedList.length
                      ? '在「智能体 → 模型供应商 → 管理模型 → 编辑」里登记单价后即可计入费用'
                      : '这些 token 对应的模型记录已不在册，无处登记单价' }}</span>
                  </div>
                  <div class="unpriced-tags">
                    <a-tag v-for="m in shownUnpriced" :key="m.label" color="orange">
                      {{ m.label }} · {{ fmtTokensCompact(m.tokens) }}<template v-if="Number(m.refs) > 1">（{{ m.refs }} 个供应商）</template>
                    </a-tag>
                    <button v-if="unpricedList.length > UNPRICED_SHOWN" class="app-link-btn" @click="unpricedAll = !unpricedAll">
                      {{ unpricedAll ? '收起' : `展开全部 ${unpricedList.length} 类` }}
                    </button>
                  </div>
                </div>
                <div v-if="cost.crossTenant && (cost.byTenant || []).length > 1" class="detail-block">
                  <div class="detail-title">按租户结算</div>
                  <a-table :data-source="cost.byTenant || []" :columns="costTenantCols" size="small" row-key="tenantId"
                           :pagination="costPager" :locale="{ emptyText: '该时间范围内没有用量' }">
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '未计价' }}</template>
                    </template>
                  </a-table>
                </div>
                <div class="detail-block">
                  <div class="detail-title">按用户</div>
                  <a-table :data-source="cost.byUser || []" :columns="costUserCols" size="small" row-key="uid"
                           :pagination="costPager" :locale="{ emptyText: '该时间范围内没有用量' }">
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '—' }}</template>
                    </template>
                  </a-table>
                </div>
                <div class="detail-block">
                  <div class="detail-title">按模型</div>
                  <a-table :data-source="cost.byModel || []" :columns="costModelCols" size="small" row-key="model"
                           :pagination="costPager" :locale="{ emptyText: '该时间范围内没有用量' }">
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '未计价' }}</template>
                    </template>
                  </a-table>
                </div>
                <div class="detail-block">
                  <div class="detail-title">按部门</div>
                  <a-table :data-source="cost.byDepartment || []" :columns="costDeptCols" size="small"
                           :row-key="r => r.departmentId || 'none'" :pagination="costPager"
                           :locale="{ emptyText: '该时间范围内没有用量' }">
                    <template #bodyCell="{ column, record }">
                      <template v-if="column.key === 'cost'">{{ record.cost != null ? fmtCost(record.cost) : '未计价' }}</template>
                    </template>
                  </a-table>
                </div>
              </a-collapse-panel>
            </a-collapse>
          </div>
        </a-tab-pane>
      </a-tabs>

      <!-- ==================== 配额编辑 ==================== -->
      <a-modal v-model:open="quotaModal" :title="quotaForm.id ? '编辑配额' : '新建配额'" @ok="submitQuota">
        <a-form layout="vertical">
          <a-form-item v-if="superadmin" label="归属租户" extra="平台级管理员可给任一租户设限；租户管理员固定为本租户">
            <a-select v-model:value="quotaForm.tenantId" :options="tenantOptions" style="width:100%" :disabled="!!quotaForm.id" />
          </a-form-item>
          <a-form-item label="作用域">
            <a-segmented v-model:value="quotaForm.scope" :options="quotaScopeOptions" @change="onQuotaScopeChange" />
          </a-form-item>
          <a-form-item v-if="quotaForm.scope === 'department'" label="部门">
            <a-select v-model:value="quotaForm.scopeId" :options="deptOptions" placeholder="选部门" style="width:100%" />
          </a-form-item>
          <a-form-item v-if="quotaForm.scope === 'user'" label="成员">
            <a-select v-model:value="quotaForm.scopeId" :options="memberOptions" placeholder="选成员" style="width:100%"
                      show-search option-filter-prop="label" />
          </a-form-item>
          <a-form-item label="周期">
            <a-segmented v-model:value="quotaForm.period" :options="[{ value: 'day', label: '按日' }, { value: 'month', label: '按月' }]" />
          </a-form-item>
          <a-form-item label="token 上限（0＝不按 token 限）">
            <a-input-number v-model:value="quotaForm.tokenLimit" :min="0" :step="100000" style="width:100%"
                            :formatter="v => (v || 0).toLocaleString('zh-CN')" :parser="v => v.replace(/[^0-9]/g, '')" />
          </a-form-item>
          <a-form-item label="费用上限（元，留空＝不按金额限）">
            <a-input-number v-model:value="quotaForm.costLimit" :min="0" :step="1" style="width:100%" />
            <div class="metric-sub">未配单价的模型不计金额（token 照算）。</div>
          </a-form-item>
          <a-form-item label="到额处理">
            <a-switch v-model:checked="quotaForm.enforce" checked-children="熔断" un-checked-children="只告警" />
          </a-form-item>
          <a-form-item label="备注（为什么设这条）">
            <a-input v-model:value="quotaForm.remark" :maxlength="200" placeholder="如：外部合作账号只给 200 万/月" />
          </a-form-item>
        </a-form>
      </a-modal>

      <!-- ==================== Trace 详情 ==================== -->
      <a-drawer v-model:open="detailDrawer" :title="detailTitle" width="560" destroy-on-close>
        <a-spin :spinning="detailLoading">
          <template v-if="detail">
            <template v-if="detail.kind === 'chat'">
              <div class="dt-block"><div class="dt-label">问题</div><pre class="dt-pre">{{ detail.log?.question }}</pre></div>
              <div class="dt-block"><div class="dt-label">回答摘要</div><pre class="dt-pre">{{ detail.log?.answerSummary }}</pre></div>
              <template v-if="detail.message">
                <div class="dt-block"><div class="dt-label">回答全文</div><pre class="dt-pre">{{ detail.message.content }}</pre></div>
                <div class="dt-block">
                  <div class="dt-label">引用来源（{{ (detail.message.sources || []).length }}）</div>
                  <div v-for="(s, i) in detail.message.sources" :key="i" class="dt-source">
                    <span class="dt-source-name">{{ s.title || s.docTitle || s.docId }}</span>
                    <span class="dt-meta">{{ s.knowledgeId ? '块 ' + s.knowledgeId.slice(0, 8) : '' }}</span>
                  </div>
                  <div v-if="!(detail.message.sources || []).length" class="dt-meta">无引用</div>
                </div>
                <div class="dt-block">
                  <div class="dt-label">工具调用（{{ (detail.message.toolCalls || []).length }}）· Token（{{ detail.message.tokens?.total || 0 }}）</div>
                  <div v-for="(t, i) in detail.message.toolCalls" :key="i" class="dt-tool">
                    <div class="dt-tool-head" @click="t._open = !t._open">
                      <span class="dt-tool-name">{{ t.label || t.name }}</span>
                      <span v-if="t.label && t.label !== t.name" class="dt-meta">{{ t.name }}</span>
                      <a-tag :color="toolStatusColor(t.status)">{{ toolStatusLabel(t.status) }}</a-tag>
                      <span v-if="t.elapsedMs != null" class="dt-meta">{{ t.elapsedMs }} ms</span>
                      <span class="dt-meta dt-tool-toggle">{{ t._open ? '收起' : '展开' }}</span>
                    </div>
                    <div v-if="t._open" class="dt-tool-body">
                      <div v-if="t.hint" class="dt-tool-hint">{{ t.hint }}</div>
                      <template v-if="toolArgFields(t).length">
                        <div class="dt-io-label">入参</div>
                        <div v-for="f in toolArgFields(t)" :key="f.key" class="dt-arg">
                          <div class="dt-arg-key">{{ f.key }}</div>
                          <pre class="dt-pre dt-arg-val">{{ toolArgText(t, f) }}</pre>
                          <a v-if="f.truncated" class="dt-arg-more" @click.stop="toggleArg(t, f.key)">
                            {{ toolArgOpen(t, f.key) ? '收起' : `展开全部（${f.total} 字）` }}
                          </a>
                        </div>
                      </template>
                      <template v-if="t.result">
                        <div class="dt-io-label">输出</div>
                        <pre class="dt-pre">{{ t.result }}</pre>
                      </template>
                    </div>
                  </div>
                  <div v-if="!(detail.message.toolCalls || []).length" class="dt-meta">本轮没有工具调用</div>
                </div>
              </template>
              <div v-else class="dt-block"><div class="dt-label">全过程</div><div class="dt-meta">该记录较早，只有摘要视图。</div></div>
              <div v-if="detail.feedback" class="dt-block">
                <div class="dt-label">用户反馈</div>
                <pre class="dt-pre">{{ detail.feedback.rating === 1 ? '👍 好评' : '👎 差评' }}{{ detail.feedback.text ? '：' + detail.feedback.text : '' }}</pre>
              </div>
              <div v-if="detail.sample" class="dt-block">
                <div class="dt-label">采样状态</div>
                <a-tag :color="detail.sample.status === 'pending' ? 'orange' : detail.sample.status === 'labeled' ? 'green' : 'default'">
                  {{ { pending: '待标注', labeled: '已回流', dismissed: '已忽略' }[detail.sample.status] }}
                </a-tag>
              </div>
            </template>
            <template v-else>
              <div class="dt-block">
                <div class="dt-label">运行信息</div>
                <div class="dt-kv"><span>工作流</span><b>{{ detail.workflowName }}</b></div>
                <div class="dt-kv"><span>状态</span><a-tag :color="wfStatusColor(detail.run?.status)">{{ wfStatusLabel(detail.run?.status) }}</a-tag></div>
                <div class="dt-kv"><span>触发</span>{{ { manual: '手动调试', api: 'API 触发', agent: '智能体对话' }[detail.run?.triggerType] || detail.run?.triggerType }}</div>
                <div class="dt-kv"><span>版本</span>{{ detail.run?.version != null ? 'v' + detail.run.version : '草稿' }}</div>
                <div class="dt-kv"><span>耗时</span>{{ detail.run?.durationMs != null ? detail.run.durationMs + ' ms' : '—' }}</div>
              </div>
              <div v-if="detail.run?.error" class="dt-block"><div class="dt-label">错误</div><pre class="dt-pre">{{ detail.run.error }}</pre></div>
              <div class="dt-block"><div class="dt-label">入参</div><pre class="dt-pre">{{ prettyJson(detail.run?.inputs) }}</pre></div>
              <div class="dt-block"><div class="dt-label">出参</div><pre class="dt-pre">{{ prettyJson(detail.run?.outputs) }}</pre></div>
              <div class="dt-block">
                <div class="dt-label">节点记录（{{ wfTraces.length }}）</div>
                <div v-for="(t, i) in wfTraces" :key="i" class="wf-trace" @click="t._open = !t._open">
                  <div class="wf-trace-head">
                    <a-tag :color="{ success: 'green', failed: 'red', waiting: 'orange' }[t.status] || 'red'">{{ t.status }}</a-tag>
                    <span class="wf-trace-name">{{ t.nodeId }}<span class="dt-meta">（{{ t.type }}）</span></span>
                    <span class="dt-meta">{{ t.elapsedMs != null ? t.elapsedMs + ' ms' : '' }}</span>
                    <span class="dt-meta" style="margin-left:auto">{{ t._open ? '收起' : '展开' }}</span>
                  </div>
                  <div v-if="t.error" class="wf-run-err">{{ t.error }}</div>
                  <pre v-if="t._open && (t.input || t.output)" class="dt-pre">{{ JSON.stringify({ input: t.input, output: t.output }, null, 2) }}</pre>
                </div>
              </div>
            </template>
          </template>
        </a-spin>
      </a-drawer>

      <!-- ==================== 标注回流 ==================== -->
      <a-modal v-model:open="labelModal" title="标注并回流评测集" ok-text="回流" cancel-text="取消"
               :confirm-loading="labelLoading" @ok="submitLabel">
        <div class="dt-block">
          <div class="dt-label">问题</div>
          <pre class="dt-pre">{{ labelRow?.question }}</pre>
        </div>
        <div class="dt-block">
          <div class="dt-label">回答摘要</div>
          <pre class="dt-pre">{{ labelRow?.answerSummary }}</pre>
        </div>
        <div class="dt-block" style="margin-bottom:12px">
          <div class="dt-label">期望命中的知识块（该轮实际命中的已预填，可增删）</div>
          <a-select v-model:value="labelKnowledgeIds" mode="tags" style="width: 100%" :open="false"
                    placeholder="知识块 ID（已按该轮命中预填）" :token-separators="[',', ' ']" />
        </div>
        <a-input v-model:value="labelNote" placeholder="备注（可选，如差评原因）" />
      </a-modal>

      <!-- ==================== 补知识块 / 入库 ==================== -->
      <a-modal v-model:open="addVisible" title="补知识块" ok-text="创建" cancel-text="取消"
               :confirm-loading="addLoading" @ok="submitAdd">
        <a-form layout="vertical">
          <a-form-item label="标题（默认取问题）">
            <a-input v-model:value="addForm.title" :maxlength="200" />
          </a-form-item>
          <a-form-item label="回答 / 知识内容（必填）">
            <a-textarea v-model:value="addForm.content" :rows="6" placeholder="把标准答案沉淀为知识块，下次同类问题即可命中" />
          </a-form-item>
          <a-form-item label="归属文档 ID（可选）">
            <a-input v-model:value="addForm.docId" placeholder="留空则作为独立知识块" />
          </a-form-item>
        </a-form>
      </a-modal>

      <!-- ==================== 保存视图 ==================== -->
      <a-modal v-model:open="viewModal" title="保存当前视图" ok-text="保存" cancel-text="取消" @ok="saveView">
        <a-input v-model:value="viewName" :maxlength="24" placeholder="如：每周一质量巡检" @press-enter="saveView" />
        <div class="modal-hint">保存时间范围、页签、执行记录筛选与采样状态；下次可从页头「视图」一键恢复。</div>
      </a-modal>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import { TooltipComponent, GridComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { themeState } from '../utils/theme'
import { isSuperadminSync } from '../utils/auth'
import {
  getAnalytics, getDashboardActions, getDashboardAlerts, getTraceOverview,
  createKnowledge, addEvalCase, getEvalLastReport, runEvalAutoCheck,
  listTraces, getChatTrace, getWorkflowTrace, listTracePool, labelTraceSample, dismissTraceSample,
  runTraceSampling, getTraceStats,
  getUsageCost, listQuotas, saveQuota, deleteQuota, listDepartments, listUsers, listTenants
} from '../api'

echarts.use([LineChart, PieChart, BarChart, TooltipComponent, GridComponent, LegendComponent, TitleComponent, CanvasRenderer])

// ==================================================================================================
// 全局：时间范围 / 页签 / 自动刷新 / 视图
// ==================================================================================================
const DASH_TABS = ['overview', 'trace', 'pool', 'cost']
const route = useRoute()
const router = useRouter()
const tab = ref(DASH_TABS.includes(route.query.tab) ? route.query.tab : 'overview')
const range = ref(30)
const compare = ref(true)
const autoRefresh = ref(true)
const alertsClosed = ref(false)
const rangeOptions = [
  { value: 7, label: '近 7 天' }, { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' }, { value: 0, label: '全部' }
]
const rangeLabel = computed(() => rangeOptions.find(o => o.value === range.value)?.label || `近 ${range.value} 天`)

// ---------- 总览卡片顺序（P2：随「保存视图」一并保存） ----------
const LAYOUT_KEY = 'wenqu.dashboard.layout.v1'
const DEFAULT_ORDER = ['kpi', 'charts', 'tops', 'checkup', 'actions']
const layoutEdit = ref(false)
const blockOrder = ref(loadLayout())
function normalizeOrder(v) {
  if (!Array.isArray(v)) return [...DEFAULT_ORDER]
  const out = v.filter(k => DEFAULT_ORDER.includes(k))
  DEFAULT_ORDER.forEach(k => { if (!out.includes(k)) out.push(k) })
  return out
}
function loadLayout() {
  try { return normalizeOrder(JSON.parse(localStorage.getItem(LAYOUT_KEY) || '[]')) } catch (e) { return [...DEFAULT_ORDER] }
}
function persistLayout() {
  try { localStorage.setItem(LAYOUT_KEY, JSON.stringify(blockOrder.value)) } catch (e) { /* 隐私模式忽略 */ }
}
const orderOf = key => blockOrder.value.indexOf(key)
function moveBlock(key, dir) {
  const i = blockOrder.value.indexOf(key)
  const j = i + dir
  if (i < 0 || j < 0 || j >= blockOrder.value.length) return
  const arr = [...blockOrder.value]
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
  blockOrder.value = arr
  persistLayout()
  nextTick(renderCharts)
}
function resetLayout() {
  blockOrder.value = [...DEFAULT_ORDER]
  persistLayout()
  nextTick(renderCharts)
}

const summary = ref({})
const actionsData = ref({ counts: {}, rows: [] })
const alerts = ref({ triggered: false, items: [] })
const overviewLoading = ref(false)
const fb = computed(() => summary.value.feedback || {})
const counts = computed(() => actionsData.value.counts || {})
const health = computed(() => summary.value.health || { score: 0, status: 'empty', parts: {} })
const healthColor = computed(() => {
  const dark = themeState.value === 'dark'
  return health.value.status === 'good' ? (dark ? '#67b978' : '#3b8f4e')
    : health.value.status === 'warn' ? (dark ? '#d9a03f' : '#c77c14')
    : health.value.status === 'bad' ? (dark ? '#e07a5a' : '#d4552e')
    : (dark ? '#5f6570' : '#c9ced6')
})
const healthPill = computed(() => ({ good: 'ok', warn: 'warn', bad: 'err', empty: 'muted' }[health.value.status] || 'muted'))
const healthText = computed(() => ({ good: '良好', warn: '需关注', bad: '风险', empty: '暂无数据' }[health.value.status] || '—'))

const healthDelta = computed(() => {
  const p = summary.value.prev
  if (!p) return null
  const parts = health.value.parts || {}
  if (parts.hitRate == null) return null
  // 体检是「最近一次」的全局状态，不属于某个时间窗：两期用同一个体检分，delta 才只反映质量变化。
  // 两期都按同一公式现算（后端给的本期 score 也来自这套权重，这里只为补齐上期值）。
  const cur = scoreOf(parts.hitRate, parts.citationRate, parts.likeRate, checkupPart())
  const prev = scoreOf(100 - Number(p.noHitRate || 0), p.citationRate, p.feedback?.likeRate, checkupPart())
  return Math.round((cur - prev) * 10) / 10
})

const checkupPart = () => ({ ok: 100, decline: 45, error: 50 }[health.value.parts?.checkup] ?? 80)

function scoreOf(hitRate, citationRate, likeRate, part = 80) {
  const like = likeRate == null ? 88 : Number(likeRate)
  return Math.round((Number(hitRate) * 0.4 + Number(citationRate) * 0.2 + like * 0.3 + part * 0.1) * 10) / 10
}

// 指标卡：大数字 + 环比 + 30 天 sparkline + 上期值
const metrics = computed(() => {
  const s = summary.value
  const p = s.prev || null
  const trend = s.trend || []
  const rateSeries = (key, part) => trend.map(t => {
    if (key === 'like') return t.likeRate == null ? null : Number(t.likeRate)
    const total = Number(t.total) || 0
    return total ? Math.round(Number(t[part] || 0) / total * 1000) / 10 : null
  })
  return [
    {
      key: 'total', label: '问答总数', value: fmtInt(s.total), unit: '',
      deltaText: deltaText(p?.total, s.total, false, true),
      deltaClass: deltaClass(p?.total, s.total, false, true),
      hint: p ? '上期 ' + fmtInt(p.total) : '全部时间',
      color: accentColor(), ...sparkOf(trend.map(t => Number(t.total) || 0))
    },
    {
      key: 'nohit', label: '无命中率', value: Number(s.noHitRate || 0).toFixed(1), unit: '%',
      deltaText: deltaText(p?.noHitRate, s.noHitRate, true, false),
      deltaClass: deltaClass(p?.noHitRate, s.noHitRate, true, false),
      hint: p ? '上期 ' + Number(p.noHitRate || 0).toFixed(1) + '%' : '越低越好',
      color: '#3b8f4e', ...sparkOf(rateSeries('nohit', 'noHit'))
    },
    {
      key: 'cite', label: '有引用标注', value: Number(s.citationRate || 0).toFixed(1), unit: '%',
      deltaText: deltaText(p?.citationRate, s.citationRate, true, true),
      deltaClass: deltaClass(p?.citationRate, s.citationRate, true, true),
      hint: p ? '上期 ' + Number(p.citationRate || 0).toFixed(1) + '%' : '越高越好',
      color: accentColor(), ...sparkOf(rateSeries('cite', 'cited'))
    },
    {
      key: 'like', label: '反馈满意率', value: fb.value.likeRate == null ? '—' : Number(fb.value.likeRate).toFixed(1),
      unit: fb.value.likeRate == null ? '' : '%',
      deltaText: deltaText(p?.feedback?.likeRate, fb.value.likeRate, true, true),
      deltaClass: deltaClass(p?.feedback?.likeRate, fb.value.likeRate, true, true),
      hint: `👍 ${fb.value.likes || 0} · 👎 ${fb.value.dislikes || 0}`,
      color: '#3b8f4e', ...sparkOf(rateSeries('like', 'likeRate'))
    }
  ]
})

function accentColor() { return cssVar('--app-accent') || '#2e6be6' }
function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim() }

/** 环比文案：pt=显示百分点差；goodDown=下降是好事（无命中率） */
function deltaText(prev, cur, pt, higherBetter) {
  if (prev == null || cur == null || !compare.value) return ''
  const a = Number(prev), b = Number(cur)
  if (!Number.isFinite(a) || !Number.isFinite(b)) return ''
  if (pt) {
    const d = Math.round((b - a) * 10) / 10
    return (d > 0 ? '▲ +' : d < 0 ? '▼ ' : '— ') + Math.abs(d).toFixed(1) + 'pt'
  }
  if (a === 0) return b === 0 ? '—' : '新增'
  const d = Math.round((b - a) / a * 1000) / 10
  return (d > 0 ? '▲ ' : d < 0 ? '▼ ' : '— ') + Math.abs(d).toFixed(1) + '%'
}
function deltaClass(prev, cur, pt, higherBetter) {
  if (prev == null || cur == null || !compare.value) return 'flat'
  const d = Number(cur) - Number(prev)
  if (Math.abs(d) < (pt ? 0.05 : 0.05)) return 'flat'
  const good = higherBetter ? d > 0 : d < 0
  return good ? 'up' : 'down'
}
/** 迷你趋势线：返回 line / area 两条 path */
function sparkOf(values) {
  const vals = (values && values.length ? values : [0, 0]).map(v => v == null ? 0 : Number(v))
  const max = Math.max(...vals), min = Math.min(...vals)
  const span = max - min
  const pts = vals.map((v, i) => [
    2 + i * 96 / Math.max(1, vals.length - 1),
    // 全平序列居中画一条线，否则会贴着底边看起来像「没有数据」
    span === 0 ? 16 : 2 + (1 - (v - min) / span) * 26
  ])
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ')
  const area = `${line} L98,30 L2,30 Z`
  return { line, area }
}

// 命中构成 / 满意度
const noHitCount = computed(() => Math.round(Number(summary.value.total || 0) * Number(summary.value.noHitRate || 0) / 100))
const citedCount = computed(() => Math.max(0, Number(summary.value.total || 0) - noHitCount.value))
const citationPct = computed(() => Number(summary.value.citationRate || 0).toFixed(1))
const topMax = computed(() => Math.max(1, ...(summary.value.topQuestions || []).map(q => Number(q.count) || 0)))
const noHitMax = computed(() => Math.max(1, ...(summary.value.noHitQuestions || []).map(q => Number(q.count) || 0)))
const barWidth = (v, max) => Math.max(3, Math.round((Number(v) || 0) / max * 100)) + '%'

// 待办队列
const aqFilter = ref('all')
const aqChips = computed(() => [
  { key: 'all', label: '全部', count: counts.value.total || 0 },
  { key: 'bad', label: '差评', count: counts.value.bad || 0 },
  { key: 'gap', label: '知识缺口', count: counts.value.gap || 0 },
  { key: 'pool', label: '待标注', count: counts.value.pool || 0 }
])
const filteredActions = computed(() => {
  const rows = actionsData.value.rows || []
  return aqFilter.value === 'all' ? rows : rows.filter(r => r.type === aqFilter.value)
})
const closedPct = computed(() => {
  const closed = Number(counts.value.closedThisWeek || 0)
  const total = closed + Number(counts.value.total || 0)
  return total ? Math.round(closed / total * 100) : 0
})
const srcLabel = t => ({ bad: '差评', gap: '缺口', pool: '待标注' }[t] || t)
const srcClass = s => ({ bad: 'bad', nohit: 'gap', random: 'pool' }[s] || 'pool')
const srcText = s => ({ bad: '差评', nohit: '无引用', random: '随机' }[s] || s)

// 体检
const checkup = ref(null)
const checkupOpen = ref(false)
const checkLoading = ref(false)
const checkupLabel = computed(() => ({
  ok: '正常', decline: '下滑', empty: '待评估集', error: '执行异常'
}[checkup.value?.status] || '未体检'))
const checkupDot = computed(() => ({
  ok: 'ok', decline: 'err', empty: 'warn', error: 'err'
}[checkup.value?.status] || 'muted'))
const checkMetrics = computed(() => {
  const m = checkup.value?.metrics
  const d = checkup.value?.deltaPct || {}
  if (!m || typeof m !== 'object') return []
  return Object.entries(m).map(([name, v]) => {
    const num = Number(v)
    const isMrr = name === 'MRR'
    return {
      name: isMrr ? name : name + (name.startsWith('recall') ? `（前${name.replace('recall@', '')}名命中率）` : '（命中率）'),
      short: isMrr ? 'MRR' : name.startsWith('recall') ? `recall@${name.replace('recall@', '')}` : name,
      // MRR 是 0~1 的排序质量分，不是比率：乘 100 会读成 71%，与本意不符
      value: isMrr ? num.toFixed(2) : (num * 100).toFixed(1) + '%',
      delta: d[name] === undefined ? null : Number(d[name])
    }
  })
})
const checkCols = [
  { title: '指标', dataIndex: 'name', key: 'name', width: 220 },
  { title: '本期', dataIndex: 'value', key: 'value', width: 100 },
  { title: '较上期', key: 'delta', width: 110, customRender: ({ record }) => {
    if (record.delta === null || record.delta === undefined) return '—'
    const v = Number(record.delta)
    const color = v < -0.01 ? 'var(--app-danger)' : (v > 0.01 ? 'var(--app-ok)' : 'var(--app-text3)')
    return h('span', { style: { color } }, (v > 0 ? '▲ +' : v < 0 ? '▼ ' : '') + v.toFixed(1) + '%')
  } },
  { title: '评价', key: 'note', width: 90, customRender: ({ record }) => {
    if (record.delta === null || record.delta === undefined) return '—'
    const v = Number(record.delta)
    return h('span', { style: { color: v < 0 ? 'var(--app-danger)' : 'var(--app-ok)', fontSize: 12 } }, v < 0 ? '下滑' : (v > 0 ? '提升' : '持平'))
  } }
]
const paramsDriftText = computed(() => {
  const d = checkup.value?.paramsDrift
  if (!d || !d.length) return '线上检索参数已改动，本期数字与新参数不可比；点「立即体检」按当前参数重测一次'
  return '已改动：' + d.join('；') + '。点「立即体检」按当前参数重测一次'
})
const doAutoCheck = async () => {
  checkLoading.value = true
  try {
    const r = await runEvalAutoCheck()
    if (r.success) {
      checkup.value = r.data || null
      if (checkup.value?.status === 'decline') message.warning('体检完成：检测到检索质量下滑')
      else message.success('体检完成')
      loadOverview()
    } else message.error(r.msg || '体检失败')
  } catch (e) { message.error(e.message || '体检失败') }
  finally { checkLoading.value = false }
}

// ==================================================================================================
// 数据加载：总览
// ==================================================================================================
const loadOverview = async (silent = false) => {
  if (!silent) overviewLoading.value = true
  try {
    const [a, b, c] = await Promise.all([
      getAnalytics(range.value),
      getDashboardActions(range.value),
      getDashboardAlerts()
    ])
    if (a && a.success) summary.value = a.data || {}
    else if (a) message.error(a.msg || '看板加载失败')
    if (b && b.success) actionsData.value = b.data || { counts: {}, rows: [] }
    // 告警每次都随总览刷新：看板可能长时间开着，阈值越界不能等到下次整页刷新才出现
    if (c && c.success) alerts.value = c.data || { triggered: false, items: [] }
    await nextTick()
    renderCharts()
  } catch (e) {
    message.error('看板加载失败: ' + (e.message || ''))
  } finally {
    if (!silent) overviewLoading.value = false
  }
}

const onRangeChange = () => {
  loadOverview()
}

const goAlert = () => {
  tab.value = 'overview'
  aqFilter.value = 'all'
  nextTick(() => document.querySelector('.aq-chips')?.scrollIntoView({ behavior: 'smooth', block: 'center' }))
}

// ==================================================================================================
// 执行记录
// ==================================================================================================
const traceKind = ref('chat')
const traceDays = ref(7)
const traceHasCitation = ref(null)
const traceRating = ref(null)
const traceKeyword = ref('')
const drillKeyword = ref('')
const traceRows = ref([])
const traceLoading = ref(false)
const tracePage = ref(1)
const traceTotal = ref(0)
const TRACE_SIZE = 20
const traceOverview = ref({ trend: [] })

const traceDaysLabel = computed(() => ({
  1: '近 24 小时', 7: '近 7 天', 30: '近 30 天', 90: '近 90 天', 0: '全部'
}[traceDays.value] || `近 ${traceDays.value} 天`))

const traceCards = computed(() => {
  const o = traceOverview.value || {}
  return [
    { key: 'total', label: '总执行', value: fmtInt(o.total), unit: '', hint: `对话 ${fmtInt(o.chat)} · 工作流 ${fmtInt(o.workflow)}` },
    { key: 'success', label: '工作流成功率', value: Number(o.successRate ?? 100).toFixed(1), unit: '%', hint: '成功 / 全部运行' },
    { key: 'p95', label: 'P95 耗时', value: o.p95Ms ? (o.p95Ms / 1000).toFixed(1) : '—', unit: o.p95Ms ? 's' : '', hint: '机器实际干活时间' },
    { key: 'nocite', label: '无引用占比', value: Number(o.noCitationRate ?? 0).toFixed(1), unit: '%', hint: `无命中率 ${Number(o.noHitRate ?? 0).toFixed(1)}%` }
  ]
})

const traceCols = computed(() => traceKind.value === 'workflow' ? [
  { title: '时间', dataIndex: 'time', key: 'time', width: 150, customRender: ({ text }) => fmtTime(text) },
  { title: '工作流', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '入参', dataIndex: 'sub', key: 'sub', ellipsis: true },
  { title: '状态', key: 'statusCol', width: 170 },
  { title: '耗时', dataIndex: 'elapsedMs', key: 'elapsedMs', width: 140 },
  { title: '操作', key: 'action', width: 70 }
] : [
  { title: '时间', dataIndex: 'time', key: 'time', width: 150, customRender: ({ text }) => fmtTime(text) },
  { title: '智能体', dataIndex: 'agentName', key: 'agentName', width: 110, ellipsis: true, customRender: ({ text }) => text || '全局' },
  { title: '问题', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '状态', key: 'statusCol', width: 170 },
  { title: '命中', dataIndex: 'hitDocCount', key: 'hitDocCount', width: 70 },
  { title: '耗时', dataIndex: 'elapsedMs', key: 'elapsedMs', width: 140 },
  { title: '操作', key: 'action', width: 70 }
])
const tracePagination = computed(() => ({
  current: tracePage.value, total: traceTotal.value, pageSize: TRACE_SIZE,
  size: 'small', showSizeChanger: false, showTotal: t => `共 ${t} 条`
}))
const maxElapsed = computed(() => Math.max(1, ...traceRows.value.map(r => Number(r.elapsedMs) || 0)))
const durWidth = ms => {
  if (ms == null) return '0%'
  return Math.max(4, Math.round(Number(ms) / maxElapsed.value * 100)) + '%'
}

const loadTraces = async (p) => {
  if (typeof p === 'number') tracePage.value = p
  else tracePage.value = 1
  traceLoading.value = true
  try {
    const r = await listTraces({
      kind: traceKind.value, page: tracePage.value, size: TRACE_SIZE,
      keyword: traceKeyword.value || undefined,
      hasCitation: traceKind.value === 'chat' ? traceHasCitation.value : undefined,
      rating: traceKind.value === 'chat' ? traceRating.value : undefined,
      days: traceDays.value
    })
    if (r.success) {
      traceRows.value = r.data?.rows || []
      traceTotal.value = r.data?.total || 0
    } else message.error(r.msg || '加载失败')
  } catch (e) { message.error(e.message || '加载失败（Trace 为管理员功能）') }
  finally { traceLoading.value = false }
}
const onTraceTableChange = pg => loadTraces(pg.current)
const onTraceDaysChange = () => { loadTraces(); loadTraceOverview() }

const loadTraceOverview = async () => {
  try {
    // 「全部」没有固定天数：趋势与指标统一取近 90 天（列表本身仍按 days=0 不限）
    const r = await getTraceOverview(traceDays.value || 90)
    if (r.success) {
      traceOverview.value = r.data || { trend: [] }
      await nextTick()
      renderTraceChart()
    }
  } catch (e) { /* 指标条失败不阻断列表 */ }
}

const drill = (q) => {
  drillKeyword.value = q
  traceKeyword.value = q
  traceKind.value = 'chat'
  traceHasCitation.value = null
  traceRating.value = null
  traceDays.value = range.value || 0
  tab.value = 'trace'
  router.replace({ query: { tab: 'trace', q } })
  loadTraceOverview()
  loadTraces()
}
const clearDrill = () => {
  drillKeyword.value = ''
  traceKeyword.value = ''
  router.replace({ query: { tab: 'trace' } })
  loadTraces()
}
const showBadTraces = () => {
  tab.value = 'trace'
  traceKind.value = 'chat'
  traceRating.value = 0
  traceHasCitation.value = null
  traceKeyword.value = ''
  drillKeyword.value = ''
  traceDays.value = range.value || 0
  loadTraceOverview()
  loadTraces()
}

// 详情抽屉
const detailDrawer = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailTitle = computed(() => {
  if (!detail.value) return '执行详情'
  return detail.value.kind === 'workflow' ? `工作流 · ${detail.value.workflowName || ''}` : '对话执行详情'
})
const wfTraces = ref([])
const openTraceDetail = async record => {
  detailDrawer.value = true
  detailLoading.value = true
  detail.value = null
  wfTraces.value = []
  try {
    const r = record.kind === 'workflow' ? await getWorkflowTrace(record.id) : await getChatTrace(record.id)
    detail.value = r.data || null
    if (detail.value?.kind === 'workflow' && detail.value.run?.nodeTraces) {
      try { wfTraces.value = (JSON.parse(detail.value.run.nodeTraces) || []).map(t => ({ ...t, _open: false })) } catch (e) { wfTraces.value = [] }
    }
  } catch (e) { message.error(e.message || '详情加载失败') }
  finally { detailLoading.value = false }
}
const wfStatusLabel = s => ({ running: '运行中', success: '成功', failed: '失败', timeout: '超时', waiting_approval: '待审批' }[s] || s)
const wfStatusColor = s => ({ running: 'processing', success: 'green', failed: 'red', timeout: 'orange', waiting_approval: 'orange' }[s] || 'default')
const prettyJson = s => {
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch (e) { return s || '（无）' }
}

// 工具调用：一条一行摘要，点开看入参/输出；单个字段超长先折叠（产物正文可达几万字），按需展开。
// 后端已把落库/下发的超长字段折成 2000 字预览，这里再兜一层：老记录里仍有整份原文。
const ARG_FIELD_PREVIEW = 400
const toolStatusLabel = s => ({ start: '进行中', preparing: '生成中', done: '完成', error: '失败', denied: '已拒绝', waiting: '待批准' }[s] || s || '完成')
const toolStatusColor = s => ({ start: 'processing', preparing: 'processing', done: 'green', error: 'red', denied: 'orange', waiting: 'orange' }[s] || 'default')
const toolArgFields = t => {
  if (t._fields) return t._fields
  const fields = []
  let obj
  try { obj = JSON.parse(t.args) } catch (e) { obj = undefined }
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    for (const [key, v] of Object.entries(obj)) {
      const text = typeof v === 'string' ? v : JSON.stringify(v, null, 2)
      if (text == null || text === '') continue
      fields.push({ key, text, total: text.length, truncated: text.length > ARG_FIELD_PREVIEW })
    }
  } else {
    const text = String(t.args)
    fields.push({ key: '原始入参', text, total: text.length, truncated: text.length > ARG_FIELD_PREVIEW })
  }
  t._fields = fields
  return fields
}
const toolArgOpen = (t, key) => !!(t._argOpen && t._argOpen[key])
const toggleArg = (t, key) => { t._argOpen = { ...(t._argOpen || {}), [key]: !toolArgOpen(t, key) } }
const toolArgText = (t, f) => f.truncated && !toolArgOpen(t, f.key)
  ? f.text.slice(0, ARG_FIELD_PREVIEW) + '…（已折叠，点下方展开）'
  : f.text

// ==================================================================================================
// 采样标注
// ==================================================================================================
const poolStats = ref({})
const poolStatus = ref('pending')
const poolRows = ref([])
const poolLoading = ref(false)
const poolPage = ref(1)
const poolTotal = ref(0)
const POOL_SIZE = 10
const sampling = ref(false)
const funnel = computed(() => {
  const pending = Number(poolStats.value.pending || 0)
  const labeled = Number(poolStats.value.labeled || 0)
  const dismissed = Number(poolStats.value.dismissed || 0)
  const inCheckup = Number(poolStats.value.inCheckup || 0)
  const total = pending + labeled + dismissed
  const pct = v => total ? Math.round(v / total * 1000) / 10 : 0
  return [
    { label: '样本入池', value: total, pct: total ? 100 : 0 },
    { label: '已处置', value: labeled + dismissed, pct: pct(labeled + dismissed) },
    { label: '已回流评测集', value: labeled, pct: pct(labeled) },
    { label: '已进入体检对比', value: inCheckup, pct: pct(inCheckup) }
  ]
})

const loadPoolStats = async () => {
  try {
    const r = await getTraceStats()
    if (r.success) poolStats.value = r.data || {}
  } catch (e) { /* 静默 */ }
}
const loadPool = async (p) => {
  if (typeof p === 'number') poolPage.value = p
  else poolPage.value = 1
  poolLoading.value = true
  try {
    const r = await listTracePool({ status: poolStatus.value, page: poolPage.value, size: POOL_SIZE })
    if (r.success) {
      poolRows.value = r.data?.rows || []
      poolTotal.value = r.data?.total || 0
    } else message.error(r.msg || '加载失败')
  } catch (e) { message.error(e.message || '加载失败（采样池为管理员功能）') }
  finally { poolLoading.value = false }
}
const doSample = async () => {
  sampling.value = true
  try {
    const r = await runTraceSampling()
    if (r.success) {
      const d = r.data || {}
      message.success(`采样完成：差评 ${d.bad || 0} / 无引用 ${d.nohit || 0} / 随机 ${d.random || 0}`)
      loadPool()
      loadPoolStats()
      loadOverview()
    } else message.error(r.msg || '采样失败')
  } catch (e) { message.error(e.message || '采样失败') }
  finally { sampling.value = false }
}

const labelModal = ref(false)
const labelLoading = ref(false)
const labelRow = ref(null)
const labelKnowledgeIds = ref([])
const labelNote = ref('')
const openLabel = async record => {
  labelRow.value = record
  labelKnowledgeIds.value = []
  labelNote.value = record.note || ''
  labelModal.value = true
  if (record.qaLogId || record.id) {
    try {
      const r = await getChatTrace(record.qaLogId)
      const src = r.data?.message?.sources || []
      const ids = src.map(s => s.knowledgeId).filter(Boolean)
      if (ids.length) labelKnowledgeIds.value = [...new Set(ids)]
    } catch (e) { /* 预填失败不阻断 */ }
  }
}
const submitLabel = async () => {
  if (!labelKnowledgeIds.value.length) { message.warning('请至少填 1 个期望命中的知识块'); return }
  labelLoading.value = true
  try {
    const r = await labelTraceSample(labelRow.value.id, labelKnowledgeIds.value, labelNote.value)
    if (r.success) {
      const ev = r.data?.eval || {}
      if (ev.added) message.success('已标注并回流评测集')
      else message.info(ev.reason || '评测集已存在相同问题，样本已标记为已回流')
      labelModal.value = false
      loadPool()
      loadPoolStats()
      loadOverview()
    } else message.error(r.msg || '回流失败')
  } catch (e) { message.error(e.message || '回流失败') }
  finally { labelLoading.value = false }
}
const doDismiss = record => {
  dismissTraceSample(record.id).then(r => {
    if (r.success) {
      message.success('已忽略')
      loadPool(); loadPoolStats(); loadOverview()
    } else message.error(r.msg || '操作失败')
  }).catch(e => message.error(e.message || '操作失败'))
}

// ==================================================================================================
// 费用与配额
// ==================================================================================================
const costRange = ref(30)
const costLoading = ref(false)
const cost = ref({})
const quotas = ref([])
const quotaLoading = ref(false)
const quotaModal = ref(false)
const quotaForm = ref({ id: null, scope: 'user', scopeId: undefined, period: 'month',
  tokenLimit: 0, costLimit: null, enforce: true, remark: '', tenantId: undefined })
const quotaScopeOptions = [{ value: 'tenant', label: '本租户' }, { value: 'department', label: '按部门' }, { value: 'user', label: '按成员' }]
const deptOptions = ref([])
const memberOptions = ref([])
const tenantOptions = ref([])
const superadmin = isSuperadminSync()
const costRangeLabel = computed(() => costRange.value === 7 ? '近 7 日' : '近 30 日')
const costPager = { pageSize: 10, size: 'small', showSizeChanger: false, hideOnSinglePage: true, showTotal: t => `共 ${t} 条` }

const costUserCols = [
  { title: '用户', dataIndex: 'name', key: 'name' },
  { title: '输入 tokens', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tokens', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
const costDeptCols = [
  { title: '部门', dataIndex: 'name', key: 'name' },
  { title: '输入 tokens', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tokens', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
const costTenantCols = [
  { title: '租户', dataIndex: 'name', key: 'name' },
  { title: '标识', dataIndex: 'tenantId', key: 'tenantId', width: 110 },
  { title: '成员', dataIndex: 'members', key: 'members', width: 72, align: 'right' },
  { title: '输入 tokens', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tokens', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]
const costModelCols = [
  { title: '模型', dataIndex: 'label', key: 'label' },
  { title: '输入 tokens', dataIndex: 'promptTokens', key: 'promptTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '输出 tokens', dataIndex: 'completionTokens', key: 'completionTokens', width: 96, align: 'right', customRender: ({ text }) => fmtTokensCompact(text) },
  { title: '费用', key: 'cost', width: 110, align: 'right' }
]

const modelRows = computed(() => (cost.value.byModel || []))
const modelTotalCost = computed(() => modelRows.value.reduce((a, m) => a + (Number(m.cost) || 0), 0))
const modelTotalTokens = computed(() => modelRows.value.reduce((a, m) => a + (Number(m.tokens) || 0), 0))
/** 全部模型都未登记单价时，环形图退化为 token 占比（并明确标注），不画一个空饼 */
const modelUsesCost = computed(() => modelRows.value.some(m => m.cost != null))
const modelPct = m => {
  if (modelUsesCost.value) {
    if (m.cost == null) return '—'
    return modelTotalCost.value ? (Number(m.cost) / modelTotalCost.value * 100).toFixed(0) : '0'
  }
  return modelTotalTokens.value ? (Number(m.tokens) / modelTotalTokens.value * 100).toFixed(0) : '0'
}
const modelColor = i => {
  const dark = themeState.value === 'dark'
  const pal = dark
    ? ['#6b9df0', '#67b978', '#e09a5f', '#a98ef0', '#5fc4bb']
    : ['#2e6be6', '#3b8f4e', '#e0a44a', '#8e6fe0', '#2aa198']
  return pal[i % pal.length]
}
const userBarMax = computed(() => Math.max(1, ...(cost.value.byUser || []).map(u => Number(u.cost) || 0)))
const userBarWidth = u => {
  if (u.cost == null) return '2%'
  return Math.max(2, Math.round(Number(u.cost) / userBarMax.value * 100)) + '%'
}

const budgetRows = computed(() => {
  const rows = (quotas.value || []).map(q => {
    const tokenPct = Number(q.tokenPercent || 0)
    const costPct = Number(q.costPercent || 0)
    const pct = Math.max(tokenPct, costPct)
    const tone = q.breach ? (Number(q.enforce) === 1 ? 'danger' : 'warn') : pct >= 80 ? 'warn' : 'ok'
    const scope = { tenant: '本租户', department: '部门', user: '成员' }[q.scope] || q.scope
    return {
      id: q.id,
      name: q.scopeName || q.scopeId || scope,
      sub: `${scope} · ${q.period === 'day' ? '按日' : '按月'}`,
      pct,
      tone,
      used: Number(q.tokenLimit) > 0 ? fmtTokensCompact(q.usedTokens) : fmtCost(q.usedCost),
      limit: Number(q.tokenLimit) > 0 ? fmtTokensCompact(q.tokenLimit) : fmtCost(q.costLimit),
      state: q.breach ? (Number(q.enforce) === 1 ? '已拦下' : '只告警') : pct >= 80 ? '接近上限' : ''
    }
  })
  return rows.sort((a, b) => b.pct - a.pct)
})

const unpricedAll = ref(false)
const UNPRICED_SHOWN = 8
const unpricedList = computed(() => cost.value.unpricedModels || [])
const shownUnpriced = computed(() =>
  unpricedAll.value ? unpricedList.value : unpricedList.value.slice(0, UNPRICED_SHOWN))

const quotaCols = computed(() => {
  const base = [
    { title: '作用域', dataIndex: 'scope', key: 'scope', width: 92, customRender: ({ text }) => ({ tenant: '本租户', department: '部门', user: '成员' }[text] || text) }
  ]
  const ids = new Set((quotas.value || []).map(q => q.tenantId || 'default'))
  if (ids.size > 1) {
    base.push({ title: '租户', key: 'tenant', width: 110,
      customRender: ({ record }) => (tenantOptions.value.find(t => t.value === (record.tenantId || 'default')) || {}).label || record.tenantId || 'default' })
  }
  return base.concat([
    { title: '对象', dataIndex: 'scopeName', key: 'scopeName', ellipsis: true },
    { title: '周期', dataIndex: 'period', key: 'period', width: 68, customRender: ({ text }) => (text === 'day' ? '按日' : '按月') },
    { title: 'token 上限', key: 'tokenLimit', width: 108, align: 'right' },
    { title: '费用上限', key: 'costLimit', width: 108, align: 'right' },
    { title: '已用 / 进度', key: 'used', width: 150 },
    { title: '状态', key: 'state', width: 132 },
    { title: '操作', key: 'op', width: 110 }
  ])
})

const loadCost = async () => {
  costLoading.value = true
  try {
    const r = await getUsageCost(costRange.value)
    if (r.success) {
      cost.value = r.data || {}
      await nextTick()
      renderCostChart()
      renderModelDonut()
    } else message.error(r.msg || '费用报表加载失败')
  } catch (e) { message.error(e.message || '费用报表加载失败') }
  finally { costLoading.value = false }
}
const loadQuotas = async () => {
  quotaLoading.value = true
  try {
    const r = await listQuotas()
    if (r.success) quotas.value = r.data || []
    else message.error(r.msg || '配额清单加载失败')
    if (superadmin && !tenantOptions.value.length) {
      const rt = await listTenants()
      if (rt.success) tenantOptions.value = (rt.data || []).map(t => ({ value: t.id, label: t.name || t.id }))
    }
  } catch (e) { message.error(e.message || '配额清单加载失败') } finally { quotaLoading.value = false }
}

const openQuota = async record => {
  quotaForm.value = record ? {
    id: record.id, scope: record.scope, scopeId: record.scopeId || undefined, period: record.period,
    tokenLimit: Number(record.tokenLimit || 0), costLimit: record.costLimit == null ? null : Number(record.costLimit),
    enforce: Number(record.enforce) !== 0, remark: record.remark || '',
    tenantId: superadmin ? (record.tenantId || 'default') : undefined
  } : { id: null, scope: 'user', scopeId: undefined, period: 'month', tokenLimit: 0, costLimit: null, enforce: true, remark: '',
    tenantId: superadmin ? 'default' : undefined }
  if (!deptOptions.value.length || !memberOptions.value.length) {
    try {
      const [d, m] = await Promise.all([listDepartments(), listUsers()])
      if (d.success) deptOptions.value = (d.data || []).map(x => ({ value: x.id, label: x.name }))
      if (m.success) memberOptions.value = (m.data || []).map(x => ({ value: x.uid, label: (x.username || x.uid) + '（' + x.uid + '）' }))
    } catch (e) { message.error('部门或成员名单加载失败：' + e.message) }
  }
  quotaModal.value = true
}
const onQuotaScopeChange = () => { quotaForm.value.scopeId = undefined }
const submitQuota = async () => {
  const f = quotaForm.value
  if (f.scope !== 'tenant' && !f.scopeId) { message.warning('要先选部门或成员'); return }
  if (!(Number(f.tokenLimit) > 0) && !(f.costLimit != null && Number(f.costLimit) > 0)) {
    message.warning('至少填一个上限，否则这条规则不限任何东西'); return
  }
  try {
    const r = await saveQuota({ id: f.id, scope: f.scope, scopeId: f.scopeId, period: f.period,
      tokenLimit: Number(f.tokenLimit || 0), costLimit: f.costLimit == null || f.costLimit === '' ? null : Number(f.costLimit),
      enforce: f.enforce ? 1 : 0, remark: f.remark,
      tenantId: superadmin ? (f.tenantId || 'default') : undefined })
    if (!r.success) { message.error(r.msg || '保存失败'); return }
    message.success('已保存')
    quotaModal.value = false
    loadQuotas()
  } catch (e) { message.error(e.message || '保存失败') }
}
const removeQuota = async record => {
  const label = (record.scopeName || record.scope) + ' · ' + (record.period === 'day' ? '按日' : '按月')
  if (!window.confirm('删除这条配额「' + label + '」？删除即解除该档约束。')) return
  try {
    const r = await deleteQuota(record.id)
    if (!r.success) { message.error(r.msg || '删除失败'); return }
    message.success('已删除')
    loadQuotas()
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================================================================================================
// 待办动作：加入评估集 / 补知识块 / 入库
// ==================================================================================================
const evalAdding = ref('')
const addToEval = async record => {
  evalAdding.value = record.messageId
  try {
    const r = await addEvalCase(record.question, record.knowledgeIds || [])
    if (r.success) {
      if (r.data?.added) message.success('已加入评估集')
      else message.info(r.data?.reason || '评估集已存在相同问题')
      loadOverview()
    } else message.error(r.msg || '加入失败')
  } catch (e) { message.error(e.message || '加入失败') }
  finally { evalAdding.value = '' }
}

const addVisible = ref(false)
const addLoading = ref(false)
const addForm = ref({ title: '', content: '', docId: '' })
const openAdd = record => {
  addForm.value = {
    title: record.question || '',
    content: record.answer || '',
    docId: record.docId || ''
  }
  addVisible.value = true
}
const submitAdd = async () => {
  const f = addForm.value
  if (!f.content.trim()) { message.warning('请填写回答内容'); return }
  addLoading.value = true
  try {
    const r = await createKnowledge(f.title, f.content.trim(), f.docId.trim() || null)
    if (r.success) {
      message.success('知识块已创建')
      addVisible.value = false
      loadOverview()
    } else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
  finally { addLoading.value = false }
}

// ==================================================================================================
// P2：保存视图（localStorage，按浏览器保存）
// ==================================================================================================
const VIEW_KEY = 'wenqu.dashboard.views.v1'
const savedViews = ref(loadViews())
const viewModal = ref(false)
const viewName = ref('')
function loadViews() {
  try { return JSON.parse(localStorage.getItem(VIEW_KEY) || '[]') } catch (e) { return [] }
}
function persistViews() {
  try { localStorage.setItem(VIEW_KEY, JSON.stringify(savedViews.value)) } catch (e) { /* 隐私模式忽略 */ }
}
function openSaveView() {
  viewName.value = ''
  viewModal.value = true
}
function saveView() {
  const name = viewName.value.trim()
  if (!name) { message.warning('给视图起个名字'); return }
  savedViews.value.push({
    id: Date.now() + '',
    name,
    state: {
      range: range.value, tab: tab.value, layout: [...blockOrder.value],
      traceKind: traceKind.value, traceDays: traceDays.value,
      traceHasCitation: traceHasCitation.value, traceRating: traceRating.value,
      traceKeyword: traceKeyword.value, poolStatus: poolStatus.value
    }
  })
  persistViews()
  viewModal.value = false
  message.success('视图已保存')
}
function applyView(v) {
  const s = v.state || {}
  if (s.layout) {
    blockOrder.value = normalizeOrder(s.layout)
    persistLayout()
    nextTick(renderCharts)
  }
  if (s.range !== undefined) range.value = s.range
  if (s.tab) tab.value = s.tab
  if (s.traceKind) traceKind.value = s.traceKind
  if (s.traceDays !== undefined) traceDays.value = s.traceDays
  traceHasCitation.value = s.traceHasCitation ?? null
  traceRating.value = s.traceRating ?? null
  traceKeyword.value = s.traceKeyword || ''
  if (s.poolStatus) poolStatus.value = s.poolStatus
  loadOverview()
  if (tab.value === 'trace') { loadTraceOverview(); loadTraces() }
  if (tab.value === 'pool') { loadPoolStats(); loadPool() }
  if (tab.value === 'cost') { loadCost(); loadQuotas() }
  message.success('已切换到视图「' + v.name + '」')
}
function removeView(id) {
  savedViews.value = savedViews.value.filter(v => v.id !== id)
  persistViews()
}

// ==================================================================================================
// 链接分享：把当前页签 / 时间范围 / 筛选态带进 query，打开链接即恢复同一视图
// ==================================================================================================
const copyLink = async () => {
  const q = new URLSearchParams()
  q.set('tab', tab.value)
  q.set('range', String(range.value))
  if (drillKeyword.value) q.set('q', drillKeyword.value)
  if (tab.value === 'trace') {
    q.set('traceKind', traceKind.value)
    q.set('traceDays', String(traceDays.value))
    if (traceHasCitation.value != null) q.set('traceHasCitation', String(traceHasCitation.value))
    if (traceRating.value != null) q.set('traceRating', String(traceRating.value))
    if (traceKeyword.value) q.set('traceKeyword', traceKeyword.value)
  }
  if (tab.value === 'pool') q.set('poolStatus', poolStatus.value)
  if (tab.value === 'cost') q.set('costRange', String(costRange.value))
  const url = `${location.origin}${location.pathname}?${q.toString()}`
  try {
    await navigator.clipboard.writeText(url)
    message.success('链接已复制（含当前筛选）')
  } catch (e) {
    message.info(url)
  }
}

function applyQueryState() {
  const q = route.query
  const r = Number(q.range)
  if ([0, 7, 30, 90].includes(r)) range.value = r
  if (q.traceKind === 'workflow' || q.traceKind === 'chat') traceKind.value = q.traceKind
  const td = Number(q.traceDays)
  if ([0, 1, 7, 30, 90].includes(td)) traceDays.value = td
  if (q.traceHasCitation !== undefined && q.traceHasCitation !== '') traceHasCitation.value = Number(q.traceHasCitation)
  if (q.traceRating !== undefined && q.traceRating !== '') traceRating.value = Number(q.traceRating)
  if (q.traceKeyword) traceKeyword.value = String(q.traceKeyword)
  if (q.poolStatus) poolStatus.value = String(q.poolStatus)
  const cr = Number(q.costRange)
  if (cr === 7 || cr === 30) costRange.value = cr
  if (q.q) drillKeyword.value = String(q.q)
}

// ==================================================================================================
// 导出 CSV（当前页签、当前筛选态）
// ==================================================================================================
const exportCsv = () => {
  let rows = []
  let filename = 'dashboard'
  if (tab.value === 'overview') {
    filename = '看板总览'
    rows = metrics.value.map(m => ({ 指标: m.label, 数值: m.value + m.unit, 较上期: compare.value ? m.deltaText : '', 说明: m.hint }))
    ;(summary.value.topQuestions || []).forEach((q, i) => rows.push({ 指标: `热门问题 TOP${i + 1}`, 数值: q.count, 较上期: '', 说明: q.question }))
    ;(summary.value.noHitQuestions || []).forEach((q, i) => rows.push({ 指标: `无命中 TOP${i + 1}`, 数值: q.count, 较上期: '', 说明: q.question }))
    ;(actionsData.value.rows || []).forEach(r => rows.push({ 指标: srcLabel(r.type) + '待办', 数值: '', 较上期: '', 说明: r.question }))
  } else if (tab.value === 'trace') {
    filename = '执行记录'
    rows = traceRows.value.map(r => ({
      时间: fmtTime(r.time), 类型: r.kind === 'workflow' ? '工作流' : '对话',
      智能体: r.agentName || '全局', 标题: r.title || r.question || '', 状态: r.kind === 'workflow' ? wfStatusLabel(r.status) : (r.hasCitation ? '有引用' : '无引用'),
      命中: r.hitDocCount ?? '', 耗时ms: r.elapsedMs ?? '', 评分: r.rating === 1 ? '好评' : r.rating === 0 ? '差评' : ''
    }))
  } else if (tab.value === 'pool') {
    filename = '采样标注'
    rows = poolRows.value.map(r => ({
      入池时间: fmtTime(r.createdAt), 来源: srcText(r.source), 状态: r.status,
      问题: r.question || '', 回答摘要: r.answerSummary || '', 备注: r.note || ''
    }))
  } else {
    filename = '费用与配额'
    rows = (cost.value.byUser || []).map(r => ({
      用户: r.name, 输入tokens: r.promptTokens, 输出tokens: r.completionTokens, 费用: r.cost ?? '未计价'
    }))
  }
  if (!rows.length) { message.info('当前没有可导出的数据'); return }
  const headers = Object.keys(rows[0])
  const escape = v => `"${String(v == null ? '' : v).replace(/"/g, '""')}"`
  const csv = '\ufeff' + headers.join(',') + '\n' + rows.map(r => headers.map(k => escape(r[k])).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${filename}-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  message.success('已导出当前视图')
}

// ==================================================================================================
// 图表（ECharts；主题色取 CSS 变量，随深浅主题重绘）
// ==================================================================================================
const trendEl = ref(null)
const donutEl = ref(null)
const traceEl = ref(null)
const costEl = ref(null)
const modelEl = ref(null)
const charts = {}

function chartOf(key, el) {
  if (!el) return null
  if (!charts[key]) charts[key] = echarts.init(el)
  return charts[key]
}
function axisColors() {
  return {
    text3: cssVar('--app-text3') || '#98a0aa',
    border: cssVar('--app-border') || '#e6e9ed',
    panel: cssVar('--app-panel') || '#fff',
    text2: cssVar('--app-text2') || '#5f6570'
  }
}
function tooltipStyle(c) {
  const dark = themeState.value === 'dark'
  return {
    backgroundColor: dark ? '#262b33' : '#fff',
    borderColor: c.border,
    textStyle: { color: dark ? '#e8eaed' : '#1f2329', fontSize: 12 }
  }
}

function renderTrend() {
  const el = trendEl.value
  if (!el) return
  const chart = chartOf('trend', el)
  const rows = summary.value.trend || []
  if (!rows.length) return
  const c = axisColors()
  const labels = rows.map(r => { const p = String(r.date).split('-'); return `${+p[1]}/${+p[2]}` })
  chart.setOption({
    animation: false,
    grid: { left: 10, right: 14, top: 16, bottom: 6, containLabel: true },
    tooltip: { trigger: 'axis', ...tooltipStyle(c) },
    xAxis: {
      type: 'category', boundaryGap: false, data: labels,
      axisLine: { lineStyle: { color: c.border } }, axisTick: { show: false },
      axisLabel: { color: c.text3, fontSize: 10, interval: Math.max(0, Math.floor(labels.length / 8) - 1) }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
      axisLabel: { color: c.text3, fontSize: 10 }
    },
    series: [
      { name: '问答量', type: 'line', smooth: true, showSymbol: false, data: rows.map(r => r.total),
        lineStyle: { width: 2, color: accentColor() }, itemStyle: { color: accentColor() },
        areaStyle: { color: 'rgba(46,107,230,.10)' } },
      { name: '有引用', type: 'line', smooth: true, showSymbol: false, data: rows.map(r => r.cited),
        lineStyle: { width: 1.6, color: cssVar('--app-ok') || '#3b8f4e' }, itemStyle: { color: cssVar('--app-ok') || '#3b8f4e' } },
      { name: '无命中', type: 'line', smooth: true, showSymbol: false, data: rows.map(r => r.noHit),
        lineStyle: { width: 1.6, color: cssVar('--app-warn') || '#c77c14' }, itemStyle: { color: cssVar('--app-warn') || '#c77c14' } }
    ]
  }, true)
  chart.resize()
}

function renderDonut() {
  const el = donutEl.value
  if (!el) return
  const chart = chartOf('donut', el)
  const c = axisColors()
  const cited = Math.max(0, Number(summary.value.total || 0) - noHitCount.value)
  const noHit = Math.max(0, noHitCount.value)
  chart.setOption({
    animation: false,
    tooltip: { ...tooltipStyle(c), formatter: p => `${p.name}：${p.value} 条（${p.percent}%）` },
    title: {
      text: fmtInt(summary.value.total || 0), subtext: rangeLabel.value, left: 'center', top: '35%',
      textStyle: { color: c.text2, fontSize: 20, fontWeight: 500 },
      subtextStyle: { color: c.text3, fontSize: 10 }, itemGap: 2
    },
    series: [{
      type: 'pie', radius: ['62%', '84%'], center: ['50%', '50%'],
      label: { show: false }, labelLine: { show: false },
      itemStyle: { borderColor: c.panel, borderWidth: 2 },
      data: [
        { name: '有引用命中', value: cited, itemStyle: { color: accentColor() } },
        { name: '无命中', value: noHit, itemStyle: { color: '#e0a44a' } }
      ]
    }]
  }, true)
  chart.resize()
}

function renderTraceChart() {
  const el = traceEl.value
  if (!el) return
  const chart = chartOf('trace', el)
  const rows = traceOverview.value.trend || []
  if (!rows.length) return
  const c = axisColors()
  const labels = rows.map(r => { const p = String(r.date).split('-'); return `${+p[1]}/${+p[2]}` })
  chart.setOption({
    animation: false,
    grid: { left: 10, right: 38, top: 18, bottom: 6, containLabel: true },
    tooltip: { trigger: 'axis', ...tooltipStyle(c) },
    xAxis: {
      type: 'category', data: labels,
      axisLine: { lineStyle: { color: c.border } }, axisTick: { show: false },
      axisLabel: { color: c.text3, fontSize: 10, interval: Math.max(0, Math.floor(labels.length / 8) - 1) }
    },
    yAxis: [
      { type: 'value', splitLine: { lineStyle: { color: c.border, type: 'dashed' } }, axisLabel: { color: c.text3, fontSize: 10 } },
      { type: 'value', min: 90, max: 100, splitLine: { show: false }, axisLabel: { color: c.text3, fontSize: 10, formatter: '{value}%' } }
    ],
    series: [
      { name: '对话', type: 'bar', stack: 'run', barMaxWidth: 14, data: rows.map(r => r.chat),
        itemStyle: { color: 'rgba(46,107,230,.55)', borderRadius: [2, 2, 0, 0] } },
      { name: '工作流', type: 'bar', stack: 'run', barMaxWidth: 14, data: rows.map(r => r.workflow),
        itemStyle: { color: '#bcd0f7', borderRadius: [2, 2, 0, 0] } },
      { name: '成功率', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false,
        data: rows.map(r => r.successRate), lineStyle: { width: 1.8, color: cssVar('--app-ok') || '#3b8f4e' },
        itemStyle: { color: cssVar('--app-ok') || '#3b8f4e' } }
    ]
  }, true)
  chart.resize()
}

function renderCostChart() {
  const el = costEl.value
  if (!el) return
  const chart = chartOf('cost', el)
  const rows = cost.value.trend || []
  if (!rows.length) return
  const c = axisColors()
  const labels = rows.map(r => { const p = String(r.date).split('-'); return `${+p[1]}/${+p[2]}` })
  chart.setOption({
    animation: false,
    grid: { left: 10, right: 14, top: 16, bottom: 6, containLabel: true },
    tooltip: { trigger: 'axis', ...tooltipStyle(c), valueFormatter: v => '¥' + (Math.round(Number(v) * 100) / 100) },
    xAxis: {
      type: 'category', boundaryGap: false, data: labels,
      axisLine: { lineStyle: { color: c.border } }, axisTick: { show: false },
      axisLabel: { color: c.text3, fontSize: 10, interval: Math.max(0, Math.floor(labels.length / 7) - 1) }
    },
    yAxis: {
      type: 'value', splitLine: { lineStyle: { color: c.border, type: 'dashed' } },
      axisLabel: { color: c.text3, fontSize: 10, formatter: v => '¥' + v }
    },
    series: [{
      name: '费用', type: 'line', smooth: true, showSymbol: false, data: rows.map(r => r.cost),
      lineStyle: { width: 2, color: accentColor() }, itemStyle: { color: accentColor() },
      areaStyle: { color: 'rgba(46,107,230,.10)' }
    }]
  }, true)
  chart.resize()
}

function renderModelDonut() {
  const el = modelEl.value
  if (!el) return
  const chart = chartOf('model', el)
  const rows = modelRows.value
  if (!rows.length) return
  const c = axisColors()
  chart.setOption({
    animation: false,
    tooltip: { ...tooltipStyle(c), formatter: p => `${p.name}：${p.percent}%` },
    title: {
      text: modelUsesCost.value ? fmtCost(cost.value.total) : fmtTokensCompact(modelTotalTokens.value),
      subtext: modelUsesCost.value ? costRangeLabel.value : 'tokens',
      left: 'center', top: '35%',
      textStyle: { color: c.text2, fontSize: 17, fontWeight: 500 },
      subtextStyle: { color: c.text3, fontSize: 10 }, itemGap: 2
    },
    series: [{
      type: 'pie', radius: ['60%', '82%'], center: ['50%', '50%'],
      label: { show: false }, labelLine: { show: false },
      itemStyle: { borderColor: c.panel, borderWidth: 2 },
      data: rows.map((m, i) => ({
        name: m.label || m.model,
        value: modelUsesCost.value ? (Number(m.cost) || 0) : (Number(m.tokens) || 0),
        itemStyle: { color: modelColor(i) }
      }))
    }]
  }, true)
  chart.resize()
}

function renderCharts() { renderTrend(); renderDonut(); renderTraceChart(); renderCostChart(); renderModelDonut() }
const onResize = () => Object.values(charts).forEach(c => c && c.resize())
watch(themeState, () => { nextTick(renderCharts) })

// ==================================================================================================
// 页签 / 自动刷新 / 生命周期
// ==================================================================================================
const onTabChange = key => {
  router.replace({ query: { tab: key } })
  if (key === 'trace') { loadTraceOverview(); loadTraces() }
  if (key === 'pool') { loadPoolStats(); loadPool() }
  if (key === 'cost') { loadCost(); loadQuotas() }
  nextTick(renderCharts)
}

let autoTimer = null
function applyAutoRefresh() {
  if (autoTimer) { clearInterval(autoTimer); autoTimer = null }
  if (!autoRefresh.value) return
  autoTimer = setInterval(() => {
    // 自动刷新不铺整页 loading（方案要求「刷新不闪整页」）；手动刷新仍显示 loading
    loadOverview(true)
    if (tab.value === 'trace') { loadTraceOverview(); loadTraces() }
    if (tab.value === 'pool') { loadPoolStats(); loadPool() }
    if (tab.value === 'cost') { loadCost(); loadQuotas() }
  }, 5 * 60 * 1000)
}
watch(autoRefresh, applyAutoRefresh)

watch(() => route.query.tab, v => {
  if (DASH_TABS.includes(v) && v !== tab.value) {
    tab.value = v
    onTabChange(v)
  }
})

const reloadAll = () => {
  loadOverview()
  loadTraceOverview()
  loadTraces()
  loadPoolStats()
  loadPool()
  loadCost()
  loadQuotas()
  getEvalLastReport().then(r => { if (r && r.success) checkup.value = r.data || null }).catch(() => {})
}

onMounted(async () => {
  window.addEventListener('resize', onResize)
  applyQueryState()
  getEvalLastReport().then(r => { if (r && r.success) checkup.value = r.data || null }).catch(() => {})
  await loadOverview()
  loadTraceOverview()
  loadTraces()
  loadPoolStats()
  loadPool()
  loadCost()
  loadQuotas()
  applyAutoRefresh()
  if (route.query.q) {
    drill(String(route.query.q))
  } else if (tab.value === 'trace') {
    loadTraceOverview()
    loadTraces()
  } else if (tab.value === 'pool') {
    loadPoolStats()
    loadPool()
  } else if (tab.value === 'cost') {
    loadCost()
    loadQuotas()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (autoTimer) clearInterval(autoTimer)
  Object.values(charts).forEach(c => c && c.dispose())
})

// ==================================================================================================
// 格式化
// ==================================================================================================
const fmtInt = n => (Number(n) || 0).toLocaleString('zh-CN')
const fmtTime = t => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')
const fmtClock = t => {
  if (!t) return '—'
  const s = String(t).replace('T', ' ')
  const now = new Date()
  const ymd = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  return s.startsWith(ymd) ? s.slice(11, 16) : s.slice(0, 16)
}
const fmtAgo = t => {
  if (!t) return ''
  const ts = new Date(String(t).replace(' ', 'T')).getTime()
  if (!Number.isFinite(ts)) return ''
  const diff = Date.now() - ts
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 3600 * 1000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400 * 1000) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < 30 * 86400 * 1000) return Math.floor(diff / 86400000) + ' 天前'
  return String(t).slice(0, 10)
}
const fmtCost = v => {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  if (n === 0) return '¥0'
  if (n < 0.01) return '<¥0.01'
  return '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
const fmtTokensCompact = n => {
  const v = Number(n) || 0
  if (v >= 1e8) return (Math.round(v / 1e7) / 10) + '亿'
  if (v >= 1e4) return (Math.round(v / 1e3) / 10) + '万'
  return String(v)
}
</script>

<style scoped>
.head-stat { font-size: 12px; color: var(--app-text3); }
.tab-label { display: inline-flex; align-items: center; gap: 5px; }
.tab-badge { font-style: normal; font-size: 10px; line-height: 15px; padding: 0 5px; border-radius: 999px; background: var(--app-danger-weak); color: var(--app-danger); font-weight: 700; }
:deep(.ant-table-tbody > tr) { cursor: pointer; }
.head-acts { margin-left: auto; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.data-ts { font-size: 11px; color: var(--app-text3); white-space: nowrap; }

/* ---------- 预警横幅 ---------- */
.alert-banner { display: flex; align-items: center; gap: 10px; padding: 9px 14px; margin-bottom: 10px; border-color: var(--app-warn-border); background: var(--app-warn-weak); }
.alert-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--app-warn); flex: none; }
.alert-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--app-text2); min-width: 0; }
.alert-main b { color: var(--app-text); font-weight: 600; }
.alert-item { border-radius: 999px; padding: 1px 8px; font-size: 11px; }
.alert-item.warn { color: var(--app-warn-text); background: #fff; border: 1px solid var(--app-warn-border); }
.alert-item.err { color: var(--app-danger); background: #fff; border: 1px solid var(--app-danger-border); }
.alert-banner .app-link-btn:last-child { margin-left: 0; }
.alert-banner > .app-link-btn:nth-last-child(2) { margin-left: auto; }

/* ---------- 视图菜单 ---------- */
.view-menu { min-width: 200px; background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 8px; box-shadow: 0 6px 20px rgba(31, 35, 41, .12); padding: 6px; }
.vm-title { font-size: 10.5px; color: var(--app-text3); padding: 4px 8px 6px; }
.vm-empty { font-size: 11.5px; color: var(--app-text3); padding: 4px 8px 8px; }
.vm-row { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 6px; font-size: 12px; color: var(--app-text2); cursor: pointer; }
.vm-row:hover { background: var(--app-accent-weak); color: var(--app-accent); }
.vm-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.vm-del { color: var(--app-text3); font-size: 14px; line-height: 1; padding: 0 2px; }
.vm-del:hover { color: var(--app-danger); }
.vm-sep { height: 1px; background: var(--app-border); margin: 4px 0; }
.vm-save { color: var(--app-accent); }

/* ---------- 可排序区块 ---------- */
.overview-sortable { display: flex; flex-direction: column; gap: 10px; }
.sort-block { position: relative; display: flex; flex-direction: column; gap: 8px; }
.layout-bar { display: flex; align-items: center; gap: 12px; font-size: 11.5px; color: var(--app-text2); background: var(--app-accent-weak); border: 1px dashed var(--app-accent-border); border-radius: 6px; padding: 6px 12px; margin-bottom: 10px; }
.sort-ctrls { position: absolute; top: -8px; right: 0; display: flex; gap: 4px; z-index: 6; }
.sort-btn { width: 22px; height: 22px; border-radius: 5px; border: 1px solid var(--app-border-strong); background: var(--app-panel); color: var(--app-text2); font-size: 11px; line-height: 1; cursor: pointer; display: flex; align-items: center; justify-content: center; padding: 0; box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.sort-btn:hover:not(:disabled) { color: var(--app-accent); border-color: var(--app-accent); }
.sort-btn:disabled { opacity: .35; cursor: default; }

/* ---------- 指标行 ---------- */
.kpi-row { display: grid; grid-template-columns: 280px repeat(4, minmax(0, 1fr)); gap: 10px; }
.health-card { display: flex; flex-direction: column; }
.health-body { display: flex; align-items: center; gap: 14px; }
.health-side { display: flex; flex-direction: column; gap: 4px; }
.health-delta { font-size: 10.5px; font-weight: 600; }
.health-cap { font-size: 10px; color: var(--app-text3); }
.hd-i { font-style: normal; display: inline-flex; width: 12px; height: 12px; border-radius: 50%; border: 1px solid var(--app-border-strong); color: var(--app-text3); font-size: 9px; align-items: center; justify-content: center; margin-left: 2px; }
.health-doc { font-size: 11.5px; line-height: 1.8; color: var(--app-text2); max-width: 280px; }
.hd-title { font-size: 11px; font-weight: 700; color: var(--app-text); margin-bottom: 2px; }
.hd-foot { margin-top: 4px; padding-top: 4px; border-top: 1px dashed var(--app-border); color: var(--app-text3); }
.health-parts { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 10.5px; color: var(--app-text3); margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--app-border); }
.health-parts b { color: var(--app-text2); font-weight: 600; }

.metric-card { display: flex; flex-direction: column; justify-content: space-between; min-height: 112px; }
.metric-label { font-size: 11px; color: var(--app-text3); }
.metric-main { display: flex; align-items: baseline; gap: 7px; margin-top: 3px; }
.metric-num { font-size: 24px; font-weight: 600; letter-spacing: -.02em; line-height: 1.15; font-variant-numeric: tabular-nums; }
.metric-unit { font-style: normal; font-size: 12px; color: var(--app-text3); font-weight: 400; margin-left: 1px; }
.metric-delta { font-size: 10.5px; font-weight: 600; white-space: nowrap; }
.metric-foot { display: flex; align-items: flex-end; justify-content: space-between; gap: 8px; margin-top: 8px; }
.spark { width: 92px; height: 28px; flex: none; }
.metric-hint { font-size: 10px; color: var(--app-text3); text-align: right; line-height: 1.35; }
.up { color: var(--app-ok); } .down { color: var(--app-danger); } .flat { color: var(--app-text3); }

/* ---------- 图表 ---------- */
.chart-row { display: grid; grid-template-columns: minmax(0, 2.15fr) minmax(0, 1fr); gap: 10px; }
.legend { display: flex; align-items: center; gap: 12px; margin-left: auto; font-size: 10.5px; color: var(--app-text2); font-weight: 400; }
.legend i { width: 8px; height: 8px; border-radius: 2px; display: inline-block; margin-right: 4px; }
.echart { width: 100%; }
.trend { height: 240px; }
.donut { width: 132px; height: 132px; flex: none; }
.donut-wrap { display: flex; align-items: center; gap: 12px; }
.donut-legend { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 9px; }
.dl-row { display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--app-text2); }
.dl-dot { width: 8px; height: 8px; border-radius: 2px; flex: none; }
.dl-t { color: var(--app-text); }
.dl-s { font-size: 10px; color: var(--app-text3); }
.dl-row b { margin-left: auto; color: var(--app-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.sat-block { margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--app-border); }
.sat-head { display: flex; justify-content: space-between; font-size: 10.5px; color: var(--app-text3); }
.sat-head b { color: var(--app-text); font-weight: 600; }
.sat-bar { height: 7px; border-radius: 4px; background: #f0f2f5; overflow: hidden; margin: 6px 0 5px; display: flex; }
.sat-bar i { display: block; height: 100%; background: var(--app-ok); }
.sat-bar em { display: block; height: 100%; background: #e9a3a3; flex: 1; }
.sat-foot { display: flex; justify-content: space-between; font-size: 10.5px; color: var(--app-text2); }

/* ---------- 榜单 ---------- */
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.rank-list { display: flex; flex-direction: column; }
.rank-row { display: grid; grid-template-columns: 18px minmax(0, 1fr) 110px 40px; gap: 8px; align-items: center; padding: 6px 4px; border-bottom: 1px dashed var(--app-border); cursor: pointer; border-radius: 4px; }
.rank-row.has-act { grid-template-columns: 18px minmax(0, 1fr) 110px 40px 38px; }
.rank-row:last-child { border-bottom: none; }
.rank-row:hover { background: var(--app-panel-2, #fafbfc); }
.rank-no { font-size: 10.5px; color: var(--app-text3); text-align: center; font-family: ui-monospace, Menlo, monospace; }
.rank-no.top { color: var(--app-accent); font-weight: 700; }
.rank-q { font-size: 11.8px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rank-row:hover .rank-q { color: var(--app-accent); }
.rank-bar { height: 6px; border-radius: 3px; background: #f0f2f5; overflow: hidden; }
.rank-bar i { display: block; height: 100%; border-radius: 3px; background: var(--app-accent); }
.rank-bar i.warm { background: #e0a44a; }
.rank-c { font-size: 11px; color: var(--app-text2); text-align: right; font-variant-numeric: tabular-nums; }
.mini-link { border: none; background: none; color: var(--app-accent); font-size: 11px; cursor: pointer; padding: 0; font-family: inherit; }
.mini-link:hover { text-decoration: underline; }

/* ---------- 体检条 ---------- */
.checkup-strip { display: flex; align-items: center; gap: 10px; padding: 10px 14px; flex-wrap: wrap; }
.checkup-strip b { font-size: 12px; font-weight: 600; }
.checkup-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.checkup-dot.ok { background: var(--app-ok); box-shadow: 0 0 0 3px var(--app-ok-weak); }
.checkup-dot.warn { background: var(--app-warn); box-shadow: 0 0 0 3px var(--app-warn-weak); }
.checkup-dot.err { background: var(--app-danger); box-shadow: 0 0 0 3px var(--app-danger-weak); }
.checkup-dot.muted { background: var(--app-text3); box-shadow: 0 0 0 3px #f1f3f5; }
.checkup-meta { font-size: 10.5px; color: var(--app-text3); }
.chip { font-size: 10.5px; border: 1px solid var(--app-border); border-radius: 999px; padding: 2px 8px; color: var(--app-text2); background: var(--app-panel-2, #fafbfc); white-space: nowrap; }
.chip b { color: var(--app-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.checkup-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; }

/* ---------- 待办 ---------- */
.aq-title { flex-wrap: wrap; }
.aq-progress { margin-left: auto; display: flex; align-items: center; gap: 8px; font-size: 10.5px; color: var(--app-text3); font-weight: 400; }
.aq-progress b { color: var(--app-text); }
.aq-track { width: 110px; height: 6px; border-radius: 3px; background: #f0f2f5; overflow: hidden; }
.aq-track i { display: block; height: 100%; background: var(--app-accent); border-radius: 3px; }
.aq-chips { display: flex; gap: 6px; flex-wrap: wrap; margin: 2px 0 6px; }
.aq-chip { border: 1px solid var(--app-border); background: var(--app-panel); color: var(--app-text2); border-radius: 999px; padding: 2px 10px; font-size: 10.5px; cursor: pointer; font-family: inherit; }
.aq-chip:hover { border-color: var(--app-accent-border); color: var(--app-accent); }
.aq-chip.on { border-color: var(--app-accent-border); background: var(--app-accent-weak); color: var(--app-accent); font-weight: 600; }
.aq-row { display: flex; align-items: center; gap: 9px; padding: 8px 2px; border-bottom: 1px dashed var(--app-border); }
.aq-row:last-child { border-bottom: none; }
.src { font-size: 10px; padding: 1px 6px; border-radius: 4px; flex: none; white-space: nowrap; }
.src.bad { color: #cf1322; background: var(--app-danger-weak); border: 1px solid var(--app-danger-border); }
.src.gap { color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); }
.src.pool { color: var(--app-accent); background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); }
.aq-main { flex: 1; min-width: 0; }
.aq-q { font-size: 12px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.aq-meta { font-size: 10.5px; color: var(--app-text3); margin-top: 1px; }
.aq-acts { display: flex; gap: 10px; flex: none; }

/* ---------- 执行记录 ---------- */
.stat-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.mini-stat { min-height: 86px; }
.mini-stat .metric-num { font-size: 21px; }
.mini-stat .metric-hint { text-align: left; }
.trace-chart { height: 200px; }
.trace-filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.trace-ct { font-size: 12px; color: var(--app-text2); margin-right: 4px; }
.wf-run-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; margin-right: 5px; vertical-align: middle; }
.rd-success { background: var(--app-ok); }
.rd-failed { background: var(--app-warn); }
.dur-cell { display: flex; align-items: center; gap: 6px; }
.dur-track { width: 56px; height: 5px; border-radius: 3px; background: #f0f2f5; overflow: hidden; flex: none; }
.dur-track i { display: block; height: 100%; border-radius: 3px; background: var(--app-accent); opacity: .7; }
.dur-cell span { font-size: 10.5px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.drill-chip { display: flex; align-items: center; gap: 10px; font-size: 11.5px; color: var(--app-text2); background: var(--app-accent-weak); border: 1px solid var(--app-accent-border); border-radius: 6px; padding: 5px 10px; margin-bottom: 8px; }
.drill-chip b { color: var(--app-accent); }
.table-hint { font-size: 11px; color: var(--app-text3); padding-top: 8px; }

/* ---------- 采样标注 ---------- */
.funnel { display: flex; align-items: stretch; gap: 8px; }
.f-stage { flex: 1; background: var(--app-panel-2, #fafbfc); border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; position: relative; overflow: hidden; }
.f-stage::after { content: ''; position: absolute; left: 0; right: 0; bottom: 0; height: 3px; background: var(--app-accent); opacity: .45; }
.f-stage:nth-child(5)::after, .f-stage:nth-child(7)::after { opacity: .9; }
.f-arrow { display: flex; align-items: center; color: var(--app-border-strong); font-size: 12px; flex: none; }
.f-num { font-size: 21px; font-weight: 600; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
.f-label { font-size: 10.5px; color: var(--app-text3); margin-top: 1px; }
.f-pct { font-size: 10px; color: var(--app-text2); margin-top: 3px; }
.sample-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.sample-card { min-width: 0; border: 1px solid var(--app-border); border-radius: 8px; padding: 10px 12px; background: var(--app-panel); }
.sample-card:hover { border-color: var(--app-accent-border); }
.sample-top { display: flex; align-items: center; gap: 7px; margin-bottom: 5px; min-width: 0; }
.sample-q { flex: 1 1 auto; font-size: 12px; font-weight: 600; color: var(--app-text); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sample-time { margin-left: auto; font-size: 10px; color: var(--app-text3); flex: none; }
/* 长问题/长 URL 不换行会撑爆网格轨道（两列里第二列被挤出视口且横滚被隐藏）：
   卡片补 min-width:0，摘要按两行截断并允许 URL 内换行。 */
.sample-a {
  font-size: 11px; color: var(--app-text2); line-height: 1.55; margin-bottom: 7px;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2;
  overflow: hidden; overflow-wrap: anywhere;
}
.sample-foot { display: flex; align-items: center; gap: 8px; }
.sample-note { font-size: 10px; color: var(--app-text3); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sample-acts { margin-left: auto; display: flex; gap: 10px; flex: none; }
.pool-pager { display: flex; align-items: center; justify-content: center; gap: 12px; padding-top: 10px; font-size: 11px; color: var(--app-text3); }

/* ---------- 费用与配额 ---------- */
.budget-row { display: grid; grid-template-columns: 170px minmax(0, 1fr) 160px; gap: 12px; align-items: center; padding: 9px 0; border-bottom: 1px dashed var(--app-border); }
.budget-row:last-child { border-bottom: none; }
.b-name { font-size: 11.5px; color: var(--app-text); }
.b-sub { font-size: 10px; color: var(--app-text3); }
.budget-track { height: 10px; border-radius: 5px; background: #f0f2f5; position: relative; overflow: hidden; }
.budget-fill { height: 100%; border-radius: 5px; background: var(--app-accent); }
.budget-fill.ok { background: var(--app-accent); }
.budget-fill.warn { background: var(--app-warn); }
.budget-fill.danger { background: var(--app-danger); }
.budget-threshold { position: absolute; top: 0; bottom: 0; left: 80%; width: 1px; background: rgba(212, 85, 46, .45); }
.budget-meta { text-align: right; font-size: 11px; color: var(--app-text2); font-variant-numeric: tabular-nums; }
.budget-meta b { color: var(--app-text); font-weight: 600; }
.b-state { display: inline-block; font-size: 10px; border-radius: 4px; padding: 0 6px; margin-left: 5px; }
.b-state.warn { color: var(--app-warn-text); background: var(--app-warn-weak); border: 1px solid var(--app-warn-border); }
.b-state.danger { color: var(--app-danger); background: var(--app-danger-weak); border: 1px solid var(--app-danger-border); }
.cost-grid { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); gap: 10px; margin-top: 10px; }
.cost-grid2 { display: grid; grid-template-columns: 1fr 1.2fr; gap: 10px; margin-top: 10px; }
.cost-chart { height: 210px; }
.model-donut { width: 132px; height: 132px; flex: none; }
.user-bar { display: grid; grid-template-columns: 72px minmax(0, 1fr) 70px; gap: 8px; align-items: center; padding: 6px 0; }
.ub-name { font-size: 11.5px; color: var(--app-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ub-track { height: 7px; border-radius: 4px; background: #f0f2f5; overflow: hidden; }
.ub-track i { display: block; height: 100%; border-radius: 4px; background: var(--app-accent); }
.ub-val { font-size: 11px; color: var(--app-text2); text-align: right; font-variant-numeric: tabular-nums; }
.unpriced-box { margin-bottom: 10px; }
.unpriced-title { font-size: 12px; font-weight: 600; margin-bottom: 6px; }
.unpriced-tags { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.detail-block { margin-bottom: 12px; }
.detail-title { font-size: 12px; font-weight: 600; margin-bottom: 6px; }
.modal-hint { font-size: 11.5px; color: var(--app-text3); margin-top: 8px; line-height: 1.6; }

/* ---------- 详情抽屉 ---------- */
.dt-block { margin-bottom: 12px; }
.dt-label { font-size: 12px; color: var(--app-text3); margin-bottom: 4px; }
.dt-pre { background: var(--app-panel-2, #fafbfc); border-radius: 6px; padding: 8px 10px; margin: 0; font-size: 12px; max-height: 220px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.dt-meta { font-size: 12px; color: var(--app-text3); }
.dt-source { display: flex; align-items: center; gap: 8px; padding: 3px 0; border-bottom: 1px dashed var(--app-border); }
.dt-source:last-child { border-bottom: none; }
.dt-source-name { font-size: 13px; }
.dt-kv { display: flex; align-items: center; gap: 10px; font-size: 13px; padding: 2px 0; }
.dt-kv > span { color: var(--app-text3); font-size: 12px; width: 48px; flex: none; }
.dt-tool { border: 1px solid var(--app-border); border-radius: 6px; margin-bottom: 8px; overflow: hidden; }
.dt-tool:last-child { margin-bottom: 0; }
.dt-tool-head { display: flex; align-items: center; gap: 8px; padding: 6px 10px; cursor: pointer; background: var(--app-panel-2, #fafbfc); }
.dt-tool-name { font-size: 13px; font-weight: 500; }
.dt-tool-toggle { margin-left: auto; }
.dt-tool-body { padding: 0 10px 10px; }
.dt-tool-hint { font-size: 12px; color: var(--app-text3); margin-bottom: 6px; }
.dt-io-label { font-size: 12px; color: var(--app-text3); margin: 8px 0 4px; }
.dt-arg { margin-bottom: 8px; }
.dt-arg:last-child { margin-bottom: 0; }
.dt-arg-key { font-size: 12px; color: var(--app-text2); margin-bottom: 3px; font-family: ui-monospace, Menlo, monospace; }
.dt-arg-val { max-height: 180px; }
.dt-arg-more { display: inline-block; margin-top: 3px; font-size: 12px; color: var(--app-accent); cursor: pointer; }
.wf-trace { border-top: 1px dashed var(--app-border); padding: 6px 0; }
.wf-trace-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
.wf-trace-name { font-size: 13px; font-weight: 500; font-family: ui-monospace, Menlo, monospace; }
.wf-run-err { font-size: 12px; color: var(--app-danger); margin-top: 4px; }

@media (max-width: 1200px) {
  .kpi-row { grid-template-columns: 1fr 1fr 1fr; }
  .health-card { grid-column: span 3; }
  .chart-row, .two-col, .cost-grid, .cost-grid2 { grid-template-columns: 1fr; }
  .sample-grid { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 900px) {
  .stat-strip { grid-template-columns: 1fr 1fr; }
  .budget-row { grid-template-columns: 1fr; gap: 4px; }
  .budget-meta { text-align: left; }
  .head-acts { width: 100%; }
}
</style>
