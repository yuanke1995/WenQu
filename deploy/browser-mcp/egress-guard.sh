#!/bin/sh
# 出口过滤（容器内 iptables）。
#
# 为什么必须在网络层做：浏览器里的导航、重定向、JS 发起的 XHR 都不经 JVM，
# 应用层的 SsrfGuard 只能校验交给浏览器的第一个 URL。没有这一层，
# 「打开 http://127.0.0.1:8095」或 169.254.169.254 这类请求就能打到内网。
#
# 规则口径：按目的地址截断（不是域名白名单——那需要代理，见 README 的后续项）。
#   - 只放行业务后端一个内网地址（APP_IP），其余 RFC1918 / 链路本地 / 回环一律 DROP；
#   - 公网目的地址照常放行：默认网关虽然是内网地址，但 OUTPUT 看到的是最终公网目的 IP；
#   - Docker 内嵌 DNS 在 127.0.0.11（走 lo），必须先放行。
#
# 已知边界：域名白名单、凭据托管、验证码人工接管都不在这一层。
set -e

APP_IP="${APP_IP:-172.28.0.10}"

iptables -F OUTPUT
iptables -A OUTPUT -o lo -j ACCEPT
iptables -A OUTPUT -d "$APP_IP" -j ACCEPT
for cidr in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 100.64.0.0/10 127.0.0.0/8; do
  iptables -A OUTPUT -d "$cidr" -j DROP
done

echo "[browser-mcp] egress guard: allow ${APP_IP}, drop RFC1918/link-local/loopback"
exec "$@"
