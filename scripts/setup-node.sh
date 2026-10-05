#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 安装 / 升级 Ubuntu 上的 Node.js（NodeSource 官方源），使其满足前端依赖链要求。
#
# 背景：web/ 通过 unplugin-vue-components@32 引入 unplugin@3，后者在模块顶层
# 计算 path.resolve(import.meta.dirname, ...)。import.meta.dirname 是 Node
# 20.11+ 才有的，unplugin 自身 engines 声明为 "^20.19.0 || >=22.12.0"。
# 在 Node 18 上 import.meta.dirname === undefined，于是 vite 加载
# vite.config.js 阶段就抛：
#   TypeError [ERR_INVALID_ARG_TYPE]: The "paths[0]" argument must be of type string.
#
# 用法：
#   sudo bash scripts/setup-node.sh            # 安装 Node 22 LTS（默认）
#   sudo NODE_MAJOR=24 bash scripts/setup-node.sh
#   bash scripts/setup-node.sh --check         # 只体检，不改任何东西
#
# 幂等：版本已达标时不重复安装；重复执行只会刷新同一份源。
# ---------------------------------------------------------------------------
set -euo pipefail

NODE_MAJOR="${NODE_MAJOR:-22}"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

KEYRING="/usr/share/keyrings/nodesource.gpg"
SRCLIST="/etc/apt/sources.list.d/nodesource.list"

log()  { printf '\033[1;34m[node]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# 是否满足 "^20.19.0 || >=22.12.0"
version_ok() {
  local v="${1#v}" major minor patch
  IFS=. read -r major minor patch <<<"$v"
  major="${major%%[!0-9]*}"; minor="${minor%%[!0-9]*}"; patch="${patch%%[!0-9]*}"
  [ -n "$major" ] && [ -n "$minor" ] || return 1
  if   (( major > 22 )); then return 0
  elif (( major == 22 )); then (( minor >= 12 )) && return 0 || return 1
  elif (( major == 20 )); then (( minor >= 19 )) && return 0 || return 1
  else return 1; fi
}

# --- 体检：分别看 PATH 优先的那份 node 和系统包那份 -------------------------
PATH_NODE="$(command -v node 2>/dev/null || true)"
PATH_VER="$( [ -n "$PATH_NODE" ] && node -v 2>/dev/null || echo "" )"
SYS_VER="$( [ -x /usr/bin/node ] && /usr/bin/node -v 2>/dev/null || echo "" )"

OK_PATH=0; OK_SYS=0
[ -n "$PATH_VER" ] && version_ok "$PATH_VER" && OK_PATH=1
[ -n "$SYS_VER" ] && version_ok "$SYS_VER" && OK_SYS=1

log "PATH 优先 node : ${PATH_VER:-未安装}${PATH_NODE:+  ($PATH_NODE)}"
log "系统包 node    : ${SYS_VER:-未安装}  (/usr/bin/node)"

if (( OK_PATH )) && { (( OK_SYS )) || [ -z "$SYS_VER" ]; }; then
  log "Node $PATH_VER 满足要求（^20.19.0 || >=22.12.0），无需处理。"
  exit 0
fi

if (( OK_PATH == 0 && OK_SYS == 0 )); then
  [ "$CHECK_ONLY" = "1" ] && die "体检未通过：Node ${PATH_VER:-未安装} 低于依赖链要求 ^20.19.0 || >=22.12.0"
  log "当前 Node ${PATH_VER:-未安装} 不满足要求，开始安装 ${NODE_MAJOR}.x"
elif (( OK_PATH == 0 )); then
  log "系统包 $SYS_VER 已达标，但 PATH 优先命中更旧的 $PATH_NODE ($PATH_VER)"
  if [ "$CHECK_ONLY" = "1" ]; then
    die "体检未通过：PATH 里的 node 版本过低，请以隔离 PATH 的方式构建或升级该副本"
  fi
elif (( OK_PATH )); then
  # PATH 副本达标、系统包只是旧：不影响构建，但顺手把系统包刷到同代，避免两套版本互相打架
  log "构建用的 $PATH_VER 已达标；系统包 $SYS_VER 偏旧，顺带刷新到 ${NODE_MAJOR}.x"
  [ "$CHECK_ONLY" = "1" ] && { log "体检结论：可构建（系统包非必需升级）。"; exit 0; }
fi

command -v apt-get >/dev/null 2>&1 || die "本脚本面向 Ubuntu/Debian（未找到 apt-get）。其他系统请自行安装 Node ${NODE_MAJOR} LTS。"
[ "$(id -u)" = "0" ] || die "请以 root 执行：sudo bash scripts/setup-node.sh"

ARCH="$(dpkg --print-architecture)"
case "$ARCH" in
  amd64|arm64|armhf) ;;
  *) die "不支持的架构：$ARCH（NodeSource 提供 amd64 / arm64 / armhf）" ;;
esac

log "安装前置工具..."
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg >/dev/null

log "导入 NodeSource 签名密钥 -> $KEYRING"
tmp_key="$(mktemp)"
trap 'rm -f "$tmp_key"' EXIT
curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key -o "$tmp_key"
mkdir -p /usr/share/keyrings
gpg --dearmor --batch --yes -o "$KEYRING" "$tmp_key"
chmod 0644 "$KEYRING"

log "写入软件源 node_${NODE_MAJOR}.x（arch=$ARCH）"
cat >"$SRCLIST" <<EOF
deb [arch=$ARCH signed-by=$KEYRING] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main
EOF

log "刷新索引（只拉 NodeSource，避免其他源故障干扰）"
apt-get update -qq \
  -o Dir::Etc::sourcelist="$SRCLIST" \
  -o Dir::Etc::sourceparts="-" \
  -o APT::Get::List-Cleanup="0"

log "安装 nodejs（Node ${NODE_MAJOR}.x）"
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs

INSTALLED="$(/usr/bin/node -v 2>/dev/null || echo unknown)"
version_ok "$INSTALLED" || die "/usr/bin/node 版本为 $INSTALLED，仍不满足要求，请检查源是否生效：$SRCLIST"
log "已安装 /usr/bin/node $INSTALLED"

# --- PATH 里被别的 node 盖住时（nvm 最常见），顺带把那份也升上来 -------------
if [ -n "$PATH_NODE" ] && [ "$PATH_NODE" != "/usr/bin/node" ]; then
  if version_ok "$(node -v 2>/dev/null || echo v0.0.0)"; then
    log "PATH 优先的副本 $(node -v) 也已达标，保留不动。"
  elif [ -s "$HOME/.nvm/nvm.sh" ]; then
    log "检测到 nvm，安装并设为默认 Node ${NODE_MAJOR}："
    # shellcheck disable=SC1091
    if . "$HOME/.nvm/nvm.sh" && nvm install "$NODE_MAJOR" && nvm alias default "$NODE_MAJOR"; then
      # shellcheck disable=SC1091
      . "$HOME/.nvm/nvm.sh" >/dev/null 2>&1 || true
      nvm use default >/dev/null 2>&1 || true
      log "nvm 默认版本已切到 $(node -v)（新开终端生效）"
    else
      warn "自动切换失败，手动执行：source ~/.nvm/nvm.sh && nvm install ${NODE_MAJOR} && nvm alias default ${NODE_MAJOR}"
    fi
  else
    warn "PATH 优先的是非系统、非 nvm 的 node（$PATH_NODE = $PATH_VER），请升级它或调整 PATH 让 /usr/bin/node 优先。"
  fi
fi

cat <<EOM

完成。下一步（新开终端，确认 node -v = v${NODE_MAJOR}.x）：

  cd <仓库>/web
  node -v            # 必须 >= v22.12（或 20.19 ~ 20.x）
  npm run build      # 已有 node_modules 无需重装：esbuild / rollup 原生包走稳定 ABI，不绑 Node 大版本

EOM
