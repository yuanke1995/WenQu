# 对外集成文档站（B2）

一份产物，两处使用：独立部署的 `docs.example.com`，以及应用内「对接文档」页（`/docs`）的 iframe。
两处显示的是同一份 HTML——这是 B2 的核心口径：**不维护第二份文档**。

## 真源与产物

| 真源 | 谁产生 | 覆盖 |
|---|---|---|
| `contract/openapi.v1.json` | `scripts/check-openapi-contract.mjs --update`（扫后端 `@OpenApi`） | 端点清单：方法 / 路径 / scope / 可达性 |
| `docs-site/guides/*.md` | 人写，唯一可编辑的正文来源 | 选型、认证、run 事件、webhook、续传、错误码、SDK、能力清单 |
| `samples/*` + `contract/CHANGELOG.md` | 仓库内既有资产 | 可运行样例目录、契约变更记录 |

产物（提交入库，CI 校验是否与真源一致）：

```
web/public/docs/index.html        # 单页文档站（侧栏 + 正文 + 搜索索引）
web/public/docs/search-index.json # 构建期生成，供站内搜索
web/public/docs/assets/docs.css   # 从 docs-site/assets 原样拷贝
web/public/docs/assets/docs.js
```

## 构建

```bash
node scripts/build-docs.mjs            # 构建（写 web/public/docs/）
node scripts/build-docs.mjs --check    # 只校验产物是否落后于真源（CI 用）
node scripts/build-docs.mjs --out=/tmp/docs   # 换输出目录，给独立部署用
```

零第三方依赖（自带的迷你 Markdown 渲染器 + 手写模板），所以 CI 里不需要 `npm install` 就能跑，
也不会因为某个 md 库升级把排版改一遍。

## 约定

- **新增指南**：在 `docs-site/guides/` 加 `NN-slug.md`，frontmatter 写 `title / group / order / lead`。
  `group` 只取 `开始 / 参考 / 指南 / 资源`（构建脚本按这个顺序排侧栏），`slug` 就是页面锚点。
- **锚点**：标题默认按文本 slug；需要稳定短锚点就写显式锚点 `## 前端嵌入 {#cap-embed}`。
  跨页引用用 `#<页面 slug>`（如 `#run-events`）或 `#<显式锚点>`。
- **地址**：正文里一律写 `__BASE__`（= 服务地址 + `/ai`），构建期原样保留，由 `docs.js` 在浏览器里替换：
  `?base=` 优先，其次 `localStorage`，最后回落 `location.origin + '/ai'`。
  这样同一份产物在本地（web 5800 / 后端 8090）、线上同域、独立文档站三种场景都成立。
- **端点表不许手写**：API 参考整页由契约快照生成。手写端点表等于给「只增不删」开了个后门。
- **已做过的取舍**：不引入文档框架（VitePress/Docusaurus）。文档站只需要「侧栏 + 正文 + 搜索 + 复制」，
  而为它引入一条前端工具链，会把前端与后端的构建都拖进文档依赖里。

## 应用内嵌入

`web/src/views/ApiDocsPage.vue` 只是一个外壳：`iframe src="/docs/index.html?base=<publicUrl('/ai')>"`。
改文档内容不需要动 Vue 文件；改口径也不需要同步两处。

## CI

`.github/workflows/ci.yml` 的 `docs` job 跑 `--check`：改了 `guides/*.md`、契约快照或样例却没重新构建，
直接失败并提示跑构建。**不要**为了让 CI 变绿去改断言——重新构建并提交产物。
