# A · 项目容器：给问渠补上「围绕一件事反复干活」的那个根

> 立卡日 2026-10-17 ｜ 状态：**待批立项**（§六 两个开放项定了才开工）
> 上游：`docs/positioning-decisions-2026q4.md`（D3 = 容器优先）、`docs/positioning-2026q4.md` §五
> 规格对齐：`docs/p5-agent-as-tool-2026q4.md`、`docs/p6-ragservice-split-2026q4.md`
> 一句话：**执行能力早就是项目级的，容器却还停在会话级，成果散在用户级——本卡补的是中间那个聚合根。**

---

## 〇、为什么现在必须补（查实结论，非推测）

### 三条源码自认（本仓自己写下来的）

| 出处 | 原话 |
|---|---|
| `SandboxService.java:21` | `workdirPath 传 null：那是新栈「项目工作目录」的落点，本工程没有项目/工作区概念` |
| `ScheduledJobService.java:41` | `去掉 project（项目）依赖——本工程没有项目概念` |
| `Artifact.java:12` | `归属用户（不是会话）：会话可以删、可以清理，但产出的成果仍归人生` |

第一条尤其说明问题：**接线的口子早就留好了，一直没人填。**

### 一条硬取证

```
grep -rn "tenantId\|tenant_id" src/main/java web/src  → 0
grep -rn "projectId\|project_id" src/main/java web/src → 0
54 张 c_ai_* 表 → 无 project / workspace 实体
```

**问渠没有"工作容器"这个维度。** 一切持续性的载体都是**会话**：todo 挂会话（`c_ai_session.todos`）、沙盒目录按会话切分、计划卡挂消息行、审批挂 turn。**换个会话 = 换一个世界**：文件不跟过去、进度不跟过去、产物回不到上下文。

### 后果不是"少个功能"，是三条线同时断

| 断在哪 | 具体表现 | 上游卡的对应 |
|---|---|---|
| **差异化** | 定位稿 §四-2 把"智能体即服务"列为主场（DSH 结构上做不到）。但审计四条对外通道：MCP **无状态一次性**、对外工作流**排除人工审核**、分享页**不暴露产物**——**没有一条能承载"多轮往返 + 带交付物"的业务流程** | `positioning-decisions` §三 |
| **治理闭环** | "缺口补块"是护城河的一环（差评/无命中 → 补文档）。真实产物显示用户在做"条文核查""查证汇总"——**核查结论就是质量反馈，但现在只落在文件里，回不到知识库** | `positioning-2026q4` §四-2 |
| **用户体感** | 57 个真实产物（`data/artifacts/`）在库里是 `uid` 下一张**扁平表**、只能按文件名搜、**没有分组、回不到产生它的上下文**。用户已经在拿它干项目（官网规划、考勤方案、条文核查），产品却只当它是一堆附件 | `positioning-decisions` §二 |

### 一句话

**执行能力是"项目级"的，容器是"会话级"的，成果是"用户级"的，而把三者串起来的那个东西不存在。**

---

## 一、设计：Project 是什么，不是什么

### 是

一个**属于单个用户**的、**可长期存在**的工作容器，把「一个工作目录 + 若干次会话 + 若干份产物 + 知识/智能体配置」收在一个根下。

```
Project（归属 uid）
  ├─ workdir_path   项目级工作目录（沙盒在此落盘，跨会话、跨容器重建都还在）
  ├─ conversations  多次讨论（session.project_id）
  ├─ artifacts      多份交付物（artifact.project_id）
  ├─ knowledge/agent 该项目默认用哪套知识范围与助手（可为空=跟随使用者可见范围）
  └─ scheduled     定时任务（可选，二期）
```

### 不是（写下来是为了挡住下一轮"顺手也做了吧"）

| 不是 | 理由 |
|---|---|
| **不是团队协作空间** | 项目归属 `uid`，不是部门也不是租户。D1=A（单客户私有化）且 `tenantId` 零命中——**引入共享项目等于引入一套新的权限模型**，那不是本卡范围 |
| **不是编排单元** | 不装工作流定义、不做画布。工作流是"怎么跑"，项目是"在哪跑、跑出了什么"。定位稿 §四-3 明确不做编排广度竞赛 |
| **不是新的权限墙** | `project` 不改检索可见性。库里 `walled` 口径（登录页父轮不限库）是 P5 有意解绑的，别顺手加回来 |
| **不是必填概念** | **默认项目自动创建**。用户不建项目也能照常问答——只是产物开始有归属。这是本卡能不能不破坏现有体验的关键 |

### §一-5 · 最小字段集（对齐 `Yuxi` 的已验证形状，砍到够用）

```sql
CREATE TABLE IF NOT EXISTS `c_ai_project` (
    `id`             VARCHAR(64)  NOT NULL COMMENT '项目 UUID',
    `uid`            VARCHAR(64)  NOT NULL COMMENT '归属用户（单用户容器，不做共享）',
    `name`           VARCHAR(255) DEFAULT NULL COMMENT '项目名；implicit 项目可为空（前端显示「默认工作区」）',
    `selection_status` VARCHAR(20) NOT NULL DEFAULT 'selectable' COMMENT 'implicit=每人一个自动默认 / selectable=用户可显式选',
    `workdir_path`   VARCHAR(512) NOT NULL COMMENT '项目工作目录（相对 user-data 根的 POSIX 相对路径；provisioner 侧 normalize_workdir_path 会拒绝对路径与非法分量）',
    `directory_mode` VARCHAR(20)  NOT NULL DEFAULT 'managed' COMMENT 'managed=平台托管 / linked=关联已有目录（二期）',
    `status`         VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active / archived',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT '幂等创建键（自动建默认项目时防并发重复插）',
    `create_time`    DATETIME     NOT NULL,
    `update_time`    DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_uid_implicit` (`uid`, `selection_status`, `deleted`),
    KEY `idx_project_uid` (`uid`)
) COMMENT='项目容器：把工作目录/会话/产物收在一个可持续的根下';
```

**唯一约束只锁 `(uid, implicit)` 这一档**：每人最多一个默认项目，用户自建项目不限个数。`migration` 时要处理存量重复。

---

## 二、三条接线（本卡的全部工程量，按"发现顺序"排）

### §二-1 · 沙盒 workdir 上提（**最重要的一条，且成本最低**）

**现状取证**：
- `ProvisionerSandboxBackend` 构造器**早已接受 `workdirPath`**（`:112/:127/:143`，`null` → `""`）；
- provisioner 侧**早已实现**：`normalize_workdir_path()`（拒绝对路径、拒绝非法分量）→ `shared/{uid}/workspace/<workdir_parts>`（`app.py:210-213`）；
- **只有 `SandboxService` 四处硬编码传 `null`**（`backend` 两处：`:65`/`:95`；`available` 探测 `:77`；`release` `:105`）。

**改动**：把 `backend(sessionId, uid, …)` 的第 1 参数语义从"会话"上提为"**作用域**"，由调用方决定传 sessionId（无项目）还是 projectId（有项目）。

**为什么这一条就能实现"同一项目共用一个工作区"**（机制已存在，无需新造）：

```java
// ProvisionerSandboxProvider:114
sandboxIdForThread(threadId, uid) → sha256(uid + ":" + threadId)[0..12]
```

沙盒容器 id 是 `(uid, 作用域id)` 的哈希。**把 projectId 当作用域 id 传进去，同一项目的所有会话天然命中同一个容器与同一个 workdir**——容器空闲回收后重建，`shared/{uid}/workspace/<project>` 仍在盘上，**文件不丢**。

**收益**：
1. 项目内多个会话共享工作区（现在跨会话互相不可见，`app.py:679-695` 注释明说"从挂载层杜绝跨会话读写"）；
2. 现有"容器空闲 60 分钟回收"不再是数据丢失风险——**这是"项目级工作目录"相对"会话级临时目录"最实质的差别**；
3. 不用改 provisioner 一行。

**已知取舍（诚实记下）**：项目内**多个会话并发**时共用同一容器，彼此的 shell 进程与文件系统可见。会话级已有一轮互斥（`runtime.tryBeginTurn`），跨会话没有。**本卡先如实记录，不做项目级互斥**——理由是当前 53 个会话/1 个活跃用户，并发冲突是理论问题；真客户出现并发工作流需求时再补（补法是 `Project` 上加一把轮级锁，不是改沙盒）。

### §二-2 · 会话与产物挂到项目

**改动**：
- `c_ai_session` 增 `project_id VARCHAR(64) DEFAULT NULL`；
- `c_ai_artifact` 增 `project_id VARCHAR(64) DEFAULT NULL`；
- **两列都可空**——`NULL` = 未归属（存量），非空 = 有归属。**不强加 NOT NULL**（见 §三 迁移）。

**读取口径**：
- 新会话：解析当前项目（显式选中 → 否则该 uid 的 `implicit` 项目，懒创建）写入 `project_id`；
- 产物落库：沿用产物已有的 `sessionId`（`Artifact.sessionId` 可空、已存在），**由 session 反推 project**——`ArtifactService.writeBytes` 已拿得到 sessionId，无需新增参数；
- 产物列表：`ArtifactController./list` 增可选 `projectId` 过滤（**不加就还是现在那张全量扁平表，行为不变**）。

### §二-3 · 删除项目时的收容（安全边界）

**删除一个项目，按"谁归谁"分三类处理**：

| 对象 | 处理 | 依据 |
|---|---|---|
| 沙盒容器与 workdir | **释放容器**；workdir **默认保留**（它是项目的工作产出，不是缓存） | 与产物"会话可删、成果归人"同一口径 |
| 会话 | **不删**，`project_id` 置 `NULL`（退化成未归属） | 会话是历史记录，删项目不该销毁对话史 |
| 产物 | **不删**，`project_id` 置 `NULL` | `Artifact` 注释原话"产出的成果仍归人生"——**这条设计意图必须在本卡里被继续遵守** |

**明确不做**：级联删除。理由：本产品的既有设计反复强调"成果归人"，级联删除与它直接冲突；用户要清产物有独立的批量删除入口（`ArtifactController./batch-delete`）。

---

## 三、存量迁移（57 个产物 / 53 个会话怎么归属）

**原则：零破坏、可回退、不猜测。**

### 不做的（明确否掉）

| 方案 | 为什么否 |
|---|---|
| 按时间窗/文件名正则把存量产物分到"猜出来的项目" | **猜测会产生错误归属**，而错误归属比"未归属"更难纠正——用户没法知道系统把哪份文件分错了 |
| 强制 `project_id NOT NULL` + 按会话逐个回填 | 会话与"项目"不是一对一（一个项目本来就该有多次会话），回填必然造假 |
| 直接把每个会话变成一个项目 | 会产生 53 个同名/无名项目，把列表淹掉——**默认项目存在的意义就是避免这个** |

### 要做的（三步，每步都可单独回退）

1. **加列（可空）+ 建表**：`schema.sql` 加 `c_ai_project` 与两个可空列，`SchemaMigrator` 自动补存量库。**此步对现有行为零影响**（不读新列 = 一切照旧）。
2. **懒创建默认项目**：第一个**新**会话/新产物落库时，为该 uid 创建 `selection_status='implicit'` 的默认项目（走 `idempotency_key` 防并发重复）。存量数据**一个都不动**。
3. **可选回填（不做也行）**：给一个管理员触发的迁移端点，按"同一会话的产物归到该项目"把**已有 `sessionId` 的产物**归到该会话所属项目——**只处理有一条明确归属链的**（artifact.session_id → session.project_id），链断的一律留 `NULL`。

**回退**：三步都不删数据、不改既有列。回退 = 停止写入新列 + 前端不显示项目入口，**旧行为完整保留**。

---

## 四、四条规则（本仓既有的、本卡继续遵守的）

1. **fail-loud，不静默降级**：项目解析失败（如 `implicit` 项目建不出来）→ 如实告诉用户，按"无项目"继续问答，**不假装有项目**。
2. **不发明权限**：`project` 不是权限边界。`project_id` 只做归属与过滤，**任何检索/可见性判定都不读它**。
3. **可空即合法**：`NULL` 是一等状态（"未归属"），不是待修复的脏数据。所有读路径必须容忍 `NULL`。
4. **不猜**：迁移不回填无法确定归属的数据（§三）。

---

## 五、施工切分（三刀，每刀可独立交付与回退）

| 刀 | 内容 | 交付物 | 可验证断言 |
|---|---|---|---|
| **1** | `c_ai_project` 表 + 实体/服务 + `implicit` 项目懒创建；会话与产物加可空列；**不做任何 UI** | 后端骨架 | 新会话落库带 `project_id`；`NULL` 不影响任何既有读路径；72 项既有测试全绿 |
| **2** | 沙盒 workdir 上提（§二-1）+ 产物列表按项目过滤 | **用户可感知**：同一项目的两个会话看到同一个工作区文件 | 同项目两次会话在沙盒内 `ls` 到同一文件；容器回收后文件仍在；不同项目互不可见 |
| **3** | 前端项目切换与「我的产物」按项目分组 | 界面 | 57 个存量产物显示为"未归属"，**不丢失、不误分组**；新建会话默认落默认项目 |

**刀 1 与刀 2 之间没有硬依赖**，可并行；刀 3 依赖刀 1。

---

## 六、两个开放项（定了才开工）

### A-D1 · 默认项目要不要在 UI 上可见？

- **A. 完全不可见（推荐）**：`implicit` 项目只作为后台归属容器，对话页与产物页**不出现"项目"字样**。用户显式建项目时才露出切换器。
  - 依据：D2 选业务岗，且定位稿 §五-2 的教训是"别让用户面对一堆要先配的东西"。**首装体验 = 还是那个问答框**。
- **B. 可见且可命名**：首装即显示"默认工作区"，鼓励用户改名。
  - 代价：多一个要理解的概念；收益是产物分组立刻可被用户控制。

**我倾向 A**：本卡的第一目标是"让产物有归属、让工作区能跨会话"，不是"教用户项目管理"。可见性可以在一刀之后按反馈加。

### A-D2 · `workdir_path` 用项目 id 还是可读名？

- **A. 项目 id（推荐）**：`projects/{id}`——重命名项目不影响路径，无重名冲突，无需转义中文。
- **B. 可读 slug**：`projects/{name}`——用户在沙盒里 `ls` 更直观，但要处理重名、特殊字符、重命名迁移。

**我倾向 A**，理由是本仓对路径安全已有硬口径（`normalize_workdir_path` 拒绝非法分量），**把不可信输入（用户起的名）放进路径是自找边界问题**。可读性用前端展示解决。

---

## 七、不做清单（挡下一轮"顺手也做了吧"）

| 不做 | 理由 |
|---|---|
| 项目级并发互斥 | §二-1 取舍已记；出现真实并发需求再补 |
| 项目模板 / 脚手架 | 不是本卡目标，且会把"轻容器"变成"要配的东西" |
| 项目共享 / 团队协作 | D1=A + `tenantId` 零命中；引入即引入新权限模型 |
| 项目级知识库独立权属 | 检索可见性口径不动（§四-2） |
| 级联删除 | 与"成果归人"设计意图冲突（§二-3） |
| 把 `project_id` 回填到非空 | §三 已否 |

---

## 八、验收断言（真机行为，对齐 P5 卡的写法）

1. **不建项目也能用**：全新用户不碰任何项目设置，问答链路与今天**逐字相同**；产物照常产出。
2. **同项目共享工作区**：同一项目下开两个会话，会话 A 在沙盒写 `a.txt`，会话 B `ls` 看得到；换到另一项目看不到。
3. **容器回收不丢文件**：会话 A 写文件 → 等空闲回收（或手工释放）→ 会话 B 读同一项目，文件仍在。
4. **存量零损失**：57 个存量产物一条不少，全部显示为"未归属"；**没有任何一条被猜到某个项目里**。
5. **删除项目不毁成果**：删除项目后，其下产物与会话仍在（`project_id=NULL`），产物仍可下载。
6. **回归**：既有 72 项测试保持全绿；`config-schema.json` 校验通过（本卡不改配置项）。

---

## 九、与上游卡的关系

| 卡 | 关系 |
|---|---|
| `p5-agent-as-tool-2026q4.md` | P5 把"派遣路由"换成 `consultAgent`（模型自主委派）。**本卡是它的空间对应物**：P5 理顺了"谁来干"，本卡理顺"在哪干、干出了什么" |
| `p6-ragservice-split-2026q4.md` | P6 拆的是检索运行时。本卡不碰检索链路（§四-2） |
| `p7-agent-as-service-2026q4.md` | **本卡是 P7 的前置**：P7 要让外部系统"围绕一个工作对象多轮往返"，而那个对象就是 Project。P7 开工前应先看本卡 §二-1 的沙盒作用域结论 |
| `positioning-decisions-2026q4.md` | D3 的推荐（容器优先）由本卡兑现 |

## 关联

- `docs/positioning-2026q4.md` §四-2（差异化分层）、§五-2（默认值）
- `docs/positioning-decisions-2026q4.md` §二（D2 取证：57 个真实产物）、§三（D3：容器优先）
- 代码：`SandboxService.java:21`、`ProvisionerSandboxProvider.java:114`、`deploy/sandbox-provisioner/app.py:210-213`、`Artifact.java:12`、`ScheduledJobService.java:41`
- 参照实现（非同仓）：`~/IdeaProjects/Yuxi` 的 `Project` 模型（`workdir_path` / `directory_mode` / `selection_status` 三字段的已验证形状）
