#!/usr/bin/env bash
# 本地 CI 闸门：按本次改动面挑跑 CI 里同一组检查，push 前先红，别让远端替我们发现。
#
# 用法：
#   ./scripts/ci-local.sh          # 对比 origin/master 的改动 + 工作区/暂存改动，挑受影响的面
#   ./scripts/ci-local.sh --all    # 四个 job 全量跑（等价 CI）
#   ./scripts/ci-local.sh --list   # 只打印这次会跑哪些面，不执行
#
# 已挂 .githooks/pre-push 时 git push 会自动执行；临时跳过用 git push --no-verify。
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

all=0
list_only=0
for arg in "$@"; do
  case "$arg" in
    --all) all=1 ;;
    --list) list_only=1 ;;
    *) echo "未知参数：$arg（支持 --all / --list）" >&2; exit 2 ;;
  esac
done

base="${CI_BASE:-origin/master}"
if git rev-parse --verify -q "$base" >/dev/null; then
  base_ref="$(git merge-base "$base" HEAD)"
else
  base_ref="HEAD"
fi

changed="$(
  { git diff --name-only "$base_ref"...HEAD || true
    git diff --name-only || true
    git diff --cached --name-only || true
  } | sort -u
)"

if [ -z "$changed" ] && [ "$all" -eq 0 ]; then
  echo "相对 $base 没有改动，跳过本地 CI。"
  exit 0
fi

backend=0 web=0 sdk_js=0 sdk_py=0 contract=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    pom.xml|src/main/java/*|src/main/resources/*|src/test/*) backend=1; contract=1 ;;
    web/*) web=1 ;;
    sdk/js/*) sdk_js=1 ;;
    sdk/python/*) sdk_py=1 ;;
    contract/*|scripts/check-openapi-contract.mjs) contract=1 ;;
    .github/workflows/*|scripts/ci-local.sh|.githooks/*) all=1 ;;
  esac
done <<< "$changed"

if [ "$all" -eq 1 ]; then
  backend=1; web=1; sdk_js=1; sdk_py=1; contract=1
fi

suites=()
[ "$backend" -eq 1 ] && suites+=("backend: mvn verify")
[ "$contract" -eq 1 ] && suites+=("contract: node scripts/check-openapi-contract.mjs --check")
[ "$web" -eq 1 ] && suites+=("web: npm test + npm run build")
[ "$sdk_js" -eq 1 ] && suites+=("sdk-js: npm ci + build + test")
[ "$sdk_py" -eq 1 ] && suites+=("sdk-py: unittest（PYTHONPATH=src）")

if [ "${#suites[@]}" -eq 0 ]; then
  echo "改动只涉及文档/其他文件，无需跑 CI 检查："
  echo "$changed" | sed 's/^/  /'
  exit 0
fi

echo "本次要跑："
printf '  - %s\n' "${suites[@]}"
[ "$list_only" -eq 1 ] && exit 0

MVN="${MAVEN_BIN:-}"
if [ -z "$MVN" ]; then
  if command -v mvn >/dev/null 2>&1; then
    MVN=mvn
  elif [ -x "/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn" ]; then
    MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn"
  else
    echo "找不到 mvn：装 Maven 或设置 MAVEN_BIN 指向 mvn 可执行文件。" >&2
    exit 1
  fi
fi

failed=()
run() {
  local name="$1"; shift
  echo ""
  echo "===== $name"
  if "$@"; then
    echo "----- $name 通过"
  else
    echo "----- $name 失败" >&2
    failed+=("$name")
  fi
}

[ "$backend" -eq 1 ] && run "backend" "$MVN" -B verify
[ "$contract" -eq 1 ] && run "contract" node scripts/check-openapi-contract.mjs --check
[ "$web" -eq 1 ] && run "web" bash -c 'cd web && npm test && npm run build'
if [ "$sdk_js" -eq 1 ] || [ "$sdk_py" -eq 1 ]; then
  run "sdk-version" node scripts/check-sdk-version.mjs
fi
[ "$sdk_js" -eq 1 ] && run "sdk-js" bash -c 'cd sdk/js && npm ci && npm run build && npm test'
[ "$sdk_py" -eq 1 ] && run "sdk-py" bash -c 'cd sdk/python; if ! python3 -c "import requests" >/dev/null 2>&1; then echo "跳过：当前 python3 缺 requests，先执行 python3 -m pip install -e sdk/python" >&2; exit 0; fi; PYTHONPATH=src python3 -m unittest discover -s tests -v'

echo ""
if [ "${#failed[@]}" -gt 0 ]; then
  echo "本地 CI 失败：${failed[*]}（修复后再 push；确认无关可用 git push --no-verify 跳过）" >&2
  exit 1
fi
echo "本地 CI 全部通过。"
