<template>
  <div class="app-page ae-page">
    <!-- ==================== 总览（效果图场景 01） ==================== -->
    <template v-if="!currentAgentId">
      <div class="app-page-head">
        <h3 class="app-page-title">智能体评测</h3>
        <span class="head-hint">用真实问题回归配置改动，发布前知道变好还是变坏</span>
        <a-input v-model:value="kw" size="small" class="kw" placeholder="搜索智能体" allow-clear>
          <template #prefix><search-outlined class="ic" /></template>
        </a-input>
        <a-button size="small" type="primary" @click="createSuiteOpen = true">
          <template #icon><plus-outlined /></template>新建评测集
        </a-button>
      </div>

      <div class="app-page-body">
        <a-spin :spinning="loadingSummary">
          <!-- 统计条 -->
          <div class="stat-row">
            <div class="app-card stat">
              <span class="lab">已建立评测集</span>
              <span class="val">{{ s.suiteCount ?? '—' }}<em>/ {{ s.agentCount ?? '—' }} 个智能体</em></span>
              <span class="sub">{{ (s.agentCount ?? 0) - (s.suiteCount ?? 0) || '0' }} 个智能体尚未建立</span>
            </div>
            <div class="app-card stat">
              <span class="lab">本周回归</span>
              <span class="val">{{ s.weekRuns ?? 0 }}<em>次</em></span>
              <span class="sub good">近 7 天累计运行</span>
            </div>
            <div class="app-card stat">
              <span class="lab">平均通过率</span>
              <span class="val">{{ s.avgPassRate == null ? '—' : pct(s.avgPassRate) }}</span>
              <span class="sub" :class="(s.avgPassRateDelta ?? 0) >= 0 ? 'good' : 'bad'">
                {{ s.avgPassRateDelta == null ? '暂无可比的上版' : '较上版 ' + signed(s.avgPassRateDelta) + 'pp' }}</span>
            </div>
            <div class="app-card stat">
              <span class="lab">低于门槛</span>
              <span class="val" :class="{ danger: (s.belowCount ?? 0) > 0 }">{{ s.belowCount ?? 0 }}<em>个</em></span>
              <span class="sub" :class="{ bad: (s.belowCount ?? 0) > 0 }">{{ belowNames || '没有低于门槛的智能体' }}</span>
            </div>
          </div>

          <!-- 质量概况条 -->
          <div class="app-card quality-strip">
            <div class="qs-cell">
              <div>
                <div class="qs-lab">门禁分布</div>
                <div class="qs-items">
                  <span class="qs-item"><i class="dot ok"></i>达标 <b>{{ s.distribution?.pass ?? 0 }}</b></span>
                  <span class="qs-item"><i class="dot warn"></i>有变更 <b>{{ s.distribution?.changed ?? 0 }}</b></span>
                  <span class="qs-item"><i class="dot err"></i>低于门槛 <b>{{ s.distribution?.below ?? 0 }}</b></span>
                  <span class="qs-item"><i class="dot grey"></i>未评测 <b>{{ (s.distribution?.unevaluated ?? 0) + (s.distribution?.none ?? 0) }}</b></span>
                  <span class="qs-item"><i class="dot blue"></i>运行中 <b>{{ s.distribution?.running ?? 0 }}</b></span>
                </div>
              </div>
            </div>
            <div class="qs-trend">
              <div class="qs-lab">近 6 周平均通过率</div>
              <svg viewBox="0 0 400 42" preserveAspectRatio="none" class="spark">
                <polygon :points="sparkArea" fill="var(--app-accent-weak)" opacity=".75"></polygon>
                <polyline :points="sparkLine" fill="none" stroke="var(--app-accent)" stroke-width="1.6"></polyline>
              </svg>
            </div>
            <div class="qs-note">
              <div class="t">本周回归 <b>{{ s.weekRuns ?? 0 }}</b> 次 · 全部智能体 {{ s.agentCount ?? 0 }} 个</div>
              <div class="s">评测消耗单列在「使用统计 · 智能体评测」</div>
            </div>
          </div>

          <!-- 智能体门禁表 -->
          <div class="app-card tbl-card">
            <a-table size="small" :data-source="filteredRows" :pagination="false" row-key="agentId"
                     :locale="{ emptyText: '还没有可见的智能体' }">
              <a-table-column title="智能体" :width="230" key="name">
                <template #default="{ record }">
                  <span class="agent-cell">
                    <!-- 评测 summary 行的图标字段叫 agentIcon，AgentAvatar 读 icon——调用侧对齐字段名 -->
                    <AgentAvatar :agent="{ icon: record.agentIcon, isBuiltin: record.isBuiltin }" :size="22" />
                    <span class="agent-name">{{ record.agentName }}</span>
                    <span class="agent-sub">v{{ record.agentVersion ?? '—' }}{{ record.isBuiltin === 1 ? ' · 内置' : '' }}</span>
                  </span>
                </template>
              </a-table-column>
              <a-table-column title="评测集" :width="86" key="cases">
                <template #default="{ record }">
                  <span v-if="record.caseCount != null">{{ record.caseCount }} 条</span>
                  <span v-else class="muted">未建立</span>
                </template>
              </a-table-column>
              <a-table-column title="最近通过率" :width="150" key="rate">
                <template #default="{ record }">
                  <template v-if="record.lastRun?.passRate != null">
                    <span class="bar" :class="barClass(record.lastRun.passRate, record.gate?.thresholdRate)"><i :style="{ width: Math.min(100, record.lastRun.passRate) + '%' }"></i></span>
                    <span :class="rateClass(record)">{{ pct(record.lastRun.passRate) }}</span>
                  </template>
                  <span v-else-if="record.gate?.gate === 'running'" class="muted">
                    <span class="bar blue"><i :style="{ width: runProgress(record) + '%' }"></i></span>{{ runProgressText(record) }}
                  </span>
                  <span v-else class="muted">—</span>
                </template>
              </a-table-column>
              <a-table-column title="较上版" :width="86" key="delta">
                <template #default="{ record }">
                  <span v-if="record.deltaRate != null" :class="record.deltaRate >= 0 ? 'cell-good' : 'cell-bad'">{{ signed(record.deltaRate) }}</span>
                  <span v-else class="muted">—</span>
                </template>
              </a-table-column>
              <a-table-column title="最近运行" :width="150" key="time">
                <template #default="{ record }">
                  <span v-if="record.gate?.gate === 'running'" class="muted">正在运行 · {{ runProgressText(record) }}</span>
                  <span v-else-if="record.lastRun" class="muted">{{ shortTime(record.lastRun.finishedAt) }}</span>
                  <span v-else class="muted">—</span>
                </template>
              </a-table-column>
              <a-table-column title="门禁状态" :width="120" key="gate">
                <template #default="{ record }">
                  <span class="pill" :class="gateClass(record.gate?.gate)">{{ gateText(record.gate?.gate) }}</span>
                </template>
              </a-table-column>
              <a-table-column title="操作" :width="150" key="ops">
                <template #default="{ record }">
                  <template v-if="record.gate?.gate === 'running'">
                    <a class="link" @click="cancelRunOf(record)">取消</a>
                    <a class="link" @click="openAgent(record.agentId)">详情</a>
                  </template>
                  <template v-else-if="record.gate?.gate === 'none'">
                    <a class="link" @click="openAgent(record.agentId, 'suite')">建立评测集</a>
                  </template>
                  <template v-else>
                    <a class="link" @click="confirmRunOf(record)">运行回归</a>
                    <a class="link" @click="openAgent(record.agentId)">详情</a>
                  </template>
                </template>
              </a-table-column>
            </a-table>
          </div>

          <!-- 底部两栏 -->
          <div class="bottom-grid">
            <div class="app-card mini-card">
              <div class="h">最近回归<span class="more"><a class="link" @click="focusFirstRunningOrFirst">全部运行</a></span></div>
              <div v-if="!s.recentRuns?.length" class="empty-line">还没有运行记录</div>
              <div v-for="r in s.recentRuns" :key="r.id" class="mini-row">
                <span class="name">{{ r.agentName }} · v{{ r.agentVersion ?? '—' }}</span>
                <span class="meta">{{ r.total ?? '—' }} 条{{ r.finishedAt ? ' · ' + shortTime(r.finishedAt) : ' · 进行中' }}</span>
                <span :class="r.status === 'succeeded' ? (r.passRate >= (r._th ?? 80) ? 'cell-good' : 'cell-bad') : 'muted'">
                  {{ r.status === 'succeeded' ? pct(r.passRate) : runStatusText(r.status) }}</span>
                <span class="pill mini" :class="gateClass(runGate(r))">{{ gateText(runGate(r)) }}</span>
              </div>
            </div>
            <div class="app-card mini-card">
              <div class="h">需要处理<span class="more"><span class="pill plain mini">{{ s.todos?.length ?? 0 }} 项</span></span></div>
              <div v-if="!s.todos?.length" class="empty-line">一切正常，没有需要处理的项</div>
              <div v-for="(t, i) in s.todos" :key="i" class="mini-row">
                <span class="name">{{ t.agentName }}</span>
                <span class="meta">{{ t.text }}</span>
                <a class="link" @click="openAgent(t.agentId, t.kind === 'none' ? 'suite' : '')">{{ t.kind === 'none' ? '去建立' : '去运行' }}</a>
              </div>
            </div>
          </div>

          <div class="foot-note">
            <span>门禁判定：通过率 ≥ 套件门槛且无 0 分严重失败；新建套件默认门槛 {{ s.gateMode === 'block' ? '80%' : '80%' }}。</span>
            <span class="sp"></span>
            <span>评测默认只读，不执行需要人工确认的工具</span>
            <a class="link" @click="helpOpen = !helpOpen">查看评测口径</a>
          </div>
          <div v-if="helpOpen" class="app-card help-card">
            <ul>
              <li><b>评测集</b>：一个智能体一套，用例 = 问题 + 期望引用（知识块/文档）。可手动添加，也可「从会话采样」——差评轮次优先，把该轮实际引用当作期望引用。</li>
              <li><b>运行回归</b>：每条用例跑一轮<b>真实问答</b>（检索、人设、工具、引用与网页问答完全一致），需人工确认的工具按拒绝处理，评测只读。</li>
              <li><b>指标</b>：综合分 = 引用召回×40% + 忠实度×30% + 相关性×30%（无期望引用按 0/50/50）。通过 = 综合分 ≥ 套件通过分且有期望引用时命中 ≥ 1 条。忠实度/相关性由评判模型打分，<b>模型判定仅供参考</b>；未配置评判模型时只按引用召回计分。</li>
              <li><b>门禁</b>：保存智能体时若有变更未评测或低于门槛，会弹「保存前检查」——默认提示，管理员可在设置页（维护 · agentEval）改为阻断。</li>
            </ul>
          </div>
        </a-spin>
      </div>
    </template>

    <!-- ==================== 详情（效果图场景 02） ==================== -->
    <template v-else>
      <div class="crumb">
        <a class="back" @click="closeAgent"><left-outlined /> 评测总览</a>
        <span class="sep">/</span>
        <span>{{ agentRow?.agentName || currentAgentId }}</span>
      </div>
      <div class="detail-head">
        <AgentAvatar :agent="{ icon: agentRow?.agentIcon, isBuiltin: agentRow?.isBuiltin }" :size="38" />
        <div class="dh-main">
          <div class="dh-title">{{ agentRow?.agentName || currentAgentId }}
            <span class="pill plain mini">v{{ agentRow?.agentVersion ?? gate?.agentVersion ?? '—' }}</span>
            <span class="pill mini" :class="gateClass(gate?.gate)">{{ gateText(gate?.gate) }}</span>
          </div>
          <div class="dh-sub">
            {{ suiteInfo?.caseCount ?? 0 }} 条用例 · 门槛 {{ pct(suiteInfo?.suite?.thresholdRate) }}
            <template v-if="detail?.run"> · 最近运行 {{ shortTime(detail.run.finishedAt || detail.run.startedAt) }}</template>
          </div>
        </div>
        <div class="dh-acts">
          <a-button size="small" @click="innerTab = 'suite'">评测集</a-button>
          <a-button size="small" :disabled="succeededRuns.length < 1" @click="compareOpen = true">对比版本</a-button>
          <a-button size="small" type="primary" :loading="starting" @click="confirmRunOf()">运行回归</a-button>
        </div>
      </div>

      <div class="app-page-body">
        <a-spin :spinning="loadingDetail">
          <!-- 概览卡 -->
          <div class="sum-grid">
            <div class="app-card score-card">
              <span class="lab">最近综合分</span>
              <span class="score" :class="scoreTone(detail?.run?.avgScore)">{{ num1(detail?.run?.avgScore) }}</span>
              <span class="sub">召回 {{ num0(avgOf('recall')) }} · 忠实 {{ num0(dimAvg('faithfulness')) }} · 相关 {{ num0(dimAvg('relevance')) }}</span>
            </div>
            <div class="app-card mini-stat">
              <span class="lab">通过率</span>
              <span class="val" :class="detail?.run?.passRate != null && gate?.gate === 'below' ? 'danger' : ''">{{ pct(detail?.run?.passRate) }}</span>
              <span class="sub">{{ detail?.run?.passed ?? '—' }} / {{ detail?.run?.total ?? '—' }} 条通过 · 门槛 {{ pct(gate?.thresholdRate) }}</span>
            </div>
            <div class="app-card mini-stat">
              <span class="lab">平均耗时</span>
              <span class="val">{{ secs(detail?.run?.avgLatencyMs) }}</span>
              <span class="sub">按最近一次运行</span>
            </div>
            <div class="app-card mini-stat">
              <span class="lab">本次消耗</span>
              <span class="val">{{ tokens(detail?.run?.totalTokens) }}</span>
              <span class="sub">{{ detail?.run?.judgeModel ? '评判 ' + judgeShort : '未配置评判模型 · 仅按引用召回计分' }}</span>
            </div>
            <div class="app-card gate-card" :class="gateCardClass(gate?.gate)">
              <div class="row1"><span class="pill mini" :class="gateClass(gate?.gate)">{{ gateText(gate?.gate) }}</span>
                <span class="row1t">{{ gateCardText(gate?.gate) }}</span></div>
              <div class="row2">通过率 ≥ <b>{{ pct(gate?.thresholdRate) }}</b> 且无 0 分严重失败 → 达标<template v-if="gate?.lastRun">；上次运行 v{{ gate.lastRun.agentVersion }} · {{ pct(gate.lastRun.passRate) }}</template></div>
            </div>
          </div>

          <!-- 趋势 -->
          <div class="app-card trend-card">
            <div class="trend-head">
              <span class="t">近 {{ trendPoints.length || 0 }} 次回归</span>
              <span class="s">通过率趋势 · 只统计成功运行</span>
              <span class="sp"></span>
              <div class="legend"><span><i class="dot ok"></i>达标</span><span><i class="dot err"></i>低于门槛</span></div>
            </div>
            <svg v-if="trendPoints.length > 1" viewBox="0 0 1120 112" preserveAspectRatio="none" class="trend-svg">
              <line v-for="(t, i) in trendTicks" :key="'g' + i" x1="30" :y1="t.y" x2="1120" :y2="t.y" stroke="var(--app-border)" />
              <text v-for="(t, i) in trendTicks" :key="'t' + i" x="0" :y="t.y + 3" font-size="10" fill="var(--app-text3)">{{ t.label }}</text>
              <polygon v-if="detailTrendArea" :points="detailTrendArea" fill="var(--app-accent-weak)" opacity=".7"></polygon>
              <polyline :points="detailTrendLine" fill="none" stroke="var(--app-accent)" stroke-width="2"></polyline>
              <circle v-for="(p, i) in trendPoints" :key="i" :cx="p.x" :cy="p.y" r="4"
                      :fill="p.ok ? 'var(--app-ok)' : 'var(--app-danger)'">
                <title>{{ p.label }}</title>
              </circle>
              <text v-for="(p, i) in trendPoints" :key="'p' + i" :x="Math.min(1080, Math.max(40, p.x))" :y="p.labelY"
                    font-size="10" fill="var(--app-text3)" text-anchor="middle">{{ p.labelShort }}</text>
            </svg>
            <div v-else class="trend-empty">成功运行满 2 次后展示趋势</div>
          </div>

          <!-- 页内 Tab -->
          <div class="inner-tabs">
            <span class="inner-tab" :class="{ on: innerTab === 'results' }" @click="switchTab('results')">
              本次结果 <span class="cnt">{{ detail?.results?.length ?? 0 }}</span></span>
            <span class="inner-tab" :class="{ on: innerTab === 'history' }" @click="switchTab('history')">
              历史运行 <span class="cnt">{{ runsTotal }}</span></span>
            <span class="inner-tab" :class="{ on: innerTab === 'suite' }" @click="switchTab('suite')">
              评测集 <span class="cnt">{{ suiteInfo?.cases?.length ?? 0 }}</span></span>
            <span class="sp"></span>
            <template v-if="innerTab === 'results'">
              <span class="chip" :class="{ on: failOnly }" @click="failOnly = !failOnly">仅失败 {{ failCount }}</span>
            </template>
          </div>

          <!-- 本次结果 -->
          <div v-if="innerTab === 'results'" class="app-card tbl-card results-card">
            <a-table size="small" :data-source="resultRows" :pagination="false" row-key="id"
                     :expand-row-by-click="true" :defaultExpandAllRows="false"
                     :rowClassName="r => (r.passed === 1 ? 'row' : 'row fail')"
                     :locale="{ emptyText: '还没有运行结果——点右上「运行回归」跑一次' }">
              <a-table-column title="用例" key="q" :ellipsis="true">
                <template #default="{ record }"><span class="q">{{ record.question }}</span></template>
              </a-table-column>
              <a-table-column title="综合分" :width="84" key="score">
                <template #default="{ record }">
                  <span :class="record.passed === 1 ? 'cell-strong' : 'cell-bad'">{{ num0(record.score) }}</span>
                </template>
              </a-table-column>
              <a-table-column title="引用召回" :width="96" key="recall">
                <template #default="{ record }">
                  <span v-if="record.recall != null" :class="record.recall >= 1 ? 'cell-good' : (record.recall > 0 ? '' : 'cell-bad')">
                    {{ record._hit }}/{{ record._expected }}</span>
                  <span v-else class="muted">—</span>
                </template>
              </a-table-column>
              <a-table-column title="忠实度" :width="84" key="faith">
                <template #default="{ record }"><span :class="dimClass(record.faithfulness)">{{ num0(record.faithfulness) }}</span></template>
              </a-table-column>
              <a-table-column title="相关性" :width="84" key="rel">
                <template #default="{ record }"><span :class="dimClass(record.relevance)">{{ num0(record.relevance) }}</span></template>
              </a-table-column>
              <a-table-column title="耗时" :width="80" key="lat">
                <template #default="{ record }"><span class="muted">{{ record._secs }}</span></template>
              </a-table-column>
              <a-table-column title="状态" :width="82" key="st">
                <template #default="{ record }">
                  <span class="pill mini" :class="record.passed === 1 ? 'ok' : 'err'">{{ record.passed === 1 ? '通过' : '失败' }}</span>
                </template>
              </a-table-column>
              <template #expandedRowRender="{ record }">
                <div class="detail-inner">
                  <div class="detail-col grow">
                    <div class="k">失败原因</div>
                    <div class="v" :class="{ reason: record.passed !== 1 }">{{ record.failureReason || '无——本轮通过' }}</div>
                    <div class="k" style="margin-top:10px">评判摘要</div>
                    <div class="v">{{ record.judgeNote || '未配置评判模型，仅按引用命中计分' }}</div>
                  </div>
                  <div class="detail-col">
                    <div class="k">期望引用 · {{ record._expected || 0 }} 条</div>
                    <template v-if="record._expectedRefs?.length">
                      <div v-for="r in record._expectedRefs" :key="r.id" class="src-row">
                        <span class="tag">{{ r.kind === 'doc' ? '文档' : '知识块' }}</span>{{ refTitle(r.id) }}
                        <span class="miss" v-if="!record._hitIds.includes(r.id)">未命中</span>
                        <span class="hit" v-else>已命中</span>
                      </div>
                    </template>
                    <div v-else class="v muted">该用例没有设置期望引用</div>
                    <div class="k" style="margin-top:10px">实际引用 · {{ record._actualRefs.length }} 条</div>
                    <div v-if="record._actualRefs.length">
                      <div v-for="(r, i) in record._actualRefs" :key="i" class="src-row"><span class="tag">来源</span>{{ r }}</div>
                    </div>
                    <div v-else class="v muted">本轮没有引用任何知识库内容</div>
                  </div>
                </div>
              </template>
            </a-table>
          </div>

          <!-- 历史运行 -->
          <div v-if="innerTab === 'history'" class="app-card tbl-card">
            <a-table size="small" :data-source="runsRows" :pagination="{ total: runsTotal, pageSize: runsSize, current: runsPage, size: 'small', onChange: p => { runsPage = p; loadRuns() } }"
                     row-key="id" :locale="{ emptyText: '还没有运行历史' }">
              <a-table-column title="版本" :width="80" key="v">
                <template #default="{ record }"><span>v{{ record.agentVersion ?? '—' }}</span></template>
              </a-table-column>
              <a-table-column title="触发" :width="90" key="trigger">
                <template #default="{ record }"><span class="muted">{{ triggerText(record.trigger) }}</span></template>
              </a-table-column>
              <a-table-column title="通过" :width="110" key="pass">
                <template #default="{ record }">
                  <span v-if="record.status === 'succeeded'">{{ record.passed ?? '—' }}/{{ record.total ?? '—' }}</span>
                  <span v-else class="muted">{{ runStatusText(record.status) }}</span>
                </template>
              </a-table-column>
              <a-table-column title="通过率" :width="100" key="rate">
                <template #default="{ record }">
                  <span v-if="record.passRate != null" :class="record.passRate >= (suiteInfo?.suite?.thresholdRate ?? 80) ? 'cell-good' : 'cell-bad'">{{ pct(record.passRate) }}</span>
                  <span v-else class="muted">—</span>
                </template>
              </a-table-column>
              <a-table-column title="平均分" :width="90" key="score">
                <template #default="{ record }"><span>{{ num1(record.avgScore) }}</span></template>
              </a-table-column>
              <a-table-column title="完成时间" :width="150" key="time">
                <template #default="{ record }"><span class="muted">{{ shortTime(record.finishedAt || record.startedAt) }}</span></template>
              </a-table-column>
              <a-table-column title="操作" :width="130" key="ops">
                <template #default="{ record }">
                  <a v-if="record.status === 'succeeded' || record.resultsKept" class="link" @click="viewRun(record.id)">查看结果</a>
                  <a v-if="record.status === 'running'" class="link" @click="doCancelRun(record.id)">取消</a>
                </template>
              </a-table-column>
            </a-table>
          </div>

          <!-- 评测集 -->
          <div v-if="innerTab === 'suite'" class="suite-wrap">
            <div class="app-card suite-set">
              <div class="ss-title">套件设置</div>
              <div class="ss-form">
                <label>通过率门槛(%) <a-input-number v-model:value="suiteForm.thresholdRate" :min="0" :max="100" size="small" /></label>
                <label>单条通过分 <a-input-number v-model:value="suiteForm.passScore" :min="0" :max="100" size="small" /></label>
                <a-button size="small" :disabled="!suiteInfo?.suite" @click="saveSuiteSettings">保存设置</a-button>
                <span class="sp"></span>
                <template v-if="suiteInfo?.suite">
                  <a-input-number v-model:value="sampleCount" :min="1" :max="50" size="small" style="width:70px" />
                  <a-checkbox v-model:checked="sampleOnlyBad" style="font-size:12px">只要差评</a-checkbox>
                  <a-button size="small" @click="doSample" :loading="sampling">从会话采样</a-button>
                  <a-button size="small" @click="openCaseEditor()">+ 新增用例</a-button>
                </template>
              </div>
              <div v-if="!suiteInfo?.suite" class="ss-empty">
                还没有评测集。<a class="link" @click="doCreateSuite">创建评测集</a>（门槛默认 {{ suiteForm.thresholdRate }}%），然后「从会话采样」或手动加用例。
              </div>
            </div>
            <div class="app-card tbl-card">
              <a-table size="small" :data-source="suiteInfo?.cases || []" :pagination="suiteInfo?.cases?.length > 10 ? { pageSize: 10, size: 'small' } : false"
                       row-key="id" :locale="{ emptyText: '评测集为空——从会话采样一键生成，或手动新增' }">
                <a-table-column title="用例" key="q" :ellipsis="true">
                  <template #default="{ record }"><span class="q">{{ record.question }}</span></template>
                </a-table-column>
                <a-table-column title="期望引用" :width="90" key="exp">
                  <template #default="{ record }">{{ expectedCount(record) || '—' }}</template>
                </a-table-column>
                <a-table-column title="来源" :width="96" key="src">
                  <template #default="{ record }"><span class="muted">{{ sourceText(record.source) }}</span></template>
                </a-table-column>
                <a-table-column title="状态" :width="76" key="en">
                  <template #default="{ record }">
                    <span class="pill mini" :class="record.enabled === 1 ? 'ok' : 'muted'">{{ record.enabled === 1 ? '启用' : '停用' }}</span>
                  </template>
                </a-table-column>
                <a-table-column title="操作" :width="130" key="ops">
                  <template #default="{ record }">
                    <a class="link" @click="openCaseEditor(record)">编辑</a>
                    <a class="link" @click="toggleCase(record)">{{ record.enabled === 1 ? '停用' : '启用' }}</a>
                    <a class="link danger" @click="removeCase(record)">删除</a>
                  </template>
                </a-table-column>
              </a-table>
            </div>
          </div>
        </a-spin>
      </div>
    </template>

    <!-- ==================== 弹窗 ==================== -->
    <a-modal v-model:open="runConfirmOpen" :width="480" title="运行回归" ok-text="开始回归" :confirm-loading="starting"
             @ok="doStartRun" @cancel="runConfirmOpen = false">
      <div class="rc-body" v-if="runTarget">
        <div class="rc-line">智能体：<b>{{ runTarget.agentName }}</b> · 用例 <b>{{ runTarget.caseCount }}</b> 条</div>
        <div class="rc-line">预计模型调用：<b>{{ runTarget.caseCount * 2 }}</b> 次（每条 = 1 次作答 + {{ hasJudge ? 1 : 0 }} 次评判{{ hasJudge ? '' : '，未配置评判模型' }}）+ 每条 1 次检索</div>
        <div class="rc-line">预计耗时：约 {{ runMinutes }} 分钟；评测默认<b>只读</b>，不会执行需要人工确认的工具。</div>
        <div class="rc-line muted">费用按模型单价计入「使用统计 · 智能体评测 / 对话问答」，可在那里核对。</div>
      </div>
    </a-modal>

    <a-modal v-model:open="caseEditorOpen" :width="560" :title="caseForm.id ? '编辑用例' : '新增用例'" ok-text="保存"
             :confirm-loading="caseSaving" @ok="saveCase" @cancel="caseEditorOpen = false">
      <div class="case-form">
        <div class="cf-row"><label>问题（一题一轮）</label>
          <a-textarea v-model:value="caseForm.question" :rows="2" :maxlength="2000" placeholder="例如：违约责任条款是否缺失？" /></div>
        <div class="cf-row"><label>期望引用 · 知识块 ID（每行一个，可留空；「会话采样」会自动填好）</label>
          <a-textarea v-model:value="caseForm.kbIds" :rows="3" placeholder="知识块 ID 列表" /></div>
        <div class="cf-row"><label>期望引用 · 文档 ID（每行一个，可留空）</label>
          <a-textarea v-model:value="caseForm.docIds" :rows="2" placeholder="文档 ID 列表" /></div>
        <div class="cf-row two">
          <div><label>备注 / 考察点</label><a-input v-model:value="caseForm.note" :maxlength="500" /></div>
          <div><label>启用</label><a-switch v-model:checked="caseForm.enabled" size="small" /></div>
        </div>
      </div>
    </a-modal>

    <a-modal v-model:open="createSuiteOpen" :width="420" title="为智能体建立评测集" :footer="null">
      <div class="pick-list">
        <div v-if="!unevaluatedAgents.length" class="empty-line">所有可见智能体都已建立评测集</div>
        <div v-for="a in unevaluatedAgents" :key="a.agentId" class="pick-row" @click="openAgent(a.agentId, 'suite')">
          <AgentAvatar :agent="{ icon: a.agentIcon, isBuiltin: a.isBuiltin }" :size="22" />
          <span class="agent-name">{{ a.agentName }}</span>
          <span class="sp"></span>
          <right-outlined class="ic" />
        </div>
      </div>
    </a-modal>

    <EvalCompareModal v-model:open="compareOpen" :agent-id="currentAgentId" :runs="runsRows" :initial-b="detail?.run?.id" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { SearchOutlined, PlusOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons-vue'
import EvalCompareModal from '../components/agent/EvalCompareModal.vue'
import AgentAvatar from '../components/AgentAvatar.vue'
import {
  agentEvalSummary, agentEvalSuite, agentEvalSaveSuite, agentEvalMutateCases, agentEvalGenerateCases,
  agentEvalStartRun, agentEvalRuns, agentEvalRun, agentEvalCancelRun, agentEvalGate
} from '../api'

const route = useRoute()
const router = useRouter()

// ==================== 总览 ====================
const s = ref({})
const loadingSummary = ref(false)
const kw = ref('')
const helpOpen = ref(false)
const createSuiteOpen = ref(false)
let summaryTimer = null

const loadSummary = async () => {
  loadingSummary.value = s.value.agents == null
  try {
    const r = await agentEvalSummary()
    if (r.success) s.value = r.data || {}
  } catch (e) { /* 静默：总览拉不到保持现状 */ }
  finally { loadingSummary.value = false }
  scheduleSummaryPoll()
}

/** 任何智能体在跑就 3s 轮询总览（运行中行显示进度），全空闲即停 */
function scheduleSummaryPoll () {
  const running = (s.value.agents || []).some(a => a.gate?.gate === 'running')
  if (running && !summaryTimer) {
    summaryTimer = setInterval(async () => {
      try {
        const r = await agentEvalSummary()
        if (r.success) {
          s.value = r.data || {}
          if (!(s.value.agents || []).some(a => a.gate?.gate === 'running') && summaryTimer) {
            clearInterval(summaryTimer); summaryTimer = null
            message.success('评测运行已结束')
          }
        }
      } catch (e) { /* 单次失败下次再试 */ }
    }, 3000)
  }
}

const filteredRows = computed(() => {
  const rows = s.value.agents || []
  const k = kw.value.trim().toLowerCase()
  return k ? rows.filter(r => String(r.agentName || '').toLowerCase().includes(k)) : rows
})
const unevaluatedAgents = computed(() => (s.value.agents || []).filter(a => a.gate?.gate === 'none'))
const belowNames = computed(() => {
  const names = (s.value.agents || []).filter(a => a.gate?.gate === 'below').map(a => a.agentName)
  return names.length ? names.slice(0, 2).join('、') + (names.length > 2 ? ' 等' : '') : ''
})

// ==================== 详情 ====================
const currentAgentId = ref('')
const agentRow = ref(null)
const gate = ref(null)
const detail = ref(null)
const suiteInfo = ref(null)
const runsRows = ref([])
const runsTotal = ref(0)
const runsPage = ref(1)
const runsSize = ref(20)
const loadingDetail = ref(false)
const starting = ref(false)
const innerTab = ref('results')
const failOnly = ref(false)
const suiteForm = ref({ thresholdRate: 80, passScore: 60 })
const sampleCount = ref(10)
const sampleOnlyBad = ref(false)
const sampling = ref(false)
const caseEditorOpen = ref(false)
const caseSaving = ref(false)
const caseForm = ref({})
const runConfirmOpen = ref(false)
const runTarget = ref(null)
const compareOpen = ref(false)
let detailTimer = null

function openAgent (id, tab) {
  currentAgentId.value = id
  innerTab.value = tab === 'suite' ? 'suite' : 'results'
  router.replace({ query: { ...route.query, agent: id } })
  agentRow.value = (s.value.agents || []).find(a => a.agentId === id) || null
  loadDetail()
}

function closeAgent () {
  currentAgentId.value = ''
  detail.value = null
  suiteInfo.value = null
  gate.value = null
  const q = { ...route.query }
  delete q.agent
  router.replace({ query: q })
  loadSummary()
}

/** 从路由还原详情态（AgentsPage「评测」按钮 / 刷新走这里） */
watch(() => route.query.agent, v => {
  const id = v ? String(v) : ''
  if (id && id !== currentAgentId.value) openAgent(id)
  else if (!id && currentAgentId.value) { currentAgentId.value = ''; detail.value = null }
})

async function loadDetail () {
  if (!currentAgentId.value) return
  loadingDetail.value = detail.value == null
  const id = currentAgentId.value
  try {
    const [g, suiteR, runsR] = await Promise.all([
      agentEvalGate(id), agentEvalSuite(id), agentEvalRuns(id, runsPage.value, runsSize.value)
    ])
    if (g.success) gate.value = g.data
    if (suiteR.success) {
      suiteInfo.value = suiteR.data
      if (suiteInfo.value?.suite) {
        suiteForm.value.thresholdRate = suiteInfo.value.suite.thresholdRate ?? 80
        suiteForm.value.passScore = suiteInfo.value.suite.passScore ?? 60
      }
    }
    if (runsR.success) {
      runsRows.value = runsR.data?.rows || []
      runsTotal.value = runsR.data?.total || 0
    }
    // 本次结果：优先在跑的运行，否则最近一次成功运行
    const runningId = gate.value?.runningRun?.id
    const targetId = runningId || runsRows.value.find(r => r.status === 'succeeded')?.id
    if (targetId) await viewRun(targetId, true)
    else detail.value = null
    if (runningId) scheduleDetailPoll(runningId)
    // 补一次 agentRow（可能从深链直接进来，总览还没加载）
    if (!agentRow.value && s.value.agents?.length) agentRow.value = s.value.agents.find(a => a.agentId === id) || null
  } catch (e) { message.error(e.message || '加载评测详情失败') }
  finally { loadingDetail.value = false }
}

async function viewRun (runId, silent) {
  try {
    const r = await agentEvalRun(runId)
    if (r.success) {
      detail.value = r.data
      enrichResults()
      if (!silent) {
        // 历史运行页点「查看结果」：切到本次结果页签，否则数据换了用户却看不见（真机踩过：点了没反应）
        innerTab.value = 'results'
        failOnly.value = false
      }
      if (r.data?.running) scheduleDetailPoll(runId)
    } else if (!silent) message.error(r.msg || '加载运行详情失败')
  } catch (e) { if (!silent) message.error(e.message || '加载运行详情失败') }
}

/** 运行中 3s 轮询；结束那一刻补拉历史/套件/总览（徽标与计数回位） */
function scheduleDetailPoll (runId) {
  if (detailTimer) clearInterval(detailTimer)
  detailTimer = setInterval(async () => {
    try {
      const r = await agentEvalRun(runId)
      if (!r.success) return
      detail.value = r.data
      enrichResults()
      if (!r.data?.running) {
        if (detailTimer) { clearInterval(detailTimer); detailTimer = null }
        loadRuns(); agentEvalSuite(currentAgentId.value).then(x => { if (x.success) suiteInfo.value = x.data }).catch(() => {})
        // 门禁态一并回刷：页头/门禁卡的「运行中」要落回达标/低于门槛
        agentEvalGate(currentAgentId.value).then(x => { if (x.success) gate.value = x.data }).catch(() => {})
        loadSummary()
        message.success('评测运行已结束：通过率 ' + pct(r.data?.run?.passRate))
      }
    } catch (e) { /* 单次失败下次再试 */ }
  }, 3000)
}

function switchTab (t) {
  innerTab.value = t
  if (t === 'history') loadRuns()
}

async function loadRuns () {
  if (!currentAgentId.value) return
  try {
    const r = await agentEvalRuns(currentAgentId.value, runsPage.value, runsSize.value)
    if (r.success) { runsRows.value = r.data?.rows || []; runsTotal.value = r.data?.total || 0 }
  } catch (e) { /* 静默 */ }
}

// ==================== 运行 ====================
function confirmRunOf (row) {
  const target = row || agentRow.value
  if (!target) return
  if (target.gate?.gate === 'none' || !target.caseCount) {
    message.warning('先建立评测集并添加用例，再运行回归')
    openAgent(target.agentId, 'suite')
    return
  }
  runTarget.value = target
  runConfirmOpen.value = true
}

const hasJudge = computed(() => !!detail.value?.run?.judgeModel || !!gate.value?.judgeConfigured)
const runMinutes = computed(() => Math.max(1, Math.ceil((runTarget.value?.caseCount || 0) * 3.4 / 60)))

async function doStartRun () {
  if (!currentAgentId.value && runTarget.value) openAgent(runTarget.value.agentId)
  starting.value = true
  try {
    const r = await agentEvalStartRun(currentAgentId.value, { trigger: 'manual' })
    if (r.success) {
      message.success('回归已启动')
      runConfirmOpen.value = false
      innerTab.value = 'results'
      await loadDetail()
      if (r.data?.id) scheduleDetailPoll(r.data.id)
    } else { message.error(r.msg || '启动失败'); runConfirmOpen.value = false }
  } catch (e) { message.error(e.message || '启动失败'); runConfirmOpen.value = false }
  finally { starting.value = false }
}

async function doCancelRun (runId) {
  try {
    const r = await agentEvalCancelRun(runId)
    if (r.success) { message.success('已请求取消，等在跑的那条结束后收口'); loadDetail(); loadSummary() }
    else message.error(r.msg || '取消失败')
  } catch (e) { message.error(e.message || '取消失败') }
}
const cancelRunOf = row => doCancelRun(row.gate?.runningRun?.id)

// ==================== 套件与用例 ====================
async function doCreateSuite () {
  try {
    const r = await agentEvalSaveSuite(currentAgentId.value, {
      thresholdRate: suiteForm.value.thresholdRate, passScore: suiteForm.value.passScore
    })
    if (r.success) { message.success('评测集已创建'); await loadDetail() }
    else message.error(r.msg || '创建失败')
  } catch (e) { message.error(e.message || '创建失败') }
}

async function saveSuiteSettings () {
  try {
    const r = await agentEvalSaveSuite(currentAgentId.value, {
      thresholdRate: suiteForm.value.thresholdRate, passScore: suiteForm.value.passScore
    })
    if (r.success) message.success('套件设置已保存')
    else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
}

async function doSample () {
  sampling.value = true
  try {
    const r = await agentEvalGenerateCases(currentAgentId.value, { count: sampleCount.value, onlyBad: sampleOnlyBad.value })
    if (r.success) {
      message.success(`已采样 ${r.data?.added ?? 0} 条用例（请求 ${r.data?.requested ?? '—'} 条）`)
      const x = await agentEvalSuite(currentAgentId.value)
      if (x.success) suiteInfo.value = x.data
      loadSummary()
    } else message.error(r.msg || '采样失败')
  } catch (e) { message.error(e.message || '采样失败') }
  finally { sampling.value = false }
}

function openCaseEditor (c) {
  if (!suiteInfo.value?.suite) { message.warning('先创建评测集'); return }
  caseForm.value = c ? {
    id: c.id, question: c.question, note: c.note || '',
    kbIds: idsToText(c.expectedKnowledgeIds), docIds: idsToText(c.expectedDocIds),
    enabled: c.enabled === 1
  } : { id: '', question: '', note: '', kbIds: '', docIds: '', enabled: true }
  caseEditorOpen.value = true
}

async function saveCase () {
  const f = caseForm.value
  if (!String(f.question || '').trim()) { message.warning('问题不能为空'); return }
  caseSaving.value = true
  const body = f.id
    ? { update: [{ id: f.id, question: f.question.trim(), note: f.note, tags: [], enabled: f.enabled ? 1 : 0,
      expectedKnowledgeIds: textToIds(f.kbIds), expectedDocIds: textToIds(f.docIds) }] }
    : { add: [{ question: f.question.trim(), note: f.note || '', tags: [], source: 'manual', enabled: f.enabled ? 1 : 0,
      expectedKnowledgeIds: textToIds(f.kbIds), expectedDocIds: textToIds(f.docIds) }] }
  try {
    const r = await agentEvalMutateCases(currentAgentId.value, body)
    if (r.success) {
      message.success(f.id ? '用例已更新' : '用例已添加')
      caseEditorOpen.value = false
      const x = await agentEvalSuite(currentAgentId.value)
      if (x.success) suiteInfo.value = x.data
      loadSummary()
    } else message.error(r.msg || '保存失败')
  } catch (e) { message.error(e.message || '保存失败') }
  finally { caseSaving.value = false }
}

async function toggleCase (c) {
  try {
    const r = await agentEvalMutateCases(currentAgentId.value, { update: [{ id: c.id, enabled: c.enabled === 1 ? 0 : 1 }] })
    if (r.success) { c.enabled = c.enabled === 1 ? 0 : 1; loadSummary() }
    else message.error(r.msg || '操作失败')
  } catch (e) { message.error(e.message || '操作失败') }
}

async function removeCase (c) {
  try {
    const r = await agentEvalMutateCases(currentAgentId.value, { delete: [c.id] })
    if (r.success) {
      message.success('用例已删除')
      const x = await agentEvalSuite(currentAgentId.value)
      if (x.success) suiteInfo.value = x.data
      loadSummary()
    } else message.error(r.msg || '删除失败')
  } catch (e) { message.error(e.message || '删除失败') }
}

// ==================== 派生 ====================
const succeededRuns = computed(() => runsRows.value.filter(r => r.status === 'succeeded'))
const failCount = computed(() => (detail.value?.results || []).filter(r => r.passed !== 1).length)
const resultRows = computed(() => {
  const rows = detail.value?.results || []
  return failOnly.value ? rows.filter(r => r.passed !== 1) : rows
})
const judgeShort = computed(() => {
  const m = detail.value?.run?.judgeModel || ''
  return m.length > 18 ? m.slice(0, 18) + '…' : m
})
/** 结果行预计算：期望/命中条数、耗时、期望引用对照（展开卡直接读） */
function enrichResults () {
  const cases = new Map((suiteInfo.value?.cases || []).map(c => [c.id, c]))
  for (const r of (detail.value?.results || [])) {
    const c = cases.get(r.caseId)
    const expKb = idsOf(c?.expectedKnowledgeIds)
    const expDoc = idsOf(c?.expectedDocIds)
    r._expected = expKb.length + expDoc.length
    r._hit = r.recall == null ? 0 : Math.round(r.recall * r._expected)
    r._secs = r.latencyMs != null ? (Math.round(r.latencyMs / 100) / 10) + 's' : '—'
    r._expectedRefs = [...expKb.map(id => ({ id, kind: 'kb' })), ...expDoc.map(id => ({ id, kind: 'doc' }))]
    const srcKb = srcIds(r.sources, 'knowledgeId').concat(srcIds(r.sources, 'docId'))
    r._hitIds = srcKb
    r._actualRefs = srcTitles(r.sources)
  }
}
const refTitle = id => detail.value?.refTitles?.[id] || id
/** 趋势图比例尺：动态 y 域（数据上下各让 8 分收进 0-100；两点同值保底 ±5 分），点少时 x 内收别顶到两端 */
const trendScale = computed(() => {
  const runs = succeededRuns.value.slice(0, 6).reverse()
  const rates = runs.map(r => Math.min(100, Math.max(0, r.passRate ?? 0)))
  const lo = rates.length ? Math.min(...rates) : 0
  const hi = rates.length ? Math.max(...rates) : 100
  let yMin = Math.max(0, Math.floor(lo - 8))
  let yMax = Math.min(100, Math.ceil(hi + 8))
  if (yMax - yMin < 10) {
    const mid = (yMax + yMin) / 2
    yMin = Math.max(0, Math.round(mid - 5))
    yMax = Math.min(100, Math.round(mid + 5))
  }
  // 域边界吸附 5 的倍数：平线在 0% 时出 0%/5%/10% 整刻度，不出 9% 这类怪数
  yMin = Math.max(0, Math.floor(yMin / 5) * 5)
  yMax = Math.min(100, Math.ceil(yMax / 5) * 5)
  if (yMax <= yMin) yMax = Math.min(100, yMin + 5)
  const n = runs.length
  const xPad = n <= 2 ? 280 : n <= 4 ? 160 : 80
  const yOf = v => Math.round(4 + 92 * (1 - (v - yMin) / Math.max(1, yMax - yMin)))
  const points = runs.map((r, i) => {
    const y = yOf(Math.min(100, Math.max(0, r.passRate ?? 0)))
    return {
      x: n === 1 ? 560 : Math.round(xPad + (1120 - 2 * xPad) * i / (n - 1)),
      y,
      // 标签避开贴底：点靠近底部时标签放点上方，否则放下方（都留出与点的间隙）
      labelY: y >= 82 ? y - 12 : Math.min(110, y + 18),
      ok: (r.passRate ?? 0) >= (suiteInfo.value?.suite?.thresholdRate ?? 80),
      label: `v${r.agentVersion ?? '?'} · ${pct(r.passRate)} · ${shortTime(r.finishedAt)}`,
      labelShort: `v${r.agentVersion ?? '?'} · ${pct(r.passRate)}`
    }
  })
  // 三条网格线取域顶/域中/域底，标签用真实刻度值；靠得太近的标签去重（≥14px 才画）
  const tickRaw = [
    { y: yOf(yMax), label: pct(yMax) },
    { y: yOf(Math.round((yMax + yMin) / 2)), label: pct(Math.round((yMax + yMin) / 2)) },
    { y: yOf(yMin), label: pct(yMin) }
  ].sort((a, b) => a.y - b.y)
  const ticks = []
  for (const t of tickRaw) {
    if (t.y < 8 || t.y > 106) continue
    if (ticks.length && t.y - ticks[ticks.length - 1].y < 14) continue
    ticks.push(t)
  }
  // 全平（跨度 <2pp）不画面积：满宽条带看着像渲染 bug，只留点与连线
  const flat = hi - lo < 2
  return { points, ticks, line: points.map(p => `${p.x},${p.y}`).join(' '),
           area: (!flat && points.length) ? points.map(p => `${p.x},${p.y}`).join(' ') + ` ${points[points.length - 1].x},112 ${points[0].x},112` : '' }
})
const trendPoints = computed(() => trendScale.value.points)
const trendTicks = computed(() => trendScale.value.ticks)
const detailTrendLine = computed(() => trendScale.value.line)
const detailTrendArea = computed(() => trendScale.value.area)
const sparkLine = computed(() => {
  const pts = s.value.trend || []
  const vals = pts.map(p => p.value)
  return pts.map((p, i) => {
    const x = pts.length === 1 ? 200 : Math.round(400 * i / (pts.length - 1))
    const v = vals[i] == null ? (vals.filter(x2 => x2 != null).slice(-1)[0] ?? 0) : vals[i]
    const y = Math.round(40 - (Math.min(100, Math.max(0, v)) / 100) * 36)
    return `${x},${y}`
  }).join(' ')
})
const sparkArea = computed(() => sparkLine.value ? sparkLine.value + ' 400,42 0,42' : '')
const dimAvg = key => {
  const vals = (detail.value?.results || []).map(r => r[key]).filter(v => v != null)
  return vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null
}
const avgOf = key => dimAvg(key)

// ==================== 展示工具 ====================
const pct = v => (v == null ? '—' : (Math.round(v) === v ? v.toFixed(0) : v.toFixed(1)) + '%')
const num0 = v => (v == null ? '—' : String(Math.round(v)))
const num1 = v => (v == null ? '—' : String(Math.round(v * 10) / 10))
const signed = v => (v == null ? '—' : (v > 0 ? '+' : '') + (Math.round(v * 10) / 10))
const secs = v => (v == null ? '—' : v >= 60000 ? Math.round(v / 6000) / 100 + 'm' : (Math.round(v / 100) / 10) + 's')
const tokens = v => (v == null ? '—' : v >= 1000 ? Math.round(v / 100) / 10 + 'k' : String(v))
const shortTime = t => (t ? String(t).replace('T', ' ').slice(5, 16) : '—')
const dimClass = v => (v == null ? 'muted' : v >= 80 ? 'cell-good' : v >= 60 ? '' : 'cell-bad')
const scoreTone = v => (v == null ? '' : v >= 80 ? 'good' : v >= 60 ? '' : 'bad')
const rateClass = r => (r.lastRun.passRate >= (r.gate?.thresholdRate ?? 80) ? 'cell-strong' : 'cell-bad')
const barClass = (rate, th) => (rate >= (th ?? 80) ? '' : rate >= (th ?? 80) * 0.9 ? 'mid' : 'low')
const runProgress = r => {
  const rr = r.gate?.runningRun || r.runningRun
  if (!rr?.total) return 0
  return Math.min(100, Math.round(((rr.done ?? 0) / rr.total) * 100))
}
const runProgressText = r => {
  const rr = r.gate?.runningRun || r.runningRun
  return rr ? `${rr.done ?? 0}/${rr.total}` : ''
}
const runGate = r => (r.status === 'succeeded' ? ((r.passRate ?? 0) >= 80 ? 'pass' : 'below') : r.status === 'running' ? 'running' : 'unevaluated')
const runStatusText = st => ({ succeeded: '成功', failed: '失败', cancelled: '已取消', running: '运行中' }[st] || st)
const triggerText = t => ({ manual: '手动', gate: '保存门禁', scheduled: '定时' }[t] || t)
const sourceText = t => ({ manual: '手动新增', session: '会话采样', import: '导入' }[t] || t)
const GATE_TEXT = { none: '未评测', unevaluated: '未评测', changed: '有变更未评测', running: '运行中', pass: '达标', below: '低于门槛' }
const gateText = g => GATE_TEXT[g] || '—'
const GATE_CLASS = { none: 'muted', unevaluated: 'muted', changed: 'warn', running: 'blue', pass: 'ok', below: 'err' }
const gateClass = g => GATE_CLASS[g] || 'muted'
const gateCardClass = g => ({ pass: 'ok', below: '', changed: 'warn', unevaluated: 'warn', running: 'blue' }[g] || '')
const gateCardText = g => ({ pass: '保存前不会被提示', below: '保存前会被提示', changed: '保存前会被提示', unevaluated: '跑一次就有门禁基线', running: '回归进行中', none: '先建立评测集' }[g] || '')
function expectedCount (c) {
  return idsOf(c.expectedKnowledgeIds).length + idsOf(c.expectedDocIds).length
}
function idsToText (json) { return idsOf(json).join('\n') }
function textToIds (t) {
  return String(t || '').split(/[\n,，;；]/).map(x => x.trim()).filter(Boolean)
}
function idsOf (json) {
  if (!json) return []
  try { const a = JSON.parse(json); return Array.isArray(a) ? a.filter(Boolean) : [] } catch (e) { return [] }
}
function srcIds (json, key) {
  try {
    const a = typeof json === 'string' ? JSON.parse(json) : json
    if (!Array.isArray(a)) return []
    return a.map(x => x?.[key]).filter(Boolean)
  } catch (e) { return [] }
}
function srcTitles (json) {
  try {
    const a = typeof json === 'string' ? JSON.parse(json) : json
    if (!Array.isArray(a)) return []
    return a.map(x => x?.title || x?.fileName || '未命名来源').slice(0, 10)
  } catch (e) { return [] }
}
function focusFirstRunningOrFirst () {
  const running = (s.value.agents || []).find(a => a.gate?.gate === 'running')
  const first = running || (s.value.agents || [])[0]
  if (first) openAgent(first.agentId, 'history')
}

// ==================== 生命周期 ====================
onMounted(async () => {
  await loadSummary()
  const qid = route.query.agent ? String(route.query.agent) : ''
  if (qid) openAgent(qid)
})
onUnmounted(() => {
  if (summaryTimer) clearInterval(summaryTimer)
  if (detailTimer) clearInterval(detailTimer)
})
</script>

<style scoped>
.ae-page { min-height: 0; }
.head-hint { font-size: 12px; color: var(--app-text3); flex: 1 1 auto; min-width: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kw { width: 180px; }
.kw .ic { color: var(--app-text3); }
.app-page-body { padding: 14px 20px 20px; overflow-y: auto; }

/* ---- 统计卡 ---- */
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 12px; }
.stat { height: 76px; padding: 12px 16px; display: flex; flex-direction: column; justify-content: center; gap: 4px; }
.stat .lab { font-size: 11px; color: var(--app-text3); }
.stat .val { font-size: 20px; font-weight: 600; letter-spacing: -.01em; color: var(--app-text); }
.stat .val em { font-style: normal; font-size: 12px; font-weight: 500; color: var(--app-text3); margin-left: 4px; }
.stat .val.danger { color: var(--app-danger); }
.stat .sub { font-size: 11px; color: var(--app-text3); }
.stat .sub.good { color: var(--app-ok); }
.stat .sub.bad { color: var(--app-danger); }

/* ---- 质量概况条 ---- */
.quality-strip { height: 92px; display: flex; align-items: stretch; margin-bottom: 12px; overflow: hidden; padding: 0; }
.qs-cell { display: flex; align-items: center; gap: 16px; padding: 0 18px; }
.qs-lab { font-size: 11px; color: var(--app-text3); margin-bottom: 7px; }
.qs-items { display: flex; align-items: center; gap: 16px; }
.qs-item { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.qs-item b { font-size: 14px; font-weight: 600; color: var(--app-text); }
.qs-trend { flex: 1; min-width: 0; padding: 14px 18px 0; display: flex; flex-direction: column; }
.qs-trend .spark { width: 100%; height: 42px; display: block; }
.qs-note { width: 270px; flex: none; display: flex; flex-direction: column; justify-content: center; gap: 4px;
  padding: 0 18px; border-left: 1px solid var(--app-border); }
.qs-note .t { font-size: 12px; color: var(--app-text2); }
.qs-note .t b { color: var(--app-text); font-weight: 600; }
.qs-note .s { font-size: 11px; color: var(--app-text3); }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dot.ok { background: var(--app-ok); }
.dot.warn { background: var(--app-warn); }
.dot.err { background: var(--app-danger); }
.dot.grey { background: #c3c9d1; }
.dot.blue { background: var(--app-accent); }

/* ---- 表格卡（AuditLogPage 同款紧凑规格） ---- */
.tbl-card { overflow: hidden; }
.tbl-card :deep(.ant-table) { font-size: 11.5px; background: transparent; }
.tbl-card :deep(.ant-table-container) { border: none; }
.tbl-card :deep(.ant-table-thead > tr > th) {
  background: var(--app-panel-2, #fafbfc); color: var(--app-text3);
  font-size: 11px; font-weight: 500; padding: 8px 10px;
  border-bottom: 1px solid var(--app-border); white-space: nowrap;
}
.tbl-card :deep(.ant-table-thead > tr > th::before) { display: none; }
.tbl-card :deep(.ant-table-tbody > tr > td) {
  padding: 8px 10px; border-bottom: 1px solid #eef1f4; vertical-align: middle; }
.tbl-card :deep(.ant-table-thead > tr > th:first-child),
.tbl-card :deep(.ant-table-tbody > tr > td:first-child) { padding-left: 14px; }
.tbl-card :deep(.ant-table-thead > tr > th:last-child),
.tbl-card :deep(.ant-table-tbody > tr > td:last-child) { padding-right: 14px; }
.tbl-card :deep(.ant-table-tbody > tr:hover > td) { background: var(--app-accent-weak, #eef2f7); }
.tbl-card :deep(.ant-table-tbody > tr:last-child > td) { border-bottom: none; }
.tbl-card :deep(.ant-table-tbody > tr.fail > td:first-child) { box-shadow: inset 3px 0 0 var(--app-danger); }
.tbl-card :deep(.ant-table-tbody > tr.fail > td) { background: #fffbfa; }

.agent-cell { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.agent-ico { width: 22px; height: 22px; border-radius: 6px; background: var(--app-accent-weak); color: var(--app-accent);
  display: inline-flex; align-items: center; justify-content: center; font-size: 12px; flex: none; }
.agent-ico.builtin { background: var(--app-accent-weak); color: var(--app-accent); }
.agent-name { color: var(--app-text); font-weight: 500; font-size: 12px; }
.agent-sub { font-size: 10px; color: var(--app-text3); margin-left: 2px; }
.muted { color: var(--app-text3); }
.cell-strong { color: var(--app-text); font-weight: 600; }
.cell-good { color: var(--app-ok); font-weight: 600; }
.cell-bad { color: var(--app-danger); font-weight: 600; }
.q { color: var(--app-text2); }
.bar { width: 68px; height: 5px; border-radius: 3px; background: #eef1f4; display: inline-block;
  vertical-align: middle; overflow: hidden; margin-right: 7px; }
.bar i { display: block; height: 100%; border-radius: 3px; background: var(--app-ok); }
.bar.low i { background: var(--app-danger); }
.bar.mid i { background: var(--app-warn); }
.bar.blue i { background: var(--app-accent); }
.link { color: var(--app-accent); font-size: 12px; cursor: pointer; text-decoration: none; }
.link + .link { margin-left: 8px; }
.link.danger { color: var(--app-danger); }

.pill { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; line-height: 1;
  padding: 4px 8px; border-radius: 999px; white-space: nowrap; }
.pill::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.pill.plain::before, .pill.mini::before { display: none; }
.pill.ok { color: var(--app-ok); background: var(--app-ok-weak); }
.pill.warn { color: var(--app-warn-text, #a3691b); background: var(--app-warn-weak); }
.pill.err { color: var(--app-danger); background: #fbecea; }
.pill.muted { color: var(--app-text3); background: #f1f3f5; }
.pill.blue { color: var(--app-accent); background: var(--app-accent-weak); }
.pill.mini { font-size: 10px; padding: 3px 7px; }

/* ---- 底部两栏 ---- */
.bottom-grid { display: grid; grid-template-columns: 1.25fr 1fr; gap: 12px; margin-top: 12px; }
.mini-card { padding: 12px 16px; min-height: 150px; }
.mini-card .h { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
  color: var(--app-text); margin-bottom: 6px; }
.mini-card .h .more { margin-left: auto; font-size: 11px; font-weight: 400; }
.mini-row { display: flex; align-items: center; gap: 8px; height: 34px; border-top: 1px solid #eef1f4;
  font-size: 12px; color: var(--app-text2); }
.mini-row .name { color: var(--app-text); font-weight: 500; width: 170px; flex: none;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mini-row .meta { color: var(--app-text3); font-size: 11px; flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-line { padding: 14px 0; font-size: 12px; color: var(--app-text3); }

.foot-note { display: flex; align-items: center; gap: 8px; padding: 10px 2px 0; font-size: 11px; color: var(--app-text3); }
.foot-note .sp { flex: 1; }
.help-card { margin-top: 10px; padding: 12px 16px; font-size: 12px; color: var(--app-text2); }
.help-card ul { margin: 0; padding-left: 18px; display: grid; gap: 6px; }

/* ---- 详情 ---- */
.crumb { display: flex; align-items: center; gap: 8px; color: var(--app-text3); font-size: 12px;
  padding: 0 20px; height: 40px; background: var(--app-panel); border-bottom: 1px solid var(--app-border); flex: none; }
.crumb .back { color: var(--app-text2); cursor: pointer; display: inline-flex; align-items: center; gap: 4px; }
.crumb .sep { opacity: .6; }
.detail-head { flex: none; height: 68px; display: flex; align-items: center; gap: 12px;
  padding: 0 20px; background: var(--app-panel); border-bottom: 1px solid var(--app-border); }
.big-avatar { width: 38px; height: 38px; border-radius: 10px; background: var(--app-accent-weak); color: var(--app-accent);
  display: inline-flex; align-items: center; justify-content: center; font-size: 18px; flex: none; }
.dh-title { font-size: 15px; font-weight: 600; color: var(--app-text); display: flex; align-items: center; gap: 8px; }
.dh-sub { font-size: 12px; color: var(--app-text3); margin-top: 4px; }
.dh-acts { margin-left: auto; display: flex; align-items: center; gap: 8px; }

.sum-grid { display: grid; grid-template-columns: 220px repeat(3, 1fr) 260px; gap: 12px; margin-bottom: 12px; }
.score-card { padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.score-card .lab { font-size: 11px; color: var(--app-text3); }
.score-card .score { font-size: 34px; font-weight: 650; letter-spacing: -.02em; line-height: 1.15; color: var(--app-text); }
.score-card .score.good { color: var(--app-ok); }
.score-card .score.bad { color: var(--app-danger); }
.score-card .sub { font-size: 11px; color: var(--app-text3); }
.mini-stat { padding: 14px 16px; display: flex; flex-direction: column; justify-content: center; gap: 5px; }
.mini-stat .lab { font-size: 11px; color: var(--app-text3); }
.mini-stat .val { font-size: 18px; font-weight: 600; color: var(--app-text); }
.mini-stat .val.danger { color: var(--app-danger); }
.mini-stat .sub { font-size: 11px; color: var(--app-text3); }
.gate-card { padding: 12px 14px; display: flex; flex-direction: column; gap: 6px; border-left: 3px solid var(--app-danger); }
.gate-card.ok { border-left-color: var(--app-ok); }
.gate-card.warn { border-left-color: var(--app-warn); }
.gate-card.blue { border-left-color: var(--app-accent); }
.gate-card .row1 { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.gate-card .row1t { color: var(--app-text2); font-weight: 500; }
.gate-card .row2 { font-size: 11px; color: var(--app-text3); line-height: 1.55; }
.gate-card .row2 b { color: var(--app-text2); font-weight: 500; }

.trend-card { padding: 12px 16px; margin-bottom: 12px; }
.trend-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 6px; }
.trend-head .t { font-size: 12px; font-weight: 600; color: var(--app-text); }
.trend-head .s { font-size: 11px; color: var(--app-text3); }
.trend-head .sp { flex: 1; }
.legend { display: flex; align-items: center; gap: 14px; font-size: 11px; color: var(--app-text3); }
.legend .dot { margin-right: 4px; }
.trend-svg { width: 100%; height: 96px; display: block; }
.trend-empty { height: 60px; display: flex; align-items: center; justify-content: center;
  font-size: 12px; color: var(--app-text3); }

.inner-tabs { height: 42px; display: flex; align-items: stretch; gap: 24px; padding: 0 4px;
  border-bottom: 1px solid var(--app-border); margin-bottom: 12px; flex: none; }
.inner-tab { position: relative; display: flex; align-items: center; gap: 6px; font-size: 13px;
  color: var(--app-text2); cursor: pointer; user-select: none; }
.inner-tab.on { color: var(--app-text); font-weight: 500; }
.inner-tab.on::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px;
  background: var(--app-accent); border-radius: 1px; }
.cnt { font-size: 10px; line-height: 16px; padding: 0 5px; border-radius: 3px; background: #f1f3f5; color: var(--app-text3); }
.inner-tab.on .cnt { background: var(--app-accent-weak); color: var(--app-accent); }
.inner-tabs .sp { flex: 1; }
.chip { align-self: center; font-size: 11px; padding: 3px 8px; border-radius: 999px; border: 1px solid var(--app-border);
  color: var(--app-text2); background: var(--app-panel); cursor: pointer; user-select: none; }
.chip.on { border-color: var(--app-accent-border, #bcd0f7); color: var(--app-accent); background: var(--app-accent-weak); }

.results-card { flex: 1; min-height: 0; }
.detail-inner { padding: 12px 16px 14px 20px; display: flex; flex-wrap: wrap; gap: 20px 24px;
  background: #fffbfa; border-radius: 6px; }
.detail-col { flex: 1; min-width: 260px; }
.detail-col.grow { flex: 1.2; }
.detail-col .k { font-size: 11px; color: var(--app-text3); margin-bottom: 5px; }
.detail-col .v { font-size: 12px; color: var(--app-text2); line-height: 1.6; }
.v.reason { color: var(--app-danger-text, #cf1322); }
.src-row { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 6px; font-size: 11px; margin-bottom: 5px; color: var(--app-text2); }
.src-row .tag { padding: 1px 5px; border-radius: 3px; background: var(--app-panel-2, #fafbfc);
  border: 1px solid var(--app-border); color: var(--app-text3); flex: none; }
.src-row .miss { color: var(--app-danger); border: 1px solid #f3c9be; background: var(--app-danger-weak);
  padding: 0 4px; border-radius: 3px; }
.src-row .hit { color: var(--app-ok); border: 1px solid var(--app-ok-weak); background: var(--app-ok-weak);
  padding: 0 4px; border-radius: 3px; }

.suite-wrap { display: flex; flex-direction: column; gap: 12px; }
.suite-set { padding: 12px 16px; }
.ss-title { font-size: 12px; font-weight: 600; color: var(--app-text); margin-bottom: 8px; }
.ss-form { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--app-text2); }
.ss-form label { display: inline-flex; align-items: center; gap: 6px; }
.ss-form .sp { flex: 1; }
.ss-empty { margin-top: 10px; font-size: 12px; color: var(--app-text3); }

/* ---- 弹窗内 ---- */
.rc-body { display: grid; gap: 8px; font-size: 12.5px; color: var(--app-text2); }
.rc-line b { color: var(--app-text); }
.rc-line.muted { color: var(--app-text3); font-size: 11.5px; }
.case-form { display: grid; gap: 10px; }
.cf-row label { display: block; font-size: 11px; color: var(--app-text3); margin-bottom: 4px; }
.cf-row.two { display: grid; grid-template-columns: 1fr auto; gap: 14px; align-items: end; }
.pick-list { display: grid; gap: 4px; max-height: 320px; overflow-y: auto; }
.pick-row { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 8px;
  cursor: pointer; font-size: 12px; }
.pick-row:hover { background: var(--app-accent-weak); }
.pick-row .sp { flex: 1; }
.pick-row .ic { color: var(--app-text3); }
</style>
