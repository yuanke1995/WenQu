#!/usr/bin/env bash
# ============================================================
#  编译 / 运行 wenqu-server（本机无 maven 环境下的等价方式）
#
#  为什么不用 maven：本机未安装 maven。此脚本用 javac + ~/.m2 下的依赖 jar
#  完成同样的编译，等价于 `mvn compile`。
#
#  ⚠️ -parameters 不可省略：
#     Spring MVC 依赖方法参数名解析 @PathVariable / @RequestParam（注解未显式写名字时）。
#     maven-compiler-plugin 由 spring-boot-starter-parent 配置为自动加该参数；
#     javac 手编若不加，Controller 运行时抛
#     "Name for argument of type [java.lang.String] not specified"。
#
#  ⚠️ 每次编译前清空 target/classes（等价 `mvn clean compile`），不可省略：
#     javac 是增量写盘、不删除已不存在的源文件对应的旧 .class。本工程做过一次
#     包内搬迁（repository/*Mapper → repository/port/*Mapper），旧包下的 .class 残留在
#     target/classes 里，会被 Spring 的 MapperScannerConfigurer 一并扫到，启动即报
#     ConflictingBeanDefinitionException（如同名类同时存在于两个包）。
#     同理，曾经踩过"残留双主类 .class 导致 single main class 报错"。
#
#  ⚠️ 本脚本的 classpath 是「~/.m2 全量」，不是「pom 解析」：
#     任何躺在 ~/.m2 里的 jar 都能满足编译，哪怕它没写进 pom.xml。因此会出现
#     「命令行编译通过、IDEA 报找不到符号」。改动源码引入新依赖后，先跑
#     `bash build.sh deps-check` 对一次账，再补 pom.xml。
#
#  ⚠️ 启动时的工作目录必须是模块根（wenqu-server/）：
#     RuntimePaths 里的 skill-sources / skill-projections / user-data / data 都是
#     **相对路径**，随 cwd 落盘。IDE 默认工作目录是模块根，本脚本必须与其一致，
#     否则运行期目录会分裂到仓库根（两处各写一份，互不可见）。
#
#  用法：
#    bash build.sh                 # 只编译（全量，到 target/classes）
#    bash build.sh run             # 编译后【前台】启动（占住当前终端；Ctrl+C 停止）
#    bash build.sh start           # 编译后【后台】启动（日志 run/server.log）
#    bash build.sh stop            # 停止后台实例（按端口兜底，能清掉看不见的野进程）
#    bash build.sh restart         # stop + start
#    bash build.sh status          # 端口 / PID / 健康检查
#    bash build.sh logs            # tail -f run/server.log
#    bash build.sh deps-check      # 对账「pom 依赖闭包」与「源码 import」
#    bash build.sh compile         # 同不传参数的行为
#
#    WENQU_SKIP_COMPILE=1 bash build.sh restart   # 跳过编译（确认未改 Java 源码时用）
#
#  为什么必须有 start/stop（而不是只用 run）：
#    `run` 是前台进程：Ctrl+C、关掉终端窗口、或在另一个终端再跑一次，都会制造
#    「Port 8095 was already in use → APPLICATION FAILED TO START」的假故障。
#    更麻烦的是后台实例一旦脱离终端（nohup / IDE / 脚本拉起），`ps` 未必看得见，
#    于是"明明没跑却又起不来"。stop 直接按端口找监听者，不依赖 pid 文件。
# ============================================================
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/wenqu-server"
JAVA_HOME="${JAVA_HOME:-/Users/yuki/Library/Java/JavaVirtualMachines/corretto-17.0.20.1/Contents/Home}"
DEPS=/tmp/wenqu-server-deps.txt
PORT="${SERVER_PORT:-8095}"
RUN_DIR="$MODULE/run"
PID_FILE="$RUN_DIR/server.pid"
LOG_FILE="$RUN_DIR/server.log"

# ── 依赖 jar 清单（首次生成后复用；排除 sources/javadoc 与冲突的旧版 slf4j-api） ──
if [ ! -f "$DEPS" ]; then
  find ~/.m2 -name "*.jar" \
    | grep -v -- "-sources.jar" | grep -v -- "-javadoc.jar" \
    | grep -v "slf4j-api-1.7.36.jar" > "$DEPS"
  echo "已生成依赖清单: $DEPS ($(wc -l < "$DEPS" | tr -d ' ') 个 jar)"
fi
CP="$(tr '\n' ':' < "$DEPS")"

# 三个密钥必须显式固定，否则每次重启密钥重生成、旧登录令牌全失效
# （现象：联调时"刚登录又被踢回登录页"）：
#   WENQU_JWT_SECRET  —— 既有契约（/api/ai/auth/*）的 HS256 签名密钥；
#   JWT_SECRET_KEY    —— 照搬契约（/api/auth/*）的签名密钥；
#   WENQU_INSTANCE_ID —— 照搬契约 JWT 的 issuer 后缀（iss=wenqu-know:<实例ID>）。
# 另外 SERVER__PORT 必须剔除：会话注入的 SERVER__PORT 会经 Spring relaxed binding
# 抢走 server.port，只留下面显式的 SERVER_PORT。
JWT_ENVS=(
  "SERVER_PORT=$PORT"
  "WENQU_JWT_SECRET=${WENQU_JWT_SECRET:-wenqu-local-dev-jwt-secret-32-chars!!}"
  "JWT_SECRET_KEY=${JWT_SECRET_KEY:-wenqu-local-dev-signing-key-32-chars!!}"
  "WENQU_INSTANCE_ID=${WENQU_INSTANCE_ID:-local}"
)

listener_pid() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1 || true
}

compile_sources() {
  if [ "${WENQU_SKIP_COMPILE:-0}" = "1" ]; then
    echo "跳过编译（WENQU_SKIP_COMPILE=1，请确认未改动 Java 源码）"
    return 0
  fi
  find "$MODULE/src/main/java" -name "*.java" > /tmp/wenqu-server-srcs.txt
  rm -rf "$MODULE/target/classes"
  mkdir -p "$MODULE/target/classes"
  echo "编译 $(wc -l < /tmp/wenqu-server-srcs.txt | tr -d ' ') 个源文件（-parameters 已启用）…"
  "$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -parameters \
    -cp "$CP" -d "$MODULE/target/classes" @/tmp/wenqu-server-srcs.txt
  cp -R "$MODULE/src/main/resources/." "$MODULE/target/classes/"
  echo "✓ 编译完成 → $MODULE/target/classes"
}

start_background() {
  local occupied
  occupied="$(listener_pid)"
  if [ -n "$occupied" ]; then
    echo "✗ 端口 $PORT 已被 PID $occupied 占用，无法启动。" >&2
    echo "  先执行：bash build.sh stop   （或整体：bash build.sh restart）" >&2
    exit 1
  fi
  mkdir -p "$RUN_DIR"
  : > "$LOG_FILE"
  echo "后台启动中… 日志：wenqu-server/run/server.log"
  (
    cd "$MODULE"
    env -u SERVER__PORT "${JWT_ENVS[@]}" \
      nohup "$JAVA_HOME/bin/java" -cp "target/classes:$CP" \
      com.wisesoft.wenqu.WenquServerApplication >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
  )
  local i
  for i in $(seq 1 60); do
    sleep 1
    if grep -q "Started WenquServerApplication" "$LOG_FILE" 2>/dev/null; then
      echo "✓ 启动成功（PID $(cat "$PID_FILE")，$(grep -o 'Started WenquServerApplication in [0-9.]* seconds' "$LOG_FILE" | tail -1)）"
      echo "  地址：http://127.0.0.1:$PORT/v2    健康检查：/v2/api/system/health"
      return 0
    fi
    if grep -qE "APPLICATION FAILED TO START|Application run failed" "$LOG_FILE" 2>/dev/null; then
      echo "✗ 启动失败，日志尾部：" >&2
      tail -20 "$LOG_FILE" >&2
      return 1
    fi
  done
  echo "✗ 60 秒内未就绪，请查看 wenqu-server/run/server.log" >&2
  return 1
}

stop_background() {
  local pid
  pid="$(listener_pid)"
  if [ -z "$pid" ]; then
    echo "未在运行（端口 $PORT 无监听）"
    rm -f "$PID_FILE"
    return 0
  fi
  echo "停止 PID $pid …"
  kill "$pid" 2>/dev/null || true
  local i
  for i in $(seq 1 20); do
    sleep 0.5
    [ -z "$(listener_pid)" ] && break
  done
  if [ -n "$(listener_pid)" ]; then
    echo "优雅退出超时，强制结束"
    kill -9 "$pid" 2>/dev/null || true
    sleep 1
  fi
  rm -f "$PID_FILE"
  echo "✓ 已停止"
}

case "${1:-compile}" in
  compile)
    compile_sources
    ;;
  run)
    compile_sources
    echo "前台启动（Ctrl+C 停止）…"
    cd "$MODULE"
    env -u SERVER__PORT "${JWT_ENVS[@]}" \
      "$JAVA_HOME/bin/java" -cp "target/classes:$CP" \
      com.wisesoft.wenqu.WenquServerApplication
    ;;
  start)
    compile_sources
    start_background
    ;;
  stop)
    stop_background
    ;;
  restart)
    WENQU_SKIP_COMPILE="${WENQU_SKIP_COMPILE:-0}"
    stop_background
    compile_sources
    start_background
    ;;
  status)
    pid="$(listener_pid)"
    if [ -z "$pid" ]; then
      echo "状态：未运行（端口 $PORT 无监听）"
      exit 1
    fi
    echo "状态：运行中"
    echo "  PID ：$pid"
    echo "  端口：$PORT"
    echo -n "  健康："
    curl -s -o /dev/null -w "HTTP %{http_code}\n" \
      "http://127.0.0.1:$PORT/v2/api/system/health" 2>/dev/null \
      || echo "探测失败（curl 不可用？服务仍可能在运行）"
    ;;
  logs)
    tail -f "$LOG_FILE"
    ;;
  deps-check)
    "${PYTHON:-python3}" "$MODULE/tools/deps-check.py"
    ;;
  *)
    echo "未知子命令：$1" >&2
    echo "可用：compile | run | start | stop | restart | status | logs | deps-check" >&2
    exit 2
    ;;
esac
