@echo off
powershell -NoProfile -Command "Start-Process -FilePath '%~dp0..\ai-service\.venv\Scripts\python.exe' -ArgumentList '-m','uvicorn','app.main:app','--host','0.0.0.0','--port','8000' -WorkingDirectory '%~dp0..\ai-service' -WindowStyle Hidden"
echo AI service started: http://localhost:8000/health
