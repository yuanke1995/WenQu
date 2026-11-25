# 项目记忆（WenQu）

## 环境约定
- **本地 5801 端口 = 线上（生产）环境**：`web/package.json` 的 `npm run dev:prod` 以 `--proxy-target http://49.232.129.201/ai --web-base http://localhost:5801 --port 5801` 启动，Vite 跑在 5801，`/proxy` 指向线上后端 `http://49.232.129.201/ai`。因此浏览器打开 `localhost:5801` 的问答/会话/操作实际都打在线上，而非本地 8090/8091。涉及该端口的问答、改动、推送、数据操作一律按线上环境谨慎对待。
- 线上服务器 SSH 别名 `wenqu`（ubuntu@49.232.129.201）；应用容器 `wen-qu`，实时日志 `/app/data/logs/wenqu.log`，会话归档 `/app/data/agentscope/session-archive`。

## 编码约束（AI 生成代码必须遵守）

本仓库代码绝大多数由 AI 生成。AI 的默认行为是「找到最相关的现成文件、往后追加」——
这会让类越加越大、依赖越加越多（Controller 曾长到 33 个依赖、单文件 5000 行）。
下面每条都是**可被机器判定**的硬指标，不是「请保持良好设计」这类软建议。
违反时 `mvn verify` / `npm run lint` 会直接失败。

### 一、规模上限

| 对象 | 上限 | 超限怎么办 |
| --- | --- | --- |
| 单个 Java 文件 | 1200 行 | 按职责拆类，而不是继续追加 |
| 单个 Java 方法 | 80 行 | 抽私有方法或独立协作者类 |
| 单个 Vue SFC | 800 行 | 拆子组件 + 抽 `useXxx()` 组合式函数 |
| 单个 JS/TS 逻辑模块 | 800 行 | 拆成多个小模块 |
| 单个 JS 函数 | 150 行 | 同上 |

### 二、耦合上限

1. **Controller 依赖字段 ≤ 8 个**，且**禁止注入 Mapper**（必须经 Service 层）。
   超限就按资源子域拆成多个 Controller。
2. **Service 依赖字段 ≤ 12 个**。超限说明混了多个职责，先拆再接新功能。
3. 单方法参数 ≤ 5 个，超过用参数对象封装。
4. 圈复杂度 ≤ 20，嵌套深度 ≤ 4。

### 三、写法约定

5. 依赖注入**必须构造器注入**（`private final` + Lombok `@RequiredArgsConstructor`）。
   禁止 `@Autowired` / `@Resource` 字段注入。
6. 禁止在字段声明处写全限定类名（`private final com.wenqu.ai.service.Foo foo;`），
   必须先 `import` 再写短名。
7. 禁止 `System.out` / `System.err` / `printStackTrace`，统一用 `@Slf4j` 的 `log.*`。

### 四、改动的第一原则

8. **新增功能优先「新建类 / 新建模块」，而不是「往现有大类里加方法」。**
   只有当新逻辑与该类职责完全一致、且加完不超限时才改现有文件。
   往大文件里追加是最后选项，且必须在 commit message 里说明原因。

### 五、动手前先量

改任何文件之前，先看一眼它当前的行数与依赖数：

```bash
wc -l path/to/File.java          # 或 .vue / .js
grep -cE '^\s+private final ' path/to/Controller.java
```

若已接近或超过上表上限，**先提议拆分方案、再动手改**；不要在原文件上继续堆。

### 六、闸门与白名单

- 后端：`mvn verify` 会跑 Checkstyle（规则 `config/checkstyle/checkstyle.xml`）。
- 前端：`cd web && npm run lint`（规则 `web/eslint.config.js`）。
- 存量违规登记在白名单里（`config/checkstyle/suppressions.xml`、`web/eslint.config.js`
  的 `GRANDFATHERED`），策略是**棘轮：只许变好，不许变坏**。
  - 白名单只允许登记「本次改动之前就已存在」的违规，并在 commit message 里写理由。
  - 已修复的文件必须从白名单删掉，不许留僵尸条目。
  - 新文件不在白名单里，从第一天起必须合规。
- 只想跳过检查快速编译：`mvn -Dcheckstyle.skip=true compile`；
  提交前仍须跑一次完整的 `mvn verify`。