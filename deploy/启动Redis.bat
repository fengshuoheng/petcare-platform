@echo off
powershell -NoProfile -Command "Start-Process -FilePath 'D:\software\Redis-x64-3.2.100\redis-server.exe' -ArgumentList 'D:\software\Redis-x64-3.2.100\redis.windows.conf' -WorkingDirectory 'D:\software\Redis-x64-3.2.100' -WindowStyle Hidden"
echo Redis started: 127.0.0.1:6379
