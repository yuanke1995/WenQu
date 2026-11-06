# 浏览器操作服务（browser-mcp）

平台级 MCP 服务「浏览器操作」的数据面：一个 headless Chromium + Playwright MCP 的 streamable HTTP 端点。
问渠侧不需要任何额外配置——启动期会播种平台行（`c_ai_user_mcp.scope='platform'`）与「网页操作助手」，
只有地址要在部署时按下面的说明改一次。

## 起服务

```bash
docker compose --profile browser up -d browser-mcp
```

默认不随主栈启动（profile），不起它就是「有助手、没能力」：MCP 面板会显示未连接，
助手调用工具时得到明确的失败结果，不会静默降级。

启动后把平台服务地址改成容器名（平台行仅管理员可编辑，MCP 面板 → 浏览器操作 → 编辑）：

```
http://browser-mcp:8931/mcp
```

本机 IDE 跑后端时不用容器，直接：

```bash
npx --yes @playwright/mcp@0.0.84 --host 127.0.0.1 --port 8931 --headless --isolated --no-webmcp --allowed-hosts '*'
```

地址保持默认的 `http://127.0.0.1:8931/mcp` 即可。

两个本机实测踩到的点：

- **必须带 `--host 127.0.0.1`**：不带时只监听 IPv6 回环（`[::1]:8931`），后端按 `127.0.0.1` 连会一直停在
  `connecting`，等重试退避起来像「服务坏了」。
- **MCP 默认用系统 Chrome**（`channel=chrome`）：没装 Chrome 就先 `--browser msedge`（或 firefox/webkit）跑；
  容器里用 `config.json` 指向随包安装的 Chromium。错误信息会直接告诉你缺哪个浏览器。

## 安全边界（都做了）

- **不发布端口**：只在 compose 内网可达，宿主机上 `localhost:8931` 是通的只有本机 npx 那种起法。
- **出口过滤**：`egress-guard.sh` 只放行业务后端一个内网地址，其余 RFC1918 / 链路本地 / 回环
  一律 DROP。浏览器里的跳转与 XHR 不经 JVM，代码层 SSRF 校验管不到——这一层是必须的。
- **profile 不落盘**：`--isolated`，浏览器数据只在内存，会话结束即清，多用户之间不残留登录态。
- **页面不能注册工具**：`--no-webmcp`。
- **工具黑名单**：`browser_evaluate` / `browser_run_code_unsafe`（任意代码执行）、
  `browser_file_upload` / `browser_drop`（本地文件外送）、`browser_network_request(s)` /
  `browser_console_messages`（整块内容倒进上下文）在播种的助手配置里默认禁用。

## 还没做（下一阶段）

- 域名白名单（当前是「公网都可以」，不是「只许某几个站点」）；
- 凭据托管（需要登录态的内部系统：用 `--secrets` + 服务端填充，密码不进模型上下文）；
- 验证码/登录的人工接管（当前约定是模型停下来调 `askUser`）；
- 每用户独立浏览器实例（当前共享容器、每会话独立 context；并发高时按 CPU/内存扩容或按用户分实例）。

## 资源

Chromium 需要大共享内存：compose 里已设 `shm_size: 1gb`（默认 64MB 会直接崩）。
每个使用者一条 MCP 连接 = 一个浏览器 context，空闲 5 分钟自动关浏览器；
并发上量时调 `BROWSER_MCP_MEMORY` 并考虑多副本。
