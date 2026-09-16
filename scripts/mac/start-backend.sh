#!/usr/bin/env bash
# ============================================================
#  Start backend jar (wenqu) - macOS / Linux
#  JAR: <project root>/target/wenqu.jar
#  Before first run: export DB_PASSWORD
#  or fill the defaults below
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$ROOT/target/wenqu.jar"

# 切到项目根运行：Spring Boot 从 ./config/ 加载外部 application-local.yml（本地密钥不入 jar）
cd "$ROOT"

if [ ! -f "$JAR" ]; then
  echo "JAR not found. Build first: mvn package"
  exit 1
fi

# ---- edit these two lines (same values as IDEA run config) ----
export DB_PASSWORD="${DB_PASSWORD:-CHANGE_ME}"
# --------------------------------------------------------------

# ---- optional overrides (uncomment to change) ----
# export REDIS_PORT=6380
# export AI_VISION_BASE_URL=http://localhost:11434
# export AI_RERANK_BASE_URL=http://localhost:7997
# export AI_RERANK_ENABLED=true
# export AI_QUERY_REWRITE_ENABLED=true

# ---- port check: stop any running instance first ----
if lsof -i :8090 >/dev/null 2>&1; then
  echo "Port 8090 is in use. Stop the current backend first."
  exit 1
fi

# ---- JDK: prefer JAVA_HOME, fallback to PATH ----
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="$(command -v java || echo java)"
fi

echo "Starting wenqu on :8090 (Ctrl+C to stop)..."
exec "$JAVA_CMD" -Xms256m -Xmx1024m -jar "$JAR"
