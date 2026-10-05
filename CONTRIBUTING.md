# 贡献指南（Contributing）

感谢对问渠（WenQu）的兴趣！欢迎提交 Issue 与 Pull Request。

## 许可约定

提交即表示你同意你的贡献以 [MIT License](LICENSE) 随本项目发布。

## 本地开发

环境准备、环境变量与一键起依赖（`docker compose up -d redis-stack meilisearch`）见 [README · 快速开始](README.md#快速开始)。缺必填环境变量会 fail-fast，按 README「配置环境变量」逐项配置即可。

- **后端**：Java 17 · Maven 单体工程（`pom.xml` 在仓库根），入口 `WenQuApplication`，启动方式见 README「启动后端」（直跑 / 打包 / Docker Compose 三选一）
- **前端**：`web/` 目录，Node ≥22.12（`.nvmrc` 固定 22 LTS），`npm install && npm run dev`；dev 端口 5800，`/proxy` 转发后端 `http://localhost:8090/ai`
- **本地私有配置**：放 `config/application-local.yml`（已被 .gitignore 忽略，不要 force add）；运行时数据在 `data/`、`user-data/`，同样不入库

## 提交规范

- 中文 conventional commit：`类型(范围): 描述`，如 `feat(chat): 支持xxx`、`fix(kb): 修复yyy`
- 一个 PR 只做一件事；UI 改动附截图，行为改动写清验证步骤
- 设置项相关改动须同步 `src/main/resources/config-schema.json`（设置页渲染的唯一 schema 源）

## 报 Issue

- **Bug**：附部署方式（Compose / 裸机）、版本或 commit、复现步骤、相关日志片段
- **功能建议**：先讲场景再说方案；大特性建议先开 Issue 讨论再动手
