@echo off
REM ============================================================
REM  Start backend jar (wenqu) - Windows
REM  JAR: <project root>\target\wenqu.jar
REM  Before first run: fill DB_PASSWORD / AI_TRUSTED_TOKEN below
REM  (same values as your IDEA run config / platform gateway)
REM ============================================================
setlocal
set ROOT=%~dp0..\..
set JAR=%ROOT%\target\wenqu.jar

if not exist "%JAR%" (
    echo JAR not found. Build first:  mvn package
    pause
    exit /b 1
)

REM ---- 必填：填你的实际值（与 IDEA run config / 平台网关一致；不要提交真实值）----
REM 留空 = 后端启动即 fail-fast（DB_PASSWORD 缺失），不会用默认密钥兜底
set DB_PASSWORD=
set AI_TRUSTED_TOKEN=
REM -----------------------------------------------------------------------------

REM ---- optional overrides (uncomment to change) ----
REM set REDIS_PORT=6380
REM set AI_VISION_BASE_URL=http://localhost:11434
REM set AI_RERANK_BASE_URL=http://localhost:7997
REM set AI_RERANK_ENABLED=true
REM set AI_QUERY_REWRITE_ENABLED=true

REM ---- port check: stop any running instance first ----
netstat -ano | findstr ":8090" >nul
if not errorlevel 1 (
    echo.
    echo ============================================
    echo   ERROR: Port 8090 is already in use.
    echo   A backend is still running. Stop it first:
    echo     - IDEA: click the red Stop square
    echo     - or:  taskkill /F /PID 12345 -- replace with real PID
    echo   Then run this script again.
    echo ============================================
    echo.
    timeout /t 10 >nul
    exit /b 1
)

REM ---- JDK: prefer JAVA_HOME, fallback to PATH ----
set JAVA_CMD=java
if exist "%JAVA_HOME%\bin\java.exe" set JAVA_CMD=%JAVA_HOME%\bin\java.exe

echo Starting wenqu on :8090 (keep this window open)...
"%JAVA_CMD%" -Xms256m -Xmx1024m -jar "%JAR%"
pause
