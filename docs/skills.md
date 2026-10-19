# 技能系统（Skills）

技能是一份「这类问题该怎么做」的能力包：一个目录 + 一份 `SKILL.md`（frontmatter 的 `name` / `description`
+ 给模型看的正文），可选带 `references/`、`scripts/` 等附属文件。模型每轮先看清单，判断相关后才读正文。

本文件说明问渠的技能来源、用户自助安装/管理、存储布局、接口与安全边界。

---

## 1. 四层来源（优先级递增）

| 优先级 | 来源 | 落点 | 谁能写 | 作用域 |
|---|---|---|---|---|
| 1（最低） | 内置 | `user-data/skills-builtin/{name}/SKILL.md`（启动时从 jar 的 `classpath:skills/` 落盘） | 随版本分发 | 所有人 |
| 2 | 历史投影 | `skill-projections/{uid}/{name}/SKILL.md` | 无（P3 前的存量，只读） | 本人 |
| 3 | 沙盒工作区 | 沙箱用户数据根 `skills/{name}/SKILL.md`（`shared/{uid}/workspace/sessions/{sid}` 或 `.../projects/...`） | 助手（框架 `skill_manage`） | 当前会话/工作区 |
| 4（最高） | **用户自装** | `user-data/skills-users/{uid}/{name}/SKILL.md`（宿主） | 用户本人（本页面的接口） | 本人，跨会话 |

同名时上层覆盖下层。用户自装放在最高优先级：明确安装的版本不应被一次会话里临时沉淀的同名草稿顶掉。

> 模型侧的可见清单由 `WenquSkillRepository#catalog` 按当轮 `RuntimeContext.userId` 合成；
> 框架还会额外挂一个可写的沙盒工作区仓库（`skill_manage` 的落点）。两者在同一会话里同名时，
> 模型看到的是沙盒工作区那一份（框架的层序），而管理页展示的是用户自装那一份——这是已知的口径差异。

## 2. 存储布局

```
user-data/skills-users/{uid}/
├── .disabled                 # 个人停用清单（一行一个技能名；内置技能也能停）
└── report-helper/
    ├── SKILL.md              # 必需；frontmatter 的 name 必须等于目录名
    ├── references/guide.md   # 可选；模型可按需读取
    └── scripts/run.sh        # 可选；随包保存
```

- `{uid}` 经 `SandboxPaths.uidDirname` 路径安全化（OIDC 的 `iss:sub` 等字符不会进目录名）。
- 目录名即技能名，安装/新建时强制 `name == 目录名`，避免「引用串 / 停用清单 / 目录」三套标识打架。
- `<uid>` 级目录不经过沙箱：沙箱没起也能装、能读；容器重建不丢；随 `user-data/` 一起备份。
- 存储根可配：`wenqu.skills.users-dir`（环境变量 `WENQU_SKILLS_USERS_DIR`），默认 `user-data/skills-users`。

## 3. 用户能做什么

| 动作 | 入口 | 说明 |
|---|---|---|
| 新建 | 技能页「新建技能」 | 只写 Markdown 正文即可，frontmatter 由平台补；描述必填（模型靠它判断何时读取） |
| 上传 zip | 技能页「上传 zip」 | 根目录或唯一外层目录里有 `SKILL.md`；可带 `references/`、`scripts/`、`assets/` |
| 从 URL 安装 | 技能页「从 URL 安装」 | 仅 `SKILL.md` 纯文本；来源域名由 `skill.remoteAllowedHosts` 白名单控制，默认关闭 |
| 编辑 | 卡片「编辑」 | 改正文与 description；技能名不可改（改名会打断智能体上的引用） |
| 删除 | 卡片「删除」 | 仅自己安装的技能；内置/历史只读 |
| 个人停用 | 卡片「停用」 | 只影响自己；内置技能也能停；停用后不进模型清单，但输入框点名仍可用一次 |
| 下载 | 卡片「下载」/ 详情「下载 zip」 | 导出整包（含 references/scripts），用于备份或分享 |
| 预览附属文件 | 详情「附属文件」 | 只读文本；二进制提示下载 |

平台级停用仍由管理员在「系统设置 → 工具调用 → 技能」维护（`skill.disabled`），对所有人生效。

## 4. 接口

基路径 `/api/ai/skill`，登录用户可用；资源级归属在控制器内按 `RequestUser.uid()` 判定。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/available?sessionId=` | 对话选用清单（内置 + 用户自装 + 历史投影 + 指定会话的工作区技能） |
| GET | `/list` | 管理页清单（来源、停用/由谁停、可否编辑删除、体积、文件数）+ 远程安装开关 + 安装限制 |
| GET | `/detail?name=` | 元信息 + 正文 + `raw`（`SKILL.md` 原文，编辑用）+ 文件清单 |
| GET | `/content?name=` | 正文（与模型本轮吃到的同源） |
| GET | `/file?name=&path=` | 用户自装技能下的文本文件预览 |
| POST | `/` | 新建：`{name, description, content, overwrite?}` |
| POST | `/install` | URL 安装：`{url, name?, overwrite?}` |
| POST | `/install/zip` | multipart：`file` + `name?` + `overwrite?` |
| PUT | `/{name}` | 编辑：`{description?, content}` |
| DELETE | `/{name}` | 删除（仅本人自装） |
| PUT | `/{name}/disabled` | 个人停用/启用：`{disabled}` |
| GET | `/download?name=` | 下载整包 zip |

`GET /available` 的条目字段：`name` / `dirName` / `ref` / `description` / `source` / `disabled` /
`platformDisabled` / `userDisabled` / `selectable` / `editable` / `deletable` / `disableable` / `size` /
`updatedAt` / `fileCount`。`ref` 对个人技能是 `{uid}/{name}`（智能体引用串），内置技能是裸名；
工作区技能 `selectable=false`（随会话走，不能钉进智能体配置）。

## 5. 安全边界

- **路径**：技能名白名单 `[\p{IsHan}A-Za-z0-9_-]{1,64}`；任何落盘路径 `normalize()` 后必须仍在技能根内。
- **zip**：逐条目拒绝绝对路径、`..`、反斜杠、盘符、隐藏文件；条目 ≤ 240、文件 ≤ 120、单文件 ≤ 2MB、
  解压总量 ≤ 20MB、深度 ≤ 8；先解到 `.tmp` 再整体移动，失败不留半成品。
- **URL**：仅 http/https；host 精确匹配 `skill.remoteAllowedHosts`（空=关闭）；每一跳都过
  `SsrfGuard.requirePublicHost`（禁回环/内网/链路本地/多播）；手动跟随重定向（≤3 次）；
  响应 ≤ 512KB；内容只保存为文本，绝不执行。
- **覆盖**：内置与历史投影的技能名是保留名，不允许被用户技能覆盖；同名安装必须显式 `overwrite`，
  整包替换（旧包里的 references/scripts 会丢）。
- **归属**：所有写操作只作用于 `RequestUser.uid()` 名下；匿名请求直接 401。

## 6. 与框架 `skill_manage` 的关系

- 助手在对话里沉淀技能仍走 `skill_manage`，落在**沙盒工作区**，作用域是会话/工作区，跨会话不稳定。
- 用户自助安装落在**宿主 uid 级目录**，跨会话稳定；两者并存，不互相覆盖存储。
- 读侧统一由 `WenquSkillRepository` 合成；`getAllSkills` 过滤停用，`getSkill`（点名）不过滤——
  点名是明确意图，优先于停用。
- 用户自装技能的 `references/` / `scripts/` 会作为 `AgentSkill.resources` 提供给
  `load_skill_through_path` 按需读取；但**脚本不会自动投影进沙箱执行**（`<files-root>` 只对工作区/市场
  技能渲染）。需要脚本执行的技能，目前请让助手写进沙盒工作区，或等后续把用户目录纳入 staging。

## 7. 配置与运维

| 键 | 默认 | 说明 |
|---|---|---|
| `skill.disabled` | 空 | 平台停用清单（逗号/换行分隔技能名），对所有人生效 |
| `skill.remoteAllowedHosts` | 空 | URL 安装允许的域名，逗号分隔、精确匹配；空=关闭远程安装 |
| `wenqu.skills.users-dir` | `user-data/skills-users` | 用户自装技能根（启动参数/环境变量） |
| `SKILL_MAX_FILE_CHARS` | 20000 | 单技能正文注入上限（常量） |

- **备份/迁移**：备份 `user-data/skills-users/` 即可；目录结构即数据，无数据库表。
- **审计**：安装/编辑/删除/启停都会打 `[AUDIT]` 日志（`uid` + 技能名 + 动作）。
- **排障**：列表读不到时先看 `user-data/skills-users/{uid}/` 是否存在、`SKILL.md` frontmatter 是否合法；
  zip 安装失败会带具体原因（路径/体积/缺少 SKILL.md/frontmatter 不合法）。

## 8. 已知限制

1. 用户自装技能里的脚本不会自动进沙箱执行（见 6）。
2. 同一会话内，若模型用 `skill_manage` 写了与用户自装同名的技能，模型侧优先看到工作区那份。
3. 历史投影（`skill-projections/{uid}`）只读：可停用、可查看，不能编辑/删除；建议重新安装后忽略旧条目。
4. 个人停用清单按技能名记录；删除技能会同时移除它的停用记录。

## 9. 测试

- `UserSkillStoreTest`：读写闭环、路径穿越、zip 解包/穿越、内置保留名、URL 域名白名单。
- `WenquSkillRepositoryTest`：四层合并、平台/个人停用、点名不过滤、无 uid 不越权。
- `SkillControllerTest`：清单契约、新建、zip 安装、内置不可删、匿名 401。
