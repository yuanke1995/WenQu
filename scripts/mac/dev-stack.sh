#!/usr/bin/env bash
# ============================================================
#  问渠本机开发栈统一启停 - macOS
#
#  管什么（原则：数据在谁手里就管谁，不搬盘；meili 是例外——索引属可从 MySQL 重算的
#  派生数据，已整体搬进 docker 命名卷）
#    mysql     3306   brew services mysql@8.0
#    redis     6379   原生 redis-stack-server —— 向量库在这个实例；compose 里的
#                     同名服务是另一份实例（本机 app 连 6379 这个）
#    meili     7700   docker compose deploy/meilisearch —— 关键词索引在命名卷
#                     wenqu-meili-data（原原生实例的 data.ms 已迁入该卷）
#    searxng   8888   docker compose deploy/searxng   联网搜索 generic 服务商
#    sandbox   8002   docker compose deploy/sandbox-provisioner
#
#  用法（任意目录都可执行，根目录由脚本自身位置解析，无需 cd）
#    dev-stack.sh                启动默认组（mysql redis meili searxng sandbox）
#    dev-stack.sh up searxng     启动指定单元，可空格给多个
#    dev-stack.sh down           停止默认组；down <unit...> 停止指定单元
#    dev-stack.sh restart meili  重启指定单元
#    dev-stack.sh status         一览（含端口、方式、探活结果）
#    dev-stack.sh logs sandbox   跟随日志（Ctrl+C 退出）
#
#  可用环境变量覆盖（换机/改端口）：
#    MYSQL_SERVICE REDIS_PORT REDIS_DATA_DIR MEILI_KEY MEILI_PORT
#    SEARXNG_PORT SANDBOX_PORT OCR_PORT LOG_DIR
#  MEILI_KEY 两侧必须一致：它既是容器的 MEILI_MASTER_KEY，也要等于设置页「检索设置 →
#  服务连接」里的 Key，不一致是 403。
#  索引数据在命名卷 wenqu-meili-data 里，不在宿主目录；想推倒重来：
#    docker compose -f deploy/meilisearch/docker-compose.yml down &&
#    docker volume rm wenqu-meili-data && 本脚本 up meili
#  得到的是空索引，需在设置页全量重建（或 POST /api/ai/search-index/reindex）。
#
#  想少敲路径，可在 ~/.zshrc 加（路径换成你的项目根）：
#    alias wenqu-stack='<项目根>/scripts/mac/dev-stack.sh'
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="${LOG_DIR:-$ROOT/logs}"

MYSQL_SERVICE="${MYSQL_SERVICE:-mysql@8.0}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_DATA_DIR="${REDIS_DATA_DIR:-/opt/homebrew/var/db/redis-stack}"
REDIS_CLI="${REDIS_CLI:-$(command -v redis-cli 2>/dev/null || echo /opt/homebrew/bin/redis-cli)}"
MEILI_KEY="${MEILI_KEY:-my-master-key-123456}"
MEILI_PORT="${MEILI_PORT:-7700}"
# compose 读这两个变量渲染 master key 与宿主端口，必须是环境变量
export MEILI_KEY MEILI_PORT
MEILI_COMPOSE="$ROOT/deploy/meilisearch/docker-compose.yml"
MEILI_URL="http://127.0.0.1:${MEILI_PORT}"
SEARXNG_PORT="${SEARXNG_PORT:-8888}"
SEARXNG_URL="http://127.0.0.1:${SEARXNG_PORT}"
SANDBOX_PORT="${SANDBOX_PORT:-8002}"
SANDBOX_URL="http://127.0.0.1:${SANDBOX_PORT}"

DEFAULT_UNITS=(mysql redis meili searxng sandbox)
ALL_UNITS=(mysql redis meili searxng sandbox)

# ---- 探活原语 ----
tcp_up() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
http_up() { curl -sf -m "${2:-4}" "$1" >/dev/null 2>&1; }

# ---- docker ----
if command -v docker >/dev/null 2>&1; then
  DOCKER="docker"
elif [ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]; then
  DOCKER="/Applications/Docker.app/Contents/Resources/bin/docker"
else
  DOCKER=""
fi
compose() { local f="$1"; shift; "$DOCKER" compose -f "$f" "$@"; }
docker_ready() {
  if [ -z "$DOCKER" ]; then echo "  docker 未找到，请安装 Docker Desktop"; return 1; fi
  "$DOCKER" info >/dev/null 2>&1 || { echo "  Docker daemon 未运行，请先启动 Docker Desktop"; return 1; }
}

wait_up() { "$1" && return 0; printf '  等待中'; for _ in $(seq 1 "${2:-12}"); do
  sleep 2; if "$1"; then printf ' 就绪\n'; return 0; fi; printf '.'; done
  printf '  超时\n'; return 1; }

# ============================================================
#  各单元：u_<name>_up / u_<name>_down / u_<name>_health / u_<name>_logs
# ============================================================

u_mysql_health() { tcp_up "$MYSQL_PORT"; }
u_mysql_up() {
  if u_mysql_health; then echo "  已在运行 :$MYSQL_PORT"; return 0; fi
  command -v brew >/dev/null 2>&1 || { echo "  brew 未找到，请手动启动 MySQL"; return 1; }
  echo "  brew services start $MYSQL_SERVICE"
  brew services start "$MYSQL_SERVICE" >/dev/null 2>&1
  wait_up u_mysql_health 30
}
u_mysql_down() {
  u_mysql_health || { echo "  本来就没跑"; return 0; }
  brew services stop "$MYSQL_SERVICE" >/dev/null 2>&1 && echo "  已停止（数据在 brew 的 my.cnf 数据目录，未动）"
}
u_mysql_logs() { echo "  brew 服务，日志: $(brew --prefix 2>/dev/null)/var/log/$MYSQL_SERVICE.log" >&2; tail -f "$(brew --prefix 2>/dev/null)/var/log/$MYSQL_SERVICE.log" 2>/dev/null; }

u_redis_health() { [ "$("$REDIS_CLI" -p "$REDIS_PORT" ping 2>/dev/null)" = "PONG" ]; }
u_redis_up() {
  if u_redis_health; then echo "  已在运行 :$REDIS_PORT"; return 0; fi
  local bin="${REDIS_BIN:-$(command -v redis-server || echo /opt/homebrew/bin/redis-server)}"
  [ -x "$bin" ] || { echo "  redis-server 未找到（$bin）"; return 1; }
  mkdir -p "$REDIS_DATA_DIR" "$LOG_DIR"
  # 必须在数据目录里启动：dump.rdb（向量库全量数据）按 cwd 解析，换目录起就是空库
  echo "  启动 redis-stack-server（cwd=$REDIS_DATA_DIR）"
  ( cd "$REDIS_DATA_DIR" && nohup "$bin" --port "$REDIS_PORT" >>"$LOG_DIR/redis-stack.log" 2>&1 & )
  wait_up u_redis_health 15 || return 1
  "$REDIS_CLI" -p "$REDIS_PORT" MODULE LIST 2>/dev/null | grep -q ReJSON \
    || echo "  警告: 加载的不是 redis-stack（缺 ReJSON/Search），向量检索会失败"
}
u_redis_down() {
  u_redis_health || { echo "  本来就没跑"; return 0; }
  "$REDIS_CLI" -p "$REDIS_PORT" shutdown save 2>/dev/null
  u_redis_health && echo "  仍在运行，未停止" || echo "  已停止（shutdown save，dump.rdb 已落盘）"
}
u_redis_logs() { tail -f "$LOG_DIR/redis-stack.log" 2>/dev/null || echo "  当前实例不是本脚本起的，日志在它自己的 stdout/$(brew --prefix 2>/dev/null)/var/log/" >&2; }

u_meili_health() { http_up "$MEILI_URL/health"; }
u_meili_up() {
  if u_meili_health; then echo "  已在运行 $MEILI_URL"; return 0; fi
  docker_ready || return 1
  local f="$MEILI_COMPOSE" out
  out="$(compose "$f" up -d 2>&1)" || { echo "  docker compose up 失败:"; printf '%s\n' "$out" | sed 's/^/    /'; return 1; }
  echo "  wenqu-meilisearch $MEILI_URL（索引数据在命名卷 wenqu-meili-data）"
  wait_up u_meili_health 20
}
u_meili_down() {
  docker_ready || return 1
  u_meili_health || echo "  本来就没跑"
  compose "$MEILI_COMPOSE" down >/dev/null 2>&1 \
    && echo "  已停止（容器移除，数据卷 wenqu-meili-data 未动）"
}
u_meili_logs() { docker_ready && compose "$MEILI_COMPOSE" logs -f; }

u_searxng_health() { http_up "$SEARXNG_URL/" 6; }
u_searxng_up() {
  if u_searxng_health; then echo "  已在运行 $SEARXNG_URL"; return 0; fi
  docker_ready || return 1
  local f="$ROOT/deploy/searxng/docker-compose.yml" out
  out="$(compose "$f" up -d 2>&1)" || { echo "  docker compose up 失败:"; printf '%s\n' "$out" | sed 's/^/    /'; return 1; }
  echo "  wenqu-searxng :$SEARXNG_PORT"
  wait_up u_searxng_health 20 || return 1
  curl -sf -m 25 "$SEARXNG_URL/search?q=test&format=json" 2>/dev/null | grep -qE '"(results|queries)"' \
    && echo "  JSON 接口可用" \
    || echo "  警告: /search?format=json 没拿到 JSON，检查 settings.yml 的 search.formats 与引擎可达性"
}
u_searxng_down() {
  docker_ready || return 1
  u_searxng_health || echo "  本来就没跑"
  compose "$ROOT/deploy/searxng/docker-compose.yml" down >/dev/null 2>&1 \
    && echo "  已停止（容器移除，无数据卷）"
}
u_searxng_logs() { docker_ready && compose "$ROOT/deploy/searxng/docker-compose.yml" logs -f; }

u_sandbox_health() { http_up "$SANDBOX_URL/health" 6; }
u_sandbox_up() {
  if u_sandbox_health; then echo "  已在运行 $SANDBOX_URL"; return 0; fi
  docker_ready || return 1
  local f="$ROOT/deploy/sandbox-provisioner/docker-compose.yml" out
  echo "  启动 sandbox-provisioner（镜像缺失时自动构建）"
  out="$(compose "$f" up -d 2>&1)" || { echo "  docker compose up 失败:"; printf '%s\n' "$out" | sed 's/^/    /'; return 1; }
  wait_up u_sandbox_health 30 && echo "  wenqu-sandbox-provisioner $SANDBOX_URL/health OK"
}
u_sandbox_down() {
  docker_ready || return 1
  u_sandbox_health || echo "  本来就没跑"
  compose "$ROOT/deploy/sandbox-provisioner/docker-compose.yml" down >/dev/null 2>&1 \
    && echo "  已停止（沙盒容器随之移除；user-data/ 与 skill-projections/ 宿主目录未动）"
}
u_sandbox_logs() { docker_ready && compose "$ROOT/deploy/sandbox-provisioner/docker-compose.yml" logs -f; }

# OCR 走 deploy/ocr 的 docker compose（不在本脚本管理范围内）。

# ============================================================
#  调度
# ============================================================

SELECTED=()
select_units() {
  SELECTED=()
  if [ "$#" -eq 0 ]; then SELECTED=("${DEFAULT_UNITS[@]}"); return 0; fi
  local u
  for u in "$@"; do
    case " ${ALL_UNITS[*]} " in
      *" $u "*) SELECTED+=("$u") ;;
      *) echo "未知单元: $u（可选: ${ALL_UNITS[*]}）" >&2; return 1 ;;
    esac
  done
}

run_for() {
  local action="$1" u rc=0
  for u in "${SELECTED[@]}"; do
    printf '[%s] %s\n' "$action" "$u"
    "u_${u}_${action}" || rc=1
  done
  return $rc
}

do_status() {
  printf '%-9s %-7s %-24s %s\n' "单元" "端口" "方式" "状态"
  printf -- '---------------------------------------------------------------\n'
  printf '%-9s %-7s %-24s %s\n' "mysql" "$MYSQL_PORT" "brew $MYSQL_SERVICE" "$(u_mysql_health && echo 运行中 || echo 未运行)"
  printf '%-9s %-7s %-24s %s\n' "redis" "$REDIS_PORT" "原生 redis-stack-server" "$(u_redis_health && echo 运行中 || echo 未运行)"
  printf '%-9s %-7s %-24s %s\n' "meili" "$MEILI_PORT" "docker compose" "$(u_meili_health && echo 运行中 || echo 未运行)"
  printf '%-9s %-7s %-24s %s\n' "searxng" "$SEARXNG_PORT" "docker compose" "$(u_searxng_health && echo 运行中 || echo 未运行)"
  printf '%-9s %-7s %-24s %s\n' "sandbox" "$SANDBOX_PORT" "docker compose" "$(u_sandbox_health && echo 运行中 || echo 未运行)"
  echo
  [ -n "$DOCKER" ] && "$DOCKER" info >/dev/null 2>&1 && echo "docker: 就绪" || echo "docker: 不可用（Docker Desktop 未启动？）"
  echo "项目根: $ROOT"
}

usage() {
  cat <<EOF
用法: $(basename "${BASH_SOURCE[0]}") [start|up|stop|down|restart|status|logs|help] [单元...]
  不给动作 = start 默认组（${DEFAULT_UNITS[*]}）
  单元: ${ALL_UNITS[*]}
  任意目录都可执行；详见文件头注释。
EOF
}

case "${1:-start}" in
  start|up)     select_units "${@:2}" || exit 2; run_for up ;;
  stop|down)    select_units "${@:2}" || exit 2
                echo "提示: 只停进程/容器，不删数据卷、索引与 dump.rdb。"
                run_for down ;;
  restart)      select_units "${@:2}" || exit 2; run_for down; run_for up ;;
  status|ps)    do_status ;;
  logs|log)     select_units "${@:2}" || exit 2
                [ "${#SELECTED[@]}" = "1" ] || { echo "logs 只接一个单元"; exit 2; }
                "u_${SELECTED[0]}_logs" ;;
  help|-h|--help) usage ;;
  *) echo "未知动作: $1"; usage; exit 2 ;;
esac
