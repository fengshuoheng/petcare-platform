@echo off
powershell -NoProfile -Command "Start-Process -FilePath '%~dp0nginx\nginx.exe' -WorkingDirectory '%~dp0nginx' -WindowStyle Hidden"
echo Frontend started: http://localhost:8090
