@echo off
set PATH=D:\Software\flutter\bin;D:\Software\flutter\bin\mingit\cmd;D:\Software\flutter\bin\mingit\mingw64\bin;D:\Software\flutter\bin\cache\dart-sdk\bin;C:\Windows\system32;C:\Windows;C:\WINDOWS\System32\WindowsPowerShell\v1.0;C:\Program Files\PowerShell\7
cd /d D:\Projects\adai-one
echo Building...
call flutter build web --release
echo Done: %errorlevel%
pause
