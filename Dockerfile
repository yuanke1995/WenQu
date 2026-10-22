# ============ 构建阶段 ============
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 先拉依赖缓存层
COPY pom.xml .
# 依赖下载走阿里云 Maven 镜像（国内构建提速）；海外构建可删本行与 deploy/maven-settings.xml
COPY deploy/maven-settings.xml /root/.m2/settings.xml
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ============ 运行阶段 ============
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/wenqu.jar app.jar
# curl 供 HEALTHCHECK 使用
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    # 图片存储目录（挂载卷；非 root 用户需要写权限）
    # user-data 也必须在这里建好：技能落盘根（skills-builtin / skills-users）挂的是命名卷，
    # 而命名卷首次创建时按镜像里挂载点的属主初始化——镜像里没这个目录就是 root:root 755，
    # 应用以 appuser 跑，BuiltinSkillSeeder 直接 AccessDeniedException（实测踩过：内置技能整批不可用）。
    && mkdir -p /app/data/images /app/user-data \
    && useradd -r -m appuser \
    && chown -R appuser:appuser /app
USER appuser
EXPOSE 8090
ENV SERVER_PORT=8090 \
    REDIS_HOST=redis-stack \
    REDIS_PORT=6379 \
    # JVM 内存经 JAVA_OPTS 注入（compose 默认 -Xms256m -Xmx1400m，按容器内存限额调整）
    JAVA_OPTS=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8090/ai/actuator/health || exit 1
# sh -c 展开 JAVA_OPTS 环境变量；exec 使 java 成为 PID 1 正确接收 SIGTERM 优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
