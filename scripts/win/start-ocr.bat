@echo off
REM ============================================================
REM  MinerU OCR 版面解析服务管理 - Windows
REM  Compose: <project root>\deploy\ocr\docker-compose.yml
REM  端口:    127.0.0.1:30011 -> 容器 30001
REM           （parse.ocrMineruUri 默认已对齐 30011，设置页无需改）
REM
REM  用法:  start-ocr.bat [start^|stop^|status^|health^|logs]
REM
REM  注意:
REM    - 需要 Docker Desktop 在运行；镜像不存在时 start 会自动构建
REM    - 首次解析自动下载 ~2GB 模型到 mineru-models 卷
REM    - 后端使用: 设置页「文档解析默认模板 - PDF 解析引擎」选 mineru
REM ============================================================
setlocal
set ROOT=%~dp0..\..
set COMPOSE_FILE=%ROOT%\deploy\ocr\docker-compose.yml
set HEALTH_URL=http://127.0.0.1:30011/openapi.json

set ACTION=%1
if "%ACTION%"=="" set ACTION=start

if /i "%ACTION%"=="stop"    goto :stop
if /i "%ACTION%"=="status"  goto :status
if /i "%ACTION%"=="health"  goto :health
if /i "%ACTION%"=="logs"    goto :logs
if /i "%ACTION%"=="start"   goto :start
echo 用法: %~nx0 [start^|stop^|status^|health^|logs]
pause
exit /b 1

:start
docker info >nul 2>&1
if errorlevel 1 (
    echo Docker daemon 未运行，请先启动 Docker Desktop。
    pause
    exit /b 1
)
echo Starting MinerU OCR ^(wenqu-mineru^) on 127.0.0.1:30011 ...
docker compose -f "%COMPOSE_FILE%" up -d --build
echo 等待服务就绪后可用健康检查确认:
echo   scripts\win\start-ocr.bat health
echo 提示: 首次解析会下载 ~2GB 模型（docker logs wenqu-mineru 观察进度）。
pause
exit /b 0

:stop
echo Stopping wenqu-mineru（模型卷保留，下次免下载）...
docker compose -f "%COMPOSE_FILE%" down
pause
exit /b 0

:status
docker ps -a --filter name=wenqu-mineru
goto :health

:health
curl -sf -m 5 "%HEALTH_URL%" | findstr "/file_parse" >nul
if errorlevel 1 (
    echo UNREACHABLE  %HEALTH_URL%（容器未起 / 还在启动 / 端口被占）
) else (
    echo HEALTHY  %HEALTH_URL%
)
pause
exit /b 0

:logs
docker logs -f wenqu-mineru
