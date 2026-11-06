# 浏览器操作 MCP 服务（平台级「浏览器操作」的数据面）。
#
# 为什么自己构建而不是拉现成镜像：MCP 包锁定的 playwright 版本决定 Chromium 修订号，
# 两者对不上浏览器起不来。这里让包自己装它要的那一版浏览器，版本天然一致。
#
# 安全前提（与本目录 README 同步，缺一不可）：
#   - 不发布端口到宿主机，只在 compose 内网里给后端用；
#   - 出口过滤见 egress-guard.sh：浏览器里的每次跳转与 XHR 都不经 JVM，代码层 SSRF 校验只能管第一跳；
#   - --isolated：浏览器 profile 只在内存里，会话结束即清，多用户之间不残留登录态；
#   - --no-webmcp：不允许页面通过 WebMCP 往工具表里注册任何东西。
# --no-sandbox 的代价由上面三条兜：容器本身可丢弃、出口收敛、profile 不落盘。
# 基础镜像可换：内网/受限网络环境（拉不到 Docker Hub）可用本地已有、带 node 的镜像构建——
# 只要它是 glibc 发行版（Debian/Ubuntu）且 node ≥18，Playwright 的 Chromium 就能正常跑。
ARG BASE_IMAGE=node:22-bookworm-slim
FROM ${BASE_IMAGE}

ARG MCP_VERSION=0.0.84
# Playwright 浏览器下载源（国内直连 cdn.playwright.dev 会卡住）：
# 设成 https://cdn.npmmirror.com/binaries/playwright 即可，路径结构一致。
ARG PLAYWRIGHT_DOWNLOAD_HOST=

WORKDIR /app
# 出口过滤脚本要用 iptables；精简基础镜像（含沙盒镜像）通常没带，显式装上
RUN apt-get update \
    && apt-get install -y --no-install-recommends iptables \
    && rm -rf /var/lib/apt/lists/*
RUN npm init -y >/dev/null \
    && npm i --no-audit --no-fund @playwright/mcp@${MCP_VERSION} \
    && PLAYWRIGHT_DOWNLOAD_HOST=${PLAYWRIGHT_DOWNLOAD_HOST} npx playwright install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/* /root/.npm

ENV PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright \
    TZ=Asia/Shanghai

COPY egress-guard.sh /usr/local/bin/egress-guard.sh
RUN chmod +x /usr/local/bin/egress-guard.sh

# MCP 默认用系统 Chrome（channel=chrome），镜像里没有——指向随包安装的 Chromium
COPY config.json /app/config.json

EXPOSE 8931

# --allowed-hosts '*'：服务名由 compose 决定，端口不对外发布；Host 校验拦不住真实攻击面，
#   真实边界是网络隔离与出口过滤。
# --idle-timeout：长时间没人用就关掉浏览器，免得空占内存（会话重连后自动重建）。
ENTRYPOINT ["/usr/local/bin/egress-guard.sh", "npx", "--no-install", "playwright-mcp", \
    "--host", "0.0.0.0", "--port", "8931", \
    "--headless", "--isolated", "--no-webmcp", "--no-sandbox", \
    "--allowed-hosts", "*", "--config", "/app/config.json", \
    "--output-dir", "/tmp/browser-output", \
    "--idle-timeout", "300000"]
