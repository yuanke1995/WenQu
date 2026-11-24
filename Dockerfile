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

# ── 第一层：系统依赖 + 目录 + 用户（与源码无关，永远命中缓存） ──
# 为什么必须排在 COPY jar 之前：Docker 的层缓存是「本层输入不变 + 上层命中」才复用，
# COPY app.jar 一变，它下面所有层全部作废。原先 apt 装在 COPY 之后，
# 于是「改一行 Java → 重下 250MB LibreOffice」，一次构建十来分钟，改代码根本没法验证。
# 这两块都不读源码，提到前面之后改代码重建只剩 jar 那几秒。
# curl 供 HEALTHCHECK 使用。
# INSTALL_OFFICE=true（默认）额外装 LibreOffice Writer/Calc/Impress 与中文字体：
# 「文档处理」工具的 PDF 转换与文档渲染依赖 soffice；不需要可 --build-arg INSTALL_OFFICE=false 缩小镜像。
ARG INSTALL_OFFICE=true
# 换国内镜像源：archive.ubuntu.com 在国内实测只有 150 KB/s，装全套 LibreOffice（约 250MB）
# 要跑半小时，改代码根本没法验证；清华/阿里源同包实测快 3~5 倍。仅影响构建期，运行期无副作用。
# deb822 格式（Ubuntu 24.04+）改 URI 行；老式 sources.list 一并兜住（找不到就跳过）。
RUN sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; \
            s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' \
        /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true; \
    sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; \
            s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' \
        /etc/apt/sources.list 2>/dev/null || true
RUN apt-get update \
    && if [ "$INSTALL_OFFICE" = "true" ]; then \
         EXTRA="libreoffice-writer libreoffice-calc libreoffice-impress fonts-noto-cjk"; \
       else \
         EXTRA=""; \
       fi \
    && apt-get install -y --no-install-recommends curl tzdata $EXTRA \
    && rm -rf /var/lib/apt/lists/* \
    # 图片存储目录（挂载卷；非 root 用户需要写权限）
    # user-data 也必须在这里建好：技能落盘根（skills-builtin / skills-users）挂的是命名卷，
    # 而命名卷首次创建时按镜像里挂载点的属主初始化——镜像里没这个目录就是 root:root 755，
    # 应用以 appuser 跑，BuiltinSkillSeeder 直接 AccessDeniedException（实测踩过：内置技能整批不可用）。
    && mkdir -p /app/data/images /app/user-data \
    && useradd -r -m appuser

# ── 第二层：应用产物（唯一随代码变化的层） ──
# 挂到上面那层之后：改代码只重跑「拷 jar + 改属主」，文件名与属主在同一个 COPY 里一次定下来，
# 避免再拆一层 chown（多一层就多一次全量拷贝）。
COPY --from=build --chown=appuser:appuser /app/target/wenqu.jar app.jar

USER appuser
EXPOSE 8090
ENV SERVER_PORT=8090 \
    TZ=Asia/Shanghai \
    REDIS_HOST=redis-stack \
    REDIS_PORT=6379 \
    # JVM 内存经 JAVA_OPTS 注入（compose 默认 -Xms256m -Xmx1400m，按容器内存限额调整）
    JAVA_OPTS=""
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8090/ai/actuator/health || exit 1
# sh -c 展开 JAVA_OPTS 环境变量；exec 使 java 成为 PID 1 正确接收 SIGTERM 优雅停机
ENTRYPOINT ["sh", "-c", "exec java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 $JAVA_OPTS -jar app.jar"]
