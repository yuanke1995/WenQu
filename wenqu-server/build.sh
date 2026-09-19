#!/usr/bin/env bash
# ============================================================
#  编译 wenqu-server（本机无 maven 环境下的等价方式）
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
#  用法：
#    bash build.sh          # 编译到 target/classes
#    bash build.sh run      # 编译后启动（前台）
# ============================================================
set -e
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/Users/yuki/Library/Java/JavaVirtualMachines/corretto-17.0.20.1/Contents/Home}"
DEPS=/tmp/wenqu-server-deps.txt
MODULE=wenqu-server

# 依赖 jar 清单（首次生成后复用；排除 sources/javadoc 与冲突的旧版 slf4j-api）
if [ ! -f "$DEPS" ]; then
  find ~/.m2 -name "*.jar" \
    | grep -v -- "-sources.jar" | grep -v -- "-javadoc.jar" \
    | grep -v "slf4j-api-1.7.36.jar" > "$DEPS"
  echo "已生成依赖清单: $DEPS ($(wc -l < "$DEPS" | tr -d ' ') 个 jar)"
fi

find "$MODULE/src/main/java" -name "*.java" > /tmp/wenqu-server-srcs.txt
CP="$(tr '\n' ':' < "$DEPS")"
mkdir -p "$MODULE/target/classes"

echo "编译 $(wc -l < /tmp/wenqu-server-srcs.txt | tr -d ' ') 个源文件（-parameters 已启用）…"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -parameters \
  -cp "$CP" -d "$MODULE/target/classes" @/tmp/wenqu-server-srcs.txt
cp -R "$MODULE/src/main/resources/." "$MODULE/target/classes/"
echo "✓ 编译完成 → $MODULE/target/classes"

if [ "$1" = "run" ]; then
  # 端口必须显式指定：环境注入的 SERVER__PORT 会经 relaxed binding 覆盖 server.port
  SERVER_PORT=8095 WENQU_JWT_SECRET="${WENQU_JWT_SECRET:-v2-local-secret-32-characters!!!}" \
    "$JAVA_HOME/bin/java" -cp "$MODULE/target/classes:$CP" com.wisesoft.wenqu.WenquServerApplication
fi
