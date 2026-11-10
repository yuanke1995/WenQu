# 市场（Marketplace）UI/UX 与功能升级方案（先出方案，确认后再写代码）

## 0. 结论先行

| 项 | 结论 |
|---|---|
| **本次第一步** | 产出本方案 + `design/mockups/market-upgrade/market-upgrade.html`（现状对照 + 6 屏高保真效果图），**这一步不写业务代码** |
| **核心主张** | 把市场从「上架动作的集合」升级为「发现 → 安装 → 跟踪更新 → 反馈」的**闭环运营台**：默认页有东西看、搜索跨类型、装过的能回看、更新有说明、详情看得懂 |
| **最大缺口** | 没有「我安装的」视图，也没有更新聚合入口——安装记录表自己声明「不是已装清单」（[MarketInstall.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/model/MarketInstall.java#L14)），用户装完就断线，「有更新」只能逐页签肉眼找 |
| **范围** | 市场页五个页签的列表/搜索/排序/分页、详情弹窗、上架与我的上架管理、更新提示链路、侧边栏市场入口徽标；不动「安装即复制」核心语义 |
| **不做** | 不引入新视觉体系（沿用 `web/src/views/app.css` 令牌）；不做跨租户/对外分享市场；不做付费分发；不改权限边界（`market:read/publish/install` + 资源级判定不变） |
| **后端最小改动** | ① list 增排序/分页参数 ② 已装清单与 updates 聚合查询 ③ 版本历史表 + 发布说明 ④ 元信息编辑端点 ⑤ icon/category 可选字段 |
| **默认推荐** | 分四期：一期纯前端体验修复（F1-F4）；二期安装闭环（F5-F7）；三期详情结构化与作者运营（F8-F10）；四期反馈与精选（F11-F12） |

---

## 1. 现状诊断（基于代码与截图，非猜测）

### 1.1 现状截图

用户截图所示：默认落在「技能 0」空页签，而「工作流 3」「应用模板 1」有内容；搜索框、刷新、上架按钮均在页头。

### 1.2 代码事实

| 编号 | 现状 | 证据 |
|---|---|---|
| M1 | 默认页签硬编码为 `skill`——技能为空时首屏就是空态，用户第一眼看到「这个页签还是空的」，而不是市场里已有的 4 件物品 | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L253-L256) |
| M2 | 搜索只作用于当前页签（`load()` 把 `type=tab` 一起传），跨类型找东西要逐个页签试 | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L313-L329) |
| M3 | 点击标签走的是 `keyword=标签词` 的模糊匹配，而后端本有专用 `tag` 过滤参数；且 `counts` 调用不传 tag，切页签后角标数与列表结果可能对不上 | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L336-L341)；[MarketService.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/service/MarketService.java#L159-L195) |
| M4 | 列表固定按 `update_time` 倒序 + 硬上限 `LIMIT MAX_LIST`：无排序选项（最热/安装量），无分页，超出上限静默截断 | [MarketService.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/service/MarketService.java#L137-L140) |
| M5 | 没有「我安装的」视图：`c_ai_market_install` 只服务徽标判定，注释明确「不是已装清单」；用户无法在市场回看自己装过什么 | [MarketInstall.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/model/MarketInstall.java#L10-L14) |
| M6 | 「有更新」只在进入市场、切到对应页签时才可见：侧边栏「市场」入口无角标，无跨页签的更新聚合列表，更无「一键全部更新」 | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L68) |
| M7 | 详情弹窗对应用模板/工作流/MCP 直接甩原始 JSON（`configJson` pre 块）：安装人要自己读懂 DSL/配置才能判断「装的是不是我要的」；工作流无流程图预览、智能体无能力摘要 | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L180-L181) |
| M8 | 卡片描述固定 38px 高硬裁切、无省略号；物品无图标/封面/分类维度；非 MCP 类型没有「官方」徽标（内置官方模板与用户物品同款白卡） | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L506)（样式）、[L59-L67](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L59-L67) |
| M9 | 重复上架只按内容指纹检出「有更新」，没有发布说明：装过的人看到徽标，但不知道这次改了什么、要不要更 | [MarketController.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/controller/MarketController.java#L91-L95)；[MarketItem.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/model/MarketItem.java#L63-L67) |
| M10 | 「我的上架」只有下架/删除三个动作，改简介/描述/标签必须重新走一遍完整上架；作者看不到自己条目的安装数据（只有卡片上一个累计数） | [MarketplacePage.vue](file:///Users/wangxi/workspace/WenQu/web/src/views/MarketplacePage.vue#L124-L127) |
| M11 | 无任何质量信号与运营位：无点赞/评论，无管理员精选/置顶；`publish` 表单里标签是自由文本，无建议与规范提示 | [MarketPublishModal.vue](file:///Users/wangxi/workspace/WenQu/web/src/components/MarketPublishModal.vue#L30-L35) |

### 1.3 用户现在回答不了的四个问题

1. **市场里有什么值得装？** 默认页签是空的、搜索不出当前页签、没有热度排序和推荐位。
2. **我装过什么、哪些有更新？** 没有已装清单；「有更新」徽标散在四个页签里，要逐页翻。
3. **这次更新改了什么？** 只有「有更新」三个字，没有发布说明，用户不敢点更新（fork 类还怕丢掉本地改动语境）。
4. **这个物品适不适合我？** 详情是原始 JSON；没有预览、没有分类、没有别人用过好没好用的信号。

---

## 2. 设计目标与原则

1. **首屏即发现**：进入市场 3 秒内看到可装的东西——默认落在有内容的页签、支持全局搜索、有「最热/最新」排序。
2. **装完不断线**：新增「我安装的」页签 + 更新聚合（侧边栏徽标 + 一键全部更新），把安装记录从「徽标数据源」升级为「用户资产视图」。
3. **装前知道装的是什么**：详情从 JSON 墙升级为结构化预览（工作流流程图缩览、智能体能力清单、技能说明卡）；更新有发布说明。
4. **作者能运营**：重新上架必填/可选「更新说明」；元信息可单独编辑；能看到安装趋势。
5. **沿用现有设计语言**：只用 `app.css` 令牌与 `.app-card / .app-btn / .app-pill` 类；页签形态、卡片网格、详情弹窗骨架不推翻，渐进增强。
6. **不破坏核心语义**：「安装即复制」「内容指纹判更新」「引用按权限裁剪 + warnings」三条既有规则原样保留，所有新 UI 只是把已有事实呈现得更清楚。

---

## 3. 信息架构与交互重构

### 3.1 页签结构（场景 1）

```
页签：技能 · 应用模板 · 工作流 · MCP 服务 · 我安装的(新增) · 我的上架
默认落点：上次访问的页签（localStorage）→ 否则第一个 counts>0 的页签 → 否则技能
「我安装的」= 安装记录 join 物品表，逐条带 已是最新/有更新/已下架 三态 + 更新入口
```

- 空态页签不再只说「第一个上架的人就是你」，追加一行「其他页签有 N 件物品」+ 跳转链接。

### 3.2 工具行（场景 1）

```
搜索框（全局，跨类型，结果按类型分组）· 排序下拉（最新/最热/安装量）· 标签 chips（点标签=tag 精确过滤，counts 同步传 tag）
分页：滚动加载（后端 page/pageSize，超上限不再静默截断）
```

### 3.3 卡片（场景 1）

- 描述改 2 行省略号 + hover 全文；缺描述给警示态（对齐技能页方案 F3）。
- 增加类别图标（按类型/分类给色块图标，不引入外部图片）；`authorUid=system` 的非 MCP 条目同样打「平台官方」徽标。
- 「有更新」徽标点击 = 直接弹更新说明（见 3.5），而不是让用户先进详情自己找。

### 3.4 详情弹窗（场景 2）

保留 820px 弹窗骨架，「内容」页签按类型结构化：

| 类型 | 现在的展示 | 升级为 |
|---|---|---|
| 技能 | SKILL.md 正文（已可用） | 平移 + 附属文件列表 |
| 应用模板 | 原始 JSON | 能力摘要卡：人设一句话、知识库/工具/技能/MCP 各几项（引用明细折叠） |
| 工作流 | 原始 JSON | 只读流程图缩览（复用画布渲染器的只读态）+「查看原始 DSL」折叠 |
| MCP | 原始 JSON | 参数说明表（名称/地址/传输类型/是否需要认证） |

底部动作区增加「版本历史」入口（见 3.5）。

### 3.5 更新闭环（场景 3，核心新增）

- **发布说明**：`publish` 时（重复上架）多一个「这次改了什么」输入框，落 `c_ai_market_version`（itemId/version/contentHash/releaseNote/时间）。
- **消费侧**：「有更新」徽标点开 = 当前已装指纹与最新指纹之间的 changelog 列表；技能更新确认弹窗里同步展示说明文案（现在只警告会丢本地改动，没说对面有什么新东西）。
- **聚合**：`GET /market/updates` 返回我装的、有更新的条目；侧边栏「市场」项挂红点计数；「我安装的」页签顶部「一键全部更新」（fork 类=装新副本、技能=逐条确认后覆盖）。

### 3.6 我的上架（场景 4）

- 卡片操作增加「编辑信息」：单独改 简介/详细介绍/标签/图标分类，不动快照（新端点 `PUT /{id}/meta`），避免为改个错别字重新上架。
- 每条展示安装趋势（累计 + 最近 30 天），作者可展开最近安装人列表（企业内网场景，同租户可见）。

---

## 4. 功能升级清单（优先级与改动面）

| # | 升级项 | 前端 | 后端 | 优先级 |
|---|---|---|---|---|
| F1 | 默认页签策略 + 空态跨页签引导 | MarketplacePage.vue | 无 | **P0** |
| F2 | 全局搜索 + 标签走 tag 参数 + counts 传 tag 对齐角标 | 搜索/标签逻辑 | list/counts 已有参数，仅需前端接通 | **P0** |
| F3 | 排序（最新/最热）+ 分页（滚动加载） | 列表 | list 增 `sort/page/pageSize` | **P0** |
| F4 | 卡片体验：描述省略+hover、类型图标、官方徽标扩展到非 MCP | 卡片 | 无 | **P0** |
| F5 | 「我安装的」页签（三态 + 更新入口） | 新页签 | installed 视图查询（join 安装记录） | **P1** |
| F6 | 更新聚合：`/market/updates` + 侧边栏徽标 + 一键全部更新 | 市场页 + 侧边栏 | updates 端点 | **P1** |
| F7 | 发布说明 + 版本历史 + 更新弹窗展示 changelog | 上架弹窗/徽标点击流 | `c_ai_market_version` 表 + publish 扩展 | **P1** |
| F8 | 详情结构化预览（智能体能力卡/工作流只读流程图/MCP 参数表） | 详情弹窗 | agent 摘要字段可前端从 configJson 提炼，工作流复用现有渲染 | **P1** |
| F9 | 我的上架：元信息编辑 + 安装趋势 | 管理操作 | `PUT /{id}/meta` + 安装统计查询 | **P2** |
| F10 | 上架表单体验：卡片效果实时预览、标签建议、分类选择 | MarketPublishModal | MarketItem 增 `icon/category`（可选） | **P2** |
| F11 | 轻量反馈：点赞 + 一句话评价，聚合回卡片 | 详情/卡片 | 评价表 + 端点 | **P2** |
| F12 | 管理员精选/置顶运营位 | 列表头部 | featured 标记（复用官方条目维护面） | **P2** |

---

## 5. 效果图导览

打开 [`design/mockups/market-upgrade/market-upgrade.html`](file:///Users/wangxi/workspace/WenQu/design/mockups/market-upgrade/market-upgrade.html)：

| 场景 | 画面 | 对应升级项 |
|---|---|---|
| 00 | 现状截图（对照底图，已存 `assets/current-market.png`） | §1 诊断 |
| 01 | 升级后主列表：默认落在有内容页签 + 全局搜索 + 排序/标签 chips + 新卡片（图标/官方徽标/描述省略）+ 侧边栏红点 | F1-F4 |
| 02 | 「我安装的」页签：更新汇总条 + 一键全部更新 + 三态卡片 | F5-F6 |
| 03 | 更新说明弹窗：changelog + 覆盖警告 | F6、F7 |
| 04 | 详情结构化预览：应用模板能力摘要卡 / 工作流只读流程图 | F8 |
| 05 | 发布弹窗：重复上架填发布说明 + 卡片效果实时预览 | F7、F10 |
| 06 | 我的上架：编辑信息 + 安装趋势 + 最近安装人 | F9 |

---

## 6. 后端最小改动集（确认后实施）

1. **list/counts 扩展**：`sort`（update_time|install_count）、`page/pageSize` 替代硬 LIMIT；`installed=true` 视图（`c_ai_market_install` join 物品，含 最新/有更新/已下架 三态）；counts 与列表同条件（tag 已支持，前端补传即可）。
2. **updates 聚合**：`GET /api/ai/market/updates` 返回 `[{id,name,itemType,installedHash,latestHash,latestNote}]`，判定逻辑复用现有指纹比对，不新增机制。
3. **版本历史**：新表 `c_ai_market_version(id, item_id, version, content_hash, release_note, create_time)`；publish 覆盖快照时同事务写一条；`GET /{id}/versions`。
4. **元信息编辑**：`PUT /{id}/meta`（summary/description/tags/icon/category），只动展示字段、不动 payload 与指纹——**不触发**「有更新」提示。
5. **字段扩展**：`c_ai_market_item` 增 `icon`、`category`、`featured`（默认空/false，扩列不改语义）。
6. **反馈（P2）**：`c_ai_market_rating(uid, item_id, like, comment)`，uk_uid_item 一人一条可改。

不新增权限点：沿用 `market:read/publish/install` 与资源级判定（[MarketController.java](file:///Users/wangxi/workspace/WenQu/src/main/java/com/wenqu/ai/controller/MarketController.java#L33-L36)）；「一键全部更新」逐条走现有 install 端点，权限与裁剪 warnings 语义不变。

---

## 7. 实施步骤（方案确认后启动）

1. **一期（P0，见效最快）**：F1-F4。前端体验修复为主，后端只加 sort/page。
2. **二期（P1）**：F5-F7。安装闭环——已装清单、更新聚合与徽标、发布说明与版本历史。
3. **三期（P1）**：F8。详情结构化预览。
4. **四期（P2）**：F9-F12。作者运营、上架表单体验、反馈与精选。

---

## 8. 明确不做

- 不改「安装即复制」语义：装完就是自己的资产，与市场条目断关联的既有设计（含 fork 裁剪 warnings）原样保留。
- 不把「已装清单」变成卸载管理：技能副本、fork 出的智能体/工作流仍归各自管理页，市场只读展示状态并提供更新。
- 不做星级评分/评论社区化运营：内部市场只做轻量「点赞 + 一句话」。
- 不做跨租户/对外公开市场：MCP 目录的「平台官方」边界同样适用于未来的精选位。
- 不引入封面图/外部图片资源：卡片图标一律用现有图标库 + 类别色，避免图片资产治理成本。
