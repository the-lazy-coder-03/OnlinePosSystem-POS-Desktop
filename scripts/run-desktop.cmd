@echo off
setlocal
cd /d "%~dp0.."
call mvnw.cmd -B -ntp -pl pos-desktop -am install -DskipTests
if errorlevel 1 exit /b %errorlevel%
call mvnw.cmd -pl pos-desktop javafx:run
exit /b %errorlevel%
