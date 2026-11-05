---
name: wenqu-dev
description: 问渠三仓（wenqu / dynamic-form / dynamic-form-vue）开发规范——防回归三道闸、已知雷区清单、环境与验证栈速查、提交纪律、路线图账本。凡在这三仓做任何开发（新功能、修 bug、重构、调样式）或问「某功能现状 / 接下来做什么」，动手前先走本技能：查影响面、查雷区、查账本，完工后回写。核心目标：做新功能不改烂已有功能。
---

# 问渠开发规范

一句话主轴：**这个仓最贵的损失不是没做出新功能，是把好的改烂了。** 每次改动过三道闸。

## 第一道闸：动手前——影响面盘点

改任何共享代码前，按序回答四问（说清即可，不必写文档）：

1. **被谁共用？** grep 调用方/引用方；改签名、语义、默认值时，列出所有受影响路径——这份清单第三道闸要用。
2. **有没有第二真相源？** 本仓多处存在「一份逻辑、多份同步物」，改一处必须同步全部：
   - 菜单图标白名单：PermissionsPage / AppLayout 两份 ICONS + RbacSeedRunner 种子，三处同步；
   - 设置项：`config-schema.json` 是设置页唯一 schema 源；运行时默认值以 ConfigDefaults/defaults() 为权威，三层口径别混；
   - AI 契约：提示词源 + 前端校验器 + 自测三处；
   - 新表落库：schema.sql +（全局表）GLOBAL_TABLES + 种子/迁移，缺一 runtime 才炸。
3. **存量兼容吗？** 老表单/老会话/存量库行/已发 token 是否受影响。**默认必须兼容、行为零变化**；确不做兼容的，先请用户拍板，并把口径记进账本（先例：列宽 % 改造「老表单 px 原样保留」）。
4. **有没有人在做同一块？** 查 [references/roadmap.md](references/roadmap.md) 活跃线。并行会话很多，撞车先查——2026-10-06 一次审计里四节路线图已被并行会话做掉。

涉及具体技术域（建表/mapper、antdv 组件、设计器撤销、iframe、验证方法）时，先读 [references/hazards.md](references/hazards.md) 对应节——全部条目来自真实回归史，命中就把对应回归加进完工清单。

## 第二道闸：动手时——边界纪律

- **先证机制再动手**：本仓多次「三层根因」案例证明表层诊断常是错的。定位到现象后，先找到能解释全部症状的机制，再改；解释不了的症状不许略过。
- **改动范围 = 拍板范围**：顺手发现邻居 bug 记下来（账本「遗留债」），不混进当前提交，不顺手修。
- **不加第二真相源**：发现两处代码各说各话，先收编成一处再改，而不是负责任地同步改两处。

## 第三道闸：完工前——受影响面回归

1. 拿出第一道闸盘点的受影响路径清单，**逐条真机走一遍**——不是只测新功能。
2. 集成边界（前后端契约、模型输出、文件解析）必须用真实数据验；单测全绿不代表真机不炸。
3. 验证验的是**提交态**（git worktree / 干净 HEAD），不是混着并行 WIP 的工作区。
4. iab 真机验收的坑（过期合成帧、rAF 饿死、teleport 定位器失灵）见 hazards.md「假绿」节。

## 环境速查

| 项 | 值 |
|---|---|
| 后端调试 | 8090 = IDEA 调试进程，**永远勿杀**；自验独立栈用 8091 jar（从仓库根启动） |
| 上下文 | `/ai`；API 前缀 `/ai/api/ai` |
| 前端 | `web/`，vite dev（端口 5800，`/proxy` → 8090/ai）；后端不托管静态 |
| 编译 | 本机无全局 mvn，用 `/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn`；`-pl` 须带 `-am` |
| MySQL | root 免密；开发库 wenqu_ai（ai_doc_assistant 是旧库） |
| 私有配置 | `config/application-local.yml`（gitignored，不 force add） |
| JWT | 密钥固定，可自签直登 |
| 兄弟仓 | dynamic-form：8000 前端 → 8080 后端、MySQL dtbd 库、登录 `/dtbd/login?user=admin`、API 头 X-Token；dynamic-form-vue 配套 |
| 线上 | 腾讯云 docker 栈 49.232.129.201，可自签 token 直测 |

临时栈纪律：起完必须核验代理指向；收尾把造的临时用户/数据连后端自动补建的 implicit 行一起清掉。

## 提交纪律

- 用户要求时才提交；直接 master；中文 conventional commit（`类型(范围): 描述`，细则见 CONTRIBUTING.md）。
- **并行会话共用暂存区**：`git add` 会把别人未提交的 hunk 卷进来（真实发生过）。只提自己的 hunk 用 `git hash-object -w <file>` + `git update-index --cacheinfo`；整文件属于自己才整文件 add。
- 提交后 **`git show --stat` 核对文件清单**——发现卷入，`git reset HEAD~1 -- <path>` + `--amend` 剥离。
- 不入库：`src/test/`（约定）、`dumps/`、`docs/`、`.zcode/`（skills 例外，随仓走）。
- 用户可见文案禁「本地/自建/localhost」指平台侧服务，统一「平台内置XX服务」；「本地」仅限开发注释与部署方文档。

## 路线图账本

[references/roadmap.md](references/roadmap.md) 是开发路线的单一事实源（它存在的原因：路线图曾两度随 gitignored 目录散失、排期状态不明）：

- **开工前**查「活跃线」：撞车检查 + 上下文（这条线为什么做、做到哪、有没有待拍板阻塞）。
- **完工时**回写：条目补 commit hash + 日期；「待拍板」区落定一项转正一项。
- **新立项**：计划文档放 `.zcode/plans/`（现有惯例），账本「活跃线」加一行指向它。
- 状态变化随手回写，不攒批。

## 与 CONTRIBUTING / 会话记忆的关系

- CONTRIBUTING.md 是对外贡献者口径（PR 规范、快速开始）；本技能是内部开发纪律，冲突时内部纪律优先，但对外口径别破坏。
- 会话记忆里有更细的坑账与案例细节；本技能收编稳定约定，记忆补充时效性细节。两者冲突时以核实过的为准，并顺手更正本技能——账本和雷区清单都是活文档。
