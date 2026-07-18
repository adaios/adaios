@echo off
chcp 65001 >nul
title 安装情报监控定时任务
echo ========================================
echo  情报监控 — 定时任务安装
echo ========================================
echo.

REM 获取当前目录（项目根目录）
set PROJECT_DIR=%~dp0

REM 检查 monitor.py 是否存在
if not exist "%PROJECT_DIR%09-scripts\monitor.py" (
    echo [错误] 找不到 09-scripts/monitor.py
    echo        请在项目根目录运行此脚本
    pause
    exit /b 1
)

REM 创建定时任务：交易日早8:30 跑一次
schtasks /create /tn "adai-trading-monitor-morning" /sc weekly /d MON,TUE,WED,THU,FRI /st 08:30 /tr "python \"%PROJECT_DIR%09-scripts\monitor.py\"" /f

REM 创建定时任务：交易日午间12:00 跑一次
schtasks /create /tn "adai-trading-monitor-noon" /sc weekly /d MON,TUE,WED,THU,FRI /st 12:00 /tr "python \"%PROJECT_DIR%09-scripts\monitor.py\"" /f

echo.
echo ✅ 定时任务安装完成！
echo.
echo 已创建的任务：
echo   1. adai-trading-monitor-morning  — 交易日 08:30
echo   2. adai-trading-monitor-noon     — 交易日 12:00
echo.
echo 手动测试：python 09-scripts/monitor.py
echo.
pause
