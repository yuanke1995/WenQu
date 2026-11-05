# 已知雷区清单（真实回归史提炼）

> 全部条目来自本仓及兄弟仓的真实踩雷/回归史，不是理论风险。**修过 ≠ 模式绝迹**——同型坑反复踩过的不止一处（如 GLOBAL_TABLES 三踩）。
> 用法：改到对应域的代码前，先扫一遍对应节；命中条目就把「回归验证」加进完工清单。

## 后端（wenqu）

1. **租户行级过滤**：新全局表不进 `WenquTenantLineHandler.GLOBAL_TABLES`（src/main/java/com/wenqu/ai/config/WenquTenantLineHandler.java）就会被拼 tenant 条件查出空/错数据——已三踩。新表落库前必查。
2. **actable 与 MyBatis-Plus 是两套命名**：actable `@Column` 与 MP 驼峰映射不一致时**运行时才炸列名**（c_sys_* 建表踩过）。建表后跑一次真实查询再交付。
3. **mapper XML 只扫 `resources/mapper/**`**——放别处静默失效，启动不报错。
4. **fastjson 序列化 Map 里的 Date 走时间戳**，不走全局日期格式——接口出参别用 Map 裸装日期。
5. **集合字段校验 + 字段可缺失**：必须 LinkedHashSet 或 null 守卫（saveModels NPE 教训）。
6. **线程池拒投**：`execute` 返回 false 被忽略 = 任务静默丢失（dispatch 队列满踩过）；「拒投后仍持锁等待」是死锁族（2026-11-04 四连）。拒投必须有显式出路。
7. **思考模型的辅助调用**（压缩/摘要/判定）：走 reasoningOffBody 模式（关思考字段 + 关不掉就放宽 maxTokens），否则思考吃光 max_tokens、正文本空。
8. **事务内改缓存/外部调用**注意时序击穿（RBAC 3630397f：事务内做失效，把「即时生效」弄失效）。
9. **删迁移类**：开发阶段迁移用完即删是拍板政策，但删 SchemaMigrator 类的东西后，存量库的列差要手工 ALTER（新装库走 schema.sql 不受影响）。

## 前端（web/，antdv + vite）

1. **antdv 已知版本坑**：`#optionLabel` 对未知值插槽收到 undefined 直接崩（4.2.6，须容错）；1.7.8 无 a-image（灯箱须命令式挂 body）；checkbox-group 会被注入 `align-self:center`。
2. **tooltip + disabled + button-group 同用必炸**。
3. **模板内 `fixed` 浮层会被祖先 z-index/transform 上下文圈住**（页头 z-index:198 圈住灯箱案例）；全屏浮层 teleport 到 body，别赌模板内 z-index。
4. **计算属性直读 DOM 须先读 reactive 缓存**，否则 ResizeObserver 重算死循环；遮挡/内置浏览器场景 rAF/RO 全饿死。
5. **`el.__vue__` 是最内层 vm，比业务组件差一代**——找目标 vm 要向上回溯；`element.__vue__` 同理。
6. **iframe 里的 history.go(-1) 会穿透联合会话历史回退宿主**（SuccessResult 1 秒倒计时跳转踩过）——弹窗内跳转用显式路由。
7. **hadToken 类判定须快照发出时刻**：刷新掉登录 = 早于守卫恢复的裸请求 401 被误判「会话过期」。
8. **控制台报错行号/文件名与磁盘对不上** = 旧标签页化石代码，硬刷新再排查，别顺着错栈修。

## 三仓家族（dynamic-form / dynamic-form-vue / wenqu 联动面）

1. **设计器撤销机制**：撤销两帧重灌、属性直改不走 mutation、基线两拍归一化、设置类开关不进撤销栈、渐进挂载循环提前终止——改编辑器相关代码前先读懂这套约定再动手。
2. **组件上屏前 id 必须换 uuid**：c_table/c_table_column 用组件 id 当物理主键，撞了炸保存。
3. **子表 prop = 物理表名全库唯一**；克隆挂靠要递归迁移孙表行（copyNestChildRows），脏列/NPE 三层雷都在这条链上。
4. **列宽双单位（px 固定 / % 自适应）**：「老表单 px 行为零变化」是拍板口径——动渲染端（renderColPx 家族）必须跑存量表单回归。
5. **AI 契约三处同步**：提示词源（extract-component-spec.mjs）+ 前端校验器 + 自测；extract 必须放行信封（首版漏判把真补丁冤杀三连）。
6. **集成边界必须真实数据验**：仿真绕过 extract 时单测全绿、真机照炸（原话教训）。大表 schema 在 ~2 万字处的模型输出腐损属于此类边界。

## 假绿 / 假故障（验证反模式）

1. **iab 截图可能是过期合成帧**；弹层开闭以 DOM 为准；验 DOM 组件必须连宿主非 scoped 块的 CSS 一起搬；遮挡页签计时器 1s 节流 + rAF 饿死（截图强制出帧）。
2. **验证栈会遭并行会话干扰**（配置被改回、进程被杀、git 被外部暂存）——验证要验提交态（git worktree HEAD + node_modules 软链）。
3. **顺手修别杀邻居**：关面板时顺手 closeLightbox 杀掉浮窗预览的案例——收尾阶段自查「这次改动是否波及无关路径」。
4. **「被删/没生效」先查配置语义**（isHiddenHelp 勾选案例），别急着当回归修。
5. **登录/会话类「bug」先核对账号口径**：登录只认 uid=登录账号，username=昵称仅展示。

## 流程雷

1. **并行会话共用暂存区**：`git add` 会把别人的 hunk 卷进你的提交（ManualSeedService rename 实际卷入过 14380d7、靠事后剥离才干净）。只提自己的 hunk：`git hash-object -w <file>` + `git update-index --cacheinfo`。
2. **提交后 `git show --stat` 是必须步骤**，不是仪式——发现卷入，`git reset HEAD~1 -- <path>` + `--amend` 剥离。
3. **验证产物通道的假象**：iab 里截图/动效可能是合成帧；「产品通道没有 X 格式」先确认产物通道真实支持面（gif/mp4 案例）。
