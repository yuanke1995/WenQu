{{/* 问渠 WenQu chart 模板辅助 */}}

{{- define "wenqu.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "wenqu.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "wenqu.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* 选择器只放「哪个发布」，不放版本/track：选择器不可变，把易变的东西放进去会让 upgrade 直接失败 */}}
{{- define "wenqu.selectorLabels" -}}
app.kubernetes.io/name: {{ include "wenqu.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "wenqu.labels" -}}
helm.sh/chart: {{ include "wenqu.chart" . }}
{{ include "wenqu.selectorLabels" . }}
app.kubernetes.io/version: {{ default .Chart.AppVersion .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "wenqu.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}

{{- define "wenqu.secretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- printf "%s-secrets" (include "wenqu.fullname" .) -}}
{{- end -}}
{{- end -}}

{{/*
  多副本 + 非共享卷 = 文件只在某一个副本上看得见（症状是同一个产物时而能打开时而 404）。
  这属于「宁可装不上，也不要装成随机坏」的判据，所以在渲染阶段就停，而不是留一条 warning 让人忽略。
  灰度版与稳定版共用同一组卷，判据只看总副本数。
*/}}
{{- define "wenqu.validateTopology" -}}
{{- $want := int .Values.replicaCount -}}
{{- if .Values.canary.enabled -}}
{{- $want = add $want (int .Values.canary.replicaCount) -}}
{{- end -}}
{{- if gt $want 1 -}}
{{- if and .Values.persistence.data.enabled (ne .Values.persistence.data.accessMode "ReadWriteMany") -}}
{{- fail (printf "副本合计 %d > 1 时 persistence.data.accessMode 必须是 ReadWriteMany（当前 %s）：各副本各拿一块 RWO 卷会让产物/上传/记忆文件只在某一个副本上可见。要么用共享存储，要么把副本降到 1。" $want .Values.persistence.data.accessMode) -}}
{{- end -}}
{{- if and .Values.persistence.userData.enabled (ne .Values.persistence.userData.accessMode "ReadWriteMany") -}}
{{- fail (printf "副本合计 %d > 1 时 persistence.userData.accessMode 必须是 ReadWriteMany（当前 %s）：用户自装技能会只在某一个副本上存在。" $want .Values.persistence.userData.accessMode) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "wenqu.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "wenqu.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
