#!/usr/bin/env bash
# 问渠(wenqu) 脚本统一调度器
# ---------------------------------------------------------------------------
# scripts/ 下所有开发/运维脚本按分组集中管理，本调度器提供单一入口：
#   列出分组与脚本、按文件名或「分组 + 文件名」运行，并自动选择解释器。
# 另提供 infra 子命令，统一管理本项目的所有中间件（启停/探活）。
#
# 用法:
#   ./wenqu.sh                                 # 打印全部分组与脚本清单
#   ./wenqu.sh <group>                         # 列出某分组下的脚本
#   ./wenqu.sh run <name> [args..]             # 按文件名(任意子目录)查找并运行
#   ./wenqu.sh <group> <name> [args..]         # 在指定分组下运行
#
#   ./wenqu.sh infra                           # 列出所有中间件状态(端口/运行/启停命令)
#   ./wenqu.sh infra up <name|all>             # 启动中间件
#   ./wenqu.sh infra down <name|all>           # 停止中间件
#   ./wenqu.sh infra restart <name|all>        # 重启中间件
#   ./wenqu.sh infra logs <name>               # 跟踪某中间件日志
#   ./wenqu.sh infra status [name|all]         # 仅打印运行状态
#
#   中间件: mysql redis meili searxng sandbox ocr
#
# 解释器:  .sh->bash   .py->python3   .js/.mjs->node
#          .bat->仅 Windows   .sql->打印 mysql 执行命令
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GRPS="db services codegen setup loadtest mac win"
INFRA_NAMES="mysql redis meili searxng sandbox ocr"

# ===================== 脚本分组 =====================
desc_of() {
  case "$1" in
    db/init-admin-2026-09-17.sql)            echo "初始化管理员账号" ;;
    db/fix-schema-2026-09-16.sql)            echo "补齐早期 schema（表/列）" ;;
    db/fix-schema-2026-09-16-auth.sql)       echo "补齐 auth 相关 schema" ;;
    db/fix-schema-2026-10-12-tool-approval-text.sql) echo "修复 tool_approval 文本字段" ;;
    db/migrate-reasoning-levels-2026-10-03.sql)      echo "迁移 reasoning 级别" ;;
    db/cleanup-personal-model-refs-2026-10-03.sql)   echo "清理个人模型引用" ;;
    db/cleanup-deep-reasoning-switches-2026-10-03.sql) echo "清理深度推理开关" ;;
    db/cleanup-retired-config-2026-09-30.sql) echo "清理已废弃配置" ;;
    services/mcp-test-server.py)             echo "最小 MCP 测试服务(:8931)" ;;
    services/fake_upstream_no_tools.py)      echo "假上游(tools 拒路测试, :7999)" ;;
    codegen/gen_config_schema.js)            echo "配置字段 ↔ defaults 一致性校验" ;;
    setup/setup-node.sh)                     echo "Ubuntu 安装/升级 Node(前端依赖)" ;;
    loadtest/mock-gateway.mjs)               echo "压测 mock 网关(:9101)" ;;
    loadtest/loadtest.mjs)                   echo "100 路并发压测" ;;
    mac/dev-stack.sh)                        echo "本机开发栈统一启停(mysql/redis/meili/searxng/sandbox)" ;;
    mac/start-backend.sh)                    echo "启动后端 jar" ;;
    win/start-backend.bat)                   echo "启动后端 jar (Windows)" ;;
    *) echo "" ;;
  esac
}

is_group() {
  for g in $GRPS; do [ "$g" = "$1" ] && return 0; done
  return 1
}

list_group() {
  local g="$1"
  echo "▸ $g/"
  local f rel d
  for f in $(cd "$SCRIPT_DIR/$g" 2>/dev/null && ls -1 2>/dev/null); do
    rel="$g/$f"
    d="$(desc_of "$rel")"
    printf "    %-42s %s\n" "$f" "$d"
  done
}

list_all() {
  echo "问渠脚本清单 (scripts/)"
  echo "用法: ./wenqu.sh [group] [name] [args]   |   ./wenqu.sh run <name> [args]"
  echo
  for g in $GRPS; do list_group "$g"; echo; done
}

find_rel() {
  ( cd "$SCRIPT_DIR" && find . -type f -name "$1" 2>/dev/null | head -1 | sed 's|^\./||' )
}

run_file() {
  local f="$1"; shift || true
  [ -f "$f" ] || { echo "未找到脚本: $f" >&2; exit 1; }
  case "$f" in
    *.sh)  bash "$f" "$@" ;;
    *.py)  python3 "$f" "$@" ;;
    *.js|*.mjs) node "$f" "$@" ;;
    *.bat) case "$(uname)" in
             MINGW*|CYGWIN*|MSYS*) cmd.exe "$f" "$@" ;;
             *) echo "(.bat 仅 Windows 可用，请在 Windows 上运行 scripts/win/ 下对应脚本)" >&2; exit 1 ;;
           esac ;;
    *.sql) echo "SQL 不可直接执行，请运行：" >&2
           echo "  mysql -h \"\${DB_HOST:-127.0.0.1}\" -u \"\${DB_USER:-root}\" -p \"\${DB_NAME:-wenqu_ai}\" --force < \"$f\"" >&2
           exit 0 ;;
    *) echo "不支持的类型: $f" >&2; exit 1 ;;
  esac
}

# ===================== 中间件 infra =====================
infra_port() {
  case "$1" in
    mysql)   echo 3306  ;;
    redis)   echo 6379  ;;
    meili)   echo 7700  ;;
    searxng) echo 8888  ;;
    sandbox) echo 8002  ;;
    ocr)     echo 30011 ;;
  esac
}
infra_container() {
  case "$1" in
    meili)   echo ai-doc-meilisearch ;;
    searxng) echo wenqu-searxng ;;
    sandbox) echo wenqu-sandbox-provisioner ;;
    ocr)     echo wenqu-mineru ;;
    *) echo "" ;;
  esac
}
infra_desc() {
  case "$1" in
    mysql)   echo "MySQL 数据库 (brew mysql@8.0)" ;;
    redis)   echo "Redis 缓存  (brew redis)" ;;
    meili)   echo "Meilisearch 检索 (docker ai-doc-meilisearch)" ;;
    searxng) echo "SearXNG 联网搜索 (docker compose)" ;;
    sandbox) echo "代码沙盒 (docker compose)" ;;
    ocr)     echo "MinerU OCR (docker compose)" ;;
  esac
}
# 返回: running(docker) | running | stopped
infra_state() {
  local name="$1" port="$2" c
  c="$(infra_container "$name")"
  if [ -n "$c" ] && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$c"; then
    echo "running(docker)"; return
  fi
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "running"; return
  fi
  echo "stopped"
}
infra_up() {
  local name="$1"
  case "$name" in
    mysql)   brew services start mysql@8.0 ;;
    redis)   brew services start redis ;;
    meili)   docker start ai-doc-meilisearch 2>/dev/null \
               || echo "容器不存在，请先运行: docker run -d --name ai-doc-meilisearch -p 7700:7700 -e MEILI_ENV=development getmeili/meilisearch:v1.53" ;;
    searxng) docker start wenqu-searxng 2>/dev/null \
               || (cd "$PROJECT_ROOT/deploy/searxng" && docker compose up -d) ;;
    sandbox) (cd "$PROJECT_ROOT/deploy/sandbox-provisioner" && docker compose up -d) ;;
    ocr)     (cd "$PROJECT_ROOT/deploy/ocr" && docker compose up -d) ;;
  esac
}
infra_down() {
  local name="$1"
  case "$name" in
    mysql)   brew services stop mysql@8.0 ;;
    redis)   brew services stop redis ;;
    meili)   docker stop ai-doc-meilisearch ;;
    searxng) docker stop wenqu-searxng ;;
    sandbox) (cd "$PROJECT_ROOT/deploy/sandbox-provisioner" && docker compose down) ;;
    ocr)     (cd "$PROJECT_ROOT/deploy/ocr" && docker compose down) ;;
  esac
}
infra_logs() {
  local name="$1"
  case "$name" in
    mysql)   brew services info mysql@8.0 --log 2>/dev/null | tail -n 50 || echo "无日志" ;;
    redis)   tail -f "$(brew --prefix)/var/log/redis.log" 2>/dev/null || echo "无日志" ;;
    meili)   docker logs -f ai-doc-meilisearch ;;
    searxng) docker logs -f wenqu-searxng ;;
    sandbox) (cd "$PROJECT_ROOT/deploy/sandbox-provisioner" && docker compose logs -f) ;;
    ocr)     (cd "$PROJECT_ROOT/deploy/ocr" && docker compose logs -f) ;;
  esac
}
infra_status_line() {
  local name="$1" port state desc
  port="$(infra_port "$name")"
  state="$(infra_state "$name" "$port")"
  desc="$(infra_desc "$name")"
  printf "    %-9s %-5s %-18s %s\n" "$name" ":$port" "$state" "$desc"
}
infra_cmd_help() {
  local name="$1"
  case "$name" in
    mysql)   echo "brew services start/stop mysql@8.0" ;;
    redis)   echo "brew services start/stop redis" ;;
    meili)   echo "docker start/stop ai-doc-meilisearch" ;;
    searxng) echo "docker start/stop wenqu-searxng  (或 cd deploy/searxng && docker compose up/down)" ;;
    sandbox) echo "cd deploy/sandbox-provisioner && docker compose up -d/down" ;;
    ocr)     echo "cd deploy/ocr && docker compose up -d/down" ;;
  esac
}
infra_list() {
  echo "问渠中间件 (infra)"
  echo "用法: ./wenqu.sh infra [up|down|restart|logs|status] [name|all]"
  echo
  printf "    %-9s %-5s %-18s %s\n" "NAME" "PORT" "STATE" "说明"
  for n in $INFRA_NAMES; do infra_status_line "$n"; done
  echo
  echo "   启停命令示例:"
  for n in $INFRA_NAMES; do
    printf "     %-9s %s\n" "$n" "$(infra_cmd_help "$n")"
  done
}
infra_dispatch() {
  local action="${1:-}" target="${2:-all}"
  case "$action" in
    ""|status) : ;;  # 默认按 status 处理
    up|down|restart|logs) : ;;
    *) echo "未知 infra 动作: $action (用 ./wenqu.sh infra 查看)" >&2; exit 1 ;;
  esac
  local names=""
  if [ "$target" = "all" ]; then names="$INFRA_NAMES";
  else
    for n in $INFRA_NAMES; do [ "$n" = "$target" ] && names="$target"; done
    [ -z "$names" ] && { echo "未知中间件: $target (可选: $INFRA_NAMES)" >&2; exit 1; }
  fi
  for n in $names; do
    case "$action" in
      ""|status) infra_status_line "$n" ;;
      up)        echo "▶ up $n"; infra_up "$n" ;;
      down)      echo "■ down $n"; infra_down "$n" ;;
      restart)   echo "↻ restart $n"; infra_down "$n"; sleep 1; infra_up "$n" ;;
      logs)      infra_logs "$n"; break ;;  # logs 跟踪阻塞，单次即可
    esac
  done
}

# ===================== 入口 =====================
main() {
  local cmd="${1:-}"
  case "$cmd" in
    ""|-h|--help|help) list_all; exit 0 ;;
    infra)
      shift || true
      if [ $# -eq 0 ]; then infra_list; exit 0; fi
      infra_dispatch "$@"
      ;;
    run)
      shift || true
      local name="${1:-}"
      [ -z "$name" ] && { echo "用法: wenqu.sh run <脚本名>" >&2; exit 1; }
      shift || true
      local rel; rel="$(find_rel "$name")"
      [ -z "$rel" ] && { echo "未找到: $name" >&2; exit 1; }
      run_file "$SCRIPT_DIR/$rel" "$@"
      ;;
    *)
      if is_group "$cmd"; then
        local g="$cmd"; shift || true
        if [ $# -eq 0 ]; then list_group "$g"; exit 0; fi
        local name="$1"; shift || true
        local f="$SCRIPT_DIR/$g/$name"
        [ -f "$f" ] || { echo "未找到: $g/$name" >&2; exit 1; }
        run_file "$f" "$@"
      else
        local rel; rel="$(find_rel "$cmd")"
        if [ -n "$rel" ]; then shift || true; run_file "$SCRIPT_DIR/$rel" "$@";
        else echo "未知分组或脚本: $cmd (用 ./wenqu.sh 查看清单)" >&2; exit 1; fi
      fi
      ;;
  esac
}

main "$@"
