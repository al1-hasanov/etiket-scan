@echo off
setlocal
cd /d "%~dp0"
echo Starting Etiket API at http://127.0.0.1:8080
echo Requires JDK 25. Leave this window open while testing.
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar "dist\etiket-backend-0.2.0.jar"
) else (
  java -jar "dist\etiket-backend-0.2.0.jar"
)
if errorlevel 1 pause
