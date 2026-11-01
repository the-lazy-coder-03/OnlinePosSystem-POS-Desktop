@echo off
setlocal
cd /d "%~dp0.."
call mvnw.cmd -q -pl pos-desktop javafx:run -Ddesktop.mainClass=za.co.onlinepos.desktop.SalesPreview
exit /b %errorlevel%
