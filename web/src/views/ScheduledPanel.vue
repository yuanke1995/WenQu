<template>
  <div class="app-page sched-page">
    <div class="app-page-head">
      <span class="head-stats">
        共 <b>{{ stats.total }}</b> 个任务 · 启用 <b>{{ stats.enabled }}</b>
        · 今日已跑 <b>{{ stats.todayRuns }}</b><span class="slash">/</span>待跑 <b>{{ stats.todayDue }}</b>
        · 近 7 天 <b>{{ stats.runs7d }}</b> 次 · 成功率 <b>{{ rateText }}</b>
      </span>
      <span v-if="attentionCount" class="attn-chip" role="button" tabindex="0"
            @click="setFilter('attention')" @keydown.enter="setFilter('attention')">
        <warning-outlined /> {{ attentionCount }} 个需关注
      </span>
      <i class="vline" />
      <span class="head-hint">到点自动执行，结果写进任务专属会话；停用或归档不会补跑</span>
      <div class="head-r">
        <a-input v-model:value="keyword" class="sched-search" size="small" allow-clear
                 placeholder="搜索名称或指令">
          <template #prefix><search-outlined /></template>
        </a-input>
        <a-tooltip title="刷新">
          <button class="app-icon-btn" :disabled="loading" @click="load()"><reload-outlined /></button>
        </a-tooltip>
        <button class="app-btn" @click="openCreate()"><plus-outlined /> 新建任务</button>
        <div v-if="rows.length" class="batch-group">
          <button class="app-btn ghost small" :class="{ 'batch-on': batchMode }" @click="toggleBatchMode">
            {{ batchMode ? '退出管理' : '批量管理' }}
          </button>
        </div>
      </div>
    </div>

    <div class="filter-row">
      <button v-for="f in filters" :key="f.key" class="fchip" :class="{ on: statusFilter === f.key, warn: f.warn }"
              @click="setFilter(f.key)">
        {{ f.label }} <b>{{ f.count }}</b>
      </button>
      <a-dropdown :trigger="['click']" class="sort-dd">
        <button class="sort">{{ sortLabel }} <down-outlined /></button>
        <template #overlay>
          <a-menu @click="({ key }) => { sortBy = key }">
            <a-menu-item key="next">按下次运行</a-menu-item>
            <a-menu-item key="last">按最近执行</a-menu-item>
            <a-menu-item key="created">按创建时间</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>

    <div class="app-page-body">
      <div v-if="nextRail.length" class="rail">
        <span class="lbl"><clock-circle-outlined /> 接下来</span>
        <template v-for="(r, i) in nextRail" :key="r.id">
          <span v-if="i" class="sep">·</span>
          <span class="rail-item">
            <span class="when">{{ nextLabel(r.nextRunAt, true) }}</span>
            <b>{{ r.name }}</b>
            <span class="rel">{{ relative(r.nextRunAt) }}</span>
          </span>
        </template>
        <span class="rail-all">共 {{ nextRail.length }} 条即将运行</span>
      </div>

      <a-spin :spinning="loading">
        <div v-if="!rows.length && !loading" class="app-card sched-empty">
          <p class="empty-title">还没有定时任务</p>
          <p class="head-hint-plain">选一个模板开始，也可以从空白建一条；到点自动执行，结果写进任务专属会话</p>
          <div class="tpl-grid">
            <button v-for="t in TEMPLATES.filter(x => !x.blank)" :key="t.key" class="tpl-card" @click="openCreate(t)">
              <span class="tpl-icon"><component :is="t.icon" /></span>
              <span class="tpl-name">{{ t.label }}</span>
              <span class="tpl-desc">{{ t.desc }}</span>
            </button>
          </div>
        </div>

        <div v-else-if="filteredRows.length" class="sched-list">
          <div v-for="r in filteredRows" :key="r.id" class="sched-card"
               :class="cardClass(r)" :title="batchMode ? '点击选择' : '点击查看执行历史'"
               @click="batchMode ? toggleSelect(r.id) : openRuns(r)">
            <div class="tc-top">
              <span v-if="batchMode" class="check" :class="{ on: selected.includes(r.id) }"
                    @click.stop="toggleSelect(r.id)" />
              <i class="dot" :class="dotClass(r)" />
              <span class="tc-name">{{ r.name }}</span>
              <span v-if="r.running" class="tag run"><i class="pulse" />运行中 {{ elapsed(r) }}</span>
              <span v-if="isAttention(r)" class="tag attn">需关注</span>
              <span v-if="!r.enabled && !r.running" class="tag off">已停用</span>
              <span v-if="r.projectName" class="chip"><folder-outlined />{{ r.projectName }}</span>
              <span v-if="r.agentName" class="chip"><robot-outlined />{{ r.agentName }}</span>
              <div class="tc-right" @click.stop>
                <a-switch size="small" :checked="r.enabled" :loading="busyId === r.id"
                          @change="v => onToggle(r, v)" />
                <a-dropdown :trigger="['click']">
                  <button class="more" aria-label="更多操作">⋯</button>
                  <template #overlay>
                    <a-menu @click="({ key }) => menuAction(key, r)">
                      <a-menu-item key="clone"><copy-outlined /> 复制任务</a-menu-item>
                      <a-menu-item key="skip" :disabled="!r.enabled || r.running"><step-forward-outlined /> 跳过下一次</a-menu-item>
                      <a-menu-divider />
                      <a-menu-item key="delete" danger><delete-outlined /> 删除</a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </div>
            </div>

            <div class="tc-sched">
              <clock-circle-outlined />
              <span class="sched-main">{{ humanCron(r.cron) }}</span>
              <span class="tz">{{ zoneLabel(r.timezone) }}</span>
              <code class="cron">{{ r.cron }}</code>
              <span class="next" v-if="r.running">本轮 <b>{{ fmtTime(r.lastRun?.startedAt) }} 开始</b> · 已运行 {{ elapsed(r) }}</span>
              <span class="next" v-else-if="r.enabled && r.nextRunAt">下次 <b>{{ nextLabel(r.nextRunAt) }}</b><span class="rel">（{{ relative(r.nextRunAt) }}）</span></span>
              <span class="next" v-else-if="!r.enabled">已停用 · 启用后从下个周期开始</span>
              <span class="next" v-else>—</span>
            </div>

            <div class="tc-prompt">
              <span class="prompt-text">{{ r.prompt }}</span>
              <span class="tc-meta">
                <span>{{ r.deepThink ? '深度思考 开' : '深度思考 关' }}</span>
                <span class="sep">·</span>
                <span>{{ r.modelRef ? '指定模型' : '模型 跟随个人' }}</span>
                <span v-if="r.endAt" class="sep">·</span>
                <span v-if="r.endAt">到期 {{ fmtDate(r.endAt) }}</span>
              </span>
            </div>

            <div v-if="isAttention(r) && r.lastRun?.error" class="tc-alert">
              <warning-outlined />
              <span class="txt">上次失败：{{ shortError(r.lastRun.error) }}<template v-if="r.health?.consecutiveFailures > 1"> · 已连续 {{ r.health.consecutiveFailures }} 次</template></span>
              <span class="sp" />
              <button class="link" @click.stop="openRuns(r)">查看原因</button>
              <button class="link" @click.stop="doRun(r)">重试</button>
            </div>

            <div class="tc-foot">
              <span v-if="r.running" class="last" style="color:var(--app-accent)">
                <loading-outlined /> 正在检索与整理，可查看实时进度
              </span>
              <span v-else-if="r.lastRun" class="last" :class="lastClass(r.lastRun.status)">
                <check-circle-outlined v-if="r.lastRun.status === 'succeeded'" />
                <close-circle-outlined v-else-if="r.lastRun.status === 'failed'" />
                <history-outlined v-else />
                上次 {{ fmtDateTime(r.lastRun.startedAt) }} · {{ runLabel(r.lastRun.status) }} · {{ durationText(r.lastRun) }}
              </span>
              <span v-else class="last">还没有运行过</span>
              <button v-if="r.sessionId" class="link" @click.stop="openSession(r.sessionId)">
                {{ r.running ? '查看实时 →' : '查看结果 →' }}
              </button>
              <span class="health" :title="healthTitle(r)">
                <i v-for="(s, i) in dotsOf(r)" :key="i" class="hd" :class="s" />
              </span>
              <span class="hsum">{{ healthText(r) }}</span>
              <div class="ops" @click.stop>
                <button class="app-btn ghost small run" :disabled="r.running || busyId === r.id" @click="doRun(r)">
                  <loading-outlined v-if="r.running || busyId === r.id" />
                  <play-circle-outlined v-else />
                  {{ r.running ? '执行中…' : '立即执行' }}
                </button>
                <button class="app-btn ghost small" @click="openRuns(r)">执行历史</button>
                <button class="app-btn ghost small" @click="openEdit(r)">编辑</button>
              </div>
            </div>
          </div>
        </div>

        <div v-else-if="!loading" class="app-card sched-empty">
          <p class="empty-title">没有匹配的任务</p>
          <p class="head-hint-plain">换个关键词或筛选条件试试</p>
        </div>
      </a-spin>
    </div>

    <div v-if="batchMode && rows.length" class="batch-bar">
      <span class="cnt">已选 <b>{{ selected.length }}</b> 个任务</span>
      <span class="div" />
      <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(true)">启用</button>
      <button class="app-btn ghost small" :disabled="!selected.length || batchBusy" @click="doBatchEnabled(false)">停用</button>
      <button class="app-btn ghost small danger-text" :disabled="!selected.length || batchBusy" @click="doBatchDelete">删除</button>
      <span class="div" />
      <button class="app-btn ghost small" @click="toggleBatchMode">取消</button>
    </div>

    <div v-if="undo" class="undo-toast">
      <check-circle-outlined />
      <span>已删除 {{ undo.count }} 个任务</span>
      <button class="undo" @click="undoDelete">撤销</button>
    </div>

    <!-- ==================== 新建 / 编辑 ==================== -->
    <a-modal v-model:open="showEdit" width="800px" :footer="null" :mask-closable="false"
             :title="editing ? '编辑定时任务' : '新建定时任务'" wrap-class-name="sched-modal" @cancel="showEdit = false">
      <div class="mbody">
        <div v-if="!editing" class="tpl-row">
          <button v-for="t in TEMPLATES" :key="t.key" class="tpl" :class="{ on: templateKey === t.key }"
                  @click="applyTemplate(t)">
            <component :is="t.icon" />{{ t.label }}
          </button>
        </div>

        <div class="form-grid">
          <div class="form-col">
            <div class="field">
              <div class="flabel"><span class="req">*</span>任务名称</div>
              <a-input v-model:value="form.name" :maxlength="100" placeholder="如：每早知识库巡检" />
              <div v-if="errors.name" class="ferr">{{ errors.name }}</div>
            </div>
            <div class="row2 field">
              <div>
                <div class="flabel">工作区 <span class="opt">可选</span></div>
                <a-select v-model:value="form.projectId" allow-clear show-search option-filter-prop="label"
                          placeholder="不属于任何工作区" :options="projectOptions" style="width: 100%" />
              </div>
              <div>
                <div class="flabel">智能体</div>
                <a-select v-model:value="form.agentId" allow-clear show-search option-filter-prop="label"
                          placeholder="默认智能体" :options="agentOptions" style="width: 100%" />
              </div>
            </div>
            <div class="field">
              <div class="flabel"><span class="req">*</span>执行指令</div>
              <a-textarea v-model:value="form.prompt" :rows="3" :maxlength="2000" show-count
                          placeholder="每轮发送给智能体的内容，如：汇总知识库里最近新增文档的要点，列成清单" />
              <div v-if="errors.prompt" class="ferr">{{ errors.prompt }}</div>
            </div>

            <div class="field">
              <div class="flabel"><span class="req">*</span>执行周期</div>
              <div class="seg">
                <button v-for="t in SCHEDULE_TYPES" :key="t.key" class="s" :class="{ on: form.scheduleType === t.key }"
                        @click="setScheduleType(t.key)">{{ t.label }}</button>
              </div>

              <div v-if="form.scheduleType === 'weekly'" class="week">
                <button v-for="d in WEEK" :key="d.v" class="wd" :class="{ on: form.weekdays.includes(d.v) }"
                        @click="toggleWeekday(d.v)">{{ d.label }}</button>
              </div>

              <div v-if="form.scheduleType === 'monthly'" class="inline-row">
                <span class="inline-label">每月</span>
                <a-input-number v-model:value="form.monthDay" :min="1" :max="31" :precision="0" style="width: 90px" />
                <span class="inline-label">日</span>
              </div>

              <div v-if="form.scheduleType === 'interval'" class="inline-row">
                <span class="inline-label">每</span>
                <a-input-number v-model:value="form.intervalValue" :min="1" :max="form.intervalUnit === 'minute' ? 59 : 23"
                                :precision="0" style="width: 90px" />
                <a-select v-model:value="form.intervalUnit" style="width: 100px"
                          :options="[{ value: 'minute', label: '分钟' }, { value: 'hour', label: '小时' }]" />
              </div>

              <div v-if="['daily', 'weekly', 'monthly'].includes(form.scheduleType)" class="timerow">
                <a-time-picker v-model:value="form.time" format="HH:mm" value-format="HH:mm"
                               :minute-step="5" placeholder="09:00" style="width: 120px" />
                <a-select v-model:value="form.timezone" show-search option-filter-prop="label"
                          class="tz-select" :options="zoneOptions" />
              </div>
              <div v-else class="timerow">
                <a-select v-model:value="form.timezone" show-search option-filter-prop="label"
                          class="tz-select" :options="zoneOptions" />
              </div>

              <div v-if="form.scheduleType === 'advanced'" class="adv-box">
                <a-input v-model:value="form.advancedCron" placeholder="5 段：分 时 日 月 周，如 0 9 * * 1-5" />
                <div class="fhint">5 段写法（分 时 日 月 周）：<code>0 9 * * 1-5</code> = 工作日 9 点；<code>*/30 * * * *</code> = 每 30 分钟</div>
              </div>
              <div v-if="errors.cron" class="ferr">{{ errors.cron }}</div>

              <div class="summary">
                <thunderbolt-outlined />
                <span><b>{{ humanSummary }}</b><span v-if="previewTimes.length" class="dim"> · 下次 {{ previewTimes[0] ? previewLabel(previewTimes[0]) : '—' }}</span></span>
              </div>
              <div v-if="previewError" class="ferr">{{ previewError }}</div>
            </div>

            <div class="setfold" role="button" tabindex="0" @click="showSettings = !showSettings"
                 @keydown.enter="showSettings = !showSettings">
              <down-outlined :class="{ rotated: showSettings }" />
              <span>运行设置</span>
              <span class="vals">模型 {{ form.modelRef ? '已指定' : '跟随个人' }} · 通知 {{ notifyLabel(form.notifyOn) }} · 重试 {{ form.maxRetries }} 次</span>
            </div>
            <div v-if="showSettings" class="settings-box">
              <div class="row2">
                <div>
                  <div class="flabel">模型</div>
                  <ModelSelect v-model="form.modelRef" type="chat" width="100%"
                               inherit-label="跟随个人默认模型" inherit-resolve="personalDefault" />
                </div>
                <div>
                  <div class="flabel">深度思考</div>
                  <a-switch v-model:checked="form.deepThink" />
                  <span class="fhint" style="margin-left: 8px">先思考再检索（更慢）</span>
                </div>
              </div>
              <div class="row2" style="margin-top: 10px">
                <div>
                  <div class="flabel">通知</div>
                  <a-select v-model:value="form.notifyOn" :options="NOTIFY_OPTIONS" />
                </div>
                <div>
                  <div class="flabel">到期时间 <span class="opt">可选</span></div>
                  <a-date-picker v-model:value="form.endAt" show-time value-format="YYYY-MM-DDTHH:mm:ss"
                                 placeholder="不设到期" style="width: 100%" />
                </div>
              </div>
              <div class="row2" style="margin-top: 10px">
                <div>
                  <div class="flabel">失败重试</div>
                  <div class="inline-row">
                    <a-input-number v-model:value="form.maxRetries" :min="0" :max="5" :precision="0" style="width: 80px" />
                    <span class="inline-label">次，间隔</span>
                    <a-input-number v-model:value="form.retryIntervalSec" :min="10" :max="3600" :precision="0" style="width: 100px" />
                    <span class="inline-label">秒</span>
                  </div>
                </div>
                <div>
                  <div class="flabel">单轮超时 <span class="opt">秒，留空跟随全局</span></div>
                  <a-input-number v-model:value="form.timeoutSec" :min="30" :max="86400" :precision="0"
                                  placeholder="跟随全局" style="width: 100%" />
                </div>
              </div>
            </div>
            <div v-if="errors.endAt" class="ferr">{{ errors.endAt }}</div>
          </div>

          <aside class="preview-col">
            <div class="preview">
              <div class="ph">卡片预览</div>
              <div class="pvcard">
                <div class="pv-top">
                  <i class="dot ok" />
                  <span class="pv-name">{{ form.name || '未命名任务' }}</span>
                  <span class="pv-tag">已启用</span>
                </div>
                <div class="pv-line"><clock-circle-outlined /><b>{{ humanCron(cronValue) }}</b><span>{{ zoneLabel(form.timezone) }}</span></div>
                <div class="pv-prompt">{{ form.prompt || '填写执行指令后在这里预览' }}</div>
                <div class="pv-foot">
                  <robot-outlined /><span>{{ agentName || '默认智能体' }}</span>
                  <template v-if="projectName"><span>·</span><folder-outlined /><span>{{ projectName }}</span></template>
                </div>
              </div>
              <div class="nextlist">
                <div class="t">接下来 3 次</div>
                <div v-if="previewLoading" class="nextrow dim">计算中…</div>
                <div v-else-if="!previewTimes.length" class="nextrow dim">规则生效后显示</div>
                <div v-for="t in previewTimes" :key="t" class="nextrow">
                  <span class="d">{{ previewDate(t) }}</span>
                  <span>{{ previewClock(t) }}</span>
                  <span class="w">{{ previewWeek(t) }}</span>
                </div>
              </div>
              <div class="mtip">{{ zoneLabel(form.timezone) }}；保存后按实际触发时刻重新计算。停用不会补跑错过的轮次。</div>
            </div>
          </aside>
        </div>
      </div>
      <div class="mfoot">
        <span class="fhint">保存后立即按新周期计算下一次运行</span>
        <span class="sp" />
        <button class="app-btn ghost" @click="showEdit = false">取消</button>
        <button class="app-btn ghost" :disabled="saving" @click="save()">保存</button>
        <button class="app-btn" :disabled="saving" @click="save(true)"><check-outlined /> 保存并启用</button>
      </div>
    </a-modal>

    <!-- ==================== 执行历史抽屉 ==================== -->
    <a-drawer v-model:open="showRuns" :width="560" :closable="false" placement="right"
              :body-style="{ padding: '0 20px 16px' }" wrap-class-name="sched-drawer">
      <template #title>
        <div class="dtitle">
          <i class="dot" :class="dotClass(runsJob || {})" />
          <span class="name">{{ runsJob?.name }}</span>
          <span v-if="runsJob?.running" class="tag run"><i class="pulse" />运行中</span>
          <span v-else-if="runsJob?.enabled" class="tag on">已启用</span>
          <span v-else class="tag off">已停用</span>
        </div>
        <div class="dsub" v-if="runsJob">
          {{ humanCron(runsJob.cron) }} · {{ zoneLabel(runsJob.timezone) }}<template v-if="runsJob.agentName"> · 智能体 {{ runsJob.agentName }}</template>
        </div>
      </template>
      <template #extra>
        <div style="display:flex;align-items:center;gap:12px">
          <a-switch v-if="runsJob" size="small" :checked="runsJob.enabled" @change="v => onToggle(runsJob, v)" />
          <a-tooltip title="关闭"><button class="app-icon-btn" @click="showRuns = false"><close-outlined /></button></a-tooltip>
        </div>
      </template>

      <template v-if="runsJob">
        <div class="sumrow">
          <div class="sumcell"><div class="k">近 7 天运行</div><div class="v">{{ runsJob.health?.runs7d ?? 0 }}<small>次</small></div></div>
          <div class="sumcell"><div class="k">成功率</div><div class="v">{{ runsJob.health?.successRate7d == null ? '—' : runsJob.health.successRate7d + '%' }}</div></div>
          <div class="sumcell"><div class="k">平均耗时</div><div class="v">{{ avgDurationText }}<small v-if="avgDurationUnit">{{ avgDurationUnit }}</small></div></div>
        </div>
        <div class="nextcard">
          <clock-circle-outlined />
          <span class="t" v-if="runsJob.enabled && runsJob.nextRunAt">下次 <b>{{ nextLabel(runsJob.nextRunAt) }}</b> <span class="rel">· {{ relative(runsJob.nextRunAt) }}</span></span>
          <span class="t" v-else>当前已停用，启用后从下个周期开始</span>
          <div class="ops">
            <button class="app-btn ghost small" :disabled="!runsJob.enabled || runsJob.running" @click="doSkip(runsJob)">跳过下一次</button>
            <button class="app-btn small" :disabled="runsJob.running || busyId === runsJob.id" @click="doRun(runsJob)">
              <play-circle-outlined /> 立即执行
            </button>
          </div>
        </div>

        <div class="dtabs">
          <button class="dtab" :class="{ on: drawerTab === 'runs' }" @click="drawerTab = 'runs'">执行记录</button>
          <button class="dtab" :class="{ on: drawerTab === 'config' }" @click="drawerTab = 'config'">运行配置</button>
        </div>

        <template v-if="drawerTab === 'runs'">
          <div class="dfilter">
            <button class="f" :class="{ on: runsFilter === 'all' }" @click="runsFilter = 'all'">全部 <b>{{ runs.length }}</b></button>
            <button class="f" :class="{ on: runsFilter === 'failed' }" @click="runsFilter = 'failed'">失败 <b>{{ failedCount }}</b></button>
            <button class="f" :class="{ on: runsFilter === 'manual' }" @click="runsFilter = 'manual'">手动 <b>{{ manualCount }}</b></button>
            <span class="right">最近 100 条</span>
          </div>
          <a-spin :spinning="runsLoading">
            <div v-if="!filteredRuns.length" class="head-hint-plain" style="padding:16px 0">还没有执行记录</div>
            <div v-for="g in runGroups" :key="g.label" class="dgroup-wrap">
              <div class="dgroup">{{ g.label }}</div>
              <div v-for="x in g.items" :key="x.id" class="drun">
                <div class="drun-top">
                  <span class="rstatus" :class="runStatusClass(x.status)">
                    <check-outlined v-if="x.status === 'succeeded'" />
                    <close-outlined v-else-if="x.status === 'failed'" />
                    <step-forward-outlined v-else-if="x.status === 'skipped'" />
                    <loading-outlined v-else />
                  </span>
                  <span class="rtime">{{ fmtTime(x.startedAt) }}</span>
                  <span class="rtag">{{ x.trigger === 'manual' ? '手动' : '定时' }}</span>
                  <span v-if="x.attempt > 1" class="rtag warn">第 {{ x.attempt }} 次</span>
                  <span class="rdur">{{ x.status === 'running' ? '执行中 · ' + runElapsed(x) : durationText(x) }}</span>
                  <div class="ops">
                    <button v-if="x.sessionId" class="link" @click="openSession(x.sessionId)">查看会话 →</button>
                    <button v-if="x.status === 'failed'" class="link" @click="doRun(runsJob)">重跑</button>
                    <button v-if="x.status === 'skipped'" class="link muted" @click="toggleRun(x.id)">
                      {{ expandedRuns.includes(x.id) ? '收起' : '原因' }}
                    </button>
                  </div>
                </div>

                <template v-if="x.status === 'failed' && expandedRuns.includes(x.id)">
                  <div class="rerr">
                    <div class="line"><warning-outlined /><span>{{ shortError(x.error) || '执行异常，未返回原因' }}</span></div>
                    <ul class="tips">
                      <li v-for="(tip, ti) in failureTips(x.error)" :key="ti">{{ tip }}</li>
                    </ul>
                    <div v-if="x.error" class="code">
                      <code>{{ x.error }}</code>
                      <button class="link" @click="copyText(x.error)">复制</button>
                    </div>
                  </div>
                </template>
                <template v-else-if="x.status === 'succeeded' && x.answer">
                  <div class="ranswer" :class="{ open: expandedRuns.includes(x.id) }">{{ x.answer }}</div>
                  <button v-if="x.answer.length > 80" class="answer-toggle" @click="toggleRun(x.id)">
                    {{ expandedRuns.includes(x.id) ? '收起' : '展开全文' }}
                  </button>
                </template>
                <div v-else-if="x.status === 'skipped'" class="ranswer">
                  {{ expandedRuns.includes(x.id) ? (x.error || '本轮跳过') : '本轮跳过；下一周期照常触发。' }}
                </div>
                <div v-else-if="x.status === 'running'" class="ranswer">本轮正在执行，结果写进任务专属会话。</div>
              </div>
            </div>
          </a-spin>
        </template>

        <div v-else class="dconfig">
          <div class="cfg-row"><span>周期</span><b>{{ humanCron(runsJob.cron) }}</b></div>
          <div class="cfg-row"><span>原始规则</span><code>{{ runsJob.cron }}</code></div>
          <div class="cfg-row"><span>时区</span><b>{{ zoneLabel(runsJob.timezone) }}</b></div>
          <div class="cfg-row"><span>智能体</span><b>{{ runsJob.agentName || '默认智能体' }}</b></div>
          <div class="cfg-row"><span>模型</span><b>{{ runsJob.modelRef ? '已指定' : '跟随个人默认' }}</b></div>
          <div class="cfg-row"><span>工作区</span><b>{{ runsJob.projectName || '不属于任何工作区' }}</b></div>
          <div class="cfg-row"><span>深度思考</span><b>{{ runsJob.deepThink ? '开' : '关' }}</b></div>
          <div class="cfg-row"><span>通知</span><b>{{ notifyLabel(runsJob.notifyOn) }}</b></div>
          <div class="cfg-row"><span>失败重试</span><b>{{ runsJob.maxRetries || 0 }} 次<template v-if="runsJob.maxRetries">，间隔 {{ runsJob.retryIntervalSec }} 秒</template></b></div>
          <div class="cfg-row"><span>单轮超时</span><b>{{ runsJob.timeoutSec ? runsJob.timeoutSec + ' 秒' : '跟随全局' }}</b></div>
          <div class="cfg-row"><span>到期时间</span><b>{{ runsJob.endAt ? fmtDateTime(runsJob.endAt) : '不设到期' }}</b></div>
          <div class="cfg-prompt">
            <div class="k">执行指令</div>
            <div class="v">{{ runsJob.prompt }}</div>
          </div>
          <button class="app-btn ghost" style="margin-top: 12px" @click="openEdit(runsJob)"><edit-outlined /> 编辑任务</button>
        </div>
      </template>
      <div class="dfoot">执行记录保留最近 100 条 / 可配置天数；完整回答在结果会话里。失败会按任务的通知偏好提醒。</div>
    </a-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  PlusOutlined, SearchOutlined, ReloadOutlined, ClockCircleOutlined, DownOutlined,
  PlayCircleOutlined, HistoryOutlined, DeleteOutlined, CopyOutlined, EditOutlined,
  WarningOutlined, CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined,
  CheckOutlined, CloseOutlined, FolderOutlined, RobotOutlined, ThunderboltOutlined,
  StepForwardOutlined, FileTextOutlined, BarChartOutlined, PlusCircleOutlined
} from '@ant-design/icons-vue'
import ModelSelect from '../components/ModelSelect.vue'
import { locateField } from '../utils/fieldLocate'
import {
  listScheduledJobs, getScheduledOverview, previewScheduledCron, createScheduledJob,
  updateScheduledJob, deleteScheduledJob, toggleScheduledJob, runScheduledJob,
  listScheduledRuns, listAvailableAgents, listProjects, batchDeleteScheduledJobs,
  batchToggleScheduledJobs, skipScheduledJobNext, restoreScheduledJob
} from '../api'

const route = useRoute()
const router = useRouter()

// ==================== 列表与总览 ====================
const rows = ref([])
const overview = ref(null)
const loading = ref(false)
const busyId = ref('')
const keyword = ref('')
const statusFilter = ref('all')
const sortBy = ref('next')
const nowTick = ref(Date.now())

const stats = computed(() => {
  if (overview.value) return overview.value
  const list = rows.value
  return {
    total: list.length,
    enabled: list.filter(r => r.enabled).length,
    running: list.filter(r => r.running).length,
    todayRuns: 0,
    todayDue: 0,
    runs7d: list.reduce((n, r) => n + (r.health?.runs7d || 0), 0),
    successRate7d: null,
    attention: list.filter(isAttention).length
  }
})
const rateText = computed(() => stats.value.successRate7d == null ? '—' : stats.value.successRate7d + '%')
const attentionCount = computed(() => rows.value.filter(isAttention).length)

function isAttention (r) {
  return r.health?.consecutiveFailures >= 2 || r.lastRun?.status === 'failed'
}

const filters = computed(() => [
  { key: 'all', label: '全部', count: rows.value.length },
  { key: 'enabled', label: '启用', count: rows.value.filter(r => r.enabled).length },
  { key: 'disabled', label: '停用', count: rows.value.filter(r => !r.enabled).length },
  { key: 'attention', label: '需关注', count: attentionCount.value, warn: true },
  { key: 'running', label: '运行中', count: rows.value.filter(r => r.running).length }
])
const sortLabel = computed(() => ({ next: '按下次运行', last: '按最近执行', created: '按创建时间' }[sortBy.value]))
const setFilter = k => { statusFilter.value = k }

const matchesKeyword = r => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return true
  return [r.name, r.prompt, r.agentName, r.projectName].some(v => String(v || '').toLowerCase().includes(k))
}
const filteredRows = computed(() => {
  let list = rows.value.filter(matchesKeyword)
  if (statusFilter.value === 'enabled') list = list.filter(r => r.enabled)
  else if (statusFilter.value === 'disabled') list = list.filter(r => !r.enabled)
  else if (statusFilter.value === 'attention') list = list.filter(isAttention)
  else if (statusFilter.value === 'running') list = list.filter(r => r.running)
  const sorted = [...list]
  sorted.sort((a, b) => {
    if (sortBy.value === 'created') return String(b.createTime || '').localeCompare(String(a.createTime || ''))
    if (sortBy.value === 'last') return String(b.lastRun?.startedAt || '').localeCompare(String(a.lastRun?.startedAt || ''))
    if (a.enabled !== b.enabled) return a.enabled ? -1 : 1
    return String(a.nextRunAt || '9999').localeCompare(String(b.nextRunAt || '9999'))
  })
  return sorted
})
const nextRail = computed(() => rows.value
  .filter(r => r.enabled && r.nextRunAt)
  .sort((a, b) => String(a.nextRunAt).localeCompare(String(b.nextRunAt)))
  .slice(0, 3))

const cardClass = r => ({
  'is-running': r.running,
  'is-attention': isAttention(r),
  'is-disabled': !r.enabled && !r.running,
  'is-selected': batchMode.value && selected.value.includes(r.id)
})
const dotClass = r => r.running ? 'run' : (!r.enabled ? 'muted' : (isAttention(r) ? 'bad' : 'ok'))

const load = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    const r = await listScheduledJobs()
    rows.value = ((r && r.data) || []).map(x => ({ ...x, running: !!x.running }))
    const alive = new Set(rows.value.map(x => x.id))
    selected.value = selected.value.filter(id => alive.has(id))
    try {
      const o = await getScheduledOverview()
      if (o && o.success !== false) overview.value = o.data || null
    } catch (e) { /* 总览失败不挡列表 */ }
  } catch (e) {
    if (!silent) message.error('定时任务加载失败：' + (e.message || '请刷新重试'))
    rows.value = []
  } finally {
    if (!silent) loading.value = false
  }
  if (showRuns.value && runsJob.value) {
    const fresh = rows.value.find(x => x.id === runsJob.value.id)
    if (fresh) runsJob.value = fresh
    if (drawerTab.value === 'runs') loadRuns(runsJob.value, true)
  }
}

const loadAgents = async () => {
  try {
    const r = await listAvailableAgents()
    agentOptions.value = ((r && r.data) || [])
      .map(a => ({ value: a.id || a.agentId, label: a.name || a.id || a.agentId }))
      .filter(o => o.value)
  } catch (e) { agentOptions.value = [] }
}
const loadProjects = async () => {
  try {
    const r = await listProjects()
    projectOptions.value = ((r && r.data) || [])
      .filter(p => p && p.status !== 'archived')
      .map(p => ({ value: p.id, label: p.name || p.id }))
  } catch (e) { projectOptions.value = [] }
}

// ==================== 轮询与倒计时 ====================
let pollTimer = null
let clockTimer = null
const hasRunning = computed(() => rows.value.some(r => r.running))
const schedulePoll = () => {
  clearTimeout(pollTimer)
  const delay = hasRunning.value ? 10000 : 60000
  pollTimer = setTimeout(async () => {
    if (!document.hidden) await load(true)
    schedulePoll()
  }, delay)
}
const onVisibility = () => {
  if (!document.hidden) { load(true); schedulePoll() }
}

// ==================== 批量与撤销 ====================
const batchMode = ref(false)
const selected = ref([])
const batchBusy = ref(false)
const undo = ref(null)
let undoTimer = null
const toggleBatchMode = () => {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = []
}
const toggleSelect = id => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const doBatchEnabled = async on => {
  if (!selected.value.length) return
  batchBusy.value = true
  try {
    const r = await batchToggleScheduledJobs([...selected.value], on)
    reportBatch(r, on ? '启用' : '停用')
    await load()
  } catch (e) {
    message.error((on ? '批量启用' : '批量停用') + '失败：' + (e.message || ''))
  } finally { batchBusy.value = false }
}
const doBatchDelete = () => {
  const names = rows.value.filter(r => selected.value.includes(r.id)).map(r => r.name)
  Modal.confirm({
    title: `删除选中的 ${names.length} 个定时任务？`,
    content: `将删除：${names.slice(0, 5).join('、')}${names.length > 5 ? ' 等' : ''}。任务不再触发，已产生的执行记录与结果会话保留；删除后 8 秒内可撤销。`,
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      batchBusy.value = true
      try {
        const r = await batchDeleteScheduledJobs([...selected.value])
        const data = (r && r.data) || {}
        const okIds = data.succeeded || []
        reportBatch(r, '删除')
        selected.value = []
        if (okIds.length) showUndo(okIds)
        batchMode.value = false
        await load()
      } catch (e) {
        message.error('批量删除失败：' + (e.message || ''))
      } finally { batchBusy.value = false }
    }
  })
}
const reportBatch = (r, verb) => {
  const data = (r && r.data) || {}
  const okCount = (data.succeeded || []).length
  const failed = data.failed || []
  if (!failed.length) { message.success(`已${verb} ${okCount} 个任务`); return }
  Modal.warning({
    title: `${verb}完成：成功 ${okCount} 个，失败 ${failed.length} 个`,
    content: failed.map(f => `「${f.name || f.id}」：${f.error}`).join('；'),
    okText: '知道了'
  })
}
const showUndo = ids => {
  if (undoTimer) clearTimeout(undoTimer)
  undo.value = { ids, count: ids.length }
  undoTimer = setTimeout(() => { undo.value = null }, 8000)
}
const undoDelete = async () => {
  if (!undo.value) return
  const ids = [...undo.value.ids]
  undo.value = null
  if (undoTimer) clearTimeout(undoTimer)
  try {
    const rs = await Promise.all(ids.map(id => restoreScheduledJob(id)))
    const failed = rs.filter(r => r && r.success === false)
    if (failed.length) message.warning(`已恢复 ${ids.length - failed.length} 个；${failed.length} 个恢复失败`)
    else message.success(`已恢复 ${ids.length} 个任务（停用态）`)
    await load()
  } catch (e) {
    message.error('撤销失败：' + (e.message || ''))
  }
}

// ==================== 单任务操作 ====================
const doRun = async row => {
  if (!row || busyId.value === row.id) return
  busyId.value = row.id
  try {
    const r = await runScheduledJob(row.id)
    if (r && r.success !== false) {
      message.success('已触发执行，跑完可在执行历史里看结果')
      await load(true)
      if (showRuns.value && runsJob.value?.id === row.id) await loadRuns(runsJob.value, true)
    } else {
      message.error((r && r.msg) || '触发失败')
    }
  } catch (e) {
    message.error('触发失败：' + (e.message || ''))
  } finally { busyId.value = '' }
}
const onToggle = async (row, enabled) => {
  busyId.value = row.id
  try {
    const r = await toggleScheduledJob(row.id, enabled)
    if (r && r.success !== false) {
      message.success(enabled ? '已启用' : '已停用')
      await load(true)
    } else {
      message.error((r && r.msg) || '操作失败')
    }
  } catch (e) {
    message.error('操作失败：' + (e.message || ''))
  } finally { busyId.value = '' }
}
const doSkip = async row => {
  busyId.value = row.id
  try {
    const r = await skipScheduledJobNext(row.id)
    if (r && r.success !== false) {
      message.success('已跳过下一次')
      await load()
    } else {
      message.error((r && r.msg) || '跳过失败')
    }
  } catch (e) {
    message.error('跳过失败：' + (e.message || ''))
  } finally { busyId.value = '' }
}
const menuAction = (key, row) => {
  if (key === 'clone') openCreate(cloneSeed(row))
  else if (key === 'skip') doSkip(row)
  else if (key === 'delete') removeOne(row)
}
const removeOne = row => {
  Modal.confirm({
    title: `删除定时任务「${row.name}」？`,
    content: '任务不再触发；已产生的执行记录与结果会话保留。删除后 8 秒内可撤销。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteScheduledJob(row.id)
        if (r && r.success !== false) {
          message.success('已删除')
          showUndo([row.id])
          if (showRuns.value && runsJob.value?.id === row.id) showRuns.value = false
          await load()
        } else {
          message.error((r && r.msg) || '删除失败')
        }
      } catch (e) { message.error('删除失败：' + (e.message || '')) }
    }
  })
}
const openSession = sid => { if (sid) router.push({ path: '/chat', query: { sid } }) }
const copyText = async text => {
  try { await navigator.clipboard.writeText(String(text || '')); message.success('已复制') }
  catch (e) { message.warning('复制失败，请手动选择文本') }
}

// ==================== 执行历史抽屉 ====================
const showRuns = ref(false)
const runsJob = ref(null)
const runs = ref([])
const runsLoading = ref(false)
const runsFilter = ref('all')
const drawerTab = ref('runs')
const expandedRuns = ref([])
const failedCount = computed(() => runs.value.filter(x => x.status === 'failed').length)
const manualCount = computed(() => runs.value.filter(x => x.trigger === 'manual').length)
const filteredRuns = computed(() => {
  if (runsFilter.value === 'failed') return runs.value.filter(x => x.status === 'failed')
  if (runsFilter.value === 'manual') return runs.value.filter(x => x.trigger === 'manual')
  return runs.value
})
const runGroups = computed(() => {
  const out = []
  const byKey = new Map()
  for (const x of filteredRuns.value) {
    const d = parseTs(x.startedAt) || new Date()
    const key = d.toDateString()
    if (!byKey.has(key)) {
      const g = { key, label: dayLabel(d), items: [] }
      byKey.set(key, g)
      out.push(g)
    }
    byKey.get(key).items.push(x)
  }
  return out
})
const openRuns = async row => {
  runsJob.value = row
  runs.value = []
  expandedRuns.value = []
  runsFilter.value = 'all'
  drawerTab.value = 'runs'
  showRuns.value = true
  await loadRuns(row)
}
const loadRuns = async (row, silent = false) => {
  if (!row) return
  if (!silent) runsLoading.value = true
  try {
    const r = await listScheduledRuns(row.id, 100)
    runs.value = (r && r.data) || []
    if (!expandedRuns.value.length) {
      const firstFailed = runs.value.find(x => x.status === 'failed')
      if (firstFailed) expandedRuns.value = [firstFailed.id]
    }
  } catch (e) {
    if (!silent) message.error('执行历史加载失败：' + (e.message || ''))
    runs.value = []
  } finally {
    if (!silent) runsLoading.value = false
  }
}
const toggleRun = id => {
  expandedRuns.value = expandedRuns.value.includes(id)
    ? expandedRuns.value.filter(x => x !== id)
    : [...expandedRuns.value, id]
}
const avgDurationText = computed(() => {
  const done = runs.value.filter(x => x.durationMs != null && x.status !== 'running' && x.status !== 'skipped')
  if (!done.length) return '—'
  const avg = done.reduce((n, x) => n + Number(x.durationMs || 0), 0) / done.length
  return avg >= 60000 ? (avg / 60000).toFixed(1) : String(Math.round(avg / 1000))
})
const avgDurationUnit = computed(() => {
  const done = runs.value.filter(x => x.durationMs != null && x.status !== 'running' && x.status !== 'skipped')
  if (!done.length) return ''
  const avg = done.reduce((n, x) => n + Number(x.durationMs || 0), 0) / done.length
  return avg >= 60000 ? '分' : '秒'
})
const failureTips = err => {
  const s = String(err || '')
  if (/超时|timeout/i.test(s)) {
    return [
      '确认该时段的模型供应商可用；可在「运行配置」调大单轮超时。',
      '若知识库文档量突增，可把指令拆成「先列清单、再逐篇总结」两步。'
    ]
  }
  if (/401|403|unauthorized|api ?key|密钥|鉴权/i.test(s)) {
    return ['检查任务指定的模型供应商凭据是否过期。', '如果使用个人默认模型，确认个人模型设置仍然有效。']
  }
  if (/归档|archiv/i.test(s)) {
    return ['任务归属的工作区已归档：恢复工作区后再启用。', '或把任务改绑到正常的工作区。']
  }
  if (/线程池|繁忙|queue|reject/i.test(s)) {
    return ['同一时段任务过多：错开执行时间，或减少并发任务。', '已排队的轮次会在下个周期照常触发，不会丢失后续任务。']
  }
  return ['查看该任务的结果会话，确认模型是否返回了错误。', '必要时调整指令或智能体，再手动重跑一次验证。']
}

// ==================== 编辑器 ====================
const showEdit = ref(false)
const editing = ref(null)
const saving = ref(false)
const showSettings = ref(false)
const templateKey = ref('')
const agentOptions = ref([])
const projectOptions = ref([])
const previewTimes = ref([])
const previewLoading = ref(false)
const previewError = ref('')
const errors = reactive({ name: '', prompt: '', cron: '', endAt: '' })

const blank = () => ({
  name: '', prompt: '', agentId: null, projectId: null, timezone: 'Asia/Shanghai',
  modelRef: '', deepThink: false, enabled: true,
  notifyOn: 'all', maxRetries: 0, retryIntervalSec: 60, timeoutSec: null, endAt: null,
  scheduleType: 'weekly', time: '09:00', weekdays: [1, 2, 3, 4, 5],
  monthDay: 1, intervalUnit: 'hour', intervalValue: 1, advancedCron: ''
})
const form = reactive(blank())

const WEEK = [
  { v: 1, label: '一' }, { v: 2, label: '二' }, { v: 3, label: '三' }, { v: 4, label: '四' },
  { v: 5, label: '五' }, { v: 6, label: '六' }, { v: 0, label: '日' }
]
const SCHEDULE_TYPES = [
  { key: 'daily', label: '每天' }, { key: 'weekly', label: '每周' }, { key: 'monthly', label: '每月' },
  { key: 'interval', label: '间隔' }, { key: 'advanced', label: '高级' }
]
const NOTIFY_OPTIONS = [
  { value: 'all', label: '完成和失败都通知' },
  { value: 'failure', label: '仅失败时通知' },
  { value: 'none', label: '不通知' }
]
const notifyLabel = v => ({ all: '完成和失败', failure: '仅失败', none: '不通知' }[v] || '完成和失败')

const TEMPLATES = [
  { key: 'digest', label: '知识库早报', desc: '工作日汇总新增文档', icon: ThunderboltOutlined,
    seed: { name: '知识库早报', scheduleType: 'weekly', weekdays: [1, 2, 3, 4, 5], time: '09:00', prompt: '汇总知识库里最近 24 小时新增文档的要点，按主题列成清单，并标注来源文档。' } },
  { key: 'weekly', label: '周报汇总', desc: '每周五生成周报草稿', icon: FileTextOutlined,
    seed: { name: '周报汇总', scheduleType: 'weekly', weekdays: [5], time: '18:00', prompt: '汇总本周各工作区的产出与未完成事项，生成周报草稿并列出下周建议。' } },
  { key: 'data', label: '数据核对', desc: '每天核对订单与库存', icon: BarChartOutlined,
    seed: { name: '数据核对', scheduleType: 'daily', time: '08:00', prompt: '核对昨日订单与库存快照，差异超过 0.5% 的 SKU 列出来并说明原因。' } },
  { key: 'blank', label: '空白', desc: '从零开始', icon: PlusCircleOutlined, blank: true }
]

const zoneLabel = z => ({
  'Asia/Shanghai': 'Asia/Shanghai（北京）',
  'Asia/Tokyo': 'Asia/Tokyo（东京）',
  'America/New_York': 'America/New_York（纽约）',
  'Europe/London': 'Europe/London（伦敦）',
  'UTC': 'UTC'
}[z] || z || 'Asia/Shanghai')
const FALLBACK_ZONES = ['Asia/Shanghai', 'Asia/Tokyo', 'Asia/Singapore', 'UTC', 'Europe/London', 'America/New_York', 'America/Los_Angeles']
const zoneOptions = computed(() => {
  let zones = FALLBACK_ZONES
  try {
    if (typeof Intl !== 'undefined' && Intl.supportedValuesOf) zones = Intl.supportedValuesOf('timeZone')
  } catch (e) { /* 老浏览器用兜底 */ }
  return zones.map(z => ({ value: z, label: zoneLabel(z) }))
})
const agentName = computed(() => agentOptions.value.find(o => o.value === form.agentId)?.label || '')
const projectName = computed(() => projectOptions.value.find(o => o.value === form.projectId)?.label || '')

const parseTime = t => {
  const [h, m] = String(t || '09:00').split(':').map(Number)
  return [Number.isFinite(h) ? h : 9, Number.isFinite(m) ? m : 0]
}
const pad2 = n => String(n).padStart(2, '0')
const cronValue = computed(() => {
  const [h, m] = parseTime(form.time)
  if (form.scheduleType === 'daily') return `${m} ${h} * * *`
  if (form.scheduleType === 'weekly') {
    const days = formatDow(form.weekdays)
    return `${m} ${h} * * ${days || '*'}`
  }
  if (form.scheduleType === 'monthly') return `${m} ${h} ${form.monthDay || 1} * *`
  if (form.scheduleType === 'interval') {
    const n = Math.max(1, Number(form.intervalValue) || 1)
    return form.intervalUnit === 'minute' ? `*/${n} * * * *` : `${m} */${n} * * *`
  }
  return String(form.advancedCron || '').trim()
})
const cronValid = computed(() => cronValue.value.split(/\s+/).filter(Boolean).length === 5)
const humanSummary = computed(() => {
  const t = form.time || '09:00'
  const zone = zoneLabel(form.timezone)
  if (form.scheduleType === 'daily') return `每天 ${t} 运行 · ${zone}`
  if (form.scheduleType === 'weekly') return `${humanWeekdays(form.weekdays)} ${t} 运行 · ${zone}`
  if (form.scheduleType === 'monthly') return `每月 ${form.monthDay} 日 ${t} 运行 · ${zone}`
  if (form.scheduleType === 'interval') return `每 ${form.intervalValue} ${form.intervalUnit === 'minute' ? '分钟' : '小时'}运行 · ${zone}`
  return `按自定义规则 ${form.advancedCron || '—'} 运行 · ${zone}`
})
const humanWeekdays = days => {
  const s = [...days].sort((a, b) => a - b)
  if (!s.length) return '每周'
  if (s.length === 7) return '每天'
  if (s.join(',') === '1,2,3,4,5') return '每周一至周五'
  return '每周' + s.map(d => WEEK.find(w => w.v === d)?.label || '').join('、')
}
const humanCron = cron => {
  const p = String(cron || '').trim().split(/\s+/)
  if (p.length !== 5) return cron || '—'
  const [mi, ho, dom, mon, dow] = p
  if (mi.startsWith('*/') && ho === '*' && dom === '*' && mon === '*' && dow === '*') return `每 ${mi.slice(2)} 分钟`
  if (/^\d+$/.test(mi) && ho === '*' && dom === '*' && mon === '*' && dow === '*') return `每小时（第 ${mi} 分）`
  if (/^\d+$/.test(mi) && ho.startsWith('*/') && dom === '*' && mon === '*' && dow === '*') return `每 ${ho.slice(2)} 小时（第 ${mi} 分）`
  if (/^\d+$/.test(mi) && /^\d+$/.test(ho)) {
    const time = `${pad2(ho)}:${pad2(mi)}`
    if (dom === '*' && mon === '*' && dow === '*') return `每天 ${time}`
    if (dom === '*' && mon === '*' && dow !== '*') return `${humanDow(dow)} ${time}`
    if (/^\d+$/.test(dom) && mon === '*' && dow === '*') return `每月 ${dom} 日 ${time}`
  }
  return `自定义 · ${cron}`
}
const humanDow = dow => {
  if (dow === '*' || dow === '1-7' || dow === '0-6') return '每天'
  if (dow === '1-5') return '工作日'
  const days = expandDow(dow)
  if (!days) return '每周'
  if (days.length === 7) return '每天'
  if (days.join(',') === '1,2,3,4,5') return '工作日'
  return '每周' + days.map(d => WEEK.find(w => w.v === d)?.label || '').join('、')
}
const formatDow = days => {
  const s = [...new Set(days)].sort((a, b) => a - b)
  if (s.length === 7) return '*'
  const out = []
  for (let i = 0; i < s.length; i++) {
    let j = i
    while (j + 1 < s.length && s[j + 1] === s[j] + 1) j++
    if (j - i >= 2) out.push(`${s[i]}-${s[j]}`)
    else for (let k = i; k <= j; k++) out.push(String(s[k]))
    i = j
  }
  return out.join(',')
}
const expandDow = s => {
  const out = []
  for (const part of String(s).split(',')) {
    const m = part.match(/^(\d)-(\d)$/)
    if (m) {
      let a = Number(m[1]); const b = Number(m[2])
      while (a !== b) { out.push(a); a = (a + 1) % 7 }
      out.push(b)
    } else if (/^\d$/.test(part)) {
      out.push(Number(part))
    } else return null
  }
  return [...new Set(out)].sort((a, b) => a - b)
}
const setBuilderFromCron = cron => {
  const p = String(cron || '').trim().split(/\s+/)
  if (p.length !== 5) { form.scheduleType = 'advanced'; form.advancedCron = cron || ''; return }
  const [mi, ho, dom, mon, dow] = p
  if (mi.startsWith('*/') && ho === '*' && dom === '*' && mon === '*' && dow === '*') {
    form.scheduleType = 'interval'; form.intervalUnit = 'minute'; form.intervalValue = Number(mi.slice(2)) || 1
    return
  }
  if (/^\d+$/.test(mi) && ho === '*' && dom === '*' && mon === '*' && dow === '*') {
    form.scheduleType = 'interval'; form.intervalUnit = 'hour'; form.intervalValue = 1
    form.time = `00:${pad2(mi)}`
    return
  }
  if (/^\d+$/.test(mi) && ho.startsWith('*/') && dom === '*' && mon === '*' && dow === '*') {
    form.scheduleType = 'interval'; form.intervalUnit = 'hour'; form.intervalValue = Number(ho.slice(2)) || 1
    form.time = `00:${pad2(mi)}`
    return
  }
  if (!/^\d+$/.test(mi) || !/^\d+$/.test(ho)) { form.scheduleType = 'advanced'; form.advancedCron = cron; return }
  form.time = `${pad2(ho)}:${pad2(mi)}`
  if (dom !== '*' && mon === '*' && dow === '*') {
    form.scheduleType = 'monthly'; form.monthDay = Number(dom) || 1
  } else if (dom === '*' && mon === '*' && dow !== '*') {
    form.scheduleType = 'weekly'
    form.weekdays = expandDow(dow) || [1, 2, 3, 4, 5]
  } else if (dom === '*' && mon === '*' && dow === '*') {
    form.scheduleType = 'daily'
  } else {
    form.scheduleType = 'advanced'; form.advancedCron = cron
  }
}
const toggleWeekday = d => {
  form.weekdays = form.weekdays.includes(d)
    ? form.weekdays.filter(x => x !== d)
    : [...form.weekdays, d]
}
/** 切到「高级」时把当前可视化规则带进 cron 输入框，避免用户面对一个空框重写一遍 */
const setScheduleType = key => {
  if (key === 'advanced' && form.scheduleType !== 'advanced') {
    form.advancedCron = cronValue.value
  }
  form.scheduleType = key
}

let previewSeq = 0
let previewTimer = null
const refreshPreview = () => {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(async () => {
    const cron = cronValue.value
    previewError.value = ''
    if (!cronValid.value) { previewTimes.value = []; return }
    const seq = ++previewSeq
    previewLoading.value = true
    try {
      const r = await previewScheduledCron(cron, form.timezone, 3)
      if (seq !== previewSeq) return
      if (r && r.success !== false) previewTimes.value = r.data || []
      else { previewTimes.value = []; previewError.value = (r && r.msg) || '规则不合法' }
    } catch (e) {
      if (seq === previewSeq) { previewTimes.value = []; previewError.value = e.message || '周期预览失败' }
    } finally {
      if (seq === previewSeq) previewLoading.value = false
    }
  }, 350)
}
watch(() => [cronValue.value, form.timezone], refreshPreview)

const resetForm = () => {
  Object.assign(form, blank())
  templateKey.value = ''
  showSettings.value = false
  Object.keys(errors).forEach(k => { errors[k] = '' })
  previewTimes.value = []
  previewError.value = ''
}
const applyTemplate = t => {
  templateKey.value = t.key
  if (t.blank) { resetForm(); templateKey.value = 'blank'; return }
  resetForm()
  templateKey.value = t.key
  Object.assign(form, t.seed)
  refreshPreview()
}
const applySeed = seed => {
  if (!seed) return
  resetForm()
  if (seed.name) form.name = seed.name
  if (seed.prompt) form.prompt = String(seed.prompt).slice(0, 2000)
  if (seed.agentId) form.agentId = seed.agentId
  if (seed.projectId) form.projectId = seed.projectId
  if (seed.modelRef) form.modelRef = seed.modelRef
  if (seed.timezone) form.timezone = seed.timezone
  if (seed.notifyOn) form.notifyOn = seed.notifyOn
  if (seed.maxRetries != null) form.maxRetries = seed.maxRetries
  if (seed.retryIntervalSec != null) form.retryIntervalSec = seed.retryIntervalSec
  if (seed.timeoutSec != null) form.timeoutSec = seed.timeoutSec
  if (seed.deepThink != null) form.deepThink = !!seed.deepThink
  if (seed.scheduleType) form.scheduleType = seed.scheduleType
  if (seed.weekdays) form.weekdays = seed.weekdays
  if (seed.time) form.time = seed.time
  if (seed.cron) setBuilderFromCron(seed.cron)
  refreshPreview()
}
const openCreate = seed => {
  editing.value = null
  resetForm()
  if (seed && seed.seed) applyTemplate(seed)
  else if (seed) applySeed(seed)
  showEdit.value = true
  loadAgents()
  loadProjects()
  refreshPreview()
}
const cloneSeed = row => ({
  name: (row.name || '未命名') + ' 副本',
  prompt: row.prompt,
  agentId: row.agentId || '',
  projectId: row.projectId || '',
  modelRef: row.modelRef || '',
  timezone: row.timezone,
  notifyOn: row.notifyOn,
  maxRetries: row.maxRetries,
  retryIntervalSec: row.retryIntervalSec,
  timeoutSec: row.timeoutSec,
  deepThink: row.deepThink,
  cron: row.cron
})
const openEdit = row => {
  if (showRuns.value) showRuns.value = false
  editing.value = row
  resetForm()
  Object.assign(form, {
    name: row.name || '', prompt: row.prompt || '', agentId: row.agentId || null,
    projectId: row.projectId || null, timezone: row.timezone || 'Asia/Shanghai',
    modelRef: row.modelRef || '', deepThink: !!row.deepThink, enabled: !!row.enabled,
    notifyOn: row.notifyOn || 'all', maxRetries: row.maxRetries ?? 0,
    retryIntervalSec: row.retryIntervalSec ?? 60, timeoutSec: row.timeoutSec ?? null,
    endAt: row.endAt ? String(row.endAt) : null
  })
  setBuilderFromCron(row.cron)
  showEdit.value = true
  loadAgents()
  loadProjects()
  refreshPreview()
}
const validate = () => {
  Object.keys(errors).forEach(k => { errors[k] = '' })
  if (!form.name.trim()) errors.name = '请填写任务名称'
  if (!form.prompt.trim()) errors.prompt = '请填写执行指令'
  if (!cronValid.value) errors.cron = '执行周期需为 5 段（分 时 日 月 周）'
  if (form.scheduleType === 'weekly' && !form.weekdays.length) errors.cron = '至少选择一个星期'
  if (form.endAt) {
    const t = new Date(String(form.endAt).replace(' ', 'T')).getTime()
    if (Number.isFinite(t) && t <= Date.now()) errors.endAt = '到期时间必须晚于当前时间'
  }
  return !Object.values(errors).some(Boolean)
}
const save = async (forceEnabled) => {
  if (saving.value) return
  if (!validate()) return
  saving.value = true
  const enabled = forceEnabled === true ? true : form.enabled
  try {
    const body = {
      name: form.name.trim(), prompt: form.prompt.trim(),
      agentId: form.agentId || '', projectId: form.projectId || '',
      cron: cronValue.value.trim(), timezone: form.timezone,
      modelRef: form.modelRef || '', deepThink: !!form.deepThink, enabled,
      notifyOn: form.notifyOn, maxRetries: Number(form.maxRetries) || 0,
      retryIntervalSec: Number(form.retryIntervalSec) || 60,
      timeoutSec: form.timeoutSec ? Number(form.timeoutSec) : null,
      endAt: form.endAt || null
    }
    const r = editing.value
      ? await updateScheduledJob(editing.value.id, body)
      : await createScheduledJob(body)
    if (r && r.success !== false) {
      message.success(editing.value ? '已保存' : '已创建')
      showEdit.value = false
      await load()
    } else {
      message.error((r && r.msg) || '保存失败')
    }
  } catch (e) {
    message.error('保存失败：' + (e.message || ''))
  } finally { saving.value = false }
}

// ==================== 格式化 ====================
const parseTs = t => {
  if (!t) return null
  const d = new Date(String(t).replace(' ', 'T'))
  return Number.isFinite(d.getTime()) ? d : null
}
const fmtDateTime = t => {
  const d = parseTs(t)
  if (!d) return '—'
  const now = new Date()
  const sameYear = d.getFullYear() === now.getFullYear()
  return `${sameYear ? '' : d.getFullYear() + '-'}${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}
const fmtDate = t => {
  const d = parseTs(t)
  return d ? `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}` : '—'
}
const fmtTime = t => {
  const d = parseTs(t)
  return d ? `${pad2(d.getHours())}:${pad2(d.getMinutes())}` : '—'
}
const nextLabel = (t, short = false) => {
  const d = parseTs(t)
  if (!d) return '—'
  const now = new Date()
  const day = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const target = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const diff = Math.round((target - day) / 86400000)
  const hm = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
  if (diff === 0) return `今天 ${hm}`
  if (diff === 1) return `明天 ${hm}`
  if (short) {
    const w = '日一二三四五六'[d.getDay()]
    return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} 周${w} ${hm}`
  }
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${hm}`
}
const relative = t => {
  const d = parseTs(t)
  if (!d) return ''
  const ms = d.getTime() - nowTick.value
  if (ms <= 0) return '即将执行'
  const min = Math.round(ms / 60000)
  if (min < 60) return `${min} 分钟后`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时后`
  return `${Math.floor(hour / 24)} 天后`
}
const elapsed = row => {
  const start = parseTs(row.lastRun?.startedAt)
  if (!start) return ''
  const sec = Math.max(0, Math.round((nowTick.value - start.getTime()) / 1000))
  if (sec < 60) return `00:${pad2(sec)}`
  return `${pad2(Math.floor(sec / 60))}:${pad2(sec % 60)}`
}
const runElapsed = x => {
  const start = parseTs(x.startedAt)
  if (!start) return ''
  const sec = Math.max(0, Math.round((Date.now() - start.getTime()) / 1000))
  return sec < 60 ? `${sec} 秒` : `${Math.floor(sec / 60)} 分 ${sec % 60} 秒`
}
const durationText = x => {
  if (!x) return '—'
  if (x.durationMs != null) {
    const ms = Number(x.durationMs)
    return ms >= 60000 ? `${(ms / 60000).toFixed(1)} 分钟` : `${Math.max(1, Math.round(ms / 1000))} 秒`
  }
  const a = parseTs(x.startedAt); const b = parseTs(x.finishedAt)
  if (!a || !b) return '—'
  const ms = b - a
  return ms >= 60000 ? `${(ms / 60000).toFixed(1)} 分钟` : `${Math.max(1, Math.round(ms / 1000))} 秒`
}
const runLabel = s => ({ running: '执行中', succeeded: '成功', failed: '失败', skipped: '已跳过' }[s] || s || '—')
const runStatusClass = s => ({ succeeded: 'ok', failed: 'bad', skipped: 'skip', running: 'run' }[s] || '')
const lastClass = s => ({ succeeded: 'ok', failed: 'bad', skipped: 'warn' }[s] || '')
const shortError = e => {
  const s = String(e || '').replace(/\s+/g, ' ').trim()
  return s.length > 80 ? s.slice(0, 80) + '…' : s
}
const dotsOf = r => {
  const list = (r.health?.recentRuns || []).slice().reverse()
  return list.map(s => ({ succeeded: 's', failed: 'f', skipped: 'k', running: 'r' }[s] || 'm'))
}
const healthText = r => {
  const h = r.health
  if (!h) return ''
  const finished = (h.runs7d || 0)
  return h.successRate7d == null ? `近 7 天 ${finished} 次` : `${h.successRate7d}% · ${finished} 次`
}
const healthTitle = r => {
  const h = r.health
  if (!h) return ''
  return `近 7 天运行 ${h.runs7d} 次，失败 ${h.failed7d} 次` + (h.consecutiveFailures ? `，已连续失败 ${h.consecutiveFailures} 次` : '')
}
const dayLabel = d => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const target = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const diff = Math.round((today - target) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}
const previewLabel = t => {
  const d = parseTs(t)
  return d ? `${fmtDateTime(t)} 周${'日一二三四五六'[d.getDay()]}` : '—'
}
const previewDate = t => {
  const d = parseTs(t)
  return d ? `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}` : '—'
}
const previewClock = t => fmtTime(t)
const previewWeek = t => {
  const d = parseTs(t)
  return d ? `周${'日一二三四五六'[d.getDay()]}` : ''
}

// ==================== 深链 / 草稿 ====================
const applyJobDeepLink = async () => {
  const id = typeof route.query.jobId === 'string' ? route.query.jobId : ''
  if (!id) return
  router.replace({ path: route.path, query: { ...route.query, jobId: undefined } })
  const row = rows.value.find(x => x.id === id)
  if (!row) { message.warning('这条定时任务不在你的列表里（可能已删除，或不是你建的）'); return }
  openEdit(row)
  locateField('jobModelRef')
}
const applyDraft = () => {
  const hasDraftQuery = route.query.draft === '1'
  let raw = null
  try { raw = sessionStorage.getItem('wenqu.scheduledDraft') } catch (e) { /* ignore */ }
  if (raw) {
    try {
      const seed = JSON.parse(raw)
      openCreate(seed)
    } catch (e) { /* ignore */ }
    try { sessionStorage.removeItem('wenqu.scheduledDraft') } catch (e) { /* ignore */ }
  }
  if (hasDraftQuery) {
    router.replace({ path: route.path, query: { ...route.query, draft: undefined } })
  }
}

onMounted(async () => {
  await load()
  applyDraft()
  applyJobDeepLink()
  schedulePoll()
  clockTimer = setInterval(() => { nowTick.value = Date.now() }, 30000)
  document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
  clearTimeout(pollTimer)
  clearInterval(clockTimer)
  clearTimeout(undoTimer)
  clearTimeout(previewTimer)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
/* 页头与筛选：沿用 app.css 的底与描边，只补本页信息密度 */
.sched-page { position: relative; }
.head-stats { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.head-stats b { color: var(--app-text); font-weight: 600; }
.head-stats .slash { color: var(--app-border-strong); margin: 0 2px; }
.attn-chip { flex: none; display: inline-flex; align-items: center; gap: 4px; height: 22px;
  padding: 0 8px; border-radius: 999px; background: var(--app-danger-weak); color: var(--app-danger);
  font-size: 11.5px; cursor: pointer; }
.attn-chip :deep(.anticon) { font-size: 11px; }
.vline { width: 1px; height: 14px; background: var(--app-border); flex: none; }
.head-hint { font-size: 12px; color: var(--app-text3); min-width: 0; flex: 1 1 auto;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.head-r { flex: none; display: flex; align-items: center; gap: 8px; margin-left: auto; }
.sched-search { width: 180px; }
.batch-group { display: flex; align-items: center; padding-left: 12px; border-left: 1px solid var(--app-border); }
.batch-on { color: var(--app-accent); border-color: var(--app-accent); }

.filter-row { flex: none; height: 40px; display: flex; align-items: center; gap: 6px; padding: 0 20px; }
.fchip { height: 26px; display: inline-flex; align-items: center; gap: 5px; padding: 0 10px;
  border: 1px solid transparent; border-radius: 999px; background: transparent;
  font-size: 12px; color: var(--app-text2); cursor: pointer; }
.fchip b { font-weight: 500; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.fchip.on { background: var(--app-panel); border-color: var(--app-border); color: var(--app-text);
  box-shadow: var(--app-shadow-sm); }
.fchip.on b { color: var(--app-accent); }
.fchip.warn { color: var(--app-danger); }
.fchip.warn b { color: var(--app-danger); }
.sort-dd { margin-left: auto; }
.sort { height: 26px; display: inline-flex; align-items: center; gap: 5px; border: none;
  background: transparent; font-size: 12px; color: var(--app-text3); cursor: pointer; }

/* 接下来时间条 */
.rail { min-height: 38px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 6px 12px; background: var(--app-panel); border: 1px solid var(--app-border);
  border-radius: 8px; box-shadow: var(--app-shadow-sm); margin-bottom: 10px; font-size: 12px; }
.rail .lbl { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text3); flex: none; }
.rail-item { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text2); white-space: nowrap; }
.rail-item b { font-weight: 500; color: var(--app-text); }
.rail .when { color: var(--app-accent); }
.rail .rel { color: var(--app-text3); font-size: 11.5px; }
.rail .sep { color: var(--app-border-strong); }
.rail-all { margin-left: auto; color: var(--app-text3); font-size: 11.5px; }

/* 卡片 */
.sched-list { display: flex; flex-direction: column; gap: 9px; }
.sched-card { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  padding: 10px 14px; box-shadow: var(--app-shadow-sm); display: flex; flex-direction: column;
  gap: 5px; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.sched-card:hover { border-color: var(--app-border-strong); }
.sched-card.is-running { border-color: var(--app-accent-border); box-shadow: 0 0 0 2px rgba(46, 107, 230, .06); }
.sched-card.is-attention { border-color: #f0c9bd; }
.sched-card.is-disabled { opacity: .74; }
.sched-card.is-selected { background: #fbfcff; border-color: var(--app-accent-border); }
.check { width: 14px; height: 14px; border-radius: 4px; border: 1px solid var(--app-border-strong);
  background: var(--app-panel); flex: none; position: relative; }
.check.on { border-color: var(--app-accent); background: var(--app-accent); }
.check.on::after { content: ''; position: absolute; left: 4px; top: 1px; width: 4px; height: 8px;
  border: solid #fff; border-width: 0 1.6px 1.6px 0; transform: rotate(45deg); }
.dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.dot.ok { background: var(--app-ok); }
.dot.bad { background: var(--app-danger); }
.dot.muted { background: #c1c7cf; }
.dot.run { background: var(--app-accent); box-shadow: 0 0 0 3px rgba(46, 107, 230, .12); }
.tc-top { display: flex; align-items: center; gap: 8px; min-height: 20px; flex-wrap: wrap; }
.tc-name { font-size: 14px; font-weight: 600; color: var(--app-text); white-space: nowrap;
  max-width: 320px; overflow: hidden; text-overflow: ellipsis; }
.tag { flex: none; display: inline-flex; align-items: center; gap: 4px; height: 19px; padding: 0 7px;
  border-radius: 4px; font-size: 11px; line-height: 1; }
.tag.run { background: var(--app-accent-weak); color: var(--app-accent); }
.tag.run .pulse { width: 5px; height: 5px; border-radius: 50%; background: var(--app-accent);
  animation: pulse 1.6s ease-in-out infinite; }
.tag.attn { background: var(--app-danger-weak); color: var(--app-danger); }
.tag.off { background: #f2f4f6; color: var(--app-text3); }
.tag.on { background: var(--app-ok-weak); color: var(--app-ok); }
.tag.warn { background: var(--app-warn-weak); color: var(--app-warn-text); }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .28; } }
.chip { flex: none; display: inline-flex; align-items: center; gap: 4px; height: 19px; padding: 0 6px;
  border-radius: 4px; background: var(--app-panel-2); color: var(--app-text3); font-size: 11px; }
.tc-right { margin-left: auto; display: flex; align-items: center; gap: 12px; flex: none; }
.more { border: none; background: none; padding: 0 2px; color: var(--app-text3); font-size: 15px;
  letter-spacing: 1px; line-height: 1; cursor: pointer; }
.tc-sched { display: flex; align-items: center; gap: 7px; min-height: 19px; font-size: 12px;
  color: var(--app-text3); min-width: 0; flex-wrap: wrap; }
.sched-main { flex: none; font-size: 12.5px; font-weight: 500; color: var(--app-text); }
.tz { flex: none; font-size: 11.5px; color: var(--app-text3); }
.cron { flex: none; font-family: var(--app-font-mono, ui-monospace, Menlo, monospace); font-size: 10.5px;
  line-height: 16px; padding: 0 5px; border-radius: 4px; background: var(--app-panel-2); color: var(--app-text3); }
.next { margin-left: auto; flex: none; font-size: 12px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.next b { color: var(--app-accent); font-weight: 600; }
.next .rel { color: var(--app-text3); font-size: 11.5px; }
.tc-prompt { display: flex; align-items: center; gap: 12px; min-width: 0; }
.prompt-text { min-width: 0; flex: 1 1 auto; font-size: 12.5px; color: var(--app-text2);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tc-meta { flex: none; display: flex; gap: 8px; font-size: 11.5px; color: var(--app-text3); }
.tc-meta .sep { color: var(--app-border-strong); }
.tc-foot { display: flex; align-items: center; gap: 9px; min-height: 24px; flex-wrap: wrap; }
.last { flex: none; display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px;
  color: var(--app-text3); font-variant-numeric: tabular-nums; }
.last.ok { color: var(--app-ok); }
.last.bad { color: var(--app-danger); }
.last.warn { color: var(--app-warn); }
.link { border: none; background: none; padding: 0; font-size: 11.5px; color: var(--app-accent);
  white-space: nowrap; cursor: pointer; }
.link.muted { color: var(--app-text3); }
.health { display: inline-flex; align-items: center; gap: 3px; flex: none; }
.hd { width: 6px; height: 6px; border-radius: 50%; }
.hd.s { background: var(--app-ok); }
.hd.f { background: var(--app-danger); }
.hd.k { background: var(--app-warn); }
.hd.r { background: var(--app-accent); }
.hd.m { background: #e2e6ea; }
.hsum { font-size: 11px; color: var(--app-text3); font-variant-numeric: tabular-nums; }
.ops { margin-left: auto; display: flex; align-items: center; gap: 8px; flex: none; }
.ops .run { color: var(--app-accent); border-color: var(--app-accent-border); }
.tc-alert { display: flex; align-items: center; gap: 6px; min-height: 25px; padding: 4px 9px;
  border-radius: 6px; background: var(--app-danger-weak); font-size: 11.5px; color: var(--app-danger); }
.tc-alert .txt { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tc-alert .sp { flex: 1; }
.tc-alert .link { color: var(--app-danger); flex: none; }

/* 空状态 */
.sched-empty { text-align: center; padding: 28px 16px; }
.empty-title { margin: 0 0 6px; font-weight: 500; }
.tpl-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px;
  max-width: 720px; margin: 18px auto 0; }
.tpl-card { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; padding: 12px;
  border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel); cursor: pointer;
  text-align: left; }
.tpl-card:hover { border-color: var(--app-accent-border); background: var(--app-accent-weak); }
.tpl-icon { color: var(--app-accent); font-size: 15px; }
.tpl-name { font-size: 13px; font-weight: 500; color: var(--app-text); }
.tpl-desc { font-size: 11.5px; color: var(--app-text3); }

/* 批量与撤销 */
.batch-bar { position: absolute; left: 50%; bottom: 22px; transform: translateX(-50%);
  display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px;
  background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 10px;
  box-shadow: 0 12px 32px rgba(16, 24, 40, .16); z-index: 20; }
.batch-bar .cnt { font-size: 12px; color: var(--app-text2); white-space: nowrap; }
.batch-bar .cnt b { color: var(--app-accent); font-weight: 600; }
.batch-bar .div { width: 1px; height: 18px; background: var(--app-border); }
.danger-text { color: var(--app-danger); }
.undo-toast { position: absolute; left: 20px; bottom: 24px; display: flex; align-items: center; gap: 8px;
  height: 38px; padding: 0 13px; background: #1f2329; color: #fff; border-radius: 8px;
  box-shadow: 0 10px 28px rgba(16, 24, 40, .24); font-size: 12px; z-index: 30; }
.undo-toast .undo { border: none; background: none; color: #8db4ff; font-size: 12px; cursor: pointer; }

/* 弹窗 */
:deep(.sched-modal .ant-modal-body) { padding: 14px 24px 0; }
.mbody { display: flex; flex-direction: column; gap: 12px; }
.tpl-row { display: flex; gap: 8px; }
.tpl { flex: 1; display: flex; align-items: center; gap: 7px; height: 38px; padding: 0 10px;
  border: 1px solid var(--app-border); border-radius: 7px; font-size: 12px; color: var(--app-text2);
  background: var(--app-panel); cursor: pointer; }
.tpl.on { border-color: var(--app-accent); background: var(--app-accent-weak); color: var(--app-accent);
  font-weight: 500; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) 240px; gap: 18px; }
.form-col { min-width: 0; }
.field { margin-bottom: 10px; }
.flabel { display: flex; align-items: center; gap: 6px; margin-bottom: 5px; font-size: 12px; color: var(--app-text2); }
.flabel .opt { font-size: 11px; color: var(--app-text3); }
.flabel .req { color: var(--app-danger); }
.row2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.ferr { margin-top: 4px; font-size: 11.5px; color: var(--app-danger); }
.fhint { font-size: 11.5px; color: var(--app-text3); line-height: 1.6; }
.seg { display: flex; gap: 4px; background: var(--app-panel-2); padding: 3px; border-radius: 7px; }
.seg .s { flex: 1; height: 24px; border: none; background: transparent; border-radius: 5px;
  font-size: 12px; color: var(--app-text2); cursor: pointer; }
.seg .s.on { background: var(--app-panel); color: var(--app-accent); font-weight: 500;
  box-shadow: var(--app-shadow-sm); }
.week { display: flex; gap: 5px; margin-top: 8px; }
.wd { width: 30px; height: 26px; border: 1px solid var(--app-border); border-radius: 5px;
  background: var(--app-panel); font-size: 12px; color: var(--app-text2); cursor: pointer; }
.wd.on { border-color: var(--app-accent); background: var(--app-accent-weak); color: var(--app-accent); }
.inline-row { display: flex; align-items: center; gap: 7px; margin-top: 8px; flex-wrap: wrap; }
.inline-label { font-size: 12px; color: var(--app-text2); }
.timerow { display: flex; gap: 8px; margin-top: 8px; }
.tz-select { flex: 1; min-width: 0; }
.adv-box { margin-top: 8px; }
.summary { margin-top: 9px; display: flex; align-items: center; gap: 6px; padding: 7px 10px;
  border-radius: 6px; background: var(--app-accent-weak); font-size: 11.5px; color: var(--app-text2); }
.summary :deep(.anticon) { color: var(--app-accent); }
.summary b { color: var(--app-text); font-weight: 600; }
.summary .dim { color: var(--app-text3); }
.setfold { margin-top: 9px; display: flex; align-items: center; gap: 7px; min-height: 32px; padding: 0 10px;
  border: 1px solid var(--app-border); border-radius: 7px; background: var(--app-panel-2);
  font-size: 12px; color: var(--app-text2); cursor: pointer; }
.setfold .vals { margin-left: auto; font-size: 11px; color: var(--app-text3); }
.setfold .rotated { transform: rotate(180deg); }
.settings-box { margin-top: 8px; padding: 10px 12px; border: 1px solid var(--app-border); border-radius: 7px; }
.preview-col { min-width: 0; }
.preview { border: 1px solid var(--app-border); border-radius: 8px; background: var(--app-panel-2);
  padding: 9px 10px; }
.preview .ph { font-size: 10.5px; color: var(--app-text3); letter-spacing: .04em; margin-bottom: 7px; }
.pvcard { background: var(--app-panel); border: 1px solid var(--app-border); border-radius: 7px;
  padding: 8px 9px; box-shadow: var(--app-shadow-sm); }
.pv-top { display: flex; align-items: center; gap: 6px; }
.pv-name { font-size: 12px; font-weight: 600; color: var(--app-text); overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.pv-tag { flex: none; font-size: 10px; line-height: 16px; padding: 0 5px; border-radius: 3px;
  background: var(--app-ok-weak); color: var(--app-ok); }
.pv-line { margin-top: 5px; display: flex; align-items: center; gap: 5px; font-size: 10.5px;
  color: var(--app-text3); }
.pv-line b { color: var(--app-text); font-weight: 500; font-size: 11px; }
.pv-prompt { margin-top: 5px; font-size: 10.5px; color: var(--app-text2); white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; }
.pv-foot { margin-top: 6px; display: flex; align-items: center; gap: 4px; font-size: 10px;
  color: var(--app-text3); }
.nextlist { margin-top: 10px; }
.nextlist .t { font-size: 11px; color: var(--app-text3); margin-bottom: 5px; }
.nextrow { display: flex; align-items: center; gap: 6px; min-height: 24px; font-size: 11.5px;
  color: var(--app-text2); border-bottom: 1px dashed var(--app-border); }
.nextrow:last-child { border-bottom: none; }
.nextrow .d { color: var(--app-text); font-variant-numeric: tabular-nums; }
.nextrow .w { margin-left: auto; color: var(--app-text3); }
.nextrow.dim { color: var(--app-text3); }
.mtip { margin-top: 8px; font-size: 10.5px; line-height: 1.6; color: var(--app-text3); }
.mfoot { display: flex; align-items: center; gap: 8px; padding: 14px 0 16px; }
.mfoot .sp { flex: 1; }

/* 抽屉 */
:deep(.sched-drawer .ant-drawer-header) { padding: 16px 20px 12px; }
.dtitle { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dtitle .name { font-size: 16px; font-weight: 600; color: var(--app-text); }
.dsub { margin-top: 4px; font-size: 12px; color: var(--app-text3); white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis; }
.sumrow { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-top: 14px; }
.sumcell { background: var(--app-panel-2); border: 1px solid var(--app-border); border-radius: 8px;
  padding: 8px 10px; }
.sumcell .k { font-size: 11px; color: var(--app-text3); }
.sumcell .v { margin-top: 2px; font-size: 18px; font-weight: 600; color: var(--app-text);
  font-variant-numeric: tabular-nums; }
.sumcell .v small { font-size: 11px; font-weight: 400; color: var(--app-text3); margin-left: 2px; }
.nextcard { margin-top: 10px; display: flex; align-items: center; gap: 10px; padding: 9px 11px;
  border: 1px solid var(--app-accent-border); border-radius: 8px; background: var(--app-accent-weak); }
.nextcard > :deep(.anticon) { color: var(--app-accent); }
.nextcard .t { font-size: 12px; color: var(--app-text2); }
.nextcard .t b { color: var(--app-text); font-weight: 600; }
.nextcard .t .rel { color: var(--app-text3); font-size: 11.5px; }
.nextcard .ops { margin-left: auto; }
.dtabs { margin-top: 12px; display: flex; align-items: center; gap: 20px; border-bottom: 1px solid var(--app-border); }
.dtab { position: relative; height: 34px; border: none; background: transparent; font-size: 12.5px;
  color: var(--app-text2); cursor: pointer; }
.dtab.on { color: var(--app-accent); font-weight: 500; }
.dtab.on::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px;
  background: var(--app-accent); }
.dfilter { display: flex; align-items: center; gap: 8px; margin-top: 9px; font-size: 11.5px;
  color: var(--app-text3); }
.dfilter .f { display: inline-flex; align-items: center; gap: 4px; height: 22px; padding: 0 8px;
  border: none; background: transparent; border-radius: 5px; color: var(--app-text2); cursor: pointer; }
.dfilter .f.on { background: var(--app-panel-2); color: var(--app-text); font-weight: 500; }
.dfilter .f b { font-weight: 500; color: var(--app-text3); }
.dfilter .right { margin-left: auto; }
.dgroup { font-size: 10.5px; color: var(--app-text3); letter-spacing: .05em; margin: 9px 0 2px; }
.drun { padding: 8px 0; border-bottom: 1px solid #f1f3f5; }
.drun-top { display: flex; align-items: center; gap: 7px; min-height: 19px; font-size: 12px; flex-wrap: wrap; }
.rstatus { width: 15px; height: 15px; border-radius: 50%; display: inline-flex; align-items: center;
  justify-content: center; flex: none; }
.rstatus.ok { background: var(--app-ok-weak); color: var(--app-ok); }
.rstatus.bad { background: var(--app-danger-weak); color: var(--app-danger); }
.rstatus.skip { background: var(--app-warn-weak); color: var(--app-warn); }
.rstatus.run { background: var(--app-accent-weak); color: var(--app-accent); }
.rstatus :deep(.anticon) { font-size: 9px; }
.rtime { color: var(--app-text); font-weight: 500; font-variant-numeric: tabular-nums; }
.rtag { font-size: 10.5px; line-height: 16px; padding: 0 5px; border-radius: 3px;
  background: var(--app-panel-2); color: var(--app-text3); }
.rtag.warn { background: var(--app-warn-weak); color: var(--app-warn-text); }
.rdur { color: var(--app-text3); font-size: 11.5px; }
.drun-top .ops { margin-left: auto; gap: 10px; }
.ranswer { margin: 4px 0 0 22px; font-size: 11.5px; line-height: 1.55; color: var(--app-text2);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: pre-wrap; }
.ranswer.open { display: block; -webkit-line-clamp: unset; }
.answer-toggle { margin: 3px 0 0 22px; border: none; background: none; padding: 0; font-size: 11px;
  color: var(--app-accent); cursor: pointer; }
.rerr { margin: 5px 0 0 22px; padding: 7px 9px; border-radius: 6px; background: var(--app-danger-weak); }
.rerr .line { display: flex; align-items: flex-start; gap: 5px; font-size: 11.5px; line-height: 1.55;
  color: var(--app-danger); }
.rerr .tips { margin: 4px 0 0 16px; padding: 0 0 0 12px; font-size: 11px; line-height: 1.7;
  color: var(--app-text3); }
.rerr .code { margin-top: 5px; display: flex; align-items: center; gap: 8px; background: var(--app-code-bg, #f6f8fa);
  border-radius: 5px; padding: 5px 8px; font-family: var(--app-font-mono, ui-monospace, Menlo, monospace);
  font-size: 10.5px; color: var(--app-text2); }
.rerr .code code { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dconfig { padding-top: 12px; }
.cfg-row { display: flex; align-items: baseline; gap: 12px; min-height: 30px; padding: 5px 0;
  border-bottom: 1px solid #f1f3f5; font-size: 12px; }
.cfg-row span { flex: none; width: 84px; color: var(--app-text3); }
.cfg-row b { color: var(--app-text); font-weight: 500; }
.cfg-row code { font-family: var(--app-font-mono, ui-monospace, Menlo, monospace); font-size: 11px;
  background: var(--app-panel-2); padding: 1px 5px; border-radius: 4px; }
.cfg-prompt { margin-top: 12px; }
.cfg-prompt .k { font-size: 11px; color: var(--app-text3); margin-bottom: 4px; }
.cfg-prompt .v { font-size: 12px; line-height: 1.7; color: var(--app-text2); white-space: pre-wrap;
  background: var(--app-panel-2); border-radius: 6px; padding: 8px 10px; }
.dfoot { margin-top: 14px; padding-top: 10px; border-top: 1px solid var(--app-border);
  font-size: 11px; line-height: 1.7; color: var(--app-text3); }

@media (max-width: 1280px) {
  .head-hint { display: none; }
  .sched-search { width: 150px; }
  .form-grid { grid-template-columns: 1fr; }
  .preview-col { display: none; }
}
</style>
