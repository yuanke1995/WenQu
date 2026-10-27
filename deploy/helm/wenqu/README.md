# 问渠 WenQu · Helm chart

后端与前端同源（镜像里已含前端构建产物），所以一个 Deployment 就是整个应用。
键的取值理由写在 `values.yaml` 的注释里，这里只放命令。

## 安装

```bash
# 最小可跑（外部已有 MySQL / Redis Stack）
helm install wenqu deploy/helm/wenqu \
  --namespace wenqu --create-namespace \
  --set config.dbHost=mysql.internal --set config.redisHost=redis.internal \
  --set secrets.values.dbPassword='<库口令>' \
  --set secrets.values.jwtSecret='<≥32 位随机串，各副本一致>' \
  --set ingress.host=wenqu.example.com

helm get notes wenqu -n wenqu      # 访问地址、灰度与回滚命令、缺什么会怎样
```

密钥不想留在 shell 历史里就用外部管理的 Secret：

```bash
  --set secrets.create=false --set secrets.existingSecret=wenqu-app-keys
```

## 升级 / 灰度 / 回滚

```bash
# 常规滚动升级（先起新再停旧，在跑的问答轮不被断）
helm upgrade wenqu deploy/helm/wenqu --reuse-values --set image.tag=1.4.0

# 灰度：新镜像先接一小部分流量。两版共用同一套库与盘 ⇒ 只放向后兼容的变更
helm upgrade wenqu deploy/helm/wenqu --reuse-values \
  --set canary.enabled=true --set canary.imageTag=1.4.0 --set canary.weight=5
# 内部先验证（命中该请求头一定进灰度版，比权重好确认）
helm upgrade wenqu deploy/helm/wenqu --reuse-values \
  --set canary.enabled=true --set canary.imageTag=1.4.0 --set canary.header.enabled=true
# 逐步放量：--set canary.weight=25 → 50
# 全量切换：把新 tag 变成稳定版，撤掉灰度
helm upgrade wenqu deploy/helm/wenqu --reuse-values --set image.tag=1.4.0 --set canary.enabled=false
# 回滚：灰度期只撤灰度；换过稳定版就 helm rollback
helm upgrade wenqu deploy/helm/wenqu --reuse-values --set canary.enabled=false
helm history wenqu -n wenqu && helm rollback wenqu <REVISION> -n wenqu
```

带不兼容结构变更的版本不要灰度：先 `python3 scripts/dr/tenant-dr.py backup --all --out /backup/wenqu`，
停机升级（`schema.sql` 的补列由应用启动时自动执行，但回退不会自动做）。

## 多副本的三条硬要求

1. **共享存储**：`persistence.data.accessMode` 与 `persistence.userData.accessMode` 必须是
   `ReadWriteMany`（NFS / CephFS / EFS…）。各副本各挂一块 RWO 卷，产物与技能会「只在某一台上看得见」——
   chart 在副本合计 > 1 且不满足时直接拒绝渲染。
2. **`AI_JWT_SECRET` 各副本一致**：不一致就是登录随机 401、图片签名地址换副本失效。
3. **SSE 不被缓冲、不被 60s 读超时掐断**：ingress 注解里已写好；自己再套网关时照抄
   `proxy-buffering=off` 与一小时的读写超时。

多副本上线前把两个启动自愈内部键置 false（`keyword.reconcileOnStartup`、`parse.recoverStuckOnStartup`），
让启动对账收敛为单点，见手册「多副本部署要求」。

## 备份与按租户恢复

```bash
python3 scripts/dr/tenant-dr.py backup  --tenant acme --out /backup/wenqu
python3 scripts/dr/tenant-dr.py restore --from /backup/wenqu/<包> --into-db wenqu_restore
```

命令与注意事项见手册「备份与按租户恢复」。备份需要后端在线：盘上目录由应用报，脚本不重抄路径规则。

## 本机核对过什么

`deploy/helm/wenqu` 在提交前做过静态核对（模板 `{{}}` 成对、缩进为偶数、无制表符、
模板引用的每个 `.Values.*` 路径都在 `values.yaml` 里存在、`include` 的 `define` 都有定义）。
**没有跑 `helm lint` 与 `helm template`**——开发机上没有 helm。请在有 helm 的环境里补一遍：

```bash
helm lint deploy/helm/wenqu
helm template wenqu deploy/helm/wenqu --set canary.enabled=true --set canary.imageTag=canary \
  --set replicaCount=2 > /tmp/rendered.yaml && python3 -c "import yaml,sys;list(yaml.safe_load_all(open('/tmp/rendered.yaml')))"
```
