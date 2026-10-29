@echo off
setlocal
cd /d "%~dp0.."
if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" (
  "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" "%~dp0..\tools\dev-desktop.py" %*
  goto finished
)
where py >nul 2>&1
if not errorlevel 1 (
  py -3 "%~dp0..\tools\dev-desktop.py" %*
  goto finished
)
echo Python 3 is required. Install Python 3.13 for this Windows account and try again.
pause
exit /b 1
:finished
set "launch_result=%errorlevel%"
if not "%launch_result%"=="0" (
  echo OnlinePOS could not start. Review the error above.
  pause
)
exit /b %launch_result%
