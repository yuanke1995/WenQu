#!/usr/bin/env bash
#
# 沙盒 provisioner 本地启动脚本。
#
# 与 IDEA / build.sh 起的 API 成对使用：API 侧必须设置
#   SANDBOX_PROVISIONER_URL=http://127.0.0.1:8002
# 用 127.0.0.1 而非容器服务名 sandbox-provisioner，一是本机无该 DNS 记录，
# 二是回环地址在 macOS 系统代理的例外列表内，可绕开代理对出站请求的劫持。
#
# 后端模式（PROVISIONER_BACKEND）：
#   memory  —— 默认，由本脚本在宿主机直接起。沙盒记录只存进程内存，不需要容器
#              运行时；管理面（create/discover/touch/delete）完全可用，但没有真实
#              沙盒实例，沙盒内文件与 shell 调用会失败。
#   docker  —— 真沙盒，**不用本脚本**：容器后端在构造期要 inspect 自身容器来反推
#              宿主路径（创建兄弟容器需要宿主绝对路径），必须跑在容器里，因此改用
#              同目录的 docker-compose.yml：
#                  docker compose up -d --build
#
# 用法：
#   bash run.sh install    # 建 .venv 并安装依赖
#   bash run.sh start      # 后台启动
#   bash run.sh stop       # 停止
#   bash run.sh restart
#   bash run.sh status     # 进程状态 + /health 探测
#   bash run.sh logs       # 跟踪日志
#   bash run.sh foreground # 前台启动（调试用）
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/.venv"
PY="$VENV/bin/python"
RUN_DIR="$HERE/run"
PID_FILE="$RUN_DIR/provisioner.pid"
LOG_FILE="$RUN_DIR/provisioner.log"
BOOTSTRAP_PY="${WENQU_BOOTSTRAP_PYTHON:-python3}"

BIND_HOST="${SANDBOX_PROVISIONER_BIND:-127.0.0.1}"
PORT="${SANDBOX_PROVISIONER_PORT:-8002}"

# 与 .idea/runConfigurations/WenquServerApplication.xml 中的取值保持同源。
: "${SANDBOX_PROVISIONER_TOKEN:=wenqu-local-dev-sandbox-provisioner-token}"
export SANDBOX_PROVISIONER_TOKEN
export PROVISIONER_BACKEND="${PROVISIONER_BACKEND:-memory}"
export PROVISIONER_PUBLIC_URL="${PROVISIONER_PUBLIC_URL:-http://127.0.0.1:$PORT}"
# memory 后端没有真实沙盒实例，create 返回的 sandbox_url 取 provisioner 内置默认值。
# 若你另有可用的沙盒 runtime，用 MEMORY_SANDBOX_URL_TEMPLATE 覆盖成它的地址。
# **不要指向 provisioner 自身的 /api/sandboxes/<id>/proxy**：那是管理面对外暴露的转发入口，
# 在 memory 后端下 sandbox_url 会等于该入口本身，转发时形成自引用循环。
if [ -n "${MEMORY_SANDBOX_URL_TEMPLATE:-}" ]; then
  export MEMORY_SANDBOX_URL_TEMPLATE
fi

mkdir -p "$RUN_DIR"

if [ "${#SANDBOX_PROVISIONER_TOKEN}" -lt 32 ]; then
  echo "SANDBOX_PROVISIONER_TOKEN 至少需要 32 字符（provisioner 启动期校验）" >&2
  exit 1
fi

install_deps() {
  if [ ! -x "$PY" ]; then
    echo "==> 创建隔离 venv：$VENV"
    "$BOOTSTRAP_PY" -m venv "$VENV"
  fi
  echo "==> 安装依赖"
  "$VENV/bin/pip" install --disable-pip-version-check -i https://pypi.tuna.tsinghua.edu.cn/simple \
    -r "$HERE/requirements.txt"
  echo "==> 完成"
}

is_running() {
  [ -f "$PID_FILE" ] || return 1
  local pid
  pid="$(cat "$PID_FILE")"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

start() {
  if [ ! -x "$PY" ]; then
    echo "未找到 $PY，请先执行：bash run.sh install" >&2
    exit 1
  fi
  if is_running; then
    echo "已在运行（pid=$(cat "$PID_FILE")）"
    return 0
  fi
  echo "==> 启动 provisioner：backend=$PROVISIONER_BACKEND bind=$BIND_HOST:$PORT"
  cd "$HERE"
  nohup "$PY" -m uvicorn app:app --host "$BIND_HOST" --port "$PORT" \
    >>"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  sleep 2
  if is_running; then
    echo "==> pid=$(cat "$PID_FILE")，日志：$LOG_FILE"
    status
  else
    echo "启动失败，末尾日志：" >&2
    tail -20 "$LOG_FILE" >&2 || true
    rm -f "$PID_FILE"
    exit 1
  fi
}

stop() {
  if ! is_running; then
    echo "未在运行"
    rm -f "$PID_FILE"
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  echo "==> 停止 pid=$pid"
  kill "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    is_running || break
    sleep 1
  done
  if is_running; then
    echo "==> 强制结束"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "==> 已停止"
}

status() {
  if is_running; then
    echo "进程：运行中（pid=$(cat "$PID_FILE")）"
  else
    echo "进程：未运行"
  fi
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 3 \
    -H "Authorization: Bearer $SANDBOX_PROVISIONER_TOKEN" \
    "http://$BIND_HOST:$PORT/health" || echo 000)"
  if [ "$code" = "200" ]; then
    echo "健康：200 OK（http://$BIND_HOST:$PORT/health）"
  else
    echo "健康：不可达（HTTP $code）"
  fi
  echo "后端：$PROVISIONER_BACKEND   公网URL：$PROVISIONER_PUBLIC_URL"
}

foreground() {
  cd "$HERE"
  exec "$PY" -m uvicorn app:app --host "$BIND_HOST" --port "$PORT"
}

case "${1:-start}" in
  install) install_deps ;;
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) tail -f "$LOG_FILE" ;;
  foreground) foreground ;;
  *)
    echo "用法：bash run.sh {install|start|stop|restart|status|logs|foreground}" >&2
    exit 1
    ;;
esac
