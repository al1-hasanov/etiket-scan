@echo off
setlocal
cd /d "%~dp0"
echo Starting Etiket API at http://127.0.0.1:8080
echo Requires JDK 25. Leave this window open while testing.
if not exist "backend\build\libs\etiket-backend-0.5.1.jar" (
  echo Build the backend first: gradlew.bat -PbackendOnly :backend:bootJar
  pause
  exit /b 1
)
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar "backend\build\libs\etiket-backend-0.5.1.jar"
) else (
  java -jar "backend\build\libs\etiket-backend-0.5.1.jar"
)
if errorlevel 1 pause
