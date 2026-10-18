#!/usr/bin/env bash
# 一次性迁移：Redis 向量索引与 Meilisearch 关键词索引改名 ai-doc-* → wenqu-*
#
# 背景：代码默认值与 compose 已全部改为 wenqu-*（索引名、容器名、Redis 键前缀），
#       存量实例里还留着旧名。旧名不删会有两份索引同时吃同一批数据（内存翻倍），
#       留着也容易让人误判「改配置没生效」。
#
# 顺序（重要）：先发布改名后的代码 → 每个知识库被访问一次（应用会按新名建索引，
#       同一批 JSON 向量键自动重新入索引，不需要重新嵌入）→ 再跑本脚本回收旧索引。
#       提前跑也不会坏事：新索引不存在时本脚本只报告、不动手。
#
# 用法：
#   bash scripts/redis/rename-ai-doc-indexes-2026-10-17.sh            # 只检查与报告
#   bash scripts/redis/rename-ai-doc-indexes-2026-10-17.sh --apply     # 回收旧 KB 索引 + Meili 改名
#   bash scripts/redis/rename-ai-doc-indexes-2026-10-17.sh --apply --purge-global-index
#       # 额外连数据删掉已退役的全局向量索引 ai-doc-index（默认不碰）
#
# 连接参数（与后端同源，默认连本机）：
#   REDIS_HOST=127.0.0.1 REDIS_PORT=6379 REDIS_PASSWORD=
#   MEILI_URL=http://127.0.0.1:7700 MEILI_KEY=（等同 AI_MEILI_KEY，未设 master key 时留空）
set -uo pipefail

APPLY=0
PURGE_GLOBAL=0
for a in "$@"; do
  case "$a" in
    --apply) APPLY=1 ;;
    --purge-global-index) PURGE_GLOBAL=1 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "未知参数: $a（--apply / --purge-global-index）" >&2; exit 1 ;;
  esac
done

REDIS_HOST=${REDIS_HOST:-127.0.0.1}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_PASSWORD=${REDIS_PASSWORD:-}
REDIS_CLI=${REDIS_CLI:-$(command -v redis-cli || echo /opt/homebrew/bin/redis-cli)}
MEILI_URL=${MEILI_URL:-http://127.0.0.1:7700}
MEILI_KEY=${MEILI_KEY:-}
OLD_INDEX=${AI_MEILI_OLD_INDEX:-ai-doc-chunks}
NEW_INDEX=${AI_MEILI_INDEX:-wenqu-chunks}

[ -x "$REDIS_CLI" ] || { echo "找不到 redis-cli（可用 REDIS_CLI=/path/to/redis-cli 指定）" >&2; exit 1; }
[ "$APPLY" = 1 ] && echo "== 模式：--apply（会真的删旧索引/改 Meili 索引名）==" || echo "== 模式：只检查（加 --apply 才动手）=="

rcli() {
  if [ -n "$REDIS_PASSWORD" ]; then
    "$REDIS_CLI" -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning --raw "$@"
  else
    "$REDIS_CLI" -h "$REDIS_HOST" -p "$REDIS_PORT" --raw "$@"
  fi
}

# 索引内文档数（取不到返回 -1）
docs_of() { rcli FT.SEARCH "$1" '*' LIMIT 0 0 2>/dev/null | head -1 | tr -d ' \r'; }

mcli() { # mcli METHOD PATH [JSON_BODY]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$MEILI_KEY" ]; then
    if [ -n "$body" ]; then
      curl -s -m 10 -X "$method" -H "Authorization: Bearer $MEILI_KEY" -H 'Content-Type: application/json' \
        -d "$body" "$MEILI_URL$path"
    else
      curl -s -m 10 -X "$method" -H "Authorization: Bearer $MEILI_KEY" "$MEILI_URL$path"
    fi
  else
    curl -s -m 10 -X "$method" ${body:+-H 'Content-Type: application/json' -d "$body"} "$MEILI_URL$path"
  fi
}

echo
echo "===== 1. Redis 知识库向量索引 ====="
LIST_OUT=$(rcli FT._LIST 2>&1) || { echo "FT._LIST 失败：$LIST_OUT" >&2; echo "（检查 REDIS_HOST/PORT/PASSWORD，或 Redis 是否带 Search 模块）" >&2; exit 1; }
ALL_INDEXES=$(printf '%s\n' "$LIST_OUT" | tr -d '\r' | sed '/^$/d')
printf '%s\n' "$ALL_INDEXES" | grep -q '^wenqu-kb-\|^ai-doc-kb-' || echo "（没有任何 ai-doc-kb-* / wenqu-kb-* 索引——本机可能还没建过知识库向量）"

KB_TOTAL=0 KB_READY=0 KB_DROPPED=0 KB_WAIT=0
while IFS= read -r old; do
  [ -n "$old" ] || continue
  case "$old" in ai-doc-kb-*) ;; *) continue ;; esac
  KB_TOTAL=$((KB_TOTAL + 1))
  kb=${old#ai-doc-kb-}
  new="wenqu-kb-$kb"
  old_docs=$(docs_of "$old"); old_docs=${old_docs:-0}
  if ! printf '%s\n' "$ALL_INDEXES" | grep -qx "$new"; then
    if [ "${old_docs:-0}" = "0" ]; then
      echo "  $old：0 文档且新索引不存在 → 空索引，可直接回收"
      KB_READY=$((KB_READY + 1))
      [ "$APPLY" = 1 ] && rcli FT.DROPINDEX "$old" >/dev/null && echo "    已 FT.DROPINDEX $old"
    else
      echo "  $old：$old_docs 文档，但 $new 还没建 → 跳过（先用改名后的应用访问该知识库，或直接调一次检索）"
      KB_WAIT=$((KB_WAIT + 1))
    fi
    continue
  fi
  new_docs=$(docs_of "$new"); new_docs=${new_docs:-0}
  if [ "${new_docs:-0}" -lt "${old_docs:-0}" ]; then
    echo "  $old：$old_docs 文档 / $new：$new_docs 文档 → 新索引还在入数据，稍后重跑"
    KB_WAIT=$((KB_WAIT + 1))
    continue
  fi
  echo "  $old：$old_docs 文档 / $new：$new_docs 文档 → 齐平，可回收旧索引（不带 DD，向量数据保留）"
  KB_READY=$((KB_READY + 1))
  if [ "$APPLY" = 1 ]; then
    rcli FT.DROPINDEX "$old" >/dev/null && { echo "    已 FT.DROPINDEX $old"; KB_DROPPED=$((KB_DROPPED + 1)); } \
      || echo "    回收失败：$old"
  fi
done <<< "$(printf '%s\n' "$ALL_INDEXES")"
echo "  小计：旧 KB 索引 $KB_TOTAL 个，可回收 $KB_READY 个，已回收 $KB_DROPPED 个，待新索引就绪 $KB_WAIT 个"

echo
echo "===== 2. 已退役的全局向量索引 ai-doc-index ====="
if printf '%s\n' "$ALL_INDEXES" | grep -qx 'ai-doc-index'; then
  g=$(docs_of ai-doc-index)
  keys=$(rcli --scan --pattern 'ai:chunk:*' 2>/dev/null | grep -c . || true)
  echo "  仍存在：索引内 $g 条，键 ai:chunk:* 共 $keys 条（spring.ai.vectorstore.type=none 后已无人读取，属孤儿）"
  if [ "$PURGE_GLOBAL" = 1 ] && [ "$APPLY" = 1 ]; then
    rcli FT.DROPINDEX ai-doc-index DD >/dev/null && echo "    已连数据删除（FT.DROPINDEX ai-doc-index DD）"
  else
    echo "  要连数据一起清：加 --purge-global-index（默认不碰）"
  fi
else
  echo "  不存在（已清理）"
fi

echo
echo "===== 3. 残留的 ai-doc:* 运行时键 ====="
res=$(rcli --scan --pattern 'ai-doc:*' 2>/dev/null | grep -c . || true)
if [ "${res:-0}" -gt 0 ]; then
  echo "  仍有 $res 个（会话降级缓存/限流窗口/重嵌锁）。三类都带 TTL，改名后的应用只读写 wenqu:*，旧键会自行过期——不迁、不删。"
  [ "$APPLY" = 1 ] && echo "  想立刻清空：redis-cli -h $REDIS_HOST -p $REDIS_PORT --scan --pattern 'ai-doc:*' | xargs redis-cli -h $REDIS_HOST -p $REDIS_PORT DEL"
else
  echo "  无"
fi

echo
echo "===== 4. Meilisearch 关键词索引 ====="
if ! curl -s -m 5 "$MEILI_URL/health" | grep -q available; then
  echo "  $MEILI_URL 不可达（未起 meili 或关键词引擎是 mysql）→ 跳过"
else
  idx_json=$(mcli GET '/indexes')
  if printf '%s' "$idx_json" | grep -q '"code"'; then
    echo "  索引清单读取失败：$(printf '%s' "$idx_json" | head -c 200)"
  else
  has_old=$(printf '%s' "$idx_json" | /usr/bin/python3 -c "
import sys,json
try: rs=json.load(sys.stdin).get('results',[])
except Exception: rs=[]
uids=[r.get('uid') for r in rs]
print(('old' if '$OLD_INDEX' in uids else '')+('+' if '$OLD_INDEX' in uids and '$NEW_INDEX' in uids else '')+('new' if '$NEW_INDEX' in uids else ''))
")
  case "$has_old" in
    *old*new*) echo "  $OLD_INDEX 与 $NEW_INDEX 同时存在 → 本脚本不猜哪个更新，请自行核对后删旧的那个" ;;
    *old*) [ "$has_old" = "old" ] && echo "  只有 $OLD_INDEX" ;;
    *new*) echo "  已是 $NEW_INDEX（无需处理）" ;;
    *) echo "  两个索引都不存在（还没灌过关键词索引）" ;;
  esac
  if [ "$has_old" = "old" ] && [ "$APPLY" = 1 ]; then
    echo "  改名（保留全部文档，不重建）：PATCH /indexes/$OLD_INDEX → uid=$NEW_INDEX"
    task=$(mcli PATCH "/indexes/$OLD_INDEX" "{\"uid\":\"$NEW_INDEX\"}")
    printf '  任务：%s\n' "$task"
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      sleep 1
      st=$(mcli GET '/tasks?indexUid='"$NEW_INDEX"'&limit=1')
      printf '%s' "$st" | grep -q '"succeeded"' && { echo "  改名完成"; break; }
    done
    mcli GET "/indexes/$NEW_INDEX/stats" | head -c 200; echo
  fi
  fi
fi

echo
echo "===== 收尾核对 ====="
echo "  - 应用侧：设置页「检索设置」保存一次关键词配置（或调检索调试），确认日志里索引已是 $NEW_INDEX"
echo "  - 向量侧：redis-cli FT._LIST 只剩 wenqu-kb-*；知识库检索有召回、引用列表非空"
echo "  - compose：容器改名会在下次 docker compose up -d 时重建（数据在命名卷里，不动）"
