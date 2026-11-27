@echo off
cd /d "%~dp0.."
if not exist "target\petcare-platform-1.0.0.jar" (
  echo Jar not found. Run: mvn -DskipTests package
  pause
  exit /b 1
)
start "petcare-backend" cmd /c "java -jar target\petcare-platform-1.0.0.jar --spring.profiles.active=local"
echo Backend starting: http://localhost:8082/doc.html
