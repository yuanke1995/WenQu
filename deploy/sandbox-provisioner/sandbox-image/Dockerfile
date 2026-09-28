# 派生沙盒镜像：在官方 all-in-one-sandbox 基础上加 JDK。
#
# 为什么不进沙盒再装：沙盒内 agent 命令以 gem（uid 1000）普通用户执行，
# 不在 sudoers 里，apt 安装必然权限不足 —— 运行时环境必须在镜像层解决。
#
# 构建方式（在 sandbox-image/ 目录下）：
#   docker build -t all-in-one-sandbox:1.11.0-jdk17 .
# 启用方式：deploy/sandbox-provisioner/.env 里设
#   SANDBOX_IMAGE=all-in-one-sandbox:1.11.0-jdk17
# 然后重启 provisioner（docker compose up -d）。已存在的旧沙盒容器
# 仍用旧镜像，等会话结束/空闲回收后，新沙盒自动用新镜像。
FROM enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:1.11.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
