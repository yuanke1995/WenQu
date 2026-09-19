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
#  ⚠️ 每次编译前清空 target/classes（等价 `mvn clean compile`），不可省略：
#     javac 是增量写盘、不删除已不存在的源文件对应的旧 .class。本工程做过一次
#     包内搬迁（repository/*Mapper → repository/port/*Mapper），旧包下的 .class 残留在
#     target/classes 里，会被 Spring 的 MapperScannerConfigurer 一并扫到，启动即报
#     ConflictingBeanDefinitionException（如同名类同时存在于两个包）。
#     同理，曾经踩过"残留双主类 .class 导致 single main class 报错"。
#
#  用法：
#    bash build.sh          # 全量编译到 target/classes
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
rm -rf "$MODULE/target/classes"
mkdir -p "$MODULE/target/classes"

echo "编译 $(wc -l < /tmp/wenqu-server-srcs.txt | tr -d ' ') 个源文件（-parameters 已启用）…"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -parameters \
  -cp "$CP" -d "$MODULE/target/classes" @/tmp/wenqu-server-srcs.txt
cp -R "$MODULE/src/main/resources/." "$MODULE/target/classes/"
echo "✓ 编译完成 → $MODULE/target/classes"

if [ "$1" = "run" ]; then
  # 端口必须显式指定：环境注入的 SERVER__PORT 会经 relaxed binding 覆盖 server.port。
  # 三个密钥必须显式固定，否则联调时会出现「刚登录又被踢回登录页」：
  #   WENQU_JWT_SECRET  —— 既有契约（/api/ai/auth/*）的 HS256 签名密钥，未配置时启动期随机生成；
  #   JWT_SECRET_KEY    —— 参考实现契约（/api/auth/*）的签名密钥，未配置时开发环境随机生成；
  #   WENQU_INSTANCE_ID —— 参考实现契约 JWT 的 issuer 后缀（iss=wenqu-know:<实例ID>），未配置时随机。
  # 三者都只在「进程内」记忆，重启即变，已发出的令牌全部失效。这里给本地开发固定值。
  SERVER_PORT=8095 \
  WENQU_JWT_SECRET="${WENQU_JWT_SECRET:-wenqu-local-dev-jwt-secret-32-chars!!}" \
  JWT_SECRET_KEY="${JWT_SECRET_KEY:-wenqu-local-dev-signing-key-32-chars!!}" \
  WENQU_INSTANCE_ID="${WENQU_INSTANCE_ID:-local}" \
    "$JAVA_HOME/bin/java" -cp "$MODULE/target/classes:$CP" com.wisesoft.wenqu.WenquServerApplication
fi
