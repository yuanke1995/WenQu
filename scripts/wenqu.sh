#!/usr/bin/env sh
# 薄封装：全部逻辑在跨平台的 wenqu.mjs 里（Windows 用 wenqu.bat / wenqu.ps1）。
exec node "$(dirname "$0")/wenqu.mjs" "$@"
