# MinerU 2.x 版面解析服务（问渠 parse.ocrEngine=mineru 的后端）
#
# 为什么自构建而不用官方镜像：官方 Docker 镜像只发 NVIDIA 场景（非 NVIDIA
# 官方标注 pending update），且多为 linux/amd64；本机是 Apple Silicon（arm64），
# amd64 + Rosetta/QEMU 转译跑 AVX 系深度学习栈会 Illegal instruction。
# 这里用原生 arm64 基础镜像 + mineru[pipeline,api]——2.x 的 pipeline backend
# 依赖 torch/onnxruntime/ultralytics（均有 aarch64 wheel，无 paddle），
# 提供与问渠老副本 MinerUOcrEngine / 新栈 MinerUParser 一致的 /file_parse 契约。
#
# 不装 vlm/gradio extras：CPU-only 场景 pipeline backend 最稳，省 1GB+ 体积。
# 4.x 重构了 API（server start + tiers），/file_parse 是 2.x 契约，故锁 2.7.6（2.x 末版）。
FROM python:3.11-slim

# libgl1/libglib2.0-0：opencv-python 运行时依赖；curl：容器内健康检查
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

# MINERU_MODEL_SOURCE=modelscope：模型从国内源拉（huggingface 本机直连不稳）
ENV MINERU_MODEL_SOURCE=modelscope \
    MINERU_HOME=/models
RUN pip install --no-cache-dir "mineru[pipeline,api]==2.7.6"

EXPOSE 30001
# 模型在首次解析请求时按 pipeline backend 自动下载到 /models（volume 持久化）
CMD ["mineru-api", "--host", "0.0.0.0", "--port", "30001"]
