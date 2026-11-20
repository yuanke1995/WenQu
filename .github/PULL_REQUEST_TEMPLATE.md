<!-- 标题格式：类型(范围): 描述，如 feat(chat): 支持xxx -->

## 这个 PR 做了什么

## 怎么验证

## 自查清单

- [ ] 后端编译/打包通过
- [ ] 前端构建通过（`cd web && npm run build`）
- [ ] 未引入密钥或本地私有配置
- [ ] 涉及设置项的改动已同步 `config-schema.json`
- [ ] 跨平台：未硬编码 OS 路径/二进制；平台差异走 `scripts/local-agent/platform/` 或公共工具（`TextIo`/`FileNameSanitizer`/`pathUtil`/`fileName`/`clipboard`）
- [ ] 涉及执行器或脚本：`node scripts/run-tests.mjs scripts` 通过；行尾 `node scripts/check-eol.mjs` 通过
