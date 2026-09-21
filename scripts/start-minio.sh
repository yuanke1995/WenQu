#!/usr/bin/env bash
# 问渠(wenqu)本地 MinIO 服务启动脚本
#
# 用途：后端 MinioStorageClient 只认 MinIO 作为文档存储后端（无本地文件系统备选），
#       本地开发需先起一个 MinIO 实例。本脚本封装启动/停止/状态检查。
#
# 默认 root 账号 minioadmin/minioadmin，与后端 MinioStorageClient 构造器默认值一致，
# 因此后端无需额外配置 MINIO_ACCESS_KEY/MINIO_SECRET_KEY，只需把 MINIO_URI 指向本机。
#
# 用法：
#   bash scripts/start-minio.sh start     # 后台启动（默认）
#   bash scripts/start-minio.sh stop      # 停止
#   bash scripts/start-minio.sh status    # 查看监听状态
#   bash scripts/start-minio.sh restart   # 重启
#
# 环境变量（均可覆盖，默认值见下）：
#   MINIO_BIN         minio 二进制路径（默认自动探测 /opt/homebrew/bin/minio 或 /usr/local/bin/minio）
#   MINIO_DATA_DIR    数据目录（默认 $HOME/minio-data）
#   MINIO_PORT        服务端口（默认 9000）
#   MINIO_CONSOLE_PORT 控制台端口（默认 9001）
#   MINIO_ROOT_USER   管理员账号（默认 minioadmin，须与后端 MINIO_ACCESS_KEY 一致）
#   MINIO_ROOT_PASSWORD 管理员密码（默认 minioadmin，须与后端 MINIO_SECRET_KEY 一致）

set -euo pipefail

MINIO_PORT="${MINIO_PORT:-9000}"
MINIO_CONSOLE_PORT="${MINIO_CONSOLE_PORT:-9001}"
MINIO_DATA_DIR="${MINIO_DATA_DIR:-$HOME/minio-data}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-minioadmin}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minioadmin}"

# 自动探测 minio 二进制
if [ -z "${MINIO_BIN:-}" ]; then
  if [ -x /opt/homebrew/bin/minio ]; then
    MINIO_BIN=/opt/homebrew/bin/minio
  elif [ -x /usr/local/bin/minio ]; then
    MINIO_BIN=/usr/local/bin/minio
  elif command -v minio >/dev/null 2>&1; then
    MINIO_BIN="$(command -v minio)"
  else
    echo "✗ 找不到 minio 二进制。请先安装：brew install minio" >&2
    exit 1
  fi
fi

PID_FILE="$MINIO_DATA_DIR/.minio.pid"
LOG_FILE="$MINIO_DATA_DIR/minio.log"

listener_pid() {
  lsof -nP -iTCP:"$MINIO_PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true
}

case "${1:-start}" in
  start)
    existing="$(listener_pid)"
    if [ -n "$existing" ]; then
      echo "✗ 端口 $MINIO_PORT 已被 PID $existing 占用，MinIO 似乎已在运行。"
      echo "  若需重启：bash $0 restart"
      exit 1
    fi
    mkdir -p "$MINIO_DATA_DIR"
    : > "$LOG_FILE"
    echo "启动 MinIO（data=$MINIO_DATA_DIR, port=$MINIO_PORT, console=:$MINIO_CONSOLE_PORT）…"
    MINIO_ROOT_USER="$MINIO_ROOT_USER" \
    MINIO_ROOT_PASSWORD="$MINIO_ROOT_PASSWORD" \
    nohup "$MINIO_BIN" server "$MINIO_DATA_DIR" \
      --address ":$MINIO_PORT" --console-address ":$MINIO_CONSOLE_PORT" \
      >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    # 等待监听就绪
    for i in $(seq 1 30); do
      sleep 1
      if [ -n "$(listener_pid)" ]; then
        echo "✓ MinIO 已启动（PID $(cat "$PID_FILE")），监听 http://localhost:$MINIO_PORT"
        echo "  控制台: http://localhost:$MINIO_CONSOLE_PORT  (账号 $MINIO_ROOT_USER)"
        echo "  日志: $LOG_FILE"
        exit 0
      fi
    done
    echo "✗ 启动后 30s 内未监听 $MINIO_PORT，请查看日志："
    echo "  tail -n 30 $LOG_FILE"
    exit 1
    ;;
  stop)
    target="$(listener_pid)"
    if [ -z "$target" ] && [ -f "$PID_FILE" ]; then
      target="$(cat "$PID_FILE" 2>/dev/null || true)"
    fi
    if [ -n "$target" ]; then
      echo "停止 MinIO (PID $target)…"
      kill "$target" 2>/dev/null || true
      rm -f "$PID_FILE"
      echo "✓ 已发送停止信号"
    else
      echo "未找到运行中的 MinIO 进程"
    fi
    ;;
  status)
    pid="$(listener_pid)"
    if [ -n "$pid" ]; then
      echo "✓ MinIO 运行中（PID $pid），监听 http://localhost:$MINIO_PORT"
    else
      echo "✗ MinIO 未运行"
      exit 1
    fi
    ;;
  restart)
    bash "$0" stop || true
    sleep 1
    bash "$0" start
    ;;
  *)
    echo "用法: bash $0 {start|stop|status|restart}" >&2
    exit 1
    ;;
esac
