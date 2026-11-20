#!/usr/bin/env sh
# 薄封装：开发栈统一由跨平台的 `wenqu.mjs infra` 提供（不再依赖 brew / /opt/homebrew）。
# 兼容原来的 [start|stop|restart|status|logs] [单元...] 参数形态。
DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "${1:-}" in
  start|up)    ACTION=up; shift ;;
  stop|down)   ACTION=down; shift ;;
  restart)     ACTION=restart; shift ;;
  status)      ACTION=status; shift ;;
  logs)        ACTION=logs; shift ;;
  "")          ACTION=up ;;
  *)           ACTION="$1"; shift ;;
esac
if [ "$#" -eq 0 ]; then set -- all; fi
exec node "$DIR/../wenqu.mjs" infra "$ACTION" "$@"
