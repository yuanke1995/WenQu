#!/usr/bin/env bash
# ============================================================
#  MinerU OCR 版面解析服务管理 - macOS / Linux
#  Compose: <project root>/deploy/ocr/docker-compose.yml
#  服务:    wenqu-mineru（arm64 CPU，2.7.6 pipeline backend）
#  端口:    127.0.0.1:30011 -> 容器 30001
#           （parse.ocrMineruUri 默认已对齐 30011，设置页无需改）
#
#  用法:
#    scripts/mac/start-ocr.sh            # 启动（默认动作）
#    scripts/mac/start-ocr.sh start      # 同上
#    scripts/mac/start-ocr.sh stop       # 停止并移除容器（模型卷保留）
#    scripts/mac/start-ocr.sh status     # 容器状态 + 健康检查
#    scripts/mac/start-ocr.sh health     # 只做 /openapi.json 探活
#    scripts/mac/start-ocr.sh logs       # 跟随日志（看模型下载进度）
#
#  注意:
#    - 需要 Docker Desktop 在运行；镜像不存在时 start 会自动构建
#      （首次构建 ~10 分钟）
#    - 首次解析自动下载 ~2GB 模型到 mineru-models 卷，之后不重复下载
#    - 后端使用: 设置页「文档解析默认模板 → PDF 解析引擎」选 mineru
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="$ROOT/deploy/ocr"
HEALTH_URL="http://127.0.0.1:30011/openapi.json"

# ---- docker: prefer PATH, fallback to Docker Desktop on macOS ----
if command -v docker >/dev/null 2>&1; then
  DOCKER="docker"
elif [ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]; then
  DOCKER="/Applications/Docker.app/Contents/Resources/bin/docker"
else
  echo "docker not found. Install Docker Desktop first."
  exit 1
fi

health() {
  if command -v curl >/dev/null 2>&1 \
     && curl -sf -m 5 "$HEALTH_URL" 2>/dev/null | grep -q '/file_parse'; then
    echo "HEALTHY  $HEALTH_URL (paths 含 /file_parse)"
    return 0
  fi
  echo "UNREACHABLE  $HEALTH_URL（容器未起 / 还在启动 / 端口被占）"
  return 1
}

case "${1:-start}" in
  start)
    if ! $DOCKER info >/dev/null 2>&1; then
      echo "Docker daemon 未运行，请先启动 Docker Desktop。"
      exit 1
    fi
    echo "Starting MinerU OCR (wenqu-mineru) on 127.0.0.1:30011 ..."
    $DOCKER compose -f "$COMPOSE_DIR/docker-compose.yml" up -d --build 2>&1 | tail -3
    echo "等待服务就绪（healthcheck 每 30s 一次，最长 ~2 分钟）..."
    for _ in $(seq 1 12); do
      if health >/dev/null 2>&1; then break; fi
      sleep 10
    done
    health || echo "未就绪也可稍后重试: scripts/mac/start-ocr.sh health"
    echo "提示: 首次解析会下载 ~2GB 模型（docker logs wenqu-mineru 观察进度）。"
    ;;
  stop)
    echo "Stopping wenqu-mineru（模型卷 mineru-models 保留，下次免下载）..."
    $DOCKER compose -f "$COMPOSE_DIR/docker-compose.yml" down 2>&1 | tail -2
    ;;
  status)
    $DOCKER ps -a --filter name=wenqu-mineru --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    health || true
    ;;
  health)
    health
    ;;
  logs)
    $DOCKER logs -f wenqu-mineru
    ;;
  *)
    echo "用法: $0 [start|stop|status|health|logs]"
    exit 1
    ;;
esac
